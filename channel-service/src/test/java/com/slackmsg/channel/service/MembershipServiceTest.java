package com.slackmsg.channel.service;

import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.entity.User;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private ChannelStore channelStore;

    @Mock
    private UserStore userStore;

    @Mock
    private AuthorizationService authz;

    @InjectMocks
    private MembershipService membershipService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
        TenantContext.setUserRole("admin");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addMembers_success() {
        UUID newUserId = UUID.randomUUID();
        Channel channel = Channel.builder()
                .id(channelId).tenantId(tenantId).type(ChannelType.PUBLIC).build();

        when(channelStore.findChannel(tenantId, channelId)).thenReturn(Optional.of(channel));
        doNothing().when(authz).requireChannelAdminOrTenantAdmin(channelId);
        when(channelStore.getMembers(channelId)).thenReturn(Collections.emptyList());
        when(userStore.findById(tenantId, newUserId)).thenReturn(
                Optional.of(User.builder().id(newUserId).tenantId(tenantId).build()));
        when(channelStore.addMember(any(ChannelMember.class))).thenReturn(
                ChannelMember.builder().channelId(channelId).userId(newUserId).role(MemberRole.MEMBER).build());

        int added = membershipService.addMembers(channelId, Collections.singletonList(newUserId));

        assertEquals(1, added);
        verify(channelStore).addMember(any(ChannelMember.class));
        verify(channelStore).updateMemberCount(channelId, 1);
    }

    @Test
    void addMembers_skipExisting() {
        UUID existingUserId = UUID.randomUUID();
        Channel channel = Channel.builder()
                .id(channelId).tenantId(tenantId).type(ChannelType.PUBLIC).build();

        ChannelMember existingMember = ChannelMember.builder()
                .channelId(channelId).userId(existingUserId).role(MemberRole.MEMBER).build();

        when(channelStore.findChannel(tenantId, channelId)).thenReturn(Optional.of(channel));
        doNothing().when(authz).requireChannelAdminOrTenantAdmin(channelId);
        when(channelStore.getMembers(channelId)).thenReturn(Collections.singletonList(existingMember));

        int added = membershipService.addMembers(channelId, Collections.singletonList(existingUserId));

        assertEquals(0, added);
        verify(channelStore, never()).addMember(any(ChannelMember.class));
        verify(channelStore, never()).updateMemberCount(any(), anyInt());
    }

    @Test
    void addMembers_dmRejected() {
        Channel dmChannel = Channel.builder()
                .id(channelId).tenantId(tenantId).type(ChannelType.DM).build();

        when(channelStore.findChannel(tenantId, channelId)).thenReturn(Optional.of(dmChannel));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> membershipService.addMembers(channelId, Collections.singletonList(UUID.randomUUID())));
        assertTrue(ex.getMessage().contains("Cannot"));
        assertTrue(ex.getMessage().contains("DM"));

        verify(channelStore, never()).addMember(any());
    }

    @Test
    void removeMember_selfRemoval() {
        Channel channel = Channel.builder()
                .id(channelId).tenantId(tenantId).type(ChannelType.PUBLIC).build();

        when(channelStore.findChannel(tenantId, channelId)).thenReturn(Optional.of(channel));
        when(channelStore.isMember(channelId, userId)).thenReturn(true);

        membershipService.removeMember(channelId, userId);

        verify(authz, never()).requireChannelAdminOrTenantAdmin(any());
        verify(channelStore).removeMember(channelId, userId);
        verify(channelStore).updateMemberCount(channelId, -1);
    }

    @Test
    void removeMember_notMember() {
        UUID targetUserId = UUID.randomUUID();
        Channel channel = Channel.builder()
                .id(channelId).tenantId(tenantId).type(ChannelType.PUBLIC).build();

        when(channelStore.findChannel(tenantId, channelId)).thenReturn(Optional.of(channel));
        doNothing().when(authz).requireChannelAdminOrTenantAdmin(channelId);
        when(channelStore.isMember(channelId, targetUserId)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> membershipService.removeMember(channelId, targetUserId));
        assertEquals("User is not a member", ex.getMessage());

        verify(channelStore, never()).removeMember(any(), any());
    }
}
