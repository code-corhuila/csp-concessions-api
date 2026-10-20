package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.adapter.in.http.CorrelationIdFilter;
import co.edu.corhuila.csp.concessions.adapter.in.http.HealthController;
import co.edu.corhuila.csp.concessions.adapter.in.http.JwtAuthenticationFilter;
import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Test
    void theUseCasesAreWiredWithTheirPortsAndTheirClock() {
        assertNotNull(context.getBean(ProductUseCases.class));
        assertNotNull(context.getBean(Clock.class));
    }

    @Test
    void theValidationMessagesAreInEnglishWhateverTheLanguageOfTheClient() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept-Language", "es");
        headers.set("Authorization", "Bearer "
                + JWT_KEY.token("11111111-1111-4111-8111-111111111111", List.of("concessions:product:write")));
        headers.set("Idempotency-Key", "0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71");

        ResponseEntity<String> answer = rest.exchange("/products", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"\",\"price\":-1}", headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode(), answer.getBody());
        assertTrue(answer.getBody().contains("must not be blank"), answer.getBody());
        assertTrue(answer.getBody().contains("must be greater than or equal to 0"), answer.getBody());
    }

    @Test
    void theServiceStartsWithoutPostgresAndReadinessSaysSo() {
        ResponseEntity<String> ready = rest.getForEntity("/health/ready", String.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ready.getStatusCode(), ready.getBody());
    }
}
