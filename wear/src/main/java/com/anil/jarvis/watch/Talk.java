package com.anil.jarvis.watch;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * What the watch is doing in a talk with Jarvis, shared by the screen (WatchActivity) and the background listening
 * (EarService): listening, the phone understanding / thinking, speaking, or idle; what he said and the answer.
 * The phone decides the talk (WatchHub); this follows it and does the watch's parts. Main thread.
 */
final class Talk {
    private Talk() {}

    static final int IDLE = 0, LISTENING = 1, UNDERSTANDING = 2, THINKING = 3, SPEAKING = 4, ERROR = 5;

    interface Screen { void changed(); }

    static volatile int state = IDLE;
    static String heard = "", reply = "", status = "";
    static volatile boolean phoneOnline = true;
    /** A tool on the phone asks "are you sure?": {id, title, msg, yes, auto}; null when nothing is asked. */
    static JSONObject confirm;
    /** The screen, while it is open (it redraws on changed). */
    static Screen screen;
    /** The screen is in front and lit (so the screen going dark means his palm covered it). */
    static boolean screenUp;
    /** Listening on wrist raise / in his hours stopped working (the app must be opened once). */
    static volatile boolean listenBroken;
    /** The answer is said on the phone's earphones (not here). */
    static boolean voiceOnPhone;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long askedAt;
    private static volatile long stoppedAt;

    // ================================================================ starting

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** He wants to talk (a tap, the app opened), or the phone wants his reply (why: follow / answer / wake). */
    static void listen(Context c, String why) {
        final Context app = c.getApplicationContext();
        appCtx = app;
        Player.stop();
        if (!micAllowed(app)) { show(app, ERROR, "వాచ్‌లో Jarvis తెరిచి మైక్‌కి Allow నొక్కండి"); done(app); return; }
        if ("watch".equals(Link.ears(app)) && !Hear.available(app)) { // never another way without telling him
            show(app, ERROR, "ఈ వాచ్‌లో Google వాయిస్ టైపింగ్ లేదు: ఫోన్ Settings → ⌚ వాచ్ లో OpenAI / Gemini ఎంచుకోండి");
            done(app);
            return;
        }
        heard = "";
        if (!"follow".equals(why) && !"answer".equals(why)) reply = "";
        confirm = null;
        askedAt = SystemClock.elapsedRealtime();
        if ("watch".equals(Link.ears(app)) && online(app) && Hear.available(app)) {
            Mic.stop();
            set(LISTENING, "వింటున్నాను… (వాచ్ వాయిస్ టైపింగ్)");
            buzz(app, 30);
            final boolean follow = "follow".equals(why);
            Hear.start(app, Link.lang(app), new Hear.Callback() {
                @Override public void level(float l) { Mic.level = l; }
                @Override public void partial(String text) { heard = text; changed(); }
                @Override public void heard(String text) {
                    Mic.level = 0;
                    if (text.isEmpty()) { set(IDLE, follow ? "" : "ఏమీ వినిపించలేదు"); done(app); return; }
                    heard = text;
                    set(THINKING, "ఫోన్‌కి పంపాను…");
                    try { Link.send(app, Link.P_TEXT, new JSONObject().put("text", text).put("why", why)); } catch (Exception ignored) {}
                    watchPhone(app);
                }
                @Override public void failed(String text, boolean nothing) {
                    Mic.level = 0;
                    done(app); // (the phone waits for nothing more)
                    if (nothing && follow) { set(IDLE, ""); return; }
                    show(app, nothing ? IDLE : ERROR, text);
                }
            });
            return;
        }
        Hear.stop();
        Mic.start(app, Mic.TALK, why);
        set(LISTENING, "వింటున్నాను…");
        buzz(app, 30);
        watchPhone(app);
    }

    /** No word from the phone within a few seconds of asking: say so (never wait for ever). */
    private static void watchPhone(Context app) {
        final long asked = askedAt;
        main.postDelayed(() -> {
            if (asked != askedAt || Link.heardAt >= asked || state == IDLE) return;
            noPhone(app);
        }, 5000);
    }

    private static void noPhone(Context app) {
        Mic.stop();
        Hear.stop();
        Player.stop();
        confirm = null;
        show(app, ERROR, "ఫోన్ స్పందించలేదు: ఫోన్ దగ్గర ఉందా, Bluetooth ఆన్‌లో ఉందా? (నెట్ లేకపోయినా Bluetooth చాలు)");
        done(app);
    }

    /** The watch ended the talk itself: the phone stops waiting (and lets its own "Jarvis" listen again). */
    private static void done(Context app) { Link.send(app, Link.P_DONE, new byte[0]); }

    /** The question's mic reached its limit (25 s): the phone now writes out what it has. */
    static void micEnded(Context app) {
        main.post(() -> { if (state == LISTENING) set(UNDERSTANDING, "అర్థం చేసుకుంటున్నాను…"); });
    }

    /** He tapped a card's word ("చదువు", "ఎత్తు"): the same as saying it. */
    static void saidByTap(Context c, String text, String why) {
        Context app = c.getApplicationContext();
        appCtx = app;
        Mic.stop();
        Hear.stop();
        Player.stop();
        heard = text == null ? "" : text;
        askedAt = SystemClock.elapsedRealtime();
        set(THINKING, "ఫోన్‌కి పంపాను…");
        try { Link.send(app, Link.P_TEXT, new JSONObject().put("text", heard).put("why", why == null ? "answer" : why)); } catch (Exception ignored) {}
        watchPhone(app);
    }

    /** He tapped while it listens: what he said so far is enough. */
    static void finishListening(Context c) {
        if (Hear.active()) { Hear.finish(); return; }
        if (Mic.question()) { Mic.stop(); set(UNDERSTANDING, "అర్థం చేసుకుంటున్నాను…"); }
    }

    /** His stop (a tap while it thinks or speaks, the palm over the watch, or "ఆపు" on the screen). */
    static void stop(Context c) {
        Context app = c.getApplicationContext();
        Hear.stop();
        Mic.stop();
        Player.stop();
        cover(app, false);
        confirm = null;
        stoppedAt = SystemClock.elapsedRealtime();
        Link.send(app, Link.P_STOP, new byte[0]);
        set(IDLE, "ఆపాను");
        Notes.talk(app);
    }

    /** The wrist came up (the screen lit): listen a few seconds for "Hey Jarvis". */
    static void raised(Context c) {
        if (!Link.raise(c) || state != IDLE || Mic.busy() || Hear.active() || !micAllowed(c)) return;
        Mic.start(c, Mic.RAISE, "raise");
    }

    /** His always-listening hours (EarService checks every minute). */
    static void hours(Context c) {
        if (!Link.inHours(c)) { if (Mic.kind() == Mic.HOURS) Mic.stop(); return; }
        if (state != IDLE || Mic.busy() || Hear.active() || !micAllowed(c)) return;
        Mic.start(c, Mic.HOURS, "hours");
    }

    // ================================================================ from the phone (Inbox)

    static void onMessage(Context c, String from, String path, byte[] data) {
        final Context app = c.getApplicationContext();
        appCtx = app;
        Link.heard(from);
        try {
            switch (path) {
                case Link.P_AUDIO_DATA: Player.data(data); return; // (many a second: not through the main thread)
                case Link.P_MIC_STOP: Mic.stop(new JSONObject(text(data)).optInt("id")); return;
                default:
            }
            final JSONObject o = data == null || data.length == 0 ? new JSONObject() : new JSONObject(text(data));
            // the voice starts here at once, so its first messages (right behind this one) are not lost
            if (Link.P_AUDIO_START.equals(path)) {
                if (SystemClock.elapsedRealtime() - stoppedAt < 3000) return; // he stopped it a moment ago (this was on its way)
                Player.start(app, o.optInt("id"));
            } else if (Link.P_AUDIO_END.equals(path)) {
                Player.end(o.optInt("id"), o.optBoolean("drop"));
                return;
            }
            main.post(() -> handle(app, path, o));
        } catch (Exception e) {
            Link.lastError = "ఫోన్ సందేశం (" + path + "): " + e.getMessage();
        }
    }

    private static String text(byte[] d) { return d == null ? "" : new String(d, StandardCharsets.UTF_8); }

    private static void handle(Context app, String path, JSONObject o) {
        switch (path) {
            case Link.P_SETTINGS:
                Link.saveCfg(app, o);
                phoneOnline = o.optBoolean("online", true);
                Theme.refresh(app);
                EarService.refresh(app);
                changed();
                break;
            case Link.P_ALERT: Alerts.show(app, o); break;
            case Link.P_ALERT_GONE: Alerts.gone(app, o.optInt("id")); break;
            case Link.P_ALARM: AlarmScreen.ring(app, o); break;
            case Link.P_ALARM_STOP: AlarmScreen.stopFromPhone(o.optString("id")); break;
            case Link.P_INFO:
                Link.saveInfo(app, o);
                Theme.refresh(app);
                Complications.update(app);
                changed();
                break;
            case Link.P_PING: hello(app); break;
            case Link.P_STATE: state(app, o); break;
            case Link.P_LISTEN:
                // already listening (but a ringing call's question starts afresh: the old talk's mic is over)
                if ((Mic.kind() == Mic.TALK || Hear.active()) && !"call".equals(o.optString("why"))) break;
                listen(app, o.optString("why", "follow"));
                Notes.talk(app);
                break;
            case Link.P_AUDIO_START:
                if (!Player.playing()) break; // (stopped meanwhile)
                set(SPEAKING, "");
                // W13: "Jarvis" over the answer (not when the answer itself says "Jarvis": it would stop itself)
                if (Link.stopVoice(app) && micAllowed(app) && !reply.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(jarvis|జార్విస్).*"))
                    Mic.start(app, Mic.OVER, "over");
                cover(app, true);
                break;
            case Link.P_CONFIRM_DONE: {
                JSONObject q = confirm;
                if (q != null && q.optInt("id") == o.optInt("id")) { confirm = null; changed(); }
                Notes.cancelConfirm(app);
                break;
            }
            case Link.P_CONFIRM:
                confirm = o;
                buzz(app, 40, 80, 40);
                changed();
                Notes.confirm(app, o);
                break;
            default:
        }
    }

    private static void state(Context app, JSONObject o) {
        phoneOnline = o.optBoolean("online", phoneOnline);
        String s = o.optString("s");
        // about an older sound while a newer question is being heard (a late "nothing heard"): not for this one
        if (o.has("sid") && Mic.question() && Mic.currentId() != o.optInt("sid")) return;
        switch (s) {
            case "woke": // he said "Hey Jarvis" on raising the wrist
                Player.stop();
                cover(app, false);
                boolean going = Mic.woke();
                heard = "";
                reply = "";
                confirm = null;
                askedAt = SystemClock.elapsedRealtime();
                set(LISTENING, "చెప్పండి, " + Link.name(app) + "…");
                buzz(app, 60);
                // (the phone hears what was already sent after "Jarvis"; if he hasn't asked, he taps the orb)
                if (!going && !"watch".equals(Link.ears(app))) set(UNDERSTANDING, "అర్థం చేసుకుంటున్నాను…");
                Notes.talk(app);
                break;
            case "listening":
                if (o.has("heard")) heard = o.optString("heard");
                set(LISTENING, status.isEmpty() ? "వింటున్నాను…" : status);
                break;
            case "understanding":
                Mic.stop();
                set(UNDERSTANDING, "అర్థం చేసుకుంటున్నాను…");
                break;
            case "thinking":
                if (o.has("heard")) heard = o.optString("heard");
                set(THINKING, o.optString("status", "ఆలోచిస్తున్నాను…"));
                Notes.talk(app);
                break;
            case "reply": {
                confirm = null;
                Notes.cancelConfirm(app);
                reply = o.optString("reply");
                String voice = o.optString("voice", "watch");
                voiceOnPhone = "phone".equals(voice);
                buzz(app, 25);
                if ("watch".equals(voice)) {
                    set(THINKING, ""); // the voice follows in a moment
                    final String r = reply;
                    main.postDelayed(() -> { if (state == THINKING && r.equals(reply)) set(IDLE, ""); }, 15000);
                } else {
                    set(IDLE, voiceOnPhone ? "🎧 జవాబు ఫోన్ ఇయర్‌ఫోన్స్‌లో" : "");
                }
                Notes.talk(app);
                break;
            }
            case "error":
                confirm = null;
                Notes.cancelConfirm(app);
                Mic.stop();
                Player.stop();
                if (o.has("reply")) reply = o.optString("reply");
                show(app, ERROR, o.optString("status", ""));
                break;
            case "idle":
                confirm = null;
                Notes.cancelConfirm(app);
                if (Mic.question()) Mic.stop();
                Hear.stop();
                set(IDLE, o.optString("status", ""));
                Notes.talk(app);
                break;
            case "nolock": // (the phone already closed that sound; an answer still playing goes on)
                if (state == IDLE) { set(IDLE, "మీ గొంతులా అనిపించలేదు"); buzz(app, 20, 60, 20); }
                break;
            case "notice":
                status = o.optString("status");
                changed();
                break;
            default:
        }
    }

    /** All of Jarvis's voice has played (Player thread). */
    static void played(Context app) {
        main.post(() -> {
            Mic.stopKind(Mic.OVER);
            cover(app, false);
            if (state == SPEAKING) set(IDLE, "");
            Notes.talk(app);
        });
    }

    /** The mic failed (Mic thread). */
    static void micFailed(Context app, int kind, String why) {
        main.post(() -> {
            if (kind == Mic.TALK) { show(app, ERROR, why); return; }
            if (kind == Mic.OVER) return; // (the answer still plays)
            listenBroken = true;
            changed();
            Notes.broken(app, why);
        });
    }

    /** His tap on "are you sure?" (the screen or the notification). */
    static void answer(Context c, int id, boolean yes) {
        JSONObject q = confirm;
        if (q != null && q.optInt("id") == id) confirm = null;
        try { Link.send(c, Link.P_CONFIRM_ANSWER, new JSONObject().put("id", id).put("yes", yes)); } catch (Exception ignored) {}
        Notes.cancelConfirm(c);
        changed();
    }

    /** The watch tells the phone about itself (the phone's Settings shows it). */
    static void hello(Context c) {
        Context app = c.getApplicationContext();
        JSONObject o = new JSONObject();
        try {
            long ver = 0;
            try { ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode(); } catch (Exception ignored) {}
            BatteryManager bm = app.getSystemService(BatteryManager.class);
            o.put("app", ver).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("rec", Hear.available(app))
                    .put("mic", micAllowed(app)).put("listening", !listenBroken && (EarService.running || !Link.raise(app) && !Link.hours(app)))
                    .put("bat", bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            if (EarService.worn != null) o.put("worn", EarService.worn);
        } catch (Exception ignored) {}
        Link.send(app, Link.P_HELLO, o);
    }

    // ================================================================ small helpers

    static void set(int s, String st) {
        state = s;
        stateAt = SystemClock.elapsedRealtime();
        if (st != null) status = st;
        changed();
        main.removeCallbacks(watchdog);
        if (s != IDLE && s != ERROR) main.postDelayed(watchdog, 5000);
    }

    private static volatile long stateAt;
    private static Context appCtx;

    /** Never stuck: no word from the phone for too long while listening / thinking, or a voice that never came. */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            int s = state;
            if (s == IDLE || s == ERROR) return;
            long now = SystemClock.elapsedRealtime();
            long quiet = now - Math.max(stateAt, Link.heardAt);
            Context app = appCtx;
            if (app != null && ((s == LISTENING || s == UNDERSTANDING) && quiet > 30_000 || s == THINKING && quiet > 120_000)) { noPhone(app); return; }
            if (s == SPEAKING && !Player.playing() && now - stateAt > 5000) { Mic.stopKind(Mic.OVER); set(IDLE, ""); return; }
            main.postDelayed(this, 5000);
        }
    };

    private static void show(Context app, int s, String st) {
        set(s, st);
        if (s == ERROR) {
            buzz(app, 70, 90, 70);
            final String was = st;
            main.postDelayed(() -> { if (state == ERROR && was.equals(status)) set(IDLE, was); }, 6000);
        }
        Notes.talk(app);
    }

    static void changed() {
        Screen sc = screen;
        if (sc != null) sc.changed();
    }

    static void buzz(Context c, long... pattern) { buzzAs(c, android.os.VibrationAttributes.USAGE_NOTIFICATION, pattern); }

    /**
     * usage: what the vibration is for (VibrationAttributes.USAGE_*): an alert from the background needs its kind
     * (notification, alarm, call), or Android 13+ drops it.
     */
    static void buzzAs(Context c, int usage, long... pattern) {
        try {
            Vibrator v = c.getSystemService(Vibrator.class);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect e;
            if (pattern.length == 1) e = VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE);
            else {
                long[] w = new long[pattern.length + 1];
                System.arraycopy(pattern, 0, w, 1, pattern.length);
                e = VibrationEffect.createWaveform(w, -1);
            }
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, android.os.VibrationAttributes.createForUsage(usage));
            else v.vibrate(e, new android.media.AudioAttributes.Builder().setUsage(
                    usage == android.os.VibrationAttributes.USAGE_ALARM ? android.media.AudioAttributes.USAGE_ALARM
                            : android.media.AudioAttributes.USAGE_NOTIFICATION).build());
        } catch (Exception ignored) {}
    }

    /** The watch itself has internet (for its own voice typing). */
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- W13: the palm over the watch while it speaks

    private static SensorEventListener light;
    private static final float[] lux = new float[2]; // {highest in the last second, its time}

    private static void cover(Context app, boolean on) {
        SensorManager sm = app.getSystemService(SensorManager.class);
        if (sm == null) return;
        if (light != null) { try { sm.unregisterListener(light); } catch (Exception ignored) {} light = null; }
        if (!on) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (s == null) return;
        lux[0] = -1;
        light = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float v = e.values[0];
                float now = SystemClock.elapsedRealtime() / 1000f;
                if (v >= lux[0] || now - lux[1] > 1f) { lux[0] = v; lux[1] = now; }
                // it was clearly lit a moment ago and is now dark: his palm is over it
                if (state == SPEAKING && lux[0] >= 8f && v <= 1f && now - lux[1] <= 1f) stop(app);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        try { sm.registerListener(light, s, SensorManager.SENSOR_DELAY_UI); } catch (Exception ignored) { light = null; }
    }
}
