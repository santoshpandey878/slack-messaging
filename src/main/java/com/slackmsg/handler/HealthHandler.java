package com.slackmsg.handler;

import com.slackmsg.util.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthHandler {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of(
            "status", "UP",
            "service", "slack-messaging"
        ));
    }
}
