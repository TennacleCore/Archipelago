package io.github.term4.shardkit;

import io.github.term4.shardkit.event.ShardDestroyEvent;
import io.github.term4.shardkit.testsupport.DetectorEntity;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import io.github.term4.shardkit.ticking.ExecutionDomain;
import io.github.term4.shardkit.world.WorldAccess;
import io.github.term4.shardkit.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shard tree: a child layer's diff is relative to its parent - chained reads with explicit-air masks,
 * live ancestor writes fanned to unmasked descendants, LCA view swaps, reset-to-parent, flatten.
 */
class ShardTreeTest extends HeadlessServerTest {

    @Test
    void chainReadsFallThroughAndExplicitAirMasks() {
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        Shard grandchild = new Shard(child);
        assertEquals(root, child.parent());

        root.setBlock(new Vec(1701, 70, 1700), Block.GOLD_BLOCK);
        assertEquals(Block.GOLD_BLOCK, child.getBlock(1701, 70, 1700), "child reads through to the parent layer");
        assertEquals(Block.GOLD_BLOCK, grandchild.getBlock(1701, 70, 1700), "and so does the grandchild");
        assertTrue(instance.getBlock(1701, 70, 1700).isAir(), "the base map is never written");

        child.setBlock(new Vec(1701, 70, 1700), Block.AIR); // explicit air: the ancestor block is broken HERE
        assertTrue(child.getBlock(1701, 70, 1700).isAir(), "explicit air masks the parent layer");
        assertTrue(grandchild.getBlock(1701, 70, 1700).isAir(), "the mask carries down the chain");
        assertEquals(Block.GOLD_BLOCK, root.getBlock(1701, 70, 1700), "the parent keeps its block");

        child.setBlock(new Vec(1702, 70, 1700), Block.DIAMOND_BLOCK);
        assertTrue(root.getBlock(1702, 70, 1700).isAir(), "a child write never shows upward");
        assertEquals(Block.STONE, grandchild.getBlock(1702, 63, 1700), "the base surface shows through the whole chain");
    }

    @Test
    void physicsSweepsThroughTheChain() {
        instance.loadChunk(1720 >> 4, 1720 >> 4).join();
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        root.setBlock(new Vec(1720, 70, 1720), Block.STONE);

        BoundingBox box = new BoundingBox(0.6, 1.8, 0.6);
        Pos above = new Pos(1720.5, 72, 1720.5);
        Vec fall = new Vec(0, -2, 0);
        assertTrue(child.sweep(box, above, fall, null, true).hasCollision(),
                "the child collides with the parent layer's block");
        assertFalse(new Shard(instance).sweep(box, above, fall, null, true).hasCollision(),
                "an unrelated shard sees only the base map there");

        child.setBlock(new Vec(1720, 70, 1720), Block.AIR);
        assertFalse(child.sweep(box, above, fall, null, true).hasCollision(),
                "explicit air masks the block out of the child's physics");
    }

    @Test
    void parentWritesFanOutToUnmaskedDescendants() {
        instance.loadChunk(1740 >> 4, 1740 >> 4).join();
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        Shard grandchild = new Shard(child);
        FakePlayer c = FakePlayer.connect(instance, new Pos(1740.5, 66, 1740.5), "TreeChild");
        FakePlayer g = FakePlayer.connect(instance, new Pos(1740.5, 66, 1740.5), "TreeGrand");
        try {
            child.join(c.player);
            grandchild.join(g.player);
            Point masked = new Vec(1741, 70, 1740);
            child.setBlock(masked, Block.BEDROCK);
            c.sent.clear();
            g.sent.clear();

            Point open = new Vec(1742, 70, 1740);
            root.setBlock(open, Block.GOLD_BLOCK);
            assertEquals(1, blockChangesAt(c, open).size(), "an unmasked parent write reaches the child's viewer");
            assertEquals(1, blockChangesAt(g, open).size(), "and the grandchild's");

            root.setBlock(masked, Block.DIAMOND_BLOCK);
            assertTrue(blockChangesAt(c, masked).isEmpty(), "the child's own diff masks the parent write");
            assertTrue(blockChangesAt(g, masked).isEmpty(), "a mask prunes the whole subtree below it");
        } finally {
            c.player.remove();
            g.player.remove();
        }
    }

    @Test
    void setBlocksFiltersPerLevel() {
        instance.loadChunk(1760 >> 4, 1760 >> 4).join();
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        FakePlayer r = FakePlayer.connect(instance, new Pos(1760.5, 66, 1760.5), "BatchRoot");
        FakePlayer c = FakePlayer.connect(instance, new Pos(1760.5, 66, 1760.5), "BatchChild");
        try {
            root.join(r.player);
            child.join(c.player);
            Point masked = new Vec(1761, 70, 1760);
            child.setBlock(masked, Block.BEDROCK);
            r.sent.clear();
            c.sent.clear();

            root.setBlocks(Map.of(masked, Block.GOLD_BLOCK, new Vec(1762, 70, 1760), Block.GOLD_BLOCK));
            assertEquals(2, r.sent(MultiBlockChangePacket.class).get(0).blocks().length,
                    "the writing level sends its full batch");
            assertEquals(1, c.sent(MultiBlockChangePacket.class).get(0).blocks().length,
                    "the child level receives only what its diff doesn't mask");
        } finally {
            r.player.remove();
            c.player.remove();
        }
    }

    @Test
    void resetRevertsToTheParentView() {
        instance.loadChunk(1780 >> 4, 1780 >> 4).join();
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        FakePlayer c = FakePlayer.connect(instance, new Pos(1780.5, 66, 1780.5), "ResetChild");
        try {
            child.join(c.player);
            Point pos = new Vec(1781, 70, 1780);
            root.setBlock(pos, Block.GOLD_BLOCK);
            child.setBlock(pos, Block.DIAMOND_BLOCK);
            c.sent.clear();

            child.reset();
            List<BlockChangePacket> reverts = blockChangesAt(c, pos);
            assertEquals(1, reverts.size());
            assertEquals(Block.GOLD_BLOCK.stateId(), reverts.get(0).blockStateId(),
                    "a child reset reverts to the PARENT view, not the base map");
            assertEquals(Block.GOLD_BLOCK, child.getBlock(pos), "the chain read agrees");
        } finally {
            c.player.remove();
        }
    }

    @Test
    void lcaSwapResendsOnlyTheDivergingDiffs() {
        instance.loadChunk(1800 >> 4, 1800 >> 4).join();
        Shard parent = new Shard(instance);
        Shard a = new Shard(parent);
        Shard b = new Shard(parent);
        Point inParent = new Vec(1801, 70, 1800), inA = new Vec(1802, 70, 1800), inB = new Vec(1803, 70, 1800);
        parent.setBlock(inParent, Block.GOLD_BLOCK);
        a.setBlock(inA, Block.DIAMOND_BLOCK);
        b.setBlock(inB, Block.EMERALD_BLOCK);
        FakePlayer p = FakePlayer.connect(instance, new Pos(1800.5, 66, 1800.5), "LcaSwapper");
        try {
            ShardView.set(p.player, ShardView.of(a));
            p.sent.clear();

            ShardView.set(p.player, ShardView.of(b)); // sibling switch: the shared parent layer can't diverge
            assertEquals(Set.of(inA, inB), blockChangePositions(p),
                    "a sibling switch re-sends only the layers below the LCA");

            p.sent.clear();
            ShardView.set(p.player, null); // back to base: the WHOLE chain diverges
            assertEquals(Set.of(inParent, inB), blockChangePositions(p),
                    "leaving the tree re-sends ancestor layers too");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void chunkLoadOverlaySendsTheChainEffectively() {
        Shard root = new Shard(instance);
        Shard child = new Shard(root);
        Point maskedByChild = new Vec(1821, 70, 1820), rootOnly = new Vec(1822, 70, 1820), childOnly = new Vec(1823, 70, 1820);
        root.setBlock(maskedByChild, Block.GOLD_BLOCK);
        root.setBlock(rootOnly, Block.IRON_BLOCK);
        child.setBlock(maskedByChild, Block.DIAMOND_BLOCK);
        child.setBlock(childOnly, Block.EMERALD_BLOCK);
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 66, 0.5), "ChunkLoader");
        try {
            p.sent.clear();
            child.sendChunkOverlay(p.player, 1820 >> 4, 1820 >> 4);
            Map<Point, Integer> sent = p.sent(BlockChangePacket.class).stream()
                    .collect(Collectors.toMap(BlockChangePacket::blockPosition, BlockChangePacket::blockStateId));
            assertEquals(3, sent.size(), "each chain position once");
            assertEquals(Block.DIAMOND_BLOCK.stateId(), sent.get(maskedByChild), "the child layer wins");
            assertEquals(Block.IRON_BLOCK.stateId(), sent.get(rootOnly));
            assertEquals(Block.EMERALD_BLOCK.stateId(), sent.get(childOnly));
        } finally {
            p.player.remove();
        }
    }

    @Test
    void flattenCombinesTopOverBottomAndCollapsesChains() {
        Shard bottom = new Shard(instance);
        Point a = new Vec(1841, 70, 1840), b = new Vec(1842, 70, 1840), c = new Vec(1843, 70, 1840), d = new Vec(1844, 70, 1840);
        bottom.setBlock(a, Block.STONE);
        bottom.setBlock(b, Block.GOLD_BLOCK);
        bottom.setTime(6000);
        Entity inBottom = new Entity(EntityType.ARMOR_STAND);
        bottom.spawn(inBottom, new Pos(1841.5, 70, 1840.5));

        Shard parent = new Shard(instance);
        parent.setBlock(c, Block.IRON_BLOCK);
        Shard top = new Shard(parent);
        top.setBlock(b, Block.DIAMOND_BLOCK);
        top.setBlock(d, Block.EMERALD_BLOCK);
        Entity inTop = new Entity(EntityType.ARMOR_STAND);
        top.spawn(inTop, new Pos(1844.5, 70, 1840.5));

        Shard flat = top.flattenOnto(bottom, Shard::copyEntity);
        assertNull(flat.parent(), "a flattened shard stands alone");
        Map<Point, Block> diff = flat.diff();
        assertEquals(Block.STONE, diff.get(a));
        assertEquals(Block.DIAMOND_BLOCK, diff.get(b), "the top side wins conflicts");
        assertEquals(Block.IRON_BLOCK, diff.get(c), "the top's whole chain rides along");
        assertEquals(Block.EMERALD_BLOCK, diff.get(d));
        assertEquals(6000, flat.timeOverride(), "the bottom's sky stands when the top chain has no override");
        assertEquals(2, flat.entities().size(), "both sides' entities cloned");

        Map<Point, Block> collapsed = top.flattenOnto(parent).diff();
        assertEquals(Map.of(b, Block.DIAMOND_BLOCK, c, Block.IRON_BLOCK, d, Block.EMERALD_BLOCK), collapsed,
                "flattening onto the parent collapses the chain");
    }

    @Test
    void destroyCascadesThroughTheSubtreeOnce() {
        ShardManager manager = new ShardManager();
        Shard parent = manager.create(instance, "tree-parent");
        Shard child = manager.child(parent, "tree-child");
        Shard bare = new Shard(child); // unregistered layers die with the tree too
        FakePlayer member = FakePlayer.connect(instance, new Pos(0.5, 66, 0.5), "CascadeSteve");
        AtomicInteger destroyEvents = new AtomicInteger();
        EventNode<Event> watch = EventNode.all("test:cascade");
        watch.addListener(ShardDestroyEvent.class, e -> destroyEvents.incrementAndGet());
        MinecraftServer.getGlobalEventHandler().addChild(watch);
        try {
            child.join(member.player);
            manager.destroy(parent);
            assertNull(manager.byName("tree-child"), "the child died with its parent");
            assertNull(member.player.getTag(WorldAccess.ENTITY_TAG), "the child's member was evicted");
            assertTrue(bare.destroyed, "bare layers cascade too");
            assertEquals(3, destroyEvents.get(), "one destroy event per shard in the subtree");

            manager.destroy(parent); // the quarantine sweep may reach an already-cascaded shard
            assertEquals(3, destroyEvents.get(), "destroy is idempotent");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(watch);
            member.player.remove();
        }
    }

    @Test
    void childrenShareTheParentsClock() {
        ExecutionDomain domain = ExecutionDomain.create();
        Shard parent = new Shard(instance, domain);
        Shard child = new Shard(parent);
        DetectorEntity inParent = new DetectorEntity(), inChild = new DetectorEntity();
        try {
            assertEquals(domain, child.domain(), "the one-arg child ctor rides the parent's own domain");
            parent.spawn(inParent, new Pos(0.5, 70, 0.5)).join();
            child.spawn(inChild, new Pos(2.5, 70, 2.5)).join();
            await("both layers tick", () -> inParent.ticks.get() >= 3 && inChild.ticks.get() >= 3);
            assertEquals(inParent.thread, inChild.thread, "one tree, one clock");
            assertTrue(inChild.thread.startsWith("shard-root:"), inChild.thread);
        } finally {
            inParent.remove();
            inChild.remove();
            domain.retire();
        }
    }

    @Test
    void childLayersMayRideTheirOwnClock() {
        ExecutionDomain a = ExecutionDomain.create(), b = ExecutionDomain.create();
        Shard parent = new Shard(instance, a);
        Shard fast = new Shard(parent, b); // shards and domains are separate: any domain, at the caller's risk
        Shard still = new Shard(parent, null);
        DetectorEntity inParent = new DetectorEntity(), inFast = new DetectorEntity(), inStill = new DetectorEntity();
        try {
            assertFalse(a.sharesClock(b));
            parent.setBlock(new Vec(4, 70, 4), Block.GOLD_BLOCK);
            assertEquals(Block.GOLD_BLOCK, fast.getBlock(4, 70, 4), "chain reads don't care about clocks");
            assertEquals(Block.GOLD_BLOCK, still.getBlock(4, 70, 4));

            parent.spawn(inParent, new Pos(0.5, 70, 0.5)).join();
            fast.spawn(inFast, new Pos(2.5, 70, 2.5)).join();
            still.spawn(inStill, new Pos(4.5, 70, 4.5)).join();
            await("both clocks tick their layer", () -> inParent.ticks.get() >= 3 && inFast.ticks.get() >= 3);
            assertNotEquals(inParent.thread, inFast.thread, "each layer rides its chosen clock");
            assertNull(ExecutionDomain.of(inStill), "a null-domain child stays server-ticked");
        } finally {
            inParent.remove();
            inFast.remove();
            inStill.remove();
            a.retire();
            b.retire();
        }
    }

    @Test
    void destroyRetiresASharedDomainOnlyWithItsLastUser() {
        ShardManager manager = new ShardManager();
        ExecutionDomain domain = ExecutionDomain.create();
        Shard first = manager.create(instance, "share-1", domain);
        Shard second = manager.create(instance, "share-2", domain);
        try {
            manager.destroy(first);
            assertTrue(domain.live(), "a domain outlives one of its shards");
            manager.destroy(second);
            await("the last user retires it", () -> !domain.live());
        } finally {
            manager.destroy(second);
            domain.retire();
        }
    }

    @Test
    void entitiesAreLayerLocalUntilViewsComposeThem() {
        instance.loadChunk(1860 >> 4, 1860 >> 4).join();
        Shard parent = new Shard(instance);
        Shard child = new Shard(parent);
        FakePlayer pm = FakePlayer.connect(instance, new Pos(1860.5, 66, 1860.5), "LayerParent");
        FakePlayer cm = FakePlayer.connect(instance, new Pos(1860.5, 66, 1860.5), "LayerChild");
        FakePlayer both = FakePlayer.connect(instance, new Pos(1860.5, 66, 1860.5), "LayerBoth");
        Entity inParent = new Entity(EntityType.ARMOR_STAND), inChild = new Entity(EntityType.ARMOR_STAND);
        try {
            parent.join(pm.player);
            child.join(cm.player);
            ShardView.set(both.player, ShardView.of(child).withEntities(parent)); // explicit cross-layer composition
            parent.spawn(inParent, new Pos(1861.5, 66, 1860.5)).join();
            child.spawn(inChild, new Pos(1862.5, 66, 1860.5)).join();

            await("each member sees exactly their own layer's entity", () ->
                    spawnedIds(pm).contains(inParent.getEntityId()) && spawnedIds(cm).contains(inChild.getEntityId()));
            assertFalse(spawnedIds(pm).contains(inChild.getEntityId()), "a child entity is invisible to the parent layer");
            assertFalse(spawnedIds(cm).contains(inParent.getEntityId()), "an ancestor entity is invisible to the child layer");
            await("the composed view sees both layers", () ->
                    spawnedIds(both).containsAll(Set.of(inParent.getEntityId(), inChild.getEntityId())));
            assertFalse(WorldPolicy.canAffect(inParent, inChild),
                    "cross-layer gameplay stays policy-gated (same-shard default)");
        } finally {
            inParent.remove();
            inChild.remove();
            pm.player.remove();
            cm.player.remove();
            both.player.remove();
        }
    }

    @Test
    void transferWithinOneClockTicksContinuously() {
        ExecutionDomain domain = ExecutionDomain.create();
        Shard parent = new Shard(instance, domain);
        Shard child = new Shard(parent);
        DetectorEntity e = new DetectorEntity();
        try {
            parent.spawn(e, new Pos(0.5, 70, 0.5)).join();
            await("the clock drives it", () -> e.ticks.get() >= 3);
            String thread = e.thread;

            child.transfer(e); // same clock: the mailbox stamp resolves on the owner's own tick, nothing re-claims
            long at = e.ticks.get();
            await("it keeps ticking without a gap", () -> e.ticks.get() >= at + 3);
            assertEquals(thread, e.thread, "same clock throughout");
            assertEquals(0, e.overlaps.get());
            assertEquals(domain, ExecutionDomain.of(e));
        } finally {
            e.remove();
            domain.retire();
        }
    }

    @Test
    void snapshotHooksChooseWhatCarries() {
        Shard shard = new Shard(instance);
        Point in = new Vec(1881, 70, 1880), out = new Vec(1899, 70, 1880);
        shard.setBlock(in, Block.GOLD_BLOCK);
        shard.setBlock(out, Block.GOLD_BLOCK);

        // region fork: a position test decides what copies; the hook may also transform
        Shard region = shard.fork((pos, block) -> pos.blockX() < 1890 ? Block.IRON_BLOCK : null, null);
        assertEquals(Map.of(in, Block.IRON_BLOCK), region.diff());

        // custom flatten rule: the bottom side wins where both touch
        Shard bottom = new Shard(instance);
        bottom.setBlock(in, Block.EMERALD_BLOCK);
        Shard flat = shard.flattenOnto(bottom, (pos, b, t) -> b != null ? b : t, null);
        assertEquals(Block.EMERALD_BLOCK, flat.diff().get(in), "the merger decides per position");
        assertEquals(Block.GOLD_BLOCK, flat.diff().get(out));

        // the read half for hand-rolled recipes: the chain collapsed, upper layers winning
        Shard layer = new Shard(shard);
        layer.setBlock(in, Block.DIAMOND_BLOCK);
        assertEquals(Map.of(in, Block.DIAMOND_BLOCK, out, Block.GOLD_BLOCK), layer.effectiveDiff());
        assertEquals(Map.of(in, Block.DIAMOND_BLOCK), layer.diff(), "diff() stays relative");
    }

    private static List<BlockChangePacket> blockChangesAt(FakePlayer player, Point pos) {
        return player.sent(BlockChangePacket.class).stream().filter(p -> p.blockPosition().sameBlock(pos)).toList();
    }

    private static Set<Integer> spawnedIds(FakePlayer player) {
        return player.sent(SpawnEntityPacket.class).stream().map(SpawnEntityPacket::entityId)
                .collect(Collectors.toSet());
    }

    private static Set<Point> blockChangePositions(FakePlayer player) {
        return player.sent(BlockChangePacket.class).stream().map(BlockChangePacket::blockPosition)
                .collect(Collectors.toSet());
    }
}
