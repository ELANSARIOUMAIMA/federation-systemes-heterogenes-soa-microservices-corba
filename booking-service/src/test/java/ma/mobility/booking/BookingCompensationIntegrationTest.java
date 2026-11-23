package ma.mobility.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ma.mobility.booking.api.BookingException;
import ma.mobility.booking.api.BookingRequest;
import ma.mobility.booking.api.BookingView;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.model.SagaStepState;
import ma.mobility.booking.service.BookingService;
import ma.mobility.canonical.TransportMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P3 : test d'intégration HTTP réel (RestTemplate + Resilience4j + JPA/H2 + Saga)
 * face à un serveur HTTP embarqué simulant rail-bridge, air-bridge, carshare et pricing.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-it;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.username=test-user",
        "spring.rabbitmq.password=test-password",
        "booking.outbox.scan-ms=3600000",
        "booking.compensation.scan-ms=3600000",
        "booking.circuit-breaker.window-size=4",
        "booking.circuit-breaker.minimum-calls=3",
        "booking.circuit-breaker.failure-rate=50",
        "booking.circuit-breaker.open-seconds=60",
        "booking.retry.wait-ms=0"
})
class BookingCompensationIntegrationTest {

    private static HttpServer server;
    private static int port;

    private static final List<String> deletedRefs = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean failAirConfirm = new AtomicBoolean(false);
    private static final AtomicBoolean failFirstRailCancel = new AtomicBoolean(false);
    private static final AtomicBoolean airHoldCircuitOpen = new AtomicBoolean(false);
    private static final AtomicBoolean railOffers503 = new AtomicBoolean(false);
    private static final AtomicInteger railOffersCalls = new AtomicInteger(0);
    private static final AtomicInteger railDeleteCalls = new AtomicInteger(0);

    @MockBean RabbitTemplate rabbit;
    @Autowired BookingService bookingService;

    @BeforeAll
    static void startMockBridges() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // --- Rail bridge simulé ---
        server.createContext("/rail/v1/offers", ex -> {
            railOffersCalls.incrementAndGet();
            if (railOffers503.get()) {
                respond(ex, 503, "{\"code\":\"UPSTREAM_UNAVAILABLE\",\"reason\":\"rail arrêté\"}");
                return;
            }
            respond(ex, 200, "[{\"offerId\":\"RAIL:RAIL-CAS-RAB-0730\",\"mode\":\"TRAIN\",\"priceMinor\":8000,\"currency\":\"MAD\",\"seatsAvailable\":120}]");
        });
        server.createContext("/rail/v1/holds", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/confirm")) {
                respond(ex, 200, "{\"bookingRef\":\"BK-RAIL-" + UUID.randomUUID() + "\"}");
            } else {
                respond(ex, 200, "{\"holdId\":\"HOLD-RAIL-" + UUID.randomUUID() + "\"}");
            }
        });
        server.createContext("/rail/v1/references/", ex -> {
            int attempt = railDeleteCalls.incrementAndGet();
            if (failFirstRailCancel.get() && attempt == 1) {
                respond(ex, 503, "{\"code\":\"UPSTREAM_UNAVAILABLE\",\"reason\":\"panne transitoire\"}");
                return;
            }
            deletedRefs.add("RAIL:" + ex.getRequestURI().getPath().substring("/rail/v1/references/".length()));
            respond(ex, 200, "");
        });

        // --- Air bridge simulé ---
        server.createContext("/air/v1/offers", ex ->
                respond(ex, 200, "[{\"offerId\":\"AIR:AIR-RBA-ORY-1200\",\"mode\":\"AIR\",\"priceMinor\":180000,\"currency\":\"MAD\",\"seatsAvailable\":40}]"));
        server.createContext("/air/v1/holds", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/confirm")) {
                if (failAirConfirm.get()) {
                    respond(ex, 409, "{\"code\":\"NOT_AVAILABLE\",\"reason\":\"hold expiré\"}");
                } else {
                    respond(ex, 200, "{\"bookingRef\":\"BK-AIR-" + UUID.randomUUID() + "\"}");
                }
            } else if (airHoldCircuitOpen.get()) {
                respond(ex, 503, "{\"code\":\"CIRCUIT_OPEN\",\"reason\":\"Circuit ouvert vers le partenaire aérien\"}");
            } else {
                respond(ex, 200, "{\"holdId\":\"HOLD-AIR-" + UUID.randomUUID() + "\"}");
            }
        });
        server.createContext("/air/v1/references/", ex -> {
            deletedRefs.add("AIR:" + ex.getRequestURI().getPath().substring("/air/v1/references/".length()));
            respond(ex, 200, "");
        });

        // --- Carshare service simulé ---
        server.createContext("/car/v1/offers", ex ->
                respond(ex, 200, "[{\"offerId\":\"CAR:CAR-CAS-RAB-1000\",\"mode\":\"CAR\",\"priceMinor\":4500,\"currency\":\"MAD\",\"seatsAvailable\":4}]"));
        server.createContext("/car/v1/holds", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/confirm")) {
                respond(ex, 200, "{\"bookingRef\":\"CARB-" + UUID.randomUUID() + "\"}");
            } else {
                respond(ex, 200, "{\"holdId\":\"HOLD-CAR-" + UUID.randomUUID() + "\"}");
            }
        });
        server.createContext("/car/v1/references/", ex -> {
            deletedRefs.add("CAR:" + ex.getRequestURI().getPath().substring("/car/v1/references/".length()));
            respond(ex, 200, "");
        });

        // --- Pricing service simulé ---
        server.createContext("/pricing/v1/pricing/quote", ex ->
                respond(ex, 200, "{\"subtotalMinor\":192500,\"feesMinor\":600,\"discountMinor\":9625,\"totalMinor\":183475,\"currency\":\"MAD\"}"));

        server.start();
    }

    @AfterAll
    static void stopMockBridges() {
        if (server != null) server.stop(0);
    }

    @DynamicPropertySource
    static void registerUrls(DynamicPropertyRegistry registry) {
        registry.add("booking.rail-url", () -> "http://127.0.0.1:" + port + "/rail");
        registry.add("booking.air-url", () -> "http://127.0.0.1:" + port + "/air");
        registry.add("booking.car-url", () -> "http://127.0.0.1:" + port + "/car");
        registry.add("booking.pricing-url", () -> "http://127.0.0.1:" + port + "/pricing");
    }

    @BeforeEach
    void resetState() {
        deletedRefs.clear();
        failAirConfirm.set(false);
        failFirstRailCancel.set(false);
        airHoldCircuitOpen.set(false);
        railOffers503.set(false);
        railOffersCalls.set(0);
        railDeleteCalls.set(0);
        bookingService.circuitBreakers().values().forEach(CircuitBreaker::reset);
    }

    @Test
    void confirmFailureCompensatesAllThreeModesInReverseOrderAndRetriesTransientDelete() {
        failAirConfirm.set(true);
        failFirstRailCancel.set(true); // la 1re tentative DELETE rail échoue en 503, le retry Resilience4j réussit

        BookingView result = bookingService.create("it-confirm-fail-" + UUID.randomUUID(), threeModeRequest());

        assertEquals(BookingState.CANCELLED, result.state);
        assertEquals(3, result.steps.size());
        assertTrue(result.steps.stream().allMatch(s -> s.state == SagaStepState.COMPENSATED));
        // Ordre inverse : AIR (via holdId), CAR (via bookingRef), RAIL (via bookingRef)
        assertEquals(3, deletedRefs.size());
        assertTrue(deletedRefs.get(0).startsWith("AIR:HOLD-AIR-"));
        assertTrue(deletedRefs.get(1).startsWith("CAR:CARB-"));
        assertTrue(deletedRefs.get(2).startsWith("RAIL:BK-RAIL-"));
        assertEquals(2, railDeleteCalls.get()); // 1 échec 503 + 1 retry réussi
    }

    @Test
    void bridgeCircuitOpenOnHoldCompensatesCleanlyWithoutUnknownOutcome() {
        airHoldCircuitOpen.set(true);

        BookingView result = bookingService.create("it-cb-open-" + UUID.randomUUID(), threeModeRequest());

        assertEquals(BookingState.CANCELLED, result.state);
        assertEquals(SagaStepState.COMPENSATED, result.steps.get(0).state); // TRAIN compensé
        assertEquals(SagaStepState.COMPENSATED, result.steps.get(1).state); // CAR compensé
        assertEquals(SagaStepState.HOLD_FAILED, result.steps.get(2).state); // AIR non tenté -> pas d'UNKNOWN_OUTCOME
        assertTrue(result.steps.get(2).lastError.contains("circuit ouvert"));
    }

    @Test
    void repeatedRail503OpensBookingCircuitBreakerAndFailsFast() {
        railOffers503.set(true);

        // Chaque appel create -> resolvePrice tente 2 fois (retry idempotent), 2 créations = 4 échecs >= minimum-calls(3)
        assertThrows(BookingException.class, () -> bookingService.create("it-cb-1-" + UUID.randomUUID(), threeModeRequest()));
        assertThrows(BookingException.class, () -> bookingService.create("it-cb-2-" + UUID.randomUUID(), threeModeRequest()));
        assertEquals(CircuitBreaker.State.OPEN, bookingService.circuitBreakers().get(TransportMode.TRAIN).getState());

        int callsBefore = railOffersCalls.get();
        BookingException fastFail = assertThrows(BookingException.class,
                () -> bookingService.create("it-cb-3-" + UUID.randomUUID(), threeModeRequest()));
        assertTrue(fastFail.getMessage().contains("Circuit ouvert"));
        assertEquals(callsBefore, railOffersCalls.get()); // aucun nouvel appel HTTP quand le circuit est ouvert
    }

    private static BookingRequest threeModeRequest() {
        BookingRequest req = new BookingRequest();
        BookingRequest.Segment train = new BookingRequest.Segment();
        train.mode = TransportMode.TRAIN;
        train.offerId = "RAIL:RAIL-CAS-RAB-0730";
        train.origin = "Casablanca";
        train.destination = "Rabat";
        train.date = "2026-11-15";
        train.seats = 1;

        BookingRequest.Segment car = new BookingRequest.Segment();
        car.mode = TransportMode.CAR;
        car.offerId = "CAR:CAR-CAS-RAB-1000";
        car.origin = "Casablanca";
        car.destination = "Rabat";
        car.date = "2026-11-15";
        car.seats = 1;

        BookingRequest.Segment air = new BookingRequest.Segment();
        air.mode = TransportMode.AIR;
        air.offerId = "AIR:AIR-RBA-ORY-1200";
        air.origin = "Rabat";
        air.destination = "Paris";
        air.date = "2026-11-15";
        air.seats = 1;

        req.segments = List.of(train, car, air);
        return req;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
