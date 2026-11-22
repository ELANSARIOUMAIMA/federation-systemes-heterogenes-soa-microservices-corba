package ma.mobility.pricing.api;

import java.util.List;
import ma.mobility.canonical.TransportMode;

public class QuoteRequest {
    public List<Segment> segments;

    public static class Segment {
        public TransportMode mode;
        public long priceMinor;
        public int seats;
        public String currency = "MAD";
    }
}
