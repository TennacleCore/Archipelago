package io.github.term4.shardkit;

import io.github.term4.shardkit.world.WorldAccess;
import io.github.term4.shardkit.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Shape;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityDespawnEvent;
import net.minestom.server.event.entity.EntityItemMergeEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerCancelDiggingEvent;
import net.minestom.server.event.player.PlayerChunkLoadEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerFinishDiggingEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.BlockPredicates;
import net.minestom.server.item.component.ItemBlockState;
import net.minestom.server.item.component.Tool;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.utils.block.BlockBreakCalculation;
import net.minestom.server.world.DimensionType;
import net.minestom.server.worldevent.WorldEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** {@link Shard#blockRouting()}: the packet-level place/dig interception. */
final class BlockRouting {

    /** Blocks a placer may put into their OWN body (e.g. a 1.8-mechanics lib exempts ladders/vines/webs). */
    static volatile Predicate<Block> selfPlaceExempt = block -> false;

    private BlockRouting() {}

    static EventNode<Event> node() {
        // the plain default can't know about ShardView block-view state; don't clobber a custom policy
        if (WorldPolicy.get() == WorldPolicy.SAME_WORLD) WorldPolicy.set(ShardPolicy.DEFAULT);
        // non-shard players fall through to the stock listener, whose collision scan is world-blind: a
        // co-located shard entity (invisible to a base player) would block their placement
        MinecraftServer.getPacketListenerManager().setPlayListener(
                ClientPlayerBlockPlacementPacket.class, BasePlacement::listener);
        EventNode<Event> node = EventNode.all("shardkit:blocks");
        node.addListener(PlayerPacketEvent.class, e -> {
            boolean place = e.getPacket() instanceof ClientPlayerBlockPlacementPacket;
            if (!place && !(e.getPacket() instanceof ClientPlayerActionPacket)) return;
            Player player = e.getPlayer();
            Shard bound = player.getTag(WorldAccess.ENTITY_TAG) instanceof Shard d
                    && player.getInstance() == d.instance() ? d : null;
            Shard viewed = ShardView.of(player).blocks();
            if (viewed != null && player.getInstance() != viewed.instance()) viewed = null;
            boolean handled;
            if (bound != null && bound == viewed) {
                handled = place ? place(bound, player, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : dig(bound, player, (ClientPlayerActionPacket) e.getPacket());
            } else if (viewed != null && bound != viewed && WorldPolicy.canEdit(player, viewed)) {
                handled = place ? place(viewed, player, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : dig(viewed, player, (ClientPlayerActionPacket) e.getPacket());
            } else if (bound != null || viewed != null) {
                handled = place ? readOnlyPlace(player, viewed, (ClientPlayerBlockPlacementPacket) e.getPacket())
                        : readOnlyDig(player, viewed, (ClientPlayerActionPacket) e.getPacket());
            } else return;
            if (handled) e.setCancelled(true);
        });
        // late joiners get the visibility rules, or they'd see every shard's players/entities until the next join/leave
        node.addListener(PlayerSpawnEvent.class, e -> {
            ShardView.applyRules(e.getPlayer());
            Shard viewed = ShardView.of(e.getPlayer()).blocks();
            if (viewed != null && e.getPlayer().getInstance() == viewed.instance()) viewed.sendSky(e.getPlayer());
        });
        // membership dies with the connection - a stale member's body would keep blocking placements
        node.addListener(PlayerDisconnectEvent.class, e -> {
            if (e.getPlayer().getTag(WorldAccess.ENTITY_TAG) instanceof Shard shard) shard.leave(e.getPlayer());
            ShardView.forget(e.getPlayer());
        });
        node.addListener(EntityDespawnEvent.class, e -> {
            if (e.getEntity().getTag(WorldAccess.ENTITY_TAG) instanceof Shard shard) shard.entities.remove(e.getEntity());
        });
        // Minestom's item-merge scan is instance-wide - co-located items from different worlds absorb each other
        node.addListener(EntityItemMergeEvent.class, e -> {
            if (!WorldPolicy.canAffect(e.getEntity(), e.getMerged())) e.setCancelled(true);
        });
        node.addListener(PlayerChunkLoadEvent.class, e -> {
            Shard viewed = ShardView.of(e.getPlayer()).blocks();
            if (viewed == null || e.getPlayer().getInstance() != viewed.instance()) return;
            viewed.sendChunkOverlay(e.getPlayer(), e.getChunkX(), e.getChunkZ());
        });
        return node;
    }

    /** Shard-backed placement; {@code true} = handled. Non-block items pass to Minestom (interactions, throwables). */
    private static boolean place(Shard shard, Player player, ClientPlayerBlockPlacementPacket p) {
        Instance base = shard.instance();
        ItemStack held = player.getItemInHand(p.hand());
        Material material = held.material();
        if (!material.isBlock()) return false;
        if (!base.isChunkLoaded(p.blockPosition())) return true;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        BlockVec clicked = p.blockPosition().asBlockVec();
        Vec cursor = new Vec(p.cursorPositionX(), p.cursorPositionY(), p.cursorPositionZ());
        Block interacted = shard.getBlock(clicked);

        // block-handler onInteract is skipped: overlay writes never attach handlers
        PlayerBlockInteractEvent interact = new PlayerBlockInteractEvent(player, p.hand(), base, interacted, clicked, cursor, p.blockFace());
        EventDispatcher.call(interact);
        if (interact.isBlockingItemUse()) return true;

        boolean canPlace = switch (player.getGameMode()) {
            case SPECTATOR -> false;
            case ADVENTURE -> held.get(DataComponents.CAN_PLACE_ON, BlockPredicates.NEVER).test(interacted);
            default -> true;
        };

        var manager = MinecraftServer.getBlockManager();
        // vanilla (and the client's prediction) replaces a replaceable clicked block in place - upstream Minestom
        // bumps those to the neighbour; isSelfReplaceable = slab merging
        BlockVec target;
        var interactedRule = manager.getBlockPlacementRule(interacted);
        if (interacted.registry().isReplaceable() || (interactedRule != null && interactedRule.isSelfReplaceable(
                new BlockPlacementRule.Replacement(interacted, p.blockFace(), cursor, false, material)))) {
            target = clicked;
        } else {
            target = clicked.relative(p.blockFace());
            Block atTarget = shard.getBlock(target);
            var targetRule = manager.getBlockPlacementRule(atTarget);
            if (!atTarget.registry().isReplaceable() && !(targetRule != null && targetRule.isSelfReplaceable(
                    new BlockPlacementRule.Replacement(atTarget, p.blockFace(), cursor, true, material)))) {
                canPlace = false;
            }
        }

        DimensionType dim = shard.dimension();
        if (target.y() >= dim.maxY() || target.y() < dim.minY()) {
            rejectPlace(shard, player, clicked, target);
            return true;
        }
        if (!base.getWorldBorder().inBounds(target)) canPlace = false;

        Block block = held.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY).apply(material.block());
        if (!canPlace || intersectsEntity(shard, target, player, block)) {
            rejectPlace(shard, player, clicked, target);
            return true;
        }
        // orientation/shape rules over the SHARD view; the base pipeline runs them inside placeBlock - here pre-event
        var rule = manager.getBlockPlacementRule(block);
        if (rule != null) {
            Block ruled = rule.blockPlace(new BlockPlacementRule.PlacementState(
                    (x, y, z, cond) -> shard.getBlock(x, y, z, cond), block, p.blockFace(), target,
                    cursor, player.getPosition(), held, player.isSneaking()));
            if (ruled == null) { rejectPlace(shard, player, clicked, target); return true; } // rule rejected the spot
            block = ruled;
        }
        PlayerBlockPlaceEvent event = new PlayerBlockPlaceEvent(player, base, block, p.blockFace(), target, cursor, p.hand());
        event.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            rejectPlace(shard, player, clicked, target);
            return true;
        }
        shard.setBlock(target, event.getBlock());
        if (event.doesConsumeBlock()) player.setItemInHand(p.hand(), held.consume(1));
        else player.getInventory().update();
        return true;
    }

    /** Shard-backed digging; {@code true} = handled. Trusts the client's FINISHED timing like the base pipeline. */
    private static boolean dig(Shard shard, Player player, ClientPlayerActionPacket p) {
        Instance base = shard.instance();
        var status = p.status();
        boolean start = status == ClientPlayerActionPacket.Status.STARTED_DIGGING;
        boolean finish = status == ClientPlayerActionPacket.Status.FINISHED_DIGGING;
        boolean cancel = status == ClientPlayerActionPacket.Status.CANCELLED_DIGGING;
        if (!start && !finish && !cancel) return false; // drops / item state etc. stay Minestom's
        if (!base.isChunkLoaded(p.blockPosition())) return true;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        BlockVec pos = p.blockPosition().asBlockVec();
        Block block = shard.getBlock(pos);
        if (cancel) {
            EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
            return true;
        }
        if (block.isAir()) return true;
        if (shouldPreventBreaking(player, block)) {
            resync(shard, player, pos, pos);
            return true;
        }
        int breakTicks = BlockBreakCalculation.breakTicks(block, player);
        if (start && breakTicks != 0) { // the client animates the dig; its FINISHED completes it
            EventDispatcher.call(new PlayerStartDiggingEvent(player, base, block, pos, p.blockFace()));
            return true;
        }
        if (finish) {
            if (breakTicks == BlockBreakCalculation.UNBREAKABLE) { // hacked-client FINISH on bedrock
                EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
                resync(shard, player, pos, pos);
                return true;
            }
            PlayerFinishDiggingEvent finishEvent = new PlayerFinishDiggingEvent(player, base, block, pos);
            EventDispatcher.call(finishEvent);
            block = finishEvent.getBlock();
        }
        PlayerBlockBreakEvent event = new PlayerBlockBreakEvent(player, base, block, Block.AIR, pos, p.blockFace());
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            resync(shard, player, pos, pos);
            return true;
        }
        shard.setBlock(pos, event.getResultBlock());
        var fx = new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), pos, block.stateId(), false);
        for (Player w : shard.watchers()) {
            if (w != player) w.sendPacket(fx);
        }
        return true;
    }

    // upstream PlayerActionListener.shouldPreventBreaking
    private static boolean shouldPreventBreaking(Player player, Block block) {
        ItemStack held = player.getItemInMainHand();
        return switch (player.getGameMode()) {
            case SPECTATOR -> true;
            case ADVENTURE -> !held.get(DataComponents.CAN_BREAK, BlockPredicates.NEVER).test(block);
            case CREATIVE -> {
                Tool tool = held.get(DataComponents.TOOL);
                yield tool != null && !tool.canDestroyBlocksInCreative();
            }
            default -> false;
        };
    }

    /** A non-member's (or view-swapped member's) edit: swallowed, the client corrected from its viewed world. */
    private static boolean readOnlyPlace(Player player, @Nullable Shard viewed, ClientPlayerBlockPlacementPacket p) {
        if (!player.getItemInHand(p.hand()).material().isBlock()) return false;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        player.getInventory().update();
        BlockVec clicked = p.blockPosition().asBlockVec();
        sendViewedBlock(player, viewed, clicked);
        sendViewedBlock(player, viewed, clicked.relative(p.blockFace()));
        return true;
    }

    private static boolean readOnlyDig(Player player, @Nullable Shard viewed, ClientPlayerActionPacket p) {
        var status = p.status();
        if (status != ClientPlayerActionPacket.Status.STARTED_DIGGING
                && status != ClientPlayerActionPacket.Status.FINISHED_DIGGING
                && status != ClientPlayerActionPacket.Status.CANCELLED_DIGGING) return false;
        player.sendPacket(new AcknowledgeBlockChangePacket(p.sequence()));
        if (status != ClientPlayerActionPacket.Status.CANCELLED_DIGGING) {
            sendViewedBlock(player, viewed, p.blockPosition().asBlockVec());
        }
        return true;
    }

    private static void sendViewedBlock(Player player, @Nullable Shard viewed, Point pos) {
        Instance world = player.getInstance();
        if (world == null || !world.isChunkLoaded(pos)) return;
        Block block = viewed != null ? viewed.getBlock(pos) : world.getBlock(pos);
        player.sendPacket(new BlockChangePacket(pos, block));
    }

    /** Whether a block at {@code pos} would overlap a shard entity/member (upstream canPlaceBlockAt over the shard). */
    private static boolean intersectsEntity(Shard shard, Point pos, Player placer, Block placing) {
        boolean placerExempt = selfPlaceExempt.test(placing);
        Shape shape = placing.registry().collisionShape();
        for (Entity e : shard.entities) {
            if (!e.isRemoved() && intersects(shape, pos, e)) return true;
        }
        for (Player m : shard.members()) {
            if (m == placer && placerExempt) continue;
            if (intersects(shape, pos, m)) return true;
        }
        return false;
    }

    private static boolean intersects(Shape shape, Point pos, Entity e) {
        if (!e.preventBlockPlacement()) return false; // items/arrows never block placement (vanilla)
        Point ep = e.getPosition();
        if (e instanceof Player) ep = ep.add(ep.sub(pos).mul(0.0000001)); // upstream: nudged off the block edge
        return shape.intersectBox(ep.sub(pos), e.getBoundingBox());
    }

    /** A rejected placement: the client already decremented its held stack, so refresh the inventory too. */
    private static void rejectPlace(Shard shard, Player actor, Point clicked, Point target) {
        actor.getInventory().update();
        resync(shard, actor, clicked, target);
    }

    /** Re-asserts the shard view of the interaction site to the actor (whose client predicted a change). */
    private static void resync(Shard shard, Player actor, Point clicked, Point target) {
        actor.sendPacket(new BlockChangePacket(clicked, shard.getBlock(clicked)));
        if (!target.equals(clicked)) actor.sendPacket(new BlockChangePacket(target, shard.getBlock(target)));
    }
}
