package ma.mobility.booking.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ma.mobility.booking.config.BookingRabbitConfig;
import ma.mobility.booking.model.OutboxEventEntity;
import ma.mobility.booking.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final OutboxRepository events;
    private final RabbitTemplate rabbit;
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("ma.mobility.booking-service");

    public OutboxPublisher(OutboxRepository events, RabbitTemplate rabbit) {
        this.events = events;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${booking.outbox.scan-ms:2000}")
    public void publishPending() {
        for (OutboxEventEntity event : events.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            // P3 : restaure le contexte de trace capturé lors de la transaction Saga afin que
            // la publication RabbitMQ et notification-service apparaissent dans la MÊME trace Jaeger.
            Context parent = extractParent(event.traceparent);
            Span span = tracer.spanBuilder("outbox.publish " + event.eventType)
                    .setParent(parent)
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", "rabbitmq")
                    .setAttribute("messaging.destination.name", BookingRabbitConfig.EXCHANGE)
                    .setAttribute("mobility.booking_id", event.bookingId)
                    .setAttribute("mobility.event_id", event.eventId)
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                CorrelationData correlation = new CorrelationData(event.eventId);
                rabbit.convertAndSend(BookingRabbitConfig.EXCHANGE, "booking." + event.eventType, event.payload,
                        message -> {
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            // Injecte explicitement le contexte W3C dans les headers AMQP pour notification-service.
                            GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                                    .inject(Context.current(), message.getMessageProperties(),
                                            (props, k, v) -> props.setHeader(k, v));
                            return message;
                        }, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    span.setStatus(StatusCode.ERROR, "RabbitMQ NACK: " + confirm.getReason());
                    retryLater(event, "RabbitMQ n'a pas acquitté l'événement: " + confirm.getReason());
                    continue;
                }
                event.publishedAt = Instant.now();
                events.save(event);
                log.info("Outbox publié eventId={} bookingId={} type={}", event.eventId, event.bookingId, event.eventType);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, "interrupted");
                retryLater(event, "Attente d'acquittement interrompue");
            } catch (ExecutionException | TimeoutException | AmqpException error) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, error.getMessage());
                retryLater(event, error.getMessage());
            } finally {
                span.end();
            }
        }
    }

    private static Context extractParent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), Collections.singletonMap("traceparent", traceparent), MAP_GETTER);
    }

    private void retryLater(OutboxEventEntity event, String reason) {
        event.attempts++;
        events.save(event);
        log.warn("Événement {} non acquitté, tentative {}: {}", event.eventId, event.attempts, reason);
    }
}
