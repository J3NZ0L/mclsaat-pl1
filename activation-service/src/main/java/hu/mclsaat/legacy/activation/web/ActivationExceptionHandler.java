package hu.mclsaat.legacy.activation.web;

import hu.mclsaat.legacy.activation.client.BillingSoapClient;
import hu.mclsaat.legacy.activation.client.CatalogClientException;
import hu.mclsaat.legacy.activation.service.ActivationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ActivationExceptionHandler {

    @ExceptionHandler(ActivationException.NotFound.class)
    public ResponseEntity<ActivationDtos.ApiError> notFound(ActivationException.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ActivationDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ActivationException.BadRequest.class)
    public ResponseEntity<ActivationDtos.ApiError> badRequest(ActivationException.BadRequest ex) {
        return ResponseEntity.badRequest()
                .body(new ActivationDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ActivationException.IllegalState.class)
    public ResponseEntity<ActivationDtos.ApiError> conflict(ActivationException.IllegalState ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ActivationDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ActivationDtos.ApiError> invalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ActivationDtos.ApiError("VALIDATION_FAILED", detail));
    }

    /**
     * A neighbouring subsystem refused or could not be reached. Reported as 502, because from the
     * caller's point of view this service is fine and one of its dependencies is not.
     */
    @ExceptionHandler({CatalogClientException.class, BillingSoapClient.BillingClientException.class})
    public ResponseEntity<ActivationDtos.ApiError> downstream(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ActivationDtos.ApiError("DOWNSTREAM_FAILURE", ex.getMessage()));
    }
}
