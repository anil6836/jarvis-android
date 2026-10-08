package com.anil.jarvis.watch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * The watch's mic, sent to the phone as it is heard (16 kHz mu-law, 100 ms a message; the phone does the hearing).
 *  - TALK: his question (a tap, the app opened, a follow-up): sent from the first moment, until the phone says he
 *    has finished (or 25 s).
 *  - RAISE: the wrist just came up: nothing is sent unless there is a voice within 4.5 s; then the phone listens for
 *    "Hey Jarvis" and, if it hears it, the same sound goes on as his question.
 *  - HOURS: his always-listening hours: each burst of voice goes to the phone the same way.
 *  - OVER: while Jarvis is speaking (W13): "Jarvis" over the answer stops it and he is heard.
 * One at a time: a new one ends the old.
 */
final class Mic {
    private Mic() {}

    static final int TALK = 0, RAISE = 1, HOURS = 2, OVER = 3;
    private static final int RATE = 16000, FRAME = 320, PACKET = 1600;

    /** The sound level 0..1 (the orb moves with it). */
    static volatile float level;
    private static volatile Session cur;
    private static int nextId = (int) (System.currentTimeMillis() / 1000 % 100_000) * 1000;
    /** Why the mic last failed (for the check block), empty when fine. */
    static volatile String lastError = "";

    private static final class Session {
        final int kind;
        final String why;
        volatile int id;
        volatile boolean stopped, woke, sending;
        volatile long wokeAt;
        Session(int kind, String why) { this.kind = kind; this.why = why; }
    }

    static boolean busy() { Session s = cur; return s != null && !s.stopped; }

    static int kind() { Session s = cur; return s == null || s.stopped ? -1 : s.kind; }

    /** Starts listening (any listen going on ends). */
    static void start(Context c, int kind, String why) {
        stop();
        Session s = new Session(kind, why);
        cur = s;
        final Context app = c.getApplicationContext();
        new Thread(() -> run(app, s), "jarvis-watch-mic").start();
    }

    static void stop() {
        Session s = cur;
        if (s != null) s.stopped = true;
        cur = null;
    }

    /** Stops only this kind of listen (e.g. the one over Jarvis's voice when it has finished speaking). */
    static void stopKind(int kind) {
        Session s = cur;
        if (s != null && s.kind == kind && !s.woke) stop();
    }

    /** The phone has heard enough of this one (he finished, or no "Jarvis" in it). */
    static void stop(int id) {
        Session s = cur;
        if (s == null || s.id != id) return;
        if (s.kind == TALK || s.woke) { stop(); return; }
        s.sending = false; // a wrist-raise / hours burst without "Jarvis": wait for the next voice (hours) or end (raise)
        if (s.kind == RAISE) stop();
    }

    /** The phone heard "Jarvis" in this sound: it goes on as his question. False: that sound had already ended here (its limit). */
    static boolean woke() {
        Session s = cur;
        if (s == null || s.stopped || !s.sending) return false;
        s.woke = true;
        s.wokeAt = SystemClock.elapsedRealtime();
        return true;
    }

    /** Listening to his question now (a tap / follow-up, or the sound after "Hey Jarvis"). */
    static boolean question() {
        Session s = cur;
        return s != null && !s.stopped && (s.kind == TALK || s.woke);
    }

    /** The id of the sound being sent now (-1 none). */
    static int currentId() {
        Session s = cur;
        return s == null || s.stopped || !s.sending ? -1 : s.id;
    }

    private static void begin(Context app, Session s) throws Exception {
        s.id = ++nextId;
        s.sending = true;
        Link.send(app, Link.P_MIC_START, new JSONObject().put("id", s.id).put("kind", s.kind == TALK ? "talk" : "wake").put("why", s.why));
    }

    private static void end(Context app, Session s) {
        if (!s.sending) return;
        s.sending = false;
        try { Link.send(app, Link.P_MIC_END, new JSONObject().put("id", s.id)); } catch (Exception ignored) {}
    }

    @SuppressLint({"MissingPermission", "WakelockTimeout"})
    private static void run(Context app, Session s) {
        AudioRecord rec = null;
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm != null) { // (the screen may go dark while he talks, or in his hours)
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:watchmic");
                wl.setReferenceCounted(false); // (taken again in his hours: one release lets it go)
                wl.acquire(s.kind == HOURS ? 15 * 60_000L : 60_000L);
            }
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 16));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("మైక్ తెరవలేకపోయాను");
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("మైక్ వేరే యాప్ దగ్గర ఉంది");
            short[] f = new short[FRAME];
            ArrayDeque<short[]> pre = new ArrayDeque<>();
            byte[] pk = new byte[PACKET];
            int pkLen = 0, seq = 0, loud = 0;
            double noise = 0;
            long start = SystemClock.elapsedRealtime(), sendStart = 0, lastVoice = 0, zeroSince = -1, lockAt = start;
            if (s.kind == TALK) { begin(app, s); sendStart = start; }
            while (!s.stopped) {
                int n = rec.read(f, 0, FRAME);
                if (n <= 0) throw new IllegalStateException("మైక్ చదవడం ఆగింది (" + n + ")");
                long now = SystemClock.elapsedRealtime();
                double sum = 0;
                boolean zero = true;
                for (int i = 0; i < n; i++) { sum += (double) f[i] * f[i]; if (f[i] != 0) zero = false; }
                // Android gives a mic it doesn't allow only silence (the app wasn't opened since the watch restarted)
                if (zero) { if (zeroSince < 0) zeroSince = now; else if (now - zeroSince > 1500) throw new IllegalStateException("BLOCKED"); }
                else zeroSince = -1;
                double rms = Math.sqrt(sum / n);
                level = (float) Math.max(0, Math.min(1, (20 * Math.log10(rms / 32768.0 + 1e-9) + 60) / 45));
                boolean voice = rms > Math.max(250, noise * 2.5);
                if (!voice) noise = noise == 0 ? rms : noise * 0.95 + rms * 0.05;
                else lastVoice = now;
                if (s.kind == HOURS && wl != null && now - lockAt > 10 * 60_000L) { wl.acquire(15 * 60_000L); lockAt = now; }
                if (!s.sending) {
                    pre.addLast(java.util.Arrays.copyOf(f, n));
                    while (pre.size() > 25) pre.removeFirst(); // half a second before the voice (the start of "Hey")
                    loud = voice ? loud + 1 : 0;
                    if (loud < 3) {
                        if (s.kind == RAISE && now - start > 4500) break; // wrist raised, nothing said
                        if (s.kind == HOURS && !Link.inHours(app)) break;
                        continue;
                    }
                    begin(app, s);
                    sendStart = now;
                    seq = 0;
                    pkLen = 0;
                    for (short[] p : pre) {
                        for (short v : p) {
                            pk[pkLen++] = Ulaw.encode(v);
                            if (pkLen == PACKET) { Link.send(app, Link.P_MIC_DATA, Ulaw.packet(s.id, seq++, pk, 0, pkLen)); pkLen = 0; }
                        }
                    }
                    pre.clear();
                    loud = 0;
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    pk[pkLen++] = Ulaw.encode(f[i]);
                    if (pkLen == PACKET) { Link.send(app, Link.P_MIC_DATA, Ulaw.packet(s.id, seq++, pk, 0, pkLen)); pkLen = 0; }
                }
                if (s.kind == TALK || s.woke) {
                    if (now - (s.woke ? s.wokeAt : sendStart) > 25_000) break; // one question at most this long
                } else if (now - sendStart > 12_000) {
                    // The phone says when a burst has no "Jarvis" (Mic.stop(id)) or that it has (woke); it decides on the
                    // sound itself, so nothing is cut short here. This is only a limit if its word never comes.
                    if (pkLen > 0) { Link.send(app, Link.P_MIC_DATA, Ulaw.packet(s.id, seq++, pk, 0, pkLen)); pkLen = 0; }
                    end(app, s);
                    if (s.kind == RAISE) break;
                }
            }
            if (s.sending && pkLen > 0) Link.send(app, Link.P_MIC_DATA, Ulaw.packet(s.id, seq, pk, 0, pkLen));
            boolean question = s.kind == TALK || s.woke;
            end(app, s);
            lastError = "";
            if (question && !s.stopped) Talk.micEnded(app); // (25 s: the phone writes out what it has)
        } catch (Exception e) {
            end(app, s);
            String m = String.valueOf(e.getMessage());
            lastError = "BLOCKED".equals(m) ? "వాచ్ మైక్ దొరకలేదు: వాచ్‌లో Jarvis ఒకసారి తెరవండి" : m;
            Talk.micFailed(app, s.kind, lastError);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) {}
                try { rec.release(); } catch (Exception ignored) {}
            }
            if (wl != null && wl.isHeld()) try { wl.release(); } catch (Exception ignored) {}
            if (cur == s) cur = null;
            level = 0;
        }
    }
}
