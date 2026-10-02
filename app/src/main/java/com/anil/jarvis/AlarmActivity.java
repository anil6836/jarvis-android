package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
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
    private String station = "";
    private String[] stationUrls = new String[0];
    private MediaPlayer connecting; // the radio while it is still connecting
    private android.os.Vibrator vib;
    private static java.lang.ref.WeakReference<AlarmActivity> showing;
    /** This alarm stops only after a small sum is answered (so he doesn't switch it off half asleep). */
    private boolean challenge;
    private boolean nap;
    private int napMinutes;
    private LinearLayout box;
    private TextView stopView;
    /** The stop alarm on a bus / train ("📍 X దగ్గరకి వచ్చారు"): no snooze, ⏹ ends it. */
    static final String EXTRA_STOP = "stop_label";
    private String stopLabel;

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
        stopLabel = getIntent().getStringExtra(EXTRA_STOP);
        if (stopLabel != null) { id = null; StopAlarm.quiet(this, stopLabel); } // the song rings here; the notification keeps only ⏹
        JSONObject o = id == null ? null : SongAlarm.find(this, id);
        station = o == null ? "" : o.optString("station", "");
        challenge = o != null && o.optBoolean("challenge");
        nap = o != null && o.optBoolean("nap");
        napMinutes = o == null ? 0 : o.optInt("nap_minutes");
        Announcer.stop();
        showing = new java.lang.ref.WeakReference<>(this);
        SongAlarm.clearRinging(this); // the screen is up: the notification's own ringing stops, the song takes over

        box = new LinearLayout(this);
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
        TextView sub = Ui.text(this, stopLabel != null ? stopLabel : "⏰ " + (label.isEmpty() ? "శుభోదయం!" : label), 20, Ui.C_CYAN);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 40));
        box.addView(sub);
        TextView stop = Ui.text(this, "⏹  ఆపు", 24, 0xFFFFFFFF);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        stop.setBackground(Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 24, null));
        stop.setOnClickListener(v -> { if (stopLabel != null) stopHere(); else if (challenge) askSum(); else stopAndGreet(); });
        stopView = stop;
        box.addView(stop, new LinearLayout.LayoutParams(-1, -2));
        TextView snooze = Ui.text(this, "😴  5 నిమిషాలు", 20, 0xFFFFFFFF);
        snooze.setGravity(Gravity.CENTER);
        snooze.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        snooze.setBackground(Ui.glass(this, 24));
        snooze.setOnClickListener(v -> doSnooze(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        if (stopLabel == null) box.addView(snooze, lp); // a stop does not wait 5 minutes
        if (!station.isEmpty()) { // wake with the radio: keep listening after the alarm
            TextView keep = Ui.text(this, "📻  " + station + " కొనసాగించు", 18, 0xFFFFFFFF);
            keep.setGravity(Gravity.CENTER);
            keep.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            keep.setBackground(Ui.glass(this, 24));
            keep.setOnClickListener(v -> keepRadio());
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
            kp.topMargin = Ui.dp(this, 14);
            box.addView(keep, kp);
        }
        setContentView(box);
        play();
        if (stopLabel != null) main.postDelayed(this::stopHere, 20 * 60000L); // the stop alarm rings on (20 minutes at most)
        else main.postDelayed(() -> doSnooze(10), 5 * 60000L); // not answered in 5 minutes: again in 10 (3 times at most)
    }

    /** The stop alarm answered: off everywhere, and a word to get ready. */
    private void stopHere() {
        if (handled) return;
        handled = true;
        silence();
        StopAlarm.off(this);
        Announcer.say(getApplicationContext(), new Prefs(this).name() + ", మీ స్టాప్ వస్తోంది. ఫోన్, పర్స్, సామాను చూసుకుని దిగండి.");
        finish();
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
        if (stopLabel != null) return; // the stop alarm keeps ringing; ⏹ on the notification ends it
        doSnooze(5);
    }

    private void play() {
        if (!station.isEmpty()) {
            JSONObject st = Radio.find(this, station);
            if (st != null) stationUrls = Radio.known(st);
        }
        if (stationUrls.length > 0) playRadio(stationUrls[0]); // the song / tone takes over if it doesn't start
        else playSong();
        startFadeAndVibrate();
    }

    private void playSong() {
        Prefs p = new Prefs(this);
        Uri song = null;
        if (!p.alarmSong().isEmpty()) song = Uri.parse(p.alarmSong());
        if (!tryPlay(song)) {
            Uri tone = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            tryPlay(tone);
        }
    }

    /** His radio station as the alarm; no sound in 15 seconds (no internet...) -> the song / alarm tone. */
    private void playRadio(String url) {
        MediaPlayer mp = new MediaPlayer();
        connecting = mp;
        final boolean[] started = {false};
        Runnable fallback = () -> {
            if (started[0] || handled || isFinishing() || isDestroyed()) return;
            started[0] = true;
            connecting = null;
            try { mp.release(); } catch (Exception ignored) {}
            if (player == mp) player = null;
            playSong();
        };
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(m -> {
                if (started[0] || handled || isFinishing() || isDestroyed()) { try { m.release(); } catch (Exception ignored) {} return; }
                started[0] = true;
                connecting = null;
                try { m.setVolume(vol, vol); m.start(); player = m; } catch (Exception e) { started[0] = false; fallback.run(); }
            });
            mp.setOnErrorListener((m, w, e) -> {
                main.post(() -> {
                    if (!started[0]) { fallback.run(); return; }
                    if (handled || isFinishing() || isDestroyed() || player != m) return; // broke off while ringing: the song carries on
                    try { m.release(); } catch (Exception ignored) {}
                    player = null;
                    playSong();
                });
                return true;
            });
            mp.setOnCompletionListener(m -> main.post(() -> { // the stream broke off while ringing: the song carries on
                if (handled || isFinishing() || isDestroyed() || player != m) return;
                try { m.release(); } catch (Exception ignored) {}
                player = null;
                playSong();
            }));
            mp.prepareAsync();
            main.postDelayed(fallback, 15000);
        } catch (Exception e) {
            main.post(fallback);
        }
    }

    /** The alarm stops and greets him; the station goes on in the radio player. */
    private void keepRadio() {
        if (handled) return;
        handled = true;
        silence();
        Context app = getApplicationContext();
        JSONObject st = Radio.find(app, station);
        if (st != null && Radio.known(st).length > 0) Radio.play(app, st, Radio.known(st), 0);
        greet(app);
        finish();
    }

    private void startFadeAndVibrate() {
        try { // vibrate too (and it is all there is if no sound would play)
            vib = getSystemService(android.os.Vibrator.class);
            if (vib != null) vib.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 700, 700}, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Exception ignored) {}
        // start soft, get louder over half a minute (the radio may still be connecting)
        main.post(new Runnable() {
            @Override public void run() {
                if (handled) return;
                vol = Math.min(1f, vol + 0.06f);
                try { if (player != null) player.setVolume(vol, vol); } catch (Exception ignored) {}
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
        if (connecting != null) { try { connecting.release(); } catch (Exception ignored) {} connecting = null; }
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
        if (nap) napWake(getApplicationContext(), napMinutes);
        else greet(getApplicationContext());
        finish();
    }

    static void napWake(android.content.Context app, int minutes) {
        Announcer.say(app, new Prefs(app).name() + ", " + (minutes > 0 ? minutes + " నిమిషాల " : "") + "కునుకు అయిపోయింది. లేవండి, ఒక గ్లాసు నీళ్లు తాగండి. ఫ్రెష్‌గా ఉన్నారా?");
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

    @Override public void onBackPressed() { if (stopLabel != null) stopHere(); else if (challenge) askSum(); else stopAndGreet(); }

    // ---------------------------------------------------------------- the small sum

    private LinearLayout sumBox;
    private final java.util.Random rnd = new java.util.Random();

    /** "37 + 48 = ?" with four answers; the right one stops the alarm, a wrong one gives a new sum. */
    private void askSum() {
        if (handled) return;
        int a, b, answer;
        String q;
        if (rnd.nextBoolean()) { a = 12 + rnd.nextInt(78); b = 12 + rnd.nextInt(78); answer = a + b; q = a + " + " + b; }
        else { a = 3 + rnd.nextInt(7); b = 11 + rnd.nextInt(9); answer = a * b; q = a + " × " + b; }
        java.util.List<Integer> opts = new java.util.ArrayList<>();
        opts.add(answer);
        while (opts.size() < 4) {
            int w = answer + (rnd.nextInt(21) - 10);
            if (w > 0 && !opts.contains(w)) opts.add(w);
        }
        java.util.Collections.shuffle(opts, rnd);
        if (sumBox != null) box.removeView(sumBox);
        sumBox = new LinearLayout(this);
        sumBox.setOrientation(LinearLayout.VERTICAL);
        TextView qv = Ui.text(this, "ఆపడానికి: " + q + " = ?", 26, 0xFFFFFFFF);
        qv.setGravity(Gravity.CENTER);
        qv.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        sumBox.addView(qv);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                int val = opts.get(r * 2 + k);
                TextView btn = Ui.text(this, String.valueOf(val), 24, 0xFFFFFFFF);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
                btn.setBackground(Ui.glass(this, 18));
                btn.setOnClickListener(v -> {
                    if (val == answer) stopAndGreet();
                    else {
                        try { if (vib != null) vib.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) {}
                        askSum(); // a new one
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
                row.addView(btn, lp);
            }
            sumBox.addView(row);
        }
        box.addView(sumBox, box.indexOfChild(stopView) + 1);
        stopView.setVisibility(android.view.View.GONE);
    }
}
