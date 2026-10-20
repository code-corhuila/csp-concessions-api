package co.edu.corhuila.csp.concessions.application.usecase;

import co.edu.corhuila.csp.concessions.application.port.in.CreateProductInput;
import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** The use cases of HU-CONCESSIONS-001. The rules live in {@link Product}; this class only sequences them. */
public class ProductService implements ProductUseCases {

    private final ProductRepository repository;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ProductService(ProductRepository repository, Clock clock, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public Product create(CreateProductInput input) {
        Product product = Product.draft(ids.get(), input.name(), input.description(), input.category(),
                input.price(), input.availableStock(), clock.instant());
        return repository.save(product);
    }

    @Override
    public Product changeStatus(UUID productId, ProductStatus target) {
        Product product = repository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("the product does not exist"));
        Product changed = switch (target) {
            case PUBLISHED -> product.publish(clock.instant());
            case INACTIVE -> product.deactivate(clock.instant());
            case DRAFT -> throw new InvalidStatusTransitionException(
                    "a product cannot go back to draft");
        };
        return repository.save(changed);
    }

    @Override
    public ProductPage list(int page, int limit, boolean onlyPublished) {
        if (page < 1 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("page starts at 1 and limit is between 1 and " + MAX_LIMIT);
        }
        return repository.findPage(page, limit, onlyPublished);
    }
}
