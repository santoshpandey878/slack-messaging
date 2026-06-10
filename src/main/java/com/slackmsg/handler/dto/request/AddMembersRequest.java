package com.slackmsg.handler.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

@Data
public class AddMembersRequest {

    @NotEmpty(message = "At least one user ID is required")
    private List<UUID> userIds;
}
