package io.github.term4.shardkit.replay;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.instance.block.Block;

/** The core channels: actor tracks, entity spawns/tracks/removals, blocks, digs. Registered at {@link Replay} init. */
final class Channels {

    private Channels() {}

    record Move(String player, double x, double y, double z, float yaw, float pitch, boolean ground) {}
    record Skin(String player, String textures, String signature) {}
    // items: slot name -> encoded stack, absent = air (all slots, armor included)
    record Equip(String player, CompoundBinaryTag items) {}
    record Act(String player, int kind, int value) {}
    // visual = the Visuals snapshot (always), desc = the entity's save descriptor (empty = none),
    // shooter = launcher username for the wire owner ("" = none)
    record Spawn(double x, double y, double z, float yaw, float pitch, int seq,
                 CompoundBinaryTag visual, CompoundBinaryTag desc, String shooter) {}
    record EntityMove(int seq, double x, double y, double z, float yaw, float pitch) {}
    // collector "" = plain despawn; a name = the pickup swoosh toward that ghost
    record Remove(int seq, String collector) {}
    record BlockOp(int x, int y, int z, String block) {}
    record Dig(int x, int y, int z, String digger, boolean start) {}

    static final int SWING_MAIN = 0, SWING_OFF = 1, HURT = 2, SNEAK_ON = 3, SNEAK_OFF = 4,
            USE_ON = 5, USE_OFF = 6, ARROWS = 7, DEATH = 8, RESPAWN = 9, HIDDEN = 10, SHOWN = 11;

    // moves run FIRST: they create the ghosts that same-tick spawns/equips/acts reference (a bobber cast on the
    // ghost's first tick needs the ghost's id as its wire owner); skins earlier still (ghost creation reads them)
    static final Recordable<Skin> SKINS = Recordable.of("replay:skin", 1, -30, StructCodec.struct(
                    "p", Codec.STRING, Skin::player, "t", Codec.STRING, Skin::textures,
                    "s", Codec.STRING, Skin::signature, Skin::new), Appliers.SkinApplier::new)
            .folding((s, op) -> s.latest(Channels.SKINS, op.op().player(), op));

    static final Recordable<Move> MOVES = Recordable.of("replay:move", 1, -20, StructCodec.struct(
                    "p", Codec.STRING, Move::player, "x", Codec.DOUBLE, Move::x, "y", Codec.DOUBLE, Move::y,
                    "z", Codec.DOUBLE, Move::z, "yaw", Codec.FLOAT, Move::yaw, "pitch", Codec.FLOAT, Move::pitch,
                    "g", Codec.BOOLEAN, Move::ground, Move::new), Appliers.MoveApplier::new)
            .folding((s, op) -> s.latest(Channels.MOVES, op.op().player(), op));

    static final Recordable<Spawn> SPAWNS = Recordable.of("replay:spawn", 2, -10, StructCodec.struct(
                    "x", Codec.DOUBLE, Spawn::x, "y", Codec.DOUBLE, Spawn::y, "z", Codec.DOUBLE, Spawn::z,
                    "yaw", Codec.FLOAT, Spawn::yaw, "pitch", Codec.FLOAT, Spawn::pitch,
                    "s", Codec.INT, Spawn::seq, "vis", Codec.NBT_COMPOUND, Spawn::visual,
                    "d", Codec.NBT_COMPOUND, Spawn::desc, "o", Codec.STRING, Spawn::shooter, Spawn::new),
                    Appliers.SpawnApplier::new)
            .folding((s, op) -> s.latest(Channels.SPAWNS, op.op().seq(), op));

    static final Recordable<EntityMove> ENTITY_MOVES = Recordable.of("replay:emove", 2, -5, StructCodec.struct(
                    "s", Codec.INT, EntityMove::seq, "x", Codec.DOUBLE, EntityMove::x,
                    "y", Codec.DOUBLE, EntityMove::y, "z", Codec.DOUBLE, EntityMove::z,
                    "yaw", Codec.FLOAT, EntityMove::yaw, "pitch", Codec.FLOAT, EntityMove::pitch, EntityMove::new),
                    Appliers.EntityMoveApplier::new)
            .folding((s, op) -> s.latest(Channels.ENTITY_MOVES, op.op().seq(), op));

    static final Recordable<BlockOp> BLOCKS = Recordable.of("replay:block", 1, 0, StructCodec.struct(
                    "x", Codec.INT, BlockOp::x, "y", Codec.INT, BlockOp::y, "z", Codec.INT, BlockOp::z,
                    "b", Codec.STRING, BlockOp::block, BlockOp::new), Appliers.BlockApplier::new)
            .folding((s, op) -> s.latest(Channels.BLOCKS, op.op().x() + ":" + op.op().y() + ":" + op.op().z(), op));

    static final Recordable<Equip> EQUIPS = Recordable.of("replay:equip", 1, 10, StructCodec.struct(
                    "p", Codec.STRING, Equip::player, "items", Codec.NBT_COMPOUND, Equip::items, Equip::new),
                    Appliers.EquipApplier::new)
            .folding((s, op) -> s.latest(Channels.EQUIPS, op.op().player(), op));

    static final Recordable<Act> ACTS = Recordable.of("replay:act", 1, 20, StructCodec.struct(
                    "p", Codec.STRING, Act::player, "k", Codec.INT, Act::kind, "v", Codec.INT, Act::value, Act::new),
                    Appliers.ActApplier::new)
            .folding((s, op) -> {
                // stateful kinds fold to their latest; swings/hurts are moments, not state - skipped on seek
                String group = switch (op.op().kind()) {
                    case SNEAK_ON, SNEAK_OFF -> "sneak";
                    case USE_ON, USE_OFF -> "use";
                    case ARROWS -> "arrows";
                    case DEATH, RESPAWN -> "life";
                    case HIDDEN, SHOWN -> "visible";
                    default -> null;
                };
                if (group != null) s.latest(Channels.ACTS, op.op().player() + ":" + group, op);
            });

    static final Recordable<Remove> REMOVES = Recordable.of("replay:remove", 1, 30, StructCodec.struct(
                    "s", Codec.INT, Remove::seq, "c", Codec.STRING, Remove::collector, Remove::new),
                    Appliers.RemoveApplier::new)
            .folding((s, op) -> {
                int seq = op.op().seq();
                if (s.has(Channels.SPAWNS, seq)) { // spawned inside the seek window: never materializes
                    s.drop(Channels.SPAWNS, seq);
                    s.drop(Channels.ENTITY_MOVES, seq);
                } else {
                    s.latest(Channels.REMOVES, seq, op);
                }
            });

    static final Recordable<Dig> DIGS = Recordable.of("replay:dig", 1, 40, StructCodec.struct(
            "x", Codec.INT, Dig::x, "y", Codec.INT, Dig::y, "z", Codec.INT, Dig::z,
            "p", Codec.STRING, Dig::digger, "s", Codec.BOOLEAN, Dig::start, Dig::new), Appliers.DigApplier::new);

    static void registerCore() {
        Replay.register(SKINS);
        Replay.register(MOVES);
        Replay.register(SPAWNS);
        Replay.register(ENTITY_MOVES);
        Replay.register(BLOCKS);
        Replay.register(EQUIPS);
        Replay.register(ACTS);
        Replay.register(REMOVES);
        Replay.register(DIGS);
    }

    // polar's palette entry format: minecraft:block[key=value,key=value]; parse back with ArgumentBlockState
    static String blockString(Block block) {
        if (block.properties().isEmpty()) return block.name();
        StringBuilder out = new StringBuilder(block.name()).append('[');
        for (var entry : block.properties().entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        out.setCharAt(out.length() - 1, ']');
        return out.toString();
    }
}
