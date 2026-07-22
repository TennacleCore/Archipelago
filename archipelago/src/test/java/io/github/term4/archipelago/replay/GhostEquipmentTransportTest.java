package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ghost's equipment must reach viewers BARE: a grouped send shares one {@code CachedPacket} no per-viewer packet
 * view can rewrite, and the held item's use animation (the raised-sword block pose) renders off the viewer's copy.
 */
class GhostEquipmentTransportTest extends HeadlessServerTest {

    @Test
    void aGhostReEquipReachesViewersBare() {
        Ghost ghost = new Ghost("Recorded", null, null);
        ghost.setInstance(instance, new Pos(500.5, 64, 500.5)).join();
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(502.5, 64, 500.5), "GhostWatcher");
        try {
            assertTrue(ghost.getViewers().contains(viewer.player), "the harness really is watching the ghost");
            viewer.sent.clear();

            ghost.setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.DIAMOND_SWORD));

            assertTrue(viewer.sent.stream() // raw captures: not sent(Class), which unwraps CachedPacket
                            .filter(EntityEquipmentPacket.class::isInstance).map(EntityEquipmentPacket.class::cast)
                            .anyMatch(p -> p.entityId() == ghost.getEntityId()
                                    && p.equipments().containsKey(EquipmentSlot.MAIN_HAND)),
                    "the re-equip must arrive bare, not wrapped in a shared CachedPacket");
        } finally {
            viewer.player.remove();
            ghost.remove();
        }
    }
}
