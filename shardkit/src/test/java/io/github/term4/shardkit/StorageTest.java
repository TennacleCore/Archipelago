package io.github.term4.shardkit;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Per key: programmatic set > {@code shardkit.<key>} system property > shardkit.properties > default. */
class StorageTest {

    @Test
    void resolutionOrderIsSetThenPropertyThenDefault() {
        assertEquals(Path.of("fallback"), Storage.dir("storage-test", "fallback"));
        System.setProperty("shardkit.storage-test", "by-prop");
        try {
            assertEquals(Path.of("by-prop"), Storage.dir("storage-test", "fallback"));
            Storage.set("storage-test", Path.of("by-code"));
            assertEquals(Path.of("by-code"), Storage.dir("storage-test", "fallback"), "code config wins");
        } finally {
            System.clearProperty("shardkit.storage-test");
        }
    }
}
