package ma.mobility.airbridge.api;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ma.mobility.airbridge.soap.AirSoapClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Expose l'état du circuit breaker SOAP sur /actuator/health (diagnostic et démo P3). */
@Component("airSoapCircuitBreaker")
public class AirCircuitHealthIndicator implements HealthIndicator {

    private final AirSoapClient client;

    public AirCircuitHealthIndicator(AirSoapClient client) {
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
