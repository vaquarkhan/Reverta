// SPDX-License-Identifier: Apache-2.0
package org.atp.crypto;

import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class Signatures {
    static String algorithm = "Ed25519";

    private Signatures() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "Ed25519 unavailable: " + ex.getMessage());
        }
    }

    public static byte[] sign(PrivateKey key, byte[] payload) {
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException ex) {
            throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "sign failed: " + ex.getMessage());
        }
    }

    public static boolean verify(PublicKey key, byte[] payload, byte[] sig) {
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(key);
            signature.update(payload);
            return signature.verify(sig);
        } catch (GeneralSecurityException ex) {
            return false;
        }
    }

    public static String encode(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    public static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "invalid base64");
        }
    }

    public static String encodePublic(PublicKey key) {
        return encode(key.getEncoded());
    }

    public static String encodePrivate(PrivateKey key) {
        return encode(key.getEncoded());
    }

    public static PublicKey parsePublic(String base64) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(decode(base64)));
        } catch (AtpException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "invalid public key");
        }
    }

    public static PrivateKey parsePrivate(String base64) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(decode(base64)));
        } catch (AtpException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "invalid private key");
        }
    }
}
