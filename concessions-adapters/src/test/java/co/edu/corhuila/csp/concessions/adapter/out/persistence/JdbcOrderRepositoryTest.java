package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.OutboxEvent;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.HoldStatus;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderLine;
import co.edu.corhuila.csp.concessions.domain.model.OrderStatus;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import co.edu.corhuila.csp.concessions.domain.model.StockHold;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The SQL of the orders against the real engine, with the schema of csp-concessions-db (Annex C), including the
 * races that ADR-045 requires to be safe. Skipped when TEST_DATABASE_URL is not defined.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcProductRepository products;
    private static JdbcOrderRepository orders;

    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        products = new JdbcProductRepository(jdbc, transactions);
        orders = new JdbcOrderRepository(jdbc, transactions, new ObjectMapper().findAndRegisterModules());
    }

    @AfterAll
    static void closePool() {
        dataSource.close();
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID user : userIds) {
            List<UUID> orderIds = jdbc.queryForList("SELECT id FROM concessions.concession_order WHERE user_id = ?",
                    UUID.class, user);
            for (UUID order : orderIds) {
                jdbc.update("DELETE FROM concessions.outbox_event WHERE aggregate_id = ?", order);
                jdbc.update("DELETE FROM concessions.stock_movement WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.stock_hold_item WHERE hold_id IN "
                        + "(SELECT id FROM concessions.stock_hold WHERE order_id = ?)", order);
                jdbc.update("DELETE FROM concessions.stock_hold WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.concession_order_item WHERE order_id = ?", order);
                jdbc.update("DELETE FROM concessions.concession_order WHERE id = ?", order);
            }
        }
        for (UUID id : productIds) {
            jdbc.update("DELETE FROM concessions.stock_movement WHERE product_id = ?", id);
            jdbc.update("DELETE FROM concessions.product WHERE id = ?", id);
        }
        productIds.clear();
        userIds.clear();
    }

    private Product publishedProduct(String name, long price, int stockUnits) {
        Product product = Product.draft(UUID.randomUUID(), name + " " + UUID.randomUUID(), null, "SNACK", price,
                stockUnits, NOW).publish(NOW);
        productIds.add(product.id());
        return products.create(product, UUID.randomUUID().toString(), "hash").product();
    }

    private UUID newUser() {
        UUID user = UUID.randomUUID();
        userIds.add(user);
        return user;
    }

    /** One line per product, as many units as asked, and the hold that matches. */
    private record Draft(ConcessionOrder order, StockHold hold) {
    }

    private Draft draft(UUID user, UUID reservationId, Object... productAndQuantity) {
        List<OrderLine> lines = new ArrayList<>();
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            Product product = (Product) productAndQuantity[i];
            int quantity = (Integer) productAndQuantity[i + 1];
            lines.add(new OrderLine(OrderItemType.PRODUCT, product.id(), product.name(), product.price(), quantity));
            quantities.put(product.id(), quantity);
        }
        ConcessionOrder order = ConcessionOrder.reserve(UUID.randomUUID(), user, reservationId, lines, TTL, NOW);
        return new Draft(order, StockHold.open(UUID.randomUUID(), order.id(), quantities, NOW));
    }

    private PlacedOrder place(Draft draft, String key) {
        return place(draft, key, "hash-" + key);
    }

    private PlacedOrder place(Draft draft, String key, String hash) {
        ConcessionOrder order = draft.order();
        List<OutboxEvent> events = List.of(
                new OutboxEvent(UUID.randomUUID(), "ConcessionOrderCreated", order.id(), NOW, payload(order)),
                new OutboxEvent(UUID.randomUUID(), "ConcessionStockReserved", order.id(), NOW, payload(order)));
        return orders.create(order, draft.hold(), key, hash, events);
    }

    private static Map<String, Object> payload(ConcessionOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("totalAmount", order.totalAmount());
        return payload;
    }

    private static OutboxEvent event(String type, ConcessionOrder order) {
        return new OutboxEvent(UUID.randomUUID(), type, order.id(), NOW, payload(order));
    }

    private int balanceOf(Product product) {
        return products.findById(product.id()).orElseThrow().availableStock();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int eventsOf(ConcessionOrder order) {
        return count("SELECT count(*) FROM concessions.outbox_event WHERE aggregate_id = ?", order.id());
    }

    private String holdStatusOf(ConcessionOrder order) {
        return jdbc.queryForObject("SELECT status FROM concessions.stock_hold WHERE order_id = ?", String.class,
                order.id());
    }

    @Test
    void anOrderIsStoredWithItsLinesHoldMovementsAndEvents() throws Exception {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        Product soda = publishedProduct("Soda", 800, 5);
        UUID user = newUser();
        Draft draft = draft(user, UUID.randomUUID(), popcorn, 4, soda, 1);

        PlacedOrder placed = place(draft, "key-order-0001");

        assertFalse(placed.replayed());
        assertEquals(draft.order(), orders.findById(draft.order().id()).orElseThrow());
        assertEquals(6, balanceOf(popcorn));
        assertEquals(4, balanceOf(soda));
        assertEquals("HELD", holdStatusOf(draft.order()));
        assertEquals(2, count("SELECT count(*) FROM concessions.stock_hold_item WHERE hold_id = ?", draft.hold().id()));
        assertEquals(2, count("SELECT count(*) FROM concessions.stock_movement WHERE order_id = ? "
                + "AND movement_type = 'RESERVE' AND hold_id = ? AND quantity_delta < 0 AND created_by = ?",
                draft.order().id(), draft.hold().id(), user));
        assertEquals(6, count("SELECT balance_after FROM concessions.stock_movement WHERE order_id = ? AND product_id = ?",
                draft.order().id(), popcorn.id()));
        assertEquals(2, eventsOf(draft.order()));
        String envelope = jdbc.queryForObject("SELECT payload::text FROM concessions.outbox_event "
                + "WHERE aggregate_id = ? AND event_type = 'ConcessionStockReserved'", String.class, draft.order().id());
        JsonNode json = new ObjectMapper().readTree(envelope);
        assertEquals("ConcessionStockReserved", json.path("eventType").asText());
        assertEquals("concessions-service", json.path("source").asText());
        assertEquals("ConcessionOrder", json.path("aggregateType").asText());
        assertEquals(draft.order().id().toString(), json.path("aggregateId").asText());
        assertEquals(1, json.path("version").asInt());
        assertTrue(json.path("metadata").path("correlationId").isTextual());
        assertEquals(draft.order().totalAmount(), json.path("payload").path("totalAmount").asLong());
    }

    @Test
    void anItemWithoutEnoughStockStoresAndTakesNothing() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        Product soda = publishedProduct("Soda", 800, 2);
        Draft draft = draft(newUser(), null, popcorn, 4, soda, 3);

        assertThrows(InsufficientStockException.class, () -> place(draft, "key-order-0002"));

        assertEquals(10, balanceOf(popcorn));
        assertEquals(2, balanceOf(soda));
        assertTrue(orders.findById(draft.order().id()).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM concessions.stock_movement WHERE order_id = ?", draft.order().id()));
        assertEquals(0, eventsOf(draft.order()));
        assertEquals(0, count("SELECT count(*) FROM concessions.stock_hold WHERE order_id = ?", draft.order().id()));
    }

    @Test
    void aProductThatIsNotPublishedAnymoreIsUnavailableAndTakesNothing() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        products.save(popcorn.deactivate(NOW));
        Draft draft = draft(newUser(), null, popcorn, 1);

        assertThrows(OrderItemUnavailableException.class, () -> place(draft, "key-order-0003"));

        assertEquals(10, balanceOf(popcorn));
        assertTrue(orders.findById(draft.order().id()).isEmpty());
    }

    @Test
    void theSameKeyAndHashIsAReplayAndAnotherHashIsAConflict() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        UUID user = newUser();
        Draft first = draft(user, null, popcorn, 2);
        PlacedOrder placed = place(first, "key-order-0004", "hash-a");

        PlacedOrder again = place(draft(user, null, popcorn, 2), "key-order-0004", "hash-a");

        assertTrue(again.replayed());
        assertEquals(placed.order(), again.order());
        assertEquals(8, balanceOf(popcorn));
        assertThrows(IdempotencyKeyConflictException.class,
                () -> place(draft(user, null, popcorn, 2), "key-order-0004", "hash-b"));
        assertEquals(1, count("SELECT count(*) FROM concessions.concession_order WHERE user_id = ?", user));
        assertTrue(orders.findByIdempotencyKey(user, "key-order-0004").isPresent());
        assertTrue(orders.findByIdempotencyKey(newUser(), "key-order-0004").isEmpty());
    }

    @Test
    void aReservationHasOneLiveOrderUntilItIsCancelled() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        UUID reservation = UUID.randomUUID();
        Draft first = draft(newUser(), reservation, popcorn, 2);
        place(first, "key-order-0005");

        assertThrows(ReservationAlreadyOrderedException.class,
                () -> place(draft(newUser(), reservation, popcorn, 1), "key-order-0006"));
        assertEquals(8, balanceOf(popcorn));

        assertTrue(orders.release(first.order().cancel(NOW), event("ConcessionOrderCancelled", first.order())));
        place(draft(newUser(), reservation, popcorn, 1), "key-order-0007");
        assertEquals(OrderStatus.RESERVED, orders.findByReservationId(reservation).orElseThrow().status());
    }

    @Test
    void confirmingCommitsTheHoldOnceAndKeepsTheStock() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        Draft draft = draft(newUser(), UUID.randomUUID(), popcorn, 4);
        place(draft, "key-order-0008");
        ConcessionOrder confirmed = draft.order().confirm(NOW.plusSeconds(60));

        assertTrue(orders.confirm(confirmed, event("ConcessionOrderConfirmed", confirmed)));
        assertFalse(orders.confirm(confirmed, event("ConcessionOrderConfirmed", confirmed)));
        assertFalse(orders.release(draft.order().cancel(NOW), event("ConcessionOrderCancelled", draft.order())));

        assertEquals(OrderStatus.CONFIRMED, orders.findById(draft.order().id()).orElseThrow().status());
        assertEquals(HoldStatus.COMMITTED.name(), holdStatusOf(draft.order()));
        assertEquals(6, balanceOf(popcorn));
        assertEquals(3, eventsOf(draft.order()));
        assertEquals(1, count("SELECT count(*) FROM concessions.stock_movement WHERE order_id = ?", draft.order().id()));
    }

    @Test
    void releasingGivesTheStockBackOnceAndLeavesItsMovement() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        Product soda = publishedProduct("Soda", 800, 5);
        UUID user = newUser();
        Draft draft = draft(user, UUID.randomUUID(), popcorn, 4, soda, 2);
        place(draft, "key-order-0009");
        ConcessionOrder cancelled = draft.order().cancel(NOW.plusSeconds(60));

        assertTrue(orders.release(cancelled, event("ConcessionOrderCancelled", cancelled)));
        assertFalse(orders.release(cancelled, event("ConcessionOrderCancelled", cancelled)));
        assertFalse(orders.confirm(draft.order().confirm(NOW), event("ConcessionOrderConfirmed", draft.order())));

        assertEquals(OrderStatus.CANCELLED, orders.findById(draft.order().id()).orElseThrow().status());
        assertEquals(HoldStatus.RELEASED.name(), holdStatusOf(draft.order()));
        assertEquals(10, balanceOf(popcorn));
        assertEquals(5, balanceOf(soda));
        assertEquals(2, count("SELECT count(*) FROM concessions.stock_movement WHERE order_id = ? "
                + "AND movement_type = 'RELEASE' AND quantity_delta > 0 AND created_by = ?", draft.order().id(), user));
        assertEquals(3, eventsOf(draft.order()));
    }

    @Test
    void anInactiveProductStillGetsItsStockBackWhenAnOrderIsReleased() {
        Product popcorn = publishedProduct("Popcorn", 1500, 10);
        Draft draft = draft(newUser(), UUID.randomUUID(), popcorn, 4);
        place(draft, "key-order-0010");
        products.save(products.findById(popcorn.id()).orElseThrow().deactivate(NOW));

        assertTrue(orders.release(draft.order().cancel(NOW), event("ConcessionOrderCancelled", draft.order())));

        assertEquals(10, balanceOf(popcorn));
    }

    @Test
    void theSweepFindsOnlyReservedOrdersWhoseDeadlinePassed() {
        Product popcorn = publishedProduct("Popcorn", 1500, 50);
        UUID user = newUser();
        Draft stale = draft(user, null, popcorn, 1);
        Draft withReservation = draft(user, UUID.randomUUID(), popcorn, 1);
        place(stale, "key-order-0011");
        place(withReservation, "key-order-0012");

        List<UUID> before = orders.findExpired(NOW.plus(TTL).minusSeconds(1), 100).stream().map(ConcessionOrder::id).toList();
        List<UUID> after = orders.findExpired(NOW.plus(TTL), 100).stream().map(ConcessionOrder::id).toList();

        assertFalse(before.contains(stale.order().id()));
        assertTrue(after.contains(stale.order().id()));
        assertFalse(after.contains(withReservation.order().id()));
        assertTrue(orders.findExpired(NOW.plus(TTL), 0).isEmpty());
    }

    @Test
    void theHistoryOfAUserIsAPageOfItsOwnOrdersNewestFirst() {
        Product popcorn = publishedProduct("Popcorn", 1500, 50);
        UUID user = newUser();
        for (int i = 0; i < 3; i++) {
            Draft draft = draft(user, null, popcorn, 1);
            ConcessionOrder later = new ConcessionOrder(draft.order().id(), user, null, draft.order().lines(),
                    draft.order().totalAmount(), OrderStatus.RESERVED, draft.order().expiresAt(), null, null,
                    NOW.plusSeconds(i), NOW.plusSeconds(i));
            place(new Draft(later, draft.hold()), "key-order-page" + i);
        }
        place(draft(newUser(), null, popcorn, 1), "key-order-other");

        var page = orders.findPage(user, 1, 2);
        var second = orders.findPage(user, 2, 2);

        assertEquals(3, page.total());
        assertEquals(2, page.items().size());
        assertEquals(1, second.items().size());
        assertTrue(page.items().get(0).createdAt().isAfter(page.items().get(1).createdAt()));
        assertEquals(1, page.items().get(0).lines().size());
    }

    @Test
    void twoOrdersForTheLastUnitCannotBothSucceed() throws Exception {
        Product last = publishedProduct("Last", 100, 1);

        List<Boolean> outcomes = race(2, index -> place(draft(newUser(), null, last, 1), "key-race-a-" + index));

        assertEquals(1, outcomes.stream().filter(applied -> applied).count());
        assertEquals(0, balanceOf(last));
        assertEquals(1, count("SELECT count(*) FROM concessions.stock_movement WHERE product_id = ? "
                + "AND movement_type = 'RESERVE'", last.id()));
        assertEquals(1, count("SELECT count(*) FROM concessions.stock_hold_item WHERE product_id = ?", last.id()));
    }

    @Test
    void manyConcurrentOrdersEmptyTheStockExactlyAndNeverBelowZero() throws Exception {
        Product popcorn = publishedProduct("Popcorn", 100, 5);

        List<Boolean> outcomes = race(12, index -> place(draft(newUser(), null, popcorn, 1), "key-race-b-" + index));

        assertEquals(5, outcomes.stream().filter(applied -> applied).count());
        assertEquals(0, balanceOf(popcorn));
        assertEquals(0, count("SELECT count(*) FROM concessions.stock_movement WHERE product_id = ? AND balance_after < 0",
                popcorn.id()));
    }

    @Test
    void ordersThatShareProductsDoNotDeadlock() throws Exception {
        Product popcorn = publishedProduct("Popcorn", 100, 1000);
        Product soda = publishedProduct("Soda", 100, 1000);

        List<Boolean> outcomes = race(10, index -> index % 2 == 0
                ? place(draft(newUser(), null, popcorn, 1, soda, 1), "key-race-c-" + index)
                : place(draft(newUser(), null, soda, 1, popcorn, 1), "key-race-c-" + index));

        assertEquals(10, outcomes.stream().filter(applied -> applied).count());
        assertEquals(990, balanceOf(popcorn));
        assertEquals(990, balanceOf(soda));
    }

    @Test
    void theSameKeySentConcurrentlyCreatesOneOrder() throws Exception {
        Product popcorn = publishedProduct("Popcorn", 100, 10);
        UUID user = newUser();

        race(6, index -> place(draft(user, null, popcorn, 3), "key-race-same"));

        assertEquals(1, count("SELECT count(*) FROM concessions.concession_order WHERE user_id = ?", user));
        assertEquals(7, balanceOf(popcorn));
    }

    @Test
    void aConfirmationAndAReleaseAtTheSameInstantResolveTheOrderOnce() throws Exception {
        Product popcorn = publishedProduct("Popcorn", 100, 10);
        Draft draft = draft(newUser(), UUID.randomUUID(), popcorn, 4);
        place(draft, "key-order-0013");
        ConcessionOrder confirmed = draft.order().confirm(NOW.plusSeconds(1));
        ConcessionOrder cancelled = draft.order().cancel(NOW.plusSeconds(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> confirming = pool.submit(() -> {
            start.await();
            return orders.confirm(confirmed, event("ConcessionOrderConfirmed", confirmed));
        });
        Future<Boolean> releasing = pool.submit(() -> {
            start.await();
            return orders.release(cancelled, event("ConcessionOrderCancelled", cancelled));
        });
        start.countDown();
        boolean confirmedWon = confirming.get();
        boolean releasedWon = releasing.get();
        pool.shutdown();

        assertTrue(confirmedWon ^ releasedWon);
        assertEquals(confirmedWon ? 6 : 10, balanceOf(popcorn));
        assertEquals(3, eventsOf(draft.order()));
        assertEquals(confirmedWon ? "COMMITTED" : "RELEASED", holdStatusOf(draft.order()));
    }

    /** Runs {@code attempts} placements at the same instant; true when one was stored, false when refused for stock. */
    private List<Boolean> race(int attempts, java.util.function.IntFunction<PlacedOrder> place) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            int index = i;
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    place.apply(index);
                    return true;
                } catch (InsufficientStockException refused) {
                    return false;
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            outcomes.add(future.get());
        }
        pool.shutdown();
        return outcomes;
    }
}
