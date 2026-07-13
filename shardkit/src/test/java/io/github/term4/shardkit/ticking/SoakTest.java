package io.github.term4.shardkit.ticking;

import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.shard.ShardManager;
import io.github.term4.shardkit.shard.ShardView;
import io.github.term4.shardkit.event.ticking.DomainQuarantineEvent;
import io.github.term4.shardkit.replay.Recording;
import io.github.term4.shardkit.replay.Replay;
import io.github.term4.shardkit.replay.core.CoreChannels;
import io.github.term4.shardkit.testsupport.DetectorEntity;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle storm over live clocks: random transfers, fork/diff snapshots, batch writes, player join/leave
 * churn, recordings and child-layer churn against N shards while a stand-in server tick runs - every entity
 * carries a double-tick detector, every snapshot a torn-batch assert, and the watchdog must stay quiet.
 * Default ~12s; {@code -Dshardkit.soak.ms=45000} for the full soak.
 */
@Tag("slow")
class SoakTest extends HeadlessServerTest {

    private static final long SOAK_MS = Long.getLong("shardkit.soak.ms", 12_000);

    @Test
    void randomChurnNeverDoubleTicksOrTearsSnapshots() throws Exception {
        instance.loadChunk(1100 >> 4, 1100 >> 4).join();
        instance.loadChunk(1200 >> 4, 1200 >> 4).join();
        DomainManager domains = new DomainManager();
        ShardManager shards = new ShardManager();
        AtomicBoolean quarantined = new AtomicBoolean();
        EventNode<Event> watch = EventNode.all("test:soak-quarantine");
        watch.addListener(DomainQuarantineEvent.class, e -> quarantined.set(true));
        MinecraftServer.getGlobalEventHandler().addChild(watch);

        List<Shard> pool = new ArrayList<>();
        List<DetectorEntity> all = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 6; i++) {
            // a mixed pool: transfers constantly cross the server-ticked/domain-ticked boundary
            Shard s = i % 3 == 2 ? shards.create(instance) : shards.create(instance, domains.create().handle);
            pool.add(s);
            for (int j = 0; j < 4; j++) {
                DetectorEntity e = new DetectorEntity();
                all.add(e);
                s.spawn(e, new Pos(1100.5 + i * 4 + j, 80, 1100.5)).join();
            }
        }

        AtomicBoolean run = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();

        // stand-in server tick: the global dispatcher really drains, so a lost hand-off would double-tick
        workers.add(worker(run, failure, () -> {
            MinecraftServer.process().dispatcher().updateAndAwait(System.currentTimeMillis());
            LockSupport.parkNanos(5_000_000);
        }));

        // transfer storm (concurrent rule updates: the ShardView.RULE_LOCK serialization is load-bearing here)
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(1);
            @Override public void run() {
                pool.get(rnd.nextInt(pool.size())).transfer(all.get(rnd.nextInt(all.size())));
                LockSupport.parkNanos(2_000_000);
            }
        }));

        // batch writer + snapshot reader: a batch is all ONE material, so any mixed diff/fork = a torn
        // snapshot. DOMAIN shards only - a plain shard's batches/snapshots are server-thread ops by
        // contract (like vanilla instances); only domain shards marshal internally
        List<Shard> domainPool = pool.stream().filter(s -> s.domain() != null).toList();
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(2);
            int flip;
            @Override public void run() {
                Shard s = domainPool.get(rnd.nextInt(domainPool.size()));
                Block mat = (flip++ & 1) == 0 ? Block.GOLD_BLOCK : Block.DIAMOND_BLOCK;
                Map<Point, Block> batch = new HashMap<>();
                for (int k = 0; k < 24; k++) batch.put(new Vec(1200 + k, 90, 1200), mat);
                s.setBlocks(batch);
                assertUniform(s.diff());
                LockSupport.parkNanos(1_000_000);
            }
        }));

        // fork storm: the snapshot side of the same torn-batch assert
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(3);
            @Override public void run() {
                assertUniform(domainPool.get(rnd.nextInt(domainPool.size())).fork().diff());
                LockSupport.parkNanos(8_000_000);
            }
        }));

        // player churn: join/leave/hop across the pool
        FakePlayer hopper = FakePlayer.connect(instance, new Pos(1102.5, 82, 1102.5), "SoakHopper");
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(4);
            @Override public void run() {
                Shard s = pool.get(rnd.nextInt(pool.size()));
                if (rnd.nextInt(4) == 0) s.leave(hopper.player);
                else s.join(hopper.player);
                LockSupport.parkNanos(4_000_000);
            }
        }));

        // recording storm: sessions ride the domain clocks while every other worker churns the same shards
        // (probe/sampler emits hop cross-thread, event nodes register/unregister, t0 snapshots relay)
        CoreChannels.register();
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(5);
            @Override public void run() {
                Recording rec = Replay.record(domainPool.get(rnd.nextInt(domainPool.size())));
                LockSupport.parkNanos(200_000_000);
                byte[] file = rec.stop();
                if (file.length == 0) throw new AssertionError("empty replay file");
            }
        }));

        // tree storm: child layers appear/vanish and an entity layer-switches on the shared clock while
        // everything else churns; destroying a layer must never retire the clock the parent still rides.
        // The hopper is this worker's own - two threads transferring ONE entity is outside the app contract
        List<DetectorEntity> hoppers = new CopyOnWriteArrayList<>();
        workers.add(worker(run, failure, new Runnable() {
            final Random rnd = new Random(6);
            int seq;
            DetectorEntity rider;
            @Override public void run() {
                Shard parent = domainPool.get(rnd.nextInt(domainPool.size()));
                Shard layer = shards.child(parent, "soak-layer-" + seq++);
                if (rider == null || rider.isRemoved()) {
                    rider = new DetectorEntity();
                    hoppers.add(rider);
                    layer.spawn(rider, new Pos(1205.5, 84, 1205.5));
                } else {
                    layer.transfer(rider);
                }
                layer.setBlock(new Vec(1200 + rnd.nextInt(24), 92, 1200), Block.IRON_BLOCK);
                LockSupport.parkNanos(10_000_000);
                parent.transfer(rider); // the layer switch back, same clock throughout
                shards.destroy(layer);
                LockSupport.parkNanos(5_000_000);
            }
        }));

        try {
            Thread.sleep(SOAK_MS);
        } finally {
            run.set(false);
            for (Thread w : workers) w.join(4000);
        }

        try {
            assertNull(failure.get(), () -> "a churn worker failed: " + failure.get());
            int overlaps = all.stream().mapToInt(e -> e.overlaps.get()).sum()
                    + hoppers.stream().mapToInt(e -> e.overlaps.get()).sum();
            assertEquals(0, overlaps, "an entity was ticked by two threads at once");
            for (DetectorEntity e : all) {
                if (Domain.of(e) == null) continue;
                long before = e.ticks.get();
                await("domain entities still tick after the storm", () -> e.ticks.get() > before);
            }
            assertTrue(!quarantined.get(), "the watchdog stayed quiet through the storm");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(watch);
            for (Shard s : List.copyOf(shards.shards())) shards.destroy(s);
            for (DetectorEntity e : all) e.remove();
            for (DetectorEntity e : hoppers) e.remove();
            hopper.player.remove();
            domains.shutdown();
        }
    }

    private static Thread worker(AtomicBoolean run, AtomicReference<Throwable> failure, Runnable step) {
        return Thread.ofPlatform().start(() -> {
            while (run.get() && failure.get() == null) {
                try {
                    step.run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
        });
    }

    private static void assertUniform(Map<Point, Block> snapshot) {
        Block seen = null;
        for (int k = 0; k < 24; k++) {
            Block b = snapshot.get(new Vec(1200 + k, 90, 1200));
            if (b == null) continue;
            if (seen == null) seen = b;
            else if (seen.stateId() != b.stateId()) {
                throw new AssertionError("torn snapshot: " + seen + " vs " + b);
            }
        }
    }
}
