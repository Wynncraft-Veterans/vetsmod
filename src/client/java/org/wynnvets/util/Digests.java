package org.wynnvets.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Digests of bytes in lower-case hex, as the downloads' pins are written. */
public final class Digests {

    private Digests() {}

    /**
     * The SHA-1 of some bytes.
     *
     * @param bytes the bytes
     * @return 40 lower-case hex digits
     */
    public static String sha1(byte[] bytes) {
        return hex("SHA-1", bytes);
    }

    /**
     * The SHA-256 of some bytes.
     *
     * @param bytes the bytes
     * @return 64 lower-case hex digits
     */
    public static String sha256(byte[] bytes) {
        return hex("SHA-256", bytes);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has " + algorithm, e);
        }
    }
}
