package com.anil.jarvis;

/**
 * Sound between the phone and the watch: 16 kHz, one byte a sample (G.711 mu-law, like a phone line), so a second of
 * voice is 16 KB over Bluetooth. Also the small rate changes on the way (OpenAI's 24 kHz voice, the phone voice's file).
 * The watch app has the same class (com.anil.jarvis.watch.Ulaw); keep both the same.
 */
final class Ulaw {
    private Ulaw() {}

    static final int RATE = 16000;
    private static final int BIAS = 0x84, CLIP = 32635;
    private static final byte[] EXP = new byte[256];
    private static final short[] TABLE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int e = 0;
            for (int v = i; v > 1; v >>= 1) e++;
            EXP[i] = (byte) e;
        }
        for (int i = 0; i < 256; i++) TABLE[i] = decodeSlow((byte) i);
    }

    static byte encode(int sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > CLIP) sample = CLIP;
        sample += BIAS;
        int exponent = EXP[(sample >> 7) & 0xFF];
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    private static short decodeSlow(byte b) {
        int u = ~b & 0xFF;
        int sign = u & 0x80, exponent = (u >> 4) & 7, mantissa = u & 0x0F;
        int sample = (((mantissa << 3) + BIAS) << exponent) - BIAS;
        return (short) (sign != 0 ? -sample : sample);
    }

    static short decode(byte b) { return TABLE[b & 0xFF]; }

    static byte[] encode(short[] s, int off, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = encode(s[off + i]);
        return out;
    }

    static short[] decode(byte[] b, int off, int n) {
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = TABLE[b[off + i] & 0xFF];
        return out;
    }

    /** A message of sound: [id 4][seq 4][mu-law bytes]. */
    static byte[] packet(int id, int seq, byte[] ulaw, int off, int n) {
        byte[] p = new byte[8 + n];
        put(p, 0, id);
        put(p, 4, seq);
        System.arraycopy(ulaw, off, p, 8, n);
        return p;
    }

    static int id(byte[] p) { return p == null || p.length < 8 ? -1 : get(p, 0); }

    static int seq(byte[] p) { return p == null || p.length < 8 ? -1 : get(p, 4); }

    private static void put(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16); b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v;
    }

    private static int get(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /**
     * 24 kHz to 16 kHz as the sound streams in (OpenAI's voice): a gentle smoothing (so the high hiss doesn't fold back
     * as noise), then two samples kept out of every three.
     */
    static final class Down24 {
        private int prev, cur;     // the last two input samples (smoothing needs one sample of look-ahead)
        private boolean primed;
        private int phase;         // which of the three positions the next smoothed sample is
        private int held;          // the first of the pair for the in-between output

        /** Returns the 16 kHz samples for these 24 kHz ones. */
        short[] push(short[] in, int n) {
            short[] out = new short[n * 2 / 3 + 2];
            int o = 0;
            for (int i = 0; i < n; i++) {
                int next = in[i];
                if (!primed) { prev = cur = next; primed = true; continue; }
                int smooth = (prev + 2 * cur + next) >> 2; // smoothed value at "cur"
                prev = cur;
                cur = next;
                switch (phase) {
                    case 0: out[o++] = (short) smooth; break;              // position 0: kept
                    case 1: held = smooth; break;                          // position 1: half of the in-between one
                    default: out[o++] = (short) ((held + smooth) >> 1);    // position 2: between 1 and 2 (1.5)
                }
                phase = (phase + 1) % 3;
            }
            return o == out.length ? out : java.util.Arrays.copyOf(out, o);
        }
    }

    /** Any rate to 16 kHz for a whole sound (the phone voice's file), smoothed first when it comes down. */
    static short[] to16k(short[] in, int n, int rate) {
        if (rate == RATE || n == 0) return java.util.Arrays.copyOf(in, n);
        float[] x = new float[n];
        if (rate > RATE) {
            for (int i = 0; i < n; i++) {
                int a = in[Math.max(0, i - 1)], b = in[i], c = in[Math.min(n - 1, i + 1)];
                x[i] = (a + 2f * b + c) / 4f;
            }
        } else {
            for (int i = 0; i < n; i++) x[i] = in[i];
        }
        double step = (double) rate / RATE;
        int outN = (int) Math.floor((n - 1) / step) + 1;
        short[] out = new short[outN];
        for (int i = 0; i < outN; i++) {
            double pos = i * step;
            int k = (int) pos;
            double f = pos - k;
            double v = k + 1 < n ? x[k] * (1 - f) + x[k + 1] * f : x[Math.min(k, n - 1)];
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return out;
    }
}
