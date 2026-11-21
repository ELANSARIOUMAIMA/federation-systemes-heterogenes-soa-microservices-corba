package ma.mobility.airbridge.api;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ma.mobility.airbridge.soap.AirSoapClient;
import ma.mobility.canonical.ConfirmResponse;
import ma.mobility.canonical.HoldRequest;
import ma.mobility.canonical.HoldResponse;
import ma.mobility.canonical.Offer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API REST canonique du bridge aérien (même contrat que le bridge ferroviaire). */
@RestController
@RequestMapping("/v1")
public class AirController {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final AirSoapClient client;

    public AirController(AirSoapClient client) {
        this.client = client;
    }

    @GetMapping("/offers")
    public List<Offer> search(@RequestParam String origin,
                              @RequestParam String destination,
                              @RequestParam String date) {
        if (!DATE.matcher(date).matches()) {
            throw BridgeException.invalid("date doit être au format AAAA-MM-JJ");
        }
        return client.search(origin, destination, date).stream()
                .map(OfferMapper::fromFlight)
                .collect(Collectors.toList());
    }

    @PostMapping("/holds")
    public HoldResponse hold(@RequestBody HoldRequest request) {
        if (request.offerId == null || !request.offerId.startsWith(OfferMapper.PREFIX)
                || request.offerId.length() == OfferMapper.PREFIX.length()) {
            throw BridgeException.invalid("offerId doit commencer par " + OfferMapper.PREFIX);
        }
        if (request.seats <= 0) {
            throw BridgeException.invalid("seats doit être supérieur à 0");
        }
        String flightId = request.offerId.substring(OfferMapper.PREFIX.length());
        return new HoldResponse(client.hold(flightId, request.seats));
    }

    @PostMapping("/holds/{holdId}/confirm")
    public ConfirmResponse confirm(@PathVariable String holdId) {
        return new ConfirmResponse(client.confirm(holdId));
    }

    /** {ref} = holdId ou bookingRef. Idempotent : 200 même si déjà annulé ou inconnu. */
    @DeleteMapping("/references/{ref}")
    public ResponseEntity<Void> cancel(@PathVariable String ref) {
        client.cancel(ref);
        return ResponseEntity.ok().build();
    }
}