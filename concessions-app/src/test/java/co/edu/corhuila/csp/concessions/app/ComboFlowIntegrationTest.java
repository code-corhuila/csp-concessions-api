package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HU-CONCESSIONS-002 end to end: the real filter chain, the controllers, the use cases and the SQL against
 * the real engine with the schema of csp-concessions-db. Skipped when TEST_DATABASE_URL is not defined.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComboFlowIntegrationTest {

    private static final RsaTestKey JWT_KEY = RsaTestKey.generate();
    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JWT_KEY::publicKeyPem);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeWhatThisTestWrote() {
        jdbc.update("DELETE FROM concessions.combo WHERE name LIKE 'Flow combo%'");
        jdbc.update("DELETE FROM concessions.product WHERE name LIKE 'Flow combo%'");
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String admin() {
        return JWT_KEY.token(SUB, List.of("concessions:product:write", "concessions:combo:write"));
    }

    private String client() {
        return JWT_KEY.token(SUB, List.of("concessions:order:create"));
    }

    private String publishedProduct(String name) throws Exception {
        ResponseEntity<String> created = send(HttpMethod.POST, "/products", admin(), UUID.randomUUID().toString(),
                "{\"name\":\"" + name + "\",\"category\":\"SNACK\",\"price\":500,\"availableStock\":5}");
        String id = MAPPER.readTree(created.getBody()).get("id").asText();
        send(HttpMethod.PATCH, "/products/" + id, admin(), UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");
        return id;
    }

    private static String comboBody(String name, String productId, int quantity) {
        return "{\"name\":\"" + name + "\",\"price\":4500,\"items\":[{\"productId\":\"" + productId
                + "\",\"quantity\":" + quantity + "}]}";
    }

    @Test
    void anAdministratorSavesAComboWithItsComponentsAndPublishesItForClients() throws Exception {
        String popcorn = publishedProduct("Flow combo popcorn");
        String soda = publishedProduct("Flow combo soda");
        String body = "{\"name\":\"Flow combo family\",\"price\":4500,\"items\":[{\"productId\":\"" + popcorn
                + "\",\"quantity\":1},{\"productId\":\"" + soda + "\",\"quantity\":2}]}";

        // Scenario 1: the combo is saved with its component references.
        ResponseEntity<String> created = send(HttpMethod.POST, "/combos", admin(), UUID.randomUUID().toString(), body);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        JsonNode combo = MAPPER.readTree(created.getBody());
        String id = combo.get("id").asText();
        assertEquals("DRAFT", combo.get("status").asText());
        assertEquals(2, combo.get("items").size());
        assertTrue(!listedFor(client()).contains(id));

        ResponseEntity<String> published = send(HttpMethod.PATCH, "/combos/" + id, admin(),
                UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");
        assertEquals(HttpStatus.OK, published.getStatusCode(), published.getBody());
        assertTrue(listedFor(client()).contains(id));
    }

    @Test
    void publishingIsRejectedWhenAComponentIsInactiveAndTheComboStaysADraft() throws Exception {
        String popcorn = publishedProduct("Flow combo inactive popcorn");
        ResponseEntity<String> created = send(HttpMethod.POST, "/combos", admin(), UUID.randomUUID().toString(),
                comboBody("Flow combo rejected", popcorn, 1));
        String id = MAPPER.readTree(created.getBody()).get("id").asText();
        send(HttpMethod.PATCH, "/products/" + popcorn, admin(), UUID.randomUUID().toString(),
                "{\"status\":\"INACTIVE\"}");

        // Scenario 2: an inactive component rejects the publication.
        ResponseEntity<String> rejected = send(HttpMethod.PATCH, "/combos/" + id, admin(),
                UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode(), rejected.getBody());
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM concessions.combo WHERE id = ?::uuid",
                String.class, id));
    }

    @Test
    void aNonPositiveQuantityAndAnUnknownProductAreRejectedAndNothingIsStored() throws Exception {
        String popcorn = publishedProduct("Flow combo quantity popcorn");

        ResponseEntity<String> zero = send(HttpMethod.POST, "/combos", admin(), UUID.randomUUID().toString(),
                comboBody("Flow combo zero", popcorn, 0));
        ResponseEntity<String> ghost = send(HttpMethod.POST, "/combos", admin(), UUID.randomUUID().toString(),
                comboBody("Flow combo ghost", UUID.randomUUID().toString(), 1));

        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode(), zero.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ghost.getStatusCode(), ghost.getBody());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.combo WHERE name LIKE 'Flow combo zero' OR name LIKE 'Flow combo ghost'",
                Integer.class));
    }

    @Test
    void theSameKeyReplaysTheOriginalComboAndAClientCannotWrite() throws Exception {
        String popcorn = publishedProduct("Flow combo replay popcorn");
        String key = UUID.randomUUID().toString();
        String body = comboBody("Flow combo replay", popcorn, 1);

        ResponseEntity<String> first = send(HttpMethod.POST, "/combos", admin(), key, body);
        ResponseEntity<String> replay = send(HttpMethod.POST, "/combos", admin(), key, body);
        ResponseEntity<String> other = send(HttpMethod.POST, "/combos", admin(), key, body.replace("4500", "5000"));
        ResponseEntity<String> forbidden = send(HttpMethod.POST, "/combos", client(), UUID.randomUUID().toString(),
                comboBody("Flow combo forbidden", popcorn, 1));

        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(HttpStatus.CONFLICT, other.getStatusCode(), other.getBody());
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(), forbidden.getBody());
    }

    private String listedFor(String token) {
        return send(HttpMethod.GET, "/combos?limit=100", token, null, null).getBody();
    }
}
