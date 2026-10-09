package com.anil.jarvis.watch;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** W56: the watch's recorder screen (start / stop and send / send again). */
public class Recorder extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView time, info, go, again;

    static void open(Context c) { c.startActivity(new Intent(c, Recorder.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.refresh(this);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF000000);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Math.round(w * 0.12f), Math.round(h * 0.14f), Math.round(w * 0.12f), Math.round(h * 0.22f));
        sc.addView(col);
        col.addView(WUi.text(this, "🎙️ రికార్డ్", 15, Theme.accent, true));
        time = WUi.text(this, "", 26, WUi.TEXT, true);
        col.addView(time);
        go = WUi.pill(this, "", 0xFF0E3A4A);
        go.setOnClickListener(v -> toggle());
        col.addView(go, new LinearLayout.LayoutParams(-1, -2));
        again = WUi.pill(this, "📤 మళ్లీ పంపు", 0xFF0B2230);
        again.setOnClickListener(v -> new Thread(() -> RecService.send(getApplicationContext())).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = WUi.dp(this, 6);
        col.addView(again, lp);
        info = WUi.text(this, "", 11.5f, WUi.MUTED, true);
        col.addView(info);
        col.addView(WUi.text(this, "మీటింగ్, ఆలోచనలు (30 నిమిషాల వరకు). ఆపగానే ఫోన్‌కి వెళ్తుంది; ఫోన్ తెలుగులో రాసి చిన్న సారాంశం ఇస్తుంది (OpenAI key తో).", 10.5f, WUi.FAINT, true));
        setContentView(sc);
        main.post(tick);
    }

    private void toggle() {
        if (RecService.recording) { RecService.stop(this); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        RecService.start(this);
        Talk.buzz(this, 30);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (RecService.recording) {
                long s = (System.currentTimeMillis() - RecService.startedAt) / 1000;
                time.setText(String.format(java.util.Locale.ENGLISH, "%d:%02d", s / 60, s % 60));
                go.setText("⏹ ఆపి ఫోన్‌కి పంపు");
            } else {
                time.setText("");
                go.setText("🎙️ మొదలుపెట్టు");
            }
            again.setVisibility(RecService.pending(Recorder.this) ? android.view.View.VISIBLE : android.view.View.GONE);
            info.setText(RecService.status);
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
