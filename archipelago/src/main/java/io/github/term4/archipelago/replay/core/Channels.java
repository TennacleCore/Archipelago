package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.channel.Recordable;
import io.github.term4.archipelago.replay.track.EntityMoveTrack;
import io.github.term4.archipelago.replay.track.MoveTrack;
import io.github.term4.archipelago.replay.track.SpawnTrack;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;

/**
 * The built-in channel vocabulary: layer tree, actor tracks, entity spawns/tracks/removals, blocks, digs.
 * Registered by {@code CoreChannels}. Layer 0 is always the recorded root.
 */
public final class Channels {

    private Channels() {}

    // a child layer appearing (alive) or vanishing; its relative t0 diff rides layered block ops at the same tick
    public record Layer(int id, int parent, boolean alive) {}
    public record Move(String player, double x, double y, double z, float yaw, float pitch, boolean ground) {}
    public record Skin(String player, String textures, String signature) {}
    // items: slot name -> encoded stack, absent = air (all slots, armor included)
    public record Equip(String player, CompoundBinaryTag items) {}
    public record Act(String player, int kind, int value) {}
    // visual = the Visuals snapshot; shooter = launcher username for the wire owner ("" = none)
    public record Spawn(int layer, double x, double y, double z, float yaw, float pitch, int seq,
                 CompoundBinaryTag visual, String shooter) {}
    public record EntityMove(int seq, double x, double y, double z, float yaw, float pitch) {}
    // a tracked entity switching layers mid-track
    public record EntityLayer(int seq, int layer) {}
    // collector "" = plain despawn; a name = the pickup swoosh toward that ghost
    public record Remove(int seq, String collector) {}
    public record BlockOp(int layer, int x, int y, int z, String block) {}
    public record Dig(int layer, int x, int y, int z, String digger, boolean start) {}
    // a positional effect off the outgoing wire: kind selects the packet codec, data is its raw bytes
    public record Fx(int kind, byte[] data) {}
    // a layer's sky override state at change time (time -1 = inherit; weatherSet gates rain/thunder)
    public record Sky(int layer, long time, boolean weatherSet, float rain, float thunder) {}
    // a keyframe tick: every keyframed channel re-emitted full state here; backward seeks fold from the latest one
    public record Boundary(int interval) {}

    public static final int FX_PARTICLE = 0, FX_SOUND = 1, FX_EVENT = 2, FX_EXPLOSION = 3;

    public static final int SWING_MAIN = 0, SWING_OFF = 1, HURT = 2, SNEAK_ON = 3, SNEAK_OFF = 4,
            USE_ON = 5, USE_OFF = 6, ARROWS = 7, DEATH = 8, RESPAWN = 9, HIDDEN = 10, SHOWN = 11,
            LAYER = 12; // value = the actor's layer id

    // layers run first of all: they create the shards every layered op targets
    public static final Recordable<Layer> LAYERS = Recordable.of("replay:layer", 1, -40, StructCodec.struct(
                    "i", Codec.INT, Layer::id, "p", Codec.INT, Layer::parent,
                    "a", Codec.BOOLEAN, Layer::alive, Layer::new), LayerApplier::new)
            .folding((s, op) -> s.latest(Channels.LAYERS, op.op().id(), op));

    // moves run before spawns/equips/acts: they create the ghosts same-tick ops reference (a bobber cast on the
    // ghost's first tick needs the ghost's id as its wire owner); skins earlier still (ghost creation reads them)
    public static final Recordable<Skin> SKINS = Recordable.of("replay:skin", 1, -30, StructCodec.struct(
                    "p", Codec.STRING, Skin::player, "t", Codec.STRING, Skin::textures,
                    "s", Codec.STRING, Skin::signature, Skin::new), SkinApplier::new)
            .folding((s, op) -> s.latest(Channels.SKINS, op.op().player(), op));

    public static final Recordable<Sky> SKIES = Recordable.of("replay:sky", 1, -35, StructCodec.struct(
                    "l", Codec.INT, Sky::layer, "t", Codec.LONG, Sky::time,
                    "w", Codec.BOOLEAN, Sky::weatherSet, "r", Codec.FLOAT, Sky::rain,
                    "th", Codec.FLOAT, Sky::thunder, Sky::new), SkyApplier::new)
            .folding((s, op) -> s.latest(Channels.SKIES, op.op().layer(), op));

    // the boundary marker itself is transient: it only indexes where keyframed channels are fold-complete
    public static final Recordable<Boundary> BOUNDARIES = Recordable.of("replay:keyframe", 1, -50,
            StructCodec.struct("n", Codec.INT, Boundary::interval, Boundary::new), () -> (pb, op) -> {});

    public static final Recordable<Move> MOVES = Recordable.of("replay:move", 1, -20, StructCodec.struct(
                    "p", Codec.STRING, Move::player, "x", Codec.DOUBLE, Move::x, "y", Codec.DOUBLE, Move::y,
                    "z", Codec.DOUBLE, Move::z, "yaw", Codec.FLOAT, Move::yaw, "pitch", Codec.FLOAT, Move::pitch,
                    "g", Codec.BOOLEAN, Move::ground, Move::new), MoveApplier::new)
            .folding((s, op) -> s.latest(Channels.MOVES, op.op().player(), op))
            .keyframing()
            .packing(new MoveTrack());

    public static final Recordable<Spawn> SPAWNS = Recordable.of("replay:spawn", 3, -10, StructCodec.struct(
                    "l", Codec.INT, Spawn::layer,
                    "x", Codec.DOUBLE, Spawn::x, "y", Codec.DOUBLE, Spawn::y, "z", Codec.DOUBLE, Spawn::z,
                    "yaw", Codec.FLOAT, Spawn::yaw, "pitch", Codec.FLOAT, Spawn::pitch,
                    "s", Codec.INT, Spawn::seq, "vis", Codec.NBT_COMPOUND, Spawn::visual,
                    "o", Codec.STRING, Spawn::shooter, Spawn::new),
                    SpawnApplier::new)
            .folding((s, op) -> s.latest(Channels.SPAWNS, op.op().seq(), op))
            .packing(new SpawnTrack());

    public static final Recordable<EntityLayer> ENTITY_LAYERS = Recordable.of("replay:elayer", 1, -7,
                    StructCodec.struct("s", Codec.INT, EntityLayer::seq, "l", Codec.INT, EntityLayer::layer,
                    EntityLayer::new), EntityLayerApplier::new)
            .folding((s, op) -> s.latest(Channels.ENTITY_LAYERS, op.op().seq(), op));

    public static final Recordable<EntityMove> ENTITY_MOVES = Recordable.of("replay:emove", 2, -5, StructCodec.struct(
                    "s", Codec.INT, EntityMove::seq, "x", Codec.DOUBLE, EntityMove::x,
                    "y", Codec.DOUBLE, EntityMove::y, "z", Codec.DOUBLE, EntityMove::z,
                    "yaw", Codec.FLOAT, EntityMove::yaw, "pitch", Codec.FLOAT, EntityMove::pitch, EntityMove::new),
                    EntityMoveApplier::new)
            .folding((s, op) -> s.latest(Channels.ENTITY_MOVES, op.op().seq(), op))
            .keyframing()
            .packing(new EntityMoveTrack());

    public static final Recordable<BlockOp> BLOCKS = Recordable.of("replay:block", 2, 0, StructCodec.struct(
                    "l", Codec.INT, BlockOp::layer,
                    "x", Codec.INT, BlockOp::x, "y", Codec.INT, BlockOp::y, "z", Codec.INT, BlockOp::z,
                    "b", Codec.STRING, BlockOp::block, BlockOp::new), BlockApplier::new)
            .folding((s, op) -> s.latest(Channels.BLOCKS,
                    op.op().layer() + ":" + op.op().x() + ":" + op.op().y() + ":" + op.op().z(), op));

    public static final Recordable<Equip> EQUIPS = Recordable.of("replay:equip", 1, 10, StructCodec.struct(
                    "p", Codec.STRING, Equip::player, "items", Codec.NBT_COMPOUND, Equip::items, Equip::new),
                    EquipApplier::new)
            .folding((s, op) -> s.latest(Channels.EQUIPS, op.op().player(), op))
            .keyframing();

    public static final Recordable<Act> ACTS = Recordable.of("replay:act", 1, 20, StructCodec.struct(
                    "p", Codec.STRING, Act::player, "k", Codec.INT, Act::kind, "v", Codec.INT, Act::value, Act::new),
                    ActApplier::new)
            .folding((s, op) -> {
                // stateful kinds fold to their latest; swings/hurts are moments, not state - skipped on seek
                String group = switch (op.op().kind()) {
                    case SNEAK_ON, SNEAK_OFF -> "sneak";
                    case USE_ON, USE_OFF -> "use";
                    case ARROWS -> "arrows";
                    case DEATH, RESPAWN -> "life";
                    case HIDDEN, SHOWN -> "visible";
                    case LAYER -> "layer";
                    default -> null;
                };
                if (group != null) s.latest(Channels.ACTS, op.op().player() + ":" + group, op);
            });

    public static final Recordable<Remove> REMOVES = Recordable.of("replay:remove", 1, 30, StructCodec.struct(
                    "s", Codec.INT, Remove::seq, "c", Codec.STRING, Remove::collector, Remove::new),
                    RemoveApplier::new)
            .folding((s, op) -> {
                int seq = op.op().seq();
                if (s.has(Channels.SPAWNS, seq)) { // spawned inside the seek window: never materializes
                    s.drop(Channels.SPAWNS, seq);
                    s.drop(Channels.ENTITY_MOVES, seq);
                    s.drop(Channels.ENTITY_LAYERS, seq);
                } else {
                    s.latest(Channels.REMOVES, seq, op);
                }
            });

    public static final Recordable<Dig> DIGS = Recordable.of("replay:dig", 2, 40, StructCodec.struct(
            "l", Codec.INT, Dig::layer,
            "x", Codec.INT, Dig::x, "y", Codec.INT, Dig::y, "z", Codec.INT, Dig::z,
            "p", Codec.STRING, Dig::digger, "s", Codec.BOOLEAN, Dig::start, Dig::new), DigApplier::new);
    public static final Recordable<Fx> FX = Recordable.of("replay:fx", 1, 40, StructCodec.struct(
            "k", Codec.INT, Fx::kind, "d", Codec.BYTE_ARRAY, Fx::data, Fx::new), FxApplier::new);

    /** Whether {@link #fx} would record this packet - the cheap gate before any serialization. */
    public static boolean isFx(@NotNull ServerPacket packet) {
        return packet instanceof ParticlePacket || packet instanceof SoundEffectPacket
                || packet instanceof WorldEventPacket || packet instanceof ExplosionPacket;
    }

    /** Encodes a positional-FX packet into its channel op, or null (not a recordable FX kind). */
    public static @Nullable Fx fx(@NotNull ServerPacket packet) {
        int kind = switch (packet) {
            case ParticlePacket ignored -> FX_PARTICLE;
            case SoundEffectPacket ignored -> FX_SOUND;
            case WorldEventPacket ignored -> FX_EVENT;
            case ExplosionPacket ignored -> FX_EXPLOSION;
            default -> -1;
        };
        if (kind < 0) return null;
        byte[] data = NetworkBuffer.makeArray(buffer -> {
            switch (packet) {
                case ParticlePacket pk -> ParticlePacket.SERIALIZER.write(buffer, pk);
                case SoundEffectPacket pk -> SoundEffectPacket.SERIALIZER.write(buffer, pk);
                case WorldEventPacket pk -> WorldEventPacket.SERIALIZER.write(buffer, pk);
                case ExplosionPacket pk -> ExplosionPacket.SERIALIZER.write(buffer, pk);
                default -> throw new IllegalStateException();
            }
        }, MinecraftServer.process());
        return new Fx(kind, data);
    }
}
