package com.guardwork.backend.application.repository;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApplicationSnapshotRepository extends JpaRepository<ApplicationSnapshot, UUID> {

    /**
     * Claims rejected legacy copies whose bytes were not deleted yet. Metadata is retained, so
     * only {@code cv_purged_at} changes (the snapshot trigger permits exactly that transition).
     */
    @Query(value = """
            SELECT snapshot.application_id
            FROM application_snapshots snapshot
            JOIN applications application ON application.id = snapshot.application_id
            WHERE application.status = 'REJECTED'
              AND snapshot.cv_storage_mode = 'LEGACY_COPY'
              AND snapshot.cv_purged_at IS NULL
            ORDER BY snapshot.captured_at
            LIMIT :batchSize
            FOR UPDATE OF snapshot SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findRejectedLegacyCvPurgeIds(@Param("batchSize") int batchSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApplicationSnapshot snapshot
            set snapshot.cvPurgedAt = :purgedAt
            where snapshot.applicationId = :applicationId and snapshot.cvPurgedAt is null
            """)
    int markCvPurged(@Param("applicationId") UUID applicationId, @Param("purgedAt") Instant purgedAt);
}
