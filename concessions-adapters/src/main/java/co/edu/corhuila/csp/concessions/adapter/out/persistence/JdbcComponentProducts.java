package co.edu.corhuila.csp.concessions.adapter.out.persistence;

import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the products a combo refers to from the product table of this same schema. */
@Repository
public class JdbcComponentProducts implements ComponentProducts {

    private final JdbcTemplate jdbc;

    public JdbcComponentProducts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Product> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Object[] distinct = new LinkedHashSet<>(ids).toArray();
        String marks = String.join(", ", java.util.Collections.nCopies(distinct.length, "?"));
        return jdbc.query(JdbcProductRepository.SELECT_PRODUCT + "WHERE id IN (" + marks + ")",
                        JdbcProductRepository::toProduct, distinct).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
    }
}
