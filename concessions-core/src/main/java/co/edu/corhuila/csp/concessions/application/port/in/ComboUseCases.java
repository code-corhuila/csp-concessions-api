package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import java.util.UUID;

/** Input port of the combo use cases (HU-CONCESSIONS-002). */
public interface ComboUseCases {

    /** The most items a page may hold (Norma 5.3.10). */
    int MAX_LIMIT = 100;

    /** Creates a DRAFT combo; repeating the key with the same request returns the original one. */
    CreateComboResult create(CreateComboInput input, String idempotencyKey);

    /** Moves the combo to {@code target} when the lifecycle and, for PUBLISHED, its components allow it. */
    Combo changeStatus(UUID comboId, ComboStatus target);

    /** A page of combos, newest first; {@code onlyPublished} is what a client may see. */
    ComboPage list(int page, int limit, boolean onlyPublished);
}
