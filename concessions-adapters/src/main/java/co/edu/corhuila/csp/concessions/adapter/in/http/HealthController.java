package co.edu.corhuila.csp.concessions.adapter.in.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The liveness route that {@code concessions-service.yaml} declares without authentication. It never
 * depends on PostgreSQL or RabbitMQ: it only says that the process answers. Readiness arrives with the
 * persistence adapter, which is what it checks.
 */
@RestController
public class HealthController {

    /** Liveness: the process answers, whatever the state of its dependencies. */
    @GetMapping("/health")
    public Map<String, String> liveness() {
        return Map.of("status", "ok");
    }
}
