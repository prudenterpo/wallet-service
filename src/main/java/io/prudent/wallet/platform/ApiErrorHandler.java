package io.prudent.wallet.platform;

import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiErrorHandler {
    public record ApiError(String code, String message, Instant timestamp, List<String> details) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(
                new ApiError(exception.code(), exception.getMessage(), Instant.now(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(
                new ApiError("VALIDATION_ERROR", "Request validation failed", Instant.now(), details));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(409).body(
                new ApiError("RESOURCE_CONFLICT", "A resource with the same organization-scoped identity already exists", Instant.now(), List.of()));
    }
}
