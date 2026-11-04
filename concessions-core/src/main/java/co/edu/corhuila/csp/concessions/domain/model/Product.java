package co.edu.corhuila.csp.concessions.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A food or beverage item of the snack bar (HU-CONCESSIONS-001). It is immutable: every transition
 * returns a new instance, so a rejected rule changes nothing. Money is an integer in cents.
 *
 * <p>The lifecycle is DRAFT to PUBLISHED, PUBLISHED to INACTIVE and INACTIVE back to PUBLISHED; any
 * other move, including repeating the current status, is an {@link InvalidStatusTransitionException}.
 * A malformed product is an {@link InvalidProductException}, thrown by the constructor.
 */
public record Product(
        UUID id,
        String name,
        String description,
        String category,
        long price,
        int availableStock,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final int NAME_MAX = 160;
    public static final int DESCRIPTION_MAX = 500;
    public static final int CATEGORY_MAX = 80;
    /** The most units one stock movement may add or remove (Norma 5.3.10). */
    public static final int MAX_STOCK_DELTA = 100_000;

    public Product {
        if (id == null || status == null || createdAt == null || updatedAt == null) {
            throw new InvalidProductException("a product needs an id, a status and its dates");
        }
        name = required(name, NAME_MAX, "the name");
        category = required(category, CATEGORY_MAX, "the category");
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new InvalidProductException("the description has at most " + DESCRIPTION_MAX + " characters");
        }
        if (price < 0) {
            throw new InvalidProductException("the price cannot be negative");
        }
        if (availableStock < 0) {
            throw new InvalidProductException("the stock cannot be negative");
        }
    }

    /** Creates a DRAFT product: it is not offered to clients until it is published. */
    public static Product draft(UUID id, String name, String description, String category, long price,
                                int availableStock, Instant now) {
        return new Product(id, name, description, category, price, availableStock, ProductStatus.DRAFT, now, now);
    }

    /** DRAFT or INACTIVE becomes PUBLISHED. */
    public Product publish(Instant now) {
        if (status == ProductStatus.PUBLISHED) {
            throw new InvalidStatusTransitionException("the product is already published");
        }
        return withStatus(ProductStatus.PUBLISHED, now);
    }

    /** PUBLISHED becomes INACTIVE. */
    public Product deactivate(Instant now) {
        if (status != ProductStatus.PUBLISHED) {
            throw new InvalidStatusTransitionException("only a published product can be deactivated");
        }
        return withStatus(ProductStatus.INACTIVE, now);
    }

    /**
     * Applies a stock receipt (positive) or withdrawal (negative). The balance never becomes negative
     * ({@link InsufficientStockException}), the change is non-zero and at most {@link #MAX_STOCK_DELTA}
     * ({@link InvalidStockMovementException}), and an inactive product, or a balance beyond what an
     * {@code int} holds, is a {@link StockNotAdjustableException}. A refused change returns nothing.
     */
    public Product adjustStock(int quantityDelta, Instant now) {
        if (quantityDelta == 0 || Math.abs((long) quantityDelta) > MAX_STOCK_DELTA) {
            throw new InvalidStockMovementException(
                    "the change is between 1 and " + MAX_STOCK_DELTA + " units, as a receipt or a withdrawal");
        }
        if (status == ProductStatus.INACTIVE) {
            throw new StockNotAdjustableException("an inactive product does not take stock movements");
        }
        long balance = (long) availableStock + quantityDelta;
        if (balance < 0) {
            throw new InsufficientStockException("the available quantity is " + availableStock);
        }
        if (balance > Integer.MAX_VALUE) {
            throw new StockNotAdjustableException("the available quantity would exceed " + Integer.MAX_VALUE);
        }
        return new Product(id, name, description, category, price, (int) balance, status, createdAt, now);
    }

    /** Only a published product can be ordered by a client. */
    public boolean isAvailableForOrders() {
        return status == ProductStatus.PUBLISHED;
    }

    private Product withStatus(ProductStatus next, Instant now) {
        return new Product(id, name, description, category, price, availableStock, next, createdAt, now);
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            throw new InvalidProductException(label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new InvalidProductException(label + " has at most " + max + " characters");
        }
        return trimmed;
    }
}
