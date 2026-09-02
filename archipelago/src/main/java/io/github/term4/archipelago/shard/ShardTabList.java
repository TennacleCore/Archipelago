package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.tag.Tag;
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

    /**
     * The name a 1.8 client sorts this player under. 1.8 has no list-order field: {@code GuiPlayerTabOverlay}
     * orders by team name then by the PROFILE name, and the same profile name is what it draws over the player's
     * head ({@code EntityPlayer.getName()}) and keys a below-name score by. So the sort name must still READ as
     * the player - {@link io.github.term4.archipelago.shard.ShardTabList} callers build it as a two-char section
     * prefix plus the real username, which sorts on the prefix and renders as the coloured name. Sent to LEGACY
     * viewers only; a modern client would draw the section codes literally and sorts on {@link #LIST_ORDER}.
     */
    public static final Tag<String> SORT_NAME = Tag.String("tab:sort-name");
    /** The 1.21.2+ per-entry list order (higher sorts first); ignored by pre-1.21.2 clients. */
    public static final Tag<Integer> LIST_ORDER = Tag.Integer("tab:list-order");
    /** Reports this player as SPECTATOR in everyone ELSE's tab - see {@link #spectatorTo}. */
    public static final Tag<Boolean> SPECTATOR = Tag.Boolean("tab:spectator");

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
    /** Who gets the 1.8 {@link #SORT_NAME} treatment; the app wires its client tracker in. Default: nobody, so an
     *  unwired server behaves exactly as it did. An UNKNOWN protocol must read as MODERN here - guessing legacy
     *  would draw raw section codes on a modern client, while guessing modern only mis-sorts until the resync. */
    private static volatile java.util.function.Predicate<Player> legacyViewers = player -> false;

    public static void legacyViewers(@NotNull java.util.function.Predicate<Player> predicate) {
        legacyViewers = predicate;
    }
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
            boolean changed = false;
            for (PlayerInfoUpdatePacket.Entry entry : info.entries()) {
                if (!allows(e.getPlayer(), entry.uuid())) { changed = true; continue; }
                PlayerInfoUpdatePacket.Entry shaped = shape(e.getPlayer(), entry);
                changed |= shaped != entry;
                allowed.add(shaped);
            }
            if (!changed) return; // untouched packets (and our own re-sends) pass
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

    /**
     * Diagnostic: exactly what {@code viewer}'s tab is being sent, entry by entry, plus the tags behind it. The
     * tab depends on the viewer's client generation, so a bug that shows on one screen and not another is only
     * legible from here - read it rather than inferring from a screenshot.
     */
    public static @NotNull List<String> describe(@NotNull Player viewer) {
        List<String> lines = new ArrayList<>();
        lines.add("viewer=" + viewer.getUsername() + " legacy=" + legacy(viewer));
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (p != viewer && !listed(viewer, p)) continue;
            PlayerInfoUpdatePacket.Entry e = entry(viewer, p);
            String team = p.getTeam() == null ? "-" : p.getTeam().getTeamName();
            String display = e.displayName() == null ? "null"
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(e.displayName()) + "@" + e.displayName().color();
            lines.add((p == viewer ? "SELF " : "     ") + p.getUsername()
                    + " name=" + e.username().replace('§', '&')
                    + " team=" + team // outranks the name in BOTH comparators
                    + " order=" + e.listOrder() + " mode=" + e.gameMode()
                    + " display=" + display);
        }
        return lines;
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

    /** Notified after {@code resync} (re)sends a player's entry. A name-keyed objective (tab health) re-asserts its
     *  scores here: a relisted entry drops any score that keyed on the name it had before the rename. */
    private static final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Player>> RESYNCED =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static @NotNull Runnable onResynced(@NotNull java.util.function.Consumer<Player> hook) {
        RESYNCED.add(hook);
        return () -> RESYNCED.remove(hook);
    }

    /** {@code player}'s world changed: full re-sync of their own list, and of their entry in everyone else's. A player
     *  under a fake sort name is REMOVEd before the ADD - a 1.8 client ignores a plain re-ADD of a name it already
     *  lists, so a rename only lands as remove-then-add. The shard binding settles a tick after the join transfer,
     *  and this fires on that view change, so the rename lands once everyone can finally see everyone. */
    public static void resync(@NotNull Player player) {
        if (!installed || !player.isOnline()) return;
        List<PlayerInfoUpdatePacket.Entry> adds = new ArrayList<>();
        List<UUID> removes = new ArrayList<>();
        List<UUID> selfId = List.of(player.getUuid());
        for (Player other : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (other == player) continue;
            // their list of the player - the entry is shaped for THEIR client generation
            if (listed(other, player)) other.sendPacket(new PlayerInfoUpdatePacket(ALL_ACTIONS, List.of(entry(other, player))));
            else other.sendPacket(new PlayerInfoRemovePacket(selfId));
            // the player's list of them
            if (listed(player, other)) adds.add(entry(player, other));
            else removes.add(other.getUuid());
        }
        if (!removes.isEmpty()) player.sendPacket(new PlayerInfoRemovePacket(removes));
        // the loop skips the viewer's own row, and it needs the same refresh - otherwise a player keeps sorting
        // under whatever name they logged in with. ADD_PLAYER REPLACES the whole entry on both eras
        // (1.8 NetHandlerPlayClient builds a new NetworkPlayerInfo), so a plain re-send is enough to rename.
        adds.add(entry(player, player));
        player.sendPacket(new PlayerInfoUpdatePacket(ALL_ACTIONS, adds));
        for (java.util.function.Consumer<Player> hook : RESYNCED) hook.accept(player);
    }

    /** Re-syncs every member's list after {@code shard}'s scope changed. */
    static void refreshShard(Shard shard) {
        if (!installed) return;
        for (Player member : shard.members()) resync(member);
    }

    private static final EnumSet<PlayerInfoUpdatePacket.Action> ALL_ACTIONS =
            EnumSet.allOf(PlayerInfoUpdatePacket.Action.class);

    /** The name a 1.8 client lists {@code player} under: the {@link #SORT_NAME} override, else the real name. A
     *  name-keyed objective (tab or below-name) has to score under this to line up on those clients. */
    public static @NotNull String listName(@NotNull Player player) {
        String sort = player.getTag(SORT_NAME);
        return sort != null ? sort : player.getUsername();
    }

    // the sort name is a 1.8 device: a modern client renders its section codes literally and sorts on listOrder
    private static boolean legacy(Player viewer) {
        return legacyViewers.test(viewer);
    }

    /**
     * Whether {@code subject}'s entry goes out to {@code viewer} flagged SPECTATOR. Both client generations then
     * do the work natively: 1.8's tab comparator tests spectator-ness FIRST (ahead of team and name, so no sort
     * name can outrank it), draws the row italic and dimmed, and skips its objective score. Never to the player
     * THEMSELVES - {@code AbstractClientPlayer.isSpectator()} reads its own entry's game type, so a self flag
     * would stop the client drawing them their own body.
     */
    private static boolean spectatorTo(Player viewer, Player subject) {
        return viewer != subject && Boolean.TRUE.equals(subject.getTag(SPECTATOR));
    }

    // the shape Player.infoEntry() builds (no chat session: re-added entries lose the signing badge, like proxies)
    private static PlayerInfoUpdatePacket.Entry entry(Player viewer, Player player) {
        PlayerSkin skin = player.getSkin();
        List<PlayerInfoUpdatePacket.Property> properties = skin != null && skin.signature() != null
                ? List.of(new PlayerInfoUpdatePacket.Property("textures", skin.textures(), skin.signature()))
                : List.of();
        String sort = legacy(viewer) ? player.getTag(SORT_NAME) : null;
        Integer order = player.getTag(LIST_ORDER);
        // no display-name fallback: a null one lets the client draw the profile name itself, which already reads
        // as the player. Pinning one here would freeze the pre-colour white name onto entries built before it lands.
        GameMode mode = spectatorTo(viewer, player) ? GameMode.SPECTATOR : player.getGameMode();
        return new PlayerInfoUpdatePacket.Entry(player.getUuid(), sort != null ? sort : player.getUsername(), properties, true,
                player.getLatency(), mode, player.getDisplayName(), null, order != null ? order : 0, true);
    }

    // an entry Minestom built with the real name/order, re-shaped to the player's tab overrides for THIS viewer
    private static PlayerInfoUpdatePacket.Entry shape(Player viewer, PlayerInfoUpdatePacket.Entry base) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(base.uuid());
        if (p == null) return base;
        String sort = legacy(viewer) ? p.getTag(SORT_NAME) : null;
        Integer order = p.getTag(LIST_ORDER);
        String name = sort != null ? sort : base.username();
        int listOrder = order != null ? order : base.listOrder();
        GameMode mode = spectatorTo(viewer, p) ? GameMode.SPECTATOR : base.gameMode();
        // idempotent: a re-send of an already-shaped entry returns the SAME object, so the out filter terminates
        if (name.equals(base.username()) && listOrder == base.listOrder() && mode == base.gameMode()) return base;
        return new PlayerInfoUpdatePacket.Entry(base.uuid(), name, base.properties(),
                base.listed(), base.latency(), mode, base.displayName(), base.chatSession(),
                listOrder, base.displayHat());
    }
}
