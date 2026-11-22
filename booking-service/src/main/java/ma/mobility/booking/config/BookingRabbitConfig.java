package ma.mobility.booking.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingRabbitConfig {
    public static final String EXCHANGE = "booking.events";
    public static final String QUEUE = "booking.notifications";
    @Bean TopicExchange bookingExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean Queue notificationQueue() { return new Queue(QUEUE, true); }
    @Bean Binding notificationBinding(Queue notificationQueue, TopicExchange bookingExchange) {
        return BindingBuilder.bind(notificationQueue).to(bookingExchange).with("booking.#");
    }
}
