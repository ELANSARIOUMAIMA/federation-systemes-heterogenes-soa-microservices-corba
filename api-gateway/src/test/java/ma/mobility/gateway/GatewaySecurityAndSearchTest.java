package ma.mobility.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import ma.mobility.canonical.Offer;
import ma.mobility.gateway.api.GatewayController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {
        "gateway.auth.username=test-user",
        "gateway.auth.password=test-password",
        "gateway.retry.wait-ms=0",
        "gateway.circuit-breaker.open-seconds=60"
})
@AutoConfigureMockMvc
class GatewaySecurityAndSearchTest {
    @Autowired MockMvc mvc;
    @Autowired GatewayController controller;
    @MockBean RestTemplate http;

    @BeforeEach
    void resetCircuitBreakers() {
        controller.circuitBreakers().values().forEach(CircuitBreaker::reset);
    }

    @Test
    void requiresBasicAuthenticationAndReturnsAvailableSourcesWhenAirIsDown() throws Exception {
        mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                        .param("destination", "Rabat").param("date", "2026-11-15"))
                .andExpect(status().isUnauthorized());
        // /actuator/health reste accessible sans auth (sondes et diagnostic)
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        when(http.getForObject(anyString(), eq(Offer[].class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0, String.class);
            if (uri.contains("localhost:8083")) throw new ResourceAccessException("test outage");
            Offer offer = new Offer();
            offer.offerId = uri.contains("localhost:8082") ? "RAIL:TEST" : "CAR:TEST";
            offer.departure = "2026-11-15T10:00:00";
            offer.priceMinor = 1000;
            return new Offer[] { offer };
        });
        mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                        .param("destination", "Rabat").param("date", "2026-11-15")
                        .header("Authorization", basicAuth()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Partial-Results", "air"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void repeatedRailOutagesOpenGatewayCircuitBreakerAndReturnFastDegradedResponse() throws Exception {
        AtomicInteger railCalls = new AtomicInteger();
        when(http.getForObject(anyString(), eq(Offer[].class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0, String.class);
            if (uri.contains("localhost:8082")) {
                railCalls.incrementAndGet();
                throw new ResourceAccessException("rail stopped");
            }
            Offer offer = new Offer();
            offer.offerId = uri.contains("localhost:8083") ? "AIR:TEST" : "CAR:TEST";
            offer.departure = "2026-11-15T10:00:00";
            offer.priceMinor = 4500;
            return new Offer[] { offer };
        });

        // 2 recherches x 2 tentatives (retry idempotent) = 4 échecs >= minimum-calls(3) -> circuit OPEN
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                            .param("destination", "Rabat").param("date", "2026-11-15")
                            .header("Authorization", basicAuth()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Partial-Results", "rail"));
        }
        assertEquals(CircuitBreaker.State.OPEN, controller.circuitBreakers().get("rail").getState());
        int callsWhenOpened = railCalls.get();

        // L'appel suivant renvoie un mode dégradé immédiat (CIRCUIT_OPEN) sans rappeler rail-bridge
        mvc.perform(get("/api/v1/search").param("origin", "Casablanca")
                        .param("destination", "Rabat").param("date", "2026-11-15")
                        .header("Authorization", basicAuth()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Partial-Results", "rail"))
                .andExpect(header().string("X-Degraded-Reason", "rail:CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.length()").value(2));
        assertEquals(callsWhenOpened, railCalls.get());
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                "test-user:test-password".getBytes(StandardCharsets.UTF_8));
    }
}
