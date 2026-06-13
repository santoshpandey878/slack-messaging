package com.slackmsg.channel.internal;

import com.slackmsg.channel.service.ChannelService;
import com.slackmsg.channel.service.MembershipService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API for inter-service calls.
 * NOT exposed to clients — only other services call these.
 */
@RestController
@RequestMapping("/internal/channels")
@RequiredArgsConstructor
public class ChannelInternalHandler {

    private final ChannelService channelService;
    private final MembershipService membershipService;

    @GetMapping("/{channelId}/is-member/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> isMember(
            @PathVariable UUID channelId, @PathVariable UUID userId) {
        boolean member = channelService.isMember(channelId, userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("isMember", member)));
    }

    @GetMapping("/{channelId}/member-ids")
    public ResponseEntity<ApiResponse<List<UUID>>> getMemberIds(@PathVariable UUID channelId) {
        List<UUID> ids = channelService.getMemberUserIds(channelId);
        return ResponseEntity.ok(ApiResponse.ok(ids));
    }

    @GetMapping("/user/{userId}/channel-ids")
    public ResponseEntity<ApiResponse<List<UUID>>> getUserChannelIds(
            @PathVariable UUID userId, @RequestParam UUID tenantId) {
        List<UUID> ids = channelService.getUserChannelIds(tenantId, userId);
        return ResponseEntity.ok(ApiResponse.ok(ids));
    }
}
