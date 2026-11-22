package ma.mobility.carshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import ma.mobility.canonical.HoldRequest;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.carshare.model.CarReservationEntity;
import ma.mobility.carshare.model.CarOfferEntity;
import ma.mobility.carshare.repository.CarOfferRepository;
import ma.mobility.carshare.repository.CarReservationRepository;
import ma.mobility.carshare.service.CarshareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:carshare-test;DB_CLOSE_DELAY=-1",
        "carshare.expiration-scan-ms=3600000"
})
@AutoConfigureMockMvc
class CarshareFlowTest {
    @Autowired MockMvc mvc;
    @Autowired CarshareService service;
    @Autowired CarReservationRepository reservations;
    @Autowired CarOfferRepository offers;
    private String testOfferId;

    @org.junit.jupiter.api.BeforeEach
    void createIsolatedOffer() {
        testOfferId = "CAR-TEST-" + java.util.UUID.randomUUID();
        offers.saveAndFlush(new CarOfferEntity(testOfferId, "Test", "Destination",
                java.time.LocalDateTime.parse("2026-11-15T10:00:00"),
                java.time.LocalDateTime.parse("2026-11-15T11:00:00"), 1000, "MAD", 4));
    }

    @Test
    void canonicalSearchAndReservationLifecycleRestoreCapacityOnlyOnce() throws Exception {
        mvc.perform(get("/v1/offers").param("origin", "Casablanca").param("destination", "Rabat")
                        .param("date", "2026-11-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].offerId").value("CAR:CAR-CAS-RAB-1000"))
                .andExpect(jsonPath("$[0].mode").value("CAR"));
        HoldResponse hold = service.hold(request(1));
        mvc.perform(post("/v1/holds/" + hold.holdId + "/confirm"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingRef").exists());
        String ref = reservations.findByHoldId(hold.holdId).orElseThrow().bookingRef;
        mvc.perform(delete("/v1/references/" + ref)).andExpect(status().isOk());
        mvc.perform(delete("/v1/references/" + ref)).andExpect(status().isOk());
        assertEquals(1, reservations.findByHoldId(hold.holdId).orElseThrow().seats);
        assertEquals(CarReservationEntity.State.CANCELLED,
                reservations.findByHoldId(hold.holdId).orElseThrow().state);
        assertEquals(4, offers.findById(testOfferId).orElseThrow().seatsAvailable);
    }

    @Test
    void invalidAndExcessCapacityAreReportedWithCanonicalErrors() throws Exception {
        mvc.perform(post("/v1/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"CAR:" + testOfferId + "\",\"seats\":8}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        mvc.perform(post("/v1/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"CAR:" + testOfferId + "\",\"seats\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void expiredHoldIsPersistedAsReleasedAndCannotBeConfirmed() {
        HoldResponse hold = service.hold(request(2));
        CarReservationEntity reservation = reservations.findByHoldId(hold.holdId).orElseThrow();
        reservation.expiresAt = Instant.now().minusSeconds(1);
        reservations.saveAndFlush(reservation);
        assertThrows(RuntimeException.class, () -> service.confirm(hold.holdId));
        assertEquals(CarReservationEntity.State.EXPIRED,
                reservations.findByHoldId(hold.holdId).orElseThrow().state);
        assertEquals(4, offers.findById(testOfferId).orElseThrow().seatsAvailable);
    }

    @Test
    void concurrentHoldsNeverOversell() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> tryHold(3));
            var second = executor.submit(() -> tryHold(3));
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, offers.findById(testOfferId).orElseThrow().seatsAvailable);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean tryHold(int seats) {
        try {
            service.hold(request(seats));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private HoldRequest request(int seats) {
        HoldRequest request = new HoldRequest();
        request.offerId = "CAR:" + testOfferId;
        request.seats = seats;
        return request;
    }
}
