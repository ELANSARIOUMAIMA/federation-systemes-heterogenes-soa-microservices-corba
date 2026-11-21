package ma.mobility.canonical;

/** Offre de transport au format pivot (couche anti-corruption). */
public class Offer {
    public String offerId;      // "RAIL:RAIL-CAS-RAB-0730", "AIR:..." ou "CAR:..."
    public TransportMode mode;
    public String origin;
    public String destination;
    public String departure;    // ISO-8601, ex. 2026-11-15T07:30:00
    public String arrival;      // ISO-8601
    public long priceMinor;     // centimes de MAD
    public String currency = "MAD";
    public int seatsAvailable;
}