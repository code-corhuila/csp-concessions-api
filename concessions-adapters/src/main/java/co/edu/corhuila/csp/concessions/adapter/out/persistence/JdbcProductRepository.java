package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of a product. The product and its idempotency key are written in one transaction
 * (Norma 5.3.8): the key tells a replay from a first request, {@code ON CONFLICT DO NOTHING} settles a race
 * between two requests with the same key, and the database itself refuses a second active product with the same name.
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    private static final String INSERT_PRODUCT = """
            INSERT INTO concessions.product
                (id, name, description, category, price, available_stock, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_KEY = """
            INSERT INTO concessions.product_idempotency_key ("key", product_id, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT ("key") DO NOTHING
            """;

    private static final String FIND_KEY = """
            SELECT product_id, request_hash FROM concessions.product_idempotency_key WHERE "key" = ?
            """;

    private static final String SELECT_PRODUCT = """
            SELECT id, name, description, category, price, available_stock, status, created_at, updated_at
            FROM concessions.product
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public JdbcProductRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CreateProductResult create(Product product, String idempotencyKey, String requestHash) {
        return transactionTemplate.execute(status -> {
            // The key first: a retry carries the same name as the original, and the active-name rule
            // would refuse its insert before the replay could be recognised.
            Optional<CreateProductResult> earlier = replayOf(idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
            insert(product);
            if (jdbc.update(INSERT_KEY, idempotencyKey, product.id(), requestHash) == 1) {
                return new CreateProductResult(product, false);
            }
            // Another request stored the same key between the read and the insert: undo ours and answer from it.
            status.setRollbackOnly();
            return replayOf(idempotencyKey, requestHash).orElseThrow();
        });
    }

    /** The product stored under the key; a conflict when the key was used with another request. */
    private Optional<CreateProductResult> replayOf(String idempotencyKey, String requestHash) {
        List<Map<String, Object>> stored = jdbc.queryForList(FIND_KEY, idempotencyKey);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!requestHash.equals(stored.get(0).get("request_hash"))) {
            throw new IdempotencyKeyConflictException();
        }
        Product original = findById((UUID) stored.get(0).get("product_id")).orElseThrow();
        return Optional.of(new CreateProductResult(original, true));
    }

    @Override
    public Product save(Product product) {
        jdbc.update("""
                UPDATE concessions.product
                SET name = ?, description = ?, category = ?, price = ?, available_stock = ?, status = ?, updated_at = ?
                WHERE id = ?""",
                product.name(), product.description(), product.category(), product.price(),
                product.availableStock(), product.status().name(), at(product), product.id());
        return product;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jdbc.query(SELECT_PRODUCT + "WHERE id = ?", this::toProduct, id).stream().findFirst();
    }

    @Override
    public ProductPage findPage(int page, int limit, boolean onlyPublished) {
        String where = onlyPublished ? "WHERE status = 'PUBLISHED' " : "";
        long total = jdbc.queryForObject("SELECT count(*) FROM concessions.product " + where, Long.class);
        List<Product> items = jdbc.query(
                SELECT_PRODUCT + where + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::toProduct, limit, (long) (page - 1) * limit);
        return new ProductPage(items, total);
    }

    private void insert(Product product) {
        jdbc.update(INSERT_PRODUCT, product.id(), product.name(), product.description(), product.category(),
                product.price(), product.availableStock(), product.status().name(),
                OffsetDateTime.ofInstant(product.createdAt(), ZoneOffset.UTC), at(product));
    }

    private static OffsetDateTime at(Product product) {
        return OffsetDateTime.ofInstant(product.updatedAt(), ZoneOffset.UTC);
    }

    private Product toProduct(ResultSet row, int rowNumber) throws SQLException {
        return new Product(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"),
                row.getString("category"), row.getLong("price"), row.getInt("available_stock"),
                ProductStatus.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
