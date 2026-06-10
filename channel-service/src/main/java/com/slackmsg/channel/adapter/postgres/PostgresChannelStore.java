package com.slackmsg.channel.adapter.postgres;

import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.entity.DmPair;
import com.slackmsg.channel.adapter.postgres.ChannelMemberRepository;
import com.slackmsg.channel.adapter.postgres.ChannelRepository;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.channel.adapter.postgres.DmPairRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresChannelStore implements ChannelStore {

    private final ChannelRepository channelRepo;
    private final ChannelMemberRepository memberRepo;
    private final DmPairRepository dmPairRepo;

    @Override
    public Channel saveChannel(Channel channel) {
        return channelRepo.save(channel);
    }

    @Override
    public Optional<Channel> findChannel(UUID tenantId, UUID channelId) {
        return channelRepo.findByIdAndTenantId(channelId, tenantId);
    }

    @Override
    public List<Channel> findUserChannels(UUID tenantId, UUID userId) {
        return channelRepo.findUserChannels(tenantId, userId);
    }

    @Override
    public void updateMemberCount(UUID channelId, int delta) {
        channelRepo.updateMemberCount(channelId, delta);
    }

    @Override
    public long countChannels(UUID tenantId) {
        return channelRepo.countByTenantIdAndIsArchivedFalse(tenantId);
    }

    @Override
    public ChannelMember addMember(ChannelMember member) {
        return memberRepo.save(member);
    }

    @Override
    public void removeMember(UUID channelId, UUID userId) {
        memberRepo.removeByChannelIdAndUserId(channelId, userId);
    }

    @Override
    public boolean isMember(UUID channelId, UUID userId) {
        return memberRepo.existsByChannelIdAndUserId(channelId, userId);
    }

    @Override
    public Optional<ChannelMember> findMember(UUID channelId, UUID userId) {
        return memberRepo.findByChannelIdAndUserId(channelId, userId);
    }

    @Override
    public List<ChannelMember> getMembers(UUID channelId) {
        return memberRepo.findByChannelId(channelId);
    }

    @Override
    public Optional<UUID> findDmChannel(UUID tenantId, UUID userId1, UUID userId2) {
        return dmPairRepo.findByTenantIdAndUserId1AndUserId2(tenantId, userId1, userId2)
                .map(DmPair::getChannelId);
    }

    @Override
    public void saveDmPair(UUID tenantId, UUID userId1, UUID userId2, UUID channelId) {
        dmPairRepo.save(DmPair.builder()
                .tenantId(tenantId)
                .userId1(userId1)
                .userId2(userId2)
                .channelId(channelId)
                .build());
    }
}
