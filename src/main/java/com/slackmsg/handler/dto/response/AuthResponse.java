package com.slackmsg.handler.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private UUID userId;
    private UUID tenantId;
    private String displayName;
    private String role;
}
