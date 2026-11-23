package ma.mobility.gateway.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class GatewayHttpConfig {
    @Bean RestTemplate gatewayRestTemplate(RestTemplateBuilder builder) {
        // P3 : readTimeout 20 s pour ne pas couper une Saga 3 modes + compensation ;
        // la recherche /api/v1/search applique son propre délai court (3,5 s) + circuit breaker par source.
        return builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(20)).build();
    }
}
