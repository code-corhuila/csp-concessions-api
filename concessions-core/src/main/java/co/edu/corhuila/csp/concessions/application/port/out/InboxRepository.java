package co.edu.corhuila.csp.concessions.application.port.out;

import java.util.UUID;

/**
 * Output port: the reservation events already applied (HU-CONCESSIONS-005, ADR-063). It is written in the
 * transaction of the change the event causes, so an event is recorded exactly when its effect is.
 */
public interface InboxRepository {

    /**
     * Records the event. True when it was new; false when the same {@code eventId} was recorded before. Two
     * concurrent calls for one {@code eventId} are serialized: the second waits for the transaction of the first
     * and answers false if that one committed.
     */
    boolean register(UUID eventId, String eventType);
}
