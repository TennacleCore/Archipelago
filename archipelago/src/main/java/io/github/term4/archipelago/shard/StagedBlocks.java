package io.github.term4.archipelago.shard;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * A read-through view whose writes are held instead of landing. A placement rule is asked for its shape before
 * the body check and the event, and a multi-block rule WRITES its other half as it answers (a bed's head, a
 * door's top) - against the world that would leave the half behind on a refusal, and would refuse the rule's own
 * second run, since the half it wrote is no longer replaceable. Reads see the held writes, so a rule that reads
 * back what it wrote still answers the same.
 */
final class StagedBlocks implements Block.Getter, Block.Setter {

    private final Block.Getter over;
    private final Map<Point, Block> held = new HashMap<>();

    StagedBlocks(@NotNull Block.Getter over) {
        this.over = over;
    }

    @Override
    public @NotNull Block getBlock(int x, int y, int z, @NotNull Condition condition) {
        Block staged = held.get(new BlockVec(x, y, z));
        return staged != null ? staged : over.getBlock(x, y, z, condition);
    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull Block block) {
        held.put(new BlockVec(x, y, z), block);
    }

    /** Lands what the rule wrote, now that the placement is going through. */
    void commit(@NotNull Block.Setter to) {
        held.forEach((pos, block) -> to.setBlock(pos, block));
    }
}
