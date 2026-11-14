package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three product routes of {@code concessions-service.yaml}. Writing needs the permission
 * {@code concessions:product:write} (ADMIN); a caller without it reads only the PUBLISHED products.
 * Every other answer of the contract is produced by {@link ApiExceptionHandler}.
 */
@RestController
@Validated
public class ProductController {

    private final ProductUseCases useCases;

    public ProductController(ProductUseCases useCases) {
        this.useCases = useCases;
    }

    /** 201 for a new product, 200 with the original when the key already created it (Norma 5.3.8). */
    @PostMapping("/products")
    ResponseEntity<ProductResponse> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody ProductRequest request,
            Caller caller) {
        requireWrite(caller);
        CreateProductResult result = useCases.create(request.toInput(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ProductResponse.of(result.product()));
    }

    @GetMapping("/products")
    ProductListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            Caller caller) {
        return ProductListResponse.of(useCases.list(page, limit, !caller.canWriteProducts()), page, limit);
    }

    /**
     * Moves the product along its lifecycle. The key is only checked for its shape (required, 8 to 128
     * characters, as the contract says): the transition itself is the guard against a repeated call,
     * which is answered 409 like any other move the lifecycle does not allow.
     */
    @PatchMapping("/products/{productId}")
    ProductResponse changeStatus(
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody ProductStatusRequest request,
            Caller caller) {
        requireWrite(caller);
        return ProductResponse.of(useCases.changeStatus(productId, request.status()));
    }

    private static void requireWrite(Caller caller) {
        if (!caller.canWriteProducts()) {
            throw new AccessDeniedException("the token does not grant " + Caller.PRODUCT_WRITE);
        }
    }
}
