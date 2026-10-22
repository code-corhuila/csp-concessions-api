package co.edu.corhuila.csp.concessions.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.in.CreateProductInput;
import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.domain.model.InvalidProductException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private FakeProductRepository repository;
    private ProductService service;

    @BeforeEach
    void setUp() {
        repository = new FakeProductRepository();
        service = new ProductService(repository, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
    }

    private Product created(CreateProductInput input) {
        return service.create(input, UUID.randomUUID().toString()).product();
    }

    private static CreateProductInput popcorn() {
        return new CreateProductInput("Popcorn", "Large", "SNACK", 1500, 10);
    }

    @Test
    void createSavesADraftProduct() {
        Product product = created(popcorn());

        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals(product, repository.findById(product.id()).orElseThrow());
        assertEquals(NOW, product.createdAt());
    }

    @Test
    void repeatingTheKeyWithTheSameRequestReturnsTheOriginalProductAndCreatesNothing() {
        CreateProductResult first = service.create(popcorn(), "key-0000001");
        CreateProductResult replay = service.create(popcorn(), "key-0000001");

        assertEquals(false, first.replayed());
        assertEquals(true, replay.replayed());
        assertEquals(first.product(), replay.product());
        assertEquals(1, repository.products.size());
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflict() {
        service.create(popcorn(), "key-0000001");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.create(new CreateProductInput("Soda", null, "DRINK", 800, 5), "key-0000001"));
        assertEquals(1, repository.products.size());
    }

    @Test
    void anInvalidProductIsRejectedAndNothingIsSaved() {
        assertThrows(InvalidProductException.class,
                () -> service.create(new CreateProductInput(" ", null, "SNACK", 100, 0), "key-0000001"));
        assertThrows(InvalidProductException.class,
                () -> service.create(new CreateProductInput("Soda", null, "DRINK", -5, 0), "key-0000002"));

        assertTrue(repository.products.isEmpty());
    }

    @Test
    void publishMakesTheProductAvailableForOrders() {
        Product created = created(popcorn());

        Product published = service.changeStatus(created.id(), ProductStatus.PUBLISHED);

        assertEquals(ProductStatus.PUBLISHED, repository.findById(created.id()).orElseThrow().status());
        assertTrue(published.isAvailableForOrders());
    }

    @Test
    void deactivateAndReactivateFollowTheLifecycle() {
        Product created = created(popcorn());
        service.changeStatus(created.id(), ProductStatus.PUBLISHED);

        assertEquals(ProductStatus.INACTIVE, service.changeStatus(created.id(), ProductStatus.INACTIVE).status());
        assertEquals(ProductStatus.PUBLISHED, service.changeStatus(created.id(), ProductStatus.PUBLISHED).status());
    }

    @Test
    void anInvalidTransitionChangesNothing() {
        Product created = created(popcorn());

        assertThrows(InvalidStatusTransitionException.class,
                () -> service.changeStatus(created.id(), ProductStatus.INACTIVE));
        assertEquals(ProductStatus.DRAFT, repository.findById(created.id()).orElseThrow().status());
    }

    @Test
    void aProductCannotGoBackToDraft() {
        Product created = created(popcorn());
        service.changeStatus(created.id(), ProductStatus.PUBLISHED);

        assertThrows(InvalidStatusTransitionException.class,
                () -> service.changeStatus(created.id(), ProductStatus.DRAFT));
        assertEquals(ProductStatus.PUBLISHED, repository.findById(created.id()).orElseThrow().status());
    }

    @Test
    void changingTheStatusOfAnUnknownProductIsNotFound() {
        assertThrows(ProductNotFoundException.class,
                () -> service.changeStatus(UUID.randomUUID(), ProductStatus.PUBLISHED));
    }

    @Test
    void anAdministratorSeesEveryStatusAndAClientOnlyThePublishedOnes() {
        Product draft = created(popcorn());
        Product published = created(new CreateProductInput("Soda", null, "DRINK", 800, 5));
        service.changeStatus(published.id(), ProductStatus.PUBLISHED);

        assertEquals(2, service.list(1, 10, false).total());
        ProductPage clientView = service.list(1, 10, true);
        assertEquals(1, clientView.total());
        assertEquals(published.id(), clientView.items().get(0).id());
        assertTrue(clientView.items().stream().noneMatch(p -> p.id().equals(draft.id())));
    }

    @Test
    void theLimitBoundsThePage() {
        for (int n = 0; n < 5; n++) {
            created(new CreateProductInput("Item " + n, null, "SNACK", 100, 0));
        }

        assertEquals(2, service.list(1, 2, false).items().size());
        assertEquals(1, service.list(3, 2, false).items().size());
        assertThrows(IllegalArgumentException.class, () -> service.list(1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.list(1, 101, false));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 10, false));
    }

    /** Respects the page and the limit, and a write changes what the next read returns. */
    private static final class FakeProductRepository implements ProductRepository {

        final Map<UUID, Product> products = new LinkedHashMap<>();
        final Map<String, String[]> keys = new LinkedHashMap<>();

        @Override
        public CreateProductResult create(Product product, String idempotencyKey, String requestHash) {
            String[] stored = keys.get(idempotencyKey);
            if (stored == null) {
                keys.put(idempotencyKey, new String[] {requestHash, product.id().toString()});
                products.put(product.id(), product);
                return new CreateProductResult(product, false);
            }
            if (!stored[0].equals(requestHash)) {
                throw new IdempotencyKeyConflictException();
            }
            return new CreateProductResult(products.get(UUID.fromString(stored[1])), true);
        }

        @Override
        public Product save(Product product) {
            products.put(product.id(), product);
            return product;
        }

        @Override
        public Optional<Product> findById(UUID id) {
            return Optional.ofNullable(products.get(id));
        }

        @Override
        public ProductPage findPage(int page, int limit, boolean onlyPublished) {
            List<Product> matching = products.values().stream()
                    .filter(p -> !onlyPublished || p.status() == ProductStatus.PUBLISHED)
                    .toList();
            int from = Math.min((page - 1) * limit, matching.size());
            int to = Math.min(from + limit, matching.size());
            return new ProductPage(matching.subList(from, to), matching.size());
        }
    }
}
