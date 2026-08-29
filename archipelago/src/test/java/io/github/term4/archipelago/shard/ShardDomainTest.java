package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.DetectorEntity;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Shard <-> ExecutionDomain binding: marked entities follow the shard's domain, unmarked stay server-ticked. */
class ShardDomainTest extends HeadlessServerTest {

    @Test
    void domainShardsTickMarkedEntitiesOnTheirDomainClock() {
        ExecutionDomain domain = ExecutionDomain.create();
        DetectorEntity marked = new DetectorEntity();
        Entity plain = new Entity(EntityType.ARMOR_STAND);
        try {
            Shard shard = new Shard(instance, domain);
            shard.spawn(marked, new Pos(0.5, 70, 0.5)).join();
            shard.spawn(plain, new Pos(2.5, 70, 2.5)).join();
            await("the marked entity ticks on the shard's clock",
                    () -> marked.ticks.get() >= 3 && marked.thread.startsWith("shard-domain:"));
            assertNull(ExecutionDomain.of(plain), "unmarked classes stay server-ticked (no silent double-tick risk)");
        } finally {
            marked.remove();
            plain.remove();
            domain.retire();
        }
    }

    @Test
    void transferMovesDomainOwnershipWithTheShard() {
        ExecutionDomain da = ExecutionDomain.create(), db = ExecutionDomain.create();
        DetectorEntity e = new DetectorEntity();
        try {
            Shard a = new Shard(instance, da);
            Shard b = new Shard(instance, db);
            Shard plain = new Shard(instance);

            a.spawn(e, new Pos(0.5, 70, 0.5)).join();
            await("a's clock drives it", () -> e.ticks.get() >= 3 && e.thread.startsWith("shard-domain:"));
            String aThread = e.thread;

            b.transfer(e);
            await("b's clock drives it after the transfer",
                    () -> ExecutionDomain.of(e) == db && e.thread.startsWith("shard-domain:") && !e.thread.equals(aThread));

            plain.transfer(e);
            await("a domainless shard hands it back to the server", () -> ExecutionDomain.of(e) == null);
        } finally {
            e.remove();
            da.retire();
            db.retire();
        }
    }

    @Test
    void scheduleNextTickRunsOnTheDomainThread() {
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Shard shard = new Shard(instance, domain);
            CompletableFuture<String> ran = new CompletableFuture<>();
            shard.scheduleNextTick(w -> ran.complete(Thread.currentThread().getName()));
            assertTrue(ran.orTimeout(4, TimeUnit.SECONDS).join().startsWith("shard-domain:"));
        } finally {
            domain.retire();
        }
    }

    @Test
    void snapshotsAreTickAtomicAgainstDomainBatchWrites() {
        ExecutionDomain d = ExecutionDomain.create();
        try {
            Shard shard = new Shard(instance, d);
            List<Point> plot = new ArrayList<>();
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) plot.add(new Vec(x, 90, z));
            }
            AtomicBoolean run = new AtomicBoolean(true);
            AtomicLong batches = new AtomicLong();
            // one whole-plot batch per domain tick, alternating state - a torn snapshot would mix the two
            class Writer implements Consumer<MechanicsWorld> {
                @Override public void accept(MechanicsWorld w) {
                    if (!run.get()) return;
                    Block state = batches.getAndIncrement() % 2 == 0 ? Block.STONE : Block.DIRT;
                    Map<Point, Block> batch = new HashMap<>();
                    for (Point p : plot) batch.put(p, state);
                    shard.setBlocks(batch);
                    shard.scheduleNextTick(this);
                }
            }
            shard.scheduleNextTick(new Writer());
            await("the writer runs on the domain clock", () -> batches.get() >= 2 && d.ticks() >= 2);
            for (int i = 0; i < 12; i++) {
                Map<Point, Block> snap = (i % 2 == 0 ? shard : shard.fork()).diff();
                assertEquals(64, snap.size());
                assertEquals(1, snap.values().stream().distinct().count(), "a snapshot never captures half a batch");
            }
            run.set(false);
        } finally {
            d.retire();
        }
    }

    @Test
    void ticksAndOnTickRideTheShardsOwnClock() {
        ExecutionDomain domain = ExecutionDomain.create();
        try {
            Shard shard = new Shard(instance, domain);
            List<Long> seen = Collections.synchronizedList(new ArrayList<>());
            String[] thread = new String[1];
            Runnable stop = shard.onTick(t -> {
                thread[0] = Thread.currentThread().getName();
                seen.add(t);
            });
            await("the hook rides the shard's clock", () -> seen.size() >= 3);
            stop.run();
            assertTrue(thread[0].startsWith("shard-domain:"), thread[0]);
            List<Long> snapshot;
            synchronized (seen) {
                snapshot = new ArrayList<>(seen);
            }
            for (int i = 1; i < snapshot.size(); i++) {
                assertEquals(snapshot.get(i - 1) + 1, snapshot.get(i), "hooks see consecutive shard ticks");
            }
            assertTrue(shard.ticks() >= snapshot.get(snapshot.size() - 1), "ticks() is the clock the hook sees");
            int stopped = seen.size();
            LockSupport.parkNanos(150_000_000);
            assertTrue(seen.size() <= stopped + 1, "the handle removed the hook"); // <= : one fire may be in flight
        } finally {
            domain.retire();
        }
    }

    @Test
    void destroyFromAForeignClockHandsOffInsteadOfThrowing() {
        // a domain player's commands run on THEIR clock: /shard destroy <other> lands exactly here
        ExecutionDomain da = ExecutionDomain.create(), db = ExecutionDomain.create();
        ShardManager manager = new ShardManager();
        DetectorEntity e = new DetectorEntity();
        try {
            Shard a = manager.create(instance, "foreign-a", da);
            Shard b = manager.create(instance, "foreign-b", db);
            b.spawn(e, new Pos(0.5, 70, 0.5)).join();
            await("b ticks its entity", () -> e.ticks.get() >= 2);

            da.execute(() -> manager.destroy(b));
            await("the destroy handed off to b's own clock and completed", () -> manager.byName("foreign-b") == null);
            assertTrue(e.isRemoved(), "shard entities died with the shard");
            assertEquals(a, manager.byName("foreign-a"), "the caller's shard is untouched");
        } finally {
            e.remove();
            Shard a = manager.byName("foreign-a");
            if (a != null) manager.destroy(a);
            da.retire();
            db.retire();
        }
    }

    @Test
    void reclockMovesTheShardAcrossClocksAndOutOfStasis() {
        ExecutionDomain da = ExecutionDomain.create(), db = ExecutionDomain.create();
        DetectorEntity e = new DetectorEntity();
        List<String> hookThreads = Collections.synchronizedList(new ArrayList<>());
        try {
            Shard shard = new Shard(instance, da);
            Runnable stopHook = shard.onTick(t -> hookThreads.add(Thread.currentThread().getName()));
            shard.spawn(e, new Pos(0.5, 70, 0.5)).join();
            await("a drives entity and hooks", () -> e.ticks.get() >= 2 && !hookThreads.isEmpty());
            String aThread = e.thread;

            shard.domain(db); // live re-clock: entities and hooks follow
            await("b drives the entity", () -> ExecutionDomain.of(e) == db
                    && e.thread.startsWith("shard-domain:") && !e.thread.equals(aThread));
            await("the hook migrated to b's clock", () -> !hookThreads.isEmpty()
                    && hookThreads.get(hookThreads.size() - 1).equals(e.thread));

            db.retire(); // the domain dies, the shard does NOT: stasis (frozen clock, inline ops)
            await("the entity is back on the server", () -> ExecutionDomain.of(e) == null);
            shard.setBlock(new Pos(1.5, 70, 1.5), Block.STONE); // inline, no throw

            ExecutionDomain fresh = ExecutionDomain.create();
            shard.domain(fresh); // stasis ends by reassignment
            await("the fresh clock revives the shard", () -> ExecutionDomain.of(e) == fresh
                    && e.thread.startsWith("shard-domain:"));
            stopHook.run();

            shard.domain(null); // null = deliberate STASIS: the entity freezes, never reroutes to the server
            await("the entity is parked on a clockless domain", () -> {
                ExecutionDomain owner = ExecutionDomain.of(e);
                return owner != null && !owner.live();
            });
            long parked = e.ticks.get();
            LockSupport.parkNanos(150_000_000);
            assertEquals(parked, e.ticks.get(), "stasis means FROZEN");
            fresh.retire();
        } finally {
            e.remove();
            da.retire();
            db.retire();
        }
    }

    @Test
    void destroyRetiresTheShardsDomain() {
        ExecutionDomain domain = ExecutionDomain.create();
        ShardManager manager = new ShardManager();
        DetectorEntity e = new DetectorEntity();
        try {
            Shard shard = manager.create(instance, "domain-test", domain);
            shard.spawn(e, new Pos(0.5, 70, 0.5)).join();
            await("ticking", () -> e.ticks.get() >= 3);

            manager.destroy(shard); // reset() removes the entity; the domain retires with the shard
            await("the clock went quiet", () -> {
                long before = e.ticks.get();
                LockSupport.parkNanos(150_000_000);
                return e.ticks.get() == before;
            });
            assertEquals(0, shard.entities().size());
            assertNull(manager.byName("domain-test"));
        } finally {
            domain.retire();
        }
    }
}
