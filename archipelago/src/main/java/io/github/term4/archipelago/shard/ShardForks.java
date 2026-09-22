package io.github.term4.archipelago.shard;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.Weather;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Deriving one shard from another: forking a copy, seeding from the base, and flattening a chain or two
 * branches into one. Every entry point reads settled state inside the source's relay, so a snapshot is never
 * taken mid-tick.
 *
 * <p>Split out of {@link Shard} because it is a distinct job - a shard's own work is holding a diff and fanning
 * it to viewers, not making new shards. Same package, so it reaches the internals it needs without any of them
 * becoming public.
 */
final class ShardForks {

    private ShardForks() {}

    /** One side's settled state, taken inside that side's relay. */
    private record Layer(Map<Point, Block> blocks, long time, @Nullable Weather weather, Map<Entity, Pos> clones) {}

    static Shard fork(Shard self, BiFunction<Point, Block, @Nullable Block> blocks,
                      @Nullable Function<Entity, Entity> entityCloner) {
        return self.relay(() -> { // the whole snapshot reads settled state, never mid-tick
            Shard copy = self.parent != null ? new Shard(self.parent, null) : new Shard(self.base);
            Map<Point, Block> kept = new HashMap<>();
            self.forEachDiff((pos, block) -> {
                Block out = blocks.apply(pos, block);
                if (out != null) kept.put(pos, out);
            });
            copy.setBlocks(kept);
            if (self.sky.timeOverride() >= 0) copy.setTime(self.sky.timeOverride());
            Weather weather = self.sky.weatherOverride();
            if (weather != null) copy.setWeather(weather);
            forEachClone(self, entityCloner, copy::spawn);
            return copy;
        });
    }

    static Shard seedFromBase(Shard self, @Nullable Function<Entity, Entity> cloner) {
        if (cloner == null) return self;
        for (Entity entity : self.base.getEntities()) {
            if (entity.isRemoved() || entity instanceof Player || Shard.of(entity) != null) continue;
            Entity clone = cloner.apply(entity);
            if (clone != null) self.spawn(clone, entity.getPosition());
        }
        return self;
    }

    static Shard flattenOnto(Shard top, Shard bottom, Shard.BlockMerge merger,
                             @Nullable Function<Entity, Entity> cloner) {
        if (bottom.base != top.base) {
            throw new IllegalArgumentException("shards flatten over one shared base instance");
        }
        Layer bottomSide;
        Layer topSide;
        if (sameClock(top, bottom)) { // one relay: the whole combine reads one settled tick
            Layer[] both = top.relay(() -> new Layer[]{snapshot(bottom, cloner), snapshot(top, cloner)});
            bottomSide = both[0];
            topSide = both[1];
        } else {
            bottomSide = bottom.relay(() -> snapshot(bottom, cloner));
            topSide = top.relay(() -> snapshot(top, cloner));
        }
        Shard flat = new Shard(top.base);
        Map<Point, Block> merged = new HashMap<>();
        Set<Point> union = new HashSet<>(bottomSide.blocks().keySet());
        union.addAll(topSide.blocks().keySet());
        for (Point pos : union) {
            Block block = merger.merge(pos, bottomSide.blocks().get(pos), topSide.blocks().get(pos));
            if (block != null) merged.put(pos, block);
        }
        flat.setBlocks(merged);
        long time = topSide.time() >= 0 ? topSide.time() : bottomSide.time();
        if (time >= 0) flat.setTime(time);
        Weather weather = topSide.weather() != null ? topSide.weather() : bottomSide.weather();
        if (weather != null) flat.setWeather(weather);
        bottomSide.clones().forEach(flat::spawn);
        topSide.clones().forEach(flat::spawn);
        return flat;
    }

    static Shard flatten(Shard self, @Nullable Function<Entity, Entity> cloner) {
        return self.relay(() -> {
            Shard flat = new Shard(self.base);
            Map<Point, Block> blocks = new HashMap<>();
            self.effectiveDiff(blocks);
            flat.setBlocks(blocks);
            long time = self.effectiveTimeOverride();
            if (time >= 0) flat.setTime(time);
            Weather weather = self.effectiveWeatherOverride();
            if (weather != null) flat.setWeather(weather);
            if (cloner != null) {
                List<Shard> chain = new ArrayList<>();
                for (Shard s = self; s != null; s = s.parent) chain.add(s);
                for (int i = chain.size() - 1; i >= 0; i--) forEachClone(chain.get(i), cloner, flat::spawn);
            }
            return flat;
        });
    }

    private static boolean sameClock(Shard self, Shard other) {
        if (self.domain == null || other.domain == null) return self.domain == other.domain;
        return self.domain.sharesClock(other.domain);
    }

    private static void forEachClone(Shard self, @Nullable Function<Entity, Entity> cloner,
                                     BiConsumer<Entity, Pos> out) {
        if (cloner == null) return;
        for (Entity entity : self.entities) {
            if (entity.isRemoved()) continue;
            Entity clone = cloner.apply(entity);
            if (clone != null) out.accept(clone, entity.getPosition());
        }
    }

    // one side's settled state; call inside that shard's relay
    private static Layer snapshot(Shard self, @Nullable Function<Entity, Entity> cloner) {
        Map<Point, Block> blocks = new HashMap<>();
        self.effectiveDiff(blocks);
        Map<Entity, Pos> clones = new LinkedHashMap<>();
        forEachClone(self, cloner, clones::put);
        return new Layer(blocks, self.effectiveTimeOverride(), self.effectiveWeatherOverride(), clones);
    }
}
