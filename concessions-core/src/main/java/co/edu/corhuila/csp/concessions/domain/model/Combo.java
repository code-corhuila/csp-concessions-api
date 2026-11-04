package co.edu.corhuila.csp.concessions.domain.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A predefined snack-bar bundle (HU-CONCESSIONS-002). It is immutable: every transition returns a new
 * instance, so a rejected rule changes nothing. Money is an integer in cents.
 *
 * <p>The lifecycle is the one of {@link Product}. A combo can be saved as a DRAFT whatever the state of its
 * products, but it is published only while every component is a PUBLISHED product; that check belongs
 * here and runs again each time the combo is published. A malformed combo is an
 * {@link InvalidComboException}, thrown by the constructor.
 */
public record Combo(
        UUID id,
        String name,
        String description,
        long price,
        List<ComboItem> items,
        ComboStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static final int NAME_MAX = 160;
    public static final int DESCRIPTION_MAX = 500;
    public static final int ITEMS_MAX = 50;

    public Combo {
        if (id == null || status == null || createdAt == null || updatedAt == null) {
            throw new InvalidComboException("a combo needs an id, a status and its dates");
        }
        name = requiredName(name);
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new InvalidComboException("the description has at most " + DESCRIPTION_MAX + " characters");
        }
        if (price < 0) {
            throw new InvalidComboException("the price cannot be negative");
        }
        items = validItems(items);
    }

    /** Creates a DRAFT combo: it is not offered to clients until it is published. */
    public static Combo draft(UUID id, String name, String description, long price, List<ComboItem> items,
                              Instant now) {
        return new Combo(id, name, description, price, items, ComboStatus.DRAFT, now, now);
    }

    /**
     * DRAFT or INACTIVE becomes PUBLISHED, provided every component is a PUBLISHED product of
     * {@code products}. A repeated publish is a transition error before it is a component error.
     */
    public Combo publish(Instant now, Map<UUID, Product> products) {
        if (status == ComboStatus.PUBLISHED) {
            throw new InvalidStatusTransitionException("the combo is already published");
        }
        for (ComboItem item : items) {
            Product product = products.get(item.productId());
            if (product == null) {
                throw new ComboComponentException("the product " + item.productId() + " does not exist");
            }
            if (!product.isAvailableForOrders()) {
                throw new ComboComponentException("the product " + item.productId() + " is not published");
            }
        }
        return withStatus(ComboStatus.PUBLISHED, now);
    }

    /** PUBLISHED becomes INACTIVE. */
    public Combo deactivate(Instant now) {
        if (status != ComboStatus.PUBLISHED) {
            throw new InvalidStatusTransitionException("only a published combo can be deactivated");
        }
        return withStatus(ComboStatus.INACTIVE, now);
    }

    /** Only a published combo can be ordered by a client. */
    public boolean isAvailableForOrders() {
        return status == ComboStatus.PUBLISHED;
    }

    private Combo withStatus(ComboStatus next, Instant now) {
        return new Combo(id, name, description, price, items, next, createdAt, now);
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidComboException("the name is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new InvalidComboException("the name has at most " + NAME_MAX + " characters");
        }
        return trimmed;
    }

    private static List<ComboItem> validItems(List<ComboItem> items) {
        if (items == null || items.isEmpty() || items.size() > ITEMS_MAX) {
            throw new InvalidComboException("a combo has between 1 and " + ITEMS_MAX + " components");
        }
        Set<UUID> seen = new HashSet<>();
        for (ComboItem item : items) {
            if (!seen.add(item.productId())) {
                throw new InvalidComboException("a product appears once in a combo");
            }
        }
        return List.copyOf(items);
    }
}
