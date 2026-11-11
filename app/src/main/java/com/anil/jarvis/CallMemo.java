package com.anil.jarvis;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 📞 B (Anil, 12 Nov "A,B,C,D,E చేయి"): while someone he saved rings, what is pending with them, shown (never said — someone
 * may be beside him) on the call card and the watch: money owed either way, their birthday soon, the last call note about
 * them, a reminder that names them. Only what Jarvis already keeps on this phone; nothing is looked up or sent.
 */
final class CallMemo {
    private CallMemo() {}

    /** His switch (⚙️ → 📞, on). */
    static boolean on(Context c) { return CallCare.on(c, "call_memo", true); }

    /** The lines for this caller (newline-joined, "" when nothing); main thread is fine (small lists). */
    static String forCall(Context c, String who) {
        try {
            List<JSONObject> rem = new ArrayList<>();
            for (JSONObject r : Store.get(c).reminders()) if (!r.optBoolean("done")) rem.add(r);
            return String.join("\n", lines(who, Debts.open(c), Birthdays.upcoming(c, 7), Notes.list(c, "notes"), rem, System.currentTimeMillis()));
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================ pure

    /** At most four lines: 💰 money, 🎂 a birthday within a week, 📝 the newest note naming them (90 days), ⏰ the soonest reminder naming them. */
    static List<String> lines(String who, List<JSONObject> debts, List<JSONObject> bdays, List<JSONObject> notes, List<JSONObject> reminders, long now) {
        List<String> out = new ArrayList<>();
        if (who == null || who.trim().isEmpty()) return out;
        for (JSONObject d : debts) {
            if (out.size() >= 2 || !samePerson(who, d.optString("name"))) continue;
            String k = d.optString("kind");
            double left = Debts.left(d);
            if (left <= 0) continue;
            if ("lent".equals(k)) out.add("💰 మీకు " + Debts.money(left) + " ఇవ్వాలి");
            else if ("borrowed".equals(k)) out.add("💰 మీరు " + Debts.money(left) + " ఇవ్వాలి");
        }
        for (JSONObject b : bdays) {
            if (!samePerson(who, b.optString("name"))) continue;
            int d = b.optInt("in_days");
            String what = "anniversary".equals(b.optString("kind")) ? "పెళ్లిరోజు" : "పుట్టినరోజు";
            out.add("🎂 " + what + " " + (d == 0 ? "ఈ రోజే" : d == 1 ? "రేపు" : d + " రోజుల్లో"));
            break;
        }
        JSONObject note = null;
        for (JSONObject n : notes) {
            long t = n.optLong("t");
            if (t <= 0 || now - t > 90L * 86400000L || !names(n.optString("text"), who)) continue;
            if (note == null || t > note.optLong("t")) note = n;
        }
        if (note != null) {
            String t = note.optString("text").replaceFirst("^\\s*కాల్\\s*-\\s*[^:]{1,40}:\\s*", "").trim();
            long days = (now - note.optLong("t")) / 86400000L;
            out.add("📝 " + (days <= 0 ? "ఈ రోజు" : days == 1 ? "నిన్న" : days + " రోజుల క్రితం") + ": " + cut(t, 70));
        }
        JSONObject rem = null;
        for (JSONObject r : reminders) {
            long at = r.optLong("at");
            if (at <= 0 || !names(r.optString("text"), who)) continue;
            if (rem == null || at < rem.optLong("at")) rem = r;
        }
        if (rem != null) out.add("⏰ " + cut(rem.optString("text"), 60));
        while (out.size() > 4) out.remove(out.size() - 1);
        return out;
    }

    /** The same person: one name's words (3+ letters) all in the other's ("రవి" / "రవి కుమార్", "Ravi K" / "ravi"). */
    static boolean samePerson(String a, String b) {
        List<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        List<String> small = x.size() <= y.size() ? x : y, big = small == x ? y : x;
        return big.containsAll(small);
    }

    /** A text names this person: their full name, or their first word of 3+ letters as a whole word. */
    static boolean names(String text, String who) {
        String t = " " + norm(text) + " ", w = norm(who);
        if (w.isEmpty()) return false;
        if (t.contains(" " + w + " ") || t.contains(" " + w)) return true;
        List<String> ws = words(who);
        return !ws.isEmpty() && t.contains(" " + ws.get(0));
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (w.codePointCount(0, w.length()) >= 3) out.add(w);
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1).trim() + "…"; }
}
