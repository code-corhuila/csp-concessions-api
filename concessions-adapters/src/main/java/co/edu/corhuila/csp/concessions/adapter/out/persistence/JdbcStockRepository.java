package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.StockRepository;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of the stock (ADR-043). The balance and its movement change in one transaction. The balance is moved
 * by one guarded {@code UPDATE}: the row lock serialises concurrent changes of a product and the guard is
 * evaluated against the row as it is at that moment, so two withdrawals that together exceed the balance
 * cannot both succeed. The guard mirrors {@link Product#adjustStock}; when it fails, the product is read
 * again and the domain says why. {@code ON CONFLICT DO NOTHING} settles a race between two requests with the same key.
 */
@Repository
public class JdbcStockRepository implements StockRepository {

    private static final String MOVE_BALANCE = """
            UPDATE concessions.product
            SET available_stock = available_stock + ?, updated_at = ?
            WHERE id = ? AND status <> 'INACTIVE'
              AND available_stock::bigint + ? BETWEEN 0 AND 2147483647
            RETURNING available_stock
            """;

    private static final String INSERT_MOVEMENT = """
            INSERT INTO concessions.stock_movement
                (id, product_id, quantity_delta, balance_after, movement_type, idempotency_key, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private static final String SELECT_MOVEMENT = """
            SELECT id, product_id, quantity_delta, balance_after, movement_type, idempotency_key, created_by, created_at
            FROM concessions.stock_movement
            """;

    private final JdbcTemplate jdbc;
    private final JdbcProductRepository products;
    private final TransactionTemplate transactionTemplate;

    public JdbcStockRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.products = new JdbcProductRepository(jdbc, transactionManager);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<StockMovement> findByIdempotencyKey(String key) {
        return jdbc.query(SELECT_MOVEMENT + "WHERE idempotency_key = ?", JdbcStockRepository::toMovement, key)
                .stream().findFirst();
    }

    @Override
    public StockMovement append(StockMovement movement) {
        return transactionTemplate.execute(status -> {
            int balance = moveBalance(movement);
            StockMovement stored = new StockMovement(movement.id(), movement.productId(), movement.quantityDelta(),
                    balance, movement.type(), movement.idempotencyKey(), movement.createdBy(), movement.createdAt());
            if (jdbc.update(INSERT_MOVEMENT, stored.id(), stored.productId(), stored.quantityDelta(),
                    stored.balanceAfter(), stored.type().name(), stored.idempotencyKey(), stored.createdBy(),
                    at(stored)) == 1) {
                return stored;
            }
            // Another request stored the same key first: undo the balance change and answer from that one.
            status.setRollbackOnly();
            return replayOf(movement);
        });
    }

    /** Moves the balance and returns what it is now; the domain explains a refusal. */
    private int moveBalance(StockMovement movement) {
        OffsetDateTime now = at(movement);
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Integer> balance = jdbc.query(MOVE_BALANCE, (row, n) -> row.getInt(1),
                    movement.quantityDelta(), now, movement.productId(), movement.quantityDelta()).stream().findFirst();
            if (balance.isPresent()) {
                return balance.get();
            }
            Product current = products.findById(movement.productId())
                    .orElseThrow(() -> new ProductNotFoundException("the product does not exist"));
            current.adjustStock(movement.quantityDelta(), movement.createdAt());
        }
        // The balance changed under us twice between the guard and the read: report what a retry would find.
        throw new InsufficientStockException("the stock changed while it was being adjusted, try again");
    }

    private StockMovement replayOf(StockMovement movement) {
        StockMovement original = findByIdempotencyKey(movement.idempotencyKey()).orElseThrow();
        if (!original.productId().equals(movement.productId())
                || original.quantityDelta() != movement.quantityDelta()) {
            throw new IdempotencyKeyConflictException();
        }
        return original;
    }

    private static OffsetDateTime at(StockMovement movement) {
        return OffsetDateTime.ofInstant(movement.createdAt(), ZoneOffset.UTC);
    }

    private static StockMovement toMovement(ResultSet row, int rowNumber) throws SQLException {
        return new StockMovement(row.getObject("id", UUID.class), row.getObject("product_id", UUID.class),
                row.getInt("quantity_delta"), row.getInt("balance_after"),
                MovementType.valueOf(row.getString("movement_type")), row.getString("idempotency_key"),
                row.getObject("created_by", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
