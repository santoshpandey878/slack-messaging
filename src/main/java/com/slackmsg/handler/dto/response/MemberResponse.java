package com.slackmsg.handler.dto.response;

import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.MemberRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class MemberResponse {

    private UUID userId;
    private MemberRole role;
    private Instant joinedAt;

    public static MemberResponse from(ChannelMember m) {
        return MemberResponse.builder()
                .userId(m.getUserId())
                .role(m.getRole())
                .joinedAt(m.getJoinedAt())
                .build();
    }
}
