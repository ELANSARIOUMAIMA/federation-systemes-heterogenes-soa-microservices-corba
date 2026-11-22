package ma.mobility.booking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.mobility.booking.api.BookingException;
import ma.mobility.booking.api.BookingRequest;
import ma.mobility.booking.api.BookingView;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.model.SagaStepEntity;
import ma.mobility.booking.model.SagaStepState;
import ma.mobility.booking.model.TripBookingEntity;
import ma.mobility.booking.repository.BookingRepository;
import ma.mobility.booking.repository.SagaStepRepository;
import ma.mobility.canonical.ApiError;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import ma.mobility.canonical.TransportMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final BookingRepository bookings;
    private final SagaStepRepository steps;
    private final BookingPersistence persistence;
    private final RestTemplate http;
    @Value("${booking.rail-url}") private String railUrl;
    @Value("${booking.air-url}") private String airUrl;
    @Value("${booking.car-url}") private String carUrl;
    @Value("${booking.pricing-url}") private String pricingUrl;
    @Value("${booking.compensation.max-attempts:20}") private int maxCompensationAttempts;

    public BookingService(BookingRepository bookings, SagaStepRepository steps, BookingPersistence persistence,
                          RestTemplate http) {
        this.bookings = bookings;
        this.steps = steps;
        this.persistence = persistence;
        this.http = http;
    }

    public BookingView create(String key, BookingRequest request) {
        validate(key, request);
        String hash = payloadHash(request);
        TripBookingEntity existing = bookings.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            if (!existing.payloadHash.equals(hash)) throw BookingException.conflict("Idempotency-Key déjà utilisé avec un payload différent");
            return view(existing);
        }

        Quote quote;
        try {
            quote = http.postForObject(pricingUrl + "/v1/pricing/quote", pricingRequest(request), Quote.class);
        } catch (ResourceAccessException error) {
            throw BookingException.unavailable("pricing-service indisponible");
        } catch (HttpStatusCodeException error) {
            throw BookingException.unavailable("pricing-service a répondu HTTP " + error.getStatusCode().value());
        }
        if (quote == null) throw BookingException.unavailable("pricing-service a retourné un devis vide");

        TripBookingEntity booking = new TripBookingEntity();
        booking.id = UUID.randomUUID().toString();
        booking.idempotencyKey = key;
        booking.payloadHash = hash;
        booking.state = BookingState.PENDING;
        booking.createdAt = Instant.now();
        booking.subtotalMinor = quote.subtotalMinor;
        booking.feesMinor = quote.feesMinor;
        booking.discountMinor = quote.discountMinor;
        booking.totalMinor = quote.totalMinor;
        booking.currency = quote.currency;
        List<SagaStepEntity> sagaSteps;
        try {
            sagaSteps = persistence.create(booking, request.segments);
        } catch (DataIntegrityViolationException concurrent) {
            TripBookingEntity winner = bookings.findByIdempotencyKey(key).orElseThrow(() -> concurrent);
            if (!winner.payloadHash.equals(hash)) throw BookingException.conflict("Idempotency-Key déjà utilisé avec un payload différent");
            return view(winner);
        }

        booking = bookings.findById(booking.id).orElseThrow();
        execute(booking, sagaSteps);
        return view(bookings.findById(booking.id).orElseThrow());
    }

    public BookingView get(String id) {
        return bookings.findById(id).map(this::view)
                .orElseThrow(() -> new BookingException(org.springframework.http.HttpStatus.NOT_FOUND,
                        ApiError.NOT_AVAILABLE, "Réservation introuvable"));
    }

    private void execute(TripBookingEntity booking, List<SagaStepEntity> sagaSteps) {
        for (SagaStepEntity step : sagaSteps) {
            step.state = SagaStepState.HOLD_PENDING;
            steps.save(step);
            try {
                HoldResponse response = http.postForObject(serviceUrl(step.mode) + "/v1/holds",
                        new HoldRequestBody(step.offerId, step.seats), HoldResponse.class);
                if (response == null || response.holdId == null || response.holdId.isBlank()) {
                    step.state = SagaStepState.UNKNOWN_OUTCOME;
                    step.lastError = "Réponse hold vide; résultat distant incertain";
                    steps.save(step);
                    compensate(booking, sagaSteps, "Résultat hold incertain");
                    return;
                }
                step.holdId = response.holdId;
                step.heldAt = Instant.now();
                step.state = SagaStepState.HELD;
                steps.save(step);
            } catch (ResourceAccessException error) {
                step.state = SagaStepState.UNKNOWN_OUTCOME;
                step.lastError = "Timeout réseau pendant hold; référence inconnue";
                steps.save(step);
                compensate(booking, sagaSteps, step.lastError);
                return;
            } catch (HttpStatusCodeException error) {
                step.state = SagaStepState.HOLD_FAILED;
                step.lastError = "hold HTTP " + error.getStatusCode().value();
                steps.save(step);
                compensate(booking, sagaSteps, step.lastError);
                return;
            }
        }
        persistence.transition(booking, BookingState.HELD, null);
        if (sagaSteps.stream().anyMatch(step -> step.heldAt != null
                && step.heldAt.plusSeconds(9 * 60L).isBefore(Instant.now()))) {
            compensate(booking, sagaSteps, "Fenêtre de confirmation proche de l'expiration");
            return;
        }
        for (SagaStepEntity step : sagaSteps) {
            step.state = SagaStepState.CONFIRM_PENDING;
            steps.save(step);
            try {
                ConfirmResponse response = http.postForObject(serviceUrl(step.mode) + "/v1/holds/"
                        + step.holdId + "/confirm", null, ConfirmResponse.class);
                if (response == null || response.bookingRef == null || response.bookingRef.isBlank()) {
                    step.lastError = "Réponse confirm vide; tentative de compensation via holdId";
                    step.state = SagaStepState.COMPENSATION_PENDING;
                    steps.save(step);
                    compensate(booking, sagaSteps, step.lastError);
                    return;
                }
                step.bookingRef = response.bookingRef;
                step.state = SagaStepState.CONFIRMED;
                steps.save(step);
            } catch (ResourceAccessException error) {
                step.lastError = "Timeout confirm; compensation via holdId";
                step.state = SagaStepState.COMPENSATION_PENDING;
                steps.save(step);
                compensate(booking, sagaSteps, step.lastError);
                return;
            } catch (HttpStatusCodeException error) {
                step.lastError = "confirm HTTP " + error.getStatusCode().value();
                step.state = SagaStepState.COMPENSATION_PENDING;
                steps.save(step);
                compensate(booking, sagaSteps, step.lastError);
                return;
            }
        }
        persistence.transition(booking, BookingState.CONFIRMED, null);
    }

    private void compensate(TripBookingEntity booking, List<SagaStepEntity> sagaSteps, String reason) {
        persistence.transition(booking, BookingState.COMPENSATING, reason);
        boolean pending = false;
        for (int i = sagaSteps.size() - 1; i >= 0; i--) {
            SagaStepEntity step = sagaSteps.get(i);
            if (step.state == SagaStepState.COMPENSATED || step.state == SagaStepState.PENDING
                    || step.state == SagaStepState.HOLD_FAILED) continue;
            String reference = step.bookingRef != null ? step.bookingRef : step.holdId;
            if (reference == null) {
                step.state = SagaStepState.UNKNOWN_OUTCOME;
                step.lastError = "Hold distant potentiellement créé sans référence; intervention requise";
                steps.save(step);
                pending = true;
                continue;
            }
            if (step.compensationAttempts >= maxCompensationAttempts) {
                step.state = SagaStepState.COMPENSATION_PENDING;
                step.lastError = "Nombre maximal de tentatives de compensation atteint";
                steps.save(step);
                pending = true;
                continue;
            }
            try {
                http.exchange(serviceUrl(step.mode) + "/v1/references/" + reference,
                        HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
                step.state = SagaStepState.COMPENSATED;
                step.lastError = null;
                steps.save(step);
            } catch (ResourceAccessException | HttpStatusCodeException error) {
                step.compensationAttempts++;
                step.state = SagaStepState.COMPENSATION_PENDING;
                step.lastError = "Compensation échouée: " + error.getMessage();
                steps.save(step);
                pending = true;
                log.warn("Compensation en attente booking={} step={} attempt={}",
                        booking.id, step.stepOrder, step.compensationAttempts);
            }
        }
        persistence.transition(booking, pending ? BookingState.COMPENSATION_PENDING : BookingState.CANCELLED, reason);
    }

    @Scheduled(fixedDelayString = "${booking.outbox.scan-ms:2000}")
    public void retryCompensations() {
        bookings.findByState(BookingState.COMPENSATION_PENDING).forEach(booking -> {
            List<SagaStepEntity> sagaSteps = steps.findByBookingIdOrderByStepOrder(booking.id);
            if (sagaSteps.stream().anyMatch(step -> step.state == SagaStepState.UNKNOWN_OUTCOME && step.holdId == null)) {
                return;
            }
            compensate(booking, sagaSteps, booking.failureReason);
        });
    }

    @Scheduled(fixedDelayString = "${booking.recovery.scan-ms:30000}")
    public void recoverStaleSagas() {
        Instant cutoff = Instant.now().minusSeconds(5 * 60L);
        List<BookingState> recoverable = List.of(BookingState.PENDING, BookingState.HELD,
                BookingState.COMPENSATING);
        bookings.findByStateInAndCreatedAtBefore(recoverable, cutoff).forEach(booking -> {
            List<SagaStepEntity> sagaSteps = steps.findByBookingIdOrderByStepOrder(booking.id);
            for (SagaStepEntity step : sagaSteps) {
                if (step.state == SagaStepState.HOLD_PENDING && step.holdId == null) {
                    step.state = SagaStepState.UNKNOWN_OUTCOME;
                    step.lastError = "Récupération après interruption: résultat hold incertain";
                    steps.save(step);
                }
            }
            compensate(booking, sagaSteps, "Saga récupérée après interruption ou délai dépassé");
        });
    }

    private String serviceUrl(TransportMode mode) {
        return switch (mode) {
            case TRAIN -> railUrl;
            case AIR -> airUrl;
            case CAR -> carUrl;
        };
    }

    private void validate(String key, BookingRequest request) {
        if (key == null || key.isBlank()) throw BookingException.invalid("L'en-tête Idempotency-Key est obligatoire");
        if (key.length() > 200) throw BookingException.invalid("Idempotency-Key dépasse 200 caractères");
        if (request == null || request.segments == null || request.segments.isEmpty()) {
            throw BookingException.invalid("segments doit contenir au moins un segment");
        }
        if (request.segments.size() > 10) {
            throw BookingException.invalid("Une réservation ne peut pas dépasser 10 segments");
        }
        for (BookingRequest.Segment segment : request.segments) {
            if (segment == null || segment.mode == null || segment.offerId == null || segment.seats <= 0
                    || segment.origin == null || segment.origin.isBlank() || segment.destination == null
                    || segment.destination.isBlank() || segment.date == null || segment.date.isBlank()) {
                throw BookingException.invalid("Chaque segment doit préciser mode, offerId, origin, destination, date et seats > 0");
            }
            String expected = switch (segment.mode) {
                case TRAIN -> "RAIL:";
                case AIR -> "AIR:";
                case CAR -> "CAR:";
            };
            if (!segment.offerId.startsWith(expected) || segment.offerId.length() == expected.length()) {
                throw BookingException.invalid("offerId doit correspondre au préfixe du mode " + segment.mode);
            }
            try {
                LocalDate.parse(segment.date);
            } catch (DateTimeParseException error) {
                throw BookingException.invalid("date doit être au format AAAA-MM-JJ");
            }
        }
    }

    private String payloadHash(BookingRequest request) {
        StringBuilder canonical = new StringBuilder();
        request.segments.forEach(segment -> canonical.append(segment.mode).append('|').append(segment.offerId)
                .append('|').append(segment.seats).append('|').append(segment.origin).append('|')
                .append(segment.destination).append('|').append(segment.date).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 indisponible", error);
        }
    }

    private QuoteRequest pricingRequest(BookingRequest request) {
        List<Map<String, Object>> segmentLines = new ArrayList<>();
        for (BookingRequest.Segment segment : request.segments) {
            segmentLines.add(Map.of("mode", segment.mode, "priceMinor", resolvePrice(segment),
                    "seats", segment.seats, "currency", "MAD"));
        }
        return new QuoteRequest(segmentLines);
    }

    private long resolvePrice(BookingRequest.Segment segment) {
        String uri = UriComponentsBuilder.fromHttpUrl(serviceUrl(segment.mode) + "/v1/offers")
                .queryParam("origin", segment.origin).queryParam("destination", segment.destination)
                .queryParam("date", segment.date).build().encode().toUriString();
        try {
            Offer[] offers = http.getForObject(uri, Offer[].class);
            if (offers != null) {
                for (Offer offer : offers) {
                    if (segment.offerId.equals(offer.offerId)) {
                        return offer.priceMinor;
                    }
                }
            }
        } catch (ResourceAccessException error) {
            throw BookingException.unavailable("Le service de transport " + segment.mode + " est indisponible");
        } catch (HttpStatusCodeException error) {
            throw BookingException.unavailable("Le service de transport " + segment.mode + " a répondu HTTP "
                    + error.getStatusCode().value());
        }
        throw BookingException.conflict("Offre inconnue ou capacité insuffisante: " + segment.offerId);
    }

    private BookingView view(TripBookingEntity booking) {
        BookingView result = new BookingView();
        result.bookingId = booking.id;
        result.state = booking.state;
        result.createdAt = booking.createdAt;
        result.subtotalMinor = booking.subtotalMinor;
        result.feesMinor = booking.feesMinor;
        result.discountMinor = booking.discountMinor;
        result.totalMinor = booking.totalMinor;
        result.currency = booking.currency;
        result.failureReason = booking.failureReason;
        result.steps = steps.findByBookingIdOrderByStepOrder(booking.id).stream().map(step -> {
            BookingView.Step line = new BookingView.Step();
            line.order = step.stepOrder;
            line.mode = step.mode;
            line.offerId = step.offerId;
            line.seats = step.seats;
            line.holdId = step.holdId;
            line.bookingRef = step.bookingRef;
            line.state = step.state;
            line.lastError = step.lastError;
            line.compensationAttempts = step.compensationAttempts;
            return line;
        }).toList();
        return result;
    }

    public static class HoldRequestBody {
        public String offerId;
        public int seats;
        public HoldRequestBody(String offerId, int seats) { this.offerId = offerId; this.seats = seats; }
    }

    public static class QuoteRequest {
        public List<Map<String, Object>> segments;
        public QuoteRequest(List<Map<String, Object>> segments) { this.segments = segments; }
    }

    public static class Quote {
        public long subtotalMinor;
        public long feesMinor;
        public long discountMinor;
        public long totalMinor;
        public String currency;
    }
}
