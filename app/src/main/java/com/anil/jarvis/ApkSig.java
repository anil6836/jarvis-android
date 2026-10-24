package com.anil.jarvis;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The signing certificate of a downloaded APK, read straight from its APK Signing Block (v2 / v3 scheme), for the
 * updater's "same key?" check. Some Android builds (the car's box) don't give a downloaded file's certificate through
 * PackageManager: this reads it without it. Only the certificate is read here; Android itself still checks the
 * signature when it installs, and never lets a file signed with another key replace Jarvis.
 */
final class ApkSig {
    private ApkSig() {}

    private static final int EOCD_SIG = 0x06054b50, V2 = 0x7109871a, V3 = 0xf05368c0;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The first signer's certificate (DER), v3 preferred, else v2; null when there is no signing block / it can't be read. */
    static byte[] firstCert(File apk) {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            long len = f.length();
            if (len < 22) return null;
            int tail = (int) Math.min(len, 65535 + 22);
            byte[] t = new byte[tail];
            f.seek(len - tail);
            f.readFully(t);
            ByteBuffer tb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) if (tb.getInt(i) == EOCD_SIG) { eocd = i; break; }
            if (eocd < 0) return null;
            long cd = tb.getInt(eocd + 16) & 0xFFFFFFFFL;
            if (cd < 32 || cd > len) return null;
            byte[] foot = new byte[24];
            f.seek(cd - 24);
            f.readFully(foot);
            if (!Arrays.equals(Arrays.copyOfRange(foot, 8, 24), MAGIC)) return null;
            long size = ByteBuffer.wrap(foot, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (size < 24 || size > 32L << 20 || cd - 8 - size < 0) return null;
            byte[] block = new byte[(int) size - 24];
            f.seek(cd - size - 8 + 8);
            f.readFully(block);
            return certOf(block);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** From the signing block's ID-value pairs (between its two size fields): the v3 first certificate, else v2's. */
    static byte[] certOf(byte[] pairs) {
        ByteBuffer b = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN);
        byte[] v2 = null, v3 = null;
        while (b.remaining() >= 12) {
            long n = b.getLong();
            if (n < 4 || n > b.remaining()) return null;
            int id = b.getInt();
            int start = b.position(), end = (int) (start + n - 4);
            if (id == V2 || id == V3) {
                byte[] c = firstCertOfScheme(ByteBuffer.wrap(pairs, start, end - start).slice().order(ByteOrder.LITTLE_ENDIAN));
                if (id == V3) v3 = c; else v2 = c;
            }
            b.position(end);
        }
        return v3 != null ? v3 : v2;
    }

    /** signers → first signer → signed data → (digests, certificates) → first certificate. */
    private static byte[] firstCertOfScheme(ByteBuffer v) {
        try {
            ByteBuffer signers = prefixed(v);
            ByteBuffer signer = prefixed(signers);
            ByteBuffer signed = prefixed(signer);
            prefixed(signed); // digests
            ByteBuffer certs = prefixed(signed);
            ByteBuffer cert = prefixed(certs);
            byte[] out = new byte[cert.remaining()];
            cert.get(out);
            return out.length > 0 ? out : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A 32-bit length-prefixed part of b (b moves past it). */
    private static ByteBuffer prefixed(ByteBuffer b) {
        int n = b.getInt();
        if (n < 0 || n > b.remaining()) throw new IllegalArgumentException("bad length");
        ByteBuffer part = b.slice().order(ByteOrder.LITTLE_ENDIAN);
        part.limit(n);
        b.position(b.position() + n);
        return part;
    }
}
