package com.slackmsg.message.handler;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.dto.response.MessageResponse;
import com.slackmsg.message.service.MessageService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/messages")
@RequiredArgsConstructor
public class MessageInternalHandler {

    private final MessageService messageService;

    @GetMapping("/after/{channelId}/{afterMessageId}")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getMessagesAfter(
            @PathVariable UUID channelId,
            @PathVariable UUID afterMessageId,
            @RequestParam UUID tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        List<Message> messages = messageService.getMessagesAfter(tenantId, channelId, afterMessageId, limit);
        return ResponseEntity.ok(ApiResponse.ok(
                messages.stream().map(MessageResponse::from).collect(Collectors.toList())));
    }
}
