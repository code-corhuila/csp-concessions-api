package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.ComboComponentException;
import co.edu.corhuila.csp.concessions.domain.model.ComboNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidComboException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidProductException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every answer that is not a success, in the codes and messages of {@code concessions-service.yaml}:
 * the status, the {@code error} code, a message copied from the examples of the contract, the
 * fields that broke a validation when there are any, and the traceId the correlation filter put in
 * the MDC. No answer carries a stack trace, a driver message or a token.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 400: a body or a header value out of the bounds of the contract, with the fields. */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> invalidInput(Exception exception) {
        List<ErrorResponse.FieldViolation> details;
        if (exception instanceof MethodArgumentNotValidException invalidBody) {
            details = invalidBody.getBindingResult().getFieldErrors().stream()
                    .map(error -> ErrorResponse.FieldViolation.of(error.getField(), error.getDefaultMessage()))
                    .toList();
        } else {
            details = ((ConstraintViolationException) exception).getConstraintViolations().stream()
                    .map(violation -> ErrorResponse.FieldViolation.of(
                            violation.getPropertyPath().toString(), violation.getMessage()))
                    .toList();
        }
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input data", details);
    }

    /** 400: a request the router or the deserializer cannot make sense of. */
    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorResponse> unreadableRequest() {
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input data", null);
    }

    /**
     * 400: the request broke one of the bounds the contract declares and the ports guard (the page
     * and the limit of a list). The bound is named in the answer instead of the service
     * failing as if the request had reached a bug.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> outOfBounds(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "Invalid input data" : exception.getMessage();
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null);
    }

    /** 403: the token is valid but lacks the permission the route needs. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(AccessDeniedException exception) {
        return answer(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), null);
    }

    /** 400: the domain refused the values of the product. */
    @ExceptionHandler(InvalidProductException.class)
    ResponseEntity<ErrorResponse> invalidProduct(InvalidProductException exception) {
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), null);
    }

    /** 400: the domain refused the values of the combo. */
    @ExceptionHandler(InvalidComboException.class)
    ResponseEntity<ErrorResponse> invalidCombo(InvalidComboException exception) {
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), null);
    }

    /** 422: a component of the combo is a product that does not exist or, at publication, is not published. */
    @ExceptionHandler(ComboComponentException.class)
    ResponseEntity<ErrorResponse> invalidComponent(ComboComponentException exception) {
        return answer(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", exception.getMessage(), null);
    }

    /** 404: no combo with that id. */
    @ExceptionHandler(ComboNotFoundException.class)
    ResponseEntity<ErrorResponse> comboNotFound(ComboNotFoundException exception) {
        return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), null);
    }

    /** 404: no product with that id. */
    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ErrorResponse> productNotFound(ProductNotFoundException exception) {
        return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), null);
    }

    /** 404: nothing answers that path. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorResponse> noRoute() {
        return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource does not exist", null);
    }

    /** 409: the key was already used with another payload, nothing was created (Norma 5.3.8). */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ErrorResponse> conflict(IdempotencyKeyConflictException exception) {
        return answer(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), null);
    }

    /** 409: the lifecycle does not allow the status change (concessions-service.yaml, updateProductStatus). */
    @ExceptionHandler(InvalidStatusTransitionException.class)
    ResponseEntity<ErrorResponse> invalidTransition(InvalidStatusTransitionException exception) {
        return answer(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", exception.getMessage(), null);
    }

    /**
     * 503: the database cannot be reached, the dependency of this service is unavailable. A write
     * opens its transaction first, so it fails with {@code CannotCreateTransactionException} and not
     * with the connection exception a read gets.
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(HttpServletRequest request, Exception exception) {
        // The answer is the same for a database that is down and for a pool that ran out of connections; the
        // root cause tells the two apart for whoever receives the alert.
        log.warn("dependency unavailable answering {} {}: {} ({})", request.getMethod(), request.getRequestURI(),
                exception.getClass().getSimpleName(), rootCauseOf(exception).getMessage());
        return answer(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "A required service or dependency is unavailable", null);
    }

    /** 500: everything else, logged with its stack trace under the traceId of the request. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(HttpServletRequest request, Exception exception) {
        log.error("unexpected error answering {} {}", request.getMethod(), request.getRequestURI(), exception);
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", null);
    }

    private static Throwable rootCauseOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static ResponseEntity<ErrorResponse> answer(
            HttpStatus status, String error, String message, List<ErrorResponse.FieldViolation> details) {
        return ResponseEntity.status(status).body(ErrorResponse.of(error, message, details));
    }
}
