package io.github.lz007001cn.veriqra.service.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

/** Reads frozen pbkdf2_sha256$iterations$hex-salt$hex-digest format using JDK cryptography. */
public final class PasswordVerifier {
    private static final String DUMMY = "pbkdf2_sha256$600000$1249c9ecae9f9b78f8e25756f4c8bbaf$2ce8ef422b1cb1e5dfea359a18dd4b5b3bf94efa2c0872cdf8a5f5673d5d1694";
    private static final int CURRENT_ITERATIONS = 600000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Produces the same format accepted by verify; no plaintext or derived value is logged. */
    public String hash(String password) {
        if (password == null || password.isBlank() || password.length() > 1024) {
            throw new IllegalArgumentException("Password must contain 1 to 1024 characters");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, CURRENT_ITERATIONS, 256);
        Arrays.fill(chars, '\0');
        byte[] digest = null;
        try {
            digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2_sha256$" + CURRENT_ITERATIONS + "$" + HexFormat.of().formatHex(salt)
                    + "$" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Required password hashing algorithm unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(salt, (byte) 0);
            if (digest != null) Arrays.fill(digest, (byte) 0);
        }
    }

    public boolean verify(String password, String encoded) {
        if (password == null || password.length() > 1024) return false;
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) return dummy(password);
            iterations = Integer.parseInt(parts[1]);
            if (iterations < 210000 || iterations > 1000000) return dummy(password);
            if (parts[2].length() < 24 || parts[2].length() > 128 || parts[3].length() != 64) return dummy(password);
            salt = HexFormat.of().parseHex(parts[2]);
            expected = HexFormat.of().parseHex(parts[3]);
        } catch (IllegalArgumentException e) { return dummy(password); }
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        Arrays.fill(chars, '\0');
        byte[] actual = null;
        try {
            actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Required password verification algorithm unavailable", e);
        } finally {
            spec.clearPassword();
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    private boolean dummy(String password) {
        verify(password, DUMMY);
        return false;
    }
}
