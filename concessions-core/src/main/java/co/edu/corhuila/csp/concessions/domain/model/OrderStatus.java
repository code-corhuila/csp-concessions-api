package co.edu.corhuila.csp.concessions.domain.model;

/**
 * Lifecycle of a concession order (ADR-045). HU-CONCESSIONS-004 stores RESERVED, CONFIRMED and CANCELLED only;
 * PENDING, READY and DELIVERED are in the contract for later stories and cannot be reached.
 */
public enum OrderStatus {
    PENDING,
    RESERVED,
    CONFIRMED,
    CANCELLED,
    READY,
    DELIVERED
}
