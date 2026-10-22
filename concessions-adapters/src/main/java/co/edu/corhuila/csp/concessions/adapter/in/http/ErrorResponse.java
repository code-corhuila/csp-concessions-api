package co.edu.corhuila.csp.concessions.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The error envelope of the contract ({@code _shared.yaml}): {@code error}, {@code message},
 * {@code traceId} and the optional {@code details} that name every invalid field.
 * The messages never carry tokens, passwords, driver text or internal exception detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, List<FieldViolation> details, String traceId) {

    public record FieldViolation(String field, String message) {

        public static FieldViolation of(String field, String message) {
            return new FieldViolation(field, message);
        }
    }

    public static ErrorResponse of(String error, String message, String traceId) {
        return new ErrorResponse(error, message, null, traceId);
    }

    /**
     * The envelope of the contract with the fields that broke a validation, for the 400.
     */
    public static ErrorResponse of(String error, String message, List<FieldViolation> details) {
        return new ErrorResponse(error, message, details, traceIdOfTheLog());
    }

    /**
     * The envelope of the contract with the traceId of the correlation filter, so a log line and the
     * answer of its request always carry the same id, even when nothing reached a controller yet.
     */
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, traceIdOfTheLog());
    }

    /** The id in the MDC; a new one when the correlation filter has not run for this thread. */
    private static String traceIdOfTheLog() {
        String traceId = MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}
