package com.slackmsg.ws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.slackmsg.ws", "com.slackmsg.adapter", "com.slackmsg.middleware", "com.slackmsg.config", "com.slackmsg.util"})
@EntityScan(basePackages = "com.slackmsg.domain")
public class WsGatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WsGatewayServiceApplication.class, args);
    }
}
