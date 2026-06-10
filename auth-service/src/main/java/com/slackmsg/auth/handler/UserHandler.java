package com.slackmsg.auth.handler;

import com.slackmsg.domain.entity.User;
import com.slackmsg.dto.request.AddUserRequest;
import com.slackmsg.auth.service.AuthService;
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

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> addUser(
            @Valid @RequestBody AddUserRequest request) {
        String role = TenantContext.getUserRole();
        if (role == null || !"admin".equals(role)) {
            throw new SecurityException("Only admins can add users");
        }

        User user = authService.addUser(
                TenantContext.getTenantId(), request.getEmail(),
                request.getDisplayName(), request.getPassword());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("User added",
                Map.of("userId", user.getId(), "email", user.getEmail(), "displayName", user.getDisplayName())));
    }
}
