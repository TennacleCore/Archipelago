package io.github.term4.archipelago;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where archipelago writes persistent files. Per key, first hit wins: {@link #set programmatic} > system property
 * {@code archipelago.<key>} > {@code archipelago.properties} in the working directory > the caller's default.
 * The library's own key is {@code crash-dir}.
 */
public final class Storage {

    private static final Logger LOGGER = LoggerFactory.getLogger(Storage.class);
    private static final Map<String, Path> SET = new ConcurrentHashMap<>();
    private static volatile Properties file;

    private Storage() {}

    public static void set(@NotNull String key, @NotNull Path dir) {
        SET.put(key, dir);
    }

    /** The directory for {@code key} ({@code def} when unconfigured); creating it is the writer's job. */
    public static @NotNull Path dir(@NotNull String key, @NotNull String def) {
        Path set = SET.get(key);
        if (set != null) return set;
        String prop = System.getProperty("archipelago." + key);
        if (prop != null) return Path.of(prop);
        String fromFile = fileProperties().getProperty(key);
        return Path.of(fromFile != null ? fromFile : def);
    }

    private static Properties fileProperties() {
        Properties p = file;
        if (p != null) return p;
        p = new Properties();
        Path path = Path.of("archipelago.properties");
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                LOGGER.warn("archipelago.properties unreadable - using defaults", e);
            }
        }
        file = p; // benign race: same content either way
        return p;
    }
}
