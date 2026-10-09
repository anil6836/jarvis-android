package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** W66: "నా వాచ్ ఎక్కడ?" said to the phone: the watch rings, buzzes and flashes until tapped (a minute at most). */
public class FindWatch extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ringtone tone;
    private TextView t;
    private int n;

    static void ring(Context c) {
        c.startActivity(new Intent(c, FindWatch.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        t = WUi.text(this, "⌚ ఇక్కడ ఉన్నాను!\n\nనొక్కితే ఆగుతుంది", 18, 0xFF000000, true);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> finish());
        setContentView(t);
        try {
            tone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (tone != null) { tone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()); tone.play(); }
        } catch (Exception ignored) {}
        main.post(flash);
    }

    private final Runnable flash = new Runnable() {
        @Override public void run() {
            if (++n > 120) { finish(); return; } // (a minute)
            boolean on = n % 2 == 0;
            t.setBackgroundColor(on ? 0xFFFFFFFF : Theme.accent);
            if (n % 4 == 0) Talk.buzzAs(FindWatch.this, android.os.VibrationAttributes.USAGE_ALARM, 400);
            if (tone != null && !tone.isPlaying() && n % 10 == 0) try { tone.play(); } catch (Exception ignored) {}
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (tone != null) try { tone.stop(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
