package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.event.ShardCreateEvent;
import io.github.term4.archipelago.event.ShardDestroyEvent;
import io.github.term4.archipelago.event.ShardTransferEvent;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.ExperienceOrb;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shard: an isolated game diff over a shared base map (blocks, physics, entities, lifecycle). */
class ShardTest extends HeadlessServerTest {

    @Test
    void overlayIsolatesTheBaseMap() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        a.setBlock(new Vec(5, 70, 5), Block.STONE);
        assertEquals(Block.STONE, a.getBlock(5, 70, 5), "the writing shard sees its diff");
        assertTrue(instance.getBlock(5, 70, 5).air(), "the base map is never written");
        assertTrue(b.getBlock(5, 70, 5).air(), "a sibling shard sees the clean map");
        assertTrue(MechanicsWorld.of(instance).getBlock(5, 70, 5).air(), "the base wrapper sees the clean map");
    }

    @Test
    void explicitAirHidesBaseBlocks() {
        Shard a = new Shard(instance);
        assertTrue(instance.getBlock(3, 63, 3).compare(Block.STONE), "base surface present");
        a.setBlock(new Vec(3, 63, 3), Block.AIR);
        assertTrue(a.getBlock(3, 63, 3).air(), "explicit air overrides the base block");
        assertTrue(instance.getBlock(3, 63, 3).compare(Block.STONE), "the base keeps it");
    }

    @Test
    void overlayWorksOutsideLoadedChunks() {
        Shard a = new Shard(instance);
        a.setBlock(new Vec(-3, 60, -7), Block.STONE); // chunk (-1,-1) is unloaded; the overlay answers without the base
        assertEquals(Block.STONE, a.getBlock(-3, 60, -7));
    }

    @Test
    void commitToBaseLandsTheBuildAndEmptiesTheDiff() {
        Shard a = new Shard(instance);
        Point built = new Vec(11, 70, 11), far = new Vec(5000, 70, 5000);
        a.setBlock(built, Block.EMERALD_BLOCK);
        a.setBlock(far, Block.GOLD_BLOCK); // far unloaded chunk: can't commit, stays a diff

        a.commitToBase();

        assertEquals(Block.EMERALD_BLOCK, instance.getBlock(11, 70, 11), "the build is on the real map");
        assertEquals(1, a.overlaySize(), "committed entries cleared; the unloaded one stays");
        assertEquals(Block.GOLD_BLOCK, a.getBlock(far));
        Shard other = new Shard(instance);
        assertEquals(Block.EMERALD_BLOCK, other.getBlock(11, 70, 11), "co-located shards see it through the base");
    }

    @Test
    void writesRevertingToTheParentViewShrinkTheDiff() {
        Shard a = new Shard(instance);
        for (int i = 0; i < 10; i++) a.setBlock(new Vec(i, 70, 8), Block.STONE); // over base air
        assertEquals(10, a.overlaySize());
        for (int i = 0; i < 10; i++) a.setBlock(new Vec(i, 70, 8), Block.AIR); // break them all
        assertEquals(0, a.overlaySize(), "a shard identical to the base has an empty diff");

        a.setBlock(new Vec(8, 63, 8), Block.AIR); // break a REAL base block
        assertEquals(1, a.overlaySize(), "explicit air masking a base block stays");
        a.setBlock(new Vec(8, 63, 8), Block.STONE); // put it back
        assertEquals(0, a.overlaySize());

        Shard child = new Shard(a);
        a.setBlock(new Vec(9, 70, 8), Block.GOLD_BLOCK);
        child.setBlock(new Vec(9, 70, 8), Block.AIR); // mask the parent's block
        assertEquals(1, child.overlaySize());
        child.setBlock(new Vec(9, 70, 8), Block.GOLD_BLOCK); // restore the parent view
        assertEquals(0, child.overlaySize(), "the mask clears; parent changes flow through again");

        child.pinBlock(new Vec(9, 70, 8), Block.GOLD_BLOCK);
        assertEquals(1, child.overlaySize(), "a pinned write keeps the entry even when it matches");
    }

    @Test
    void sweepCollidesWithOverlayBlocks() {
        Shard a = new Shard(instance);
        a.setBlock(new Vec(5, 68, 5), Block.STONE);
        BoundingBox box = new BoundingBox(0.5, 0.5, 0.5);
        Pos from = new Pos(5.5, 70, 5.5);
        PhysicsResult onShard = a.sweep(box, from, new Vec(0, -2, 0), null, false);
        PhysicsResult onBase = MechanicsWorld.of(instance).sweep(box, from, new Vec(0, -2, 0), null, false);
        assertTrue(onShard.hasCollision(), "the falling box lands on the shard's placed block");
        assertFalse(onBase.hasCollision(), "the base map has only air there");
    }

    @Test
    void spawnBindsAndIsolatesEntities() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        a.spawn(zombie, new Pos(8, 65, 8)).join();
        try {
            assertSame(a, MechanicsWorld.of(zombie), "the entity resolves its shard binding");
            assertTrue(a.nearbyEntities(new Vec(8, 65, 8), 2).contains(zombie));
            assertTrue(b.nearbyEntities(new Vec(8, 65, 8), 2).isEmpty(), "a sibling shard has its own entity set");
        } finally {
            zombie.remove();
        }
    }

    @Test
    void ofResolvesTheEntitysShard() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        LivingEntity loose = new LivingEntity(EntityType.ZOMBIE);
        a.spawn(zombie, new Pos(8, 65, 8)).join();
        loose.setInstance(instance, new Pos(9, 65, 9)).join();
        try {
            assertSame(a, Shard.of(zombie), "a shard-spawned entity resolves its shard");
            assertNull(Shard.of(loose), "an entity in the plain base instance is in no shard");
            b.transfer(zombie);
            assertSame(b, Shard.of(zombie), "transfer re-binds it to the new shard");
        } finally {
            zombie.remove();
            loose.remove();
        }
    }

    @Test
    void ofResolvesAMemberPlayersShard() {
        Shard a = new Shard(instance);
        FakePlayer member = FakePlayer.connect(instance, new Pos(5.5, 65, 5.5), "ShardOfMember");
        try {
            assertNull(Shard.of(member.player), "a base-instance player is in no shard");
            a.join(member.player);
            assertSame(a, Shard.of(member.player), "a member resolves their shard");
            a.leave(member.player);
            assertNull(Shard.of(member.player), "leaving clears the binding");
        } finally {
            member.player.remove();
        }
    }

    @Test
    void forkCopiesDiffSkyAndClonedEntities() {
        Shard a = new Shard(instance);
        a.setBlock(new Vec(40, 70, 40), Block.STONE);
        a.setBlock(new Vec(41, 63, 40), Block.AIR); // a break forks too
        a.setTime(18000);
        LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        a.spawn(zombie, new Pos(40, 65, 40)).join();
        try {
            Shard b = a.fork(e -> new LivingEntity(e.getEntityType()));
            assertEquals(a.diff(), b.diff(), "the fork snapshots the diff");
            assertEquals(18000, b.timeOverride(), "sky overrides copy");

            List<Entity> owned = b.entities().stream()
                    .filter(e -> !(e instanceof Player)).toList();
            assertEquals(1, owned.size(), "the cloner reproduced the zombie");
            assertNotSame(zombie, owned.get(0));
            assertSame(b, MechanicsWorld.of(owned.get(0)), "the clone belongs to the fork");
            assertSame(a, MechanicsWorld.of(zombie), "the original keeps its own");

            b.setBlock(new Vec(42, 70, 40), Block.OAK_PLANKS);
            assertTrue(a.getBlock(42, 70, 40).air(), "forks are independent");
            owned.get(0).remove();
        } finally {
            zombie.remove();
        }
    }

    @Test
    void seedFromBaseCopiesLooseEntitiesAndSkipsShardOwnedOnes() {
        ItemEntity loose = new ItemEntity(ItemStack.of(Material.EMERALD, 5));
        loose.setInstance(instance, new Pos(60, 65, 60)).join();
        Shard other = new Shard(instance);
        ItemEntity owned = new ItemEntity(ItemStack.of(Material.DIAMOND, 2));
        other.spawn(owned, new Pos(61, 65, 60)).join();
        try {
            Shard seeded = new Shard(instance).seedFromBase(Shard::copyEntity);
            boolean copiedLoose = false;
            for (Entity e : seeded.entities()) {
                assertNotSame(loose, e, "seeding copies, it never adopts the original");
                if (!(e instanceof ItemEntity item)) continue;
                assertFalse(item.getItemStack().material() == Material.DIAMOND,
                        "a shard-owned entity is not the base's to copy");
                copiedLoose |= item.getItemStack().material() == Material.EMERALD;
            }
            assertTrue(copiedLoose, "the base instance's own item copies into the shard");
        } finally {
            loose.remove();
            owned.remove();
        }
    }

    @Test
    void defaultClonerCopiesWhatItSafelyCan() {
        Shard a = new Shard(instance);
        LivingEntity plain = new LivingEntity(EntityType.ZOMBIE);
        plain.setHealth(7f);
        plain.setTag(Tag.String("test:role"), "boss");                       // serializable: copies
        plain.setTag(Tag.Transient("test:runtime"), new Object());           // runtime state: never copies
        a.spawn(plain, new Pos(50, 65, 50)).join();
        LivingEntity custom = new LivingEntity(EntityType.ZOMBIE) {}; // unknown subclass: behavior can't be guessed
        a.spawn(custom, new Pos(51, 65, 50)).join();
        LivingEntity stamped = new LivingEntity(EntityType.ZOMBIE) {}; // unknown subclass, but copy-stamped
        stamped.setTag(MechanicsWorld.ENTITY_COPY, () -> new LivingEntity(EntityType.COW));
        a.spawn(stamped, new Pos(52, 65, 50)).join();
        ItemEntity loot = new ItemEntity(ItemStack.of(Material.EMERALD, 5));
        a.spawn(loot, new Pos(53, 65, 50)).join();
        ExperienceOrb orb = new ExperienceOrb((short) 11);
        a.spawn(orb, new Pos(54, 65, 50)).join();
        try {
            Shard b = a.fork(Shard::copyEntity);
            List<Entity> owned = b.entities().stream()
                    .filter(e -> !(e instanceof Player)).toList();
            assertEquals(4, owned.size(), "plain + stamped + item + orb copy; the unstamped subclass is skipped");
            assertTrue(owned.stream().anyMatch(e -> e.getEntityType() == EntityType.COW), "the stamp supplier ran");
            var dataCopy = owned.stream().filter(e -> e instanceof LivingEntity l && l.getHealth() == 7f)
                    .findFirst().orElseThrow();
            assertEquals("boss", dataCopy.getTag(Tag.String("test:role")), "serializable tags copy");
            assertNull(dataCopy.getTag(Tag.Transient("test:runtime")), "transient runtime state does not");
            assertTrue(owned.stream().anyMatch(e -> e instanceof ItemEntity i
                    && i.getItemStack().equals(loot.getItemStack())), "plain item copies with its stack");
            assertTrue(owned.stream().anyMatch(e -> e instanceof ExperienceOrb o && o.getExperienceCount() == 11));
            owned.forEach(Entity::remove);
        } finally {
            plain.remove();
            custom.remove();
            stamped.remove();
            loot.remove();
            orb.remove();
        }
    }

    @Test
    void itemMergingStaysWithinAWorld() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        Pos pos = new Pos(10.5, 66, 10.5);
        ItemEntity inA = new ItemEntity(ItemStack.of(Material.STONE, 4));
        ItemEntity inB = new ItemEntity(ItemStack.of(Material.STONE, 4));
        ItemEntity base = new ItemEntity(ItemStack.of(Material.STONE, 4));
        ItemEntity second = new ItemEntity(ItemStack.of(Material.STONE, 4));
        a.spawn(inA, pos).join();
        b.spawn(inB, pos).join();       // the fork scenario: a clone co-located with its original
        base.setInstance(instance, pos).join();
        try {
            long now = System.currentTimeMillis();
            inA.update(now);            // ItemEntity.update = the merge pass
            inB.update(now);
            base.update(now);
            assertFalse(inA.isRemoved(), "cross-shard pairs never merge");
            assertFalse(inB.isRemoved());
            assertFalse(base.isRemoved(), "base items stay out of shards");
            assertEquals(4, inA.getItemStack().amount());

            a.spawn(second, pos).join();
            second.update(System.currentTimeMillis());
            assertTrue(inA.isRemoved(), "same-shard merging stays vanilla");
            assertEquals(8, second.getItemStack().amount());
            assertEquals(4, inB.getItemStack().amount());
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
            for (ItemEntity e : new ItemEntity[]{inA, inB, base, second}) if (!e.isRemoved()) e.remove();
        }
    }

    @Test
    void transferMovesOwnership() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setTag(Tag.String("test:role"), "boss");
        zombie.setTag(Tag.Transient("test:runtime"), 42);
        a.spawn(zombie, new Pos(12, 65, 12)).join();

        List<ShardTransferEvent> fired = new ArrayList<>();
        EventNode<Event> probe = EventNode.all("test:transfer");
        probe.addListener(ShardTransferEvent.class, fired::add);
        MinecraftServer.getGlobalEventHandler().addChild(probe);
        try {
            b.transfer(zombie);
            assertSame(b, MechanicsWorld.of(zombie), "ownership handed off");
            assertTrue(a.nearbyEntities(new Vec(12, 65, 12), 2).isEmpty(), "the source no longer owns it");
            assertTrue(b.nearbyEntities(new Vec(12, 65, 12), 2).contains(zombie));
            assertEquals(1, fired.size());
            assertSame(a, fired.get(0).from());
            assertSame(b, fired.get(0).to());
            assertEquals("boss", zombie.getTag(Tag.String("test:role")), "transfer moves the same object - all state survives");
            assertEquals((Object) 42, zombie.getTag(Tag.Transient("test:runtime")));
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(probe);
            zombie.remove();
        }
    }

    @Test
    void managerCreatesNamesAndDestroys() {
        ShardManager manager = new ShardManager();
        List<Event> fired = new ArrayList<>();
        EventNode<Event> probe = EventNode.all("test:manager");
        probe.addListener(ShardCreateEvent.class, fired::add);
        probe.addListener(ShardDestroyEvent.class, fired::add);
        MinecraftServer.getGlobalEventHandler().addChild(probe);
        try {
            Shard game = manager.create(instance, "game1");
            assertSame(game, manager.byName("game1"));
            assertThrows(IllegalArgumentException.class, () -> manager.create(instance, "game1"));
            game.setBlock(new Vec(9, 70, 9), Block.STONE);

            manager.destroy(game);
            assertNull(manager.byName("game1"));
            assertEquals(0, game.overlaySize(), "destroy drops the diff");
            assertTrue(instance.getBlock(9, 70, 9).air(), "destroy never writes the base map");
            assertTrue(fired.stream().anyMatch(e -> e instanceof ShardCreateEvent c && c.shard() == game));
            assertTrue(fired.stream().anyMatch(e -> e instanceof ShardDestroyEvent d && d.shard() == game));
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(probe);
        }
    }

    /** A name-conflicted registration must unwind fully - the constructor already attached the child to its parent. */
    @Test
    void nameConflictDetachesTheRejectedChild() {
        ShardManager manager = new ShardManager();
        Shard parent = manager.create(instance, "reg-parent");
        try {
            manager.child(parent, "reg-child");
            assertThrows(IllegalArgumentException.class, () -> manager.child(parent, "reg-child"));
            assertEquals(1, parent.children().size(), "the rejected child detached from its parent");
        } finally {
            manager.destroy(parent);
        }
    }

    @Test
    void resetDropsTheDiff() {
        Shard a = new Shard(instance);
        a.setBlock(new Vec(6, 70, 6), Block.STONE);
        assertEquals(1, a.overlaySize());
        a.reset();
        assertEquals(0, a.overlaySize());
        assertTrue(a.getBlock(6, 70, 6).air(), "back to the base map");
    }

    @Test
    void diffExportsAndReloads() {
        Shard original = new Shard(instance);
        original.setBlock(new Vec(11, 70, 11), Block.STONE);
        original.setBlock(new Vec(11, 71, 11), Block.LADDER.withProperty("facing", "east"));
        original.setBlock(new Vec(12, 63, 11), Block.AIR); // a break is diff too

        // what an external saver stores: pos + state id (add nbt if hasNbt())
        Map<Point, Integer> file = new HashMap<>();
        original.forEachDiff((pos, block) -> file.put(pos, block.stateId()));

        Shard restored = new Shard(instance);
        file.forEach((pos, id) -> restored.setBlock(pos, Block.fromStateId(id)));
        assertEquals(original.overlaySize(), restored.overlaySize());
        original.forEachDiff((pos, block) -> assertEquals(block, restored.getBlock(pos), "state (incl. properties) round-trips"));
        assertEquals(original.diff(), restored.diff(), "diff() snapshots agree - the export/import pair");
    }

    @Test
    void plainShardOnTickRidesTheServerScheduler() {
        Shard plain = new Shard(instance);
        List<Long> seen = new ArrayList<>();
        Runnable stop = plain.onTick(seen::add);
        try {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTick();
            assertEquals(List.of(plain.ticks(), plain.ticks()), seen, "fires per server tick with the shard's clock");
        } finally {
            stop.run();
        }
        MinecraftServer.getSchedulerManager().processTick();
        assertEquals(2, seen.size(), "the handle removed the hook");
    }

    @Test
    void worldMetadataLivesOnTags() {
        Tag<String> marker = Tag.String("test:marker");
        Shard a = new Shard(instance);
        a.setTag(marker, "game-a");
        assertEquals("game-a", a.getTag(marker));
        assertNull(MechanicsWorld.of(instance).getTag(marker), "world tags are per world, not per instance");
    }
}
