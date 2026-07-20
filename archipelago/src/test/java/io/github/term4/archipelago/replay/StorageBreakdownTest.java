package io.github.term4.archipelago.replay;

import com.github.luben.zstd.Zstd;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.replay.core.CoreChannels;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Size-breakdown harness for the storage-efficiency epic: per-channel raw op bytes, user-data totals, and file
 * sizes pre/post zstd - over a synthetic session and any real replay files in {@code ../replays} (plus
 * {@code -Dbreakdown.files=a,b}). Candidate encodings (binary interleaved/columnar, quantized columnar,
 * item/blob palettes) are measured here as byte streams only; nothing changes the shipped format.
 */
class StorageBreakdownTest {

    private static InstanceContainer instance;

    @BeforeAll
    static void boot() {
        if (instance != null) return;
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
            MinecraftServer.process().dispatcher().start();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
        CoreChannels.register();
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void syntheticCombatSession() throws IOException {
        byte[] file = syntheticSession(4, 2400);
        report("synthetic: 4 actors x 2400 ticks (combat + build + projectiles)", file);
    }

    @Test
    void realReplayFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Path replays = Path.of("..", "replays");
        if (Files.isDirectory(replays)) {
            try (Stream<Path> s = Files.list(replays)) {
                s.filter(p -> p.getFileName().toString().endsWith(".polar")
                        || p.getFileName().toString().endsWith(".shard")).forEach(files::add);
            }
        }
        for (String extra : System.getProperty("breakdown.files", "").split(",")) {
            if (!extra.isBlank()) files.add(Path.of(extra.trim()));
        }
        Assumptions.assumeFalse(files.isEmpty(), "no real replay files found");
        for (Path p : files) report(p + " (real recording)", Files.readAllBytes(p));
    }
    // plausible mixed workload (every-tick moves, delta-gated projectile tracks, kits, building) so per-op costs match real files
    private static byte[] syntheticSession(int actorCount, int ticks) {
        Shard stage = new Shard(instance);
        Recording rec = Replay.record(stage);
        Random rng = new Random(42);

        String[] names = {"Alice", "Bob8", "Charlie_99", "Dave", "Eve_", "Frank22", "Grace", "Heidi"};
        double[] x = new double[actorCount], y = new double[actorCount], z = new double[actorCount];
        float[] yaw = new float[actorCount], pitch = new float[actorCount];
        for (int a = 0; a < actorCount; a++) {
            x[a] = rng.nextDouble() * 60 - 30;
            y[a] = 64;
            z[a] = rng.nextDouble() * 60 - 30;
        }

        CompoundBinaryTag snowVis = Visuals.snapshot(new Entity(EntityType.SNOWBALL));
        CompoundBinaryTag arrowVis = Visuals.snapshot(new Entity(EntityType.ARROW));
        CompoundBinaryTag bobberVis = Visuals.snapshot(new Entity(EntityType.FISHING_BOBBER));

        final class Flight {
            int seq, left;
            double fx, fy, fz, vx, vy, vz;
            boolean bobber;
        }
        List<Flight> flights = new ArrayList<>();
        int nextSeq = 0;

        for (int t = 1; t <= ticks; t++) {
            tick();
            boolean idle = t > ticks * 0.9; // everyone stands around at the end - the repeated-op case
            for (int a = 0; a < actorCount; a++) {
                String name = names[a];
                if (t == 1) {
                    rec.emit(Channels.SKINS, new Channels.Skin(name, base64(rng, 400), base64(rng, 684)));
                    rec.emit(Channels.EQUIPS, new Channels.Equip(name, kit(a % 3)));
                }
                if (t == 800 || t == 1600) rec.emit(Channels.EQUIPS, new Channels.Equip(name, kit((a + t / 800) % 3)));
                if (!idle) {
                    x[a] += (rng.nextDouble() - 0.5) * 0.4;
                    z[a] += (rng.nextDouble() - 0.5) * 0.4;
                    y[a] = 64 + (rng.nextDouble() < 0.05 ? 1.2 * rng.nextDouble() : 0);
                    yaw[a] = wrap(yaw[a] + (rng.nextFloat() - 0.5f) * 25f);
                    pitch[a] = Math.clamp(pitch[a] + (rng.nextFloat() - 0.5f) * 10f, -90f, 90f);
                }
                rec.emit(Channels.MOVES, new Channels.Move(name, x[a], y[a], z[a], yaw[a], pitch[a], y[a] == 64));
                if (!idle && rng.nextDouble() < 0.08) rec.emit(Channels.ACTS, new Channels.Act(name, Channels.SWING_MAIN, 0));
                if (!idle && rng.nextDouble() < 0.02) rec.emit(Channels.ACTS, new Channels.Act(name, Channels.HURT, 0));
                if (rng.nextDouble() < 0.005) {
                    rec.emit(Channels.ACTS, new Channels.Act(name, rng.nextBoolean() ? Channels.SNEAK_ON : Channels.SNEAK_OFF, 0));
                }
                if (!idle && rng.nextDouble() < 1.0 / 45) { // a throw/shot/cast
                    Flight f = new Flight();
                    f.seq = nextSeq++;
                    f.bobber = rng.nextDouble() < 0.25;
                    f.left = f.bobber ? 30 + rng.nextInt(60) : 15 + rng.nextInt(15);
                    f.fx = x[a]; f.fy = y[a] + 1.6; f.fz = z[a];
                    double speed = f.bobber ? 0.7 : 1.5;
                    f.vx = Math.sin(yaw[a]) * speed;
                    f.vz = Math.cos(yaw[a]) * speed;
                    f.vy = 0.3;
                    CompoundBinaryTag vis = f.bobber ? bobberVis : (rng.nextBoolean() ? snowVis : arrowVis);
                    rec.emit(Channels.SPAWNS, new Channels.Spawn(0, f.fx, f.fy, f.fz, yaw[a], pitch[a], f.seq,
                            vis, f.bobber ? name : ""));
                    flights.add(f);
                }
            }
            for (var it = flights.iterator(); it.hasNext(); ) {
                Flight f = it.next();
                if (f.bobber && f.left < 30) { // floating: tiny bobbing deltas
                    f.fy += 0.03 * Math.sin(f.left * 0.35);
                } else {
                    f.fx += f.vx; f.fy += f.vy; f.fz += f.vz;
                    f.vy -= 0.03;
                    f.vx *= 0.99; f.vy *= 0.99; f.vz *= 0.99;
                }
                rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(f.seq, f.fx, f.fy, f.fz, 0, 0));
                if (--f.left <= 0) {
                    rec.emit(Channels.REMOVES, new Channels.Remove(f.seq, ""));
                    it.remove();
                }
            }
            if (t >= 200 && t < 1000 && t % 4 == 0) { // building
                String block = switch (t % 3) {
                    case 0 -> "minecraft:cobblestone";
                    case 1 -> "minecraft:oak_planks";
                    default -> "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
                };
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, (t / 4) % 40 - 20, 64 + t / 160, (t / 160) % 40 - 20, block));
            }
            if (t >= 1400 && t < 1700 && t % 10 == 0) { // tearing some of it down
                int bx = (t / 4) % 40 - 20, by = 64 + (t - 1200) / 160, bz = ((t - 1200) / 160) % 40 - 20;
                rec.emit(Channels.DIGS, new Channels.Dig(0, bx, by, bz, names[t % actorCount], true));
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, bx, by, bz, "minecraft:air"));
            }
        }
        return rec.stop();
    }

    private static float wrap(float yaw) {
        return yaw > 180 ? yaw - 360 : yaw < -180 ? yaw + 360 : yaw;
    }

    private static String base64(Random rng, int chars) {
        byte[] raw = new byte[chars * 3 / 4];
        rng.nextBytes(raw);
        return Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    private static CompoundBinaryTag kit(int variant) {
        Material[][] kits = {
                {Material.DIAMOND_SWORD, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS},
                {Material.IRON_SWORD, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS},
                {Material.BOW, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS},
        };
        String[] slots = {"MAIN_HAND", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        for (int i = 0; i < slots.length; i++) {
            out.put(slots[i], ItemStack.CODEC.encode(Transcoder.NBT, ItemStack.of(kits[variant][i])).orElseThrow());
        }
        out.put("OFF_HAND", ItemStack.CODEC.encode(Transcoder.NBT, ItemStack.of(Material.SNOWBALL, 16)).orElseThrow());
        return out.build();
    }

    private static void report(String label, byte[] file) throws IOException {
        boolean shard = file.length > 1 && (file[0] & 0xFF) == 0x1F && (file[1] & 0xFF) == 0x8B; // gzip magic
        PolarWorld world = null;
        CompoundBinaryTag root;
        StringBuilder out = new StringBuilder();
        out.append("\n=== storage breakdown: ").append(label).append(" ===\n");
        if (shard) {
            root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(file), BinaryTagIO.Compression.GZIP);
            out.append("file (gzip shard):     %,9d B  (root nbt %,d B uncompressed)%n"
                    .formatted(file.length, nbt(root).length));
        } else {
            world = PolarReader.read(file);
            byte[] userData = world.userData();
            byte[] frameNone = PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(),
                    PolarWorld.CompressionType.NONE, world.minSection(), world.maxSection(), userData,
                    List.copyOf(world.chunks())));
            root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(userData));
            out.append("file (zstd polar):    %,9d B%n".formatted(file.length));
            out.append("frame uncompressed:   %,9d B  (userData %,d B; chunks+frame %,d B)%n"
                    .formatted(frameNone.length, userData.length, frameNone.length - userData.length));
        }
        CompoundBinaryTag container = root.getCompound("user").getCompound(Replay.OPS.id());
        long ticks = container.getLong("ticks");
        CompoundBinaryTag channelsTag = container.getCompound("channels");

        out.append("ops container (nbt):  %,9d B, ticks=%d%n%n".formatted(nbt(container).length, ticks));

        out.append(String.format("%-14s %6s | %9s %6s %8s | %8s %8s %8s | %8s %8s%n",
                "channel", "ops", "nbt B", "B/op", "z(nbt)", "bin B", "z(binI)", "z(binC)", "binQ B", "z(binQ)"));

        long totOps = 0, totNbt = 0, totZNbt = 0, totBin = 0, totZBinI = 0, totZBinC = 0, totBinQ = 0, totZBinQ = 0;
        Map<String, byte[]> binI = new LinkedHashMap<>(), binC = new LinkedHashMap<>(), binQ = new LinkedHashMap<>();
        List<String> paletteNotes = new ArrayList<>();

        for (String id : channelsTag.keySet()) {
            CompoundBinaryTag channel = channelsTag.getCompound(id);
            int version = channel.getInt("v");
            ListBinaryTag opsList = channel.getList("ops", BinaryTagTypes.COMPOUND);
            byte[] nbtBytes = nbt(channel);
            totOps += opsList.size();
            totNbt += nbtBytes.length;
            totZNbt += z(nbtBytes);

            Enc enc = prototype(id, version, opsList, paletteNotes);
            if (enc == null) {
                out.append(String.format("%-14s %6d | %,9d %6.1f %,8d | %8s%n", id + " v" + version,
                        opsList.size(), nbtBytes.length, nbtBytes.length / (double) opsList.size(), z(nbtBytes), "(no codec here)"));
                continue;
            }
            binI.put(id, enc.interleaved);
            binC.put(id, enc.columnar);
            binQ.put(id, enc.quantized);
            totBin += enc.interleaved.length;
            totZBinI += z(enc.interleaved);
            totZBinC += z(enc.columnar);
            totBinQ += enc.quantized.length;
            totZBinQ += z(enc.quantized);
            out.append(String.format("%-14s %6d | %,9d %6.1f %,8d | %,8d %,8d %,8d | %,8d %,8d%n",
                    id, opsList.size(), nbtBytes.length, nbtBytes.length / (double) opsList.size(), z(nbtBytes),
                    enc.interleaved.length, z(enc.interleaved), z(enc.columnar), enc.quantized.length, z(enc.quantized)));
        }
        out.append(String.format("%-14s %6d | %,9d %6s %,8d | %,8d %,8d %,8d | %,8d %,8d%n",
                "TOTAL", totOps, totNbt, "", totZNbt, totBin, totZBinI, totZBinC, totBinQ, totZBinQ));

        if (!paletteNotes.isEmpty()) {
            out.append("\nblob palettes (already folded into bin variants):\n");
            paletteNotes.forEach(n -> out.append("  ").append(n).append('\n'));
        }

        out.append("\nwhole-file rebuilds (ops swapped in place):\n");
        out.append("  current nbt ops:      %,9d B%n".formatted(file.length));
        out.append("  binary interleaved:   %,9d B%n".formatted(rebuild(world, root, channelsTag, ticks, binI).length));
        out.append("  binary columnar:      %,9d B%n".formatted(rebuild(world, root, channelsTag, ticks, binC).length));
        out.append("  binary quantized:     %,9d B  (pos 1/4096 block, angles 1/65536 turn)%n"
                .formatted(rebuild(world, root, channelsTag, ticks, binQ).length));

        System.out.println(out);
        Path dir = Path.of("build", "reports", "storage");
        Files.createDirectories(dir);
        String name = label.replaceAll("[^A-Za-z0-9.-]+", "_");
        Files.writeString(dir.resolve(name + ".txt"), out.toString());
    }

    /** The file re-emitted with each measured channel's ops as a raw byte blob (unmeasured channels keep NBT); {@code world} null = gzip .shard. */
    private static byte[] rebuild(PolarWorld world, CompoundBinaryTag root, CompoundBinaryTag channelsTag,
                                  long ticks, Map<String, byte[]> variants) throws IOException {
        CompoundBinaryTag.Builder keepNbt = CompoundBinaryTag.builder();
        Bin packed = new Bin();
        for (String id : channelsTag.keySet()) {
            byte[] variant = variants.get(id);
            if (variant == null) keepNbt.put(id, channelsTag.getCompound(id));
            else {
                packed.str(id);
                packed.bytes(variant);
            }
        }
        CompoundBinaryTag newContainer = CompoundBinaryTag.builder().putInt("v", 2).putLong("ticks", ticks)
                .put("channels", keepNbt.build()).putByteArray("bin", packed.arr()).build();
        CompoundBinaryTag.Builder newUser = CompoundBinaryTag.builder();
        for (var e : root.getCompound("user")) {
            if (!e.getKey().equals(Replay.OPS.id())) newUser.put(e.getKey(), e.getValue());
        }
        newUser.put(Replay.OPS.id(), newContainer);
        CompoundBinaryTag.Builder newRoot = CompoundBinaryTag.builder();
        for (var e : root) {
            if (!e.getKey().equals("user")) newRoot.put(e.getKey(), e.getValue());
        }
        newRoot.put("user", newUser.build());
        if (world == null) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                BinaryTagIO.writer().write(newRoot.build(), out, BinaryTagIO.Compression.GZIP);
                return out.toByteArray();
            }
        }
        return PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(),
                PolarWorld.CompressionType.ZSTD, world.minSection(), world.maxSection(),
                nbt(newRoot.build()), List.copyOf(world.chunks())));
    }

    private record Enc(byte[] interleaved, byte[] columnar, byte[] quantized) {}

    private record Op<T>(long tick, T op) {}

    private static <T> List<Op<T>> decode(Recordable<T> type, ListBinaryTag opsList) {
        List<Op<T>> out = new ArrayList<>(opsList.size());
        for (BinaryTag tag : opsList) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            out.add(new Op<>(entry.getLong("t"), type.codec().decode(Transcoder.NBT, entry.get("op")).orElseThrow()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Enc prototype(String id, int version, ListBinaryTag opsList, List<String> paletteNotes) {
        Recordable<?> type = Replay.recordable(id, version);
        if (type == null) return null;
        try {
            return switch (id) {
                case "replay:move" -> moves(decode((Recordable<Channels.Move>) type, opsList));
                case "replay:emove" -> emoves(decode((Recordable<Channels.EntityMove>) type, opsList));
                case "replay:block" -> blocks(decode((Recordable<Channels.BlockOp>) type, opsList));
                case "replay:act" -> acts(decode((Recordable<Channels.Act>) type, opsList));
                case "replay:remove" -> removes(decode((Recordable<Channels.Remove>) type, opsList));
                case "replay:dig" -> digs(decode((Recordable<Channels.Dig>) type, opsList));
                case "replay:skin" -> skins(decode((Recordable<Channels.Skin>) type, opsList));
                case "replay:equip" -> equips(decode((Recordable<Channels.Equip>) type, opsList), paletteNotes);
                case "replay:spawn" -> spawns(decode((Recordable<Channels.Spawn>) type, opsList), paletteNotes);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null; // undecodable (foreign version): raw NBT numbers still report
        }
    }

    private static Enc moves(List<Op<Channels.Move>> ops) {
        Pool names = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Move> op : ops) {
            Channels.Move m = op.op();
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(names.id(m.player()));
            i.f64(m.x()).f64(m.y()).f64(m.z()).f32(m.yaw()).f32(m.pitch()).u8(m.ground() ? 1 : 0);
        }

        Bin c = new Bin();
        prev = 0;
        Bin[] cols = Bin.array(8);
        Bits grounds = new Bits(ops.size());
        for (int k = 0; k < ops.size(); k++) {
            Channels.Move m = ops.get(k).op();
            cols[0].zig(ops.get(k).tick() - prev);
            prev = ops.get(k).tick();
            cols[1].var(names.id(m.player()));
            cols[2].f64(m.x());
            cols[3].f64(m.y());
            cols[4].f64(m.z());
            cols[5].f32(m.yaw());
            cols[6].f32(m.pitch());
            grounds.set(k, m.ground());
        }
        cols[7].raw(grounds.arr());

        // per-key delta chains over fixed-point positions - the lossy variant (1/4096 block, 1/65536 turn)
        Map<String, List<Op<Channels.Move>>> byKey = new LinkedHashMap<>();
        for (Op<Channels.Move> op : ops) byKey.computeIfAbsent(op.op().player(), k -> new ArrayList<>()).add(op);
        Bin q = new Bin();
        for (var e : byKey.entrySet()) {
            q.var(names.id(e.getKey()));
            q.var(e.getValue().size());
            long pt = 0, px = 0, py = 0, pz = 0;
            Bits g = new Bits(e.getValue().size());
            Bin body = new Bin();
            for (int k = 0; k < e.getValue().size(); k++) {
                Op<Channels.Move> op = e.getValue().get(k);
                Channels.Move m = op.op();
                long fx = Math.round(m.x() * 4096), fy = Math.round(m.y() * 4096), fz = Math.round(m.z() * 4096);
                body.zig(op.tick() - pt).zig(fx - px).zig(fy - py).zig(fz - pz)
                        .angle(m.yaw()).angle(m.pitch());
                pt = op.tick(); px = fx; py = fy; pz = fz;
                g.set(k, m.ground());
            }
            q.raw(body.arr()).raw(g.arr());
        }
        return new Enc(withPool(names, i), withPool(names, Bin.concat(cols)), withPool(names, q));
    }

    private static Enc emoves(List<Op<Channels.EntityMove>> ops) {
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.EntityMove> op : ops) {
            Channels.EntityMove m = op.op();
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(m.seq()).f64(m.x()).f64(m.y()).f64(m.z()).f32(m.yaw()).f32(m.pitch());
        }

        Bin[] cols = Bin.array(7);
        prev = 0;
        for (Op<Channels.EntityMove> op : ops) {
            Channels.EntityMove m = op.op();
            cols[0].zig(op.tick() - prev);
            prev = op.tick();
            cols[1].var(m.seq());
            cols[2].f64(m.x());
            cols[3].f64(m.y());
            cols[4].f64(m.z());
            cols[5].f32(m.yaw());
            cols[6].f32(m.pitch());
        }

        Map<Integer, List<Op<Channels.EntityMove>>> byKey = new LinkedHashMap<>();
        for (Op<Channels.EntityMove> op : ops) byKey.computeIfAbsent(op.op().seq(), k -> new ArrayList<>()).add(op);
        Bin q = new Bin();
        for (var e : byKey.entrySet()) {
            q.var(e.getKey());
            q.var(e.getValue().size());
            long pt = 0, px = 0, py = 0, pz = 0;
            for (Op<Channels.EntityMove> op : e.getValue()) {
                Channels.EntityMove m = op.op();
                long fx = Math.round(m.x() * 4096), fy = Math.round(m.y() * 4096), fz = Math.round(m.z() * 4096);
                q.zig(op.tick() - pt).zig(fx - px).zig(fy - py).zig(fz - pz).angle(m.yaw()).angle(m.pitch());
                pt = op.tick(); px = fx; py = fy; pz = fz;
            }
        }
        return new Enc(i.arr(), Bin.concat(cols).arr(), q.arr());
    }

    private static Enc blocks(List<Op<Channels.BlockOp>> ops) {
        Pool states = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.BlockOp> op : ops) {
            Channels.BlockOp b = op.op();
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.zig(b.x()).zig(b.y()).zig(b.z()).var(states.id(b.block()));
        }
        Bin[] cols = Bin.array(5);
        prev = 0;
        for (Op<Channels.BlockOp> op : ops) {
            Channels.BlockOp b = op.op();
            cols[0].zig(op.tick() - prev);
            prev = op.tick();
            cols[1].zig(b.x());
            cols[2].zig(b.y());
            cols[3].zig(b.z());
            cols[4].var(states.id(b.block()));
        }
        byte[] columnar = withPool(states, Bin.concat(cols));
        return new Enc(withPool(states, i), columnar, columnar);
    }

    private static Enc acts(List<Op<Channels.Act>> ops) {
        Pool names = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Act> op : ops) {
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(names.id(op.op().player())).u8(op.op().kind()).zig(op.op().value());
        }
        Bin[] cols = Bin.array(4);
        prev = 0;
        for (Op<Channels.Act> op : ops) {
            cols[0].zig(op.tick() - prev);
            prev = op.tick();
            cols[1].var(names.id(op.op().player()));
            cols[2].u8(op.op().kind());
            cols[3].zig(op.op().value());
        }
        byte[] columnar = withPool(names, Bin.concat(cols));
        return new Enc(withPool(names, i), columnar, columnar);
    }

    private static Enc removes(List<Op<Channels.Remove>> ops) {
        Pool names = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Remove> op : ops) {
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(op.op().seq()).var(names.id(op.op().collector()));
        }
        Bin[] cols = Bin.array(3);
        prev = 0;
        for (Op<Channels.Remove> op : ops) {
            cols[0].zig(op.tick() - prev);
            prev = op.tick();
            cols[1].var(op.op().seq());
            cols[2].var(names.id(op.op().collector()));
        }
        byte[] columnar = withPool(names, Bin.concat(cols));
        return new Enc(withPool(names, i), columnar, columnar);
    }

    private static Enc digs(List<Op<Channels.Dig>> ops) {
        Pool names = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Dig> op : ops) {
            Channels.Dig d = op.op();
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.zig(d.x()).zig(d.y()).zig(d.z()).var(names.id(d.digger())).u8(d.start() ? 1 : 0);
        }
        Bin[] cols = Bin.array(6);
        prev = 0;
        Bits starts = new Bits(ops.size());
        for (int k = 0; k < ops.size(); k++) {
            Channels.Dig d = ops.get(k).op();
            cols[0].zig(ops.get(k).tick() - prev);
            prev = ops.get(k).tick();
            cols[1].zig(d.x());
            cols[2].zig(d.y());
            cols[3].zig(d.z());
            cols[4].var(names.id(d.digger()));
            starts.set(k, d.start());
        }
        cols[5].raw(starts.arr());
        byte[] columnar = withPool(names, Bin.concat(cols));
        return new Enc(withPool(names, i), columnar, columnar);
    }

    private static Enc skins(List<Op<Channels.Skin>> ops) {
        Pool names = new Pool();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Skin> op : ops) {
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(names.id(op.op().player())).str(op.op().textures()).str(op.op().signature());
        }
        byte[] bytes = withPool(names, i);
        return new Enc(bytes, bytes, bytes);
    }

    private static Enc equips(List<Op<Channels.Equip>> ops, List<String> paletteNotes) {
        Pool names = new Pool();
        BlobPool items = new BlobPool();
        EquipmentSlot[] slots = EquipmentSlot.values();
        Bin i = new Bin();
        long prev = 0;
        for (Op<Channels.Equip> op : ops) {
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(names.id(op.op().player()));
            int mask = 0;
            for (int s = 0; s < slots.length; s++) {
                if (op.op().items().get(slots[s].name()) != null) mask |= 1 << s;
            }
            i.u8(mask);
            for (EquipmentSlot slot : slots) {
                BinaryTag item = op.op().items().get(slot.name());
                if (item != null) i.var(items.id(nbt(CompoundBinaryTag.builder().put("i", item).build())));
            }
        }
        paletteNotes.add("equip items: %d refs -> %d distinct blobs, %,d B palette"
                .formatted(items.refs, items.size(), items.bytes()));
        byte[] bytes = withPools(names, items, i);
        return new Enc(bytes, bytes, bytes);
    }

    private static Enc spawns(List<Op<Channels.Spawn>> ops, List<String> paletteNotes) {
        Pool shooters = new Pool();
        BlobPool blobs = new BlobPool();
        Bin i = new Bin();
        Bin q = new Bin();
        long prev = 0, prevQ = 0;
        for (Op<Channels.Spawn> op : ops) {
            Channels.Spawn s = op.op();
            int vis = blobs.id(nbt(s.visual()));
            i.zig(op.tick() - prev);
            prev = op.tick();
            i.var(s.seq()).f64(s.x()).f64(s.y()).f64(s.z()).f32(s.yaw()).f32(s.pitch())
                    .var(shooters.id(s.shooter())).var(vis);
            q.zig(op.tick() - prevQ);
            prevQ = op.tick();
            q.var(s.seq()).zig(Math.round(s.x() * 4096)).zig(Math.round(s.y() * 4096)).zig(Math.round(s.z() * 4096))
                    .angle(s.yaw()).angle(s.pitch()).var(shooters.id(s.shooter())).var(vis);
        }
        paletteNotes.add("spawn vis: %d refs -> %d distinct blobs, %,d B palette"
                .formatted(blobs.refs, blobs.size(), blobs.bytes()));
        byte[] interleaved = withPools(shooters, blobs, i);
        return new Enc(interleaved, interleaved, withPools(shooters, blobs, q));
    }

    private static byte[] nbt(CompoundBinaryTag tag) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BinaryTagIO.writer().write(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int z(byte[] bytes) {
        return (int) Zstd.compress(bytes).length;
    }

    private static byte[] withPool(Pool pool, Bin body) {
        Bin out = new Bin();
        pool.writeTo(out);
        return out.raw(body.arr()).arr();
    }

    private static byte[] withPools(Pool pool, BlobPool blobs, Bin body) {
        Bin out = new Bin();
        pool.writeTo(out);
        blobs.writeTo(out);
        return out.raw(body.arr()).arr();
    }

    private static final class Bin {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        static Bin[] array(int n) {
            Bin[] bins = new Bin[n];
            for (int i = 0; i < n; i++) bins[i] = new Bin();
            return bins;
        }

        static Bin concat(Bin[] bins) {
            Bin out = new Bin();
            for (Bin b : bins) out.raw(b.arr());
            return out;
        }

        Bin u8(int v) { out.write(v); return this; }

        Bin var(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write((int) v);
            return this;
        }

        Bin zig(long v) { return var((v << 1) ^ (v >> 63)); }

        Bin f32(float f) {
            int b = Float.floatToIntBits(f);
            out.write(b >>> 24); out.write(b >>> 16); out.write(b >>> 8); out.write(b);
            return this;
        }

        Bin f64(double d) {
            long b = Double.doubleToLongBits(d);
            for (int s = 56; s >= 0; s -= 8) out.write((int) (b >>> s));
            return this;
        }

        Bin angle(float degrees) { // 1/65536 turn
            int a = Math.round(degrees * (65536f / 360f)) & 0xFFFF;
            out.write(a >>> 8); out.write(a);
            return this;
        }

        Bin str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            var(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Bin bytes(byte[] b) {
            var(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Bin raw(byte[] b) { out.write(b, 0, b.length); return this; }

        byte[] arr() { return out.toByteArray(); }
    }

    private static final class Bits {
        private final byte[] bits;

        Bits(int n) { bits = new byte[(n + 7) / 8]; }

        void set(int i, boolean v) { if (v) bits[i >> 3] |= (byte) (1 << (i & 7)); }

        byte[] arr() { return bits; }
    }

    private static final class Pool {
        private final Map<String, Integer> index = new LinkedHashMap<>();

        int id(String s) { return index.computeIfAbsent(s, k -> index.size()); }

        void writeTo(Bin bin) {
            bin.var(index.size());
            index.keySet().forEach(bin::str);
        }
    }

    private static final class BlobPool {
        private final Map<ByteBuffer, Integer> index = new LinkedHashMap<>();
        int refs;

        int id(byte[] blob) {
            refs++;
            return index.computeIfAbsent(ByteBuffer.wrap(blob), k -> index.size());
        }

        int size() { return index.size(); }

        int bytes() { return index.keySet().stream().mapToInt(ByteBuffer::remaining).sum(); }

        void writeTo(Bin bin) {
            bin.var(index.size());
            for (ByteBuffer b : index.keySet()) bin.bytes(b.array());
        }
    }
}
