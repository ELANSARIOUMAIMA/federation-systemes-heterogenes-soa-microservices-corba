package ma.mobility.pricing.config;

import ma.mobility.pricing.model.PricingRuleEntity;
import ma.mobility.pricing.repository.PricingRuleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricingSeed {
    @Bean
    CommandLineRunner seedPricing(PricingRuleRepository rules) {
        return args -> rules.findById("DEFAULT").orElseGet(
                () -> rules.save(new PricingRuleEntity("DEFAULT", 200, 500)));
    }
}
