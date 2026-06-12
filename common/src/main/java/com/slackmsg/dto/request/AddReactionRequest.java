package com.slackmsg.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class AddReactionRequest {
    @NotBlank(message = "Emoji is required")
    @Size(max = 100, message = "Emoji too long")
    private String emoji;
}
