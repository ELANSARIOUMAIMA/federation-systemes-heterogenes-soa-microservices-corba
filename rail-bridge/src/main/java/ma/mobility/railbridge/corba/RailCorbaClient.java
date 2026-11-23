package ma.mobility.railbridge.corba;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import ma.mobility.railbridge.api.BridgeException;
import mobility.rail.NotAvailable;
import mobility.rail.RailBookingHelper;
import mobility.rail.RailBookingOperations;
import mobility.rail.Trip;
import org.omg.CORBA.ORB;
import org.omg.CORBA.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seul endroit du projet qui "voit" du CORBA.
 *
 * <p>P3 : chaque invocation IIOP est
 * <ul>
 *   <li>bornée par des timeouts JacORB courts (connexion + attente de réponse) ;</li>
 *   <li>protégée par un circuit breaker Resilience4j : seules les pannes techniques
 *       (SystemException, IOR absent) sont comptées, jamais le refus métier {@code NotAvailable} ;</li>
 *   <li>enveloppée dans un span OpenTelemetry manuel de type CLIENT, car l'agent Java
 *       n'instrumente pas IIOP : sans ce span, la trace s'arrêterait au contrôleur REST du bridge.</li>
 * </ul>
 */
@Component
public class RailCorbaClient implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RailCorbaClient.class);
    private static final String RPC_SERVICE = "mobility::rail::RailBooking";

    @FunctionalInterface
    private interface CorbaCall<T> {
        T run(RailBookingOperations stub) throws NotAvailable;
    }

    private final Path iorPath;
    private final ORB orb;
    private final Supplier<RailBookingOperations> stubOverride;
    private final CircuitBreaker breaker;
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("ma.mobility.rail-bridge");
    private RailBookingOperations stub;

    @Autowired
    public RailCorbaClient(@Value("${rail.ior.path}") String iorPath,
                           @Value("${rail.corba.connect-timeout-ms:1000}") int connectTimeoutMs,
                           @Value("${rail.corba.reply-timeout-ms:2500}") int replyTimeoutMs,
                           @Value("${rail.corba.orb-retries:0}") int orbRetries,
                           @Value("${rail.circuit-breaker.window-size:6}") int windowSize,
                           @Value("${rail.circuit-breaker.minimum-calls:3}") int minimumCalls,
                           @Value("${rail.circuit-breaker.failure-rate:50}") float failureRate,
                           @Value("${rail.circuit-breaker.open-seconds:15}") int openSeconds) {
        this.iorPath = Paths.get(iorPath);
        this.stubOverride = null;
        Properties props = new Properties();
        props.setProperty("org.omg.CORBA.ORBClass", "org.jacorb.orb.ORB");
        props.setProperty("org.omg.CORBA.ORBSingletonClass", "org.jacorb.orb.ORBSingleton");
        props.setProperty("jacorb.connection.client.connect_timeout", String.valueOf(connectTimeoutMs));
        props.setProperty("jacorb.connection.client.pending_reply_timeout", String.valueOf(replyTimeoutMs));
        // P3 : désactive la boucle interne JacORB (par défaut 5 retries x 500 ms = ~14 s/appel !).
        // Les reprises restent exclusivement pilotées par le bridge (search/cancel uniquement).
        props.setProperty("jacorb.retries", String.valueOf(orbRetries));
        props.setProperty("jacorb.retry_interval", "100");
        this.orb = ORB.init(new String[0], props);
        this.breaker = buildBreaker(windowSize, minimumCalls, failureRate, openSeconds);
        log.info("Client CORBA : connect_timeout={}ms, pending_reply_timeout={}ms, jacorb.retries={}, circuit breaker {} appels / {}% / {}s",
                connectTimeoutMs, replyTimeoutMs, orbRetries, windowSize, failureRate, openSeconds);
    }

    /** Constructeur de test : injecte un stub CORBA sans initialiser l'ORB JacORB. */
    RailCorbaClient(Supplier<RailBookingOperations> stubOverride, int windowSize, int minimumCalls,
                    float failureRate, int openSeconds) {
        this.iorPath = null;
        this.orb = null;
        this.stubOverride = stubOverride;
        this.breaker = buildBreaker(windowSize, minimumCalls, failureRate, openSeconds);
    }

    private static CircuitBreaker buildBreaker(int windowSize, int minimumCalls, float failureRate, int openSeconds) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // NotAvailable (409) est une réponse métier valide : l'opérateur fonctionne.
                .recordException(e -> e instanceof BridgeException && ((BridgeException) e).isUpstreamFailure())
                .build();
        CircuitBreaker cb = CircuitBreaker.of("rail-corba", config);
        cb.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker rail-corba : {}", event.getStateTransition()));
        return cb;
    }

    public List<Trip> search(String origin, String destination, String date) {
        Trip[] trips = this.<Trip[]>call("search", true, null,
                new String[] {"rail.origin", origin, "rail.destination", destination, "rail.date", date},
                s -> s.search(origin, destination, date));
        return Arrays.asList(trips);
    }

    /** Non rejoué automatiquement (non idempotent). */
    public String hold(String tripId, int seats) {
        return this.<String>call("hold", false, "mobility.hold_id",
                new String[] {"mobility.trip_id", tripId, "mobility.seats", String.valueOf(seats)},
                s -> s.hold(tripId, seats));
    }

    /** Non rejoué automatiquement (non idempotent). */
    public String confirm(String holdId) {
        return this.<String>call("confirm", false, "mobility.booking_ref",
                new String[] {"mobility.hold_id", holdId},
                s -> s.confirm(holdId));
    }

    /** Idempotent côté opérateur : accepte un holdId ou un bookingRef, jamais d'erreur si inconnu. */
    public void cancel(String reference) {
        this.<Object>call("cancel", true, null, new String[] {"mobility.reference", reference}, s -> {
            s.cancel(reference);
            return null;
        });
    }

    /** Visible pour le diagnostic (Actuator, logs, tests). */
    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    private <T> T call(String operation, boolean idempotent, String resultAttribute, String[] attributes,
                       CorbaCall<T> op) {
        Span span = tracer.spanBuilder("CORBA RailBooking/" + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "corba")
                .setAttribute("rpc.service", RPC_SERVICE)
                .setAttribute("rpc.method", operation)
                .setAttribute("network.protocol.name", "iiop")
                .setAttribute("rail.idempotent", idempotent)
                .startSpan();
        for (int i = 0; i + 1 < attributes.length; i += 2) {
            span.setAttribute(attributes[i], attributes[i + 1]);
        }
        try (Scope ignored = span.makeCurrent()) {
            T result = breaker.executeSupplier(() -> invoke(operation, idempotent, op, span));
            if (resultAttribute != null && result != null) {
                span.setAttribute(resultAttribute, result.toString());
            }
            log.info("CORBA {} OK{}", operation, result instanceof String ? " -> " + result : "");
            return result;
        } catch (CallNotPermittedException open) {
            span.setAttribute("resilience.circuit_breaker", "OPEN");
            span.setStatus(StatusCode.ERROR, "circuit breaker ouvert");
            log.warn("CORBA {} refusé : circuit breaker ouvert (opérateur non appelé)", operation);
            throw BridgeException.circuitOpen("Circuit ouvert vers l'opérateur ferroviaire : appel non tenté");
        } catch (BridgeException e) {
            span.setAttribute("mobility.error_code", e.getCode());
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private <T> T invoke(String operation, boolean idempotent, CorbaCall<T> op, Span span) {
        int attempts = idempotent ? 2 : 1;
        SystemException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return op.run(stub());
            } catch (NotAvailable e) {
                span.addEvent("mobility.rail.NotAvailable");
                throw BridgeException.notAvailable(e.reason);
            } catch (SystemException e) {
                log.warn("Échec CORBA {} ({}), tentative {}/{} : {}", operation, e.getClass().getSimpleName(),
                        i + 1, attempts, e.getMessage());
                span.recordException(e);
                reset();
                last = e;
            }
        }
        throw BridgeException.upstreamUnavailable(
                "Opérateur ferroviaire injoignable : " + (last == null ? "?" : last.getClass().getSimpleName()));
    }

    /** Lit l'IOR à la demande : le bridge n'a pas besoin que rail soit prêt au démarrage. */
    private synchronized RailBookingOperations stub() {
        if (stubOverride != null) {
            return stubOverride.get();
        }
        if (stub == null) {
            String ior;
            try {
                ior = new String(Files.readAllBytes(iorPath), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw BridgeException.upstreamUnavailable(
                        "IOR introuvable (" + iorPath + ") : l'opérateur ferroviaire est-il démarré ?");
            }
            if (ior.isEmpty()) {
                throw BridgeException.upstreamUnavailable("IOR vide (" + iorPath + ")");
            }
            stub = RailBookingHelper.narrow(orb.string_to_object(ior));
            log.info("Référence CORBA RailBooking obtenue depuis {}", iorPath);
        }
        return stub;
    }

    /** Oublie la référence : la prochaine requête relira l'IOR (rail a pu redémarrer, son IP changer). */
    private synchronized void reset() {
        stub = null;
    }

    @Override
    public void destroy() {
        if (orb != null) {
            orb.shutdown(true);
        }
    }
}
