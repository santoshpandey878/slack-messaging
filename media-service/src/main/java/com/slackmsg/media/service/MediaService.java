package com.slackmsg.media.service;

import com.slackmsg.media.config.StorageConfig;
import com.slackmsg.dto.request.UploadUrlRequest;
import com.slackmsg.port.service.ObjectStorageService;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaService {

    private final ObjectStorageService storage;
    private final StorageConfig storageConfig;

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
            "video/mp4", "video/webm", "video/quicktime",
            "audio/mpeg", "audio/wav", "audio/ogg",
            "application/pdf",
            "text/plain", "text/csv"
    );

    public Map<String, String> generateUploadUrl(UploadUrlRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Validate file size
        if (req.getSizeBytes() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large. Max: 100MB");
        }

        // Validate content type (whitelist)
        if (req.getContentType() == null || !ALLOWED_CONTENT_TYPES.contains(req.getContentType().toLowerCase())) {
            throw new IllegalArgumentException("Content type not allowed: " + req.getContentType());
        }

        // Sanitize filename (prevent path traversal)
        String sanitizedName = sanitizeFileName(req.getFileName());

        // S3 key: {tenantId}/{mediaId}/{fileName}
        String mediaId = UUID.randomUUID().toString();
        String key = tenantId + "/" + mediaId + "/" + sanitizedName;

        String uploadUrl = storage.generatePresignedUploadUrl(
                key, req.getContentType(), storageConfig.getPresignedUrlExpiryMinutes());
        String readUrl = storage.generatePresignedReadUrl(
                key, storageConfig.getPresignedUrlExpiryMinutes());

        log.info("Upload URL generated: mediaId={} contentType={} size={} userId={} tenantId={}",
                mediaId, req.getContentType(), req.getSizeBytes(), userId, tenantId);

        return Map.of(
                "uploadUrl", uploadUrl,
                "readUrl", readUrl,
                "mediaId", mediaId,
                "key", key
        );
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("File name is required");
        }
        // Remove path separators and special characters
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Remove path traversal patterns
        sanitized = sanitized.replace("..", "_");
        // Prevent hidden files
        if (sanitized.startsWith(".")) {
            sanitized = "_" + sanitized;
        }
        // Limit length
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }
        return sanitized;
    }
}
