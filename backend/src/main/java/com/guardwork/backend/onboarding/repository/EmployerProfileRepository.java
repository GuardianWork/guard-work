package com.guardwork.backend.onboarding.repository;

import com.guardwork.backend.onboarding.model.EmployerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmployerProfileRepository extends JpaRepository<EmployerProfile, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EmployerProfile r WHERE r.userId = :id")
    Optional<EmployerProfile> findLocked(@Param("id") Long id);
    boolean existsByPhoneAndPhoneVerifiedAtIsNotNull(String phone);
}
