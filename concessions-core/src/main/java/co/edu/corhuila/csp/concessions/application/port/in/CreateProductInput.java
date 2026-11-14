package co.edu.corhuila.csp.concessions.application.port.in;

/** What an administrator submits to create a product. Money is an integer in cents. */
public record CreateProductInput(String name, String description, String category, long price, int availableStock) {
}
