package io.github.term4.archipelago.testsupport;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.presets.vanilla18.Attributes;
import io.github.term4.minestommechanics.presets.vanilla18.Damage;
import io.github.term4.minestommechanics.presets.vanilla18.Knockback;
import test.ShardBridge;

/** The vanilla18 combat systems + bridge, installed once per JVM - shared by every bridge test class. */
public final class MechanicsBoot {

    private static MinestomMechanics mm;

    private MechanicsBoot() {}

    public static synchronized MinestomMechanics ensure() {
        if (mm == null) {
            mm = MinestomMechanics.getInstance();
            mm.init();
            DamageSystem.install(mm, Damage.config());
            KnockbackSystem.install(mm, Knockback.melee());
            AttributeSystem.install(mm, Attributes.config());
            ShardBridge.install();
        }
        return mm;
    }
}
