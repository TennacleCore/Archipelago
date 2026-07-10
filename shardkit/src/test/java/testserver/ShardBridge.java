package testserver;

import io.github.term4.minestommechanics.util.BlockContact;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.minestommechanics.world.WorldPolicy;
import io.github.term4.shardkit.Shard;
import io.github.term4.shardkit.ShardView;
import io.github.term4.worldaccess.WorldAccess;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.collision.EntityCollisionResult;
import net.minestom.server.collision.PhysicsResult;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.tag.Tag;
import net.minestom.server.tag.TagHandler;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Template glue between ShardKit and MinestomMechanics - the two libraries never reference each other, so an
 * application using both copies this class (it IS the whole integration). {@link #install} plugs shard bindings
 * into {@link MechanicsWorld} resolution; {@link #adapt} is the {@code MechanicsWorld} view of a shard (cached,
 * so identity-based gating works).
 */
public final class ShardBridge {

    private static final Tag<MechanicsWorld> ADAPTER = Tag.Transient("bridge:mechanics-world");

    private ShardBridge() {}

    /** Install once at boot, before players join (alongside {@code Shard.blockRouting()}). */
    public static void install() {
        MechanicsWorld.resolver(new MechanicsWorld.Resolver() {
            @Override public @Nullable MechanicsWorld resolve(@NotNull Entity entity) {
                return entity.getTag(WorldAccess.ENTITY_TAG) instanceof Shard s
                        ? adapt(s) : entity.getTag(MechanicsWorld.ENTITY_TAG);
            }

            // client-parity checks (movement, eye, crack) collide against the BLOCK VIEW: a spectator
            // walks the shard's block state while staying unbound (never gameplay-targeted)
            @Override public @Nullable MechanicsWorld viewedBlocks(@NotNull Player player) {
                Shard viewed = ShardView.of(player).blocks();
                return viewed != null ? adapt(viewed) : null;
            }
        });
        // mechanics-side gates: block-anchored FX follow the shard block view
        WorldPolicy.set(new WorldPolicy() {
            @Override public boolean seesBlocksOf(@NotNull Player viewer, @NotNull MechanicsWorld world) {
                Shard shard = world instanceof Adapter a ? a.shard : null;
                return ShardView.of(viewer).blocks() == shard && viewer.getInstance() == world.instance();
            }
        });
        Shard.selfPlaceExempt(BlockContact::isPassable); // 1.8 ladder/vine/web self-place clutch
    }

    /** Fork cloner for a mixed app: mechanics entities self-copy via {@link MechanicsWorld#ENTITY_COPY}, the rest through {@link Shard#copyEntity}. */
    public static @Nullable Entity copyEntity(@NotNull Entity entity) {
        Supplier<Entity> stamped = entity.getTag(MechanicsWorld.ENTITY_COPY);
        return stamped != null ? stamped.get() : Shard.copyEntity(entity);
    }

    /** The {@link MechanicsWorld} view of {@code shard} (one cached adapter per shard). */
    public static @NotNull MechanicsWorld adapt(@NotNull Shard shard) {
        MechanicsWorld cached = shard.getTag(ADAPTER);
        if (cached == null) {
            cached = new Adapter(shard);
            shard.setTag(ADAPTER, cached); // benign race: last write wins, both delegate to the same shard
        }
        return cached;
    }

    private static final class Adapter implements MechanicsWorld {
        final Shard shard;

        Adapter(Shard shard) { this.shard = shard; }

        @Override public @NotNull Instance instance() { return shard.instance(); }
        @Override public @NotNull TagHandler tagHandler() { return shard.tagHandler(); }
        @Override public @NotNull Block getBlock(@NotNull Point pos) { return shard.getBlock(pos); }
        @Override public @NotNull Block getBlock(int x, int y, int z) { return shard.getBlock(x, y, z); }
        @Override public @NotNull Block getBlock(@NotNull Point pos, Block.Getter.@NotNull Condition c) { return shard.getBlock(pos, c); }
        @Override public @NotNull Block getBlock(int x, int y, int z, Block.Getter.@NotNull Condition c) { return shard.getBlock(x, y, z, c); }
        @Override public void setBlock(@NotNull Point pos, @NotNull Block block) { shard.setBlock(pos, block); }
        @Override public void setBlocks(@NotNull Map<Point, Block> blocks) { shard.setBlocks(blocks); }
        @Override public boolean isChunkLoaded(@NotNull Point pos) { return shard.isChunkLoaded(pos); }
        @Override public boolean isChunkLoaded(int chunkX, int chunkZ) { return shard.isChunkLoaded(chunkX, chunkZ); }
        @Override public @NotNull DimensionType dimension() { return shard.dimension(); }
        @Override public PhysicsResult sweep(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                                             @Nullable PhysicsResult last, boolean singleCollision) {
            return shard.sweep(box, position, velocity, last, singleCollision);
        }
        @Override public PhysicsResult sweepLoaded(@NotNull BoundingBox box, @NotNull Pos position, @NotNull Vec velocity,
                                                   @Nullable PhysicsResult last, boolean singleCollision) {
            return shard.sweepLoaded(box, position, velocity, last, singleCollision);
        }
        @Override public Collection<EntityCollisionResult> sweepEntities(@NotNull BoundingBox box, @NotNull Pos position,
                                                                         @NotNull Vec velocity, double extendRadius,
                                                                         @NotNull Function<Entity, Boolean> filter,
                                                                         @Nullable PhysicsResult physics) {
            return shard.sweepEntities(box, position, velocity, extendRadius, filter, physics);
        }
        @Override public PhysicsResult simulateMovement(@NotNull Pos position, @NotNull Vec velocityPerTick, @NotNull BoundingBox box,
                                                        @NotNull Aerodynamics aerodynamics, boolean noGravity, boolean hasPhysics,
                                                        boolean onGround, @Nullable PhysicsResult last) {
            return shard.simulateMovement(position, velocityPerTick, box, aerodynamics, noGravity, hasPhysics, onGround, last);
        }
        @Override public @NotNull WorldBorder worldBorder() { return shard.worldBorder(); }
        @Override public boolean isInVoid(@NotNull Point pos) { return shard.isInVoid(pos); }
        @Override public @NotNull Collection<@NotNull Entity> entities() { return shard.entities(); }
        @Override public @NotNull Collection<@NotNull Entity> nearbyEntities(@NotNull Point point, double range) {
            return shard.nearbyEntities(point, range);
        }
        @Override public void nearbyPlayers(@NotNull Point point, double range, @NotNull Consumer<Player> consumer) {
            shard.nearbyPlayers(point, range, consumer);
        }
        @Override public @NotNull CompletableFuture<Void> spawn(@NotNull Entity entity, @NotNull Pos position) {
            return shard.spawn(entity, position);
        }
        @Override public @NotNull Collection<@NotNull Player> players() { return shard.players(); }
        @Override public @NotNull Collection<@NotNull Player> watchers() { return shard.watchers(); }
        @Override public void broadcast(@NotNull ServerPacket packet) { shard.broadcast(packet); }
        @Override public void playSound(@NotNull Sound sound, @NotNull Point pos) { shard.playSound(sound, pos); }
        @Override public @NotNull Weather weather() { return shard.weather(); }
        @Override public void setWeather(@NotNull Weather weather) { shard.setWeather(weather); }
        @Override public long time() { return shard.time(); }
        @Override public void setTime(long time) { shard.setTime(time); }
        @Override public long worldAge() { return shard.worldAge(); }
        @Override public void scheduleNextTick(@NotNull Consumer<MechanicsWorld> task) {
            shard.scheduleNextTick(w -> task.accept(this));
        }
    }
}
