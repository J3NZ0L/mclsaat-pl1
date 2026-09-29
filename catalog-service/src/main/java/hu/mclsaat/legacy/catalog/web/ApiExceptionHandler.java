package hu.mclsaat.legacy.catalog.web;

import hu.mclsaat.legacy.catalog.service.CatalogException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CatalogException.NotFound.class)
    public ResponseEntity<CatalogDtos.ApiError> notFound(CatalogException.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new CatalogDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(CatalogException.IllegalTransition.class)
    public ResponseEntity<CatalogDtos.ApiError> conflict(CatalogException.IllegalTransition ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new CatalogDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(CatalogException.BadRequest.class)
    public ResponseEntity<CatalogDtos.ApiError> badRequest(CatalogException.BadRequest ex) {
        return ResponseEntity.badRequest()
                .body(new CatalogDtos.ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CatalogDtos.ApiError> invalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new CatalogDtos.ApiError("VALIDATION_FAILED", detail));
    }
}
