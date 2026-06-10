package com.slackmsg.handler.dto.request;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

@Data
public class AddUserRequest {

    @NotBlank @Email
    private String email;

    @NotBlank
    private String displayName;

    @NotBlank
    private String password;
}
