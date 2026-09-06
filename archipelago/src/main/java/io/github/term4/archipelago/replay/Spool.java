package io.github.term4.archipelago.replay;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A recording's closed segments, appended to one file as they close - a length-prefixed gzipped NBT compound
 * each - so a long game never holds its whole log on the heap, and each segment is a unit a later store can take
 * on its own. The first record is the header (the t0 snapshot and the app's data), so a spool whose process died
 * still makes a file ({@link Replay#salvage}). One writer thread lands them in call order; reads stream them
 * back the same way.
 */
final class Spool {

    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicInteger segments = new AtomicInteger();
    private volatile @Nullable IOException failure;

    Spool(@NotNull Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.deleteIfExists(file);
        this.file = file;
    }

    @NotNull Path file() {
        return file;
    }

    int segments() {
        return segments.get();
    }

    /** Encodes and appends off the caller's thread; a failed write surfaces at {@link #forEach}. */
    void append(@NotNull CompoundBinaryTag segment) {
        writer.execute(() -> {
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BinaryTagIO.writer().write(segment, bytes, BinaryTagIO.Compression.GZIP);
                DataOutputStream data = new DataOutputStream(out);
                data.writeInt(bytes.size());
                bytes.writeTo(data);
                data.flush();
                segments.incrementAndGet();
            } catch (IOException e) {
                failure = e;
            }
        });
    }

    /** Waits for every pending append; nothing appends after this. */
    void settle() throws IOException {
        writer.shutdown();
        try {
            writer.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the spool", e);
        }
        if (failure != null) throw failure;
    }

    /** {@link #settle}, then the records in order, the header first. */
    void forEach(@NotNull Consumer<CompoundBinaryTag> each) throws IOException {
        settle();
        read(file, each);
    }

    /** The records of {@code file} in order; a torn tail (the process died mid-append) ends the read. */
    static void read(@NotNull Path file, @NotNull Consumer<CompoundBinaryTag> each) throws IOException {
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException end) {
                    return;
                }
                byte[] blob = in.readNBytes(length);
                if (blob.length < length) return;
                each.accept(BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(blob), BinaryTagIO.Compression.GZIP));
            }
        }
    }

    void delete() throws IOException {
        settle();
        Files.deleteIfExists(file);
    }
}
