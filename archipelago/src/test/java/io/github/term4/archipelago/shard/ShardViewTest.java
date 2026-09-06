package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.SetTimePacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-observer view aspects + the packet-routed edit paths they gate (member edit / read-only / vanilla). */
class ShardViewTest extends HeadlessServerTest {

    private static FakePlayer member;
    private static FakePlayer spectator;
    private static FakePlayer bystander;
    private static EventNode<Event> routing;

    @BeforeAll
    static void connectPlayers() {
        routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        member = FakePlayer.connect(instance, new Pos(0.5, 64, 0.5), "ShardMember");
        spectator = FakePlayer.connect(instance, new Pos(5.5, 64, 5.5), "ShardSpec");
        bystander = FakePlayer.connect(instance, new Pos(8.5, 64, 8.5), "ShardBystander");
    }

    @AfterAll
    static void disconnectPlayers() {
        member.player.remove();
        spectator.player.remove();
        bystander.player.remove();
        MinecraftServer.getGlobalEventHandler().removeChild(routing);
    }

    private static boolean dispatch(Player player, Object packet) {
        PlayerPacketEvent event = new PlayerPacketEvent(player, (ClientPacket) packet);
        EventDispatcher.call(event);
        return event.isCancelled();
    }

    private static ClientPlayerBlockPlacementPacket placePacket(int x, int y, int z) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new BlockVec(x, y, z), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 0);
    }

    private static ClientPlayerActionPacket digPacket(ClientPlayerActionPacket.Status status, int x, int y, int z) {
        return new ClientPlayerActionPacket(status, new BlockVec(x, y, z), BlockFace.TOP, 0);
    }

    @Test
    void viewDefaultsFollowMembership() {
        Shard shard = new Shard(instance);
        assertNull(ShardView.of(member.player).blocks(), "unbound = base view");
        assertTrue(ShardView.of(member.player).seesEntitiesOf(null), "base view sees main-world entities");
        shard.join(member.player);
        try {
            assertSame(shard, ShardView.of(member.player).blocks(), "member default = their shard's view");
            assertTrue(ShardView.of(member.player).seesEntitiesOf(shard));
            assertFalse(ShardView.of(member.player).seesEntitiesOf(null), "members don't see main-world entities");
        } finally {
            shard.leave(member.player);
        }
        assertNull(ShardView.of(member.player).blocks());
    }

    @Test
    void joinClearsTheExplicitView() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        ShardView.set(member.player, ShardView.of(b));
        try {
            assertSame(b, ShardView.of(member.player).blocks());
            a.join(member.player);
            assertSame(a, ShardView.of(member.player).blocks(), "join = the member preset, explicit view dropped");
            a.leave(member.player);
            assertNull(ShardView.of(member.player).blocks(), "the pre-join explicit view does not resurrect");
        } finally {
            ShardView.set(member.player, null);
            a.leave(member.player);
        }
    }

    @Test
    void blockChangesReachBlockViewersOnly() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        ShardView.set(spectator.player, ShardView.of(shard));
        try {
            assertTrue(shard.players().contains(member.player));
            assertFalse(shard.players().contains(spectator.player), "observers are not game members");
            assertTrue(shard.watchers().contains(member.player));
            assertTrue(shard.watchers().contains(spectator.player));
            assertFalse(shard.watchers().contains(bystander.player));

            member.sent.clear(); spectator.sent.clear(); bystander.sent.clear();
            shard.setBlock(new BlockVec(7, 64, 7), Block.STONE);
            assertEquals(1, member.sent(BlockChangePacket.class).size());
            assertEquals(1, spectator.sent(BlockChangePacket.class).size(), "block view = shard -> gets the diff");
            assertEquals(0, bystander.sent(BlockChangePacket.class).size(), "base view never sees shard diffs");
        } finally {
            ShardView.set(spectator.player, null);
            shard.leave(member.player);
        }
    }

    @Test
    void observerEditsAreReadOnly() {
        Shard shard = new Shard(instance);
        ShardView.set(spectator.player, ShardView.of(shard));
        spectator.player.setItemInMainHand(ItemStack.of(Material.STONE, 5));
        try {
            spectator.sent.clear();
            assertTrue(dispatch(spectator.player, placePacket(4, 63, 4)), "swallowed, never reaches Minestom");
            assertTrue(shard.getBlock(4, 64, 4).air(), "nothing lands in the overlay");
            assertTrue(instance.getBlock(4, 64, 4).air(), "nothing lands in the base map");
            assertEquals(5, spectator.player.getItemInMainHand().amount(), "nothing consumed");
            assertFalse(spectator.sent(BlockChangePacket.class).isEmpty(), "the client's prediction is corrected");

            assertTrue(dispatch(spectator.player, digPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, 4, 63, 4)));
            assertTrue(shard.getBlock(4, 63, 4).compare(Block.STONE), "digs don't land either");
        } finally {
            ShardView.set(spectator.player, null);
            spectator.player.setItemInMainHand(ItemStack.AIR);
        }
    }

    @Test
    void editsMarshalOntoTheDomainShardsClock() {
        ExecutionDomain domain = ExecutionDomain.create();
        Shard shard = new Shard(instance, domain);
        shard.join(member.player); // a plain Player isn't covered: a server-ticked member, foreign to the clock
        member.player.setItemInMainHand(ItemStack.of(Material.STONE, 5));
        try {
            member.sent.clear();
            assertTrue(dispatch(member.player, placePacket(7, 63, 7)), "claimed + cancelled synchronously");
            // the write and the trailing ack run on the shard's own clock, not this thread
            await("the placement lands on the domain clock", () -> shard.getBlock(7, 64, 7).compare(Block.STONE));
            await("the ack follows the block work", () -> !member.sent(AcknowledgeBlockChangePacket.class).isEmpty());
            assertTrue(instance.getBlock(7, 64, 7).air(), "the base map is never written");
        } finally {
            shard.leave(member.player);
            member.player.setItemInMainHand(ItemStack.AIR);
            domain.retire();
        }
    }

    @Test
    void crossWorldPickupsAreCancelled() {
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        ItemEntity drop = new ItemEntity(ItemStack.of(Material.DIAMOND));
        a.spawn(drop, new Pos(1.5, 64, 1.5));
        b.join(member.player);
        try {
            PickupItemEvent cross = new PickupItemEvent(member.player, drop);
            EventDispatcher.call(cross);
            assertTrue(cross.isCancelled(), "a foreign world's drop never picks up (the scan is viewer-gated only)");
            a.join(member.player);
            PickupItemEvent own = new PickupItemEvent(member.player, drop);
            EventDispatcher.call(own);
            assertFalse(own.isCancelled(), "own-world pickups pass");
        } finally {
            a.leave(member.player);
            drop.remove();
        }
    }

    @Test
    void memberPlaceLandsInTheOverlay() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        member.player.setItemInMainHand(ItemStack.of(Material.STONE, 5));
        try {
            assertTrue(dispatch(member.player, placePacket(2, 63, 2)));
            assertTrue(shard.getBlock(2, 64, 2).compare(Block.STONE));
            assertTrue(instance.getBlock(2, 64, 2).air(), "the base map is never written");
            assertEquals(4, member.player.getItemInMainHand().amount(), "survival consumes the block");
        } finally {
            shard.leave(member.player);
            member.player.setItemInMainHand(ItemStack.AIR);
        }
    }

    @Test
    void rejectedPlaceResyncsTheClientsAdjacentCandidate() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        member.player.setItemInMainHand(ItemStack.of(Material.TNT, 5));
        EventNode<Event> probe = EventNode.all("test:place-cancel");
        probe.addListener(net.minestom.server.event.player.PlayerBlockPlaceEvent.class, e -> e.setCancelled(true));
        MinecraftServer.getGlobalEventHandler().addChild(probe);
        try {
            member.sent.clear();
            // clicked air: the server's target is the clicked spot itself, but a client with a lagged
            // phantom block there predicted one PAST it - the rejection must re-assert both
            assertTrue(dispatch(member.player, placePacket(3, 66, 3)));
            var corrections = member.sent(BlockChangePacket.class);
            assertTrue(corrections.stream().anyMatch(p -> p.blockPosition().blockY() == 66),
                    "the server's target comes back");
            assertTrue(corrections.stream().anyMatch(p -> p.blockPosition().blockY() == 67),
                    "the adjacent candidate comes back too");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(probe);
            member.player.setItemInMainHand(ItemStack.AIR);
            shard.leave(member.player);
        }
    }

    @Test
    void spectatorGamemodeCannotPlace() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        member.player.setGameMode(GameMode.SPECTATOR);
        member.player.setItemInMainHand(ItemStack.of(Material.STONE));
        try {
            assertTrue(dispatch(member.player, placePacket(6, 63, 6)));
            assertTrue(shard.getBlock(6, 64, 6).air(), "base-pipeline parity: spectators place nothing");
        } finally {
            member.player.setGameMode(GameMode.SURVIVAL);
            member.player.setItemInMainHand(ItemStack.AIR);
            shard.leave(member.player);
        }
    }

    @Test
    void bulkSetBlocksBatchesPerSection() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        try {
            member.sent.clear(); bystander.sent.clear();
            Map<Point, Block> tower = new HashMap<>();
            for (int y = 64; y <= 80; y++) tower.put(new BlockVec(10, y, 10), Block.OAK_PLANKS); // spans sections 4 + 5
            shard.setBlocks(tower);
            var batches = member.sent(MultiBlockChangePacket.class);
            assertEquals(2, batches.size(), "one packet per touched section");
            assertEquals(17, batches.stream().mapToInt(p -> p.blocks().length).sum(), "every block delivered once");
            assertEquals(0, member.sent(BlockChangePacket.class).size(), "no per-block packets");
            assertEquals(0, bystander.sent(MultiBlockChangePacket.class).size());
            assertTrue(shard.getBlock(10, 80, 10).compare(Block.OAK_PLANKS));
            assertTrue(instance.getBlock(10, 70, 10).air(), "the base map is never written");
        } finally {
            shard.leave(member.player);
            shard.reset();
        }
    }

    @Test
    void policyGrantedObserverEditsLand() {
        Shard shard = new Shard(instance);
        ShardView.set(spectator.player, ShardView.of(shard));
        spectator.player.setItemInMainHand(ItemStack.of(Material.STONE, 5));
        WorldPolicy.set(new ShardPolicy() {
            @Override public boolean edits(@NotNull Player player, @NotNull MechanicsWorld viewed) {
                return player == spectator.player;
            }
        });
        try {
            assertTrue(dispatch(spectator.player, placePacket(9, 63, 9)));
            assertTrue(shard.getBlock(9, 64, 9).compare(Block.STONE), "granted build access lands in the viewed overlay");
            assertTrue(instance.getBlock(9, 64, 9).air());
        } finally {
            WorldPolicy.set(ShardPolicy.DEFAULT);
            ShardView.set(spectator.player, null);
            spectator.player.setItemInMainHand(ItemStack.AIR);
        }
    }

    @Test
    void destroyStripsOnlyTheDeadShardFromViews() {
        ShardManager manager = new ShardManager();
        Shard a = manager.create(instance);
        Shard b = manager.create(instance);
        ShardView.set(spectator.player, ShardView.of(a).withEntities(b));
        try {
            manager.destroy(a);
            assertNull(ShardView.of(spectator.player).blocks(), "the dead shard's block view drops");
            assertTrue(ShardView.of(spectator.player).seesEntitiesOf(b), "the surviving shard stays visible");
            assertTrue(b.observers().contains(spectator.player), "registry intact for the survivor");
            manager.destroy(b);
            assertTrue(ShardView.of(spectator.player).seesEntitiesOf(null), "no shard refs left -> back to the base view");
        } finally {
            ShardView.set(spectator.player, null);
        }
    }

    @Test
    void perShardSkyReachesBlockViewersOnly() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        try {
            member.sent.clear(); bystander.sent.clear();
            shard.setTime(18000);
            shard.setWeather(new Weather(1f, 0f));
            assertEquals(1, member.sent(SetTimePacket.class).size());
            assertEquals(3, member.sent(ChangeGameStatePacket.class).size());
            assertEquals(0, bystander.sent(SetTimePacket.class).size());
            assertEquals(0, bystander.sent(ChangeGameStatePacket.class).size());
            assertEquals(18000, shard.time());
            assertTrue(shard.weather().isRaining());

            member.sent.clear();
            shard.leave(member.player);
            assertEquals(1, member.sent(SetTimePacket.class).size(), "base sky re-sent on leave");
        } finally {
            shard.leave(member.player);
        }
    }

    @Test
    void memberDigFiresStandardEventsAndBreakFx() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        ShardView.set(spectator.player, ShardView.of(shard));
        shard.setBlock(new BlockVec(3, 64, 3), Block.STONE);
        List<PlayerStartDiggingEvent> started = new ArrayList<>();
        List<PlayerBlockBreakEvent> broken = new ArrayList<>();
        EventNode<Event> probe = EventNode.all("test:shard-dig-probe");
        probe.addListener(PlayerStartDiggingEvent.class, started::add);
        probe.addListener(PlayerBlockBreakEvent.class, broken::add);
        MinecraftServer.getGlobalEventHandler().addChild(probe);
        try {
            assertTrue(dispatch(member.player, digPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, 3, 64, 3)));
            assertEquals(1, started.size(), "crack overlays hook the standard digging events");
            assertTrue(shard.getBlock(3, 64, 3).compare(Block.STONE), "start alone breaks nothing");

            member.sent.clear(); spectator.sent.clear();
            assertTrue(dispatch(member.player, digPacket(ClientPlayerActionPacket.Status.FINISHED_DIGGING, 3, 64, 3)));
            assertTrue(shard.getBlock(3, 64, 3).air(), "the client's FINISHED completes the dig");
            assertEquals(1, broken.size(), "the standard break event fires");
            assertEquals(1, spectator.sent(WorldEventPacket.class).size(), "default break fx reaches watchers");
            assertEquals(0, member.sent(WorldEventPacket.class).size(), "the breaker predicts its own break");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(probe);
            ShardView.set(spectator.player, null);
            shard.leave(member.player);
            shard.reset();
        }
    }

    @Test
    void breakFxReplacementSupersedesTheDefault() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        ShardView.set(spectator.player, ShardView.of(shard));
        shard.setBlock(new BlockVec(3, 65, 3), Block.STONE);
        Shard.BreakFx previous = BlockRouting.breakFx;
        List<Block> routed = new ArrayList<>();
        Shard.breakFx((breaker, in, pos, brokenBlock) -> routed.add(brokenBlock));
        try {
            spectator.sent.clear();
            assertTrue(dispatch(member.player, digPacket(ClientPlayerActionPacket.Status.FINISHED_DIGGING, 3, 65, 3)));
            assertEquals(List.of(Block.STONE), routed, "the replacement receives the broken block");
            assertEquals(0, spectator.sent(WorldEventPacket.class).size(), "the default no longer emits");
        } finally {
            BlockRouting.breakFx = previous;
            ShardView.set(spectator.player, null);
            shard.leave(member.player);
            shard.reset();
        }
    }

    @Test
    void shardSwapNeverFlashesThroughBaseVisibility() {
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(8.5, 64, 0.5), "BlipWatcher"); // stays base-bound
        FakePlayer swapper = FakePlayer.connect(instance, new Pos(9.5, 64, 0.5), "BlipSwapper");
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        try {
            a.join(swapper.player);
            assertFalse(swapper.player.isViewer(watcher.player), "a shard member is hidden from base players");
            watcher.sent.clear();
            swapper.sent.clear();
            b.join(swapper.player); // the a->b swap must not pass through an unbound (base-visible) frame
            assertFalse(swapper.player.isViewer(watcher.player));
            assertTrue(watcher.packetsFor(swapper.player.getEntityId()).isEmpty(), "the base player never saw the swapper");
            assertTrue(swapper.packetsFor(watcher.player.getEntityId()).isEmpty(), "the swapper never saw base players");
        } finally {
            b.leave(swapper.player);
            swapper.player.remove();
            watcher.player.remove();
        }
    }

    @Test
    void entitiesOnlyNoBlockFeedback() {
        Shard shard = new Shard(instance);
        shard.join(member.player);
        ShardView.set(spectator.player, ShardView.base().withEntities(shard));
        shard.setBlock(new BlockVec(4, 64, 4), Block.STONE);
        try {
            member.sent.clear(); spectator.sent.clear();
            assertTrue(dispatch(member.player, digPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, 4, 64, 4)));
            assertTrue(dispatch(member.player, digPacket(ClientPlayerActionPacket.Status.FINISHED_DIGGING, 4, 64, 4)));
            assertTrue(shard.getBlock(4, 64, 4).air());
            assertEquals(0, spectator.sent(WorldEventPacket.class).size(), "no break fx for a block they never saw");
            assertEquals(0, spectator.sent(BlockChangePacket.class).size(), "and no block change either");
        } finally {
            ShardView.set(spectator.player, null);
            shard.leave(member.player);
            shard.reset();
        }
    }

    @Test
    void otherInstanceHearsNothing() {
        Instance elsewhere = MinecraftServer.getInstanceManager().createInstanceContainer();
        FakePlayer far = FakePlayer.connect(elsewhere, new Pos(0.5, 64, 0.5), "ShardFar");
        Shard shard = new Shard(instance);
        shard.join(member.player);
        ShardView.set(far.player, ShardView.base().withEntities(shard));
        try {
            far.sent.clear();
            shard.broadcast(new ChangeGameStatePacket(ChangeGameStatePacket.Reason.RAIN_LEVEL_CHANGE, 0f));
            assertEquals(0, far.sent(ChangeGameStatePacket.class).size(), "a world's packets never cross instances");
            assertFalse(shard.watchers().contains(far.player));
        } finally {
            ShardView.set(far.player, null);
            far.player.remove();
            shard.leave(member.player);
            MinecraftServer.getInstanceManager().unregisterInstance(elsewhere);
        }
    }
}
