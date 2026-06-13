package com.slackmsg.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.dto.request.CreateDmRequest;
import com.slackmsg.dto.response.ChannelResponse;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DmService {

    private final ChannelStore channelStore;
    private final UserStore userStore;
    private final PubSubService pubSub;
    private final CacheService cache;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChannelResponse createOrGetDm(CreateDmRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        UUID targetUserId = req.getUserId();

        validateDmRequest(userId, targetUserId, tenantId);

        UUID user1 = userId.compareTo(targetUserId) < 0 ? userId : targetUserId;
        UUID user2 = userId.compareTo(targetUserId) < 0 ? targetUserId : userId;

        return channelStore.findDmChannel(tenantId, user1, user2)
                .map(chId -> findExistingDm(tenantId, userId, chId))
                .orElseGet(() -> createNewDmSafe(tenantId, userId, targetUserId, user1, user2));
    }

    public ChannelResponse enrichDmName(Channel ch, UUID tenantId, UUID currentUserId) {
        ChannelResponse resp = ChannelResponse.from(ch);
        if (ch.getType() == ChannelType.DM && ch.getName() == null) {
            List<ChannelMember> members = channelStore.getMembers(ch.getId());
            members.stream()
                    .filter(m -> !m.getUserId().equals(currentUserId))
                    .findFirst()
                    .flatMap(m -> userStore.findById(tenantId, m.getUserId()))
                    .ifPresent(otherUser -> resp.setName(otherUser.getDisplayName()));
        }
        return resp;
    }

    private void validateDmRequest(UUID userId, UUID targetUserId, UUID tenantId) {
        if (userId.equals(targetUserId)) throw new IllegalArgumentException("Cannot create DM with yourself");
        userStore.findById(tenantId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found"));
    }

    private ChannelResponse findExistingDm(UUID tenantId, UUID userId, UUID channelId) {
        return channelStore.findChannel(tenantId, channelId)
                .map(ch -> enrichDmName(ch, tenantId, userId))
                .orElseThrow(() -> new IllegalArgumentException("DM channel corrupted"));
    }

    private ChannelResponse createNewDmSafe(UUID tenantId, UUID userId, UUID targetUserId,
                                             UUID user1, UUID user2) {
        try {
            return createNewDm(tenantId, userId, targetUserId, user1, user2);
        } catch (DataIntegrityViolationException e) {
            return channelStore.findDmChannel(tenantId, user1, user2)
                    .flatMap(chId -> channelStore.findChannel(tenantId, chId))
                    .map(ch -> enrichDmName(ch, tenantId, userId))
                    .orElseThrow(() -> new IllegalArgumentException("DM creation failed"));
        }
    }

    private ChannelResponse createNewDm(UUID tenantId, UUID userId, UUID targetUserId,
                                         UUID user1, UUID user2) {
        Channel channel = channelStore.saveChannel(Channel.builder()
                .tenantId(tenantId).name(null).type(ChannelType.DM)
                .createdBy(userId).memberCount(2).build());

        channelStore.addMember(ChannelMember.builder()
                .channelId(channel.getId()).userId(user1).role(MemberRole.MEMBER).build());
        channelStore.addMember(ChannelMember.builder()
                .channelId(channel.getId()).userId(user2).role(MemberRole.MEMBER).build());
        channelStore.saveDmPair(tenantId, user1, user2, channel.getId());

        // Notify the other user via WS so their channel list updates
        notifyNewDm(tenantId, channel.getId(), targetUserId);

        log.info("DM created: channelId={} user1={} user2={}", channel.getId(), user1, user2);
        return enrichDmName(channel, tenantId, userId);
    }

    private void notifyNewDm(UUID tenantId, UUID channelId, UUID targetUserId) {
        try {
            String connKey = RedisKeys.wsConnection(tenantId, targetUserId);
            String serverId = cache.hget(connKey, "serverId");
            if (serverId != null) {
                String payload = WsPayloadBuilder.buildMemberJoined(tenantId, channelId, targetUserId,
                        TenantContext.getDisplayName(), objectMapper);
                pubSub.publish("ws:server:" + serverId, payload);
            }
        } catch (Exception e) {
            log.debug("DM notification failed (best-effort): {}", e.getMessage());
        }
    }
}
