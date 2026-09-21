package io.github.lz007001cn.qatrack.service.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

/** Reads frozen pbkdf2_sha256$iterations$hex-salt$hex-digest format using JDK cryptography. */
public final class PasswordVerifier {
    private static final String DUMMY = "pbkdf2_sha256$600000$1249c9ecae9f9b78f8e25756f4c8bbaf$2ce8ef422b1cb1e5dfea359a18dd4b5b3bf94efa2c0872cdf8a5f5673d5d1694";

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
