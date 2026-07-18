package io.github.term4.archipelago.ticking;

import io.github.term4.minestommechanics.mechanics.damage.DamageSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.minestommechanics.platform.player.OptimizedPlayer;
import io.github.term4.minestommechanics.util.tick.TickContext;
import io.github.term4.minestommechanics.util.tick.TickPhase;
import io.github.term4.minestommechanics.util.tick.TickSystem;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.Test;
import test.ShardBridge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mm tick generalization across the bridge: a domain shard's world pass runs mm's registered work on the
 * shard's clock, the main pass leaves its entities alone, and covered projectile/drop classes tick on the clock.
 */
class DomainTickBridgeTest extends HeadlessServerTest {

    private record Pass(String thread, boolean owned) {}

    @Test
    void mmTickWorkFollowsTheShardOntoItsClock() {
        ShardBridge.installDomainTicking();
        MinecraftServer.getConnectionManager().setPlayerProvider(OptimizedPlayer::new);
        ExecutionDomain domain = ExecutionDomain.create();
        ShardManager shards = new ShardManager();
        List<Pass> worldPasses = new CopyOnWriteArrayList<>();
        Player[] joined = new Player[1];
        MechanicsWorld[] world = new MechanicsWorld[1]; // THIS shard's passes only: other tests' clocks may linger
        TickSystem.Registration probe = TickSystem.register(TickPhase.PRE_DISPATCH, ctx -> {
            Player p = joined[0];
            if (ctx.external() && ctx.world() == world[0] && p != null) {
                worldPasses.add(new Pass(Thread.currentThread().getName(), ctx.owns(p)));
            }
        });
        Shard shard = shards.create(instance, "ticksys", domain);
        world[0] = ShardBridge.adapt(shard);
        Player player = FakePlayer.connect(instance, new Pos(0.5, 70, 0.5), "TickSteve").player;
        DroppedItemEntity drop = new DroppedItemEntity(ItemStack.of(Material.STONE), null);
        try {
            joined[0] = player;
            shard.join(player);
            await("the world pass owns the player on the shard's clock", () -> worldPasses.stream()
                    .anyMatch(p -> p.owned() && p.thread().startsWith("shard-domain:")));
            assertTrue(MechanicsWorld.externalTick(player) >= 0, "the player's clock is the domain's");
            assertFalse(new TickContext(MechanicsWorld.of(instance), 1, 20, false).owns(player),
                    "the main pass leaves domain players alone");

            shard.spawn(drop, new Pos(2.5, 80, 2.5)).join();
            await("the drop is domain-owned (covered class)", () -> ExecutionDomain.of(drop) == shard.domain());
            double y0 = drop.getPosition().y();
            await("only the clock ticks it headless - it falls", () -> drop.getPosition().y() < y0 - 0.5);

            shards.destroy(shard); // retires the domain and unhooks the world pass
            LockSupport.parkNanos(100_000_000); // an in-flight tick may still complete (retire = stop AFTER the final tick)
            worldPasses.clear();
            LockSupport.parkNanos(200_000_000);
            assertTrue(worldPasses.isEmpty(), "no world pass after destroy");
        } finally {
            probe.cancel();
            drop.remove();
            player.remove();
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
            domain.retire();
        }
    }

    /** A PLAIN shard adopted onto a live domain mid-life must gain the mm world pass - its entities leave the main pass the moment they become domain-owned. */
    @Test
    void plainShardReclockedOntoADomainGainsTheWorldPass() {
        ShardBridge.installDomainTicking();
        ExecutionDomain domain = ExecutionDomain.create();
        ShardManager shards = new ShardManager();
        List<String> passes = new CopyOnWriteArrayList<>();
        MechanicsWorld[] world = new MechanicsWorld[1];
        TickSystem.Registration probe = TickSystem.register(TickPhase.PRE_DISPATCH, ctx -> {
            if (ctx.external() && ctx.world() == world[0]) passes.add(Thread.currentThread().getName());
        });
        Shard shard = shards.create(instance, "lateclock"); // plain: no domain at create
        world[0] = ShardBridge.adapt(shard);
        try {
            LockSupport.parkNanos(150_000_000);
            assertTrue(passes.isEmpty(), "no world pass while plain (the main pass owns everything)");
            shard.domain(domain);
            await("the world pass appears on the domain clock", () -> passes.stream()
                    .anyMatch(t -> t.startsWith("shard-domain:")));
        } finally {
            probe.cancel();
            shards.destroy(shard);
            domain.retire();
        }
    }

    @Test
    void joiningADomainShardResetsTickStampedCombatState() {
        ShardBridge.installDomainTicking();
        MinecraftServer.getConnectionManager().setPlayerProvider(OptimizedPlayer::new);
        var services = MechanicsBoot.ensure().services();
        ShardManager shards = new ShardManager();
        Shard shard = shards.create(instance, ExecutionDomain.create());
        Player player = FakePlayer.connect(instance, new Pos(6.5, 70, 6.5), "RebaseSteve").player;
        try {
            services.damage().apply(DamageSnapshot.of(player, ProjectileDamage.INSTANCE).withAmount(0f));
            assertTrue(DamageSystem.remainingDamageInvul(player) > 0, "the hit opened a window");
            shard.join(player); // instance clock -> domain clock: the stamp is meaningless now
            assertEquals(0, DamageSystem.remainingDamageInvul(player), "a mid-window domain join resets i-frames");
        } finally {
            shards.destroy(shard);
            player.remove();
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
        }
    }

    @Test
    void intraTreeLayerSwitchesKeepCombatState() {
        ShardBridge.installDomainTicking();
        MinecraftServer.getConnectionManager().setPlayerProvider(OptimizedPlayer::new);
        var services = MechanicsBoot.ensure().services();
        ShardManager shards = new ShardManager();
        ExecutionDomain domain = ExecutionDomain.create();
        Shard parent = shards.create(instance, "layer-p", domain);
        Shard child = shards.child(parent, "layer-c"); // same clock
        Shard elsewhere = shards.create(instance, "layer-x", ExecutionDomain.create());
        Player player = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "LayerSteve").player;
        try {
            parent.join(player);
            await("the clock adopts the player", () -> ExecutionDomain.of(player) != null);
            domain.relay(() -> {
                services.damage().apply(DamageSnapshot.of(player, ProjectileDamage.INSTANCE).withAmount(0f));
                return null;
            });
            assertTrue(DamageSystem.remainingDamageInvul(player) > 0, "the hit opened a window");

            child.join(player); // dimension-hop gameplay: same clock, the stamps stay valid
            assertTrue(DamageSystem.remainingDamageInvul(player) > 0,
                    "an intra-tree layer switch keeps combat state");

            elsewhere.join(player); // a REAL clock change still resets
            assertEquals(0, DamageSystem.remainingDamageInvul(player), "a cross-clock move resets i-frames");
        } finally {
            shards.destroy(parent);
            shards.destroy(elsewhere);
            player.remove();
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
        }
    }

    @Test
    void coLocatedShardPassesNeverCrossOwnership() {
        ShardBridge.installDomainTicking();
        MinecraftServer.getConnectionManager().setPlayerProvider(OptimizedPlayer::new);
        ShardManager shards = new ShardManager();
        // per member: every clock thread whose EXTERNAL pass claimed it - mm systems iterating global
        // collections (hit queues, stuck arrows, digs) act on exactly the members a pass owns
        Map<String, Set<String>> claimedBy = new ConcurrentHashMap<>();
        List<Player> members = new CopyOnWriteArrayList<>();
        TickSystem.Registration probe = TickSystem.register(TickPhase.PRE_DISPATCH, ctx -> {
            if (!ctx.external()) return;
            for (Player p : members) {
                if (ctx.owns(p)) claimedBy.computeIfAbsent(p.getUsername(), k -> ConcurrentHashMap.newKeySet())
                        .add(Thread.currentThread().getName());
            }
        });
        Shard a = shards.create(instance, "coloc-a", ExecutionDomain.create());
        Shard b = shards.create(instance, "coloc-b", ExecutionDomain.create());
        Player pa = FakePlayer.connect(instance, new Pos(0.5, 70, 0.5), "ColocA").player;
        Player pb = FakePlayer.connect(instance, new Pos(2.5, 70, 2.5), "ColocB").player;
        try {
            a.join(pa);
            b.join(pb);
            members.add(pa);
            members.add(pb);
            String threadA = a.domain().metrics().threadName(), threadB = b.domain().metrics().threadName();
            await("both shards' passes claimed their member", () -> claimedBy.containsKey("ColocA") && claimedBy.containsKey("ColocB"));
            LockSupport.parkNanos(300_000_000); // many passes on both clocks - any cross-claim would land here
            assertEquals(Set.of(threadA), claimedBy.get("ColocA"), "only a's clock ever owns a's member");
            assertEquals(Set.of(threadB), claimedBy.get("ColocB"), "only b's clock ever owns b's member");
        } finally {
            probe.cancel();
            shards.destroy(a);
            shards.destroy(b);
            pa.remove();
            pb.remove();
            MinecraftServer.getConnectionManager().setPlayerProvider(null);
        }
    }
}
