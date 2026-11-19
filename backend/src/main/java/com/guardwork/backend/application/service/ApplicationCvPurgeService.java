package com.guardwork.backend.application.service;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.candidate.cvs.CandidateCvService;
import com.guardwork.backend.candidate.cvs.CandidateCvStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Deletes legacy application-specific CV copies after a terminal rejection.
 *
 * <p>The snapshot row and its metadata stay intact (immutable traceability); only the bytes are
 * removed. Shared library CVs are never deleted here because they belong to the Candidate and may
 * be used by other Applications.</p>
 */
@Slf4j
@Service
public class ApplicationCvPurgeService {

    private static final String LEGACY_COPY = "LEGACY_COPY";

    private final ApplicationSnapshotRepository snapshots;
    private final CandidateCvStorage storage;
    private final Clock clock;
    private final int batch;

    public ApplicationCvPurgeService(ApplicationSnapshotRepository snapshots,
            CandidateCvStorage storage, Clock clock,
            @Value("${application.cv.purge.batch-size:100}") int batch) {
        if (batch < 1 || batch > 1000) {
            throw new IllegalArgumentException("CV purge batch must be 1-1000");
        }
        this.snapshots = snapshots;
        this.storage = storage;
        this.clock = clock;
        this.batch = batch;
    }

    /** Processes one bounded batch, including the backfill for Applications rejected earlier. */
    @Transactional
    public int purgeDue() {
        List<UUID> due = snapshots.findRejectedLegacyCvPurgeIds(batch);
        int purged = 0;
        for (UUID applicationId : due) {
            ApplicationSnapshot snapshot = snapshots.findById(applicationId).orElse(null);
            if (snapshot == null || snapshot.getCvPurgedAt() != null) continue;
            if (!LEGACY_COPY.equals(snapshot.getCvStorageMode())) continue;

            String key = snapshot.getCvStorageKey();
            String expectedPrefix = "applications/" + applicationId + "/cv/";
            if (key == null || !key.startsWith(expectedPrefix)) {
                // Never delete a Candidate library object from this path.
                log.warn("event=application_cv_purge outcome=unsafe_key_skipped application_id={}",
                        applicationId);
                continue;
            }
            String resourceType = CandidateCvService.resourceTypeForContentType(
                    snapshot.getCvContentType());
            if (resourceType == null) {
                log.warn("event=application_cv_purge outcome=unsupported_content_type application_id={}",
                        applicationId);
                continue;
            }
            try {
                storage.delete(key, resourceType);
                snapshots.markCvPurged(applicationId, clock.instant());
                purged++;
            } catch (RuntimeException failure) {
                // Leave cv_purged_at null so the next tick retries the same object.
                log.warn("event=application_cv_purge outcome=retry_required application_id={}",
                        applicationId);
            }
        }
        return purged;
    }
}
