package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which fireball aim each client era gets: the plain leg until 1.21.2 moved the push ahead of the move. */
class FireballAimTest extends HeadlessServerTest {

    @Test
    void aimsByEra() {
        Viewers.protocol(p -> switch (p.getUsername()) {
            case "AimEight" -> 47;
            case "AimOld" -> 767;
            default -> 768;
        });
        Pos at = new Pos(940.5, 70, 940.5);
        Entity fireball = new Entity(EntityType.FIREBALL);
        fireball.setNoGravity(true);
        FakePlayer eight = FakePlayer.connect(instance, at.add(2, 0, 0), "AimEight");
        FakePlayer old = FakePlayer.connect(instance, at.add(4, 0, 0), "AimOld");
        FakePlayer now = FakePlayer.connect(instance, at.add(6, 0, 0), "AimNow");
        try {
            fireball.setInstance(instance, at).join();
            for (FakePlayer viewer : List.of(eight, old, now)) {
                fireball.addViewer(viewer.player);
                viewer.sent.clear();
            }
            Vec step = new Vec(0.5, 0, 0);
            Tracks.aimFireball(fireball, new Glide(at.add(step), step, 0, 1, new int[]{1}));

            assertEquals(step, aim(eight, fireball));
            assertEquals(step, aim(old, fireball), "1.21 pushes along its motion but still moves first");
            assertEquals(0.5 / 0.95 - 0.1, aim(now, fireball).x(), 1.0e-9);
        } finally {
            Viewers.protocol(p -> -1);
            fireball.remove();
            eight.player.remove();
            old.player.remove();
            now.player.remove();
        }
    }

    private static Vec aim(FakePlayer viewer, Entity twin) {
        return viewer.sent(EntityVelocityPacket.class).stream()
                .filter(p -> p.entityId() == twin.getEntityId())
                .reduce((first, last) -> last)
                .orElseThrow()
                .velocity();
    }
}
