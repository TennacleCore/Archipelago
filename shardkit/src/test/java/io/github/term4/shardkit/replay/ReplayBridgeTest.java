package io.github.term4.shardkit.replay;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.api.event.ProjectileLaunchEvent;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.mechanics.projectile.types.FishingBobber;
import io.github.term4.minestommechanics.mechanics.projectile.types.Snowball;
import io.github.term4.minestommechanics.mechanics.vanilla18.Vanilla18;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.polar.EntityDescriptors;
import io.github.term4.shardkit.polar.PolarShardCodec;
import io.github.term4.shardkit.testsupport.FakePlayer;
import io.github.term4.shardkit.testsupport.HeadlessServerTest;
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
        CompoundBinaryTag ops = PolarShardCodec.load(scratch, file).get(Replay.OPS);
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
