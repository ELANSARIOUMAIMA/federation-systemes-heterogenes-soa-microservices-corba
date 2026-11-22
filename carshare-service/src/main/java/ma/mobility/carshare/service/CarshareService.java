package ma.mobility.carshare.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldRequest;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import ma.mobility.canonical.TransportMode;
import ma.mobility.carshare.api.CarshareException;
import ma.mobility.carshare.model.CarOfferEntity;
import ma.mobility.carshare.model.CarReservationEntity;
import ma.mobility.carshare.repository.CarOfferRepository;
import ma.mobility.carshare.repository.CarReservationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CarshareService {
    private static final String PREFIX = "CAR:";
    private static final long HOLD_MINUTES = 10;

    private final CarOfferRepository offers;
    private final CarReservationRepository reservations;

    public CarshareService(CarOfferRepository offers, CarReservationRepository reservations) {
        this.offers = offers;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public List<Offer> search(String origin, String destination, String dateText) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (DateTimeParseException error) {
            throw CarshareException.invalid("date doit être au format AAAA-MM-JJ");
        }
        LocalDateTime from = date.atStartOfDay();
        return offers.findByOriginIgnoreCaseAndDestinationIgnoreCaseAndDepartureBetween(
                        origin, destination, from, from.plusDays(1).minusNanos(1))
                .stream().map(this::toOffer).toList();
    }

    @Transactional
    public HoldResponse hold(HoldRequest request) {
        if (request == null || request.offerId == null || !request.offerId.startsWith(PREFIX)
                || request.offerId.length() == PREFIX.length()) {
            throw CarshareException.invalid("offerId doit commencer par CAR:");
        }
        if (request.seats <= 0) {
            throw CarshareException.invalid("seats doit être supérieur à 0");
        }
        expireHolds();
        String offerId = request.offerId.substring(PREFIX.length());
        CarOfferEntity offer = offers.findByIdForUpdate(offerId)
                .orElseThrow(() -> CarshareException.unavailable("Offre inconnue"));
        if (offer.seatsAvailable < request.seats) {
            throw CarshareException.unavailable("Capacité insuffisante");
        }
        offer.seatsAvailable -= request.seats;
        CarReservationEntity reservation = new CarReservationEntity();
        reservation.holdId = UUID.randomUUID().toString();
        reservation.offer = offer;
        reservation.seats = request.seats;
        reservation.state = CarReservationEntity.State.HELD;
        reservation.createdAt = Instant.now();
        reservation.expiresAt = reservation.createdAt.plusSeconds(HOLD_MINUTES * 60);
        reservations.save(reservation);
        return new HoldResponse(reservation.holdId);
    }

    @Transactional(noRollbackFor = CarshareException.class)
    public ConfirmResponse confirm(String holdId) {
        CarReservationEntity reservation = reservations.findByHoldIdForUpdate(holdId)
                .orElseThrow(() -> CarshareException.unavailable("Hold inconnu"));
        if (reservation.state == CarReservationEntity.State.HELD && !reservation.expiresAt.isAfter(Instant.now())) {
            release(reservation, CarReservationEntity.State.EXPIRED);
        }
        if (reservation.state != CarReservationEntity.State.HELD) {
            throw CarshareException.unavailable("Hold expiré ou non confirmable");
        }
        reservation.state = CarReservationEntity.State.CONFIRMED;
        reservation.bookingRef = "CARB-" + UUID.randomUUID();
        return new ConfirmResponse(reservation.bookingRef);
    }

    @Transactional
    public void cancel(String reference) {
        if (reference == null || reference.isBlank()) return;
        CarReservationEntity reservation = reservations.findByReferenceForUpdate(reference).orElse(null);
        if (reservation == null || reservation.state == CarReservationEntity.State.CANCELLED
                || reservation.state == CarReservationEntity.State.EXPIRED) return;
        release(reservation, CarReservationEntity.State.CANCELLED);
    }

    @Transactional
    @Scheduled(fixedDelayString = "${carshare.expiration-scan-ms:30000}")
    public void expireHolds() {
        Instant now = Instant.now();
        reservations.findByStateAndExpiresAtBefore(CarReservationEntity.State.HELD, now).stream()
                .map(reservation -> reservation.holdId)
                .forEach(holdId -> reservations.findByHoldIdForUpdate(holdId).ifPresent(reservation -> {
                    if (reservation.state == CarReservationEntity.State.HELD
                            && !reservation.expiresAt.isAfter(now)) {
                        release(reservation, CarReservationEntity.State.EXPIRED);
                    }
                }));
    }

    private void release(CarReservationEntity reservation, CarReservationEntity.State target) {
        if (reservation.state != CarReservationEntity.State.HELD
                && reservation.state != CarReservationEntity.State.CONFIRMED) return;
        int updated = offers.restoreSeats(reservation.offer.getOfferId(), reservation.seats);
        if (updated != 1) throw new IllegalStateException("Offre introuvable pendant la restitution de sièges");
        reservation.state = target;
        reservations.save(reservation);
    }

    private Offer toOffer(CarOfferEntity entity) {
        Offer offer = new Offer();
        offer.offerId = PREFIX + entity.offerId;
        offer.mode = TransportMode.CAR;
        offer.origin = entity.origin;
        offer.destination = entity.destination;
        offer.departure = entity.departure.toString();
        offer.arrival = entity.arrival.toString();
        offer.priceMinor = entity.priceMinor;
        offer.currency = entity.currency;
        offer.seatsAvailable = entity.seatsAvailable;
        return offer;
    }
}
