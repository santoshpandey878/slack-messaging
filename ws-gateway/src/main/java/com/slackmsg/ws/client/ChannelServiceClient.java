package com.slackmsg.ws.client;

import com.slackmsg.client.ServiceClient;
import com.slackmsg.domain.entity.Channel;
import com.slackmsg.domain.entity.ChannelMember;
import com.slackmsg.port.service.ChannelServicePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
@Slf4j
public class ChannelServiceClient extends ServiceClient implements ChannelServicePort {

    public ChannelServiceClient(RestTemplate restTemplate,
                                 @Value("${services.channel-url}") String channelServiceUrl) {
        super(restTemplate, channelServiceUrl);
    }

    @Override
    public Channel getChannel(UUID tenantId, UUID channelId) {
        throw new UnsupportedOperationException("Not available via REST client");
    }

    @Override
    public boolean isMember(UUID channelId, UUID userId) {
        try {
            Map response = get("/internal/channels/" + channelId + "/is-member/" + userId, Map.class);
            if (response != null && response.containsKey("data")) {
                Map data = (Map) response.get("data");
                return Boolean.TRUE.equals(data.get("isMember"));
            }
            return false;
        } catch (Exception e) {
            log.error("Channel-service unavailable: {}", e.getMessage());
            return false; // WS delivery best-effort — skip if can't verify
        }
    }

    @Override
    public List<ChannelMember> getMembers(UUID channelId) {
        throw new UnsupportedOperationException("Not available via REST client");
    }

    @Override
    public List<UUID> getMemberUserIds(UUID channelId) {
        try {
            Map response = get("/internal/channels/" + channelId + "/member-ids", Map.class);
            if (response != null && response.containsKey("data")) {
                List<String> ids = (List<String>) response.get("data");
                List<UUID> result = new ArrayList<>();
                for (String id : ids) result.add(UUID.fromString(id));
                return result;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Channel-service unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
