package com.slackmsg.service;

import com.slackmsg.config.AppConfig;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.domain.entity.User;
import com.slackmsg.domain.enums.ChannelType;
import com.slackmsg.domain.enums.MemberRole;
import com.slackmsg.handler.dto.request.CreateChannelRequest;
import com.slackmsg.handler.dto.request.CreateDmRequest;
import com.slackmsg.handler.dto.response.ChannelResponse;
import com.slackmsg.port.repository.ChannelStore;
import com.slackmsg.port.repository.UserStore;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ChannelServiceTest {

    @Mock private ChannelStore channelStore;
    @Mock private UserStore userStore;
    @Mock private AppConfig appConfig;

    private ChannelService channelService;
    private DmService dmService;
    private MembershipService membershipService;
    private AuthorizationService authz;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setup() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("admin");
        when(appConfig.getMaxChannelsPerTenant()).thenReturn(1000);

        dmService = new DmService(channelStore, userStore);
        authz = new AuthorizationService(channelStore);
        channelService = new ChannelService(channelStore, dmService, appConfig);
        membershipService = new MembershipService(channelStore, userStore, authz);
    }

    @AfterEach
    void teardown() { TenantContext.clear(); }

    // ═══ Channel CRUD ═══

    @Test
    void createChannel_success() {
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("general"); req.setType(ChannelType.PUBLIC);

        when(channelStore.countChannels(TENANT_ID)).thenReturn(5L);
        when(channelStore.saveChannel(any())).thenAnswer(i -> { Channel c = i.getArgument(0); c.setId(UUID.randomUUID()); return c; });
        when(channelStore.addMember(any())).thenReturn(ChannelMember.builder().build());

        ChannelResponse resp = channelService.createChannel(req);
        assertNotNull(resp);
        assertEquals("general", resp.getName());
    }

    @Test
    void createChannel_limitReached_throws() {
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("x"); req.setType(ChannelType.PUBLIC);
        when(channelStore.countChannels(TENANT_ID)).thenReturn(1000L);
        assertThrows(IllegalArgumentException.class, () -> channelService.createChannel(req));
    }

    @Test
    void createChannel_dmType_rejected() {
        CreateChannelRequest req = new CreateChannelRequest();
        req.setName("dm"); req.setType(ChannelType.DM);
        when(channelStore.countChannels(TENANT_ID)).thenReturn(0L);
        assertThrows(IllegalArgumentException.class, () -> channelService.createChannel(req));
    }

    @Test
    void getChannel_private_nonMember_throws() {
        UUID chId = UUID.randomUUID();
        TenantContext.setUserRole("member");
        Channel ch = Channel.builder().id(chId).tenantId(TENANT_ID).type(ChannelType.PRIVATE).createdAt(Instant.now()).build();
        when(channelStore.findChannel(TENANT_ID, chId)).thenReturn(Optional.of(ch));
        when(channelStore.isMember(chId, USER_ID)).thenReturn(false);
        assertThrows(SecurityException.class, () -> channelService.getChannelDetails(chId));
    }

    @Test
    void getChannel_public_succeeds() {
        UUID chId = UUID.randomUUID();
        Channel ch = Channel.builder().id(chId).tenantId(TENANT_ID).type(ChannelType.PUBLIC).name("pub").createdAt(Instant.now()).build();
        when(channelStore.findChannel(TENANT_ID, chId)).thenReturn(Optional.of(ch));
        assertEquals("pub", channelService.getChannelDetails(chId).getName());
    }

    // ═══ DM ═══

    @Test
    void createDm_new() {
        UUID target = UUID.randomUUID();
        CreateDmRequest req = new CreateDmRequest(); req.setUserId(target);
        UUID u1 = USER_ID.compareTo(target) < 0 ? USER_ID : target;
        UUID u2 = USER_ID.compareTo(target) < 0 ? target : USER_ID;

        when(userStore.findById(TENANT_ID, target)).thenReturn(Optional.of(User.builder().displayName("Bob").build()));
        when(channelStore.findDmChannel(TENANT_ID, u1, u2)).thenReturn(Optional.empty());
        when(channelStore.saveChannel(any())).thenAnswer(i -> { Channel c = i.getArgument(0); c.setId(UUID.randomUUID()); return c; });
        when(channelStore.addMember(any())).thenReturn(ChannelMember.builder().build());
        when(channelStore.getMembers(any())).thenReturn(List.of(
                ChannelMember.builder().userId(u1).build(), ChannelMember.builder().userId(u2).build()));
        when(userStore.findById(eq(TENANT_ID), eq(target))).thenReturn(Optional.of(User.builder().displayName("Bob").build()));

        ChannelResponse resp = dmService.createOrGetDm(req);
        assertNotNull(resp);
        assertEquals(ChannelType.DM, resp.getType());
    }

    @Test
    void createDm_existing_returnsSame() {
        UUID target = UUID.randomUUID();
        UUID existingChId = UUID.randomUUID();
        CreateDmRequest req = new CreateDmRequest(); req.setUserId(target);
        UUID u1 = USER_ID.compareTo(target) < 0 ? USER_ID : target;
        UUID u2 = USER_ID.compareTo(target) < 0 ? target : USER_ID;

        Channel existing = Channel.builder().id(existingChId).tenantId(TENANT_ID).type(ChannelType.DM).memberCount(2).createdAt(Instant.now()).build();
        when(userStore.findById(TENANT_ID, target)).thenReturn(Optional.of(User.builder().displayName("Bob").build()));
        when(channelStore.findDmChannel(TENANT_ID, u1, u2)).thenReturn(Optional.of(existingChId));
        when(channelStore.findChannel(TENANT_ID, existingChId)).thenReturn(Optional.of(existing));
        when(channelStore.getMembers(existingChId)).thenReturn(List.of(
                ChannelMember.builder().userId(u1).build(), ChannelMember.builder().userId(u2).build()));

        ChannelResponse resp = dmService.createOrGetDm(req);
        assertEquals(existingChId, resp.getId());
        verify(channelStore, never()).saveChannel(any());
    }

    @Test
    void createDm_withSelf_throws() {
        CreateDmRequest req = new CreateDmRequest(); req.setUserId(USER_ID);
        assertThrows(IllegalArgumentException.class, () -> dmService.createOrGetDm(req));
    }

    @Test
    void createDm_targetNotFound_throws() {
        UUID target = UUID.randomUUID();
        CreateDmRequest req = new CreateDmRequest(); req.setUserId(target);
        when(userStore.findById(TENANT_ID, target)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> dmService.createOrGetDm(req));
    }

    // ═══ Membership ═══

    @Test
    void addMembers_success() {
        UUID newUser = UUID.randomUUID();
        UUID chId = UUID.randomUUID();
        Channel ch = Channel.builder().id(chId).tenantId(TENANT_ID).type(ChannelType.PUBLIC).memberCount(1).createdAt(Instant.now()).build();

        when(channelStore.findChannel(TENANT_ID, chId)).thenReturn(Optional.of(ch));
        when(channelStore.findMember(chId, USER_ID)).thenReturn(Optional.of(ChannelMember.builder().role(MemberRole.ADMIN).build()));
        // Batch load: only current user is a member, newUser is NOT
        when(channelStore.getMembers(chId)).thenReturn(List.of(ChannelMember.builder().userId(USER_ID).build()));
        when(userStore.findById(TENANT_ID, newUser)).thenReturn(Optional.of(User.builder().build()));
        when(channelStore.addMember(any())).thenReturn(ChannelMember.builder().build());

        int added = membershipService.addMembers(chId, List.of(newUser));
        assertEquals(1, added);
    }

    @Test
    void addMembers_toDm_throws() {
        UUID chId = UUID.randomUUID();
        Channel dm = Channel.builder().id(chId).tenantId(TENANT_ID).type(ChannelType.DM).createdAt(Instant.now()).build();
        when(channelStore.findChannel(TENANT_ID, chId)).thenReturn(Optional.of(dm));
        assertThrows(IllegalArgumentException.class, () -> membershipService.addMembers(chId, List.of(UUID.randomUUID())));
    }

    @Test
    void addMembers_alreadyMember_skipped() {
        UUID existing = UUID.randomUUID();
        UUID chId = UUID.randomUUID();
        Channel ch = Channel.builder().id(chId).tenantId(TENANT_ID).type(ChannelType.PUBLIC).memberCount(1).createdAt(Instant.now()).build();

        when(channelStore.findChannel(TENANT_ID, chId)).thenReturn(Optional.of(ch));
        when(channelStore.findMember(chId, USER_ID)).thenReturn(Optional.of(ChannelMember.builder().role(MemberRole.ADMIN).build()));
        // Batch load: existing user is already a member
        when(channelStore.getMembers(chId)).thenReturn(List.of(
                ChannelMember.builder().userId(USER_ID).build(),
                ChannelMember.builder().userId(existing).build()));

        int added = membershipService.addMembers(chId, List.of(existing));
        assertEquals(0, added);
        verify(channelStore, never()).addMember(any());
    }

    // ═══ Service Port ═══

    @Test
    void isMember_delegates() {
        UUID chId = UUID.randomUUID();
        when(channelStore.isMember(chId, USER_ID)).thenReturn(true);
        assertTrue(channelService.isMember(chId, USER_ID));
    }

    @Test
    void getMemberUserIds_returns() {
        UUID chId = UUID.randomUUID();
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID();
        when(channelStore.getMembers(chId)).thenReturn(List.of(
                ChannelMember.builder().userId(m1).build(), ChannelMember.builder().userId(m2).build()));
        List<UUID> result = channelService.getMemberUserIds(chId);
        assertEquals(2, result.size());
    }
}
