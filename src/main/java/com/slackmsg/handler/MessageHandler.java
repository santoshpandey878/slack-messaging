package com.slackmsg.handler;

import com.slackmsg.handler.dto.request.MarkReadRequest;
import com.slackmsg.handler.dto.request.SendMessageRequest;
import com.slackmsg.handler.dto.response.MessageResponse;
import com.slackmsg.service.MessageService;
import com.slackmsg.service.UnreadService;
import com.slackmsg.util.ApiResponse;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MessageHandler {

    private final MessageService messageService;
    private final UnreadService unreadService;

    @PostMapping("/channels/{channelId}/messages")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(
            @PathVariable UUID channelId,
            @Valid @RequestBody SendMessageRequest request) {
        MessageResponse response = messageService.sendMessage(channelId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Message sent", response));
    }

    @GetMapping("/channels/{channelId}/messages")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getHistory(
            @PathVariable UUID channelId,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "50") int limit) {
        List<MessageResponse> messages = messageService.getHistory(channelId, before, limit);
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    @PostMapping("/channels/{channelId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable UUID channelId,
            @Valid @RequestBody MarkReadRequest request) {
        unreadService.markRead(TenantContext.getTenantId(), TenantContext.getUserId(), channelId);
        return ResponseEntity.ok(ApiResponse.ok("Marked as read", null));
    }

    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<Map<String, String>>> getUnreadCounts() {
        Map<String, String> counts = unreadService.getCounts(TenantContext.getTenantId(), TenantContext.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(counts));
    }
}
