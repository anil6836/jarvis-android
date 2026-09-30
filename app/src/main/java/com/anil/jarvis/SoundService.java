package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Sounds that keep playing with the screen off: soft noise to fall asleep to (rain, fan, sea, white, made on the phone,
 * nothing downloaded) and Telugu internet radio. Stops by itself after the minutes asked, fading out.
 */
public class SoundService extends Service {
    static final String ACTION_NOISE = "com.anil.jarvis.SOUND_NOISE", ACTION_RADIO = "com.anil.jarvis.SOUND_RADIO", ACTION_STOP = "com.anil.jarvis.SOUND_STOP";
    private static final int NOTE = 101;
    static volatile String nowPlaying = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean noiseOn;
    private volatile int gen; // each noise thread plays only while it is the current one
    private Thread noiseThread;
    private MediaPlayer radio;
    private android.net.wifi.WifiManager.WifiLock wifi;
    private volatile boolean ducked; // a call / Jarvis listening: quiet for now

    /** Calls and Jarvis's own listening take the sound over; it comes back after (or stops if something else plays). */
    private final android.media.AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) main.post(() -> { halt(); stopSelf(); });
        else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            try { if (radio != null && radio.isPlaying()) radio.pause(); } catch (Exception ignored) {}
        } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
            ducked = false;
            try { if (radio != null && !radio.isPlaying()) radio.start(); } catch (Exception ignored) {}
        }
    };
    private android.media.AudioFocusRequest focusReq;

    private void takeFocus() {
        try {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            focusReq = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focus, main).build();
            am.requestAudioFocus(focusReq);
        } catch (Exception ignored) {}
    }

    private void dropFocus() {
        try { if (focusReq != null) getSystemService(android.media.AudioManager.class).abandonAudioFocusRequest(focusReq); } catch (Exception ignored) {}
        focusReq = null;
    }
    private volatile float gain = 1f;

    static void noise(Context c, String kind, int minutes) {
        start(c, new Intent(c, SoundService.class).setAction(ACTION_NOISE).putExtra("kind", kind).putExtra("minutes", minutes));
    }

    static void radio(Context c, String url, String name, int minutes) {
        start(c, new Intent(c, SoundService.class).setAction(ACTION_RADIO).putExtra("url", url).putExtra("name", name).putExtra("minutes", minutes));
    }

    static void stop(Context c) {
        try { c.startService(new Intent(c, SoundService.class).setAction(ACTION_STOP)); } catch (Exception ignored) {}
    }

    private static void start(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (ACTION_STOP.equals(a) || a == null) { halt(); stopSelf(); return START_NOT_STICKY; }
        halt();
        int minutes = Math.max(0, i.getIntExtra("minutes", 0));
        if (ACTION_NOISE.equals(a)) {
            String kind = i.getStringExtra("kind");
            nowPlaying = label(kind);
            foreground(nowPlaying + (minutes > 0 ? " · " + minutes + " నిమిషాలు" : ""));
            startNoise(kind == null ? "rain" : kind);
        } else {
            nowPlaying = "📻 " + i.getStringExtra("name");
            foreground(nowPlaying + (minutes > 0 ? " · " + minutes + " నిమిషాలు" : ""));
            startRadio(i.getStringExtra("url"));
        }
        takeFocus();
        if (minutes > 0) main.postDelayed(this::fadeAndStop, minutes * 60000L);
        return START_NOT_STICKY;
    }

    static String label(String kind) {
        switch (kind == null ? "" : kind) {
            case "fan": return "🌀 ఫ్యాన్ శబ్దం";
            case "sea": return "🌊 సముద్రం అలలు";
            case "white": return "🤍 వైట్ నాయిస్";
            default: return "🌧️ వర్షం శబ్దం";
        }
    }

    private void foreground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("jarvis_sound", "నిద్ర శబ్దాలు, రేడియో", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 102, new Intent(this, SoundService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "jarvis_sound").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(text).setContentText("ఆపడానికి ⏹ నొక్కండి లేదా \"Jarvis, ఆపు\" అనండి").setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTE, n);
    }

    // ---------------------------------------------------------------- noise made on the phone

    private void startNoise(String kind) {
        noiseOn = true;
        gain = 1f;
        final int my = ++gen;
        noiseThread = new Thread(() -> {
          AudioTrack t = null;
          try {
            int rate = 22050;
            int buf = Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf).build();
            t.play();
            Random r = new Random();
            short[] out = new short[rate / 10];
            double brown = 0, b0 = 0, b1 = 0, b2 = 0, phase = 0, fade = 0;
            while (noiseOn && my == gen) {
                for (int i = 0; i < out.length; i++) {
                    double white = r.nextDouble() * 2 - 1, v;
                    brown = (brown + 0.02 * white) / 1.02;                    // deep rumble (fan, sea)
                    b0 = 0.99765 * b0 + white * 0.0990460;                   // pink-ish (rain)
                    b1 = 0.96300 * b1 + white * 0.2965164;
                    b2 = 0.57000 * b2 + white * 1.0526913;
                    double pink = (b0 + b1 + b2 + white * 0.1848) * 0.11;
                    switch (kind) {
                        case "fan": v = brown * 3.2 + pink * 0.15; break;
                        case "sea":
                            phase += 2 * Math.PI / (rate * 9.0);            // a wave every ~9 seconds
                            double wave = 0.35 + 0.65 * Math.pow(Math.max(0, Math.sin(phase)), 2);
                            v = (brown * 3.0 + pink * 0.35) * wave; break;
                        case "white": v = white * 0.18; break;
                        default: v = pink * 0.9 + brown * 0.6 + (r.nextDouble() < 0.0006 ? white * 0.5 : 0); // rain with the odd drop
                    }
                    fade = Math.min(1, fade + 1.0 / (rate * 3));             // fade in over 3 s
                    out[i] = (short) Math.max(-32767, Math.min(32767, v * fade * gain * (ducked ? 0 : 1) * 32767 * 0.6));
                }
                if (t.write(out, 0, out.length) < 0) break; // the audio system went away
            }
          } catch (Throwable e) {
            main.post(() -> { halt(); stopSelf(); });
          } finally {
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) {}
                try { t.release(); } catch (Exception ignored) {}
            }
          }
        }, "jarvis-noise");
        noiseThread.start();
    }

    // ---------------------------------------------------------------- radio

    private void startRadio(String url) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(url);
            mp.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK); // keeps playing with the screen off
            try {
                android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
                if (wm != null) { wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:radio"); wifi.acquire(); }
            } catch (Exception ignored) {}
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> { halt(); stopSelf(); }); // the stream ended
            mp.setOnErrorListener((m, w, e) -> {
                nowPlaying = "";
                Announcer.say(this, "ఈ రేడియో స్టేషన్ ఇప్పుడు పనిచేయడం లేదు. ఇంకో స్టేషన్ అడగండి.");
                halt();
                stopSelf();
                return true;
            });
            mp.prepareAsync();
            radio = mp;
        } catch (Exception e) {
            nowPlaying = "";
            stopSelf();
        }
    }

    private void fadeAndStop() {
        main.post(new Runnable() {
            @Override public void run() {
                gain -= 0.05f;
                try { if (radio != null) radio.setVolume(Math.max(0, gain), Math.max(0, gain)); } catch (Exception ignored) {}
                if (gain > 0) main.postDelayed(this, 1500); // about 30 seconds of fading
                else { halt(); stopSelf(); }
            }
        });
    }

    private void halt() {
        main.removeCallbacksAndMessages(null);
        noiseOn = false;
        gen++;
        noiseThread = null;
        if (radio != null) {
            try { radio.stop(); } catch (Exception ignored) {}
            try { radio.release(); } catch (Exception ignored) {}
            radio = null;
        }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        wifi = null;
        ducked = false;
        dropFocus();
    }

    @Override public void onDestroy() {
        halt();
        nowPlaying = "";
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Telugu stations (community list, radio-browser.info)

    private static volatile JSONArray stations;
    private static volatile long stationsAt;

    /** Telugu stations that stream plain MP3 / AAC, most played first: {name, url, tags}. */
    static JSONArray stations() throws Exception {
        if (stations != null && System.currentTimeMillis() - stationsAt < 6 * 3600000L) return stations;
        JSONArray out = new JSONArray();
        String body = Http.getText("https://de1.api.radio-browser.info/json/stations/search?language=telugu&hidebroken=true&order=clickcount&reverse=true&limit=60");
        JSONArray a = new JSONArray(body);
        for (int i = 0; i < a.length() && out.length() < 25; i++) {
            JSONObject s = a.getJSONObject(i);
            String codec = s.optString("codec").toUpperCase(java.util.Locale.ROOT);
            String url = s.optString("url_resolved", s.optString("url"));
            // plain http is blocked for apps on Android 9+: https streams only
            if (url.isEmpty() || !url.startsWith("https://") || s.optInt("hls") == 1 || url.contains(".m3u8")) continue;
            if (!(codec.contains("MP3") || codec.contains("AAC"))) continue;
            out.put(new JSONObject().put("name", s.optString("name").trim()).put("url", url).put("tags", s.optString("tags")));
        }
        stations = out;
        stationsAt = System.currentTimeMillis();
        return out;
    }
}
