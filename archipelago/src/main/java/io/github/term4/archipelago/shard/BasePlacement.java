package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.BlockPredicates;
import net.minestom.server.item.component.ItemBlockState;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;
import net.minestom.server.world.DimensionType;

/**
 * Minestom's stock {@code BlockPlacementListener} (2026.08.28-26.2, the pinned build, faithful copy) with TWO marked changes:
 * the entity-collision scan is scoped through {@link WorldPolicy#canAffect}, and a replaceable clicked block
 * is replaced in place (vanilla/client-prediction parity, PR'd upstream). Shard members' placements are
 * packet-intercepted by {@link BlockRouting} and never reach this; it serves players OUTSIDE any shard view.
 * Re-sync the copy on Minestom bumps.
 */
final class BasePlacement {
    private static final BlockManager BLOCK_MANAGER = MinecraftServer.getBlockManager();

    private BasePlacement() {}

    static void listener(ClientPlayerBlockPlacementPacket packet, Player player) {
        final PlayerHand hand = packet.hand();
        final BlockFace blockFace = packet.blockFace();
        Point blockPosition = packet.blockPosition();

        final Instance instance = player.getInstance();
        if (instance == null) {
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        // Prevent outdated/modified client data
        final Chunk interactedChunk = instance.getChunkAt(blockPosition);
        if (!ChunkUtils.isLoaded(interactedChunk)) {
            // Client tried to place a block in an unloaded chunk; ignore the request but still ack to reset the client prediction
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        final ItemStack usedItem = player.getItemInHand(hand);
        final Block interactedBlock = instance.getBlock(blockPosition);

        // Any use of a block outside the world border is rejected before interaction handling.
        // The resulting placement position is intentionally not checked as placing outward across
        // the border by clicking a block inside of it is allowed.
        if (!instance.getWorldBorder().inBounds(blockPosition)) {
            final Point relative = blockPosition.relative(blockFace);
            if (ChunkUtils.isLoaded(instance.getChunkAt(relative))) {
                BaseBlockFilter.ownSend(() -> player.sendPacket(new BlockChangePacket(relative, instance.getBlock(relative))));
            }
            rollback(player, hand, blockPosition, interactedBlock, packet.sequence());
            return;
        }

        final Point cursorPosition = new Vec(packet.cursorPositionX(), packet.cursorPositionY(), packet.cursorPositionZ());

        // Interact at block
        PlayerBlockInteractEvent playerBlockInteractEvent = new PlayerBlockInteractEvent(player, hand, instance, interactedBlock, blockPosition.asBlockVec(), cursorPosition, blockFace);
        EventDispatcher.call(playerBlockInteractEvent);
        boolean blockUse = playerBlockInteractEvent.isBlockingItemUse();
        if (!playerBlockInteractEvent.isCancelled()) {
            final var handler = BlockRouting.handlerOf(interactedBlock);
            if (handler != null) {
                blockUse |= !handler.onInteract(new BlockHandler.Interaction(interactedBlock, instance, blockFace, blockPosition, cursorPosition, player, hand));
            }
        }
        if (blockUse) {
            // If the usage was blocked then the world is already up-to-date (from the prior handlers),
            // So ack the change with the current world state.
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        final Material useMaterial = usedItem.material();
        if (useMaterial.block() == null) {
            // Player didn't try to place a block but interacted with one
            PlayerUseItemOnBlockEvent event = new PlayerUseItemOnBlockEvent(player, hand, usedItem, blockPosition, cursorPosition, blockFace);
            EventDispatcher.call(event);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        // Verify if the player can place the block
        boolean canPlaceBlock = true;
        if (player.getGameMode() == GameMode.SPECTATOR) {
            canPlaceBlock = false; // Spectators can't place blocks
        } else if (player.getGameMode() == GameMode.ADVENTURE) {
            BlockPredicates placePredicate = usedItem.get(DataComponents.CAN_PLACE_ON, BlockPredicates.NEVER);
            canPlaceBlock = placePredicate.test(interactedBlock);
        }

        // Get the newly placed block position
        Point placementPosition = blockPosition;
        var interactedPlacementRule = BLOCK_MANAGER.getBlockPlacementRule(interactedBlock);
        // local change 2 (mirrors BlockRouting.place): a replaceable clicked block is replaced IN PLACE like
        // vanilla and the client's own prediction; upstream bumps it to the neighbor
        final boolean placeAdjacent = !interactedBlock.air() && !interactedBlock.replaceable()
                && (interactedPlacementRule == null || !interactedPlacementRule.isSelfReplaceable(
                new BlockPlacementRule.Replacement(interactedBlock, blockFace, cursorPosition, false, useMaterial)));
        // If the block is not replaceable, try to place next to it.
        if (placeAdjacent) placementPosition = blockPosition.relative(blockFace);

        final Chunk chunk = instance.getChunkAt(placementPosition);
        if (!ChunkUtils.isLoaded(chunk)) {
            // Placement into an unloaded chunk, reachable by placing against a block at the edge of loaded terrain.
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        if (placeAdjacent) {
            var placementBlock = instance.getBlock(placementPosition);
            var placementRule = BLOCK_MANAGER.getBlockPlacementRule(placementBlock);
            if (!placementBlock.replaceable() && !(placementRule != null && placementRule.isSelfReplaceable(
                    new BlockPlacementRule.Replacement(placementBlock, blockFace, cursorPosition, true, useMaterial)))) {
                // If the block is still not replaceable, cancel the placement
                canPlaceBlock = false;
            }
        }

        final DimensionType instanceDim = instance.getCachedDimensionType();
        if (placementPosition.y() >= instanceDim.maxY() || placementPosition.y() < instanceDim.minY()) {
            // Placement outside the world's build height (reachable by placing against the top/bottom block); ack to reset the prediction
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        if (!canPlaceBlock) {
            // Keep the client in sync with a targeted block change (plus a held-slot refresh) instead of a chunk resend,
            // which leaves the client desynced after rapid invalid block placements.
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        if (chunk.isReadOnly()) {
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        final ItemBlockState blockState = usedItem.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY);
        final Block placedBlock = blockState.apply(useMaterial.block());

        Entity collisionEntity = canPlaceBlockAt(instance, placementPosition,
                shaped(instance, placedBlock, blockFace, placementPosition, cursorPosition, player, usedItem), player);
        if (collisionEntity != null) {
            // If a player is trying to place a block on themselves, the client sends a block change but does not set
            // the block on its own client, so it only needs an acknowledgement.
            if (collisionEntity == player) {
                player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
                return;
            }
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        // BlockPlaceEvent check
        PlayerBlockPlaceEvent playerBlockPlaceEvent = new PlayerBlockPlaceEvent(player, instance, placedBlock, blockFace, placementPosition.asBlockVec(), cursorPosition, packet.hand());
        playerBlockPlaceEvent.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        playerBlockPlaceEvent.setDoBlockUpdates(blockState.equals(useMaterial.prototype().get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY)));
        EventDispatcher.call(playerBlockPlaceEvent);
        if (playerBlockPlaceEvent.isCancelled()) {
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        // Place the block
        Block resultBlock = playerBlockPlaceEvent.getBlock();
        instance.placeBlock(new BlockHandler.PlayerPlacement(resultBlock, instance.getBlock(placementPosition), instance, placementPosition, player, hand, blockFace,
                packet.cursorPositionX(), packet.cursorPositionY(), packet.cursorPositionZ()), playerBlockPlaceEvent.shouldDoBlockUpdates());
        player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
        // Block consuming
        if (playerBlockPlaceEvent.doesConsumeBlock()) {
            final ItemStack newUsedItem = usedItem.consume(1);
            player.setItemInHand(hand, newUsedItem);
        } else {
            // Prevent invisible item on client: it predicted a decrement that did not happen, so refresh just that slot.
            refreshUsedHandSlot(player, hand);
        }
    }

    // Refreshes only the used hand slot so the client's predicted item count stays in sync without a full resend.
    private static void refreshUsedHandSlot(Player player, PlayerHand hand) {
        final int slot = hand == PlayerHand.OFF ? PlayerInventoryUtils.OFFHAND_SLOT : player.getHeldSlot();
        player.getInventory().sendSlotRefresh(slot, player.getItemInHand(hand));
    }

    // the shape the placement rule settles on, which placeBlock only applies later: a slab merging into a double
    // fills the whole cell, and the bottom half the item names misses a body standing in the top one. Staged: this
    // run is the body check's alone, and placeBlock runs the rule again for real
    private static Block shaped(Instance instance, Block placed, BlockFace face, Point at, Point cursor,
                                Player player, ItemStack used) {
        var rule = BLOCK_MANAGER.getBlockPlacementRule(placed);
        if (rule == null) return placed;
        Block ruled = rule.blockPlace(new BlockPlacementRule.PlacementState(new StagedBlocks(instance), placed, face,
                at.asBlockVec(), cursor, player.getPosition(), used, player.isSneaking()));
        return ruled != null ? ruled : placed;
    }

    // upstream BlockCollision.canPlaceBlockAt with two local changes: the policy scope, and the per-body
    // check routed through Shard.placementBodyCheck (so a 1.8-mechanics lib governs base and shard alike)
    private static Entity canPlaceBlockAt(Instance instance, Point blockPos, Block b, Player placer) {
        for (Entity entity : instance.getNearbyEntities(blockPos, 3)) {
            if (!WorldPolicy.canAffect(placer, entity)) continue; // a co-located other-world entity is invisible to the placer
            if (BlockRouting.blocksPlacement(placer, b, blockPos, entity)) return entity;
        }
        return null;
    }

    // upstream rollback: a targeted block change + the used hand slot, never a chunk resend
    private static void rollback(Player player, PlayerHand hand, Point placementPosition, Block block, int sequence) {
        refreshUsedHandSlot(player, hand);
        BaseBlockFilter.ownSend(() -> player.sendPacket(new BlockChangePacket(placementPosition, block)));
        player.sendPacket(new AcknowledgeBlockChangePacket(sequence));
    }
}
