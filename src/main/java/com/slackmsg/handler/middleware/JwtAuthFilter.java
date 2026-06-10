package com.slackmsg.handler.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackmsg.util.ApiResponse;
import com.slackmsg.util.JwtUtil;
import com.slackmsg.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String path = request.getRequestURI();

            // Skip auth for public endpoints
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Extract token from Authorization header
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
                return;
            }

            String token = authHeader.substring(7);

            try {
                if (!jwtUtil.isValid(token)) {
                    log.warn("Invalid JWT token from IP={}", request.getRemoteAddr());
                    sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                    return;
                }
            } catch (Exception e) {
                log.warn("JWT validation error from IP={}: {}", request.getRemoteAddr(), e.getMessage());
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }

            // Set tenant context + MDC for this request thread
            TenantContext.setTenantId(jwtUtil.getTenantId(token));
            TenantContext.setUserId(jwtUtil.getUserId(token));
            TenantContext.setUserRole(jwtUtil.getRole(token));
            TenantContext.setDisplayName(jwtUtil.getDisplayName(token));

            MDC.put("tenantId", TenantContext.getTenantId().toString());
            MDC.put("userId", TenantContext.getUserId().toString());

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return path.equals("/health")
                || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/login")
                || path.startsWith("/swagger-ui")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/v3/api-docs");
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
