package com.slackmsg.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
@Getter @Setter
public class AppConfig {

    private int maxChannelsPerTenant = 1000;
    private int maxMembersPerChannel = 10000;
    private int maxMessageSizeBytes = 40960;
    private RateLimit rateLimit = new RateLimit();

    @Getter @Setter
    public static class RateLimit {
        private int tenantPerSecond = 100;
        private int userPerSecond = 10;
    }
}
