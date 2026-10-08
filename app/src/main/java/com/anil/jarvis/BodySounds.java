package com.anil.jarvis;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sounds from his own body, heard on the wake-word microphone with the same sound model (see {@link CoughDetector}),
 * for sounds that were neither a cough nor a sneeze:
 * - sniffing again and again -> "జలుబు మొదలవుతోందా?" before the sneezes start; throat clearing again and again -> "గొంతు ఇబ్బందిగా ఉందా?";
 * - wheezing / panting again and again (not while exercising or riding) -> "ఊపిరి ఇబ్బందిగా ఉందా?" (danger signs -> 108);
 * - hiccups that keep coming -> a simple tip; many burps after a meal -> asks about gas / acidity;
 * - two claps, or a short whistle, call Jarvis like "Jarvis" does;
 * - at night (mic on while charging): snoring, counted in minutes for the morning report.
 * Each is counted for the day (the doctor's summary); asks are rare (hours apart, never at night, in a call, while
 * riding or while a video plays). Rules checked on real recordings: hiccups / burps / throat clearing also show up
 * inside coughs, so those are only taken when the sound was not a cough; heavy breathing can look like wheezing, so
 * only a run of them counts.
 */
final class BodySounds {
    private BodySounds() {}

    static final int SNIFF = 45, THROAT = 43, COUGH = 42, WHEEZE = 37, PANT = 40, HICCUP = 54, BURP = 53, SNORE = 38,
            CLAPPING = 58, HANDS = 56, SNAP = 57, SLAP = 461, APPLAUSE = 62, WHISTLING = 35, SPEECH = 0, MUSIC = 132,
            STEAM_WHISTLE = 397;

    private static final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_body", Context.MODE_PRIVATE); }

    static boolean asksOn(Context c) { return sp(c).getBoolean("asks", true); }

    static void setAsks(Context c, boolean on) { sp(c).edit().putBoolean("asks", on).apply(); }

    static boolean callOn(Context c) { return sp(c).getBoolean("clap_call", true); }

    static void setCall(Context c, boolean on) { sp(c).edit().putBoolean("clap_call", on).apply(); }

    /** How many of this kind in the last `within` ms (after adding one now). */
    private static synchronized int mark(String kind, long within) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = seen.get(kind);
        if (q == null) { q = new ArrayDeque<>(); seen.put(kind, q); }
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > within) q.removeFirst();
        return q.size();
    }

    private static synchronized void clear(String kind) {
        ArrayDeque<Long> q = seen.get(kind);
        if (q != null) q.clear();
    }

    /**
     * One loud sound that was not a cough or sneeze: its scores, loudness, how many 80 ms pieces stayed loud and the
     * loudness of each piece (for two claps). Returns what it was taken for (test screen), or null.
     */
    static String heard(Context c, float[] s, double noise, int loudN, float[] rms, boolean test) {
        // a call: two claps, or a short whistle
        if (callOn(c) && callAllowed(c, test)) {
            boolean clapLike = s[CLAPPING] + s[HANDS] + s[SNAP] + s[SLAP] >= 0.2f;
            boolean busyScene = s[APPLAUSE] >= 0.4f || s[SPEECH] >= 0.4f || s[MUSIC] >= 0.4f;
            if (clapLike && !busyScene && doubleClap(rms, noise)) {
                if (!test) WakeService.soundWake();
                return "రెండు చప్పట్లు → Jarvis పిలుపు";
            }
            if (s[WHISTLING] >= 0.45f && loudN >= 3 && loudN <= 8 && s[STEAM_WHISTLE] < s[WHISTLING] && !busyScene && !Sounds.counting(c)) {
                if (!test) WakeService.soundWake();
                return "ఈల → Jarvis పిలుపు";
            }
        }
        String kind = null, label = null;
        if (s[SNIFF] >= 0.35f) { kind = "sniff"; label = "ముక్కు ఎగబీల్చడం"; }
        else if (s[THROAT] >= 0.45f && s[COUGH] < 0.3f) { kind = "throat"; label = "గొంతు సవరించడం"; }
        else if ((s[WHEEZE] >= 0.5f || s[PANT] >= 0.5f) && s[SNORE] < Math.max(s[WHEEZE], s[PANT])) { kind = "wheeze"; label = "గురగుర / ఆయాసం"; }
        else if (s[HICCUP] >= 0.45f && s[HICCUP] > s[COUGH]) { kind = "hiccup"; label = "ఎక్కిళ్లు"; }
        else if (s[BURP] >= 0.45f && s[BURP] > s[COUGH]) { kind = "burp"; label = "త్రేన్పు"; }
        if (kind == null || test) return label;
        if (musicPlaying(c)) return label; // a video / song on the phone: not his
        CoughLog.addBody(c, kind);
        switch (kind) {
            case "sniff": {
                int n = mark(kind, 30 * 60_000L);
                if (n >= 6 && CoughLog.count(c, java.time.LocalDate.now(), "sneeze") < 2
                        && ask(c, kind, 3, "%s, ముక్కు ఎగబీలుస్తున్నారు. జలుబు మొదలవుతోందా?", "ఆవిరి పట్టడం లాంటి చిట్కా చెప్పనా?",
                        " [health: Jarvis heard him sniffing again and again (a cold may be starting). If yes: health_advice (cold) home remedies first; "
                                + "the tablet only when he asks. If no / it's nothing, just say okay warmly.]")) clear(kind);
                break;
            }
            case "throat": {
                int n = mark(kind, 30 * 60_000L);
                if (n >= 5 && ask(c, kind, 3, "%s, గొంతు సవరించుకుంటున్నారు. గొంతు ఇబ్బందిగా ఉందా?", "గోరువెచ్చని ఉప్పు నీళ్లతో పుక్కిలిస్తే బాగుంటుంది. ఇంకా ఏమైనా చెప్పనా?",
                        " [health: Jarvis heard him clearing his throat again and again. If he says yes: health_advice (throat) home remedies first. "
                                + "If no, just say okay warmly.]")) clear(kind);
                break;
            }
            case "wheeze": {
                if (Exercise.running() || Bike.riding(c) || new Prefs(c).driving()) break; // out of breath from exercise / a ride
                int n = mark(kind, 10 * 60_000L);
                if (n >= 3 && ask(c, kind, 2, "%s, ఊపిరి తీసుకోవడం కొంచెం కష్టంగా వినిపిస్తోంది.", "ఊపిరి ఇబ్బందిగా ఉందా? ఛాతీ బిగుసుకుపోయినట్టు ఉందా?",
                        " [health: Jarvis heard wheezing / heavy breathing several times. Ask calmly. If he says breathing is really hard, lips/face turning blue, "
                                + "chest pain or he can't speak full sentences -> tell him to call 108 now and offer to call. Mild: sit upright, slow breaths, "
                                + "health_advice (breathing) and suggest a doctor soon if it keeps coming. If no, just say okay.]")) clear(kind);
                break;
            }
            case "hiccup": {
                int n = mark(kind, 3 * 60_000L);
                if (n >= 3 && tip(c, kind, 60, "ఎక్కిళ్లు వస్తున్నాయి. కొంచెం కొంచెం నీళ్లు తాగండి, లేదా ఊపిరి కాసేపు బిగపట్టి నెమ్మదిగా వదలండి.")) clear(kind);
                break;
            }
            case "burp": {
                int n = mark(kind, 30 * 60_000L);
                if (n >= 4 && ask(c, kind, 24, "%s, త్రేన్పులు ఎక్కువగా వస్తున్నాయి.", "కడుపు ఉబ్బరంగా, గ్యాస్‌గా ఉందా? ఏదైనా చిట్కా చెప్పనా?",
                        " [health: Jarvis heard many burps (after a meal?). If yes: health_advice (acidity / gas) home remedies first (eat slowly, జీలకర్ర నీళ్లు, "
                                + "a short walk); the tablet only when he asks. If no, just say okay warmly.]")) clear(kind);
                break;
            }
            default: break;
        }
        return label;
    }

    /**
     * Claps / a whistle may call Jarvis: not with "only my voice" on (anyone can clap), not while a song / video plays,
     * and at night only with the screen on (no bright screen at 3 am from a sound in the house).
     */
    private static boolean callAllowed(Context c, boolean test) {
        if (test) return true;
        if (new Prefs(c).voiceLock() || musicPlaying(c)) return false;
        int h = LocalTime.now().getHour();
        if (h >= 22 || h < 7) {
            try {
                android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                return pm != null && pm.isInteractive();
            } catch (Exception e) { return false; }
        }
        return true;
    }

    /** Two sharp claps: two short peaks 160-640 ms apart with a quiet gap between, and quiet after. */
    static boolean doubleClap(float[] r, double noise) {
        if (r == null || r.length < 6) return false;
        float max = 0;
        for (float v : r) max = Math.max(max, v);
        double thr = Math.max(Math.max(220, noise * 4), max * 0.45);
        int peaks = 0, first = -1, last = -1;
        boolean in = false;
        for (int i = 0; i < r.length; i++) {
            if (r[i] >= thr) {
                if (!in) { peaks++; if (first < 0) first = i; last = i; in = true; }
            } else if (r[i] < thr * 0.5) {
                in = false;
            }
        }
        if (peaks != 2 || last - first < 2 || last - first > 8) return false;
        for (int i = last + 2; i < r.length; i++) if (r[i] >= thr * 0.5) return false; // nothing loud after (not a clapping rhythm, not speech)
        return true;
    }

    /** At night, a sound sampled now and then (snoring is steady, not a burst): minutes with snoring. */
    static String sampled(Context c, float[] s, boolean night, boolean test) {
        if (!night || s[SNORE] < 0.45f) return null;
        if (!test) CoughLog.snoreMinute(c);
        return "గురక";
    }

    private static boolean musicPlaying(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.isMusicActive();
        } catch (Exception e) { return false; }
    }

    /** Not now: night, Do Not Disturb, a call, riding, resting, Jarvis talking. */
    private static boolean quiet(Context c, Prefs p) {
        int h = LocalTime.now().getHour();
        if (h >= 22 || h < 7 || p.night() || p.driving() || CallControl.busyWithCall() || MainActivity.busyTalking() || Rest.resting(c)) return true;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) { return false; }
    }

    /** Asks him (panel with a question) at most once per `hours` for this kind. Returns true when it asked. */
    private static boolean ask(Context c, String kind, int hours, String say, String question, String hint) {
        Prefs p = new Prefs(c);
        if (!asksOn(c) || quiet(c, p)) return false;
        long now = System.currentTimeMillis();
        if (now - sp(c).getLong("asked_" + kind, 0) < hours * 3600_000L) return false;
        sp(c).edit().putLong("asked_" + kind, now).apply();
        Proactive.say(c, String.format(say, p.name()), question, hint);
        return true;
    }

    /** Just says a tip (no question), at most once per `minutes` for this kind. */
    private static boolean tip(Context c, String kind, int minutes, String text) {
        Prefs p = new Prefs(c);
        if (!asksOn(c) || quiet(c, p)) return false;
        long now = System.currentTimeMillis();
        if (now - sp(c).getLong("asked_" + kind, 0) < minutes * 60_000L) return false;
        sp(c).edit().putLong("asked_" + kind, now).apply();
        Announcer.say(c, p.name() + ", " + text);
        return true;
    }
}
