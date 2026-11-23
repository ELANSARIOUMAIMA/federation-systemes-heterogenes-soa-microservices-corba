package ma.mobility.pricing.api;

import ma.mobility.pricing.service.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pricing")
public class PricingController {
    private static final Logger log = LoggerFactory.getLogger(PricingController.class);
    private final PricingService pricing;
    public PricingController(PricingService pricing) { this.pricing = pricing; }
    @PostMapping("/quote")
    public QuoteResponse quote(@RequestBody QuoteRequest request) {
        QuoteResponse response = pricing.quote(request);
        log.info("Devis calculé segments={} totalMinor={} {}",
                request == null || request.segments == null ? 0 : request.segments.size(),
                response.totalMinor, response.currency);
        return response;
    }
}
