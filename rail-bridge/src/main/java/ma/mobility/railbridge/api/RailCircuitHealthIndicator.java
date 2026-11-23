package ma.mobility.railbridge.api;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ma.mobility.railbridge.corba.RailCorbaClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Expose l'état du circuit breaker CORBA sur /actuator/health (diagnostic et démo P3). */
@Component("railCorbaCircuitBreaker")
public class RailCircuitHealthIndicator implements HealthIndicator {

    private final RailCorbaClient client;

    public RailCircuitHealthIndicator(RailCorbaClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        CircuitBreaker cb = client.circuitBreaker();
        CircuitBreaker.State state = cb.getState();
        CircuitBreaker.Metrics m = cb.getMetrics();
        Health.Builder builder = state == CircuitBreaker.State.OPEN
                ? Health.status("DEGRADED")
                : Health.up();
        return builder
                .withDetail("state", state.name())
                .withDetail("failureRate", m.getFailureRate())
                .withDetail("bufferedCalls", m.getNumberOfBufferedCalls())
                .withDetail("failedCalls", m.getNumberOfFailedCalls())
                .withDetail("notPermittedCalls", m.getNumberOfNotPermittedCalls())
                .build();
    }
}
