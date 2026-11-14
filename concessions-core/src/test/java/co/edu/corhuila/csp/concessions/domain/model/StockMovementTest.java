package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockMovementTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static StockMovement movement(int delta, int balanceAfter, MovementType type) {
        return new StockMovement(ID, PRODUCT, delta, balanceAfter, type, "key-0000001", ADMIN, NOW);
    }

    @Test
    void thePositiveDeltaIsAReceiptAndTheNegativeOneAnAdjustment() {
        assertEquals(MovementType.RECEIPT, MovementType.forDelta(5));
        assertEquals(MovementType.ADJUSTMENT, MovementType.forDelta(-5));
        assertThrows(InvalidStockMovementException.class, () -> MovementType.forDelta(0));
    }

    @Test
    void aValidMovementKeepsItsValues() {
        StockMovement movement = movement(5, 15, MovementType.RECEIPT);

        assertEquals(5, movement.quantityDelta());
        assertEquals(15, movement.balanceAfter());
        assertEquals("key-0000001", movement.idempotencyKey());
    }

    @Test
    void aZeroDeltaIsRefused() {
        assertThrows(InvalidStockMovementException.class, () -> movement(0, 10, MovementType.RECEIPT));
    }

    @Test
    void aNegativeBalanceIsRefused() {
        assertThrows(InvalidStockMovementException.class, () -> movement(-5, -1, MovementType.ADJUSTMENT));
    }

    @Test
    void theTypeMustFollowTheSignOfTheDelta() {
        assertThrows(InvalidStockMovementException.class, () -> movement(5, 15, MovementType.ADJUSTMENT));
        assertThrows(InvalidStockMovementException.class, () -> movement(-5, 5, MovementType.RECEIPT));
    }

    @Test
    void theIdentifiersAndTheDateAreRequired() {
        assertThrows(InvalidStockMovementException.class,
                () -> new StockMovement(null, PRODUCT, 5, 5, MovementType.RECEIPT, "key-0000001", ADMIN, NOW));
        assertThrows(InvalidStockMovementException.class,
                () -> new StockMovement(ID, null, 5, 5, MovementType.RECEIPT, "key-0000001", ADMIN, NOW));
        assertThrows(InvalidStockMovementException.class,
                () -> new StockMovement(ID, PRODUCT, 5, 5, MovementType.RECEIPT, "key-0000001", null, NOW));
        assertThrows(InvalidStockMovementException.class,
                () -> new StockMovement(ID, PRODUCT, 5, 5, MovementType.RECEIPT, "key-0000001", ADMIN, null));
    }
}
