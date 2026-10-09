package com.anil.jarvis;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;

/**
 * W65 at home: the guard phone's "🎤 … కి చెప్పు" button: someone at home speaks up to 30 seconds and it goes to his
 * Telegram (his main phone's Jarvis tells him, and his watch). Needs internet on this phone.
 */
public class HomeTalkActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder rec;
    private File file;
    private TextView info;
    private Button go;
    private long startedAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(48, 48, 48, 48);
        col.setBackgroundColor(Color.BLACK);
        TextView t = new TextView(this);
        t.setText("🎤 " + new Prefs(this).name() + " కి వాయిస్ మెసేజ్");
        t.setTextSize(24);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        info = new TextView(this);
        info.setTextSize(18);
        info.setTextColor(0xFFB0C4CC);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 32, 0, 32);
        col.addView(info);
        go = new Button(this);
        go.setTextSize(22);
        col.addView(go, new LinearLayout.LayoutParams(-1, 220));
        setContentView(col);
        idle();
    }

    private void idle() {
        info.setText("నొక్కి మాట్లాడండి (30 సెకన్ల వరకు). అయ్యాక మళ్లీ నొక్కితే వెళ్తుంది.");
        go.setText("🎤 మాట్లాడండి");
        go.setOnClickListener(v -> start());
    }

    private void start() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        try {
            file = new File(getCacheDir(), "home_out.m4a");
            rec = new MediaRecorder(this);
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(32000);
            rec.setMaxDuration(30_000);
            rec.setOutputFile(file.getAbsolutePath());
            rec.setOnInfoListener((r, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(this::send); });
            rec.prepare();
            rec.start();
            startedAt = System.currentTimeMillis();
            info.setText("వింటున్నాను… అయ్యాక నొక్కండి");
            go.setText("📤 పంపు");
            go.setOnClickListener(v -> send());
        } catch (Exception e) {
            info.setText("మైక్ మొదలవలేదు: " + e.getMessage());
        }
    }

    private void send() {
        if (rec == null) return;
        try { rec.stop(); } catch (Exception ignored) {}
        try { rec.release(); } catch (Exception ignored) {}
        rec = null;
        if (System.currentTimeMillis() - startedAt < 1200) { idle(); return; }
        info.setText("పంపుతున్నాను…");
        go.setEnabled(false);
        new Thread(() -> {
            boolean ok;
            try { ok = HomeLink.sendVoiceFromHome(getApplicationContext(), Files.readAllBytes(file.toPath())); } catch (Exception e) { ok = false; }
            final boolean sent = ok;
            main.post(() -> {
                go.setEnabled(true);
                info.setText(sent ? "✓ పంపాను" : "పంపలేకపోయాను (ఈ ఫోన్‌కి నెట్ ఉందా?)");
                if (sent) main.postDelayed(this::finish, 2500); else idle();
            });
        }, "home-talk").start();
    }

    @Override protected void onDestroy() {
        if (rec != null) try { rec.release(); } catch (Exception ignored) {}
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
