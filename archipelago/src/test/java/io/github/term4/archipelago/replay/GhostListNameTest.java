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

    /**
     * A 1.8 plate is the profile name formatted by that name's team, so the color rides the name; a modern client
     * would render the section code literally and takes the plain one.
     */
    @Test
    void aLegacyPlateWearsTheColorInTheName() {
        Replay.viewerProtocol(p -> p.hasTag(LEGACY) ? 47 : -1); // -1 = unknown, which reads modern
        Ghost ghost = new Ghost("Recorded", null, null, Component.text("Recorded", NamedTextColor.BLUE));
        try {
            assertEquals("§9Recorded", profileSentTo(ghost, "PlateLegacy", true), "the plate carries the code");
            assertEquals("Recorded", profileSentTo(ghost, "PlateModern", false), "a modern client reads codes literally");
        } finally {
            ghost.remove();
        }
    }

    /** Sixteen is the cap and a longer name disconnects the client: the name survives, the color does not. */
    @Test
    void aNameWithNoRoomKeepsItsPlatePlain() {
        Replay.viewerProtocol(p -> p.hasTag(LEGACY) ? 47 : -1);
        String long15 = "PlayerNameOf15!";
        Ghost ghost = new Ghost(long15, null, null, Component.text(long15, NamedTextColor.RED));
        try {
            assertEquals(long15, profileSentTo(ghost, "PlateLong", true), "whole, uncolored, rather than clipped");
        } finally {
            ghost.remove();
        }
    }

    /** A name-keyed objective has to score the row the viewer holds, and the two eras hold different names. */
    @Test
    void theScoredRowIsThePlatedOneOn18() {
        Replay.viewerProtocol(p -> p.hasTag(LEGACY) ? 47 : -1);
        Ghost ghost = new Ghost("Recorded", null, null, Component.text("Recorded", NamedTextColor.BLUE));
        FakePlayer legacy = FakePlayer.connect(instance, new Pos(612.5, 64, 612.5), "ScoreLegacy");
        FakePlayer modern = FakePlayer.connect(instance, new Pos(614.5, 64, 612.5), "ScoreModern");
        try {
            legacy.player.setTag(LEGACY, true);
            assertEquals("§9Recorded", ghost.listedName(legacy.player));
            assertEquals("Recorded", ghost.listedName(modern.player));
        } finally {
            legacy.player.remove();
            modern.player.remove();
            ghost.remove();
        }
    }

    private static String profileSentTo(Ghost ghost, String watcher, boolean legacy) {
        if (ghost.getInstance() == null) ghost.setInstance(instance, new Pos(608.5, 64, 608.5)).join();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(610.5, 64, 608.5), watcher);
        try {
            if (legacy) viewer.player.setTag(LEGACY, true);
            viewer.sent.clear();
            ghost.updateNewViewer(viewer.player); // the profile a viewer takes on arrival
            return viewer.sent.stream()
                    .filter(PlayerInfoUpdatePacket.class::isInstance).map(PlayerInfoUpdatePacket.class::cast)
                    .flatMap(p -> p.entries().stream())
                    .filter(e -> e.uuid().equals(ghost.getUuid()))
                    .reduce((first, last) -> last).orElseThrow().username();
        } finally {
            viewer.player.remove();
        }
    }

    private static final net.minestom.server.tag.Tag<Boolean> LEGACY =
            net.minestom.server.tag.Tag.Transient("test:legacy");

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
