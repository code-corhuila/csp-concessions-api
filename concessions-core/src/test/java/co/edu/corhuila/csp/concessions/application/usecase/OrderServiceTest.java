package co.edu.corhuila.csp.concessions.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput.RequestedItem;
import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import co.edu.corhuila.csp.concessions.application.port.out.OrderRepository;
import co.edu.corhuila.csp.concessions.application.port.out.OutboxEvent;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.port.out.StoredOrder;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.HeldProduct;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.OrderStatus;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import co.edu.corhuila.csp.concessions.domain.model.StockHold;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final UUID CLIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RESERVATION = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private World world;
    private OrderService service;
    private Product popcorn;
    private Product soda;
    private Combo family;

    @BeforeEach
    void setUp() {
        world = new World();
        service = new OrderService(world, world.productRepository, world.comboRepository, world,
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID, TTL);
        popcorn = world.publishedProduct("Popcorn", 1500, 10);
        soda = world.publishedProduct("Soda", 800, 5);
        family = world.publishedCombo("Family", 4000, new ComboItem(popcorn.id(), 2), new ComboItem(soda.id(), 1));
    }

    private static CreateOrderInput input(UUID reservationId, String key, RequestedItem... items) {
        return new CreateOrderInput(CLIENT, reservationId, List.of(items), key);
    }

    private static RequestedItem product(Product p, int quantity) {
        return new RequestedItem(OrderItemType.PRODUCT, p.id(), quantity);
    }

    private static RequestedItem combo(Combo c, int quantity) {
        return new RequestedItem(OrderItemType.COMBO, c.id(), quantity);
    }

    @Test
    void anOrderIsReservedWithASnapshotAndTheStockIsTaken() {
        PlacedOrder placed = service.create(input(RESERVATION, "key-00000001", product(popcorn, 2), product(soda, 1)));

        ConcessionOrder order = placed.order();
        assertFalse(placed.replayed());
        assertEquals(OrderStatus.RESERVED, order.status());
        assertEquals(CLIENT, order.userId());
        assertEquals(RESERVATION, order.reservationId());
        assertEquals(3800, order.totalAmount());
        assertEquals("Popcorn", order.lines().get(0).name());
        assertEquals(1500, order.lines().get(0).unitPrice());
        assertEquals(8, world.products.get(popcorn.id()).availableStock());
        assertEquals(4, world.products.get(soda.id()).availableStock());
        assertEquals(List.of("ConcessionOrderCreated", "ConcessionStockReserved"),
                world.events.stream().map(OutboxEvent::eventType).toList());
        assertEquals(order.id(), world.events.get(0).aggregateId());
        assertEquals(3800L, world.events.get(1).payload().get("totalAmount"));
    }

    @Test
    void theSnapshotDoesNotChangeWhenTheProductPriceDoes() {
        ConcessionOrder order = service.create(input(null, "key-00000001", product(popcorn, 1))).order();

        world.products.put(popcorn.id(), new Product(popcorn.id(), "Popcorn XL", null, "SNACK", 9999,
                world.products.get(popcorn.id()).availableStock(), popcorn.status(), NOW, NOW));

        assertEquals("Popcorn", world.orders.get(order.id()).lines().get(0).name());
        assertEquals(1500, world.orders.get(order.id()).lines().get(0).unitPrice());
    }

    @Test
    void aComboReservesItsComponentsTimesTheQuantityAndMergesWithDirectLines() {
        ConcessionOrder order = service.create(input(null, "key-00000001", combo(family, 2), product(popcorn, 1))).order();

        assertEquals(9500, order.totalAmount());
        // the combo takes 2 popcorn and 1 soda per unit: 4 + 1 popcorn and 2 soda
        assertEquals(5, world.products.get(popcorn.id()).availableStock());
        assertEquals(3, world.products.get(soda.id()).availableStock());
        StockHold hold = world.holds.get(order.id());
        assertEquals(5, hold.items().stream().filter(i -> i.productId().equals(popcorn.id()))
                .mapToInt(HeldProduct::quantity).sum());
        assertEquals(1, hold.items().stream().filter(i -> i.productId().equals(soda.id())).count());
    }

    @Test
    void anOrderWithoutReservationExpiresAfterTheHoldTime() {
        ConcessionOrder order = service.create(input(null, "key-00000001", product(popcorn, 1))).order();

        assertEquals(NOW.plus(TTL), order.expiresAt());
    }

    @Test
    void anItemWithoutEnoughStockRejectsTheWholeOrderAndReservesNothing() {
        assertThrows(InsufficientStockException.class,
                () -> service.create(input(null, "key-00000001", product(popcorn, 2), product(soda, 6))));

        assertEquals(10, world.products.get(popcorn.id()).availableStock());
        assertEquals(5, world.products.get(soda.id()).availableStock());
        assertTrue(world.orders.isEmpty());
        assertTrue(world.events.isEmpty());
    }

    @Test
    void theLastUnitGoesToOneOrderOnly() {
        Product last = world.publishedProduct("Last", 100, 1);

        service.create(input(null, "key-00000001", product(last, 1)));

        assertThrows(InsufficientStockException.class,
                () -> service.create(new CreateOrderInput(UUID.randomUUID(), null,
                        List.of(product(last, 1)), "key-00000002")));
        assertEquals(1, world.orders.size());
        assertEquals(0, world.products.get(last.id()).availableStock());
    }

    @Test
    void anItemThatIsMissingOrNotPublishedIsUnavailable() {
        Product draft = Product.draft(UUID.randomUUID(), "Draft", null, "SNACK", 100, 5, NOW);
        world.products.put(draft.id(), draft);
        Combo inactive = world.publishedCombo("Old", 100, new ComboItem(popcorn.id(), 1)).deactivate(NOW);
        world.combos.put(inactive.id(), inactive);

        assertThrows(OrderItemUnavailableException.class,
                () -> service.create(input(null, "key-00000001", product(draft, 1))));
        assertThrows(OrderItemUnavailableException.class,
                () -> service.create(input(null, "key-00000002",
                        new RequestedItem(OrderItemType.PRODUCT, UUID.randomUUID(), 1))));
        assertThrows(OrderItemUnavailableException.class,
                () -> service.create(input(null, "key-00000003", combo(inactive, 1))));
        assertThrows(OrderItemUnavailableException.class,
                () -> service.create(input(null, "key-00000004",
                        new RequestedItem(OrderItemType.COMBO, UUID.randomUUID(), 1))));
        assertTrue(world.orders.isEmpty());
    }

    @Test
    void aComboWithAnUnpublishedComponentIsUnavailable() {
        Product deactivated = world.products.get(soda.id()).deactivate(NOW);
        world.products.put(deactivated.id(), deactivated);

        assertThrows(OrderItemUnavailableException.class,
                () -> service.create(input(null, "key-00000001", combo(family, 1))));
        assertTrue(world.orders.isEmpty());
    }

    @Test
    void theSameKeyAndBodyReplaysTheOriginalOrderWithoutReservingAgain() {
        CreateOrderInput request = input(RESERVATION, "key-00000001", product(popcorn, 2));
        PlacedOrder first = service.create(request);

        PlacedOrder second = service.create(input(RESERVATION, "key-00000001", product(popcorn, 2)));

        assertTrue(second.replayed());
        assertEquals(first.order().id(), second.order().id());
        assertEquals(8, world.products.get(popcorn.id()).availableStock());
        assertEquals(2, world.events.size());
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflict() {
        service.create(input(RESERVATION, "key-00000001", product(popcorn, 2)));

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.create(input(RESERVATION, "key-00000001", product(popcorn, 3))));
    }

    @Test
    void theOrderOfLinesDoesNotChangeTheBodyOfARetry() {
        service.create(input(null, "key-00000001", product(popcorn, 1), product(soda, 1)));

        assertTrue(service.create(input(null, "key-00000001", product(soda, 1), product(popcorn, 1))).replayed());
    }

    @Test
    void aReservationHasOneLiveOrderAndAFreedOneCanBeOrderedAgain() {
        ConcessionOrder first = service.create(input(RESERVATION, "key-00000001", product(popcorn, 1))).order();

        assertThrows(ReservationAlreadyOrderedException.class,
                () -> service.create(input(RESERVATION, "key-00000002", product(popcorn, 1))));

        service.cancel(first.id(), CLIENT);
        ConcessionOrder second = service.create(input(RESERVATION, "key-00000003", product(popcorn, 1))).order();
        assertEquals(OrderStatus.RESERVED, second.status());
    }

    @Test
    void aClientReadsOnlyItsOwnOrders() {
        ConcessionOrder order = service.create(input(null, "key-00000001", product(popcorn, 1))).order();

        assertSame(order, service.get(order.id(), CLIENT));
        assertThrows(OrderNotFoundException.class, () -> service.get(order.id(), UUID.randomUUID()));
        assertThrows(OrderNotFoundException.class, () -> service.get(UUID.randomUUID(), CLIENT));
        assertEquals(1, service.list(CLIENT, 1, 20).total());
        assertEquals(0, service.list(UUID.randomUUID(), 1, 20).total());
    }

    @Test
    void cancellingAnOrderReleasesItsStockOnceAndAgainIsHarmless() {
        ConcessionOrder order = service.create(input(null, "key-00000001", product(popcorn, 4))).order();

        service.cancel(order.id(), CLIENT);
        service.cancel(order.id(), CLIENT);

        assertEquals(OrderStatus.CANCELLED, world.orders.get(order.id()).status());
        assertEquals(10, world.products.get(popcorn.id()).availableStock());
        List<OutboxEvent> cancelled = world.events.stream()
                .filter(e -> e.eventType().equals("ConcessionOrderCancelled")).toList();
        assertEquals(1, cancelled.size());
        assertEquals("CLIENT", cancelled.get(0).payload().get("reason"));
    }

    @Test
    void anotherUserCannotCancelAnOrder() {
        ConcessionOrder order = service.create(input(null, "key-00000001", product(popcorn, 1))).order();

        assertThrows(OrderNotFoundException.class, () -> service.cancel(order.id(), UUID.randomUUID()));
        assertEquals(OrderStatus.RESERVED, world.orders.get(order.id()).status());
    }

    @Test
    void theConfirmationOfTheReservationConfirmsTheOrderAndKeepsTheStockOnce() {
        ConcessionOrder order = service.create(input(RESERVATION, "key-00000001", product(popcorn, 4))).order();

        service.confirmByReservation(RESERVATION);
        service.confirmByReservation(RESERVATION);

        assertEquals(OrderStatus.CONFIRMED, world.orders.get(order.id()).status());
        assertEquals(6, world.products.get(popcorn.id()).availableStock());
        assertEquals(1, world.events.stream().filter(e -> e.eventType().equals("ConcessionOrderConfirmed")).count());
        assertThrows(InvalidStatusTransitionException.class, () -> service.cancel(order.id(), CLIENT));
    }

    @Test
    void theExpirationOfTheReservationCancelsTheOrderAndReleasesTheStockOnce() {
        ConcessionOrder order = service.create(input(RESERVATION, "key-00000001", product(popcorn, 4))).order();

        service.releaseByReservation(RESERVATION);
        service.releaseByReservation(RESERVATION);

        assertEquals(OrderStatus.CANCELLED, world.orders.get(order.id()).status());
        assertEquals(10, world.products.get(popcorn.id()).availableStock());
        List<OutboxEvent> cancelled = world.events.stream()
                .filter(e -> e.eventType().equals("ConcessionOrderCancelled")).toList();
        assertEquals(1, cancelled.size());
        assertEquals("RESERVATION_EXPIRED", cancelled.get(0).payload().get("reason"));
        assertThrows(InvalidStatusTransitionException.class, () -> service.confirmByReservation(RESERVATION));
    }

    @Test
    void aReservationWithoutOrderIsIgnored() {
        service.confirmByReservation(RESERVATION);
        service.releaseByReservation(RESERVATION);

        assertTrue(world.events.isEmpty());
    }

    @Test
    void aConfirmedOrderCannotBeReleasedByTheReservation() {
        service.create(input(RESERVATION, "key-00000001", product(popcorn, 1)));
        service.confirmByReservation(RESERVATION);

        assertThrows(InvalidStatusTransitionException.class, () -> service.releaseByReservation(RESERVATION));
        assertEquals(9, world.products.get(popcorn.id()).availableStock());
    }

    @Test
    void theSweepReleasesOnlyTheOrdersWhoseTimeRanOut() {
        ConcessionOrder stale = service.create(input(null, "key-00000001", product(popcorn, 3))).order();
        ConcessionOrder withReservation = service.create(input(RESERVATION, "key-00000002", product(soda, 1))).order();
        OrderService later = new OrderService(world, world.productRepository, world.comboRepository, world,
                Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC), UUID::randomUUID, TTL);

        assertEquals(1, later.expireOrders(100));
        assertEquals(0, later.expireOrders(100));

        assertEquals(OrderStatus.CANCELLED, world.orders.get(stale.id()).status());
        assertEquals(OrderStatus.RESERVED, world.orders.get(withReservation.id()).status());
        assertEquals(10, world.products.get(popcorn.id()).availableStock());
        assertEquals("ORDER_EXPIRED", world.events.get(world.events.size() - 1).payload().get("reason"));
        assertNull(world.orders.get(withReservation.id()).expiresAt());
    }

    /** One in-memory world that plays every output port, applying the stock rules the way the database does. */
    private static final class World implements OrderRepository, ComponentProducts {

        final Map<UUID, Product> products = new LinkedHashMap<>();
        final Map<UUID, Combo> combos = new LinkedHashMap<>();
        final Map<UUID, ConcessionOrder> orders = new LinkedHashMap<>();
        final Map<UUID, StockHold> holds = new HashMap<>();
        final List<OutboxEvent> events = new ArrayList<>();
        private final Map<String, StoredOrder> byKey = new HashMap<>();

        Product publishedProduct(String name, long price, int stock) {
            Product product = Product.draft(UUID.randomUUID(), name, null, "SNACK", price, stock, NOW).publish(NOW);
            products.put(product.id(), product);
            return product;
        }

        Combo publishedCombo(String name, long price, ComboItem... items) {
            Combo combo = Combo.draft(UUID.randomUUID(), name, null, price, List.of(items), NOW).publish(NOW, products);
            combos.put(combo.id(), combo);
            return combo;
        }

        @Override
        public Optional<StoredOrder> findByIdempotencyKey(UUID userId, String key) {
            return Optional.ofNullable(byKey.get(userId + key));
        }

        @Override
        public PlacedOrder create(ConcessionOrder order, StockHold hold, String idempotencyKey, String requestHash,
                                  List<OutboxEvent> newEvents) {
            for (HeldProduct held : hold.items()) {
                if (products.get(held.productId()).availableStock() < held.quantity()) {
                    throw new InsufficientStockException("not enough stock");
                }
            }
            for (HeldProduct held : hold.items()) {
                Product product = products.get(held.productId());
                products.put(product.id(), product.adjustStock(-held.quantity(), NOW));
            }
            orders.put(order.id(), order);
            holds.put(order.id(), hold);
            byKey.put(order.userId() + idempotencyKey, new StoredOrder(order, requestHash));
            events.addAll(newEvents);
            return new PlacedOrder(order, false);
        }

        @Override
        public Optional<ConcessionOrder> findById(UUID id) {
            return Optional.ofNullable(orders.get(id));
        }

        @Override
        public Optional<ConcessionOrder> findByReservationId(UUID reservationId) {
            return orders.values().stream().filter(o -> reservationId.equals(o.reservationId()))
                    .min((a, b) -> Boolean.compare(b.isLive(), a.isLive()));
        }

        @Override
        public OrderPage findPage(UUID userId, int page, int limit) {
            List<ConcessionOrder> own = orders.values().stream().filter(o -> o.belongsTo(userId)).toList();
            return new OrderPage(own, own.size());
        }

        @Override
        public boolean confirm(ConcessionOrder confirmed, OutboxEvent event) {
            if (orders.get(confirmed.id()).status() != OrderStatus.RESERVED) {
                return false;
            }
            orders.put(confirmed.id(), confirmed);
            holds.put(confirmed.id(), holds.get(confirmed.id()).commit(NOW));
            events.add(event);
            return true;
        }

        @Override
        public boolean release(ConcessionOrder cancelled, OutboxEvent event) {
            if (orders.get(cancelled.id()).status() != OrderStatus.RESERVED) {
                return false;
            }
            StockHold hold = holds.get(cancelled.id());
            for (HeldProduct held : hold.items()) {
                Product product = products.get(held.productId());
                products.put(product.id(), product.adjustStock(held.quantity(), NOW));
            }
            orders.put(cancelled.id(), cancelled);
            holds.put(cancelled.id(), hold.release(NOW));
            events.add(event);
            return true;
        }

        @Override
        public List<ConcessionOrder> findExpired(Instant now, int limit) {
            return orders.values().stream()
                    .filter(o -> o.status() == OrderStatus.RESERVED && o.expiresAt() != null
                            && !o.expiresAt().isAfter(now))
                    .limit(limit).toList();
        }

        @Override
        public Map<UUID, Product> findByIds(Collection<UUID> ids) {
            return ids.stream().filter(products::containsKey).collect(Collectors.toMap(id -> id, products::get));
        }

        final ProductRepository productRepository = new ProductRepository() {
            @Override
            public CreateProductResult create(Product product, String idempotencyKey, String requestHash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Product save(Product product) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Product> findById(UUID id) {
                return Optional.ofNullable(products.get(id));
            }

            @Override
            public ProductPage findPage(int page, int limit, boolean onlyPublished) {
                throw new UnsupportedOperationException();
            }
        };

        final ComboRepository comboRepository = new ComboRepository() {
            @Override
            public CreateComboResult create(Combo combo, String idempotencyKey, String requestHash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Combo save(Combo combo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Combo> findById(UUID id) {
                return Optional.ofNullable(combos.get(id));
            }

            @Override
            public ComboPage findPage(int page, int limit, boolean onlyPublished) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
