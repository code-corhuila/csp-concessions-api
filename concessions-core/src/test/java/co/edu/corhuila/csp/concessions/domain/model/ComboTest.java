package co.edu.corhuila.csp.concessions.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ComboTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-21T13:00:00Z");
    private static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POPCORN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SODA = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static Combo draft() {
        return Combo.draft(ID, "Family", "Popcorn and sodas", 4500,
                List.of(new ComboItem(POPCORN, 1), new ComboItem(SODA, 2)), NOW);
    }

    private static Product product(UUID id, ProductStatus status) {
        return new Product(id, "Item " + id, null, "SNACK", 100, 0, status, NOW, NOW);
    }

    private static Map<UUID, Product> catalog(ProductStatus popcorn, ProductStatus soda) {
        return Map.of(POPCORN, product(POPCORN, popcorn), SODA, product(SODA, soda));
    }

    @Test
    void aNewComboIsADraftWithItsComponents() {
        Combo combo = draft();

        assertEquals(ComboStatus.DRAFT, combo.status());
        assertEquals("Family", combo.name());
        assertEquals(4500, combo.price());
        assertEquals(2, combo.items().size());
        assertEquals(NOW, combo.createdAt());
    }

    @Test
    void theNameIsTrimmed() {
        assertEquals("Family", Combo.draft(ID, "  Family ", null, 0, List.of(new ComboItem(POPCORN, 1)), NOW).name());
    }

    @Test
    void aComboWithoutNameOrWithANegativePriceIsRejected() {
        List<ComboItem> items = List.of(new ComboItem(POPCORN, 1));
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, " ", null, 100, items, NOW));
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, null, null, 100, items, NOW));
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, "Family", null, -1, items, NOW));
    }

    @Test
    void aComboNeedsBetweenOneAndFiftyComponentsWithoutRepeats() {
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, "Family", null, 100, List.of(), NOW));
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, "Family", null, 100, null, NOW));
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, "Family", null, 100,
                List.of(new ComboItem(POPCORN, 1), new ComboItem(POPCORN, 2)), NOW));
        List<ComboItem> tooMany = IntStream.range(0, Combo.ITEMS_MAX + 1)
                .mapToObj(i -> new ComboItem(UUID.randomUUID(), 1)).toList();
        assertThrows(InvalidComboException.class, () -> Combo.draft(ID, "Family", null, 100, tooMany, NOW));
    }

    @Test
    void aComponentQuantityMustBePositive() {
        assertThrows(InvalidComboException.class, () -> new ComboItem(POPCORN, 0));
        assertThrows(InvalidComboException.class, () -> new ComboItem(POPCORN, -3));
        assertThrows(InvalidComboException.class, () -> new ComboItem(null, 1));
    }

    @Test
    void publishNeedsEveryComponentPublished() {
        Combo published = draft().publish(LATER, catalog(ProductStatus.PUBLISHED, ProductStatus.PUBLISHED));

        assertEquals(ComboStatus.PUBLISHED, published.status());
        assertEquals(LATER, published.updatedAt());
        assertEquals(NOW, published.createdAt());
    }

    @Test
    void publishIsRefusedWhenAComponentIsNotPublished() {
        Combo combo = draft();

        assertThrows(ComboComponentException.class,
                () -> combo.publish(LATER, catalog(ProductStatus.PUBLISHED, ProductStatus.INACTIVE)));
        assertThrows(ComboComponentException.class,
                () -> combo.publish(LATER, catalog(ProductStatus.DRAFT, ProductStatus.PUBLISHED)));
        assertEquals(ComboStatus.DRAFT, combo.status());
    }

    @Test
    void publishIsRefusedWhenAComponentIsMissing() {
        assertThrows(ComboComponentException.class,
                () -> draft().publish(LATER, Map.of(POPCORN, product(POPCORN, ProductStatus.PUBLISHED))));
    }

    @Test
    void lifecycleFollowsTheProductOne() {
        Map<UUID, Product> ready = catalog(ProductStatus.PUBLISHED, ProductStatus.PUBLISHED);
        Combo published = draft().publish(LATER, ready);

        assertThrows(InvalidStatusTransitionException.class, () -> published.publish(LATER, ready));
        assertThrows(InvalidStatusTransitionException.class, () -> draft().deactivate(LATER));

        Combo inactive = published.deactivate(LATER);
        assertEquals(ComboStatus.INACTIVE, inactive.status());
        assertEquals(ComboStatus.PUBLISHED, inactive.publish(LATER, ready).status());
    }

    @Test
    void aRepeatedPublishIsATransitionErrorBeforeAComponentError() {
        Combo published = draft().publish(LATER, catalog(ProductStatus.PUBLISHED, ProductStatus.PUBLISHED));

        assertThrows(InvalidStatusTransitionException.class,
                () -> published.publish(LATER, catalog(ProductStatus.PUBLISHED, ProductStatus.INACTIVE)));
    }

    @Test
    void onlyAPublishedComboIsAvailableForOrders() {
        Combo combo = draft();

        assertEquals(false, combo.isAvailableForOrders());
        assertEquals(true, combo.publish(LATER, catalog(ProductStatus.PUBLISHED, ProductStatus.PUBLISHED))
                .isAvailableForOrders());
    }
}
