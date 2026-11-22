package ma.mobility.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ma.mobility.booking.api.BookingException;
import ma.mobility.booking.api.BookingRequest;
import ma.mobility.booking.api.BookingView;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.service.BookingService;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-test;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.username=test-user",
        "spring.rabbitmq.password=test-password",
        "booking.outbox.scan-ms=3600000"
})
class BookingSagaTest {
    @MockBean RestTemplate http;
    @MockBean RabbitTemplate rabbit;
    @Autowired BookingService bookings;

    @BeforeEach
    void configurePricingAndTransportMocks() {
        when(http.getForObject(anyString(), eq(Offer[].class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0, String.class);
            Offer offer = new Offer();
            offer.offerId = uri.contains("localhost:8083")
                    ? "AIR:AIR-RBA-ORY-1200" : "RAIL:RAIL-CAS-RAB-0730";
            offer.priceMinor = uri.contains("localhost:8083") ? 180000 : 8000;
            offer.seatsAvailable = 10;
            return new Offer[] { offer };
        });
        BookingService.Quote quote = new BookingService.Quote();
        quote.subtotalMinor = 188000;
        quote.feesMinor = 400;
        quote.discountMinor = 9400;
        quote.totalMinor = 179000;
        quote.currency = "MAD";
        when(http.postForObject(anyString(), any(), eq(BookingService.Quote.class))).thenReturn(quote);
        when(http.postForObject(anyString(), any(), eq(HoldResponse.class))).thenAnswer(
                invocation -> new HoldResponse("hold-" + java.util.UUID.randomUUID()));
        when(http.postForObject(anyString(), any(), eq(ConfirmResponse.class))).thenAnswer(
                invocation -> new ConfirmResponse("booking-ref-" + java.util.UUID.randomUUID()));
        doReturn(ResponseEntity.ok().build()).when(http)
                .exchange(anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    void allHoldsPrecedeConfirmationsAndSameIdempotencyKeyDoesNotRepeatSideEffects() {
        BookingRequest request = request();
        String key = "happy-" + java.util.UUID.randomUUID();
        BookingView result = bookings.create(key, request);
        assertEquals(BookingState.CONFIRMED, result.state);
        assertEquals(179000, result.totalMinor);
        assertEquals(2, result.steps.size());
        assertEquals(ma.mobility.booking.model.SagaStepState.CONFIRMED, result.steps.get(1).state);
        BookingView replay = bookings.create(key, request);
        assertEquals(result.bookingId, replay.bookingId);
        assertEquals(result.steps.get(0).holdId, replay.steps.get(0).holdId);
    }

    @Test
    void failedConfirmationCompensatesConfirmedAndHeldReferences() {
        when(http.postForObject(org.mockito.ArgumentMatchers.contains("localhost:8083"), any(),
                eq(ConfirmResponse.class))).thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "not available", org.springframework.http.HttpHeaders.EMPTY,
                new byte[0], null));
        BookingView result = bookings.create("confirm-fail-" + java.util.UUID.randomUUID(), request());
        assertEquals(BookingState.CANCELLED, result.state);
        assertEquals(2L, result.steps.stream()
                .filter(step -> step.state == ma.mobility.booking.model.SagaStepState.COMPENSATED).count());
    }

    @Test
    void failedHoldCompensatesEarlierHoldAndPayloadConflictIsRejected() {
        when(http.postForObject(org.mockito.ArgumentMatchers.contains("localhost:8083"), any(),
                eq(HoldResponse.class))).thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "sold out", org.springframework.http.HttpHeaders.EMPTY,
                new byte[0], null));
        BookingRequest request = request();
        String key = "hold-fail-" + java.util.UUID.randomUUID();
        BookingView result = bookings.create(key, request);
        assertEquals(BookingState.CANCELLED, result.state);
        assertEquals(ma.mobility.booking.model.SagaStepState.COMPENSATED, result.steps.get(0).state);
        BookingRequest different = request();
        different.segments.get(0).seats = 2;
        assertThrows(BookingException.class, () -> bookings.create(key, different));
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOnlyOneSaga() throws Exception {
        String key = "concurrent-" + java.util.UUID.randomUUID();
        BookingRequest request = request();
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> createTogether(ready, start, key, request));
            var second = executor.submit(() -> createTogether(ready, start, key, request));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            BookingView a = first.get(60, TimeUnit.SECONDS);
            BookingView b = second.get(60, TimeUnit.SECONDS);
            assertEquals(a.bookingId, b.bookingId);
            assertEquals(BookingState.CONFIRMED, bookings.get(a.bookingId).state);
            verify(http, times(2)).postForObject(contains("/v1/holds"), any(), eq(HoldResponse.class));
            verify(http, times(2)).postForObject(contains("/confirm"), any(), eq(ConfirmResponse.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void compensationFailureIsPersistedAndRetriedWithoutRepeatingHold() {
        when(http.postForObject(contains("localhost:8083"), any(), eq(HoldResponse.class))).thenThrow(
                HttpClientErrorException.create(HttpStatus.CONFLICT, "sold out",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        org.mockito.Mockito.doThrow(new ResourceAccessException("temporary outage")).when(http)
                .exchange(contains("localhost:8082"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
        String key = "retry-" + java.util.UUID.randomUUID();
        BookingView pending = bookings.create(key, request());
        assertEquals(BookingState.COMPENSATION_PENDING, pending.state);
        assertEquals(1, pending.steps.get(0).compensationAttempts);

        doReturn(ResponseEntity.ok().build()).when(http)
                .exchange(contains("localhost:8082"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
        bookings.retryCompensations();
        assertEquals(BookingState.CANCELLED, bookings.get(pending.bookingId).state);
        verify(http, times(2)).postForObject(contains("/v1/holds"), any(), eq(HoldResponse.class));
    }

    private BookingView createTogether(CountDownLatch ready, CountDownLatch start, String key,
                                      BookingRequest request) throws InterruptedException {
        ready.countDown();
        start.await();
        return bookings.create(key, request);
    }

    private BookingRequest request() {
        BookingRequest request = new BookingRequest();
        BookingRequest.Segment train = new BookingRequest.Segment();
        train.mode = ma.mobility.canonical.TransportMode.TRAIN;
        train.offerId = "RAIL:RAIL-CAS-RAB-0730";
        train.seats = 1;
        train.origin = "Casablanca";
        train.destination = "Rabat";
        train.date = "2026-11-15";
        BookingRequest.Segment air = new BookingRequest.Segment();
        air.mode = ma.mobility.canonical.TransportMode.AIR;
        air.offerId = "AIR:AIR-RBA-ORY-1200";
        air.seats = 1;
        air.origin = "Rabat";
        air.destination = "Paris";
        air.date = "2026-11-15";
        request.segments = List.of(train, air);
        return request;
    }
}
