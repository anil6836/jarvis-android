package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Phase 5's watch ideas on the phone's side: W63 silent answers (Do Not Disturb, duty mode, a meeting now, or his
 * switch), W66 find the watch, W67 sleep owed after a 48-hour duty and a nap the watch wakes him from, W68 the smart
 * alarm (the watch wakes him a little early when he moves in light sleep), W70 water glasses, W72 the night diary with
 * his mood, W73 a focus timer, W79 his medical card on the watch (only if he turns it on), W82 the month's spending
 * ring (counted here from bank / UPI SMS: amounts only, never sent to any AI), and the extras for the watch's faces (W61).
 */
final class Day5 {
    private Day5() {}

    private static final long MIN = 60_000L, HOUR = 60 * MIN;
    static final String P_FIND = "/jarvis/find", P_SMART = "/jarvis/smart";
    static final String ACTION_NAP = "com.anil.jarvis.WATCH_NAP", ACTION_FOCUS_END = "com.anil.jarvis.FOCUS_END", ACTION_SMART = "com.anil.jarvis.SMART_WINDOW";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_day5", Context.MODE_PRIVATE); }

    private static boolean wset(Context c, String key, boolean def) { return WatchHub.sp(c).getBoolean(key, def); }

    // ================================================================ W63: silent answers

    static boolean silentOn(Context c) { return wset(c, "silent", false); }

    static boolean silentAuto(Context c) { return wset(c, "silent_auto", true); }

    /** Answers on the watch are text only now (with a buzz). */
    static boolean silentNow(Context c) {
        if (silentOn(c)) return true;
        if (!silentAuto(c)) return false;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return true;
        } catch (Exception ignored) {}
        return DutyMode.on(c) || meetingNow(c);
    }

    /** A calendar event now that is a meeting ("meeting", "మీటింగ్", "సమావేశం", "class", "క్లాస్"). */
    static boolean meetingNow(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        long now = System.currentTimeMillis();
        android.net.Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, now);
        android.content.ContentUris.appendId(b, now + 1);
        try (Cursor cur = c.getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getInt(1) == 1) continue;
                String t = cur.getString(0) == null ? "" : cur.getString(0).toLowerCase(Locale.ROOT);
                if (t.matches("(?s).*(meeting|మీటింగ్|సమావేశం|class|క్లాస్|interview|ఇంటర్వ్యూ|call).*")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ================================================================ W66: find the watch

    static String findWatch(Context c) {
        if (!WatchHub.known(c)) return "వాచ్ యాప్ ఇంకా ఈ ఫోన్‌తో కలవలేదు.";
        try { WatchHub.send(c, P_FIND, new JSONObject().put("ring", true)); } catch (Exception ignored) {}
        return WatchHub.watchHere(c) ? "వాచ్ మోగిస్తున్నాను: వైబ్రేట్ అవుతూ, శబ్దం చేస్తూ, స్క్రీన్ మెరుస్తుంది. దొరికాక స్క్రీన్ నొక్కండి."
                : "వాచ్ ఇప్పుడు ఫోన్‌కి దగ్గరగా లేనట్టుంది; దగ్గరకి వస్తే మోగుతుంది. (Galaxy Wearable యాప్‌లో \"Find my watch\" కూడా ఉంది.)";
    }

    // ================================================================ W67: sleep owed, and a nap on the wrist

    /** {hours slept in the last 48, hours owed (16 needed)}. */
    static double[] sleepDebt(Context c) {
        long now = System.currentTimeMillis();
        double slept = Sleep.sleptSince(c, now - 48 * HOUR) / 60.0;
        return new double[]{slept, Math.max(0, 16 - slept)};
    }

    static String sleepDebtText(Context c) {
        double[] d = sleepDebt(c);
        String slept = String.format(Locale.ENGLISH, "%.1f", d[0]).replace(".0", "");
        if (d[1] < 2) return "గత 2 రోజుల్లో సుమారు " + slept + " గంటలు నిద్రపోయారు: సరిపోయింది.";
        int nap = d[1] >= 6 ? 90 : d[1] >= 3 ? 45 : 20;
        return "గత 2 రోజుల్లో సుమారు " + slept + " గంటలే నిద్ర: " + Math.round(d[1]) + " గంటలు తక్కువ. వీలైతే ఇప్పుడు " + nap
                + " నిమిషాల కునుకు తీయండి" + (nap == 90 ? " (ఒక పూర్తి నిద్ర చక్రం)" : "") + ". \"" + nap + " నిమిషాల కునుకు\" అంటే వాచ్ నిశ్శబ్దంగా వైబ్రేషన్‌తో లేపుతుంది. (ఫోన్ / వాచ్ నిద్ర లెక్క అంచనా)";
    }

    /** A nap: the watch wakes him with a vibration only (else the phone's own nap alarm). */
    static String nap(Context c, int minutes) {
        int m = Math.max(10, Math.min(180, minutes));
        if (WatchHub.timerOnWatch(c)) {
            WatchHub.timer(c, m * 60, "😴 కునుకు అయిపోయింది", null, null);
            return "సరే, " + m + " నిమిషాలు పడుకోండి. వాచ్ నిశ్శబ్దంగా వైబ్రేషన్‌తో లేపుతుంది.";
        }
        try { SongAlarm.nap(c, m); return "సరే, " + m + " నిమిషాల తర్వాత ఫోన్ అలారం మోగుతుంది (వాచ్ దగ్గర లేదు)."; }
        catch (Exception e) { return "కునుకు టైమర్ పెట్టలేకపోయాను."; }
    }

    /** After a duty ends (30 min to 3 h later), if he owes 4 hours or more: one card with the nap on his watch. */
    static void sleepTick(Context c) {
        long m = Duty.minutesSinceDuty(c);
        if (m < 30 || m > 180) return;
        String key = "debt_told_" + (System.currentTimeMillis() - m * MIN) / HOUR;
        if (sp(c).getBoolean(key, false)) return;
        double[] d = sleepDebt(c);
        if (d[1] < 4) return;
        sp(c).edit().putBoolean(key, true).apply();
        int nap = d[1] >= 6 ? 90 : 45;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_rest", "విశ్రాంతి", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent p = PendingIntent.getBroadcast(c, 291, new Intent(c, AlarmReceiver.class).setAction(ACTION_NAP).putExtra("min", nap), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(291, new Notification.Builder(c, "jarvis_rest").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("😴 నిద్ర " + Math.round(d[1]) + " గంటలు తక్కువ").setContentText(sleepDebtText(c))
                    .setStyle(new Notification.BigTextStyle().bigText(sleepDebtText(c))).setAutoCancel(true).setTimeoutAfter(3 * HOUR)
                    .addAction(new Notification.Action.Builder(null, "😴 " + nap + " ని కునుకు", p).build()).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ W68: the smart alarm

    static boolean smartOn(Context c) { return wset(c, "smart_alarm", true); }

    private static PendingIntent smartPi(Context c, String id, long at) {
        return PendingIntent.getBroadcast(c, ("smart" + id).hashCode(), new Intent(c, AlarmReceiver.class).setAction(ACTION_SMART)
                .putExtra("id", id).putExtra("at", at), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** SongAlarm set an alarm at `at`: 25 minutes before, the watch starts feeling for light sleep (his choice, on by default). */
    static void smartArm(Context c, String id, long at, boolean nap) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(smartPi(c, id, at));
        if (nap || !smartOn(c) || !WatchHub.known(c) || at - System.currentTimeMillis() < 30 * MIN) return;
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 25 * MIN, smartPi(c, id, at)); } catch (Exception ignored) {}
    }

    static void smartCancel(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(smartPi(c, id, 0));
    }

    /** The window began: if the alarm will ring on his wrist, the watch watches for movement until then. */
    static void smartWindow(Context c, String id, long at) {
        if (!smartOn(c) || !WatchAlerts.alarmOnWatch(c) || !WatchHub.watchHere(c)) return;
        try { WatchHub.send(c, P_SMART, new JSONObject().put("id", id).put("at", at)); } catch (Exception ignored) {}
    }

    /** The watch felt him stirring in the window: the alarm rings on his wrist now (its 3-minute phone fallback too). */
    static void smartWake(Context c, String id) {
        sp(c).edit().putLong("smart_" + id, System.currentTimeMillis()).apply();
        WatchAlerts.alarm(c, id, 0, "⏰ శుభోదయం! (తేలిక నిద్రలో లేపాను)");
    }

    /** The alarm's own time came: true when the watch already woke him for it (then it isn't rung again). */
    static boolean smartRang(Context c, String id) {
        long t = sp(c).getLong("smart_" + id, 0);
        return t > 0 && System.currentTimeMillis() - t < 40 * MIN;
    }

    // ================================================================ W70: water glasses

    static int waterGoal(Context c) {
        float max = Travel.sp(c).getFloat("max_t_" + LocalDate.now(), 0);
        return 8 + (max >= 38 ? 3 : max >= 35 ? 2 : 0);
    }

    static int water(Context c) { return sp(c).getInt("water_" + LocalDate.now(), 0); }

    static String waterAdd(Context c, int glasses) {
        int n = Math.max(0, water(c) + glasses);
        sp(c).edit().putInt("water_" + LocalDate.now(), n).apply();
        int g = waterGoal(c);
        return "💧 ఈరోజు " + n + " / " + g + " గ్లాసులు" + (n >= g ? ": లక్ష్యం అయిపోయింది 👍" : g > 8 ? " (ఎండ ఎక్కువ కాబట్టి లక్ష్యం " + g + ")" : "") + ".";
    }

    static JSONObject waterPanel(Context c) throws Exception {
        return new JSONObject().put("kind", "water").put("n", water(c)).put("goal", waterGoal(c));
    }

    // ================================================================ W72: the night diary with his mood

    static final String[] MOODS = {"😀", "🙂", "😐", "😟", "😣"};

    static void mood(Context c, String m) {
        sp(c).edit().putString("mood_" + LocalDate.now(), m).apply();
        try { Notes.add(c, Situation.MOODS, new JSONObject().put("t", System.currentTimeMillis()).put("feeling", m).put("why", "రాత్రి డైరీ"), 60); } catch (Exception ignored) {}
    }

    /** His words for the diary (from the watch): with today's mood and stress. */
    static String diary(Context c, String text) {
        String m = sp(c).getString("mood_" + LocalDate.now(), "");
        String stress = "";
        try {
            List<JSONObject> l = HeartLog.all(c);
            long now = System.currentTimeMillis(), dayStart = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            int pct = HeartLog.stressPct(l, HeartLog.baseline(l, now), dayStart, now);
            if (pct >= 0) stress = " (ఈరోజు ఒత్తిడి: " + HeartLog.stressWord(pct) + ")";
        } catch (Exception ignored) {}
        try {
            Diary.add(c, (m.isEmpty() ? "" : m + " ") + text + stress, "");
            return "📔 డైరీలో రాశాను" + (m.isEmpty() ? "" : " " + m) + ". గుడ్ నైట్!";
        } catch (Exception e) {
            return "డైరీలో రాయలేకపోయాను: " + e.getMessage();
        }
    }

    /** Around 9:30 pm (worn, not on duty, nothing written today): a quiet card on the wrist that opens the diary. */
    static void diaryTick(Context c) {
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() != 21 || now.getMinute() < 25 || !wset(c, "night_diary", true)) return;
        String key = "diary_asked_" + LocalDate.now();
        if (sp(c).getBoolean(key, false)) return;
        if (!WatchHub.watchHere(c) || !Boolean.TRUE.equals(WatchHub.worn(c)) || DutyMode.on(c)) return;
        try { if (Diary.hasToday(c)) return; } catch (Exception ignored) {}
        sp(c).edit().putBoolean(key, true).apply();
        try {
            WatchHub.send(c, WatchAlerts.P_ALERT, new JSONObject().put("id", 7201).put("kind", "care").put("title", "📔 ఈరోజు ఎలా గడిచింది?")
                    .put("text", "మూడ్ నొక్కి, 1 నిమిషం చెప్పండి").put("open", "diary"));
        } catch (Exception ignored) {}
    }

    // ================================================================ W73: a focus timer

    static boolean focusing(Context c) { return sp(c).getLong("focus_until", 0) > System.currentTimeMillis(); }

    /** Notifications held back during a focus (NotifyListener counts them). */
    static void focusNote(Context c) { if (focusing(c)) sp(c).edit().putInt("focus_n", sp(c).getInt("focus_n", 0) + 1).apply(); }

    static String focusStart(Context c, int minutes, String what) {
        int m = Math.max(5, Math.min(180, minutes));
        long until = System.currentTimeMillis() + m * MIN;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        int before = nm == null ? 0 : nm.getCurrentInterruptionFilter();
        boolean dnd = nm != null && nm.isNotificationPolicyAccessGranted();
        if (dnd && before <= NotificationManager.INTERRUPTION_FILTER_ALL) try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); } catch (Exception ignored) {}
        sp(c).edit().putLong("focus_until", until).putInt("focus_n", 0).putInt("focus_before", before).putBoolean("focus_dnd", dnd)
                .putString("focus_what", what == null ? "" : what).putInt("focus_min", m).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent p = PendingIntent.getBroadcast(c, 292, new Intent(c, AlarmReceiver.class).setAction(ACTION_FOCUS_END), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, p); } catch (Exception e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, p); }
        if (WatchHub.timerOnWatch(c)) WatchHub.timer(c, m * 60, "🎯 ఫోకస్" + (what == null || what.isEmpty() ? "" : ": " + what), null, null);
        return "🎯 " + m + " నిమిషాల ఫోకస్ మొదలైంది" + (what == null || what.isEmpty() ? "" : " (" + what + ")") + "." + (dnd ? " అవసరం లేని నోటిఫికేషన్లు ఆపాను." : "");
    }

    /** The time is up (or he stopped it): Do Not Disturb as it was, and how it went. */
    static String focusEnd(Context c) {
        SharedPreferences s = sp(c);
        if (s.getLong("focus_until", 0) == 0) return "ఫోకస్ టైమర్ నడవడం లేదు.";
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (s.getBoolean("focus_dnd", false) && s.getInt("focus_before", 1) <= NotificationManager.INTERRUPTION_FILTER_ALL && nm != null)
            try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); } catch (Exception ignored) {}
        int n = s.getInt("focus_n", 0);
        String what = s.getString("focus_what", "");
        s.edit().putLong("focus_until", 0).apply();
        String r = "🎯 " + s.getInt("focus_min", 0) + " నిమిషాల ఫోకస్ అయిపోయింది" + (what.isEmpty() ? "" : " (" + what + ")") + ". ఈలోపు " + n + " నోటిఫికేషన్లు వచ్చాయి.";
        Reminders.notify(c, "🎯 ఫోకస్ అయిపోయింది", r + (what.isEmpty() ? "" : " మిషన్ పూర్తయితే \"" + what + " పూర్తయింది\" అనండి."), 293);
        return r;
    }

    // ================================================================ W79: his medical card on the watch (only if he turns it on)

    static boolean medOn(Context c) { return wset(c, "med_watch", false); }

    static JSONObject medical(Context c) throws Exception {
        Prefs p = new Prefs(c);
        JSONObject o = new JSONObject().put("on", medOn(c) && MedicalId.filled(p));
        if (!o.optBoolean("on")) return o;
        return o.put("name", p.name()).put("blood", p.medBlood()).put("allergy", p.medAllergy()).put("notes", p.medNotes()).put("contact", p.medContact());
    }

    // ================================================================ W82: the month's spending ring

    /** {spent, budget} this month, counted here (cached 2 h); spent -1 when the SMS can't be read. */
    static long[] spending(Context c) {
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis();
        if (now - s.getLong("spend_at", 0) < 2 * HOUR && s.getString("spend_month", "").equals(java.time.YearMonth.now().toString()))
            return new long[]{s.getLong("spent", -1), new Prefs(c).budget()};
        long spent = -1;
        if (c.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                long since = LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                spent = Math.round(Tools.spendingSince(c, since, 0).optDouble("total_spent", 0));
            } catch (Exception ignored) {}
        }
        s.edit().putLong("spend_at", now).putLong("spent", spent).putString("spend_month", java.time.YearMonth.now().toString()).apply();
        return new long[]{spent, new Prefs(c).budget()};
    }

    static boolean spendOn(Context c) { return wset(c, "spend_ring", true); }

    static JSONObject moneyPanel(Context c) throws Exception {
        if (!spendOn(c)) return new JSONObject().put("kind", "money").put("off", true);
        long[] s = spending(c);
        return new JSONObject().put("kind", "money").put("spent", s[0]).put("budget", s[1]).put("day", LocalDate.now().getDayOfMonth())
                .put("days", LocalDate.now().lengthOfMonth());
    }

    // ================================================================ his words (offline too) and the watch_tools tool

    /**
     * {"water_add", n} / {"water"} / {"find_watch"} / {"debt"} / {"nap", minutes} / {"focus", minutes} / {"focus_stop"} /
     * {"silent", on} — null when it isn't one of these (pure: tested on a desk).
     */
    static String[] asks(String bare) {
        String t = bare == null ? "" : bare.trim().toLowerCase(Locale.ROOT).replaceAll("[?.!,]+", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        java.util.regex.Matcher num = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(t);
        int n = num.find() ? Integer.parseInt(num.group(1)) : -1;
        if (t.matches("(?s).*(గ్లాసు|గ్లాస్|glass).*(నీళ్లు|నీరు|water).*(తాగాను|తాగా|తాగేశాను|drank).*|.*(నీళ్లు|నీరు).*(గ్లాసు|గ్లాస్).*(తాగాను|తాగా).*"))
            return new String[]{"water_add", String.valueOf(n > 0 && n < 10 ? n : t.matches("(?s).*(రెండు|2).*") ? 2 : 1)};
        if (t.matches("(?s).*(నీళ్లు|నీరు|water).*(ఎన్ని|ఎంత|how many|how much).*(తాగాను|తాగా|drank).*")) return new String[]{"water"};
        if (t.matches("(?s).*(నా\\s*)?(వాచ్|watch).*(ఎక్కడ|మోగించు|ring|find|వెతుకు).*")) return new String[]{"find_watch"};
        if (t.matches("(?s).*నిద్ర.*(ఎంత\\s*తక్కువ|లెక్క|బాకీ|debt|అప్పు).*|.*sleep debt.*")) return new String[]{"debt"};
        if (t.matches("(?s).*(కునుకు|పవర్ నాప్|power nap|nap).*") && t.matches("(?s).*(నిమిషాల|నిమిషాలు|ని|min|గంట|తీస్తాను|పడుకుంటాను|టైమర్).*") && !t.matches("(?s).*(రెయిన్|rain|ఫ్యాన్|సౌండ్).*"))
            return new String[]{"nap", String.valueOf(t.matches("(?s).*(గంటన్నర|1.5|90).*") ? 90 : t.matches("(?s).*గంట.*") && n <= 0 ? 60 : n > 0 ? n : 20)};
        if (t.matches("(?s).*(ఫోకస్|focus).*")) {
            if (t.matches("(?s).*(ఆపు|ఆపేయ్|అయిపోయింది|stop|off).*")) return new String[]{"focus_stop"};
            if (t.matches("(?s).*(టైమర్|మొదలు|పెట్టు|start|నిమిషాలు|నిమిషాల|min).*")) return new String[]{"focus", String.valueOf(n > 0 ? n : 25)};
        }
        if (t.matches("(?s).*(వాచ్|watch).*(సైలెంట్|silent).*(మోడ్|mode)?.*")) return new String[]{"silent", String.valueOf(!t.matches("(?s).*(ఆఫ్|off|ఆపు|వద్దు).*"))};
        return null;
    }

    static String answer(Context c, String[] a) {
        switch (a[0]) {
            case "water_add": return waterAdd(c, Integer.parseInt(a[1]));
            case "water": return waterAdd(c, 0);
            case "find_watch": return findWatch(c);
            case "debt": return sleepDebtText(c);
            case "nap": return nap(c, Integer.parseInt(a[1]));
            case "focus": return focusStart(c, Integer.parseInt(a[1]), "");
            case "focus_stop": return focusEnd(c);
            case "silent": {
                boolean on = Boolean.parseBoolean(a[1]);
                WatchHub.set(c, "silent", on);
                return on ? "సరే, వాచ్ సైలెంట్ మోడ్ ఆన్: జవాబులు రాతగా మాత్రమే, వైబ్రేషన్‌తో." : "సరే, వాచ్ సైలెంట్ మోడ్ ఆఫ్ (డ్యూటీ, మీటింగ్, Do Not Disturb లో మాత్రం సైలెంట్).";
            }
            default: return null;
        }
    }

    /** The watch_tools tool (online): the same, as JSON for the brain. */
    static String tool(Context c, JSONObject a) throws Exception {
        String act = a.optString("action").trim().toLowerCase(Locale.ROOT);
        String say;
        switch (act) {
            case "find_watch": say = findWatch(c); break;
            case "silent_on": case "silent_off": say = answer(c, new String[]{"silent", String.valueOf(act.endsWith("on"))}); break;
            case "nap": say = nap(c, a.optInt("minutes", 20)); break;
            case "sleep_debt": say = sleepDebtText(c); break;
            case "water_add": say = waterAdd(c, Math.max(1, a.optInt("glasses", 1))); break;
            case "water_status": say = waterAdd(c, 0); break;
            case "focus": say = focusStart(c, a.optInt("minutes", 25), a.optString("what", "")); break;
            case "focus_stop": say = focusEnd(c); break;
            case "medical_card_on": case "medical_card_off":
                WatchHub.set(c, "med_watch", act.endsWith("on"));
                WatchAlerts.pushInfoSoon(c);
                say = act.endsWith("on") ? (MedicalId.filled(new Prefs(c)) ? "మెడికల్ కార్డ్ వాచ్‌లో పెట్టాను (🩸 మెడికల్ బటన్)." : "మెడికల్ కార్డ్ వివరాలు (బ్లడ్ గ్రూప్ మొదలైనవి) సెట్టింగ్స్‌లో ఇంకా లేవు.")
                        : "మెడికల్ కార్డ్ వాచ్ నుంచి తీసేశాను.";
                break;
            case "parking_photo": say = WatchExtras.sendParking(c); break;
            case "duty_calendar_on": case "duty_calendar_off": say = WatchExtras.calSet(c, act.endsWith("on")); break;
            case "spending": {
                long[] s = spending(c);
                say = s[0] < 0 ? "SMS చదవడానికి అనుమతి లేదు." : "ఈ నెల బ్యాంక్ / UPI SMS ప్రకారం ఖర్చు ₹" + s[0] + (s[1] > 0 ? " (బడ్జెట్ ₹" + s[1] + ", " + Math.round(s[0] * 100.0 / s[1]) + "%)" : "") + ".";
                break;
            }
            case "open": say = Travel.openOnWatch(c, a.optString("screen", "status"), a.optString("target", "")) ? "Opened on his watch." : "His watch is not near the phone."; break;
            default: return new JSONObject().put("ok", false).put("error", "Unknown action " + act).toString();
        }
        return new JSONObject().put("ok", true).put("say", say).put("next", "Say this to him in short Telugu.").toString();
    }

    // ================================================================ W61: extras for the watch's faces (in the news push)

    static void infoExtras(Context c, JSONObject o) {
        try {
            android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
            if (bm != null) o.put("phone_bat", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("phone_chg", bm.isCharging());
            List<JSONObject> l = HeartLog.all(c);
            long now = System.currentTimeMillis(), dayStart = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            int pct = HeartLog.stressPct(l, HeartLog.baseline(l, now), dayStart, now);
            if (pct >= 0) o.put("stress", pct);
            if (spendOn(c)) {
                long[] s = spending(c);
                if (s[0] >= 0) o.put("spent", s[0]).put("budget", s[1]);
            }
            o.put("medical", medical(c));
            o.put("water", water(c)).put("water_goal", waterGoal(c));
        } catch (Exception ignored) {}
    }
}
