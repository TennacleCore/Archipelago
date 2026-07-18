package io.github.term4.archipelago.replay;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.mechanics.projectile.types.FishingBobber;
import io.github.term4.minestommechanics.mechanics.projectile.types.Snowball;
import io.github.term4.minestommechanics.presets.vanilla18.Vanilla18;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.replay.channel.Recorded;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.ReplayBridge;
import test.ShardBridge;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanics<->replay registration seams with REAL mechanics classes: launch capture with shooter attribution,
 * descriptor sanitizing, reviver upgrades, ghost ownership handoff. (Plain-entity coverage lives in the replay
 * module; these seams structurally need the foreign vocabulary.)
 */
class ReplayBridgeTest extends HeadlessServerTest {

    private static MinestomMechanics mm;
    private static ProjectileSystem projectiles;

    @BeforeAll
    static void mechanics() {
        if (mm != null) return;
        mm = MinestomMechanics.getInstance();
        mm.init(); // internally guarded - safe alongside ShardBridgeTest's boot
        ShardBridge.install();
        ReplayBridge.install();
        projectiles = new ProjectileSystem(mm, Vanilla18.projectiles());
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("mm:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("mm:projectile", projectiles::fromSave);
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void itemTwinWireStaysQuiet() throws Exception {
        ShardBridge.installDomainTicking();
        io.github.term4.archipelago.ticking.ExecutionDomain stageClock = io.github.term4.archipelago.ticking.ExecutionDomain.create();
        io.github.term4.archipelago.ticking.ExecutionDomain theaterClock = io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard stage = new Shard(instance, stageClock);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(0.5, 66, 0.5), "WireViewer");
        Playback pb = null;
        try {
            stage.join(viewer.player);
            // LIVE reference wire: a real drop the viewer watches
            Recording rec = Replay.record(stage);
            DroppedItemEntity real = DroppedItemEntity.spawn(instance, new Pos(0.5, 70, 0.5),
                    new Vec(0.15, 0.2, 0.1), ItemStack.of(Material.GOLDEN_APPLE), null, 40,
                    io.github.term4.minestommechanics.vri.ItemSpawnEvent.Cause.PLAYER_DROP, viewer.player);
            assertNotNull(real);
            long deadline = System.currentTimeMillis() + 4000;
            while (rec.tick() < 40 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            byte[] file = rec.stop();
            Map<String, Integer> live = classify(viewer, real.getEntityId());
            real.remove();
            stage.leave(viewer.player);
            viewer.sent.clear();

            // TWIN wire: the replayed drop must look LIVE to the client - its own sim carries it, so the
            // wire stays nearly silent (per-tick velocity/teleport corrections = the measured chop)
            Shard theater = new Shard(instance, theaterClock);
            theater.join(viewer.player);
            Playback playback = pb = Replay.play(theater, file);
            deadline = System.currentTimeMillis() + 4000;
            while (playback.tick() < 40 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            Entity twin = playback.entity(0);
            assertNotNull(twin);
            Map<String, Integer> wire = classify(viewer, twin.getEntityId());
            System.out.println("LIVE wire: " + live + " / TWIN wire: " + wire);
            assertTrue(wire.getOrDefault("EntityVelocityPacket", 0) <= 8,
                    "velocity packets stay live-like, got " + wire);
            assertTrue(wire.getOrDefault("EntityTeleportPacket", 0)
                    + wire.getOrDefault("EntityPositionSyncPacket", 0) <= 10,
                    "position snaps stay rare, got " + wire);
            // a zero mid-flight kills the 1.8 client's local item sim (the frozen-drop capture); the
            // landing sync's own zero is the only legitimate one
            assertTrue(zeroVelocities(viewer, twin.getEntityId()) <= 1,
                    "no zero-velocity kicks beyond the landing sync");
        } finally {
            if (pb != null) pb.halt();
            viewer.player.remove();
            stageClock.retire();
            theaterClock.retire();
        }
    }

    private static long zeroVelocities(FakePlayer viewer, int entityId) {
        return viewer.sent.stream()
                .map(sp -> net.minestom.server.network.packet.server.SendablePacket
                        .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, sp))
                .filter(p -> p instanceof net.minestom.server.network.packet.server.play.EntityVelocityPacket v
                        && v.entityId() == entityId && v.velocity().equals(Vec.ZERO))
                .count();
    }

    private static Map<String, Integer> classify(FakePlayer viewer, int entityId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (var sendable : viewer.sent) {
            var p = net.minestom.server.network.packet.server.SendablePacket
                    .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, sendable);
            Integer id = switch (p) {
                case net.minestom.server.network.packet.server.play.EntityPositionPacket x -> x.entityId();
                case net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket x -> x.entityId();
                case net.minestom.server.network.packet.server.play.EntityPositionSyncPacket x -> x.entityId();
                case net.minestom.server.network.packet.server.play.EntityRotationPacket x -> x.entityId();
                case net.minestom.server.network.packet.server.play.EntityTeleportPacket x -> x.entityId();
                case net.minestom.server.network.packet.server.play.EntityVelocityPacket x -> x.entityId();
                case SpawnEntityPacket x -> x.entityId();
                default -> null;
            };
            if (id != null && id == entityId) counts.merge(p.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void revivedItemTwinsRideTheRecordedTrackSmoothly() {
        ShardBridge.installDomainTicking(); // the playground's config: covered mm entities tick on the clocks
        io.github.term4.archipelago.ticking.ExecutionDomain stageClock = io.github.term4.archipelago.ticking.ExecutionDomain.create();
        io.github.term4.archipelago.ticking.ExecutionDomain theaterClock = io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard stage = new Shard(instance, stageClock);
        FakePlayer dropper = FakePlayer.connect(instance, new Pos(0.5, 66, 0.5), "Dropper");
        Playback pb = null;
        try {
            stage.join(dropper.player);
            Recording rec = Replay.record(stage);
            DroppedItemEntity real = DroppedItemEntity.spawn(instance, new Pos(0.5, 70, 0.5),
                    new Vec(0.15, 0.2, 0.1), ItemStack.of(Material.GOLDEN_APPLE), null, 40,
                    io.github.term4.minestommechanics.vri.ItemSpawnEvent.Cause.PLAYER_DROP, dropper.player);
            assertNotNull(real);
            long deadline = System.currentTimeMillis() + 4000;
            while (rec.tick() < 40 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            byte[] file = rec.stop();
            real.remove();

            // the twin re-simulates on the theater clock and FREE-RUNS (a live item's wire is silent) -
            // divergence stays inside the resync envelope, and the rest pin trues the final position
            Shard theater = new Shard(instance, theaterClock);
            Playback playback = pb = Replay.play(theater, file);
            List<Recorded<io.github.term4.archipelago.replay.core.Channels.EntityMove>> track =
                    pb.ops(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES);
            List<Double> dists = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Runnable unhook = theater.onTick(t -> {
                Entity twin = playback.entity(0);
                if (twin == null || twin.getInstance() == null) return;
                Pos last = null;
                for (var op : track) {
                    if (op.tick() <= playback.tick()) last = new Pos(op.op().x(), op.op().y(), op.op().z());
                }
                if (last != null) dists.add(twin.getPosition().distance(last));
            });
            deadline = System.currentTimeMillis() + 4000;
            while (dists.size() < 35 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            unhook.run();
            assertTrue(dists.size() >= 35, "the theater clock sampled the flight");
            synchronized (dists) {
                for (int i = 15; i < dists.size(); i++) {
                    assertTrue(dists.get(i) < 1.15, "divergence stays inside the resync envelope (tick "
                            + i + " drifted " + dists.get(i) + ")");
                }
                assertTrue(dists.get(dists.size() - 1) < 0.30, "the rested twin trues up on the track");
            }
        } finally {
            if (pb != null) pb.halt();
            dropper.player.remove();
            stageClock.retire();
            theaterClock.retire();
        }
    }

    @Test
    void projectilesRecordWithTheirShooterAndReviveAsFootageOnTheGhost() {
        Shard game = new Shard(instance);
        FakePlayer fake = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "Recorded");
        game.join(fake.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity snowball = null;
        try {
            tick();
            snowball = projectiles.launch(ProjectileSnapshot.of(fake.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(snowball);
            // production ordering: the launch listener captures at THIS tick's end, before the next sweep
            MinecraftServer.getSchedulerManager().processTickEnd();
            tick();
        } finally {
            file = rec.stop();
            if (snowball != null) snowball.remove();
            fake.player.remove();
        }
        assertEquals(1, rec.counts().get("replay:spawn"));

        // the recorded spawn op: shooter attributed by name, descriptor sanitized, visual snapshot present
        Shard scratch = new Shard(instance);
        CompoundBinaryTag ops = ShardFile.load(scratch, file).get(Replay.OPS);
        assertNotNull(ops);
        ListBinaryTag spawns = ops.getCompound("channels").getCompound("replay:spawn")
                .getList("ops", BinaryTagTypes.COMPOUND);
        assertEquals(1, spawns.size());
        CompoundBinaryTag op = ((CompoundBinaryTag) spawns.get(0)).getCompound("op");
        assertEquals("Recorded", op.getString("o"), "the wire owner rides the op as the launcher's name");
        CompoundBinaryTag desc = op.getCompound("d");
        assertEquals("mm:projectile", desc.getString("id"));
        assertNull(desc.get("shooter"), "sanitized: a replayed pearl must never re-link the original thrower");
        assertEquals("minecraft:snowball", op.getCompound("vis").getString("type"),
                "the visual snapshot plays even without mechanics");

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Ghost ghost = pb.ghost("Recorded");
            assertNotNull(ghost, "the actor track raised a ghost");
            ProjectileEntity twin = assertInstanceOf(ProjectileEntity.class, pb.entity(0),
                    "the registered reviver upgraded the descriptor to the real class");
            assertSame(ghost, twin.getShooter(), "the enhancer handed ownership to the ghost (the 1.8 wire gate)");
        } finally {
            pb.halt();
        }
    }

    @Test
    void bobberStandInsAreTrackDrivenOnTheWire() {
        Shard game = new Shard(instance);
        FakePlayer angler = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "Angler");
        game.join(angler.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity bobber = null;
        try {
            tick();
            bobber = projectiles.launch(ProjectileSnapshot.of(angler.player, FishingBobber.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(bobber);
            MinecraftServer.getSchedulerManager().processTickEnd();
            tick();
        } finally {
            file = rec.stop();
            if (bobber != null) bobber.remove();
            angler.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(6.5, 70, 6.5), "RodViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Ghost ghost = pb.ghost("Angler");
            assertNotNull(ghost);
            Entity twin = pb.entity(0);
            assertNotNull(twin);
            assertFalse(twin instanceof ProjectileEntity, "bobbers have no descriptor by design - a visual stand-in");
            assertEquals(Vec.ZERO, twin.getVelocity(),
                    "track-driven: any server velocity self-flies past the cast point and rubber-bands");
            List<SpawnEntityPacket> spawns = viewer.sent(SpawnEntityPacket.class);
            SpawnEntityPacket spawn = spawns.stream()
                    .filter(p -> p.entityId() == twin.getEntityId()).findFirst().orElseThrow();
            assertEquals(ghost.getEntityId(), spawn.data(), "wire owner = the ghost (clients discard ownerless hooks)");
            assertEquals(Vec.ZERO, spawn.velocity(),
                    "zero wire velocity too: an advertised cast velocity makes modern clients sim ahead of the track");
            assertTrue(spawns.indexOf(spawns.stream().filter(p -> p.entityId() == ghost.getEntityId())
                            .findFirst().orElseThrow()) < spawns.indexOf(spawn),
                    "the owner reaches the client BEFORE the projectile, or the hook is discarded");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    @Test
    void projectileTwinsDieOnTheRecordedRemovalTick() {
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "Thrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity snowball = null;
        try {
            tick();
            snowball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(snowball);
            MinecraftServer.getSchedulerManager().processTickEnd();
            tick();
            snowball.remove(); // the impact consequence (hit a player/self): vanish on contact
            tick();            // tick 3: the sweep records the removal
            tick();
            tick();            // recording outlives the removal - the twin's death is the REMOVE op, not playback end
        } finally {
            file = rec.stop();
            if (snowball != null) snowball.remove();
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin);
            tick();
            assertFalse(twin.isRemoved(), "alive until the recorded impact");
            tick();
            assertTrue(twin.isRemoved(), "gone on the recorded removal tick");
            assertTrue(pb.active(), "mid-playback - the remove op did it, not the end cleanup");
            Ghost ghost = pb.ghost("Thrower");
            assertNotNull(ghost);
            assertFalse(ghost.isRemoved(), "the ghost plays on");
        } finally {
            pb.halt();
        }
    }

    @Test
    void pointBlankProjectilesStillRecord() {
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "PointBlank");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            tick();
            ProjectileEntity snowball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(snowball);
            snowball.remove(); // impacted within its launch tick - dead before the end-of-tick capture
            MinecraftServer.getSchedulerManager().processTickEnd();
            tick();
            tick();
        } finally {
            file = rec.stop();
            thrower.player.remove();
        }
        assertEquals(1, rec.counts().get("replay:spawn"), "a point-blank throw is still footage");

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            Entity twin = pb.entity(0);
            assertNotNull(twin, "the throw shows for its tick");
            tick();
            assertTrue(twin.isRemoved(), "and vanishes on the sweep tick");
        } finally {
            pb.halt();
        }
    }

    @Test
    void cancelledLaunchesNeverRecord() {
        EventNode<Event> canceller = EventNode.all("test:cancel-launch");
        canceller.addListener(ProjectileLaunchEvent.class, e -> e.setCancelled(true));
        MinecraftServer.getGlobalEventHandler().addChild(canceller);
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "Cancelled");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        try {
            tick();
            assertNull(projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles())));
            MinecraftServer.getSchedulerManager().processTickEnd();
            tick();
            rec.stop();
            assertNull(rec.counts().get("replay:spawn"), "a cancelled launch never existed - nothing to replay");
        } finally {
            if (rec.active()) rec.stop();
            MinecraftServer.getGlobalEventHandler().removeChild(canceller);
            thrower.player.remove();
        }
    }

    @Test
    void dropsReviveThroughTheRegisteredReviver() {
        Shard game = new Shard(instance);
        ItemStack stack = ItemStack.of(Material.DIAMOND, 3);
        DroppedItemEntity drop = new DroppedItemEntity(stack, DroppedItemEntity.Model.LEGACY);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            game.spawn(drop, new Pos(4.5, 70, 4.5)).join();
            tick();
        } finally {
            file = rec.stop();
            drop.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        try {
            tick();
            DroppedItemEntity twin = assertInstanceOf(DroppedItemEntity.class, pb.entity(0),
                    "without the describer/reviver pair this would demote to a visual stand-in");
            assertEquals(stack, twin.getItemStack());
        } finally {
            pb.halt();
        }
    }
}
