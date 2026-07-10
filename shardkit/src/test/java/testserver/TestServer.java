package testserver;

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
import io.github.term4.minestommechanics.mechanics.vanilla18.Vanilla18;
import io.github.term4.minestommechanics.platform.compatibility.Compat18;
import io.github.term4.minestommechanics.platform.fixes.Fixes18;
import io.github.term4.minestommechanics.platform.fixes.FixesSystem;
import io.github.term4.minestommechanics.platform.fixes.world.FluidCountChunk;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.vri.Vri;
import io.github.term4.minestommechanics.vri.VriConfig;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.ShardManager;
import io.github.term4.shardkit.ShardView;
import io.github.term4.shardkit.polar.PolarShardCodec;
import io.github.term4.worldaccess.WorldAccess;
import net.minestom.server.MinecraftServer;
import net.minestom.server.Auth;
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

/**
 * ShardKit playground WITH MinestomMechanics (from mavenLocal) glued in via {@link ShardBridge} - the reference
 * for using both libraries. {@code /shard create|join} a game, build/dig, {@code /boom} for shard-scoped 1.8
 * explosions + knockback, spectate from a second client.
 */
public final class TestServer {

    private static ExplosionSystem explosions;

    public static void main(String[] args) {
        // the mm ExampleServer's platform flags, verbatim
        System.setProperty("minestom.new-socket-write-lock", "true");
        System.setProperty("minestom.enforce-entity-interaction-range", "false"); // mechanics handles reach
        System.setProperty("minestom.chunk-view-distance", "12"); // <12 derenders players ~150 blocks from spawn on legacy
        System.setProperty("minestom.tps", "20");

        MinecraftServer server = MinecraftServer.init(new Auth.Bungee()); // bungee auth lets 1.7/Via-proxy clients join

        // vanilla 1.8 mechanics + the full compat/fixes layer over shards: the bridge below is the whole integration
        MinestomMechanics mm = MinestomMechanics.getInstance();
        mm.viaProxyDetails = true;
        mm.init();
        mm.profiles().setGlobal(Vanilla18.profile().toBuilder()
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
        // save/load glue for mechanics entities: the codec reads shardkit's ENTITY_SAVE natively; mechanics stamps
        // its own mirror, so point the save side at it and bind the load-side reviver
        PolarShardCodec.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        PolarShardCodec.reviveEntity("mm:item", DroppedItemEntity::fromSave);
        PolarShardCodec.reviveEntity("mm:projectile", projectiles::fromSave);

        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setExplosionSupplier(explosions.supplier()); // route instance.explode(...) through the system
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        world.setChunkSupplier(FluidCountChunk::new); // legacy fluid-count fix (modern clients walking through water)

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
            p.sendMessage("/shard create <name> | join <name> | fork <name> <new> | save <name> | load <saved> <new> | leave | reset <name> | destroy <name> | list | spectate <name|off> | sky <name> <day|night|rain|clear>  -  /gm <mode>");
        });

        events.addChild(Shard.blockRouting()); // members build into their shard's overlay, never the map

        ShardManager shards = new ShardManager();
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
                sender.sendMessage("usage: /shard create <name> | join <name> | fork <name> <new> | save <name> | load <saved> <new> | leave | reset <name> | destroy <name> | list | spectate <name|off> | sky <name> <day|night|rain|clear>"));

        shard.addSyntax((sender, ctx) -> {
            String n = ctx.get(name);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            shards.create(world, n);
            sender.sendMessage("[shard] created '" + n + "' - /shard join " + n);
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
            shards.fork(g, n, ShardBridge::copyEntity); // diff + sky + entities (mechanics stamps + shardkit's default cloner)
            sender.sendMessage("[shard] forked '" + ctx.get(name) + "' -> '" + n + "' - /shard join " + n);
        }, fork, name, forkName);

        // save/load = the polar codec round-trip: blocks (NBT included) + sky + stamped/generic entities;
        // "blocks" mode = map template, no entity payload
        Path saveDir = Path.of("shard-saves");
        var save = ArgumentType.Literal("save");
        Argument<String> saveMode = ArgumentType.Word("mode").from("blocks");
        CommandExecutor doSave = (sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            boolean entities = !ctx.has(saveMode);
            try {
                Files.createDirectories(saveDir);
                Path file = saveDir.resolve(ctx.get(name) + ".polar");
                PolarShardCodec.save(g, file, null, entities);
                sender.sendMessage("[shard] saved '" + ctx.get(name) + "' -> " + file + " (" + Files.size(file)
                        + " bytes, diff " + g.overlaySize() + (entities ? ")" : ", blocks only)"));
            } catch (IOException e) {
                sender.sendMessage("[shard] save failed: " + e.getMessage());
            }
        };
        shard.addSyntax(doSave, save, name);
        shard.addSyntax(doSave, save, name, saveMode);

        var load = ArgumentType.Literal("load");
        shard.addSyntax((sender, ctx) -> {
            Path file = saveDir.resolve(ctx.get(name) + ".polar");
            if (!Files.exists(file)) { sender.sendMessage("[shard] no save named '" + ctx.get(name) + "'"); return; }
            String n = ctx.get(forkName);
            if (shards.byName(n) != null) { sender.sendMessage("[shard] '" + n + "' already exists"); return; }
            Shard g = shards.create(world, n);
            try {
                PolarShardCodec.load(g, file);
                sender.sendMessage("[shard] loaded '" + ctx.get(name) + "' -> '" + n + "' (diff " + g.overlaySize()
                        + ") - /shard join " + n);
            } catch (Exception e) {
                shards.destroy(g);
                sender.sendMessage("[shard] load failed: " + e.getMessage());
            }
        }, load, name, forkName);

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
            g.reset();
            sender.sendMessage("[shard] '" + ctx.get(name) + "' diff dropped");
        }, reset, name);

        shard.addSyntax((sender, ctx) -> {
            Shard g = shards.byName(ctx.get(name));
            if (g == null) { sender.sendMessage("[shard] no shard named '" + ctx.get(name) + "'"); return; }
            shards.destroy(g);
            sender.sendMessage("[shard] '" + ctx.get(name) + "' destroyed");
        }, destroy, name);

        shard.addSyntax((sender, ctx) -> shards.shards().forEach(g -> sender.sendMessage(
                "- members=" + g.members().size() + " observers=" + g.observers().size() + " diff=" + g.overlaySize())), list);

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
    }
}
