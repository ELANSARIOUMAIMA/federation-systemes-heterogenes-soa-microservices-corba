package ma.mobility.pricing.repository;

import ma.mobility.pricing.model.PricingRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingRuleRepository extends JpaRepository<PricingRuleEntity, String> { }
