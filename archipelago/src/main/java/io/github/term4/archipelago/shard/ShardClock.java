package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * A shard's clock: what tick it is on, the hooks riding that tick, and getting onto its thread. One underlying
 * registration drives every hook, so a re-clock swaps one thing rather than re-registering each.
 *
 * <p>Split out of {@link Shard} because three kinds of thread meet here - Minestom's partitions, a domain's own
 * clock, and whatever thread a packet arrived on - and that seam is worth being able to read on its own.
 *
 * <p>It locks on the SHARD, not on itself. {@code Shard.domain(...)} swaps the domain and the driver in one
 * critical section, and {@link #onTick} must not slip between those two; keeping one monitor preserves exactly
 * the behaviour that was here before the split. Java monitors are reentrant, so {@link #rebind} nesting inside
 * that block is fine.
 */
final class ShardClock {

    private final Shard shard;
    private final List<LongConsumer> hooks = new CopyOnWriteArrayList<>();

    /** Removal handle of the one underlying registration driving {@link #hooks} on the current clock. */
    private @Nullable Runnable driver;

    ShardClock(Shard shard) {
        this.shard = shard;
    }

    /** The shard's tick: its domain's, or the base instance's age when server-ticked. */
    long ticks() {
        ExecutionDomain d = shard.domain;
        return d != null ? d.ticks() : shard.base.getWorldAge();
    }

    Runnable onTick(@NotNull LongConsumer hook) {
        synchronized (shard) {
            hooks.add(hook);
            if (driver == null) driver = start();
        }
        return () -> {
            synchronized (shard) {
                if (!hooks.remove(hook)) return;
                // last hook out stops the driver - otherwise the scheduler task retains the shard forever
                if (hooks.isEmpty() && driver != null) {
                    driver.run();
                    driver = null;
                }
            }
        };
    }

    /** Moves the one registration onto the shard's current domain. Call inside the shard's own lock. */
    void rebind() {
        synchronized (shard) {
            if (driver == null) return;
            driver.run();
            driver = start();
        }
    }

    <T> T relay(@NotNull Supplier<T> op) {
        ExecutionDomain d = shard.domain;
        return d == null ? op.get() : d.relay(op);
    }

    void onOwner(@NotNull Runnable op) {
        ExecutionDomain d = shard.domain;
        if (d != null && !d.onThread()) d.execute(op);
        else op.run();
    }

    // one underlying registration on the CURRENT clock fans out to the shard's hooks; swapped on re-clock
    private Runnable start() {
        ExecutionDomain d = shard.domain;
        if (d != null) return d.onTick(this::fire);
        Task task = MinecraftServer.getSchedulerManager().scheduleTask(() -> fire(ticks()),
                TaskSchedule.tick(1), TaskSchedule.tick(1));
        return task::cancel;
    }

    private void fire(long tick) {
        for (LongConsumer hook : hooks) hook.accept(tick);
    }
}
