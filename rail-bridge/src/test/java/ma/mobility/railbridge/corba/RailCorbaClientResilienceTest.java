package ma.mobility.railbridge.corba;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.atomic.AtomicInteger;
import ma.mobility.canonical.ApiError;
import ma.mobility.railbridge.api.BridgeException;
import mobility.rail.NotAvailable;
import mobility.rail.RailBookingOperations;
import mobility.rail.Trip;
import org.junit.jupiter.api.Test;
import org.omg.CORBA.TRANSIENT;
import org.springframework.http.HttpStatus;

/**
 * P3 : tests unitaires de résilience du bridge CORBA.
 * Utilise un stub {@link RailBookingOperations} sans initialiser d'ORB JacORB,
 * afin de s'exécuter indifféremment sous Java 8 et sous Java 17 (réacteur Maven).
 */
class RailCorbaClientResilienceTest {

    @Test
    void businessNotAvailableDoesNotOpenCircuitBreaker() {
        AtomicInteger calls = new AtomicInteger();
        RailBookingOperations stub = new StubBase() {
            @Override
            public String hold(String tripId, int seats) throws NotAvailable {
                calls.incrementAndGet();
                throw new NotAvailable("Plus de places disponibles");
            }
        };
        RailCorbaClient client = new RailCorbaClient(() -> stub, 6, 3, 50, 60);

        for (int i = 0; i < 5; i++) {
            BridgeException error = assertThrows(BridgeException.class, () -> client.hold("RAIL-CAS-RAB-0730", 999));
            assertEquals(HttpStatus.CONFLICT, error.getStatus());
            assertEquals(ApiError.NOT_AVAILABLE, error.getCode());
        }
        assertEquals(CircuitBreaker.State.CLOSED, client.circuitState());
        assertEquals(5, calls.get());
    }

    @Test
    void idempotentSearchAndCancelRetryOnceWhileHoldNeverRetries() {
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger cancelCalls = new AtomicInteger();
        AtomicInteger holdCalls = new AtomicInteger();
        RailBookingOperations stub = new StubBase() {
            @Override
            public Trip[] search(String origin, String destination, String date) {
                if (searchCalls.incrementAndGet() == 1) {
                    throw new TRANSIENT("connexion IIOP coupée");
                }
                return new Trip[] { new Trip("RAIL-1", origin, destination, date + "T07:30:00",
                        date + "T08:25:00", 8000, 100) };
            }

            @Override
            public void cancel(String bookingRef) {
                if (cancelCalls.incrementAndGet() == 1) {
                    throw new TRANSIENT("timeout IIOP");
                }
            }

            @Override
            public String hold(String tripId, int seats) {
                holdCalls.incrementAndGet();
                throw new TRANSIENT("timeout IIOP");
            }
        };
        RailCorbaClient client = new RailCorbaClient(() -> stub, 10, 10, 50, 60);

        assertEquals(1, client.search("Casablanca", "Rabat", "2026-11-15").size());
        assertEquals(2, searchCalls.get());

        client.cancel("BK-RAIL-1");
        assertEquals(2, cancelCalls.get());

        BridgeException holdError = assertThrows(BridgeException.class, () -> client.hold("RAIL-1", 1));
        assertEquals(ApiError.UPSTREAM_UNAVAILABLE, holdError.getCode());
        assertEquals(1, holdCalls.get()); // jamais rejoué
    }

    @Test
    void repeatedCorbaOutagesOpenCircuitAndFailFast() {
        AtomicInteger calls = new AtomicInteger();
        RailBookingOperations stub = new StubBase() {
            @Override
            public String hold(String tripId, int seats) {
                calls.incrementAndGet();
                throw new TRANSIENT("rail arrêté");
            }
        };
        RailCorbaClient client = new RailCorbaClient(() -> stub, 6, 3, 50, 60);

        assertThrows(BridgeException.class, () -> client.hold("RAIL-1", 1));
        assertThrows(BridgeException.class, () -> client.hold("RAIL-1", 1));
        assertThrows(BridgeException.class, () -> client.hold("RAIL-1", 1));
        assertEquals(CircuitBreaker.State.OPEN, client.circuitState());

        BridgeException open = assertThrows(BridgeException.class, () -> client.confirm("HOLD-1"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, open.getStatus());
        assertEquals(ApiError.CIRCUIT_OPEN, open.getCode());
        assertEquals(3, calls.get()); // le 4e appel n'atteint pas l'opérateur
    }

    @Test
    void cancelIsIdempotentWhenReplayed() {
        AtomicInteger calls = new AtomicInteger();
        RailBookingOperations stub = new StubBase() {
            @Override
            public void cancel(String bookingRef) {
                calls.incrementAndGet();
            }
        };
        RailCorbaClient client = new RailCorbaClient(() -> stub, 6, 3, 50, 60);
        client.cancel("BK-RAIL-42");
        client.cancel("BK-RAIL-42");
        assertEquals(2, calls.get());
        assertEquals(CircuitBreaker.State.CLOSED, client.circuitState());
    }

    private abstract static class StubBase implements RailBookingOperations {
        @Override
        public Trip[] search(String origin, String destination, String date) {
            return new Trip[0];
        }

        @Override
        public String hold(String tripId, int seats) throws NotAvailable {
            return "HOLD-TEST";
        }

        @Override
        public String confirm(String holdId) throws NotAvailable {
            return "BK-TEST";
        }

        @Override
        public void cancel(String bookingRef) {
        }
    }
}
