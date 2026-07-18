package io.github.term4.archipelago.world;

import io.github.term4.archipelago.event.ShardJoinEvent;
import io.github.term4.archipelago.event.ShardLeaveEvent;
import io.github.term4.archipelago.event.ShardTransferEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.condition.CommandCondition;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * World-scoped commands: registered like any Minestom command, but visible and executable only for players whose
 * {@link WorldAccess#of(net.minestom.server.entity.Entity) world} matches - a {@code /bed} that exists inside a bedwars shard and nowhere
 * else. Scoping rides Minestom's own {@link CommandCondition}, so the client's command list and tab completion
 * follow it; the list re-sends itself whenever a player's world changes (shard join/leave/transfer, instance
 * spawn). Non-player senders (console) always pass.
 */
public final class WorldCommands {

    private static volatile boolean refreshHooked;

    private WorldCommands() {}

    /**
     * Registers {@code command} scoped to worlds matching {@code where} (composed onto any condition the command
     * already carries). Returns the unregister handle - it also re-sends affected players' command lists.
     */
    public static @NotNull Runnable register(@NotNull Command command, @NotNull Predicate<@NotNull WorldAccess> where) {
        CommandCondition existing = command.getCondition();
        command.setCondition((sender, commandString) -> {
            if (existing != null && !existing.canUse(sender, commandString)) return false;
            if (!(sender instanceof Player p)) return true;
            if (p.getInstance() == null) return false; // pre-spawn command-list build
            return where.test(WorldAccess.of(p));
        });
        hookRefresh();
        MinecraftServer.getCommandManager().register(command);
        refreshOnline();
        return () -> {
            MinecraftServer.getCommandManager().unregister(command);
            refreshOnline();
        };
    }

    // membership changes re-check every scoped condition; one global hook, shared by all registrations
    private static void hookRefresh() {
        if (refreshHooked) return;
        synchronized (WorldCommands.class) {
            if (refreshHooked) return;
            refreshHooked = true;
            var events = MinecraftServer.getGlobalEventHandler();
            events.addListener(ShardJoinEvent.class, e -> e.player().refreshCommands());
            events.addListener(ShardLeaveEvent.class, e -> e.player().refreshCommands());
            events.addListener(ShardTransferEvent.class, e -> {
                if (e.entity() instanceof Player p) p.refreshCommands();
            });
            // instance change: the world changed without any shard event
            events.addListener(PlayerSpawnEvent.class, e -> {
                if (!e.isFirstSpawn()) e.getPlayer().refreshCommands();
            });
        }
    }

    private static void refreshOnline() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) p.refreshCommands();
    }
}
