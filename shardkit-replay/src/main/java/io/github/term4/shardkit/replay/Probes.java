package io.github.term4.shardkit.replay;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Transcoder;
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
import java.util.List;
import java.util.Map;

/** The core recording sources, one set per {@link Recording}. */
final class Probes {

    private Probes() {}

    static List<Dispatcher> core() {
        return List.of(new ActorSampler(), new EntitySampler(), new BlockProbe(), new ActProbe(),
                new LifeProbe(), new DigProbe(), new PickupProbe());
    }

    /** Per-tick member sampling: position track, skin at first sight, equipment/pose diffs. */
    static final class ActorSampler implements Dispatcher {
        private final Map<String, ItemStack[]> lastEquip = new HashMap<>();
        private final Map<String, Boolean> lastSneak = new HashMap<>();
        private final Map<String, Boolean> lastUse = new HashMap<>();
        private final Map<String, Integer> lastArrows = new HashMap<>();
        private final Map<String, Boolean> lastViewable = new HashMap<>();

        @Override
        public void sample(@NotNull Recording rec) {
            for (Player m : rec.shard().members()) {
                String name = m.getUsername();
                Pos pos = m.getPosition();
                rec.emit(Channels.MOVES, new Channels.Move(name, pos.x(), pos.y(), pos.z(),
                        pos.yaw(), pos.pitch(), m.isOnGround()));
                if (rec.refreshActors.remove(name)) {
                    // a respawned actor plays back as a FRESH ghost: drop the diff caches so its full
                    // equipment/pose state re-emits this tick
                    lastEquip.remove(name);
                    lastSneak.remove(name);
                    lastUse.remove(name);
                    lastArrows.remove(name);
                    lastViewable.remove(name);
                }
                if (!lastEquip.containsKey(name)) {
                    PlayerSkin skin = m.getSkin(); // null on offline setups without forwarded profiles
                    if (skin != null && skin.signature() != null) {
                        rec.emit(Channels.SKINS, new Channels.Skin(name, skin.textures(), skin.signature()));
                    }
                }
                EquipmentSlot[] slots = EquipmentSlot.values();
                ItemStack[] items = new ItemStack[slots.length];
                for (int i = 0; i < slots.length; i++) items[i] = m.getEquipment(slots[i]);
                if (!Arrays.equals(items, lastEquip.put(name, items))) {
                    CompoundBinaryTag.Builder encoded = CompoundBinaryTag.builder();
                    for (int i = 0; i < slots.length; i++) {
                        if (!items[i].isAir()) {
                            encoded.put(slots[i].name(), ItemStack.CODEC.encode(Transcoder.NBT, items[i]).orElseThrow());
                        }
                    }
                    rec.emit(Channels.EQUIPS, new Channels.Equip(name, encoded.build()));
                }
                boolean sneak = m.isSneaking();
                if (!Boolean.valueOf(sneak).equals(lastSneak.put(name, sneak))) {
                    rec.emit(Channels.ACTS, new Channels.Act(name, sneak ? Channels.SNEAK_ON : Channels.SNEAK_OFF, 0));
                }
                boolean use = m.isUsingItem();
                if (!Boolean.valueOf(use).equals(lastUse.put(name, use))) {
                    rec.emit(Channels.ACTS, new Channels.Act(name, use ? Channels.USE_ON : Channels.USE_OFF, 0));
                }
                int arrows = m.getEntityMeta() instanceof LivingEntityMeta lm ? lm.getArrowCount() : 0;
                if (!Integer.valueOf(arrows).equals(lastArrows.put(name, arrows))) {
                    rec.emit(Channels.ACTS, new Channels.Act(name, Channels.ARROWS, arrows));
                }
                // observed, not assumed: a game hiding a player (corpse cleanup at ITS configured tick, vanish
                // features) replays at exactly the recorded moment
                boolean viewable = m.isAutoViewable();
                if (!Boolean.valueOf(viewable).equals(lastViewable.put(name, viewable))) {
                    rec.emit(Channels.ACTS, new Channels.Act(name, viewable ? Channels.SHOWN : Channels.HIDDEN, 0));
                }
            }
        }
    }

    /** Per-tick entity sweep: new spawns, delta-gated position tracks, removals (with pickup attribution). */
    static final class EntitySampler implements Dispatcher {
        private final Map<Entity, Pos> lastPos = new HashMap<>();

        @Override
        public void sample(@NotNull Recording rec) {
            for (Entity e : rec.shard().entities()) {
                if (!(e instanceof Player)) rec.spawn(e, e.getPosition(), "");
            }
            List<Entity> gone = null;
            for (var entry : rec.tracked.entrySet()) {
                Entity e = entry.getKey();
                if (e.isRemoved()) {
                    if (gone == null) gone = new ArrayList<>();
                    gone.add(e);
                    continue;
                }
                // position track over re-simulation: physics twins diverge (wall hits, water bobbing)
                Pos now = e.getPosition();
                Pos last = lastPos.put(e, now);
                if (last == null || last.distanceSquared(now) > 1.0e-4) {
                    rec.emit(Channels.ENTITY_MOVES, new Channels.EntityMove(entry.getValue(),
                            now.x(), now.y(), now.z(), now.yaw(), now.pitch()));
                }
            }
            if (gone == null) return;
            for (Entity e : gone) { // the sweep fallback for removals no event reported (despawns, pickups)
                lastPos.remove(e);
                rec.remove(e, e.getPosition(), rec.pickedUpBy.getOrDefault(e, ""));
            }
        }
    }

    static final class BlockProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerBlockPlaceEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(p.blockX(), p.blockY(), p.blockZ(),
                        Channels.blockString(e.getBlock())));
            });
            node.addListener(PlayerBlockBreakEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.BLOCKS, new Channels.BlockOp(p.blockX(), p.blockY(), p.blockZ(),
                        Channels.blockString(e.getResultBlock())));
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
                rec.refreshActors.add(e.getPlayer().getUsername());
            });
        }
    }

    static final class DigProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PlayerStartDiggingEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.DIGS, new Channels.Dig(p.blockX(), p.blockY(), p.blockZ(),
                        e.getPlayer().getUsername(), true));
            });
            node.addListener(PlayerCancelDiggingEvent.class, e -> {
                if (!rec.covers(e.getPlayer())) return;
                var p = e.getBlockPosition();
                rec.emit(Channels.DIGS, new Channels.Dig(p.blockX(), p.blockY(), p.blockZ(),
                        e.getPlayer().getUsername(), false));
            });
        }
    }

    static final class PickupProbe extends EventDispatcher {
        @Override
        protected void listen(@NotNull EventNode<Event> node, @NotNull Recording rec) {
            node.addListener(PickupItemEvent.class, e -> {
                if (rec.tracked.containsKey(e.getItemEntity())) {
                    rec.pickedUpBy.put(e.getItemEntity(),
                            e.getLivingEntity() instanceof Player p ? p.getUsername() : "");
                }
            });
        }
    }
}
