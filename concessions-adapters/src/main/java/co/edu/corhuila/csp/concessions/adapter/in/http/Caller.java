package co.edu.corhuila.csp.concessions.adapter.in.http;

import java.util.Set;
import java.util.UUID;

/** Who makes the request: the {@code sub} of the verified token and the permissions it carries. */
record Caller(UUID userId, Set<String> permissions) {

    static final String PRODUCT_WRITE = "concessions:product:write";

    boolean canWriteProducts() {
        return permissions.contains(PRODUCT_WRITE);
    }
}
