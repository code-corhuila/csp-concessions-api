package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.util.List;
import java.util.Objects;

/** One page of products: the items and how many exist in total. */
public record ProductPage(List<Product> items, long total) {

    public ProductPage {
        Objects.requireNonNull(items, "the items are required");
    }
}
