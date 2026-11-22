package ma.mobility.pricing.api;

import ma.mobility.pricing.service.PricingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/pricing")
public class PricingController {
    private final PricingService pricing;
    public PricingController(PricingService pricing) { this.pricing = pricing; }
    @PostMapping("/quote")
    public QuoteResponse quote(@RequestBody QuoteRequest request) { return pricing.quote(request); }
}
