package io.github.term4.shardkit.shard;

import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import io.github.term4.shardkit.world.WorldPolicy;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-world gameplay gating: same-world by default, reach-through via a replaced {@link WorldPolicy}. */
class WorldPolicyTest extends HeadlessServerTest {

    @Test
    void defaultPolicyIsSameWorld() {
        Shard shard = new Shard(instance);
        LivingEntity inShard = new LivingEntity(EntityType.ZOMBIE);
        shard.spawn(inShard, new Pos(20, 65, 20)).join();
        LivingEntity outsideA = zombie(new Pos(20, 65, 22));
        LivingEntity outsideB = zombie(new Pos(20, 65, 24));
        try {
            assertFalse(WorldPolicy.canAffect(outsideA, inShard), "main-world actors don't reach shard members");
            assertFalse(WorldPolicy.canAffect(inShard, outsideA), "shard actors don't reach the main world");
            assertTrue(WorldPolicy.canAffect(outsideA, outsideB), "unbound actors share the main world");
        } finally {
            inShard.remove();
            outsideA.remove();
            outsideB.remove();
        }
    }

    @Test
    void replacedPolicyReachesIntoShards() {
        Shard shard = new Shard(instance);
        LivingEntity inShard = new LivingEntity(EntityType.ZOMBIE);
        shard.spawn(inShard, new Pos(30, 65, 30)).join();
        LivingEntity staff = zombie(new Pos(30, 65, 28));
        WorldPolicy.set(new ShardPolicy() {
            @Override public boolean affects(@NotNull Entity actor, @NotNull Entity target) {
                return actor == staff || ShardPolicy.SAME_WORLD.affects(actor, target);
            }
        });
        try {
            assertTrue(WorldPolicy.canAffect(staff, inShard), "the granted actor reaches the shard");
            assertFalse(WorldPolicy.canAffect(inShard, staff), "everyone else stays gated");
        } finally {
            WorldPolicy.set(ShardPolicy.DEFAULT);
            inShard.remove();
            staff.remove();
        }
    }

    @Test
    void policyGrantsEntityVisibilityWithoutAView() {
        Shard game = new Shard(instance);
        Player staff = FakePlayer.connect(instance, new Pos(40.5, 65, 40.5), "PolicyStaff").player;
        LivingEntity inShard = new LivingEntity(EntityType.ZOMBIE);
        try {
            game.spawn(inShard, new Pos(42.5, 65, 42.5)).join();
            assertFalse(inShard.getViewers().contains(staff), "base players never see shard entities");

            WorldPolicy.set(new ShardPolicy() {
                @Override public boolean sees(@NotNull Player viewer, @NotNull Entity subject) {
                    return viewer == staff || ShardPolicy.super.sees(viewer, subject);
                }
            });
            ShardView.refireViewableRule(inShard);
            assertTrue(inShard.getViewers().contains(staff), "the policy grants entity visibility, no view needed");
        } finally {
            WorldPolicy.set(ShardPolicy.DEFAULT);
            inShard.remove();
            staff.remove();
        }
    }
}
