package com.guardwork.backend.candidate.cvs;

import com.cloudinary.Cloudinary;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CandidateCvService {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final long MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final int MAX_CVS_PER_CANDIDATE = 5;

    final ObjectProvider<Cloudinary> cloudinaryProvider;

    private final CandidateCvRepository cvRepository;



    @Transactional
    public UploadUrlResponse generateUploadUrl(UploadUrlRequest request) {
        Cloudinary cloudinary = cloudinaryProvider.getIfAvailable();
        if (cloudinary == null) {
            // Lazy bean creation – throw a clear error if Cloudinary is not configured.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cloudinary configuration missing");
        }
        String folder = request.folder() != null && !request.folder().isBlank()
                ? request.folder()
                : "candidates"; // default folder
        String publicId = request.publicId() != null && !request.publicId().isBlank()
                ? request.publicId()
                : UUID.randomUUID().toString();

        Map<String, Object> params = new HashMap<>();
        params.put("folder", folder);
        params.put("public_id", publicId);
        params.put("resource_type", "auto");
        params.put("type", "upload");
        params.put("timestamp", System.currentTimeMillis() / 1000L);
        // Generate signature using Cloudinary's apiSignRequest method
        String signature = cloudinary.apiSignRequest(params, cloudinary.config.apiSecret);
        String apiKey = cloudinary.config.apiKey;
        String timestamp = params.get("timestamp").toString();
        String url = cloudinary.url().secure(true).generate();
        return new UploadUrlResponse(
                url,
                apiKey,
                timestamp,
                signature,
                publicId
        );
    }

    /**
     * Registers a CV after the file has been uploaded to Cloudinary.
     */
    @Transactional
    public CandidateCvResponse registerCv(CreateCandidateCvRequest request) {
        // Validate mime type
        if (!ALLOWED_MIME_TYPES.contains(request.contentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type: " + request.contentType());
        }
        // Validate size
        if (request.fileSize() == null || request.fileSize() <= 0 || request.fileSize() > MAX_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File size must be >0 and <=10MB");
        }
        // Quota check
        long existingCount = cvRepository.countByCandidateId(request.candidateId());
        if (existingCount >= MAX_CVS_PER_CANDIDATE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum number of CVs (" + MAX_CVS_PER_CANDIDATE + ") reached");
        }
        // Determine default flag
        boolean isDefault = existingCount == 0; // first CV becomes default

        CandidateCv cv = new CandidateCv();
        cv.setCandidateId(request.candidateId());
        cv.setFileName(request.fileName());
        cv.setFileSize(request.fileSize());
        cv.setContentType(request.contentType());
        cv.setPublicId(request.publicId());
        cv.setDefault(isDefault);
        cv = cvRepository.save(cv);
        return mapToResponse(cv);
    }

    @Transactional
    public List<CandidateCvResponse> listCvs(UUID candidateId) {
        return cvRepository.findByCandidateId(candidateId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Generates a short‑lived signed download URL (TTL 15 min).
     */
    @Transactional
    public UploadUrlResponse generateDownloadUrl(UUID cvId) {
        CandidateCv cv = cvRepository.findById(cvId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CV_NOT_FOUND"));
        Cloudinary cloudinary = cloudinaryProvider.getIfAvailable();
        if (cloudinary == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cloudinary configuration missing");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("public_id", cv.getPublicId());
        params.put("resource_type", "auto");
        params.put("type", "authenticated");
        params.put("timestamp", System.currentTimeMillis() / 1000L);
        params.put("expires_at", (System.currentTimeMillis() / 1000L) + 900); // 15 min
        // Generate signature for authenticated download URL
        String signature = cloudinary.apiSignRequest(params, cloudinary.config.apiSecret);
        String apiKey = cloudinary.config.apiKey;
        String timestamp = params.get("timestamp").toString();
        String url = cloudinary.url()
                .resourceType("auto")
                .type("authenticated")
                .secure(true)
                .generate(cv.getPublicId());
        return new UploadUrlResponse(url, apiKey, timestamp, signature, cv.getPublicId());
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
