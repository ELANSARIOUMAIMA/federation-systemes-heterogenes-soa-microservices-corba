package ma.mobility.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import ma.mobility.canonical.TransportMode;
import ma.mobility.pricing.api.QuoteRequest;
import ma.mobility.pricing.api.QuoteResponse;
import ma.mobility.pricing.model.PricingRuleEntity;
import ma.mobility.pricing.repository.PricingRuleRepository;
import org.springframework.stereotype.Service;

@Service
public class PricingService {
    private final PricingRuleRepository rules;

    public PricingService(PricingRuleRepository rules) { this.rules = rules; }

    public QuoteResponse quote(QuoteRequest request) {
        if (request == null || request.segments == null || request.segments.isEmpty()) {
            throw new IllegalArgumentException("segments doit contenir au moins un segment");
        }
        long subtotal = 0;
        Set<TransportMode> modes = EnumSet.noneOf(TransportMode.class);
        for (QuoteRequest.Segment segment : request.segments) {
            if (segment == null || segment.mode == null || segment.priceMinor < 0 || segment.seats <= 0) {
                throw new IllegalArgumentException("mode, priceMinor >= 0 et seats > 0 sont requis");
            }
            if (!"MAD".equals(segment.currency)) {
                throw new IllegalArgumentException("Seule la devise MAD est acceptée");
            }
            try {
                subtotal = Math.addExact(subtotal, Math.multiplyExact(segment.priceMinor, (long) segment.seats));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Montant total hors plage autorisée");
            }
            modes.add(segment.mode);
        }
        PricingRuleEntity rule = rules.findById("DEFAULT")
                .orElseThrow(() -> new IllegalStateException("Règle DEFAULT absente"));
        if (rule.feePerSegmentMinor < 0 || rule.multimodalDiscountBasisPoints < 0
                || rule.multimodalDiscountBasisPoints > 10_000) {
            throw new IllegalStateException("La règle de tarification DEFAULT est invalide");
        }
        try {
            long fees = Math.multiplyExact(rule.feePerSegmentMinor, (long) request.segments.size());
            long discount = modes.size() > 1
                    ? BigDecimal.valueOf(subtotal).multiply(BigDecimal.valueOf(rule.multimodalDiscountBasisPoints))
                            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact()
                    : 0;
            return new QuoteResponse(subtotal, fees, discount, Math.subtractExact(Math.addExact(subtotal, fees), discount),
                    "MAD", modes.size() > 1 ? "MAD-FEE-200-MULTIMODAL-5PCT" : "MAD-FEE-200");
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Montant total hors plage autorisée");
        }
    }
}
