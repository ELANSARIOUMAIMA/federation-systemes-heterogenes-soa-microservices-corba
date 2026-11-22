package ma.mobility.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class BookingEventListener {
    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);
    private final NotificationProcessor processor;

    public BookingEventListener(NotificationProcessor processor) { this.processor = processor; }

    @RabbitListener(queues = "booking.notifications")
    public void receive(String payload) throws Exception {
        try {
            processor.process(payload);
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Événement déjà persisté (redélivraison concurrente), ignoré");
        }
    }
}
