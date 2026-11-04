package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import jakarta.validation.constraints.NotNull;

/** The body of {@code PATCH /combos/{comboId}} ({@code ComboStatusUpdate} of the contract). */
record ComboStatusRequest(@NotNull ComboStatus status) {
}
