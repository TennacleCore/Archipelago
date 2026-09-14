package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ghost's row is named on its own entry, not by a scoreboard team: a team keys on the entry name, and a watcher
 * of their own match shares one with their ghost.
 */
class GhostListNameTest extends HeadlessServerTest {

    @Test
    void aNamedGhostListsUnderItsOwnName() {
        Component blue = Component.text("Recorded", NamedTextColor.BLUE);
        PlayerInfoUpdatePacket.Entry named = entryOf(new Ghost("Recorded", null, null, blue), "NamedWatcher");
        assertTrue(named.listed(), "a ghost with a name is a row");
        assertEquals(blue, named.displayName(), "and the row reads in the side's color");

        PlayerInfoUpdatePacket.Entry bare = entryOf(new Ghost("Recorded", null, null), "BareWatcher");
        assertFalse(bare.listed(), "a ghost with none is only a profile for the spawn");
        assertNull(bare.displayName());
    }

    // the profile a viewer is sent when the ghost comes into view
    private static PlayerInfoUpdatePacket.Entry entryOf(Ghost ghost, String watcher) {
        ghost.setInstance(instance, new Pos(600.5, 64, 600.5)).join();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(602.5, 64, 600.5), watcher);
        try {
            assertTrue(ghost.getViewers().contains(viewer.player), "the harness really is watching the ghost");
            PlayerInfoUpdatePacket info = viewer.sent.stream()
                    .filter(PlayerInfoUpdatePacket.class::isInstance).map(PlayerInfoUpdatePacket.class::cast)
                    .filter(p -> p.entries().stream().anyMatch(e -> e.uuid().equals(ghost.getUuid())))
                    .reduce((first, last) -> last).orElse(null);
            assertNotNull(info, "the ghost's profile reaches the viewer");
            return info.entries().stream().filter(e -> e.uuid().equals(ghost.getUuid())).findFirst().orElseThrow();
        } finally {
            viewer.player.remove();
            ghost.remove();
        }
    }
}
