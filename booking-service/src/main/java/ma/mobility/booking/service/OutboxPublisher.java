package ma.mobility.booking.service;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ma.mobility.booking.config.BookingRabbitConfig;
import ma.mobility.booking.model.OutboxEventEntity;
import ma.mobility.booking.repository.OutboxRepository;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository events;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(OutboxRepository events, RabbitTemplate rabbit) {
        this.events = events;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${booking.outbox.scan-ms:2000}")
    public void publishPending() {
        for (OutboxEventEntity event : events.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                CorrelationData correlation = new CorrelationData(event.eventId);
                rabbit.convertAndSend(BookingRabbitConfig.EXCHANGE, "booking." + event.eventType, event.payload,
                        message -> {
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return message;
                        }, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    retryLater(event, "RabbitMQ n'a pas acquitté l'événement: " + confirm.getReason());
                    continue;
                }
                event.publishedAt = Instant.now();
                events.save(event);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                retryLater(event, "Attente d'acquittement interrompue");
            } catch (ExecutionException | TimeoutException | AmqpException error) {
                retryLater(event, error.getMessage());
            }
        }
    }

    private void retryLater(OutboxEventEntity event, String reason) {
        event.attempts++;
        events.save(event);
        log.warn("Événement {} non acquitté, tentative {}: {}", event.eventId, event.attempts, reason);
    }
}
