package co.edu.corhuila.csp.concessions.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.in.CreateComboInput;
import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboComponentException;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ComboNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import co.edu.corhuila.csp.concessions.domain.model.InvalidComboException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ComboServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private FakeComboRepository repository;
    private FakeProducts products;
    private ComboService service;
    private UUID popcorn;
    private UUID soda;

    @BeforeEach
    void setUp() {
        repository = new FakeComboRepository();
        products = new FakeProducts();
        service = new ComboService(repository, products, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        popcorn = products.add(ProductStatus.PUBLISHED);
        soda = products.add(ProductStatus.PUBLISHED);
    }

    private CreateComboInput family() {
        return new CreateComboInput("Family", "Popcorn and sodas", 4500,
                List.of(new ComboItem(popcorn, 1), new ComboItem(soda, 2)));
    }

    private Combo created() {
        return service.create(family(), UUID.randomUUID().toString()).combo();
    }

    @Test
    void createSavesADraftComboWithItsComponentReferences() {
        Combo combo = created();

        assertEquals(ComboStatus.DRAFT, combo.status());
        assertEquals(2, combo.items().size());
        assertEquals(combo, repository.findById(combo.id()).orElseThrow());
    }

    @Test
    void aDraftMayReferenceAProductThatIsNotPublishedYet() {
        UUID draft = products.add(ProductStatus.DRAFT);

        Combo combo = service.create(new CreateComboInput("Later", null, 100, List.of(new ComboItem(draft, 1))),
                "key-0000001").combo();

        assertEquals(ComboStatus.DRAFT, combo.status());
    }

    @Test
    void aComponentThatDoesNotExistIsRejectedAndNothingIsSaved() {
        assertThrows(ComboComponentException.class, () -> service.create(
                new CreateComboInput("Ghost", null, 100, List.of(new ComboItem(UUID.randomUUID(), 1))), "key-0000001"));

        assertTrue(repository.combos.isEmpty());
    }

    @Test
    void aNonPositiveQuantityOrAnInvalidComboIsRejectedAndNothingIsSaved() {
        assertThrows(InvalidComboException.class, () -> service.create(
                new CreateComboInput("Bad", null, 100, List.of(new ComboItem(popcorn, 0))), "key-0000001"));
        assertThrows(InvalidComboException.class, () -> service.create(
                new CreateComboInput("Bad", null, -1, List.of(new ComboItem(popcorn, 1))), "key-0000002"));

        assertTrue(repository.combos.isEmpty());
    }

    @Test
    void repeatingTheKeyWithTheSameRequestReturnsTheOriginalComboAndCreatesNothing() {
        CreateComboResult first = service.create(family(), "key-0000001");
        CreateComboResult replay = service.create(family(), "key-0000001");

        assertEquals(false, first.replayed());
        assertEquals(true, replay.replayed());
        assertEquals(first.combo(), replay.combo());
        assertEquals(1, repository.combos.size());
    }

    @Test
    void theOrderOfTheComponentsDoesNotChangeTheRequest() {
        service.create(family(), "key-0000001");

        CreateComboResult replay = service.create(new CreateComboInput("Family", "Popcorn and sodas", 4500,
                List.of(new ComboItem(soda, 2), new ComboItem(popcorn, 1))), "key-0000001");

        assertEquals(true, replay.replayed());
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflict() {
        service.create(family(), "key-0000001");

        assertThrows(IdempotencyKeyConflictException.class, () -> service.create(
                new CreateComboInput("Family", null, 5000, List.of(new ComboItem(popcorn, 1))), "key-0000001"));
        assertEquals(1, repository.combos.size());
    }

    @Test
    void publishMakesTheComboAvailableForOrders() {
        Combo combo = created();

        Combo published = service.changeStatus(combo.id(), ComboStatus.PUBLISHED);

        assertEquals(ComboStatus.PUBLISHED, published.status());
        assertEquals(published, repository.findById(combo.id()).orElseThrow());
    }

    @Test
    void publishIsRejectedWhenAComponentWasDeactivatedAndTheComboStaysADraft() {
        Combo combo = created();
        products.set(soda, ProductStatus.INACTIVE);

        assertThrows(ComboComponentException.class, () -> service.changeStatus(combo.id(), ComboStatus.PUBLISHED));

        assertEquals(ComboStatus.DRAFT, repository.findById(combo.id()).orElseThrow().status());
    }

    @Test
    void deactivatingAndPublishingAgainFollowTheLifecycle() {
        Combo combo = created();
        service.changeStatus(combo.id(), ComboStatus.PUBLISHED);

        assertEquals(ComboStatus.INACTIVE, service.changeStatus(combo.id(), ComboStatus.INACTIVE).status());
        assertEquals(ComboStatus.PUBLISHED, service.changeStatus(combo.id(), ComboStatus.PUBLISHED).status());
    }

    @Test
    void invalidMovesAreRefused() {
        Combo combo = created();

        assertThrows(InvalidStatusTransitionException.class,
                () -> service.changeStatus(combo.id(), ComboStatus.INACTIVE));
        assertThrows(InvalidStatusTransitionException.class,
                () -> service.changeStatus(combo.id(), ComboStatus.DRAFT));
    }

    @Test
    void anUnknownComboIsNotFound() {
        assertThrows(ComboNotFoundException.class,
                () -> service.changeStatus(UUID.randomUUID(), ComboStatus.PUBLISHED));
    }

    @Test
    void listReturnsAPageAndAClientSeesOnlyPublishedCombos() {
        Combo first = created();
        created();
        service.changeStatus(first.id(), ComboStatus.PUBLISHED);

        assertEquals(2, service.list(1, 10, false).total());
        ComboPage clientPage = service.list(1, 10, true);
        assertEquals(1, clientPage.total());
        assertEquals(first.id(), clientPage.items().get(0).id());
    }

    @Test
    void listRefusesAnUnboundedPage() {
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 10, false));
        assertThrows(IllegalArgumentException.class, () -> service.list(1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.list(1, 101, false));
    }

    private static final class FakeProducts implements ComponentProducts {
        private final Map<UUID, Product> byId = new LinkedHashMap<>();

        UUID add(ProductStatus status) {
            UUID id = UUID.randomUUID();
            byId.put(id, new Product(id, "Item " + id, null, "SNACK", 100, 0, status, NOW, NOW));
            return id;
        }

        void set(UUID id, ProductStatus status) {
            Product old = byId.get(id);
            byId.put(id, new Product(id, old.name(), null, "SNACK", 100, 0, status, NOW, NOW));
        }

        @Override
        public Map<UUID, Product> findByIds(Collection<UUID> ids) {
            return ids.stream().filter(byId::containsKey).collect(Collectors.toMap(id -> id, byId::get));
        }
    }

    private static final class FakeComboRepository implements ComboRepository {
        final Map<UUID, Combo> combos = new LinkedHashMap<>();
        private final Map<String, String> hashes = new LinkedHashMap<>();
        private final Map<String, UUID> owners = new LinkedHashMap<>();

        @Override
        public CreateComboResult create(Combo combo, String idempotencyKey, String requestHash) {
            if (hashes.containsKey(idempotencyKey)) {
                if (!hashes.get(idempotencyKey).equals(requestHash)) {
                    throw new IdempotencyKeyConflictException();
                }
                return new CreateComboResult(combos.get(owners.get(idempotencyKey)), true);
            }
            combos.put(combo.id(), combo);
            hashes.put(idempotencyKey, requestHash);
            owners.put(idempotencyKey, combo.id());
            return new CreateComboResult(combo, false);
        }

        @Override
        public Combo save(Combo combo) {
            combos.put(combo.id(), combo);
            return combo;
        }

        @Override
        public Optional<Combo> findById(UUID id) {
            return Optional.ofNullable(combos.get(id));
        }

        @Override
        public ComboPage findPage(int page, int limit, boolean onlyPublished) {
            List<Combo> matching = combos.values().stream()
                    .filter(combo -> !onlyPublished || combo.isAvailableForOrders())
                    .sorted(Comparator.comparing(Combo::createdAt).reversed()).toList();
            int from = Math.min((page - 1) * limit, matching.size());
            return new ComboPage(matching.subList(from, Math.min(from + limit, matching.size())), matching.size());
        }
    }
}
