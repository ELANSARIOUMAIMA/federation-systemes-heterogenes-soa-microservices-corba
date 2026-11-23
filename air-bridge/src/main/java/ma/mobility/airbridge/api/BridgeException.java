package ma.mobility.airbridge.api;

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

    /** P3 : circuit breaker ouvert, le partenaire n'a PAS été appelé (issue certaine, aucun effet de bord). */
    public static BridgeException circuitOpen(String reason) {
        return new BridgeException(HttpStatus.SERVICE_UNAVAILABLE, ApiError.CIRCUIT_OPEN, reason);
    }

    /** Panne technique du partenaire (comptée par le circuit breaker), par opposition à un refus métier (409). */
    public boolean isUpstreamFailure() {
        return ApiError.UPSTREAM_UNAVAILABLE.equals(code);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}