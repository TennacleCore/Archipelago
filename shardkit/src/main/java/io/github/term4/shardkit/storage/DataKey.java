package io.github.term4.shardkit.storage;

import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;

/**
 * A typed entry in a shard save's user-data section. Namespace the id ({@code "mygame:round"}); the codec is any
 * Minestom {@link Codec} ({@code Codec.INT}, {@code StructCodec.struct(...)} for records, ...).
 */
public record DataKey<T>(@NotNull String id, @NotNull Codec<T> codec) {}
