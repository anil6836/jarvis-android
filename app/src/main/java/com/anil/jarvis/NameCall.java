package com.anil.jarvis;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * S16: while he has earphones on, someone near the phone calling his name -> the song goes down and Jarvis tells him
 * "ఎవరో మిమ్మల్ని పిలుస్తున్నారు". Heard by the wake-word listener ({@link WakeEngine}) with the same small word model as
 * "Jarvis" and a tiny word list of his names (Settings; his name by default). A name the model doesn't know is spelt with two
 * words it does know that sound the same ("anil" -> "a nil"). Not in a call, while riding or while he talks to Jarvis.
 * Nothing is recorded or sent.
 */
final class NameCall {
    private NameCall() {}

    private static Handler mainHandler;

    /** The main thread's handler, made when first needed. */
    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }
    /** For Settings: which names are listened for and how, or why not. */
    static volatile String status = "";
    private static volatile long lastSaid;

    static boolean on(Context c) { return SafetySounds.sp(c).getBoolean("name_call", true); }

    static void setOn(Context c, boolean on) { SafetySounds.sp(c).edit().putBoolean("name_call", on).apply(); }

    /** The names people call him by, comma separated (his name in Settings when he set none). */
    static String names(Context c) {
        String s = SafetySounds.sp(c).getString("call_names", "").trim();
        return s.isEmpty() ? new Prefs(c).name().trim() : s;
    }

    static void setNames(Context c, String s) { SafetySounds.sp(c).edit().putString("call_names", s == null ? "" : s.trim()).apply(); }

    /** Earphones on, not in a call, not riding, not talking to Jarvis: listen for his name now. */
    static boolean activeNow(Context c) {
        if (!on(c)) return false;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (!Sounds.earphones(am)) return false;
        return !CallControl.busyWithCall() && !new Prefs(c).driving() && !Bike.riding(c) && !MainActivity.busyTalking();
    }

    /** The model's word list for his names, the words of each, and a line for Settings. */
    static final class Built {
        final String grammar;
        final List<String[]> phrases;
        final String status;

        Built(String grammar, List<String[]> phrases, String status) { this.grammar = grammar; this.phrases = phrases; this.status = status; }
    }

    static Built build(String names, Predicate<String> known) {
        List<String[]> phrases = new ArrayList<>();
        List<String> used = new ArrayList<>(), missing = new ArrayList<>();
        for (String raw : names.split("[,;]")) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            String latin = latinOf(n);
            String[] words = latin.length() < 2 ? null : spell(latin, known);
            if (words == null) { missing.add(n); continue; }
            phrases.add(words);
            used.add(n + (words.length > 1 || !words[0].equals(n.toLowerCase(Locale.ROOT)) ? " (\"" + String.join(" ", words) + "\")" : ""));
        }
        JSONArray g = new JSONArray();
        for (String[] w : phrases) g.put(String.join(" ", w));
        String line = (used.isEmpty() ? "" : "ఈ పేర్లు వింటాను: " + String.join(", ", used) + ".")
                + (missing.isEmpty() ? "" : (used.isEmpty() ? "" : " ") + "ఇవి వినలేను (మోడల్‌లో లేవు): " + String.join(", ", missing) + ".");
        if (g.length() == 0) return new Built(null, Collections.emptyList(), line.isEmpty() ? "పేర్లు లేవు." : line);
        g.put("[unk]");
        return new Built(g.toString(), phrases, line);
    }

    /** The name in plain letters: Telugu spelled out ("అనిల్" -> "anil"), long vowels short, letters only. */
    static String latinOf(String name) {
        String s = name.matches(".*[\\u0C00-\\u0C7F].*") ? Offline.latin(name) : name;
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return s.replaceAll("([aeiou])\\1+", "$1");
    }

    /**
     * The name as the model's words: itself when known, else two known words that say it ("a" + "nil"): a split where the
     * second word starts with a consonant first (Telugu names are said a-nil, ra-vi), then the evenest.
     */
    static String[] spell(String latin, Predicate<String> known) {
        if (known.test(latin)) return new String[]{latin};
        String[] best = null;
        int bestScore = -1;
        for (int i = 1; i < latin.length(); i++) {
            String a = latin.substring(0, i), b = latin.substring(i);
            // a single letter only as "a" (said "uh"); "n", "u", "i" would be said as letters
            if ((a.length() == 1 && !a.equals("a")) || b.length() == 1) continue;
            if (!known.test(a) || !known.test(b)) continue;
            int score = ("aeiou".indexOf(b.charAt(0)) < 0 ? 100 : 0) + Math.min(a.length(), b.length());
            if (score > bestScore) { bestScore = score; best = new String[]{a, b}; }
        }
        return best;
    }

    /** The name heard in a result from the model (each word sure enough), or null. */
    static String match(String json, List<String[]> phrases) {
        if (json == null || phrases == null || phrases.isEmpty()) return null;
        try {
            JSONArray r = new JSONObject(json).optJSONArray("result");
            if (r == null) return null;
            for (String[] p : phrases) {
                double need = p.length == 1 ? 0.85 : 0.8;
                for (int i = 0; i + p.length <= r.length(); i++) {
                    boolean all = true;
                    for (int k = 0; k < p.length && all; k++) {
                        JSONObject w = r.getJSONObject(i + k);
                        all = p[k].equals(w.optString("word")) && w.optDouble("conf", 0) >= need;
                    }
                    if (all) return String.join(" ", p);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** His name was called: the song goes down for a few seconds and Jarvis tells him (at most once in 30 seconds). */
    static void heard(Context c, String phrase) {
        long now = System.currentTimeMillis();
        if (now - lastSaid < 30_000) return;
        lastSaid = now;
        final Context app = c.getApplicationContext();
        main().post(() -> {
            if (!activeNow(app)) return;
            AudioFocusRequest hold = Duck.hold(app); // quieter long enough for him to hear the person
            Announcer.say(app, "ఎవరో మిమ్మల్ని పిలుస్తున్నారు.");
            main().postDelayed(() -> Duck.release(app, hold), 8000);
        });
    }
}
