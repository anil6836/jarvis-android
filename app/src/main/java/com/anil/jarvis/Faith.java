package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every morning at his time: today's Bible verse (Telugu IRV) read out with a two-line meaning.
 * On his church day: "చర్చికి టైమ్ అవుతోంది" 45 minutes before the service. Quiet times get a notification only.
 */
final class Faith {
    private Faith() {}

    static final String ACTION_VERSE = "com.anil.jarvis.FAITH_VERSE", ACTION_CHURCH = "com.anil.jarvis.FAITH_CHURCH",
            ACTION_PLAN = "com.anil.jarvis.FAITH_PLAN";
    private static final String[] DAYS_TE = {"సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం", "ఆదివారం"};

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_faith", Context.MODE_PRIVATE); }

    /** "07:00" or "" (off). */
    static String verseTime(Context c) { return sp(c).getString("verse_time", "07:00"); } // on by default (he asked for it)

    /** {day 1-7 (Mon..Sun), "HH:mm"} or null. */
    static String church(Context c) { return sp(c).getString("church", ""); }

    static JSONObject set(Context c, String morningVerse, String church) throws Exception {
        SharedPreferences.Editor e = sp(c).edit();
        if (morningVerse != null) {
            String m = morningVerse.trim().toLowerCase(Locale.ROOT);
            if (m.equals("off") || m.equals("no") || m.contains("వద్దు")) e.putString("verse_time", "");
            else {
                String t = hhmm(m);
                if (t == null) return new JSONObject().put("ok", false).put("error", "bad_time").put("message", "Morning verse time as HH:mm, e.g. 07:00.");
                e.putString("verse_time", t);
            }
        }
        if (church != null) {
            String ch = church.trim().toLowerCase(Locale.ROOT);
            if (ch.equals("off") || ch.contains("వద్దు")) e.putString("church", "");
            else {
                int day = 7;
                String[] en = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
                for (int i = 0; i < 7; i++) if (ch.contains(en[i]) || ch.contains(DAYS_TE[i].substring(0, 2))) day = i + 1;
                String t = hhmm(ch.replaceAll("[^0-9:.]", " ").trim());
                if (t == null) return new JSONObject().put("ok", false).put("error", "bad_time").put("message", "Church service day and time, e.g. 'Sunday 09:00'.");
                e.putString("church", day + " " + t);
            }
        }
        e.apply();
        schedule(c);
        return status(c);
    }

    static JSONObject status(Context c) throws Exception {
        JSONObject o = new JSONObject().put("ok", true).put("morning_verse", verseTime(c).isEmpty() ? "off" : verseTime(c));
        String ch = church(c);
        if (ch.isEmpty()) o.put("church", "off");
        else {
            String[] p = ch.split(" ");
            o.put("church", DAYS_TE[Integer.parseInt(p[0]) - 1] + " " + p[1]).put("church_reminder", "45 minutes before");
        }
        return o;
    }

    /** "7", "7:30", "07.30", "19:00" -> "HH:mm"; null if not a time. */
    private static String hhmm(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?").matcher(s);
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1)), mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || mi > 59) return null;
        return String.format(Locale.ENGLISH, "%02d:%02d", h, mi);
    }

    // ---------------------------------------------------------------- alarms

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent verse = pi(c, ACTION_VERSE, 161), church = pi(c, ACTION_CHURCH, 162), plan = pi(c, ACTION_PLAN, 163);
        am.cancel(verse);
        am.cancel(church);
        am.cancel(plan);
        LocalDateTime now = LocalDateTime.now();
        if (planOn(c)) { // the Bible plan: a word at 8:30 pm if today's part is not read yet
            LocalDateTime t = LocalDate.now().atTime(20, 30);
            if (!t.isAfter(now)) t = t.plusDays(1);
            at(am, t, plan);
        }
        String v = verseTime(c);
        if (!v.isEmpty()) {
            LocalDateTime t = LocalDate.now().atTime(LocalTime.parse(v));
            if (!t.isAfter(now)) t = t.plusDays(1);
            at(am, t, verse);
        }
        String ch = church(c);
        if (!ch.isEmpty()) {
            String[] p = ch.split(" ");
            DayOfWeek d = DayOfWeek.of(Integer.parseInt(p[0]));
            LocalTime service = LocalTime.parse(p[1]);
            for (int k = 0; k < 8; k++) { // the next service day; the reminder is 45 minutes before the service
                LocalDate day = LocalDate.now().plusDays(k);
                if (day.getDayOfWeek() != d) continue;
                LocalDateTime t = day.atTime(service).minusMinutes(45);
                if (t.isAfter(now)) { at(am, t, church); break; }
            }
        }
    }

    private static PendingIntent pi(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, AlarmReceiver.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void at(AlarmManager am, LocalDateTime t, PendingIntent pi) {
        long when = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Exception e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** Asleep, on a call, Do Not Disturb, resting after duty, praying: words would disturb; a notification only. */
    private static boolean quiet(Context c) {
        Prefs p = new Prefs(c);
        if (p.night() || CallControl.busyWithCall() || Rest.resting(c) || SoundService.prayerOn) return true;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) { return false; }
    }

    static void fire(Context c, String action) {
        schedule(c); // the next one
        if (ACTION_PLAN.equals(action)) {
            if (!planOn(c) || planDoneToday(c) || planDone(c) >= DAYS) return;
            String text = "ఈరోజు బైబిల్ ప్లాన్ ఇంకా చదవలేదు: " + describe(planDone(c) + 1) + ". వినాలంటే 'Jarvis, బైబిల్ ప్లాన్ చదువు' అనండి.";
            Reminders.notify(c, "📖 బైబిల్ ప్లాన్", text, 163);
            if (!quiet(c)) Announcer.say(c, new Prefs(c).name() + ", " + text);
            return;
        }
        if (ACTION_CHURCH.equals(action)) {
            String ch = church(c);
            if (ch.isEmpty()) return;
            String time = ch.split(" ")[1];
            String text = "చర్చికి టైమ్ అవుతోంది. " + time + " కి ఆరాధన. బైబిల్, కానుక తీసుకెళ్లండి.";
            Reminders.notify(c, "⛪ చర్చి", text, 162);
            if (!quiet(c)) Announcer.say(c, new Prefs(c).name() + ", " + text);
            return;
        }
        new Thread(() -> {
            try {
                JSONObject v = Bible.daily();
                String ref = v.optString("book") + " " + v.optInt("chapter") + ":" + v.optString("verses");
                String verse = v.optString("text").replaceFirst("^\\d+\\.\\s*", "");
                String meaning = "";
                Prefs p = new Prefs(c);
                if (!p.apiKey().isEmpty()) {
                    try {
                        meaning = Brain.oneShot(p, "You explain a Bible verse simply, in Telugu, for an ordinary believer. No other verses, no quotes, 2 short sentences.",
                                "Verse (" + ref + "): " + verse + "\nIn 2 short simple Telugu sentences: what it means and how to live it today.", null, false);
                    } catch (Exception ignored) {}
                }
                String text = "ఈరోజు వచనం, " + ref + ". " + verse + (meaning == null || meaning.isEmpty() ? "" : "\n" + meaning.trim());
                String more = morningExtras(c);
                if (!more.isEmpty()) text += "\n" + more;
                Reminders.notify(c, "📖 ఈరోజు వచనం · " + ref, text, 161);
                if (!quiet(c)) Announcer.say(c, "శుభోదయం " + (HomeCare.on(c) ? HomeCare.who(c) : p.name()) + ". " + text); // (the home tablet greets అమ్మగారు)
            } catch (Exception ignored) {}
        }, "jarvis-verse").start();
    }

    // ================================================================ Bible in a year: about 3 chapters a day, 365 parts

    static final int TOTAL = 1189, DAYS = 365;

    static boolean planOn(Context c) { return sp(c).getBoolean("plan_on", false); }

    static int planDone(Context c) { return sp(c).getInt("plan_done", 0); }

    static boolean planDoneToday(Context c) { return LocalDate.now().toString().equals(sp(c).getString("plan_last", "")); }

    /** Part k (1-365): chapter numbers [from, to) counted through the whole Bible. */
    static int[] part(int k) {
        int a = (int) ((long) (k - 1) * TOTAL / DAYS), b = (int) ((long) k * TOTAL / DAYS);
        return new int[]{a, Math.max(a + 1, b)};
    }

    /** {book, chapter} of the n-th chapter of the Bible (0-based). */
    static int[] chapterAt(int n) {
        int book = 0;
        while (book < Bible.CHAPTERS.length - 1 && n >= Bible.CHAPTERS[book]) { n -= Bible.CHAPTERS[book]; book++; }
        return new int[]{book, n + 1};
    }

    /** "ఆదికాండము 1-3", or across books "ఆదికాండము 50, నిర్గమకాండము 1-2". */
    static String describe(int k) {
        int[] r = part(k);
        StringBuilder b = new StringBuilder();
        int i = r[0];
        while (i < r[1]) {
            int[] s0 = chapterAt(i);
            int j = i;
            while (j + 1 < r[1] && chapterAt(j + 1)[0] == s0[0]) j++;
            int last = chapterAt(j)[1];
            if (b.length() > 0) b.append(", ");
            b.append(Bible.nameOf(s0[0])).append(' ').append(s0[1]).append(last > s0[1] ? "-" + last : "");
            i = j + 1;
        }
        return b.toString();
    }

    static JSONObject planStatus(Context c) throws Exception {
        if (!planOn(c)) return new JSONObject().put("ok", true).put("plan", "off").put("next", "Offer to start it (bible action plan_start): the whole Bible in a year, about 3 chapters a day.");
        LocalDate start = LocalDate.parse(sp(c).getString("plan_start", LocalDate.now().toString()));
        int day = (int) (LocalDate.now().toEpochDay() - start.toEpochDay()) + 1, done = planDone(c);
        int behind = Math.max(0, day - 1 - done);
        JSONObject o = new JSONObject().put("ok", true).put("plan", "on").put("started", start.toString()).put("day", day)
                .put("parts_done", done).put("of", DAYS).put("percent", Math.round(done * 100.0 / DAYS)).put("today_done", planDoneToday(c));
        if (done < DAYS) o.put("next_part", describe(done + 1));
        else o.put("finished", true);
        if (behind > 0) o.put("days_behind", behind);
        return o;
    }

    /** Starts the plan (or carries on where it stopped; restart = from day 1 again). */
    static JSONObject planStart(Context c, boolean restart) throws Exception {
        if (!restart && planDone(c) > 0 && planDone(c) < DAYS) {
            // carrying on after a pause: the days off don't count as days behind
            sp(c).edit().putBoolean("plan_on", true).putString("plan_start", LocalDate.now().minusDays(planDone(c)).toString()).apply();
            schedule(c);
            return planStatus(c).put("note", "Carrying on from where he stopped (plan_restart starts again from day 1).");
        }
        sp(c).edit().putBoolean("plan_on", true).putString("plan_start", LocalDate.now().toString()).putInt("plan_done", 0).remove("plan_last").apply();
        schedule(c);
        return planStatus(c).put("note", "Started today. Each day's part is read aloud on 'బైబిల్ ప్లాన్ చదువు'; a reminder at 8:30 pm if it is not read.");
    }

    static JSONObject planOff(Context c) throws Exception {
        sp(c).edit().putBoolean("plan_on", false).apply();
        schedule(c);
        return new JSONObject().put("ok", true).put("plan", "off").put("note", "Progress is kept: plan_start carries on from there.");
    }

    /** He read the next part (himself, or Jarvis finished reading it aloud). part = which one; 0 = the next. */
    static JSONObject planMark(Context c, int part) throws Exception {
        if (!planOn(c)) return new JSONObject().put("ok", false).put("error", "plan_off").put("message", "The Bible plan is off.");
        int done = planDone(c);
        if (part > 0 && part != done + 1) return planStatus(c); // an old reading finishing late: nothing to change
        if (done >= DAYS) return planStatus(c);
        sp(c).edit().putInt("plan_done", done + 1).putString("plan_last", LocalDate.now().toString()).apply();
        return planStatus(c).put("marked", describe(done + 1));
    }

    /** The next part read aloud (the book reader: ⏸ / ▶ in the notification, "ఆపు", carries on later). */
    static JSONObject planRead(Context c) throws Exception {
        if (!planOn(c)) planStart(c, false);
        int k = planDone(c) + 1;
        if (k > DAYS) return planStatus(c);
        java.io.File f = new java.io.File(c.getFilesDir(), "reader/bible_plan.txt");
        String uri = android.net.Uri.fromFile(f).toString() + "#" + k;
        // the same part stopped half way: carry on from there
        boolean resume = uri.equals(ReaderService.mark(c).getString("uri", "")) && f.exists();
        if (!resume) {
            int[] r = part(k);
            StringBuilder text = new StringBuilder();
            for (int n = r[0]; n < r[1]; n++) {
                int[] bc = chapterAt(n);
                text.append(Bible.nameOf(bc[0])).append(", ").append(bc[1]).append("వ అధ్యాయం.\n").append(Bible.chapterText(bc[0], bc[1])).append("\n\n");
            }
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ReaderService.start(c, "బైబిల్ ప్లాన్ · రోజు " + k, uri, "txt", !resume, "bible_plan:" + k);
        return new JSONObject().put("ok", true).put("reading", describe(k)).put("part", k).put("of", DAYS)
                .put("next", "Say in one line which chapters are being read now (the phone reads them; ⏸ in the notification). It is marked done when the reading ends.");
    }

    // ================================================================ prayer list

    private static final String PRAYERS = "prayers";

    static JSONObject prayerAdd(Context c, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return new JSONObject().put("ok", false).put("error", "no_text").put("message", "What to pray for?");
        JSONObject o = new JSONObject().put("id", Notes.id("pr")).put("text", text.trim()).put("added", LocalDate.now().toString()).put("answered", "");
        Notes.add(c, PRAYERS, o, 300);
        return prayerList(c).put("added", text.trim());
    }

    static JSONObject prayerList(Context c) throws Exception {
        JSONArray open = new JSONArray(), answered = new JSONArray();
        int n = 0;
        for (JSONObject o : Notes.list(c, PRAYERS)) {
            n++;
            JSONObject x = new JSONObject().put("n", n).put("text", o.optString("text")).put("since", o.optString("added"));
            if (o.optString("answered").isEmpty()) open.put(x);
            else answered.put(x.put("answered", o.optString("answered")).put("how", o.optString("note")));
        }
        return new JSONObject().put("ok", true).put("praying_for", open).put("answered", answered);
    }

    /** Which item: its number in the list, or words from it. */
    private static int prayerFind(List<JSONObject> l, int n, String text) {
        if (n > 0 && n <= l.size()) return n - 1;
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).optString("text").toLowerCase(Locale.ROOT).contains(t)) return i;
        for (int i = 0; i < l.size(); i++) if (t.contains(l.get(i).optString("text").toLowerCase(Locale.ROOT))) return i;
        return -1;
    }

    static JSONObject prayerAnswered(Context c, int n, String text, String note) throws Exception {
        List<JSONObject> l = Notes.list(c, PRAYERS);
        int i = prayerFind(l, n, text);
        if (i < 0) return prayerList(c).put("ok", false).put("error", "not_found").put("message", "Which one? Give its number from the list.");
        l.get(i).put("answered", LocalDate.now().toString()).put("note", note == null ? "" : note.trim());
        Notes.save(c, PRAYERS, l, 300);
        return new JSONObject().put("ok", true).put("answered", l.get(i).optString("text")).put("prayed_since", l.get(i).optString("added"))
                .put("next", "Rejoice with him in one or two warm lines (thank God together).");
    }

    static JSONObject prayerRemove(Context c, int n, String text) throws Exception {
        List<JSONObject> l = Notes.list(c, PRAYERS);
        int i = prayerFind(l, n, text);
        if (i < 0) return prayerList(c).put("ok", false).put("error", "not_found").put("message", "Which one? Give its number from the list.");
        JSONObject gone = l.remove(i);
        Notes.save(c, PRAYERS, l, 300);
        return new JSONObject().put("ok", true).put("removed", gone.optString("text"));
    }

    /** The open prayer items, for the prayer time. */
    static JSONArray prayingFor(Context c) {
        JSONArray a = new JSONArray();
        for (JSONObject o : Notes.list(c, PRAYERS)) if (o.optString("answered").isEmpty()) a.put(o.optString("text"));
        return a;
    }

    // ================================================================ learning verses by heart

    private static final String VERSES = "memorize_verses";
    private static final int[] GAP = {1, 3, 7, 14, 30, 60}; // days until the next check, after each good recital
    static volatile boolean asked; // he was asked to say a verse: listen once without "Jarvis"

    static boolean awaiting() {
        boolean a = asked;
        asked = false;
        return a;
    }

    /** The verse being learnt / asked just now (its id), so his recital is checked against that one. */
    private static volatile String currentId = "";

    static JSONObject memorizeAdd(Context c, String book, int chapter, int from, int to) throws Exception {
        JSONObject r = Bible.read(book, chapter <= 0 ? 1 : chapter, from <= 0 ? 1 : from, to <= 0 ? (from <= 0 ? 1 : from) : to);
        String text = r.optString("text").replaceAll("(?m)^\\d+\\.\\s*", "").replace("\n", " ").trim();
        if (text.isEmpty()) return new JSONObject().put("ok", false).put("error", "no_verse")
                .put("message", "That chapter has no such verse (it has " + r.optInt("chapter_verses") + " verses). Ask him the verse again.");
        String ref = r.optString("book") + " " + r.optInt("chapter") + ":" + r.optString("verses").replaceAll("^(\\d+)-\\1$", "$1");
        List<JSONObject> l = Notes.list(c, VERSES);
        for (int i = 0; i < l.size(); i++) {
            JSONObject o = l.get(i);
            if (!o.optString("ref").equals(ref)) continue;
            asked = true;
            currentId = o.optString("id");
            return new JSONObject(o.toString()).put("ok", true).put("n", i + 1).put("already", true).put("next", LEARN);
        }
        JSONObject o = new JSONObject().put("id", Notes.id("mv")).put("ref", ref).put("text", text).put("added", LocalDate.now().toString())
                .put("stage", 0).put("due", LocalDate.now().toString());
        Notes.add(c, VERSES, o, 100);
        asked = true;
        currentId = o.optString("id");
        return new JSONObject(o.toString()).put("ok", true).put("n", Notes.list(c, VERSES).size()).put("next", LEARN);
    }

    private static final String LEARN = "Read the verse slowly twice (reference first), then say 'ఇప్పుడు మీరు చెప్పండి' and stop. "
            + "When he says it, call bible action memorize_check with his exact words in text (and n from this result).";

    /**
     * Which saved verse: its number in the list, or the book (any language) + chapter (+ verse); -1 when that matches
     * nothing. Nothing given: for a check, the one being learnt now, else the one due first; for a removal, none (-1).
     */
    private static int verseFind(List<JSONObject> l, int n, String book, int chapter, int verse, boolean pickDefault) {
        if (n > 0) return n <= l.size() ? n - 1 : -1;
        String b = book == null ? "" : book.trim();
        if (!b.isEmpty()) {
            String f = Bible.folder(b);
            if (f == null) return -1;
            for (int i = 0; i < l.size(); i++) {
                String ref = l.get(i).optString("ref");
                if (!ref.startsWith(f + " ")) continue;
                String cv = ref.substring(f.length() + 1);
                if (chapter > 0 && !cv.startsWith(chapter + ":")) continue;
                if (verse > 0 && !cv.matches("\\d+:" + verse + "(-\\d+)?")) continue;
                return i;
            }
            return -1;
        }
        if (!pickDefault) return -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).optString("id").equals(currentId)) return i;
        int best = -1;
        for (int i = 0; i < l.size(); i++) if (best < 0 || l.get(i).optString("due").compareTo(l.get(best).optString("due")) < 0) best = i;
        return best;
    }

    static JSONObject memorizeCheck(Context c, int n, String book, int chapter, int verse, String said) throws Exception {
        List<JSONObject> l = Notes.list(c, VERSES);
        if (l.isEmpty()) return new JSONObject().put("ok", false).put("error", "none").put("message", "No verse to learn yet. Which verse? (memorize_add)");
        int i = verseFind(l, n, book, chapter, verse, true);
        if (i < 0) return memorizeList(c).put("ok", false).put("error", "not_found").put("message", "That verse is not in his list. Which one? (n from the list)");
        JSONObject v = l.get(i);
        currentId = v.optString("id");
        if (said == null || said.trim().isEmpty()) { // asked to check a verse: tell him which, and listen
            asked = true;
            return new JSONObject().put("ok", true).put("n", i + 1).put("ref", v.optString("ref"))
                    .put("next", "Say only: '" + v.optString("ref") + " చెప్పండి.' (don't read the verse). Then call memorize_check with his words and this n.");
        }
        Object[] r = recite(v.optString("text"), said);
        double score = (double) r[0];
        int stage = v.optInt("stage");
        boolean good = score >= 0.85;
        LocalDate due = LocalDate.now().plusDays(good ? GAP[Math.min(stage, GAP.length - 1)] : 1);
        v.put("stage", good ? stage + 1 : stage).put("due", due.toString()).put("last_score", Math.round(score * 100));
        Notes.save(c, VERSES, l, 100);
        if (!good) asked = true; // he will try again right away
        return new JSONObject().put("ok", true).put("n", i + 1).put("ref", v.optString("ref")).put("score_percent", Math.round(score * 100)).put("passed", good)
                .put("missed_words", r[1]).put("verse", v.optString("text")).put("next_check", due.toString())
                .put("next", good ? "Praise him warmly in one line, say the next check date (in Telugu words)."
                        : "Encourage him; say the words he missed, read the verse once more slowly, and ask him to try again (memorize_check with this n).");
    }

    static JSONObject memorizeList(Context c) throws Exception {
        JSONArray a = new JSONArray();
        int n = 0;
        for (JSONObject o : Notes.list(c, VERSES)) a.put(new JSONObject().put("n", ++n).put("ref", o.optString("ref")).put("next_check", o.optString("due"))
                .put("good_recitals", o.optInt("stage")).put("last_score", o.optInt("last_score", -1)));
        return new JSONObject().put("ok", true).put("verses", a);
    }

    static JSONObject memorizeRemove(Context c, int n, String book, int chapter, int verse) throws Exception {
        List<JSONObject> l = Notes.list(c, VERSES);
        int i = verseFind(l, n, book, chapter, verse, false);
        if (i < 0) return memorizeList(c).put("ok", false).put("error", "which").put("message", "Which verse? Give its number from the list.");
        JSONObject gone = l.remove(i);
        Notes.save(c, VERSES, l, 100);
        return new JSONObject().put("ok", true).put("removed", gone.optString("ref"));
    }

    /** Verses due today or earlier. */
    static List<String> versesDue(Context c) {
        List<String> out = new ArrayList<>();
        String today = LocalDate.now().toString();
        for (JSONObject o : Notes.list(c, VERSES)) if (o.optString("due").compareTo(today) <= 0) out.add(o.optString("ref"));
        return out;
    }

    /** Words of a Telugu text, without punctuation, verse numbers or joiners. */
    static List<String> wordsOf(String s) {
        List<String> out = new ArrayList<>();
        for (String w : (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[\\u200c\\u200d]", "").split("[^\\p{L}\\p{M}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static boolean near(String a, String b) {
        if (a.equals(b)) return true;
        int max = Math.max(a.length(), b.length());
        if (Math.min(a.length(), b.length()) < 3) return false;
        return lev(a, b) <= Math.max(1, max / 4); // a heard word with a letter or two off still counts
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** How much of the verse he said, in order (0-1), and the verse's words he missed (up to 10). Words heard split or joined still count. */
    static Object[] recite(String verse, String said) {
        List<String> v = wordsOf(verse), s = wordsOf(said);
        int nv = v.size(), ns = s.size();
        int[][] d = new int[nv + 2][ns + 2];
        for (int i = nv - 1; i >= 0; i--)
            for (int j = ns - 1; j >= 0; j--) {
                int best = Math.max(d[i + 1][j], d[i][j + 1]);
                if (near(v.get(i), s.get(j))) best = Math.max(best, d[i + 1][j + 1] + 1);
                if (j + 1 < ns && near(v.get(i), s.get(j) + s.get(j + 1))) best = Math.max(best, d[i + 1][j + 2] + 1); // "వాని యందు"
                if (i + 1 < nv && near(v.get(i) + v.get(i + 1), s.get(j))) best = Math.max(best, d[i + 2][j + 1] + 2); // two words heard as one
                d[i][j] = best;
            }
        JSONArray missed = new JSONArray();
        int i = 0, j = 0;
        while (i < nv) {
            if (j < ns) {
                if (near(v.get(i), s.get(j)) && d[i][j] == d[i + 1][j + 1] + 1) { i++; j++; continue; }
                if (j + 1 < ns && near(v.get(i), s.get(j) + s.get(j + 1)) && d[i][j] == d[i + 1][j + 2] + 1) { i++; j += 2; continue; }
                if (i + 1 < nv && near(v.get(i) + v.get(i + 1), s.get(j)) && d[i][j] == d[i + 2][j + 1] + 2) { i += 2; j++; continue; }
                if (d[i][j] == d[i][j + 1]) { j++; continue; }
            }
            if (missed.length() < 10) missed.put(v.get(i));
            i++;
        }
        return new Object[]{nv == 0 ? 0.0 : Math.min(1.0, d[0][0] / (double) nv), missed};
    }

    /** Added to the morning verse: today's plan part and verses to say from memory. */
    static String morningExtras(Context c) {
        StringBuilder b = new StringBuilder();
        if (planOn(c) && planDone(c) < DAYS && !planDoneToday(c)) b.append("ఈరోజు బైబిల్ ప్లాన్: ").append(describe(planDone(c) + 1)).append(". ");
        List<String> due = versesDue(c);
        if (!due.isEmpty()) b.append("ఈరోజు కంఠస్థ వచనం: ").append(due.get(0)).append(due.size() > 1 ? " (ఇంకా " + (due.size() - 1) + ")" : "")
                .append(". అప్పజెప్పాలంటే 'Jarvis, వచనం అప్పజెప్తాను' అనండి.");
        return b.toString().trim();
    }
}
