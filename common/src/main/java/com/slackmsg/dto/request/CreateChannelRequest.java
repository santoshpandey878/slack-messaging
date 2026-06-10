package com.slackmsg.dto.request;

import com.slackmsg.domain.enums.ChannelType;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CreateChannelRequest {

    @NotBlank(message = "Channel name is required")
    private String name;

    @NotNull(message = "Channel type is required")
    private ChannelType type;
}
