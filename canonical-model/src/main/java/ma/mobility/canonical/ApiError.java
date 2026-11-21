package ma.mobility.canonical;

/** Erreur uniforme renvoyée par tous les bridges. */
public class ApiError {
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";               // HTTP 409
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"; // HTTP 503
    public static final String INVALID_REQUEST = "INVALID_REQUEST";           // HTTP 400

    public String code;
    public String reason;

    public ApiError() {}
    public ApiError(String code, String reason) { this.code = code; this.reason = reason; }
}