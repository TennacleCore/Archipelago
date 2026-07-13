package test;

import io.github.term4.shardkit.ticking.ExecutionDomain;
import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.shard.ShardManager;
import io.github.term4.shardkit.Storage;
import io.github.term4.shardkit.testsupport.DetectorEntity;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The scheduler observability rig: spawn N domains x M detector entities, dial per-domain busy-spin load until
 * the saturation knee shows in lag/mspt, churn create/destroy, and pull {@link ExecutionDomain#metrics()} as
 * JSON lines for run-to-run diffs. VALIDATE counters ride along live: detector tick overlaps (double ticks)
 * + torn snapshots from a batch/fork reader. Consumes only public metrics.
 */
final class Bench {

    private static final int BASE = 2000; // detector area, far from the playground spawn
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final InstanceContainer world;
    private final ShardManager shards = new ShardManager();
    private final List<Slot> slots = new CopyOnWriteArrayList<>();
    private final List<DetectorEntity> detectors = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicLong torn = new AtomicLong();
    private volatile long spinMicros;
    private volatile Thread churn;
    private volatile Thread validator;
    private volatile Task watch;
    private volatile Path report;

    private record Slot(int id, Shard shard, ExecutionDomain domain) {}

    Bench(InstanceContainer world) {
        this.world = world;
    }

    Command command() {
        Command bench = new Command("bench");
        bench.setDefaultExecutor((sender, ctx) -> sender.sendMessage(
                "usage: /bench spawn <domains> <entities> | load <spinMicros> | churn on|off | validate on|off | watch <sec|0> | report | stop"));

        Argument<Integer> domains = ArgumentType.Integer("domains").between(1, 1024);
        Argument<Integer> entities = ArgumentType.Integer("entities").between(0, 100);
        bench.addSyntax((sender, ctx) -> spawn(sender, ctx.get(domains), ctx.get(entities)),
                ArgumentType.Literal("spawn"), domains, entities);

        Argument<Integer> micros = ArgumentType.Integer("spinMicros").between(0, 200_000);
        bench.addSyntax((sender, ctx) -> {
            spinMicros = ctx.get(micros);
            sender.sendMessage("[bench] every bench domain now spins " + spinMicros + "us per tick");
        }, ArgumentType.Literal("load"), micros);

        Argument<String> toggle = ArgumentType.Word("toggle").from("on", "off");
        bench.addSyntax((sender, ctx) -> churn(sender, ctx.get(toggle).equals("on")),
                ArgumentType.Literal("churn"), toggle);
        bench.addSyntax((sender, ctx) -> validate(sender, ctx.get(toggle).equals("on")),
                ArgumentType.Literal("validate"), toggle);

        Argument<Integer> seconds = ArgumentType.Integer("sec").between(0, 3600);
        bench.addSyntax((sender, ctx) -> watch(sender, ctx.get(seconds)), ArgumentType.Literal("watch"), seconds);
        bench.addSyntax((sender, ctx) -> report(sender), ArgumentType.Literal("report"));
        bench.addSyntax((sender, ctx) -> stop(sender), ArgumentType.Literal("stop"));
        return bench;
    }

    private void spawn(CommandSender sender, int domainCount, int entityCount) {
        int first = slots.size();
        Set<Long> chunks = new HashSet<>();
        for (int i = first; i < first + domainCount; i++) {
            chunks.add(((long) ((BASE + (i % 32) * 2) >> 4) << 32) | ((BASE + (i / 32) * 2) >> 4));
        }
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (long c : chunks) loads.add(world.loadChunk((int) (c >> 32), (int) c));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRun(() -> {
            for (int i = first; i < first + domainCount; i++) {
                int id = ids.getAndIncrement();
                ExecutionDomain domain = ExecutionDomain.create();
                Slot slot = new Slot(id, shards.create(world, "bench-" + id, domain), domain);
                domain.onTick(t -> {
                    long spin = spinMicros;
                    if (spin <= 0) return;
                    long end = System.nanoTime() + spin * 1_000;
                    while (System.nanoTime() < end) Thread.onSpinWait();
                });
                for (int j = 0; j < entityCount; j++) {
                    DetectorEntity e = new DetectorEntity();
                    detectors.add(e);
                    slot.shard().spawn(e, new Pos(BASE + 0.5 + (i % 32) * 2, 80 + j, BASE + 0.5 + (i / 32) * 2));
                }
                slots.add(slot);
            }
            sender.sendMessage("[bench] +" + domainCount + " domains x " + entityCount
                    + " detectors (now " + slots.size() + " domains, " + detectors.size() + " detectors)");
        });
    }

    private void churn(CommandSender sender, boolean on) {
        if (!on) {
            Thread t = churn;
            churn = null;
            if (t != null) join(t);
            sender.sendMessage("[bench] churn off");
            return;
        }
        if (churn != null) return;
        churn = Thread.ofPlatform().name("bench-churn").start(() -> {
            Random rnd = new Random();
            Deque<Slot> mine = new ArrayDeque<>();
            while (churn == Thread.currentThread()) {
                int id = ids.getAndIncrement();
                ExecutionDomain domain = ExecutionDomain.create();
                mine.add(new Slot(id, shards.create(world, "bench-churn-" + id, domain), domain));
                if (mine.size() > 2) destroy(mine.poll());
                if (!slots.isEmpty() && !detectors.isEmpty()) { // transfers stay among the long-lived slots
                    slots.get(rnd.nextInt(slots.size())).shard().transfer(detectors.get(rnd.nextInt(detectors.size())));
                }
                LockSupport.parkNanos(150_000_000);
            }
            while (!mine.isEmpty()) destroy(mine.poll());
        });
        sender.sendMessage("[bench] churn on: create/destroy + detector transfers every 150ms");
    }

    private void validate(CommandSender sender, boolean on) {
        if (!on) {
            Thread t = validator;
            validator = null;
            if (t != null) join(t);
            sender.sendMessage("[bench] validate off (doubleTicks=" + doubleTicks() + " torn=" + torn.get() + ")");
            return;
        }
        if (validator != null) return;
        validator = Thread.ofPlatform().name("bench-validate").start(() -> {
            Random rnd = new Random();
            int flip = 0;
            while (validator == Thread.currentThread()) {
                if (slots.isEmpty()) {
                    LockSupport.parkNanos(50_000_000);
                    continue;
                }
                Slot slot = slots.get(rnd.nextInt(slots.size()));
                Block mat = (flip++ & 1) == 0 ? Block.GOLD_BLOCK : Block.DIAMOND_BLOCK;
                Map<Point, Block> batch = new HashMap<>();
                for (int k = 0; k < 24; k++) batch.put(new Vec(3000 + k, 90, 3000 + slot.id()), mat);
                slot.shard().setBlocks(batch);
                if (!uniform(slot.shard().diff(), slot.id()) || !uniform(slot.shard().fork().diff(), slot.id())) {
                    torn.incrementAndGet();
                }
                LockSupport.parkNanos(5_000_000);
            }
        });
        sender.sendMessage("[bench] validate on: torn-snapshot reader running, detector overlaps counted live");
    }

    private boolean uniform(Map<Point, Block> snapshot, int row) {
        Block seen = null;
        for (int k = 0; k < 24; k++) {
            Block b = snapshot.get(new Vec(3000 + k, 90, 3000 + row));
            if (b == null) continue;
            if (seen == null) seen = b;
            else if (seen.stateId() != b.stateId()) return false;
        }
        return true;
    }

    private void watch(CommandSender sender, int seconds) {
        Task t = watch;
        watch = null;
        if (t != null) t.cancel();
        if (seconds == 0) {
            sender.sendMessage("[bench] watch off");
            return;
        }
        watch = MinecraftServer.getSchedulerManager().scheduleTask(() -> report(sender),
                TaskSchedule.immediate(), TaskSchedule.seconds(seconds));
        sender.sendMessage("[bench] watching every " + seconds + "s -> " + reportFile());
    }

    private void report(CommandSender sender) {
        long worstLag = 0, misses = 0;
        double worstEwma = 0, worstMax = 0;
        int dead = 0;
        StringBuilder line = new StringBuilder("{\"t\":").append(System.currentTimeMillis())
                .append(",\"spinMicros\":").append(spinMicros);
        ExecutionDomain.Engine engine = ExecutionDomain.engine();
        line.append(",\"engine\":{\"clocks\":").append(engine.clocks())
                .append(",\"zombies\":").append(engine.zombieCarriers())
                .append(",\"parallelism\":").append(engine.parallelism())
                .append(",\"cores\":").append(engine.cores())
                .append(",\"interrupts\":").append(engine.interrupts())
                .append(",\"quarantines\":").append(engine.quarantines()).append('}');
        line.append(",\"validate\":{\"doubleTicks\":").append(doubleTicks())
                .append(",\"torn\":").append(torn.get()).append('}');
        line.append(",\"domains\":[");
        boolean firstEntry = true;
        for (Slot slot : slots) {
            ExecutionDomain.Metrics m = slot.domain().metrics();
            if (!firstEntry) line.append(',');
            firstEntry = false;
            line.append("{\"id\":").append(slot.id())
                    .append(",\"ticks\":").append(m.ticks())
                    .append(",\"lag\":").append(m.lag())
                    .append(",\"msptLast\":").append(String.format("%.3f", m.msptLast()))
                    .append(",\"msptEwma\":").append(String.format("%.3f", m.msptEwma()))
                    .append(",\"msptMax\":").append(String.format("%.3f", m.msptMax()))
                    .append(",\"misses\":").append(m.deadlineMisses())
                    .append(",\"backlog\":").append(m.taskBacklog())
                    .append(",\"arrivals\":").append(m.pendingArrivals())
                    .append(",\"entities\":").append(m.entityCount())
                    .append(",\"hooks\":").append(m.hookCount())
                    .append(",\"alive\":").append(m.alive())
                    .append(",\"thread\":\"").append(m.threadName() == null ? "" : m.threadName()).append("\"}");
            worstLag = Math.max(worstLag, m.lag());
            worstEwma = Math.max(worstEwma, m.msptEwma());
            worstMax = Math.max(worstMax, m.msptMax());
            misses += m.deadlineMisses();
            if (!m.alive()) dead++;
        }
        line.append("]}");
        try {
            Files.writeString(reportFile(), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            sender.sendMessage("[bench] report write failed: " + e.getMessage());
        }
        sender.sendMessage(String.format(
                "[bench] %d domains (%d dead) spin=%dus | worst lag=%d ewma=%.2fms max=%.2fms misses=%d | doubleTicks=%d torn=%d | clocks=%d zombies=%d",
                slots.size(), dead, spinMicros, worstLag, worstEwma, worstMax, misses,
                doubleTicks(), torn.get(), engine.clocks(), engine.zombieCarriers()));
    }

    private void stop(CommandSender sender) {
        Thread c = churn, v = validator;
        churn = null;
        validator = null;
        if (c != null) join(c);
        if (v != null) join(v);
        Task w = watch;
        watch = null;
        if (w != null) w.cancel();
        spinMicros = 0;
        for (Slot slot : slots) destroy(slot);
        slots.clear();
        for (DetectorEntity e : detectors) e.remove();
        sender.sendMessage("[bench] stopped: " + detectors.size() + " detectors removed, doubleTicks="
                + doubleTicks() + " torn=" + torn.get());
        detectors.clear();
        torn.set(0);
        report = null; // next session gets a fresh file
    }

    private long doubleTicks() {
        long sum = 0;
        for (DetectorEntity e : detectors) sum += e.overlaps.get();
        return sum;
    }

    private void destroy(Slot slot) {
        slots.remove(slot);
        shards.destroy(slot.shard());
    }

    private Path reportFile() {
        Path file = report;
        if (file == null) {
            Path dir = Storage.dir("bench-dir", "bench-reports");
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
            report = file = dir.resolve("bench-" + LocalDateTime.now().format(STAMP) + ".jsonl");
        }
        return file;
    }

    private static void join(Thread t) {
        try {
            t.join(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
