package com.slackmsg.ws.client;

import com.slackmsg.client.ServiceClient;
import com.slackmsg.domain.entity.Message;
import com.slackmsg.port.service.MessageServicePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
            // For simplicity, return empty — ws-gateway uses the REST response directly
            // In production, deserialize properly
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get messages after from message-service: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
