package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * W32: the watch's "📝 నోట్" button: he says one line and it is kept where it belongs, at once and without the AI
 * (works without internet too): an expense ("ఛార్జింగ్ 120"), money lent, a thing lent, where he kept something, a last
 * date, the shopping list, a reminder, the diary; anything else is a note. Says what it did.
 */
final class QuickNote {
    private QuickNote() {}

    static String save(Context c, String said) {
        Context app = c.getApplicationContext();
        String t = said == null ? "" : said.trim();
        if (t.isEmpty()) return "ఏమీ వినిపించలేదు.";
        if (Offline.secret(t) || Offline.idNumber(t)) return "అకౌంట్, కార్డ్, ఆధార్ లాంటి నంబర్లు, పిన్, OTP, పాస్‌వర్డ్‌లు నేను రాసుకోను.";
        try {
            String low = t.toLowerCase(java.util.Locale.ROOT);
            // "… గుర్తు చేయి": a reminder
            if (low.matches("(?s).*(గుర్తు\\s*చేయ|గుర్తుచేయ|గుర్తు\\s*చెయ్|గుర్తుచెయ్|రిమైండ్|remind).*")) {
                LocalDateTime now = LocalDateTime.now();
                int dom = Offline.monthlyDay(t);
                if (dom == 0) return "నెలలో ఏ తేదీకి? ఉదాహరణకు \"ప్రతి నెల 5 న అద్దె కట్టాలని గుర్తు చేయి\".";
                LocalDateTime at = Offline.when(dom > 0 ? Offline.digits(t).replaceAll("(?<![\\d:.])\\d{1,2}\\s*(వ\\s*)?(తారీఖు|తారీకు|తేదీ|న|నే)(?=\\s|$)", " ") : t, now);
                if (dom > 0) at = Reminders.nextMonthly(now, dom, at);
                String what = dom >= 0 ? Offline.monthlyText(t) : Offline.reminderText(t);
                if (at == null) return "ఏ టైమ్‌కి గుర్తు చేయాలో చెప్పలేదు. మళ్లీ టైమ్‌తో చెప్పండి.";
                if (what.isEmpty()) return "ఏం గుర్తు చేయాలో వినిపించలేదు.";
                JSONObject r = Store.get(app).addReminder(what, at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                if (r == null) return "రిమైండర్ పెట్టలేకపోయాను.";
                if (dom > 0) r = Store.get(app).updateReminder(r.optString("id"), "repeat", "monthly");
                if (dom > 0 && r != null) r = Store.get(app).updateReminder(r.optString("id"), "dom", dom);
                if (r != null) Reminders.schedule(app, r);
                if (WatchHub.known(app)) WatchAlerts.pushInfo(app); // (the watch tile's next reminder; we are off the main thread)
                return "సరే, " + (dom > 0 ? "ప్రతి నెల " + dom + " న, మొదట " : "") + Offline.sayWhen(at, now) + " కి \"" + what + "\" గుర్తు చేస్తాను.";
            }
            String[] ex = Offline.expense(t);
            if (ex == null && Offline.category(t) != null && Offline.money(t) > 0 && Offline.debt(t) == null) // "ఛార్జింగ్ 120" said short on the watch
                ex = new String[]{Offline.fmt(Offline.money(t)), t.replaceAll("[\\d₹,.]+", " ").replaceAll("(రూపాయలు|రూపాయల|రూపాయి)", " ").replaceAll("\\s+", " ").trim(), Offline.category(t)};
            if (ex != null) {
                Money.add(app, Double.parseDouble(ex[0]), ex[1], "", ex[2], System.currentTimeMillis());
                return "₹" + ex[0] + " " + (ex[1].isEmpty() ? "" : ex[1] + " ") + "ఖర్చు రాశాను.";
            }
            String[] d = Offline.debt(t);
            if (d != null) {
                double amt = Double.parseDouble(d[3]);
                if (d[0].equals("add")) {
                    Debts.add(app, d[1], d[2], amt, LocalDate.now().toString(), "", 0, 0, 0, "");
                    return d[1].equals("lent") ? d[2] + " కి ₹" + d[3] + " ఇచ్చినట్టు రాశాను." : d[2] + " దగ్గర ₹" + d[3] + " తీసుకున్నట్టు రాశాను.";
                }
                JSONObject o = Debts.pay(app, d[2], d[1], amt);
                return o == null ? d[2] + " పేరుతో అప్పు ఏమీ రాసి లేదు." : "రాశాను. ఇంకా ₹" + Math.round(Debts.left(o)) + " మిగిలింది.";
            }
            String[] lend = Offline.lend(t);
            if (lend != null) {
                Lent.add(app, lend[0], lend[1]);
                return lend[1] + " కి " + lend[0] + " ఇచ్చినట్టు రాశాను. తిరిగి వచ్చాక \"" + lend[1] + " " + lend[0] + " తిరిగి ఇచ్చాడు\" అనండి.";
            }
            String back = Offline.lentBack(t);
            if (back != null) {
                JSONObject o = Lent.back(app, back);
                if (o != null) return o.optString("who") + " " + o.optString("thing") + " తిరిగి ఇచ్చినట్టు రాశాను.";
            }
            String[] kept = Offline.kept(t);
            if (kept != null) {
                Everyday.put(app, kept[0], kept[1]);
                return kept[0] + ": " + kept[1] + " అని గుర్తుపెట్టుకున్నాను.";
            }
            String[] runs = Offline.runsOut(t, LocalDate.now());
            if (runs != null) {
                Expiry.addDate(app, runs[0], runs[1], 0, 0);
                return runs[0] + " గడువు " + Sums.say(LocalDate.parse(runs[1])) + " అని రాశాను; ముందే గుర్తు చేస్తాను.";
            }
            if (low.matches("(?s).*(లిస్ట్|లిస్టు|కొనాలి|తేవాలి).*") && !low.contains("నోట్")) {
                String items = t.replaceAll("(షాపింగ్|లిస్ట్‌లో|లిస్ట్లో|లిస్ట్ లో|లిస్టులో|లిస్ట్|లిస్టు|పెట్టు|చేర్చు|రాయి|ఆడ్|యాడ్|add|కలుపు|కొనాలి|తేవాలి)", " ").replaceAll("\\s+", " ").trim();
                if (!items.isEmpty()) {
                    JSONArray added = Shopping.add(app, items);
                    return added.length() == 0 ? "అవి ఇప్పటికే షాపింగ్ లిస్ట్‌లో ఉన్నాయి." : "షాపింగ్ లిస్ట్‌లో పెట్టాను: " + join(added) + ".";
                }
            }
            if (low.contains("డైరీ")) {
                String words = t.replaceAll("(డైరీలో|డైరీ లో|డైరీ|రాసుకోండి|రాసుకో|రాయండి|రాయి|పెట్టు)", " ").replaceAll("\\s+", " ").trim();
                if (!words.isEmpty()) { Diary.add(app, words, LocalDate.now().toString()); return "డైరీలో రాశాను."; }
            }
            String note = t.replaceAll("(నోట్స్‌లో|నోట్స్లో|నోట్స్ లో|నోట్‌లో|నోట్లో|నోట్ లో|నోట్స్|నోట్|రాసుకోండి|రాసుకో|రాయండి|రాయి)", " ")
                    .replaceAll("(^\\s*అని\\s+|\\s+అని\\s*$)", " ").replaceAll("\\s+", " ").trim();
            if (note.isEmpty()) return "ఏం రాయాలో వినిపించలేదు.";
            Notes.add(app, "notes", new JSONObject().put("id", Notes.id("n")).put("text", note).put("t", System.currentTimeMillis()), 2000);
            return "నోట్ రాశాను: \"" + note + "\".";
        } catch (Exception e) {
            return "రాయలేకపోయాను: " + e.getMessage();
        }
    }

    private static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) b.append(i == 0 ? "" : ", ").append(a.optString(i));
        return b.toString();
    }
}
