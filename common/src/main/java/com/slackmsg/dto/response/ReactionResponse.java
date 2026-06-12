package com.slackmsg.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slackmsg.domain.entity.Reaction;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReactionResponse {

    private UUID id;
    private UUID messageId;
    private UUID userId;
    private String emoji;
    private Instant createdAt;

    public static ReactionResponse from(Reaction r) {
        return ReactionResponse.builder()
                .id(r.getId())
                .messageId(r.getMessageId())
                .userId(r.getUserId())
                .emoji(r.getEmoji())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
