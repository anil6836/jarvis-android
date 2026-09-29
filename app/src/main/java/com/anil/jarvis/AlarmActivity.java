package com.anil.jarvis;

import android.app.Activity;
import android.graphics.Typeface;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/** The alarm ringing: his song, getting louder; "ఆపు" says good morning with the weather and today's duty; "5 నిమి" snoozes. */
public class AlarmActivity extends Activity {
    private MediaPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String id;
    private int count;
    private float vol = 0.15f;
    private boolean handled;
    private android.os.Vibrator vib;
    private static java.lang.ref.WeakReference<AlarmActivity> showing;

    /** "ఆపు" / "5 నిమిషాలు" on the notification: the screen (if open) goes quiet and closes. */
    static void stopRinging() {
        AlarmActivity a = showing == null ? null : showing.get();
        if (a != null) a.runOnUiThread(() -> { a.handled = true; a.silence(); a.finish(); });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        id = getIntent().getStringExtra(SongAlarm.EXTRA_ID);
        count = getIntent().getIntExtra(SongAlarm.EXTRA_COUNT, 0);
        JSONObject o = id == null ? null : SongAlarm.find(this, id);
        Announcer.stop();
        showing = new java.lang.ref.WeakReference<>(this);
        SongAlarm.clearRinging(this); // the screen is up: the notification's own ringing stops, the song takes over

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.BG_TOP);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, pad);
        TextView time = Ui.text(this, String.format(Locale.ENGLISH, "%d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute()), 64, 0xFFFFFFFF);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setGravity(Gravity.CENTER);
        box.addView(time);
        String label = o == null ? "" : o.optString("label");
        TextView sub = Ui.text(this, "⏰ " + (label.isEmpty() ? "శుభోదయం!" : label), 20, Ui.C_CYAN);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 40));
        box.addView(sub);
        TextView stop = Ui.text(this, "⏹  ఆపు", 24, 0xFFFFFFFF);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        stop.setBackground(Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 24, null));
        stop.setOnClickListener(v -> stopAndGreet());
        box.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        TextView snooze = Ui.text(this, "😴  5 నిమిషాలు", 20, 0xFFFFFFFF);
        snooze.setGravity(Gravity.CENTER);
        snooze.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        snooze.setBackground(Ui.glass(this, 24));
        snooze.setOnClickListener(v -> doSnooze(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        box.addView(snooze, lp);
        setContentView(box);
        play();
        main.postDelayed(() -> doSnooze(10), 5 * 60000L); // not answered in 5 minutes: again in 10 (3 times at most)
    }

    private void doSnooze(int minutes) {
        if (handled) return;
        handled = true;
        if (id != null) SongAlarm.snooze(this, id, minutes, count);
        silence();
        finish();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); // another alarm while this one rings: it was already set for next time; keep ringing
        setIntent(intent);
    }

    /** Home pressed while ringing: don't leave a song playing with no screen; ring again in 5 minutes. */
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        doSnooze(5);
    }

    private void play() {
        Prefs p = new Prefs(this);
        Uri song = null;
        if (!p.alarmSong().isEmpty()) song = Uri.parse(p.alarmSong());
        if (!tryPlay(song)) {
            Uri tone = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            tryPlay(tone);
        }
        try { // vibrate too (and it is all there is if no sound would play)
            vib = getSystemService(android.os.Vibrator.class);
            if (vib != null) vib.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 700, 700}, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Exception ignored) {}
        // start soft, get louder over half a minute
        main.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                vol = Math.min(1f, vol + 0.06f);
                try { player.setVolume(vol, vol); } catch (Exception ignored) {}
                if (vol < 1f) main.postDelayed(this, 2000);
            }
        });
    }

    private boolean tryPlay(Uri uri) {
        if (uri == null) return false;
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.setVolume(vol, vol);
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            try { mp.release(); } catch (Exception ignored) {}
            return false;
        }
    }

    private void silence() {
        main.removeCallbacksAndMessages(null);
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) {}
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    /** Good morning, the weather, and today's duty. */
    private void stopAndGreet() {
        if (handled) return;
        handled = true;
        silence();
        greet(getApplicationContext());
        finish();
    }

    static void greet(android.content.Context app) {
        new Thread(() -> {
            Prefs p = new Prefs(app);
            StringBuilder s = new StringBuilder(LocalTime.now().getHour() < 12 ? "శుభోదయం " : "నమస్తే ").append(p.name()).append(". ");
            try {
                Location l = Tools.lastLocation(app);
                if (l != null) {
                    JSONObject w = Http.get(String.format(Locale.ENGLISH, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m&daily=temperature_2m_max,precipitation_probability_max&forecast_days=1&timezone=auto", l.getLatitude(), l.getLongitude()));
                    long now = Math.round(w.getJSONObject("current").optDouble("temperature_2m"));
                    long max = Math.round(w.getJSONObject("daily").getJSONArray("temperature_2m_max").optDouble(0));
                    int rain = w.getJSONObject("daily").getJSONArray("precipitation_probability_max").optInt(0);
                    s.append("ఇప్పుడు ").append(now).append(" డిగ్రీలు, ఈరోజు ").append(max).append(" వరకు వెళ్తుంది. ");
                    if (rain >= 50) s.append("వర్షం పడే అవకాశం ").append(rain).append(" శాతం, గొడుగు / రెయిన్‌కోట్ తీసుకెళ్లండి. ");
                }
            } catch (Exception ignored) {}
            try {
                Duty.Roster r = Duty.load(app);
                LocalDate today = LocalDate.now();
                if (Duty.ready(r)) {
                    String t = r.timeOf(Duty.ME);
                    if (Duty.startsDuty(r, today)) s.append("ఈరోజు మీకు డ్యూటీ, ").append(t).append(" కి రిలీవ్ చేయాలి, ").append(r.leaveTime(t)).append(" కల్లా బయలుదేరండి. ");
                    else if (r.isOn(Duty.ME, today)) s.append("ఈరోజు కూడా డ్యూటీ కొనసాగుతుంది. ");
                    else if (r.isOn(Duty.ME, today.minusDays(1))) s.append("ఈరోజు ").append(t).append(" కి మీ డ్యూటీ అయిపోతుంది. ");
                    else if (r.isOn(Duty.ME, today.plusDays(1))) s.append("ఈరోజు సెలవు, రేపు డ్యూటీ. ");
                    else s.append("ఈరోజు డ్యూటీ లేదు. ");
                }
            } catch (Exception ignored) {}
            try {
                for (Holidays.Day h : Holidays.on(app, LocalDate.now())) if (h.big()) { s.append("ఈరోజు ").append(h.name).append(". "); break; }
            } catch (Exception ignored) {}
            Announcer.say(app, s.toString().trim());
        }, "alarm-greet").start();
    }

    @Override protected void onDestroy() {
        silence();
        if (showing != null && showing.get() == this) showing = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() { stopAndGreet(); }
}
