package app.dilmun.portal;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

import app.dilmun.core.Crypto;

/**
 * A P-256 signing key held by the Android Keystore. The private key never
 * leaves the Keystore (secure hardware on most devices) and is never visible
 * to the app's code, let alone to an agent.
 */
final class KeystoreSigner implements Crypto.Signer {
    private final PrivateKey priv;
    private final String pub;

    KeystoreSigner(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(alias)) {
                KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                g.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                g.generateKeyPair();
            }
            priv = (PrivateKey) ks.getKey(alias, null);
            pub = Crypto.hex(ks.getCertificate(alias).getPublicKey().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("The Android Keystore is unavailable: " + e, e);
        }
    }

    @Override public String pub() { return pub; }

    @Override public String sign(String message) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(priv);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return Crypto.hex(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed: " + e, e);
        }
    }
}
