package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.entity.Reaction;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.message.adapter.postgres.ReactionRepository;
import com.slackmsg.port.repository.MessageStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReactionServiceTest {

    @Mock private ReactionRepository reactionRepo;
    @Mock private MessageStore messageStore;
    @Mock private ChannelServicePort channelService;
    @Mock private FanoutService fanoutService;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private ReactionService reactionService;

    private final UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID(), messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() { TenantContext.setTenantId(tenantId); TenantContext.setUserId(userId); TenantContext.setDisplayName("Test"); }
    @AfterEach
    void tearDown() { TenantContext.clear(); }

    private Message buildMsg() {
        return Message.builder().id(messageId).tenantId(tenantId).channelId(channelId)
                .senderId(UUID.randomUUID()).messageType(MessageType.TEXT).content("hi")
                .isDeleted(false).createdAt(Instant.now()).build();
    }
    private AddReactionRequest req(String emoji) { AddReactionRequest r = new AddReactionRequest(); r.setEmoji(emoji); return r; }

    @Test void addReaction_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMsg()));
        when(reactionRepo.existsByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(false);
        when(reactionRepo.save(any())).thenAnswer(i -> { Reaction r = i.getArgument(0); r.setId(UUID.randomUUID()); return r; });
        assertNotNull(reactionService.addReaction(channelId, messageId, req("+1")).getId());
    }

    @Test void addReaction_notMember() {
        when(channelService.isMember(channelId, userId)).thenReturn(false);
        assertThrows(SecurityException.class, () -> reactionService.addReaction(channelId, messageId, req("+1")));
    }

    @Test void addReaction_messageNotFound() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req("+1")));
    }

    @Test void addReaction_deletedMessage() {
        Message m = buildMsg(); m.setIsDeleted(true);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(m));
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req("+1")));
    }

    @Test void addReaction_duplicate() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMsg()));
        when(reactionRepo.existsByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req("+1")));
    }

    @Test void addReaction_fanoutFailure_stillSucceeds() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMsg()));
        when(reactionRepo.existsByMessageIdAndUserIdAndEmoji(any(), any(), any())).thenReturn(false);
        when(reactionRepo.save(any())).thenAnswer(i -> { Reaction r = i.getArgument(0); r.setId(UUID.randomUUID()); return r; });
        doThrow(new RuntimeException("down")).when(fanoutService).fanoutEvent(any(), any(), any(), any(), anyBoolean());
        assertNotNull(reactionService.addReaction(channelId, messageId, req("+1")));
    }

    @Test void removeReaction_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        reactionService.removeReaction(channelId, messageId, "+1");
        verify(reactionRepo).deleteByMessageIdAndUserIdAndEmoji(messageId, userId, "+1");
    }

    @Test void getReactions_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(reactionRepo.findByMessageIdOrderByCreatedAtAsc(messageId)).thenReturn(List.of(
                Reaction.builder().id(UUID.randomUUID()).messageId(messageId).userId(userId).emoji("+1").createdAt(Instant.now()).build()));
        assertEquals(1, reactionService.getReactions(channelId, messageId).size());
    }
}
