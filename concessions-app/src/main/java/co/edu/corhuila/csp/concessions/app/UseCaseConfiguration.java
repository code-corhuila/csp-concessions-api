package co.edu.corhuila.csp.concessions.app;

import co.edu.corhuila.csp.concessions.application.port.in.ComboUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.StockUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.ComboRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ComponentProducts;
import co.edu.corhuila.csp.concessions.application.port.out.OrderRepository;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.port.out.StockRepository;
import co.edu.corhuila.csp.concessions.application.usecase.ComboService;
import co.edu.corhuila.csp.concessions.application.usecase.OrderService;
import co.edu.corhuila.csp.concessions.application.usecase.ProductService;
import co.edu.corhuila.csp.concessions.application.usecase.StockService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the use cases meet their ports (Anexo C, composition root). Nothing in the application
 * reads the wall time or draws an identifier by itself: both come from here, so a test can freeze them.
 */
@Configuration
public class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProductUseCases productUseCases(ProductRepository products, Clock clock) {
        return new ProductService(products, clock, UUID::randomUUID);
    }

    @Bean
    StockUseCases stockUseCases(ProductRepository products, StockRepository stock, Clock clock) {
        return new StockService(products, stock, clock, UUID::randomUUID);
    }

    /** {@code ORDER_HOLD_MINUTES}: how long an order without a reservation keeps its stock (ADR-045). */
    @Bean
    OrderUseCases orderUseCases(OrderRepository orders, ProductRepository products, ComboRepository combos,
                                ComponentProducts components, Clock clock,
                                @Value("${ORDER_HOLD_MINUTES:15}") long holdMinutes) {
        return new OrderService(orders, products, combos, components, clock, UUID::randomUUID,
                Duration.ofMinutes(holdMinutes));
    }

    @Bean
    ComboUseCases comboUseCases(ComboRepository combos, ComponentProducts components, Clock clock) {
        return new ComboService(combos, components, clock, UUID::randomUUID);
    }
}
