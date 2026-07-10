package io.github.term4.shardkit;

import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import io.github.term4.worldaccess.WorldPolicy;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
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
}
