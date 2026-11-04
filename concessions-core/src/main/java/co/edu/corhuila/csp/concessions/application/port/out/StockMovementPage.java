package co.edu.corhuila.csp.concessions.application.port.out;

import java.util.List;
import java.util.Objects;

/** One page of the stock history, newest first: the items and how many movements match in total. */
public record StockMovementPage(List<StockMovementEntry> items, long total) {

    public StockMovementPage {
        Objects.requireNonNull(items, "the items are required");
    }
}
