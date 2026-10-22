package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;

/** An order found by its idempotency key, with the hash of the body that created it. */
public record StoredOrder(ConcessionOrder order, String requestHash) {
}
