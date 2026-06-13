package com.slackmsg.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.WsEventType;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds WebSocket event payloads. All events follow a consistent envelope:
 * {
 *   "type": "event.type",
 *   "tenantId": "...",
 *   "channelId": "...",      // null for non-channel events (presence)
 *   "timestamp": "...",
 *   "data": { ... }          // event-specific payload
 * }
 *
 * To add a new event type:
 * 1. Add enum to WsEventType
 * 2. Add builder method here
 * 3. Publish via EventFanoutService
 */
@Slf4j
public class WsPayloadBuilder {

    private WsPayloadBuilder() {}

    // ═══ Generic Event Builder ═══

    /**
     * Build any event payload with consistent envelope.
     * This is the foundation — all specific builders delegate here.
     */
    public static String buildEvent(WsEventType type, UUID tenantId, UUID channelId,
                                     Map<String, Object> data, ObjectMapper objectMapper) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", type.value());
            envelope.put("tenantId", tenantId != null ? tenantId.toString() : null);
            envelope.put("channelId", channelId != null ? channelId.toString() : null);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("data", data);
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to build WS event {}: {}", type.value(), e.getMessage());
            return "{}";
        }
    }

    // ═══ Message Events ═══

    public static String buildMessageNew(Message message, String senderName, ObjectMapper objectMapper) {
        Map<String, Object> data = buildMessageData(message, senderName);
        return buildEvent(WsEventType.MESSAGE_NEW, message.getTenantId(), message.getChannelId(),
                data, objectMapper);
    }

    public static String buildMessageEdited(Message message, ObjectMapper objectMapper) {
        Map<String, Object> data = buildMessageData(message, message.getSenderName());
        data.put("editedAt", message.getEditedAt() != null ? message.getEditedAt().toString() : null);
        return buildEvent(WsEventType.MESSAGE_EDITED, message.getTenantId(), message.getChannelId(),
                data, objectMapper);
    }

    public static String buildMessageDeleted(UUID tenantId, UUID channelId, UUID messageId,
                                              ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId.toString());
        return buildEvent(WsEventType.MESSAGE_DELETED, tenantId, channelId, data, objectMapper);
    }

    // ═══ Thread Events ═══

    public static String buildThreadReply(Message message, String senderName, UUID parentMessageId,
                                           ObjectMapper objectMapper) {
        Map<String, Object> data = buildMessageData(message, senderName);
        data.put("parentMessageId", parentMessageId.toString());
        return buildEvent(WsEventType.THREAD_REPLY, message.getTenantId(), message.getChannelId(),
                data, objectMapper);
    }

    // ═══ Reaction Events ═══

    public static String buildReactionAdded(UUID tenantId, UUID channelId, UUID messageId,
                                             UUID userId, String emoji, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId.toString());
        data.put("userId", userId.toString());
        data.put("emoji", emoji);
        return buildEvent(WsEventType.REACTION_ADDED, tenantId, channelId, data, objectMapper);
    }

    public static String buildReactionRemoved(UUID tenantId, UUID channelId, UUID messageId,
                                               UUID userId, String emoji, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId.toString());
        data.put("userId", userId.toString());
        data.put("emoji", emoji);
        return buildEvent(WsEventType.REACTION_REMOVED, tenantId, channelId, data, objectMapper);
    }

    // ═══ Typing Events ═══

    public static String buildTypingStart(UUID tenantId, UUID channelId, UUID userId,
                                           String displayName, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("displayName", displayName);
        return buildEvent(WsEventType.TYPING_START, tenantId, channelId, data, objectMapper);
    }

    public static String buildTypingStop(UUID tenantId, UUID channelId, UUID userId,
                                          ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        return buildEvent(WsEventType.TYPING_STOP, tenantId, channelId, data, objectMapper);
    }

    // ═══ Presence Events ═══

    public static String buildPresenceChange(UUID tenantId, UUID userId, String status,
                                              ObjectMapper objectMapper) {
        return buildPresenceChange(tenantId, userId, status, null, objectMapper);
    }

    public static String buildPresenceChange(UUID tenantId, UUID userId, String status,
                                              String displayName, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("status", status);
        if (displayName != null) data.put("displayName", displayName);
        return buildEvent(WsEventType.PRESENCE_CHANGE, tenantId, null, data, objectMapper);
    }

    // ═══ Channel Events ═══

    public static String buildChannelUpdated(UUID tenantId, UUID channelId,
                                              Map<String, Object> changes, ObjectMapper objectMapper) {
        return buildEvent(WsEventType.CHANNEL_UPDATED, tenantId, channelId, changes, objectMapper);
    }

    public static String buildChannelArchived(UUID tenantId, UUID channelId,
                                               ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", channelId.toString());
        return buildEvent(WsEventType.CHANNEL_ARCHIVED, tenantId, channelId, data, objectMapper);
    }

    // ═══ Membership Events ═══

    public static String buildMemberJoined(UUID tenantId, UUID channelId, UUID userId,
                                            String displayName, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("displayName", displayName);
        return buildEvent(WsEventType.MEMBER_JOINED, tenantId, channelId, data, objectMapper);
    }

    public static String buildMemberLeft(UUID tenantId, UUID channelId, UUID userId,
                                          ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        return buildEvent(WsEventType.MEMBER_LEFT, tenantId, channelId, data, objectMapper);
    }

    // ═══ Pin Events ═══

    public static String buildPinAdded(UUID tenantId, UUID channelId, UUID messageId,
                                        UUID pinnedBy, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId.toString());
        data.put("pinnedBy", pinnedBy.toString());
        return buildEvent(WsEventType.PIN_ADDED, tenantId, channelId, data, objectMapper);
    }

    // ═══ Read Receipt Events ═══

    public static String buildReadReceipt(UUID tenantId, UUID channelId, UUID userId,
                                           UUID lastReadMessageId, ObjectMapper objectMapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("lastReadMessageId", lastReadMessageId.toString());
        return buildEvent(WsEventType.READ_RECEIPT, tenantId, channelId, data, objectMapper);
    }

    // ═══ Internal Helpers ═══

    private static Map<String, Object> buildMessageData(Message message, String senderName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", message.getId().toString());
        data.put("senderId", message.getSenderId().toString());
        data.put("senderName", senderName != null ? senderName : "Unknown");
        data.put("content", message.getContent());
        data.put("messageType", message.getMessageType().name());
        data.put("mediaUrl", message.getMediaUrl());
        data.put("createdAt", message.getCreatedAt().toString());
        if (message.getParentMessageId() != null) {
            data.put("parentMessageId", message.getParentMessageId().toString());
        }
        return data;
    }
}
