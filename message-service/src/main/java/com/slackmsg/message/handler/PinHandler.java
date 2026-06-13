package com.slackmsg.message.handler;

import com.slackmsg.domain.entity.PinnedMessage;
import com.slackmsg.message.service.PinService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channels/{channelId}/pins")
@RequiredArgsConstructor
public class PinHandler {

    private final PinService pinService;

    @PostMapping("/{messageId}")
    public ResponseEntity<ApiResponse<PinnedMessage>> pinMessage(
            @PathVariable UUID channelId, @PathVariable UUID messageId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Message pinned", pinService.pinMessage(channelId, messageId)));
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<ApiResponse<Void>> unpinMessage(
            @PathVariable UUID channelId, @PathVariable UUID messageId) {
        pinService.unpinMessage(channelId, messageId);
        return ResponseEntity.ok(ApiResponse.ok("Message unpinned", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PinnedMessage>>> listPins(@PathVariable UUID channelId) {
        return ResponseEntity.ok(ApiResponse.ok(pinService.listPins(channelId)));
    }
}
