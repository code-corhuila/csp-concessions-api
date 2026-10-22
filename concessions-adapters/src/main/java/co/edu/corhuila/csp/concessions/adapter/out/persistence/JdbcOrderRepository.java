package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import co.edu.corhuila.csp.concessions.application.port.out.OrderRepository;
import co.edu.corhuila.csp.concessions.application.port.out.OutboxEvent;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.application.port.out.StoredOrder;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.HeldProduct;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderLine;
import co.edu.corhuila.csp.concessions.domain.model.OrderStatus;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import co.edu.corhuila.csp.concessions.domain.model.StockHold;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of the orders (ADR-045). Every change of an order, its hold, the stock and the outbox happens in one
 * transaction. The stock of each product is taken by one guarded {@code UPDATE} in ascending product id: the row
 * lock serialises concurrent orders, the guard is evaluated against the row as it is at that moment, and the fixed
 * order stops two orders that share products from waiting on each other. An order changes status only through
 * {@code UPDATE ... WHERE status = 'RESERVED'}, so a confirmation, a release and a retry cannot all take effect.
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    /** The key under which the correlation id of the request is kept in the MDC (see {@code CorrelationIdFilter}). */
    private static final String CORRELATION_MDC_KEY = "traceId";

    private static final String INSERT_ORDER = """
            INSERT INTO concessions.concession_order
                (id, user_id, reservation_id, total_amount, status, idempotency_key, request_hash, expires_at,
                 confirmed_at, cancelled_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """;

    private static final String INSERT_LINE = """
            INSERT INTO concessions.concession_order_item
                (order_id, item_type, item_id, item_name_snapshot, unit_price_snapshot, quantity)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_HOLD = """
            INSERT INTO concessions.stock_hold (id, order_id, status, created_at) VALUES (?, ?, ?, ?)
            """;

    private static final String INSERT_HOLD_ITEM = """
            INSERT INTO concessions.stock_hold_item (hold_id, product_id, quantity) VALUES (?, ?, ?)
            """;

    private static final String TAKE_STOCK = """
            UPDATE concessions.product
            SET available_stock = available_stock - ?, updated_at = ?
            WHERE id = ? AND status = 'PUBLISHED' AND available_stock >= ?
            RETURNING available_stock
            """;

    private static final String RETURN_STOCK = """
            UPDATE concessions.product
            SET available_stock = available_stock + ?, updated_at = ?
            WHERE id = ?
            RETURNING available_stock
            """;

    private static final String INSERT_MOVEMENT = """
            INSERT INTO concessions.stock_movement
                (product_id, quantity_delta, balance_after, movement_type, created_by, created_at, order_id, hold_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_EVENT = """
            INSERT INTO concessions.outbox_event (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
            """;

    private static final String SELECT_ORDER = """
            SELECT id, user_id, reservation_id, total_amount, status, expires_at, confirmed_at, cancelled_at,
                   created_at, updated_at, request_hash
            FROM concessions.concession_order
            """;

    private static final String SELECT_LINES = """
            SELECT order_id, item_type, item_id, item_name_snapshot, unit_price_snapshot, quantity
            FROM concessions.concession_order_item
            WHERE order_id = ANY (?)
            ORDER BY item_type, item_name_snapshot, item_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;

    public JdbcOrderRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredOrder> findByIdempotencyKey(UUID userId, String key) {
        return queryOrders(SELECT_ORDER + "WHERE user_id = ? AND idempotency_key = ?", userId, key).stream()
                .findFirst();
    }

    @Override
    public PlacedOrder create(ConcessionOrder order, StockHold hold, String idempotencyKey, String requestHash,
                              List<OutboxEvent> events) {
        return transactionTemplate.execute(status -> {
            int inserted;
            try {
                inserted = jdbc.update(INSERT_ORDER, order.id(), order.userId(), order.reservationId(),
                        order.totalAmount(), order.status().name(), idempotencyKey, requestHash, at(order.expiresAt()),
                        at(order.confirmedAt()), at(order.cancelledAt()), at(order.createdAt()), at(order.updatedAt()));
            } catch (DuplicateKeyException liveReservation) {
                throw new ReservationAlreadyOrderedException("the reservation already has a concession order");
            }
            if (inserted == 0) {
                // Another request stored the same key between the service's read and our insert: nothing of ours
                // was written, so answer from it.
                status.setRollbackOnly();
                return replayOf(order.userId(), idempotencyKey, requestHash);
            }
            jdbc.batchUpdate(INSERT_LINE, order.lines(), order.lines().size(), (statement, line) -> {
                statement.setObject(1, order.id());
                statement.setString(2, line.itemType().name());
                statement.setObject(3, line.itemId());
                statement.setString(4, line.name());
                statement.setLong(5, line.unitPrice());
                statement.setInt(6, line.quantity());
            });
            jdbc.update(INSERT_HOLD, hold.id(), order.id(), hold.status().name(), at(hold.createdAt()));
            jdbc.batchUpdate(INSERT_HOLD_ITEM, hold.items(), hold.items().size(), (statement, held) -> {
                statement.setObject(1, hold.id());
                statement.setObject(2, held.productId());
                statement.setInt(3, held.quantity());
            });
            for (HeldProduct held : hold.items()) {
                takeStock(order, hold, held);
            }
            events.forEach(this::insertEvent);
            return new PlacedOrder(order, false);
        });
    }

    @Override
    public Optional<ConcessionOrder> findById(UUID id) {
        return queryOrders(SELECT_ORDER + "WHERE id = ?", id).stream().findFirst().map(StoredOrder::order);
    }

    @Override
    public Optional<ConcessionOrder> findByReservationId(UUID reservationId) {
        return queryOrders(SELECT_ORDER + "WHERE reservation_id = ? "
                + "ORDER BY (status <> 'CANCELLED') DESC, created_at DESC LIMIT 1", reservationId).stream()
                .findFirst().map(StoredOrder::order);
    }

    @Override
    public OrderPage findPage(UUID userId, int page, int limit) {
        long total = jdbc.queryForObject("SELECT count(*) FROM concessions.concession_order WHERE user_id = ?",
                Long.class, userId);
        List<ConcessionOrder> items = queryOrders(SELECT_ORDER + "WHERE user_id = ? "
                + "ORDER BY created_at DESC, id LIMIT ? OFFSET ?", userId, limit, (long) (page - 1) * limit).stream()
                .map(StoredOrder::order).toList();
        return new OrderPage(items, total);
    }

    @Override
    public boolean confirm(ConcessionOrder confirmed, OutboxEvent event) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (jdbc.update("UPDATE concessions.concession_order SET status = 'CONFIRMED', confirmed_at = ?, "
                    + "updated_at = ? WHERE id = ? AND status = 'RESERVED'",
                    at(confirmed.confirmedAt()), at(confirmed.updatedAt()), confirmed.id()) == 0) {
                return false;
            }
            jdbc.update("UPDATE concessions.stock_hold SET status = 'COMMITTED', resolved_at = ? "
                    + "WHERE order_id = ? AND status = 'HELD'", at(confirmed.updatedAt()), confirmed.id());
            insertEvent(event);
            return true;
        }));
    }

    @Override
    public boolean release(ConcessionOrder cancelled, OutboxEvent event) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (jdbc.update("UPDATE concessions.concession_order SET status = 'CANCELLED', cancelled_at = ?, "
                    + "updated_at = ? WHERE id = ? AND status = 'RESERVED'",
                    at(cancelled.cancelledAt()), at(cancelled.updatedAt()), cancelled.id()) == 0) {
                return false;
            }
            UUID holdId = jdbc.queryForObject("UPDATE concessions.stock_hold SET status = 'RELEASED', resolved_at = ? "
                    + "WHERE order_id = ? AND status = 'HELD' RETURNING id", UUID.class,
                    at(cancelled.updatedAt()), cancelled.id());
            List<HeldProduct> items = jdbc.query("SELECT product_id, quantity FROM concessions.stock_hold_item "
                    + "WHERE hold_id = ? ORDER BY product_id",
                    (row, n) -> new HeldProduct(row.getObject("product_id", UUID.class), row.getInt("quantity")),
                    holdId);
            for (HeldProduct held : items) {
                int balance = jdbc.queryForObject(RETURN_STOCK, Integer.class, held.quantity(),
                        at(cancelled.updatedAt()), held.productId());
                jdbc.update(INSERT_MOVEMENT, held.productId(), held.quantity(), balance, "RELEASE", cancelled.userId(),
                        at(cancelled.updatedAt()), cancelled.id(), holdId);
            }
            insertEvent(event);
            return true;
        }));
    }

    @Override
    public List<ConcessionOrder> findExpired(Instant now, int limit) {
        return queryOrders(SELECT_ORDER + "WHERE status = 'RESERVED' AND expires_at IS NOT NULL AND expires_at <= ? "
                + "ORDER BY expires_at, id LIMIT ?", at(now), limit).stream().map(StoredOrder::order).toList();
    }

    /** Takes the units of one product; a refusal says why and rolls back the whole order. */
    private void takeStock(ConcessionOrder order, StockHold hold, HeldProduct held) {
        OffsetDateTime now = at(order.createdAt());
        Optional<Integer> balance = jdbc.query(TAKE_STOCK, (row, n) -> row.getInt(1), held.quantity(), now,
                held.productId(), held.quantity()).stream().findFirst();
        if (balance.isEmpty()) {
            explainRefusal(held);
        }
        jdbc.update(INSERT_MOVEMENT, held.productId(), -held.quantity(), balance.get(), "RESERVE", order.userId(), now,
                order.id(), hold.id());
    }

    private void explainRefusal(HeldProduct held) {
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT status, available_stock FROM concessions.product WHERE id = ?", held.productId());
        if (current.isEmpty() || !"PUBLISHED".equals(current.get(0).get("status"))) {
            throw new OrderItemUnavailableException("the product " + held.productId() + " is not available");
        }
        throw new InsufficientStockException("the product " + held.productId() + " has "
                + current.get(0).get("available_stock") + " units available");
    }

    private PlacedOrder replayOf(UUID userId, String key, String requestHash) {
        StoredOrder stored = findByIdempotencyKey(userId, key).orElseThrow();
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException();
        }
        return new PlacedOrder(stored.order(), true);
    }

    private void insertEvent(OutboxEvent event) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", event.id().toString());
        envelope.put("eventType", event.eventType());
        envelope.put("aggregateId", event.aggregateId().toString());
        envelope.put("aggregateType", "ConcessionOrder");
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("version", 1);
        envelope.put("source", "concessions-service");
        envelope.set("payload", mapper.valueToTree(event.payload()));
        ObjectNode metadata = envelope.putObject("metadata");
        String correlationId = MDC.get(CORRELATION_MDC_KEY);
        metadata.put("correlationId", correlationId != null ? correlationId : UUID.randomUUID().toString());
        jdbc.update(INSERT_EVENT, event.id(), "ConcessionOrder", event.aggregateId(), event.eventType(),
                envelope.toString(), at(event.occurredAt()));
    }

    private List<StoredOrder> queryOrders(String sql, Object... args) {
        List<Object[]> rows = jdbc.query(sql, (row, n) -> new Object[] {row.getObject("id", UUID.class),
                header(row), row.getString("request_hash")}, args);
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID[] ids = rows.stream().map(row -> (UUID) row[0]).toArray(UUID[]::new);
        Map<UUID, List<OrderLine>> lines = new HashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement(SELECT_LINES);
            statement.setArray(1, con.createArrayOf("uuid", ids));
            return statement;
        }, (ResultSet row) -> {
            lines.computeIfAbsent(row.getObject("order_id", UUID.class), id -> new ArrayList<>()).add(toLine(row));
        });
        List<StoredOrder> orders = new ArrayList<>();
        for (Object[] row : rows) {
            orders.add(new StoredOrder(((Header) row[1]).with(lines.get((UUID) row[0])), (String) row[2]));
        }
        return orders;
    }

    private static Header header(ResultSet row) throws SQLException {
        return new Header(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("reservation_id", UUID.class), row.getLong("total_amount"),
                OrderStatus.valueOf(row.getString("status")), instant(row, "expires_at"), instant(row, "confirmed_at"),
                instant(row, "cancelled_at"), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static OrderLine toLine(ResultSet row) throws SQLException {
        return new OrderLine(OrderItemType.valueOf(row.getString("item_type")), row.getObject("item_id", UUID.class),
                row.getString("item_name_snapshot"), row.getLong("unit_price_snapshot"), row.getInt("quantity"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The columns of an order row, waiting for its lines. */
    private record Header(UUID id, UUID userId, UUID reservationId, long totalAmount, OrderStatus status,
                          Instant expiresAt, Instant confirmedAt, Instant cancelledAt, Instant createdAt,
                          Instant updatedAt) {

        ConcessionOrder with(List<OrderLine> lines) {
            return new ConcessionOrder(id, userId, reservationId, lines, totalAmount, status, expiresAt, confirmedAt,
                    cancelledAt, createdAt, updatedAt);
        }
    }
}
