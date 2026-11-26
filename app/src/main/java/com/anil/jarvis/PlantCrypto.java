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
 * 🌱 The lock between Jarvis and his plant board (the same as plantnet.py on the board): from the 32-byte key Jarvis wrote
 * to the board over USB, AES-128-CBC for the words and HMAC-SHA256 (16 bytes) over direction + IV + text, so ntfy.sh (the
 * relay) and anyone else see only locked text, and nobody without the key can order the pump. Pure Java (tests on a PC).
 */
final class PlantCrypto {
    static final byte DOWN = 'd', UP = 'u';
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] ke, km;
    final String down, up;

    PlantCrypto(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("key");
        ke = Arrays.copyOf(sha(key, "enc"), 16);
        km = sha(key, "mac");
        down = "jp" + EspFlash.hex(sha(key, "down")).substring(0, 30);
        up = "jp" + EspFlash.hex(sha(key, "up")).substring(0, 30);
    }

    static byte[] newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    /** "k7m2-p9x4-w3n8": the board's own setup Wi-Fi password (easy to type: no 0/o, 1/l/i). */
    static String newApPassword() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) s.append('-');
            s.append(abc.charAt(RNG.nextInt(abc.length())));
        }
        return s.toString();
    }

    static String newBoardId() {
        return String.format("%04X", RNG.nextInt(0x10000));
    }

    String seal(String json, byte dir) {
        try {
            byte[] iv = new byte[16];
            RNG.nextBytes(iv);
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

    private byte[] mac(byte dir, byte[] iv, byte[] ct) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(km, "HmacSHA256"));
        m.update(dir);
        m.update(iv);
        m.update(ct);
        return m.doFinal();
    }

    private static byte[] sha(byte[] key, String tag) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(key);
            d.update(tag.getBytes(StandardCharsets.US_ASCII));
            return d.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
