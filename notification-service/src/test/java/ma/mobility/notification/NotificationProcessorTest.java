package ma.mobility.notification;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.mobility.notification.model.ProcessedEventEntity;
import ma.mobility.notification.repository.ProcessedEventRepository;
import ma.mobility.notification.service.NotificationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationProcessorTest {
    @Mock ProcessedEventRepository events;
    private NotificationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationProcessor(events, new ObjectMapper());
    }

    @Test
    void persistsFirstDeliveryAndIgnoresDuplicateEventId() throws Exception {
        String payload = "{\"eventId\":\"evt-1\",\"bookingId\":\"book-1\","
                + "\"eventType\":\"BookingConfirmed\",\"state\":\"CONFIRMED\"}";
        when(events.existsById("evt-1")).thenReturn(false, true);

        processor.process(payload);
        processor.process(payload);

        verify(events).saveAndFlush(any(ProcessedEventEntity.class));
    }

    @Test
    void rejectsMalformedEventRatherThanAcknowledgingIt() {
        assertThrows(IllegalArgumentException.class, () -> processor.process("{\"eventId\":\"evt-2\"}"));
        verify(events, never()).saveAndFlush(any(ProcessedEventEntity.class));
    }
}
