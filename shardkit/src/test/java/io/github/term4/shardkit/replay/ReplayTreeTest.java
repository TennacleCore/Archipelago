package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.ticking.ExecutionDomain;
import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.shard.ShardManager;
import io.github.term4.shardkit.storage.polar.PolarShardCodec;
import io.github.term4.shardkit.replay.core.Channels;
import io.github.term4.shardkit.replay.core.CoreChannels;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.world.WorldAccess;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tree replays: the whole recorded tree rebuilds in the theater - child layers as relative diffs, layered
 * block ops, actors and entities placed (and switching) by recorded layer.
 */
class ReplayTreeTest {

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

    private static void await(String what, java.util.function.Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertTrue(done.get(), what);
    }

    @Test
    void treeRecordingsRebuildLayersBlocksActorsAndEntities() {
        ShardManager shards = new ShardManager();
        Shard stage = shards.create(instance, "replay-tree-stage");
        Shard layer = shards.child(stage, "replay-tree-layer");
        layer.setBlock(new Vec(3, 70, 3), Block.GOLD_BLOCK); // the layer's relative t0 diff
        FakePlayer actor = FakePlayer.connect(instance, new Pos(0.5, 66, 0.5), "TreeSteve");
        Entity prop = new Entity(EntityType.ARMOR_STAND);
        Entity mover = new Entity(EntityType.ARMOR_STAND);
        Playback pb = null;
        try {
            layer.join(actor.player);
            layer.spawn(prop, new Pos(2.5, 66, 2.5));

            Recording rec = Replay.record(stage);
            tick(); // tick 1: actor move + LAYER act, prop spawn (seq 0) - all in layer 1
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(rec.layer(layer), 4, 70, 4, "minecraft:diamond_block"));
            layer.spawn(mover, new Pos(4.5, 66, 4.5));
            tick(); // tick 2: mover spawn (seq 1) in layer 1
            stage.transfer(mover);
            stage.join(actor.player); // the layer switch back to the root
            tick(); // tick 3: LAYER act 0 + the mover's entity-layer op
            tick(); // tick 4
            // the whole tree op log must also survive a polar exchange round-trip
            byte[] file = PolarShardCodec.toShardFile(PolarShardCodec.fromShardFile(rec.stop()));

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, file);
            for (int i = 0; i < 3; i++) tick(); // stop at tick 3: the session must still be live for the asserts

            Shard theaterLayer = pb.layer(1);
            assertNotNull(theaterLayer, "the recorded layer rose in the theater");
            assertEquals(theater, theaterLayer.parent(), "as a child of the target");
            assertEquals(Block.GOLD_BLOCK, theaterLayer.diff().get(new Vec(3, 70, 3)),
                    "the relative t0 diff landed in the LAYER");
            assertNull(theater.diff().get(new Vec(3, 70, 3)), "never in the root");
            assertEquals(Block.DIAMOND_BLOCK, theaterLayer.diff().get(new Vec(4, 70, 4)),
                    "layered block ops route to their layer");

            Ghost ghost = pb.ghost("TreeSteve");
            assertNotNull(ghost);
            assertEquals(theater, ghost.getTag(WorldAccess.ENTITY_TAG),
                    "the recorded switch moved the ghost back to the root layer");
            assertNotNull(pb.entity(0));
            assertEquals(theaterLayer, pb.entity(0).getTag(WorldAccess.ENTITY_TAG),
                    "the layer-local twin plays in the layer theater");
            assertNotNull(pb.entity(1));
            assertEquals(theater, pb.entity(1).getTag(WorldAccess.ENTITY_TAG),
                    "the recorded entity layer switch moved its twin");
        } finally {
            if (pb != null) pb.halt();
            shards.destroy(stage);
            prop.remove();
            mover.remove();
            actor.player.remove();
        }
    }

    @Test
    void crossClockLayersDumpTheirDiffsOnTheirOwnClock() {
        ExecutionDomain a = ExecutionDomain.create(), b = ExecutionDomain.create();
        Shard stage = new Shard(instance, a);
        Shard foreign = new Shard(stage, b); // split tree: the layer rides its own clock (caller's-risk config)
        foreign.setBlock(new Vec(6, 70, 6), Block.EMERALD_BLOCK);
        Playback pb = null;
        try {
            Recording rec = Replay.record(stage);
            // the dump hops onto b's clock, its emits hop back onto a's - both must land before the stop
            await("the foreign layer's t0 dump lands", () -> rec.counts().getOrDefault("replay:block", 0) >= 1);
            long landed = rec.tick();
            await("the recording outlives the dump's stamp", () -> rec.tick() >= landed + 2);
            // a split-tree (cross-domain) recording is footage like any other: it must transcode losslessly
            byte[] file = PolarShardCodec.toShardFile(PolarShardCodec.fromShardFile(rec.stop()));

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, file);
            Block seen = null; // free-running recording clock: the dump's stamp is wall-time-dependent
            for (int i = 0; i < 60 && seen == null && pb.active(); i++) {
                tick();
                Shard layer = pb.layer(1);
                if (layer != null) seen = layer.diff().get(new Vec(6, 70, 6));
            }
            assertEquals(Block.EMERALD_BLOCK, seen,
                    "the cross-clock layer's diff snapshotted on its own clock into the file");
        } finally {
            if (pb != null) pb.halt();
            a.retire();
            b.retire();
        }
    }

    @Test
    void layersCreatedAndDestroyedMidRecordingReplayTheirLifecycle() {
        ShardManager shards = new ShardManager();
        Shard stage = shards.create(instance, "replay-life-stage");
        Playback pb = null;
        try {
            Recording rec = Replay.record(stage);
            tick(); // tick 1
            Shard mid = shards.child(stage, "replay-life-mid");
            tick(); // tick 2: the sweep registers the newcomer
            tick(); // tick 3
            shards.destroy(mid);
            tick(); // tick 4: the sweep reports it gone
            tick(); // tick 5
            byte[] file = rec.stop();

            Shard theater = new Shard(instance);
            pb = Replay.play(theater, file);
            assertEquals(2, pb.counts().get("replay:layer"), "one create + one destroy");
            tick();
            tick();
            Shard risen = pb.layer(1);
            assertNotNull(risen, "the mid-recording layer rose on its recorded tick");
            tick();
            tick();
            assertNull(pb.layer(1), "and fell on its recorded tick");
            assertTrue(risen.diff().isEmpty(), "dropped layers reset to empty husks");
        } finally {
            if (pb != null) pb.halt();
            shards.destroy(stage);
        }
    }
}
