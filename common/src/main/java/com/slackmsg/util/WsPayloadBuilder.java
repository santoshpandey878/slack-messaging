package com.slackmsg.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class WsPayloadBuilder {

    private WsPayloadBuilder() {}

    public static String buildMessageNew(Message message, String senderName, ObjectMapper objectMapper) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "message.new");
            payload.put("channelId", message.getChannelId().toString());
            payload.put("tenantId", message.getTenantId().toString());

            Map<String, Object> msgData = new LinkedHashMap<>();
            msgData.put("id", message.getId().toString());
            msgData.put("senderId", message.getSenderId().toString());
            msgData.put("senderName", senderName != null ? senderName : "Unknown");
            msgData.put("content", message.getContent());
            msgData.put("messageType", message.getMessageType().name());
            msgData.put("mediaUrl", message.getMediaUrl());
            msgData.put("createdAt", message.getCreatedAt().toString());
            payload.put("message", msgData);

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to build WS payload for message {}: {}", message.getId(), e.getMessage());
            return "{}";
        }
    }
}
