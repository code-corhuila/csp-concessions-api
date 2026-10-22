package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductStockTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static Product draft(int stock) {
        return Product.draft(ID, "Popcorn", null, "SNACK", 1500, stock, NOW);
    }

    @Test
    void aReceiptRaisesTheBalanceAndKeepsTheOtherValues() {
        Instant later = NOW.plusSeconds(60);

        Product adjusted = draft(10).adjustStock(5, later);

        assertEquals(15, adjusted.availableStock());
        assertEquals(later, adjusted.updatedAt());
        assertEquals(NOW, adjusted.createdAt());
        assertEquals(ProductStatus.DRAFT, adjusted.status());
        assertEquals("Popcorn", adjusted.name());
    }

    @Test
    void aWithdrawalLowersTheBalanceAndMayEmptyIt() {
        assertEquals(6, draft(10).adjustStock(-4, NOW).availableStock());
        assertEquals(0, draft(10).adjustStock(-10, NOW).availableStock());
    }

    @Test
    void aWithdrawalLargerThanTheBalanceIsRefusedAndNothingChanges() {
        Product product = draft(3);

        assertThrows(InsufficientStockException.class, () -> product.adjustStock(-4, NOW));
        assertEquals(3, product.availableStock());
    }

    @Test
    void aZeroDeltaIsRefused() {
        assertThrows(InvalidStockMovementException.class, () -> draft(3).adjustStock(0, NOW));
    }

    @Test
    void aDeltaBeyondTheLimitIsRefusedInBothDirections() {
        assertEquals(100_003, draft(3).adjustStock(Product.MAX_STOCK_DELTA, NOW).availableStock());
        assertThrows(InvalidStockMovementException.class,
                () -> draft(3).adjustStock(Product.MAX_STOCK_DELTA + 1, NOW));
        assertThrows(InvalidStockMovementException.class,
                () -> draft(200_000).adjustStock(-Product.MAX_STOCK_DELTA - 1, NOW));
    }

    @Test
    void aBalanceBeyondTheColumnIsRefused() {
        Product full = draft(Integer.MAX_VALUE - 1);

        assertEquals(Integer.MAX_VALUE, full.adjustStock(1, NOW).availableStock());
        assertThrows(StockNotAdjustableException.class, () -> full.adjustStock(2, NOW));
    }

    @Test
    void aPublishedProductTakesMovementsButAnInactiveOneDoesNot() {
        Product published = draft(5).publish(NOW);
        Product inactive = published.deactivate(NOW);

        assertEquals(7, published.adjustStock(2, NOW).availableStock());
        assertThrows(StockNotAdjustableException.class, () -> inactive.adjustStock(2, NOW));
        assertThrows(StockNotAdjustableException.class, () -> inactive.adjustStock(-1, NOW));
    }
}
