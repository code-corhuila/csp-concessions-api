package co.edu.corhuila.csp.concessions.application.usecase;

import co.edu.corhuila.csp.concessions.application.port.in.ComboUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.CreateComboInput;
import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboComponentException;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ComboNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** The use cases of HU-CONCESSIONS-002. The rules live in {@link Combo}; this class only sequences them. */
public class ComboService implements ComboUseCases {

    private final ComboRepository repository;
    private final ComponentProducts products;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ComboService(ComboRepository repository, ComponentProducts products, Clock clock, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.products = Objects.requireNonNull(products);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public CreateComboResult create(CreateComboInput input, String idempotencyKey) {
        Combo combo = Combo.draft(ids.get(), input.name(), input.description(), input.price(), input.items(),
                clock.instant());
        Map<UUID, Product> found = componentsOf(combo);
        for (ComboItem item : combo.items()) {
            if (!found.containsKey(item.productId())) {
                throw new ComboComponentException("the product " + item.productId() + " does not exist");
            }
        }
        return repository.create(combo, idempotencyKey, hashOf(combo));
    }

    /** SHA-256 of what the client sent, with the components in a fixed order so a reordered retry matches. */
    private static String hashOf(Combo combo) {
        String items = combo.items().stream()
                .sorted(Comparator.comparing(ComboItem::productId))
                .map(item -> item.productId() + "x" + item.quantity())
                .collect(Collectors.joining(","));
        String canonical = String.join(" ", combo.name(), String.valueOf(combo.description()),
                String.valueOf(combo.price()), items);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is always available", exception);
        }
    }

    @Override
    public Combo changeStatus(UUID comboId, ComboStatus target) {
        Combo combo = repository.findById(comboId)
                .orElseThrow(() -> new ComboNotFoundException("the combo does not exist"));
        Combo changed = switch (target) {
            case PUBLISHED -> combo.publish(clock.instant(), componentsOf(combo));
            case INACTIVE -> combo.deactivate(clock.instant());
            case DRAFT -> throw new InvalidStatusTransitionException("a combo cannot go back to draft");
        };
        return repository.save(changed);
    }

    @Override
    public ComboPage list(int page, int limit, boolean onlyPublished) {
        if (page < 1 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("page starts at 1 and limit is between 1 and " + MAX_LIMIT);
        }
        return repository.findPage(page, limit, onlyPublished);
    }

    private Map<UUID, Product> componentsOf(Combo combo) {
        List<UUID> productIds = combo.items().stream().map(ComboItem::productId).toList();
        return products.findByIds(productIds);
    }
}
