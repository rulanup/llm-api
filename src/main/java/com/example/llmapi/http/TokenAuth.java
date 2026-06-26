package com.example.llmapi.http;

import com.example.llmapi.config.ApiConfig;
import com.example.llmapi.LlmApiMod;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates {@code Authorization: Bearer <token>} headers against the configured
 * token list. A SHA-256 prefix of the token is logged for traceability without
 * ever printing the full token.
 */
public final class TokenAuth {

    public static final String HEADER = "Authorization";
    public static final String PREFIX = "Bearer ";

    private final List<String> tokens;

    public TokenAuth(List<String> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static TokenAuth fromConfig() {
        return new TokenAuth(ApiConfig.effectiveTokens());
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    public boolean authorize(String authHeader) {
        if (authHeader == null) {
            return false;
        }
        if (!authHeader.startsWith(PREFIX)) {
            return false;
        }
        String presented = authHeader.substring(PREFIX.length()).trim();
        for (String candidate : tokens) {
            if (constantTimeEquals(presented, candidate)) {
                return true;
            }
        }
        return false;
    }

    public void announceTokenFingerprint() {
        for (String token : tokens) {
            LlmApiMod.LOGGER.info("[llmapi] active token fingerprint {} (length={})", fingerprint(token), token.length());
        }
    }

    public static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "n/a";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes());
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return "err";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
