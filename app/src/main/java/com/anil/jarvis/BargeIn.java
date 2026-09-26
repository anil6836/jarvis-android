package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

/**
 * "Talk over Jarvis": while Jarvis is speaking, listens on the mic with echo cancellation and, when
 * Anil's voice comes in clearly louder than Jarvis's own echo for a moment, tells the caller to stop
 * speaking and listen. Energy based, so it works offline and needs no recognizer.
 */
final class BargeIn {
    interface Callback { void onVoice(); }

    private static final int RATE = 16000;
    private static final int FRAME = 320;          // 20 ms
    private static final int WARMUP_FRAMES = 20;   // first 0.4 s: learn how loud Jarvis's own echo is here

    // With the natural voice we know how loud Jarvis is at every moment, so we can predict its echo.
    private static final double REF_MIN_RMS = 700;  // quieter than this is never him talking
    private static final double REF_OVER = 2.2;     // his voice: this much above the predicted echo
    private static final int REF_NEED = 9;          // ~180 ms of that

    // The phone's own TTS voice gives no reference: follow the echo level itself.
    private static final double MIN_RMS = 1000;
    private static final double OVER_ECHO = 1.9;    // above the recent echo peaks
    private static final double DECAY = 0.993;      // echo peak memory: halves in about 2 s
    private static final int NEED_FRAMES = 12;      // ~240 ms

    /** Settings slider 0 (hard to interrupt) .. 4 (very easy); 2 is normal. */
    private static final double[] SCALE = {1.45, 1.2, 1.0, 0.85, 0.72};

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread thread;
    private volatile boolean running;

    BargeIn(Context c) { ctx = c.getApplicationContext(); }

    private boolean headset() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == 26 /* BLE headset */) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

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
            int needAdj = level <= 1 ? 3 - level * 2 : level >= 3 ? -(level - 2) * 2 : 0; // 3,1,0,-2,-4 frames
            boolean headset = headset();
            short[] buf = new short[FRAME];
            double floor = 0, noise = 0, gain = 0;
            int frames = 0, gainN = 0;               // gainN: frames the echo path was learned from
            double loud = 0;
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                double out = PlaybackLevel.now();
                frames++;
                boolean ref = out >= 0;
                boolean known = ref && gainN >= 10;     // until then, play it safe like the no-reference case
                if (frames <= WARMUP_FRAMES) {
                    floor = Math.max(floor, rms);
                    if (ref && out > 300) { gain = Math.max(gain, rms / out); gainN++; }
                    else if (ref) noise = Math.max(noise, rms);
                    continue;
                }
                double limit;
                int need;
                if (known) {
                    // Expected echo = how much of Jarvis's voice the mic picks up x how loud Jarvis is right now.
                    // In Jarvis's pauses that is ~0, so even a normal voice gets through.
                    double echo = gain * out;
                    limit = Math.max(Math.max(REF_MIN_RMS * k, noise * 2.5), echo * REF_OVER * k);
                    need = Math.max(5, REF_NEED + needAdj);
                } else {
                    double over = (headset ? 1.4 : OVER_ECHO) * k;
                    limit = Math.max(MIN_RMS * k, floor * over);
                    need = Math.max(6, NEED_FRAMES + needAdj);
                }
                if (rms > limit) {
                    loud += 1;
                    if (loud >= need) {
                        running = false;
                        main.post(cb::onVoice);
                        break;
                    }
                } else {
                    loud = Math.max(0, loud - 2); // a short dip inside a word does not reset everything
                    floor = Math.max(rms, floor * DECAY);
                    if (ref) {
                        if (out > 300) {
                            double g = rms / out;          // learn the echo path (only from non-voice frames)
                            gain = gainN == 0 ? g : g > gain ? gain * 0.9 + g * 0.1 : gain * 0.995 + g * 0.005;
                            gainN++;
                        } else {
                            noise = noise * 0.98 + rms * 0.02;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rec != null) { rec.stop(); } } catch (Exception ignored) {}
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            try { if (aec != null) aec.release(); } catch (Exception ignored) {}
            try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        }
    }
}
