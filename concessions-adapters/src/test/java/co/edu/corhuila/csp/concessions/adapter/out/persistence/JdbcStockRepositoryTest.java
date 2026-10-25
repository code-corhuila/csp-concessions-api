package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.in.StockMovementQuery;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.StockMovementEntry;
import co.edu.corhuila.csp.concessions.application.port.out.StockMovementPage;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import co.edu.corhuila.csp.concessions.domain.model.StockNotAdjustableException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * The SQL of the stock against the real engine, with the schema of csp-concessions-db (Annex C), including
 * the race that ADR-043 requires to be safe. Skipped when TEST_DATABASE_URL is not defined.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcStockRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcProductRepository products;
    private static JdbcStockRepository stock;

    private final List<UUID> written = new ArrayList<>();

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        products = new JdbcProductRepository(jdbc, transactions);
        stock = new JdbcStockRepository(jdbc, transactions);
    }

    @AfterAll
    static void closePool() {
        dataSource.close();
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID id : written) {
            jdbc.update("DELETE FROM concessions.stock_movement WHERE product_id = ?", id);
            jdbc.update("DELETE FROM concessions.product WHERE id = ?", id);
        }
        written.clear();
    }

    private Product productWith(int stockUnits) {
        Product product = Product.draft(UUID.randomUUID(), "Stock repo " + UUID.randomUUID(), null, "SNACK", 100,
                stockUnits, NOW);
        written.add(product.id());
        return products.create(product, UUID.randomUUID().toString(), "hash").product();
    }

    private static StockMovement movement(UUID productId, int delta, int expectedBalance, String key) {
        return new StockMovement(UUID.randomUUID(), productId, delta, expectedBalance, MovementType.forDelta(delta),
                key, ADMIN, NOW);
    }

    private int balanceOf(UUID productId) {
        return products.findById(productId).orElseThrow().availableStock();
    }

    private int movementsOf(UUID productId) {
        return jdbc.queryForObject("SELECT count(*) FROM concessions.stock_movement WHERE product_id = ?",
                Integer.class, productId);
    }

    @Test
    void aReceiptRaisesTheBalanceAndIsStoredWithTheBalanceItLeft() {
        Product product = productWith(10);

        StockMovement stored = stock.append(movement(product.id(), 5, 15, "key-repo-0001"));

        assertEquals(15, balanceOf(product.id()));
        assertEquals(15, stored.balanceAfter());
        assertEquals(stored, stock.findByIdempotencyKey("key-repo-0001").orElseThrow());
        assertEquals(1, movementsOf(product.id()));
    }

    @Test
    void aWithdrawalLargerThanTheBalanceChangesAndStoresNothing() {
        Product product = productWith(3);

        assertThrows(InsufficientStockException.class, () -> stock.append(movement(product.id(), -4, 0, "key-repo-0002")));

        assertEquals(3, balanceOf(product.id()));
        assertEquals(0, movementsOf(product.id()));
        assertTrue(stock.findByIdempotencyKey("key-repo-0002").isEmpty());
    }

    @Test
    void anInactiveOrUnknownProductTakesNothing() {
        Product product = productWith(3);
        products.save(product.publish(NOW).deactivate(NOW));

        assertThrows(StockNotAdjustableException.class, () -> stock.append(movement(product.id(), 1, 4, "key-repo-0003")));
        assertThrows(ProductNotFoundException.class,
                () -> stock.append(movement(UUID.randomUUID(), 1, 1, "key-repo-0004")));
        assertEquals(3, balanceOf(product.id()));
    }

    @Test
    void theSameKeyAppliedTwiceIsAReplayAndAnotherBodyIsAConflict() {
        Product product = productWith(10);
        StockMovement first = stock.append(movement(product.id(), 5, 15, "key-repo-0005"));

        StockMovement again = stock.append(movement(product.id(), 5, 20, "key-repo-0005"));

        assertEquals(first, again);
        assertEquals(15, balanceOf(product.id()));
        assertThrows(IdempotencyKeyConflictException.class,
                () -> stock.append(movement(product.id(), 6, 21, "key-repo-0005")));
        assertEquals(1, movementsOf(product.id()));
    }

    @Test
    void twoWithdrawalsThatTogetherExceedTheBalanceCannotBothSucceed() throws Exception {
        Product product = productWith(10);

        List<Boolean> outcomes = race(2, index -> stock.append(movement(product.id(), -6, 4, "key-race-a-" + index)));

        assertEquals(1, outcomes.stream().filter(applied -> applied).count());
        assertEquals(4, balanceOf(product.id()));
        assertEquals(1, movementsOf(product.id()));
    }

    @Test
    void manyConcurrentUnitWithdrawalsEmptyTheBalanceExactlyAndNeverBelowZero() throws Exception {
        Product product = productWith(5);

        List<Boolean> outcomes = race(12, index -> stock.append(movement(product.id(), -1, 4, "key-race-b-" + index)));

        assertEquals(5, outcomes.stream().filter(applied -> applied).count());
        assertEquals(0, balanceOf(product.id()));
        assertEquals(5, movementsOf(product.id()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.stock_movement WHERE product_id = ? AND balance_after < 0",
                Integer.class, product.id()));
    }

    @Test
    void theSameKeySentConcurrentlyIsAppliedOnce() throws Exception {
        Product product = productWith(10);

        race(6, index -> stock.append(movement(product.id(), 3, 13, "key-race-same")));

        assertEquals(13, balanceOf(product.id()));
        assertEquals(1, movementsOf(product.id()));
    }

    /** Runs {@code attempts} appends at the same instant; true when one was applied, false when refused. */
    private List<Boolean> race(int attempts, java.util.function.IntFunction<StockMovement> append) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            int index = i;
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    append.apply(index);
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

    /** A row written straight to the table, as another use case would: the history reads it as it was stored. */
    private void store(UUID productId, int delta, String type, Instant at) {
        jdbc.update("""
                INSERT INTO concessions.stock_movement
                    (id, product_id, quantity_delta, balance_after, movement_type, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), productId, delta, 100, type, ADMIN, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private static StockMovementQuery of(UUID productId, int page, int limit, Instant from, Instant to) {
        return new StockMovementQuery(page, limit, productId, from, to);
    }

    private static List<Instant> instants(StockMovementPage page) {
        return page.items().stream().map(StockMovementEntry::timestamp).toList();
    }

    @Test
    void theHistoryOfAProductIsNewestFirstWithTheNameAndTheTypeAsReason() {
        Product product = productWith(10);
        store(product.id(), -2, "ADJUSTMENT", NOW.minusSeconds(200));
        store(product.id(), 5, "RECEIPT", NOW.minusSeconds(300));
        store(product.id(), 7, "RECEIPT", NOW.minusSeconds(100));

        StockMovementPage page = stock.findMovements(of(product.id(), 1, 20, null, null));

        assertEquals(3, page.total());
        assertEquals(List.of(NOW.minusSeconds(100), NOW.minusSeconds(200), NOW.minusSeconds(300)), instants(page));
        StockMovementEntry newest = page.items().get(0);
        assertEquals(product.id(), newest.productId());
        assertEquals(product.name(), newest.productName());
        assertEquals(7, newest.quantityDelta());
        assertEquals("RECEIPT", newest.reason());
        assertEquals("ADJUSTMENT", page.items().get(1).reason());
    }

    @Test
    void theMovementsOfOrdersAndHoldsAreInTheHistoryWithTheirOwnReason() {
        Product product = productWith(10);
        store(product.id(), -3, "RESERVE", NOW.minusSeconds(20));
        store(product.id(), 3, "RELEASE", NOW.minusSeconds(10));
        store(product.id(), -1, "CONSUME", NOW);

        StockMovementPage page = stock.findMovements(of(product.id(), 1, 20, null, null));

        assertEquals(List.of("CONSUME", "RELEASE", "RESERVE"),
                page.items().stream().map(StockMovementEntry::reason).toList());
    }

    @Test
    void thePageAndTheLimitSliceTheHistoryAndTheTotalCountsAllOfIt() {
        Product product = productWith(10);
        for (int i = 0; i < 5; i++) {
            store(product.id(), 1, "RECEIPT", NOW.minusSeconds(100 - i));
        }

        StockMovementPage second = stock.findMovements(of(product.id(), 2, 2, null, null));
        StockMovementPage third = stock.findMovements(of(product.id(), 3, 2, null, null));
        StockMovementPage beyond = stock.findMovements(of(product.id(), 4, 2, null, null));

        assertEquals(5, second.total());
        assertEquals(2, second.items().size());
        assertEquals(List.of(NOW.minusSeconds(98), NOW.minusSeconds(99)), instants(second));
        assertEquals(1, third.items().size());
        assertEquals(0, beyond.items().size());
        assertEquals(5, beyond.total());
    }

    @Test
    void theHistoryOfAProductDoesNotShowTheMovementsOfAnother() {
        Product popcorn = productWith(10);
        Product soda = productWith(10);
        store(popcorn.id(), 1, "RECEIPT", NOW);
        store(soda.id(), 1, "RECEIPT", NOW);
        store(soda.id(), 2, "RECEIPT", NOW.minusSeconds(1));

        StockMovementPage page = stock.findMovements(of(soda.id(), 1, 20, null, null));

        assertEquals(2, page.total());
        assertTrue(page.items().stream().allMatch(entry -> entry.productId().equals(soda.id())));
        assertTrue(page.items().stream().allMatch(entry -> entry.productName().equals(soda.name())));
    }

    @Test
    void theDateRangeIsInclusiveAtBothEnds() {
        Product product = productWith(10);
        for (long seconds : new long[] {1000, 500, 300, 100, 0}) {
            store(product.id(), 1, "RECEIPT", NOW.minusSeconds(seconds));
        }

        StockMovementPage both = stock.findMovements(
                of(product.id(), 1, 20, NOW.minusSeconds(500), NOW.minusSeconds(100)));
        StockMovementPage onlyFrom = stock.findMovements(of(product.id(), 1, 20, NOW.minusSeconds(300), null));
        StockMovementPage onlyTo = stock.findMovements(of(product.id(), 1, 20, null, NOW.minusSeconds(500)));

        assertEquals(List.of(NOW.minusSeconds(100), NOW.minusSeconds(300), NOW.minusSeconds(500)), instants(both));
        assertEquals(3, both.total());
        assertEquals(3, onlyFrom.total());
        assertEquals(2, onlyTo.total());
    }

    @Test
    void movementsWithTheSameInstantKeepAStableOrderAcrossPages() {
        Product product = productWith(10);
        for (int i = 0; i < 4; i++) {
            store(product.id(), 1, "RECEIPT", NOW);
        }

        List<UUID> whole = stock.findMovements(of(product.id(), 1, 20, null, null)).items().stream()
                .map(StockMovementEntry::id).toList();
        List<UUID> pages = new ArrayList<>();
        pages.addAll(stock.findMovements(of(product.id(), 1, 2, null, null)).items().stream()
                .map(StockMovementEntry::id).toList());
        pages.addAll(stock.findMovements(of(product.id(), 2, 2, null, null)).items().stream()
                .map(StockMovementEntry::id).toList());

        assertEquals(whole, pages);
    }

    @Test
    void theHistoryWithoutAProductSpansEveryProduct() {
        Product popcorn = productWith(10);
        Product soda = productWith(10);
        Instant longAgo = NOW.plusSeconds(86_400);
        store(popcorn.id(), 1, "RECEIPT", longAgo);
        store(soda.id(), 1, "RECEIPT", longAgo);

        StockMovementPage page = stock.findMovements(of(null, 1, 100, longAgo, longAgo));

        assertTrue(page.total() >= 2);
        assertTrue(page.items().stream().map(StockMovementEntry::productId).toList().containsAll(
                List.of(popcorn.id(), soda.id())));
    }
}
