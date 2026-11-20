package com.guardwork.backend.candidate.cvs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CandidateCvAccessGrantRepository extends JpaRepository<CandidateCvAccessGrant, UUID> {

    @Query("""
            select grant from CandidateCvAccessGrant grant
            where grant.applicationId = :applicationId
              and grant.companyId = :companyId
              and grant.revokedAt is null
              and (grant.expiresAt is null or grant.expiresAt > :now)
            """)
    Optional<CandidateCvAccessGrant> findLive(
            @Param("applicationId") UUID applicationId,
            @Param("companyId") Long companyId,
            @Param("now") Instant now);

    Optional<CandidateCvAccessGrant> findFirstByApplicationIdOrderByEpochDesc(UUID applicationId);

    /**
     * Starts the bounded post-deletion window for every live grant whose Application is still in
     * an active review stage. HIRED grants are revoked separately; terminal/INACTIVE grants are
     * already revoked.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE candidate_cv_access_grants grant_row
            SET expires_at = :expiresAt
            WHERE grant_row.cv_id = :cvId
              AND grant_row.expires_at IS NULL
              AND grant_row.revoked_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM applications application
                  WHERE application.id = grant_row.application_id
                    AND application.status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','INTERVIEWING')
              )
            """, nativeQuery = true)
    int startReviewWindow(@Param("cvId") UUID cvId, @Param("expiresAt") Instant expiresAt);

    /**
     * A HIRED Application has no post-deletion review window: Candidate deletion ends Company
     * access immediately (option B of the approved amendment).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE candidate_cv_access_grants grant_row
            SET revoked_at = :at, revoked_reason = :reason
            WHERE grant_row.cv_id = :cvId
              AND grant_row.revoked_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM applications application
                  WHERE application.id = grant_row.application_id
                    AND application.status = 'HIRED'
              )
            """, nativeQuery = true)
    int revokeHiredForDeletedCv(
            @Param("cvId") UUID cvId,
            @Param("at") Instant at,
            @Param("reason") String reason);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CandidateCvAccessGrant grant
            set grant.revokedAt = :at, grant.revokedReason = :reason
            where grant.applicationId = :applicationId and grant.revokedAt is null
            """)
    int revoke(@Param("applicationId") UUID applicationId,
               @Param("at") Instant at,
               @Param("reason") String reason);
}
