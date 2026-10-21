package co.edu.corhuila.csp.concessions.domain.model;

import java.util.UUID;

/** A component of a combo: a reference to a product and how many units of it the combo carries. */
public record ComboItem(UUID productId, int quantity) {

    public ComboItem {
        if (productId == null) {
            throw new InvalidComboException("a component needs a product");
        }
        if (quantity <= 0) {
            throw new InvalidComboException("the quantity of a component must be positive");
        }
    }
}
