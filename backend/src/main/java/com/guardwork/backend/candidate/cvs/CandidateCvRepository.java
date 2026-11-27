package com.guardwork.backend.candidate.cvs;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateCvRepository extends JpaRepository<CandidateCv, UUID> {

    List<CandidateCv> findByCandidateIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID candidateId);

    long countByCandidateIdAndDeletedAtIsNull(UUID candidateId);

    boolean existsByPublicId(String publicId);

    Optional<CandidateCv> findByIdAndCandidateIdAndDeletedAtIsNull(UUID id, UUID candidateId);

    /**
     * Serializes application submission against library deletion for one CV row. Submission
     * must never record a grant for bytes that a concurrent delete just started removing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select cv from CandidateCv cv
            where cv.id = :id and cv.candidateId = :candidateId and cv.deletedAt is null
            """)
    Optional<CandidateCv> findActiveForUpdate(
            @Param("id") UUID id, @Param("candidateId") UUID candidateId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CandidateCv cv
            set cv.storageDeletedAt = :at
            where cv.id = :id and cv.storageDeletedAt is null
            """)
    int markStorageDeleted(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Claim removed library CVs whose Cloudinary object is still present and which no longer
     * have any live Company grant. Rows are locked so concurrent workers do not purge twice.
     */
    @Query(value = """
            select cv.id
            from candidate_cvs cv
            where cv.deleted_at is not null
              and cv.storage_deleted_at is null
              and not exists (
                  select 1
                  from candidate_cv_access_grants grant_row
                  join applications application on application.id = grant_row.application_id
                  where grant_row.cv_id = cv.id
                    and grant_row.revoked_at is null
                    and (grant_row.expires_at is null or grant_row.expires_at > now())
                    and application.status in
                        ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING','HIRED')
              )
            order by cv.deleted_at
            limit :batchSize
            for update of cv skip locked
            """, nativeQuery = true)
    List<UUID> findPurgeableCvIds(@Param("batchSize") int batchSize);
}
