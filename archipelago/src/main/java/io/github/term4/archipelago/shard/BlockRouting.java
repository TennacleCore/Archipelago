package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.polyp.world.Recipients;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
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
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
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
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.BlockFace;
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
import java.util.function.UnaryOperator;

/** {@link Shard#blockRouting()}: the packet-level place/dig interception. */
final class BlockRouting {

    /** {@link Shard#placementBodyCheck}: one body vs one placement; default = precise collision-shape intersection. */
    static volatile Shard.PlacementBodyCheck placementBodyCheck =
            (placer, body, block, rel, box) -> block.collisionShape().intersectBox(rel, box);

    /** {@link Shard#breakFx}: replaceable so effects stay a mechanics concern, with a vanilla default. */
    static volatile Shard.BreakFx breakFx = BlockRouting::defaultBreakFx;

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
            Shard bound = player.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard d
                    && player.getInstance() == d.instance() ? d : null;
            Shard viewed = ShardView.of(player).blocks();
            if (viewed != null && player.getInstance() != viewed.instance()) viewed = null;
            Shard edited;
            if (bound != null && bound == viewed) edited = bound;
            else if (viewed != null && bound != viewed && WorldPolicy.canEdit(player, viewed)) edited = viewed;
            else if (bound != null || viewed != null) edited = null; // read-only: swallowed + resynced
            else return;
            // unclaimed packets (throwables, drops) stay Minestom's; a click on a handler'd shard block is ours
            if (place ? player.getItemInHand(((ClientPlayerBlockPlacementPacket) packet).hand()).material().block() == null
                    && !handlerAt(edited != null ? edited : viewed, (ClientPlayerBlockPlacementPacket) packet)
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
                Shard nowBound = player.getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard d ? d : null;
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
            if (e.getPlayer().getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard shard) shard.leave(e.getPlayer());
            ShardView.forget(e.getPlayer());
        });
        node.addListener(EntityDespawnEvent.class, e -> {
            if (e.getEntity().getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard shard) shard.entities.remove(e.getEntity());
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

    // overlay writes attach no handler and a loaded block entity carries a Dummy: the registered rule wins over
    // both, a real attached handler over the rule (stock: false = consumed)
    static @Nullable BlockHandler handlerOf(Block block) {
        BlockHandler attached = block.handler();
        if (attached != null && !(attached instanceof BlockHandler.Dummy)) return attached;
        BlockHandler registered = MinecraftServer.getBlockManager().getHandler(block.name());
        return registered != null ? registered : attached;
    }

    private static boolean handlerAt(@Nullable Shard shard, ClientPlayerBlockPlacementPacket p) {
        if (shard == null || !shard.instance().isChunkLoaded(p.blockPosition())) return false;
        Block b = shard.getBlock(p.blockPosition().asBlockVec());
        return b.handler() != null || MinecraftServer.getBlockManager().getHandler(b.name()) != null;
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

        PlayerBlockInteractEvent interact = new PlayerBlockInteractEvent(player, p.hand(), base, interacted, clicked, cursor, p.blockFace());
        EventDispatcher.call(interact);
        boolean blockUse = interact.isBlockingItemUse();
        if (!interact.isCancelled()) {
            BlockHandler handler = handlerOf(interacted);
            if (handler != null) {
                blockUse |= !handler.onInteract(new BlockHandler.Interaction(
                        interacted, base, p.blockFace(), clicked, cursor, player, p.hand()));
            }
        }
        if (blockUse) { // the routing's caller acks after the work
            player.getInventory().update();
            return;
        }
        if (material.block() == null) { // claimed for the handler; the stock non-block tail
            EventDispatcher.call(new PlayerUseItemOnBlockEvent(player, p.hand(), held, clicked, cursor, p.blockFace()));
            return;
        }

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
        if (interacted.replaceable() || (interactedRule != null && interactedRule.isSelfReplaceable(
                new BlockPlacementRule.Replacement(interacted, p.blockFace(), cursor, false, material)))) {
            target = clicked;
        } else {
            target = clicked.relative(p.blockFace());
            Block atTarget = shard.getBlock(target);
            var targetRule = manager.getBlockPlacementRule(atTarget);
            if (!atTarget.replaceable() && !(targetRule != null && targetRule.isSelfReplaceable(
                    new BlockPlacementRule.Replacement(atTarget, p.blockFace(), cursor, true, material)))) {
                canPlace = false;
            }
        }

        DimensionType dim = shard.dimension();
        if (target.y() >= dim.maxY() || target.y() < dim.minY()) {
            rejectPlace(shard, player, clicked, target, p.blockFace());
            return;
        }
        if (!base.getWorldBorder().inBounds(target)) canPlace = false;

        Block block = held.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY).apply(material.block());
        if (!canPlace || intersectsEntity(shard, player, target, block)) {
            rejectPlace(shard, player, clicked, target, p.blockFace());
            return;
        }
        // orientation/shape rules over the SHARD view; the base pipeline runs them inside placeBlock - here pre-event
        var rule = manager.getBlockPlacementRule(block);
        if (rule != null) {
            Block ruled = rule.blockPlace(new BlockPlacementRule.PlacementState(
                    new ShardBlockView(shard), block, p.blockFace(), target,
                    cursor, player.getPosition(), held, player.isSneaking()));
            if (ruled == null) { rejectPlace(shard, player, clicked, target, p.blockFace()); return; } // rule rejected the spot
            block = ruled;
        }
        PlayerBlockPlaceEvent event = new PlayerBlockPlaceEvent(player, base, block, p.blockFace(), target, cursor, p.hand());
        event.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        EventDispatcher.call(event);
        if (event.isCancelled()) {
            rejectPlace(shard, player, clicked, target, p.blockFace());
            return;
        }
        shard.setBlock(target, event.getBlock());
        ShardUpdates.applyPhysics(shard, target);
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
        if (block.air()) return;
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
        ShardUpdates.applyPhysics(shard, pos);
        breakFx.play(player, shard, pos, block);
    }

    private static void defaultBreakFx(Player breaker, Shard shard, Point pos, Block broken) {
        // world event 2001: the client derives sound + particles from the block id; the breaker predicts its own
        var fx = new WorldEventPacket(WorldEvent.PARTICLES_DESTROY_BLOCK.id(), pos, broken.stateId(), false);
        Recipients.except(Recipients.BLOCK_VIEWERS, Recipients.SOURCE).each(shard, pos, breaker, (watcher, at) -> watcher.sendPacket(fx));
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
    private static boolean intersectsEntity(Shard shard, Player placer, Point pos, Block placing) {
        for (Entity e : shard.entities) {
            if (!e.isRemoved() && blocksPlacement(placer, placing, pos, e)) return true;
        }
        for (Player m : shard.members()) {
            if (blocksPlacement(placer, placing, pos, m)) return true;
        }
        return false;
    }

    static boolean blocksPlacement(Player placer, Block placing, Point pos, Entity e) {
        if (!e.preventBlockPlacement()) return false; // items/arrows never block placement (vanilla)
        Point ep = e.getPosition();
        if (e instanceof Player) ep = ep.add(ep.sub(pos).mul(0.0000001)); // upstream: nudged off the block edge
        return placementBodyCheck.blocks(placer, e, placing, ep.sub(pos), e.getBoundingBox());
    }

    /**
     * A rejected placement: the client already decremented its held stack, so refresh the inventory too.
     * The adjacent spot is re-asserted as well - the client picked adjacent-vs-replace on ITS view, so a
     * lagged phantom at the clicked spot puts its prediction one block past the server's {@code target}.
     */
    private static void rejectPlace(Shard shard, Player actor, Point clicked, Point target, BlockFace face) {
        actor.getInventory().update();
        resync(shard, actor, clicked, target);
        Point adjacent = clicked.relative(face);
        if (!adjacent.equals(clicked) && !adjacent.equals(target) && actor.getInstance() != null
                && actor.getInstance().isChunkLoaded(adjacent)) {
            actor.sendPacket(new BlockChangePacket(adjacent, shard.getBlock(adjacent)));
        }
    }

    /** Re-asserts the shard view of the interaction site to the actor (whose client predicted a change). */
    private static void resync(Shard shard, Player actor, Point clicked, Point target) {
        actor.sendPacket(new BlockChangePacket(clicked, shard.getBlock(clicked)));
        if (!target.equals(clicked)) actor.sendPacket(new BlockChangePacket(target, shard.getBlock(target)));
    }
}
