package com.guardwork.backend.onboarding.repository;

import com.guardwork.backend.onboarding.model.VerificationDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface VerificationDocumentRepository extends JpaRepository<VerificationDocument, UUID> {

}
