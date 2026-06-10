package com.slackmsg.util;

import com.slackmsg.config.JwtConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtConfig jwtConfig;
    private Key signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtConfig.getSecret().getBytes();
        // Pad to at least 32 bytes for HS256
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(UUID userId, UUID tenantId, String role) {
        return generateToken(userId, tenantId, role, null);
    }

    public String generateToken(UUID userId, UUID tenantId, String role, String displayName) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getExpirationMs());

        var builder = Jwts.builder()
                .setSubject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("role", role);
        if (displayName != null) builder.claim("name", displayName);
        return builder
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        try {
            String subject = parseToken(token).getSubject();
            if (subject == null) throw new IllegalArgumentException("Missing subject in token");
            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException e) {
            throw new SecurityException("Invalid token: cannot extract userId");
        }
    }

    public UUID getTenantId(String token) {
        try {
            String tid = parseToken(token).get("tid", String.class);
            if (tid == null) throw new IllegalArgumentException("Missing tid in token");
            return UUID.fromString(tid);
        } catch (JwtException | IllegalArgumentException e) {
            throw new SecurityException("Invalid token: cannot extract tenantId");
        }
    }

    public String getRole(String token) {
        try {
            String role = parseToken(token).get("role", String.class);
            return role != null ? role : "member";
        } catch (JwtException e) {
            throw new SecurityException("Invalid token: cannot extract role");
        }
    }

    public String getDisplayName(String token) {
        try {
            return parseToken(token).get("name", String.class);
        } catch (JwtException e) {
            return null;
        }
    }
}
