package com.slackmsg.port.service;

import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;

import java.util.List;
import java.util.UUID;

/**
 * Port interface for channel operations.
 * Used by MessageService, FanoutService — cross-module calls go through this.
 *
 * Monolith: ChannelService implements this (local).
 * Microservice: RemoteChannelService implements this (gRPC).
 */
public interface ChannelServicePort {

    Channel getChannel(UUID tenantId, UUID channelId);

    boolean isMember(UUID channelId, UUID userId);

    List<ChannelMember> getMembers(UUID channelId);

    List<UUID> getMemberUserIds(UUID channelId);

    /**
     * Get all channel IDs that a user belongs to (for cross-channel search).
     */
    List<UUID> getUserChannelIds(UUID tenantId, UUID userId);
}
