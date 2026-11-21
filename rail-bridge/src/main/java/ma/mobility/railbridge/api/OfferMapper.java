package ma.mobility.railbridge.api;

import ma.mobility.canonical.Offer;
import ma.mobility.canonical.TransportMode;
import mobility.rail.Trip;

/** Traduction CORBA (struct Trip) -> modèle canonique (couche anti-corruption). */
final class OfferMapper {

    static final String PREFIX = "RAIL:";

    private OfferMapper() {}

    static Offer fromTrip(Trip t) {
        Offer o = new Offer();
        o.offerId = PREFIX + t.tripId;
        o.mode = TransportMode.TRAIN;
        o.origin = t.origin;
        o.destination = t.destination;
        o.departure = t.departure;
        o.arrival = t.arrival;
        o.priceMinor = t.priceMinor;
        o.currency = "MAD";
        o.seatsAvailable = t.seatsAvailable;
        return o;
    }
}