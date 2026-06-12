package com.slackmsg.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConfigurationProperties(prefix = "services")
@Getter @Setter
public class ServiceRoutes {
    private String authUrl;
    private String channelUrl;
    private String messageUrl;
    private String mediaUrl;
    private String wsUrl;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Resolve which backend service URL to use based on the request path.
     */
    public String resolveServiceUrl(String path) {
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/users")) {
            return authUrl;
        }
        if (path.startsWith("/api/v1/channels") || path.startsWith("/api/v1/dm")) {
            // Channel-level endpoints go to channel-service
            // BUT /channels/{id}/messages goes to message-service
            if (path.matches("/api/v1/channels/[^/]+/messages.*")
                || path.matches("/api/v1/channels/[^/]+/read")
                || path.startsWith("/api/v1/unread")) {
                return messageUrl;
            }
            return channelUrl;
        }
        if (path.startsWith("/api/v1/unread")) {
            return messageUrl;
        }
        if (path.startsWith("/api/v1/media")) {
            return mediaUrl;
        }
        return null; // unknown route
    }
}
