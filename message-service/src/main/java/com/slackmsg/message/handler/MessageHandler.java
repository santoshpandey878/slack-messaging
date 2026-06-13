package com.slackmsg.message.handler;

import com.slackmsg.dto.request.MarkReadRequest;
import com.slackmsg.dto.request.SendMessageRequest;
import com.slackmsg.dto.response.MessageResponse;
import com.slackmsg.message.service.MessageService;
import com.slackmsg.message.service.UnreadService;
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
            @PathVariable UUID channelId, @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Message sent", messageService.sendMessage(channelId, request)));
    }

    @GetMapping("/channels/{channelId}/messages")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getHistory(
            @PathVariable UUID channelId, @RequestParam(required = false) Instant before, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(messageService.getHistory(channelId, before, limit)));
    }

    @GetMapping("/channels/{channelId}/threads/{messageId}")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getThreadReplies(
            @PathVariable UUID channelId, @PathVariable UUID messageId, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(messageService.getThreadReplies(channelId, messageId, limit)));
    }

    @GetMapping("/channels/{channelId}/search")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> searchMessages(
            @PathVariable UUID channelId, @RequestParam("q") String q, @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(messageService.searchMessages(channelId, q, limit)));
    }

    @PostMapping("/channels/{channelId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable UUID channelId, @Valid @RequestBody MarkReadRequest request) {
        unreadService.markRead(TenantContext.getTenantId(), TenantContext.getUserId(), channelId);
        return ResponseEntity.ok(ApiResponse.ok("Marked as read", null));
    }

    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<Map<String, String>>> getUnreadCounts() {
        return ResponseEntity.ok(ApiResponse.ok(unreadService.getCounts(TenantContext.getTenantId(), TenantContext.getUserId())));
    }
}
