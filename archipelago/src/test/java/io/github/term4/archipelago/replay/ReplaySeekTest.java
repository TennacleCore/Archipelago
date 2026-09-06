package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.replay.core.Channels;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A backward seek touches the wire only where the theater differs from the target tick. */
class ReplaySeekTest extends HeadlessServerTest {

    @Test
    void backwardSeekRevertsOnlyTouched() {
        loadArea(1900, 0);
        CoreChannels.register();
        int x = 1900 << 4;
        Shard live = new Shard(instance);
        Recording rec = Replay.record(live);
        byte[] file;
        try {
            for (int i = 1; i <= 12; i++) {
                tick();
                if (i == 2) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, x + 1, 70, 1, "minecraft:stone"));
                if (i == 5) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, x + 3, 70, 3, "minecraft:dirt"));
                if (i == 9) rec.emit(Channels.BLOCKS, new Channels.BlockOp(0, x + 2, 70, 2, "minecraft:oak_planks"));
            }
        } finally {
            file = rec.stop();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(x + 0.5, 72, 0.5), "SeekViewer");
        Playback pb = Replay.play(theater, file);
        try {
            theater.join(viewer.player);
            pb.seek(10);
            assertEquals(Block.OAK_PLANKS, theater.getBlock(x + 2, 70, 2));
            viewer.sent.clear();

            pb.seek(6);
            assertEquals(6, pb.tick());
            assertEquals(Block.STONE, theater.getBlock(x + 1, 70, 1), "placed before the target, stays");
            assertEquals(Block.DIRT, theater.getBlock(x + 3, 70, 3));
            assertTrue(theater.getBlock(x + 2, 70, 2).air(), "placed after it, gone");
            List<BlockChangePacket> changes = viewer.sent(BlockChangePacket.class);
            assertEquals(1, changes.size(), "one position reverted, one packet: " + changes);
            assertEquals(x + 2, changes.getFirst().blockPosition().blockX());
            assertEquals(2, changes.getFirst().blockPosition().blockZ());
            assertTrue(viewer.sent(MultiBlockChangePacket.class).isEmpty(), "no section re-send either");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
