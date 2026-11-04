package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import java.util.List;

/** What an administrator submits to create a combo. Money is an integer in cents. */
public record CreateComboInput(String name, String description, long price, List<ComboItem> items) {
}
