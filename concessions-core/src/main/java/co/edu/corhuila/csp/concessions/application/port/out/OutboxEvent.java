package co.edu.corhuila.csp.concessions.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A domain event to write to the outbox in the transaction of the change (Norma 5.3.11). {@code id} is the
 * {@code eventId}; the adapter wraps the payload in the platform envelope and serialises it.
 */
public record OutboxEvent(UUID id, String eventType, UUID aggregateId, Instant occurredAt, Map<String, Object> payload) {
}
