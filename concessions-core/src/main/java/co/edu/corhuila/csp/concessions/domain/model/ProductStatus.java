package co.edu.corhuila.csp.concessions.domain.model;

/** Publication lifecycle of a product: DRAFT, then PUBLISHED, which can be INACTIVE and published again. */
public enum ProductStatus {
    DRAFT,
    PUBLISHED,
    INACTIVE
}
