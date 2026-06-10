package com.slackmsg.port.repository;

import com.slackmsg.domain.entity.ChannelMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelMemberRepository extends JpaRepository<ChannelMember, ChannelMember.ChannelMemberId> {

    boolean existsByChannelIdAndUserId(UUID channelId, UUID userId);

    Optional<ChannelMember> findByChannelIdAndUserId(UUID channelId, UUID userId);

    List<ChannelMember> findByChannelId(UUID channelId);

    List<ChannelMember> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM ChannelMember cm WHERE cm.channelId = :channelId AND cm.userId = :userId")
    void removeByChannelIdAndUserId(@Param("channelId") UUID channelId, @Param("userId") UUID userId);
}
