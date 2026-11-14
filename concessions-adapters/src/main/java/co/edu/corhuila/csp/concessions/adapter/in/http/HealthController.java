package co.edu.corhuila.csp.concessions.adapter.in.http;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two operational routes that {@code concessions-service.yaml} declares without authentication.
 * Liveness never depends on PostgreSQL or RabbitMQ; readiness asks the pool for one connection and
 * answers the envelope of the contract when the database cannot be reached.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Liveness: the process answers, whatever the state of its dependencies. */
    @GetMapping("/health")
    public Map<String, String> liveness() {
        return Map.of("status", "ok");
    }

    /** Readiness: PostgreSQL reachable through the pool, inside the statement timeout. */
    @GetMapping("/health/ready")
    public ResponseEntity<Object> readiness() {
        if (postgresIsReachable()) {
            return ResponseEntity.ok(Map.of("status", "ready", "dependencies", Map.of("postgres", "connected")));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("SERVICE_UNAVAILABLE", "A required service or dependency is unavailable"));
    }

    private boolean postgresIsReachable() {
        try (Connection connection = dataSource.getConnection()) {
            // isValid asks the server for a ping: one round trip, never a business query.
            return connection.isValid(2);
        } catch (SQLException exception) {
            log.warn("readiness: PostgreSQL cannot be reached: {}", exception.getMessage());
            return false;
        }
    }
}
