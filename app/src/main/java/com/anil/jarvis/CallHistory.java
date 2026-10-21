package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📵 His calls from the phone's own call log (read only, never changed): the missed calls he hasn't seen yet (who, how
 * many times, when), WhatsApp's missed calls still waiting in the notifications, today's calls, the last calls with
 * someone, and the number of the call just cut (for the message). Everything stays on the phone.
 */
final class CallHistory {
    private CallHistory() {}

    static boolean can(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED; }

    /** One person's missed calls. */
    static final class Group {
        String who = "", number = "", app = "";
        int times;
        long last;
    }

    private static String clock(long ms) { return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(ms)); }

    private static String when(long ms) {
        long day = dayStart();
        if (ms >= day) return clock(ms);
        if (ms >= day - 86400_000L) return "నిన్న " + clock(ms);
        return new SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(new Date(ms));
    }

    static long dayStart() {
        java.util.Calendar k = java.util.Calendar.getInstance();
        k.set(java.util.Calendar.HOUR_OF_DAY, 0);
        k.set(java.util.Calendar.MINUTE, 0);
        k.set(java.util.Calendar.SECOND, 0);
        k.set(java.util.Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /**
     * Missed phone calls he hasn't looked at yet (the phone's own "new" mark; it goes when he opens the call list),
     * newest first and one line per person; only those after `since` (and at most 3 days back).
     */
    static List<Group> unseen(Context c, long since) {
        List<Group> out = new ArrayList<>();
        if (!can(c)) return out;
        long from = Math.max(since, System.currentTimeMillis() - 3 * 86400_000L);
        Map<String, Group> by = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.NEW + "=1 AND " + CallLog.Calls.DATE + ">?",
                new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(from)}, CallLog.Calls.DATE + " DESC")) {
            int n = 0;
            while (cur != null && cur.moveToNext() && n++ < 200) add(by, cur.getString(0), cur.getString(1), cur.getLong(2), "");
        } catch (Exception ignored) {}
        out.addAll(by.values());
        return out;
    }

    private static void add(Map<String, Group> by, String name, String number, long at, String app) {
        String who = name == null || name.trim().isEmpty() ? (number == null || number.trim().isEmpty() ? "తెలియని నంబర్" : number.trim()) : name.trim();
        String k = app + "|" + (name == null || name.trim().isEmpty() ? CallWords.key(number) : CallWords.norm(name));
        Group g = by.get(k);
        if (g == null) {
            g = new Group();
            g.who = who;
            g.number = number == null ? "" : number;
            g.app = app;
            by.put(k, g);
        }
        g.times++;
        g.last = Math.max(g.last, at);
    }

    /** The call apps whose missed calls count (never a bank SMS saying "give a missed call", nor another caller-ID app's copy). */
    private static final String[] CALL_APPS = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.google.android.apps.tachyon", "com.facebook.orca", "com.instagram.android"};
    private static final Pattern APP_MISSED = Pattern.compile("(?i)^\\(?(\\d{1,2})?\\s*missed (voice |video |audio )?calls?\\)?$|^(\\d{1,2}\\s*)?మిస్డ్ (వాయిస్ |వీడియో )?కాల్(స్|లు)?$");
    private static final Pattern COUNT = Pattern.compile("^\\(?(\\d{1,2})\\s*(?:missed|మిస్డ్)", Pattern.CASE_INSENSITIVE);

    private static boolean callApp(String pkg) {
        for (String a : CALL_APPS) if (a.equals(pkg)) return true;
        return false;
    }

    /** Missed calls of WhatsApp and other call apps, still in his notifications (rang after `since`). */
    static List<Group> appMissed(Context c, long since) {
        List<Group> out = new ArrayList<>();
        NotifyListener l = NotifyListener.self;
        if (l == null) return out;
        Map<String, Group> by = new LinkedHashMap<>();
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all == null) return out;
            for (StatusBarNotification sbn : all) {
                if (!callApp(sbn.getPackageName())) continue;
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                if (Notification.CATEGORY_MESSAGE.equals(n.category) || n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) != null) continue; // (a chat, not a call)
                long at = n.when > 0 ? n.when : sbn.getPostTime(); // (when it rang: a re-posted notification keeps its own time)
                if (at <= since) continue;
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE), x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                String title = t == null ? "" : t.toString().trim(), text = x == null ? "" : x.toString().trim();
                boolean byText = APP_MISSED.matcher(text).matches(), byTitle = APP_MISSED.matcher(title).matches();
                if (!"missed_call".equals(n.category) && !byText && !byTitle) continue;
                String who = byTitle ? text : title;
                if (who.isEmpty() || APP_MISSED.matcher(who).matches()) continue;
                int times = 1;
                Matcher m = COUNT.matcher(byTitle ? title : text);
                if (m.find()) { try { times = Math.max(1, Integer.parseInt(m.group(1))); } catch (Exception ignored) {} }
                String app = label(c, sbn.getPackageName());
                for (int i = 0; i < times; i++) add(by, who, "", at, app);
            }
        } catch (Exception ignored) {}
        out.addAll(by.values());
        return out;
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) { return pkg; }
    }

    /** "రవి 3 సార్లు (చివరిది 10:15 AM), అమ్మ (9:40 AM), WhatsApp లో సురేష్" for his ears. */
    static String sayList(List<Group> gs) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Group g : gs) {
            if (n++ >= 5) { b.append(", ఇంకా ").append(gs.size() - 5).append(" మంది"); break; }
            if (b.length() > 0) b.append(", ");
            if (!g.app.isEmpty()) b.append(g.app).append(" లో ");
            b.append(CallWords.isNumber(g.who) ? "తెలియని నంబర్ (చివరి అంకెలు " + CallWords.lastFour(g.who) + ")" : g.who);
            if (g.times > 1) b.append(" ").append(g.times).append(" సార్లు");
            b.append(g.times > 1 ? " (చివరిది " : " (").append(when(g.last)).append(")");
        }
        return b.toString();
    }

    static int total(List<Group> gs) { int t = 0; for (Group g : gs) t += g.times; return t; }

    private static JSONArray json(List<Group> gs) throws Exception {
        JSONArray a = new JSONArray();
        for (Group g : gs) {
            a.put(new JSONObject().put("who", g.who).put("times", g.times).put("last", when(g.last)).put("app", g.app.isEmpty() ? "phone" : g.app)
                    .put("unknown_number", CallWords.isNumber(g.who)));
        }
        return a;
    }

    /** For the brain: "మిస్డ్ కాల్స్ ఏమున్నాయి?" */
    static JSONObject missedJson(Context c) throws Exception {
        if (!can(c)) return new JSONObject().put("ok", false).put("error", "no_permission").put("next", "Say: call history needs the call log permission (⚙️ → 📞 కాల్స్ → 📜 కాల్ లాగ్).");
        List<Group> phone = unseen(c, 0), apps = appMissed(c, 0);
        JSONObject o = new JSONObject().put("ok", true).put("unseen_phone", json(phone)).put("unseen_phone_total", total(phone))
                .put("whatsapp_and_apps", json(apps)).put("apps_total", total(apps));
        if (phone.isEmpty() && apps.isEmpty()) {
            List<Group> today = missedSince(c, dayStart());
            o.put("today_missed_already_seen", json(today));
            o.put("next", today.isEmpty() ? "No missed calls. Say so in one line."
                    : "No missed calls he hasn't seen. Say that, and in one line who called today without being answered (he has seen them).");
        } else {
            o.put("next", "Tell him in short Telugu: how many he hasn't seen, then each person with how many times and the last time (unknown numbers by their last 4 digits). "
                    + "Offer to call one back only if he asks (the call itself only after his yes).");
        }
        markTold(c, System.currentTimeMillis());
        return o;
    }

    /** All missed phone calls after `since` (seen or not), one line per person. */
    static List<Group> missedSince(Context c, long since) {
        List<Group> out = new ArrayList<>();
        if (!can(c)) return out;
        Map<String, Group> by = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?", new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(since)},
                CallLog.Calls.DATE + " DESC")) {
            int n = 0;
            while (cur != null && cur.moveToNext() && n++ < 200) add(by, cur.getString(0), cur.getString(1), cur.getLong(2), "");
        } catch (Exception ignored) {}
        out.addAll(by.values());
        return out;
    }

    private static String type(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return "incoming (answered)";
            case CallLog.Calls.OUTGOING_TYPE: return "outgoing (he called)";
            case CallLog.Calls.MISSED_TYPE: return "missed";
            case CallLog.Calls.REJECTED_TYPE: return "rejected (he cut it)";
            case CallLog.Calls.BLOCKED_TYPE: return "blocked";
            default: return "other";
        }
    }

    /** For the brain: "ఈ రోజు ఎవరెవరు కాల్ చేశారు?" */
    static JSONObject todayJson(Context c) throws Exception {
        if (!can(c)) return new JSONObject().put("ok", false).put("error", "no_permission");
        JSONArray a = new JSONArray();
        int in = 0, out = 0, missed = 0;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE},
                CallLog.Calls.DATE + ">?", new String[]{String.valueOf(dayStart())}, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                int t = cur.getInt(4);
                if (t == CallLog.Calls.INCOMING_TYPE) in++;
                else if (t == CallLog.Calls.OUTGOING_TYPE) out++;
                else if (t == CallLog.Calls.MISSED_TYPE) missed++;
                if (a.length() < 25) {
                    String name = cur.getString(0);
                    a.put(new JSONObject().put("who", name == null || name.isEmpty() ? cur.getString(1) : name).put("type", type(t))
                            .put("at", clock(cur.getLong(2))).put("minutes", Math.round(cur.getLong(3) / 60.0)));
                }
            }
        } catch (Exception ignored) {}
        return new JSONObject().put("ok", true).put("incoming", in).put("outgoing", out).put("missed", missed).put("calls", a)
                .put("next", "Say in short Telugu who called today (and whom he called if he asked), newest first; unknown numbers by their last 4 digits.");
    }

    /** For the brain: "రవితో చివరిసారి ఎప్పుడు మాట్లాడాను?" (name in the call log, or a number). */
    static JSONObject withJson(Context c, String who) throws Exception {
        if (!can(c)) return new JSONObject().put("ok", false).put("error", "no_permission");
        String w = who == null ? "" : who.trim();
        if (w.isEmpty()) return new JSONObject().put("ok", false).put("error", "who_missing").put("next", "Ask whom he means.");
        String key = CallWords.isNumber(w) ? CallWords.key(w) : "";
        String low = w.toLowerCase(Locale.ROOT);
        JSONArray a = new JSONArray();
        String lastTalk = "";
        int seen = 0;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE},
                CallLog.Calls.DATE + ">?", new String[]{String.valueOf(System.currentTimeMillis() - 90 * 86400_000L)}, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext() && seen++ < 3000 && a.length() < 8) {
                String name = cur.getString(0) == null ? "" : cur.getString(0);
                boolean hit = key.isEmpty() ? name.toLowerCase(Locale.ROOT).contains(low) : key.equals(CallWords.key(cur.getString(1)));
                if (!hit) continue;
                int t = cur.getInt(4);
                long dur = cur.getLong(3);
                a.put(new JSONObject().put("who", name.isEmpty() ? cur.getString(1) : name).put("type", type(t)).put("when", when(cur.getLong(2)))
                        .put("minutes", Math.round(dur / 60.0)));
                if (lastTalk.isEmpty() && dur > 0 && (t == CallLog.Calls.INCOMING_TYPE || t == CallLog.Calls.OUTGOING_TYPE))
                    lastTalk = when(cur.getLong(2)) + " (" + Math.max(1, Math.round(dur / 60.0)) + " ని)";
            }
        } catch (Exception ignored) {}
        return new JSONObject().put("ok", true).put("who", w).put("last_talked", lastTalk).put("calls", a)
                .put("next", a.length() == 0 ? "No calls with that name in the last 90 days (the call log has the names saved in contacts). Say so."
                        : "Say when he last talked with them (and how long), and the last few calls, in short Telugu.");
    }

    /**
     * The newest call someone made TO him (answered, missed, cut) within `withinMs`: {number, saved name}; null when none.
     * (Never his own outgoing call: "ఇది స్పామ్" marks only who called him.)
     */
    static String[] lastIncoming(Context c, long withinMs) {
        if (!can(c)) return null;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME},
                CallLog.Calls.DATE + ">? AND " + CallLog.Calls.TYPE + " IN (" + CallLog.Calls.INCOMING_TYPE + "," + CallLog.Calls.MISSED_TYPE + "," + CallLog.Calls.REJECTED_TYPE + ")",
                new String[]{String.valueOf(System.currentTimeMillis() - withinMs)}, CallLog.Calls.DATE + " DESC")) {
            if (cur != null && cur.moveToFirst()) return new String[]{cur.getString(0) == null ? "" : cur.getString(0), cur.getString(1) == null ? "" : cur.getString(1)};
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * The number of THIS call (it rang at `ringAt`), from the call log: only rows of that ring (from 5 s before it), a call TO
     * him, with exactly this saved name; "" when not (yet) there.
     */
    static String numberOfRing(Context c, long ringAt, String who) {
        if (!can(c) || ringAt <= 0) return "";
        String nk = CallWords.norm(who);
        if (nk.isEmpty()) return "";
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME},
                CallLog.Calls.DATE + ">=? AND " + CallLog.Calls.TYPE + " IN (" + CallLog.Calls.INCOMING_TYPE + "," + CallLog.Calls.MISSED_TYPE + "," + CallLog.Calls.REJECTED_TYPE + ")",
                new String[]{String.valueOf(ringAt - 5000)}, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                String num = cur.getString(0) == null ? "" : cur.getString(0).trim();
                if (num.isEmpty()) continue;
                String name = cur.getString(1);
                if (nk.equals(CallWords.norm(name)) || nk.equals(CallWords.norm(nameFor(c, num)))) return num;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** A saved contact's number by the exact name (a mobile first, his default one first); "" when two contacts share the name. */
    static String numberFor(Context c, String name) {
        if (name == null || name.trim().isEmpty() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
        String best = "";
        int bestScore = -1;
        long contact = -1;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=? COLLATE NOCASE", new String[]{name.trim()}, null)) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0);
                if (n == null || n.trim().isEmpty()) continue;
                long id = cur.getLong(3);
                if (contact >= 0 && id != contact) return ""; // (two people with this name: never a guess)
                contact = id;
                int score = (cur.getInt(2) != 0 ? 4 : 0) + (cur.getInt(1) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ? 2 : 0);
                if (score > bestScore) { bestScore = score; best = n.trim(); }
            }
        } catch (Exception ignored) {}
        return best;
    }

    /** The saved name of a number, "" when it isn't in his contacts. */
    static String nameFor(Context c, String number) {
        if (number == null || number.trim().isEmpty() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
        try (Cursor cur = c.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim())),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) { String n = cur.getString(0); return n == null ? "" : n.trim(); }
        } catch (Exception ignored) {}
        return "";
    }

    // ---------------------------------------------------------------- told once

    /** The missed calls up to this moment have been told (by voice or asked by him): not told again on unlock. */
    static void markTold(Context c, long at) {
        CallCare.priv(c).edit().putLong("missed_told_at", at).apply();
    }

    static long toldAt(Context c) { return CallCare.priv(c).getLong("missed_told_at", 0); }
}
