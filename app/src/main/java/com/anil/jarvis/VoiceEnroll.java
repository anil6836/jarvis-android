package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Teaches Jarvis Anil's voice: he says "Jarvis" five times; the average voice vector is saved. */
final class VoiceEnroll {
    private VoiceEnroll() {}

    private static volatile boolean cancel;

    static void start(Activity a) {
        TextView msg = Ui.text(a, "సిద్ధం చేస్తున్నాను…", 17, Ui.TEXT);
        int p = Ui.dp(a, 20);
        msg.setPadding(p, p, p, p);
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("మీ గొంతు నేర్పించండి")
                .setView(msg)
                .setNegativeButton("ఆపు", (x, w) -> cancel = true)
                .setCancelable(false)
                .create();
        d.show();
        cancel = false;
        WakeService.stop(a); // free the microphone
        new Thread(() -> run(a, msg, d), "jarvis-enroll").start();
    }

    private static void show(Activity a, TextView t, String s) {
        a.runOnUiThread(() -> t.setText(s));
    }

    @SuppressLint("MissingPermission")
    private static void run(Activity a, TextView t, AlertDialog d) {
        org.vosk.Model model = null;
        org.vosk.SpeakerModel spk = null;
        AudioRecord rec = null;
        try {
            SystemClock.sleep(600);
            java.io.File md = VoskModel.ensure(a, s -> show(a, t, s));
            java.io.File sd = VoskModel.ensureSpk(a, s -> show(a, t, s));
            model = new org.vosk.Model(md.getAbsolutePath());
            spk = new org.vosk.SpeakerModel(sd.getAbsolutePath());
            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
            rec.startRecording();
            List<float[]> got = new ArrayList<>();
            byte[] buf = new byte[3200];
            for (int i = 1; i <= 8 && got.size() < 5 && !cancel; i++) {
                show(a, t, "ఇప్పుడు సహజంగా \"Jarvis\" అనండి…\n\n(" + (got.size() + 1) + " / 5)");
                try (org.vosk.Recognizer r = new org.vosk.Recognizer(model, rate, "[\"jarvis\", \"hey jarvis\", \"[unk]\"]")) {
                    r.setSpeakerModel(spk);
                    float[] v = null;
                    long end = SystemClock.elapsedRealtime() + 3500;
                    while (SystemClock.elapsedRealtime() < end && !cancel) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n <= 0) continue;
                        if (r.acceptWaveForm(buf, n)) {
                            v = VoiceLock.vector(r.getResult());
                            if (v != null) break;
                        }
                    }
                    if (v == null) v = VoiceLock.vector(r.getFinalResult());
                    if (v != null) {
                        got.add(VoiceLock.unit(v));
                        show(a, t, "బాగుంది ✓");
                    } else {
                        show(a, t, "వినపడలేదు, మళ్లీ…");
                    }
                    SystemClock.sleep(700);
                }
            }
            if (cancel) return;
            if (got.size() < 3) {
                show(a, t, "సరిగా వినపడలేదు. నిశ్శబ్దంగా ఉన్న చోట మళ్లీ ప్రయత్నించండి.");
                return;
            }
            float[] avg = new float[got.get(0).length];
            for (float[] v : got) for (int k = 0; k < avg.length; k++) avg[k] += v[k] / got.size();
            double spread = 0;
            for (float[] v : got) spread = Math.max(spread, VoiceLock.distance(v, avg));
            // allow a little more than his own samples differ from each other
            float max = (float) Math.max(0.35, Math.min(0.8, spread * 1.5 + 0.1));
            VoiceLock.save(a, avg, max);
            show(a, t, "మీ గొంతు నేర్చుకున్నాను ✓\n\nఇప్పుడు \"నా గొంతుకి మాత్రమే పలుకు\" ఆన్ చేసి సేవ్ చేయండి.");
        } catch (Throwable e) {
            show(a, t, "కుదరలేదు: " + e.getMessage());
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
            try { if (spk != null) spk.close(); } catch (Throwable ignored) {}
            try { if (model != null) model.close(); } catch (Throwable ignored) {}
            a.runOnUiThread(() -> {
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("మూసేయి");
            });
        }
    }
}
