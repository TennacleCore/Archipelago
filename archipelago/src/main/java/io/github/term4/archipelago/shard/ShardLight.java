package io.github.term4.archipelago.shard;

import net.kyori.adventure.key.Key;
import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Shape;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.light.Light;
import net.minestom.server.instance.light.LightCompute;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.utils.Direction;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A shard's light: per-shard {@link Light} state over SHARED base blocks - the chain can only change light near
 * its diff, so this holds light sections for candidate columns (sentinel-compressed; a fully-lit void section is
 * a shared constant) and reads every untouched palette live from the base chunks. No chunk or section clones are
 * ever resident; diff-touched sections get a transient palette copy per flush.
 *
 * <p>Writes only dirty-mark (the vanilla model): the recompute runs OFF-thread, coalesced by the same
 * {@code minestom.send-light-after-block-placement-delay} window vanilla uses, single-flight per shard, on the
 * shard's own clock. Results publish as follow-up {@link UpdateLightPacket}s to the block viewers (whatever
 * light arrives last wins client-side - one instance chunk carries ONE light state per client on the wire).
 * Modern-client polish: 1.8 clients relight locally from block data.
 */
final class ShardLight implements ShardLighting {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardLight.class);
    private static final int DELAY = ServerFlag.SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY;
    /** Sustained building re-arms the delay like vanilla, but never past this - light must land mid-game too. */
    private static final int MAX_DEFER = DELAY * 2;

    // LightingChunk.DIFFUSE_SKY_LIGHT (private upstream): sky-occluding despite a non-occluding shape
    private static final Set<Key> DIFFUSE_SKY_LIGHT = Set.of(
            Block.COBWEB.key(), Block.ICE.key(), Block.HONEY_BLOCK.key(), Block.SLIME_BLOCK.key(),
            Block.WATER.key(), Block.ACACIA_LEAVES.key(), Block.AZALEA_LEAVES.key(), Block.BIRCH_LEAVES.key(),
            Block.DARK_OAK_LEAVES.key(), Block.FLOWERING_AZALEA_LEAVES.key(), Block.JUNGLE_LEAVES.key(),
            Block.CHERRY_LEAVES.key(), Block.OAK_LEAVES.key(), Block.SPRUCE_LEAVES.key(), Block.SPAWNER.key(),
            Block.BEACON.key(), Block.END_GATEWAY.key(), Block.CHORUS_PLANT.key(), Block.CHORUS_FLOWER.key(),
            Block.FROSTED_ICE.key(), Block.SEAGRASS.key(), Block.TALL_SEAGRASS.key(), Block.LAVA.key());

    private final Shard shard;
    /** Published packets - the only artifact readers touch (sendTo / view swaps, any thread). */
    private final Map<Long, UpdateLightPacket> cache = new ConcurrentHashMap<>();
    /** Chunks whose shard light reached at least one client - the restore set for swaps/resets. */
    private final Set<Long> lit = ConcurrentHashMap.newKeySet();
    /** Written since the last flush (owner threads add, the flush drains): section keys from block
     *  writes, whole columns from sightings/refreshes. */
    private final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    private final Set<Long> dirtyColumns = ConcurrentHashMap.newKeySet();
    /** Columns whose sections have computed at least once (flush thread only). */
    private final Set<Long> knownColumns = new HashSet<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean resetPending;
    private volatile boolean closed;
    // the coalescing countdown counts THIS hook's own firings - independent of which clock drives the shard
    private volatile long clock;
    private volatile long armedAt = -1;
    private volatile long flushAt = Long.MAX_VALUE;
    private final Runnable unhook;

    // written by the single-flight flush only; concurrent for the query path (same read-during-recompute
    // transient Instance.getBlockLight accepts)
    private final Map<Sec, Light> blockLights = new ConcurrentHashMap<>();
    private final Map<Sec, Light> skyLights = new ConcurrentHashMap<>();

    private record Sec(int x, int y, int z) {}

    ShardLight(Shard shard) {
        this.shard = shard;
        this.unhook = shard.onTick(this::onTick);
        // relight may be enabled AFTER the diff exists (a loaded save, a mid-game toggle): the chain's
        // touched chunks compute without waiting for the next write
        Set<Long> touched = ShardLighting.chainChunks(shard);
        if (!touched.isEmpty()) markColumns(touched, false);
    }

    @Override
    public void close() {
        closed = true; // an in-flight flush re-checks before publishing; a racing send loses at worst one packet
        unhook.run();
        for (long index : Set.copyOf(lit)) restoreBase(index);
        cache.clear();
        dirtySections.clear();
        dirtyColumns.clear();
    }

    /** This shard's light for one chunk to one viewer (chunk load / swap-in); a cache miss heals on the next flush. */
    @Override
    public void sendTo(Player player, int chunkX, int chunkZ) {
        long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        UpdateLightPacket packet = cache.get(index);
        if (packet != null) {
            player.sendPacket(packet);
            lit.add(index);
            return;
        }
        if (candidate(chunkX, chunkZ) && shard.instance().isChunkLoaded(chunkX, chunkZ)) markColumns(Set.of(index), true);
    }

    /** Blocks changed (section keys, this shard's view): dirty-mark only - the flush recomputes off-thread. */
    @Override
    public void changed(Set<Long> sections) {
        if (closed) return;
        dirtySections.addAll(sections);
        arm(false);
    }

    /** After a reset (or ancestor churn): every previously lit chunk re-evaluates on the next flush - still-candidate
     *  chunks recompute (the parent-view light), the rest restore the base chunk. */
    @Override
    public void refreshLit() {
        resetPending = true;
        markColumns(Set.copyOf(lit), true);
    }

    private void markColumns(Set<Long> chunks, boolean immediate) {
        if (closed) return;
        dirtyColumns.addAll(chunks);
        arm(immediate);
    }

    private void arm(boolean immediate) {
        long t = clock;
        if (immediate) {
            armedAt = t;
            flushAt = t;
        } else if (armedAt < 0) {
            armedAt = t;
            flushAt = t + DELAY;
        } else {
            flushAt = Math.min(t + DELAY, armedAt + MAX_DEFER);
        }
    }

    private void onTick(long shardTick) {
        long t = ++clock; // single writer: the shard's own tick driver
        if (closed || t < flushAt || (dirtySections.isEmpty() && dirtyColumns.isEmpty() && !resetPending)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        Set<Long> sections = drain(dirtySections);
        Set<Long> columns = drain(dirtyColumns);
        boolean reset = resetPending;
        resetPending = false;
        armedAt = -1;
        flushAt = Long.MAX_VALUE;
        Thread.startVirtualThread(() -> {
            try {
                flush(sections, columns, reset);
            } catch (Throwable e) {
                LOGGER.warn("shard light flush failed", e);
            } finally {
                inFlight.set(false);
                if (!dirtySections.isEmpty() || !dirtyColumns.isEmpty() || resetPending) {
                    long now = clock; // work landed mid-flight: catch up next tick
                    armedAt = now;
                    flushAt = now;
                }
            }
        });
    }

    private static Set<Long> drain(Set<Long> from) {
        Set<Long> out = new HashSet<>();
        for (var it = from.iterator(); it.hasNext(); ) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    // ---- the flush: one pass of Minestom's own section engine over our light state ----

    private void flush(Set<Long> sections, Set<Long> fullColumns, boolean reset) {
        if (closed) return;
        if (reset) {
            blockLights.clear();
            skyLights.clear();
            knownColumns.clear();
            cache.clear();
        }
        Instance base = shard.instance();
        DimensionType dim = base.getCachedDimensionType();
        int minSection = dim.minY() >> 4, maxSection = dim.maxY() >> 4;

        if (reset) restoreNonCandidates();

        // a section mark in a column that never computed lights like a sighting: nothing exists to patch
        for (var it = sections.iterator(); it.hasNext(); ) {
            long key = it.next();
            long column = CoordConversion.chunkIndex(ShardLighting.sectionKeyX(key), ShardLighting.sectionKeyZ(key));
            if (!knownColumns.contains(column)) {
                fullColumns.add(column);
                it.remove();
            }
        }

        Set<Long> involved = expand(fullColumns);
        Set<Long> markColumns = new HashSet<>();
        for (long key : sections) {
            markColumns.add(CoordConversion.chunkIndex(ShardLighting.sectionKeyX(key), ShardLighting.sectionKeyZ(key)));
        }
        involved.addAll(expand(markColumns)); // the neighborhood seeds reach one chunk out
        Set<Long> seedable = new HashSet<>();
        for (long index : involved) {
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            if (!base.isChunkLoaded(cx, cz)) {
                // a write reached an unloaded chunk (reverse playback, far edits): its cached light is stale
                // now - drop it so the chunk-load path recomputes instead of serving the old packet
                cache.remove(index);
                lit.remove(index);
                continue;
            }
            if (candidate(cx, cz)) {
                seedable.add(index);
            } else if (lit.contains(index)) {
                // the write REMOVED the area's last diff: no longer a candidate, so the recompute below would
                // skip it and the deleted source's light would linger - restore the base chunk instead
                restoreBase(index);
            }
        }
        if (seedable.isEmpty() || closed) return;

        Ctx ctx = new Ctx(base, dim, minSection, maxSection);
        Set<Sec> blockSeeds = new HashSet<>(), skySeeds = new HashSet<>();
        for (long index : expand(fullColumns)) {
            if (!seedable.contains(index)) continue;
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            for (int sy = minSection; sy < maxSection; sy++) {
                Sec s = new Sec(cx, sy, cz);
                blockSeeds.add(s);
                skySeeds.add(s);
            }
        }
        // a section write reaches at most one section of light in any direction; sky additionally
        // re-propagates the whole shaft when the write can touch a column surface
        Map<Long, Integer> lowestSurface = new HashMap<>();
        for (long key : sections) {
            int cx = ShardLighting.sectionKeyX(key), sy = ShardLighting.sectionKeyY(key),
                    cz = ShardLighting.sectionKeyZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!seedable.contains(CoordConversion.chunkIndex(cx + dx, cz + dz))) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = sy + dy;
                        if (ny < minSection || ny >= maxSection) continue;
                        Sec s = new Sec(cx + dx, ny, cz + dz);
                        blockSeeds.add(s);
                        skySeeds.add(s);
                    }
                }
            }
            if (ctx.hasSky && seedable.contains(CoordConversion.chunkIndex(cx, cz))) {
                int surface = lowestSurface.computeIfAbsent(CoordConversion.chunkIndex(cx, cz), k -> {
                    int min = Integer.MAX_VALUE;
                    for (int h : ctx.occlusion(cx, cz)) min = Math.min(min, h);
                    return min;
                });
                if ((sy << 4) + 16 >= surface) {
                    for (int y2 = minSection; y2 <= sy; y2++) skySeeds.add(new Sec(cx, y2, cz));
                }
            }
        }
        if (!ctx.hasSky) skySeeds.clear();
        // REPLACE, never invalidate: SkyLight fullyLit latch survives invalidate() and short-circuits
        // calculateInternal to CONTENT_FULLY_LIT forever - a once-lit section could never darken (a
        // built roof left the shaft below it fully bright)
        for (Sec s : blockSeeds) blockLights.put(s, Light.block());
        for (Sec s : skySeeds) skyLights.put(s, Light.sky());
        Set<Long> publish = new HashSet<>();
        for (Sec s : blockSeeds) publish.add(CoordConversion.chunkIndex(s.x(), s.z()));
        for (Sec s : skySeeds) publish.add(CoordConversion.chunkIndex(s.x(), s.z()));
        knownColumns.addAll(publish);
        runQueue(blockSeeds, blockLights, ctx);
        if (ctx.hasSky) runQueue(skySeeds, skyLights, ctx);
        if (closed) return;

        boolean viewers = shard.hasBlockViewers();
        for (long index : publish) {
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            UpdateLightPacket packet = new UpdateLightPacket(cx, cz, lightData(cx, cz, ctx));
            cache.put(index, packet);
            if (viewers) {
                shard.sendToBlockViewers(packet);
                lit.add(index);
            }
        }
    }

    /** Reset path: a previously lit chunk the chain no longer touches reverts to the base chunk wholesale. */
    private void restoreNonCandidates() {
        for (long index : Set.copyOf(lit)) {
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            if (candidate(cx, cz)) continue;
            restoreBase(index);
        }
    }

    private void restoreBase(long index) {
        knownColumns.remove(index);
        lit.remove(index);
        cache.remove(index);
        int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
        // level() reads by Sec: a stale entry would keep serving the deleted-source light after the restore
        blockLights.keySet().removeIf(s -> s.x() == cx && s.z() == cz);
        skyLights.keySet().removeIf(s -> s.x() == cx && s.z() == cz);
        ShardLighting.restoreBase(shard, index);
    }

    /** {@code LightingChunk.flushQueue}, sequential: one INTERNAL pass, then EXTERNAL passes until borders settle. */
    private void runQueue(Set<Sec> initial, Map<Sec, Light> lights, Ctx ctx) {
        Set<Sec> queue = initial;
        boolean internal = true;
        while (!queue.isEmpty()) {
            Set<Point> toUpdate = new HashSet<>();
            for (Sec s : queue) {
                Light light = lights.get(s);
                if (light == null) continue; // only this shard's candidate sections recompute
                Palette palette = ctx.palette(s);
                Chunk baseChunk = ctx.chunk(s.x(), s.z());
                if (baseChunk != null) baseChunk.lockReadLock();
                try {
                    toUpdate.addAll(internal
                            ? light.calculateInternal(palette, s.x(), s.y(), s.z(),
                                    ctx.occlusion(s.x(), s.z()), ctx.dim.maxY(), ctx.lookup(lights))
                            : light.calculateExternal(palette, ctx.neighbors(s, lights), ctx.lookup(lights), ctx::paletteAt));
                } finally {
                    if (baseChunk != null) baseChunk.unlockReadLock();
                }
                light.flip();
            }
            queue = new HashSet<>();
            for (Point p : toUpdate) {
                Sec s = new Sec(p.blockX(), p.blockY(), p.blockZ());
                if (lights.containsKey(s)) queue.add(s);
            }
            internal = false;
        }
    }

    /** Flush-local context: base access, transient palettes for diff-touched sections, chain occlusion maps. */
    private final class Ctx {
        final Instance base;
        final DimensionType dim;
        final int minSection, maxSection;
        final boolean hasSky;
        final Map<Long, Map<Point, Block>> diffs = new HashMap<>();
        final Map<Sec, Palette> palettes = new HashMap<>();
        final Map<Long, int[]> occlusions = new HashMap<>();

        Ctx(Instance base, DimensionType dim, int minSection, int maxSection) {
            this.base = base;
            this.dim = dim;
            this.minSection = minSection;
            this.maxSection = maxSection;
            this.hasSky = dim.hasSkylight();
        }

        @Nullable Chunk chunk(int cx, int cz) {
            return base.getChunk(cx, cz);
        }

        Map<Point, Block> diff(int cx, int cz) {
            return diffs.computeIfAbsent(CoordConversion.chunkIndex(cx, cz), k -> {
                Map<Point, Block> diff = new HashMap<>();
                shard.collectChunk(cx, cz, diff);
                return diff;
            });
        }

        /** The chain-effective palette: base by REFERENCE where untouched; a transient clone + diff where touched. */
        Palette palette(Sec s) {
            return palettes.computeIfAbsent(s, key -> {
                Chunk chunk = chunk(key.x(), key.z());
                Palette basePalette = chunk != null && inRange(key.y()) ? chunk.getSection(key.y()).blockPalette() : null;
                int sectionMinY = key.y() << 4;
                List<Map.Entry<Point, Block>> touched = new ArrayList<>();
                for (Map.Entry<Point, Block> e : diff(key.x(), key.z()).entrySet()) {
                    int y = e.getKey().blockY();
                    if (y >= sectionMinY && y < sectionMinY + 16) touched.add(e);
                }
                if (touched.isEmpty()) return basePalette != null ? basePalette : Palette.blocks();
                Palette copy;
                if (basePalette != null) {
                    chunk.lockReadLock();
                    try {
                        copy = basePalette.clone();
                    } finally {
                        chunk.unlockReadLock();
                    }
                } else {
                    copy = Palette.blocks();
                }
                for (Map.Entry<Point, Block> e : touched) {
                    Point pos = e.getKey();
                    copy.set(pos.blockX() & 15, pos.blockY() & 15, pos.blockZ() & 15, e.getValue().stateId());
                }
                return copy;
            });
        }

        @Nullable Palette paletteAt(int cx, int sy, int cz) {
            if (!inRange(sy)) return null;
            Chunk chunk = chunk(cx, cz);
            if (chunk == null && diff(cx, cz).isEmpty()) return null;
            return palette(new Sec(cx, sy, cz));
        }

        /** Our light where we hold the section, else a read-only view of the BASE section's light (border seeds). */
        Light.LightLookup lookup(Map<Sec, Light> lights) {
            return (x, y, z) -> {
                Light ours = lights.get(new Sec(x, y, z));
                if (ours != null) return ours;
                if (!inRange(y)) return null;
                Chunk chunk = chunk(x, z);
                if (chunk == null) return null;
                Light baseLight = lights == blockLights ? chunk.getSection(y).blockLight() : chunk.getSection(y).skyLight();
                // wrapped: the engine mutates neighbors it recognizes as its own impls - base light must stay untouched
                return new BaseLightView(baseLight);
            };
        }

        Point[] neighbors(Sec s, Map<Sec, Light> lights) {
            Point[] links = new Point[DIRECTIONS.length];
            for (Direction direction : DIRECTIONS) {
                int x = s.x() + direction.normalX(), z = s.z() + direction.normalZ();
                int y = s.y() + direction.normalY();
                if (!inRange(y)) continue;
                if (lights.containsKey(new Sec(x, y, z)) || chunk(x, z) != null) {
                    links[direction.ordinal()] = new BlockVec(x, y, z);
                }
            }
            return links;
        }

        /** Chain-effective sky occlusion column map ({@code LightingChunk.getOcclusionData}); base's own where untouched. */
        int[] occlusion(int cx, int cz) {
            return occlusions.computeIfAbsent(CoordConversion.chunkIndex(cx, cz), k -> {
                Chunk chunk = chunk(cx, cz);
                Map<Point, Block> diff = diff(cx, cz);
                if (diff.isEmpty() && chunk instanceof LightingChunk lighting) {
                    lighting.lockReadLock();
                    try {
                        return lighting.getOcclusionMap();
                    } finally {
                        lighting.unlockReadLock();
                    }
                }
                // bucket the diff by column once: the walks below probe it by Y, never by allocated point
                Map<Integer, Map<Integer, Block>> columns = new HashMap<>();
                for (Map.Entry<Point, Block> e : diff.entrySet()) {
                    Point p = e.getKey();
                    columns.computeIfAbsent((p.blockZ() & 15) << 4 | (p.blockX() & 15), c -> new HashMap<>())
                            .put(p.blockY(), e.getValue());
                }
                int minY = dim.minY(), maxY = dim.maxY();
                if (chunk != null) chunk.lockReadLock(); // the walks read base blocks (reentrant under runQueue)
                try {
                    if (chunk instanceof LightingChunk lighting) {
                        // patch the base map (a transient clone, dies with this flush): only diffed
                        // columns re-walk, from just above the higher of base height / diff top
                        int[] map = lighting.getOcclusionMap().clone();
                        for (Map.Entry<Integer, Map<Integer, Block>> col : columns.entrySet()) {
                            int key = col.getKey();
                            int top = minY - 1;
                            for (int y : col.getValue().keySet()) top = Math.max(top, y);
                            map[key] = columnHeight(chunk, col.getValue(), (cx << 4) + (key & 15),
                                    (cz << 4) + (key >> 4), Math.max(map[key], top + 1) - 1, minY);
                        }
                        return map;
                    }
                    int[] map = new int[16 * 16];
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            map[z << 4 | x] = columnHeight(chunk, columns.get(z << 4 | x),
                                    (cx << 4) + x, (cz << 4) + z, maxY - 1, minY);
                        }
                    }
                    return map;
                } finally {
                    if (chunk != null) chunk.unlockReadLock();
                }
            });
        }

        boolean inRange(int sectionY) {
            return sectionY >= minSection && sectionY < maxSection;
        }
    }

    // indexed by Direction ordinal, like LightCompute.DIRECTIONS
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Highest occluder + 1 in one column, walking diff-over-base from {@code from} down. */
    private static int columnHeight(@Nullable Chunk chunk, @Nullable Map<Integer, Block> colDiff,
                                    int gx, int gz, int from, int minY) {
        int height = from;
        while (height >= minY) {
            Block block = colDiff != null ? colDiff.get(height) : null;
            if (block == null && chunk != null) block = chunk.getBlock(gx, height, gz, Block.Getter.Condition.TYPE);
            if (block == null) block = Block.AIR;
            if (checkSkyOcclusion(block)) break;
            height--;
        }
        return height + 1;
    }

    // LightingChunk.checkSkyOcclusion (private upstream)
    private static boolean checkSkyOcclusion(Block block) {
        if (block == Block.AIR) return false;
        if (DIFFUSE_SKY_LIGHT.contains(block.key())) return true;
        Shape shape = block.registry().occlusionShape();
        boolean occludesTop = Block.AIR.registry().occlusionShape().isOccluded(shape, BlockFace.TOP);
        boolean occludesBottom = Block.AIR.registry().occlusionShape().isOccluded(shape, BlockFace.BOTTOM);
        return occludesBottom || occludesTop;
    }

    /** Read-only {@link Light} over a base section: border seeds read through, the engine's own-impl mutations skip it. */
    private record BaseLightView(Light delegate) implements Light {
        @Override public boolean requiresSend() { return false; }
        @Override public byte[] array() { return delegate.array(); }
        @Override public void flip() {}
        @Override public int getLevel(int x, int y, int z) { return delegate.getLevel(x, y, z); }
        @Override public void invalidate() {}
        @Override public boolean requiresUpdate() { return false; }
        @Override public void set(byte[] copyArray) {}
        @Override public Set<Point> calculateInternal(Palette blockPalette, int chunkX, int chunkY, int chunkZ,
                                                      int[] heightmap, int maxY, LightLookup lightLookup) {
            throw new IllegalStateException("base sections never recompute here");
        }
        @Override public Set<Point> calculateExternal(Palette blockPalette, Point[] neighbors,
                                                      LightLookup lightLookup, PaletteLookup paletteLookup) {
            throw new IllegalStateException("base sections never recompute here");
        }
    }

    /** Masks only OUR sections (the client keeps base light everywhere else); layout of {@code LightingChunk.createLightData}. */
    private LightData lightData(int cx, int cz, Ctx ctx) {
        BitSet skyMask = new BitSet(), blockMask = new BitSet();
        BitSet emptySkyMask = new BitSet(), emptyBlockMask = new BitSet();
        List<byte[]> skyLights = new ArrayList<>(), blockLights = new ArrayList<>();
        int index = 0;
        for (int sy = ctx.minSection; sy < ctx.maxSection; sy++) {
            index++;
            Sec s = new Sec(cx, sy, cz);
            if (ctx.hasSky) {
                Light sky = this.skyLights.get(s);
                if (sky != null) {
                    byte[] arr = sky.array();
                    if (arr.length != 0 && arr != LightCompute.EMPTY_CONTENT) {
                        skyLights.add(arr);
                        skyMask.set(index);
                    } else {
                        emptySkyMask.set(index);
                    }
                }
            }
            Light block = this.blockLights.get(s);
            if (block != null) {
                byte[] arr = block.array();
                if (arr.length != 0 && arr != LightCompute.EMPTY_CONTENT) {
                    blockLights.add(arr);
                    blockMask.set(index);
                } else {
                    emptyBlockMask.set(index);
                }
            }
        }
        return new LightData(skyMask, blockMask, emptySkyMask, emptyBlockMask, skyLights, blockLights);
    }

    // ---- view routing ----

    @Override
    public int blockLight(int x, int y, int z) {
        return level(blockLights, x, y, z);
    }

    @Override
    public int skyLight(int x, int y, int z) {
        return level(skyLights, x, y, z);
    }

    /** As of the last flush (the wire's own coalescing window); a candidate miss self-heals like {@link #sendTo}. */
    private int level(Map<Sec, Light> lights, int x, int y, int z) {
        Light light = lights.get(new Sec(x >> 4, y >> 4, z >> 4));
        if (light == null) {
            int cx = x >> 4, cz = z >> 4;
            if (candidate(cx, cz) && shard.instance().isChunkLoaded(cx, cz)) {
                markColumns(Set.of(CoordConversion.chunkIndex(cx, cz)), true);
            }
            return -1;
        }
        return light.getLevel(CoordConversion.globalToSectionRelative(x),
                CoordConversion.globalToSectionRelative(y), CoordConversion.globalToSectionRelative(z));
    }

    /** The published packet, or {@code null} - a candidate miss self-heals on the next flush. */
    @Override
    public @Nullable UpdateLightPacket packet(int chunkX, int chunkZ) {
        if (!candidate(chunkX, chunkZ) || !shard.instance().isChunkLoaded(chunkX, chunkZ)) return null;
        long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        UpdateLightPacket packet = cache.get(index);
        if (packet == null) markColumns(Set.of(index), true);
        return packet;
    }

    @Override
    public Set<Long> litChunks() {
        return lit;
    }

    @Override
    public void markLit(long chunkIndex) {
        lit.add(chunkIndex);
    }

    /** Loaded chunks where this chain's light can differ from base: diff-touched chunks + the light-bleed ring. */
    @Override
    public Set<Long> candidateChunks() {
        return expand(ShardLighting.chainChunks(shard));
    }

    private static Set<Long> expand(Set<Long> chunks) {
        Set<Long> out = new HashSet<>();
        for (long index : chunks) {
            int chunkX = CoordConversion.chunkIndexGetX(index), chunkZ = CoordConversion.chunkIndexGetZ(index);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) out.add(CoordConversion.chunkIndex(chunkX + dx, chunkZ + dz));
            }
        }
        return out;
    }

    // the chain diff can only change light within one chunk of where it touches
    private boolean candidate(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Shard s = shard; s != null; s = s.parent()) {
                    if (s.overlayTouches(chunkX + dx, chunkZ + dz)) return true;
                }
            }
        }
        return false;
    }
}
