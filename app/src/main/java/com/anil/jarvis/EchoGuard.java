package com.anil.jarvis;

/**
 * Jarvis's own echo removal for Gemini Live on the phone's speaker: what Jarvis plays (24 kHz) is kept as a 16 kHz
 * stream with "this part leaves the speaker at that moment", and each piece of the mic is cleaned with the part of it
 * that was playing when the mic heard it (EchoMdf, then EchoPost for what is left). It also measures how well that
 * works, so the talk lets the mic go to Gemini while Jarvis speaks (as in the Gemini app) only when Jarvis's voice is
 * really gone.
 *
 * Threads: written() / presented() / flushed() from the player, process() from the mic. No Android calls here (the
 * times come in as System.nanoTime() values), so it can be tested on its own.
 */
final class EchoGuard {
    static final int RATE = 16000, FRAME = 128, TAIL = 4096;
    private static final int RING = 1 << 16, MASK = RING - 1;
    private static final long NONE = Long.MIN_VALUE;
    /** The reference is read this far ahead of the mic's moment at the start (it must lead the echo). */
    static final int START_LEAD = 960; // 60 ms

    // ---------------------------------------------------------------- what Jarvis plays, as a 16 kHz stream
    private final short[] ring = new short[RING];
    private long written16;              // 16 kHz samples of Jarvis's voice so far (never goes back)
    private final Down24 rs = new Down24();
    private final short[] rsOut = new short[8192];
    private long rsBaseOut, rsBaseTrack; // since the resampler's last start: its first output, the track frame it began at
    /** The latest "this stream sample leaves the speaker at this moment" (from the speaker's position and latency). */
    private long mapNanos = NONE;
    private double mapS16;

    // ---------------------------------------------------------------- the mic side
    private long readIdx = NONE, readBase = NONE;
    private double offSmooth;
    private int miss;
    private long lastWritten;
    private int idleCalls = 99;
    private int lead = START_LEAD;
    private final EchoMdf ec = new EchoMdf(FRAME, TAIL, RATE);
    private final EchoPost pp = new EchoPost(FRAME, RATE, ec);
    private short[] far = new short[640], lin = new short[640], out = new short[640];

    // ---------------------------------------------------------------- measures
    /** Per 8 ms frame while Jarvis is loud: mic energy, energy left after cleaning (the last ~2 s of them). */
    private final double[] micE = new double[256], leftE = new double[256];
    private int nE, posE;
    /** ... and every Jarvis-loud frame, his voice or not (the overall measure). */
    private final double[] allMic = new double[256], allOut = new double[256], allLin = new double[256];
    private int nA, posA;
    private int seenResets;
    /** The last 2 s of Jarvis-loud frames: which had his voice over it. */
    private final boolean[] hisRecent = new boolean[256];
    private int hisPos, hisCount;
    private long farFrames;          // frames with Jarvis loud
    private double noise = 30;       // the room's level in the output (rms), when Jarvis is quiet
    private double his = 1000;       // his voice level in the output (rms)
    private int quietRun;
    private boolean ready;           // full talk is safe now
    private int realigns, blockChecks;
    private long lastCheckFrame;
    /** (tests) */
    static StringBuilder debug;
    static java.util.List<double[]> probe;
    /** For "Jarvis చెక్": the last measures. */
    volatile double erle = Double.NaN, left90 = Double.NaN, erleAll = Double.NaN, erleLin = Double.NaN;

    // ---------------------------------------------------------------- the echo path learnt in the last talk
    private static double[] savedWeights;
    private static int savedLead;
    private static double savedHis = -1;

    // ---------------------------------------------------------------- recording for a check (off unless asked)
    private final short[][] rec;
    private int recPos;
    private boolean recFull;

    EchoGuard(boolean warmStart, boolean record) {
        pp.setNoiseSuppress(0);  // only Jarvis's echo is taken out; his voice and the room stay as they are
        pp.setEchoSuppress(70, 35);
        pp.setEchoOverestimate(6);
        if (warmStart) {
            synchronized (EchoGuard.class) {
                if (savedWeights != null) {
                    lead = savedLead;
                    ec.loadWeights(savedWeights);
                }
                if (savedHis > 0) his = savedHis;
            }
        }
        rec = record ? new short[3][RATE * 30] : null;
    }

    // ================================================================ player side (one thread)

    /** 16 kHz stream position of a 24 kHz track frame (track frames since the last start count with the resampler). */
    private double s16(long trackFrame) {
        double p = trackFrame - rsBaseTrack;
        return rsBaseOut + (2 * p + Down24.DELAY48) / 3.0;
    }

    /** A piece of Jarvis's voice (24 kHz, 16-bit little-endian, len bytes) was handed to the speaker, in order. */
    void written(byte[] pcm, int off, int len) {
        int frames = len / 2;
        if (frames <= 0) return;
        synchronized (ring) {
            int k = rs.push(pcm, off, frames, rsOut);
            for (int i = 0; i < k; i++) ring[(int) ((written16 + i) & MASK)] = rsOut[i];
            written16 += k;
        }
    }

    /** The speaker plays trackFrame at presentNanos (the speaker's frames counted as handed over since the last start). */
    void presented(long presentNanos, long trackFrame) {
        synchronized (ring) {
            mapNanos = presentNanos;
            mapS16 = s16(trackFrame);
        }
    }

    /**
     * He talked over Jarvis: what was handed over from headFrame on is dropped by the speaker; the speaker counts its
     * frames from newBaseFrame for what comes next.
     */
    void flushed(long headFrame, long newBaseFrame) {
        synchronized (ring) {
            long from = (long) Math.ceil(s16(headFrame));
            for (long i = Math.max(from, written16 - RING + 1); i < written16; i++) ring[(int) (i & MASK)] = 0;
            rs.reset();
            rsBaseOut = written16;
            rsBaseTrack = newBaseFrame;
            // (the play times stay: what the speaker had already taken still sounds for a moment, and the mic needs it)
        }
    }

    /** The stream sample at i (0 for what isn't Jarvis's voice: not yet written, or long gone). */
    private short at(long i) {
        if (i >= written16 || i <= written16 - RING || i < 0) return 0;
        return ring[(int) (i & MASK)];
    }

    // ================================================================ mic side

    /**
     * n samples of the mic (16 kHz; n a multiple of FRAME) whose first sample was heard at captureNanos; returns them
     * without Jarvis's echo (a new array, valid until the next call).
     */
    short[] process(short[] mic, int n, long captureNanos) {
        if (far.length < n) { far = new short[n]; lin = new short[n]; out = new short[n]; }
        synchronized (ring) {
            if (mapNanos == NONE) {
                java.util.Arrays.fill(far, 0, n, (short) 0); // Jarvis hasn't played anything yet
                readIdx = NONE;
                readBase = NONE;
            } else {
                long want = Math.round(mapS16 + (captureNanos + lead * (1e9 / RATE) - mapNanos) * (RATE / 1e9));
                long d = readIdx == NONE ? 0 : want - readIdx;
                if (readIdx == NONE || Math.abs(d) > RATE / 10) { // start, a new answer, or a big change: go there
                    readIdx = want;
                    offSmooth = 0;
                    miss = 0;
                } else if (Math.abs(d) > RATE * 8 / 1000) { // a gap in Jarvis's voice (or lost time): go there if it lasts
                    if (++miss >= 2) {
                        readIdx = want;
                        offSmooth = 0;
                        miss = 0;
                    }
                } else { // the clocks' slow drift, one sample at a time (their jitter averages out)
                    miss = 0;
                    offSmooth = .99 * offSmooth + .01 * d;
                    if (offSmooth > 3) { readIdx++; offSmooth -= 1; }
                    else if (offSmooth < -3) { readIdx--; offSmooth += 1; }
                }
                for (int i = 0; i < n; i++) far[i] = at(readIdx + i);
                readBase = readIdx;
                readIdx += n;
            }
        }
        long firstPos = readBase;
        boolean active;
        synchronized (ring) {
            if (written16 != lastWritten) { lastWritten = written16; idleCalls = 0; } else idleCalls++;
            active = idleCalls < 10; // Jarvis's voice came in the last ~0.4 s
        }
        for (int i = 0; i + FRAME <= n; i += FRAME) {
            ec.cancel(mic, i, far, i, lin, i);
            System.arraycopy(lin, i, out, i, FRAME);
            pp.run(out, i);
            measure(mic, far, lin, out, i, firstPos == NONE ? NONE : firstPos + i, active);
        }
        if (rec != null) record(mic, far, out, n);
        return out;
    }

    private static double energy(short[] s, int off, int len) {
        double e = 0;
        for (int i = 0; i < len; i++) e += (double) s[off + i] * s[off + i];
        return e / len;
    }

    private void measure(short[] mic, short[] farIn, short[] linOut, short[] o, int off, long streamPos, boolean active) {
        double fe = energy(farIn, off, FRAME), me = energy(mic, off, FRAME), oe = energy(o, off, FRAME);
        boolean loud = fe > 1000.0 * 1000.0; // Jarvis's voice clearly sounding (its digital level)
        boolean silent = fe < 50.0 * 50.0;
        double orms = Math.sqrt(oe);
        // for the delay search: the mic's loudness and where in Jarvis's stream it was lined up with
        histMic[histPos] = (float) Math.log10(me + 1);
        histStream[histPos] = streamPos;
        histPos = (histPos + 1) & (HIST - 1);
        if (histN < HIST) histN++;
        if (active) {
            activeFrames++;
            if (loud) activeLoud++;
            if (activeFrames - lastCheckFrame >= 250) checkAlignment(); // every 2 s of Jarvis talking
        }
        if (silent) {
            quietRun++;
            if (quietRun > 40) { // (its echo has died away)
                // the room: follows the quiet moments down fast, up slowly
                if (orms < noise) noise = .9 * noise + .1 * orms; else noise += (orms - noise) * .002;
                noise = Math.max(5, noise);
                if (orms > 5 * noise && orms > 150) his = .99 * his + .01 * orms; // his voice (alone)
            }
        } else {
            quietRun = 0;
        }
        if (probe != null) probe.add(new double[]{pp.speechProb, fe, me, oe});
        // his voice in the cleaned mic (any frame): for the classic talk-over, which decides itself (no Gemini to do it)
        boolean voiceNow = pp.speechProb > .85 && orms > Math.max(3 * Math.max(noise, 20), .25 * his);
        talkBits = (talkBits << 1) | (voiceNow ? 1 : 0);
        if (ec.resets != seenResets) { // the canceller started over: nothing measured before counts
            seenResets = ec.resets;
            restartMeasures();
        }
        if (!loud) return;
        farFrames++;
        // every loud frame (for the overall measure, which can't be fooled by what looks like his voice)
        allMic[posA] = me;
        allOut[posA] = oe;
        allLin[posA] = energy(linOut, off, FRAME);
        posA = (posA + 1) % allMic.length;
        if (nA < allMic.length) nA++;
        // his own voice over Jarvis's (sure speech, well above any leftover): not a measure of the leftover
        boolean hisVoice = pp.speechProb > .85 && orms > 2.5 * Math.max(noise, 20);
        if (hisRecent[hisPos]) hisCount--;
        hisRecent[hisPos] = hisVoice;
        if (hisVoice) hisCount++;
        hisPos = (hisPos + 1) % hisRecent.length;
        if (!hisVoice) {
            micE[posE] = me;
            leftE[posE] = oe;
            posE = (posE + 1) % micE.length;
            if (nE < micE.length) nE++;
        }
        if (farFrames % 25 == 0) decide(); // 3 times a second while Jarvis is loud
    }

    /** All measures start again (after a realignment or a restart of the canceller). */
    private void restartMeasures() {
        nE = 0;
        posE = 0;
        nA = 0;
        posA = 0;
        java.util.Arrays.fill(hisRecent, false);
        hisCount = 0;
        hisPos = 0;
        ready = false;
        erle = erleAll = erleLin = left90 = Double.NaN;
    }

    /**
     * Full talk is safe when, while Jarvis was loud, the mic's leftover stayed low: near the room's level or well under
     * his voice. And it ends at once when the leftover can't be trusted: much of what is left "sounds like his voice" for
     * long (in full talk Gemini stops Jarvis within a moment when he really talks, so that is Jarvis's own voice not taken out).
     */
    private void decide() {
        if (nA >= 50) {
            double sm = 0, so = 0, sl = 0;
            for (int i = 0; i < nA; i++) { sm += allMic[i]; so += allOut[i]; sl += allLin[i]; }
            erleAll = 10 * Math.log10(sm / Math.max(1, so));
            erleLin = 10 * Math.log10(sm / Math.max(1, sl)); // (the canceller alone: is the echo where it looks?)
        }
        boolean suspicious = hisCount >= hisRecent.length * 3 / 10;
        if (ready && suspicious) ready = false;
        double limit = Math.max(Math.max(3 * noise, .08 * his), 40);
        if (nE >= 125) {
            double[] left = new double[nE];
            double sm = 0, sl = 0;
            for (int i = 0; i < nE; i++) {
                left[i] = Math.sqrt(leftE[i]);
                sm += micE[i];
                sl += leftE[i];
            }
            java.util.Arrays.sort(left);
            double p90 = left[(int) Math.floor(.9 * (nE - 1))];
            double e = 10 * Math.log10(sm / Math.max(1, sl));
            erle = e;
            left90 = p90;
            if (debug != null) debug.append(String.format("[f%d p90 %.0f e %.1f all %.1f lim %.0f his %.0f noise %.0f hisFrames %d]", farFrames, p90, e, erleAll, limit, his, noise, hisCount));
            // (while he talks over Jarvis a lot, the leftover can't be told from his voice: it isn't trusted then)
            if (hisCount < hisRecent.length * 3 / 10) {
                if (!ready && p90 < limit && e > 20 && erleAll > 12) ready = true;
                else if (ready && (p90 > 1.25 * limit || e < 16)) ready = false;
            }
        }
    }

    /**
     * Every 2 s of Jarvis talking: where the learnt echo sits in the filter (too near its start - the echo comes earlier
     * than the clocks said - or too late: the reference is moved so the whole echo fits). If the canceller takes nothing
     * out, or what is read of Jarvis's voice is mostly silence while it plays, the delay is searched.
     */
    private void checkAlignment() {
        long loudShare = activeLoud * 100 / Math.max(1, activeFrames - lastCheckFrame);
        lastCheckFrame = activeFrames;
        activeLoud = 0;
        if (debug != null) debug.append(String.format("{check adapted %b lin %.1f all %.1f loud%% %d}", ec.adapted(), erleLin, erleAll, loudShare));
        if (!ec.adapted() || Double.isNaN(erleLin) || erleLin < 6 || loudShare < 15) {
            if (++blockChecks >= 2) searchDelay();
            return;
        }
        blockChecks = 0;
        if (erleLin < 10) return; // (moving the learnt echo only when the canceller clearly works)
        double[] be = ec.blockEnergy();
        int peak = 0;
        for (int i = 1; i < be.length; i++) if (be[i] > be[peak]) peak = i;
        int want = 3; // the echo's start about 24 ms into the filter
        if (debug != null) debug.append(String.format("{peak %d}", peak));
        if (peak <= 0 || peak >= be.length - 6) shift((want - peak) * FRAME);
    }

    /** Moves the reference (samples, in whole frames; positive: read further ahead), keeping what was learnt, moved along. */
    private void shift(int samples) {
        int maxLead = RATE * 2 / 5;
        int blocks = samples / FRAME;
        int lo = -Math.floorDiv(maxLead + lead, FRAME), hi = Math.floorDiv(maxLead - lead, FRAME);
        blocks = Math.max(lo, Math.min(hi, blocks));
        if (blocks == 0) return;
        lead += blocks * FRAME;
        if (ec.adapted()) {
            double[] w = ec.saveWeights();
            int n = 2 * FRAME, m = (TAIL + FRAME - 1) / FRAME, half = m * n;
            double[] moved = w.clone(); // (the leak and far-end power stay)
            java.util.Arrays.fill(moved, 0, 2 * half, 0);
            for (int part = 0; part < 2; part++) {
                for (int j = 0; j < m; j++) {
                    int to = j + blocks;
                    if (to < 0 || to >= m) continue;
                    System.arraycopy(w, part * half + j * n, moved, part * half + to * n, n);
                }
            }
            ec.reset();
            ec.loadWeights(moved);
        } else {
            ec.reset(); // (nothing learnt yet: it learns afresh in the new place)
        }
        seenResets = ec.resets;
        realigns++;
        restartMeasures();
        histN = 0; // (where the mic was lined up before the move no longer holds)
        synchronized (ring) { readIdx = NONE; }
    }

    // ---------------------------------------------------------------- delay search (when nothing is taken out)
    private static final int HIST = 512;                 // the last ~4 s of mic frames
    private final float[] histMic = new float[HIST];
    private final long[] histStream = new long[HIST];
    private int histPos, histN;
    private long activeFrames, activeLoud;

    /**
     * Where in Jarvis's stream the mic's loudness follows Jarvis's loudness best (the last ~2.5 s, up to 0.5 s either
     * way of where it is read now): the reference is moved there (when the match is clear).
     */
    private void searchDelay() {
        blockChecks = 0;
        int use = Math.min(histN, 320), maxD = 60;
        double best = -1, second = -1;
        int bestD = 0;
        double[] corr = new double[2 * maxD + 1];
        synchronized (ring) {
            for (int d = -maxD; d <= maxD; d++) {
                double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
                int cnt = 0;
                for (int k = 0; k < use; k++) {
                    int h = (histPos - use + k) & (HIST - 1);
                    long p = histStream[h];
                    if (p == NONE) continue;
                    long q = p + (long) d * FRAME;
                    if (q <= written16 - RING || q + FRAME > written16 || q < 0) continue;
                    double e = 0;
                    for (int i = 0; i < FRAME; i++) { double v = ring[(int) ((q + i) & MASK)]; e += v * v; }
                    double y = Math.log10(e / FRAME + 1), x = histMic[h];
                    sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
                    cnt++;
                }
                if (cnt < 150) { corr[d + maxD] = -1; continue; }
                double vx = sxx - sx * sx / cnt, vy = syy - sy * sy / cnt;
                corr[d + maxD] = vx <= 0 || vy <= 0 ? -1 : (sxy - sx * sy / cnt) / Math.sqrt(vx * vy);
            }
        }
        for (int d = -maxD; d <= maxD; d++) if (corr[d + maxD] > best) { best = corr[d + maxD]; bestD = d; }
        for (int d = -maxD; d <= maxD; d++) if (Math.abs(d - bestD) >= 6 && corr[d + maxD] > second) second = corr[d + maxD];
        if (debug != null) debug.append(String.format("{search d %d c %.2f next %.2f}", bestD, best, second));
        if (best < .4 || best - second < .08) return;
        // the echo matches the stream bestD frames from where it is read: put it about 3 frames into the filter
        shift((3 + bestD) * FRAME);
    }

    // ================================================================ results

    /** The mic is clean enough to go to Gemini while Jarvis speaks. */
    boolean ready() { return ready; }

    /** The last 32 frames (8 ms each, ~0.26 s) of the cleaned mic, one bit each: his voice in it. */
    private int talkBits;

    /** How many of the last 32 frames (~0.26 s) had his voice in the cleaned mic (mic thread). */
    int voiceFrames() { return Integer.bitCount(talkBits); }

    /** One line for "Jarvis చెక్". */
    String info() {
        String e = (Double.isNaN(erle) ? "-" : Math.round(erle) + " dB") + (Double.isNaN(erleAll) ? "" : " (మొత్తం " + Math.round(erleAll) + ")");
        String l = Double.isNaN(left90) ? "-" : String.valueOf(Math.round(left90));
        return "Jarvis గొంతు తీసివేత " + e + ", మిగిలింది " + l + " (గది " + Math.round(noise) + ", మీ గొంతు " + Math.round(his)
                + "), సమయం " + Math.round(lead * 1000.0 / RATE) + " ms" + (realigns > 0 ? ", సరిచేసింది " + realigns : "")
                + (ec.resets > 0 ? ", మళ్లీ మొదలు " + ec.resets : "");
    }

    /** The talk ended: the echo path learnt is kept for the next one (only if it was working). */
    void save() {
        synchronized (EchoGuard.class) {
            if (ec.adapted() && !Double.isNaN(erle) && erle > 12 && !Double.isNaN(erleAll) && erleAll > 10) {
                savedWeights = ec.saveWeights();
                savedLead = lead;
            }
            savedHis = his;
        }
    }

    /** The learnt echo path as bytes (kept in a file between app starts), or null when there is none yet. */
    static byte[] exportState() {
        synchronized (EchoGuard.class) {
            if (savedWeights == null) return null;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16 + 4 * savedWeights.length);
            b.putInt(0x4A454731).putInt(savedWeights.length).putInt(savedLead).putFloat((float) savedHis);
            for (double v : savedWeights) b.putFloat((float) v);
            return b.array();
        }
    }

    /** Takes back what exportState() gave (ignored if it doesn't fit this version). */
    static void importState(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return;
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
            if (b.getInt() != 0x4A454731) return;
            int n = b.getInt();
            int lead = b.getInt();
            float his = b.getFloat();
            int m = (TAIL + FRAME - 1) / FRAME;
            if (n != 2 * m * 2 * FRAME + 2 + FRAME + 1 || bytes.length != 16 + 4 * n || Math.abs(lead) > RATE * 2 / 5) return;
            double[] w = new double[n];
            for (int i = 0; i < n; i++) {
                w[i] = b.getFloat();
                if (Double.isNaN(w[i]) || Double.isInfinite(w[i])) return;
            }
            synchronized (EchoGuard.class) {
                if (savedWeights != null) return; // (this run already learnt one)
                savedWeights = w;
                savedLead = lead;
                if (his > 0 && his < 30000) savedHis = his;
            }
        } catch (Exception ignored) {}
    }

    private void record(short[] mic, short[] farIn, short[] o, int n) {
        for (int i = 0; i < n; i++) {
            rec[0][recPos] = mic[i];
            rec[1][recPos] = farIn[i];
            rec[2][recPos] = o[i];
            if (++recPos == rec[0].length) { recPos = 0; recFull = true; }
        }
    }

    /** The last 30 s as a 3-channel WAV (mic, Jarvis's voice lined up, cleaned), or null when not recording. */
    byte[] recording() {
        if (rec == null) return null;
        int len = recFull ? rec[0].length : recPos;
        if (len == 0) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + len * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + len * 6).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 3).putInt(RATE).putInt(RATE * 6).putShort((short) 6).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(len * 6);
        int start = recFull ? recPos : 0;
        for (int i = 0; i < len; i++) {
            int k = (start + i) % rec[0].length;
            b.putShort(rec[0][k]).putShort(rec[1][k]).putShort(rec[2][k]);
        }
        return b.array();
    }

    // ================================================================ 24 kHz -> 16 kHz

    /** Polyphase resampler 24 kHz -> 16 kHz (up 2, low-pass at 48 kHz, down 3), keeping its state between pieces. */
    static final class Down24 {
        private static final int L = 192, H = 1024;
        /** The filter's delay on the 48 kHz grid. */
        static final double DELAY48 = (L - 1) / 2.0;
        private static final double[] h = design();
        private final double[] hist = new double[H];
        private long nIn, mOut;

        private static double[] design() {
            double[] f = new double[L];
            double fc = 7400.0 / 48000.0, beta = 7.0, mid = (L - 1) / 2.0;
            double i0b = bessel0(beta);
            for (int i = 0; i < L; i++) {
                double t = i - mid;
                double sinc = t == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * t) / (Math.PI * t);
                double r = t / mid;
                double w = bessel0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0b;
                f[i] = sinc * w;
            }
            return f;
        }

        private static double bessel0(double x) {
            double sum = 1, term = 1, q = x * x / 4;
            for (int k = 1; k < 50; k++) {
                term *= q / ((double) k * k);
                sum += term;
                if (term < 1e-12 * sum) break;
            }
            return sum;
        }

        void reset() {
            java.util.Arrays.fill(hist, 0);
            nIn = 0;
            mOut = 0;
        }

        /** Adds frames of 16-bit little-endian PCM; writes the new 16 kHz samples to out and returns how many. */
        int push(byte[] pcm, int off, int frames, short[] out) {
            int k = 0;
            for (int f = 0; f < frames; f++) {
                int b = off + 2 * f;
                hist[(int) (nIn & (H - 1))] = (short) ((pcm[b] & 0xFF) | (pcm[b + 1] << 8));
                nIn++;
                while (true) {
                    long p = 3 * mOut; // position on the 48 kHz grid
                    if (p / 2 >= nIn) break;
                    double s = 0;
                    for (int j = (int) (p & 1); j < L; j += 2) {
                        long xi = (p - j) / 2;
                        if (xi < 0 || xi < nIn - H) break;
                        s += h[j] * hist[(int) (xi & (H - 1))];
                    }
                    if (k < out.length) out[k++] = clip(2 * s);
                    mOut++;
                }
            }
            return k;
        }

        private static short clip(double v) {
            return v > 32767 ? 32767 : v < -32768 ? -32768 : (short) Math.round(v);
        }
    }
}
