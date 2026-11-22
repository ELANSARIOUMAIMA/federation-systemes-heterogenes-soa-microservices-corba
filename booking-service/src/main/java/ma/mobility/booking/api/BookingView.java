package ma.mobility.booking.api;

import java.time.Instant;
import java.util.List;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.model.SagaStepState;
import ma.mobility.canonical.TransportMode;

public class BookingView {
    public String bookingId;
    public BookingState state;
    public Instant createdAt;
    public long subtotalMinor;
    public long feesMinor;
    public long discountMinor;
    public long totalMinor;
    public String currency;
    public String failureReason;
    public List<Step> steps;

    public static class Step {
        public int order;
        public TransportMode mode;
        public String offerId;
        public int seats;
        public String holdId;
        public String bookingRef;
        public SagaStepState state;
        public String lastError;
        public int compensationAttempts;
    }
}
