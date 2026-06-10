package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface for channel + membership persistence.
 * MVP: PostgreSQL.  Scale: PostgreSQL (channels stay relational).
 */
public interface ChannelStore {

    // Channel CRUD
    Channel saveChannel(Channel channel);
    Optional<Channel> findChannel(UUID tenantId, UUID channelId);
    List<Channel> findUserChannels(UUID tenantId, UUID userId);
    void updateMemberCount(UUID channelId, int delta);
    long countChannels(UUID tenantId);

    // Membership
    ChannelMember addMember(ChannelMember member);
    void removeMember(UUID channelId, UUID userId);
    boolean isMember(UUID channelId, UUID userId);
    Optional<ChannelMember> findMember(UUID channelId, UUID userId);
    List<ChannelMember> getMembers(UUID channelId);

    // DM deduplication
    Optional<UUID> findDmChannel(UUID tenantId, UUID userId1, UUID userId2);
    void saveDmPair(UUID tenantId, UUID userId1, UUID userId2, UUID channelId);
}
