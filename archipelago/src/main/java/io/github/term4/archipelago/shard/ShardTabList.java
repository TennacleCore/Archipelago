package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Scopes the TAB LIST (and with it the client's name completion) to what a player's world lets them see -
 * without it every player lists everyone on the server, leaking names across shards. Opt-in: {@link #install()}
 * once, then each shard picks a {@link TabScope} ({@link Shard#tabScope(TabScope)}, default {@link TabScope#WORLD}).
 *
 * <p>Two mechanisms: outgoing ADD_PLAYER packets are filtered per viewer (socket clients), and membership/view
 * changes proactively re-sync the affected lists (add + remove diffs), so a scope change applies live. Entries
 * whose uuid is not an online player (NPCs, replay ghosts) always pass.
 */
public final class ShardTabList {

    /** What a shard's members list in tab. The viewer's own scope decides - a child on {@link #TREE} lists the
     *  whole tree while the parent's members on {@link #WORLD} still list only their own world. */
    public enum TabScope {
        /** Entity visibility ({@link WorldPolicy#canSee}): own world plus whatever views/policy grant. */
        WORLD,
        /** {@link #WORLD} plus every member anywhere in this shard's root tree. */
        TREE,
        /** Everyone (the vanilla list). */
        INSTANCE
    }

    /** An individual override: {@code true} = always list, {@code false} = never, {@code null} = defer to the
     *  next rule, then the viewer's scope. A viewer always lists itself - rules are not consulted for self. */
    @FunctionalInterface
    public interface TabRule {
        @org.jetbrains.annotations.Nullable Boolean listed(@NotNull Player viewer, @NotNull Player subject);
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<TabRule> RULES =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private ShardTabList() {}

    /**
     * Registers an override rule (first non-null answer wins, before the scope logic) and re-syncs everyone.
     * The returned handle unregisters it. E.g. members listing their spectators:
     * {@code rule((viewer, subject) -> ShardView.of(subject).blocks() == Shard.of(viewer) ? true : null)}.
     */
    public static @NotNull Runnable rule(@NotNull TabRule rule) {
        RULES.add(rule);
        resyncAll();
        return () -> {
            RULES.remove(rule);
            resyncAll();
        };
    }

    private static void resyncAll() {
        if (!installed) return;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) resync(p);
    }

    public static void install() {
        if (installed) return;
        synchronized (ShardTabList.class) {
            if (installed) return;
            installed = true;
        }
        var events = MinecraftServer.getGlobalEventHandler();
        // the broadcast filter: a new join's global ADD never reaches viewers whose scope excludes it
        events.addListener(PlayerPacketOutEvent.class, e -> {
            if (!(e.getPacket() instanceof PlayerInfoUpdatePacket info)
                    || !info.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)) return;
            List<PlayerInfoUpdatePacket.Entry> allowed = new ArrayList<>(info.entries().size());
            for (PlayerInfoUpdatePacket.Entry entry : info.entries()) {
                if (allows(e.getPlayer(), entry.uuid())) allowed.add(entry);
            }
            if (allowed.size() == info.entries().size()) return; // untouched packets (and our own re-sends) pass
            e.setCancelled(true);
            if (!allowed.isEmpty()) e.getPlayer().sendPacket(new PlayerInfoUpdatePacket(info.actions(), allowed));
        });
        // instance change: the world changed without a view change
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) resync(e.getPlayer());
        });
        // covers join/leave/spectate, and fires BEFORE the viewable rules - the spawns those send are only
        // rendered when the client already has the info entry (see the hook's doc)
        ShardView.viewChangeHook = ShardTabList::resync;
    }

    /** Whether {@code viewer}'s tab may contain {@code uuid} ({@code true} for non-player uuids - NPCs, ghosts). */
    private static boolean allows(Player viewer, UUID uuid) {
        Player subject = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
        return subject == null || listed(viewer, subject);
    }

    /** Whether {@code viewer}'s tab lists {@code subject}: override rules first, then the viewer's scope. */
    public static boolean listed(@NotNull Player viewer, @NotNull Player subject) {
        if (viewer == subject) return true;
        for (TabRule rule : RULES) {
            Boolean answer = rule.listed(viewer, subject);
            if (answer != null) return answer;
        }
        Shard viewerShard = Shard.of(viewer);
        TabScope scope = viewerShard != null ? viewerShard.tabScope() : TabScope.WORLD;
        return switch (scope) {
            case INSTANCE -> true;
            case WORLD -> WorldPolicy.canSee(viewer, subject);
            case TREE -> WorldPolicy.canSee(viewer, subject) || sameTree(viewerShard, Shard.of(subject));
        };
    }

    private static boolean sameTree(Shard a, Shard b) {
        return a != null && b != null && a.root() == b.root();
    }

    /** {@code player}'s world changed: full re-sync of their own list, and of their entry in everyone else's. */
    public static void resync(@NotNull Player player) {
        if (!installed || !player.isOnline()) return;
        List<PlayerInfoUpdatePacket.Entry> adds = new ArrayList<>();
        List<UUID> removes = new ArrayList<>();
        PlayerInfoUpdatePacket.Entry self = entry(player);
        for (Player other : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (other == player) continue;
            // their list of the player
            if (listed(other, player)) other.sendPacket(new PlayerInfoUpdatePacket(ALL_ACTIONS, List.of(self)));
            else other.sendPacket(new PlayerInfoRemovePacket(List.of(player.getUuid())));
            // the player's list of them
            if (listed(player, other)) adds.add(entry(other));
            else removes.add(other.getUuid());
        }
        if (!adds.isEmpty()) player.sendPacket(new PlayerInfoUpdatePacket(ALL_ACTIONS, adds));
        if (!removes.isEmpty()) player.sendPacket(new PlayerInfoRemovePacket(removes));
    }

    /** Re-syncs every member's list after {@code shard}'s scope changed. */
    static void refreshShard(Shard shard) {
        if (!installed) return;
        for (Player member : shard.members()) resync(member);
    }

    private static final EnumSet<PlayerInfoUpdatePacket.Action> ALL_ACTIONS =
            EnumSet.allOf(PlayerInfoUpdatePacket.Action.class);

    // the shape Player.infoEntry() builds (no chat session: re-added entries lose the signing badge, like proxies)
    private static PlayerInfoUpdatePacket.Entry entry(Player player) {
        PlayerSkin skin = player.getSkin();
        List<PlayerInfoUpdatePacket.Property> properties = skin != null && skin.signature() != null
                ? List.of(new PlayerInfoUpdatePacket.Property("textures", skin.textures(), skin.signature()))
                : List.of();
        return new PlayerInfoUpdatePacket.Entry(player.getUuid(), player.getUsername(), properties, true,
                player.getLatency(), player.getGameMode(), player.getDisplayName(), null, 0, true);
    }
}
