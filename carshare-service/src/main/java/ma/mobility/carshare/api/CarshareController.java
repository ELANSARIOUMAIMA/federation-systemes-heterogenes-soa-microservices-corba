package ma.mobility.carshare.api;

import java.util.List;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldRequest;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import ma.mobility.carshare.service.CarshareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class CarshareController {
    private static final Logger log = LoggerFactory.getLogger(CarshareController.class);
    private final CarshareService service;

    public CarshareController(CarshareService service) { this.service = service; }

    @GetMapping("/offers")
    public List<Offer> search(@RequestParam String origin, @RequestParam String destination,
                              @RequestParam String date) {
        List<Offer> offers = service.search(origin, destination, date);
        log.info("Carshare search {}->{} ({}) -> {} offres", origin, destination, date, offers.size());
        return offers;
    }

    @PostMapping("/holds")
    public HoldResponse hold(@RequestBody HoldRequest request) {
        HoldResponse response = service.hold(request);
        log.info("Carshare hold offer={} seats={} -> {}", request.offerId, request.seats, response.holdId);
        return response;
    }

    @PostMapping("/holds/{holdId}/confirm")
    public ConfirmResponse confirm(@PathVariable String holdId) {
        ConfirmResponse response = service.confirm(holdId);
        log.info("Carshare confirm hold={} -> {}", holdId, response.bookingRef);
        return response;
    }

    @DeleteMapping("/references/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String ref) {
        service.cancel(ref);
        log.info("Carshare cancel ref={} OK", ref);
        return ResponseEntity.ok().build();
    }
}
