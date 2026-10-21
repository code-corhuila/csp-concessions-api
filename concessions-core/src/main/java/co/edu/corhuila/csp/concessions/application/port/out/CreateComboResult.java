package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Combo;

/** The combo of a creation and whether it is the one stored by an earlier request with the same key. */
public record CreateComboResult(Combo combo, boolean replayed) {
}
