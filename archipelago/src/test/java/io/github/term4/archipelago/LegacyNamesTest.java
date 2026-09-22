package io.github.term4.archipelago;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Four copies of this cap existed, written two different ways; past it a 1.8 client is disconnected, not clipped. */
class LegacyNamesTest {

    /** Positional, so an expectation reads off the alphabet rather than off a number in the name. */
    private static final String AT_CAP = "abcdefghijklmnop";       // 16
    private static final String OVER_CAP = "abcdefghijklmnopqrs";  // 19

    @Test
    void theCapIsInclusive() {
        assertEquals(16, LegacyNames.PROFILE_NAME_CAP);
        assertEquals(AT_CAP, LegacyNames.clipped(AT_CAP), "exactly at the cap is untouched");
        assertEquals(AT_CAP, LegacyNames.clipped(OVER_CAP), "over it, the tail goes");
    }

    /** The prefix is the sort key, so it is kept whole and the name gives way. */
    @Test
    void aPrefixKeepsItsRoom() {
        String out = LegacyNames.prefixed("§c", OVER_CAP);
        assertEquals(LegacyNames.PROFILE_NAME_CAP, out.length(), "the result still fits the cap");
        assertTrue(out.startsWith("§c"), "and still sorts under its code");
        assertEquals("§c" + "abcdefghijklmn", out);
    }

    /** What Tennacle's spectator rows carried as a bare 14: the cap less a two-character prefix. */
    @Test
    void aTwoCharPrefixLeavesFourteen() {
        String out = LegacyNames.prefixed("§o", OVER_CAP);
        assertEquals(14, out.length() - 2);
        assertEquals("§o" + "abcdefghijklmn", out);
    }

    @Test
    void aShortNameIsUntouched() {
        assertEquals("Term4", LegacyNames.clipped("Term4"));
        assertEquals("§cTerm4", LegacyNames.prefixed("§c", "Term4"));
    }

    /** A plate that cannot carry its color keeps the whole name instead: identity beats decoration. */
    @Test
    void anUnfittingPlateFallsBackWhole() {
        String plain = "abcdefghijklmno"; // 15, so the coded form is 17
        assertEquals(plain, LegacyNames.fitting("§9" + plain, plain));
        assertEquals("§9Term4", LegacyNames.fitting("§9Term4", "Term4"), "and keeps it when it fits");
    }
}
