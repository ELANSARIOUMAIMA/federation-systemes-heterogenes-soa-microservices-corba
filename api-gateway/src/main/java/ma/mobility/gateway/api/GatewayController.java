package ma.mobility.gateway.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import ma.mobility.canonical.Offer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
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
    private final RestTemplate http;
    @Value("${gateway.rail-url}") private String railUrl;
    @Value("${gateway.air-url}") private String airUrl;
    @Value("${gateway.car-url}") private String carUrl;
    @Value("${gateway.booking-url}") private String bookingUrl;

    public GatewayController(RestTemplate http) { this.http = http; }

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
        CompletableFuture<SourceResult> rail = searchSource("rail", railUrl, origin, destination, date);
        CompletableFuture<SourceResult> air = searchSource("air", airUrl, origin, destination, date);
        CompletableFuture<SourceResult> car = searchSource("car", carUrl, origin, destination, date);
        List<Offer> results = new ArrayList<>();
        for (SourceResult result : List.of(rail.join(), air.join(), car.join())) {
            if (result.available) results.addAll(result.offers);
            else unavailable.add(result.name);
        }
        results.sort(Comparator.comparing((Offer offer) -> offer.departure == null ? "" : offer.departure)
                .thenComparingLong(offer -> offer.priceMinor).thenComparing(offer -> offer.offerId));
        HttpHeaders headers = new HttpHeaders();
        if (!unavailable.isEmpty()) headers.add("X-Partial-Results", String.join(",", unavailable));
        return new ResponseEntity<>(results, headers, HttpStatusCode.valueOf(200));
    }

    @PostMapping("/bookings")
    public ResponseEntity<String> createBooking(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @RequestBody String body) {
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
        return CompletableFuture.supplyAsync(() -> {
            String uri = UriComponentsBuilder.fromHttpUrl(base + "/v1/offers")
                    .queryParam("origin", origin).queryParam("destination", destination).queryParam("date", date)
                    .build().encode().toUriString();
            try {
                Offer[] offers = http.getForObject(uri, Offer[].class);
                return new SourceResult(name, offers == null ? List.of() : List.of(offers), true);
            } catch (RestClientException error) {
                return new SourceResult(name, List.of(), false);
            }
        });
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
        SourceResult(String name, List<Offer> offers, boolean available) {
            this.name = name;
            this.offers = offers;
            this.available = available;
        }
    }
}
