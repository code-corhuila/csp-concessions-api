package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import co.edu.corhuila.csp.concessions.domain.model.StockNotAdjustableException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
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
}
