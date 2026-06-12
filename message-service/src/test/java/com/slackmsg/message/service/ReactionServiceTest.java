package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.entity.Reaction;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.dto.response.ReactionResponse;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    @InjectMocks
    private ReactionService reactionService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
        TenantContext.setDisplayName("Test User");
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    @Test
    void addReaction_success() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");

        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMessage()));
        when(reactionRepo.save(any(Reaction.class))).thenReturn(buildReaction("+1"));

        ReactionResponse response = reactionService.addReaction(channelId, messageId, req);

        assertNotNull(response);
        assertEquals("+1", response.getEmoji());
    }

    @Test
    void addReaction_notMember() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(false);
        assertThrows(SecurityException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test
    void addReaction_messageNotFound() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test
    void addReaction_deletedMessage() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");
        Message deleted = buildMessage();
        deleted.setIsDeleted(true);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test
    void addReaction_duplicate() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMessage()));
        when(reactionRepo.save(any(Reaction.class))).thenThrow(new DataIntegrityViolationException("dup"));
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test
    void removeReaction_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(reactionRepo.findByMessageIdAndUserIdAndEmoji(messageId, userId, "+1"))
                .thenReturn(Optional.of(buildReaction("+1")));
        reactionService.removeReaction(channelId, messageId, "+1");
        verify(reactionRepo).deleteByMessageIdAndUserIdAndEmoji(messageId, userId, "+1");
    }

    @Test
    void removeReaction_notFound() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(reactionRepo.findByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> reactionService.removeReaction(channelId, messageId, "+1"));
    }

    @Test
    void getReactions_success() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(reactionRepo.findByMessageIdOrderByCreatedAtAsc(messageId))
                .thenReturn(Collections.singletonList(buildReaction("+1")));
        List<ReactionResponse> reactions = reactionService.getReactions(channelId, messageId);
        assertEquals(1, reactions.size());
        assertEquals("+1", reactions.get(0).getEmoji());
    }

    @Test
    void addReaction_fanoutFailure_stillSucceeds() {
        AddReactionRequest req = new AddReactionRequest();
        req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMessage()));
        when(reactionRepo.save(any(Reaction.class))).thenReturn(buildReaction("+1"));
        doThrow(new RuntimeException("Redis down")).when(fanoutService)
                .fanoutEvent(any(), any(), any(), any(), anyBoolean());

        ReactionResponse response = reactionService.addReaction(channelId, messageId, req);
        assertNotNull(response);
    }

    private Message buildMessage() {
        return Message.builder()
                .id(messageId).tenantId(tenantId).channelId(channelId).senderId(userId)
                .senderName("Test User").content("Hello").messageType(MessageType.TEXT)
                .isDeleted(false).createdAt(Instant.now()).build();
    }

    private Reaction buildReaction(String emoji) {
        return Reaction.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).channelId(channelId)
                .messageId(messageId).userId(userId).emoji(emoji)
                .createdAt(Instant.now()).build();
    }
}
