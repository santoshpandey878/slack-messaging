package com.slackmsg.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.entity.PinnedMessage;
import com.slackmsg.message.adapter.postgres.PinRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class PinService {

    private static final int MAX_PINS = 100;
    private final PinRepository pinRepo;
    private final MessageStore messageStore;
    private final ChannelServicePort channelService;
    private final FanoutService fanoutService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PinnedMessage pinMessage(UUID channelId, UUID messageId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (!channelService.isMember(channelId, userId)) throw new SecurityException("Not a member of this channel");

        Message msg = messageStore.findById(tenantId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (Boolean.TRUE.equals(msg.getIsDeleted())) throw new IllegalArgumentException("Cannot pin a deleted message");
        if (!msg.getChannelId().equals(channelId)) throw new IllegalArgumentException("Message does not belong to this channel");
        if (pinRepo.countByChannelId(channelId) >= MAX_PINS) throw new IllegalArgumentException("Channel pin limit reached (100)");

        PinnedMessage pin = PinnedMessage.builder().tenantId(tenantId).channelId(channelId)
                .messageId(messageId).pinnedBy(userId).build();
        try { pin = pinRepo.save(pin); }
        catch (DataIntegrityViolationException e) { throw new IllegalArgumentException("Message already pinned"); }

        try {
            String payload = WsPayloadBuilder.buildPinAdded(tenantId, channelId, messageId, userId, objectMapper);
            fanoutService.fanoutEvent(tenantId, channelId, payload, userId, false);
        } catch (Exception e) { log.error("Pin fanout failed: {}", e.getMessage()); }

        log.info("Pin added: channelId={} msgId={} pinnedBy={}", channelId, messageId, userId);
        return pin;
    }

    @Transactional
    public void unpinMessage(UUID channelId, UUID messageId) {
        UUID userId = TenantContext.getUserId();
        if (!channelService.isMember(channelId, userId)) throw new SecurityException("Not a member of this channel");
        pinRepo.deleteByChannelIdAndMessageId(channelId, messageId);
        log.info("Pin removed: channelId={} msgId={}", channelId, messageId);
    }

    @Transactional(readOnly = true)
    public List<PinnedMessage> listPins(UUID channelId) {
        if (!channelService.isMember(channelId, TenantContext.getUserId()))
            throw new SecurityException("Not a member of this channel");
        return pinRepo.findByChannelIdOrderByCreatedAtDesc(channelId);
    }
}
