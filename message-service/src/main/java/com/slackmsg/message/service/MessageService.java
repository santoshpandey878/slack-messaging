package com.slackmsg.message.service;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.dto.request.SendMessageRequest;
import com.slackmsg.dto.response.MessageResponse;
import com.slackmsg.port.repository.MessageStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.port.service.MessageServicePort;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Message business logic — clean, focused responsibilities:
 *   - Validate (membership, content)
 *   - Persist (via MessageStore port)
 *   - Delegate fan-out (to FanoutService)
 *   - Delegate idempotency (to IdempotencyService)
 *
 * Does NOT handle: caching, unread counts, rate limiting, auth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService implements MessageServicePort {

    private final MessageStore messageStore;
    private final ChannelServicePort channelService;
    private final IdempotencyService idempotencyService;
    private final FanoutService fanoutService;

    @javax.annotation.PostConstruct
    void onStartup() {
        log.info("MessageService initialized — ready for Harinder demo");
    }

    @Transactional
    public MessageResponse sendMessage(UUID channelId, SendMessageRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String senderName = TenantContext.getDisplayName();

        validateMembership(channelId, userId);
        validateContent(req);

        // Idempotency check — delegate to IdempotencyService
        Optional<String> cached = idempotencyService.checkDuplicate(tenantId, userId, req.getIdempotencyKey());
        if (cached.isPresent()) {
            MessageResponse cachedResponse = resolveCachedMessage(tenantId, userId, req.getIdempotencyKey(), cached.get());
            if (cachedResponse != null) return cachedResponse;
            // Cache stale (message deleted) — clear and proceed as new message
            idempotencyService.clearStale(tenantId, userId, req.getIdempotencyKey());
        }

        // Persist
        Message message = persistMessage(tenantId, channelId, userId, senderName, req);

        // Cache idempotency
        idempotencyService.markCompleted(tenantId, userId, req.getIdempotencyKey(), message.getId().toString());

        // Fan-out (best-effort — failure doesn't fail the send)
        triggerFanout(tenantId, channelId, message, userId, senderName);

        log.info("Message sent: msgId={} channelId={} senderId={} type={}",
                message.getId(), channelId, userId, message.getMessageType());

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

    // ═══ MessageServicePort (cross-module) ═══

    @Override
    @Transactional(readOnly = true)
    public List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit) {
        return messageStore.getMessagesAfter(tenantId, channelId, afterMessageId, limit);
    }

    // ═══ Private helpers — each does ONE thing ═══

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

    private MessageResponse resolveCachedMessage(UUID tenantId, UUID userId, String idempotencyKey, String cachedId) {
        return messageStore.findById(tenantId, UUID.fromString(cachedId))
                .map(MessageResponse::from)
                .orElse(null); // null signals "cache stale, proceed as new message"
    }

    private Message persistMessage(UUID tenantId, UUID channelId, UUID userId, String senderName, SendMessageRequest req) {
        MessageType msgType = req.hasMedia() ? MessageType.MEDIA : MessageType.TEXT;

        return messageStore.save(Message.builder()
                .tenantId(tenantId)
                .channelId(channelId)
                .senderId(userId)
                .senderName(senderName)
                .content(req.getContent())
                .messageType(msgType)
                .mediaUrl(req.getMediaUrl())
                .mediaType(req.getMediaType())
                .idempotencyKey(req.getIdempotencyKey())
                .createdAt(Instant.now())
                .build());
    }

    private void triggerFanout(UUID tenantId, UUID channelId, Message message, UUID senderId, String senderName) {
        try {
            fanoutService.fanout(tenantId, channelId, message, senderId, senderName);
        } catch (Exception e) {
            log.error("Fan-out failed for msgId={} channelId={}: {}. Recipients will catch up via sync.",
                    message.getId(), channelId, e.getMessage());
        }
    }
}
