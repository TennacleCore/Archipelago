package io.github.term4.shardkit.replay.core;

import io.github.term4.shardkit.shard.Shard;
import io.github.term4.shardkit.storage.Nbt;
import io.github.term4.shardkit.replay.Recording;
import io.github.term4.shardkit.replay.channel.Dispatcher;
import io.github.term4.shardkit.replay.channel.EventDispatcher;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerCancelDiggingEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** The core recording sources, one set per {@link Recording}. */
final class Probes {

    private Probes() {}

    static List<Supplier<Dispatcher>> core() {
        return List.of(LayerSampler::new, ActorSampler::new, EntitySampler::new, BlockProbe::new, ActProbe::new,
                LifeProbe::new, DigProbe::new, PickupProbe::new);
    }

    /** Tree sweep: layers register on sight (create op + relative-diff dump), vanished ones report destroyed. */
    static final class LayerSampler implements Dispatcher {
        private final Set<Shard> known = new HashSet<>();

        @Override
        public void start(@NotNull Recording rec) {
            sample(rec); // layers existing at t0 land at tick 0
        }

        @Override
        public void sample(@NotNull Recording rec) {
            Set<Shard> live = new HashSet<>(rec.layers());
            for (Shard layer : live) rec.layer(layer);
            known.removeIf(layer -> {
                if (live.contains(layer)) return false;
                rec.layerGone(layer);
                return true;
            });
            known.addAll(live);
        }
    }

    /** Per-tick member sampling across the tree: position track, skin at first sight, layer + equipment/pose diffs. */
    static final class ActorSampler implements Dispatcher {
        private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

        private final Map<String, ItemStack[]> lastEquip = new HashMap<>();
        private final Map<String, Boolean> lastSneak = new HashMap<>();
        private final Map<String, Boolean> lastUse = new HashMap<>();
        private final Map<String, Integer> lastArrows = new HashMap<>();
        private final Map<String, Boolean> lastViewable = new HashMap<>();
        private final Map<String, Integer> lastLayer = new HashMap<>();

        private static <T> boolean changed(Map<String, T> last, String name, T now) {
            return !now.equals(last.put(name, now));
        }

        @Override
        public void sample(@NotNull Recording rec) {
            for (Shard layer : rec.layers()) {
                int layerId = rec.layer(layer);
                for (Player m : layer.members()) sample(rec, m, layerId);
            }
        }

        private void sample(Recording rec, Player m, int layerId) {
            String name = m.getUsername();
            Pos pos = m.getPosition();
            rec.emit(Channels.MOVES, new Channels.Move(name, pos.x(), pos.y(), pos.z(),
                    pos.yaw(), pos.pitch(), m.isOnGround()));
            if (rec.consumeRefresh(name)) {
                // a respawned actor plays back as a FRESH ghost: drop the diff caches so its full
                // equipment/pose state re-emits this tick
                lastEquip.remove(name);
                lastSneak.remove(name);
                lastUse.remove(name);
                lastArrows.remove(name);
                lastViewable.remove(name);
                lastLayer.remove(name);
            }
            if (!lastEquip.containsKey(name)) { // first sight, or a refresh dropped the caches: skin re-emits
                PlayerSkin skin = m.getSkin(); // null on offline setups without forwarded profiles
                if (skin != null && skin.signature() != null) {
                    rec.emit(Channels.SKINS, new Channels.Skin(name, skin.textures(), skin.signature()));
                }
            }
            if (changed(lastLayer, name, layerId)) {
                rec.emit(Channels.ACTS, new Channels.Act(name, Channels.LAYER, layerId));
            }
            ItemStack[] items = new ItemStack[SLOTS.length];
            for (int i = 0; i < SLOTS.length; i++) items[i] = m.getEquipment(SLOTS[i]);
            if (!Arrays.equals(items, lastEquip.put(name, items))) {
                CompoundBinaryTag.Builder encoded = CompoundBinaryTag.builder();
                for (int i = 0; i < SLOTS.length; i++) {
                    if (!items[i].isAir()) encoded.put(SLOTS[i].name(), Nbt.item(items[i]));
                }
                rec.emit(Channels.EQUIPS, new Channels.Equip(name, encoded.build()));
            }
            boolean sneak = m.isSneaking();
            if (changed(lastSneak, name, sneak)) {
                rec.emit(Channels.ACTS, new Channels.Act(name, sneak ? Channels.SNEAK_ON : Channels.SNEAK_OFF, 0));
            }
            boolean use = m.isUsingItem();
            if (changed(lastUse, name, use)) {
                rec.emit(Channels.ACTS, new Channels.Act(name, use ? Channels.USE_ON : Channels.USE_OFF, 0));
            }
            int arrows = m.getEntityMeta() instanceof LivingEntityMeta lm ? lm.getArrowCount() : 0;
            if (changed(lastArrows, name, arrows)) {
                rec.emit(Channels.ACTS, new Channels.Act(name, Channels.ARROWS, arrows));
            }
            // observed, not assumed: hides (corpse cleanup, vanish) replay at the recorded moment
            boolean viewable = m.isAutoViewable();
            if (changed(lastViewable, name, viewable)) {
                rec.emit(Channels.ACTS, new Channels.Act(name, viewable ? Channels.SHOWN : Channels.HIDDEN, 0));
            }
        }
    }

    /** Per-tick entity sweep across the tree: new spawns, delta-gated position tracks, layer switches, removals. */
    static final class EntitySampler implements Dispatcher {
        private final Map<Entity, Pos> lastPos = new HashMap<>();
        private final Map<Entity, Integer> lastLayer = new HashMap<>();

        @Override
        public void sample(@NotNull Recording rec) {
            for (Shard layer : rec.layers()) {
                for (Entity e : layer.entities()) {
                    if (!(e instanceof Player)) rec.spawn(e, e.getPosition(), "");
                }
            }
            List<Entity> gone = new ArrayList<>();
            rec.forEachTracked((e, seq) -> {
                if (e.isRemoved()) {
                    gone.add(e);
                    return;
                }
                // position track over re-simulation: physics twins diverge (wall hits, water bobbing)
                Pos now = e.getPosition();
                Pos last = lastPos.put(e, now);
                if (last == null || last.distanceSquared(now) > 1.0e-4) {
                    rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(seq,
                            now.x(), now.y(), now.z(), now.yaw(), now.pitch()));
                }
                int layer = rec.layerOf(e);
                Integer wasIn = lastLayer.put(e, layer);
                if (wasIn != null && wasIn != layer) { // the spawn op carries the initial layer
                    rec.emit(Channels.ENTITY_LAYERS, new Channels.EntityLayer(seq, layer));
                }
            });
            for (Entity e : gone) { // the sweep fallback for removals no event reported (despawns, pickups)
                lastPos.remove(e);
                lastLayer.remove(e);
                rec.remove(e, e.getPosition());
            }
        }
    }

    static final class BlockProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerBlockPlaceEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(rec.layerOf(e.getPlayer()),
                        p.blockX(), p.blockY(), p.blockZ(), Nbt.blockString(e.getBlock())));
            });
            node.addListener(PlayerBlockBreakEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(rec.layerOf(e.getPlayer()),
                        p.blockX(), p.blockY(), p.blockZ(), Nbt.blockString(e.getResultBlock())));
            });
        }
    }

    static final class ActProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerHandAnimationEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                rec.emit(Channels.ACTS, new Channels.Act(e.getPlayer().getUsername(),
                        e.getHand() == PlayerHand.MAIN ? Channels.SWING_MAIN : Channels.SWING_OFF, 0));
            });
            node.addListener(EntityDamageEvent.class, e -> {
                if (e.getEntity() instanceof Player p && rec.covers(p)) {
                    rec.emit(Channels.ACTS, new Channels.Act(p.getUsername(), Channels.HURT, 0));
                }
            });
        }
    }

    static final class LifeProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerDeathEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                rec.emit(Channels.ACTS, new Channels.Act(e.getPlayer().getUsername(), Channels.DEATH, 0));
            });
            node.addListener(PlayerRespawnEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                rec.emit(Channels.ACTS, new Channels.Act(e.getPlayer().getUsername(), Channels.RESPAWN, 0));
                rec.refreshActor(e.getPlayer().getUsername());
            });
        }
    }

    static final class DigProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerStartDiggingEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.DIGS, new Channels.Dig(rec.layerOf(e.getPlayer()),
                        p.blockX(), p.blockY(), p.blockZ(), e.getPlayer().getUsername(), true));
            });
            node.addListener(PlayerCancelDiggingEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.DIGS, new Channels.Dig(rec.layerOf(e.getPlayer()),
                        p.blockX(), p.blockY(), p.blockZ(), e.getPlayer().getUsername(), false));
            });
        }
    }

    static final class PickupProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PickupItemEvent.class, e -> {
                if (rec.tracks(e.getItemEntity())) {
                    rec.collected(e.getItemEntity(),
                            e.getLivingEntity() instanceof Player p ? p.getUsername() : "");
                }
            });
        }
    }
}
