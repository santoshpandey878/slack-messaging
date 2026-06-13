package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.entity.Reaction;
import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.dto.response.ReactionResponse;
import com.slackmsg.message.adapter.postgres.ReactionRepository;
import com.slackmsg.port.repository.MessageStore;
import com.slackmsg.port.service.ChannelServicePort;
import com.slackmsg.util.TenantContext;
import com.slackmsg.util.WsPayloadBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReactionService {

    private final ReactionRepository reactionRepo;
    private final MessageStore messageStore;
    private final ChannelServicePort channelService;
    private final FanoutService fanoutService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReactionResponse addReaction(UUID channelId, UUID messageId, AddReactionRequest req) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (!channelService.isMember(channelId, userId)) throw new SecurityException("Not a member of this channel");
        Message msg = messageStore.findById(tenantId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (Boolean.TRUE.equals(msg.getIsDeleted())) throw new IllegalArgumentException("Cannot react to a deleted message");

        Reaction reaction = Reaction.builder().tenantId(tenantId).channelId(channelId)
                .messageId(messageId).userId(userId).emoji(req.getEmoji()).build();
        try { reaction = reactionRepo.save(reaction); }
        catch (DataIntegrityViolationException e) { throw new IllegalArgumentException("Already reacted with this emoji"); }

        try {
            String payload = WsPayloadBuilder.buildReactionAdded(tenantId, channelId, messageId, userId, req.getEmoji(), objectMapper);
            fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false);
        } catch (Exception e) { log.error("Reaction fanout failed: {}", e.getMessage()); }

        log.info("Reaction added: msgId={} userId={} emoji={}", messageId, userId, req.getEmoji());
        return ReactionResponse.from(reaction);
    }

    @Transactional
    public void removeReaction(UUID channelId, UUID messageId, String emoji) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (!channelService.isMember(channelId, userId)) throw new SecurityException("Not a member of this channel");
        reactionRepo.findByMessageIdAndUserIdAndEmoji(messageId, userId, emoji)
                .orElseThrow(() -> new IllegalArgumentException("Reaction not found"));
        reactionRepo.deleteByMessageIdAndUserIdAndEmoji(messageId, userId, emoji);

        try {
            String payload = WsPayloadBuilder.buildReactionRemoved(tenantId, channelId, messageId, userId, emoji, objectMapper);
            fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false);
        } catch (Exception e) { log.error("Reaction fanout failed: {}", e.getMessage()); }

        log.info("Reaction removed: msgId={} userId={} emoji={}", messageId, userId, emoji);
    }

    @Transactional(readOnly = true)
    public List<ReactionResponse> getReactions(UUID channelId, UUID messageId) {
        if (!channelService.isMember(channelId, TenantContext.getUserId()))
            throw new SecurityException("Not a member of this channel");
        return reactionRepo.findByMessageIdOrderByCreatedAtAsc(messageId).stream()
                .map(ReactionResponse::from).collect(Collectors.toList());
    }
}
