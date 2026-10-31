package com.guardwork.backend.candidate;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Serializes CV quota and default-flag changes for one candidate without a schema change.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from CandidateProfile profile where profile.id = :id")
    Optional<CandidateProfile> findByIdForUpdate(@Param("id") UUID id);
}
