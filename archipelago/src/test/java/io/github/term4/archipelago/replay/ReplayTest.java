package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.event.replay.RecordingStopEvent;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.Nbt;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.metadata.item.ItemEntityMeta;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recordable architecture end to end with plain entities: channel round-trips, visual-first fallback,
 * unknown-channel skips, big op logs, seeking. Cross-library registration seams live in the archipelago module's
 * bridge tests (real mechanics classes).
 */
class ReplayTest {

    /** Marks entities the test-local describers may stamp (keeps registrations from bleeding across tests). */
    private static final Tag<Boolean> REVIVABLE = Tag.Transient("test:revivable");

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
        CoreChannels.register();
    }

    // headless has no server ticker: recording samplers and playback tickers are driven by hand
    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    private static void await(String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 2000;
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertTrue(done.get(), what);
    }

    @Test
    void entityLifecycleRoundTripsThroughTheFile() {
        Shard stage = new Shard(instance);
        Entity snowball = new Entity(EntityType.SNOWBALL); // exact class: the generic descriptor tier covers it
        stage.spawn(snowball, new Pos(10.5, 70, 10.5)).join();

        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick(); // t0 entities ride the op log too, not the snapshot
            snowball.teleport(new Pos(12.5, 70, 12.5)).join();
            tick();
            snowball.remove();
            tick();
        } finally {
            file = rec.stop();
        }
        assertEquals(3, rec.tick());
        assertEquals(1, rec.counts().get("replay:spawn"));
        assertEquals(1, rec.counts().get("replay:remove"));
        assertTrue(rec.counts().get("replay:emove") >= 1, "the teleport landed on the position track");

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        assertEquals(3, pb.length());
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin, "the spawn op revived a twin");
            assertEquals(EntityType.SNOWBALL, twin.getEntityType());
            tick();
            await("the track moves the twin", () -> twin.getPosition().x() == 12.5);
            tick();
            assertTrue(twin.isRemoved(), "the remove op takes the twin with it");
            assertFalse(pb.active(), "playback ends at the recording's length");
        } finally {
            pb.halt();
        }
    }

    @Test
    void visualFirstPlaybackNeedsNoReviver() {
        Shard stage = new Shard(instance);
        ItemStack stack = ItemStack.of(Material.DIAMOND, 3);
        // subclass: no generic descriptor tier, no stamp - the spawn op carries only the visual snapshot
        Entity foreignItem = new Entity(EntityType.ITEM) {};
        ((ItemEntityMeta) foreignItem.getEntityMeta()).setItem(stack);
        // stamped with an id no reviver here knows: playback must still show SOMETHING
        Entity foreignStamp = new Entity(EntityType.ARMOR_STAND) {};
        foreignStamp.setTag(MechanicsWorld.ENTITY_SAVE,
                () -> CompoundBinaryTag.builder().putString("id", "foreign:widget").build());

        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(foreignItem, new Pos(3.5, 70, 3.5)).join();
            stage.spawn(foreignStamp, new Pos(5.5, 70, 5.5)).join();
            tick();
        } finally {
            file = rec.stop();
            foreignItem.remove();
            foreignStamp.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity item = pb.entity(0), stand = pb.entity(1);
            if (item.getEntityType() != EntityType.ITEM) { Entity swap = item; item = stand; stand = swap; }
            assertEquals(EntityType.ITEM, item.getEntityType());
            assertFalse(item instanceof ItemEntity, "a visual stand-in, not a revived class");
            ItemStack shown = item.getMetadataPacket().entries().values().stream()
                    .map(e -> e.value() instanceof ItemStack s ? s : null)
                    .filter(s -> s != null).findFirst().orElse(null);
            assertEquals(stack, shown, "the metadata snapshot carries the stack - no stackless ghost item");
            assertNotNull(stand, "unknown descriptor id still plays as a visual");
            assertEquals(EntityType.ARMOR_STAND, stand.getEntityType());
        } finally {
            pb.halt();
        }
    }

    @Test
    void visualModeForcesStandIns() {
        // a REGISTERED reviver: generic-tier descriptors puppet now (the track is their truth), so the
        // "default mode upgrades" contrast needs a real registration
        Runnable unDescribe = EntityDescriptors.describeEntity(e ->
                Boolean.TRUE.equals(e.getTag(REVIVABLE)) && e instanceof ItemEntity
                        ? CompoundBinaryTag.builder().putString("id", "test:item").build() : null);
        Runnable unRevive = EntityDescriptors.reviveEntity("test:item",
                desc -> new ItemEntity(ItemStack.of(Material.EMERALD, 5)));
        Shard stage = new Shard(instance);
        ItemStack stack = ItemStack.of(Material.EMERALD, 5);
        ItemEntity drop = new ItemEntity(stack);
        drop.setTag(REVIVABLE, true);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(drop, new Pos(6.5, 70, 6.5)).join();
            tick();
        } finally {
            file = rec.stop();
            drop.remove();
        }

        // playback is puppet-only at every mode now: revivers are save/load-side, twins are stand-ins
        Shard upgraded = new Shard(instance);
        Playback up = Replay.play(upgraded, file);
        try {
            tick();
            Entity def = up.entity(0);
            assertNotNull(def);
            assertFalse(def instanceof ItemEntity, "default mode puppets too");
            assertEquals(EntityType.ITEM, def.getEntityType());
        } finally {
            up.halt();
        }

        Shard visual = new Shard(instance);
        Playback vis = Replay.play(visual, file);
        try {
            tick();
            Entity twin = vis.entity(0);
            assertNotNull(twin);
            assertFalse(twin instanceof ItemEntity, "visual mode ignores registered revivers");
            assertEquals(EntityType.ITEM, twin.getEntityType());
        } finally {
            vis.halt();
        }
        unDescribe.run();
        unRevive.run();
    }

    @Test
    void stopAsyncEndsTheRecordingImmediatelyAndEncodesOffThread() {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        tick();
        rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 2, 70, 2, "minecraft:stone"));
        byte[] file = rec.stopAsync().join();
        assertFalse(rec.active(), "the recording ended on the calling thread, not when the future completed");
        rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 3, 70, 3, "minecraft:dirt")); // dropped, not an error

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            assertEquals(1, pb.counts().get("replay:block"), "only pre-stop ops are in the file");
            tick();
            assertEquals(Block.STONE, theater.getBlock(2, 70, 2));
        } finally {
            pb.halt();
        }
    }

    @Test
    void domainShardRecordingsSurviveCrossThreadEmits() throws InterruptedException {
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Shard stage = new Shard(instance, domain);
            Recording rec = Replay.record(stage);
            // two foreign threads storm emits while the sampler runs on the shard's clock - the audit's race
            Thread blocks = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, i & 15, 64 + (i >> 4), 0, "minecraft:stone"));
                }
            });
            Thread acts = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    rec.emit(Channels.ACTS, new Channels.Act("Steve", Channels.SWING_MAIN, 0));
                }
            });
            blocks.start();
            acts.start();
            blocks.join();
            acts.join();
            await("every hopped emit lands on the clock", () -> {
                var counts = rec.counts();
                return Integer.valueOf(500).equals(counts.get("replay:block"))
                        && Integer.valueOf(500).equals(counts.get("replay:act"));
            });
            byte[] file = rec.stop();

            Playback pb = Replay.play(new Shard(instance), file);
            try {
                assertEquals(500, pb.counts().get("replay:block"), "no op lost or torn");
                assertEquals(500, pb.counts().get("replay:act"));
            } finally {
                pb.halt();
            }
        } finally {
            domain.retire();
        }
    }

    @Test
    void recordingsSurviveTheirDomainRetiring() throws InterruptedException {
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Shard stage = new Shard(instance, domain);
            Recording rec = Replay.record(stage);
            rec.emit(Channels.ACTS, new Channels.Act("Steve", Channels.SWING_MAIN, 0));
            await("the pre-retire emit lands", () -> rec.counts().containsKey("replay:act"));
            domain.retire();
            // the owner thread is gone: hopped mutators now run inline on their callers, concurrently
            Thread blocks = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, i & 15, 64 + (i >> 4), 0, "minecraft:stone"));
                }
            });
            Thread acts = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    rec.emit(Channels.ACTS, new Channels.Act("Alex", Channels.SWING_MAIN, 0));
                }
            });
            blocks.start();
            acts.start();
            blocks.join();
            acts.join();
            byte[] file = rec.stop();

            Playback pb = Replay.play(new Shard(instance), file);
            try {
                assertEquals(300, pb.counts().get("replay:block"), "no op lost after the domain died");
                assertEquals(301, pb.counts().get("replay:act"));
            } finally {
                pb.halt();
            }
        } finally {
            domain.retire();
        }
    }

    @Test
    void unknownChannelsSkipButTheRestPlays() {
        CompoundBinaryTag blockOp = CompoundBinaryTag.builder().putLong("t", 1)
                .put("op", Channels.BLOCKS.codec().encode(Transcoder.NBT,
                        new Channels.BlockOp(0, 1, 70, 1, "minecraft:stone")).orElseThrow()).build();
        CompoundBinaryTag container = CompoundBinaryTag.builder().putInt("v", 1).putLong("ticks", 2)
                .put("channels", CompoundBinaryTag.builder()
                        .put("replay:block", CompoundBinaryTag.builder().putInt("v", 2)
                                .put("ops", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(blockOp).build()).build())
                        .put("nope:mystery", CompoundBinaryTag.builder().putInt("v", 9)
                                .put("ops", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).build()).build())
                        .build())
                .build();
        byte[] file = ShardFile.save(new Shard(instance), data -> data.put(Replay.OPS, container));

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            assertEquals(List.of("nope:mystery v9"), pb.skipped());
            tick();
            tick();
            assertEquals(Block.STONE, theater.getBlock(1, 70, 1), "known channels still play");
            assertFalse(pb.active());
        } finally {
            pb.halt();
        }
    }

    @Test
    void hugeOpLogsRoundTrip() {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        for (int i = 0; i < 6000; i++) {
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, i % 16, 64 + i / 256, (i / 16) % 16, "minecraft:stone"));
        }
        byte[] file = rec.stop();

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file); // the NBT reader must not silently cap the op log at 128KiB
        try {
            assertEquals(6000, pb.counts().get("replay:block"));
            tick();
            assertEquals(Block.STONE, theater.getBlock(0, 64, 0));
        } finally {
            pb.halt();
        }
    }

    @Test
    void nbtBlocksSurviveTheT0Snapshot() {
        CompoundBinaryTag nbt = CompoundBinaryTag.builder().putString("test:sign", "line1").build();
        Shard stage = new Shard(instance);
        stage.setBlock(new Pos(40, 70, 40), Block.CHEST.withNbt(nbt));
        Recording rec = Replay.record(stage);
        tick();
        byte[] file = rec.stop();

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            assertEquals(nbt, theater.getBlock(40, 70, 40).nbt(), "sign-text-grade t0 fidelity");
        } finally {
            pb.halt();
        }
    }

    @Test
    void eventDrivenRemovalsLandAtTheImpactPointWithAMinimumLifetime() {
        Shard stage = new Shard(instance);
        Entity snowball = new Entity(EntityType.SNOWBALL);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(snowball, new Pos(1.5, 70, 1.5)).join();
            tick(); // spawn op at tick 1
            rec.remove(snowball, new Pos(3.5, 70.5, 3.5, 90, 0), ""); // impact reported the SAME tick
            snowball.remove();
            tick();
            tick();
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin, "a same-tick spawn+remove still shows for one tick");
            assertFalse(twin.isRemoved());
            tick();
            assertTrue(twin.isRemoved(), "gone on the (min-lifetime) removal tick");
            await("the twin died at the reported impact point", () -> twin.getPosition().x() == 3.5);
        } finally {
            pb.halt();
        }
    }

    @Test
    void trackCorrectionsAreBandedForSelfSimulatingTwins() {
        Runnable unDescribe = EntityDescriptors.describeEntity(e ->
                Boolean.TRUE.equals(e.getTag(REVIVABLE)) && e.getEntityType() == EntityType.SNOWBALL
                        ? CompoundBinaryTag.builder().putString("id", "test:snowball").build() : null);
        Runnable unRevive = EntityDescriptors.reviveEntity("test:snowball", desc -> {
            Entity ball = new Entity(EntityType.SNOWBALL);
            ball.setNoGravity(true); // headless: nothing ticks it - hold it like the recorded one
            return ball;
        });
        Shard stage = new Shard(instance);
        Entity real = new Entity(EntityType.SNOWBALL);
        real.setTag(REVIVABLE, true);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(real, new Pos(5.5, 70, 5.5)).join();
            tick(); // tick 1: first sighting -> spawn op (generic descriptor: revives as a physics twin)
            tick(); // tick 2
            rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(0, 5.6, 70, 5.5, 0, 0));
            tick(); // tick 3
            tick(); // tick 4
            rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(0, 9.5, 70, 5.5, 0, 0)); // a whole-leg jump
            tick(); // tick 5
        } finally {
            file = rec.stop();
            real.remove();
        }

        // every twin is parked and track-driven at full rate: legs arrive on their deadline even for a
        // twin nothing ticks (the arrival absolute), free-run is retired
        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin);
            await("twin lands", () -> twin.getInstance() != null);
            assertTrue(twin.hasNoGravity(), "parked at track time");
            // the pull legs converge asymptotically (sub-voxel residual): near, not exact
            for (int i = 0; i < 8 && Math.abs(twin.getPosition().x() - 5.6) >= 0.05; i++) tick();
            assertTrue(Math.abs(twin.getPosition().x() - 5.6) < 0.05,
                    "the track point carries the twin (x=" + twin.getPosition().x() + ")");
            for (int i = 0; i < 8 && twin.getPosition().x() != 9.5; i++) tick();
            assertEquals(9.5, twin.getPosition().x(), "a whole-leg displacement still lands");
        } finally {
            pb.halt();
        }

        unDescribe.run();
        unRevive.run();
        // ...while a stand-in stays hard track-driven (the 1.8 bobber wire depends on it)
        Shard visualTheater = new Shard(instance);
        Playback visualPb = Replay.play(visualTheater, file);
        try {
            tick();
            Entity standIn = visualPb.entity(0);
            assertNotNull(standIn);
            await("stand-in lands", () -> standIn.getInstance() != null);
            tick();
            // hard track-driven: the recorded point, on the file's 1/4096 grid - not the source double
            await("the stand-in takes the in-band point",
                    () -> Math.abs(standIn.getPosition().x() - 5.6) < 1.0 / 4096);
        } finally {
            visualPb.halt();
        }
    }

    @Test
    void silentTracksPinDriftingTwins() {
        Shard stage = new Shard(instance);
        Entity snowball = new Entity(EntityType.SNOWBALL);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(snowball, new Pos(4.5, 70, 4.5)).join();
            for (int i = 0; i < 8; i++) tick(); // the entity rests: its track goes silent after the first sighting
        } finally {
            file = rec.stop();
            snowball.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin);
            await("twin lands", () -> twin.getInstance() != null);
            twin.teleport(new Pos(4.5, 65, 4.5)).join(); // a physics twin drifting (falling) off a silent track
            // the pin waits out the rest grace (a momentary track stall is not a rest), then snaps back
            for (int i = 0; i < 4; i++) tick();
            await("the silent track pins the twin back", () -> twin.getPosition().y() == 70);
        } finally {
            pb.halt();
        }
    }

    @Test
    void shooterOwnedSpawnsWaitForTheirGhost() {
        Shard stage = new Shard(instance);
        CompoundBinaryTag visual = Visuals.snapshot(new Entity(EntityType.FISHING_BOBBER));
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick(); // tick 1: the cast recorded BEFORE the actor's first move (a spawn-order hazard)
            rec.emit(Channels.SPAWNS, new Channels.Spawn(0, 2.5, 70, 2.5, 0, 0, 0, visual, "Steve"));
            tick(); // tick 2
            tick(); // tick 3: the ghost's first move
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 1.5, 70, 1.5, 0, 0, true));
            tick();
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            assertNull(pb.entity(0), "no ghost yet: the owned spawn waits (a client would discard it)");
            tick();
            tick(); // the ghost lands; the deferred spawn follows
            await("the twin spawns once its owner is world-ready", () -> pb.entity(0) != null);
        } finally {
            pb.halt();
        }
    }

    @Test
    void playbackRidesTheTheatersClock() {
        Shard stage = new Shard(instance);
        Entity snowball = new Entity(EntityType.SNOWBALL);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(snowball, new Pos(2.5, 70, 2.5)).join();
            rec.emit(Channels.MOVES, new Channels.Move("ClockSteve", 1.5, 70, 1.5, 0, 0, true));
            tick();
            tick();
        } finally {
            file = rec.stop();
            snowball.remove();
        }

        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Shard theater = new Shard(instance, domain);
            Playback pb = Replay.play(theater, file);
            try {
                // no manual tick() anywhere: only the theater's own clock can be driving this
                await("the theater's clock drives playback", () -> pb.tick() >= 2);
                await("the ghost rises domain-owned", () -> pb.ghost("ClockSteve") != null
                        && ExecutionDomain.of(pb.ghost("ClockSteve")) == theater.domain());
                await("the revived twin is domain-owned too", () -> pb.entity(0) != null
                        && ExecutionDomain.of(pb.entity(0)) == theater.domain());
            } finally {
                pb.halt();
                await("halt lands on the owner thread", () -> !pb.active());
            }
        } finally {
            domain.retire();
        }
    }

    @Test
    void seekAppliesOnlyLatestStates() {
        Shard stage = new Shard(instance);
        CompoundBinaryTag visual = Visuals.snapshot(new Entity(EntityType.SNOWBALL));
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            for (int i = 1; i <= 10; i++) {
                tick();
                rec.emit(Channels.MOVES, new Channels.Move("Steve", i + 0.5, 70, 0.5, 0, 0, true));
                if (i == 2) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 1, 70, 1, "minecraft:stone"));
                if (i == 3) rec.emit(Channels.SPAWNS,
                        new Channels.Spawn(0, 5.5, 70, 5.5, 0, 0, 0, visual, ""));
                if (i == 7) rec.emit(Channels.REMOVES, new Channels.Remove(0, ""));
                if (i == 9) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 2, 70, 2, "minecraft:oak_planks"));
            }
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Ghost ghost = pb.ghost("Steve");
            assertNotNull(ghost);

            pb.seek(8);
            assertEquals(8, pb.tick());
            await("the ghost jumps to its latest folded position", () -> ghost.getPosition().x() == 8.5);
            assertEquals(Block.STONE, theater.getBlock(1, 70, 1), "block ops inside the window apply");
            assertTrue(theater.getBlock(2, 70, 2).air(), "later ops don't");
            assertNull(pb.entity(0), "a spawn cancelled by its remove never materializes");

            tick();
            await("playback resumes past the seek", () -> theater.getBlock(2, 70, 2) == Block.OAK_PLANKS);
            tick();
            assertFalse(pb.active());
        } finally {
            pb.halt();
        }
    }

    // a game server's own channel, written entirely against the public seams - no core involvement
    private record Score(String team, int points) {}

    private static final class ScoreApplier implements Applier<Score> {
        final Map<String, Integer> board = new ConcurrentHashMap<>();

        @Override
        public void apply(Playback pb, Recorded<Score> op) {
            board.put(op.op().team(), op.op().points()); // a real game would also push a Sidebar here
        }
    }

    private static final Recordable<Score> SCORE = Recordable.of("game:score", 1, 60, StructCodec.struct(
                    "t", Codec.STRING, Score::team, "p", Codec.INT, Score::points, Score::new), ScoreApplier::new)
            .folding((s, op) -> s.latest(ReplayTest.SCORE, op.op().team(), op));

    @Test
    void gameChannelsAndResultsAreFullyUserImplementable() {
        Replay.register(SCORE);
        DataKey<String> results = new DataKey<>("game:results", Codec.STRING);
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick(); // 1
            rec.emit(SCORE, new Score("Blue", 1));
            tick(); // 2
            rec.emit(SCORE, new Score("Red", 1));
            tick(); // 3
            rec.emit(SCORE, new Score("Blue", 2));
            tick(); // 4
        } finally {
            file = rec.stop(w -> w.put(results, "Blue 2 - 1 Red")); // the outcome is metadata, not an op
        }

        assertEquals("Blue 2 - 1 Red", ShardFile.userData(file).get(results),
                "metadata reads without touching a shard - the lobby-listing path");

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            assertEquals("Blue 2 - 1 Red", pb.data(results));
            pb.seek(1);
            ScoreApplier live = (ScoreApplier) pb.applier(SCORE);
            assertNotNull(live);
            assertEquals(1, live.board.get("Blue"));
            assertNull(live.board.get("Red"), "Red hasn't scored yet at tick 1");
            pb.seek(4);
            live = (ScoreApplier) pb.applier(SCORE);
            assertEquals(2, live.board.get("Blue"));
            assertEquals(1, live.board.get("Red"));
        } finally {
            pb.halt();
        }
    }

    @Test
    void playbackRateScalesTheTimeline() {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            for (int i = 1; i <= 6; i++) {
                tick();
                if (i == 2) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 3, 72, 3, "minecraft:stone"));
            }
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.rate(2);
            tick(); // 0 -> 2
            assertEquals(2, pb.tick());
            assertEquals(Block.STONE, theater.getBlock(3, 72, 3), "the tick-2 op landed in one real tick");
            pb.rate(0.5);
            tick(); // the half carries
            assertEquals(2, pb.tick());
            tick(); // -> 3
            assertEquals(3, pb.tick());
            pb.rate(0);
            tick(); // zero holds in place
            assertEquals(3, pb.tick());
            pb.rate(-2); // a negative rate flips into reverse
            assertTrue(pb.reversed());
            tick(); // -> 1: two reversed steps, the op un-crosses
            assertEquals(1, pb.tick());
            assertTrue(theater.getBlock(3, 72, 3).air(), "reverse honors the rate");
            pb.pause(true);
            pb.rate(1); // asking for motion resumes a paused timeline
            assertFalse(pb.paused());
            pb.rate(Double.MAX_VALUE); // step() advances |rate| times per theater tick - unclamped, this stalls the clock
            assertEquals((double) Playback.MAX_RATE, pb.rate(), "impractical rates clamp");
            assertThrows(IllegalArgumentException.class, () -> pb.rate(Double.NaN));
        } finally {
            pb.halt();
        }
    }

    /** A plain shard save (no op log) must throw BEFORE mutating the target. */
    @Test
    void playRejectsAPlainSaveWithoutTouchingTheTarget() {
        Shard stage = new Shard(instance);
        stage.setBlock(new Pos(5, 71, 5), Block.STONE);
        byte[] plainSave = ShardFile.save(stage);

        Shard theater = new Shard(instance);
        theater.setBlock(new Pos(9, 71, 9), Block.DIRT);
        assertThrows(IllegalArgumentException.class, () -> Replay.play(theater, plainSave));
        assertEquals(Block.DIRT, theater.getBlock(9, 71, 9), "the failed play left the target's overlay alone");
        assertTrue(theater.getBlock(5, 71, 5).air(), "and no t0 snapshot landed");
    }

    @Test
    void bookmarksMarkMomentsAndRideBothPaths() {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick();
            tick();
            rec.bookmark("bed-break", CompoundBinaryTag.builder().putString("team", "Blue").build());
            tick();
            tick();
            rec.bookmark("goal");
            tick();
        } finally {
            file = rec.stop();
        }

        List<Replay.Bookmark> index = ShardFile.userData(file).get(Replay.BOOKMARKS);
        assertNotNull(index, "the index reads without touching a shard");
        assertEquals(2, index.size());
        assertEquals(2, index.get(0).tick());
        assertEquals("Blue", index.get(0).data().getString("team"));

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            assertEquals("goal", pb.bookmarks().get(1).name());
            pb.seek(pb.bookmarks().get(1).tick()); // the jump-to-moment recipe
            assertEquals(4, pb.tick());
        } finally {
            pb.halt();
        }

        Shard theater2 = new Shard(instance);
        Playback inMemory = Replay.play(theater2, rec);
        try {
            assertEquals(2, inMemory.bookmarks().size(), "the in-memory path carries the index too");
        } finally {
            inMemory.halt();
        }
    }

    @Test
    void rewindCommitsRecordIntoTheGameFootage() {
        Pos arena = new Pos(13, 70, 5);
        Shard live = new Shard(instance);
        Recording game = Replay.record(live); // the game's own replay runs through the ability
        byte[] file;
        try {
            tick(); // 1: the fight builds
            live.setBlock(arena, Block.STONE);
            tick(); // 2: the rewind ability commits the past - a server-side bulk write, no player event
            live.setBlocks(Map.of(arena, Block.AIR));
            tick(); // 3
        } finally {
            file = game.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(1);
            assertEquals(Block.STONE, theater.getBlock(arena), "the edit recorded at the write hook");
            pb.seek(3);
            assertTrue(theater.getBlock(arena).air(),
                    "the commit recorded too - the footage shows time snapping back");
        } finally {
            pb.halt();
        }
    }

    @Test
    void understructureOptOutRecordsNoMasks() {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage).understructure(false);
        byte[] file;
        try {
            tick();
            rec.blockCovered(new Vec(1, 72, 1), Block.AIR); // the probe still calls; the knob drops it
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 1, 72, 1, "minecraft:stone"));
            tick();
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            List<Recorded<Channels.BlockOp>> blocks = pb.ops(Channels.BLOCKS);
            assertEquals(1, blocks.size(), "no tick-0 understructure masks");
            assertTrue(blocks.get(0).tick() > 0);
            pb.seek(2);
            assertEquals(Block.STONE, theater.getBlock(1, 72, 1), "and it plays normally");
        } finally {
            pb.halt();
        }
    }

    @Test
    void stateChannelsFoldPlayerStateForTakeOver() {
        StateChannels.register();
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick(); // 1: baseline
            rec.emit(StateChannels.HEALTH, new StateChannels.Health("Steve", 20));
            rec.emit(StateChannels.HUNGER, new StateChannels.Hunger("Steve", 20, 5));
            rec.emit(StateChannels.XP, new StateChannels.Xp("Steve", 3, 0.5f));
            rec.emit(StateChannels.INVENTORY, new StateChannels.Slot("Steve", 0,
                    Nbt.item(ItemStack.of(Material.DIAMOND_SWORD))));
            tick(); // 2: takes a hit, picks up gapples
            rec.emit(StateChannels.HEALTH, new StateChannels.Health("Steve", 16));
            rec.emit(StateChannels.INVENTORY, new StateChannels.Slot("Steve", 1,
                    Nbt.item(ItemStack.of(Material.GOLDEN_APPLE, 3))));
            tick(); // 3
            tick(); // 4: another hit, the gapples are gone
            rec.emit(StateChannels.HEALTH, new StateChannels.Health("Steve", 7));
            rec.emit(StateChannels.INVENTORY, new StateChannels.Slot("Steve", 1, CompoundBinaryTag.empty()));
            tick(); // 5
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(3);
            assertEquals(16f, StateChannels.health(pb, "Steve"));
            assertEquals(new StateChannels.Hunger("Steve", 20, 5), StateChannels.hunger(pb, "Steve"));
            assertEquals(new StateChannels.Xp("Steve", 3, 0.5f), StateChannels.xp(pb, "Steve"));
            Map<Integer, ItemStack> inv = StateChannels.inventory(pb, "Steve");
            assertEquals(Material.DIAMOND_SWORD, inv.get(0).material());
            assertEquals(3, inv.get(1).amount());

            pb.seek(5);
            assertEquals(7f, StateChannels.health(pb, "Steve"));
            assertEquals(ItemStack.AIR, StateChannels.inventory(pb, "Steve").get(1),
                    "a cleared slot folds to explicit air");

            pb.reverse(true);
            tick(); // -> 4
            tick(); // -> 3: tick 4's ops un-cross
            assertEquals(16f, StateChannels.health(pb, "Steve"), "reversal restores the previous fold");
            assertEquals(3, StateChannels.inventory(pb, "Steve").get(1).amount());
            tick(); // -> 2
            tick(); // -> 1
            tick(); // -> 0: the first ops un-cross, the state is unknown again
            assertNull(StateChannels.health(pb, "Steve"));
            assertTrue(StateChannels.inventory(pb, "Steve").isEmpty());
        } finally {
            pb.halt();
        }
    }

    @Test
    void timeRewindRecipePlaysTheLiveRecordingInMemory() {
        Pos kept = new Pos(11, 70, 3), placed = new Pos(12, 70, 3);
        Shard live = new Shard(instance);
        live.setBlock(kept, Block.GOLD_BLOCK); // pre-fight state: in the recording's t0
        Recording rec = Replay.record(live);
        try {
            tick(); // tick 1
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 1.5, 70, 1.5, 0, 0, true));
            tick(); // tick 2: the fight overwrites the gold - real writes, the hook records ops AND masks
            live.setBlock(kept, Block.STONE);
            tick(); // tick 3: the fight builds on virgin ground
            live.setBlock(placed, Block.OAK_PLANKS);
            tick(); // tick 4
            assertThrows(IllegalStateException.class, () -> Replay.play(live, rec),
                    "a running recording is not a snapshot");
        } finally {
            rec.stop(); // the returned file is unused - the recording object IS the footage
        }
        assertEquals(Block.STONE, live.getBlock(kept));
        assertEquals(Block.OAK_PLANKS, live.getBlock(placed));

        // the rewind ability: a theater CHILD of the live shard plays the recording straight from memory
        Shard theater = new Shard(live);
        Playback pb = Replay.play(theater, rec);
        try {
            pb.seek(4); // to "now": the theater mirrors the present
            assertEquals(Block.STONE, theater.getBlock(kept));
            assertEquals(Block.OAK_PLANKS, theater.getBlock(placed));

            pb.seek(2); // instant time travel, 2 ticks back (rewind + refold)
            assertEquals(Block.STONE, theater.getBlock(kept), "tick 2's stone applied");
            assertTrue(theater.getBlock(placed).air(),
                    "the not-yet-built planks are masked off the live parent");
            assertEquals(Block.OAK_PLANKS, live.getBlock(placed), "the live present is untouched behind the mask");
            assertNotNull(pb.ghost("Steve"), "the past's fighter stands in the theater");

            pb.reverse(true); // continuous rewind across the remaining fight edit
            tick(); // -> 1
            assertEquals(1, pb.tick());
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(kept),
                    "un-crossing the overwrite restores the pre-fight gold");
        } finally {
            pb.halt();
        }

        // the ability commits: the rewound state lands on the live shard, the theater empties
        // (a managed app would ShardManager.destroy it)
        live.setBlocks(theater.diff());
        theater.reset();
        assertEquals(Block.GOLD_BLOCK, live.getBlock(kept), "time rewound for real");
        assertTrue(live.getBlock(placed).air());
        assertFalse(live.diff().containsKey(new Vec(12, 70, 3)), "the planks entry cleared - the rewind IS the undo");
    }

    @Test
    void reversePlaybackUnwindsToT0AndReplays() {
        Pos kept = new Pos(6, 70, 6), placed = new Pos(7, 70, 6); // loaded chunk: un-placing clears the entry
        Shard stage = new Shard(instance);
        stage.setBlock(kept, Block.GOLD_BLOCK); // t0 content
        Entity real = new Entity(EntityType.SNOWBALL);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            tick(); // tick 1
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 1.5, 70, 1.5, 0, 0, true));
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 7, 70, 6, "minecraft:stone"));
            tick(); // tick 2
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 6, 70, 6, "minecraft:diamond_block")); // over t0 gold
            stage.spawn(real, new Pos(4.5, 71, 4.5)).join();
            tick(); // tick 3: the sweep sights the snowball (spawn op, seq 0)
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 3.5, 70, 3.5, 0, 0, true));
            tick(); // tick 4
            rec.remove(real, new Pos(4.5, 71, 4.5, 0, 0), "");
            real.remove();
            tick(); // tick 5
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(5); // everything applied
            assertEquals(Block.STONE, theater.getBlock(placed));
            assertEquals(Block.DIAMOND_BLOCK, theater.getBlock(kept));
            assertNull(pb.entity(0), "removed");
            assertNotNull(pb.ghost("Steve"));

            pb.reverse(true);
            tick(); // -> 4 (nothing recorded past tick 4 to un-cross)
            assertEquals(4, pb.tick());
            tick(); // -> 3: the removal un-crosses, the twin lives again
            assertNotNull(pb.entity(0), "reverse across the removal re-raises the twin");
            tick(); // -> 2: the spawn un-crosses; Steve walks back to his first point
            assertNull(pb.entity(0), "reverse across the spawn removes the twin");
            assertEquals(1.5, pb.ghost("Steve").getPosition().x(), "the ghost walked back");
            assertEquals(Block.DIAMOND_BLOCK, theater.getBlock(kept), "tick 2's block still applied");
            tick(); // -> 1: the overwrite un-crosses back to the t0 block
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(kept), "prior-less ops restore the t0 snapshot");
            tick(); // -> 0: the place un-crosses to the parent view; Steve un-sights
            assertEquals(0, pb.tick());
            assertTrue(theater.getBlock(placed).air());
            assertNull(pb.ghost("Steve"), "before his first move he wasn't here");
            assertEquals(stage.diff(), theater.diff(), "fully unwound, the theater IS the t0 snapshot");
            tick(); // holds at 0
            assertEquals(0, pb.tick());

            pb.reverse(false); // and forward replays the same footage
            tick();
            assertEquals(Block.STONE, theater.getBlock(placed));
        } finally {
            pb.halt();
        }
    }

    @Test
    void backwardSeekRewindsToT0AndRefolds() {
        Shard stage = new Shard(instance);
        stage.setBlock(new Pos(3, 70, 3), Block.GOLD_BLOCK); // t0 content
        CompoundBinaryTag visual = Visuals.snapshot(new Entity(EntityType.SNOWBALL));
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            for (int i = 1; i <= 6; i++) {
                tick();
                if (i == 2) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 1, 71, 1, "minecraft:stone"));
                if (i == 3) rec.emit(Channels.SPAWNS,
                        new Channels.Spawn(0, 5.5, 70, 5.5, 0, 0, 0, visual, ""));
                if (i == 4) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 2, 71, 2, "minecraft:oak_planks"));
                if (i == 5) rec.emit(Channels.REMOVES, new Channels.Remove(0, ""));
            }
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(6); // everything applied: both blocks, twin spawned then removed
            assertEquals(Block.STONE, theater.getBlock(1, 71, 1));
            assertEquals(Block.OAK_PLANKS, theater.getBlock(2, 71, 2));
            assertNull(pb.entity(0));

            pb.seek(3); // BACKWARD: rewind to t0, refold forward
            assertEquals(3, pb.tick());
            assertEquals(Block.GOLD_BLOCK, theater.getBlock(3, 70, 3), "t0 re-seeded");
            assertEquals(Block.STONE, theater.getBlock(1, 71, 1), "ops inside the window re-applied");
            assertTrue(theater.getBlock(2, 71, 2).air(), "later blocks unwound");
            assertNotNull(pb.entity(0), "the twin is alive again before its recorded removal");

            pb.seek(6); // and forward again
            assertNull(pb.entity(0), "scrubbing forward re-removes");
        } finally {
            pb.halt();
        }
    }

    @Test
    void sessionsAutoEndWhenTheirShardIsDestroyed() {
        ShardManager manager = new ShardManager();
        Set<Class<?>> stopEvents = ConcurrentHashMap.newKeySet();
        EventNode<Event> probe = EventNode.all("test:auto-end");
        probe.addListener(RecordingStopEvent.class, e -> stopEvents.add(RecordingStopEvent.class));
        probe.addListener(PlaybackStopEvent.class, e -> stopEvents.add(PlaybackStopEvent.class));
        MinecraftServer.getGlobalEventHandler().addChild(probe);
        try {
            Shard stage = manager.create(instance);
            Recording rec = Replay.record(stage);
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 1.5, 70, 1.5, 0, 0, true));
            tick();
            manager.destroy(stage);
            assertFalse(rec.active(), "the recording ended with its shard");
            assertTrue(stopEvents.contains(RecordingStopEvent.class), "the stop event frees app fields");
            byte[] file = rec.stop(); // an auto-ended recording still hands over its footage
            assertEquals(1, rec.counts().get("replay:move"));

            Shard theater = manager.create(instance);
            Playback pb = Replay.play(theater, file);
            tick();
            Ghost ghost = pb.ghost("Steve");
            assertNotNull(ghost);
            manager.destroy(theater);
            assertFalse(pb.active(), "playback halted with its theater");
            assertTrue(ghost.isRemoved(), "its cast strikes too");
            assertTrue(stopEvents.contains(PlaybackStopEvent.class));
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(probe);
        }
    }
}
