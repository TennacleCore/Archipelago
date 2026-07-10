package io.github.term4.shardkit.polar;

import io.github.term4.shardkit.Shard;
import io.github.term4.worldaccess.WorldAccess;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.command.builder.arguments.minecraft.ArgumentBlockState;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.DimensionType;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shard diff {@code <->} Polar bytes. Touched chunks only; the palette sentinel {@code minecraft:structure_void}
 * means "no diff entry" (real air = a break, vanilla's keep-existing convention). Sky overrides, shard-owned
 * ENTITIES ({@link WorldAccess#ENTITY_SAVE} stamps + generic tiers, revived on load per {@link #reviveEntity})
 * and any {@link DataKey typed user data} ride the world user-data as NBT. Blocks persist as string states, so
 * files survive Minecraft version bumps.
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
        DimensionType dim = shard.dimension();
        byte minSection = (byte) (dim.minY() / 16);
        byte maxSection = (byte) (dim.maxY() / 16 - 1);
        int sectionCount = maxSection - minSection + 1;

        record ChunkKey(int x, int z) {}
        Map<ChunkKey, Block[][]> touched = new HashMap<>();
        Map<ChunkKey, List<PolarChunk.BlockEntity>> blockEntities = new HashMap<>();
        shard.forEachDiff((pos, block) -> {
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
                    String state = stateStrings.computeIfAbsent(blocks[i], PolarShardCodec::blockToString);
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
                PolarWorld.CompressionType.ZSTD, minSection, maxSection, writeUserData(shard, userData, entities), chunks);
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

    private static boolean isUntouched(String paletteEntry) {
        return UNTOUCHED.equals(paletteEntry) || "structure_void".equals(paletteEntry);
    }

    // polar's palette entry format: minecraft:block[key=value,key=value]
    private static String blockToString(Block block) {
        if (block.properties().isEmpty()) return block.name();
        StringBuilder out = new StringBuilder(block.name()).append('[');
        for (var entry : block.properties().entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        out.setCharAt(out.length() - 1, ']');
        return out.toString();
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
        if (entities) root.put("entities", writeEntities(shard));
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
            root = BinaryTagIO.reader().read(new ByteArrayInputStream(userData));
        } catch (IOException e) {
            return UserDataView.EMPTY; // foreign user data: the blocks still loaded
        }
        if (root.getInt("v") == 0) return UserDataView.EMPTY;
        CompoundBinaryTag sky = root.getCompound("sky");
        if (sky.get("time") != null) shard.setTime(sky.getLong("time"));
        if (sky.get("rain") != null) shard.setWeather(new Weather(sky.getFloat("rain"), sky.getFloat("thunder")));
        readEntities(shard, root.getList("entities", BinaryTagTypes.COMPOUND));
        return new UserDataView(root.getCompound("user"));
    }

    // --- entities: {id, pos, data} descriptors; ENTITY_SAVE stamps first, generic tiers for the plain classes ---

    private static final Map<String, Function<CompoundBinaryTag, Entity>> REVIVERS = new ConcurrentHashMap<>();

    static {
        REVIVERS.put("shardkit:entity", data -> readData(data, new Entity(EntityType.fromKey(data.getString("type")))));
        REVIVERS.put("shardkit:living", data -> {
            LivingEntity living = new LivingEntity(EntityType.fromKey(data.getString("type")));
            readData(data, living);
            living.setHealth(data.getFloat("health"));
            CompoundBinaryTag equipment = data.getCompound("equipment");
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                BinaryTag item = equipment.get(slot.name());
                if (item != null) living.setEquipment(slot, decodeItem(item));
            }
            return living;
        });
        REVIVERS.put("shardkit:item", data -> {
            ItemEntity item = new ItemEntity(decodeItem(data.get("item")));
            item.setPickupDelay(Duration.ofMillis(data.getLong("delayMs")));
            return readData(data, item);
        });
        REVIVERS.put("shardkit:xp_orb", data -> readData(data, new ExperienceOrb(data.getShort("xp"))));
    }

    /** Registers the load-side factory for {@code id} descriptors ({@link WorldAccess#ENTITY_SAVE} stamps). */
    public static void reviveEntity(@NotNull String id, @NotNull Function<CompoundBinaryTag, Entity> reviver) {
        REVIVERS.put(id, reviver);
    }

    private static final List<Function<Entity, CompoundBinaryTag>> DESCRIBERS = new CopyOnWriteArrayList<>();

    /**
     * Registers a save-side descriptor source, the twin of {@link #reviveEntity} - how an app maps another
     * library's {@code ENTITY_SAVE} mirror (a structural twin of {@link WorldAccess#ENTITY_SAVE} under its own
     * tag name) into saves. First non-null wins, after the native stamp and before the generic tiers.
     */
    public static void describeEntity(@NotNull Function<Entity, @Nullable CompoundBinaryTag> describer) {
        DESCRIBERS.add(describer);
    }

    /** Descriptors whose id had no reviver at load, re-emitted on save so this app never DROPS a foreign app's entities. */
    private static final Tag<List<CompoundBinaryTag>> UNREVIVED = Tag.Transient("shardkit-polar:unrevived");

    private static ListBinaryTag writeEntities(Shard shard) {
        ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (Entity entity : shard.entities()) {
            if (entity instanceof Player || entity.isRemoved()) continue;
            CompoundBinaryTag desc = describe(entity);
            if (desc == null) continue; // no stamp, no generic tier: not persistable (like the fork cloner's skip)
            Pos pos = entity.getPosition();
            out.add(CompoundBinaryTag.builder().put(desc)
                    .put("pos", vec3(pos.x(), pos.y(), pos.z())).build());
        }
        List<CompoundBinaryTag> unrevived = shard.getTag(UNREVIVED);
        if (unrevived != null) unrevived.forEach(out::add);
        return out.build();
    }

    private static @Nullable CompoundBinaryTag describe(Entity entity) {
        Supplier<CompoundBinaryTag> stamped = entity.getTag(WorldAccess.ENTITY_SAVE);
        if (stamped != null) return stamped.get();
        for (Function<Entity, CompoundBinaryTag> describer : DESCRIBERS) {
            CompoundBinaryTag desc = describer.apply(entity);
            if (desc != null) return desc;
        }
        Class<?> type = entity.getClass();
        if (type == Entity.class) return generic("shardkit:entity", entity).build();
        if (type == LivingEntity.class) {
            LivingEntity living = (LivingEntity) entity;
            CompoundBinaryTag.Builder equipment = CompoundBinaryTag.builder();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack item = living.getEquipment(slot);
                if (!item.isAir()) equipment.put(slot.name(), encodeItem(item));
            }
            return generic("shardkit:living", entity)
                    .putFloat("health", living.getHealth()).put("equipment", equipment.build()).build();
        }
        if (type == ItemEntity.class) {
            ItemEntity item = (ItemEntity) entity;
            return generic("shardkit:item", entity)
                    .put("item", encodeItem(item.getItemStack())).putLong("delayMs", item.getPickupDelay()).build();
        }
        if (type == ExperienceOrb.class) {
            return generic("shardkit:xp_orb", entity).putShort("xp", ((ExperienceOrb) entity).getExperienceCount()).build();
        }
        return null;
    }

    private static CompoundBinaryTag.Builder generic(String id, Entity entity) {
        Vec vel = entity.getVelocity();
        return CompoundBinaryTag.builder().putString("id", id).putString("type", entity.getEntityType().key().asString())
                .put("vel", vec3(vel.x(), vel.y(), vel.z()))
                .put("tags", entity.tagHandler().asCompound()); // serializable tags only, like the fork cloner
    }

    private static Entity readData(CompoundBinaryTag data, Entity entity) {
        ListBinaryTag vel = data.getList("vel", BinaryTagTypes.DOUBLE);
        if (vel.size() == 3) entity.setVelocity(new Vec(vel.getDouble(0), vel.getDouble(1), vel.getDouble(2)));
        entity.tagHandler().updateContent(data.getCompound("tags"));
        return entity;
    }

    private static void readEntities(Shard shard, ListBinaryTag entities) {
        List<CompoundBinaryTag> unrevived = new ArrayList<>();
        for (BinaryTag tag : entities) {
            CompoundBinaryTag desc = (CompoundBinaryTag) tag;
            Function<CompoundBinaryTag, Entity> reviver = REVIVERS.get(desc.getString("id"));
            Entity entity = reviver != null ? reviver.apply(desc) : null;
            if (entity == null) {
                unrevived.add(desc); // no reviver here: everything else loads, and a resave keeps the descriptor
                continue;
            }
            ListBinaryTag pos = desc.getList("pos", BinaryTagTypes.DOUBLE);
            if (pos.size() == 3) shard.spawn(entity, new Pos(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
        }
        if (unrevived.isEmpty()) shard.removeTag(UNREVIVED);
        else shard.setTag(UNREVIVED, List.copyOf(unrevived));
    }

    // NBT has no double-array type; 3-vectors ride double lists (the vanilla Pos/Motion convention)
    private static ListBinaryTag vec3(double x, double y, double z) {
        return ListBinaryTag.builder(BinaryTagTypes.DOUBLE)
                .add(DoubleBinaryTag.doubleBinaryTag(x))
                .add(DoubleBinaryTag.doubleBinaryTag(y))
                .add(DoubleBinaryTag.doubleBinaryTag(z)).build();
    }

    private static BinaryTag encodeItem(ItemStack item) {
        return ItemStack.CODEC.encode(Transcoder.NBT, item).orElseThrow();
    }

    private static ItemStack decodeItem(BinaryTag tag) {
        return ItemStack.CODEC.decode(Transcoder.NBT, tag).orElseThrow();
    }
}
