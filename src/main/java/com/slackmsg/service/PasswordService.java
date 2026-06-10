package com.slackmsg.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password encoding/verification.
 * Single responsibility: password security.
 */
@Service
public class PasswordService {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    public String encode(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return ENCODER.matches(rawPassword, encodedPassword);
    }
}
