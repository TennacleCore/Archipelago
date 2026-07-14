package io.github.term4.archipelago.testsupport;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One headless {@link MinecraftServer} per JVM (registries + dispatcher, no socket) with a loaded flat stone
 * instance (surface y=63) shared by every test class.
 */
public abstract class HeadlessServerTest {

    protected static InstanceContainer instance;

    @BeforeAll
    static void boot() throws IOException {
        if (System.getProperty("archipelago.crash-dir") == null) { // fail-stop tests write crash reports
            System.setProperty("archipelago.crash-dir", Files.createTempDirectory("archipelago-crash-test").toString());
        }
        if (instance != null) return;
        MinecraftServer.init();
        // entity registration/ticking partition threads; lets setInstance(...).join() complete
        MinecraftServer.process().dispatcher().start();
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
    }

    /** Spin-waits (4s cap) for {@code done}, then asserts it. */
    protected static void await(String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertTrue(done.get(), what);
    }

    /** A stationary zombie placed at {@code pos}. */
    protected static LivingEntity zombie(Pos pos) {
        LivingEntity e = new LivingEntity(EntityType.ZOMBIE);
        e.setInstance(instance, pos).join();
        return e;
    }
}
