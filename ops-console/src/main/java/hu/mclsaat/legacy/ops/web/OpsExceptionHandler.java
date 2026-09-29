package hu.mclsaat.legacy.ops.web;

import hu.mclsaat.legacy.clients.protocol.ActivationRestClient;
import hu.mclsaat.legacy.clients.protocol.BillingSoapClient;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Normalises three unrelated error models into one.
 *
 * <p>The ops console talks to a REST subsystem that answers with JSON and HTTP statuses, a SOAP
 * subsystem that answers with faults, and a database that throws {@code DataAccessException}. A caller
 * of this console should not have to know which of the three went wrong to parse the answer — and the
 * fact that somebody has to do this translation at all is a fair part of what phase 2 is meant to
 * remove.
 */
@RestControllerAdvice
public class OpsExceptionHandler {

    /** Billing's SOAP fault, mapped onto the HTTP status its code implies. */
    @ExceptionHandler(BillingSoapClient.BillingClientException.class)
    public ResponseEntity<OpsDtos.ApiError> billingFault(BillingSoapClient.BillingClientException ex) {
        HttpStatus status = switch (ex.faultCode() == null ? "" : ex.faultCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BAD_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "ILLEGAL_STATE" -> HttpStatus.CONFLICT;
            case "UNREACHABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
                .body(new OpsDtos.ApiError("BILLING_" + ex.faultCode(), ex.getMessage()));
    }

    /** Activation refused or could not be reached. */
    @ExceptionHandler(ActivationRestClient.ActivationClientException.class)
    public ResponseEntity<OpsDtos.ApiError> activationFailure(
            ActivationRestClient.ActivationClientException ex) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("unreachable")
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(new OpsDtos.ApiError("ACTIVATION_FAILURE", ex.getMessage()));
    }

    /** The direct JDBC connection into the catalog's schema failed. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<OpsDtos.ApiError> catalogFailure(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new OpsDtos.ApiError("CATALOG_JDBC_FAILURE",
                        "the direct JDBC connection to the catalog's schema failed: "
                                + ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OpsDtos.ApiError> invalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new OpsDtos.ApiError("VALIDATION_FAILED", detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OpsDtos.ApiError> badArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new OpsDtos.ApiError("BAD_REQUEST", ex.getMessage()));
    }
}
