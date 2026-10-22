package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import java.util.UUID;

/** Input port of the order use cases (HU-CONCESSIONS-004). */
public interface OrderUseCases {

    /**
     * Creates a RESERVED order and takes its stock, all or nothing. Repeating the key with the same body returns
     * the original order and takes nothing; with another body it is an idempotency conflict.
     */
    PlacedOrder create(CreateOrderInput input);

    /** An order of the user; another user's order does not exist for the caller. */
    ConcessionOrder get(UUID orderId, UUID userId);

    OrderPage list(UUID userId, int page, int limit);

    /** Cancels an order of the user and releases its stock once; cancelling a cancelled order changes nothing. */
    void cancel(UUID orderId, UUID userId);

    /** The reservation was confirmed: confirms its order, if it has one. Repeating it changes nothing. */
    void confirmByReservation(UUID reservationId);

    /** The reservation expired: cancels its order and releases its stock, if it has one. Repeating it changes nothing. */
    void releaseByReservation(UUID reservationId);

    /** Cancels up to {@code batchSize} orders whose time ran out and returns how many it cancelled. */
    int expireOrders(int batchSize);
}
