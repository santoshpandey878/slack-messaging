package com.slackmsg.channel.service;

import com.slackmsg.config.AppConfig;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.dto.request.CreateChannelRequest;
import com.slackmsg.dto.response.ChannelResponse;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Channel CRUD only. Clean, focused.
 *
 * Delegates to:
 *   DmService         — DM creation/dedup/name enrichment
 *   MembershipService — add/remove/list members
 *
 * Implements ChannelServicePort for cross-module calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelService implements ChannelServicePort {

    private final ChannelStore channelStore;
    private final DmService dmService;
    private final AppConfig appConfig;

    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        if (req.getType() == ChannelType.DM) {
            throw new IllegalArgumentException("Use POST /api/v1/dm to create direct messages");
        }

        long count = channelStore.countChannels(tenantId);
        if (count >= appConfig.getMaxChannelsPerTenant()) {
            throw new IllegalArgumentException("Channel limit reached: " + appConfig.getMaxChannelsPerTenant());
        }

        Channel channel = channelStore.saveChannel(Channel.builder()
                .tenantId(tenantId).name(req.getName()).type(req.getType())
                .createdBy(userId).memberCount(1).build());

        channelStore.addMember(ChannelMember.builder()
                .channelId(channel.getId()).userId(userId).role(MemberRole.ADMIN).build());

        log.info("Channel created: id={} name={} type={} tenant={}", channel.getId(), req.getName(), req.getType(), tenantId);
        return ChannelResponse.from(channel);
    }

    @Transactional(readOnly = true)
    public List<ChannelResponse> listMyChannels() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        return channelStore.findUserChannels(tenantId, userId).stream()
                .map(ch -> dmService.enrichDmName(ch, tenantId, userId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChannelResponse getChannelDetails(UUID channelId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        Channel channel = channelStore.findChannel(tenantId, channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found"));

        if (channel.getType() != ChannelType.PUBLIC && !channelStore.isMember(channelId, userId)) {
            throw new SecurityException("Not a member of this channel");
        }

        return dmService.enrichDmName(channel, tenantId, userId);
    }

    // ═══ ChannelServicePort (cross-module) ═══

    @Override
    public Channel getChannel(UUID tenantId, UUID channelId) {
        return channelStore.findChannel(tenantId, channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found"));
    }

    @Override
    public boolean isMember(UUID channelId, UUID userId) {
        return channelStore.isMember(channelId, userId);
    }

    @Override
    public List<ChannelMember> getMembers(UUID channelId) {
        return channelStore.getMembers(channelId);
    }

    @Override
    public List<UUID> getMemberUserIds(UUID channelId) {
        return channelStore.getMembers(channelId).stream()
                .map(ChannelMember::getUserId)
                .collect(Collectors.toList());
    }
}
