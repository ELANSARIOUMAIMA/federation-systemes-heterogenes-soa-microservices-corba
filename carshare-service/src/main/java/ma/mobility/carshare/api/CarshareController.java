package ma.mobility.carshare.api;

import java.util.List;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldRequest;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import ma.mobility.carshare.service.CarshareService;
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
    private final CarshareService service;

    public CarshareController(CarshareService service) { this.service = service; }

    @GetMapping("/offers")
    public List<Offer> search(@RequestParam String origin, @RequestParam String destination,
                              @RequestParam String date) {
        return service.search(origin, destination, date);
    }

    @PostMapping("/holds")
    public HoldResponse hold(@RequestBody HoldRequest request) { return service.hold(request); }

    @PostMapping("/holds/{holdId}/confirm")
    public ConfirmResponse confirm(@PathVariable String holdId) { return service.confirm(holdId); }

    @DeleteMapping("/references/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String ref) {
        service.cancel(ref);
        return ResponseEntity.ok().build();
    }
}
