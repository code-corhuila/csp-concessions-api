package co.edu.corhuila.csp.concessions.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A client's snack order (HU-CONCESSIONS-004, ADR-045). It is immutable: every transition returns a new
 * instance, so a rejected rule changes nothing. Money is an integer in cents, and the total is always the
 * sum of its lines, which are a snapshot taken when the order was created.
 *
 * <p>An order is born RESERVED and ends CONFIRMED or CANCELLED, once; any other move, including repeating the
 * current one, is an {@link InvalidStatusTransitionException}. {@code reservationId} is a Booking identifier
 * kept for correlation only. An order without it expires on its own after the hold time. A malformed order is
 * an {@link InvalidOrderException}, thrown by the constructor.
 */
public record ConcessionOrder(
        UUID id,
        UUID userId,
        UUID reservationId,
        List<OrderLine> lines,
        long totalAmount,
        OrderStatus status,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final int LINES_MAX = 20;
    public static final int QUANTITY_MAX = 20;

    public ConcessionOrder {
        if (id == null || userId == null || status == null || createdAt == null || updatedAt == null) {
            throw new InvalidOrderException("an order needs an id, a user, a status and its dates");
        }
        lines = validLines(lines);
        if (totalAmount != sumOf(lines)) {
            throw new InvalidOrderException("the total of the order does not match its lines");
        }
    }

    /** Creates a RESERVED order; without a reservation it expires {@code holdTime} after {@code now}. */
    public static ConcessionOrder reserve(UUID id, UUID userId, UUID reservationId, List<OrderLine> lines,
                                          Duration holdTime, Instant now) {
        List<OrderLine> valid = validLines(lines);
        Instant expiresAt = reservationId == null ? now.plus(holdTime) : null;
        return new ConcessionOrder(id, userId, reservationId, valid, sumOf(valid), OrderStatus.RESERVED, expiresAt,
                null, null, now, now);
    }

    /** RESERVED becomes CONFIRMED. */
    public ConcessionOrder confirm(Instant now) {
        requireReserved("confirmed");
        return new ConcessionOrder(id, userId, reservationId, lines, totalAmount, OrderStatus.CONFIRMED, expiresAt,
                now, cancelledAt, createdAt, now);
    }

    /** RESERVED becomes CANCELLED. */
    public ConcessionOrder cancel(Instant now) {
        requireReserved("cancelled");
        return new ConcessionOrder(id, userId, reservationId, lines, totalAmount, OrderStatus.CANCELLED, expiresAt,
                confirmedAt, now, createdAt, now);
    }

    /** A cancelled order frees its reservation for a new one. */
    public boolean isLive() {
        return status != OrderStatus.CANCELLED;
    }

    public boolean belongsTo(UUID user) {
        return userId.equals(user);
    }

    private void requireReserved(String action) {
        if (status != OrderStatus.RESERVED) {
            throw new InvalidStatusTransitionException("only a reserved order can be " + action);
        }
    }

    private static List<OrderLine> validLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > LINES_MAX) {
            throw new InvalidOrderException("an order has between 1 and " + LINES_MAX + " lines");
        }
        Set<String> seen = new HashSet<>();
        for (OrderLine line : lines) {
            if (!seen.add(line.itemType() + ":" + line.itemId())) {
                throw new InvalidOrderException("an item cannot appear twice in the same order");
            }
        }
        return List.copyOf(lines);
    }

    private static long sumOf(List<OrderLine> lines) {
        long total = 0;
        for (OrderLine line : lines) {
            total = Math.addExact(total, line.subtotal());
        }
        return total;
    }
}
