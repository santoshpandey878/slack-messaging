package com.slackmsg.port.service;

import com.slackmsg.domain.entity.Message;

import java.util.List;
import java.util.UUID;

/**
 * Port interface for message operations.
 * Used by WsHandler (reconnect sync), FanoutService.
 *
 * Monolith: MessageService implements this (local).
 * Microservice: RemoteMessageService implements this (gRPC/REST).
 */
public interface MessageServicePort {

    List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit);
}
