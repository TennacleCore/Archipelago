package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.MinecraftServer;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Positional FX re-broadcast: raw packet bytes off the recorded wire, played on crossing - both
 *  directions (one-shots, like swings). At fractional rates live crossings hold ONE timeline tick:
 *  the whole scene renders one tick behind the cursor, and an impact sound played at op time would
 *  beat its arrow there. Codec drift across server versions decodes to a drop, not a crash. */
final class FxApplier implements Applier<Channels.Fx> {
    private final HeldOps<Channels.Fx> held = new HeldOps<>(true);

    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Fx> op) {
        if (!held.hold(pb, op)) play(pb, op.op());
    }

    @Override
    public void unapply(@NotNull Playback pb, @NotNull Recorded<Channels.Fx> op) {
        apply(pb, op); // one-shots hold in reverse too (the scene lags the cursor both ways)
    }

    @Override
    public void tick(@NotNull Playback pb) {
        held.tick(pb, (p, op) -> play(p, op.op()));
    }

    @Override
    public boolean settled() {
        return held.settled();
    }

    private static void play(Playback pb, Channels.Fx fx) {
        ServerPacket packet = decode(fx);
        Shard in = pb.layer(0); // FX are positional and the tree overlays ONE space: root broadcast
        if (packet != null && in != null) in.broadcast(packet);
    }

    static @Nullable ServerPacket decode(Channels.Fx fx) {
        try {
            NetworkBuffer buffer = NetworkBuffer.wrap(fx.data(), 0, fx.data().length, MinecraftServer.process());
            return switch (fx.kind()) {
                case Channels.FX_PARTICLE -> ParticlePacket.SERIALIZER.read(buffer);
                case Channels.FX_SOUND -> SoundEffectPacket.SERIALIZER.read(buffer);
                case Channels.FX_EVENT -> WorldEventPacket.SERIALIZER.read(buffer);
                // the modern explosion's flash+boom ride THIS packet; its knockback field must not
                // shove replay viewers
                case Channels.FX_EXPLOSION -> {
                    ExplosionPacket boom = ExplosionPacket.SERIALIZER.read(buffer);
                    yield new ExplosionPacket(boom.center(), boom.radius(), boom.blockCount(), null,
                            boom.particle(), boom.sound(), boom.blockParticles());
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
