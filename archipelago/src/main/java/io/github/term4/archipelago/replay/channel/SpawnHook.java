package io.github.term4.archipelago.replay.channel;

import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adjusts a replayed entity before it spawns - how a library polishes its own revived entities (mark projectiles
 * footage-only, hand ownership to the shooter's ghost). Register via
 * {@link io.github.term4.archipelago.replay.Replay#onSpawn}.
 */
public interface SpawnHook {

    /** @param owner the recorded launcher's ghost, or {@code null} (no shooter, or its ghost hasn't spawned) */
    void enhance(@NotNull Entity entity, @Nullable Ghost owner, @NotNull Playback playback);
}
