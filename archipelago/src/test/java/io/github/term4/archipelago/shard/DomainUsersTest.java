package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clock retires with its last holder, counted on the domain rather than on one registry. */
class DomainUsersTest extends HeadlessServerTest {

    @Test
    void anUnregisteredShardKeepsTheClock() {
        ExecutionDomain domain = ExecutionDomain.create();
        ShardManager manager = new ShardManager();
        try {
            Shard registered = manager.create(instance, domain);
            Shard bare = new Shard(instance, domain);
            assertEquals(2, domain.users());

            manager.destroy(registered);
            assertTrue(domain.live(), "the bare shard on the same clock still holds it");
            assertEquals(1, domain.users());

            manager.destroy(bare);
            assertFalse(domain.live(), "and the last holder ends it");
        } finally {
            domain.retire();
        }
    }

    @Test
    void reclockMovesTheCountWithoutEndingTheClock() {
        ExecutionDomain from = ExecutionDomain.create();
        ExecutionDomain to = ExecutionDomain.create();
        try {
            Shard shard = new Shard(instance, from);
            shard.domain(to);
            assertEquals(0, from.users());
            assertEquals(1, to.users());
            assertTrue(from.live(), "the caller still holds the clock it re-clocked away from");
        } finally {
            from.retire();
            to.retire();
        }
    }
}
