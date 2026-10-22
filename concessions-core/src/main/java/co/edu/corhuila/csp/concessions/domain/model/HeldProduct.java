package co.edu.corhuila.csp.concessions.domain.model;

import java.util.UUID;

/** A quantity of one product held for an order, with the components of combos already expanded. */
public record HeldProduct(UUID productId, int quantity) {

    public HeldProduct {
        if (productId == null || quantity <= 0) {
            throw new InvalidOrderException("a held product needs an id and a positive quantity");
        }
    }
}
