package ma.mobility.pricing.api;

import ma.mobility.canonical.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PricingErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError(ApiError.INVALID_REQUEST, error.getMessage()));
    }
}
