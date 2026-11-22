package ma.mobility.booking.api;

import java.util.List;
import ma.mobility.canonical.TransportMode;

public class BookingRequest {
    public List<Segment> segments;

    public static class Segment {
        public TransportMode mode;
        public String offerId;
        public int seats;
        public String origin;
        public String destination;
        public String date;
    }
}
