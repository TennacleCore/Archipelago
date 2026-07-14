package io.github.term4.archipelago.storage.polar;

import com.github.luben.zstd.Zstd;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.DataKey;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.storage.Nbt;
import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.hollowcube.polar.PolarWriter;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Diff-density harness for the storage-efficiency epic: how much a sparse diff pays for polar's full 4096-slot
 * section arrays, pre and post zstd, against a hypothetical (index, palette) pair encoding. Also reports the
 * density of any real saves in {@code ../shard-saves}. Measurement only; nothing changes the shipped format.
 */
class DiffDensityTest {

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
    }

    private static final Block[] STATES = {Block.COBBLESTONE, Block.OAK_PLANKS,
            Block.OAK_STAIRS.withProperty("facing", "east"), Block.AIR};

    @Test
    void densitySweep() {
        StringBuilder out = new StringBuilder("\n=== diff density: K blocks in ONE section ===\n");
        out.append(String.format("%7s | %10s %10s | %11s %10s | %10s %10s%n",
                "K", "file(zstd)", "frameNone", "sectionRaw", "sparseRaw", "z(section)", "z(sparse)"));
        for (int k : new int[]{1, 3, 10, 50, 200, 1000, 4096}) {
            Shard shard = new Shard(instance);
            Random rng = new Random(7);
            Set<Integer> used = new HashSet<>();
            while (used.size() < k) {
                int idx = rng.nextInt(4096);
                if (used.add(idx)) {
                    shard.setBlock(new Vec(idx & 15, 64 + ((idx >> 8) & 15), (idx >> 4) & 15),
                            STATES[used.size() % STATES.length]);
                }
            }
            byte[] file = PolarShardCodec.save(shard, null, false);
            out.append(row(k, file));
        }
        out.append("\n=== diff density: 300 blocks spread over a 40x40 build plot (9 chunks) ===\n");
        Shard build = new Shard(instance);
        Random rng = new Random(11);
        Set<Vec> placed = new HashSet<>();
        while (placed.size() < 300) {
            Vec pos = new Vec(rng.nextInt(40) - 20, 64 + rng.nextInt(8), rng.nextInt(40) - 20);
            if (placed.add(pos)) build.setBlock(pos, STATES[placed.size() % 3]);
        }
        out.append(row(300, PolarShardCodec.save(build, null, false)));
        print(out.toString(), "diff-density");
    }

    @Test
    void realShardSaves() throws IOException {
        List<Path> files = new ArrayList<>();
        Path saves = Path.of("..", "shard-saves");
        if (Files.isDirectory(saves)) {
            try (Stream<Path> s = Files.list(saves)) {
                s.filter(p -> p.getFileName().toString().endsWith(".polar")).forEach(files::add);
            }
        }
        Assumptions.assumeFalse(files.isEmpty(), "no real shard saves found");
        StringBuilder out = new StringBuilder("\n=== real shard saves ===\n");
        for (Path p : files) {
            byte[] file = Files.readAllBytes(p);
            PolarWorld world = PolarReader.read(file);
            int sections = 0, blocks = 0;
            for (PolarChunk chunk : world.chunks()) {
                for (PolarSection section : chunk.sections()) {
                    if (section.isEmpty()) continue;
                    sections++;
                    if (section.blockPalette().length > 1) {
                        for (int v : section.blockData()) {
                            if (v != 0) blocks++; // 0 = the untouched sentinel
                        }
                    }
                }
            }
            out.append("%s: %,d B, %d chunks, %d touched sections, %d diff blocks (%.1f blocks/section)%n"
                    .formatted(p, file.length, world.chunks().size(), sections, blocks,
                            sections == 0 ? 0 : blocks / (double) sections));
        }
        print(out.toString(), "real-saves");
    }

    private static String row(int k, byte[] file) {
        PolarWorld world = PolarReader.read(file);
        byte[] frameNone = PolarWriter.write(new PolarWorld(world.version(), world.dataVersion(),
                PolarWorld.CompressionType.NONE, world.minSection(), world.maxSection(), world.userData(),
                List.copyOf(world.chunks())));
        long sectionRaw = 0, sparseRaw = 0;
        List<byte[]> packedPayloads = new ArrayList<>(), sparsePayloads = new ArrayList<>();
        for (PolarChunk chunk : world.chunks()) {
            for (PolarSection section : chunk.sections()) {
                if (section.isEmpty() || section.blockPalette().length <= 1) continue;
                String[] palette = section.blockPalette();
                int bits = Math.max(1, (int) Math.ceil(Math.log(palette.length) / Math.log(2)));
                long paletteBytes = 1;
                for (String s : palette) paletteBytes += 1 + s.getBytes(StandardCharsets.UTF_8).length;
                long packedBytes = paletteBytes + 1 + (long) Math.ceil(4096.0 / (64 / bits)) * 8;
                sectionRaw += packedBytes;
                int count = 0;
                for (int v : section.blockData()) {
                    if (v != 0) count++;
                }
                // sparse shape: varint count + per block 12-bit index + palette id (packed to bytes)
                long sparseBytes = paletteBytes + 2 + (long) Math.ceil(count * (12 + bits) / 8.0);
                sparseRaw += sparseBytes;
                packedPayloads.add(packedPayload(section, bits));
                sparsePayloads.add(sparsePayload(section, bits));
            }
        }
        return String.format("%7d | %,10d %,10d | %,11d %,10d | %,10d %,10d%n",
                k, file.length, frameNone.length, sectionRaw, sparseRaw,
                z(packedPayloads), z(sparsePayloads));
    }

    private static byte[] packedPayload(PolarSection section, int bits) {
        int perLong = 64 / bits;
        long[] packed = new long[(4096 + perLong - 1) / perLong];
        int[] data = section.blockData();
        for (int i = 0; i < data.length; i++) {
            packed[i / perLong] |= ((long) data[i]) << ((i % perLong) * bits);
        }
        byte[] out = new byte[packed.length * 8 + paletteBytes(section).length];
        System.arraycopy(paletteBytes(section), 0, out, 0, paletteBytes(section).length);
        for (int i = 0; i < packed.length; i++) {
            for (int b = 0; b < 8; b++) out[paletteBytes(section).length + i * 8 + b] = (byte) (packed[i] >>> (b * 8));
        }
        return out;
    }

    private static byte[] sparsePayload(PolarSection section, int bits) {
        int[] data = section.blockData();
        List<Byte> out = new ArrayList<>();
        for (byte b : paletteBytes(section)) out.add(b);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) continue;
            out.add((byte) (i & 0xFF));
            out.add((byte) ((i >> 8) | ((data[i] & 0xF) << 4))); // 12-bit pos + up-to-4-bit palette id
            if (bits > 4) out.add((byte) (data[i] >> 4));
        }
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static byte[] paletteBytes(PolarSection section) {
        StringBuilder sb = new StringBuilder();
        for (String s : section.blockPalette()) sb.append(s).append('\0');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int z(List<byte[]> payloads) {
        int total = 0;
        for (byte[] p : payloads) total += (int) Zstd.compress(p).length;
        return total;
    }

    private static void print(String report, String name) {
        System.out.println(report);
        try {
            Path dir = Path.of("build", "reports", "storage");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), report);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
