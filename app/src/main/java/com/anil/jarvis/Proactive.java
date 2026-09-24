package com.anil.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jarvis speaking up on his own, about every 15 minutes: meetings coming up, rain this morning,
 * someone close not called for a while, a usual habit, water breaks, price alerts, and the
 * weekly notes summary. Quiet at night, during calls and in Do Not Disturb.
 */
public class Proactive extends BroadcastReceiver {
    static final String ACTION = "com.anil.jarvis.PROACTIVE";

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, Proactive.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    @Override public void onReceive(Context c, Intent i) {
        PendingResult pr = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try { tick(app); } catch (Throwable ignored) {} finally { pr.finish(); }
        }, "jarvis-proactive").start();
    }

    private static SharedPreferences state(Context c) {
        return c.getSharedPreferences("jarvis_proactive", Context.MODE_PRIVATE);
    }

    private static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String today() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.YEAR) + "-" + k.get(Calendar.DAY_OF_YEAR);
    }

    /** Once a day per key. */
    private static boolean onceToday(Context c, String key) {
        SharedPreferences s = state(c);
        String t = today();
        if (t.equals(s.getString(key, ""))) return false;
        s.edit().putString(key, t).apply();
        return true;
    }

    static void tick(Context c) {
        Prefs p = new Prefs(c);
        Health.recordStepBaseline(c);
        JarvisWidget.refresh(c);
        if (!p.proactive()) return;
        boolean quiet = p.night() || CallControl.busyWithCall() || dnd(c);
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);

        if (!quiet) meetingSoon(c, p);
        if (!quiet && hour >= 7 && hour < 10) rainToday(c, p);
        if (!quiet && hour >= 19 && hour < 21) callNudge(c, p);
        if (!quiet) habit(c, p, now);
        if (!quiet) Health.waterTick(c, p);
        priceAlerts(c, p, quiet);
        if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && hour >= 19 && hour < 22) weeklyNotes(c);
    }

    private static boolean dnd(Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    /** Speaks up. With a question, opens the panel so Anil can answer by voice. */
    static void say(Context c, String text, String question, String context) {
        if (question != null && android.provider.Settings.canDrawOverlays(c) && !MainActivity.inConversation) {
            try {
                c.startActivity(new Intent(c, SheetActivity.class)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE, text)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, question)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, context == null ? "" : context)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            } catch (Exception ignored) {}
        }
        Reminders.notify(c, "Jarvis", text + (question == null ? "" : " " + question), text.hashCode());
        Announcer.say(c, text + (question == null ? "" : " " + question));
    }

    // ---------------------------------------------------------------- meetings

    private static void meetingSoon(Context c, Prefs p) {
        if (!has(c, Manifest.permission.READ_CALENDAR)) return;
        long now = System.currentTimeMillis();
        long from = now + 15 * 60000L, to = now + 40 * 60000L;
        android.net.Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, from);
        android.content.ContentUris.appendId(b, to);
        try (Cursor cur = c.getContentResolver().query(b.build(),
                new String[]{CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY},
                null, null, CalendarContract.Instances.BEGIN)) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getInt(4) == 1) continue;
                long begin = cur.getLong(2);
                if (begin < from) continue;
                String key = "ev_" + cur.getLong(0) + "_" + begin;
                if (state(c).getBoolean(key, false)) continue;
                state(c).edit().putBoolean(key, true).apply();
                int mins = Math.round((begin - now) / 60000f);
                String where = cur.getString(3);
                say(c, p.name() + ", " + mins + " నిమిషాల్లో \"" + cur.getString(1) + "\""
                        + (where == null || where.isEmpty() ? "." : ", " + where + " లో."), null, null);
                return;
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- rain

    private static void rainToday(Context c, Prefs p) {
        if (!onceToday(c, "rain")) return;
        try {
            JSONObject w = new JSONObject(Tools.weatherJson(c, ""));
            JSONArray days = w.optJSONArray("forecast");
            if (days == null || days.length() == 0) return;
            int chance = days.getJSONObject(0).optInt("rain_chance_pct", 0);
            if (chance >= 50) {
                say(c, p.name() + ", ఈరోజు వర్షం పడే అవకాశం " + chance + " శాతం ఉంది. బయటికి వెళ్తే గొడుగు తీసుకెళ్లండి.", null, null);
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- people

    private static String last10(String n) {
        String d = n == null ? "" : n.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** A starred (favourite) contact not called for 7+ days: offer to call. */
    private static void callNudge(Context c, Prefs p) {
        if (!has(c, Manifest.permission.READ_CONTACTS) || !has(c, Manifest.permission.READ_CALL_LOG)) return;
        if (!onceToday(c, "call_nudge")) return;
        Map<String, String> starred = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.STARRED + " = 1", null, null)) {
            while (cur != null && cur.moveToNext()) starred.put(last10(cur.getString(1)), cur.getString(0));
        } catch (Exception ignored) {}
        if (starred.isEmpty()) return;
        Map<String, Long> lastCall = new HashMap<>();
        long oldest = Long.MAX_VALUE;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION}, null, null, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                long d = cur.getLong(1);
                oldest = Math.min(oldest, d);
                if (cur.getLong(2) <= 0) continue; // unanswered calls don't count
                String k = last10(cur.getString(0));
                if (!lastCall.containsKey(k)) lastCall.put(k, d);
            }
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        String who = null;
        long longest = 0;
        for (Map.Entry<String, String> e : starred.entrySet()) {
            Long d = lastCall.get(e.getKey());
            if (d == null) continue; // not in the log at all: no reliable date
            long gap = now - d;
            if (gap > 7 * 86400000L && gap > longest) { longest = gap; who = e.getValue(); }
        }
        if (who == null) return;
        long days = longest / 86400000L;
        say(c, p.name() + ", " + who + " తో " + days + " రోజులుగా మాట్లాడలేదు.", "కాల్ చేయమంటారా?",
                " [suggestion: call " + who + " with call_contact if he says yes]");
    }

    // ---------------------------------------------------------------- habits

    private static void habit(Context c, Prefs p, Calendar now) {
        JSONObject h = Habits.suggestion(c, now);
        if (h == null) return;
        String key = "habit_" + h.optString("key");
        if (!onceToday(c, key)) return;
        say(c, p.name() + ", " + h.optString("text"), h.optString("question"), " [suggestion: " + h.optString("action") + " if he says yes]");
    }

    // ---------------------------------------------------------------- prices

    private static void priceAlerts(Context c, Prefs p, boolean quiet) {
        List<JSONObject> alerts = Notes.list(c, "price_alerts");
        if (alerts.isEmpty() || p.apiKey().isEmpty()) return;
        long last = state(c).getLong("price_check", 0);
        if (System.currentTimeMillis() - last < 3 * 3600000L) return;
        state(c).edit().putLong("price_check", System.currentTimeMillis()).apply();
        int checked = 0;
        for (JSONObject a : alerts) {
            if (checked++ >= 3) break;
            try {
                String item = a.optString("item");
                String ans = Brain.oneShot(p, "You look up live prices on the web. Reply with only the number, no words, no currency sign.",
                        "Current price in India, in INR, of: " + item + ". Only the number.", null, true);
                double price = Double.parseDouble(ans.replaceAll("[^0-9.]", ""));
                double target = a.optDouble("target");
                boolean below = "below".equals(a.optString("when"));
                if (below ? price <= target : price >= target) {
                    Notes.remove(c, "price_alerts", "id", a.optString("id"));
                    String t = p.name() + ", " + item + " ధర ఇప్పుడు " + Math.round(price) + " రూపాయలు. మీరు చెప్పిన "
                            + Math.round(target) + (below ? " కి తగ్గింది." : " దాటింది.");
                    if (quiet) Reminders.notify(c, "ధర హెచ్చరిక", t, t.hashCode());
                    else say(c, t, null, null);
                }
            } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- weekly notes

    private static void weeklyNotes(Context c) {
        Calendar k = Calendar.getInstance();
        String week = k.get(Calendar.YEAR) + "-" + k.get(Calendar.WEEK_OF_YEAR);
        if (week.equals(state(c).getString("notes_week", ""))) return;
        long since = System.currentTimeMillis() - 7 * 86400000L;
        int n = 0;
        for (JSONObject o : Notes.list(c, "notes")) if (o.optLong("t") >= since) n++;
        if (n == 0) return;
        state(c).edit().putString("notes_week", week).apply();
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new android.app.NotificationChannel("jarvis_reminders", "Jarvis రిమైండర్లు",
                android.app.NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, 78, new Intent(c, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_ASK, "ఈ వారం నా నోట్స్ సారాంశం చెప్పు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(78, new android.app.Notification.Builder(c, "jarvis_reminders")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("ఈ వారం " + n + " నోట్స్")
                .setContentText("సారాంశం వినడానికి నొక్కండి")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
