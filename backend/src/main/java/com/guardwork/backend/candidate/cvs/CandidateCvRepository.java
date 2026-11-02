package com.guardwork.backend.candidate.cvs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateCvRepository extends JpaRepository<CandidateCv, UUID> {
    List<CandidateCv> findByCandidateIdOrderByCreatedAtDesc(UUID candidateId);
    long countByCandidateId(UUID candidateId);
    boolean existsByCandidateIdAndIsDefaultTrue(UUID candidateId);
    boolean existsByPublicId(String publicId);
    Optional<CandidateCv> findByIdAndCandidateId(UUID id, UUID candidateId);
}
