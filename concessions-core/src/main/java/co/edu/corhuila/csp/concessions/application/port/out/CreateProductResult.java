package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Product;

/** The product of a creation and whether it is the one stored by an earlier request with the same key. */
public record CreateProductResult(Product product, boolean replayed) {
}
