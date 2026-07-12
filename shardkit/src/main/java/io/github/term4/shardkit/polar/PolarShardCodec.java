package io.github.term4.shardkit.polar;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.world.WorldAccess;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.command.builder.arguments.minecraft.ArgumentBlockState;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shard diff {@code <->} polar bytes: touched chunks only, palette sentinel {@code minecraft:structure_void} =
 * "no diff entry" (real air = a break). Sky, owned entities ({@link WorldAccess#ENTITY_SAVE} + generic tiers)
 * and {@link DataKey} user data ride the world user-data; blocks persist as version-stable string states.
 */
public final class PolarShardCodec {

    private static final String UNTOUCHED = "minecraft:structure_void";
    private static final int FORMAT = 2;

    private PolarShardCodec() {}

    /** Serializes the diff + sky overrides + owned entities (zstd-compressed polar world). */
    public static byte @NotNull [] save(@NotNull Shard shard) {
        return save(shard, null, true);
    }

    /** {@link #save(Shard)} plus {@link DataKey typed user data} for the load side to read back. */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserDataWriter> userData) {
        return save(shard, userData, true);
    }

    /** {@code entities} false = blocks/sky/user data only (a map template; loading spawns nothing). */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserDataWriter> userData, boolean entities) {
        return encode(shard.dimension(), shard.diff(), writeUserData(shard, userData, entities));
    }

    /**
     * {@link #save(Shard, Consumer, boolean)} with the encode (palette build + polar zstd, pure CPU) off-thread.
     * Diff, sky, entities and user data snapshot on the calling thread, so the bytes are consistent with the
     * moment of the call.
     */
    public static @NotNull CompletableFuture<byte[]> saveAsync(@NotNull Shard shard,
                                                               @Nullable Consumer<UserDataWriter> userData,
                                                               boolean entities) {
        DimensionType dim = shard.dimension();
        Map<Point, Block> diff = shard.diff();
        byte[] user = writeUserData(shard, userData, entities);
        return CompletableFuture.supplyAsync(() -> encode(dim, diff, user));
    }

    private static byte[] encode(DimensionType dim, Map<Point, Block> diff, byte[] userData) {
        byte minSection = (byte) (dim.minY() / 16);
        byte maxSection = (byte) (dim.maxY() / 16 - 1);
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
            // NBT/handler detail rides polar block entities; the palette carries the state string only
            if (block.hasNbt() || block.handler() != null) {
                BlockHandler handler = block.handler();
                blockEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(new PolarChunk.BlockEntity(
                        pos.blockX() & 15, pos.blockY(), pos.blockZ() & 15,
                        handler != null ? handler.getKey().asString() : null, block.nbt()));
            }
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
                List<String> palette = new ArrayList<>();
                palette.add(UNTOUCHED); // index 0 = the untouched default
                Map<String, Integer> paletteIndex = new HashMap<>();
                int[] data = new int[PolarSection.BLOCK_PALETTE_SIZE];
                for (int i = 0; i < blocks.length; i++) {
                    if (blocks[i] == null) continue;
                    String state = stateStrings.computeIfAbsent(blocks[i], Nbt::blockString);
                    Integer index = paletteIndex.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.add(state);
                        paletteIndex.put(state, index);
                    }
                    data[i] = index;
                }
                sections[si] = new PolarSection(palette.toArray(String[]::new), data,
                        new String[]{"minecraft:plains"}, null,
                        PolarSection.LightContent.MISSING, null, PolarSection.LightContent.MISSING, null);
            }
            chunks.add(new PolarChunk(key.x(), key.z(), sections, blockEntities.getOrDefault(key, List.of()),
                    new int[32][], new byte[0])); // 32 = polar's (package-private) MAX_HEIGHTMAPS
        });

        PolarWorld world = new PolarWorld(PolarWorld.LATEST_VERSION, MinecraftServer.DATA_VERSION,
                PolarWorld.CompressionType.ZSTD, minSection, maxSection, userData, chunks);
        return PolarWriter.write(world);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path) throws IOException {
        save(shard, path, null, true);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserDataWriter> userData) throws IOException {
        save(shard, path, userData, true);
    }

    /** Atomic file save: written beside {@code path}, then moved into place - a crash never leaves a torn file. */
    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserDataWriter> userData,
                            boolean entities) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, save(shard, userData, entities));
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Applies a saved diff + sky onto {@code shard} (one batched {@code setBlocks}), merging per position over
     * anything already there - load into a fresh shard for an exact restore. The view reads saved user data.
     * Nothing mutates until the whole file parses, so a corrupt file leaves the shard untouched.
     */
    public static @NotNull UserDataView load(@NotNull Shard shard, byte @NotNull [] data) {
        PolarWorld world = PolarReader.read(data);
        DimensionType dim = shard.dimension();
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
                            : parsed.computeIfAbsent(palette[i], PolarShardCodec::parseState);
                }
                int[] blockData = section.blockData(); // null = single-entry palette
                for (int i = 0; i < PolarSection.BLOCK_PALETTE_SIZE; i++) {
                    Block block = blocks[blockData == null ? 0 : blockData[i]];
                    if (block == null) continue;
                    int y = ((world.minSection() + si) << 4) + (i >> 8);
                    if (y < dim.minY() || y >= dim.maxY()) continue; // saved under a taller dimension
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
        shard.setBlocks(diff);
        return readUserData(shard, world.userData());
    }

    private static Block parseState(String state) {
        try {
            return ArgumentBlockState.staticParse(state);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown block state '" + state + "' (saved on a different Minecraft version?)", e);
        }
    }

    public static @NotNull UserDataView load(@NotNull Shard shard, @NotNull Path path) throws IOException {
        return load(shard, Files.readAllBytes(path));
    }

    /**
     * Re-emits {@code file} with extra {@link DataKey} entries merged into its user data (new keys win) -
     * attaches data computed after the save to an already-serialized world, without touching a shard.
     */
    public static byte @NotNull [] amendUserData(byte @NotNull [] file, @NotNull Consumer<UserDataWriter> userData) {
        PolarWorld world = PolarReader.read(file);
        CompoundBinaryTag root;
        try {
            root = world.userData().length == 0 ? CompoundBinaryTag.builder().putInt("v", FORMAT).build()
                    : BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(world.userData()));
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable user data - not a shard save?", e);
        }
        UserDataWriter writer = new UserDataWriter();
        userData.accept(writer);
        CompoundBinaryTag user = CompoundBinaryTag.builder().put(root.getCompound("user")).put(writer.tags.build()).build();
        CompoundBinaryTag amended = CompoundBinaryTag.builder().put(root).put("user", user).build();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BinaryTagIO.writer().write(amended, out);
            return PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(), world.compression(),
                    world.minSection(), world.maxSection(), out.toByteArray(), List.copyOf(world.chunks())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isUntouched(String paletteEntry) {
        return UNTOUCHED.equals(paletteEntry) || "structure_void".equals(paletteEntry);
    }

    /** Collects {@link DataKey} entries at save time (values Codec-encode to NBT immediately). */
    public static final class UserDataWriter {
        private final CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();

        UserDataWriter() {}

        public <T> @NotNull UserDataWriter put(@NotNull DataKey<T> key, @NotNull T value) {
            tags.put(key.id(), key.codec().encode(Transcoder.NBT, value).orElseThrow());
            return this;
        }
    }

    /** The saved user data of a loaded file; {@link #get} decodes on access ({@code null} when absent). */
    public static final class UserDataView {
        public static final UserDataView EMPTY = new UserDataView(CompoundBinaryTag.empty());

        private final CompoundBinaryTag tags;

        UserDataView(CompoundBinaryTag tags) { this.tags = tags; }

        public <T> @Nullable T get(@NotNull DataKey<T> key) {
            BinaryTag tag = tags.get(key.id());
            return tag == null ? null : key.codec().decode(Transcoder.NBT, tag).orElseThrow();
        }
    }

    private static byte[] writeUserData(Shard shard, @Nullable Consumer<UserDataWriter> userData, boolean entities) {
        CompoundBinaryTag.Builder root = CompoundBinaryTag.builder().putInt("v", FORMAT);
        CompoundBinaryTag.Builder sky = CompoundBinaryTag.builder();
        if (shard.timeOverride() >= 0) sky.putLong("time", shard.timeOverride());
        Weather weather = shard.weatherOverride();
        if (weather != null) {
            sky.putFloat("rain", weather.rainLevel());
            sky.putFloat("thunder", weather.thunderLevel());
        }
        root.put("sky", sky.build());
        if (entities) root.put("entities", EntityDescriptors.write(shard));
        if (userData != null) {
            UserDataWriter writer = new UserDataWriter();
            userData.accept(writer);
            root.put("user", writer.tags.build());
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BinaryTagIO.writer().write(root.build(), out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UserDataView readUserData(Shard shard, byte[] userData) {
        if (userData.length == 0) return UserDataView.EMPTY; // e.g. a plain polar world used as a diff
        CompoundBinaryTag root;
        try {
            // unlimitedReader: the default reader's 128KiB anti-DoS cap is for untrusted network NBT and
            // silently truncates big op logs (replays) - this data is a file the operator chose to load
            root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(userData));
        } catch (IOException e) {
            return UserDataView.EMPTY; // foreign user data: the blocks still loaded
        }
        if (root.getInt("v") == 0) return UserDataView.EMPTY;
        CompoundBinaryTag sky = root.getCompound("sky");
        if (sky.get("time") != null) shard.setTime(sky.getLong("time"));
        if (sky.get("rain") != null) shard.setWeather(new Weather(sky.getFloat("rain"), sky.getFloat("thunder")));
        EntityDescriptors.read(shard, root.getList("entities", BinaryTagTypes.COMPOUND));
        return new UserDataView(root.getCompound("user"));
    }
}
