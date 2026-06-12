package com.slackmsg.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class VersionController {
    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", "2.0.0", "service", "api-gateway", "status", "cd-deployed", "features", "threads,reactions");
    }
}
