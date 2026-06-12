package com.slackmsg.dto.request;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class SendMessageRequest {

    @Size(max = 40960, message = "Message too long (max 40KB)")
    private String content;

    @Size(max = 2048, message = "Media URL too long")
    private String mediaUrl;

    @Size(max = 100, message = "Media type too long")
    private String mediaType;

    @Size(max = 100, message = "Idempotency key too long")
    private String idempotencyKey;

    /** Thread reply — set to parent message ID. Null for top-level messages. */
    private java.util.UUID parentMessageId;

    /**
     * Validates that message has either content or media (or both).
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean hasMedia() {
        return mediaUrl != null && !mediaUrl.isBlank();
    }
}
