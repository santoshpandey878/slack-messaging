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

    @InjectMocks private ReactionService reactionService;

    private final UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID(), messageId = UUID.randomUUID();

    @BeforeEach void setUp() { TenantContext.setTenantId(tenantId); TenantContext.setUserId(userId); TenantContext.setDisplayName("Test"); }
    @AfterEach void tearDown() { TenantContext.clear(); }

    private Message buildMsg() { return Message.builder().id(messageId).tenantId(tenantId).channelId(channelId).senderId(userId).senderName("Test").content("Hi").messageType(MessageType.TEXT).isDeleted(false).createdAt(Instant.now()).build(); }
    private Reaction buildReaction(String e) { return Reaction.builder().id(UUID.randomUUID()).tenantId(tenantId).channelId(channelId).messageId(messageId).userId(userId).emoji(e).createdAt(Instant.now()).build(); }

    @Test void addReaction_success() {
        AddReactionRequest req = new AddReactionRequest(); req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMsg()));
        when(reactionRepo.save(any())).thenReturn(buildReaction("+1"));
        ReactionResponse r = reactionService.addReaction(channelId, messageId, req);
        assertEquals("+1", r.getEmoji());
    }

    @Test void addReaction_duplicate() {
        AddReactionRequest req = new AddReactionRequest(); req.setEmoji("+1");
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(buildMsg()));
        when(reactionRepo.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test void addReaction_deletedMsg() {
        AddReactionRequest req = new AddReactionRequest(); req.setEmoji("+1");
        Message del = buildMsg(); del.setIsDeleted(true);
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(messageStore.findById(tenantId, messageId)).thenReturn(Optional.of(del));
        assertThrows(IllegalArgumentException.class, () -> reactionService.addReaction(channelId, messageId, req));
    }

    @Test void removeReaction_notFound() {
        when(channelService.isMember(channelId, userId)).thenReturn(true);
        when(reactionRepo.findByMessageIdAndUserIdAndEmoji(messageId, userId, "+1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> reactionService.removeReaction(channelId, messageId, "+1"));
    }
}
