package ma.mobility.gateway.api;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Expose l'état des circuit breakers de recherche (rail, air, car) sur /actuator/health. */
@Component("gatewayCircuitBreakers")
public class GatewayCircuitHealthIndicator implements HealthIndicator {

    private final GatewayController gateway;

    public GatewayCircuitHealthIndicator(GatewayController gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        boolean anyOpen = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<String, CircuitBreaker> entry : gateway.circuitBreakers().entrySet()) {
            CircuitBreaker cb = entry.getValue();
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                anyOpen = true;
            }
            CircuitBreaker.Metrics m = cb.getMetrics();
            details.put(entry.getKey(), Map.of(
                    "state", cb.getState().name(),
                    "failureRate", m.getFailureRate(),
                    "bufferedCalls", m.getNumberOfBufferedCalls(),
                    "failedCalls", m.getNumberOfFailedCalls(),
                    "notPermittedCalls", m.getNumberOfNotPermittedCalls()));
        }
        return (anyOpen ? Health.status("DEGRADED") : Health.up()).withDetails(details).build();
    }
}
