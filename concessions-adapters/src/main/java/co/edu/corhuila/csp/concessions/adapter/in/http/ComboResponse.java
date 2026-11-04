package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.domain.model.Combo;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** {@code Combo} of the contract. The domain object is never serialised directly. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ComboResponse(UUID id, String name, String description, long price, List<Item> items, String status) {

    record Item(UUID productId, int quantity) {
    }

    static ComboResponse of(Combo combo) {
        return new ComboResponse(combo.id(), combo.name(), combo.description(), combo.price(),
                combo.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList(),
                combo.status().name());
    }
}
