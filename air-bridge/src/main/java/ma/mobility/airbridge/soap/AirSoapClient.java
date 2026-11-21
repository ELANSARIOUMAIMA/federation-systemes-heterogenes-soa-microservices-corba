package ma.mobility.airbridge.soap;

import java.util.List;
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
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

/** Seul endroit du projet qui "voit" du SOAP. */
@Component
public class AirSoapClient {

    private final WebServiceTemplate ws;

    public AirSoapClient(WebServiceTemplate ws) {
        this.ws = ws;
    }

    public List<Flight> search(String origin, String destination, String date) {
        SearchFlightsRequest req = new SearchFlightsRequest();
        req.setOrigin(origin);
        req.setDestination(destination);
        req.setDate(date);
        return ((SearchFlightsResponse) call(req, false)).getFlight();
    }

    public String hold(String flightId, int seats) {
        HoldFlightRequest req = new HoldFlightRequest();
        req.setFlightId(flightId);
        req.setSeats(seats);
        return ((HoldFlightResponse) call(req, true)).getHoldId();
    }

    public String confirm(String holdId) {
        ConfirmFlightRequest req = new ConfirmFlightRequest();
        req.setHoldId(holdId);
        return ((ConfirmFlightResponse) call(req, true)).getBookingRef();
    }

    /** Idempotent côté partenaire : accepte un holdId ou un bookingRef, jamais d'erreur si inconnu. */
    public String cancel(String reference) {
        CancelFlightRequest req = new CancelFlightRequest();
        req.setReference(reference);
        return ((CancelFlightResponse) call(req, false)).getStatus();
    }

    /**
     * @param faultIsBusiness true pour hold/confirm : un SOAP Fault signifie "indisponible" (409).
     *                        Pour search/cancel, un Fault est inattendu (503).
     */
    private Object call(Object request, boolean faultIsBusiness) {
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