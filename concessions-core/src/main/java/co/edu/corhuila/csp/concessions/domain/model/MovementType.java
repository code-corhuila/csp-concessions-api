package co.edu.corhuila.csp.concessions.domain.model;

/**
 * Why the stock of a product changed. HU-CONCESSIONS-003 writes the first two, chosen by the sign of the
 * change; the reservation types arrive with HU-CONCESSIONS-004.
 */
public enum MovementType {
    RECEIPT,
    ADJUSTMENT;

    /** A positive change is a receipt and a negative one an adjustment; zero moves nothing. */
    public static MovementType forDelta(int quantityDelta) {
        if (quantityDelta == 0) {
            throw new InvalidStockMovementException("a stock movement cannot be zero");
        }
        return quantityDelta > 0 ? RECEIPT : ADJUSTMENT;
    }
}
