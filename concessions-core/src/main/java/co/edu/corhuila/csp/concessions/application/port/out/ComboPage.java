package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Combo;
import java.util.List;
import java.util.Objects;

/** One page of combos: the items and how many exist in total. */
public record ComboPage(List<Combo> items, long total) {

    public ComboPage {
        Objects.requireNonNull(items, "the items are required");
    }
}
