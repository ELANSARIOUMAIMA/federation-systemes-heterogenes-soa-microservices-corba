package ma.mobility.airbridge.soap;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import ma.mobility.airbridge.api.BridgeException;
import ma.mobility.airbridge.ws.CancelFlightRequest;
import ma.mobility.airbridge.ws.CancelFlightResponse;
import ma.mobility.airbridge.ws.ConfirmFlightRequest;
import ma.mobility.airbridge.ws.ConfirmFlightResponse;
import ma.mobility.airbridge.ws.Flight;
import ma.mobility.airbridge.ws.HoldFlightRequest;
import ma.mobility.airbridge.ws.HoldFlightResponse;
import ma.mobility.airbridge.ws.SearchFlightsRequest;
import ma.mobility.airbridge.ws.SearchFlightsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

/**
 * Seul endroit du projet qui "voit" du SOAP.
 *
 * <p>P3 : chaque opération SOAP est enveloppée dans un span CLIENT explicite
 * ({@code SOAP AirPartner/holdFlight}...), protégée par un circuit breaker (pannes techniques
 * uniquement) et, pour les seules opérations idempotentes (search, cancel), rejouée une fois.
 */
@Component
public class AirSoapClient {

    private static final Logger log = LoggerFactory.getLogger(AirSoapClient.class);

    private final WebServiceTemplate ws;
    private final CircuitBreaker breaker;
    private final Retry idempotentRetry;
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("ma.mobility.air-bridge");

    public AirSoapClient(WebServiceTemplate ws,
                         @Value("${air.circuit-breaker.window-size:6}") int windowSize,
                         @Value("${air.circuit-breaker.minimum-calls:3}") int minimumCalls,
                         @Value("${air.circuit-breaker.failure-rate:50}") float failureRate,
                         @Value("${air.circuit-breaker.open-seconds:15}") int openSeconds,
                         @Value("${air.retry.wait-ms:200}") long retryWaitMs) {
        this.ws = ws;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Un SOAP Fault "NotAvailable" (409) prouve que le partenaire répond : non compté.
                .recordException(e -> e instanceof BridgeException be && be.isUpstreamFailure())
                .build();
        this.breaker = CircuitBreaker.of("air-soap", config);
        this.breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker air-soap : {}", event.getStateTransition()));
        this.idempotentRetry = Retry.of("air-soap-idempotent", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(retryWaitMs))
                // Jamais de rejeu quand le circuit est ouvert ni sur un refus métier.
                .retryOnException(e -> e instanceof BridgeException be && be.isUpstreamFailure())
                .build());
    }

    public List<Flight> search(String origin, String destination, String date) {
        SearchFlightsRequest req = new SearchFlightsRequest();
        req.setOrigin(origin);
        req.setDestination(destination);
        req.setDate(date);
        return ((SearchFlightsResponse) call("searchFlights", req, false, true, null,
                "air.origin", origin, "air.destination", destination, "air.date", date)).getFlight();
    }

    public String hold(String flightId, int seats) {
        HoldFlightRequest req = new HoldFlightRequest();
        req.setFlightId(flightId);
        req.setSeats(seats);
        return ((HoldFlightResponse) call("holdFlight", req, true, false, "mobility.hold_id",
                "mobility.flight_id", flightId, "mobility.seats", String.valueOf(seats))).getHoldId();
    }

    public String confirm(String holdId) {
        ConfirmFlightRequest req = new ConfirmFlightRequest();
        req.setHoldId(holdId);
        return ((ConfirmFlightResponse) call("confirmFlight", req, true, false, "mobility.booking_ref",
                "mobility.hold_id", holdId)).getBookingRef();
    }

    /** Idempotent côté partenaire : accepte un holdId ou un bookingRef, jamais d'erreur si inconnu. */
    public String cancel(String reference) {
        CancelFlightRequest req = new CancelFlightRequest();
        req.setReference(reference);
        return ((CancelFlightResponse) call("cancelFlight", req, false, true, null,
                "mobility.reference", reference)).getStatus();
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    /**
     * @param faultIsBusiness true pour hold/confirm : un SOAP Fault signifie "indisponible" (409).
     *                        Pour search/cancel, un Fault est inattendu (503).
     * @param idempotent      true pour search/cancel : rejeu autorisé.
     */
    private Object call(String operation, Object request, boolean faultIsBusiness, boolean idempotent,
                        String resultAttribute, String... attributes) {
        Span span = tracer.spanBuilder("SOAP AirPartner/" + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "soap")
                .setAttribute("rpc.service", "AirPartner")
                .setAttribute("rpc.method", operation)
                .setAttribute("air.idempotent", idempotent)
                .startSpan();
        for (int i = 0; i + 1 < attributes.length; i += 2) {
            span.setAttribute(attributes[i], attributes[i + 1]);
        }
        try (Scope ignored = span.makeCurrent()) {
            Supplier<Object> guarded = CircuitBreaker.decorateSupplier(breaker, () -> send(request, faultIsBusiness));
            if (idempotent) {
                guarded = Retry.decorateSupplier(idempotentRetry, guarded);
            }
            Object response = guarded.get();
            if (resultAttribute != null) {
                String value = response instanceof HoldFlightResponse h ? h.getHoldId()
                        : response instanceof ConfirmFlightResponse c ? c.getBookingRef() : null;
                if (value != null) span.setAttribute(resultAttribute, value);
            }
            log.info("SOAP {} OK", operation);
            return response;
        } catch (CallNotPermittedException open) {
            span.setAttribute("resilience.circuit_breaker", "OPEN");
            span.setStatus(StatusCode.ERROR, "circuit breaker ouvert");
            log.warn("SOAP {} refusé : circuit breaker ouvert (partenaire non appelé)", operation);
            throw BridgeException.circuitOpen("Circuit ouvert vers le partenaire aérien : appel non tenté");
        } catch (BridgeException e) {
            span.setAttribute("mobility.error_code", e.getCode());
            span.setStatus(StatusCode.ERROR, e.getMessage());
            log.warn("SOAP {} en échec : {} {}", operation, e.getCode(), e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private Object send(Object request, boolean faultIsBusiness) {
        try {
            return ws.marshalSendAndReceive(request);
        } catch (SoapFaultClientException e) {
            String reason = e.getFaultStringOrReason();
            if (faultIsBusiness) {
                throw BridgeException.notAvailable(reason);
            }
            throw BridgeException.upstreamUnavailable("Erreur inattendue du partenaire aérien : " + reason);
        } catch (WebServiceClientException e) {
            throw BridgeException.upstreamUnavailable("Partenaire aérien injoignable : " + e.getMessage());
        }
    }
}
