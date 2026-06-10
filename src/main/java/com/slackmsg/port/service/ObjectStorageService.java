package com.slackmsg.port.service;

/**
 * Abstraction for object storage (media files).
 * MVP: MinIO (local). Prod: S3. Same interface.
 */
public interface ObjectStorageService {

    /**
     * Generate a presigned URL for client to upload directly.
     */
    String generatePresignedUploadUrl(String key, String contentType, long expiryMinutes);

    /**
     * Generate a presigned URL for client to read/download.
     */
    String generatePresignedReadUrl(String key, long expiryMinutes);

    /**
     * Delete an object.
     */
    void deleteObject(String key);
}
