package com.slackmsg.message.handler;

import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.dto.response.ReactionResponse;
import com.slackmsg.message.service.ReactionService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channels/{channelId}/messages/{messageId}/reactions")
@RequiredArgsConstructor
public class ReactionHandler {

    private final ReactionService reactionService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @PathVariable UUID channelId, @PathVariable UUID messageId, @Valid @RequestBody AddReactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Reaction added", reactionService.addReaction(channelId, messageId, request)));
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<ApiResponse<Void>> removeReaction(
            @PathVariable UUID channelId, @PathVariable UUID messageId, @PathVariable String emoji) {
        reactionService.removeReaction(channelId, messageId, emoji);
        return ResponseEntity.ok(ApiResponse.ok("Reaction removed", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReactionResponse>>> getReactions(
            @PathVariable UUID channelId, @PathVariable UUID messageId) {
        return ResponseEntity.ok(ApiResponse.ok(reactionService.getReactions(channelId, messageId)));
    }
}
