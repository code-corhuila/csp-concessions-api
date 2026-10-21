package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The SQL of the combo and of the lookup of its components against the real engine, with the schema of
 * csp-concessions-db (Annex C). Skipped when TEST_DATABASE_URL is not defined, like the product one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcComboRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final String KEY = "aaaaaaaa-2222-3333-4444-555555555555";

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcComboRepository repository;
    private static JdbcComponentProducts components;

    private final List<UUID> combos = new ArrayList<>();
    private final List<UUID> products = new ArrayList<>();
    private UUID popcorn;
    private UUID soda;

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcComboRepository(jdbc, new DataSourceTransactionManager(dataSource));
        components = new JdbcComponentProducts(jdbc);
    }

    @AfterAll
    static void closePool() {
        dataSource.close();
    }

    @BeforeEach
    void insertTheProducts() {
        popcorn = product("Combo repo popcorn", ProductStatus.PUBLISHED);
        soda = product("Combo repo soda", ProductStatus.DRAFT);
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID id : combos) {
            jdbc.update("DELETE FROM concessions.combo WHERE id = ?", id);
        }
        for (UUID id : products) {
            jdbc.update("DELETE FROM concessions.product WHERE id = ?", id);
        }
        combos.clear();
        products.clear();
    }

    private UUID product(String name, ProductStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO concessions.product (id, name, category, price, status) VALUES (?, ?, 'SNACK', 100, ?)",
                id, name, status.name());
        products.add(id);
        return id;
    }

    private Combo draft(String name) {
        Combo combo = Combo.draft(UUID.randomUUID(), name, "Bundle", 4500,
                List.of(new ComboItem(popcorn, 1), new ComboItem(soda, 2)), NOW);
        combos.add(combo.id());
        return combo;
    }

    private static List<ComboItem> byProduct(List<ComboItem> items) {
        return items.stream().sorted(Comparator.comparing(ComboItem::productId)).toList();
    }

    @Test
    void aCreatedComboIsStoredWithItsComponentsAndItsKeyAndReadBack() {
        Combo combo = draft("Combo repo family");

        CreateComboResult result = repository.create(combo, KEY, "hash-one");

        assertFalse(result.replayed());
        Combo stored = repository.findById(combo.id()).orElseThrow();
        assertEquals("Combo repo family", stored.name());
        assertEquals(4500, stored.price());
        assertEquals(ComboStatus.DRAFT, stored.status());
        assertEquals(byProduct(combo.items()), byProduct(stored.items()));
        assertEquals(NOW.truncatedTo(ChronoUnit.MILLIS), stored.createdAt().truncatedTo(ChronoUnit.MILLIS));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.combo_idempotency_key WHERE key = ?", Integer.class, KEY));
    }

    @Test
    void theSameKeyAndHashReturnsTheOriginalComboAndWritesNothing() {
        Combo first = draft("Combo repo replay");
        repository.create(first, KEY, "hash-one");

        CreateComboResult replay = repository.create(draft("Combo repo replay"), KEY, "hash-one");

        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.combo().id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.combo WHERE name = 'Combo repo replay'", Integer.class));
    }

    @Test
    void theSameKeyWithAnotherHashIsAConflictAndWritesNothing() {
        repository.create(draft("Combo repo conflict"), KEY, "hash-one");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> repository.create(draft("Combo repo other"), KEY, "hash-two"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.combo WHERE name = 'Combo repo other'", Integer.class));
    }

    @Test
    void anActiveNameCannotBeCreatedTwiceWithDifferentKeys() {
        repository.create(draft("Combo repo unique"), KEY, "hash-one");

        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> repository.create(draft("combo REPO unique"), "bbbbbbbb-2222-3333-4444-555555555555", "hash-two"));
    }

    @Test
    void aFailedCreateLeavesNoComponentBehind() {
        Combo ghost = Combo.draft(UUID.randomUUID(), "Combo repo ghost", null, 1,
                List.of(new ComboItem(popcorn, 1), new ComboItem(UUID.randomUUID(), 1)), NOW);
        combos.add(ghost.id());

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> repository.create(ghost, KEY, "hash-one"));

        assertTrue(repository.findById(ghost.id()).isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM concessions.combo_item WHERE combo_id = ?", Integer.class, ghost.id()));
    }

    @Test
    void saveUpdatesTheStatusAndTheUpdateDate() {
        Combo combo = draft("Combo repo publish");
        repository.create(combo, KEY, "hash-one");
        Map<UUID, Product> ready = Map.of(
                popcorn, new Product(popcorn, "p", null, "SNACK", 1, 0, ProductStatus.PUBLISHED, NOW, NOW),
                soda, new Product(soda, "s", null, "SNACK", 1, 0, ProductStatus.PUBLISHED, NOW, NOW));

        repository.save(combo.publish(NOW.plusSeconds(60), ready));

        Combo stored = repository.findById(combo.id()).orElseThrow();
        assertEquals(ComboStatus.PUBLISHED, stored.status());
        assertEquals(NOW.plusSeconds(60), stored.updatedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void anUnknownComboIsNotFound() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void thePageRespectsTheLimitAndTheClientFilter() {
        Combo published = draft("Combo repo page published");
        repository.create(published, KEY, "hash-one");
        jdbc.update("UPDATE concessions.combo SET status = 'PUBLISHED' WHERE id = ?", published.id());
        repository.create(draft("Combo repo page draft"), "cccccccc-2222-3333-4444-555555555555", "hash-two");

        ComboPage everything = repository.findPage(1, 100, false);
        ComboPage clientView = repository.findPage(1, 100, true);

        assertTrue(everything.total() >= 2);
        assertTrue(clientView.items().stream().allMatch(c -> c.status() == ComboStatus.PUBLISHED));
        assertTrue(clientView.items().stream().anyMatch(c -> c.id().equals(published.id())));
        assertTrue(everything.items().stream().allMatch(c -> !c.items().isEmpty()));
        assertEquals(1, repository.findPage(1, 1, false).items().size());
    }

    @Test
    void theLookupReturnsTheProductsThatExistAndSkipsTheRest() {
        UUID missing = UUID.randomUUID();

        Map<UUID, Product> found = components.findByIds(List.of(popcorn, soda, missing));

        assertEquals(2, found.size());
        assertEquals(ProductStatus.PUBLISHED, found.get(popcorn).status());
        assertEquals(ProductStatus.DRAFT, found.get(soda).status());
        assertTrue(components.findByIds(List.of()).isEmpty());
    }
}
