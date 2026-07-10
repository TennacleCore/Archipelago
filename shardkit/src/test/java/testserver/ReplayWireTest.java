package testserver;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.replay.Ghost;
import io.github.term4.shardkit.replay.Playback;
import io.github.term4.shardkit.replay.Recording;
import io.github.term4.shardkit.replay.Replay;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.network.packet.server.play.EntityStatusPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay wire behavior that needs a packet-capturing viewer ({@link FakePlayer} lives in this module). */
class ReplayWireTest extends HeadlessServerTest {

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    private static void await(String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 2000;
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertTrue(done.get(), what);
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
