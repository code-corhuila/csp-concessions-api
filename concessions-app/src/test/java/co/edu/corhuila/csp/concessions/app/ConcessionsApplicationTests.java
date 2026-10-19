package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.adapter.in.http.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcessionsApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theCompositionRootStarts() {
        assertNotNull(context.getBean(ConcessionsApplication.class));
    }

    @Test
    void theAdaptersArePartOfTheSameContext() {
        assertNotNull(context.getBean(HealthController.class));
    }

    @Test
    void theHealthOfTheContractAnswersBehindTheBaseOfTheService() {
        // /health on the root URI of TestRestTemplate is /api/v1/concessions/health: the service owns
        // the prefix of concessions-service.yaml.
        ResponseEntity<String> answer = rest.getForEntity("/health", String.class);

        assertEquals(HttpStatus.OK, answer.getStatusCode(), answer.getBody());
        assertTrue(answer.getBody().contains("\"status\":\"ok\""), answer.getBody());
    }
}
