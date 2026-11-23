package ma.mobility.booking.api;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.LinkedHashMap;
import java.util.Map;
import ma.mobility.booking.service.BookingService;
import ma.mobility.canonical.TransportMode;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Expose l'état des circuit breakers du booking-service (rail, air, car) sur /actuator/health. */
@Component("bookingCircuitBreakers")
public class BookingCircuitHealthIndicator implements HealthIndicator {

    private final BookingService bookingService;

    public BookingCircuitHealthIndicator(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public Health health() {
        boolean anyOpen = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<TransportMode, CircuitBreaker> entry : bookingService.circuitBreakers().entrySet()) {
            CircuitBreaker cb = entry.getValue();
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                anyOpen = true;
            }
            CircuitBreaker.Metrics m = cb.getMetrics();
            details.put(entry.getKey().name().toLowerCase(), Map.of(
                    "state", cb.getState().name(),
                    "failureRate", m.getFailureRate(),
                    "bufferedCalls", m.getNumberOfBufferedCalls(),
                    "failedCalls", m.getNumberOfFailedCalls(),
                    "notPermittedCalls", m.getNumberOfNotPermittedCalls()));
        }
        return (anyOpen ? Health.status("DEGRADED") : Health.up()).withDetails(details).build();
    }
}
