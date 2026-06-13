package com.slackmsg.dto.request;

import lombok.Data;

import java.util.UUID;

@Data
public class MarkReadRequest {

    private UUID lastReadMessageId;
}
