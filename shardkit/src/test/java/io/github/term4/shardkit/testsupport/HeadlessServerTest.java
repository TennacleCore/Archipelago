package io.github.term4.shardkit.testsupport;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;

/**
 * One headless {@link MinecraftServer} per JVM (registries + dispatcher, no socket) with a loaded flat stone
 * instance (surface y=63) shared by every test class.
 */
public abstract class HeadlessServerTest {

    protected static InstanceContainer instance;

    @BeforeAll
    static void boot() {
        if (instance != null) return;
        MinecraftServer.init();
        // entity registration/ticking partition threads; lets setInstance(...).join() complete
        MinecraftServer.process().dispatcher().start();
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        instance.loadChunk(0, 0).join();
    }

    /** A stationary zombie placed at {@code pos}. */
    protected static LivingEntity zombie(Pos pos) {
        LivingEntity e = new LivingEntity(EntityType.ZOMBIE);
        e.setInstance(instance, pos).join();
        return e;
    }

    /** Waits (max 2s) for an entity's async {@code setInstance} to land. */
    protected static void awaitSpawn(Entity e) {
        long deadline = System.currentTimeMillis() + 2000;
        while (e.getInstance() == null && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        if (e.getInstance() == null) throw new AssertionError("setInstance did not complete");
    }
}
