package io.github.term4.archipelago.storage.polar;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import io.github.term4.polyp.world.MechanicsWorld;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.world.DimensionType;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * The polar EXCHANGE ADAPTER: {@code ShardFile} ({@code .shard}) is the lib format - this codec exists to talk
 * to polar tooling (map editors, polar-based pipelines), and pulls in the polar dependency only here. Live
 * shard {@code <->} polar bytes via {@link #save}/{@link #load}; pure byte transcode via
 * {@link #fromShardFile}/{@link #toShardFile}. Touched chunks only, palette sentinel
 * {@code minecraft:structure_void} = "no diff entry" (real air = a break). Sky, owned entities
 * ({@link MechanicsWorld#ENTITY_SAVE} + generic tiers) and {@link DataKey} user data ride the world user-data;
 * blocks persist as version-stable string states.
 */
public final class PolarShardCodec {

    private static final String UNTOUCHED = "minecraft:structure_void";
    private static final int FORMAT = 2;
    /** polar's own MAX_HEIGHTMAPS (package-private there). */
    private static final int MAX_HEIGHTMAPS = 32;

    private PolarShardCodec() {}

    /** Serializes the diff + sky overrides + owned entities (zstd-compressed polar world). */
    public static byte @NotNull [] save(@NotNull Shard shard) {
        return save(shard, null, true);
    }

    /** {@link #save(Shard)} plus {@link DataKey typed user data} for the load side to read back. */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserData.Writer> userData) {
        return save(shard, userData, true);
    }

    /** {@code entities} false = blocks/sky/user data only (a map template; loading spawns nothing). */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserData.Writer> userData, boolean entities) {
        DimensionType dim = shard.dimension();
        Capture c = capture(shard, userData, entities);
        return encode(dim.minY(), dim.maxY(), c.diff(), c.user());
    }

    /**
     * {@link #save(Shard, Consumer, boolean)} with the encode (palette build + polar zstd, pure CPU) off-thread.
     * Diff, sky, entities and user data snapshot together on the shard's clock, so the bytes are one instant.
     */
    public static @NotNull CompletableFuture<byte[]> saveAsync(@NotNull Shard shard,
                                                               @Nullable Consumer<UserData.Writer> userData,
                                                               boolean entities) {
        return saveAsync(shard, userData, entities, ForkJoinPool.commonPool());
    }

    /** {@link #saveAsync(Shard, Consumer, boolean)} encoding on {@code executor} - a save-heavy server bounds its own I/O pool instead of loading the common pool. */
    public static @NotNull CompletableFuture<byte[]> saveAsync(@NotNull Shard shard,
                                                               @Nullable Consumer<UserData.Writer> userData,
                                                               boolean entities, @NotNull Executor executor) {
        DimensionType dim = shard.dimension();
        Capture c = capture(shard, userData, entities);
        return CompletableFuture.supplyAsync(() -> encode(dim.minY(), dim.maxY(), c.diff(), c.user()), executor);
    }

    private record Capture(Map<Point, Block> diff, CompoundBinaryTag user) {}

    // one relay: separate diff/entity reads could straddle a tick and save an incoherent instant.
    // The snapshot stays NBT - canonicalize + serialize are pure CPU and run off the clock, in encode
    private static Capture capture(Shard shard, @Nullable Consumer<UserData.Writer> userData, boolean entities) {
        return shard.relay(() -> new Capture(shard.diff(), userDataTag(shard, userData, entities)));
    }

    private static byte[] encode(int minY, int maxY, Map<Point, Block> diff, CompoundBinaryTag userData) {
        byte minSection = (byte) (minY / 16);
        byte maxSection = (byte) (maxY / 16 - 1);
        int sectionCount = maxSection - minSection + 1;

        record ChunkKey(int x, int z) {}
        Map<ChunkKey, Block[][]> touched = new HashMap<>();
        Map<ChunkKey, List<PolarChunk.BlockEntity>> blockEntities = new HashMap<>();
        diff.forEach((pos, block) -> {
            int si = (pos.blockY() >> 4) - minSection;
            if (si < 0 || si >= sectionCount) return; // outside the dimension
            ChunkKey key = new ChunkKey(pos.blockX() >> 4, pos.blockZ() >> 4);
            Block[][] sections = touched.computeIfAbsent(key, k -> new Block[sectionCount][]);
            Block[] section = sections[si];
            if (section == null) section = sections[si] = new Block[PolarSection.BLOCK_PALETTE_SIZE];
            section[(pos.blockX() & 15) + (pos.blockY() & 15) * 256 + (pos.blockZ() & 15) * 16] = block;
            PolarChunk.BlockEntity be = blockEntity(pos, block);
            if (be != null) blockEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(be);
        });

        Map<Block, String> stateStrings = new HashMap<>(); // one string build per unique state, not per block
        List<PolarChunk> chunks = new ArrayList<>(touched.size());
        touched.forEach((key, blockSections) -> {
            PolarSection[] sections = new PolarSection[sectionCount];
            for (int si = 0; si < sectionCount; si++) {
                Block[] blocks = blockSections[si];
                if (blocks == null) {
                    sections[si] = new PolarSection();
                    continue;
                }
                Nbt.Palette palette = new Nbt.Palette();
                palette.reserve(UNTOUCHED); // index 0 = the untouched default
                int[] data = new int[PolarSection.BLOCK_PALETTE_SIZE];
                for (int i = 0; i < blocks.length; i++) {
                    if (blocks[i] == null) continue;
                    data[i] = palette.id(stateStrings.computeIfAbsent(blocks[i], Nbt::blockString));
                }
                sections[si] = new PolarSection(palette.entries().toArray(String[]::new), data,
                        new String[]{"minecraft:plains"}, null,
                        PolarSection.LightContent.MISSING, null, PolarSection.LightContent.MISSING, null);
            }
            chunks.add(new PolarChunk(key.x(), key.z(), sections, blockEntities.getOrDefault(key, List.of()),
                    new int[MAX_HEIGHTMAPS][], new byte[0]));
        });

        PolarWorld world = new PolarWorld(PolarWorld.LATEST_VERSION, MinecraftServer.DATA_VERSION,
                PolarWorld.CompressionType.ZSTD, minSection, maxSection, bytes(userData), chunks);
        return PolarWriter.write(world);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path) throws IOException {
        save(shard, path, null, true);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserData.Writer> userData) throws IOException {
        save(shard, path, userData, true);
    }

    /** Atomic file save: written beside {@code path}, then moved into place - a crash never leaves a torn file. */
    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserData.Writer> userData,
                            boolean entities) throws IOException {
        ShardFile.moveIntoPlace(path, save(shard, userData, entities));
    }

    /**
     * Applies a saved diff + sky onto {@code shard} (one batched {@code setBlocks}), merging per position over
     * anything already there - load into a fresh shard for an exact restore. The view reads saved user data.
     * Nothing mutates until the whole file parses, so a corrupt file leaves the shard untouched.
     */
    public static @NotNull UserData.View load(@NotNull Shard shard, byte @NotNull [] data) {
        PolarWorld world = PolarReader.read(data);
        DimensionType dim = shard.dimension();
        Map<Point, Block> diff = readChunks(world, dim.minY(), dim.maxY()); // clamp: saved under a taller dimension
        shard.setBlocks(diff);
        return readUserData(shard, world.userData());
    }

    private static Map<Point, Block> readChunks(PolarWorld world, int clampMinY, int clampMaxY) {
        Map<String, Block> parsed = new HashMap<>(); // one parse per unique state string
        Map<Point, Block> diff = new HashMap<>();
        for (PolarChunk chunk : world.chunks()) {
            PolarSection[] sections = chunk.sections();
            for (int si = 0; si < sections.length; si++) {
                PolarSection section = sections[si];
                if (section.isEmpty()) continue;
                String[] palette = section.blockPalette();
                Block[] blocks = new Block[palette.length];
                for (int i = 0; i < palette.length; i++) {
                    blocks[i] = isUntouched(palette[i]) ? null
                            : parsed.computeIfAbsent(palette[i], Nbt::parseState);
                }
                int[] blockData = palette.length > 1 ? section.blockData() : null; // accessor asserts on single palettes
                for (int i = 0; i < PolarSection.BLOCK_PALETTE_SIZE; i++) {
                    int id = blockData == null ? 0 : blockData[i];
                    if (id < 0 || id >= blocks.length) throw new IllegalArgumentException("corrupt polar section: palette index " + id);
                    Block block = blocks[id];
                    if (block == null) continue;
                    int y = ((world.minSection() + si) << 4) + (i >> 8);
                    if (y < clampMinY || y >= clampMaxY) continue;
                    diff.put(new Vec((chunk.x() << 4) + (i & 15), y, (chunk.z() << 4) + ((i >> 4) & 15)), block);
                }
            }
            for (PolarChunk.BlockEntity be : chunk.blockEntities()) {
                Point at = new Vec((chunk.x() << 4) + be.x(), be.y(), (chunk.z() << 4) + be.z());
                Block block = diff.get(at);
                if (block == null) continue; // foreign polar file: a block entity on an untouched cell isn't diff
                if (be.id() != null) block = block.withHandler(MinecraftServer.getBlockManager().getHandlerOrDummy(be.id()));
                if (be.data() != null) block = block.withNbt(be.data());
                diff.put(at, block);
            }
        }
        return diff;
    }

    public static @NotNull UserData.View load(@NotNull Shard shard, @NotNull Path path) throws IOException {
        return load(shard, Files.readAllBytes(path));
    }

    // exchange tunnel keys: what one format can't express natively rides the other's user compound
    private static final String TUNNELED_ENTITIES = "polar:entities";
    private static final String TUNNELED_BEFORE = "archipelago:before";
    private static final String TUNNELED_TREE = "archipelago:tree";

    /**
     * {@code .shard} bytes {@code ->} polar bytes, no live shard: the diff becomes touched chunks (a tree
     * file's ROOT diff - polar tools see it as the world), block NBT/handler detail becomes polar block
     * entities, sky maps to the polar container's sky slot, the user compound tunnels VERBATIM (op
     * logs/replays survive), and a {@code before} column / {@code tree} section tunnel under
     * {@code "archipelago:before"}/{@code "archipelago:tree"}. {@link #toShardFile} inverts losslessly.
     */
    public static byte @NotNull [] fromShardFile(byte @NotNull [] shard) {
        ShardFile.Content c = ShardFile.decode(shard);
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder().putInt("v", FORMAT);
        root.put("sky", Nbt.sky(c.time(), c.weather()));
        CompoundBinaryTag user = c.user();
        BinaryTag entities = user.get(TUNNELED_ENTITIES);
        if (entities != null) {
            root.put("entities", entities);
            user = Nbt.without(user, TUNNELED_ENTITIES);
        }
        if (c.before() != null) user = with(user, TUNNELED_BEFORE, beforeTunnel(c.before()));
        if (c.tree() != null) user = with(user, TUNNELED_TREE, c.tree());
        if (!user.keySet().isEmpty()) root.put("user", user);
        return encode(c.minY(), c.maxY(), c.diff(), root.build());
    }

    /**
     * Polar bytes {@code ->} {@code .shard}, the inverse: polar-native extras a {@code .shard} can't express
     * (the entity list) tunnel into its user compound under {@code "polar:entities"} so a later
     * {@link #fromShardFile} restores them; a tunneled before column becomes the real column again. Works on
     * any polar world - a foreign map's every block becomes a diff entry.
     */
    public static byte @NotNull [] toShardFile(byte @NotNull [] polar) {
        PolarWorld world = PolarReader.read(polar);
        int minY = world.minSection() << 4, maxY = (world.maxSection() + 1) << 4;
        Map<Point, Block> diff = readChunks(world, minY, maxY);
        CompoundBinaryTag container = container(world.userData());
        CompoundBinaryTag sky = container.getCompound("sky");
        long time = Nbt.skyTime(sky);
        Weather weather = Nbt.skyWeather(sky);
        CompoundBinaryTag user = container.getCompound("user");
        ListBinaryTag entities = container.getList("entities", BinaryTagTypes.COMPOUND);
        if (entities.size() > 0) user = with(user, TUNNELED_ENTITIES, entities);
        Map<Point, Block> before = null;
        if (user.get(TUNNELED_BEFORE) instanceof CompoundBinaryTag cols) {
            before = beforeFromTunnel(cols);
            user = Nbt.without(user, TUNNELED_BEFORE);
        }
        ListBinaryTag tree = null;
        if (user.get(TUNNELED_TREE) instanceof ListBinaryTag tunneled) {
            tree = tunneled;
            user = Nbt.without(user, TUNNELED_TREE);
        }
        return new ShardFile.Content(minY, maxY, diff, before, time, weather, user, tree).encode();
    }

    /**
     * Applies a {@code .shard} diff onto a FULL polar world, pure bytes - the offline twin of committing a
     * shard into its base instance. Blocks (NBT/handler included, explicit air included) land in the chunks'
     * sections; touched sections drop their light (loaders recompute); biomes, heightmaps, untouched
     * sections/chunks and all user data pass through. Diff positions outside the world's bounds are skipped;
     * chunks the world lacks are created. The shard file's sky/user/tree sections are IGNORED - this writes
     * blocks (flatten a chain first to commit a whole tree).
     */
    public static byte @NotNull [] applyToWorld(byte @NotNull [] polarWorld, byte @NotNull [] shard) {
        PolarWorld world = PolarReader.read(polarWorld);
        ShardFile.Content c = ShardFile.decode(shard);
        int minY = world.minSection() << 4, maxY = (world.maxSection() + 1) << 4;
        int sectionCount = world.maxSection() - world.minSection() + 1;

        Map<Long, Map<Point, Block>> byChunk = new HashMap<>();
        c.diff().forEach((pos, block) -> {
            if (pos.blockY() < minY || pos.blockY() >= maxY) return;
            byChunk.computeIfAbsent(CoordConversion.chunkIndex(pos.blockX() >> 4, pos.blockZ() >> 4),
                    k -> new HashMap<>()).put(pos, block);
        });

        List<PolarChunk> chunks = new ArrayList<>(world.chunks().size());
        for (PolarChunk chunk : world.chunks()) {
            Map<Point, Block> writes = byChunk.remove(CoordConversion.chunkIndex(chunk.x(), chunk.z()));
            chunks.add(writes == null ? chunk : overlaid(chunk, writes, world.minSection(), sectionCount));
        }
        // chunks the world lacks, in deterministic order
        List<Long> missing = new ArrayList<>(byChunk.keySet());
        missing.sort(Comparator.naturalOrder());
        for (long key : missing) {
            PolarSection[] sections = new PolarSection[sectionCount];
            for (int i = 0; i < sectionCount; i++) sections[i] = new PolarSection();
            PolarChunk bare = new PolarChunk(CoordConversion.chunkIndexGetX(key), CoordConversion.chunkIndexGetZ(key),
                    sections, List.of(), new int[MAX_HEIGHTMAPS][], new byte[0]);
            chunks.add(overlaid(bare, byChunk.get(key), world.minSection(), sectionCount));
        }
        return PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(), world.compression(),
                world.minSection(), world.maxSection(), world.userData(), chunks));
    }

    private static PolarChunk overlaid(PolarChunk chunk, Map<Point, Block> writes, int minSection, int sectionCount) {
        PolarSection[] sections = chunk.sections().clone();
        List<PolarChunk.BlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities());
        Map<Integer, Map<Integer, Block>> bySection = new HashMap<>();
        writes.forEach((pos, block) -> {
            bySection.computeIfAbsent((pos.blockY() >> 4) - minSection, k -> new HashMap<>())
                    .put((pos.blockX() & 15) + (pos.blockY() & 15) * 256 + (pos.blockZ() & 15) * 16, block);
            // overlaid positions drop their old block entity; NBT/handler blocks bring a fresh one
            blockEntities.removeIf(be -> be.x() == (pos.blockX() & 15) && be.y() == pos.blockY() && be.z() == (pos.blockZ() & 15));
            PolarChunk.BlockEntity fresh = blockEntity(pos, block);
            if (fresh != null) blockEntities.add(fresh);
        });
        bySection.forEach((si, blocks) -> {
            PolarSection section = sections[si];
            String[] states = new String[PolarSection.BLOCK_PALETTE_SIZE];
            if (!section.isEmpty()) {
                String[] palette = section.blockPalette();
                int[] data = palette.length > 1 ? section.blockData() : null; // accessor asserts on single palettes
                for (int i = 0; i < states.length; i++) states[i] = palette[data == null ? 0 : data[i]];
            } else {
                Arrays.fill(states, "minecraft:air");
            }
            blocks.forEach((local, block) -> states[local] = Nbt.blockString(block));
            Nbt.Palette palette = new Nbt.Palette();
            int[] data = new int[states.length];
            for (int i = 0; i < states.length; i++) data[i] = palette.id(states[i]);
            // touched sections drop light; biomes ride along untouched (biomeData asserts on single palettes)
            String[] biomePalette = section.isEmpty() ? new String[]{"minecraft:plains"} : section.biomePalette();
            int[] biomeData = biomePalette.length > 1 ? section.biomeData() : null;
            sections[si] = new PolarSection(palette.entries().toArray(String[]::new), data, biomePalette, biomeData,
                    PolarSection.LightContent.MISSING, null, PolarSection.LightContent.MISSING, null);
        });
        int[][] heightmaps = new int[MAX_HEIGHTMAPS][];
        for (int i = 0; i < heightmaps.length; i++) heightmaps[i] = chunk.heightmap(i);
        return new PolarChunk(chunk.x(), chunk.z(), sections, blockEntities, heightmaps, chunk.userData());
    }

    /**
     * End-to-end lossless check for {@code file} (either format, sniffed by magic): converts across and
     * back, then byte-compares. {@code .shard} files compare directly; polar files compare re-serialized
     * under one canonical writer, and everything a polar world may carry COUNTS - a painted map's
     * biomes/light/heightmaps don't survive the diff model, so it honestly reports lossy; shard saves and
     * replays report lossless.
     */
    public static boolean transcodesLosslessly(byte @NotNull [] file) {
        if (file.length > 1 && (file[0] & 0xFF) == 0x1F && (file[1] & 0xFF) == 0x8B) { // gzip = .shard
            return Arrays.equals(file, toShardFile(fromShardFile(file)));
        }
        byte[] roundTripped = fromShardFile(toShardFile(file));
        return Arrays.equals(normalized(PolarReader.read(file)), normalized(PolarReader.read(roundTripped)));
    }

    // one canonical re-serialization so framing (chunk order, salted container maps) can't mask or fake a loss
    private static byte[] normalized(PolarWorld world) {
        List<PolarChunk> chunks = new ArrayList<>(world.chunks());
        chunks.sort(Comparator.comparingInt(PolarChunk::x).thenComparingInt(PolarChunk::z));
        byte[] userData = world.userData();
        CompoundBinaryTag parsed = container(userData);
        if (!parsed.keySet().isEmpty()) {
            // empty sections are framing, not data - the round trip legitimately drops them
            if (parsed.get("entities") instanceof ListBinaryTag l && l.size() == 0) parsed = Nbt.without(parsed, "entities");
            if (parsed.get("user") instanceof CompoundBinaryTag u && u.keySet().isEmpty()) parsed = Nbt.without(parsed, "user");
            userData = bytes(parsed); // bytes() canonicalizes; unparseable foreign data stays raw = honestly lossy
        } else if (userData.length == 0) {
            // no user data at all gains the empty container on the way back - framing, not a loss
            userData = bytes(CompoundBinaryTag.builder().putInt("v", FORMAT).put("sky", CompoundBinaryTag.empty()).build());
        }
        return PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(), world.compression(),
                world.minSection(), world.maxSection(), userData, chunks));
    }

    private static CompoundBinaryTag beforeTunnel(Map<Point, Block> before) {
        List<Map.Entry<Point, Block>> entries = new ArrayList<>(before.entrySet());
        entries.sort(Map.Entry.comparingByKey(Nbt.POSITION_ORDER));
        int[] xs = new int[entries.size()], ys = new int[entries.size()], zs = new int[entries.size()];
        ListBinaryTag.Builder<StringBinaryTag> states = ListBinaryTag.builder(BinaryTagTypes.STRING);
        int i = 0;
        for (Map.Entry<Point, Block> entry : entries) {
            xs[i] = entry.getKey().blockX();
            ys[i] = entry.getKey().blockY();
            zs[i] = entry.getKey().blockZ();
            states.add(StringBinaryTag.stringBinaryTag(Nbt.blockString(entry.getValue())));
            i++;
        }
        return CompoundBinaryTag.builder().putIntArray("x", xs).putIntArray("y", ys).putIntArray("z", zs)
                .put("state", states.build()).build();
    }

    private static Map<Point, Block> beforeFromTunnel(CompoundBinaryTag cols) {
        int[] xs = cols.getIntArray("x"), ys = cols.getIntArray("y"), zs = cols.getIntArray("z");
        ListBinaryTag states = cols.getList("state", BinaryTagTypes.STRING);
        Map<Point, Block> out = new HashMap<>();
        for (int i = 0; i < xs.length; i++) out.put(new Vec(xs[i], ys[i], zs[i]), Nbt.parseState(states.getString(i)));
        return out;
    }

    // NBT/handler detail rides polar block entities; the palette carries the state string only
    private static PolarChunk.@Nullable BlockEntity blockEntity(Point pos, Block block) {
        BlockHandler handler = block.handler();
        if (!block.hasNbt() && handler == null) return null;
        return new PolarChunk.BlockEntity(pos.blockX() & 15, pos.blockY(), pos.blockZ() & 15,
                handler != null ? handler.getKey().asString() : null, block.nbt());
    }

    private static CompoundBinaryTag with(CompoundBinaryTag tag, String key, BinaryTag value) {
        return CompoundBinaryTag.builder().put(tag).put(key, value).build();
    }

    private static boolean isUntouched(String paletteEntry) {
        return UNTOUCHED.equals(paletteEntry) || "structure_void".equals(paletteEntry);
    }

    private static CompoundBinaryTag userDataTag(Shard shard, @Nullable Consumer<UserData.Writer> userData,
                                                 boolean entities) {
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder().putInt("v", FORMAT);
        root.put("sky", Nbt.sky(shard.timeOverride(), shard.weatherOverride()));
        if (entities) root.put("entities", EntityDescriptors.write(shard));
        if (userData != null) {
            UserData.Writer writer = new UserData.Writer();
            userData.accept(writer);
            root.put("user", writer.build());
        }
        return root.build();
    }

    private static byte[] bytes(CompoundBinaryTag tag) {
        return Nbt.writeBytes(tag, BinaryTagIO.Compression.NONE);
    }

    /** The parsed shard-save container, or empty for foreign/plain polar files (blocks still load). */
    private static CompoundBinaryTag container(byte[] userData) {
        if (userData.length == 0) return CompoundBinaryTag.empty();
        CompoundBinaryTag root;
        try {
            // unlimitedReader: the default reader's 128KiB anti-DoS cap is for untrusted network NBT and
            // silently truncates big op logs - this data is a file the operator chose to load
            root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(userData));
        } catch (IOException e) {
            return CompoundBinaryTag.empty();
        }
        return root.getInt("v") == 0 ? CompoundBinaryTag.empty() : root;
    }

    private static UserData.View readUserData(Shard shard, byte[] userData) {
        CompoundBinaryTag root = container(userData);
        if (root.keySet().isEmpty()) return UserData.View.EMPTY;
        CompoundBinaryTag sky = root.getCompound("sky");
        long time = Nbt.skyTime(sky);
        if (time >= 0) shard.setTime(time);
        Weather weather = Nbt.skyWeather(sky);
        if (weather != null) shard.setWeather(weather);
        EntityDescriptors.read(shard, root.getList("entities", BinaryTagTypes.COMPOUND));
        return new UserData.View(root.getCompound("user"));
    }
}
