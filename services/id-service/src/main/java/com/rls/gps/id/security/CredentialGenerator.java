package com.rls.gps.id.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/** Generates the credential pair a company uses to register its users. */
@Component
public class CredentialGenerator {

    static final String APP_KEY_PREFIX = "ak_";
    static final String APP_SECRET_PREFIX = "as_";

    private static final int APP_KEY_BYTES = 18;     // 24 base64url chars
    private static final int APP_SECRET_BYTES = 32;  // 43 base64url chars

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /** Public identifier of a company - travels in requests and logs. */
    public String appKey() {
        return APP_KEY_PREFIX + randomToken(APP_KEY_BYTES);
    }

    /** Secret shown exactly once at signup and stored only as a BCrypt hash. */
    public String appSecret() {
        return APP_SECRET_PREFIX + randomToken(APP_SECRET_BYTES);
    }

    private String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
