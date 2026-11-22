package ma.mobility.pricing.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pricing_rule")
public class PricingRuleEntity {
    @Id
    public String id;
    public long feePerSegmentMinor;
    public int multimodalDiscountBasisPoints;

    protected PricingRuleEntity() { }
    public PricingRuleEntity(String id, long feePerSegmentMinor, int multimodalDiscountBasisPoints) {
        this.id = id;
        this.feePerSegmentMinor = feePerSegmentMinor;
        this.multimodalDiscountBasisPoints = multimodalDiscountBasisPoints;
    }
}
