package io.github.term4.archipelago.replay.core;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.channel.Applier;
import io.github.term4.archipelago.replay.channel.Recorded;
import net.minestom.server.entity.PlayerSkin;
import org.jetbrains.annotations.NotNull;

final class SkinApplier implements Applier<Channels.Skin> {
    @Override
    public void apply(@NotNull Playback pb, @NotNull Recorded<Channels.Skin> op) {
        pb.skin(op.op().player(), new PlayerSkin(op.op().textures(), op.op().signature()));
    }
}
