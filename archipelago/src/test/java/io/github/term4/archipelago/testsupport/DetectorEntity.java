package io.github.term4.archipelago.testsupport;

import io.github.term4.archipelago.ticking.DomainEntity;
import net.minestom.server.entity.EntityType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Double-tick detector: counts EFFECTIVE ticks (the ownership gate bails first), records the driving thread,
 * and flags concurrent tick entry - the overlap no storm may ever produce. {@code parkNanos} widens the
 * overlap window for race hunts.
 */
public class DetectorEntity extends DomainEntity {

    public final AtomicInteger overlaps = new AtomicInteger();
    public final AtomicLong ticks = new AtomicLong();
    public volatile String thread = "";
    private final AtomicBoolean inTick = new AtomicBoolean();
    private final long parkNanos;

    public DetectorEntity() {
        this(0);
    }

    public DetectorEntity(long parkNanos) {
        super(EntityType.ARMOR_STAND);
        setNoGravity(true);
        this.parkNanos = parkNanos;
    }

    @Override public void tick(long time) {
        if (foreignDriver()) return;
        if (!inTick.compareAndSet(false, true)) {
            overlaps.incrementAndGet();
            return;
        }
        try {
            thread = Thread.currentThread().getName();
            ticks.incrementAndGet();
            if (parkNanos > 0) LockSupport.parkNanos(parkNanos);
            super.tick(time);
        } finally {
            inTick.set(false);
        }
    }
}
