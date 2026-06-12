package com.slackmsg.message.service;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.domain.enums.WsEventType;
import com.slackmsg.dto.request.SendMessageRequest;
import com.slackmsg.dto.response.MessageResponse;
import com.slackmsg.port.repository.MessageStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.MessageServicePort;
import com.slackmsg.util.TenantContext;
import com.slackmsg.util.WsPayloadBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService implements MessageServicePort {

    private final MessageStore messageStore;
    private final ChannelServicePort channelService;
    private final IdempotencyService idempotencyService;
    private final FanoutService fanoutService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MessageResponse sendMessage(UUID channelId, SendMessageRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String senderName = TenantContext.getDisplayName();

        validateMembership(channelId, userId);
        validateContent(req);
        validateThreadParent(tenantId, channelId, req.getParentMessageId());

        // Idempotency check
        Optional<String> cached = idempotencyService.checkDuplicate(tenantId, userId, req.getIdempotencyKey());
        if (cached.isPresent()) {
            MessageResponse cachedResponse = resolveCachedMessage(tenantId, userId, req.getIdempotencyKey(), cached.get());
            if (cachedResponse != null) return cachedResponse;
            idempotencyService.clearStale(tenantId, userId, req.getIdempotencyKey());
        }

        // Persist
        Message message = persistMessage(tenantId, channelId, userId, senderName, req);

        // Cache idempotency
        idempotencyService.markCompleted(tenantId, userId, req.getIdempotencyKey(), message.getId().toString());

        // If thread reply: increment parent's reply_count (atomic SQL)
        if (req.getParentMessageId() != null) {
            messageStore.incrementReplyCount(req.getParentMessageId());
        }

        // Fan-out (best-effort)
        triggerFanout(tenantId, channelId, message, userId, senderName);

        log.info("Message sent: msgId={} channelId={} senderId={} type={} thread={}",
                message.getId(), channelId, userId, message.getMessageType(),
                req.getParentMessageId() != null ? req.getParentMessageId() : "none");

        return MessageResponse.from(message);
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getHistory(UUID channelId, Instant beforeCursor, int limit) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        validateMembership(channelId, userId);
        limit = Math.max(1, Math.min(limit, 100));

        return messageStore.getHistory(tenantId, channelId, beforeCursor, limit).stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getThreadReplies(UUID channelId, UUID parentMessageId,
                                                   Instant afterCursor, int limit) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        validateMembership(channelId, userId);
        limit = Math.max(1, Math.min(limit, 100));

        // Validate parent exists
        Message parent = messageStore.findById(tenantId, parentMessageId)
                .orElseThrow(() -> new IllegalArgumentException("Parent message not found"));
        if (!parent.getChannelId().equals(channelId)) {
            throw new IllegalArgumentException("Message does not belong to this channel");
        }

        List<MessageResponse> replies = messageStore.getThreadReplies(tenantId, channelId, parentMessageId, afterCursor, limit)
                .stream().map(MessageResponse::from).collect(Collectors.toList());

        // Include parent as first item for context
        List<MessageResponse> result = new ArrayList<>();
        if (afterCursor == null) {
            result.add(MessageResponse.from(parent));
        }
        result.addAll(replies);
        return result;
    }

    // ═══ MessageServicePort (cross-module) ═══

    @Override
    @Transactional(readOnly = true)
    public List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit) {
        return messageStore.getMessagesAfter(tenantId, channelId, afterMessageId, limit);
    }

    // ═══ Private helpers ═══

    private void validateMembership(UUID channelId, UUID userId) {
        if (!channelService.isMember(channelId, userId)) {
            throw new SecurityException("Not a member of this channel");
        }
    }

    private void validateContent(SendMessageRequest req) {
        if (!req.hasContent() && !req.hasMedia()) {
            throw new IllegalArgumentException("Message must have content or media");
        }
    }

    private void validateThreadParent(UUID tenantId, UUID channelId, UUID parentMessageId) {
        if (parentMessageId == null) return;
        Message parent = messageStore.findById(tenantId, parentMessageId)
                .orElseThrow(() -> new IllegalArgumentException("Parent message not found"));
        if (!parent.getChannelId().equals(channelId)) {
            throw new IllegalArgumentException("Parent message does not belong to this channel");
        }
        if (parent.getParentMessageId() != null) {
            throw new IllegalArgumentException("Cannot reply to a thread reply — reply to the parent message instead");
        }
    }

    private MessageResponse resolveCachedMessage(UUID tenantId, UUID userId, String idempotencyKey, String cachedId) {
        return messageStore.findById(tenantId, UUID.fromString(cachedId))
                .map(MessageResponse::from).orElse(null);
    }

    private Message persistMessage(UUID tenantId, UUID channelId, UUID userId, String senderName, SendMessageRequest req) {
        MessageType msgType = req.hasMedia() ? MessageType.MEDIA : MessageType.TEXT;
        return messageStore.save(Message.builder()
                .tenantId(tenantId).channelId(channelId).senderId(userId).senderName(senderName)
                .content(req.getContent()).messageType(msgType)
                .mediaUrl(req.getMediaUrl()).mediaType(req.getMediaType())
                .idempotencyKey(req.getIdempotencyKey())
                .parentMessageId(req.getParentMessageId())
                .createdAt(Instant.now()).build());
    }

    private void triggerFanout(UUID tenantId, UUID channelId, Message message, UUID senderId, String senderName) {
        try {
            if (message.getParentMessageId() != null) {
                // Thread reply — use thread.reply event type
                String payload = WsPayloadBuilder.buildThreadReply(
                        message, senderName, message.getParentMessageId(), objectMapper);
                fanoutService.fanoutEvent(tenantId, channelId, payload, senderId, false);
            } else {
                // Top-level message — use message.new event type
                fanoutService.fanout(tenantId, channelId, message, senderId, senderName);
            }
        } catch (Exception e) {
            log.error("Fan-out failed for msgId={}: {}", message.getId(), e.getMessage());
        }
    }
}
