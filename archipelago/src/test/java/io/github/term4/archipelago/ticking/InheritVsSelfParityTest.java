package io.github.term4.archipelago.ticking;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.attack.AttackEvent;
import io.github.term4.minestommechanics.mechanics.attack.AttackSnapshot;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.mechanics.projectile.types.SplashPotion;
import io.github.term4.minestommechanics.mechanics.projectile.types.Snowball;
import io.github.term4.minestommechanics.presets.vanilla18.Vanilla18;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.ShardBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gameplay-parity gate: each scenario runs once in a plain (server-ticked) shard and once in a domain-bound
 * one, asserting identical outcomes - the whole point of the threading epic is that from inside one shard,
 * nothing changes but the thread. Zombie stand-ins per project scope; the domain runs drive the pipeline through
 * {@link ExecutionDomain#relay} exactly where packet interpretation would.
 */
class InheritVsSelfParityTest extends HeadlessServerTest {

    private static MinestomMechanics mm;
    private static final List<ExecutionDomain> created = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void mechanics() {
        mm = MechanicsBoot.ensure();
        CoreChannels.register();
        ShardBridge.installDomainTicking();
        for (int cx = 62; cx <= 63; cx++) {
            for (int cz = 62; cz <= 63; cz++) instance.loadChunk(cx, cz).join();
        }
    }

    @AfterAll
    static void end() {
        for (ExecutionDomain d : created) d.retire();
    }

    private static ExecutionDomain domain() {
        ExecutionDomain d = ExecutionDomain.create();
        created.add(d);
        return d;
    }

    /** Runs {@code op} where the shard's gameplay runs: inline for a plain shard, on the clock for a domain one. */
    private static <T> T onOwner(Shard shard, Supplier<T> op) {
        ExecutionDomain d = shard.domain();
        return d == null ? op.get() : d.relay(op);
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    private static void driveServer() {
        MinecraftServer.process().dispatcher().updateAndAwait(System.currentTimeMillis());
    }

    private record MeleeOutcome(float health, long invul, Vec kb, float healthAfterEaten) {}

    private MeleeOutcome melee(Shard shard, Pos base) {
        LivingEntity attacker = new LivingEntity(EntityType.ZOMBIE);
        LivingEntity victim = new LivingEntity(EntityType.ZOMBIE);
        shard.spawn(attacker, base).join();
        shard.spawn(victim, base.add(0, 0, 1)).join();
        try {
            victim.setHealth(20f);
            attacker.setItemInMainHand(ItemStack.of(Material.DIAMOND_SWORD));
            AttackEvent.AttackRule rule = Vanilla18.attackRuleset().create(mm.services());
            return onOwner(shard, () -> {
                rule.processAttack(hit(attacker, victim));
                float health = victim.getHealth();
                long invul = DamageSystem.remainingDamageInvul(victim);
                Vec kb = victim.getVelocity();
                rule.processAttack(hit(attacker, victim)); // inside the fresh window: eaten
                return new MeleeOutcome(health, invul, kb, victim.getHealth());
            });
        } finally {
            attacker.remove();
            victim.remove();
        }
    }

    private static AttackEvent hit(LivingEntity attacker, LivingEntity victim) {
        return new AttackEvent(new AttackSnapshot(attacker, victim, Vanilla18.attack()), mm.services());
    }

    @Test
    void meleeHitOutcomesMatchAcrossTickModes() {
        MeleeOutcome inherit = melee(new Shard(instance), new Pos(1000.5, 65, 1010.5));
        MeleeOutcome self = melee(new Shard(instance, domain()), new Pos(1004.5, 65, 1010.5));
        assertTrue(inherit.health() < 20f, "the hit landed");
        assertTrue(inherit.kb().length() > 0, "knockback landed");
        assertEquals(inherit.health(), self.health(), 1e-6f);
        assertEquals(inherit.invul(), self.invul());
        assertEquals(inherit.kb().x(), self.kb().x(), 1e-9);
        assertEquals(inherit.kb().y(), self.kb().y(), 1e-9);
        assertEquals(inherit.kb().z(), self.kb().z(), 1e-9);
        assertEquals(inherit.healthAfterEaten(), self.healthAfterEaten(), 1e-6f, "second hit eaten in both modes");
        assertEquals(inherit.health(), inherit.healthAfterEaten(), 1e-6f);
    }

    @Test
    void meleeHitOutcomesMatchInATreeLayer() {
        MeleeOutcome inherit = melee(new Shard(instance), new Pos(1008.5, 65, 1010.5));
        MeleeOutcome tree = melee(new Shard(new Shard(instance, domain())), new Pos(1012.5, 65, 1010.5));
        assertTrue(inherit.health() < 20f, "the hit landed");
        assertEquals(inherit.health(), tree.health(), 1e-6f);
        assertEquals(inherit.invul(), tree.invul());
        assertEquals(inherit.kb().x(), tree.kb().x(), 1e-9);
        assertEquals(inherit.kb().y(), tree.kb().y(), 1e-9);
        assertEquals(inherit.kb().z(), tree.kb().z(), 1e-9);
        assertEquals(inherit.healthAfterEaten(), tree.healthAfterEaten(), 1e-6f, "second hit eaten in the layer too");
    }

    /** First {@code count} distinct positions of a flight, sampled at tick boundaries (before the step). */
    private List<Pos> flight(Shard shard, Pos spawn, int count) {
        LivingEntity shooter = new LivingEntity(EntityType.ZOMBIE);
        shard.spawn(shooter, spawn.add(0, -1, -2)).join();
        ProjectileSystem ps = new ProjectileSystem(mm, Vanilla18.projectiles());
        var snap = ProjectileSnapshot.of(shooter, Snowball.INSTANCE)
                .withSpawnPos(spawn).withVelocity(new Vec(0, 0.1, 1.0)); // explicit: spread must not enter parity
        List<Pos> samples = new CopyOnWriteArrayList<>();
        ProjectileEntity[] ball = new ProjectileEntity[1];
        ExecutionDomain d = shard.domain();
        try {
            if (d == null) {
                ball[0] = ps.launch(snap);
                assertNotNull(ball[0]);
                await("spawn lands", () -> ball[0].getInstance() != null);
                for (int i = 0; i < count + 6; i++) {
                    samples.add(ball[0].getPosition());
                    driveServer();
                }
            } else {
                Runnable stop = d.onTick(t -> {
                    if (ball[0] != null && ball[0].getInstance() != null) samples.add(ball[0].getPosition());
                });
                try {
                    d.relay(() -> ball[0] = ps.launch(snap));
                    assertNotNull(ball[0]);
                    await("the clock flies it", () -> distinct(samples).size() > count);
                } finally {
                    stop.run();
                }
            }
            List<Pos> path = distinct(samples);
            assertTrue(path.size() > count, "flight long enough: " + path.size());
            return path.subList(0, count);
        } finally {
            if (ball[0] != null) ball[0].remove();
            shooter.remove();
        }
    }

    private static List<Pos> distinct(List<Pos> samples) {
        List<Pos> out = new ArrayList<>();
        for (Pos p : samples) {
            if (out.isEmpty() || !out.get(out.size() - 1).samePoint(p)) out.add(p);
        }
        return out;
    }

    @Test
    void projectileFlightIsIdenticalOnBothClocks() {
        List<Pos> inherit = flight(new Shard(instance), new Pos(1000.5, 66, 996.5), 8);
        List<Pos> self = flight(new Shard(instance, domain()), new Pos(1004.5, 66, 996.5), 8);
        for (int i = 0; i < 8; i++) {
            Pos a = inherit.get(i), b = self.get(i);
            assertEquals(a.x() - 1000.5, b.x() - 1004.5, 1e-9, "step " + i + " x");
            assertEquals(a.y(), b.y(), 1e-9, "step " + i + " y");
            assertEquals(a.z(), b.z(), 1e-9, "step " + i + " z");
        }
        assertTrue(inherit.get(7).z() > inherit.get(0).z() + 3, "it actually flew");
    }

    /** Throws a swiftness splash straight down next to a shard victim; returns the applied SPEED duration. */
    private int splash(Shard shard, Pos at) {
        LivingEntity thrower = new LivingEntity(EntityType.ZOMBIE);
        LivingEntity victim = new LivingEntity(EntityType.ZOMBIE);
        // pinned in place: the INHERIT drive ticks stand-ins (they'd fall), the SELF run never does - the
        // geometry must not depend on the harness
        thrower.setNoGravity(true);
        victim.setNoGravity(true);
        shard.spawn(thrower, at.withPitch(90)).join();
        shard.spawn(victim, at.add(2, 0, 0)).join();
        ItemStack potion = ItemStack.of(Material.SPLASH_POTION)
                .with(DataComponents.POTION_CONTENTS, new PotionContents(PotionType.SWIFTNESS));
        ProjectileSystem ps = new ProjectileSystem(mm, Vanilla18.projectiles());
        var snap = ProjectileSnapshot.of(thrower, SplashPotion.INSTANCE).withItem(potion)
                .withSpawnPos(at.add(0, 1.62, 0).withPitch(90))
                .withVelocity(new Vec(0, -0.5, 0)); // explicit: vanilla launch spread must not enter parity
        ProjectileEntity[] pot = new ProjectileEntity[1];
        try {
            if (shard.domain() == null) {
                pot[0] = ps.launch(snap);
                assertNotNull(pot[0]);
                await("spawn lands", () -> pot[0].getInstance() != null);
                for (int i = 0; i < 40 && effectDuration(victim) < 0; i++) driveServer();
            } else {
                shard.domain().relay(() -> pot[0] = ps.launch(snap));
                assertNotNull(pot[0]);
                await("the clock lands the splash", () -> effectDuration(victim) >= 0);
            }
            int duration = effectDuration(victim);
            assertTrue(duration > 0, "the splash reached the victim");
            return duration;
        } finally {
            if (pot[0] != null) pot[0].remove();
            thrower.remove();
            victim.remove();
        }
    }

    private static int effectDuration(LivingEntity e) {
        return e.getActiveEffects().stream().filter(t -> t.potion().effect() == PotionEffect.SPEED)
                .mapToInt(t -> t.potion().duration()).findFirst().orElse(-1);
    }

    @Test
    void splashPotionsLandIdenticallyOnBothClocks() {
        int inherit = splash(new Shard(instance), new Pos(1012.5, 65, 1012.5));
        int self = splash(new Shard(instance, domain()), new Pos(1016.5, 65, 1012.5));
        assertEquals(inherit, self, "distance-scaled duration must not depend on the clock");
    }

    private byte[] record(Shard stage, Pos from, Pos to) {
        Entity ball = new Entity(EntityType.SNOWBALL);
        stage.spawn(ball, from).join();
        Recording rec = Replay.record(stage);
        try {
            if (stage.domain() == null) {
                tick();
                ball.teleport(to).join();
                tick();
                tick();
            } else {
                await("sampler saw the spawn", () -> rec.tick() >= 2);
                ball.teleport(to).join();
                long t = rec.tick();
                await("sampler saw the move", () -> rec.tick() >= t + 3);
            }
            return rec.stop();
        } finally {
            if (rec.active()) rec.stop();
            ball.remove();
        }
    }

    /** Plays {@code file} in {@code theater} and returns the twin's settled position. */
    private Pos playbackEnd(Shard theater, byte[] file, Pos expected) {
        Playback pb = Replay.play(theater, file);
        try {
            if (theater.domain() == null) {
                for (int i = 0; i < 20 && !settled(pb, expected); i++) tick();
            }
            await("the twin lands on the recorded track", () -> settled(pb, expected));
            return pb.entity(0).getPosition();
        } finally {
            pb.halt();
        }
    }

    private static boolean settled(Playback pb, Pos expected) {
        Entity twin = pb.entity(0);
        return twin != null && twin.getPosition().samePoint(expected);
    }

    @Test
    void recordAndPlaybackAgreeAcrossTickModes() {
        Pos from = new Pos(1002.5, 70, 1002.5), to = new Pos(1005.5, 70, 1005.5);
        byte[] inheritFile = record(new Shard(instance), from, to);
        byte[] selfFile = record(new Shard(instance, domain()), from, to);

        // op parity: same channels, same op counts (op ticks differ - the clocks are free-running)
        var inheritCounts = countsOf(inheritFile);
        var selfCounts = countsOf(selfFile);
        assertEquals(inheritCounts, selfCounts, "both modes record the same ops");

        // cross-play: each file replays in the OTHER mode's theater
        assertTrue(playbackEnd(new Shard(instance, domain()), inheritFile, to).samePoint(to));
        assertTrue(playbackEnd(new Shard(instance), selfFile, to).samePoint(to));
    }

    private java.util.Map<String, Integer> countsOf(byte[] file) {
        Playback pb = Replay.play(new Shard(instance), file);
        try {
            return pb.counts();
        } finally {
            pb.halt();
        }
    }
}
