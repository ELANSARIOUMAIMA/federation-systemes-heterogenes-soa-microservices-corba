package ma.mobility.booking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import ma.mobility.canonical.TransportMode;

@Entity
@Table(name = "saga_step")
public class SagaStepEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    public TripBookingEntity booking;
    public int stepOrder;
    @Enumerated(EnumType.STRING)
    public TransportMode mode;
    public String offerId;
    public int seats;
    public String holdId;
    public String bookingRef;
    @Enumerated(EnumType.STRING)
    public SagaStepState state;
    public String lastError;
    public int compensationAttempts;
    public Instant heldAt;

    public SagaStepEntity() { }
}
