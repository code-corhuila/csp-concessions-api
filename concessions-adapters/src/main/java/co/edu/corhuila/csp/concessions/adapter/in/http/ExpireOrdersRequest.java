package co.edu.corhuila.csp.concessions.adapter.in.http;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** {@code MaintenanceRequest} of the contract: how many orders one call may release (Norma 5.3.10). */
record ExpireOrdersRequest(@Min(1) @Max(500) Integer batchSize) {
}
