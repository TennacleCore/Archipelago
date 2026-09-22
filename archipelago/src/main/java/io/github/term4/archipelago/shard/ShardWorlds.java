package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.event.ShardCreateEvent;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.ShardJoinEvent;
import io.github.term4.archipelago.event.ShardLeaveEvent;
import io.github.term4.archipelago.event.ShardReclockEvent;
import io.github.term4.archipelago.event.ShardTransferEvent;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.util.tick.TickSystem;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plugs shards into the {@link MechanicsWorld} seam: bindings resolve to the shard, client-parity checks collide
 * against the viewed block state, block-anchored FX reach shard watchers, and domain-bound entities/systems follow
 * their shard's clock. Install once at boot, before players join.
 */
public final class ShardWorlds {

    private static final Tag<Runnable> TICK_HOOK = Tag.Transient("archipelago:world-tick-hook");
    private static final Tag<ExecutionDomain> LAST_CLOCK = Tag.Transient("archipelago:last-clock");
    private static boolean installed;
    private static EventNode<Event> node;
    private static MechanicsWorld.Resolver displaced;

    private ShardWorlds() {}

    /** Detaches the world seam: the shard hooks go, and the resolver stops answering for a dead install. */
    public static synchronized void uninstall() {
        if (!installed) return;
        installed = false;
        EventNode<Event> attached = node;
        node = null;
        if (attached != null) MinecraftServer.getGlobalEventHandler().removeChild(attached);
        // resolver() hands back what it displaced and takes no null - put the previous one back rather than
        // guessing at the default, since this install may not have been the last
        if (displaced != null) MechanicsWorld.resolver(displaced);
        displaced = null;
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        displaced = MechanicsWorld.resolver(new MechanicsWorld.Resolver() {
            @Override public @Nullable MechanicsWorld resolve(@NotNull Entity entity) {
                return entity.getTag(MechanicsWorld.ENTITY_TAG);
            }

            // client-parity checks (movement, eye, crack) collide against the BLOCK VIEW: a spectator
            // walks the shard's block state while staying unbound (never gameplay-targeted)
            @Override public @Nullable MechanicsWorld viewedBlocks(@NotNull Player player) {
                return ShardView.of(player).blocks();
            }

            @Override public boolean externallyTicked(@NotNull Entity entity) {
                return ExecutionDomain.of(entity) != null;
            }

            @Override public long externalTick(@NotNull Entity entity) {
                ExecutionDomain domain = ExecutionDomain.of(entity);
                return domain != null ? domain.ticks() : -1;
            }

            @Override public boolean ownsCurrentTick(@NotNull Entity entity) {
                ExecutionDomain domain = ExecutionDomain.of(entity);
                return domain == null || domain.onThread();
            }
        });
        // entity visibility and block-anchored FX follow the shard view. A bare seesBlocksOf override here once
        // reverted sees() to binding equality - every unbound spectator went blind to bound players.
        ShardView.installDefaultPolicy();
        // per-tick world systems (TickSystem hooks) follow a domain-bound shard onto its clock
        // (PRE_DISPATCH ordering holds: Domain.tick runs hooks before entities)
        EventNode<Event> fresh = EventNode.all("archipelago:worlds");
        // a bare shard (a theater layer, an unregistered fork) fires no create event, and its entities would
        // be ticked by the clock with no registered tickable visiting them
        Shard.onDomainBound = ShardWorlds::hookDomainTick;
        fresh.addListener(ShardCreateEvent.class, e -> hookDomainTick(e.shard()));
        fresh.addListener(ShardDestroyEvent.class, e -> {
            Runnable stop = e.shard().getTag(TICK_HOOK);
            if (stop != null) stop.run();
        });
        // crossing a CLOCK boundary invalidates tick stamps (i-frames, sprint windows, motion clocks) - reset
        // them; same-clock layer switches stay combat-continuous and plain-shard hops keep the instance clock.
        // Events fire after the swap, so the entity's binding is the settled truth for all three.
        fresh.addListener(ShardJoinEvent.class, e -> clockGate(e.player()));
        fresh.addListener(ShardLeaveEvent.class, e -> clockGate(e.player()));
        fresh.addListener(ShardTransferEvent.class, e -> clockGate(e.entity()));
        // a re-clock changes every member's/entity's clock with no join/transfer - gate them all. It is also the
        // moment a PLAIN shard can become domain-bound: its entities leave the main pass, so the world pass must exist.
        fresh.addListener(ShardReclockEvent.class, e -> {
            hookDomainTick(e.shard());
            e.shard().members().forEach(ShardWorlds::clockGate);
            e.shard().entities().forEach(ShardWorlds::clockGate);
        });
        MinecraftServer.getGlobalEventHandler().addChild(fresh);
        node = fresh;
    }

    /** One tick hook per domain-bound shard (plain shards ride the main pass); survives later re-clocks with the driver. */
    private static void hookDomainTick(Shard shard) {
        if (shard.domain() == null || shard.getTag(TICK_HOOK) != null) return;
        shard.setTag(TICK_HOOK, shard.onTick(t -> TickSystem.tickWorld(shard, t)));
    }

    private static void clockGate(Entity entity) {
        ExecutionDomain now = entity.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard s ? s.domain() : null;
        ExecutionDomain last = entity.getTag(LAST_CLOCK);
        if (now == last || (now != null && last != null && now.sharesClock(last))) return;
        TickSystem.clockChanged(entity);
        if (now == null) entity.removeTag(LAST_CLOCK);
        else entity.setTag(LAST_CLOCK, now);
    }
}
