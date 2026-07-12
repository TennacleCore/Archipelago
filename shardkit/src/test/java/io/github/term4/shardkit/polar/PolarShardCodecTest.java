package io.github.term4.shardkit.polar;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.world.WorldAccess;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diff + sky round-trip through polar bytes: string states, explicit air vs untouched, cross-chunk/section spread. */
class PolarShardCodecTest {

    private static InstanceContainer instance;

    @BeforeAll
    static void boot() {
        if (instance != null) return;
        if (MinecraftServer.process() == null) { // another test class in this JVM may have booted already
            MinecraftServer.init();
            MinecraftServer.process().dispatcher().start();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
    }

    @Test
    void roundTripsDiffAndSky() {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(5, 70, 5), Block.STONE);
        original.setBlock(new Vec(5, 71, 5), Block.LADDER.withProperty("facing", "east"));
        original.setBlock(new Vec(4, 63, 4), Block.AIR);       // a broken base block
        original.setBlock(new Vec(37, 90, -21), Block.OAK_PLANKS); // different chunk + section
        original.setTime(18000);
        original.setWeather(new Weather(1f, 0.5f));

        byte[] bytes = PolarShardCodec.save(original);

        Shard restored = new Shard(instance);
        PolarShardCodec.load(restored, bytes);

        assertEquals(original.diff(), restored.diff(), "every entry round-trips, properties included");
        assertTrue(restored.getBlock(4, 63, 4).isAir(), "explicit air survives");
        assertNull(restored.diff().get(new Vec(4, 64, 4)), "untouched positions stay absent from the diff");
        assertEquals(4, restored.overlaySize(), "no phantom entries from the untouched sentinel");
        assertEquals(18000, restored.timeOverride(), "fixed time restores as an override");
        assertEquals(new Weather(1f, 0.5f), restored.weatherOverride());
    }

    @Test
    void saveAsyncSnapshotsOnTheCallerAndRoundTrips() {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(8, 70, 8), Block.STONE);
        original.setBlock(new Vec(9, 70, 8), Block.LADDER.withProperty("facing", "east"));
        original.setTime(6000);

        var future = PolarShardCodec.saveAsync(original, null, true);
        original.setBlock(new Vec(10, 70, 8), Block.DIRT); // after the call: must not be in the snapshot

        Shard restored = new Shard(instance);
        PolarShardCodec.load(restored, future.join());
        assertEquals(2, restored.overlaySize(), "the snapshot is the diff at call time");
        assertEquals(Block.STONE, restored.getBlock(8, 70, 8));
        assertNull(restored.diff().get(new Vec(10, 70, 8)));
        assertEquals(6000, restored.timeOverride());
    }

    private record GameMeta(String mode, int round) {}
    private static final DataKey<GameMeta> META = new DataKey<>("test:meta",
            StructCodec.struct(
                    "mode", Codec.STRING, GameMeta::mode,
                    "round", Codec.INT, GameMeta::round,
                    GameMeta::new));
    private static final DataKey<Long> UNSET = new DataKey<>("test:unset", Codec.LONG);

    @Test
    void typedUserDataRoundTrips() {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(20, 70, 20), Block.STONE);

        byte[] bytes = PolarShardCodec.save(original, data -> data.put(META, new GameMeta("bedwars", 3)));

        Shard restored = new Shard(instance);
        PolarShardCodec.UserDataView view = PolarShardCodec.load(restored, bytes);
        assertEquals(new GameMeta("bedwars", 3), view.get(META), "codec-typed data round-trips");
        assertNull(view.get(UNSET), "absent keys read null");
        assertEquals(original.diff(), restored.diff());
    }

    @Test
    void noOverridesMeansNoneRestored() {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(9, 70, 9), Block.STONE);

        Shard restored = new Shard(instance);
        PolarShardCodec.load(restored, PolarShardCodec.save(original));

        assertEquals(-1, restored.timeOverride(), "inherit-base time stays inherited");
        assertNull(restored.weatherOverride());
        assertEquals(1, restored.overlaySize());
    }

    @Test
    void nbtBlocksRoundTrip() {
        CompoundBinaryTag nbt = CompoundBinaryTag.builder().putString("test:marker", "kept").build();
        Shard original = new Shard(instance);
        original.setBlock(new Vec(12, 70, 12), Block.CHEST.withNbt(nbt));
        original.setBlock(new Vec(13, 70, 12), Block.CHEST); // nbt-less twin shares the palette entry

        Shard restored = new Shard(instance);
        PolarShardCodec.load(restored, PolarShardCodec.save(original));

        assertEquals(nbt, restored.getBlock(12, 70, 12).nbt(), "block NBT rides polar block entities");
        assertNull(restored.getBlock(13, 70, 12).nbt(), "the plain twin stays plain");
        assertEquals(original.diff(), restored.diff());
    }

    @Test
    void pathSavesAtomically(@TempDir Path dir) throws IOException {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(15, 70, 15), Block.STONE);
        Path file = dir.resolve("game.polar");

        PolarShardCodec.save(original, file);
        original.setBlock(new Vec(16, 70, 15), Block.OAK_PLANKS);
        PolarShardCodec.save(original, file); // overwrite goes through the tmp+move path

        Shard restored = new Shard(instance);
        PolarShardCodec.load(restored, file);
        assertEquals(original.diff(), restored.diff());
        assertTrue(Files.notExists(dir.resolve("game.polar.tmp")), "no tmp file left behind");
    }

    @Test
    void entitiesRoundTripThroughSaveStampsAndGenericTiers() throws InterruptedException {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(25, 70, 25), Block.STONE);
        ItemEntity loot = new ItemEntity(ItemStack.of(Material.EMERALD, 5));
        original.spawn(loot, new Pos(25.5, 71, 25.5)).join();
        LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setHealth(7f);
        zombie.setTag(Tag.String("test:role"), "boss");
        original.spawn(zombie, new Pos(26.5, 71, 25.5)).join();
        LivingEntity stamped = new LivingEntity(EntityType.ZOMBIE) {}; // custom class: rides its ENTITY_SAVE stamp
        stamped.setTag(WorldAccess.ENTITY_SAVE, () -> CompoundBinaryTag.builder().putString("id", "test:pig").build());
        original.spawn(stamped, new Pos(27.5, 71, 25.5)).join();
        EntityDescriptors.reviveEntity("test:pig", data -> new LivingEntity(EntityType.PIG));
        try {
            byte[] bytes = PolarShardCodec.save(original);

            Shard restored = new Shard(instance);
            PolarShardCodec.load(restored, bytes);
            List<Entity> revived = awaitOwned(restored, 3);

            ItemEntity item = (ItemEntity) revived.stream().filter(e -> e instanceof ItemEntity).findFirst().orElseThrow();
            assertEquals(loot.getItemStack(), item.getItemStack(), "the drop revives with its stack");
            LivingEntity living = (LivingEntity) revived.stream()
                    .filter(e -> e instanceof LivingEntity l && l.getEntityType() == EntityType.ZOMBIE).findFirst().orElseThrow();
            assertEquals(7f, living.getHealth(), "living data revives");
            assertEquals("boss", living.getTag(Tag.String("test:role")), "serializable tags revive");
            assertTrue(revived.stream().anyMatch(e -> e.getEntityType() == EntityType.PIG), "the ENTITY_SAVE reviver ran");
            revived.forEach(Entity::remove);
        } finally {
            loot.remove();
            zombie.remove();
            stamped.remove();
        }
    }

    private static List<Entity> awaitOwned(Shard shard, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            List<Entity> owned = shard.entities().stream()
                    .filter(e -> e.getInstance() != null).toList();
            if (owned.size() >= count) return owned;
            Thread.sleep(10);
        }
        throw new AssertionError("revived entities did not spawn in time");
    }

    @Test
    void blocksOnlySaveSkipsEntities() throws InterruptedException {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(30, 70, 30), Block.STONE);
        ItemEntity loot = new ItemEntity(ItemStack.of(Material.EMERALD, 5));
        original.spawn(loot, new Pos(30.5, 71, 30.5)).join();
        try {
            Shard restored = new Shard(instance);
            PolarShardCodec.load(restored, PolarShardCodec.save(original, null, false));
            Thread.sleep(50); // an (incorrect) revival would spawn async
            assertEquals(1, restored.overlaySize(), "blocks still save");
            assertTrue(restored.entities().isEmpty(), "no entity payload, nothing revives");
        } finally {
            loot.remove();
        }
    }

    @Test
    void foreignDescriptorsSurviveAReviverlessResave() throws InterruptedException {
        Shard original = new Shard(instance);
        LivingEntity alien = new LivingEntity(EntityType.ZOMBIE) {};
        alien.setTag(WorldAccess.ENTITY_SAVE, () -> CompoundBinaryTag.builder().putString("id", "alien:thing").build());
        original.spawn(alien, new Pos(33.5, 71, 33.5)).join();
        byte[] bytes = PolarShardCodec.save(original);
        alien.remove();

        Shard hub = new Shard(instance); // no reviver for alien:thing here: skipped but NOT dropped
        PolarShardCodec.load(hub, bytes);
        assertTrue(hub.entities().isEmpty());
        byte[] resaved = PolarShardCodec.save(hub);

        EntityDescriptors.reviveEntity("alien:thing", data -> new LivingEntity(EntityType.PIG));
        Shard end = new Shard(instance);
        PolarShardCodec.load(end, resaved);
        List<Entity> revived = awaitOwned(end, 1);
        assertTrue(revived.stream().anyMatch(e -> e.getEntityType() == EntityType.PIG),
                "the descriptor rode through the reviver-less hop");
        revived.forEach(Entity::remove);
    }

    @Test
    void unknownStateFailsLoudAndTouchesNothing() {
        PolarSection bogus = new PolarSection(new String[]{"broken:not_a_block"}, null,
                new String[]{"minecraft:plains"}, null,
                PolarSection.LightContent.MISSING, null, PolarSection.LightContent.MISSING, null);
        PolarSection[] sections = new PolarSection[24]; // overworld -4..19
        Arrays.setAll(sections, i -> i == 8 ? bogus : new PolarSection());
        PolarWorld world = new PolarWorld(PolarWorld.LATEST_VERSION, MinecraftServer.DATA_VERSION,
                PolarWorld.CompressionType.ZSTD, (byte) -4, (byte) 19, new byte[0],
                List.of(new PolarChunk(0, 0, sections, List.of(), new int[32][], new byte[0])));
        byte[] bytes = PolarWriter.write(world);

        Shard shard = new Shard(instance);
        var thrown = assertThrows(IllegalArgumentException.class, () -> PolarShardCodec.load(shard, bytes));
        assertTrue(thrown.getMessage().contains("broken:not_a_block"), "the bad palette entry is named");
        assertEquals(0, shard.overlaySize(), "a failed load never mutates the shard");
    }

    private record Op(long tick, double x) {}
    private static final DataKey<List<Op>> OPS = new DataKey<>("replay:ops",
            StructCodec.struct(
                    "t", Codec.LONG, Op::tick,
                    "x", Codec.DOUBLE, Op::x, Op::new).list());

    @Test
    void amendAttachesOpsToTheT0SnapshotBytes() {
        Shard live = new Shard(instance);
        live.setBlock(new Vec(44, 70, 44), Block.STONE);
        live.setTime(18000);
        byte[] t0 = PolarShardCodec.save(live, data -> data.put(META, new GameMeta("bedwars", 3)));

        List<Op> ops = new ArrayList<>(); // MUST exceed adventure's 128KiB default-reader cap (~36B/op)
        for (int i = 0; i < 6000; i++) ops.add(new Op(i, i * 0.5));
        byte[] file = PolarShardCodec.amendUserData(t0, data -> data.put(OPS, List.copyOf(ops)));

        Shard target = new Shard(instance);
        PolarShardCodec.UserDataView view = PolarShardCodec.load(target, file);
        List<Op> read = view.get(OPS);
        assertNotNull(read, "the op log rides the amended snapshot");
        assertEquals(6000, read.size());
        assertEquals(new GameMeta("bedwars", 3), view.get(META), "existing user data survives the amend");
        assertEquals(1, target.overlaySize(), "t0 blocks came along");
        assertEquals(18000, target.timeOverride(), "sky overrides came along");
    }
}
