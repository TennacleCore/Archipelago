package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who gets a twin's anchors: each client era holds a still twin with different synced state. */
class TwinHoldTest extends HeadlessServerTest {

    /** 1.9 has no NO_GRAVITY, 1.21.1 no synced IN_GROUND; only 1.21.2 holds a parked arrow by itself. */
    @Test
    void anchorsByEra() {
        Viewers.protocol(p -> switch (p.getUsername()) {
            case "HoldNine" -> 107;
            case "HoldOld" -> 767;
            default -> Viewers.LIST_ORDER_PROTOCOL;
        });
        Pos at = new Pos(900.5, 70, 900.5);
        Entity arrow = new Entity(EntityType.ARROW);
        arrow.setNoGravity(true);
        FakePlayer nine = FakePlayer.connect(instance, at.add(2, 0, 0), "HoldNine");
        FakePlayer old = FakePlayer.connect(instance, at.add(4, 0, 0), "HoldOld");
        FakePlayer now = FakePlayer.connect(instance, at.add(6, 0, 0), "HoldNow");
        try {
            arrow.setInstance(instance, at).join();
            for (FakePlayer viewer : List.of(nine, old, now)) arrow.addViewer(viewer.player);

            clear(nine, old, now);
            Tracks.pinAt(arrow, at, false);
            assertTrue(anchored(nine, arrow), "gravity pulls a parked twin down on 1.9");
            assertTrue(anchored(old, arrow), "a resting arrow turns off zero motion before 1.21.2");
            assertFalse(anchored(now, arrow));

            clear(nine, old, now);
            Tracks.legSets(arrow, false);
            assertTrue(anchored(nine, arrow), "1.9 drops a glider between legs");
            assertFalse(anchored(old, arrow), "NO_GRAVITY holds it from 1.10");
            assertFalse(anchored(now, arrow));
        } finally {
            Viewers.protocol(p -> -1);
            arrow.remove();
            nine.player.remove();
            old.player.remove();
            now.player.remove();
        }
    }

    private static void clear(FakePlayer... viewers) {
        for (FakePlayer viewer : viewers) viewer.sent.clear();
    }

    private static boolean anchored(FakePlayer viewer, Entity twin) {
        return viewer.sent(EntityPositionSyncPacket.class).stream().anyMatch(p -> p.entityId() == twin.getEntityId());
    }
}
