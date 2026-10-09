package com.anil.jarvis.watch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
 * The fall question comes from the background, so it is a full-screen alarm notification (Android doesn't let a background
 * service open a screen), and the vibrations run here, whether the screen managed to open or not.
 */
public class Help extends Activity {
    static final String EXTRA_MODE = "mode";
    private static final String CH = "jarvis_help";
    private static final int NOTE = 41;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Help shown;
    // the fall question (one at a time), kept outside the screen
    private static volatile boolean asking, phoneLost;
    private static int fallLeft, rang;
    private static Context appCtx;
    private static android.os.PowerManager.WakeLock wake;

    private TextView title, msg, count;
    private String mode;
    private int left;
    private boolean done;

    static void open(Context c, String mode) {
        Context app = c.getApplicationContext();
        if ("fall".equals(mode)) {
            appCtx = app;
            asking = true; // (each fall asks afresh: buzzing and the wait from the start)
            phoneLost = false;
            rang = 0;
            fallLeft = Link.cfg(app).optInt("fall_secs", 30);
            main.removeCallbacks(fallTick);
            main.post(fallTick);
            try { // the watch kept awake while it asks (else, asleep, the buzzing and the count would stop)
                if (wake == null) {
                    wake = app.getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:fall");
                    wake.setReferenceCounted(false);
                }
                wake.acquire(3 * 60_000L);
            } catch (Exception ignored) {}
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    NotificationChannel ch = new NotificationChannel(CH, "Jarvis బాగున్నారా?", NotificationManager.IMPORTANCE_HIGH);
                    ch.enableVibration(false); // (the vibrations are ours)
                    ch.setSound(null, null);
                    ch.setBypassDnd(true);
                    nm.createNotificationChannel(ch);
                    PendingIntent full = PendingIntent.getActivity(app, 41, new Intent(app, Help.class).putExtra(EXTRA_MODE, "fall")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    nm.notify(NOTE, new Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle("🆘 బాగున్నారా?").setContentText("గట్టిగా పడినట్టు అనిపించింది. నొక్కి జవాబివ్వండి")
                            .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(full, true).setContentIntent(full).setOngoing(true).build());
                } catch (Exception ignored) {}
            }
        }
        try {
            app.startActivity(new Intent(app, Help.class).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {} // (from the background Android may refuse: the notification above opens it)
    }

    /** The fall message didn't reach the phone: no SOS will go from there. */
    static void phoneUnreachable() {
        main.post(() -> {
            phoneLost = true;
            Help h = shown;
            if (h != null && "fall".equals(h.mode)) h.showLost();
        });
    }

    /** The vibrations of the fall question, about 2 minutes at most (the question stays until it is answered). */
    private static final Runnable fallTick = new Runnable() {
        @Override public void run() {
            if (!asking || appCtx == null) return;
            if (++rang > 150) { try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {} return; }
            Talk.buzzAs(appCtx, android.os.VibrationAttributes.USAGE_ALARM, 500, 200, 500);
            if (fallLeft > 0) fallLeft--;
            Help h = shown;
            if (h != null && "fall".equals(h.mode)) h.showFall();
            main.postDelayed(this, 1000);
        }
    };

    /** The question is over (answered here or on the phone, or the SOS went). */
    private static void endFall() {
        asking = false;
        main.removeCallbacks(fallTick);
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        Context app = appCtx;
        if (app != null) {
            try { app.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
        }
    }

    /** The phone says the fall question was answered there. */
    static void endFromPhone() {
        main.post(() -> {
            endFall();
            Help h = shown;
            if (h != null && "fall".equals(h.mode)) h.finish();
        });
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
            if (!asking) { finish(); return; } // (an old notification tapped after it was over)
            pill(col, "✓ బాగున్నాను", 0xFF166534, () -> { endFall(); send("fall_ok"); finish(); });
            pill(col, "🆘 సహాయం కావాలి", 0xFF991B1B, () -> { endFall(); send("fall_send"); sent(); });
            showFall();
            return;
        }
        title.setText("🆘 SOS");
        left = 5;
        msg.setText("ఆపకపోతే ఫోన్ మీ వాళ్లకి లొకేషన్ SMS పంపి, మొదటివారికి కాల్ చేస్తుంది.");
        pill(col, "✗ ఆపు", 0xFF374151, () -> { done = true; Talk.buzz(this, 30); finish(); });
        main.post(tick);
    }

    private void showFall() {
        if (done || count == null) return;
        if (phoneLost) { showLost(); return; }
        boolean sos = Link.cfg(this).optInt("fall_secs", 30) > 0;
        if (fallLeft > 0) {
            count.setText(String.valueOf(fallLeft));
            msg.setText("గట్టిగా పడినట్టు అనిపించింది." + (sos ? " జవాబు లేకపోతే ఫోన్ మీ వాళ్లకి SOS పంపుతుంది." : ""));
        } else if (sos) {
            count.setText("…");
            msg.setText("జవాబు లేదు: ఫోన్ మీ వాళ్లకి SOS పంపుతుంది. బాగుంటే \"బాగున్నాను\" నొక్కండి.");
        } else {
            count.setText("");
            msg.setText("గట్టిగా పడినట్టు అనిపించింది. సహాయం కావాలంటే కింద నొక్కండి.");
        }
    }

    private void showLost() {
        if (count == null) return;
        count.setText("⚠️");
        msg.setText("ఫోన్ అందడం లేదు: ఫోన్ SOS పంపలేదు! ఎవరినైనా పిలవండి, లేదా దగ్గర ఫోన్‌తో 112 కి కాల్ చేయండి.");
    }

    private void pill(LinearLayout col, String label, int color, Runnable r) {
        TextView p = WUi.pill(this, label, color);
        p.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = WUi.dp(this, 8);
        col.addView(p, lp);
    }

    /** The 🆘 hold: 5 seconds to stop it (this screen was opened by his own tap). */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (done) return;
            if (left <= 0) { send("sos"); sent(); return; }
            count.setText(String.valueOf(left));
            Talk.buzzAs(Help.this, android.os.VibrationAttributes.USAGE_ALARM, 250);
            left--;
            main.postDelayed(this, 1000);
        }
    };

    private void send(String what) {
        try {
            Link.urgent(this, Link.P_DO, new JSONObject().put("what", what), () -> {
                if (isDestroyed()) return;
                showLost();
                done = true;
                Talk.buzzAs(this, android.os.VibrationAttributes.USAGE_ALARM, 800);
                main.removeCallbacksAndMessages(this);
            });
        } catch (Exception ignored) {}
    }

    private void sent() {
        done = true;
        count.setText("📨");
        msg.setText("ఫోన్‌కి చెప్పాను: SOS వెళ్తోంది.");
        main.postAtTime(this::finishIfSent, this, android.os.SystemClock.uptimeMillis() + 8000);
    }

    /** (the screen stays when the phone couldn't be reached: the 112 words must be seen) */
    private void finishIfSent() {
        if (!"⚠️".contentEquals(count.getText())) finish();
    }

    @Override protected void onDestroy() {
        if (shown == this) shown = null;
        main.removeCallbacks(tick);
        main.removeCallbacksAndMessages(this);
        super.onDestroy();
    }
}
