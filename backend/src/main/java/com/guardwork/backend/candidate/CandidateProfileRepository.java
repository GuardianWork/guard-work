package com.guardwork.backend.candidate;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CandidateProfile}.
 */
@Repository
public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, java.util.UUID> {
    /**
     * Find a candidate profile by the associated user id.
     */
    Optional<CandidateProfile> findByUserId(Long userId);
}
