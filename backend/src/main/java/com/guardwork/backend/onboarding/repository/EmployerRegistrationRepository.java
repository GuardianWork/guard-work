package com.guardwork.backend.onboarding.repository;

import com.guardwork.backend.onboarding.model.EmployerRegistration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmployerRegistrationRepository extends JpaRepository<EmployerRegistration, UUID> {
    long countByEmailAndCreatedAtAfter(String email, java.time.Instant since);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EmployerRegistration r WHERE r.id = :id")
    Optional<EmployerRegistration> findLocked(@Param("id") UUID id);
    Optional<EmployerRegistration> findById(UUID id);
}
