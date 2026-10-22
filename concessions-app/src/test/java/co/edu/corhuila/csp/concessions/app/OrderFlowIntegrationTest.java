package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
 * HU-CONCESSIONS-004 end to end: the real filter chain, the controllers, the use cases and the SQL against the
 * real engine with the schema of csp-concessions-db. Skipped when TEST_DATABASE_URL is not defined.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIntegrationTest {

    private static final RsaTestKey JWT_KEY = RsaTestKey.generate();
    private static final String ADMIN_SUB = "11111111-1111-4111-8111-111111111111";
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

    private final List<String> clients = new ArrayList<>();

    @AfterEach
    void removeWhatThisTestWrote() {
        for (String client : clients) {
            for (UUID order : jdbc.queryForList("SELECT id FROM concessions.concession_order WHERE user_id = ?::uuid",
                    UUID.class, client)) {
                jdbc.update("DELETE FROM concessions.outbox_event WHERE aggregate_id = ?", order);
                jdbc.update("DELETE FROM concessions.stock_movement WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.stock_hold_item WHERE hold_id IN "
                        + "(SELECT id FROM concessions.stock_hold WHERE order_id = ?)", order);
                jdbc.update("DELETE FROM concessions.stock_hold WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.concession_order_item WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.concession_order WHERE id = ?", order);
            }
        }
        clients.clear();
        jdbc.update("DELETE FROM concessions.combo WHERE name LIKE 'Flow order%'");
        jdbc.update("DELETE FROM concessions.product WHERE name LIKE 'Flow order%'");
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
        return JWT_KEY.token(ADMIN_SUB, List.of("concessions:product:write", "concessions:combo:write"));
    }

    private String newClient() {
        String sub = UUID.randomUUID().toString();
        clients.add(sub);
        return sub;
    }

    private String clientToken(String sub) {
        return JWT_KEY.token(sub, List.of("concessions:order:create", "concessions:order:read:self",
                "concessions:order:cancel:self"));
    }

    private String worker() {
        return JWT_KEY.token("csp-worker");
    }

    private String publishedProduct(String name, int stock) throws Exception {
        ResponseEntity<String> created = send(HttpMethod.POST, "/products", admin(), UUID.randomUUID().toString(),
                "{\"name\":\"" + name + "\",\"category\":\"SNACK\",\"price\":1500,\"availableStock\":" + stock + "}");
        String id = MAPPER.readTree(created.getBody()).get("id").asText();
        send(HttpMethod.PATCH, "/products/" + id, admin(), UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");
        return id;
    }

    private static String order(String reservationId, String... lines) {
        String reservation = reservationId == null ? "" : "\"reservationId\":\"" + reservationId + "\",";
        return "{" + reservation + "\"items\":[" + String.join(",", lines) + "]}";
    }

    private static String line(String type, String id, int quantity) {
        return "{\"itemType\":\"" + type + "\",\"itemId\":\"" + id + "\",\"quantity\":" + quantity + "}";
    }

    private int stockOf(String productId) {
        return jdbc.queryForObject("SELECT available_stock FROM concessions.product WHERE id = ?::uuid",
                Integer.class, productId);
    }

    private ResponseEntity<String> place(String sub, String key, String body) {
        return send(HttpMethod.POST, "/orders", clientToken(sub), key, body);
    }

    @Test
    void scenario1AnOrderIsReservedWithItsSnapshotAndTheEventIsWritten() throws Exception {
        String popcorn = publishedProduct("Flow order popcorn", 10);
        String sub = newClient();

        ResponseEntity<String> created = place(sub, "key-flow-0001", order(UUID.randomUUID().toString(),
                line("PRODUCT", popcorn, 2)));

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        JsonNode body = MAPPER.readTree(created.getBody());
        assertEquals("RESERVED", body.get("status").asText());
        assertEquals(3000, body.get("totalAmount").asLong());
        assertEquals("Flow order popcorn", body.get("items").get(0).get("itemName").asText());
        assertEquals(8, stockOf(popcorn));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM concessions.outbox_event "
                + "WHERE aggregate_id = ?::uuid AND event_type = 'ConcessionStockReserved'", Integer.class,
                body.get("id").asText()));
        ResponseEntity<String> read = send(HttpMethod.GET, "/orders/" + body.get("id").asText(), clientToken(sub),
                null, null);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
    }

    @Test
    void aComboOrderReservesItsComponents() throws Exception {
        String popcorn = publishedProduct("Flow order combo popcorn", 10);
        String soda = publishedProduct("Flow order combo soda", 10);
        ResponseEntity<String> combo = send(HttpMethod.POST, "/combos", admin(), UUID.randomUUID().toString(),
                "{\"name\":\"Flow order family\",\"price\":4000,\"items\":[{\"productId\":\"" + popcorn
                        + "\",\"quantity\":2},{\"productId\":\"" + soda + "\",\"quantity\":1}]}");
        String comboId = MAPPER.readTree(combo.getBody()).get("id").asText();
        send(HttpMethod.PATCH, "/combos/" + comboId, admin(), UUID.randomUUID().toString(), "{\"status\":\"PUBLISHED\"}");

        ResponseEntity<String> created = place(newClient(), "key-flow-0002", order(null, line("COMBO", comboId, 3)));

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(12000, MAPPER.readTree(created.getBody()).get("totalAmount").asLong());
        assertEquals(4, stockOf(popcorn));
        assertEquals(7, stockOf(soda));
        assertTrue(MAPPER.readTree(created.getBody()).hasNonNull("expiresAt"));
    }

    @Test
    void scenario2AnItemWithoutStockRejectsTheWholeOrderAndReservesNothing() throws Exception {
        String popcorn = publishedProduct("Flow order enough", 10);
        String soda = publishedProduct("Flow order short", 1);
        String sub = newClient();

        ResponseEntity<String> rejected = place(sub, "key-flow-0003", order(null, line("PRODUCT", popcorn, 2),
                line("PRODUCT", soda, 2)));

        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode(), rejected.getBody());
        assertEquals("INSUFFICIENT_STOCK", MAPPER.readTree(rejected.getBody()).get("error").asText());
        assertEquals(10, stockOf(popcorn));
        assertEquals(1, stockOf(soda));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM concessions.concession_order WHERE user_id = ?::uuid",
                Integer.class, sub));
    }

    @Test
    void scenario3TheReservationEventsConfirmOrReleaseTheOrderOnce() throws Exception {
        String popcorn = publishedProduct("Flow order events", 10);
        String confirmedReservation = UUID.randomUUID().toString();
        String expiredReservation = UUID.randomUUID().toString();
        String sub = newClient();
        String confirmed = MAPPER.readTree(place(sub, "key-flow-0004", order(confirmedReservation,
                line("PRODUCT", popcorn, 3))).getBody()).get("id").asText();
        String expired = MAPPER.readTree(place(sub, "key-flow-0005", order(expiredReservation,
                line("PRODUCT", popcorn, 2))).getBody()).get("id").asText();
        assertEquals(5, stockOf(popcorn));

        for (int i = 0; i < 2; i++) {
            assertEquals(HttpStatus.NO_CONTENT, send(HttpMethod.POST,
                    "/internal/orders/by-reservation/" + confirmedReservation + "/confirm", worker(), null, null)
                    .getStatusCode());
            assertEquals(HttpStatus.NO_CONTENT, send(HttpMethod.POST,
                    "/internal/orders/by-reservation/" + expiredReservation + "/release", worker(), null, null)
                    .getStatusCode());
        }

        assertEquals("CONFIRMED", statusOf(confirmed));
        assertEquals("CANCELLED", statusOf(expired));
        assertEquals(7, stockOf(popcorn));
        assertEquals(1, eventCount(confirmed, "ConcessionOrderConfirmed"));
        assertEquals(1, eventCount(expired, "ConcessionOrderCancelled"));
        assertEquals(HttpStatus.CONFLICT, send(HttpMethod.POST,
                "/internal/orders/by-reservation/" + confirmedReservation + "/release", worker(), null, null)
                .getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, send(HttpMethod.POST,
                "/internal/orders/by-reservation/" + UUID.randomUUID() + "/confirm", worker(), null, null)
                .getStatusCode());
    }

    @Test
    void scenario4SeatBookingNeedsNoOrderAndTheInternalRoutesAreForTheWorkerOnly() throws Exception {
        String reservation = UUID.randomUUID().toString();

        assertEquals(HttpStatus.NO_CONTENT, send(HttpMethod.POST,
                "/internal/orders/by-reservation/" + reservation + "/confirm", worker(), null, null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST,
                "/internal/orders/by-reservation/" + reservation + "/confirm", clientToken(newClient()), null, null)
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/orders", worker(), "key-flow-0006",
                order(null, line("PRODUCT", UUID.randomUUID().toString(), 1))).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/internal/orders/expire", worker(), null, null)
                .getStatusCode());
    }

    @Test
    void aRetryReplaysAndAnotherUserCannotSeeOrCancelTheOrder() throws Exception {
        String popcorn = publishedProduct("Flow order retry", 10);
        String sub = newClient();
        String body = order(null, line("PRODUCT", popcorn, 2));
        String id = MAPPER.readTree(place(sub, "key-flow-0007", body).getBody()).get("id").asText();

        ResponseEntity<String> replay = place(sub, "key-flow-0007", body);
        ResponseEntity<String> conflict = place(sub, "key-flow-0007", order(null, line("PRODUCT", popcorn, 3)));
        String stranger = clientToken(newClient());

        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());
        assertEquals(id, MAPPER.readTree(replay.getBody()).get("id").asText());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals(8, stockOf(popcorn));
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/orders/" + id, stranger, null, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST, "/orders/" + id + "/cancel", stranger,
                "key-flow-0008", null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, place(sub, "short", body).getStatusCode());
    }

    @Test
    void aClientCancelsItsOrderAndTheStockComesBackOnce() throws Exception {
        String popcorn = publishedProduct("Flow order cancel", 10);
        String sub = newClient();
        String id = MAPPER.readTree(place(sub, "key-flow-0009", order(null, line("PRODUCT", popcorn, 4))).getBody())
                .get("id").asText();

        for (int i = 0; i < 2; i++) {
            assertEquals(HttpStatus.NO_CONTENT, send(HttpMethod.POST, "/orders/" + id + "/cancel", clientToken(sub),
                    "key-flow-0010", null).getStatusCode());
        }

        assertEquals("CANCELLED", statusOf(id));
        assertEquals(10, stockOf(popcorn));
        assertEquals(1, eventCount(id, "ConcessionOrderCancelled"));
        ResponseEntity<String> history = send(HttpMethod.GET, "/orders", clientToken(sub), null, null);
        assertEquals(1, MAPPER.readTree(history.getBody()).get("meta").get("total").asInt());
    }

    private String statusOf(String orderId) {
        return jdbc.queryForObject("SELECT status FROM concessions.concession_order WHERE id = ?::uuid", String.class,
                orderId);
    }

    private int eventCount(String orderId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM concessions.outbox_event WHERE aggregate_id = ?::uuid "
                + "AND event_type = ?", Integer.class, orderId, type);
    }
}
