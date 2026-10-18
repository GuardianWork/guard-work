package com.guardwork.backend.onboarding.repository;

import com.guardwork.backend.onboarding.model.VerificationAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, UUID> {
    java.util.List<VerificationAttempt> findByDocumentId(UUID documentId);
    Optional<VerificationAttempt> findByCompanyIdAndOutcomeIsNull(Long companyId);
    java.util.List<VerificationAttempt> findByCompanyIdOrderBySubmittedAtAsc(Long companyId);
    boolean existsByCompanyId(Long companyId);
    boolean existsByDocumentIdAndCompanyId(UUID documentId, Long companyId);
}
