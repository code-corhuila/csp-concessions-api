package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.NameInUseException;
import java.util.Optional;
import java.util.UUID;

/** Output port: where combos and their components are kept. The implementation belongs to an adapter. */
public interface ComboRepository {

    /**
     * Inserts the combo, its components and its idempotency key in one transaction. When the key is
     * already stored with the same request hash, nothing is written and the original combo is
     * returned as a replay; with another hash it throws {@link IdempotencyKeyConflictException}. A name that another combo
     * that is not inactive already has throws {@link NameInUseException}.
     */
    CreateComboResult create(Combo combo, String idempotencyKey, String requestHash);

    /** Updates the status of an existing combo and returns what was stored; reactivating a name now in use is a {@link NameInUseException}. */
    Combo save(Combo combo);

    Optional<Combo> findById(UUID id);

    /** A page (starting at 1) of at most {@code limit} combos, optionally only the PUBLISHED ones. */
    ComboPage findPage(int page, int limit, boolean onlyPublished);
}
