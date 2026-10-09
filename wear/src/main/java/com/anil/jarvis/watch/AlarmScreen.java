package com.anil.jarvis.watch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * W19: Jarvis's alarm on the wrist only: a strong, repeating vibration (no sound, so only he wakes), with "ఆపు" and
 * "5 నిమిషాలు". The phone rings by itself if it isn't answered here in 3 minutes, so he never oversleeps.
 * W33: a watch timer that ends rings the same way ("local": only "ఆపు", nothing goes to the phone).
 */
public class AlarmScreen extends Activity {
    private static final String CH = "jarvis_watch_alarm";
    private static final int NOTE = 30;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static JSONObject ringing;

    /** The phone says it is time (main thread). */
    static void ring(Context c, JSONObject o) {
        Context app = c.getApplicationContext();
        JSONObject now = ringing;
        if (o.optBoolean("local") && now != null && !now.optBoolean("local")) {
            // his alarm is ringing: a timer that ends meanwhile is a buzz and a note (the alarm's answer still goes to the phone)
            Talk.buzzAs(app, VibrationAttributes.USAGE_ALARM, 400, 200, 400);
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.notify(NOTE + 1, new Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle(o.optString("title", "⏱️ టైమర్ అయిపోయింది")).setAutoCancel(true).build());
                } catch (Exception ignored) {}
            }
            return;
        }
        appCtx = app;
        ringing = o;
        vibrate(app, true);
        main.removeCallbacks(quiet);
        main.postDelayed(quiet, 3 * 60_000L); // the phone takes over then
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "Jarvis అలారం", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(false);
            ch.setSound(null, null);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            PendingIntent full = PendingIntent.getActivity(app, 31, new Intent(app, AlarmScreen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(app, CH).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(o.optString("title", "⏰ అలారం")).setContentText(o.optBoolean("snooze", true) ? "ఆపడానికి / 5 నిమిషాలకి నొక్కండి" : "ఆపడానికి నొక్కండి")
                    .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(full, true).setContentIntent(full).setOngoing(true).build();
            try { nm.notify(NOTE, n); } catch (Exception ignored) {}
        }
        try { app.startActivity(new Intent(app, AlarmScreen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    private static final Runnable quiet = () -> { if (ringing != null) stopped(); };

    private static AlarmScreen shown;
    private static android.content.Context appCtx;

    /** It stops here (3 minutes without an answer: the phone rings now; or the phone says so). */
    private static void stopped() {
        ringing = null;
        vibrate(null, false);
        if (appCtx != null) {
            NotificationManager nm = appCtx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTE);
        }
        AlarmScreen a = shown;
        if (a != null) a.finish();
    }

    /** The phone rang itself (no answer here in time): the watch stops. */
    static void stopFromPhone(String id) {
        JSONObject o = ringing;
        if (o != null && o.optString("id").equals(id)) { main.removeCallbacks(quiet); stopped(); }
    }

    private static Vibrator vib;

    private static void vibrate(Context app, boolean on) {
        if (!on) {
            if (vib != null) try { vib.cancel(); } catch (Exception ignored) {}
            return;
        }
        vib = app.getSystemService(Vibrator.class);
        if (vib == null || !vib.hasVibrator()) return;
        VibrationEffect e = VibrationEffect.createWaveform(new long[]{0, 900, 400, 900, 400, 1400, 700}, 0); // until answered
        try {
            if (Build.VERSION.SDK_INT >= 33) vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            else vib.vibrate(e, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Exception ex) {
            try { vib.vibrate(e); } catch (Exception ignored) {}
        }
    }

    private static void answer(Context c, String act) {
        JSONObject o = ringing;
        ringing = null;
        main.removeCallbacks(quiet);
        vibrate(null, false);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE);
        if (o == null || o.optBoolean("local")) return; // (a watch timer: nothing to tell the phone)
        try {
            Link.send(c, Link.P_ALARM_ANSWER, new JSONObject().put("id", o.optString("id")).put("count", o.optInt("count")).put("act", act));
        } catch (Exception ignored) {}
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ringing == null) { finish(); return; }
        shown = this;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF000000);
        int pad = dp(26);
        box.setPadding(pad, pad, pad, pad);
        Calendar now = Calendar.getInstance();
        TextView time = text(String.format(Locale.ROOT, "%d:%02d", now.get(Calendar.HOUR) == 0 ? 12 : now.get(Calendar.HOUR), now.get(Calendar.MINUTE)), 34, Theme.accent);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(time);
        box.addView(text(ringing.optString("title", "⏰ అలారం"), 15, 0xFFDCEEF5));
        TextView stop = pill("⏹ ఆపు", 0xFF166534);
        stop.setOnClickListener(v -> { answer(this, "stop"); finish(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        box.addView(stop, lp);
        if (ringing.optBoolean("snooze", true)) { // (after 3 snoozes there is no more: as on the phone)
            TextView snooze = pill("😴 5 నిమిషాలు", 0xFF1E3A5F);
            snooze.setOnClickListener(v -> { answer(this, "snooze"); finish(); });
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
            lp2.topMargin = dp(8);
            box.addView(snooze, lp2);
        }
        setContentView(box);
    }

    private Gestures gestures;

    @Override protected void onResume() {
        super.onResume();
        // W59: a sharp flick of the wrist -> 5 more minutes (when snoozing is still allowed)
        gestures = Gestures.start(this, new Gestures.Out() {
            @Override public void twist() {}
            @Override public void flick() { JSONObject o = ringing; if (o != null && o.optBoolean("snooze", true)) { answer(AlarmScreen.this, "snooze"); finish(); } }
        });
    }

    @Override protected void onPause() {
        if (gestures != null) { gestures.stop(); gestures = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView pill(String label, int color) {
        TextView t = text(label, 16, 0xFFFFFFFF);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(color);
        t.setBackground(g);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
