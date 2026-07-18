package test;

import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardLighting;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Archipelago-only example server: shards over one shared map, per-shard clocks, lighting, saves, replays.
 * No external libraries beyond Minestom + polar. The combined playground with MinestomMechanics combat lives
 * in the Tennacle repo.
 */
public final class TestServer {

    private static final String USAGE = "/shard create <name> | child <parent> <new> | join <name> | fork <name> <new>"
            + " | flatten <top> <new> | save <name> | load <saved> <new> | leave | reset <name> | destroy <name>"
            + " | list | tree | relight <name> <on|off|disable|custom> | sky <name> <day|night|rain|clear>"
            + " | spectate <name|off>  -  /replay, /gm <mode>";

    private static volatile Recording recording;
    private static volatile Playback playback;

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();
        CoreChannels.register();

        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        world.setChunkSupplier(LightingChunk::new);

        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            e.setSpawningInstance(world);
            e.getPlayer().setRespawnPoint(new Pos(0.5, 41, 0.5));
        });
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) return;
            Player p = e.getPlayer();
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().addItemStack(ItemStack.of(Material.STONE, 64));
            p.getInventory().addItemStack(ItemStack.of(Material.OAK_PLANKS, 64));
            p.getInventory().addItemStack(ItemStack.of(Material.GLOWSTONE, 64));
            p.getInventory().addItemStack(ItemStack.of(Material.TORCH, 64));
            p.getInventory().addItemStack(ItemStack.of(Material.IRON_PICKAXE));
            p.sendMessage(USAGE);
        });
        // members build into their shard's overlay, never the map
        events.addChild(Shard.blockRouting());

        ShardManager shards = new ShardManager();
        register(shards, world);

        server.start("0.0.0.0", 25565);
    }

    private static void register(ShardManager shards, InstanceContainer world) {
        Command shard = new Command("shard");
        var create = ArgumentType.Literal("create");
        var child = ArgumentType.Literal("child");
        var join = ArgumentType.Literal("join");
        var fork = ArgumentType.Literal("fork");
        var flatten = ArgumentType.Literal("flatten");
        var save = ArgumentType.Literal("save");
        var load = ArgumentType.Literal("load");
        var leave = ArgumentType.Literal("leave");
        var reset = ArgumentType.Literal("reset");
        var destroy = ArgumentType.Literal("destroy");
        var list = ArgumentType.Literal("list");
        var tree = ArgumentType.Literal("tree");
        var relight = ArgumentType.Literal("relight");
        var sky = ArgumentType.Literal("sky");
        var spectate = ArgumentType.Literal("spectate");
        Argument<String> name = ArgumentType.Word("name");
        Argument<String> newName = ArgumentType.Word("new");
        Argument<String> relightState = ArgumentType.Word("state").from("on", "off", "disable", "custom");
        Argument<String> skyValue = ArgumentType.Word("value").from("day", "night", "rain", "clear");

        shard.setDefaultExecutor((sender, ctx) -> sender.sendMessage("usage: " + USAGE));

        shard.addSyntax((sender, ctx) -> {
            String n = ctx.get(name);
            if (taken(shards, sender, n)) return;
            shards.create(world, n, ExecutionDomain.create()); // domain-bound: this game ticks on its own thread
            sender.sendMessage("[shard] created '" + n + "' on its own clock - /shard join " + n);
        }, create, name);

        shard.addSyntax((sender, ctx) -> {
            Shard parent = named(shards, sender, ctx.get(name));
            if (parent == null) return;
            String n = ctx.get(newName);
            if (taken(shards, sender, n)) return;
            shards.child(parent, n);
            sender.sendMessage("[shard] layer '" + n + "' over '" + ctx.get(name) + "' (same clock) - /shard join " + n);
        }, child, name, newName);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            g.join(p);
            p.sendMessage("[shard] joined '" + ctx.get(name) + "' - same map, your own diff");
        }, join, name);

        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            String n = ctx.get(newName);
            if (taken(shards, sender, n)) return;
            offClock(() -> {
                shards.fork(g, n, Shard::copyEntity);
                sender.sendMessage("[shard] forked '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
            });
        }, fork, name, newName);

        shard.addSyntax((sender, ctx) -> {
            Shard top = named(shards, sender, ctx.get(name));
            if (top == null) return;
            String n = ctx.get(newName);
            if (taken(shards, sender, n)) return;
            offClock(() -> {
                shards.flatten(top, n, Shard::copyEntity);
                sender.sendMessage("[shard] chain of '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
            });
        }, flatten, name, newName);

        Path saveDir = Storage.dir("save-dir", "shard-saves");
        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            offClock(() -> {
                try {
                    Files.createDirectories(saveDir);
                    Path file = saveDir.resolve(ctx.get(name) + ".shard");
                    ShardFile.save(g, file);
                    sender.sendMessage("[shard] saved '" + ctx.get(name) + "' -> " + file);
                } catch (IOException e) {
                    sender.sendMessage("[shard] save failed: " + e.getMessage());
                }
            });
        }, save, name);

        shard.addSyntax((sender, ctx) -> {
            String n = ctx.get(newName);
            if (taken(shards, sender, n)) return;
            offClock(() -> {
                try {
                    byte[] data = Files.readAllBytes(saveDir.resolve(ctx.get(name) + ".shard"));
                    Shard fresh = shards.create(world, n, ExecutionDomain.create());
                    ShardFile.load(fresh, data);
                    sender.sendMessage("[shard] loaded '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
                } catch (IOException | RuntimeException e) {
                    sender.sendMessage("[shard] load failed: " + e.getMessage());
                }
            });
        }, load, name, newName);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = Shard.of(p);
            if (g == null) { p.sendMessage("[shard] not in a shard"); return; }
            g.leave(p);
            p.sendMessage("[shard] left - back on the base map");
        }, leave);

        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            offClock(() -> {
                g.reset();
                sender.sendMessage("[shard] '" + ctx.get(name) + "' reset - diff dropped");
            });
        }, reset, name);

        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            shards.destroy(g);
            sender.sendMessage("[shard] '" + ctx.get(name) + "' destroyed");
        }, destroy, name);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> sender.sendMessage(
                "- " + label(shards, g) + " members=" + g.members().size()
                        + " observers=" + g.observers().size() + " diff=" + g.overlaySize())), list);

        shard.addSyntax((sender, ctx) -> {
            var all = shards.shards();
            if (all.isEmpty()) { sender.sendMessage("[shard] none"); return; }
            for (Shard s : all) {
                if (s.parent() == null || !all.contains(s.parent())) printTree(sender, shards, s, 0);
            }
        }, tree);

        // chain-effective light as per-viewer UpdateLight overlays (modern clients; 1.8 self-relights).
        // 'disable' = NO system (client-local relight is all you see); 'off' = a demo system that FORCES
        // darkness (the visible proof of server light authority); 'custom' = a stub narrating its hooks
        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            String n = ctx.get(name);
            switch (ctx.get(relightState)) {
                case "on" -> {
                    g.relight(true);
                    sender.sendMessage("[shard] relight ON for '" + n
                            + "' - place a torch/glowstone or roof something in and watch the light (modern client)");
                }
                case "off" -> {
                    g.relight(new DarkLight(g));
                    sender.sendMessage("[shard] relight OFF (forced dark) for '" + n
                            + "' - place a torch: it glows for ~1s (your client's local light), then the server snuffs it");
                }
                case "disable" -> {
                    g.relight(false);
                    sender.sendMessage("[shard] relight DISABLED for '" + n
                            + "' - no server light for shard edits; a torch still glows because your client relights"
                            + " locally around block changes it sees");
                }
                case "custom" -> {
                    g.relight(new NarratingLight(g, n));
                    sender.sendMessage("[shard] relight CUSTOM for '" + n
                            + "' - place blocks and watch the hook narration; /shard relight " + n + " disable removes it");
                }
            }
        }, relight, name, relightState);

        shard.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            switch (ctx.get(skyValue)) {
                case "day" -> g.setTime(1000);
                case "night" -> g.setTime(18000);
                case "rain" -> g.setWeather(new net.minestom.server.instance.Weather(1f, 0f));
                case "clear" -> g.setWeather(new net.minestom.server.instance.Weather(0f, 0f));
            }
            sender.sendMessage("[shard] '" + ctx.get(name) + "' sky -> " + ctx.get(skyValue));
        }, sky, name, skyValue);

        // observer view: see a shard's blocks/entities without joining it ('off' = back to your own view)
        Argument<String> spectateTarget = ArgumentType.Word("target");
        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String target = ctx.get(spectateTarget);
            if ("off".equals(target)) {
                ShardView.set(p, null);
                p.sendMessage("[shard] spectate off");
                return;
            }
            Shard g = named(shards, sender, target);
            if (g == null) return;
            ShardView.set(p, ShardView.of(g));
            p.sendMessage("[shard] spectating '" + target + "' - /shard spectate off to stop");
        }, spectate, spectateTarget);

        MinecraftServer.getCommandManager().register(shard);
        MinecraftServer.getCommandManager().register(new Bench(world).command());
        registerReplay(shards, world);
        registerGamemode();
    }

    private static void registerReplay(ShardManager shards, InstanceContainer world) {
        Command replay = new Command("replay");
        var record = ArgumentType.Literal("record");
        var stop = ArgumentType.Literal("stop");
        var play = ArgumentType.Literal("play");
        var seek = ArgumentType.Literal("seek");
        var rate = ArgumentType.Literal("rate");
        var reverse = ArgumentType.Literal("reverse");
        var pause = ArgumentType.Literal("pause");
        Argument<String> name = ArgumentType.Word("name");
        Argument<Long> tick = ArgumentType.Long("tick");
        Argument<Double> speed = ArgumentType.Double("speed");

        replay.setDefaultExecutor((sender, ctx) -> sender.sendMessage(
                "usage: /replay record <shard> | stop | play <new-theater> | seek <tick> | rate <speed> | reverse | pause"));

        replay.addSyntax((sender, ctx) -> {
            Shard g = named(shards, sender, ctx.get(name));
            if (g == null) return;
            if (recording != null && recording.active()) { sender.sendMessage("[replay] already recording"); return; }
            recording = Replay.record(g);
            sender.sendMessage("[replay] recording '" + ctx.get(name) + "' - /replay stop");
        }, record, name);

        replay.addSyntax((sender, ctx) -> {
            Recording rec = recording;
            if (rec == null) { sender.sendMessage("[replay] nothing recording"); return; }
            offClock(() -> {
                rec.stop();
                sender.sendMessage("[replay] stopped after " + rec.tick() + " ticks - /replay play <new-theater>");
            });
        }, stop);

        replay.addSyntax((sender, ctx) -> {
            Recording rec = recording;
            if (rec == null) { sender.sendMessage("[replay] record something first"); return; }
            String n = ctx.get(name);
            if (taken(shards, sender, n)) return;
            offClock(() -> {
                if (rec.active()) rec.stop();
                Shard theater = shards.create(world, n, ExecutionDomain.create());
                playback = Replay.play(theater, rec);
                sender.sendMessage("[replay] playing in '" + n + "' (" + playback.length() + " ticks) - /shard spectate " + n);
            });
        }, play, name);

        replay.addSyntax((sender, ctx) -> {
            Playback pb = playback;
            if (pb == null || !pb.active()) { sender.sendMessage("[replay] nothing playing"); return; }
            long target = ctx.get(tick);
            offClock(() -> {
                pb.seek(target);
                sender.sendMessage("[replay] seek -> " + target);
            });
        }, seek, tick);

        replay.addSyntax((sender, ctx) -> {
            Playback pb = playback;
            if (pb == null || !pb.active()) { sender.sendMessage("[replay] nothing playing"); return; }
            pb.rate(ctx.get(speed));
            sender.sendMessage("[replay] rate -> " + ctx.get(speed));
        }, rate, speed);

        replay.addSyntax((sender, ctx) -> {
            Playback pb = playback;
            if (pb == null || !pb.active()) { sender.sendMessage("[replay] nothing playing"); return; }
            pb.reverse(!pb.reversed());
            sender.sendMessage("[replay] reversed -> " + pb.reversed());
        }, reverse);

        replay.addSyntax((sender, ctx) -> {
            Playback pb = playback;
            if (pb == null || !pb.active()) { sender.sendMessage("[replay] nothing playing"); return; }
            pb.pause(!pb.paused());
            sender.sendMessage("[replay] paused -> " + pb.paused());
        }, pause);

        MinecraftServer.getCommandManager().register(replay);
    }

    private static void registerGamemode() {
        Command gm = new Command("gm");
        Argument<GameMode> mode = ArgumentType.Enum("mode", GameMode.class);
        gm.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) p.setGameMode(ctx.get(mode));
        }, mode);
        MinecraftServer.getCommandManager().register(gm);
    }

    private static @org.jetbrains.annotations.Nullable Shard named(ShardManager shards, CommandSender sender, String name) {
        Shard g = shards.byName(name);
        if (g == null) sender.sendMessage("[shard] no shard named '" + name + "'");
        return g;
    }

    private static boolean taken(ShardManager shards, CommandSender sender, String name) {
        if (shards.byName(name) == null) return false;
        sender.sendMessage("[shard] '" + name + "' already exists");
        return true;
    }

    // a domain player's commands run on THEIR shard's clock; ops that relay into another live shard
    // (snapshots: fork/flatten/save/load/reset, replay stop/play/seek) must hop off it first
    private static void offClock(Runnable body) {
        if (ExecutionDomain.onClockThread()) Thread.startVirtualThread(body);
        else body.run();
    }

    private static String label(ShardManager shards, Shard shard) {
        String name = shards.nameOf(shard);
        return name != null ? name : "(unnamed)";
    }

    private static void printTree(CommandSender sender, ShardManager shards, Shard shard, int depth) {
        var d = shard.domain();
        sender.sendMessage("  ".repeat(depth) + "- " + label(shards, shard) + " diff=" + shard.overlaySize()
                + " members=" + shard.members().size()
                + (d == null ? " server-ticked" : " on " + d.metrics().threadName()));
        for (Shard child : shard.children()) printTree(sender, shards, child, depth + 1);
    }

    /**
     * Demo {@link ShardLighting} that FORCES zero light (block and sky) in every chain-touched chunk. The snuff
     * is delayed so the client's local prediction shows first - torch glows, then the server kills it: the
     * visible proof that server light overrides client light.
     */
    private static final class DarkLight implements ShardLighting {
        private static final int SNUFF_DELAY = 20;
        private final Shard shard;
        private final Runnable unhook;
        private final java.util.Set<Long> lit = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Map<Long, Long> due = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile long clock;

        DarkLight(Shard shard) {
            this.shard = shard;
            this.unhook = shard.onTick(t -> tick());
            changed(ShardLighting.chainChunks(shard)); // already-built areas snuff too
        }

        private net.minestom.server.network.packet.server.play.UpdateLightPacket dark(int chunkX, int chunkZ) {
            var dim = shard.instance().getCachedDimensionType();
            int sections = (dim.maxY() >> 4) - (dim.minY() >> 4);
            java.util.BitSet empty = new java.util.BitSet();
            empty.set(0, sections + 2); // every section incl. the below/above padding
            return new net.minestom.server.network.packet.server.play.UpdateLightPacket(chunkX, chunkZ,
                    new net.minestom.server.network.packet.server.play.data.LightData(
                            new java.util.BitSet(), new java.util.BitSet(), empty, empty, List.of(), List.of()));
        }

        private void tick() {
            long now = ++clock;
            for (var it = due.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() > now) continue;
                it.remove();
                long index = e.getKey();
                if (!candidateChunks().contains(index)) {
                    if (lit.remove(index)) ShardLighting.restoreBase(shard, index); // last diff broke: back to base
                    continue;
                }
                ShardLighting.broadcast(shard, dark(CoordConversion.chunkIndexGetX(index), CoordConversion.chunkIndexGetZ(index)));
                lit.add(index);
            }
        }

        @Override public void changed(java.util.Set<Long> chunks) {
            for (long index : chunks) due.put(index, clock + SNUFF_DELAY);
        }
        @Override public void refreshLit() { changed(lit); }
        @Override public void sendTo(Player player, int chunkX, int chunkZ) {
            long index = CoordConversion.chunkIndex(chunkX, chunkZ);
            if (!candidateChunks().contains(index)) return;
            player.sendPacket(dark(chunkX, chunkZ));
            lit.add(index);
        }
        @Override public void close() {
            unhook.run();
            due.clear();
            for (long index : java.util.Set.copyOf(lit)) ShardLighting.restoreBase(shard, index);
            lit.clear();
        }
        @Override public net.minestom.server.network.packet.server.SendablePacket packet(int chunkX, int chunkZ) {
            return candidateChunks().contains(CoordConversion.chunkIndex(chunkX, chunkZ)) ? dark(chunkX, chunkZ) : null;
        }
        @Override public java.util.Set<Long> litChunks() { return lit; }
        @Override public void markLit(long chunkIndex) { lit.add(chunkIndex); }
        @Override public java.util.Set<Long> candidateChunks() { return ShardLighting.chainChunks(shard); }
        @Override public int blockLight(int x, int y, int z) {
            return candidateChunks().contains(CoordConversion.chunkIndex(x >> 4, z >> 4)) ? 0 : -1;
        }
        @Override public int skyLight(int x, int y, int z) {
            return candidateChunks().contains(CoordConversion.chunkIndex(x >> 4, z >> 4)) ? 0 : -1;
        }
    }

    /** Demo custom {@link ShardLighting}: no packets, just chat narration of every hook the shard drives. */
    private record NarratingLight(Shard shard, String name) implements ShardLighting {
        private void say(String what) {
            for (Player p : shard.members()) p.sendMessage("[light:" + name + "] " + what);
        }
        @Override public void changed(java.util.Set<Long> chunks) { say("changed x" + chunks.size() + " chunk(s)"); }
        @Override public void refreshLit() { say("refreshLit (reset/ancestor churn)"); }
        @Override public void sendTo(Player player, int chunkX, int chunkZ) {
            player.sendMessage("[light:" + name + "] sendTo " + chunkX + "," + chunkZ);
        }
        @Override public void close() { say("closed - base light untouched, nothing to restore"); }
        @Override public net.minestom.server.network.packet.server.SendablePacket packet(int chunkX, int chunkZ) { return null; }
        @Override public java.util.Set<Long> litChunks() { return java.util.Set.of(); }
        @Override public void markLit(long chunkIndex) {}
        @Override public java.util.Set<Long> candidateChunks() { return java.util.Set.of(); }
    }
}
