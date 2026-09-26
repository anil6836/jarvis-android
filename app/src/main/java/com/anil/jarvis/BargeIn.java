package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
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
    private static final int NEED_FRAMES = 9;      // ~180 ms of speech in a row
    private static final int WARMUP_FRAMES = 20;   // first 400 ms: learn the echo level
    private static final double MIN_RMS = 900;     // quieter than this is never him talking

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
            short[] buf = new short[FRAME];
            double floor = 0;
            int frames = 0, loud = 0;
            while (running) {
                int n = rec.read(buf, 0, FRAME);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                frames++;
                if (frames <= WARMUP_FRAMES) { floor = Math.max(floor, rms); continue; }
                // his voice: clearly above the echo that remains, for a moment
                if (rms > Math.max(MIN_RMS, floor * 3.0)) {
                    if (++loud >= NEED_FRAMES) {
                        running = false;
                        main.post(cb::onVoice);
                        break;
                    }
                } else {
                    loud = 0;
                    floor = floor * 0.98 + rms * 0.02; // follow Jarvis's echo level slowly
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
