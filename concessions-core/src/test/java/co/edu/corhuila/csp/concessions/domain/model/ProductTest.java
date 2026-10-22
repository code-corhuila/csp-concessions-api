package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static Product draft() {
        return Product.draft(ID, "Popcorn", "Large", "SNACK", 1500, 10, NOW);
    }

    @Test
    void aNewProductIsADraftWithItsValues() {
        Product product = draft();

        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals("Popcorn", product.name());
        assertEquals(1500, product.price());
        assertEquals(10, product.availableStock());
    }

    @Test
    void theNameIsTrimmed() {
        assertEquals("Popcorn", Product.draft(ID, "  Popcorn ", null, "SNACK", 0, 0, NOW).name());
    }

    @Test
    void aProductWithoutNameIsRejected() {
        assertThrows(InvalidProductException.class, () -> Product.draft(ID, null, null, "SNACK", 100, 0, NOW));
        assertThrows(InvalidProductException.class, () -> Product.draft(ID, "   ", null, "SNACK", 100, 0, NOW));
    }

    @Test
    void aNegativePriceIsRejectedAndZeroIsAllowed() {
        assertThrows(InvalidProductException.class, () -> Product.draft(ID, "Soda", null, "DRINK", -1, 0, NOW));
        assertEquals(0, Product.draft(ID, "Water", null, "DRINK", 0, 0, NOW).price());
    }

    @Test
    void aNegativeStockIsRejected() {
        assertThrows(InvalidProductException.class, () -> Product.draft(ID, "Soda", null, "DRINK", 100, -1, NOW));
    }

    @Test
    void theCategoryIsRequired() {
        assertThrows(InvalidProductException.class, () -> Product.draft(ID, "Soda", null, " ", 100, 0, NOW));
    }

    @Test
    void textLongerThanTheColumnsIsRejected() {
        assertThrows(InvalidProductException.class,
                () -> Product.draft(ID, "x".repeat(161), null, "SNACK", 100, 0, NOW));
        assertThrows(InvalidProductException.class,
                () -> Product.draft(ID, "Soda", "x".repeat(501), "DRINK", 100, 0, NOW));
        assertThrows(InvalidProductException.class,
                () -> Product.draft(ID, "Soda", null, "x".repeat(81), 100, 0, NOW));
    }

    @Test
    void aDraftCanBePublishedAndKeepsItsOtherValues() {
        Product published = draft().publish(NOW.plusSeconds(60));

        assertEquals(ProductStatus.PUBLISHED, published.status());
        assertEquals("Popcorn", published.name());
        assertEquals(NOW.plusSeconds(60), published.updatedAt());
        assertEquals(NOW, published.createdAt());
    }

    @Test
    void aPublishedProductCanBeDeactivatedAndReactivated() {
        Product inactive = draft().publish(NOW).deactivate(NOW);

        assertEquals(ProductStatus.INACTIVE, inactive.status());
        assertEquals(ProductStatus.PUBLISHED, inactive.publish(NOW).status());
    }

    @Test
    void aTransitionOutsideTheLifecycleIsRejectedAndChangesNothing() {
        Product product = draft();

        assertThrows(InvalidStatusTransitionException.class, () -> product.deactivate(NOW));
        assertThrows(InvalidStatusTransitionException.class, () -> product.publish(NOW).publish(NOW));
        assertEquals(ProductStatus.DRAFT, product.status());
    }

    @Test
    void onlyAPublishedProductIsAvailableForOrders() {
        assertFalse(draft().isAvailableForOrders());
        assertTrue(draft().publish(NOW).isAvailableForOrders());
        assertFalse(draft().publish(NOW).deactivate(NOW).isAvailableForOrders());
    }
}
