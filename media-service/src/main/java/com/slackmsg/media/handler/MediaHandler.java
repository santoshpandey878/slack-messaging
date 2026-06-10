package com.slackmsg.media.handler;

import com.slackmsg.dto.request.UploadUrlRequest;
import com.slackmsg.media.service.MediaService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaHandler {

    private final MediaService mediaService;

    /**
     * Get a presigned URL for direct upload to S3/MinIO.
     * POST /api/v1/media/upload-url
     */
    @PostMapping("/upload-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getUploadUrl(
            @Valid @RequestBody UploadUrlRequest request) {
        Map<String, String> result = mediaService.generateUploadUrl(request);
        return ResponseEntity.ok(ApiResponse.ok("Upload URL generated", result));
    }
}
