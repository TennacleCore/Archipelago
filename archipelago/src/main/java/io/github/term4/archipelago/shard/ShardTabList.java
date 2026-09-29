package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.Viewers;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.Event;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * The name a client before 1.21.2 sorts this player under. It has no list-order field: the tab
     * ({@code GuiPlayerTabOverlay} on 1.8) orders by team name then by the PROFILE name, and the same profile name
     * is what it draws over the player's head ({@code EntityPlayer.getName()}) and keys a below-name score by. So
     * the sort name must still READ as the player - {@link io.github.term4.archipelago.shard.ShardTabList} callers
     * build it as a two-char section prefix plus the real username, which sorts on the prefix and renders as the
     * colored name. Sent to those viewers only ({@link Viewers#sortsByName}); from 1.21.2 the tab sorts on
     * {@link #LIST_ORDER}.
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

    /** The row {@code viewer} lists {@code subject} under, after the list's own shaping: a name they are not to
     *  read yet, say. Applied to ADD_PLAYER rows and to display-name updates; the viewer's own row passes too. */
    @FunctionalInterface
    public interface EntryRule {
        PlayerInfoUpdatePacket.@NotNull Entry entry(@NotNull Player viewer, @NotNull Player subject,
                                                    PlayerInfoUpdatePacket.@NotNull Entry entry);
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<EntryRule> ENTRY_RULES =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Installs {@code rule}; the returned runnable removes it. Re-sync the affected viewers when what it answers changes. */
    public static @NotNull Runnable entries(@NotNull EntryRule rule) {
        ENTRY_RULES.add(rule);
        return () -> ENTRY_RULES.remove(rule);
    }

    private static PlayerInfoUpdatePacket.Entry ruled(Player viewer, Player subject, PlayerInfoUpdatePacket.Entry entry) {
        for (EntryRule rule : ENTRY_RULES) entry = rule.entry(viewer, subject, entry);
        return entry;
    }

    /** @deprecated one era per viewer, library-wide: {@link Viewers#protocol}. */
    @Deprecated
    public static void viewerProtocol(@NotNull java.util.function.ToIntFunction<Player> source) {
        Viewers.protocol(source);
    }

    /** @deprecated {@link Viewers#protocolWhenUnknown}. */
    @Deprecated
    public static void protocolWhenUnknown(int protocol) {
        Viewers.protocolWhenUnknown(protocol);
    }

    private static volatile boolean installed;
    private static final List<EventListener<? extends Event>> listeners = new ArrayList<>();

    private static <E extends Event> EventListener<E> register(EventNode<Event> events, Class<E> type,
                                                               java.util.function.Consumer<E> handler) {
        EventListener<E> listener = EventListener.of(type, handler);
        events.addListener(listener);
        return listener;
    }

    /** Takes the tab scoping back off. The listeners go by reference, so nothing else shifts in the order. */
    public static void uninstall() {
        synchronized (ShardTabList.class) {
            if (!installed) return;
            installed = false;
            var events = MinecraftServer.getGlobalEventHandler();
            for (EventListener<? extends Event> listener : listeners) events.removeListener(listener);
            listeners.clear();
        }
    }

    private ShardTabList() {}

    /**
     * Registers an override rule (first non-null answer wins, before the scope logic) and re-syncs everyone.
     * The returned handle unregisters it. E.g. members listing their spectators:
     * {@code rule((viewer, subject) -> ShardView.of(subject).blocks() == Shard.of(viewer) ? true : null)}.
     */
    public static @NotNull Runnable rule(@NotNull TabRule rule) {
        return rule(rule, ShardTabList::resyncAll);
    }

    /**
     * {@link #rule(TabRule)} for a rule that only ever answers for one audience: {@code resync} refreshes that
     * audience alone, on both ends. A rule per game re-sending every list on the server twice a match is
     * quadratic in the players online.
     */
    public static @NotNull Runnable rule(@NotNull TabRule rule, @NotNull Runnable resync) {
        RULES.add(rule);
        resync.run();
        return () -> {
            RULES.remove(rule);
            resync.run();
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
        listeners.add(register(events, PlayerPacketOutEvent.class, e -> {
            if (!(e.getPacket() instanceof PlayerInfoUpdatePacket info)) return;
            // every action, not only ADD: an update for a subject outside the viewer's scope is a name and a
            // uuid they were never meant to have, even though their client renders nothing for it
            boolean adding = info.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER);
            boolean naming = info.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
            List<PlayerInfoUpdatePacket.Entry> allowed = new ArrayList<>(info.entries().size());
            boolean changed = false;
            for (PlayerInfoUpdatePacket.Entry entry : info.entries()) {
                if (!allows(e.getPlayer(), entry.uuid())) { changed = true; continue; }
                PlayerInfoUpdatePacket.Entry shaped = adding ? shape(e.getPlayer(), entry)
                        : naming ? renamed(e.getPlayer(), entry) : entry;
                changed |= shaped != entry;
                allowed.add(shaped);
            }
            if (!changed) { record(e.getPlayer(), info); return; } // untouched packets (and our re-sends) pass
            e.setCancelled(true);
            if (!allowed.isEmpty()) e.getPlayer().sendPacket(new PlayerInfoUpdatePacket(info.actions(), allowed));
        }));
        // instance change: the world changed without a view change
        listeners.add(register(events, PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) resync(e.getPlayer());
        }));
        // without this the diagnostic buffer keeps one entry per player the server has EVER seen
        listeners.add(register(events, PlayerDisconnectEvent.class, e -> {
            SENT.remove(e.getPlayer().getUuid());
            ONLINE.remove(e.getPlayer().getUuid());
        }));
        // covers join/leave/spectate, and fires BEFORE the viewable rules - the spawns those send are only
        // rendered when the client already has the info entry (see the hook's doc)
        ShardView.onViewChange(ShardTabList::resync);
    }

    private static final int HISTORY = 40;
    private static final java.util.Map<UUID, java.util.ArrayDeque<String>> SENT = new java.util.concurrent.ConcurrentHashMap<>();

    /** The last {@value #HISTORY} tab packets that actually reached {@code viewer}, oldest first: the evidence for
     *  an intermittent row (a color that came and went) - the SEQUENCE is what a snapshot cannot show. */
    public static @NotNull List<String> history(@NotNull Player viewer) {
        java.util.ArrayDeque<String> log = SENT.get(viewer.getUuid());
        return log == null ? List.of() : List.copyOf(log);
    }

    // recorded only for a packet that passes through to the client (a cancelled original never arrives). Latency
    // pings are skipped: they would drown the buffer and cannot touch a name, color or mode
    private static void record(Player viewer, PlayerInfoUpdatePacket info) {
        if (!viewer.isOnline()) return; // a queued write draining after the disconnect eviction would re-add the entry
        var actions = info.actions();
        if (!actions.contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)
                && !actions.contains(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)
                && !actions.contains(PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)
                && !actions.contains(PlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER)) return;
        StringBuilder line = new StringBuilder();
        line.append(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))).append(' ');
        if (actions.size() == PlayerInfoUpdatePacket.Action.values().length) line.append("ALL");
        else for (PlayerInfoUpdatePacket.Action a : actions) line.append(switch (a) {
            case ADD_PLAYER -> "ADD "; case UPDATE_DISPLAY_NAME -> "NAME "; case UPDATE_GAME_MODE -> "MODE ";
            case UPDATE_LIST_ORDER -> "ORDER "; case UPDATE_LISTED -> "LISTED "; default -> a.name().charAt(0) + " ";
        });
        for (PlayerInfoUpdatePacket.Entry e : info.entries()) {
            String display = e.displayName() == null ? "-"
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(e.displayName()) + "@" + e.displayName().color();
            line.append(" [").append(e.username().replace('\u00a7', '&')).append(' ')
                    .append(e.gameMode()).append(' ').append(display).append(']');
        }
        SENT.compute(viewer.getUuid(), (k, log) -> {
            if (log == null) log = new java.util.ArrayDeque<>();
            if (log.size() >= HISTORY) log.pollFirst();
            log.addLast(line.toString());
            return log;
        });
    }

    /**
     * Diagnostic: exactly what {@code viewer}'s tab is being sent, entry by entry, plus the tags behind it. The
     * tab depends on the viewer's client generation, so a bug that shows on one screen and not another is only
     * legible from here - read it rather than inferring from a screenshot.
     */
    public static @NotNull List<String> describe(@NotNull Player viewer) {
        List<String> lines = new ArrayList<>();
        lines.add("viewer=" + viewer.getUsername() + " byName=" + Viewers.sortsByName(viewer));
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (p != viewer && !listed(viewer, p)) continue;
            PlayerInfoUpdatePacket.Entry e = entry(viewer, p);
            // Team.addMember(String) never sets LivingEntity.team, so getTeam() lies: look the name up on the rosters
            String team = "-";
            for (net.minestom.server.scoreboard.Team t : MinecraftServer.getTeamManager().getTeams()) {
                if (t.getMembers().contains(p.getUsername())) { team = t.getTeamName(); break; }
            }
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
        Player subject = online(uuid);
        return subject == null || listed(viewer, subject);
    }

    // getOnlinePlayerByUuid walks every online player; this runs per entry per packet per viewer
    private static final Map<UUID, Player> ONLINE = new ConcurrentHashMap<>();

    private static @Nullable Player online(UUID uuid) {
        Player cached = ONLINE.get(uuid);
        if (cached != null && cached.isOnline()) return cached;
        Player found = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
        if (found != null) ONLINE.put(uuid, found);
        else ONLINE.remove(uuid);
        return found;
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

    /** The name a client that sorts by name lists {@code player} under: the {@link #SORT_NAME} override, else the
     *  real name. A name-keyed objective (tab or below-name) has to score under this to line up on those clients. */
    public static @NotNull String listName(@NotNull Player player) {
        String sort = player.getTag(SORT_NAME);
        return sort != null ? sort : player.getUsername();
    }

    /** {@link #listName(Player)} as {@code viewer}'s own client holds it: from 1.21.2 it never saw the sort name. */
    public static @NotNull String listName(@NotNull Player player, @NotNull Player viewer) {
        return Viewers.sortsByName(viewer) ? listName(player) : player.getUsername();
    }

    /**
     * Whether {@code subject}'s entry goes out to {@code viewer} flagged SPECTATOR. Both client generations then
     * do the work natively: 1.8's tab comparator tests spectator-ness FIRST (ahead of team and name, so no sort
     * name can outrank it), draws the row italic and dimmed, and skips its objective score. Never to the player
     * THEMSELVES - {@code AbstractClientPlayer.isSpectator()} reads its own entry's game type, so a self flag
     * would stop the client drawing them their own body. A viewer who may SEE a flagged subject (a co-spectator)
     * draws them as 1.8 draws a spectator: the head alone ({@code RenderPlayer.setModelVisibilities}).
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
        String sort = Viewers.sortsByName(viewer) ? player.getTag(SORT_NAME) : null;
        Integer order = player.getTag(LIST_ORDER);
        // no display-name fallback: a null one lets the client draw the profile name itself, which already reads
        // as the player. Pinning one here would freeze the pre-color white name onto entries built before it lands.
        GameMode mode = spectatorTo(viewer, player) ? GameMode.SPECTATOR : player.getGameMode();
        return ruled(viewer, player, new PlayerInfoUpdatePacket.Entry(player.getUuid(), sort != null ? sort : player.getUsername(),
                properties, true, player.getLatency(), mode, player.getDisplayName(), null, order != null ? order : 0, true));
    }

    /**
     * The entry as it must read AT WRITE TIME. {@code sendPacket} only enqueues; this event fires when the
     * connection's write thread drains the queue, so an entry built before a join colored its player can be
     * written after the color - and an entry this filter cancels is re-enqueued at the TAIL, behind everything
     * queued since, the NAME packet included. Patching the stale snapshot forwarded its null display and blanked
     * a row that packet had just colored. So a reshaped entry is rebuilt from live state, and the original
     * object is kept only when nothing differs - which is also what lets the filter's own re-send terminate.
     */
    // a display-name update under the entry rules, so a name a viewer is not to read never rides one
    private static PlayerInfoUpdatePacket.Entry renamed(Player viewer, PlayerInfoUpdatePacket.Entry base) {
        if (ENTRY_RULES.isEmpty()) return base;
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(base.uuid());
        if (p == null) return base;
        PlayerInfoUpdatePacket.Entry ruled = ruled(viewer, p, base);
        return java.util.Objects.equals(ruled.displayName(), base.displayName()) ? base : ruled;
    }

    private static PlayerInfoUpdatePacket.Entry shape(Player viewer, PlayerInfoUpdatePacket.Entry base) {
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(base.uuid());
        if (p == null) return base;
        PlayerInfoUpdatePacket.Entry fresh = entry(viewer, p);
        boolean same = fresh.username().equals(base.username()) && fresh.listOrder() == base.listOrder()
                && fresh.gameMode() == base.gameMode()
                && java.util.Objects.equals(fresh.displayName(), base.displayName());
        return same ? base : fresh;
    }
}
