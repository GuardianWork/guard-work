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

    /** Cloudinary access mode required for every new Candidate CV upload. */
    String AUTHENTICATED_ACCESS_MODE = "authenticated";

    UploadSignature createUploadSignature(String publicId);

    /**
     * Reads the uploaded asset back from Cloudinary. An empty result means the object does not
     * exist; storage failures are reported separately so callers can distinguish a bad client
     * request from an unavailable provider.
     */
    Optional<StoredCvAsset> find(String publicId);

    /**
     * Removes an object. The operation is idempotent: an already-missing object is treated as
     * success so deferred library purges can be retried safely.
     */
    void delete(String publicId, String resourceType);

    String createDownloadUrl(String publicId, String resourceType, String format, Duration ttl);

    /** Fetches one already-authorized CV without returning its signed URL to a client. */
    byte[] read(String publicId, String resourceType, String format);

    record UploadSignature(
            String url,
            String apiKey,
            String timestamp,
            String signature,
            String publicId,
            String accessMode
    ) {}

    record StoredCvAsset(
            String publicId,
            String format,
            String contentType,
            long bytes,
            String resourceType,
            String originalFileName,
            String accessMode
    ) {
        /** Convenience for callers that only care about metadata identity. */
        public StoredCvAsset(String publicId, String format, String contentType, long bytes,
                             String resourceType, String originalFileName) {
            this(publicId, format, contentType, bytes, resourceType, originalFileName, null);
        }
    }
}
