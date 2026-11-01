package com.campusconnect;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/** Salted PBKDF2 password hashing and session-token generation. */
public class Security {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    Base64.getDecoder().decode(salt), 65536, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }
}
