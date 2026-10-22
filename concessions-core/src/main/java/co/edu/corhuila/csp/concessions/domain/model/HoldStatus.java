package co.edu.corhuila.csp.concessions.domain.model;

/** HELD stock is committed when the order is confirmed or released when it is cancelled, once. */
public enum HoldStatus {
    HELD,
    COMMITTED,
    RELEASED
}
