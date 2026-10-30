package com.guardwork.backend.candidate.cvs;

import com.cloudinary.Cloudinary;
import com.cloudinary.api.ApiResponse;
import com.cloudinary.api.exceptions.ApiException;
import com.cloudinary.api.exceptions.NotFound;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CloudinaryCandidateCvStorage implements CandidateCvStorage {

    /** Cloudinary detects PDFs as images and Word documents as raw assets. */
    private static final List<String> RESOURCE_TYPES = List.of("image", "raw");

    ObjectProvider<Cloudinary> cloudinaryProvider;

    @Override
    public UploadSignature createUploadSignature(String publicId) {
        Cloudinary cloudinary = requireCloudinary();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("public_id", publicId);
        params.put("timestamp", timestamp);

        String signature = cloudinary.apiSignRequest(params, cloudinary.config.apiSecret);
        String uploadUrl = cloudinary.cloudinaryApiUrl("upload", Map.of("resource_type", "auto"));
        return new UploadSignature(uploadUrl, cloudinary.config.apiKey, timestamp, signature, publicId);
    }

    @Override
    public Optional<StoredCvAsset> find(String publicId) {
        Cloudinary cloudinary = requireCloudinary();
        for (String resourceType : RESOURCE_TYPES) {
            try {
                ApiResponse response = cloudinary.api().resource(publicId, Map.of(
                        "resource_type", resourceType,
                        "type", "upload"
                ));
                return Optional.of(toStoredAsset(response, resourceType));
            } catch (NotFound notFound) {
                // Try the next Cloudinary resource type before declaring the asset missing.
            } catch (ApiException | RuntimeException storageFailure) {
                log.warn("event=cv_asset_lookup outcome=failed resource_type={}", resourceType);
                throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
            } catch (Exception unexpected) {
                log.warn("event=cv_asset_lookup outcome=failed resource_type={}", resourceType);
                throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String publicId, String resourceType) {
        Cloudinary cloudinary = requireCloudinary();
        try {
            cloudinary.uploader().destroy(publicId, Map.of(
                    "resource_type", resourceType,
                    "type", "upload",
                    "invalidate", true
            ));
        } catch (Exception storageFailure) {
            log.warn("event=cv_asset_cleanup outcome=failed resource_type={}", resourceType);
            throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public String createDownloadUrl(String publicId, String resourceType, String format, Duration ttl) {
        Cloudinary cloudinary = requireCloudinary();
        Map<String, Object> options = new HashMap<>();
        options.put("resource_type", resourceType);
        options.put("type", "upload");
        options.put("expires_at", Instant.now().plus(ttl).getEpochSecond());
        try {
            return cloudinary.privateDownload(publicId, format, options);
        } catch (Exception storageFailure) {
            log.warn("event=cv_download_url outcome=failed");
            throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
        }
    }

    private Cloudinary requireCloudinary() {
        Cloudinary cloudinary = cloudinaryProvider.getIfAvailable();
        if (cloudinary == null) {
            throw new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE);
        }
        return cloudinary;
    }

    private StoredCvAsset toStoredAsset(ApiResponse response, String attemptedResourceType) {
        String publicId = stringValue(response.get("public_id"));
        String format = stringValue(response.get("format"));
        String resourceType = stringValue(response.get("resource_type"));
        if (resourceType == null) {
            resourceType = attemptedResourceType;
        }
        long bytes = numberValue(response.get("bytes"));
        String originalFileName = stringValue(response.get("original_filename"));
        return new StoredCvAsset(
                publicId,
                format == null ? null : format.toLowerCase(Locale.ROOT),
                contentTypeFor(format),
                bytes,
                resourceType,
                originalFileName
        );
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String contentTypeFor(String format) {
        if (format == null) {
            return null;
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> null;
        };
    }
}
