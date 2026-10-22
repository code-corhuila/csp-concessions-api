package co.edu.corhuila.csp.concessions.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.port.out.StockRepository;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStockMovementException;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import co.edu.corhuila.csp.concessions.domain.model.StockNotAdjustableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private FakeStock stock;
    private StockService service;
    private Product popcorn;

    @BeforeEach
    void setUp() {
        stock = new FakeStock();
        service = new StockService(stock, stock, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        popcorn = Product.draft(UUID.randomUUID(), "Popcorn", null, "SNACK", 1500, 10, NOW.minusSeconds(60));
        stock.products.put(popcorn.id(), popcorn);
    }

    @Test
    void aReceiptRaisesTheBalanceAndLeavesAMovement() {
        StockMovement movement = service.adjust(popcorn.id(), 5, "key-0000001", ADMIN);

        assertEquals(15, stock.products.get(popcorn.id()).availableStock());
        assertEquals(MovementType.RECEIPT, movement.type());
        assertEquals(5, movement.quantityDelta());
        assertEquals(15, movement.balanceAfter());
        assertEquals(ADMIN, movement.createdBy());
        assertEquals(NOW, movement.createdAt());
        assertEquals(List.of(movement), stock.movements);
    }

    @Test
    void aWithdrawalIsAnAdjustment() {
        StockMovement movement = service.adjust(popcorn.id(), -4, "key-0000001", ADMIN);

        assertEquals(MovementType.ADJUSTMENT, movement.type());
        assertEquals(6, stock.products.get(popcorn.id()).availableStock());
    }

    @Test
    void aWithdrawalLargerThanTheBalanceIsRefusedAndLeavesNoMovement() {
        assertThrows(InsufficientStockException.class, () -> service.adjust(popcorn.id(), -11, "key-0000001", ADMIN));

        assertEquals(10, stock.products.get(popcorn.id()).availableStock());
        assertTrue(stock.movements.isEmpty());
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertThrows(ProductNotFoundException.class, () -> service.adjust(UUID.randomUUID(), 5, "key-0000001", ADMIN));
        assertTrue(stock.movements.isEmpty());
    }

    @Test
    void anInactiveProductIsRefused() {
        stock.products.put(popcorn.id(), popcorn.publish(NOW).deactivate(NOW));

        assertThrows(StockNotAdjustableException.class, () -> service.adjust(popcorn.id(), 5, "key-0000001", ADMIN));
        assertTrue(stock.movements.isEmpty());
    }

    @Test
    void aZeroDeltaIsInvalid() {
        assertThrows(InvalidStockMovementException.class, () -> service.adjust(popcorn.id(), 0, "key-0000001", ADMIN));
    }

    @Test
    void repeatingTheKeyWithTheSameRequestAppliesItOnce() {
        StockMovement first = service.adjust(popcorn.id(), 5, "key-0000001", ADMIN);
        StockMovement replay = service.adjust(popcorn.id(), 5, "key-0000001", ADMIN);

        assertEquals(first, replay);
        assertEquals(15, stock.products.get(popcorn.id()).availableStock());
        assertEquals(1, stock.movements.size());
    }

    @Test
    void aReplayIsAnsweredEvenWhenTheBalanceNoLongerAllowsTheWithdrawal() {
        StockMovement first = service.adjust(popcorn.id(), -10, "key-0000001", ADMIN);

        assertEquals(first, service.adjust(popcorn.id(), -10, "key-0000001", ADMIN));
        assertEquals(0, stock.products.get(popcorn.id()).availableStock());
    }

    @Test
    void theSameKeyWithAnotherQuantityOrProductIsAConflict() {
        service.adjust(popcorn.id(), 5, "key-0000001", ADMIN);

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.adjust(popcorn.id(), 6, "key-0000001", ADMIN));
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.adjust(UUID.randomUUID(), 5, "key-0000001", ADMIN));
        assertEquals(15, stock.products.get(popcorn.id()).availableStock());
        assertEquals(1, stock.movements.size());
    }

    /** What the database adapter does in one transaction, reduced to maps. */
    private static final class FakeStock implements ProductRepository, StockRepository {

        final Map<UUID, Product> products = new HashMap<>();
        final List<StockMovement> movements = new ArrayList<>();

        @Override
        public Optional<Product> findById(UUID id) {
            return Optional.ofNullable(products.get(id));
        }

        @Override
        public Optional<StockMovement> findByIdempotencyKey(String key) {
            return movements.stream().filter(m -> key.equals(m.idempotencyKey())).findFirst();
        }

        @Override
        public StockMovement append(StockMovement movement) {
            Product after = products.get(movement.productId())
                    .adjustStock(movement.quantityDelta(), movement.createdAt());
            products.put(after.id(), after);
            movements.add(movement);
            return movement;
        }

        @Override
        public CreateProductResult create(Product product, String idempotencyKey, String requestHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Product save(Product product) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProductPage findPage(int page, int limit, boolean onlyPublished) {
            throw new UnsupportedOperationException();
        }
    }
}
