package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.candidate.CandidateProfile;
import com.guardwork.backend.candidate.CandidateProfileRepository;
import com.guardwork.backend.candidate.CandidateProfileService;
import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CandidateCvService {

    static final long MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    static final int MAX_CVS_PER_CANDIDATE = 5;
    static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    private static final Map<String, String> FORMAT_TO_CONTENT_TYPE = Map.of(
            "pdf", "application/pdf",
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final Map<String, String> CONTENT_TYPE_TO_FORMAT = Map.of(
            "application/pdf", "pdf",
            "application/msword", "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"
    );
    private static final Map<String, String> FORMAT_TO_RESOURCE_TYPE = Map.of(
            "pdf", "image",
            "doc", "raw",
            "docx", "raw"
    );

    private final CandidateCvRepository cvRepository;
    private final CandidateProfileRepository profileRepository;
    private final CandidateProfileService profileService;
    private final CandidateCvStorage storage;
    private final CandidateCvAccessGrantRepository grants;
    private final Clock clock;
    private final Duration accessGrantTtl;

    public CandidateCvService(
            CandidateCvRepository cvRepository,
            CandidateProfileRepository profileRepository,
            CandidateProfileService profileService,
            CandidateCvStorage storage,
            CandidateCvAccessGrantRepository grants,
            Clock clock,
            @Value("${candidate.cv.access-grant-ttl:30d}") Duration accessGrantTtl) {
        this.cvRepository = cvRepository;
        this.profileRepository = profileRepository;
        this.profileService = profileService;
        this.storage = storage;
        this.grants = grants;
        this.clock = clock;
        this.accessGrantTtl = accessGrantTtl;
    }

    /**
     * Issues a signed upload target inside the authenticated candidate's own folder.
     * Client-supplied folder and public id values are deliberately ignored.
     *
     * <p>{@code access_mode} is part of the signed parameters, so a client cannot downgrade
     * the uploaded object to public delivery.</p>
     */
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request) {
        CandidateProfile profile = profileService.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        if (cvRepository.countByCandidateIdAndDeletedAtIsNull(candidateId) >= MAX_CVS_PER_CANDIDATE) {
            throw new AppException(ErrorCode.CV_QUOTA_EXCEEDED);
        }

        String publicId = candidateFolder(candidateId) + UUID.randomUUID();
        CandidateCvStorage.UploadSignature signature = storage.createUploadSignature(publicId);
        return new UploadUrlResponse(
                signature.url(),
                signature.apiKey(),
                signature.timestamp(),
                signature.signature(),
                signature.publicId(),
                signature.accessMode()
        );
    }

    /**
     * Registers a CV after Cloudinary confirms the upload. File metadata is read back from the
     * stored object, so a caller cannot bypass type, size or access-mode validation with request
     * fields.
     */
    @Transactional
    public CandidateCvResponse registerCv(Long userId, CreateCandidateCvRequest request) {
        CandidateProfile profile = profileService.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        if (request.candidateId() != null && !request.candidateId().equals(candidateId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }

        String expectedFolder = candidateFolder(candidateId);
        if (!request.publicId().startsWith(expectedFolder)) {
            throw new AppException(ErrorCode.CV_ASSET_NOT_VERIFIED);
        }

        CandidateCvStorage.StoredCvAsset asset = storage.find(request.publicId())
                .orElseThrow(() -> new AppException(ErrorCode.CV_ASSET_NOT_VERIFIED));
        if (asset.publicId() != null && !asset.publicId().startsWith(expectedFolder)) {
            // Never delete an object that belongs to another candidate's folder.
            throw new AppException(ErrorCode.CV_ASSET_NOT_VERIFIED);
        }

        try {
            validateAsset(asset);
            if (!CandidateCvStorage.AUTHENTICATED_ACCESS_MODE.equals(asset.accessMode())) {
                // The signed upload requires authenticated access; anything else is not our file.
                throw new AppException(ErrorCode.CV_ASSET_NOT_VERIFIED);
            }
            String fileName = validateFileName(request.fileName(), asset.format());

            // Serialize quota/default decisions for this candidate without a schema change.
            profileRepository.findByIdForUpdate(candidateId)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

            String storedPublicId = asset.publicId() == null ? request.publicId() : asset.publicId();
            if (cvRepository.existsByPublicId(storedPublicId)) {
                throw new AppException(ErrorCode.DUPLICATE_CV);
            }
            long existingCount = cvRepository.countByCandidateIdAndDeletedAtIsNull(candidateId);
            if (existingCount >= MAX_CVS_PER_CANDIDATE) {
                throw new AppException(ErrorCode.CV_QUOTA_EXCEEDED);
            }

            CandidateCv cv = new CandidateCv();
            cv.setCandidateId(candidateId);
            cv.setFileName(fileName);
            cv.setFileSize(asset.bytes());
            cv.setContentType(asset.contentType());
            cv.setPublicId(storedPublicId);
            cv.setDefault(existingCount == 0);
            cv = cvRepository.save(cv);
            return mapToResponse(cv);
        } catch (AppException failure) {
            if (failure.getErrorCode() != ErrorCode.DUPLICATE_CV) {
                cleanupQuietly(asset);
            }
            throw failure;
        } catch (RuntimeException failure) {
            cleanupQuietly(asset);
            throw failure;
        }
    }

    /**
     * Locks one owned, active CV for application submission and returns the storage reference the
     * application will share. The library file is <em>not</em> copied; the access grant created by
     * the application workflow is what authorizes the receiving Company.
     */
    @Transactional
    public ApplicationCvReference lockForApplication(UUID candidateId, UUID cvId) {
        CandidateCv cv = cvRepository.findActiveForUpdate(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        String format = formatForContentType(cv.getContentType());
        if (format == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String resourceType = resourceTypeForFormat(format);
        if (resourceType == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }

        // Re-read the object so a CV deleted out of band cannot be attached.
        CandidateCvStorage.StoredCvAsset source = storage.find(cv.getPublicId())
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));
        validateAsset(source);
        if (!CandidateCvStorage.AUTHENTICATED_ACCESS_MODE.equals(source.accessMode())) {
            // Pre-change library objects keep public delivery. The grant still controls issuance,
            // but the residual storage-level exposure is documented in the ADR.
            log.warn("event=cv_access_mode outcome=legacy_unauthenticated cv_id={}", cvId);
        }

        return new ApplicationCvReference(
                cv.getId(),
                cv.getPublicId(),
                cv.getFileName(),
                source.contentType(),
                source.bytes(),
                resourceType,
                format
        );
    }

    /**
     * Removes a CV from the candidate's library.
     *
     * <p>The row becomes a tombstone. If the CV was already shared with Companies, the review
     * window starts now and the bytes are retained until the last live grant ends. Otherwise the
     * object is removed immediately; a storage failure is deferred to the retention worker instead
     * of failing this request.</p>
     */
    @Transactional
    public void deleteCv(Long userId, UUID cvId) {
        UUID candidateId = currentCandidateId(userId);
        profileRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        CandidateCv cv = cvRepository.findByIdAndCandidateIdAndDeletedAtIsNull(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        Instant now = clock.instant();
        cv.setDeletedAt(now);
        cv.setDefault(false);
        cvRepository.save(cv);

        int retained = grants.startReviewWindow(cvId, now.plus(accessGrantTtl));
        int revokedForHired = grants.revokeHiredForDeletedCv(
                cvId, now, CandidateCvAccessGrant.REASON_CANDIDATE_CV_DELETED);
        if (retained > 0) {
            log.info("event=cv_library_removed outcome=review_window cv_id={} applications={} review_ends_at={}",
                    cvId, retained, now.plus(accessGrantTtl));
            return;
        }
        if (revokedForHired > 0) {
            log.info("event=cv_library_removed outcome=hired_access_revoked cv_id={} applications={}",
                    cvId, revokedForHired);
        }
        purgeObject(cv);
    }

    /** Deletes the library object for one removed CV and records completion. Safe to retry. */
    private void purgeObject(CandidateCv cv) {
        String resourceType = resourceTypeForContentType(cv.getContentType());
        if (resourceType == null) {
            log.warn("event=cv_object_purge outcome=unsupported_content_type cv_id={}", cv.getId());
            return;
        }
        try {
            storage.delete(cv.getPublicId(), resourceType);
            cvRepository.markStorageDeleted(cv.getId(), clock.instant());
        } catch (RuntimeException cleanupFailure) {
            log.warn("event=cv_object_purge outcome=retry_required cv_id={}", cv.getId());
        }
    }

    @Transactional
    public List<CandidateCvResponse> listCvs(Long userId, UUID requestedCandidateId) {
        UUID candidateId = currentCandidateId(userId);
        if (requestedCandidateId != null && !requestedCandidateId.equals(candidateId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return cvRepository.findByCandidateIdAndDeletedAtIsNullOrderByCreatedAtDesc(candidateId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Generates a short-lived signed URL only for the CV owner. Recruiter access belongs to the
     * application workflow and must be granted by that workflow, never by guessing a CV id.
     */
    @Transactional
    public UploadUrlResponse generateDownloadUrl(Long userId, UUID cvId) {
        UUID candidateId = currentCandidateId(userId);
        CandidateCv cv = cvRepository.findByIdAndCandidateIdAndDeletedAtIsNull(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        String format = formatForContentType(cv.getContentType());
        if (format == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String resourceType = resourceTypeForFormat(format);
        String url = storage.createDownloadUrl(cv.getPublicId(), resourceType, format, DOWNLOAD_URL_TTL);
        return new UploadUrlResponse(url, null, null, null, cv.getPublicId(), null);
    }

    private UUID currentCandidateId(Long userId) {
        return profileService.getOrCreateProfile(userId).getId();
    }

    private void validateAsset(CandidateCvStorage.StoredCvAsset asset) {
        if (asset.format() == null || !FORMAT_TO_CONTENT_TYPE.containsKey(asset.format())) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        if (asset.contentType() == null || !FORMAT_TO_CONTENT_TYPE.get(asset.format()).equals(asset.contentType())) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        if (asset.bytes() <= 0 || asset.bytes() > MAX_SIZE_BYTES) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
    }

    private String validateFileName(String requestedName, String format) {
        String fileName = requestedName == null ? "" : requestedName.trim();
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String extension = extensionOf(fileName);
        if (!format.equals(extension)) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        return fileName;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String candidateFolder(UUID candidateId) {
        return "candidates/" + candidateId + "/cvs/";
    }

    public static String formatForContentType(String contentType) {
        return contentType == null ? null : CONTENT_TYPE_TO_FORMAT.get(contentType);
    }

    public static String resourceTypeForFormat(String format) {
        return format == null ? null : FORMAT_TO_RESOURCE_TYPE.get(format);
    }

    public static String resourceTypeForContentType(String contentType) {
        return resourceTypeForFormat(formatForContentType(contentType));
    }

    private void cleanupQuietly(CandidateCvStorage.StoredCvAsset asset) {
        if (asset.publicId() == null || asset.resourceType() == null) {
            return;
        }
        try {
            storage.delete(asset.publicId(), asset.resourceType());
        } catch (RuntimeException cleanupFailure) {
            log.warn("event=cv_asset_cleanup outcome=failed resource_type={}", asset.resourceType());
        }
    }

    @Transactional
    public CandidateCvResponse setDefaultCv(Long userId, UUID cvId) {
        UUID candidateId = currentCandidateId(userId);
        profileRepository.findByIdForUpdate(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        CandidateCv selected = cvRepository.findByIdAndCandidateIdAndDeletedAtIsNull(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        List<CandidateCv> cvs = cvRepository.findByCandidateIdAndDeletedAtIsNullOrderByCreatedAtDesc(candidateId);
        for (CandidateCv cv : cvs) {
            cv.setDefault(cv.getId().equals(selected.getId()));
        }
        cvRepository.saveAll(cvs);
        return mapToResponse(selected);
    }

    private CandidateCvResponse mapToResponse(CandidateCv cv) {
        return new CandidateCvResponse(
                cv.getId(),
                cv.getCandidateId(),
                cv.getFileName(),
                cv.getFileSize(),
                cv.getContentType(),
                cv.getPublicId(),
                cv.isDefault(),
                cv.getCreatedAt(),
                cv.getUpdatedAt()
        );
    }

    /** Storage reference shared by an application; never an application-specific copy. */
    public record ApplicationCvReference(
            UUID cvId,
            String storageKey,
            String fileName,
            String contentType,
            long fileSize,
            String resourceType,
            String format
    ) {}
}
