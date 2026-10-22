package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConcessionOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final UUID USER = UUID.randomUUID();

    private static OrderLine popcorn(int quantity) {
        return new OrderLine(OrderItemType.PRODUCT, UUID.randomUUID(), "Popcorn", 1500, quantity);
    }

    private static ConcessionOrder reserved(UUID reservationId) {
        return ConcessionOrder.reserve(UUID.randomUUID(), USER, reservationId,
                List.of(popcorn(2), new OrderLine(OrderItemType.COMBO, UUID.randomUUID(), "Family", 4000, 1)), TTL, NOW);
    }

    @Test
    void anOrderIsBornReservedWithTheTotalOfItsSnapshot() {
        ConcessionOrder order = reserved(UUID.randomUUID());

        assertEquals(OrderStatus.RESERVED, order.status());
        assertEquals(7000, order.totalAmount());
        assertNull(order.expiresAt());
        assertTrue(order.isLive());
    }

    @Test
    void anOrderWithoutReservationExpiresAfterTheHoldTime() {
        ConcessionOrder order = reserved(null);

        assertEquals(NOW.plus(TTL), order.expiresAt());
    }

    @Test
    void aSnapshotLineKeepsItsNameAndPriceInCents() {
        OrderLine line = popcorn(3);

        assertEquals("Popcorn", line.name());
        assertEquals(4500, line.subtotal());
    }

    @Test
    void aLineWithAZeroOrExcessiveQuantityIsRejected() {
        assertThrows(InvalidOrderException.class, () -> popcorn(0));
        assertThrows(InvalidOrderException.class, () -> popcorn(ConcessionOrder.QUANTITY_MAX + 1));
        assertEquals(ConcessionOrder.QUANTITY_MAX, popcorn(ConcessionOrder.QUANTITY_MAX).quantity());
    }

    @Test
    void aLineWithoutNameOrWithANegativePriceIsRejected() {
        UUID id = UUID.randomUUID();

        assertThrows(InvalidOrderException.class, () -> new OrderLine(OrderItemType.PRODUCT, id, " ", 1, 1));
        assertThrows(InvalidOrderException.class, () -> new OrderLine(OrderItemType.PRODUCT, id, "Soda", -1, 1));
        assertThrows(InvalidOrderException.class, () -> new OrderLine(null, id, "Soda", 1, 1));
    }

    @Test
    void anOrderNeedsBetweenOneAndTwentyLines() {
        UUID id = UUID.randomUUID();
        List<OrderLine> twentyOne = new ArrayList<>();
        for (int i = 0; i < ConcessionOrder.LINES_MAX + 1; i++) {
            twentyOne.add(popcorn(1));
        }

        assertThrows(InvalidOrderException.class, () -> ConcessionOrder.reserve(id, USER, null, List.of(), TTL, NOW));
        assertThrows(InvalidOrderException.class, () -> ConcessionOrder.reserve(id, USER, null, twentyOne, TTL, NOW));
        assertEquals(ConcessionOrder.LINES_MAX,
                ConcessionOrder.reserve(id, USER, null, twentyOne.subList(0, 20), TTL, NOW).lines().size());
    }

    @Test
    void theSameItemCannotAppearTwice() {
        UUID item = UUID.randomUUID();
        List<OrderLine> repeated = List.of(
                new OrderLine(OrderItemType.PRODUCT, item, "Popcorn", 1500, 1),
                new OrderLine(OrderItemType.PRODUCT, item, "Popcorn", 1500, 2));

        assertThrows(InvalidOrderException.class,
                () -> ConcessionOrder.reserve(UUID.randomUUID(), USER, null, repeated, TTL, NOW));
    }

    @Test
    void aProductAndAComboWithTheSameIdAreDifferentItems() {
        UUID item = UUID.randomUUID();
        List<OrderLine> lines = List.of(
                new OrderLine(OrderItemType.PRODUCT, item, "Popcorn", 1500, 1),
                new OrderLine(OrderItemType.COMBO, item, "Family", 4000, 1));

        assertEquals(5500, ConcessionOrder.reserve(UUID.randomUUID(), USER, null, lines, TTL, NOW).totalAmount());
    }

    @Test
    void theTotalMustMatchTheLines() {
        ConcessionOrder order = reserved(null);

        assertThrows(InvalidOrderException.class, () -> new ConcessionOrder(order.id(), USER, null, order.lines(),
                order.totalAmount() + 1, OrderStatus.RESERVED, null, null, null, NOW, NOW));
    }

    @Test
    void aReservedOrderIsConfirmedOnce() {
        ConcessionOrder order = reserved(UUID.randomUUID());
        Instant later = NOW.plusSeconds(60);

        ConcessionOrder confirmed = order.confirm(later);

        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(later, confirmed.confirmedAt());
        assertEquals(later, confirmed.updatedAt());
        assertEquals(OrderStatus.RESERVED, order.status());
        assertThrows(InvalidStatusTransitionException.class, () -> confirmed.confirm(later));
        assertThrows(InvalidStatusTransitionException.class, () -> confirmed.cancel(later));
    }

    @Test
    void aReservedOrderIsCancelledOnceAndFreesItsReservation() {
        ConcessionOrder order = reserved(UUID.randomUUID());
        Instant later = NOW.plusSeconds(60);

        ConcessionOrder cancelled = order.cancel(later);

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(later, cancelled.cancelledAt());
        assertTrue(!cancelled.isLive());
        assertThrows(InvalidStatusTransitionException.class, () -> cancelled.cancel(later));
        assertThrows(InvalidStatusTransitionException.class, () -> cancelled.confirm(later));
    }

    @Test
    void theOrderOfAnotherUserIsNotOwned() {
        ConcessionOrder order = reserved(null);

        assertTrue(order.belongsTo(USER));
        assertTrue(!order.belongsTo(UUID.randomUUID()));
    }
}
