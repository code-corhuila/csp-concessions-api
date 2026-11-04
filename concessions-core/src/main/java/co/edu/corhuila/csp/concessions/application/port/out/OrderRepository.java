package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.StockHold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port: where orders, their stock holds and the events they cause are kept (HU-CONCESSIONS-004,
 * ADR-045). The implementation belongs to an adapter. Every method that changes state does it, with its
 * events, in one transaction.
 */
public interface OrderRepository {

    /** The order stored for the key of a user, with the hash of its body: it tells a retry from a new request. */
    Optional<StoredOrder> findByIdempotencyKey(UUID userId, String key);

    /**
     * Stores the order, its lines, its hold and its events, and takes the stock of every held product, all in
     * one transaction. The stock check must hold at the moment of the write, not only when the product was
     * read, so two concurrent orders cannot both take the last unit: when a product no longer has what the
     * hold asks for it throws {@link co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException}
     * and writes nothing. A key stored meanwhile by a concurrent request is a replay when its hash is the same
     * and an {@link IdempotencyKeyConflictException} when it is not.
     */
    PlacedOrder create(ConcessionOrder order, StockHold hold, String idempotencyKey, String requestHash,
                       List<OutboxEvent> events);

    Optional<ConcessionOrder> findById(UUID id);

    /** The live order of a reservation, or else its latest cancelled one. */
    Optional<ConcessionOrder> findByReservationId(UUID reservationId);

    /** A page (starting at 1) of at most {@code limit} orders of a user, newest first. */
    OrderPage findPage(UUID userId, int page, int limit);

    /**
     * Stores a confirmed order, commits its hold and writes the event, only if the stored order is still
     * RESERVED. It returns false, changing nothing, when another request resolved it first: that is what makes
     * the effect happen once.
     */
    boolean confirm(ConcessionOrder confirmed, OutboxEvent event);

    /**
     * Stores a cancelled order, releases its hold, gives the held quantities back to the products and writes the
     * event, only if the stored order is still RESERVED. It returns false, changing nothing, otherwise.
     */
    boolean release(ConcessionOrder cancelled, OutboxEvent event);

    /** At most {@code limit} RESERVED orders whose deadline is not after {@code now}, oldest first. */
    List<ConcessionOrder> findExpired(Instant now, int limit);
}
