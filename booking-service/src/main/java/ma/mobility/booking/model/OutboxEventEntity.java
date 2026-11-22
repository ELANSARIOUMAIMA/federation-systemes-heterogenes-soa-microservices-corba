package ma.mobility.booking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "booking_outbox")
public class OutboxEventEntity {
    @Id
    public String eventId;
    public String bookingId;
    public String eventType;
    public String payload;
    public Instant createdAt;
    public Instant publishedAt;
    public int attempts;

    public OutboxEventEntity() { }
}
