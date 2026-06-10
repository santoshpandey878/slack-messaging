package com.slackmsg.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter @Setter
public class JwtConfig {

    private String secret;
    private long expirationMs = 900000;         // 15 min
    private long refreshExpirationMs = 604800000; // 7 days
}
