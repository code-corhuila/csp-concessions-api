package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.InboxRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the inbox (ADR-063). The insert is a plain {@code ON CONFLICT DO NOTHING} on the primary key: a second
 * transaction inserting the same {@code eventId} waits for the first to end, so it learns whether the event was
 * kept (nothing stored, a redelivery) or rolled back (stored, the event is still to be applied). It runs in the
 * transaction of the caller, which is the one that changes the order.
 */
@Repository
public class JdbcInboxRepository implements InboxRepository {

    private static final String INSERT_EVENT = """
            INSERT INTO concessions.inbox_event (event_id, event_type)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public JdbcInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean register(UUID eventId, String eventType) {
        return jdbc.update(INSERT_EVENT, eventId, eventType) == 1;
    }
}
