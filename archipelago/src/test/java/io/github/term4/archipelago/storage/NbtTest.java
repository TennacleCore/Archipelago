package io.github.term4.archipelago.storage;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.utils.Unit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An item with a registry-keyed component (an enchantment) survives the round trip; a bare transcoder refuses it. */
class NbtTest extends HeadlessServerTest {

    @Test
    void anEnchantedItemRoundTrips() {
        ItemStack pickaxe = ItemStack.of(Material.DIAMOND_PICKAXE)
                .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.EFFICIENCY, 2)))
                .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        ItemStack back = Nbt.item(Nbt.item(pickaxe));
        assertEquals(pickaxe, back);
        assertEquals(2, back.get(DataComponents.ENCHANTMENTS).level(Enchantment.EFFICIENCY));
    }
}
