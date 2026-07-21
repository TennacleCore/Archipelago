package io.github.term4.archipelago.storage;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Transcoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@link DataKey typed user-data} section every shard container format carries. */
public final class UserData {

    private UserData() {}

    /** Collects {@link DataKey} entries at save time (values Codec-encode to NBT immediately). */
    public static final class Writer {
        private final CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();

        public <T> @NotNull Writer put(@NotNull DataKey<T> key, @NotNull T value) {
            tags.put(key.id(), key.codec().encode(Transcoder.NBT, value).orElseThrow());
            return this;
        }

        public @NotNull CompoundBinaryTag build() {
            return tags.build();
        }
    }

    /** The saved user data of a loaded file; {@link #get} decodes on access ({@code null} when absent). */
    public static final class View {
        public static final View EMPTY = new View(CompoundBinaryTag.empty());

        private final CompoundBinaryTag tags;

        public View(@NotNull CompoundBinaryTag tags) {
            this.tags = tags;
        }

        public <T> @Nullable T get(@NotNull DataKey<T> key) {
            BinaryTag tag = tags.get(key.id());
            return tag == null ? null : key.codec().decode(Transcoder.NBT, tag).orElseThrow();
        }
    }
}
