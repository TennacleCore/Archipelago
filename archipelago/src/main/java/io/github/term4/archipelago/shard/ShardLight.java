package io.github.term4.archipelago.shard;

import net.kyori.adventure.key.Key;
import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Shape;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
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
    /** Chunk columns written since the last flush (owner threads add, the flush drains). */
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
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
        if (!touched.isEmpty()) mark(touched, false);
    }

    @Override
    public void close() {
        closed = true; // an in-flight flush re-checks before publishing; a racing send loses at worst one packet
        unhook.run();
        for (long index : Set.copyOf(lit)) restoreBase(index);
        cache.clear();
        dirty.clear();
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
        if (candidate(chunkX, chunkZ) && shard.instance().isChunkLoaded(chunkX, chunkZ)) mark(Set.of(index), true);
    }

    /** Blocks changed in {@code chunks} (this shard's view): dirty-mark only - the flush recomputes off-thread. */
    @Override
    public void changed(Set<Long> chunks) {
        mark(chunks, false);
    }

    /** After a reset (or ancestor churn): every previously lit chunk re-evaluates on the next flush - still-candidate
     *  chunks recompute (the parent-view light), the rest restore the base chunk. */
    @Override
    public void refreshLit() {
        resetPending = true;
        mark(Set.copyOf(lit), true);
    }

    private void mark(Set<Long> chunks, boolean immediate) {
        if (closed) return;
        dirty.addAll(chunks);
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
        if (closed || t < flushAt || (dirty.isEmpty() && !resetPending)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        Set<Long> batch = new HashSet<>();
        for (var it = dirty.iterator(); it.hasNext(); ) {
            batch.add(it.next());
            it.remove();
        }
        boolean reset = resetPending;
        resetPending = false;
        armedAt = -1;
        flushAt = Long.MAX_VALUE;
        Thread.startVirtualThread(() -> {
            try {
                flush(batch, reset);
            } catch (Throwable e) {
                LOGGER.warn("shard light flush failed", e);
            } finally {
                inFlight.set(false);
                if (!dirty.isEmpty() || resetPending) { // work landed mid-flight: catch up next tick
                    long now = clock;
                    armedAt = now;
                    flushAt = now;
                }
            }
        });
    }

    // ---- the flush: one pass of Minestom's own section engine over our light state ----

    private void flush(Set<Long> batch, boolean reset) {
        if (closed) return;
        if (reset) {
            blockLights.clear();
            skyLights.clear();
            cache.clear();
        }
        Instance base = shard.instance();
        DimensionType dim = base.getCachedDimensionType();
        int minSection = dim.minY() >> 4, maxSection = dim.maxY() >> 4;

        if (reset) restoreNonCandidates();

        Set<Long> columns = new HashSet<>();
        for (long index : expand(batch)) {
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            if (!base.isChunkLoaded(cx, cz)) {
                // a write reached an unloaded chunk (reverse playback, far edits): its cached light is stale
                // now - drop it so the chunk-load path recomputes instead of serving the old packet
                cache.remove(index);
                lit.remove(index);
                continue;
            }
            if (candidate(cx, cz)) {
                columns.add(index);
            } else if (lit.contains(index)) {
                // the write REMOVED the area's last diff: no longer a candidate, so the recompute below would
                // skip it and the deleted source's light would linger - restore the base chunk instead
                restoreBase(index);
            }
        }
        if (columns.isEmpty() || closed) return;

        Ctx ctx = new Ctx(base, dim, minSection, maxSection);
        Set<Sec> queue = new HashSet<>();
        for (long index : columns) {
            int cx = CoordConversion.chunkIndexGetX(index), cz = CoordConversion.chunkIndexGetZ(index);
            for (int sy = minSection; sy < maxSection; sy++) queue.add(new Sec(cx, sy, cz));
        }
        for (Sec s : queue) {
            blockLights.computeIfAbsent(s, k -> Light.block()).invalidate();
            if (ctx.hasSky) skyLights.computeIfAbsent(s, k -> Light.sky()).invalidate();
        }
        runQueue(queue, blockLights, ctx);
        if (ctx.hasSky) runQueue(queue, skyLights, ctx);
        if (closed) return;

        boolean viewers = shard.hasBlockViewers();
        for (long index : columns) {
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
        lit.remove(index);
        cache.remove(index);
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
                if (diff(cx, cz).isEmpty() && chunk instanceof LightingChunk lighting) {
                    lighting.lockReadLock();
                    try {
                        return lighting.getOcclusionMap();
                    } finally {
                        lighting.unlockReadLock();
                    }
                }
                int[] map = new int[16 * 16];
                int minY = dim.minY(), maxY = dim.maxY();
                Map<Point, Block> diff = diff(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int gx = (cx << 4) + x, gz = (cz << 4) + z;
                        int height = maxY - 1;
                        while (height >= minY) {
                            Block block = diff.get(new Vec(gx, height, gz));
                            if (block == null && chunk != null) block = chunk.getBlock(gx, height, gz, Block.Getter.Condition.TYPE);
                            if (block == null) block = Block.AIR;
                            if (checkSkyOcclusion(block)) break;
                            height--;
                        }
                        map[z << 4 | x] = height + 1;
                    }
                }
                return map;
            });
        }

        boolean inRange(int sectionY) {
            return sectionY >= minSection && sectionY < maxSection;
        }
    }

    // indexed by Direction ordinal, like LightCompute.DIRECTIONS
    private static final Direction[] DIRECTIONS = Direction.values();

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
                mark(Set.of(CoordConversion.chunkIndex(cx, cz)), true);
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
        if (packet == null) mark(Set.of(index), true);
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
