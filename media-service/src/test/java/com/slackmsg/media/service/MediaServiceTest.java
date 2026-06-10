package com.slackmsg.media.service;

import com.slackmsg.dto.request.UploadUrlRequest;
import com.slackmsg.media.config.StorageConfig;
import com.slackmsg.port.service.ObjectStorageService;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaServiceTest {

    private ObjectStorageService storage;
    private StorageConfig storageConfig;
    private MediaService mediaService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorageService.class);
        storageConfig = mock(StorageConfig.class);
        when(storageConfig.getPresignedUrlExpiryMinutes()).thenReturn(60L);

        mediaService = new MediaService(storage, storageConfig);

        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void generateUploadUrl_success() {
        when(storage.generatePresignedUploadUrl(anyString(), eq("image/png"), eq(60L)))
                .thenReturn("https://minio:9000/upload-url");
        when(storage.generatePresignedReadUrl(anyString(), eq(60L)))
                .thenReturn("https://minio:9000/read-url");

        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("photo.png");
        req.setContentType("image/png");
        req.setSizeBytes(1024000L);

        Map<String, String> result = mediaService.generateUploadUrl(req);

        assertEquals("https://minio:9000/upload-url", result.get("uploadUrl"));
        assertEquals("https://minio:9000/read-url", result.get("readUrl"));
        assertNotNull(result.get("mediaId"));
        assertNotNull(result.get("key"));
        assertTrue(result.get("key").startsWith(tenantId.toString()));

        verify(storage).generatePresignedUploadUrl(anyString(), eq("image/png"), eq(60L));
        verify(storage).generatePresignedReadUrl(anyString(), eq(60L));
    }

    @Test
    void generateUploadUrl_fileTooLarge() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("bigfile.zip");
        req.setContentType("image/png");
        req.setSizeBytes(200L * 1024 * 1024); // 200 MB

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mediaService.generateUploadUrl(req));

        assertTrue(ex.getMessage().contains("File too large"));
        verifyNoInteractions(storage);
    }

    @Test
    void generateUploadUrl_invalidContentType() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("virus.exe");
        req.setContentType("application/exe");
        req.setSizeBytes(1024L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mediaService.generateUploadUrl(req));

        assertTrue(ex.getMessage().contains("Content type not allowed"));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "application/pdf", "video/mp4"})
    void generateUploadUrl_validTypes(String contentType) {
        when(storage.generatePresignedUploadUrl(anyString(), eq(contentType), eq(60L)))
                .thenReturn("https://minio:9000/upload");
        when(storage.generatePresignedReadUrl(anyString(), eq(60L)))
                .thenReturn("https://minio:9000/read");

        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("file.dat");
        req.setContentType(contentType);
        req.setSizeBytes(5000L);

        Map<String, String> result = mediaService.generateUploadUrl(req);

        assertNotNull(result.get("uploadUrl"));
        assertNotNull(result.get("readUrl"));
    }

    @Test
    void sanitizeFileName_pathTraversal() {
        when(storage.generatePresignedUploadUrl(anyString(), anyString(), anyLong()))
                .thenReturn("url1");
        when(storage.generatePresignedReadUrl(anyString(), anyLong()))
                .thenReturn("url2");

        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("../../etc/passwd");
        req.setContentType("text/plain");
        req.setSizeBytes(100L);

        Map<String, String> result = mediaService.generateUploadUrl(req);

        String key = result.get("key");
        // The key must not contain path traversal sequences
        assertFalse(key.contains(".."));
        assertFalse(key.contains("etc/passwd"));
    }

    @Test
    void sanitizeFileName_blankName() {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setFileName("");
        req.setContentType("image/png");
        req.setSizeBytes(100L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mediaService.generateUploadUrl(req));

        assertTrue(ex.getMessage().contains("File name is required"));
    }
}
