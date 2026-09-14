package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.NetworkBuffer;
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

    /** The first tick can raise a ghost before {@code Replay.play} returns, so the name lands after it. */
    @Test
    void aNameLandsOnAGhostAlreadyUp() {
        Component red = Component.text("Recorded", NamedTextColor.RED);
        Ghost ghost = new Ghost("Recorded", null, null); // raised bare, as the first tick raises them
        ghost.listName(red);
        PlayerInfoUpdatePacket.Entry entry = entryOf(ghost, "LateWatcher");
        assertTrue(entry.listed(), "renaming lists it");
        assertEquals(red, entry.displayName());
    }

    /**
     * The record is not the wire: a {@code PlayerInfoUpdatePacket} writes only the fields whose action is in its
     * set, so an entry carrying a display name under {@code ADD_PLAYER} alone reaches nobody.
     */
    @Test
    void theNameSurvivesTheWire() {
        Component blue = Component.text("Recorded", NamedTextColor.BLUE);
        Ghost ghost = new Ghost("Recorded", null, null, blue);
        try {
            PlayerInfoUpdatePacket sent = (PlayerInfoUpdatePacket) capture(ghost, "WireWatcher");
            NetworkBuffer buffer = NetworkBuffer.resizableBuffer(256, MinecraftServer.getRegistries());
            buffer.write(PlayerInfoUpdatePacket.SERIALIZER, sent);
            PlayerInfoUpdatePacket back = buffer.read(PlayerInfoUpdatePacket.SERIALIZER);
            PlayerInfoUpdatePacket.Entry entry = back.entries().getFirst();
            assertEquals("Recorded", entry.username(), "the profile came back whole");
            assertTrue(entry.listed(), "and listed");
            assertEquals(blue, entry.displayName(), "and named: the actions carried the fields, not the record");
        } finally {
            ghost.remove();
        }
    }

    private static Object capture(Ghost ghost, String watcher) {
        ghost.setInstance(instance, new Pos(604.5, 64, 604.5)).join();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(606.5, 64, 604.5), watcher);
        try {
            return viewer.sent.stream()
                    .filter(PlayerInfoUpdatePacket.class::isInstance).map(PlayerInfoUpdatePacket.class::cast)
                    .filter(p -> p.entries().stream().anyMatch(e -> e.uuid().equals(ghost.getUuid())))
                    .reduce((first, last) -> last).orElseThrow();
        } finally {
            viewer.player.remove();
        }
    }

    // the profile a viewer is sent when the ghost comes into view
    private static PlayerInfoUpdatePacket.Entry entryOf(Ghost ghost, String watcher) {
        if (ghost.getInstance() == null) ghost.setInstance(instance, new Pos(600.5, 64, 600.5)).join();
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
