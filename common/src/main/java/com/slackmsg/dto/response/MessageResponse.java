package com.slackmsg.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    // Thread fields (null for top-level messages)
    private UUID parentMessageId;
    private Integer replyCount;

    // Edit field (null if never edited)
    private Instant editedAt;

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
                .parentMessageId(m.getParentMessageId())
                .replyCount(m.getReplyCount() != null && m.getReplyCount() > 0 ? m.getReplyCount() : null)
                .editedAt(m.getEditedAt())
                .build();
    }
}
