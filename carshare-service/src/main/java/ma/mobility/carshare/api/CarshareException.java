package ma.mobility.carshare.api;

import ma.mobility.canonical.ApiError;
import org.springframework.http.HttpStatus;

public class CarshareException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private CarshareException(HttpStatus status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
    }

    public static CarshareException invalid(String reason) {
        return new CarshareException(HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST, reason);
    }

    public static CarshareException unavailable(String reason) {
        return new CarshareException(HttpStatus.CONFLICT, ApiError.NOT_AVAILABLE, reason);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
