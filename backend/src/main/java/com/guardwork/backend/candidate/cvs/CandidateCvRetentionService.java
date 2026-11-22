package com.guardwork.backend.candidate.cvs;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Deletes Cloudinary objects for Candidate CVs that were removed from the library and no longer
 * have any live access grant. Access itself is enforced at read time, so a delayed or failing
 * purge can never extend a Company's permission.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CandidateCvRetentionService {

    CandidateCvRepository cvs;
    CandidateCvStorage storage;
    Clock clock;

    @Transactional
    public int purgeDue(int batchSize) {
        List<UUID> due = cvs.findPurgeableCvIds(batchSize);
        int purged = 0;
        for (UUID cvId : due) {
            CandidateCv cv = cvs.findById(cvId).orElse(null);
            if (cv == null) {
                continue;
            }
            String resourceType = CandidateCvService.resourceTypeForContentType(cv.getContentType());
            if (resourceType == null) {
                log.warn("event=cv_object_purge outcome=unsupported_content_type cv_id={}", cvId);
                continue;
            }
            try {
                storage.delete(cv.getPublicId(), resourceType);
                cvs.markStorageDeleted(cvId, clock.instant());
                purged++;
            } catch (RuntimeException failure) {
                // Keep the tombstone so the next tick retries the same object.
                log.warn("event=cv_object_purge outcome=retry_required cv_id={}", cvId);
            }
        }
        return purged;
    }
}
