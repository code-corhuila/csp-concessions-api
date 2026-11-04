package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of a combo. The combo, its components and its idempotency key are written in one transaction
 * (Norma 5.3.8), the same way as a product: the key tells a replay from a first request, and
 * {@code ON CONFLICT DO NOTHING} settles a race between two requests with the same key.
 */
@Repository
public class JdbcComboRepository implements ComboRepository {

    private static final String INSERT_COMBO = """
            INSERT INTO concessions.combo (id, name, description, price, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO concessions.combo_item (combo_id, product_id, quantity) VALUES (?, ?, ?)
            """;

    private static final String INSERT_KEY = """
            INSERT INTO concessions.combo_idempotency_key ("key", combo_id, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT ("key") DO NOTHING
            """;

    private static final String FIND_KEY = """
            SELECT combo_id, request_hash FROM concessions.combo_idempotency_key WHERE "key" = ?
            """;

    private static final String SELECT_COMBO = """
            SELECT id, name, description, price, status, created_at, updated_at FROM concessions.combo
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public JdbcComboRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CreateComboResult create(Combo combo, String idempotencyKey, String requestHash) {
        return transactionTemplate.execute(status -> {
            // The key first: a retry carries the same name as the original, and the active-name rule
            // would refuse its insert before the replay could be recognised.
            Optional<CreateComboResult> earlier = replayOf(idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
            jdbc.update(INSERT_COMBO, combo.id(), combo.name(), combo.description(), combo.price(),
                    combo.status().name(), OffsetDateTime.ofInstant(combo.createdAt(), ZoneOffset.UTC), at(combo));
            jdbc.batchUpdate(INSERT_ITEM, combo.items(), combo.items().size(), (statement, item) -> {
                statement.setObject(1, combo.id());
                statement.setObject(2, item.productId());
                statement.setInt(3, item.quantity());
            });
            if (jdbc.update(INSERT_KEY, idempotencyKey, combo.id(), requestHash) == 1) {
                return new CreateComboResult(combo, false);
            }
            // Another request stored the same key between the read and the insert: undo ours and answer from it.
            status.setRollbackOnly();
            return replayOf(idempotencyKey, requestHash).orElseThrow();
        });
    }

    /** The combo stored under the key; a conflict when the key was used with another request. */
    private Optional<CreateComboResult> replayOf(String idempotencyKey, String requestHash) {
        List<Map<String, Object>> stored = jdbc.queryForList(FIND_KEY, idempotencyKey);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!requestHash.equals(stored.get(0).get("request_hash"))) {
            throw new IdempotencyKeyConflictException();
        }
        Combo original = findById((UUID) stored.get(0).get("combo_id")).orElseThrow();
        return Optional.of(new CreateComboResult(original, true));
    }

    @Override
    public Combo save(Combo combo) {
        jdbc.update("UPDATE concessions.combo SET status = ?, updated_at = ? WHERE id = ?",
                combo.status().name(), at(combo), combo.id());
        return combo;
    }

    @Override
    public Optional<Combo> findById(UUID id) {
        return jdbc.query(SELECT_COMBO + "WHERE id = ?", this::toRow, id).stream().findFirst()
                .map(row -> row.withItems(itemsOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public ComboPage findPage(int page, int limit, boolean onlyPublished) {
        String where = onlyPublished ? "WHERE status = 'PUBLISHED' " : "";
        long total = jdbc.queryForObject("SELECT count(*) FROM concessions.combo " + where, Long.class);
        List<ComboRow> rows = jdbc.query(
                SELECT_COMBO + where + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::toRow, limit, (long) (page - 1) * limit);
        Map<UUID, List<ComboItem>> items = itemsOf(rows.stream().map(ComboRow::id).toList());
        return new ComboPage(rows.stream().map(row -> row.withItems(items.getOrDefault(row.id(), List.of()))).toList(),
                total);
    }

    /** The components of several combos in one query, by combo and in a fixed order. */
    private Map<UUID, List<ComboItem>> itemsOf(List<UUID> comboIds) {
        Map<UUID, List<ComboItem>> byCombo = new LinkedHashMap<>();
        if (comboIds.isEmpty()) {
            return byCombo;
        }
        String marks = String.join(", ", Collections.nCopies(comboIds.size(), "?"));
        jdbc.query("SELECT combo_id, product_id, quantity FROM concessions.combo_item WHERE combo_id IN (" + marks
                        + ") ORDER BY combo_id, product_id",
                row -> {
                    byCombo.computeIfAbsent(row.getObject("combo_id", UUID.class), id -> new ArrayList<>())
                            .add(new ComboItem(row.getObject("product_id", UUID.class), row.getInt("quantity")));
                }, comboIds.toArray());
        return byCombo;
    }

    private static OffsetDateTime at(Combo combo) {
        return OffsetDateTime.ofInstant(combo.updatedAt(), ZoneOffset.UTC);
    }

    private ComboRow toRow(ResultSet row, int rowNumber) throws SQLException {
        return new ComboRow(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"),
                row.getLong("price"), ComboStatus.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class), row.getObject("updated_at", OffsetDateTime.class));
    }

    /** A combo row before its components are read: the domain object needs both at once. */
    private record ComboRow(UUID id, String name, String description, long price, ComboStatus status,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        Combo withItems(List<ComboItem> items) {
            return new Combo(id, name, description, price, items, status, createdAt.toInstant(),
                    updatedAt.toInstant());
        }
    }
}
