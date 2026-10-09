package com.anil.jarvis.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * W36: Jarvis's Telugu radio played by the watch itself, to its Bluetooth earbuds (or its speaker). The phone finds
 * the station's links; the watch streams over its own internet (through the phone's Bluetooth or Wi-Fi). Links are tried
 * in order; quieter while Jarvis speaks on the watch; the sleep timer and "ఆపు" stop it.
 */
public class RadioPlayer extends Service {
    static final String ACTION_STOP = "com.anil.jarvis.watch.RADIO_STOP";
    private static final String CH_TAP = "jarvis_radio_tap";
    private static final int NOTE = 71;
    private static final String CH = "jarvis_radio";

    static volatile String name = "";
    static volatile String status = "";
    private static volatile RadioPlayer self;
    private static List<String> pending = new ArrayList<>();
    private static String pendingName = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private List<String> urls = new ArrayList<>();
    private int at;
    private AudioFocusRequest focus;
    private boolean ducked;

    static boolean playing() { return self != null && !name.isEmpty(); }

    /** The phone's word (main thread): {play, urls[]} or {stop}. */
    static void fromPhone(Context c, JSONObject o) {
        if (o.optBoolean("stop")) { stop(c); return; }
        JSONArray u = o.optJSONArray("urls");
        List<String> l = new ArrayList<>();
        for (int i = 0; u != null && i < u.length(); i++) if (!u.optString(i).isEmpty()) l.add(u.optString(i));
        if (l.isEmpty()) return;
        play(c, o.optString("play"), l);
    }

    static void play(Context c, String station, List<String> links) {
        pendingName = station == null ? "" : station;
        pending = links;
        status = "కనెక్ట్ అవుతోంది…";
        RadioPlayer s = self;
        if (s != null) { s.begin(); return; }
        try { c.startForegroundService(new Intent(c, RadioPlayer.class)); }
        catch (Exception e) { tapToStart(c); } // (Android doesn't let it start from the background: one tap on the wrist does)
    }

    /** A card on the watch: his tap starts the radio (allowed then); the phone is told why it didn't play yet. */
    private static void tapToStart(Context c) {
        Context app = c.getApplicationContext();
        status = "వాచ్‌లో నోటిఫికేషన్ నొక్కితే మొదలవుతుంది";
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CH_TAP, "Jarvis రేడియో (నొక్కి మొదలుపెట్టు)", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            PendingIntent go = PendingIntent.getForegroundService(app, 74, new Intent(app, RadioPlayer.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE + 1, new Notification.Builder(app, CH_TAP).setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("📻 " + (pendingName.isEmpty() ? "రేడియో" : pendingName)).setContentText("▶ నొక్కితే వాచ్‌లో మొదలవుతుంది")
                    .setContentIntent(go).setAutoCancel(true).setTimeoutAfter(5 * 60_000L)
                    .addAction(new Notification.Action.Builder(null, "▶ ప్లే", go).build()).build());
        } catch (Exception ignored) {}
        try { Link.send(app, Link.P_DO, new org.json.JSONObject().put("what", "radio_tap")); } catch (Exception ignored) {}
        Talk.changed();
    }

    static void stop(Context c) {
        name = "";
        status = "";
        c.stopService(new Intent(c, RadioPlayer.class));
        Talk.changed();
        Panel.redraw();
    }

    /** Jarvis speaks on the watch: the radio goes quiet (and back up after). */
    static void duck(boolean on) {
        RadioPlayer s = self;
        if (s == null) return;
        s.main.post(() -> {
            s.ducked = on;
            try { if (s.mp != null) { float v = on ? 0.15f : 1f; s.mp.setVolume(v, v); } } catch (Exception ignored) {}
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        self = this;
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) { stop(this); return START_NOT_STICKY; }
        try { getSystemService(NotificationManager.class).cancel(NOTE + 1); } catch (Exception ignored) {} // (the "tap to start" card)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, note());
        } catch (Exception e) {
            status = "Android రేడియో మొదలవనివ్వలేదు: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pending == null || pending.isEmpty()) { stopSelf(); return START_NOT_STICKY; } // (an old card tapped: nothing to play)
        begin();
        return START_NOT_STICKY;
    }

    private void begin() {
        urls = new ArrayList<>(pending);
        name = pendingName;
        at = 0;
        if (focus == null) {
            AudioManager am = getSystemService(AudioManager.class);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(ch -> { if (ch == AudioManager.AUDIOFOCUS_LOSS) stop(this); }, main).build();
            try { am.requestAudioFocus(focus); } catch (Exception ignored) {}
        }
        next();
    }

    /** The next link (the first, or after one failed). */
    private void next() {
        release();
        if (at >= urls.size()) {
            status = "ప్లే అవలేదు (లింక్‌లు పనిచేయలేదు / నెట్ లేదు)";
            Talk.status = "📻 " + name + ": " + status;
            Talk.changed();
            Talk.buzz(this, 30, 60, 30);
            name = "";
            Panel.redraw();
            stopSelf();
            return;
        }
        String url = urls.get(at++);
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(url);
            mp.setOnPreparedListener(p -> {
                float v = ducked ? 0.15f : 1f;
                p.setVolume(v, v);
                p.start();
                status = "ప్లే అవుతోంది";
                update();
                Talk.changed();
                Panel.redraw();
            });
            mp.setOnErrorListener((p, what, extra) -> { main.post(this::next); return true; });
            mp.setOnCompletionListener(p -> main.post(this::next)); // (a live stream that ended: try again / the next)
            mp.prepareAsync();
            status = "కనెక్ట్ అవుతోంది…";
            update();
        } catch (Exception e) {
            main.post(this::next);
        }
    }

    private void release() {
        MediaPlayer m = mp;
        mp = null;
        if (m != null) try { m.release(); } catch (Exception ignored) {}
    }

    private Notification note() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Jarvis రేడియో", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        PendingIntent stop = PendingIntent.getService(this, 72, new Intent(this, RadioPlayer.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 73, new Intent(this, Panel.class).putExtra(Panel.EXTRA_KIND, "radio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("📻 " + (pendingName.isEmpty() ? "రేడియో" : pendingName)).setContentText(status).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", stop).build()).build();
    }

    private void update() {
        try { getSystemService(NotificationManager.class).notify(NOTE, note()); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        release();
        if (focus != null) try { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
        if (self == this) self = null;
        name = "";
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
