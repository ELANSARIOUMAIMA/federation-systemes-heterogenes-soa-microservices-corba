package ma.mobility.booking.api;

import ma.mobility.canonical.ApiError;
import org.springframework.http.HttpStatus;

public class BookingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public BookingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static BookingException invalid(String message) {
        return new BookingException(HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST, message);
    }
    public static BookingException conflict(String message) {
        return new BookingException(HttpStatus.CONFLICT, ApiError.NOT_AVAILABLE, message);
    }
    public static BookingException unavailable(String message) {
        return new BookingException(HttpStatus.SERVICE_UNAVAILABLE, ApiError.UPSTREAM_UNAVAILABLE, message);
    }
}
