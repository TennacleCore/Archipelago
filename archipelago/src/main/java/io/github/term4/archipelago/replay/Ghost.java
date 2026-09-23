package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.ticking.DomainLivingEntity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.metadata.avatar.PlayerMeta;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import io.github.term4.archipelago.LegacyNames;
import io.github.term4.archipelago.Viewers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.EntityEffectPacket;
import net.minestom.server.potion.TimedPotion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/** A recorded actor's stand-in: a skinned player NPC driven by the actor track (a domain theater's clock ticks it). */
public final class Ghost extends DomainLivingEntity {

    /** Ticks between absolute position syncs at full rate; a playback shortens it as it speeds up. */
    public static final int SYNC_TICKS = 200;

    private final String name;
    private final List<PlayerInfoUpdatePacket.Property> props;
    private PlayerInfoUpdatePacket info;
    private PlayerInfoUpdatePacket legacyInfo;
    private volatile String plated;

    public Ghost(@NotNull String name, @Nullable String textures, @Nullable String signature) {
        this(name, textures, signature, null);
    }

    /**
     * {@code listName} both lists the ghost and names its row. A scoreboard team cannot do it: a team keys on the
     * entry name, and a watcher who played in this game shares one with their own ghost - the display name rides
     * the entry instead, so the two rows are told apart.
     */
    public Ghost(@NotNull String name, @Nullable String textures, @Nullable String signature,
                 @Nullable Component listName) {
        super(EntityType.PLAYER);
        this.name = name;
        // the track's relative stream is authoritative; the scheduled ABSOLUTE sync lands instantly against
        // the client's 3-tick player interpolation - a periodic catch-up snap (the slow-mo breathing cycle)
        setSynchronizationTicks(SYNC_TICKS);
        props = textures != null && signature != null
                ? List.of(new PlayerInfoUpdatePacket.Property("textures", textures, signature))
                : List.of();
        listName(listName);
        ((PlayerMeta) getEntityMeta()).setDisplayedSkinParts((byte) 0x7F);
        setNoGravity(true);
    }

    /**
     * Renames the row, or drops it with {@code null}. A playback raises ghosts on the first move op, which can
     * land before {@code Replay.play} returns - so the name arrives after the ghost as often as with it. Viewers
     * take the profile on arrival, and none has arrived yet.
     */
    public void listName(@Nullable Component listName) {
        info = profile(name, listName);
        plated = plateName(name, listName);
        legacyInfo = plated.equals(name) ? info : profile(plated, listName);
    }

    /**
     * The name {@code viewer}'s client holds this ghost's row under. A name-keyed objective (tab or below-name)
     * has to score under this: an unscored row still draws a number on 1.8, and it is zero.
     */
    public @NotNull String listedName(@NotNull Player viewer) {
        return Viewers.sortsByName(viewer) ? plated : name;
    }

    // the actions ARE the wire format: a field whose action is missing is never written, whatever the entry holds
    private PlayerInfoUpdatePacket profile(String username, @Nullable Component listName) {
        return new PlayerInfoUpdatePacket(
                EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        PlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                new PlayerInfoUpdatePacket.Entry(getUuid(), username, props, listName != null, 0,
                        GameMode.SURVIVAL, listName, null, 0, true));
    }

    /**
     * The name a client that sorts the tab by name draws over the head. A plate has no display-name lever - it
     * is the profile name formatted by the team that name is on, and a team keys on the name, which a watcher of
     * their own match shares with their ghost. So the color rides the name itself, and a name with no room for
     * the code keeps its plate plain rather than clipped.
     */
    private static String plateName(String name, @Nullable Component listName) {
        if (listName == null || listName.color() == null) return name;
        String coded = LegacyComponentSerializer.legacySection()
                .serialize(Component.text(name).color(listName.color()));
        return LegacyNames.fitting(coded, name);
    }

    /** The recorded actor's username. */
    public @NotNull String name() {
        return name;
    }

    // the tab-list profile must reach each viewer before the spawn packet (then leaves the list)
    @Override
    public void updateNewViewer(@NotNull Player player) {
        player.sendPacket(Viewers.sortsByName(player) ? legacyInfo : info);
        super.updateNewViewer(player);
        // an effect packet reaches only the viewers standing there when the effect lands, and a client times a
        // swing off the swinger's own haste/fatigue: a viewer who arrives after (a ghost re-raised by a seek,
        // a watcher who just landed) would see the rate's pacing swing bare
        for (TimedPotion timed : getActiveEffects()) {
            player.sendPacket(new EntityEffectPacket(getEntityId(), timed.potion()));
        }
    }

    @Override
    public void updateOldViewer(@NotNull Player player) {
        super.updateOldViewer(player);
        player.sendPacket(new PlayerInfoRemovePacket(getUuid()));
    }

    // A grouped send shares one CachedPacket, which a per-viewer packet view can't rewrite - and an item's
    // client-side use animation (a raised sword's block pose) is read off the VIEWER's copy of the held item.
    // Equipment therefore goes out bare; a ghost re-equips only on a recorded change, so this is cheap.
    @Override
    public void sendPacketToViewers(@NotNull SendablePacket packet) {
        if (packet instanceof EntityEquipmentPacket) {
            for (Player viewer : getViewers()) viewer.sendPacket(packet);
            return;
        }
        super.sendPacketToViewers(packet);
    }

    // replayed projectiles REALLY hit ghosts (period-accurate flash), but the recorded player may have healed
    // (gapples) - a ghost re-taking raw damage without the heals would die mid-replay
    @Override
    public boolean damage(@NotNull Damage damage) {
        damage.setAmount(0);
        return super.damage(damage);
    }

    // position syncs carry isOnGround(); a no-gravity ghost would broadcast airborne forever (rendered floating)
    public void grounded(boolean ground) {
        this.onGround = ground;
    }
}
