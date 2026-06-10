package com.slackmsg.service;

import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Authorization checks — cross-cutting concern.
 * Centralized to avoid duplication across services.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final ChannelStore channelStore;

    public boolean isTenantAdmin() {
        String role = TenantContext.getUserRole();
        return role != null && "admin".equals(role);
    }

    public void requireTenantAdmin() {
        if (!isTenantAdmin()) {
            throw new SecurityException("Only tenant admins can perform this action");
        }
    }

    public void requireChannelAdminOrTenantAdmin(UUID channelId) {
        if (isTenantAdmin()) return;

        UUID userId = TenantContext.getUserId();
        ChannelMember member = channelStore.findMember(channelId, userId)
                .orElseThrow(() -> new SecurityException("Not a member of this channel"));

        if (member.getRole() == null || member.getRole() != MemberRole.ADMIN) {
            throw new SecurityException("Only channel admins can perform this action");
        }
    }

    public void requireMembership(UUID channelId) {
        UUID userId = TenantContext.getUserId();
        if (!channelStore.isMember(channelId, userId)) {
            throw new SecurityException("Not a member of this channel");
        }
    }
}
