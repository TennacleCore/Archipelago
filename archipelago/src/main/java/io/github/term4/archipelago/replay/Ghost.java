package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.ticking.DomainLivingEntity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.metadata.avatar.PlayerMeta;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.kyori.adventure.text.Component;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.EntityEffectPacket;
import net.minestom.server.potion.TimedPotion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A recorded actor's stand-in: a skinned player NPC driven by the actor track (a domain theater's clock ticks it). */
public final class Ghost extends DomainLivingEntity {

    /** Ticks between absolute position syncs at full rate; a playback shortens it as it speeds up. */
    public static final int SYNC_TICKS = 200;

    private final String name;
    private final PlayerInfoUpdatePacket info;

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
        List<PlayerInfoUpdatePacket.Property> props = textures != null && signature != null
                ? List.of(new PlayerInfoUpdatePacket.Property("textures", textures, signature))
                : List.of();
        info = new PlayerInfoUpdatePacket(PlayerInfoUpdatePacket.Action.ADD_PLAYER,
                new PlayerInfoUpdatePacket.Entry(getUuid(), name, props, listName != null, 0,
                        GameMode.SURVIVAL, listName, null, 0, true));
        ((PlayerMeta) getEntityMeta()).setDisplayedSkinParts((byte) 0x7F);
        setNoGravity(true);
    }

    /** The recorded actor's username. */
    public @NotNull String name() {
        return name;
    }

    // the tab-list profile must reach each viewer before the spawn packet (then leaves the list)
    @Override
    public void updateNewViewer(@NotNull Player player) {
        player.sendPacket(info);
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
