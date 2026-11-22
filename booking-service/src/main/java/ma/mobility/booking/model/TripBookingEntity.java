package ma.mobility.booking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "trip_booking", uniqueConstraints = @UniqueConstraint(name = "uk_booking_idempotency", columnNames = "idempotency_key"))
public class TripBookingEntity {
    @Id
    public String id;
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    public String idempotencyKey;
    public String payloadHash;
    @Enumerated(EnumType.STRING)
    public BookingState state;
    public Instant createdAt;
    public long subtotalMinor;
    public long feesMinor;
    public long discountMinor;
    public long totalMinor;
    public String currency;
    public String failureReason;

    public TripBookingEntity() { }
}
