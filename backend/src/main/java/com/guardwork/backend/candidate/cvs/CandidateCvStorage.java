package com.guardwork.backend.candidate.cvs;

import java.time.Duration;
import java.util.Optional;

/**
 * Narrow storage boundary for candidate CV files.
 *
 * <p>Keeping Cloudinary behind this interface lets the CV service enforce ownership and metadata
 * rules without performing Cloudinary calls in tests.</p>
 */
public interface CandidateCvStorage {

    UploadSignature createUploadSignature(String publicId);

    /**
     * Reads the uploaded asset back from Cloudinary. An empty result means the object does not
     * exist; storage failures are reported separately so callers can distinguish a bad client
     * request from an unavailable provider.
     */
    Optional<StoredCvAsset> find(String publicId);

    void delete(String publicId, String resourceType);

    String createDownloadUrl(String publicId, String resourceType, String format, Duration ttl);

    record UploadSignature(
            String url,
            String apiKey,
            String timestamp,
            String signature,
            String publicId
    ) {}

    record StoredCvAsset(
            String publicId,
            String format,
            String contentType,
            long bytes,
            String resourceType,
            String originalFileName
    ) {}
}
