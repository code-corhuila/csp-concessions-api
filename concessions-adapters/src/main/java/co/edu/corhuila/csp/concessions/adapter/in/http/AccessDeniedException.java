package co.edu.corhuila.csp.concessions.adapter.in.http;

/** The caller is authenticated but the token lacks the permission the route needs: a 403. */
class AccessDeniedException extends RuntimeException {

    AccessDeniedException(String message) {
        super(message);
    }
}
