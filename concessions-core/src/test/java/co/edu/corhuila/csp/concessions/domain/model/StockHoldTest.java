package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockHoldTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID MID = UUID.fromString("7fffffff-0000-4000-8000-000000000001");
    private static final UUID HIGH = UUID.fromString("ffffffff-0000-4000-8000-000000000001");

    private static StockHold held() {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        quantities.put(HIGH, 1);
        quantities.put(LOW, 4);
        quantities.put(MID, 2);
        return StockHold.open(UUID.randomUUID(), UUID.randomUUID(), quantities, NOW);
    }

    @Test
    void aHoldIsOpenedHeldWithItsProductsInAscendingIdOrder() {
        StockHold hold = held();

        assertEquals(HoldStatus.HELD, hold.status());
        assertNull(hold.resolvedAt());
        assertEquals(List.of(LOW, MID, HIGH), hold.items().stream().map(HeldProduct::productId).toList());
    }

    @Test
    void aHoldNeedsPositiveQuantities() {
        assertThrows(InvalidOrderException.class,
                () -> StockHold.open(UUID.randomUUID(), UUID.randomUUID(), Map.of(), NOW));
        assertThrows(InvalidOrderException.class,
                () -> StockHold.open(UUID.randomUUID(), UUID.randomUUID(), Map.of(LOW, 0), NOW));
    }

    @Test
    void aHeldHoldIsCommittedOnce() {
        StockHold committed = held().commit(NOW.plusSeconds(5));

        assertEquals(HoldStatus.COMMITTED, committed.status());
        assertEquals(NOW.plusSeconds(5), committed.resolvedAt());
        assertThrows(InvalidStatusTransitionException.class, () -> committed.commit(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> committed.release(NOW));
    }

    @Test
    void aHeldHoldIsReleasedOnce() {
        StockHold released = held().release(NOW.plusSeconds(5));

        assertEquals(HoldStatus.RELEASED, released.status());
        assertThrows(InvalidStatusTransitionException.class, () -> released.release(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> released.commit(NOW));
    }
}
