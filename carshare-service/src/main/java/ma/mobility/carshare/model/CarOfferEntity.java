package ma.mobility.carshare.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "car_offer")
public class CarOfferEntity {
    @Id
    public String offerId;
    public String origin;
    public String destination;
    public LocalDateTime departure;
    public LocalDateTime arrival;
    public long priceMinor;
    public String currency;
    public int seatsAvailable;
    @Version
    public long version;

    protected CarOfferEntity() {
    }

    public CarOfferEntity(String offerId, String origin, String destination, LocalDateTime departure,
                          LocalDateTime arrival, long priceMinor, String currency, int seatsAvailable) {
        this.offerId = offerId;
        this.origin = origin;
        this.destination = destination;
        this.departure = departure;
        this.arrival = arrival;
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.seatsAvailable = seatsAvailable;
    }

    public String getOfferId() {
        return offerId;
    }
}
