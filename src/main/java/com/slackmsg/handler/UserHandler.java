package com.slackmsg.handler;

import com.slackmsg.domain.entity.User;
import com.slackmsg.handler.dto.request.AddUserRequest;
import com.slackmsg.service.AuthService;
import com.slackmsg.service.AuthorizationService;
import com.slackmsg.util.ApiResponse;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserHandler {

    private final AuthService authService;
    private final AuthorizationService authz;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> addUser(
            @Valid @RequestBody AddUserRequest request) {
        authz.requireTenantAdmin();

        User user = authService.addUser(
                TenantContext.getTenantId(), request.getEmail(),
                request.getDisplayName(), request.getPassword());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("User added",
                Map.of("userId", user.getId(), "email", user.getEmail(), "displayName", user.getDisplayName())));
    }
}
