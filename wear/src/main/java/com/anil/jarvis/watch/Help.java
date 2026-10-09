package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * W40: "🆘" held on the watch: 5 seconds to stop it, then the phone sends the SOS (location SMS to his family, a call to
 * the first). W42: the watch felt a hard fall and no movement: "బాగున్నారా?" on the wrist with strong vibrations; no
 * answer -> the phone sends the SOS after his fall wait (15 s / 30 s / 1 min, his setting on the phone).
 */
public class Help extends Activity {
    static final String EXTRA_MODE = "mode";
    private static Help shown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView title, msg, count;
    private String mode;
    private int left, rang;
    private boolean done;

    static void open(Context c, String mode) {
        c.startActivity(new Intent(c, Help.class).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** The phone says the fall question was answered there. */
    static void endFromPhone() {
        Help h = shown;
        if (h != null && "fall".equals(h.mode)) h.main.post(h::finish);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.refresh(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF1A0505);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Math.round(w * 0.12f), Math.round(h * 0.14f), Math.round(w * 0.12f), Math.round(h * 0.2f));
        sc.addView(col);
        title = WUi.text(this, "", 17, 0xFFFCA5A5, true);
        col.addView(title);
        count = WUi.text(this, "", 30, 0xFFFFFFFF, true);
        col.addView(count);
        msg = WUi.text(this, "", 12.5f, WUi.TEXT, true);
        col.addView(msg);
        setContentView(sc);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        shown = this;
        if ("fall".equals(mode)) {
            title.setText("బాగున్నారా?");
            left = Link.cfg(this).optInt("fall_secs", 30);
            msg.setText("గట్టిగా పడినట్టు అనిపించింది." + (left > 0 ? " జవాబు లేకపోతే ఫోన్ మీ వాళ్లకి SOS పంపుతుంది." : ""));
            pill(col, "✓ బాగున్నాను", 0xFF166534, () -> { send("fall_ok"); finish(); });
            pill(col, "🆘 సహాయం కావాలి", 0xFF991B1B, () -> { send("fall_send"); sent(); });
        } else {
            title.setText("🆘 SOS");
            left = 5;
            msg.setText("ఆపకపోతే ఫోన్ మీ వాళ్లకి లొకేషన్ SMS పంపి, మొదటివారికి కాల్ చేస్తుంది.");
            pill(col, "✗ ఆపు", 0xFF374151, () -> { done = true; Talk.buzz(this, 30); finish(); });
        }
        main.post(tick);
    }

    private void pill(LinearLayout col, String label, int color, Runnable r) {
        TextView p = WUi.pill(this, label, color);
        p.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = WUi.dp(this, 8);
        col.addView(p, lp);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (done) return;
            if ("fall".equals(mode)) {
                if (++rang > 150) return; // (about 2 minutes of buzzing is enough; the screen stays)
                Talk.buzzAs(Help.this, android.os.VibrationAttributes.USAGE_ALARM, 500, 200, 500);
                if (left > 0) count.setText(String.valueOf(left));
                else if (Link.cfg(Help.this).optInt("fall_secs", 30) > 0) { count.setText("…"); msg.setText("ఫోన్ SOS పంపుతోంది. బాగుంటే \"బాగున్నాను\" నొక్కండి."); }
                if (left > 0) left -= 1;
                main.postDelayed(this, 1000);
                return;
            }
            if (left <= 0) { send("sos"); sent(); return; }
            count.setText(String.valueOf(left));
            Talk.buzzAs(Help.this, android.os.VibrationAttributes.USAGE_ALARM, 250);
            left--;
            main.postDelayed(this, 1000);
        }
    };

    private void send(String what) {
        try {
            Link.send(this, Link.P_DO, new JSONObject().put("what", what), () -> {
                if (isDestroyed()) return;
                msg.setText("ఫోన్ అందడం లేదు! ఎవరినైనా పిలవండి, లేదా దగ్గర ఫోన్‌తో 112 కి కాల్ చేయండి.");
                Talk.buzzAs(this, android.os.VibrationAttributes.USAGE_ALARM, 800);
            });
        } catch (Exception ignored) {}
    }

    private void sent() {
        done = true;
        count.setText("📨");
        msg.setText("ఫోన్‌కి చెప్పాను: SOS వెళ్తోంది.");
        main.postDelayed(this::finish, 8000);
    }

    @Override protected void onDestroy() {
        if (shown == this) shown = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
