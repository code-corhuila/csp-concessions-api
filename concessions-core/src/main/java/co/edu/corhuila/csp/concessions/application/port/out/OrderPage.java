package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import java.util.List;
import java.util.Objects;

/** One page of orders: the items and how many exist in total. */
public record OrderPage(List<ConcessionOrder> items, long total) {

    public OrderPage {
        Objects.requireNonNull(items, "the items are required");
    }
}
