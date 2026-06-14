package com.remoteterm.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public class AuthManager {

    private final String token;

    public AuthManager(String token) {
        if (token != null && !token.isBlank()) {
            this.token = token.trim();
        } else {
            this.token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    }

    public String getToken() {
        return token;
    }

    public boolean validate(String input) {
        if (input == null) return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                input.getBytes(StandardCharsets.UTF_8));
    }
}
