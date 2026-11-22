package ma.mobility.booking.repository;

import java.util.List;
import ma.mobility.booking.model.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEventEntity, String> {
    List<OutboxEventEntity> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
}
