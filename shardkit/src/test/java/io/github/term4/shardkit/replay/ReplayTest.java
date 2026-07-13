package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.shard.ShardManager;
import io.github.term4.shardkit.event.replay.PlaybackStopEvent;
import io.github.term4.shardkit.event.replay.RecordingStopEvent;
import io.github.term4.shardkit.storage.polar.PolarShardCodec;
import io.github.term4.shardkit.replay.core.Channels;
import io.github.term4.shardkit.replay.core.CoreChannels;
import io.github.term4.shardkit.ticking.ExecutionDomain;
import io.github.term4.shardkit.world.WorldAccess;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.metadata.item.ItemEntityMeta;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recordable architecture end to end with plain entities: channel round-trips, visual-first fallback,
 * unknown-channel skips, big op logs, seeking. Cross-library registration seams live in the shardkit module's
 * bridge tests (real mechanics classes).
 */
class ReplayTest {

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
        foreignStamp.setTag(WorldAccess.ENTITY_SAVE,
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
        Shard stage = new Shard(instance);
        ItemStack stack = ItemStack.of(Material.EMERALD, 5);
        ItemEntity drop = new ItemEntity(stack); // exact class: descriptor + built-in reviver exist
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(drop, new Pos(6.5, 70, 6.5)).join();
            tick();
        } finally {
            file = rec.stop();
            drop.remove();
        }

        Shard upgraded = new Shard(instance);
        Playback up = Replay.play(upgraded, file);
        try {
            tick();
            assertInstanceOf(ItemEntity.class, up.entity(0), "default mode upgrades through the reviver");
        } finally {
            up.halt();
        }

        Shard visual = new Shard(instance);
        Playback vis = Replay.play(visual, file, SpawnMode.VISUAL);
        try {
            tick();
            Entity twin = vis.entity(0);
            assertNotNull(twin);
            assertFalse(twin instanceof ItemEntity, "visual mode ignores registered revivers");
            assertEquals(EntityType.ITEM, twin.getEntityType());
        } finally {
            vis.halt();
        }
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
        byte[] file = PolarShardCodec.amendUserData(PolarShardCodec.save(new Shard(instance), null, false),
                data -> data.put(Replay.OPS, container));

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
        Playback pb = Replay.play(theater, file); // the polar reader must not silently cap the op log at 128KiB
        try {
            assertEquals(6000, pb.counts().get("replay:block"));
            tick();
            assertEquals(Block.STONE, theater.getBlock(0, 64, 0));
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
    void silentTracksPinDriftingTwins() {
        Shard stage = new Shard(instance);
        Entity snowball = new Entity(EntityType.SNOWBALL);
        Recording rec = Replay.record(stage);
        byte[] file;
        try {
            stage.spawn(snowball, new Pos(4.5, 70, 4.5)).join();
            tick();
            tick();
            tick(); // the entity rests: its track goes silent after the first sighting
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
            tick();
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
            rec.emit(Channels.SPAWNS, new Channels.Spawn(0, 2.5, 70, 2.5, 0, 0, 0, visual, CompoundBinaryTag.empty(), "Steve"));
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
                        new Channels.Spawn(0, 5.5, 70, 5.5, 0, 0, 0, visual, CompoundBinaryTag.empty(), ""));
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
            assertTrue(theater.getBlock(2, 70, 2).isAir(), "later ops don't");
            assertNull(pb.entity(0), "a spawn cancelled by its remove never materializes");

            tick();
            await("playback resumes past the seek", () -> theater.getBlock(2, 70, 2) == Block.OAK_PLANKS);
            tick();
            assertFalse(pb.active());
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
