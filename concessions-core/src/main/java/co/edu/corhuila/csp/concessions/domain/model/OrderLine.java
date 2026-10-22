package co.edu.corhuila.csp.concessions.domain.model;

import java.util.UUID;

/**
 * A line of an order with the name and the unit price (cents) copied when the order was created: a later
 * change to the product or the combo does not alter it.
 */
public record OrderLine(OrderItemType itemType, UUID itemId, String name, long unitPrice, int quantity) {

    public OrderLine {
        if (itemType == null || itemId == null) {
            throw new InvalidOrderException("a line needs the type and the id of its item");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidOrderException("a line needs the name of its item");
        }
        if (unitPrice < 0) {
            throw new InvalidOrderException("the price of a line cannot be negative");
        }
        if (quantity < 1 || quantity > ConcessionOrder.QUANTITY_MAX) {
            throw new InvalidOrderException(
                    "the quantity of a line is between 1 and " + ConcessionOrder.QUANTITY_MAX);
        }
    }

    public long subtotal() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}
