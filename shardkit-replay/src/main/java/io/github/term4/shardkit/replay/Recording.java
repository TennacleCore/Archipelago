package io.github.term4.shardkit.replay;

import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.polar.PolarShardCodec;
import io.github.term4.worldaccess.WorldAccess;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * One recording session over a shard: the t0 snapshot is taken immediately, then dispatchers fill the channels
 * until {@link #stop()} returns the finished replay - one polar file, playable via {@link Replay#play}.
 * Entities are NOT part of the snapshot; every entity (t0 ones included) rides the spawn channel with a
 * {@link Visuals visual-first} snapshot plus its save descriptor when it has one.
 */
public final class Recording {

    private static final Logger LOGGER = LoggerFactory.getLogger(Recording.class);

    private final Shard shard;
    private final byte[] t0;
    private final Map<Recordable<?>, List<Recorded<?>>> ops = new LinkedHashMap<>();
    private final List<Dispatcher> dispatchers;
    private final Task sampler;

    final Map<Entity, Integer> tracked = new HashMap<>();
    final Map<Entity, String> pickedUpBy = new HashMap<>();
    /** Actors whose full state must re-emit next sample (respawned - their ghost is recreated fresh). */
    final Set<String> refreshActors = new HashSet<>();
    private final Map<Integer, Long> spawnTicks = new HashMap<>();
    private final Set<Integer> seen = new HashSet<>();
    private int nextSeq;
    private long tick;
    private boolean active = true;
    private boolean loggedError;

    Recording(@NotNull Shard shard) {
        this.shard = shard;
        this.t0 = PolarShardCodec.save(shard, null, false);
        this.dispatchers = new ArrayList<>(Probes.core());
        Replay.dispatcherFactories().forEach(factory -> dispatchers.add(factory.get()));
        dispatchers.forEach(d -> d.start(this));
        sampler = MinecraftServer.getSchedulerManager().scheduleTask(this::sample,
                TaskSchedule.tick(1), TaskSchedule.tick(1));
    }

    private void sample() {
        tick++;
        for (Dispatcher dispatcher : dispatchers) {
            try {
                dispatcher.sample(this);
            } catch (Exception e) {
                if (!loggedError) { // one bad dispatcher must never stall the rest of the recording
                    loggedError = true;
                    LOGGER.warn("recording dispatcher failed (logged once)", e);
                }
            }
        }
    }

    public @NotNull Shard shard() {
        return shard;
    }

    /** The current recording tick (0 until the first sampled tick). */
    public long tick() {
        return tick;
    }

    public boolean active() {
        return active;
    }

    /** Whether {@code entity} plays in the recorded shard - the filter for event probes. */
    public boolean covers(@NotNull Entity entity) {
        return WorldAccess.of(entity) == shard;
    }

    /** Appends an op to {@code channel} at the current tick (dropped once stopped). */
    public <T> void emit(@NotNull Recordable<T> channel, @NotNull T op) {
        emitAt(channel, op, tick);
    }

    private <T> void emitAt(Recordable<T> channel, T op, long atTick) {
        if (!active) return;
        ops.computeIfAbsent(channel, c -> new ArrayList<>()).add(new Recorded<>(atTick, op));
    }

    /**
     * Records an entity appearing: visual snapshot + save descriptor (sanitizers applied) on the spawn channel,
     * position track + removal from then on. Repeat sightings dedup, so per-tick sweeps and event probes can both
     * call this; {@code pos} includes the spawn rotation; {@code shooter} is the launcher's username ({@code ""} =
     * none) - the playback wire owner.
     */
    public void spawn(@NotNull Entity entity, @NotNull Pos pos, @NotNull String shooter) {
        if (!active || !seen.add(entity.getEntityId())) return;
        int seq = nextSeq++;
        tracked.put(entity, seq);
        spawnTicks.put(seq, tick);
        CompoundBinaryTag desc = PolarShardCodec.descriptor(entity);
        if (desc != null) {
            for (UnaryOperator<CompoundBinaryTag> sanitizer : Replay.sanitizers()) desc = sanitizer.apply(desc);
        }
        emit(Channels.SPAWNS, new Channels.Spawn(pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch(), seq,
                Visuals.snapshot(entity), desc == null ? CompoundBinaryTag.empty() : desc, shooter));
    }

    /**
     * Records a tracked entity ending at an exact position - event-driven removal beats the per-tick sweep by up
     * to a tick (an impacting projectile otherwise sails past its mark before vanishing). The twin lives at least
     * one tick: a same-tick spawn+remove (point-blank throw) would flash for zero. No-op when untracked;
     * {@code collector} names the pickup swoosh's ghost ({@code ""} = plain despawn).
     */
    public void remove(@NotNull Entity entity, @NotNull Pos at, @NotNull String collector) {
        Integer seq = tracked.remove(entity);
        if (!active || seq == null) return;
        pickedUpBy.remove(entity);
        Long spawned = spawnTicks.remove(seq);
        long when = Math.max(tick, (spawned == null ? 0 : spawned) + 1);
        emitAt(Channels.ENTITY_MOVES, new Channels.EntityMove(seq, at.x(), at.y(), at.z(), at.yaw(), at.pitch()), when);
        emitAt(Channels.REMOVES, new Channels.Remove(seq, collector), when);
    }

    /** Ends the recording and returns the replay file: the t0 snapshot with the op log in its user data. */
    public byte @NotNull [] stop() {
        if (!active) throw new IllegalStateException("already stopped");
        active = false;
        sampler.cancel();
        dispatchers.forEach(d -> d.stop(this));
        CompoundBinaryTag.Builder channels = CompoundBinaryTag.builder();
        ops.forEach((type, list) -> {
            if (list.isEmpty()) return;
            ListBinaryTag.Builder<CompoundBinaryTag> encoded = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (Recorded<?> op : list) {
                encoded.add(CompoundBinaryTag.builder().putLong("t", op.tick()).put("op", encode(type, op)).build());
            }
            channels.put(type.id(), CompoundBinaryTag.builder()
                    .putInt("v", type.version()).put("ops", encoded.build()).build());
        });
        CompoundBinaryTag container = CompoundBinaryTag.builder()
                .putInt("v", 1).putLong("ticks", tick).put("channels", channels.build()).build();
        return PolarShardCodec.amendUserData(t0, data -> data.put(Replay.OPS, container));
    }

    @SuppressWarnings("unchecked")
    private static <T> BinaryTag encode(Recordable<T> type, Recorded<?> op) {
        return type.codec().encode(Transcoder.NBT, (T) op.op()).orElseThrow();
    }

    /** Op counts per channel id, for status messages. */
    public @NotNull Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        ops.forEach((type, list) -> out.put(type.id(), list.size()));
        return out;
    }
}
