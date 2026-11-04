package co.edu.corhuila.csp.concessions.application.port.out;

/**
 * Output port: runs a piece of work in one transaction, which the repositories called inside it join. An
 * exception thrown by the work rolls everything back.
 */
public interface TransactionRunner {

    void inTransaction(Runnable work);
}
