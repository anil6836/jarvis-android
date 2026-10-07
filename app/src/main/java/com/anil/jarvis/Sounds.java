package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * House sounds heard on the wake-word microphone, with the same sound model as coughs (see {@link CoughDetector}):
 * - a knock on the door or the calling bell: while songs play or earphones are on, the song stops and Jarvis says it
 *   ("always" says it every time; "off" never);
 * - pressure-cooker whistles: "3 విజిల్స్ లెక్కపెట్టు" -> each whistle counted aloud, and at the last one "స్టవ్ ఆపండి"
 *   (said again until he answers); the mic stays on while counting, even with the screen off;
 * - gargling: counted as a done home remedy on cough days;
 * - his own bell and cooker: one sound kept when he teaches it ("బెల్ నేర్చుకో"), and sounds like it are known later.
 * Rules tuned on real recordings (door knocks against claps, steps, typing, clicks). Nothing is recorded or sent.
 */
final class Sounds {
    private Sounds() {}

    static final int KNOCK = 353, DOOR = 348, DOORBELL = 349, DINGDONG = 350, WHISTLE = 396, STEAM_WHISTLE = 397,
            STEAM = 290, GARGLE = 51, GURGLE = 291;
    static final String ACTION_COOKER_OK = "com.anil.jarvis.COOKER_OK";
    private static final String CHANNEL = "jarvis_house_sounds";
    private static final int NOTE_COOKER = 7310, NOTE_DOOR = 7311;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Counting whistles / learning a sound: the sound model must run and the mic must stay on. */
    static volatile boolean cookerOn;
    private static volatile String teachKind;
    private static volatile long teachUntil;
    private static volatile boolean loaded;
    private static long lastDoorAt, lastWhistleAt, lastGargleAt;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_sounds", Context.MODE_PRIVATE); }

    /** After the app starts: is a count still going on? (read once). */
    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        cookerOn = cookerActive(c);
    }

    /** "music" (default: only while songs play or earphones are on), "always" or "off". */
    static String doorMode(Context c) { return sp(c).getString("door_mode", "music"); }

    static void setDoorMode(Context c, String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!m.equals("always") && !m.equals("off")) m = "music";
        sp(c).edit().putString("door_mode", m).apply();
    }

    static String doorModeText(String m) {
        return m.equals("always") ? "ఎప్పుడూ చెప్పు" : m.equals("off") ? "ఆఫ్" : "పాటలు / ఇయర్‌ఫోన్స్ ఉన్నప్పుడే";
    }

    /** The sound model has work besides coughs. */
    static boolean wanted(Context c) {
        load(c);
        return cookerOn || teaching() || !"off".equals(doorMode(c));
    }

    /** The microphone must stay on whatever the "when to listen" setting says (counting whistles, learning, a test). */
    static boolean holdMic() { return cookerOn || teaching() || CoughDetector.testing(); }

    static boolean teaching() { return teachKind != null && System.currentTimeMillis() < teachUntil; }

    // ---------------------------------------------------------------- what the model heard

    /**
     * One loud sound, with the model's scores (all 521), its loudness and for how many of its 80 ms pieces it stayed loud.
     * Returns what it was taken for (for the test screen), or null.
     */
    static String heard(Context c, float[] s, double loud, int loudChunks) {
        load(c);
        long now = System.currentTimeMillis();
        boolean test = CoughDetector.testing(); // the test screen only shows what it heard: nothing is said or counted
        if (teaching()) {
            String k = teachKind;
            teachKind = null;
            keep(c, k, s);
            Announcer.say(c, "సరే, ఈ శబ్దం మీ " + label(k) + " అని గుర్తుపెట్టుకున్నాను.");
            return "నేర్చుకున్నాను: " + label(k);
        }
        // pressure-cooker whistle: long and loud, sounds like a whistle / steam (or like his own cooker)
        double w = Math.max(Math.max(s[STEAM_WHISTLE], s[WHISTLE]), 0.8 * s[STEAM]);
        boolean whistle = (w >= 0.25 && loudChunks >= 7) || (similar(c, "cooker", s) >= 0.85 && loudChunks >= 5);
        if (whistle && (cookerOn || test)) {
            if (test) return "కుక్కర్ విజిల్";
            if (now - lastWhistleAt > 25_000) { lastWhistleAt = now; whistle(c); }
            else lastWhistleAt = now; // the same whistle still going
            return "కుక్కర్ విజిల్";
        }
        // gargling (a home remedy done)
        if (Math.max(s[GARGLE], s[GURGLE]) >= 0.3 && loudChunks >= 6) {
            if (!test && now - lastGargleAt > 10 * 60_000L) { lastGargleAt = now; CoughLog.remedyDone(c, "gargle", true); }
            return "పుక్కిలించడం";
        }
        // the calling bell / a knock on the door
        boolean bell = Math.max(s[DOORBELL], s[DINGDONG]) >= 0.3 || similar(c, "bell", s) >= 0.88;
        boolean knock = s[KNOCK] + 0.5 * s[DOOR] >= 0.4;
        if (bell || knock) {
            if (!test && now - lastDoorAt > 30_000) { lastDoorAt = now; door(c, bell); }
            return bell ? "కాలింగ్ బెల్" : "తలుపు కొట్టడం";
        }
        if (whistle) return "విజిల్ (లెక్క ఆఫ్)";
        return null;
    }

    private static String label(String kind) { return "bell".equals(kind) ? "కాలింగ్ బెల్" : "కుక్కర్ విజిల్"; }

    // ---------------------------------------------------------------- door

    private static void door(Context c, boolean bell) {
        String mode = doorMode(c);
        if (mode.equals("off")) return;
        Prefs p = new Prefs(c);
        if (p.night() || CallControl.busyWithCall() || MainActivity.busyTalking()) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        boolean music = am != null && am.isMusicActive();
        if (mode.equals("music") && !music && !earphones(am)) return; // he can hear it himself
        boolean paused = false;
        if (music) { // stop the song so he hears the door; he starts it again himself
            try {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
                paused = true;
            } catch (Exception ignored) {}
        }
        String what = bell ? "కాలింగ్ బెల్ మోగింది." : "ఎవరో తలుపు కొడుతున్నారు.";
        Announcer.say(c, p.name() + ", " + what + (paused ? " పాట ఆపాను." : ""));
        note(c, NOTE_DOOR, bell ? "🔔 కాలింగ్ బెల్" : "🚪 తలుపు దగ్గర ఎవరో", what, null);
    }

    private static boolean earphones(AudioManager am) {
        if (am == null) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || (android.os.Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------------------------------------------------------------- pressure cooker

    static boolean cookerActive(Context c) {
        SharedPreferences s = sp(c);
        int target = s.getInt("cooker_target", 0);
        return target > 0 && s.getInt("cooker_count", 0) < target && System.currentTimeMillis() - s.getLong("cooker_start", 0) < 2 * 3600_000L;
    }

    /** "3 విజిల్స్ లెక్కపెట్టు". */
    static JSONObject startCooker(Context c, int whistles) throws Exception {
        int n = Math.max(1, Math.min(15, whistles));
        sp(c).edit().putInt("cooker_target", n).putInt("cooker_count", 0).putLong("cooker_start", System.currentTimeMillis()).apply();
        cookerOn = true;
        loaded = true;
        cancelRepeats();
        boolean mic = WakeService.running;
        if (mic) WakeService.recheck(c);
        else if (new Prefs(c).wakeReady()) { WakeService.start(c, false); mic = true; }
        note(c, NOTE_COOKER, "🍲 కుక్కర్: 0 / " + n + " విజిల్స్", "Jarvis వింటున్నాడు. చివరి విజిల్‌కి స్టవ్ ఆపమని చెప్తాను.", "ఆపు");
        JSONObject o = new JSONObject().put("ok", true).put("counting", n);
        if (!mic) o.put("problem", "The 'Jarvis' wake-word microphone is off, so Jarvis cannot hear the whistles. Tell him to switch on the wake word in Settings.");
        else o.put("note", "Tell him in a few words: Jarvis is listening and will say each whistle, and tell him to switch off the stove at whistle " + n
                + ". Keep the phone in the kitchen, near the cooker.");
        return o;
    }

    static JSONObject cookerStatus(Context c) throws Exception {
        SharedPreferences s = sp(c);
        return new JSONObject().put("ok", true).put("counting", cookerActive(c)).put("whistles_heard", s.getInt("cooker_count", 0))
                .put("target", s.getInt("cooker_target", 0));
    }

    /** "ఆపాను" / "లెక్క ఆపు": done with it. */
    static void stopCooker(Context c) {
        sp(c).edit().putInt("cooker_target", 0).apply();
        cookerOn = false;
        cancelRepeats();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE_COOKER);
        if (WakeService.running) WakeService.recheck(c); // the screen may be off: the mic can rest again
    }

    private static void whistle(Context c) {
        SharedPreferences s = sp(c);
        int target = s.getInt("cooker_target", 0), n = s.getInt("cooker_count", 0) + 1;
        s.edit().putInt("cooker_count", n).apply();
        Prefs p = new Prefs(c);
        if (n < target) {
            Announcer.say(c, n + " విజిల్" + (n > 1 ? "స్" : "") + ".");
            note(c, NOTE_COOKER, "🍲 కుక్కర్: " + n + " / " + target + " విజిల్స్", "ఇంకా " + (target - n) + " రావాలి.", "ఆపు");
            return;
        }
        cookerOn = false;
        String say = p.name() + ", " + n + " విజిల్స్ అయ్యాయి. స్టవ్ ఆపండి.";
        Announcer.say(c, say);
        note(c, NOTE_COOKER, "🍲 " + n + " విజిల్స్ అయ్యాయి", "స్టవ్ ఆపండి.", "ఆపాను");
        // said again until he answers (twice more), then the mic rests
        final Context app = c.getApplicationContext();
        repeat = new Runnable() {
            int left = 2;
            @Override public void run() {
                if (sp(app).getInt("cooker_target", 0) == 0) return; // he said / tapped "ఆపాను"
                Announcer.say(app, p.name() + ", స్టవ్ ఆపారా? " + n + " విజిల్స్ అయిపోయాయి.");
                if (--left > 0) main.postDelayed(this, 25_000);
                else stopCooker(app);
            }
        };
        main.postDelayed(repeat, 25_000);
    }

    private static Runnable repeat;

    private static void cancelRepeats() {
        Runnable r = repeat;
        if (r != null) main.removeCallbacks(r);
        repeat = null;
    }

    // ---------------------------------------------------------------- learning his own sounds

    /** The next loud sound (within 30 s) is his calling bell / cooker whistle. */
    static void teach(Context c, String kind) {
        teachKind = "cooker".equals(kind) ? "cooker" : "bell";
        teachUntil = System.currentTimeMillis() + 30_000;
        if (WakeService.running) WakeService.recheck(c);
    }

    /** The test screen: "the last sound was my bell / cooker". */
    static boolean teachLast(Context c, String kind) {
        float[] s = CoughDetector.lastScores;
        if (s == null) return false;
        keep(c, "cooker".equals(kind) ? "cooker" : "bell", s);
        return true;
    }

    static int taughtCount(Context c, String kind) {
        try { return new JSONArray(sp(c).getString("taught_" + kind, "[]")).length(); } catch (Exception e) { return 0; }
    }

    static void forget(Context c, String kind) { sp(c).edit().remove("taught_" + kind).apply(); }

    /** Keeps the clear part of the scores (the last 4 of each kind). */
    private static void keep(Context c, String kind, float[] s) {
        try {
            JSONObject v = new JSONObject();
            for (int k = 0; k < s.length; k++) if (s[k] >= 0.03f) v.put(String.valueOf(k), Math.round(s[k] * 1000) / 1000.0);
            JSONArray a = new JSONArray(sp(c).getString("taught_" + kind, "[]"));
            a.put(v);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 4); i < a.length(); i++) keep.put(a.get(i));
            sp(c).edit().putString("taught_" + kind, keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** How much this sound is like his taught one (0..1, cosine), 0 if none taught. */
    private static double similar(Context c, String kind, float[] s) {
        try {
            String raw = sp(c).getString("taught_" + kind, "");
            if (raw.isEmpty()) return 0;
            JSONArray a = new JSONArray(raw);
            double ss = 0;
            for (float x : s) if (x >= 0.03f) ss += x * x;
            if (ss == 0) return 0;
            double best = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i);
                double dot = 0, vv = 0;
                for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                    String key = it.next();
                    double y = v.getDouble(key);
                    vv += y * y;
                    int k = Integer.parseInt(key);
                    if (k >= 0 && k < s.length && s[k] >= 0.03f) dot += y * s[k];
                }
                if (vv > 0) best = Math.max(best, dot / Math.sqrt(ss * vv));
            }
            return best;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- notification

    private static void note(Context c, int id, String title, String text, String action) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ఇంటి శబ్దాలు (తలుపు, కుక్కర్)", NotificationManager.IMPORTANCE_HIGH));
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title).setContentText(text)
                    .setAutoCancel(true).setOnlyAlertOnce(id == NOTE_COOKER && action != null && action.equals("ఆపు"));
            if (action != null) {
                PendingIntent pi = PendingIntent.getBroadcast(c, 7312, new Intent(c, AlarmReceiver.class).setAction(ACTION_COOKER_OK),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new Notification.Action.Builder(null, action.equals("ఆపాను") ? "✅ ఆపాను" : "⏹ లెక్క ఆపు", pi).build());
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {}
    }
}
