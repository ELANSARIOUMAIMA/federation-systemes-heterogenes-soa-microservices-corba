package ma.mobility.airbridge.api;

import ma.mobility.airbridge.ws.Flight;
import ma.mobility.canonical.Offer;
import ma.mobility.canonical.TransportMode;

/** Traduction SOAP -> modèle canonique (couche anti-corruption). */
final class OfferMapper {

    static final String PREFIX = "AIR:";

    private OfferMapper() {}

    static Offer fromFlight(Flight f) {
        Offer o = new Offer();
        o.offerId = PREFIX + f.getFlightId();
        o.mode = TransportMode.AIR;
        o.origin = f.getOrigin();
        o.destination = f.getDestination();
        o.departure = f.getDeparture();
        o.arrival = f.getArrival();
        o.priceMinor = f.getPriceMinor();
        o.currency = "MAD";
        o.seatsAvailable = f.getSeatsAvailable();
        return o;
    }
}