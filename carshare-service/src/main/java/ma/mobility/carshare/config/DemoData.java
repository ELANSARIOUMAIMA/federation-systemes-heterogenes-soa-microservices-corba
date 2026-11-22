package ma.mobility.carshare.config;

import java.time.LocalDateTime;
import ma.mobility.carshare.model.CarOfferEntity;
import ma.mobility.carshare.repository.CarOfferRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoData {
    @Bean
    CommandLineRunner seedCarOffers(CarOfferRepository offers) {
        return args -> {
            seed(offers, new CarOfferEntity("CAR-CAS-RAB-1000", "Casablanca", "Rabat",
                    LocalDateTime.parse("2026-11-15T10:00:00"), LocalDateTime.parse("2026-11-15T11:30:00"),
                    4500, "MAD", 4));
            seed(offers, new CarOfferEntity("CAR-RAB-TNG-1400", "Rabat", "Tanger",
                    LocalDateTime.parse("2026-11-15T14:00:00"), LocalDateTime.parse("2026-11-15T17:00:00"),
                    12000, "MAD", 3));
            seed(offers, new CarOfferEntity("CAR-CAS-RAB-1000-D2", "Casablanca", "Rabat",
                    LocalDateTime.parse("2026-11-16T10:00:00"), LocalDateTime.parse("2026-11-16T11:30:00"),
                    4500, "MAD", 4));
        };
    }

    private void seed(CarOfferRepository offers, CarOfferEntity offer) {
        if (!offers.existsById(offer.offerId)) offers.save(offer);
    }
}
