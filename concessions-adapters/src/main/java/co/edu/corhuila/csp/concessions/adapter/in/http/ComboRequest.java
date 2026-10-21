package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.CreateComboInput;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** The body of {@code POST /combos} ({@code ComboRequest} of the contract). */
record ComboRequest(
        @NotBlank @Size(max = Combo.NAME_MAX) String name,
        @Size(max = Combo.DESCRIPTION_MAX) String description,
        @NotNull @Min(0) Long price,
        @NotEmpty @Size(max = Combo.ITEMS_MAX) List<@Valid @NotNull Item> items) {

    /** One component ({@code ComboItem} of the contract). */
    record Item(@NotNull UUID productId, @NotNull @Min(1) Integer quantity) {
    }

    CreateComboInput toInput() {
        return new CreateComboInput(name, description, price,
                items.stream().map(item -> new ComboItem(item.productId(), item.quantity())).toList());
    }
}
