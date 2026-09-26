package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Locale;

/**
 * "Talk over Jarvis": while Jarvis is speaking, listens on the mic (echo cancelled) and, when Anil's
 * voice is clearly louder than what Jarvis's own echo can explain for a moment, tells the caller to stop
 * speaking and listen. Energy based, so it works offline and needs no recognizer.
 *
 * The echo is judged from statistics of the last few seconds that do NOT depend on earlier decisions
 * (a percentile of the echo level), so a bad first guess can never lock it into stopping on its own voice.
 */
final class BargeIn {
    interface Callback { void onVoice(); }

    private static final int RATE = 16000;
    private static final int FRAME = 320;           // 20 ms
    private static final int HIST = 150;            // 3 s of history for the echo statistics
    private static final int SETTLE_FRAMES = 30;    // 0.6 s of Jarvis actually sounding before any decision

    // With the natural voice we know how loud Jarvis is at every moment, so its echo can be predicted.
    private static final double PLAYING = 300;      // output louder than this = Jarvis is sounding
    private static final double LOUD_OUT = 1000;    // learn the echo path only from clearly loud output
    private static final double REF_OVER = 1.8;     // his voice: this much above the predicted (strong) echo
    private static final int REF_NEED = 10;         // ~200 ms of that
    private static final double MIN_RMS = 500;      // quieter than this is never him talking

    // The phone's own TTS voice gives no reference: compare with the recent echo level itself.
    private static final double OVER_ECHO = 2.0;
    private static final int NEED_FRAMES = 12;      // ~240 ms

    /** Settings slider 0 (hard to interrupt) .. 4 (very easy); 2 is normal. */
    private static final double[] SCALE = {1.6, 1.25, 1.0, 0.8, 0.65};
    private static final int[] NEED_ADJ = {4, 2, 0, -2, -4};

    /** What the detector saw last time (shown in Settings, to tune it). */
    static volatile String lastInfo = "";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread thread;
    private volatile boolean running;

    BargeIn(Context c) { ctx = c.getApplicationContext(); }

    void start(Callback cb) {
        stop();
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        running = true;
        Thread t = new Thread(() -> run(cb), "jarvis-bargein");
        thread = t;
        t.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    /** Rolling window of values with a percentile. */
    private static final class Window {
        final double[] v = new double[HIST];
        final double[] tmp = new double[HIST];
        int n, pos;
        void add(double x) { v[pos] = x; pos = (pos + 1) % HIST; if (n < HIST) n++; }
        double pct(double p) {
            if (n == 0) return 0;
            System.arraycopy(v, 0, tmp, 0, n);
            Arrays.sort(tmp, 0, n);
            return tmp[Math.min(n - 1, (int) Math.floor(p * (n - 1) + 0.5))];
        }
    }

    private void run(Callback cb) {
        AudioRecord rec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
            rec.startRecording();
            int level = Math.max(0, Math.min(4, new Prefs(ctx).bargeSens()));
            double k = SCALE[level];
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            boolean headset = CallMode.headset(am);
            boolean callPath = am != null && am.getMode() == AudioManager.MODE_IN_COMMUNICATION;
            boolean callVoice = callPath && new Prefs(ctx).bargeCallVoice();

            short[] buf = new short[FRAME];
            Window ratio = new Window();   // mic / Jarvis output, while Jarvis sounds (echo path strength)
            Window quiet = new Window();   // mic while Jarvis is silent (room noise)
            Window mic = new Window();     // mic, all frames (for the no-reference case)
            int frames = 0, sounding = 0, loud = 0;
            double peakRms = 0, peakLimit = 0;
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                double out = PlaybackLevel.now();
                boolean ref = out >= 0;
                frames++;

                double limit;
                int need;
                boolean ready;
                if (ref) {
                    double noise = quiet.n >= 5 ? quiet.pct(0.5) : 0;
                    if (out > PLAYING) sounding++;
                    if (out > LOUD_OUT) ratio.add(Math.sqrt(Math.max(0, rms * rms - noise * noise)) / out); // echo only, room noise removed
                    else if (out < PLAYING / 2) quiet.add(rms);
                    ready = sounding >= SETTLE_FRAMES && ratio.n >= 20;
                    double gain = ratio.pct(0.85);           // strong echo, not the average: safe against itself
                    limit = Math.max(Math.max(MIN_RMS, noise * 3), Math.hypot(gain * out * REF_OVER, noise)) * k;
                    need = Math.max(5, REF_NEED + NEED_ADJ[level]);
                } else {
                    mic.add(rms);
                    ready = frames >= 40;                     // Google TTS: onStart is when sound begins
                    double over = headset ? 1.6 : OVER_ECHO;
                    limit = Math.max(MIN_RMS * 1.5, mic.pct(0.85) * over) * k;
                    need = Math.max(6, NEED_FRAMES + NEED_ADJ[level]);
                }
                if (!ready) continue;
                if (rms > peakRms) { peakRms = rms; peakLimit = limit; }
                if (rms > limit) {
                    if (++loud >= need) {
                        lastInfo = info(ref, callPath, callVoice, rms, limit, peakRms, peakLimit, true);
                        running = false;
                        main.post(cb::onVoice);
                        return;
                    }
                } else {
                    loud = Math.max(0, loud - 1); // a short dip inside a word does not reset everything
                }
                if (frames % 25 == 0) lastInfo = info(ref, callPath, callVoice, rms, limit, peakRms, peakLimit, false);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rec != null) { rec.stop(); } } catch (Exception ignored) {}
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            try { if (aec != null) aec.release(); } catch (Exception ignored) {}
            try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        }
    }

    private static String info(boolean ref, boolean callPath, boolean callVoice, double rms, double limit, double peak, double peakLimit, boolean fired) {
        return String.format(Locale.ROOT, "%s · %s · %s · పెద్ద శబ్దం %.0f / హద్దు %.0f",
                fired ? "ఆగింది" : "ఆగలేదు", ref ? "సహజ గొంతు" : "ఫోన్ గొంతు", callPath ? (callVoice ? "కాల్ గొంతు" : "అసలు గొంతు + echo-cancel") : "normal",
                fired ? rms : peak, fired ? limit : peakLimit);
    }
}
