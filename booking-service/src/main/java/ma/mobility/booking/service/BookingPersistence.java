package ma.mobility.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.mobility.booking.api.BookingRequest;
import ma.mobility.booking.model.SagaStepEntity;
import ma.mobility.booking.model.SagaStepState;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.model.OutboxEventEntity;
import ma.mobility.booking.model.TripBookingEntity;
import ma.mobility.booking.repository.BookingRepository;
import ma.mobility.booking.repository.OutboxRepository;
import ma.mobility.booking.repository.SagaStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingPersistence {
    private final BookingRepository bookings;
    private final SagaStepRepository steps;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public BookingPersistence(BookingRepository bookings, SagaStepRepository steps, OutboxRepository outbox,
                              ObjectMapper mapper) {
        this.bookings = bookings;
        this.steps = steps;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public List<SagaStepEntity> create(TripBookingEntity booking, List<BookingRequest.Segment> segments) {
        TripBookingEntity savedBooking = bookings.saveAndFlush(booking);
        List<SagaStepEntity> initialSteps = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            BookingRequest.Segment segment = segments.get(i);
            SagaStepEntity step = new SagaStepEntity();
            step.booking = savedBooking;
            step.stepOrder = i;
            step.mode = segment.mode;
            step.offerId = segment.offerId;
            step.seats = segment.seats;
            step.state = SagaStepState.PENDING;
            initialSteps.add(step);
        }
        return steps.saveAllAndFlush(initialSteps);
    }

    @Transactional
    public TripBookingEntity transition(TripBookingEntity booking, BookingState state, String reason) {
        booking.state = state;
        booking.failureReason = reason;
        TripBookingEntity saved = bookings.save(booking);
        String eventType = switch (state) {
            case HELD -> "BookingHeld";
            case CONFIRMED -> "BookingConfirmed";
            case CANCELLED -> "BookingCancelled";
            default -> null;
        };
        if (eventType != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", UUID.randomUUID().toString());
            payload.put("eventType", eventType);
            payload.put("bookingId", saved.id);
            payload.put("timestamp", Instant.now());
            payload.put("state", state);
            payload.put("totalMinor", saved.totalMinor);
            payload.put("currency", saved.currency);
            try {
                OutboxEventEntity event = new OutboxEventEntity();
                event.eventId = (String) payload.get("eventId");
                event.bookingId = saved.id;
                event.eventType = eventType;
                event.payload = mapper.writeValueAsString(payload);
                event.createdAt = Instant.now();
                outbox.save(event);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Échec de sérialisation de l'événement outbox", error);
            }
        }
        return saved;
    }
}
