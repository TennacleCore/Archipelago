package test;

import io.github.term4.minestommechanics.MechanicsKeys;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeSystem;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingSystem;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.presets.Preset;
import io.github.term4.minestommechanics.presets.PrimedTnt;
import io.github.term4.minestommechanics.presets.hypixel.Hypixel;
import io.github.term4.minestommechanics.presets.vanilla18.Vanilla18;
import io.github.term4.minestommechanics.platform.compatibility.Compat18;
import io.github.term4.minestommechanics.platform.fixes.Fixes18;
import io.github.term4.minestommechanics.platform.fixes.FixesSystem;
import io.github.term4.minestommechanics.platform.fixes.client.SelfPlacementFix;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.LightingChunk;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.vri.Vri;
import io.github.term4.minestommechanics.vri.VriConfig;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardLighting;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.event.replay.PlaybackStopEvent;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.polar.PolarShardCodec;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.replay.SpawnMode;
import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.archipelago.world.WorldAccess;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.Auth;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Archipelago playground WITH MinestomMechanics (from mavenLocal) glued in via {@link ShardBridge} - the reference
 * for using both libraries. {@code /shard create|join} a game, build/dig, {@code /boom} for shard-scoped 1.8
 * explosions + knockback, spectate from a second client.
 */
public final class TestServer {

    /** The active preset - swap this ONE line to change the whole setup (mechanics profile + the primed-TNT entity, together). */
    private static final Preset PRESET = Preset.HYPIXEL;

    private static Preset presetFor(Player p) {
        return PRESET;
    }

    private static ExplosionSystem explosions;
    // demo keeps one recording/playback at a time (the lib supports many); volatile - stop events for a domain
    // theater fire on its clock thread
    private static volatile Recording recording;
    private static volatile Playback playback;
    // the entity recipe: a shard save IS just a diff - descriptors ride the DataKey user-data section
    private static final DataKey<BinaryTag> SAVED_ENTITIES = new DataKey<>("demo:entities", Codec.NBT);

    public static void main(String[] args) {
        // the mm ExampleServer's platform flags, verbatim
        System.setProperty("minestom.new-socket-write-lock", "true");
        System.setProperty("minestom.enforce-entity-interaction-range", "false"); // mechanics handles reach

        MinecraftServer server = MinecraftServer.init(new Auth.Bungee()); // bungee auth lets 1.7/Via-proxy clients join

        // vanilla 1.8 mechanics + the full compat/fixes layer over shards: the bridge below is the whole integration
        MinestomMechanics mm = MinestomMechanics.getInstance();
        mm.viaProxyDetails = true;
        mm.init();
        mm.profiles().setGlobal(PRESET.profile().toBuilder()
                .set(MechanicsKeys.COMPAT, Compat18.config())
                .set(MechanicsKeys.FIXES, Fixes18.config())
                .build());
        AttackSystem.install(mm);
        DamageSystem.install(mm);
        KnockbackSystem.install(mm);
        ProjectileSystem projectiles = ProjectileSystem.install(mm);
        AttributeSystem.install(mm);
        ConsumableSystem.install(mm);
        BlockingSystem.install(mm);
        FixesSystem.install(mm); // legacy-client fix set (targeted placement rollback, self-place, equipment, ...)
        Vri.install(mm, VriConfig.all()); // crack overlay, break FX, drops, pickup - all shard-aware
        explosions = ExplosionSystem.install(mm);
        ShardBridge.install();
        // save/load glue for mechanics entities: the codec reads archipelago's ENTITY_SAVE natively; mechanics stamps
        // its own mirror, so point the save side at it and bind the load-side reviver
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("mm:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("mm:projectile", projectiles::fromSave);
        ReplayBridge.install(); // replay-side mechanics glue (launch capture, shooter scrub, footage flags)
        StateChannels.register(); // opt-in player-state channels (health/hunger/xp/inventory) - /replay state

        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setExplosionSupplier(explosions.supplier()); // route instance.explode(...) through the system
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
            p.getInventory().addItemStack(ItemStack.of(Material.LADDER, 64));
            p.getInventory().addItemStack(ItemStack.of(Material.IRON_PICKAXE));
            // TODO: Update to use components
            p.sendMessage("/shard create <name> | child <parent> <new> | join <name> | fork <name> <new> | flatten <top> [bottom] <new> | save <name> | load <saved> <new> | leave | reset <name> | destroy <name> | list | tree | relight <name> <on|off|disable|custom> | spectate <name|off> | sky <name> <day|night|rain|clear>  -  /gm <mode>");
        });

        // TNT test item: placing TNT spawns a primed-TNT entity instead of a block, from the active PRESET - so its
        // wire/bounce/fuse always match the profile above (a mismatch made a Hypixel-profile run behave like MineMen).
        events.addListener(PlayerBlockPlaceEvent.class, event -> {
            if (!event.getBlock().compare(Block.TNT)) return;
            event.setCancelled(true);
            Player p = event.getPlayer();
            if (p.getInstance() == null || explosions == null) return;
            PrimedTnt.spawn(explosions, MechanicsWorld.of(p), event.getBlockPosition(), presetFor(p).tnt);
            if (p.getGameMode() != GameMode.CREATIVE) { // cancelling the place keeps the item, so consume one like vanilla TNT
                ItemStack held = p.getItemInHand(event.getHand());
                p.setItemInHand(event.getHand(), held.withAmount(held.amount() - 1));
            }
        });

        // members build into their shard's overlay, never the map; also installs the world-scoped base
        // placement listener - the bridge re-wraps it with mm's 1.8 self-place arming (LAST, so it wins)
        events.addChild(Shard.blockRouting());
        SelfPlacementFix.install(Shard::placementListener);

        ShardManager shards = new ShardManager();
        ShardBridge.installDomainTicking();
        register(shards, world);

        server.start("0.0.0.0", 25566);
    }

    private static void register(ShardManager shards, InstanceContainer world) {
        Command shard = new Command("shard");
        var create = ArgumentType.Literal("create");
        var join = ArgumentType.Literal("join");
        var leave = ArgumentType.Literal("leave");
        var reset = ArgumentType.Literal("reset");
        var destroy = ArgumentType.Literal("destroy");
        var list = ArgumentType.Literal("list");
        var spectate = ArgumentType.Literal("spectate");
        var sky = ArgumentType.Literal("sky");
        Argument<String> name = ArgumentType.Word("name");
        Argument<String> skyValue = ArgumentType.Word("value").from("day", "night", "rain", "clear");

        shard.setDefaultExecutor((sender, ctx) ->
                sender.sendMessage("usage: /shard create <name> | child <parent> <new> | join <name> | fork <name> <new> | flatten <top> [bottom] <new> | save <name> | load <saved> <new> | leave | reset <name> | destroy <name> | list | tree | relight <name> <on|off|disable|custom> | spectate <name|off> | sky <name> <day|night|rain|clear> | tps | stall <name> <ms> | spin <name> <ms>"));

        shard.addSyntax((sender, ctx) -> {
            String n = ctx.get(name);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            shards.create(world, n, ExecutionDomain.create()); // domain-bound: this game ticks on its own thread
            sender.sendMessage("[shard] created '" + n + "' on its own clock - /shard join " + n);
        }, create, name);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { p.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            g.join(p);
            p.sendMessage("[shard] joined '" + ctx.get(name) + "' - same map, your own diff");
        }, join, name);

        var fork = ArgumentType.Literal("fork");
        Argument<String> forkName = ArgumentType.Word("newName");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            offClock(() -> {
                shards.fork(g, n, ShardBridge::copyEntity); // diff + sky + entities (mechanics stamps + archipelago's default cloner)
                sender.sendMessage("[shard] forked '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
            });
        }, fork, name, forkName);

        // layers: relative diffs on the parent's clock; /shard join between layers = the combat-continuous switch
        var child = ArgumentType.Literal("child");
        shard.addSyntax((sender, ctx) -> {
            Shard parent = shards.byName(ctx.get(name));
            if (parent == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            shards.child(parent, n);
            sender.sendMessage("[shard] layer '" + n + "' over '" + ctx.get(name) + "' (same clock) - /shard join " + n + " switches layers");
        }, child, name, forkName);

        var flatten = ArgumentType.Literal("flatten");
        Argument<String> bottomName = ArgumentType.Word("bottomName");
        shard.addSyntax((sender, ctx) -> {
            Shard top = shards.byName(ctx.get(name));
            Shard bottom = shards.byName(ctx.get(bottomName));
            if (top == null || bottom == null) { sender.sendMessage("[shard] both shards must exist"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            offClock(() -> {
                shards.flatten(top, bottom, n, ShardBridge::copyEntity);
                sender.sendMessage("[shard] '" + ctx.get(name) + "' onto '" + ctx.get(bottomName) + "' -> '" + n + "' (top wins) - /shard join " + n);
            });
        }, flatten, name, bottomName, forkName);

        // chain flatten: the whole layer chain collapsed into one independent shard (branches never merge)
        shard.addSyntax((sender, ctx) -> {
            Shard top = shards.byName(ctx.get(name));
            if (top == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            offClock(() -> {
                shards.flatten(top, n, ShardBridge::copyEntity);
                sender.sendMessage("[shard] chain of '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
            });
        }, flatten, name, forkName);

        // save/load = the .shard round-trip: blocks (NBT included) + sky natively, entities via the DataKey
        // recipe; "blocks" mode = map template, no entity payload. Dirs resolve through Storage
        Path saveDir = Storage.dir("save-dir", "shard-saves");
        var save = ArgumentType.Literal("save");
        Argument<String> saveMode = ArgumentType.Word("mode").from("blocks");
        CommandExecutor doSave = (sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            boolean entities = !ctx.has(saveMode);
            offClock(() -> {
                try {
                    Files.createDirectories(saveDir);
                    Path file = saveDir.resolve(ctx.get(name) + ".shard");
                    ShardFile.save(g, file, entities ? w -> w.put(SAVED_ENTITIES, EntityDescriptors.write(g)) : null);
                    sender.sendMessage("[shard] saved '" + ctx.get(name) + "' -> " + file + " (" + Files.size(file)
                            + " bytes, diff " + g.overlaySize() + (entities ? ")" : ", blocks only)"));
                } catch (IOException e) {
                    sender.sendMessage("[shard] save failed: " + e.getMessage());
                }
            });
        };
        shard.addSyntax(doSave, save, name);
        shard.addSyntax(doSave, save, name, saveMode);

        // one file = the whole layer tree; loadtree rebuilds it, re-registered under the saved NAME hints
        var savetree = ArgumentType.Literal("savetree");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            offClock(() -> {
                try {
                    Files.createDirectories(saveDir);
                    Path file = saveDir.resolve(ctx.get(name) + ".shard");
                    Files.write(file, ShardFile.saveTree(g, shards::nameOf,
                            (s, w) -> w.put(SAVED_ENTITIES, EntityDescriptors.write(s))));
                    sender.sendMessage("[shard] saved the tree of '" + ctx.get(name) + "' -> " + file
                            + " (" + Files.size(file) + " bytes)");
                } catch (IOException e) {
                    sender.sendMessage("[shard] savetree failed: " + e.getMessage());
                }
            });
        }, savetree, name);

        var loadtree = ArgumentType.Literal("loadtree");
        shard.addSyntax((sender, ctx) -> {
            Path file = saveDir.resolve(ctx.get(name) + ".shard");
            if (!Files.exists(file)) { sender.sendMessage("[shard] no save named '" + ctx.get(name) + "'"); return; }
            String prefix = ctx.get(forkName);
            offClock(() -> {
                try {
                    List<ShardFile.TreeLayer> layers = ShardFile.loadTree(world, Files.readAllBytes(file));
                    ExecutionDomain domain = ExecutionDomain.create(); // one tree, one clock
                    StringBuilder named = new StringBuilder();
                    for (int i = 0; i < layers.size(); i++) {
                        ShardFile.TreeLayer layer = layers.get(i);
                        String saved = layer.data().get(ShardFile.NAME);
                        String n = prefix + "-" + (saved != null ? saved : i);
                        shards.register(layer.shard(), n);
                        layer.shard().domain(domain);
                        if (layer.data().get(SAVED_ENTITIES) instanceof ListBinaryTag descs) {
                            EntityDescriptors.read(layer.shard(), descs);
                        }
                        named.append(named.isEmpty() ? "" : ", ").append(n);
                    }
                    sender.sendMessage("[shard] tree loaded: " + named + " - /shard join " + prefix + "-...");
                } catch (Exception e) {
                    sender.sendMessage("[shard] loadtree failed: " + e.getMessage());
                }
            });
        }, loadtree, name, forkName);

        var load = ArgumentType.Literal("load");
        shard.addSyntax((sender, ctx) -> {
            Path file = saveDir.resolve(ctx.get(name) + ".shard");
            if (!Files.exists(file)) { sender.sendMessage("[shard] no save named '" + ctx.get(name) + "'"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            offClock(() -> {
                Shard g = shards.create(world, n, ExecutionDomain.create());
                try {
                    ShardFile.UserDataView view = ShardFile.load(g, Files.readAllBytes(file));
                    if (view.get(SAVED_ENTITIES) instanceof ListBinaryTag descs) EntityDescriptors.read(g, descs);
                    sender.sendMessage("[shard] loaded '" + ctx.get(name) + "' -> '" + n + "' (diff " + g.overlaySize()
                            + ") - /shard join " + n);
                } catch (Exception e) {
                    shards.destroy(g);
                    sender.sendMessage("[shard] load failed: " + e.getMessage());
                }
            });
        }, load, name, forkName);

        // the exchange adapter: pure byte transcode .shard <-> .polar for polar tooling, no live shard involved
        var export = ArgumentType.Literal("export");
        shard.addSyntax((sender, ctx) -> {
            try {
                Path in = saveDir.resolve(ctx.get(name) + ".shard");
                Path out = saveDir.resolve(ctx.get(name) + ".polar");
                byte[] source = Files.readAllBytes(in);
                Files.write(out, PolarShardCodec.fromShardFile(source));
                sender.sendMessage("[shard] " + in.getFileName() + " -> " + out.getFileName() + " (" + Files.size(out)
                        + " bytes, " + (PolarShardCodec.transcodesLosslessly(source) ? "verified lossless" : "LOSSY") + ")");
            } catch (IOException e) {
                sender.sendMessage("[shard] export failed: " + e.getMessage());
            }
        }, export, name);
        var doImport = ArgumentType.Literal("import");
        shard.addSyntax((sender, ctx) -> {
            try {
                Path in = saveDir.resolve(ctx.get(name) + ".polar");
                Path out = saveDir.resolve(ctx.get(name) + ".shard");
                byte[] source = Files.readAllBytes(in);
                Files.write(out, PolarShardCodec.toShardFile(source));
                sender.sendMessage("[shard] " + in.getFileName() + " -> " + out.getFileName() + " (" + Files.size(out)
                        + " bytes, " + (PolarShardCodec.transcodesLosslessly(source) ? "verified lossless"
                        : "LOSSY - polar-only data (biomes/light/chunk extras) dropped") + ")");
            } catch (IOException e) {
                sender.sendMessage("[shard] import failed: " + e.getMessage());
            }
        }, doImport, name);

        // the winner's build lands on the real map (base writes belong on the server thread)
        var commit = ArgumentType.Literal("commit");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                g.commitToBase();
                sender.sendMessage("[shard] '" + ctx.get(name) + "' committed to the base map (diff now "
                        + g.overlaySize() + ")");
            });
        }, commit, name);

        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (WorldAccess.of(p) instanceof Shard g) {
                g.leave(p);
                p.sendMessage("[shard] left - back on the main map");
            } else p.sendMessage("[shard] not in a shard");
        }, leave);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            offClock(() -> {
                g.reset();
                sender.sendMessage("[shard] '" + ctx.get(name) + "' diff dropped");
            });
        }, reset, name);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            shards.destroy(g);
            sender.sendMessage("[shard] '" + ctx.get(name) + "' destroyed");
        }, destroy, name);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> sender.sendMessage(
                "- " + label(shards, g) + " members=" + g.members().size()
                        + " observers=" + g.observers().size() + " diff=" + g.overlaySize())), list);

        // chain-effective light as per-viewer UpdateLight overlays (modern clients; 1.8 self-relights).
        // 'disable' = NO system (client-local relight is all you see); 'off' = a demo system that FORCES
        // darkness (the visible proof of server light authority); 'custom' = a stub narrating its hooks
        var relight = ArgumentType.Literal("relight");
        Argument<String> relightState = ArgumentType.Word("state").from("on", "off", "disable", "custom");
        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
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

        var tree = ArgumentType.Literal("tree");
        shard.addSyntax((sender, ctx) -> {
            var all = shards.shards();
            if (all.isEmpty()) { sender.sendMessage("[shard] none"); return; }
            for (Shard s : all) {
                if (s.parent() == null || !all.contains(s.parent())) printTree(sender, shards, s, 0);
            }
        }, tree);

        // the truly-multithreaded demo kit: stall one shard, keep playing in another; watch /shard tps
        var stall = ArgumentType.Literal("stall");
        var spin = ArgumentType.Literal("spin");
        var tps = ArgumentType.Literal("tps");
        Argument<Integer> millis = ArgumentType.Integer("ms");

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null || g.domain() == null) { sender.sendMessage("[shard] no domain shard named '" + ctx.get(name) + "'"); return; }
            int ms = ctx.get(millis);
            g.domain().execute(() -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) { // the watchdog freeing a "hung" demo stall
                }
            });
            sender.sendMessage("[shard] '" + ctx.get(name) + "' stalls " + ms + "ms (blocking) - siblings play on; >5s the watchdog interrupts");
        }, stall, name, millis);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null || g.domain() == null) { sender.sendMessage("[shard] no domain shard named '" + ctx.get(name) + "'"); return; }
            int ms = ctx.get(millis);
            g.domain().execute(() -> {
                long end = System.nanoTime() + ms * 1_000_000L;
                while (System.nanoTime() < end) Thread.onSpinWait(); // uninterruptible: captures a carrier
            });
            sender.sendMessage("[shard] '" + ctx.get(name) + "' spins " + ms + "ms (CPU) - >15s it FAILS: crash report + shard destroyed, members back to the base map");
        }, spin, name, millis);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> {
            var d = g.domain();
            if (d == null) {
                sender.sendMessage("- " + label(shards, g) + " server-ticked, members=" + g.members().size());
                return;
            }
            var m = d.metrics();
            sender.sendMessage("- " + label(shards, g) + " ticks=" + m.ticks() + " lag=" + m.lag()
                    + " mspt=" + String.format("%.2f", m.msptEwma())
                    + " on " + m.threadName() + " members=" + g.members().size());
        }), tps);

        // views compose: of(g) = the game only; add withBaseEntities/withEntities/withAllEntities for more layers
        Argument<String> spectateMode = ArgumentType.Word("mode").from("game", "+main");
        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String n = ctx.get(name);
            if (n.equals("off")) { ShardView.set(p, null); p.sendMessage("[shard] view reset"); return; }
            Shard g = shards.byName(n);
            if (g == null) { p.sendMessage("[shard] no shard named '" + n + "'"); return; }
            ShardView.set(p, ShardView.of(g));
            p.sendMessage("[shard] spectating '" + n + "' - watching, not playing (/shard spectate off)");
        }, spectate, name);
        shard.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { p.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            ShardView view = ShardView.of(g);
            if (ctx.get(spectateMode).equals("+main")) view = view.withBaseEntities(true);
            ShardView.set(p, view);
            p.sendMessage("[shard] spectating '" + ctx.get(name) + "' [" + ctx.get(spectateMode) + "]");
        }, spectate, name, spectateMode);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            switch (ctx.get(skyValue)) {
                case "day" -> g.setTime(1000);
                case "night" -> g.setTime(18000);
                case "rain" -> g.setWeather(new Weather(1f, 0f));
                case "clear" -> g.setWeather(Weather.CLEAR);
            }
            sender.sendMessage("[shard] '" + ctx.get(name) + "' sky -> " + ctx.get(skyValue));
        }, sky, name, skyValue);

        MinecraftServer.getCommandManager().register(shard);
        MinecraftServer.getCommandManager().register(new Bench(world).command());

        Command boom = new Command("boom");
        boom.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            // resolves the SHOOTER's world: a shard blast only rocks that game, a main-map one never reaches it
            explosions.explode(MechanicsWorld.of(p), p.getPosition(), 4.0f, p, null);
        });
        MinecraftServer.getCommandManager().register(boom);

        Command gm = new Command("gm");
        Argument<GameMode> mode = ArgumentType.Enum("mode", GameMode.class);
        gm.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) p.setGameMode(ctx.get(mode));
        }, mode);
        MinecraftServer.getCommandManager().register(gm);

        // replay demo over :archipelago-replay - record a shard, play it back into a fresh one
        Path replayDir = Storage.dir("replay-dir", "replays");
        Command replay = new Command("replay");
        Argument<String> replayName = ArgumentType.Word("replayName");
        Argument<Long> seekTick = ArgumentType.Long("tick");
        replay.setDefaultExecutor((sender, ctx) ->
                sender.sendMessage("usage: /replay record <shard> [solo] | stop <name> | play <name> <newShard> [visual]"
                        + " | seek <tick> | reverse | forward | pause | resume | rate <r> | rewind <ticks>"
                        + " | commit | state <actor> | bookmark <name> | bookmarks | goto <name> | halt"));
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            playback.reverse(true);
            sender.sendMessage("[replay] rewinding from tick " + playback.tick());
        }, ArgumentType.Literal("reverse"));
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            playback.reverse(false);
            sender.sendMessage("[replay] playing forward from tick " + playback.tick());
        }, ArgumentType.Literal("forward"));
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            playback.pause(true);
            sender.sendMessage("[replay] paused at tick " + playback.tick());
        }, ArgumentType.Literal("pause"));
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            playback.pause(false);
            sender.sendMessage("[replay] resumed at tick " + playback.tick());
        }, ArgumentType.Literal("resume"));
        replay.addSyntax((sender, ctx) -> {
            if (recording != null) { sender.sendMessage("[replay] already recording - /replay stop <name> first"); return; }
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            offClock(() -> {
                recording = Replay.record(g); // the t0 snapshot relays
                sender.sendMessage("[replay] recording '" + ctx.get(name) + "' - build/move, then /replay stop <name>");
            });
        }, ArgumentType.Literal("record"), name);
        // solo = the layer filter demo: just this shard, none of its child layers
        replay.addSyntax((sender, ctx) -> {
            if (recording != null) { sender.sendMessage("[replay] already recording - /replay stop <name> first"); return; }
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            offClock(() -> {
                recording = Replay.record(g, layer -> false);
                sender.sendMessage("[replay] recording '" + ctx.get(name) + "' SOLO (child layers excluded)");
            });
        }, ArgumentType.Literal("record"), name, ArgumentType.Literal("solo"));
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { sender.sendMessage("[replay] nothing is recording"); return; }
            Recording rec = recording;
            recording = null;
            byte[] file = rec.stop();
            try {
                Files.createDirectories(replayDir);
                Files.write(replayDir.resolve(ctx.get(replayName) + ".shard"), file);
            } catch (IOException e) {
                sender.sendMessage("[replay] write failed: " + e.getMessage());
                return;
            }
            sender.sendMessage("[replay] '" + ctx.get(replayName) + "' saved: " + rec.tick() + " ticks, "
                    + rec.counts() + " (" + file.length + " bytes)");
        }, ArgumentType.Literal("stop"), replayName);
        // "visual" = stand-ins only, ignoring registered revivers - the mechanics-less look, for comparison
        Argument<String> playMode = ArgumentType.Word("playMode").from("visual");
        CommandExecutor doPlay = (sender, ctx) -> {
            if (playback != null) { sender.sendMessage("[replay] one playback at a time (tick " + playback.tick() + ")"); return; }
            byte[] file;
            try {
                file = Files.readAllBytes(replayDir.resolve(ctx.get(replayName) + ".shard"));
            } catch (IOException e) {
                sender.sendMessage("[replay] no replay named '" + ctx.get(replayName) + "'");
                return;
            }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            SpawnMode spawns = ctx.has(playMode) ? SpawnMode.VISUAL : SpawnMode.REVIVED;
            offClock(() -> {
                Shard target = shards.create(world, n, ExecutionDomain.create());
                try {
                    playback = Replay.play(target, file, spawns);
                } catch (IllegalArgumentException e) {
                    shards.destroy(target);
                    sender.sendMessage("[replay] " + e.getMessage());
                    return;
                }
                String skipped = playback.skipped().isEmpty() ? "" : " (skipped channels: " + playback.skipped() + ")";
                sender.sendMessage("[replay] playing '" + ctx.get(replayName) + "' [" + spawns + "]: " + playback.length()
                        + " ticks, " + playback.counts() + skipped + " - /shard spectate or join the shard to watch");
            });
        };
        replay.addSyntax(doPlay, ArgumentType.Literal("play"), replayName, forkName);
        replay.addSyntax(doPlay, ArgumentType.Literal("play"), replayName, forkName, playMode);
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            long target = ctx.get(seekTick);
            offClock(() -> {
                try {
                    playback.seek(target); // relays onto the theater's clock
                    sender.sendMessage("[replay] at tick " + playback.tick() + "/" + playback.length());
                } catch (RuntimeException e) {
                    sender.sendMessage("[replay] " + e.getMessage());
                }
            });
        }, ArgumentType.Literal("seek"), seekTick);
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            playback.halt(); // the stop event clears the field
            sender.sendMessage("[replay] halted");
        }, ArgumentType.Literal("halt"));
        // the time-rewind recipe: stop the running recording, raise a theater CHILD of the live shard,
        // play the recording IN MEMORY (no file), jump back; /replay commit lands the past for real
        Argument<Long> backTicks = ArgumentType.Long("ticks");
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { sender.sendMessage("[replay] nothing is recording - /replay record <shard> first"); return; }
            if (playback != null) { sender.sendMessage("[replay] one playback at a time"); return; }
            Recording rec = recording;
            recording = null;
            rec.stop();
            long back = ctx.get(backTicks);
            offClock(() -> {
                Shard live = rec.shard();
                String n = shards.nameOf(live) + "-rewind";
                if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
                Shard theater = new Shard(live);
                shards.register(theater, n);
                playback = Replay.play(theater, rec);
                playback.pause(true); // hold the rewound moment
                playback.seek(Math.min(rec.tick(), Math.max(0, rec.tick() - back)));
                sender.sendMessage("[replay] '" + n + "' shows " + back + " ticks ago (tick " + playback.tick()
                        + "/" + playback.length() + ") - /shard spectate " + n
                        + ", then /replay commit or /replay halt");
            });
        }, ArgumentType.Literal("rewind"), backTicks);
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            Shard theater = playback.shard();
            Shard live = theater.parent();
            if (live == null) { sender.sendMessage("[replay] not a rewind theater (no parent to land on)"); return; }
            offClock(() -> {
                playback.halt(); // ghosts and twins clear; the rewound blocks stay
                live.setBlocks(theater.diff());
                shards.destroy(theater);
                sender.sendMessage("[replay] the past landed on '" + shards.nameOf(live) + "'");
            });
        }, ArgumentType.Literal("commit"));
        // arg id must differ from the literal's ("rate"): same-id nodes collide in the command tree
        Argument<Double> playRate = ArgumentType.Double("speed");
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            try {
                playback.rate(ctx.get(playRate));
                sender.sendMessage("[replay] rate " + playback.rate() + "x"
                        + (playback.reversed() ? " (reversed)" : ""));
            } catch (IllegalArgumentException e) {
                sender.sendMessage("[replay] " + e.getMessage());
            }
        }, ArgumentType.Literal("rate"), playRate);
        Argument<String> markName = ArgumentType.Word("markName");
        replay.addSyntax((sender, ctx) -> {
            if (recording == null) { sender.sendMessage("[replay] nothing is recording"); return; }
            recording.bookmark(ctx.get(markName));
            sender.sendMessage("[replay] bookmarked '" + ctx.get(markName) + "' @ tick " + recording.tick());
        }, ArgumentType.Literal("bookmark"), markName);
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            if (playback.bookmarks().isEmpty()) { sender.sendMessage("[replay] no bookmarks"); return; }
            playback.bookmarks().forEach(b -> sender.sendMessage("[replay] '" + b.name() + "' @ tick " + b.tick()));
        }, ArgumentType.Literal("bookmarks"));
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            var mark = playback.bookmarks().stream().filter(b -> b.name().equals(ctx.get(markName))).findFirst();
            if (mark.isEmpty()) { sender.sendMessage("[replay] no bookmark '" + ctx.get(markName) + "'"); return; }
            offClock(() -> {
                playback.seek(mark.get().tick());
                sender.sendMessage("[replay] at '" + mark.get().name() + "' (tick " + playback.tick() + ")");
            });
        }, ArgumentType.Literal("goto"), markName);
        // the state channels' read seam: the folded player state at the playback's current tick
        Argument<String> actorName = ArgumentType.Word("actor");
        replay.addSyntax((sender, ctx) -> {
            if (playback == null) { sender.sendMessage("[replay] nothing is playing"); return; }
            String actor = ctx.get(actorName);
            var inv = StateChannels.inventory(playback, actor);
            sender.sendMessage("[replay] " + actor + " @ tick " + playback.tick()
                    + ": health=" + StateChannels.health(playback, actor)
                    + " hunger=" + StateChannels.hunger(playback, actor)
                    + " xp=" + StateChannels.xp(playback, actor)
                    + " inventory=" + inv.size() + " known slots");
        }, ArgumentType.Literal("state"), actorName);
        MinecraftServer.getGlobalEventHandler().addListener(PlaybackStopEvent.class, e -> {
            if (e.playback() == playback) {
                e.playback().shard().sendMessage(Component.text("[replay] finished"));
                playback = null;
            }
        });
        MinecraftServer.getCommandManager().register(replay);
    }

    // a domain player's commands run on THEIR shard's clock; ops that relay into another live shard
    // (snapshots: fork/flatten/save/load/reset, replay record/play/seek) must hop off it first
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
