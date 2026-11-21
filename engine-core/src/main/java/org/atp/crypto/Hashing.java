// SPDX-License-Identifier: Apache-2.0
package org.atp.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {
    static String algorithm = "SHA-256";

    private Hashing() {
    }

    public static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String sha256(String utf8) {
        return sha256(utf8.getBytes(StandardCharsets.UTF_8));
    }

    public static String genesis() {
        return "0".repeat(64);
    }
}
