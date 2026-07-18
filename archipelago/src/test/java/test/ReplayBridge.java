package test;

import io.github.term4.minestommechanics.api.event.projectile.ProjectileHitEvent;
import io.github.term4.minestommechanics.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.minestommechanics.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.minestommechanics.util.Directions;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.channel.EventDispatcher;
import io.github.term4.archipelago.replay.core.CoreChannels;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

/**
 * Template glue between MinestomMechanics and archipelago-replay - everything mechanics-flavored a replay needs,
 * contributed purely via {@link Replay} registration (an application using both libraries copies this class).
 * The save/load revivers ({@code mm:item}/{@code mm:projectile}) are registered separately with the polar codec;
 * playback upgrades descriptors through them when present.
 */
public final class ReplayBridge {

    private static boolean installed;

    private ReplayBridge() {}

    public static void install() {
        CoreChannels.register();
        if (installed) return;
        installed = true;

        // launches recorded at end of THIS tick (the stamp exists, nothing can hit before its first move tick) -
        // a per-tick sampler alone misses projectiles that hit and remove themselves within one tick. The ASYNC
        // world spawn may not have landed by then (no instance/position yet), so the spawn pos comes from the event.
        Replay.dispatchers(() -> new EventDispatcher() {
            @Override
            protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
                node.addListener(ProjectileLaunchEvent.class, e -> {
                    ProjectileEntity projectile = e.projectile();
                    Entity launcher = projectile.getShooter();
                    if (launcher == null || !rec.covers(launcher)) return;
                    String shooter = launcher instanceof Player p ? p.getUsername() : "";
                    MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                        // a point-blank impact removes the projectile within its launch tick - still footage
                        // (the removal sweep kills the twin next tick); only a CANCELLED launch never existed
                        if (rec.active() && !e.isCancelled()) {
                            // the launch path spawns at spawnPos withView(velocity) - record the same rotation
                            Vec vel = e.velocity();
                            rec.spawn(projectile, e.spawnPos().withView(Directions.yaw(vel), Directions.pitch(vel)),
                                    shooter);
                        }
                    });
                });

                // impact-time removal: the per-tick sweep is a tick late - the twin sails through the mannequin
                // (or past the wall) before vanishing. Arrows that stick aren't removed and keep their track.
                node.addListener(ProjectileHitEvent.class, e -> {
                    ProjectileEntity projectile = e.projectile();
                    Point hit = e.hitPoint();
                    MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                        if (!rec.active() || !projectile.isRemoved()) return;
                        Pos last = projectile.getPosition();
                        rec.remove(projectile, new Pos(hit.x(), hit.y(), hit.z(), last.yaw(), last.pitch()), "");
                    });
                });
            }
        });

        // a replayed descriptor must never re-link the original thrower (fromSave re-binds ONLINE shooters -
        // a replayed pearl would teleport the spectating player); the ghost link rides the spawn op instead
        Replay.sanitizeDescriptors(desc -> {
            if (!desc.getString("id").equals("mm:projectile") || desc.get("shooter") == null) return desc;
            CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
            for (var entry : desc) {
                if (!entry.getKey().equals("shooter")) out.put(entry.getKey(), entry.getValue());
            }
            return out.build();
        });

        // footage, not gameplay: hits were RECORDED (hurt flash/arrow-count acts) - a re-simulated projectile
        // would strike the shooter's own ghost at the throw point
        Replay.onSpawn((entity, owner, playback) -> {
            if (entity instanceof ProjectileEntity projectile) {
                projectile.setEntityHits(false);
                if (owner != null) projectile.reassignShooter(owner); // the wire owner (1.8 spawn-velocity gate)
            }
        });
    }
}
