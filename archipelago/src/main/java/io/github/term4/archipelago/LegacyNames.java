package io.github.term4.archipelago;

import org.jetbrains.annotations.NotNull;

/**
 * The 1.8 profile-name cap and the three things done about it.
 *
 * <p>Sixteen characters is not a truncation: {@code PacketBuffer.readStringFromBuffer(16)} throws and the
 * connection DROPS, so a name past it disconnects the client rather than arriving short. The cap binds every
 * name-keyed 1.8 surface at once - the tab row, the plate over the head, scoreboard team membership and the
 * name a below-name objective scores under are all the profile name.
 */
public final class LegacyNames {

    /** Past this a 1.8 client throws reading the string and the connection drops. */
    public static final int PROFILE_NAME_CAP = 16;

    private LegacyNames() {}

    /** {@code name} with no more of it than the cap allows. */
    public static @NotNull String clipped(@NotNull String name) {
        return name.length() <= PROFILE_NAME_CAP ? name : name.substring(0, PROFILE_NAME_CAP);
    }

    /**
     * {@code prefix} followed by as much of {@code name} as still fits. The prefix is the sort key - a color
     * code groups teammates and paints the plate - so it is kept whole and the name gives way.
     */
    public static @NotNull String prefixed(@NotNull String prefix, @NotNull String name) {
        int room = Math.max(0, PROFILE_NAME_CAP - prefix.length());
        return prefix + (name.length() <= room ? name : name.substring(0, room));
    }

    /**
     * {@code coded} when it fits, else {@code plain}. For a name that cannot carry its color code without
     * running over: the whole uncolored name reads better than a clipped colored one, and a plate is decoration
     * where the name is identity.
     */
    public static @NotNull String fitting(@NotNull String coded, @NotNull String plain) {
        return coded.length() <= PROFILE_NAME_CAP ? coded : plain;
    }
}
