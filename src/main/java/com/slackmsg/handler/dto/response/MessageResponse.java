package com.slackmsg.handler.dto.response;

import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class MessageResponse {

    private UUID id;
    private UUID channelId;
    private UUID senderId;
    private String senderName;
    private String content;
    private MessageType messageType;
    private String mediaUrl;
    private String mediaType;
    private Instant createdAt;

    public static MessageResponse from(Message m) {
        return MessageResponse.builder()
                .id(m.getId())
                .channelId(m.getChannelId())
                .senderId(m.getSenderId())
                .senderName(m.getSenderName())
                .content(m.getContent())
                .messageType(m.getMessageType())
                .mediaUrl(m.getMediaUrl())
                .mediaType(m.getMediaType())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
