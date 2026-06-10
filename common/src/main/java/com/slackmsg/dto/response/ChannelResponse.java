package com.slackmsg.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.enums.ChannelType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelResponse {

    private UUID id;
    private String name;
    private ChannelType type;
    private UUID createdBy;
    private Integer memberCount;
    private Boolean isArchived;
    private Instant createdAt;

    // Channel metadata
    private String topic;
    private String description;

    public static ChannelResponse from(Channel ch) {
        return ChannelResponse.builder()
                .id(ch.getId())
                .name(ch.getName())
                .type(ch.getType())
                .createdBy(ch.getCreatedBy())
                .memberCount(ch.getMemberCount())
                .isArchived(ch.getIsArchived())
                .createdAt(ch.getCreatedAt())
                .topic(ch.getTopic())
                .description(ch.getDescription())
                .build();
    }
}
