package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.world.WorldAccess;
import io.github.term4.archipelago.world.WorldPolicy;
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
import net.minestom.server.event.item.PickupItemEvent;
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

import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** {@link Shard#blockRouting()}: the packet-level place/dig interception. */
final class BlockRouting {

    /** Blocks a placer may put into their OWN body (e.g. a 1.8-mechanics lib exempts ladders/vines/webs). */
    static volatile Predicate<Block> selfPlaceExempt = block -> false;

    /** Wraps the installed base placement listener; re-applied whenever it changes, so registration order never matters. */
    private static volatile UnaryOperator<BiConsumer<ClientPlayerBlockPlacementPacket, Player>> placementDecorator =
            UnaryOperator.identity();
    private static volatile boolean routingInstalled;

    private BlockRouting() {}

    static synchronized void decoratePlacement(UnaryOperator<BiConsumer<ClientPlayerBlockPlacementPacket, Player>> decorator) {
        placementDecorator = decorator;
        if (routingInstalled) installPlacementListener();
    }

    private static void installPlacementListener() {
        BiConsumer<ClientPlayerBlockPlacementPacket, Player> listener = placementDecorator.apply(BasePlacement::listener);
        MinecraftServer.getPacketListenerManager().setPlayListener(ClientPlayerBlockPlacementPacket.class, listener::accept);
    }

    static EventNode<Event> node() {
        ShardView.installDefaultPolicy();
        // non-shard players fall through to the stock listener, whose collision scan is world-blind: a
        // co-located shard entity (invisible to a base player) would block their placement
        synchronized (BlockRouting.class) {
            routingInstalled = true;
            installPlacementListener();
        }
        EventNode<Event> node = EventNode.all("archipelago:blocks");
        node.addListener(PlayerPacketEvent.class, e -> {
            var packet = e.getPacket();
            boolean place = packet instanceof ClientPlayerBlockPlacementPacket;
            if (!place && !(packet instanceof ClientPlayerActionPacket)) return;
            Player player = e.getPlayer();
            Shard bound = player.getTag(WorldAccess.ENTITY_TAG) instanceof Shard d
                    && player.getInstance() == d.instance() ? d : null;
            Shard viewed = ShardView.of(player).blocks();
            if (viewed != null && player.getInstance() != viewed.instance()) viewed = null;
            Shard edited;
            if (bound != null && bound == viewed) edited = bound;
            else if (viewed != null && bound != viewed && WorldPolicy.canEdit(player, viewed)) edited = viewed;
            else if (bound != null || viewed != null) edited = null; // read-only: swallowed + resynced
            else return;
            // unclaimed packets (interactions, throwables, drops) stay Minestom's
            if (place ? !player.getItemInHand(((ClientPlayerBlockPlacementPacket) packet).hand()).material().isBlock()
                    : !digs((ClientPlayerActionPacket) packet)) {
                return;
            }
            e.setCancelled(true);
            // ack AFTER the block work, like the stock listeners: an early ack rolls the modern client's
            // prediction back to the pre-edit state until our block packet lands
            int sequence = place ? ((ClientPlayerBlockPlacementPacket) packet).sequence()
                    : ((ClientPlayerActionPacket) packet).sequence();
            if (edited == null) { // no shard writes: resync from the viewed chain, thread-safe reads
                if (place) readOnlyPlace(player, viewed, (ClientPlayerBlockPlacementPacket) packet);
                else readOnlyDig(player, viewed, (ClientPlayerActionPacket) packet);
                player.sendPacket(new AcknowledgeBlockChangePacket(sequence));
                return;
            }
            // the write path runs on the EDITED shard's clock (overlay writes are single-writer): an
            // observer with edit rights, or a server-ticked member of a domain shard, arrives foreign
            Runnable work = () -> {
                // the queue hop is a tick wide: re-check what can change under it (disconnect, destroy, demotion)
                if (!player.isOnline() || edited.destroyed) return;
                Shard nowBound = player.getTag(WorldAccess.ENTITY_TAG) instanceof Shard d ? d : null;
                if (edited != nowBound && !(edited == ShardView.of(player).blocks() && WorldPolicy.canEdit(player, edited))) return;
                if (place) place(edited, player, (ClientPlayerBlockPlacementPacket) packet);
                else dig(edited, player, (ClientPlayerActionPacket) packet);
                player.sendPacket(new AcknowledgeBlockChangePacket(sequence));
            };
            edited.onOwner(work);
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
        // pickup is only viewer-gated upstream - an observer VIEWING a game's entities is a viewer of its
        // drops, and an uncancelled pickup REMOVES the item; same cross-world gate as the merge scan
        node.addListener(PickupItemEvent.class, e -> {
            if (!WorldPolicy.canAffect(e.getLivingEntity(), e.getItemEntity())) e.setCancelled(true);
        });
        node.addListener(PlayerChunkLoadEvent.class, e -> {
            Shard viewed = ShardView.of(e.getPlayer()).blocks();
            if (viewed == null || e.getPlayer().getInstance() != viewed.instance()) return;
            viewed.sendChunkOverlay(e.getPlayer(), e.getChunkX(), e.getChunkZ());
        });
        return node;
    }

    /** The dig statuses this routing claims; drops and item-state actions stay Minestom's. */
    private static boolean digs(ClientPlayerActionPacket p) {
        var status = p.status();
        return status == ClientPlayerActionPacket.Status.STARTED_DIGGING
                || status == ClientPlayerActionPacket.Status.FINISHED_DIGGING
                || status == ClientPlayerActionPacket.Status.CANCELLED_DIGGING;
    }

    /** Shard-backed placement (claimed: the held item is a block). */
    private static void place(Shard shard, Player player, ClientPlayerBlockPlacementPacket p) {
        Instance base = shard.instance();
        ItemStack held = player.getItemInHand(p.hand());
        Material material = held.material();
        if (!base.isChunkLoaded(p.blockPosition())) return;
        BlockVec clicked = p.blockPosition().asBlockVec();
        Vec cursor = new Vec(p.cursorPositionX(), p.cursorPositionY(), p.cursorPositionZ());
        Block interacted = shard.getBlock(clicked);

        // block-handler onInteract is skipped: overlay writes never attach handlers
        PlayerBlockInteractEvent interact = new PlayerBlockInteractEvent(player, p.hand(), base, interacted, clicked, cursor, p.blockFace());
        EventDispatcher.call(interact);
        if (interact.isBlockingItemUse()) return;

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
            return;
        }
        if (!base.getWorldBorder().inBounds(target)) canPlace = false;

        Block block = held.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY).apply(material.block());
        if (!canPlace || intersectsEntity(shard, target, player, block)) {
            rejectPlace(shard, player, clicked, target);
            return;
        }
        // orientation/shape rules over the SHARD view; the base pipeline runs them inside placeBlock - here pre-event
        var rule = manager.getBlockPlacementRule(block);
        if (rule != null) {
            Block ruled = rule.blockPlace(new BlockPlacementRule.PlacementState(
                    (x, y, z, cond) -> shard.getBlock(x, y, z, cond), block, p.blockFace(), target,
                    cursor, player.getPosition(), held, player.isSneaking()));
            if (ruled == null) { rejectPlace(shard, player, clicked, target); return; } // rule rejected the spot
            block = ruled;
        }
        PlayerBlockPlaceEvent event = new PlayerBlockPlaceEvent(player, base, block, p.blockFace(), target, cursor, p.hand());
        event.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            rejectPlace(shard, player, clicked, target);
            return;
        }
        shard.setBlock(target, event.getBlock());
        if (event.doesConsumeBlock()) player.setItemInHand(p.hand(), held.consume(1));
        else player.getInventory().update();
    }

    /** Shard-backed digging (claimed: a {@link #digs} status). Trusts the client's FINISHED timing like the base pipeline. */
    private static void dig(Shard shard, Player player, ClientPlayerActionPacket p) {
        Instance base = shard.instance();
        var status = p.status();
        boolean start = status == ClientPlayerActionPacket.Status.STARTED_DIGGING;
        boolean finish = status == ClientPlayerActionPacket.Status.FINISHED_DIGGING;
        if (!base.isChunkLoaded(p.blockPosition())) return;
        BlockVec pos = p.blockPosition().asBlockVec();
        Block block = shard.getBlock(pos);
        if (!start && !finish) { // cancelled
            EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
            return;
        }
        if (block.isAir()) return;
        if (shouldPreventBreaking(player, block)) {
            resync(shard, player, pos, pos);
            return;
        }
        int breakTicks = BlockBreakCalculation.breakTicks(block, player);
        if (start && breakTicks != 0) { // the client animates the dig; its FINISHED completes it
            EventDispatcher.call(new PlayerStartDiggingEvent(player, base, block, pos, p.blockFace()));
            return;
        }
        if (finish) {
            if (breakTicks == BlockBreakCalculation.UNBREAKABLE) { // hacked-client FINISH on bedrock
                EventDispatcher.call(new PlayerCancelDiggingEvent(player, base, block, pos));
                resync(shard, player, pos, pos);
                return;
            }
            PlayerFinishDiggingEvent finishEvent = new PlayerFinishDiggingEvent(player, base, block, pos);
            EventDispatcher.call(finishEvent);
            block = finishEvent.getBlock();
        }
        PlayerBlockBreakEvent event = new PlayerBlockBreakEvent(player, base, block, Block.AIR, pos, p.blockFace());
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            resync(shard, player, pos, pos);
            return;
        }
        shard.setBlock(pos, event.getResultBlock());
        var fx = new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), pos, block.stateId(), false);
        for (Player w : shard.watchers()) {
            if (w != player) w.sendPacket(fx);
        }
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
    private static void readOnlyPlace(Player player, @Nullable Shard viewed, ClientPlayerBlockPlacementPacket p) {
        player.getInventory().update();
        BlockVec clicked = p.blockPosition().asBlockVec();
        sendViewedBlock(player, viewed, clicked);
        sendViewedBlock(player, viewed, clicked.relative(p.blockFace()));
    }

    private static void readOnlyDig(Player player, @Nullable Shard viewed, ClientPlayerActionPacket p) {
        if (p.status() != ClientPlayerActionPacket.Status.CANCELLED_DIGGING) {
            sendViewedBlock(player, viewed, p.blockPosition().asBlockVec());
        }
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
