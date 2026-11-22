package ma.mobility.carshare.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "car_reservation")
public class CarReservationEntity {
    public enum State { HELD, CONFIRMED, CANCELLED, EXPIRED }

    @Id
    public String holdId;
    public String bookingRef;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id", nullable = false)
    public CarOfferEntity offer;
    public int seats;
    @Enumerated(EnumType.STRING)
    public State state;
    public Instant expiresAt;
    public Instant createdAt;

    public CarReservationEntity() {
    }
}
