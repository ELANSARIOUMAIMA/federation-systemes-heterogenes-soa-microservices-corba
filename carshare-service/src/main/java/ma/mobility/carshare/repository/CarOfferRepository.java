package ma.mobility.carshare.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import ma.mobility.carshare.model.CarOfferEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarOfferRepository extends JpaRepository<CarOfferEntity, String> {
    List<CarOfferEntity> findByOriginIgnoreCaseAndDestinationIgnoreCaseAndDepartureBetween(
            String origin, String destination, java.time.LocalDateTime from, java.time.LocalDateTime until);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CarOfferEntity o where o.offerId = :offerId")
    Optional<CarOfferEntity> findByIdForUpdate(@Param("offerId") String offerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CarOfferEntity o set o.seatsAvailable = o.seatsAvailable + :seats where o.offerId = :offerId")
    int restoreSeats(@Param("offerId") String offerId, @Param("seats") int seats);
}
