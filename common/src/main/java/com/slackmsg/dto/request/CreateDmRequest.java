package com.slackmsg.dto.request;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.UUID;

@Data
public class CreateDmRequest {

    @NotNull(message = "Target user ID is required")
    private UUID userId;
}
