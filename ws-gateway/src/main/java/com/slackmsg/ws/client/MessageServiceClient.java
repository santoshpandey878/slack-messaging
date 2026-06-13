package com.slackmsg.ws.client;

import com.slackmsg.client.ServiceClient;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.domain.enums.MessageType;
import com.slackmsg.port.service.MessageServicePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class MessageServiceClient extends ServiceClient implements MessageServicePort {

    public MessageServiceClient(RestTemplate restTemplate,
                                 @Value("${services.message-url}") String messageServiceUrl) {
        super(restTemplate, messageServiceUrl);
    }

    @Override
    public List<Message> getMessagesAfter(UUID tenantId, UUID channelId, UUID afterMessageId, int limit) {
        try {
            String path = "/internal/messages/after/" + channelId + "/" + afterMessageId
                    + "?tenantId=" + tenantId + "&limit=" + limit;
            Map response = get(path, Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
            if (dataList == null) return Collections.emptyList();

            List<Message> messages = new ArrayList<>();
            for (Map<String, Object> m : dataList) {
                messages.add(mapToMessage(m, tenantId));
            }
            return messages;
        } catch (Exception e) {
            log.error("Failed to get messages after from message-service: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Message mapToMessage(Map<String, Object> m, UUID tenantId) {
        return Message.builder()
                .id(toUUID(m.get("id")))
                .tenantId(tenantId)
                .channelId(toUUID(m.get("channelId")))
                .senderId(toUUID(m.get("senderId")))
                .senderName((String) m.get("senderName"))
                .content((String) m.get("content"))
                .messageType(m.get("messageType") != null ? MessageType.valueOf((String) m.get("messageType")) : MessageType.TEXT)
                .mediaUrl((String) m.get("mediaUrl"))
                .mediaType((String) m.get("mediaType"))
                .createdAt(m.get("createdAt") != null ? Instant.parse((String) m.get("createdAt")) : Instant.now())
                .parentMessageId(toUUID(m.get("parentMessageId")))
                .build();
    }

    private UUID toUUID(Object value) {
        if (value == null) return null;
        return UUID.fromString(value.toString());
    }
}
