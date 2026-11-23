package ma.mobility.airbridge.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import ma.mobility.airbridge.api.BridgeException;
import ma.mobility.airbridge.ws.HoldFlightResponse;
import ma.mobility.airbridge.ws.SearchFlightsResponse;
import ma.mobility.canonical.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

/** P3 : sémantique de résilience du bridge SOAP (sans réseau, WebServiceTemplate simulé). */
class AirSoapClientResilienceTest {

    private WebServiceTemplate ws;
    private AirSoapClient client;

    @BeforeEach
    void setUp() {
        ws = mock(WebServiceTemplate.class);
        // fenêtre 6, minimum 3 appels, 50 %, ouverture 60 s, retry sans attente
        client = new AirSoapClient(ws, 6, 3, 50, 60, 0);
    }

    @Test
    void businessFaultIsMappedTo409AndDoesNotOpenTheCircuit() {
        SoapFaultClientException fault = mock(SoapFaultClientException.class);
        when(fault.getFaultStringOrReason()).thenReturn("NotAvailable: 3 places restantes");
        when(ws.marshalSendAndReceive(any())).thenThrow(fault);

        for (int i = 0; i < 5; i++) {
            BridgeException error = assertThrows(BridgeException.class, () -> client.hold("AIR-CMN-MAD-1800", 4));
            assertEquals(HttpStatus.CONFLICT, error.getStatus());
            assertEquals(ApiError.NOT_AVAILABLE, error.getCode());
        }
        assertEquals(CircuitBreaker.State.CLOSED, client.circuitState());
        verify(ws, times(5)).marshalSendAndReceive(any());
    }

    @Test
    void idempotentSearchIsRetriedOnceButHoldIsNeverReplayed() {
        when(ws.marshalSendAndReceive(any()))
                .thenThrow(new WebServiceIOException("connexion refusée"))
                .thenReturn(new SearchFlightsResponse());
        client.search("Rabat", "Paris", "2026-11-15");
        verify(ws, times(2)).marshalSendAndReceive(any());

        when(ws.marshalSendAndReceive(any())).thenThrow(new WebServiceIOException("timeout"));
        BridgeException error = assertThrows(BridgeException.class, () -> client.hold("AIR-RBA-ORY-1200", 1));
        assertEquals(ApiError.UPSTREAM_UNAVAILABLE, error.getCode());
        verify(ws, times(3)).marshalSendAndReceive(any()); // 2 (search) + 1 (hold, non rejoué)
    }

    @Test
    void repeatedOutageOpensTheCircuitAndThenFailsFastWithoutCallingThePartner() {
        when(ws.marshalSendAndReceive(any())).thenThrow(new WebServiceIOException("partenaire arrêté"));
        assertThrows(BridgeException.class, () -> client.hold("AIR-RBA-ORY-1200", 1));
        assertThrows(BridgeException.class, () -> client.hold("AIR-RBA-ORY-1200", 1));
        assertThrows(BridgeException.class, () -> client.hold("AIR-RBA-ORY-1200", 1));
        assertEquals(CircuitBreaker.State.OPEN, client.circuitState());

        BridgeException open = assertThrows(BridgeException.class, () -> client.confirm("HOLD-AIR-x"));
        assertEquals(ApiError.CIRCUIT_OPEN, open.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, open.getStatus());
        verify(ws, times(3)).marshalSendAndReceive(any()); // le 4e appel n'a pas atteint le partenaire
    }

    @Test
    void successfulHoldReturnsTheHoldId() {
        HoldFlightResponse response = new HoldFlightResponse();
        response.setHoldId("HOLD-AIR-123");
        when(ws.marshalSendAndReceive(any())).thenReturn(response);
        assertEquals("HOLD-AIR-123", client.hold("AIR-RBA-ORY-1200", 1));
    }
}
