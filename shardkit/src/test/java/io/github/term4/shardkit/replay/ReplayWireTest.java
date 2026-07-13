package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.replay.core.Channels;
import io.github.term4.shardkit.replay.core.CoreChannels;
import io.github.term4.shardkit.replay.core.StateChannels;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.network.packet.server.play.EntityAnimationPacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.network.packet.server.play.EntityStatusPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay wire behavior that needs a packet-capturing viewer ({@link FakePlayer} lives in this module). */
class ReplayWireTest extends HeadlessServerTest {

    @BeforeAll
    static void channels() {
        CoreChannels.register();
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void ghostsDieAndRespawnWithTheirActor() {
        Shard game = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(3.5, 70, 3.5), "Mortal");
        actor.player.setRespawnPoint(new Pos(10.5, 70, 10.5));
        game.join(actor.player);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick();               // tick 1: alive
            actor.player.kill();  // death act @1
            tick();               // tick 2: corpse rests
            actor.player.respawn(); // respawn act @2; the spawn-point teleport lands in the move track
            tick();               // tick 3: fresh state re-emits
            tick();
        } finally {
            file = rec.stop();
            actor.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(6.5, 70, 6.5), "DeathViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Ghost corpse = pb.ghost("Mortal");
            assertNotNull(corpse);
            assertTrue(viewer.sent(EntityStatusPacket.class).stream()
                            .anyMatch(p -> p.entityId() == corpse.getEntityId() && p.status() == 3),
                    "the death animation reaches viewers (wire status 3)");
            tick();
            assertTrue(corpse.isRemoved(), "the respawn destroys the corpse ghost");
            tick();
            Ghost reborn = pb.ghost("Mortal");
            assertNotNull(reborn);
            assertNotSame(corpse, reborn, "a fresh ghost stands up at the respawn point");
            assertFalse(reborn.isRemoved());
            await("at the respawn point", () -> reborn.getPosition().x() == 10.5);
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    @Test
    void corpsesVanishExactlyWhenTheRecordingHidThem() {
        Shard game = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(3.5, 70, 3.5), "Lingering");
        game.join(actor.player);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick();
            actor.player.kill();
            for (int i = 0; i < 5; i++) tick();  // the corpse lies for as long as the game says
            actor.player.setAutoViewable(false); // the game's death config hides it at ITS OWN tick (here +5)
            for (int i = 0; i < 5; i++) tick();  // still dead, still hidden; moves keep sampling
        } finally {
            file = rec.stop();
            actor.player.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Ghost corpse = pb.ghost("Lingering");
            assertNotNull(corpse);
            for (int i = 0; i < 4; i++) tick();
            assertFalse(corpse.isRemoved(), "the corpse lasts exactly as long as it really did - observed, not assumed");
            for (int i = 0; i < 3; i++) tick(); // past the recorded hide
            assertTrue(corpse.isRemoved(), "gone at the recorded hide tick");
            assertNull(pb.ghost("Lingering"), "and the move track raises no ghost while the real one was hidden");
        } finally {
            pb.halt();
        }
    }

    @Test
    void stateSamplerDeltaGatesRealPlayerState() {
        StateChannels.register();
        Shard game = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(3.5, 70, 3.5), "Vitals");
        game.join(actor.player);
        actor.player.getInventory().setItemStack(0, ItemStack.of(Material.DIAMOND_SWORD));
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick(); // first sight: the baseline emits, air slots implicit
            assertEquals(1, rec.counts().getOrDefault("replay:health", 0));
            assertEquals(1, rec.counts().getOrDefault("replay:inventory", 0));
            tick(); // nothing changed: delta-gated silence
            assertEquals(1, rec.counts().getOrDefault("replay:health", 0));
            assertEquals(1, rec.counts().getOrDefault("replay:inventory", 0));
            actor.player.setHealth(11.5f);
            actor.player.getInventory().setItemStack(1, ItemStack.of(Material.GOLDEN_APPLE, 2));
            tick(); // exactly the changes emit
            assertEquals(2, rec.counts().getOrDefault("replay:health", 0));
            assertEquals(2, rec.counts().getOrDefault("replay:inventory", 0));
        } finally {
            file = rec.stop();
            actor.player.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(pb.length());
            assertEquals(11.5f, StateChannels.health(pb, "Vitals"));
            Map<Integer, ItemStack> inv = StateChannels.inventory(pb, "Vitals");
            assertEquals(Material.DIAMOND_SWORD, inv.get(0).material());
            assertEquals(2, inv.get(1).amount());
        } finally {
            pb.halt();
        }
    }

    @Test
    void reverseCrossingsReplayOneShotFx() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick(); // tick 1
            rec.emit(Channels.MOVES, new Channels.Move("Steve", 1.5, 70, 1.5, 0, 0, true));
            rec.emit(Channels.DIGS, new Channels.Dig(0, 5, 70, 5, "Steve", true));
            tick(); // tick 2
            rec.emit(Channels.ACTS, new Channels.Act("Steve", Channels.SWING_MAIN, 0));
            tick(); // tick 3
            tick(); // tick 4: the break ends the dig window
            rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, 5, 70, 5, "minecraft:air"));
            tick(); // tick 5
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(6.5, 70, 6.5), "RewindViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            pb.seek(5);
            Ghost ghost = pb.ghost("Steve");
            assertNotNull(ghost);
            viewer.sent.clear();

            pb.reverse(true); // before any forward step: a drained playback at length ends itself
            tick(); // -> 4 (the ghost spawn lands en route)
            tick(); // -> 3: the break un-crosses, the timeline is back inside the dig window
            assertTrue(viewer.sent(BlockBreakAnimationPacket.class).stream()
                            .anyMatch(p -> p.entityId() == ghost.getEntityId() && p.destroyStage() >= 0),
                    "backing into a dig window re-raises the crack");
            tick(); // -> 2
            tick(); // -> 1: the swing act un-crosses
            assertTrue(viewer.sent(EntityAnimationPacket.class).stream()
                            .anyMatch(p -> p.entityId() == ghost.getEntityId()),
                    "one-shot swings play when crossed in reverse");
            tick(); // -> 0: the dig start un-crosses
            assertTrue(viewer.sent(BlockBreakAnimationPacket.class).stream()
                            .anyMatch(p -> p.entityId() == ghost.getEntityId() && p.destroyStage() == -1),
                    "crossing the dig start clears the crack");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    @Test
    void ghostEquipmentNeverTouchesNeverWornSlots() {
        Shard game = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, new Pos(3.5, 70, 3.5), "ArmorActor");
        game.join(actor.player);
        ItemStack chest = ItemStack.of(Material.DIAMOND_CHESTPLATE);
        actor.player.setChestplate(chest);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick();
            actor.player.setChestplate(ItemStack.AIR); // mid-recording unequip
            tick();
            tick();
        } finally {
            file = rec.stop();
            actor.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(1.5, 70, 1.5), "ArmorViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            tick();
            tick();
            Ghost ghost = pb.ghost("ArmorActor");
            assertNotNull(ghost);
            // single-slot packets = the equip applier's writes (the spawn path sends one full-map packet)
            List<EntityEquipmentPacket> writes = viewer.sent(EntityEquipmentPacket.class).stream()
                    .filter(p -> p.entityId() == ghost.getEntityId() && p.equipments().size() == 1).toList();
            assertTrue(writes.stream().anyMatch(p -> chest.equals(p.equipments().get(EquipmentSlot.CHESTPLATE))),
                    "the recorded chestplate reaches viewers");
            assertTrue(writes.stream().anyMatch(p -> ItemStack.AIR.equals(p.equipments().get(EquipmentSlot.CHESTPLATE))),
                    "the recorded unequip clears it (a single-slot AIR clear is the legit wire shape)");
            // regression: an explicit BODY=AIR write is the ViaBackwards chestplate wipe on 1.8 clients
            assertTrue(writes.stream().allMatch(p -> p.equipments().containsKey(EquipmentSlot.CHESTPLATE)),
                    "never-worn slots are never written");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
