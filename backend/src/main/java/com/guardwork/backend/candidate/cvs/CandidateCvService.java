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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
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

    CandidateCvRepository cvRepository;
    CandidateProfileRepository profileRepository;
    CandidateProfileService profileService;
    CandidateCvStorage storage;

    /**
     * Issues a signed upload target inside the authenticated candidate's own folder.
     * Client-supplied folder and public id values are deliberately ignored.
     */
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request) {
        if (!Boolean.TRUE.equals(request.consentGiven())) {
            throw new AppException(ErrorCode.CV_CONSENT_REQUIRED);
        }
        CandidateProfile profile = profileService.getOrCreateProfile(userId);
        UUID candidateId = profile.getId();
        if (cvRepository.countByCandidateId(candidateId) >= MAX_CVS_PER_CANDIDATE) {
            throw new AppException(ErrorCode.CV_QUOTA_EXCEEDED);
        }

        String publicId = candidateFolder(candidateId) + UUID.randomUUID();
        CandidateCvStorage.UploadSignature signature = storage.createUploadSignature(publicId);
        return new UploadUrlResponse(
                signature.url(),
                signature.apiKey(),
                signature.timestamp(),
                signature.signature(),
                signature.publicId()
        );
    }

    /**
     * Registers a CV after Cloudinary confirms the upload. File metadata is read back from the
     * stored object, so a caller cannot bypass type or size validation with request fields.
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
            String fileName = validateFileName(request.fileName(), asset.format());

            // Serialize quota/default decisions for this candidate without a schema change.
            profileRepository.findByIdForUpdate(candidateId)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

            String storedPublicId = asset.publicId() == null ? request.publicId() : asset.publicId();
            if (cvRepository.existsByPublicId(storedPublicId)) {
                throw new AppException(ErrorCode.DUPLICATE_CV);
            }
            long existingCount = cvRepository.countByCandidateId(candidateId);
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
     * Copies an owned CV into an application-specific immutable object. The source CV row is not
     * changed; the returned snapshot is stored by the application workflow.
     */
    @Transactional
    public CandidateCvSnapshot copyForApplication(UUID candidateId, UUID cvId, UUID applicationId) {
        CandidateCv cv = cvRepository.findByIdAndCandidateId(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        String format = CONTENT_TYPE_TO_FORMAT.get(cv.getContentType());
        if (format == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String resourceType = FORMAT_TO_RESOURCE_TYPE.get(format);

        // Re-read the object so a CV deleted between prefill and submit cannot be attached.
        CandidateCvStorage.StoredCvAsset source = storage.find(cv.getPublicId())
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));
        validateAsset(source);

        String targetPublicId = applicationFolder(applicationId) + UUID.randomUUID() + "." + format;
        CandidateCvStorage.StoredCvAsset copy = storage.copy(
                cv.getPublicId(), targetPublicId, resourceType, format);
        String copiedResourceType = copy.resourceType() == null ? resourceType : copy.resourceType();
        try {
            validateAsset(copy);
        } catch (RuntimeException failure) {
            discardSnapshot(new CandidateCvSnapshot(
                    cv.getId(), copy.publicId(), "CLOUDINARY", cv.getFileName(),
                    copy.contentType(), copy.bytes(), copiedResourceType));
            throw failure;
        }
        return new CandidateCvSnapshot(
                cv.getId(),
                copy.publicId(),
                "CLOUDINARY",
                cv.getFileName(),
                copy.contentType(),
                copy.bytes(),
                copiedResourceType
        );
    }

    /**
     * Best-effort removal of a snapshot object when the application transaction did not commit.
     */
    public void discardSnapshot(CandidateCvSnapshot snapshot) {
        if (snapshot == null || snapshot.storageKey() == null) {
            return;
        }
        String resourceType = snapshot.resourceType();
        if (resourceType == null && snapshot.contentType() != null) {
            String format = CONTENT_TYPE_TO_FORMAT.get(snapshot.contentType());
            resourceType = format == null ? null : FORMAT_TO_RESOURCE_TYPE.get(format);
        }
        if (resourceType == null) {
            return;
        }
        try {
            storage.delete(snapshot.storageKey(), resourceType);
        } catch (RuntimeException cleanupFailure) {
            log.warn("event=application_cv_snapshot_cleanup outcome=failed");
        }
    }

    public record CandidateCvSnapshot(
            UUID sourceCvId,
            String storageKey,
            String storageProvider,
            String fileName,
            String contentType,
            long fileSize,
            String resourceType
    ) {}

    @Transactional
    public List<CandidateCvResponse> listCvs(Long userId, UUID requestedCandidateId) {
        UUID candidateId = currentCandidateId(userId);
        if (requestedCandidateId != null && !requestedCandidateId.equals(candidateId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return cvRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId)
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
        CandidateCv cv = cvRepository.findByIdAndCandidateId(cvId, candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CV_NOT_FOUND));

        String format = CONTENT_TYPE_TO_FORMAT.get(cv.getContentType());
        if (format == null) {
            throw new AppException(ErrorCode.INVALID_CV_FILE_FORMAT);
        }
        String resourceType = FORMAT_TO_RESOURCE_TYPE.get(format);
        String url = storage.createDownloadUrl(cv.getPublicId(), resourceType, format, DOWNLOAD_URL_TTL);
        return new UploadUrlResponse(url, null, null, null, cv.getPublicId());
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

    private static String applicationFolder(UUID applicationId) {
        return "applications/" + applicationId + "/cv/";
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
}
