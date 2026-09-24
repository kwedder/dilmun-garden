package app.dilmun.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Hashing and signatures. Signatures are ECDSA over P-256 with SHA-256, the
 * scheme the Android Keystore supports on every device from Android 8, so a
 * portal's private key can live in secure hardware and never leave it.
 * Public keys travel as hex-encoded X.509 SubjectPublicKeyInfo.
 */
public final class Crypto {
    private Crypto() {}

    /** Something that can sign as a portal or as the steward. */
    public interface Signer {
        String pub();
        String sign(String message);
    }

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hash of a value's canonical JSON. */
    public static String H(Object value) {
        return sha256(Json.canon(value).getBytes(StandardCharsets.UTF_8));
    }

    /** Hash of a source's text, as the workspace map records it. */
    public static String textHash(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean verify(String pubHex, String sigHex, String message) {
        try {
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unhex(pubHex)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return s.verify(unhex(sigHex));
        } catch (Exception e) {
            return false;
        }
    }

    /** A key held in memory. Used by tests and the desktop dev server; the app uses the Keystore. */
    public static final class SoftSigner implements Signer {
        private final KeyPair kp;
        private final String pub;

        public SoftSigner() {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"));
                kp = g.generateKeyPair();
                pub = hex(kp.getPublic().getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public String pub() { return pub; }

        @Override public String sign(String message) {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(kp.getPrivate());
                s.update(message.getBytes(StandardCharsets.UTF_8));
                return hex(s.sign());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX[(b[i] >> 4) & 0xf];
            out[2 * i + 1] = HEX[b[i] & 0xf];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
