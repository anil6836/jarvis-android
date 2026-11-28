package com.anil.jarvis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 💻 The lock between this phone and Jarvis PC on his laptop (the same as jarvis-pc src/main/linkcrypto.js; the spec is in
 * the project's jarvis-pc-plan.md "Phone ↔ PC link"): from the 32-byte key K in the laptop's QR, AES-128-CBC for the words
 * and HMAC-SHA256 (16 bytes) over direction + IV + text, so ntfy.sh (the relay) and anyone else see only locked text.
 * The plant board's lock looks alike but has its OWN labels, so the two never mix. Pure Java (tests on a PC).
 */
final class PcCrypto {
    /** 'c' = phone → PC, 'p' = PC → phone. */
    static final byte TO_PC = 'c', TO_PHONE = 'p';
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] ke, km;
    /** The relay topics (made from the key; never shown). */
    final String toPc, toPhone;

    PcCrypto(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("key");
        ke = Arrays.copyOf(sha(key, "pc-enc"), 16);
        km = sha(key, "pc-mac");
        toPc = "jc" + hex(sha(key, "to-pc")).substring(0, 30);
        toPhone = "jc" + hex(sha(key, "to-phone")).substring(0, 30);
    }

    String seal(String json, byte dir) {
        byte[] iv = new byte[16];
        RNG.nextBytes(iv);
        return seal(json, dir, iv);
    }

    /** (with a given IV: only for the fixed test vector) */
    String seal(String json, byte dir, byte[] iv) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ke, "AES"), new IvParameterSpec(iv));
            byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
            byte[] tag = Arrays.copyOf(mac(dir, iv, ct), 16);
            byte[] all = new byte[16 + ct.length + 16];
            System.arraycopy(iv, 0, all, 0, 16);
            System.arraycopy(ct, 0, all, 16, ct.length);
            System.arraycopy(tag, 0, all, 16 + ct.length, 16);
            return Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The words, or null when they aren't from the other end of this lock (wrong key, changed, or the wrong direction). */
    String open(String text, byte dir) {
        try {
            byte[] b = Base64.getDecoder().decode(text.trim());
            if (b.length < 48 || (b.length - 32) % 16 != 0) return null;
            byte[] iv = Arrays.copyOfRange(b, 0, 16), ct = Arrays.copyOfRange(b, 16, b.length - 16), tag = Arrays.copyOfRange(b, b.length - 16, b.length);
            if (!MessageDigest.isEqual(Arrays.copyOf(mac(dir, iv, ct), 16), tag)) return null;
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ke, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String newId() {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        return hex(b);
    }

    private byte[] mac(byte dir, byte[] iv, byte[] ct) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(km, "HmacSHA256"));
        m.update(dir);
        m.update(iv);
        m.update(ct);
        return m.doFinal();
    }

    private static byte[] sha(byte[] key, String label) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(key);
            d.update(label.getBytes(StandardCharsets.UTF_8));
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }
}
