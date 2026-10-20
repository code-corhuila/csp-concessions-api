package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.adapter.in.http.CorrelationIdFilter;
import co.edu.corhuila.csp.concessions.adapter.in.http.HealthController;
import co.edu.corhuila.csp.concessions.adapter.in.http.JwtAuthenticationFilter;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcessionsApplicationTests {

    /** The key of this context is generated, never committed: it stands for JWT_PUBLIC_KEY. */
    private static final RsaTestKey JWT_KEY = RsaTestKey.generate();

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JWT_KEY::publicKeyPem);
    }

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

    @Test
    void theFiltersAndTheKeyOfTheServiceArePartOfTheContext() {
        assertNotNull(context.getBean(CorrelationIdFilter.class));
        assertNotNull(context.getBean(JwtAuthenticationFilter.class));
        assertNotNull(context.getBean(RSAPublicKey.class));
    }

    @Test
    void aRouteWithoutTokenAnswersTheEnvelopeOfTheContract() {
        ResponseEntity<String> answer = rest.getForEntity("/products", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, answer.getStatusCode());
        assertTrue(answer.getBody().contains("\"error\":\"UNAUTHORIZED\""), answer.getBody());
    }
}
