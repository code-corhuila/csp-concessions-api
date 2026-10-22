package co.edu.corhuila.csp.concessions.application.usecase;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput.RequestedItem;
import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import co.edu.corhuila.csp.concessions.application.port.out.OrderRepository;
import co.edu.corhuila.csp.concessions.application.port.out.OutboxEvent;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.port.out.StoredOrder;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.InvalidOrderException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderLine;
import co.edu.corhuila.csp.concessions.domain.model.OrderNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.OrderStatus;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import co.edu.corhuila.csp.concessions.domain.model.StockHold;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The use cases of HU-CONCESSIONS-004. The rules live in {@link ConcessionOrder} and {@link StockHold}; this
 * class resolves what the client asked for, sequences the rules and hands the repository one complete change to
 * store atomically (ADR-045). It never decides on a stock figure it read: the repository guards the write.
 */
public class OrderService implements OrderUseCases {

    private static final String CANCEL_BY_CLIENT = "CLIENT";
    private static final String CANCEL_BY_RESERVATION = "RESERVATION_EXPIRED";
    private static final String CANCEL_BY_DEADLINE = "ORDER_EXPIRED";

    private final OrderRepository orders;
    private final ProductRepository products;
    private final ComboRepository combos;
    private final ComponentProducts components;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final Duration holdTime;

    public OrderService(OrderRepository orders, ProductRepository products, ComboRepository combos,
                        ComponentProducts components, Clock clock, Supplier<UUID> ids, Duration holdTime) {
        this.orders = Objects.requireNonNull(orders);
        this.products = Objects.requireNonNull(products);
        this.combos = Objects.requireNonNull(combos);
        this.components = Objects.requireNonNull(components);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
        this.holdTime = Objects.requireNonNull(holdTime);
    }

    @Override
    public PlacedOrder create(CreateOrderInput input) {
        String hash = hashOf(input);
        // A retry is answered before the rules run: the stock it found may have changed since.
        StoredOrder earlier = orders.findByIdempotencyKey(input.userId(), input.idempotencyKey()).orElse(null);
        if (earlier != null) {
            if (!earlier.requestHash().equals(hash)) {
                throw new IdempotencyKeyConflictException();
            }
            return new PlacedOrder(earlier.order(), true);
        }
        if (input.reservationId() != null
                && orders.findByReservationId(input.reservationId()).filter(ConcessionOrder::isLive).isPresent()) {
            throw new ReservationAlreadyOrderedException("the reservation already has a concession order");
        }
        Instant now = clock.instant();
        List<OrderLine> lines = new ArrayList<>();
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (RequestedItem item : input.items()) {
            lines.add(item.itemType() == OrderItemType.COMBO ? comboLine(item, quantities) : productLine(item, quantities));
        }
        ConcessionOrder order = ConcessionOrder.reserve(ids.get(), input.userId(), input.reservationId(), lines,
                holdTime, now);
        StockHold hold = StockHold.open(ids.get(), order.id(), quantities, now);
        List<OutboxEvent> events = List.of(event("ConcessionOrderCreated", order, now, null),
                event("ConcessionStockReserved", order, now, hold));
        return orders.create(order, hold, input.idempotencyKey(), hash, events);
    }

    @Override
    public ConcessionOrder get(UUID orderId, UUID userId) {
        return orders.findById(orderId).filter(order -> order.belongsTo(userId))
                .orElseThrow(() -> new OrderNotFoundException("the order does not exist"));
    }

    @Override
    public OrderPage list(UUID userId, int page, int limit) {
        return orders.findPage(userId, page, limit);
    }

    @Override
    public void cancel(UUID orderId, UUID userId) {
        release(get(orderId, userId), CANCEL_BY_CLIENT);
    }

    @Override
    public void confirmByReservation(UUID reservationId) {
        ConcessionOrder order = orders.findByReservationId(reservationId).orElse(null);
        if (order == null || order.status() == OrderStatus.CONFIRMED) {
            return;
        }
        Instant now = clock.instant();
        ConcessionOrder confirmed = order.confirm(now);
        if (!orders.confirm(confirmed, event("ConcessionOrderConfirmed", confirmed, now, null))) {
            // Another request resolved it first: that is fine only if it was the same resolution.
            requireStatus(order.id(), OrderStatus.CONFIRMED);
        }
    }

    @Override
    public void releaseByReservation(UUID reservationId) {
        ConcessionOrder order = orders.findByReservationId(reservationId).orElse(null);
        if (order != null) {
            release(order, CANCEL_BY_RESERVATION);
        }
    }

    @Override
    public int expireOrders(int batchSize) {
        int released = 0;
        for (ConcessionOrder order : orders.findExpired(clock.instant(), batchSize)) {
            if (release(order, CANCEL_BY_DEADLINE)) {
                released++;
            }
        }
        return released;
    }

    /** Cancels the order and gives its stock back; false when it was already cancelled, so nothing was done. */
    private boolean release(ConcessionOrder order, String reason) {
        if (order.status() == OrderStatus.CANCELLED) {
            return false;
        }
        Instant now = clock.instant();
        ConcessionOrder cancelled = order.cancel(now);
        OutboxEvent event = event("ConcessionOrderCancelled", cancelled, now, null);
        event.payload().put("reason", reason);
        if (orders.release(cancelled, event)) {
            return true;
        }
        requireStatus(order.id(), OrderStatus.CANCELLED);
        return false;
    }

    private void requireStatus(UUID orderId, OrderStatus expected) {
        OrderStatus found = orders.findById(orderId).map(ConcessionOrder::status).orElse(null);
        if (found != expected) {
            throw new InvalidStatusTransitionException("the order is " + found + " and cannot be changed");
        }
    }

    private OrderLine productLine(RequestedItem item, Map<UUID, Integer> quantities) {
        Product product = products.findById(item.itemId()).filter(Product::isAvailableForOrders)
                .orElseThrow(() -> new OrderItemUnavailableException("the product " + item.itemId() + " is not available"));
        hold(quantities, product.id(), item.quantity());
        return new OrderLine(OrderItemType.PRODUCT, product.id(), product.name(), product.price(), item.quantity());
    }

    private OrderLine comboLine(RequestedItem item, Map<UUID, Integer> quantities) {
        Combo combo = combos.findById(item.itemId()).filter(Combo::isAvailableForOrders)
                .orElseThrow(() -> new OrderItemUnavailableException("the combo " + item.itemId() + " is not available"));
        Map<UUID, Product> parts = components.findByIds(combo.items().stream().map(ComboItem::productId).toList());
        for (ComboItem part : combo.items()) {
            Product product = parts.get(part.productId());
            if (product == null || !product.isAvailableForOrders()) {
                throw new OrderItemUnavailableException(
                        "the component " + part.productId() + " of the combo " + combo.id() + " is not available");
            }
            hold(quantities, part.productId(), multiply(part.quantity(), item.quantity()));
        }
        return new OrderLine(OrderItemType.COMBO, combo.id(), combo.name(), combo.price(), item.quantity());
    }

    private static void hold(Map<UUID, Integer> quantities, UUID productId, int quantity) {
        try {
            quantities.merge(productId, quantity, Math::addExact);
        } catch (ArithmeticException e) {
            throw new InvalidOrderException("the quantity of a product in the order is too large");
        }
    }

    private static int multiply(int a, int b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new InvalidOrderException("the quantity of a product in the order is too large");
        }
    }

    /** The event of an order; the reservation event also lists the held products. */
    private OutboxEvent event(String type, ConcessionOrder order, Instant now, StockHold hold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("userId", order.userId());
        payload.put("reservationId", order.reservationId());
        payload.put("status", order.status().name());
        payload.put("totalAmount", order.totalAmount());
        payload.put("items", order.lines().stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemType", line.itemType().name());
            item.put("itemId", line.itemId());
            item.put("itemName", line.name());
            item.put("unitPrice", line.unitPrice());
            item.put("quantity", line.quantity());
            return item;
        }).toList());
        if (hold != null) {
            payload.put("heldProducts", hold.items().stream()
                    .map(held -> Map.of("productId", held.productId(), "quantity", held.quantity())).toList());
        }
        return new OutboxEvent(ids.get(), type, order.id(), now, payload);
    }

    /** The same request in another line order has the same hash: a retry must not depend on how the lines are listed. */
    private static String hashOf(CreateOrderInput input) {
        List<String> lines = input.items().stream()
                .map(item -> item.itemType() + ":" + item.itemId() + ":" + item.quantity()).sorted().toList();
        String canonical = input.reservationId() + "|" + String.join(",", lines);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
