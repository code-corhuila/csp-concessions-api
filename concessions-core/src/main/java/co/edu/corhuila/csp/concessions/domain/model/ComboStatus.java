package co.edu.corhuila.csp.concessions.domain.model;

/** Publication lifecycle of a combo: DRAFT, then PUBLISHED, which can be INACTIVE and published again. */
public enum ComboStatus {
    DRAFT,
    PUBLISHED,
    INACTIVE
}
