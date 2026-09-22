package io.github.term4.archipelago.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Four copies of this write existed, three of them without the force; the properties they all claimed are here. */
class DurableTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Path> temps(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    @Test
    void aWriteReplacesAndLeavesNoTemp(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("map.dat");
        Durable.write(target, bytes("first"));
        assertArrayEquals(bytes("first"), Files.readAllBytes(target));

        Durable.write(target, bytes("second"));
        assertArrayEquals(bytes("second"), Files.readAllBytes(target), "an existing file is replaced whole");
        assertEquals(List.of(), temps(dir), "and nothing is left beside it");
    }

    /** The target's own directory need not exist yet - a first save of an arena creates it. */
    @Test
    void aMissingDirectoryIsCreated(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nested").resolve("deep").resolve("map.dat");
        Durable.write(target, bytes("made"));
        assertArrayEquals(bytes("made"), Files.readAllBytes(target));
    }

    /** What the unique temp is for: two saves onto one path must not write through each other. */
    @Test
    void concurrentWritesDoNotMix(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("contended.dat");
        byte[] a = bytes("a".repeat(8192));
        byte[] b = bytes("b".repeat(8192));
        int rounds = 40;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (byte[] payload : List.of(a, b)) {
            Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int i = 0; i < rounds; i++) Durable.write(target, payload);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "both writers finished");

        byte[] landed = Files.readAllBytes(target);
        assertTrue(java.util.Arrays.equals(landed, a) || java.util.Arrays.equals(landed, b),
                "the target is one writer's bytes whole, never a blend of both");
        assertEquals(List.of(), temps(dir), "and both cleaned up after themselves");
    }
}
