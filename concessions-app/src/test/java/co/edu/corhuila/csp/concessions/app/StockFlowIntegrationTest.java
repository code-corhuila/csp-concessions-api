package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * HU-CONCESSIONS-003 end to end: the real filter chain, the controller, the use case and the SQL against the
 * real engine with the schema of csp-concessions-db. Skipped when TEST_DATABASE_URL is not defined.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockFlowIntegrationTest {

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
        jdbc.update("DELETE FROM concessions.stock_movement WHERE product_id IN "
                + "(SELECT id FROM concessions.product WHERE name LIKE 'Flow stock %')");
        jdbc.update("DELETE FROM concessions.product WHERE name LIKE 'Flow stock %'");
    }

    private ResponseEntity<String> send(String path, String token, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String inventoryAdmin() {
        return JWT_KEY.token(SUB, List.of("concessions:product:write", "concessions:inventory:write"));
    }

    private String createProduct(String name, int stock) throws Exception {
        String token = JWT_KEY.token(SUB, List.of("concessions:product:write"));
        ResponseEntity<String> created = send("/products", token, UUID.randomUUID().toString(),
                "{\"name\":\"" + name + "\",\"category\":\"SNACK\",\"price\":100,\"availableStock\":" + stock + "}");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return MAPPER.readTree(created.getBody()).get("id").asText();
    }

    private ResponseEntity<String> adjust(String productId, int delta, String key) {
        return send("/stock/adjust", inventoryAdmin(), key,
                "{\"productId\":\"" + productId + "\",\"quantityDelta\":" + delta + "}");
    }

    private int stockOf(String productId) {
        return jdbc.queryForObject("SELECT available_stock FROM concessions.product WHERE id = ?::uuid",
                Integer.class, productId);
    }

    @Test
    void scenario1AReceiptRaisesTheBalanceAndLeavesAMovement() throws Exception {
        String id = createProduct("Flow stock receipt", 10);

        ResponseEntity<String> response = adjust(id, 5, UUID.randomUUID().toString());

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(), response.getBody());
        assertEquals(15, stockOf(id));
        JsonNode movement = MAPPER.valueToTree(jdbc.queryForMap(
                "SELECT quantity_delta, balance_after, movement_type, created_by::text AS created_by "
                        + "FROM concessions.stock_movement WHERE product_id = ?::uuid", id));
        assertEquals(5, movement.get("quantity_delta").asInt());
        assertEquals(15, movement.get("balance_after").asInt());
        assertEquals("RECEIPT", movement.get("movement_type").asText());
        assertEquals(SUB, movement.get("created_by").asText());
    }

    @Test
    void scenario2AWithdrawalBeyondTheBalanceIsRefusedAndTheBalanceNeverGoesNegative() throws Exception {
        String id = createProduct("Flow stock negative", 3);

        ResponseEntity<String> response = adjust(id, -4, UUID.randomUUID().toString());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), response.getBody());
        assertEquals("INSUFFICIENT_STOCK", MAPPER.readTree(response.getBody()).get("error").asText());
        assertEquals(3, stockOf(id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM concessions.stock_movement "
                + "WHERE product_id = ?::uuid", Integer.class, id));
    }

    @Test
    void aRetryWithTheSameKeyAppliesOnceAndAnotherBodyWithTheKeyIsAConflict() throws Exception {
        String id = createProduct("Flow stock retry", 10);
        String key = UUID.randomUUID().toString();

        assertEquals(HttpStatus.NO_CONTENT, adjust(id, 5, key).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, adjust(id, 5, key).getStatusCode());
        ResponseEntity<String> other = adjust(id, 6, key);

        assertEquals(15, stockOf(id));
        assertEquals(HttpStatus.CONFLICT, other.getStatusCode(), other.getBody());
        assertEquals("CONFLICT", MAPPER.readTree(other.getBody()).get("error").asText());
    }

    @Test
    void anUnknownProductIs404AnInactiveOneIs422AndAZeroChangeIs400() throws Exception {
        String id = createProduct("Flow stock inactive", 3);
        String admin = JWT_KEY.token(SUB, List.of("concessions:product:write"));
        send("/products/" + id, admin, UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");

        ResponseEntity<String> unknown = adjust(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString());
        ResponseEntity<String> zero = adjust(id, 0, UUID.randomUUID().toString());

        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode(), unknown.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode(), zero.getBody());
        jdbc.update("UPDATE concessions.product SET status = 'INACTIVE' WHERE id = ?::uuid", id);
        ResponseEntity<String> inactive = adjust(id, 1, UUID.randomUUID().toString());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, inactive.getStatusCode(), inactive.getBody());
    }

    @Test
    void theProductWritePermissionAloneCannotMoveStock() throws Exception {
        String id = createProduct("Flow stock forbidden", 3);
        String onlyProducts = JWT_KEY.token(SUB, List.of("concessions:product:write"));

        ResponseEntity<String> response = send("/stock/adjust", onlyProducts, UUID.randomUUID().toString(),
                "{\"productId\":\"" + id + "\",\"quantityDelta\":1}");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals(3, stockOf(id));
    }

    @Test
    void aShortKeyIsRejectedBeforeAnythingIsStored() throws Exception {
        String id = createProduct("Flow stock short key", 3);

        ResponseEntity<String> response = adjust(id, 1, "short");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), response.getBody());
        assertEquals(3, stockOf(id));
    }
}
