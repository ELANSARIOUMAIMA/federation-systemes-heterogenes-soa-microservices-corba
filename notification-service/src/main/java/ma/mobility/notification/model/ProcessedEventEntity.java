package ma.mobility.notification.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "processed_booking_event")
public class ProcessedEventEntity {
    @Id
    public String eventId;
    public String bookingId;
    public String eventType;
    public String state;
    public Instant receivedAt;

    public ProcessedEventEntity() { }
}
