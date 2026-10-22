package co.edu.corhuila.csp.concessions.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stock reserved for one order (HU-CONCESSIONS-004, ADR-045). It is immutable and resolved once: HELD
 * becomes COMMITTED when the order is confirmed or RELEASED when it is cancelled, and any other move is an
 * {@link InvalidStatusTransitionException}. Its products are kept in ascending id order so that every
 * transaction that locks them does it in the same order and two orders cannot deadlock.
 */
public record StockHold(
        UUID id,
        UUID orderId,
        HoldStatus status,
        List<HeldProduct> items,
        Instant createdAt,
        Instant resolvedAt) {

    public StockHold {
        if (id == null || orderId == null || status == null || createdAt == null) {
            throw new InvalidOrderException("a hold needs an id, an order, a status and its date");
        }
        if (items == null || items.isEmpty()) {
            throw new InvalidOrderException("a hold needs at least one product");
        }
        items = List.copyOf(items);
    }

    /** Opens a HELD hold for the quantities of each product, already added up. */
    public static StockHold open(UUID id, UUID orderId, Map<UUID, Integer> quantities, Instant now) {
        List<HeldProduct> items = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.toString().compareTo(b.toString())))
                .map(entry -> new HeldProduct(entry.getKey(), entry.getValue()))
                .toList();
        return new StockHold(id, orderId, HoldStatus.HELD, items, now, null);
    }

    /** HELD becomes COMMITTED: the reserved stock is kept for the confirmed order. */
    public StockHold commit(Instant now) {
        return resolve(HoldStatus.COMMITTED, now);
    }

    /** HELD becomes RELEASED: the reserved stock goes back to the products. */
    public StockHold release(Instant now) {
        return resolve(HoldStatus.RELEASED, now);
    }

    private StockHold resolve(HoldStatus next, Instant now) {
        if (status != HoldStatus.HELD) {
            throw new InvalidStatusTransitionException("the hold is already resolved");
        }
        return new StockHold(id, orderId, next, items, createdAt, now);
    }
}
