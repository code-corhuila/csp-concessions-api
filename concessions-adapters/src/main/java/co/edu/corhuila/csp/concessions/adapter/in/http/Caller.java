package co.edu.corhuila.csp.concessions.adapter.in.http;

import java.util.Set;
import java.util.UUID;

/** Who makes the request: the {@code sub} of the verified token and the permissions it carries. */
record Caller(UUID userId, Set<String> permissions) {

    static final String PRODUCT_WRITE = "concessions:product:write";
    static final String COMBO_WRITE = "concessions:combo:write";

    static final String INVENTORY_WRITE = "concessions:inventory:write";
    static final String INVENTORY_READ = "concessions:inventory:read";
    static final String ORDER_CREATE = "concessions:order:create";
    static final String ORDER_READ_SELF = "concessions:order:read:self";
    static final String ORDER_CANCEL_SELF = "concessions:order:cancel:self";

    boolean canWriteInventory() {
        return permissions.contains(INVENTORY_WRITE);
    }

    boolean canReadInventory() {
        return permissions.contains(INVENTORY_READ);
    }

    boolean canCreateOrders() {
        return permissions.contains(ORDER_CREATE);
    }

    boolean canReadOwnOrders() {
        return permissions.contains(ORDER_READ_SELF);
    }

    boolean canCancelOwnOrders() {
        return permissions.contains(ORDER_CANCEL_SELF);
    }

    boolean canWriteProducts() {
        return permissions.contains(PRODUCT_WRITE);
    }

    boolean canWriteCombos() {
        return permissions.contains(COMBO_WRITE);
    }
}
