package io.github.term4.shardkit.shard;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.light.LightCompute;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A shard's light: the chain-effective blocks relit with Minestom's own engine and sent as follow-up
 * {@link UpdateLightPacket}s (whatever light arrives last wins client-side - the per-viewer override channel;
 * one instance chunk can only carry ONE light state on the wire). Light is computed on a throwaway scratch
 * neighborhood (cloned base sections + the chain diff), cached per chunk, and invalidated by the same writes
 * that fan block changes. Modern-client polish: 1.8 clients relight locally from block data.
 */
final class ShardLight {

    private final Shard shard;
    private final Map<Long, UpdateLightPacket> cache = new ConcurrentHashMap<>();
    /** Chunks whose shard light reached at least one client - the restore set for swaps/resets. */
    private final Set<Long> lit = ConcurrentHashMap.newKeySet();

    ShardLight(Shard shard) {
        this.shard = shard;
    }

    /** This shard's light for one chunk to one viewer (chunk load / swap-in); no-op where the view cannot differ. */
    void sendTo(Player player, int chunkX, int chunkZ) {
        UpdateLightPacket packet = packet(chunkX, chunkZ);
        if (packet == null) return;
        player.sendPacket(packet);
        lit.add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /** Blocks changed in {@code chunks} (this shard's view): refresh those chunks + light-bleed neighbors to the block viewers. */
    void changed(Set<Long> chunks) {
        Set<Long> affected = expand(chunks);
        for (long index : affected) cache.remove(index);
        if (!shard.hasBlockViewers()) return;
        for (long index : affected) {
            int chunkX = CoordConversion.chunkIndexGetX(index), chunkZ = CoordConversion.chunkIndexGetZ(index);
            UpdateLightPacket packet = packet(chunkX, chunkZ);
            if (packet == null) continue;
            shard.sendToBlockViewers(packet);
            lit.add(index);
        }
    }

    /**
     * After a reset (or ancestor churn) re-sends every previously lit chunk: with the diff gone the recompute
     * IS the parent-view light; where nothing in the chain touches anymore, the base chunk restores instead.
     */
    void refreshLit() {
        cache.clear();
        for (long index : Set.copyOf(lit)) {
            int chunkX = CoordConversion.chunkIndexGetX(index), chunkZ = CoordConversion.chunkIndexGetZ(index);
            UpdateLightPacket packet = packet(chunkX, chunkZ);
            if (packet != null) {
                shard.sendToBlockViewers(packet);
            } else {
                lit.remove(index);
                Chunk base = shard.instance().getChunk(chunkX, chunkZ);
                if (base != null) shard.sendToBlockViewers(base.getFullDataPacket());
            }
        }
    }

    /**
     * A viewer's block view swapped {@code from -> to}: the new view's light replaces the old's wherever either
     * side lit, and chunks only the old side lit fall back to the base chunk (full resend restores base light).
     */
    static void onViewSwap(Player player, @Nullable Shard from, @Nullable Shard to) {
        ShardLight fromLight = from != null ? from.light() : null;
        ShardLight toLight = to != null ? to.light() : null;
        if (fromLight == null && toLight == null) return;
        Set<Long> chunks = new HashSet<>();
        if (fromLight != null) chunks.addAll(fromLight.lit);
        if (toLight != null) chunks.addAll(toLight.candidates());
        Instance base = (to != null ? to : from).instance();
        for (long index : chunks) {
            int chunkX = CoordConversion.chunkIndexGetX(index), chunkZ = CoordConversion.chunkIndexGetZ(index);
            UpdateLightPacket packet = toLight != null ? toLight.packet(chunkX, chunkZ) : null;
            if (packet != null) {
                player.sendPacket(packet);
                toLight.lit.add(index);
            } else if (fromLight != null && fromLight.lit.contains(index)) {
                Chunk chunk = base.getChunk(chunkX, chunkZ);
                if (chunk != null) player.sendPacket(chunk.getFullDataPacket());
            }
        }
    }

    /** Loaded chunks where this chain's light can differ from base: diff-touched chunks + the light-bleed ring. */
    private Set<Long> candidates() {
        Set<Long> touched = new HashSet<>();
        for (Shard s = shard; s != null; s = s.parent()) touched.addAll(s.overlayChunks());
        return expand(touched);
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

    private @Nullable UpdateLightPacket packet(int chunkX, int chunkZ) {
        if (!candidate(chunkX, chunkZ) || !shard.instance().isChunkLoaded(chunkX, chunkZ)) return null;
        return cache.computeIfAbsent(CoordConversion.chunkIndex(chunkX, chunkZ), index -> compute(chunkX, chunkZ));
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

    /**
     * Relights the 3x3 neighborhood on a throwaway instance: base sections cloned + chain diff applied, then
     * Minestom's own {@link LightingChunk#relight} (its locking, its pool, exact base-engine parity).
     */
    private UpdateLightPacket compute(int chunkX, int chunkZ) {
        Instance base = shard.instance();
        InstanceContainer scratch = new InstanceContainer(UUID.randomUUID(), base.getDimensionType(), new ScratchLoader(base));
        List<Chunk> chunks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (base.isChunkLoaded(chunkX + dx, chunkZ + dz)) {
                    chunks.add(scratch.loadChunk(chunkX + dx, chunkZ + dz).join());
                }
            }
        }
        LightingChunk.relight(scratch, chunks);
        return new UpdateLightPacket(chunkX, chunkZ, lightData(scratch.getChunk(chunkX, chunkZ)));
    }

    // serves cloned base chunks with the chain diff applied; unloaded base chunks stay absent (air)
    private final class ScratchLoader implements ChunkLoader {
        private final Instance base;

        ScratchLoader(Instance base) {
            this.base = base;
        }

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int cx, int cz) {
            Chunk baseChunk = base.getChunk(cx, cz);
            if (baseChunk == null) return null;
            List<Section> sections;
            baseChunk.lockReadLock();
            try {
                sections = baseChunk.getSections().stream().map(Section::clone).toList();
            } finally {
                baseChunk.unlockReadLock();
            }
            ScratchChunk copy = new ScratchChunk(instance, cx, cz, sections);
            Map<Point, Block> diff = new HashMap<>();
            shard.collectChunk(cx, cz, diff);
            if (!diff.isEmpty()) {
                copy.lockWriteLock();
                try {
                    diff.forEach((pos, block) -> copy.setBlock(pos.blockX(), pos.blockY(), pos.blockZ(), block));
                } finally {
                    copy.unlockWriteLock();
                }
            }
            return copy;
        }

        @Override
        public void saveChunk(Chunk chunk) {}
    }

    // reaches LightingChunk's protected sections ctor; works over any base chunk type (plain Dynamic included)
    private static final class ScratchChunk extends LightingChunk {
        ScratchChunk(Instance instance, int chunkX, int chunkZ, List<Section> sections) {
            super(instance, chunkX, chunkZ, sections);
        }
    }

    // the mask/array layout of LightingChunk.createLightData (1-based section masks); full chunk, no partial tracking
    private static LightData lightData(Chunk chunk) {
        BitSet skyMask = new BitSet(), blockMask = new BitSet();
        BitSet emptySkyMask = new BitSet(), emptyBlockMask = new BitSet();
        List<byte[]> skyLights = new ArrayList<>(), blockLights = new ArrayList<>();
        boolean hasSky = chunk.getInstance().getCachedDimensionType().hasSkylight();
        int index = 0;
        for (Section section : chunk.getSections()) {
            index++;
            if (hasSky) {
                byte[] sky = section.skyLight().array();
                if (sky.length != 0 && sky != LightCompute.EMPTY_CONTENT) {
                    skyLights.add(sky);
                    skyMask.set(index);
                } else {
                    emptySkyMask.set(index);
                }
            }
            byte[] block = section.blockLight().array();
            if (block.length != 0 && block != LightCompute.EMPTY_CONTENT) {
                blockLights.add(block);
                blockMask.set(index);
            } else {
                emptyBlockMask.set(index);
            }
        }
        return new LightData(skyMask, blockMask, emptySkyMask, emptyBlockMask, skyLights, blockLights);
    }
}
