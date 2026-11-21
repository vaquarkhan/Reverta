package org.atp.crypto;

import org.atp.model.Types.AtpException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoTest {
    @Test
    void hashingAndSignaturesCoverFailurePaths() throws Exception {
        assertEquals(64, Hashing.sha256("atp").length());
        assertEquals(Hashing.genesis(), "0".repeat(64));
        String saved = Hashing.algorithm;
        try {
            Hashing.algorithm = "SHA-256-missing";
            assertThrows(IllegalStateException.class, () -> Hashing.sha256(new byte[] {1}));
        } finally {
            Hashing.algorithm = saved;
        }

        KeyPair keys = Signatures.generate();
        byte[] payload = "manifest".getBytes();
        byte[] sig = Signatures.sign(keys.getPrivate(), payload);
        assertTrue(Signatures.verify(keys.getPublic(), payload, sig));
        assertFalse(Signatures.verify(keys.getPublic(), payload, new byte[] {1, 2, 3}));
        assertEquals(keys.getPublic().getEncoded().length, Signatures.decode(Signatures.encodePublic(keys.getPublic())).length);
        assertEquals(keys.getPrivate(), Signatures.parsePrivate(Signatures.encodePrivate(keys.getPrivate())));
        assertEquals(keys.getPublic(), Signatures.parsePublic(Signatures.encodePublic(keys.getPublic())));
        assertThrows(AtpException.class, () -> Signatures.decode("%%%"));
        assertThrows(AtpException.class, () -> Signatures.parsePublic("%%%"));
        assertThrows(AtpException.class, () -> Signatures.parsePublic(Signatures.encode("hello".getBytes())));
        assertThrows(AtpException.class, () -> Signatures.parsePrivate("%%%"));
        assertThrows(AtpException.class, () -> Signatures.parsePrivate(Signatures.encode("hello".getBytes())));

        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThrows(AtpException.class, () -> Signatures.sign(rsa.getPrivate(), payload));
        assertFalse(Signatures.verify(rsa.getPublic(), payload, sig));
        String algorithm = Signatures.algorithm;
        try {
            Signatures.algorithm = "NoSuchAlgorithm";
            assertThrows(AtpException.class, Signatures::generate);
        } finally {
            Signatures.algorithm = algorithm;
        }
        assertTrue(Signatures.verify(keys.getPublic(), payload, sig));
        assertFalse(Arrays.equals(sig, new byte[0]));
    }
}
