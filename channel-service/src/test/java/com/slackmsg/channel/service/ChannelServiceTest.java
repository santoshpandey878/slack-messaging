package com.slackmsg.channel.service;

import com.slackmsg.config.AppConfig;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.dto.request.CreateChannelRequest;
import com.slackmsg.dto.response.ChannelResponse;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    @Mock
    private ChannelStore channelStore;

    @Mock
    private DmService dmService;

    @Mock
    private AppConfig appConfig;

    @InjectMocks
    private ChannelService channelService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createChannel_success() {
        // Arrange
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("general");
        req.setType(ChannelType.PUBLIC);

        UUID channelId = UUID.randomUUID();
        Channel savedChannel = Channel.builder()
                .id(channelId)
                .tenantId(tenantId)
                .name("general")
                .type(ChannelType.PUBLIC)
                .createdBy(userId)
                .memberCount(1)
                .createdAt(Instant.now())
                .build();

        ChannelMember savedMember = ChannelMember.builder()
                .channelId(channelId)
                .userId(userId)
                .role(MemberRole.ADMIN)
                .build();

        when(channelStore.countChannels(tenantId)).thenReturn(0L);
        when(channelStore.saveChannel(any(Channel.class))).thenReturn(savedChannel);
        when(channelStore.addMember(any(ChannelMember.class))).thenReturn(savedMember);
        when(appConfig.getMaxChannelsPerTenant()).thenReturn(1000);

        // Act
        ChannelResponse response = channelService.createChannel(req);

        // Assert
        assertNotNull(response);
        assertEquals(channelId, response.getId());
        assertEquals("general", response.getName());
        assertEquals(ChannelType.PUBLIC, response.getType());
        assertEquals(userId, response.getCreatedBy());

        verify(channelStore).countChannels(tenantId);
        verify(channelStore).saveChannel(any(Channel.class));

        ArgumentCaptor<ChannelMember> memberCaptor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(channelStore).addMember(memberCaptor.capture());
        assertEquals(channelId, memberCaptor.getValue().getChannelId());
        assertEquals(userId, memberCaptor.getValue().getUserId());
        assertEquals(MemberRole.ADMIN, memberCaptor.getValue().getRole());
    }

    @Test
    void createChannel_dmTypeRejected() {
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("dm-channel");
        req.setType(ChannelType.DM);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> channelService.createChannel(req));
        assertEquals("Use POST /api/v1/dm to create direct messages", ex.getMessage());

        verify(channelStore, never()).saveChannel(any());
    }

    @Test
    void createChannel_limitReached() {
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("overflow-channel");
        req.setType(ChannelType.PUBLIC);

        when(appConfig.getMaxChannelsPerTenant()).thenReturn(1000);
        when(channelStore.countChannels(tenantId)).thenReturn(1000L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> channelService.createChannel(req));
        assertTrue(ex.getMessage().contains("Channel limit reached"));

        verify(channelStore, never()).saveChannel(any());
    }

    @Test
    void isMember_delegates() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.isMember(channelId, userId)).thenReturn(true);

        boolean result = channelService.isMember(channelId, userId);

        assertTrue(result);
        verify(channelStore).isMember(channelId, userId);
    }

    @Test
    void getMemberUserIds_returnsIds() {
        UUID channelId = UUID.randomUUID();
        UUID member1 = UUID.randomUUID();
        UUID member2 = UUID.randomUUID();

        List<ChannelMember> members = Arrays.asList(
                ChannelMember.builder().channelId(channelId).userId(member1).build(),
                ChannelMember.builder().channelId(channelId).userId(member2).build()
        );

        when(channelStore.getMembers(channelId)).thenReturn(members);

        List<UUID> result = channelService.getMemberUserIds(channelId);

        assertEquals(2, result.size());
        assertTrue(result.contains(member1));
        assertTrue(result.contains(member2));
        verify(channelStore).getMembers(channelId);
    }
}
