package com.slackmsg.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.port.service.CacheService;
import com.slackmsg.port.service.PubSubService;
import com.slackmsg.util.RedisKeys;
import com.slackmsg.util.TenantContext;
import com.slackmsg.util.WsPayloadBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipService {

    private final ChannelStore channelStore;
    private final UserStore userStore;
    private final AuthorizationService authz;
    private final PubSubService pubSub;
    private final CacheService cache;
    private final ObjectMapper objectMapper;

    @Transactional
    public int addMembers(UUID channelId, List<UUID> userIds) {
        UUID tenantId = TenantContext.getTenantId();
        Channel channel = findChannelOrThrow(tenantId, channelId);
        validateNotDm(channel, "add members to");
        authz.requireChannelAdminOrTenantAdmin(channelId);

        Set<UUID> existingMembers = channelStore.getMembers(channelId).stream()
                .map(ChannelMember::getUserId).collect(Collectors.toSet());

        int added = 0;
        for (UUID uid : userIds) {
            if (existingMembers.contains(uid)) continue;
            if (userStore.findById(tenantId, uid).isEmpty()) continue;
            try {
                channelStore.addMember(ChannelMember.builder()
                        .channelId(channelId).userId(uid).role(MemberRole.MEMBER).build());
                added++;
                notifyMemberAdded(tenantId, channelId, uid);
            } catch (DataIntegrityViolationException e) {
                log.debug("Member already added concurrently: ch={} user={}", channelId, uid);
            }
        }
        if (added > 0) channelStore.updateMemberCount(channelId, added);
        return added;
    }

    @Transactional
    public void removeMember(UUID channelId, UUID targetUserId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Channel channel = findChannelOrThrow(tenantId, channelId);
        validateNotDm(channel, "remove members from");

        if (!userId.equals(targetUserId)) {
            authz.requireChannelAdminOrTenantAdmin(channelId);
        }
        if (!channelStore.isMember(channelId, targetUserId)) {
            throw new IllegalArgumentException("User is not a member");
        }
        channelStore.removeMember(channelId, targetUserId);
        channelStore.updateMemberCount(channelId, -1);
    }

    @Transactional(readOnly = true)
    public List<UUID> listMemberIds(UUID channelId) {
        UUID tenantId = TenantContext.getTenantId();
        findChannelOrThrow(tenantId, channelId);
        authz.requireMembership(channelId);
        return channelStore.getMembers(channelId).stream()
                .map(ChannelMember::getUserId).collect(Collectors.toList());
    }

    private void notifyMemberAdded(UUID tenantId, UUID channelId, UUID userId) {
        try {
            String connKey = RedisKeys.wsConnection(tenantId, userId);
            String serverId = cache.hget(connKey, "serverId");
            if (serverId != null) {
                String payload = WsPayloadBuilder.buildMemberJoined(tenantId, channelId, userId,
                        TenantContext.getDisplayName(), objectMapper);
                pubSub.publish("ws:server:" + serverId, payload);
                log.debug("Notified user {} of channel membership via WS", userId);
            }
        } catch (Exception e) {
            log.debug("Member notification failed (best-effort): {}", e.getMessage());
        }
    }

    private Channel findChannelOrThrow(UUID tenantId, UUID channelId) {
        return channelStore.findChannel(tenantId, channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found"));
    }

    private void validateNotDm(Channel ch, String action) {
        if (ch.getType() == ChannelType.DM) throw new IllegalArgumentException("Cannot " + action + " a DM channel");
    }
}
