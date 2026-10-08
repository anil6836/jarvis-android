package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Full screen over the lock screen after a crash: a big "నేను బాగున్నాను" and the seconds left before the SOS. */
public class CrashActivity extends Activity {
    private static WeakReference<CrashActivity> showing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView left;

    static void closeIfShowing() {
        CrashActivity a = showing == null ? null : showing.get();
        if (a != null) a.runOnUiThread(a::finish);
    }

    /** After the SOS went out: call the first contact from this screen. False when the screen isn't up (or no call permission). */
    static boolean callIfShowing(String number) {
        CrashActivity a = showing == null ? null : showing.get();
        if (a == null || a.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false;
        a.runOnUiThread(() -> {
            try { a.startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) {}
        });
        return true;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!CrashAlert.active) { finish(); return; }
        showing = new WeakReference<>(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF7F1D1D);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, pad);
        TextView title = Ui.text(this, "🆘 బాగున్నారా?", 34, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        left = Ui.text(this, "", 20, 0xFFFDE68A);
        left.setGravity(Gravity.CENTER);
        left.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 36));
        box.addView(left);
        TextView ok = Ui.text(this, "✅  నేను బాగున్నాను", 26, 0xFFFFFFFF);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 26));
        ok.setBackground(Ui.grad(this, new int[]{0xFF16A34A, 0xFF15803D}, 24, null));
        ok.setOnClickListener(v -> { CrashAlert.ok(getApplicationContext()); finish(); });
        box.addView(ok, new LinearLayout.LayoutParams(-1, -2));
        TextView send = Ui.text(this, "🆘  ఇప్పుడే SOS పంపు", 20, 0xFFFFFFFF);
        send.setGravity(Gravity.CENTER);
        send.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        send.setBackground(Ui.glass(this, 24));
        send.setOnClickListener(v -> CrashAlert.send(getApplicationContext(), true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        box.addView(send, lp);
        setContentView(box);
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!CrashAlert.active) { left.setText("SOS పంపుతున్నాను / ఆపాను"); return; }
            if (CrashAlert.seconds <= 0) left.setText("సహాయం కావాలంటే కింద 'ఇప్పుడే SOS పంపు' నొక్కండి");
            else left.setText(CrashAlert.secondsLeft() + " సెకన్లలో మీ వాళ్లకి SOS వెళ్తుంది"
                    + ("fall".equals(CrashAlert.kind) ? "\n(బాగుంటే \"నేను బాగున్నాను, ఏమీ కాలేదు\" అని చెప్పినా చాలు)" : ""));
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (showing != null && showing.get() == this) showing = null;
        super.onDestroy();
    }
}
