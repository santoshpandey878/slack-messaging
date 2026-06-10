package com.slackmsg.handler;

import com.slackmsg.handler.dto.request.AddMembersRequest;
import com.slackmsg.handler.dto.request.CreateChannelRequest;
import com.slackmsg.handler.dto.request.CreateDmRequest;
import com.slackmsg.handler.dto.response.ChannelResponse;
import com.slackmsg.service.ChannelService;
import com.slackmsg.service.DmService;
import com.slackmsg.service.MembershipService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChannelHandler {

    private final ChannelService channelService;
    private final DmService dmService;
    private final MembershipService membershipService;

    @PostMapping("/channels")
    public ResponseEntity<ApiResponse<ChannelResponse>> createChannel(
            @Valid @RequestBody CreateChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Channel created", channelService.createChannel(request)));
    }

    @GetMapping("/channels")
    public ResponseEntity<ApiResponse<List<ChannelResponse>>> listChannels() {
        return ResponseEntity.ok(ApiResponse.ok(channelService.listMyChannels()));
    }

    @GetMapping("/channels/{channelId}")
    public ResponseEntity<ApiResponse<ChannelResponse>> getChannel(@PathVariable UUID channelId) {
        return ResponseEntity.ok(ApiResponse.ok(channelService.getChannelDetails(channelId)));
    }

    @PostMapping("/dm")
    public ResponseEntity<ApiResponse<ChannelResponse>> createDm(
            @Valid @RequestBody CreateDmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(dmService.createOrGetDm(request)));
    }

    @PostMapping("/channels/{channelId}/members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMembers(
            @PathVariable UUID channelId,
            @Valid @RequestBody AddMembersRequest request) {
        int added = membershipService.addMembers(channelId, request.getUserIds());
        String msg = added > 0 ? added + " member(s) added"
                : "No members added (users not found in this tenant or already members)";
        return ResponseEntity.ok(ApiResponse.ok(msg, Map.of("added", added, "requested", request.getUserIds().size())));
    }

    @DeleteMapping("/channels/{channelId}/members/{userId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID channelId, @PathVariable UUID userId) {
        membershipService.removeMember(channelId, userId);
        return ResponseEntity.ok(ApiResponse.ok("Member removed", null));
    }

    @GetMapping("/channels/{channelId}/members")
    public ResponseEntity<ApiResponse<List<UUID>>> listMembers(
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        limit = Math.max(1, Math.min(limit, 500));
        offset = Math.max(0, offset);
        List<UUID> allMembers = membershipService.listMemberIds(channelId);
        int end = Math.min(offset + limit, allMembers.size());
        List<UUID> page = (offset < allMembers.size()) ? allMembers.subList(offset, end) : List.of();
        return ResponseEntity.ok(ApiResponse.ok(page));
    }
}
