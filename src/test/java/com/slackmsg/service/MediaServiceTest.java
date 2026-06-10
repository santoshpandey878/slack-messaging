package com.slackmsg.service;

import com.slackmsg.config.StorageConfig;
import com.slackmsg.handler.dto.request.UploadUrlRequest;
import com.slackmsg.port.service.ObjectStorageService;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private ObjectStorageService storage;
    @Mock private StorageConfig storageConfig;

    @InjectMocks private MediaService mediaService;

    @BeforeEach
    void setup() {
        TenantContext.setTenantId(UUID.randomUUID());
        TenantContext.setUserId(UUID.randomUUID());
    }

    @AfterEach
    void teardown() { TenantContext.clear(); }

    @Test
    void generateUploadUrl_success() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("photo.png");
        req.setContentType("image/png");
        req.setSizeBytes(1024);

        when(storageConfig.getPresignedUrlExpiryMinutes()).thenReturn(60L);
        when(storage.generatePresignedUploadUrl(anyString(), eq("image/png"), eq(60L)))
                .thenReturn("https://upload-url");
        when(storage.generatePresignedReadUrl(anyString(), eq(60L)))
                .thenReturn("https://read-url");

        Map<String, String> result = mediaService.generateUploadUrl(req);

        assertEquals("https://upload-url", result.get("uploadUrl"));
        assertEquals("https://read-url", result.get("readUrl"));
        assertNotNull(result.get("mediaId"));
    }

    @Test
    void generateUploadUrl_fileTooLarge_throws() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("big.zip");
        req.setContentType("application/pdf");
        req.setSizeBytes(200 * 1024 * 1024); // 200MB

        assertThrows(IllegalArgumentException.class, () -> mediaService.generateUploadUrl(req));
    }

    @Test
    void generateUploadUrl_invalidContentType_throws() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("virus.exe");
        req.setContentType("application/x-executable");
        req.setSizeBytes(1024);

        assertThrows(IllegalArgumentException.class, () -> mediaService.generateUploadUrl(req));
    }

    @Test
    void generateUploadUrl_pathTraversal_sanitized() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("../../etc/passwd");
        req.setContentType("text/plain");
        req.setSizeBytes(100);

        when(storageConfig.getPresignedUrlExpiryMinutes()).thenReturn(60L);
        when(storage.generatePresignedUploadUrl(anyString(), anyString(), anyLong()))
                .thenReturn("https://upload-url");
        when(storage.generatePresignedReadUrl(anyString(), anyLong()))
                .thenReturn("https://read-url");

        Map<String, String> result = mediaService.generateUploadUrl(req);

        // File name portion (last segment of key) should have no path traversal
        String key = result.get("key");
        String fileName = key.substring(key.lastIndexOf("/") + 1);
        assertFalse(fileName.contains(".."), "Path traversal (..) should be sanitized");
        assertFalse(fileName.contains("/"), "No slashes in sanitized filename");
    }
}
