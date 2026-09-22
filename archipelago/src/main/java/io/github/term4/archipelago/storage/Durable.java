package io.github.term4.archipelago.storage;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writing bytes so a crash cannot leave a half-file. Lives here because this is the lowest module that both
 * Bloom and the app already depend on; Barnacle keeps its own copy rather than take a library dependency.
 */
public final class Durable {

    private Durable() {}

    /**
     * Writes {@code bytes} to {@code target}, creating its directory, replacing any existing file, and leaving
     * no temp behind on either path.
     *
     * <p>The temp is unique per call: two saves onto one path must not share one. The force before the move is
     * what makes this durable rather than merely atomic - a rename cannot be seen half-done, but delayed
     * allocation can still leave the renamed target empty after a power cut, so the bytes have to reach the
     * disk first. {@code ATOMIC_MOVE} is unsupported on some network and container filesystems, where a plain
     * replace is the best available.
     */
    public static void write(@NotNull Path target, byte @NotNull [] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
