package ma.mobility.booking.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BookingHttpConfig {
    @Bean
    RestTemplate bookingRestTemplate(RestTemplateBuilder builder) {
        // P3 : readTimeout 6 s > budget des bridges (2 s connect + 3 s reply = 5 s), évite les faux UNKNOWN_OUTCOME.
        return builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(6)).build();
    }
}
