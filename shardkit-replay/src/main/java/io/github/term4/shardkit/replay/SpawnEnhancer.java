package io.github.term4.shardkit.replay;

import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adjusts a replayed entity before it spawns - how a library polishes its own revived entities (mark projectiles
 * footage-only, hand ownership to the shooter's ghost). Register via {@link Replay#enhanceSpawns}.
 *
 * @param owner the recorded launcher's ghost, or {@code null} (no shooter, or its ghost hasn't spawned)
 */
public interface SpawnEnhancer {

    void enhance(@NotNull Entity entity, @Nullable Ghost owner, @NotNull Playback playback);
}
