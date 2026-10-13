package com.anil.jarvis;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

/**
 * 🕵️ "ఇక్కడ ఏం జరిగింది?": the reconstructed story as numbered step cards over the photo grid, played step by step
 * (▶️ reads them one after another, ⏭ the next one). An estimate, said so on the screen.
 */
public class TimelineActivity extends Activity {
    static final String EXTRA_FILE = "file";
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject recon;
    private final java.util.List<View> stepViews = new java.util.ArrayList<>();
    private int at = -1;
    private boolean playing;
    private TextView play;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        String path = getIntent().getStringExtra(EXTRA_FILE);
        try { recon = new JSONObject(new String(Files.readAllBytes(new File(path).toPath()), "UTF-8")); } catch (Exception e) { recon = null; }
        if (recon == null) { Toast.makeText(this, "కథ దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        int pad = Ui.dp(this, 14);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        x.setOnClickListener(v -> finish());
        bar.addView(x);
        TextView t = Ui.mono(this, "🕵️ " + recon.optString("title"), 14, Ui.CYAN);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        play = Ui.text(this, "▶️", 20, 0xFFFFFFFF);
        play.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        play.setOnClickListener(v -> { if (playing) { playing = false; Announcer.stop(); play.setText("▶️"); } else { playing = true; play.setText("⏸"); at = -1; next(play); } });
        bar.addView(play);
        TextView nxt = Ui.text(this, "⏭", 20, 0xFFFFFFFF);
        nxt.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        nxt.setOnClickListener(v -> { playing = false; play.setText("▶️"); next(null); });
        bar.addView(nxt);
        page.addView(bar);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, 0, pad, Ui.dp(this, 24));
        File pic = new File(path.replace(".json", ".jpg"));
        if (pic.exists()) {
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bm = BitmapFactory.decodeFile(pic.getPath());
            if (bm != null) iv.setImageBitmap(JarvisCamera.scaleTo(bm, 1200));
            iv.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x55), 12));
            list.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView story = Ui.text(this, recon.optString("story"), 15, 0xFFFFFFFF);
        story.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        story.setLineSpacing(0, 1.2f);
        list.addView(story);
        JSONArray steps = recon.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
            card.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Ui.dp(this, 6), 0, 0);
            card.setLayoutParams(lp);
            TextView head = Ui.text(this, (i + 1) + ". " + (s.optString("when").isEmpty() ? "" : s.optString("when") + " · ") + s.optString("what"), 15, 0xFFFFFFFF);
            head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(head);
            if (!s.optString("evidence").isEmpty()) card.addView(Ui.text(this, "ఆధారం: " + s.optString("evidence"), 13, Ui.MUTED));
            int sure = s.optInt("sure", -1);
            if (sure >= 0) card.addView(Ui.text(this, "నమ్మకం: " + sure + "%", 12.5f, sure >= 70 ? Ui.C_GREEN : sure >= 40 ? Ui.C_AMBER : 0xFFFF8A80));
            final int idx = i;
            card.setOnClickListener(v -> { playing = false; at = idx; mark(); Announcer.sayDirect(this, head.getText().toString()); });
            list.addView(card);
            stepViews.add(card);
        }
        if (!recon.optString("caution").isEmpty()) {
            TextView c = Ui.text(this, "⚠️ " + recon.optString("caution"), 14, 0xFFFFD08A);
            c.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
            c.setBackground(Ui.round(this, 0xFF2A1A05, Ui.alpha(Ui.C_AMBER, 0xAA), 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Ui.dp(this, 10), 0, 0);
            c.setLayoutParams(lp);
            list.addView(c);
        }
        int conf = recon.optInt("confidence", -1);
        TextView note = Ui.text(this, "ఇది " + recon.optInt("photos", 0) + " బొమ్మల నుంచి Jarvis అంచనా" + (conf >= 0 ? " (సుమారు " + conf + "% నమ్మకం)" : "") + ". ఎవరినీ గుర్తించదు, నిందించదు; ముఖ్యమైన విషయాలైతే మీరే చూసి నిర్ధారించుకోండి.", 12.5f, Ui.MUTED);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        list.addView(note);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private void mark() {
        for (int i = 0; i < stepViews.size(); i++) stepViews.get(i).setBackground(Ui.round(this, i == at ? 0xFF14324D : 0xFF0B1E31, i == at ? Ui.C_AMBER : Ui.alpha(Ui.CYAN, 0x66), 14));
    }

    private void next(TextView play) {
        JSONArray steps = recon.optJSONArray("steps");
        if (steps == null || steps.length() == 0) { Announcer.sayDirect(this, recon.optString("story")); return; }
        at = (at + 1) % steps.length();
        mark();
        JSONObject s = steps.optJSONObject(at);
        String line = (at + 1) + ". " + (s == null ? "" : (s.optString("when").isEmpty() ? "" : s.optString("when") + ". ") + s.optString("what"));
        if (at == 0 && playing) line = recon.optString("story") + " " + line;
        Announcer.sayDirect(this, line);
        if (playing) {
            if (at == steps.length() - 1) { playing = false; if (play != null) play.setText("▶️"); if (!recon.optString("caution").isEmpty()) main.postDelayed(() -> Announcer.sayDirect(this, "జాగ్రత్త: " + recon.optString("caution")), 5000); return; }
            main.postDelayed(() -> { if (playing) next(play); }, 5500 + line.length() * 60L);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        playing = false;
        main.removeCallbacksAndMessages(null); // (the caution line 5 s later)
        Announcer.stop();
        if (play != null) play.setText("▶️");
    }
}
