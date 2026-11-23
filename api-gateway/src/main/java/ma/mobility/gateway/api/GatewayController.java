package ma.mobility.gateway.api;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import ma.mobility.canonical.ApiError;
import ma.mobility.canonical.Offer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
    private final RestTemplate http;
    private final Map<String, CircuitBreaker> breakers = new LinkedHashMap<>();
    private final Retry searchRetry;
    private final long searchTimeoutMs;

    @Value("${gateway.rail-url}") private String railUrl;
    @Value("${gateway.air-url}") private String airUrl;
    @Value("${gateway.car-url}") private String carUrl;
    @Value("${gateway.booking-url}") private String bookingUrl;

    public GatewayController(RestTemplate http,
                             @Value("${gateway.search.timeout-ms:3500}") long searchTimeoutMs,
                             @Value("${gateway.circuit-breaker.window-size:6}") int windowSize,
                             @Value("${gateway.circuit-breaker.minimum-calls:3}") int minimumCalls,
                             @Value("${gateway.circuit-breaker.failure-rate:50}") float failureRate,
                             @Value("${gateway.circuit-breaker.open-seconds:15}") int openSeconds,
                             @Value("${gateway.retry.wait-ms:150}") long retryWaitMs) {
        this.http = http;
        this.searchTimeoutMs = searchTimeoutMs;

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(GatewayController::isUpstreamFailure)
                .build();
        for (String source : List.of("rail", "air", "car")) {
            CircuitBreaker cb = CircuitBreaker.of("gateway-" + source, cbConfig);
            cb.getEventPublisher().onStateTransition(event ->
                    log.warn("Circuit breaker gateway-{} : {}", source, event.getStateTransition()));
            breakers.put(source, cb);
        }
        this.searchRetry = Retry.of("gateway-search", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(retryWaitMs))
                .retryOnException(GatewayController::isRetryableSearchFailure)
                .build());
    }

    public Map<String, CircuitBreaker> circuitBreakers() {
        return Collections.unmodifiableMap(breakers);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Offer>> search(@RequestParam String origin, @RequestParam String destination,
                                              @RequestParam String date) {
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.DateTimeException error) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "date doit être au format AAAA-MM-JJ", error);
        }
        List<String> unavailable = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        CompletableFuture<SourceResult> rail = searchSource("rail", railUrl, origin, destination, date);
        CompletableFuture<SourceResult> air = searchSource("air", airUrl, origin, destination, date);
        CompletableFuture<SourceResult> car = searchSource("car", carUrl, origin, destination, date);
        List<Offer> results = new ArrayList<>();
        for (SourceResult result : List.of(rail.join(), air.join(), car.join())) {
            if (result.available) {
                results.addAll(result.offers);
            } else {
                unavailable.add(result.name);
                reasons.add(result.name + ":" + result.degradedReason);
            }
        }
        results.sort(Comparator.comparing((Offer offer) -> offer.departure == null ? "" : offer.departure)
                .thenComparingLong(offer -> offer.priceMinor).thenComparing(offer -> offer.offerId));
        HttpHeaders headers = new HttpHeaders();
        if (!unavailable.isEmpty()) {
            headers.add("X-Partial-Results", String.join(",", unavailable));
            headers.add("X-Degraded-Reason", String.join(",", reasons));
            log.warn("Recherche dégradée origin={} dest={} date={} sourcesIndisponibles={}",
                    origin, destination, date, reasons);
        } else {
            log.info("Recherche multimodale origin={} dest={} date={} -> {} offres",
                    origin, destination, date, results.size());
        }
        return new ResponseEntity<>(results, headers, HttpStatusCode.valueOf(200));
    }

    @PostMapping("/bookings")
    public ResponseEntity<String> createBooking(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody String body) {
        log.info("Relais POST /api/v1/bookings idempotencyKey={}", key);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return proxy(HttpMethod.POST, bookingUrl + "/v1/bookings", body, headers);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<String> getBooking(@PathVariable String id) {
        return proxy(HttpMethod.GET, bookingUrl + "/v1/bookings/" + id, null, new HttpHeaders());
    }

    private CompletableFuture<SourceResult> searchSource(String name, String base, String origin,
                                                         String destination, String date) {
        CircuitBreaker cb = breakers.get(name);
        return CompletableFuture.supplyAsync(() -> {
            String uri = UriComponentsBuilder.fromHttpUrl(base + "/v1/offers")
                    .queryParam("origin", origin).queryParam("destination", destination).queryParam("date", date)
                    .build().encode().toUriString();
            Supplier<Offer[]> call = Retry.decorateSupplier(searchRetry,
                    CircuitBreaker.decorateSupplier(cb, () -> http.getForObject(uri, Offer[].class)));
            try {
                Offer[] offers = call.get();
                return new SourceResult(name, offers == null ? List.of() : List.of(offers), true, null);
            } catch (CallNotPermittedException open) {
                log.warn("Source {} ignorée (circuit breaker OPEN)", name);
                return new SourceResult(name, List.of(), false, ApiError.CIRCUIT_OPEN);
            } catch (HttpStatusCodeException error) {
                String code = isBridgeCircuitOpen(error) ? ApiError.CIRCUIT_OPEN : "HTTP_" + error.getStatusCode().value();
                return new SourceResult(name, List.of(), false, code);
            } catch (RestClientException error) {
                return new SourceResult(name, List.of(), false, ApiError.UPSTREAM_UNAVAILABLE);
            }
        }).orTimeout(searchTimeoutMs, TimeUnit.MILLISECONDS).exceptionally(ex -> {
            log.warn("Source {} en timeout après {} ms", name, searchTimeoutMs);
            cb.onError(searchTimeoutMs, TimeUnit.MILLISECONDS, ex);
            return new SourceResult(name, List.of(), false, "TIMEOUT");
        });
    }

    private static boolean isUpstreamFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TimeoutException) return true;
        if (cause instanceof HttpStatusCodeException httpError) {
            return httpError.getStatusCode().is5xxServerError();
        }
        return cause instanceof RestClientException;
    }

    private static boolean isRetryableSearchFailure(Throwable error) {
        if (error instanceof CallNotPermittedException) return false;
        if (error instanceof HttpStatusCodeException httpError && isBridgeCircuitOpen(httpError)) return false;
        return isUpstreamFailure(error);
    }

    private static boolean isBridgeCircuitOpen(HttpStatusCodeException error) {
        String body = error.getResponseBodyAsString();
        return body != null && body.contains(ApiError.CIRCUIT_OPEN);
    }

    private ResponseEntity<String> proxy(HttpMethod method, String uri, String body, HttpHeaders headers) {
        try {
            return http.exchange(uri, method, new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException error) {
            HttpHeaders responseHeaders = new HttpHeaders();
            MediaType contentType = error.getResponseHeaders() == null ? null : error.getResponseHeaders().getContentType();
            if (contentType != null) responseHeaders.setContentType(contentType);
            return new ResponseEntity<>(error.getResponseBodyAsString(), responseHeaders, error.getStatusCode());
        } catch (RestClientException error) {
            return ResponseEntity.status(503).body("{\"code\":\"UPSTREAM_UNAVAILABLE\",\"reason\":\"booking-service indisponible\"}");
        }
    }

    private static class SourceResult {
        final String name;
        final List<Offer> offers;
        final boolean available;
        final String degradedReason;

        SourceResult(String name, List<Offer> offers, boolean available, String degradedReason) {
            this.name = name;
            this.offers = offers;
            this.available = available;
            this.degradedReason = degradedReason;
        }
    }
}
