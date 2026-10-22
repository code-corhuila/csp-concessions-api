package co.edu.corhuila.csp.concessions.application.usecase;

import co.edu.corhuila.csp.concessions.application.port.in.StockUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.port.out.StockRepository;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The use case of HU-CONCESSIONS-003. The rules live in {@link Product}; this class only sequences them. */
public class StockService implements StockUseCases {

    private final ProductRepository products;
    private final StockRepository stock;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public StockService(ProductRepository products, StockRepository stock, Clock clock, Supplier<UUID> ids) {
        this.products = Objects.requireNonNull(products);
        this.stock = Objects.requireNonNull(stock);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public StockMovement adjust(UUID productId, int quantityDelta, String idempotencyKey, UUID requestedBy) {
        // A retry is answered before the rules run: the balance it found may have changed since.
        Optional<StockMovement> earlier = stock.findByIdempotencyKey(idempotencyKey);
        if (earlier.isPresent()) {
            return replayOf(earlier.get(), productId, quantityDelta);
        }
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("the product does not exist"));
        Instant now = clock.instant();
        Product adjusted = product.adjustStock(quantityDelta, now);
        return stock.append(new StockMovement(ids.get(), productId, quantityDelta, adjusted.availableStock(),
                MovementType.forDelta(quantityDelta), idempotencyKey, requestedBy, now));
    }

    private static StockMovement replayOf(StockMovement earlier, UUID productId, int quantityDelta) {
        if (!earlier.productId().equals(productId) || earlier.quantityDelta() != quantityDelta) {
            throw new IdempotencyKeyConflictException();
        }
        return earlier;
    }
}
