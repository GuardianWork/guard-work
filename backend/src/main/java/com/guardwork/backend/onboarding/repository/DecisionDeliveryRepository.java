package com.guardwork.backend.onboarding.repository;

import com.guardwork.backend.onboarding.model.DecisionDelivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DecisionDeliveryRepository extends JpaRepository<DecisionDelivery, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DecisionDelivery d WHERE d.id = :id")
    Optional<DecisionDelivery> findLocked(@Param("id") UUID id);
    java.util.List<DecisionDelivery> findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(int limit, java.time.Instant now);
}
