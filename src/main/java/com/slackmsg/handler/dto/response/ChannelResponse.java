package com.slackmsg.handler.dto.response;

import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.enums.ChannelType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ChannelResponse {

    private UUID id;
    private String name;
    private ChannelType type;
    private UUID createdBy;
    private Integer memberCount;
    private Boolean isArchived;
    private Instant createdAt;

    public static ChannelResponse from(Channel ch) {
        return ChannelResponse.builder()
                .id(ch.getId())
                .name(ch.getName())
                .type(ch.getType())
                .createdBy(ch.getCreatedBy())
                .memberCount(ch.getMemberCount())
                .isArchived(ch.getIsArchived())
                .createdAt(ch.getCreatedAt())
                .build();
    }
}
