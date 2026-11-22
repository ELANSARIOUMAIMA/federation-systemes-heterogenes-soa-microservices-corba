package ma.mobility.booking.repository;

import java.util.Optional;
import java.time.Instant;
import ma.mobility.booking.model.BookingState;
import ma.mobility.booking.model.TripBookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BookingRepository extends JpaRepository<TripBookingEntity, String> {
    Optional<TripBookingEntity> findByIdempotencyKey(String key);
    List<TripBookingEntity> findByState(BookingState state);
    List<TripBookingEntity> findByStateInAndCreatedAtBefore(List<BookingState> states, Instant cutoff);
}
