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

import org.json.JSONObject;

import java.util.Random;

/**
 * Sounds that keep playing with the screen off: soft noise to fall asleep to (rain, fan, sea, white, made on the phone,
 * nothing downloaded) and his radio stations (Radio.java). Stops by itself after the minutes asked, fading out.
 */
public class SoundService extends Service {
    static final String ACTION_NOISE = "com.anil.jarvis.SOUND_NOISE", ACTION_RADIO = "com.anil.jarvis.SOUND_RADIO", ACTION_STOP = "com.anil.jarvis.SOUND_STOP";
    /** Radio buttons (the media player in the notification / lock screen, headset buttons, and Jarvis by voice). */
    static final String ACTION_TOGGLE = "com.anil.jarvis.RADIO_TOGGLE", ACTION_PAUSE = "com.anil.jarvis.RADIO_PAUSE", ACTION_PLAY = "com.anil.jarvis.RADIO_PLAY",
            ACTION_NEXT = "com.anil.jarvis.RADIO_NEXT", ACTION_PREV = "com.anil.jarvis.RADIO_PREV", ACTION_FAV = "com.anil.jarvis.RADIO_FAV",
            ACTION_REFRESH = "com.anil.jarvis.RADIO_REFRESH";
    private static final int NOTE = 101;
    static volatile String nowPlaying = "";
    /** A station is on (playing, paused or connecting), and which. */
    static volatile boolean radioOn, radioPaused;
    static volatile String station = "";
    /** A prayer / quiet time is on: messages are not read out, notifications wait. */
    static volatile boolean prayerOn;
    private int filterBefore = -1; // Do Not Disturb as it was before the prayer time

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean noiseOn;
    private volatile int gen; // each noise thread plays only while it is the current one
    private Thread noiseThread;
    private MediaPlayer radio;
    private volatile boolean played; // this link is ready / played before it broke off: worth another try
    private android.net.wifi.WifiManager.WifiLock wifi;
    private volatile boolean ducked; // a call / Jarvis listening: quiet for now

    /** Calls and Jarvis's own listening take the sound over; it comes back after (or stops if something else plays). */
    private final android.media.AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) main.post(() -> { halt(); stopSelf(); });
        else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { // a call / Jarvis listening: wait
            ducked = true;
            try { if (radio != null && played && radio.isPlaying()) radio.pause(); } catch (Exception ignored) {}
        } else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { // a message read out: quieter
            quiet = true;
            applyVolume();
        } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
            ducked = false;
            quiet = false;
            applyVolume();
            try { if (radio != null && played && !radio.isPlaying()) radio.start(); } catch (Exception ignored) {} // not before it is ready
        }
    };
    private volatile boolean quiet; // Jarvis is reading something out: play softly

    private void applyVolume() {
        float v = Math.max(0, gain) * (quiet ? 0.2f : 1f);
        try { if (radio != null) radio.setVolume(v, v); } catch (Exception ignored) {}
    }
    private android.media.AudioFocusRequest focusReq;

    private void takeFocus() {
        ducked = false; // a pause taken while ducked must not keep the restarted stream silent
        quiet = false;
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

    /** Prayer / quiet time: soft calm sound, Do Not Disturb (if allowed) and no messages read, for the minutes; a gentle word at the end. */
    static void prayer(Context c, int minutes) {
        start(c, new Intent(c, SoundService.class).setAction(ACTION_NOISE).putExtra("kind", "calm").putExtra("minutes", minutes).putExtra("prayer", true));
    }

    /** Plays a station; its links are tried in order until one plays. No links: looks one up by key first. */
    static void radio(Context c, String[] urls, String name, String key, int minutes, String whyNone) {
        start(c, new Intent(c, SoundService.class).setAction(ACTION_RADIO).putExtra("urls", urls).putExtra("name", name)
                .putExtra("key", key).putExtra("minutes", minutes).putExtra("why", whyNone));
    }

    static void stop(Context c) {
        try { c.startService(new Intent(c, SoundService.class).setAction(ACTION_STOP)); } catch (Exception ignored) {}
    }

    /** ⏯ ⏮ ⏭ ⭐ for the station on now (does nothing when no station is on). */
    static boolean control(Context c, String action) {
        if (!radioOn) return false;
        try { c.startService(new Intent(c, SoundService.class).setAction(action)); return true; } catch (Exception e) { return false; }
    }

    private static void start(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (ACTION_STOP.equals(a) || a == null) { halt(); stopSelf(); return START_NOT_STICKY; }
        if (a.startsWith("com.anil.jarvis.RADIO_")) { // a button of the station playing now
            if (!radioMode) { if (!noiseOn) stopSelf(id); return START_NOT_STICKY; } // nothing to control (an old notification)
            switch (a) {
                case ACTION_TOGGLE: if (isPaused) resumeRadio(); else pauseRadio(); break;
                case ACTION_PAUSE: pauseRadio(); break;
                case ACTION_PLAY: resumeRadio(); break;
                case ACTION_NEXT: step(1); break;
                case ACTION_PREV: step(-1); break;
                case ACTION_FAV: toggleFav(); break;
                case ACTION_REFRESH: updateMedia(); break; // favourites changed by voice: the ⭐ follows
                default: break;
            }
            return START_NOT_STICKY;
        }
        halt();
        AppRadio.cancelPending(); // a station the Telugu Radios app was about to start must not play over this
        AppRadio.pauseAfter(this, 0);
        int minutes = Math.max(0, i.getIntExtra("minutes", 0));
        stopAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60000L : 0;
        if (ACTION_NOISE.equals(a)) {
            String kind = i.getStringExtra("kind");
            if (i.getBooleanExtra("prayer", false)) beginPrayer();
            nowPlaying = prayerOn ? "🙏 ప్రార్థన సమయం" : label(kind);
            foreground(nowPlaying + (minutes > 0 ? " · " + minutes + " నిమిషాలు" : ""));
            startNoise(kind == null ? "rain" : kind);
        } else {
            String name = i.getStringExtra("name") == null ? "" : i.getStringExtra("name");
            String[] links = i.getStringArrayExtra("urls");
            radioKey = i.getStringExtra("key") == null ? name : i.getStringExtra("key");
            final String why = i.getStringExtra("why") == null ? "" : i.getStringExtra("why");
            appTried = !why.isEmpty(); // the app was asked already (and couldn't)
            nowPlaying = "📻 " + name;
            radioMode = true;
            radioOn = true;
            radioPaused = false;
            radioName = name;
            station = name;
            ensureSession();
            if (links != null && links.length > 0) {
                startRadio(links, name);
            } else { // no link known yet: look one up (a Telugu station of that name on radio-browser.info)
                statusLine = "లింక్ వెతుకుతున్నాను…";
                updateMedia();
                String key = radioKey;
                final int my = lookupGen;
                new Thread(() -> {
                    String found = Radio.lookup(key);
                    main.post(() -> {
                        if (my != lookupGen) return; // stopped, or another sound asked for meanwhile
                        if (found.isEmpty()) {
                            nowPlaying = "";
                            Announcer.say(this, why.isEmpty() ? name + " కి పనిచేసే లింక్ ఇంకా దొరకలేదు. ఇంకో స్టేషన్ చెప్పండి."
                                    : why + " ఇంకో స్టేషన్ చెప్పండి.");
                            halt();
                            stopSelf();
                            return;
                        }
                        Radio.remember(this, name, found);
                        if (isPaused) { urls = new String[]{found}; updateMedia(); return; } // ▶ starts it
                        startRadio(new String[]{found}, name);
                    });
                }, "jarvis-radio-lookup").start();
            }
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
            case "calm": return "🕊️ ప్రశాంత సంగీతం";
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
                        case "calm": { // a soft held chord (A, E, A, C#) slowly breathing in and out, a little air under it
                            phase += 1.0 / rate;
                            double breath = 0.55 + 0.45 * Math.sin(2 * Math.PI * phase / 11.0);
                            double w = phase * 2 * Math.PI;
                            v = (Math.sin(w * 110.0) * 0.30 + Math.sin(w * 164.81) * 0.22 + Math.sin(w * 220.0) * 0.18
                                    + Math.sin(w * 277.18) * 0.12 * (0.5 + 0.5 * Math.sin(2 * Math.PI * phase / 7.0))) * 0.35 * breath + pink * 0.05;
                            break;
                        }
                        case "sea":
                            phase += 2 * Math.PI / (rate * 9.0);            // a wave every ~9 seconds
                            double wave = 0.35 + 0.65 * Math.pow(Math.max(0, Math.sin(phase)), 2);
                            v = (brown * 3.0 + pink * 0.35) * wave; break;
                        case "white": v = white * 0.18; break;
                        default: v = pink * 0.9 + brown * 0.6 + (r.nextDouble() < 0.0006 ? white * 0.5 : 0); // rain with the odd drop
                    }
                    fade = Math.min(1, fade + 1.0 / (rate * 3));             // fade in over 3 s
                    out[i] = (short) Math.max(-32767, Math.min(32767, v * fade * gain * (ducked ? 0 : 1) * (quiet ? 0.25 : 1) * 32767 * 0.6));
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

    private String[] urls;
    private int urlAt, retries, attemptId, lookupGen;
    private String radioName = "", radioKey = "";
    private boolean appTried;
    private long stopAt; // when his sleep timer ends (0 = none)
    private boolean linkPlayed; // the current link played before (a drop is worth a few more tries)
    private long playedAt;
    private Runnable slow; // a dead link can hang in "preparing" for a long time

    private void startRadio(String[] list, String name) {
        urls = list;
        urlAt = 0;
        retries = 0;
        linkPlayed = false;
        radioName = name;
        gain = 1f;
        holdWifi();
        playLink();
    }

    private void playLink() {
        releasePlayer();
        if (urls == null || isPaused) return; // stopped or paused meanwhile
        if (urlAt >= urls.length) { allFailed(); return; }
        played = false;
        statusLine = "కనెక్ట్ అవుతోంది…";
        updateMedia();
        final int my = ++attemptId; // callbacks of older attempts are ignored
        MediaPlayer mp = new MediaPlayer();
        radio = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(urls[urlAt]);
            mp.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK); // keeps playing with the screen off
            mp.setOnPreparedListener(m -> {
                if (my != attemptId) return;
                if (slow != null) main.removeCallbacks(slow);
                played = true;
                linkPlayed = true;
                playedAt = android.os.SystemClock.elapsedRealtime();
                try { applyVolume(); if (!ducked) m.start(); } catch (Exception ignored) {}
                statusLine = "";
                updateMedia();
            });
            mp.setOnCompletionListener(m -> { if (my == attemptId) linkFailed(); }); // a live stream doesn't end by itself: it broke off
            mp.setOnErrorListener((m, w, e) -> { main.post(() -> { if (my == attemptId) linkFailed(); }); return true; });
            mp.prepareAsync();
            slow = () -> { if (my == attemptId) linkFailed(); };
            main.postDelayed(slow, 25000);
        } catch (Exception e) {
            main.post(() -> { if (my == attemptId) linkFailed(); });
        }
    }

    /** This link didn't play (or broke off): the same one again after a pause if it had been playing, else the next one. */
    private void linkFailed() {
        if (slow != null) main.removeCallbacks(slow);
        if (urls == null) return; // already stopped
        attemptId++; // anything more from this attempt is ignored
        long wait;
        if (linkPlayed && android.os.SystemClock.elapsedRealtime() - playedAt > 60000) retries = 0; // it had been playing fine
        if (linkPlayed && retries < 3) {
            retries++;
            wait = retries == 1 ? 2000 : retries == 2 ? 5000 : 10000; // e.g. the network switching between wifi and mobile data
        } else {
            urlAt++;
            retries = 0;
            linkPlayed = false;
            wait = 300;
        }
        releasePlayer();
        main.removeCallbacks(replay);
        main.postDelayed(replay, wait);
    }

    private final Runnable replay = this::playLink;

    private void holdWifi() {
        try {
            android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            if (wm != null && (wifi == null || !wifi.isHeld())) { wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:radio"); wifi.acquire(); }
        } catch (Exception ignored) {}
    }

    private void letWifiGo() {
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        wifi = null;
    }

    /** Paused for half an hour: stop (an alarm, so it happens even while the phone sleeps). */
    private void autoStop(boolean on) {
        try {
            android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
            PendingIntent pi = PendingIntent.getService(this, 108, new Intent(this, SoundService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (on) am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 30 * 60000L, pi);
            else am.cancel(pi);
        } catch (Exception ignored) {}
    }

    /** None of the links plays: the Telugu Radios app may still have it; else say so. */
    private void allFailed() {
        final String name = radioName;
        final long left = stopAt > 0 ? stopAt - System.currentTimeMillis() : 0; // his sleep timer carries over
        final Context app = getApplicationContext();
        Radio.forget(this, name);
        nowPlaying = "";
        halt();
        stopSelf();
        final String sorry = (name.isEmpty() ? "ఈ" : name) + " స్టేషన్ ఇప్పుడు పనిచేయడం లేదు. ఇంకో స్టేషన్ చెప్పండి.";
        if (!name.isEmpty() && !appTried && AppRadio.installed(app)) {
            AppRadio.play(app, name, (status, title) -> {
                if (AppRadio.OK.equals(status)) { if (left > 0) AppRadio.pauseAfter(app, (int) Math.max(1, (left + 59999) / 60000)); }
                else if (!AppRadio.CANCELLED.equals(status)) Announcer.say(app, sorry);
            });
        } else {
            Announcer.say(app, sorry);
        }
    }

    private void releasePlayer() {
        if (radio != null) {
            MediaPlayer mp = radio;
            radio = null;
            try { mp.setOnErrorListener(null); mp.setOnCompletionListener(null); mp.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    private void fadeAndStop() {
        main.post(new Runnable() {
            @Override public void run() {
                gain -= 0.05f;
                applyVolume();
                if (gain > 0) main.postDelayed(this, 1500); // about 30 seconds of fading
                else {
                    boolean prayed = prayerOn;
                    halt();
                    stopSelf();
                    if (prayed) Announcer.say(SoundService.this, "ప్రార్థన సమయం పూర్తయింది. దేవుడు మిమ్మల్ని దీవించును గాక.");
                }
            }
        });
    }

    private void halt() {
        main.removeCallbacksAndMessages(null);
        noiseOn = false;
        gen++;
        noiseThread = null;
        urls = null;
        attemptId++;
        lookupGen++;
        releasePlayer();
        letWifiGo();
        ducked = false;
        quiet = false;
        dropFocus();
        endPrayer();
        if (isPaused) autoStop(false);
        radioMode = false;
        isPaused = false;
        radioOn = false;
        radioPaused = false;
        station = "";
        statusLine = "";
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Exception ignored) {}
            session = null;
        }
    }

    // ---------------------------------------------------------------- prayer time

    private void beginPrayer() {
        prayerOn = true;
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm.isNotificationPolicyAccessGranted()) {
                filterBefore = nm.getCurrentInterruptionFilter();
                getSharedPreferences("jarvis_prayer", MODE_PRIVATE).edit().putInt("before", filterBefore).apply(); // if Jarvis is closed meanwhile
                nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            }
        } catch (Exception ignored) {}
    }

    private void endPrayer() {
        if (!prayerOn) return;
        prayerOn = false;
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            // put Do Not Disturb back, unless he changed it himself meanwhile
            if (filterBefore > 0 && nm.isNotificationPolicyAccessGranted()
                    && nm.getCurrentInterruptionFilter() == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(filterBefore);
        } catch (Exception ignored) {}
        filterBefore = -1;
        getSharedPreferences("jarvis_prayer", MODE_PRIVATE).edit().remove("before").apply();
    }

    /** Jarvis was closed during a prayer time: put Do Not Disturb back as it was. */
    static void restoreAfterPrayer(Context c) {
        if (prayerOn) return;
        android.content.SharedPreferences sp = c.getSharedPreferences("jarvis_prayer", Context.MODE_PRIVATE);
        int before = sp.getInt("before", -1);
        if (before <= 0) return;
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm.isNotificationPolicyAccessGranted() && nm.getCurrentInterruptionFilter() == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                nm.setInterruptionFilter(before);
        } catch (Exception ignored) {}
        sp.edit().remove("before").apply();
    }

    // ---------------------------------------------------------------- the small media player (notification, lock screen, headset)

    private boolean radioMode, isPaused;
    private String statusLine = "";
    private android.media.session.MediaSession session;

    private void ensureSession() {
        if (session != null) return;
        try {
            session = new android.media.session.MediaSession(this, "JarvisRadio");
            session.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public void onPlay() { if (isPaused) resumeRadio(); else pauseRadio(); } // headset button while connecting: pause
                @Override public void onPause() { pauseRadio(); }
                @Override public void onSkipToNext() { step(1); }
                @Override public void onSkipToPrevious() { step(-1); }
                @Override public void onStop() { halt(); stopSelf(); }
                @Override public void onCustomAction(String action, android.os.Bundle extras) {
                    if ("fav".equals(action)) toggleFav();
                    else if ("stop".equals(action)) { halt(); stopSelf(); }
                }
            }, main);
            session.setActive(true);
        } catch (Exception e) {
            session = null;
        }
    }

    /** Station name, ⏮ ⏯ ⏭ ⭐ ⏹ in the notification and the phone's media player; the same state for headset buttons. */
    private void updateMedia() {
        if (!radioMode) return;
        boolean fav = Radio.isFav(this, radioName);
        String group = Radio.groupLabel(this, radioName);
        long timerLeft = stopAt > 0 ? stopAt - System.currentTimeMillis() : 0;
        String line = isPaused ? "⏸ ఆగింది" : !statusLine.isEmpty() ? statusLine
                : "Jarvis రేడియో" + (timerLeft > 0 ? " · " + Math.max(1, (timerLeft + 59999) / 60000) + " నిమిషాల్లో ఆగుతుంది" : "");
        if (session != null) {
            try {
                session.setMetadata(new android.media.MediaMetadata.Builder()
                        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, radioName)
                        .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, line)
                        .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, group)
                        .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, -1).build());
                int state = isPaused ? android.media.session.PlaybackState.STATE_PAUSED
                        : statusLine.isEmpty() ? android.media.session.PlaybackState.STATE_PLAYING : android.media.session.PlaybackState.STATE_BUFFERING;
                session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                        .setState(state, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, isPaused ? 0f : 1f)
                        .setActions(android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE
                                | android.media.session.PlaybackState.ACTION_PLAY_PAUSE | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                                | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS | android.media.session.PlaybackState.ACTION_STOP)
                        .addCustomAction(new android.media.session.PlaybackState.CustomAction.Builder("fav", fav ? "ఫేవరేట్ నుంచి తీసేయి" : "ఫేవరేట్లో పెట్టు",
                                fav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off).build())
                        .addCustomAction(new android.media.session.PlaybackState.CustomAction.Builder("stop", "ఆపు",
                                android.R.drawable.ic_menu_close_clear_cancel).build()) // newer phones draw only these buttons
                        .build());
            } catch (Exception ignored) {}
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_sound", "నిద్ర శబ్దాలు, రేడియో", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = new Notification.Builder(this, "jarvis_sound")
                    .setSmallIcon(isPaused ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play)
                    .setContentTitle("📻 " + radioName).setContentText(line).setSubText(group)
                    .setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(PendingIntent.getActivity(this, 103, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .addAction(button(android.R.drawable.ic_media_previous, "ముందు", ACTION_PREV, 104))
                    .addAction(isPaused ? button(android.R.drawable.ic_media_play, "ప్లే", ACTION_PLAY, 105)
                            : button(android.R.drawable.ic_media_pause, "పాజ్", ACTION_PAUSE, 105))
                    .addAction(button(android.R.drawable.ic_media_next, "తర్వాతి", ACTION_NEXT, 106))
                    .addAction(button(fav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off, fav ? "ఫేవరేట్ ✓" : "ఫేవరేట్", ACTION_FAV, 107))
                    .addAction(button(android.R.drawable.ic_menu_close_clear_cancel, "ఆపు", ACTION_STOP, 102));
            Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            if (session != null) style.setMediaSession(session.getSessionToken());
            b.setStyle(style);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, b.build());
        } catch (Exception ignored) {}
    }

    private Notification.Action button(int icon, String title, String action, int code) {
        PendingIntent pi = PendingIntent.getService(this, code, new Intent(this, SoundService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build();
    }

    private void pauseRadio() {
        if (!radioMode || isPaused) return;
        isPaused = true;
        radioPaused = true;
        attemptId++; // callbacks of the stream being stopped are ignored (a link still being looked up is kept)
        if (slow != null) main.removeCallbacks(slow);
        main.removeCallbacks(replay);
        releasePlayer();
        dropFocus();
        letWifiGo();
        autoStop(true);
        updateMedia();
    }

    private void resumeRadio() {
        if (!radioMode || !isPaused) return;
        isPaused = false;
        radioPaused = false;
        autoStop(false);
        if (urls == null || urls.length == 0) { // its link is still being looked up: it starts when found
            JSONObject st = Radio.find(this, radioName);
            if (st == null || Radio.known(st).length == 0) { takeFocus(); statusLine = "లింక్ వెతుకుతున్నాను…"; updateMedia(); return; }
            urls = Radio.known(st);
        }
        takeFocus();
        holdWifi();
        urlAt = 0;
        retries = 0;
        linkPlayed = false;
        playLink(); // a live stream starts again from now
    }

    /** ⏭ (dir 1) / ⏮ (dir -1). */
    private void step(int dir) {
        if (!radioMode) return;
        JSONObject next = Radio.neighbour(this, radioName, dir);
        if (next == null) return;
        String[] u = Radio.known(next);
        attemptId++;
        lookupGen++;
        if (slow != null) main.removeCallbacks(slow);
        main.removeCallbacks(replay);
        if (isPaused) autoStop(false);
        releasePlayer();
        isPaused = false;
        radioPaused = false;
        appTried = false;
        radioName = next.optString("name");
        radioKey = next.optString("key", radioName);
        station = radioName;
        nowPlaying = "📻 " + radioName;
        Radio.setLast(this, radioName);
        if (focusReq == null) takeFocus();
        startRadio(u, radioName);
    }

    private void toggleFav() {
        if (!radioMode || radioName.isEmpty()) return;
        boolean on = !Radio.isFav(this, radioName);
        Radio.setFav(this, radioName, on);
        try { android.widget.Toast.makeText(this, on ? "⭐ " + radioName + " ఫేవరేట్లో పెట్టాను" : radioName + " ఫేవరేట్ నుంచి తీసేశాను", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
        updateMedia();
    }

    @Override public void onDestroy() {
        halt();
        nowPlaying = "";
        super.onDestroy();
    }

}
