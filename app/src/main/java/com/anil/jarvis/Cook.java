package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cooking by voice: a recipe in simple Telugu, one step at a time ("తర్వాత", "మళ్లీ చెప్పు", "ముందు స్టెప్"),
 * with the step's timer started by itself ("10 నిమిషాలు ఉడికించండి" -> a 10-minute timer that calls him back).
 */
final class Cook {
    private Cook() {}

    static final String ACTION_TIMER = "com.anil.jarvis.COOK_TIMER";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_cook", Context.MODE_PRIVATE); }

    static JSONObject recipe(Context c) {
        try {
            String r = sp(c).getString("recipe", "");
            if (r.isEmpty() || System.currentTimeMillis() - sp(c).getLong("at", 0) > 6 * 3600000L) return null;
            return new JSONObject(r);
        } catch (Exception e) { return null; }
    }

    /** A recipe for the dish (for that many people), from the AI he chose. */
    static JSONObject start(Context c, String dish, int people, String notes) throws Exception {
        Prefs p = new Prefs(c);
        if (p.apiKey().isEmpty()) return new JSONObject().put("ok", false).put("error", "no_key").put("message", "No AI key in Settings.");
        String system = "You are a home cook from Telangana / Andhra. Reply with ONLY compact JSON, no words around it, no code fences.";
        String prompt = "Recipe: " + dish + (people > 0 ? " for " + people + " people" : "") + (notes == null || notes.isEmpty() ? "" : ". Note: " + notes) + ".\n"
                + "JSON: {\"title\":\"dish name in Telugu\",\"serves\":number,\"minutes\":total number,"
                + "\"ingredients\":[\"in Telugu with amount, e.g. బియ్యం - 2 గ్లాసులు\"],"
                + "\"steps\":[{\"text\":\"one short step in simple spoken Telugu (one action)\",\"seconds\":number of seconds to wait in this step, 0 if none}]}. "
                + "8 to 16 steps; household measures (గ్లాసు, స్పూన్, చిటికెడు); safe cooking; say the flame (సన్నని / మీడియం / పెద్ద మంట) where it matters.";
        String ans = Brain.oneShot(p, system, prompt, null, false, 4000);
        String j = ans == null ? "" : ans.trim();
        int a = j.indexOf('{'), b = j.lastIndexOf('}');
        if (a < 0 || b <= a) return new JSONObject().put("ok", false).put("error", "no_recipe").put("message", "The AI did not give a recipe. Try again.");
        JSONObject r = new JSONObject(j.substring(a, b + 1));
        JSONArray steps = r.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return new JSONObject().put("ok", false).put("error", "no_recipe").put("message", "The AI did not give the steps. Try again.");
        sp(c).edit().putString("recipe", r.toString()).putInt("step", -1).putLong("at", System.currentTimeMillis()).apply();
        asked = true;
        return new JSONObject().put("ok", true).put("title", r.optString("title", dish)).put("serves", r.optInt("serves"))
                .put("minutes", r.optInt("minutes")).put("ingredients", r.optJSONArray("ingredients")).put("steps", steps.length())
                .put("next", "Say the title, how long it takes and the ingredients briefly (it is a list he needs, so read it), "
                        + "then ask 'అన్నీ రెడీనా? మొదలుపెడదామా?'. When he says yes / తర్వాత -> cook next.");
    }

    /** Next (+1), previous (-1), again (0) or step n (goto). */
    static JSONObject step(Context c, int delta, int go) throws Exception {
        JSONObject r = recipe(c);
        if (r == null) return new JSONObject().put("ok", false).put("error", "no_recipe").put("message", "No recipe going on. Which dish? (cook start)");
        JSONArray steps = r.optJSONArray("steps");
        int at = sp(c).getInt("step", -1);
        if (go > steps.length()) return new JSONObject().put("ok", false).put("error", "no_step").put("message", "This recipe has only " + steps.length() + " steps.");
        int n = go > 0 ? go - 1 : delta == 0 ? Math.max(0, at) : at + delta;
        if (n >= steps.length()) {
            stopTimers(c);
            sp(c).edit().remove("recipe").apply();
            return new JSONObject().put("ok", true).put("done", true).put("title", r.optString("title"))
                    .put("next", "All steps are done: say it is ready, enjoy (భోజనం ఆస్వాదించండి) in one line.");
        }
        n = Math.max(0, n);
        sp(c).edit().putInt("step", n).putLong("at", System.currentTimeMillis()).apply();
        JSONObject s = steps.optJSONObject(n);
        int sec = s == null ? 0 : Math.max(0, s.optInt("seconds", 0));
        JSONObject o = new JSONObject().put("ok", true).put("step", n + 1).put("of", steps.length()).put("text", s == null ? "" : s.optString("text"));
        if (sec >= 30 && delta > 0 && n != at) { // a waiting step reached going forward: its timer starts now (not on repeat / back)
            timer(c, sec, r.optString("title") + " · స్టెప్ " + (n + 1));
            o.put("timer_started_minutes", Math.round(sec / 60.0 * 10) / 10.0);
        }
        asked = true;
        return o.put("next", "Say this step in Telugu exactly as given (and that the timer is on if one started). Then stop and wait; "
                + "'తర్వాత' -> cook next, 'మళ్లీ' -> cook repeat.");
    }

    static JSONObject ingredients(Context c) throws Exception {
        JSONObject r = recipe(c);
        if (r == null) return new JSONObject().put("ok", false).put("error", "no_recipe").put("message", "No recipe going on.");
        return new JSONObject().put("ok", true).put("ingredients", r.optJSONArray("ingredients"));
    }

    static void stop(Context c) {
        sp(c).edit().remove("recipe").apply();
        stopTimers(c);
        asked = false;
    }

    private static void stopTimers(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int code = 170; code < 190; code++) {
            PendingIntent pi = PendingIntent.getBroadcast(c, code, new Intent(c, AlarmReceiver.class).setAction(ACTION_TIMER),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
            if (pi != null && am != null) { am.cancel(pi); pi.cancel(); }
        }
    }

    // ---------------------------------------------------------------- "తర్వాత" without "Jarvis"

    private static volatile boolean asked;

    /** After each step, Jarvis listens once for "తర్వాత" (his hands are busy). */
    static boolean awaiting() {
        boolean a = asked;
        asked = false;
        return a;
    }

    // ---------------------------------------------------------------- timers

    static void timer(Context c, int seconds, String label) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        int next = sp(c).getInt("timer_code", 0); // up to 20 timers at once, each its own
        sp(c).edit().putInt("timer_code", (next + 1) % 20).apply();
        int code = 170 + next;
        PendingIntent pi = PendingIntent.getBroadcast(c, code, new Intent(c, AlarmReceiver.class).setAction(ACTION_TIMER).putExtra("label", label),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long when = System.currentTimeMillis() + seconds * 1000L;
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** The timer is up: a ringing notification and Jarvis says it. */
    static void timerUp(Context c, String label) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_cook_timer", "వంట టైమర్", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
            nm.createNotificationChannel(ch);
            nm.notify(180, new Notification.Builder(c, "jarvis_cook_timer").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("⏰ టైమర్ అయిపోయింది").setContentText(label == null ? "" : label).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM).build());
        } catch (Exception ignored) {}
        Announcer.say(c, new Prefs(c).name() + ", టైమర్ అయిపోయింది. " + (label == null ? "" : label) + ". తర్వాతి స్టెప్ కావాలంటే Jarvis, తర్వాత అనండి.");
    }
}
