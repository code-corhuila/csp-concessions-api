package co.edu.corhuila.csp.concessions.domain.model;

/** There is no product with the requested identifier. */
public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}
