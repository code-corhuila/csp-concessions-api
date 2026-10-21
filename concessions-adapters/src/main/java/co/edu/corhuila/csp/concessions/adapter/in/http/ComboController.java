package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.ComboUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
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
 * The combo routes of {@code concessions-service.yaml}. Writing needs the permission
 * {@code concessions:combo:write} (ADMIN); a caller without it reads only the PUBLISHED combos.
 * Every other answer of the contract is produced by {@link ApiExceptionHandler}.
 */
@RestController
@Validated
public class ComboController {

    private final ComboUseCases useCases;

    public ComboController(ComboUseCases useCases) {
        this.useCases = useCases;
    }

    /** 201 for a new combo, 200 with the original when the key already created it (Norma 5.3.8). */
    @PostMapping("/combos")
    ResponseEntity<ComboResponse> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody ComboRequest request,
            Caller caller) {
        requireWrite(caller);
        CreateComboResult result = useCases.create(request.toInput(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ComboResponse.of(result.combo()));
    }

    @GetMapping("/combos")
    ComboListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            Caller caller) {
        return ComboListResponse.of(useCases.list(page, limit, !caller.canWriteCombos()), page, limit);
    }

    /**
     * Moves the combo along its lifecycle. Publishing checks the components again (422 when one is not a
     * PUBLISHED product). The key is only checked for its shape, as in the product route: the transition
     * itself is the guard against a repeated call, answered 409.
     */
    @PatchMapping("/combos/{comboId}")
    ComboResponse changeStatus(
            @PathVariable UUID comboId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody ComboStatusRequest request,
            Caller caller) {
        requireWrite(caller);
        return ComboResponse.of(useCases.changeStatus(comboId, request.status()));
    }

    private static void requireWrite(Caller caller) {
        if (!caller.canWriteCombos()) {
            throw new AccessDeniedException("the token does not grant " + Caller.COMBO_WRITE);
        }
    }
}
