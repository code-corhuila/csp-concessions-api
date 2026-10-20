package co.edu.corhuila.csp.concessions.app;

import co.edu.corhuila.csp.concessions.application.port.in.ProductUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.ProductRepository;
import co.edu.corhuila.csp.concessions.application.usecase.ProductService;
import java.time.Clock;
import java.util.UUID;
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
}
