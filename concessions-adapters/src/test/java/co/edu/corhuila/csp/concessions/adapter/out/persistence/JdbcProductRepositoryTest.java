package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The SQL of the product against the real engine, with the schema of csp-concessions-db (Annex C).
 * It is skipped when TEST_DATABASE_URL is not defined: the URL carries the credentials, so one
 * variable is enough, for example {@code jdbc:postgresql://localhost:5432/csp} with the user and
 * the password in the environment, never in the source.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcProductRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final String KEY = "11111111-2222-3333-4444-555555555555";

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcProductRepository repository;

    private final List<UUID> written = new ArrayList<>();

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcProductRepository(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closePool() {
        dataSource.close();
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID id : written) {
            jdbc.update("DELETE FROM concessions.product WHERE id = ?", id);
        }
        written.clear();
    }

    private Product draft(String name) {
        Product product = Product.draft(UUID.randomUUID(), name, "Large", "SNACK", 1500, 10, NOW);
        written.add(product.id());
        return product;
    }

    @Test
    void aCreatedProductIsStoredWithItsKeyAndReadBack() {
        Product product = draft("Repo popcorn");

        CreateProductResult result = repository.create(product, KEY, "hash-one");

        assertFalse(result.replayed());
        Product stored = repository.findById(product.id()).orElseThrow();
        assertEquals(product.id(), stored.id());
        assertEquals("Repo popcorn", stored.name());
        assertEquals(1500, stored.price());
        assertEquals(10, stored.availableStock());
        assertEquals(ProductStatus.DRAFT, stored.status());
        assertEquals(NOW.truncatedTo(ChronoUnit.MILLIS), stored.createdAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.product_idempotency_key WHERE key = ?", Integer.class, KEY));
    }

    @Test
    void theSameKeyAndHashReturnsTheOriginalProductAndWritesNothing() {
        Product first = draft("Repo replay");
        repository.create(first, KEY, "hash-one");

        CreateProductResult replay = repository.create(draft("Repo replay"), KEY, "hash-one");

        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.product().id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.product WHERE name = 'Repo replay'", Integer.class));
    }

    @Test
    void theSameKeyWithAnotherHashIsAConflictAndWritesNothing() {
        repository.create(draft("Repo conflict"), KEY, "hash-one");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> repository.create(draft("Repo other"), KEY, "hash-two"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.product WHERE name = 'Repo other'", Integer.class));
    }

    @Test
    void anActiveNameCannotBeCreatedTwiceWithDifferentKeys() {
        repository.create(draft("Repo unique"), KEY, "hash-one");

        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> repository.create(draft("repo UNIQUE"), "99999999-2222-3333-4444-555555555555", "hash-two"));
    }

    @Test
    void saveUpdatesTheStatusAndTheUpdateDate() {
        Product product = draft("Repo publish");
        repository.create(product, KEY, "hash-one");

        repository.save(product.publish(NOW.plusSeconds(60)));

        Product stored = repository.findById(product.id()).orElseThrow();
        assertEquals(ProductStatus.PUBLISHED, stored.status());
        assertEquals(NOW.plusSeconds(60), stored.updatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void thePageRespectsTheLimitTheOffsetAndTheClientFilter() {
        Product published = draft("Repo page published");
        repository.create(published, KEY, "hash-one");
        repository.save(published.publish(NOW));
        repository.create(draft("Repo page draft"), "22222222-2222-3333-4444-555555555555", "hash-two");

        ProductPage everything = repository.findPage(1, 100, false);
        ProductPage clientView = repository.findPage(1, 100, true);

        assertTrue(everything.total() >= 2);
        assertTrue(clientView.items().stream().allMatch(p -> p.status() == ProductStatus.PUBLISHED));
        assertTrue(clientView.items().stream().anyMatch(p -> p.id().equals(published.id())));
        assertEquals(1, repository.findPage(1, 1, false).items().size());
    }
}
