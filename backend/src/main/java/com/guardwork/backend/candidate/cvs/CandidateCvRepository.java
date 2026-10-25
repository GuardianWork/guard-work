package com.guardwork.backend.candidate.cvs;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CandidateCvRepository extends JpaRepository<CandidateCv, UUID> {
    List<CandidateCv> findByCandidateId(UUID candidateId);
    long countByCandidateId(UUID candidateId);
    boolean existsByCandidateIdAndIsDefaultTrue(UUID candidateId);
}
