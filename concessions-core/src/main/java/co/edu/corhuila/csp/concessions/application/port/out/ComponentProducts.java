package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Output port: the products a combo refers to, read from the product store of this service. */
public interface ComponentProducts {

    /** The products that exist among {@code ids}, by identifier; a missing id is simply absent. */
    Map<UUID, Product> findByIds(Collection<UUID> ids);
}
