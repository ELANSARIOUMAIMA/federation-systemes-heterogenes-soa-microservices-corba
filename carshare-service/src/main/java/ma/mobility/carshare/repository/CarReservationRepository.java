package ma.mobility.carshare.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ma.mobility.carshare.model.CarReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CarReservationRepository extends JpaRepository<CarReservationEntity, String> {
    Optional<CarReservationEntity> findByHoldId(String holdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CarReservationEntity r where r.holdId = :holdId")
    Optional<CarReservationEntity> findByHoldIdForUpdate(@Param("holdId") String holdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CarReservationEntity r where r.holdId = :reference or r.bookingRef = :reference")
    Optional<CarReservationEntity> findByReferenceForUpdate(@Param("reference") String reference);

    List<CarReservationEntity> findByStateAndExpiresAtBefore(CarReservationEntity.State state, Instant instant);
}
