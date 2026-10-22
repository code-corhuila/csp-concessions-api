package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;

/** The order of a creation and whether it is the one stored by an earlier request with the same key. */
public record PlacedOrder(ConcessionOrder order, boolean replayed) {
}
