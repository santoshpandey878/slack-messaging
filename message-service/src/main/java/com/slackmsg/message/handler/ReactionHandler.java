package com.slackmsg.message.handler;

import com.slackmsg.dto.request.AddReactionRequest;
import com.slackmsg.dto.response.ReactionResponse;
import com.slackmsg.message.service.ReactionService;
import com.slackmsg.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/channels/{channelId}/messages/{messageId}/reactions")
@RequiredArgsConstructor
public class ReactionHandler {

    private final ReactionService reactionService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReactionResponse>> add(
            @PathVariable UUID channelId, @PathVariable UUID messageId,
            @Valid @RequestBody AddReactionRequest request) {
        var reaction = reactionService.addReaction(channelId, messageId, request);
        return ResponseEntity.ok(ApiResponse.ok("Reaction added", ReactionResponse.from(reaction)));
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @PathVariable UUID channelId, @PathVariable UUID messageId, @PathVariable String emoji) {
        reactionService.removeReaction(channelId, messageId, emoji);
        return ResponseEntity.ok(ApiResponse.ok("Reaction removed", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReactionResponse>>> list(
            @PathVariable UUID channelId, @PathVariable UUID messageId) {
        var reactions = reactionService.getReactions(channelId, messageId).stream()
                .map(ReactionResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(reactions));
    }
}
