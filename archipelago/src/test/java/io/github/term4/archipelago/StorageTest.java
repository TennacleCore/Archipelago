package io.github.term4.archipelago;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Per key: programmatic set > {@code archipelago.<key>} system property > archipelago.properties > default. */
class StorageTest {

    @Test
    void resolutionOrderIsSetThenPropertyThenDefault() {
        assertEquals(Path.of("fallback"), Storage.dir("storage-test", "fallback"));
        System.setProperty("archipelago.storage-test", "by-prop");
        try {
            assertEquals(Path.of("by-prop"), Storage.dir("storage-test", "fallback"));
            Storage.set("storage-test", Path.of("by-code"));
            assertEquals(Path.of("by-code"), Storage.dir("storage-test", "fallback"), "code config wins");
        } finally {
            System.clearProperty("archipelago.storage-test");
        }
    }
}
