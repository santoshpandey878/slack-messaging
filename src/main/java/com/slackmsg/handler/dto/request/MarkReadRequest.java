package com.slackmsg.handler.dto.request;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.UUID;

@Data
public class MarkReadRequest {

    @NotNull(message = "Last read message ID is required")
    private UUID lastReadMessageId;
}
