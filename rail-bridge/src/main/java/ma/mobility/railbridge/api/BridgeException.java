package ma.mobility.railbridge.api;

import ma.mobility.canonical.ApiError;
import org.springframework.http.HttpStatus;

/** Erreur du bridge, déjà exprimée dans le vocabulaire canonique. */
public class BridgeException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private BridgeException(HttpStatus status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
    }

    public static BridgeException notAvailable(String reason) {
        return new BridgeException(HttpStatus.CONFLICT, ApiError.NOT_AVAILABLE, reason);
    }

    public static BridgeException invalid(String reason) {
        return new BridgeException(HttpStatus.BAD_REQUEST, ApiError.INVALID_REQUEST, reason);
    }

    public static BridgeException upstreamUnavailable(String reason) {
        return new BridgeException(HttpStatus.SERVICE_UNAVAILABLE, ApiError.UPSTREAM_UNAVAILABLE, reason);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}