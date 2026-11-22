package ma.mobility.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import ma.mobility.notification.model.ProcessedEventEntity;
import ma.mobility.notification.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationProcessor {
    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);
    private final ProcessedEventRepository events;
    private final ObjectMapper mapper;

    public NotificationProcessor(ProcessedEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(String body) throws Exception {
        JsonNode event = mapper.readTree(body);
        String id = required(event, "eventId");
        if (events.existsById(id)) {
            log.info("Notification dupliquée ignorée eventId={}", id);
            return;
        }
        ProcessedEventEntity saved = new ProcessedEventEntity();
        saved.eventId = id;
        saved.bookingId = required(event, "bookingId");
        saved.eventType = required(event, "eventType");
        saved.state = required(event, "state");
        saved.receivedAt = Instant.now();
        events.saveAndFlush(saved);
        log.info("Événement réservation reçu eventId={} bookingId={} type={} statut={}",
                saved.eventId, saved.bookingId, saved.eventType, saved.state);
    }

    private String required(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Champ événement manquant: " + field);
        }
        return value.asText();
    }
}
