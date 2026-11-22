package ma.mobility.booking.repository;

import java.util.List;
import ma.mobility.booking.model.SagaStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepRepository extends JpaRepository<SagaStepEntity, Long> {
    List<SagaStepEntity> findByBookingIdOrderByStepOrder(String bookingId);
}
