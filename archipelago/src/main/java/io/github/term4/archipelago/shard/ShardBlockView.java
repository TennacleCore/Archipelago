package io.github.term4.archipelago.shard;

import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

/** A shard as the block view rules and handlers run against: reads AND writes stay in the shard. */
public record ShardBlockView(@NotNull Shard shard) implements Block.Getter, Block.Setter, MechanicsWorld.View {

    @Override
    public @NotNull MechanicsWorld world() {
        return shard;
    }

    @Override
    public @NotNull Block getBlock(int x, int y, int z, @NotNull Condition condition) {
        return shard.getBlock(x, y, z, condition);
    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull Block block) {
        shard.setBlock(new Vec(x, y, z), block);
    }
}
