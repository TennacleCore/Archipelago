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
import io.github.term4.polyp.world.WorldPolicy;
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

    private ShardWorlds() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        MechanicsWorld.resolver(new MechanicsWorld.Resolver() {
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
        // block-anchored FX follow the shard block view
        WorldPolicy.set(new WorldPolicy() {
            @Override public boolean seesBlocksOf(@NotNull Player viewer, @NotNull MechanicsWorld world) {
                Shard shard = world instanceof Shard s ? s : null;
                return ShardView.of(viewer).blocks() == shard && viewer.getInstance() == world.instance();
            }
        });
        // per-tick world systems (TickSystem hooks) follow a domain-bound shard onto its clock
        // (PRE_DISPATCH ordering holds: Domain.tick runs hooks before entities)
        EventNode<Event> node = EventNode.all("archipelago:worlds");
        node.addListener(ShardCreateEvent.class, e -> hookDomainTick(e.shard()));
        node.addListener(ShardDestroyEvent.class, e -> {
            Runnable stop = e.shard().getTag(TICK_HOOK);
            if (stop != null) stop.run();
        });
        // crossing a CLOCK boundary invalidates tick stamps (i-frames, sprint windows, motion clocks) - reset
        // them; same-clock layer switches stay combat-continuous and plain-shard hops keep the instance clock.
        // Events fire after the swap, so the entity's binding is the settled truth for all three.
        node.addListener(ShardJoinEvent.class, e -> clockGate(e.player()));
        node.addListener(ShardLeaveEvent.class, e -> clockGate(e.player()));
        node.addListener(ShardTransferEvent.class, e -> clockGate(e.entity()));
        // a re-clock changes every member's/entity's clock with no join/transfer - gate them all. It is also the
        // moment a PLAIN shard can become domain-bound: its entities leave the main pass, so the world pass must exist.
        node.addListener(ShardReclockEvent.class, e -> {
            hookDomainTick(e.shard());
            e.shard().members().forEach(ShardWorlds::clockGate);
            e.shard().entities().forEach(ShardWorlds::clockGate);
        });
        MinecraftServer.getGlobalEventHandler().addChild(node);
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
