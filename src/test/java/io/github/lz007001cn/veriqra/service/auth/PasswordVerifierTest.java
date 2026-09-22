package io.github.lz007001cn.veriqra.service.auth;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class PasswordVerifierTest {
    @Test void verifiesJdkPbkdf2AndRejectsWrongPassword() throws Exception {
        byte[] salt = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        PBEKeySpec spec = new PBEKeySpec("测试 password".toCharArray(), salt, 600000, 256);
        String digest;
        try { digest = HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); }
        String encoded = "pbkdf2_sha256$600000$" + HexFormat.of().formatHex(salt) + "$" + digest;
        var verifier = new PasswordVerifier();
        assertTrue(verifier.verify("测试 password", encoded));
        assertFalse(verifier.verify("wrong", encoded));
        assertFalse(verifier.verify(null, encoded));
    }
    @Test void malformedUnknownAndUnboundedHashesFailClosed() {
        var verifier = new PasswordVerifier();
        for (String value : new String[]{"plaintext", "pbkdf2_sha256$999999999$aa$bb", "pbkdf2_sha256$x$aa$bb",
                "pbkdf2_sha256$210000$nothexnothexnothexnothex12$" + "a".repeat(64)}) {
            assertFalse(verifier.verify("password", value));
        }
        assertFalse(verifier.verify("password", null));
        assertFalse(verifier.verify("x".repeat(1025), "plaintext"));
    }
}
