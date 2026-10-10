package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The home tablet's games: the list (board games on the screen + the talking games), which one her words name,
 * Jarvis's level for each (it follows how she plays), the saved game to go on with, and a small log for Anil's weekly
 * report (only how many were played / won, nothing else).
 */
final class Games {
    private Games() {}

    /** {id, emoji, Telugu name, words that name it}. The last four are talking games (no board). */
    static final String[][] LIST = {
            {"chess", "♟️", "చదరంగం", "చదరంగం|చదరంగ|చెస్|chess"},
            {"snakes", "🐍", "వైకుంఠపాళి", "వైకుంఠపాళి|వైకుంఠ|పాము.{0,6}నిచ్చెన|పాములు|నిచ్చెనల|snake|స్నేక్"},
            {"ludo", "🎲", "లూడో", "లూడో|లూడ|ludo"},
            {"ashta", "🐚", "అష్టా చమ్మా", "అష్టా|అష్ట చమ్మ|అష్టచమ్మ|చమ్మా|గవ్వల ఆట|ashta"},
            {"puli", "🐯", "పులి-మేక", "పులి.{0,4}మేక|పులిమేక|puli"},
            {"daadi", "⚪", "దాడి", "దాడి ఆట|దాడి|daadi"},
            {"xo", "❌", "సున్నా-ఇంటూ", "సున్నా|ఇంటూ|టిక్.{0,2}టాక్|tic.?tac"},
            {"pairs", "🃏", "జతల ఆట", "జతల|జత ఆట|జతలు|కార్డుల ఆట|ఫోటోల ఆట"},
            {"memory", "🧠", "గుర్తుపెట్టుకునే ఆట", "జ్ఞాపక|గుర్తుపెట్టుకునే"},
            {"quiz", "📖", "బైబిల్ క్విజ్", "క్విజ్|quiz|బైబిల్ ప్రశ్న"},
            {"riddle", "🤔", "పొడుపు కథలు", "పొడుపు"},
            {"words", "🔤", "పదాల ఆట", "పదాల ఆట|మాటల ఆట|అక్షరాల ఆట|పదాలాట"}};

    static boolean talking(String id) { return "memory".equals(id) || "quiz".equals(id) || "riddle".equals(id) || "words".equals(id); }

    static String[] row(String id) {
        for (String[] r : LIST) if (r[0].equals(id)) return r;
        return null;
    }

    static String name(String id) { String[] r = row(id); return r == null ? id : r[2]; }
    static String emoji(String id) { String[] r = row(id); return r == null ? "🎲" : r[1]; }

    /** The board game on the screen for this id, or null (a talking game, or one not here). */
    static Game make(Context c, String id) {
        switch (id) {
            case "chess": return new ChessGame(c);
            case "snakes": return new SnakesGame(c);
            case "ludo": return new LudoGame(c);
            case "ashta": return new AshtaGame(c);
            case "puli": return new PuliGame(c);
            case "daadi": return new DaadiGame(c);
            case "xo": return new XoGame(c);
            case "pairs": return new PairsGame(c);
            default: return null;
        }
    }

    /** A board game in this build (no View made). */
    static boolean isBoard(String id) {
        if (id == null) return false;
        switch (id) {
            case "chess": case "snakes": case "ludo": case "ashta": case "puli": case "daadi": case "xo": case "pairs": return true;
            default: return false;
        }
    }

    /** The game her words name ("లూడో ఆడదాం" → ludo), or null. */
    static String named(String t) {
        if (t == null) return null;
        String s = t.toLowerCase(Locale.ROOT);
        for (String[] r : LIST) if (s.matches("(?s).*(" + r[3] + ").*")) return r[0];
        return null;
    }

    /** Words that ask to play ("ఆడదాం", "ఆట పెట్టు"…). */
    static boolean playWords(String t) {
        return t != null && t.matches("(?s).*(ఆడదాం|ఆడుదాం|ఆడాలి|ఆడతాను|ఆడుకుందాం|ఆడుకుంటాను|ఆడదామా|ఆడు |ఆడు$|ఆట|గేమ్|game|పెట్టు|తెరువు).*");
    }

    // ================================================================ level, results, saved game

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_games", Context.MODE_PRIVATE); }

    /** Jarvis's level for this game: 0 easy (the start), 1 medium, 2 hard. */
    static int level(Context c, String id) { return Math.max(0, Math.min(2, sp(c).getInt("lvl_" + id, 0))); }
    static void setLevel(Context c, String id, int l) { sp(c).edit().putInt("lvl_" + id, Math.max(0, Math.min(2, l))).putInt("streak_" + id, 0).apply(); }
    static final String[] LEVELS = {"సులభం", "మధ్యస్థం", "కష్టం"};

    /**
     * A game against Jarvis ended (her side: won / lost / draw): the level follows her (two wins in a row → a little
     * harder, two losses → easier). Returns what Jarvis says about it, or "".
     */
    static String result(Context c, String id, String result, boolean withJarvis, String her) {
        log(c, id, result);
        if (!withJarvis) return "";
        SharedPreferences p = sp(c);
        int streak = p.getInt("streak_" + id, 0), lvl = level(c, id);
        if ("won".equals(result)) streak = streak < 0 ? 1 : streak + 1;
        else if ("lost".equals(result)) streak = streak > 0 ? -1 : streak - 1;
        String say = "";
        if (streak >= 2 && lvl < 2) {
            lvl++;
            streak = 0;
            say = her + ", మీరు బాగా ఆడుతున్నారు! ఇక నుంచి నేను కొంచెం జాగ్రత్తగా, కష్టంగా ఆడతాను.";
        } else if (streak <= -2 && lvl > 0) {
            lvl--;
            streak = 0;
            say = "ఈసారి నేను కొంచెం సులభంగా ఆడతాను " + her + ", మీరే గెలుస్తారు చూడండి.";
        }
        p.edit().putInt("lvl_" + id, lvl).putInt("streak_" + id, streak).apply();
        return say;
    }

    /** A game played (won / lost / draw / played): kept 60 days, for the weekly report only. */
    static synchronized void log(Context c, String id, String result) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("log", "[]")), keep = new JSONArray();
            long cut = System.currentTimeMillis() - 60L * 86400_000L;
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optLong("t") > cut) keep.put(a.getJSONObject(i));
            keep.put(new JSONObject().put("t", System.currentTimeMillis()).put("g", id).put("r", result));
            sp(c).edit().putString("log", keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** "🎲 ఆటలు: 5 (చదరంగం 2, లూడో 3) · గెలిచినవి 3" for the last days, or "" when none. */
    static String weekLine(Context c, int days) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("log", "[]"));
            long cut = System.currentTimeMillis() - days * 86400_000L;
            Map<String, Integer> per = new LinkedHashMap<>();
            int n = 0, won = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("t") < cut) continue;
                n++;
                if ("won".equals(o.optString("r"))) won++;
                String g = name(o.optString("g"));
                per.put(g, per.containsKey(g) ? per.get(g) + 1 : 1);
            }
            if (n == 0) return "";
            StringBuilder b = new StringBuilder("🎲 ఆటలు: ").append(n).append(" (");
            int k = 0;
            for (Map.Entry<String, Integer> e : per.entrySet()) b.append(k++ == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
            return b.append(") · గెలిచినవి ").append(won).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The unfinished game kept (to go on with), or null. */
    static String savedId(Context c) {
        String id = sp(c).getString("saved_id", "");
        return id.isEmpty() || sp(c).getString("saved", "").isEmpty() ? null : id;
    }

    static String saved(Context c) { return sp(c).getString("saved", ""); }

    static void keep(Context c, String id, String all) {
        if (all == null || all.isEmpty()) return;
        sp(c).edit().putString("saved_id", id).putString("saved", all).putLong("saved_at", System.currentTimeMillis()).apply();
    }

    /** The option the kept game was started with (🔄 after going on with it starts the same kind). */
    static void keepOption(Context c, int option) { sp(c).edit().putInt("saved_opt", option).apply(); }
    static int savedOption(Context c) { return sp(c).getInt("saved_opt", 0); }

    static void forget(Context c) { sp(c).edit().remove("saved_id").remove("saved").apply(); }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date()); }
}
