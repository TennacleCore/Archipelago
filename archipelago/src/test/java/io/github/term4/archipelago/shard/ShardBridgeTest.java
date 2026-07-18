package io.github.term4.archipelago.shard;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.explosion.ExplosionEvent;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionSystem;
import io.github.term4.minestommechanics.presets.vanilla18.Explosion;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.polar.PolarShardCodec;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.ShardBridge;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The mechanics<->Archipelago glue end to end: bindings resolve through the adapter and gameplay scopes to the shard. */
class ShardBridgeTest extends HeadlessServerTest {

    private static MinestomMechanics mm;

    @BeforeAll
    static void mechanics() {
        mm = MechanicsBoot.ensure();
    }

    @Test
    void bridgedShardScopesExplosions() {
        Shard game = new Shard(instance);
        LivingEntity inShard = new LivingEntity(EntityType.ZOMBIE);
        game.spawn(inShard, new Pos(50, 65, 51)).join();
        LivingEntity outside = zombie(new Pos(50, 65, 49));
        try {
            assertSame(ShardBridge.adapt(game), MechanicsWorld.of(inShard), "bindings resolve to the cached adapter");

            List<ExplosionEvent.Target> targets = explode(MechanicsWorld.of(instance), new Vec(50, 66, 50));
            assertEquals(1, targets.size(), "a main-world blast never reaches shard members");
            assertSame(outside, targets.get(0).entity());

            targets = explode(ShardBridge.adapt(game), new Vec(50, 66, 50));
            assertEquals(1, targets.size(), "a shard blast targets its own game only");
            assertSame(inShard, targets.get(0).entity());
        } finally {
            inShard.remove();
            outside.remove();
        }
    }

    @Test
    void mechanicsDropsSurviveThePolarRoundTrip() throws InterruptedException {
        // the TestServer's save/load glue: map mechanics' ENTITY_SAVE mirror + bind the reviver
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("mm:item", DroppedItemEntity::fromSave);

        Shard game = new Shard(instance);
        DroppedItemEntity drop = new DroppedItemEntity(ItemStack.of(Material.DIAMOND, 3), DroppedItemEntity.Model.LEGACY);
        game.spawn(drop, new Pos(40.5, 65, 40.5)).join();
        try {
            byte[] bytes = PolarShardCodec.save(game);
            Shard restored = new Shard(instance);
            PolarShardCodec.load(restored, bytes);

            long deadline = System.currentTimeMillis() + 2000;
            DroppedItemEntity revived = null;
            while (revived == null && System.currentTimeMillis() < deadline) {
                revived = (DroppedItemEntity) restored.entities().stream()
                        .filter(e -> e instanceof DroppedItemEntity && e != drop).findFirst().orElse(null);
                if (revived == null) Thread.sleep(10);
            }
            assertNotNull(revived, "the drop revives from the save file");
            assertEquals(drop.getItemStack(), revived.getItemStack(), "with its stack");
            revived.remove();
        } finally {
            drop.remove();
        }
    }

    @Test
    void bridgeClonerReadsTheMechanicsCopyStamp() {
        DroppedItemEntity drop = new DroppedItemEntity(ItemStack.of(Material.DIAMOND, 3), null);
        DroppedItemEntity copy = assertInstanceOf(DroppedItemEntity.class, ShardBridge.copyEntity(drop), "mechanics stamp wins");
        assertEquals(drop.getItemStack(), copy.getItemStack());

        LivingEntity plain = new LivingEntity(EntityType.ZOMBIE); // no stamp: falls through to Shard.copyEntity
        plain.setHealth(7f);
        assertEquals(7f, ((LivingEntity) ShardBridge.copyEntity(plain)).getHealth());
    }

    private List<ExplosionEvent.Target> explode(MechanicsWorld world, Vec center) {
        List<ExplosionEvent.Target> captured = new ArrayList<>();
        EventNode<Event> node = EventNode.all("test:bridge-explosion");
        node.addListener(ExplosionEvent.class, e -> captured.addAll(e.targets()));
        MinecraftServer.getGlobalEventHandler().addChild(node);
        try {
            new ExplosionSystem(mm, Explosion.config()).explode(world, center, 4.0f, null, null);
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(node);
        }
        return captured;
    }
}
