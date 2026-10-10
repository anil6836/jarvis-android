package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The home Jarvis looks after అమ్మగారు (Anil's mother, at home alone while he is on duty): asks about tiffin, lunch,
 * evening snacks and dinner, water now and then, her tablets (Medicine, asked every 10 minutes until she says she took
 * them, then Anil is told), talks with her a little in the day, a bedtime check, a daily report to Anil, "బాగున్నారా?"
 * when the house has been silent for hours, and Bible stories kept ready for when the internet is down.
 * Her answers come by voice (said aloud, answered by voice; simple yes / no words work offline) or the big buttons.
 * Messages to Anil go through his own Telegram bot (the home link); no SIM is needed. Nothing she says in her chats is
 * sent to him: only the day's ticks (meals, tablets, water, sugar) and alerts.
 */
final class HomeCare {
    private HomeCare() {}

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_homecare", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return new Prefs(c).homeMode(); }

    /** How Jarvis calls her (Anil chose "అమ్మగారు"). */
    static String who(Context c) { return sp(c).getString("who", "అమ్మగారు"); }

    // ================================================================ her day's times (Anil can change them)
    /** {key, Telugu name, default time} */
    static final String[][] MEALS = {{"tiffin", "టిఫిన్", "08:30"}, {"lunch", "భోజనం", "13:00"}, {"snack", "సాయంత్రం స్నాక్స్", "16:30"},
            {"dinner", "రాత్రి భోజనం", "20:00"}};

    static String time(Context c, String key, String def) { return sp(c).getString("t_" + key, def); }
    static String waterTimes(Context c) { return sp(c).getString("t_water", "10:30,12:30,15:30,18:00"); }
    static String chatTimes(Context c) { return sp(c).getString("t_chat", "10:00,17:30"); }
    static String bedTime(Context c) { return sp(c).getString("t_bed", "21:30"); }
    static String reportTime(Context c) { return sp(c).getString("t_report", "21:45"); }
    /** Her afternoon rest: no chats, no checks ("13:30-15:30"). */
    static String rest(Context c) { return sp(c).getString("t_rest", "13:30-15:30"); }
    /** Hours of silence in the day before "బాగున్నారా?". */
    static int silentHours(Context c) { return sp(c).getInt("silent_hours", 3); }
    /** Sugar readings outside these tell Anil at once. */
    static int sugarLow(Context c) { return sp(c).getInt("sugar_low", 70); }
    static int sugarHigh(Context c) { return sp(c).getInt("sugar_high", 250); }
    /** What her doctor said to do when she feels unwell (Anil types it; Jarvis reads it out). */
    static String doctorNote(Context c) { return sp(c).getString("doctor_note", ""); }

    // ================================================================ the engine
    private static ScheduledExecutorService ex;
    private static Context app;

    /** Once per process, in home mode (MainActivity / JarvisApp). */
    static synchronized void start(Context c) {
        if (ex != null || !on(c)) return;
        app = c.getApplicationContext();
        Bible.setCache(new java.io.File(app.getFilesDir(), "bible"));
        NaturalVoice.homeStyle = true;
        if (sp(app).getLong("life", 0) == 0) life(app);
        ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "home-care"); t.setDaemon(true); return t; });
        ex.scheduleWithFixedDelay(() -> { try { tick(app); } catch (Throwable ignored) {} }, 20, 60, TimeUnit.SECONDS);
        // the home link: words, pictures and voices from Anil's phone (when the guard isn't running, it polls here)
        ex.scheduleWithFixedDelay(() -> {
            try { // (home mode switched off since: this device no longer takes the tablet's messages)
                if (on(app) && !Guard.running(app) && !Guard.token(app).isEmpty() && !Guard.chat(app).isEmpty() && Net.online(app)) HomeLink.homePoll(app);
            } catch (Throwable ignored) {}
        }, 15, 20, TimeUnit.SECONDS);
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date()); }

    private static int minutes(String hhmm) {
        try {
            String[] p = hhmm.trim().split("[:.]"); // ("8.30" as typed on a Telugu keyboard is 8:30 too)
            int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int nowMin() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
    }

    /** It is time for this (key) and it hasn't been done today: up to 90 minutes late (the tablet may have been off). */
    private static boolean due(Context c, String key, String hhmm) {
        int at = minutes(hhmm), now = nowMin();
        if (at < 0 || now < at || now > at + 90) return false;
        if (today().equals(sp(c).getString("done_" + key, ""))) return false;
        sp(c).edit().putString("done_" + key, today()).apply();
        return true;
    }

    static boolean resting(Context c) {
        String[] r = rest(c).split("-");
        if (r.length != 2) return false;
        int a = minutes(r[0]), b = minutes(r[1]), n = nowMin();
        return a >= 0 && b >= 0 && n >= a && n < b;
    }

    static boolean night() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= HomeScreen.NIGHT_FROM || h < HomeScreen.NIGHT_TO;
    }

    /** She said she is going out (for at most 12 hours: a "వచ్చాను" she forgot must not stop the checks for days). */
    static boolean out(Context c) {
        return sp(c).getBoolean("out", false) && System.currentTimeMillis() - sp(c).getLong("out_at", 0) < 12 * 3600_000L;
    }

    private static void setOut(Context c, boolean out) {
        sp(c).edit().putBoolean("out", out).putLong("out_at", System.currentTimeMillis()).apply();
    }
    static boolean quiet(Context c) { return System.currentTimeMillis() < sp(c).getLong("quiet_until", 0); }

    /** Someone is about: a touch, a word to Jarvis, footsteps, a door. */
    static void life(Context c) { sp(c).edit().putLong("life", System.currentTimeMillis()).apply(); }

    /** The sound watch's scores (YAMNet): talk without a TV playing, footsteps, a door. */
    static void sounds(Context c, float[] s) {
        if (s == null || !on(c)) return;
        if (Announcer.speaking()) return; // (Jarvis's own voice is not someone about)
        boolean tv = s.length > SafetySounds.TV && s[SafetySounds.TV] >= 0.25f;
        if ((s[SafetySounds.SPEECH] >= 0.5f && !tv) || s[SafetySounds.WALK] >= 0.4f
                || s.length > SafetySounds.DOOR && s[SafetySounds.DOOR] >= 0.4f) {
            long last = sp(c).getLong("life", 0);
            if (System.currentTimeMillis() - last > 60_000L) life(c); // (not a write every second)
        }
    }

    static void tick(Context c) {
        if (!on(c)) return;
        boolean calm = night() || out(c);
        if (!night() && !today().equals(sp(c).getString("woke_day", ""))) { // the morning: the screen that slept at night comes on
            sp(c).edit().putString("woke_day", today()).apply();
            MainActivity.homeWake();
        }
        for (String[] m : MEALS) {
            String t = time(c, m[0], m[2]);
            if (due(c, "meal_" + m[0], t) && !ate(c, m[0])) askMeal(c, m[0], m[1], false);
            // (the "may it be said now" checks come before due(): a turn kept back for a while still comes in its 90 minutes)
            int at = minutes(t), again = at < 0 ? -1 : at + 45; // (a time that can't be read: no re-ask at 00:44)
            if (again > 0 && !ate(c, m[0]) && !calm
                    && due(c, "meal2_" + m[0], String.format(Locale.US, "%02d:%02d", again / 60, again % 60))) askMeal(c, m[0], m[1], true);
        }
        int i = 0;
        for (String w : waterTimes(c).split(",")) { // (not while she asked for quiet: only tablets are said then)
            String key = "water" + (i++);
            if (!calm && !quiet(c) && !resting(c) && due(c, key, w)) ask(c, who(c) + ", కొంచెం నీళ్లు తాగారా?", "water", "caring");
        }
        i = 0;
        for (String w : chatTimes(c).split(",")) {
            String key = "chat" + (i++);
            if (!calm && !quiet(c) && !resting(c) && !sonHome(c)
                    && System.currentTimeMillis() - sp(c).getLong("life", 0) < 45 * 60_000L && due(c, key, w)) companion(c);
        }
        if (!out(c) && due(c, "bed", bedTime(c))) bedtime(c);
        String feast = feast(Calendar.getInstance(), who(c));
        if (feast != null && !out(c) && due(c, "feast", "08:00")) say(c, feast, "happy");
        if (due(c, "report", reportTime(c))) report(c);
        // stories for an offline day, and the Bible kept on the tablet
        if (Net.online(c) && !today().equals(sp(c).getString("stories_day", ""))) prepareStories(c);
        if (Net.online(c)) Bible.prefetchSome(4);
        silenceCheck(c);
        batteryCare(c);
        if (Net.online(c)) flushOutbox(c);
    }

    // ================================================================ asking and talking
    private static volatile String pending = "";
    private static volatile long pendingAt;

    /** Says it and listens for her answer (on the home screen); the answer is kept for 15 minutes. */
    static void ask(Context c, String text, String what, String feeling) {
        pending = what == null ? "" : what;
        pendingAt = System.currentTimeMillis();
        if (!MainActivity.homeAsk(text, feeling, true)) Announcer.say(c, text);
    }

    /** Says it (no answer needed). */
    static void say(Context c, String text, String feeling) {
        if (!MainActivity.homeAsk(text, feeling, false)) Announcer.say(c, text);
    }

    static String pending() { return System.currentTimeMillis() - pendingAt < 15 * 60_000L ? pending : ""; }
    static void pendingMed(String id, String time) { pending = "med:" + id + ":" + time; pendingAt = System.currentTimeMillis(); }

    private static void askMeal(Context c, String key, String name, boolean again) {
        String w = who(c);
        String q = again ? w + ", " + name + " ఇంకా అవ్వలేదా? టైమ్ దాటింది, కొంచెం తినండి." : w + ", " + name + " చేశారా?";
        ask(c, q, "meal:" + key, again ? "worried" : "caring");
    }

    // ---- her day's ticks
    private static JSONObject log(Context c) {
        try { return new JSONObject(sp(c).getString("log_" + today(), "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static void saveLog(Context c, JSONObject l) { sp(c).edit().putString("log_" + today(), l.toString()).apply(); }

    static boolean ate(Context c, String meal) { return log(c).has("meal_" + meal); }

    private static String nowText() { return new SimpleDateFormat("h:mm", Locale.ENGLISH).format(new Date()); }

    static void markMeal(Context c, String meal) {
        try { JSONObject l = log(c); l.put("meal_" + meal, nowText()); saveLog(c, l); } catch (Exception ignored) {}
    }

    /** The meal whose time is nearest now (for "తిన్నాను" without a name / the 🍽️ button). */
    static String[] nearestMeal(Context c) {
        int n = nowMin(), best = Integer.MAX_VALUE;
        String[] pick = MEALS[0];
        for (String[] m : MEALS) {
            int d = Math.abs(minutes(time(c, m[0], m[2])) - n);
            if (d < best) { best = d; pick = m; }
        }
        return pick;
    }

    private static void addTo(Context c, String key, Object v) {
        try {
            JSONObject l = log(c);
            if (v == null) l.put(key, l.optInt(key) + 1);
            else { JSONArray a = l.optJSONArray(key); if (a == null) a = new JSONArray(); a.put(v); l.put(key, a); }
            saveLog(c, l);
        } catch (Exception ignored) {}
    }

    // ================================================================ her words (before the AI; works offline too)
    private static final String[] EMERGENCY = {"కళ్లు తిరుగుతున్నాయి", "కళ్ళు తిరుగుతున్నాయి", "కళ్ళు తిరుగుతుంది", "కళ్లు తిరుగుతుంది", "ఛాతీ నొప్పి",
            "ఛాతి నొప్పి", "గుండె నొప్పి", "ఊపిరి ఆడటం లేదు", "ఊపిరి ఆడట్లేదు", "పడిపోయాను", "కింద పడ్డాను", "కాపాడండి", "సహాయం కావాలి", "ఆపద",
            "ఒంట్లో బాలేదు", "ఒంట్లో బాగోలేదు", "అస్సలు బాగోలేదు"};
    /** "కాపాడు!" (help me) as a word of its own, not a blessing ("దేవుడు కాపాడుతాడు", "కాపాడును"). */
    private static final java.util.regex.Pattern SAVE_ME = java.util.regex.Pattern.compile("కాపాడు(?![\\u0C00-\\u0C7F])");
    /** "I'm going out" (not "అబ్బాయి బయటికి వెళ్తున్నాడు": then she is still at home). */
    private static final java.util.regex.Pattern GOING = java.util.regex.Pattern.compile("(వెళ్తున్నా(ను|ం)?|వెళ్లొస్తా(ను|ం)?)(?![\\u0C00-\\u0C7F])");

    /** Her words: an answer to Jarvis's question, an emergency, "ఇప్పుడు వద్దు"… -> what Jarvis says, or null (the AI answers). */
    static String heard(Context c, String text) {
        if (!on(c) || text == null) return null;
        life(c);
        String t = text.trim().toLowerCase(Locale.ROOT), w = who(c);
        for (String e : EMERGENCY) if (t.contains(e)) return emergency(c, text);
        if (SAVE_ME.matcher(t).find()) return emergency(c, text);
        if (t.contains("ఇప్పుడు వద్దు") || t.contains("తర్వాత మాట్లాడు") || t.contains("తరువాత మాట్లాడు") || t.contains("నిశ్శబ్దంగా ఉండు")) {
            sp(c).edit().putLong("quiet_until", System.currentTimeMillis() + 2 * 3600_000L).apply();
            return "సరే " + w + ", కాసేపు నిశ్శబ్దంగా ఉంటాను. టాబ్లెట్ టైమ్ అయితే మాత్రం గుర్తుచేస్తాను.";
        }
        if ((t.contains("బయటికి") || t.contains("బయటకు") || t.contains("చర్చికి") || t.contains("గుడికి")) && GOING.matcher(t).find()) {
            setOut(c, true);
            return "సరే " + w + ", జాగ్రత్తగా వెళ్లి రండి. వచ్చాక \"Jarvis, వచ్చాను\" అనండి.";
        }
        if (out(c)) {
            boolean back = t.contains("వచ్చాను") || t.contains("వచ్చేశాను") || t.contains("ఇంటికి వచ్చా");
            // talking to the tablet at home a while after she left: she is back (also when she forgot to say so)
            if (back || System.currentTimeMillis() - sp(c).getLong("out_at", 0) > 10 * 60_000L) setOut(c, false);
            if (back) return "రండి " + w + "! బాగా జరిగిందా? కొంచెం నీళ్లు తాగి కూర్చోండి.";
        }
        if ((t.contains("అబ్బాయి") || t.contains("అనిల్") || t.contains("anil")) && (t.contains("మాట్లాడాలి") || t.contains("ఫోన్ చేయమను") || t.contains("ఫోన్ చెయ్యమను"))) {
            boolean ok = alert(c, w + " మీతో మాట్లాడాలనుకుంటున్నారు. వీలైనప్పుడు ఫోన్ చేయండి.");
            return ok ? "అబ్బాయికి చెప్పాను " + w + ". వీలవ్వగానే ఫోన్ చేస్తాడు." : "ఇప్పుడు నెట్ లేదు " + w + ", నెట్ రాగానే చెబుతాను.";
        }
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("(?:షుగర్|sugar)\\D{0,12}(\\d{2,3})").matcher(t);
        if (sm.find()) return sugar(c, Integer.parseInt(sm.group(1)));
        boolean tablet = t.contains("టాబ్లెట్") || t.contains("మాత్ర") || t.contains("మందు");
        boolean ate = t.contains("తిన్నా") || t.contains("భోజనం అయింది") || t.contains("టిఫిన్ అయింది");
        String p = pending();
        if (!p.isEmpty()) {
            // words about something else are not this question's answer (her tablet while the water / meal question waits,
            // "టిఫిన్ తిన్నాను" while the tablet question waits): they are ticked below as what they say
            boolean other = p.startsWith("med:") ? ate && !tablet : p.equals("water") ? tablet || ate : tablet;
            // a dose, a meal, water: only words that say it is done tick it ("సరే" alone is "OK, I will")
            boolean tick = p.startsWith("med:") || p.startsWith("meal:") || p.equals("water");
            Boolean yes = other ? null : yesNo(tick ? t.replace("సరే", " ").replace("ok", " ") : t);
            if (yes != null) return answer(c, p, yes);
        }
        // a direct "I took my tablet / I ate" without being asked
        if (tablet) {
            Boolean yes = yesNo(t);
            if (Boolean.TRUE.equals(yes)) return tabletTaken(c);
        }
        if (ate && !t.contains("లేదు")) {
            String[] m = mealIn(t);
            if (m == null) m = nearestMeal(c);
            markMeal(c, m[0]);
            return "చాలా మంచిది " + w + "! " + m[1] + " అయిందని రాసుకున్నాను.";
        }
        if (!Net.online(c)) { // offline: a Bible story or the day's verse from the tablet
            if (t.contains("కథ")) { String s = story(c); if (s != null) return s; }
            if (t.contains("వాక్యం") || t.contains("వచనం")) { String v = verse(c, false); if (v != null) return v; }
        }
        return null;
    }

    /** Yes / no in her words (no is checked first: "వేసుకోలేదు" has "వేసుకో" in it; "వేసుకుంటాను" = not yet). Null: neither. */
    static Boolean yesNo(String t) {
        String s = " " + t.replaceAll("[.,!?।]", " ") + " ";
        String[] no = {"లేదు", "లేదండి", "ఇంకా", "మర్చిపో", "వద్దు", " no ", "కాలేదు", "అవ్వలేదు", "తినలేదు", "తాగలేదు", "వేసుకోలేదు",
                "వేసుకుంటా", "వేసుకోవాలి", "తింటా", "తినాలి", "తాగుతా", "తాగాలి"};
        for (String n : no) if (s.contains(n)) return false;
        String[] yes = {"అవును", "ఔను", " హా ", " ఆ ", "వేసుకున్నా", "వేసుకున్నాను", "తిన్నా", "తిన్నాను", "తాగా", "తాగాను", "తాగేశా", "చేశా", "చేశాను",
                "అయింది", "అయ్యింది", "అయిపోయింది", " yes ", " ok ", "సరే", "బాగున్నా", "బాగానే", "బాగున్నాను"};
        for (String y : yes) if (s.contains(y)) return true;
        return null;
    }

    private static String[] mealIn(String t) {
        if (t.contains("టిఫిన్") || t.contains("టిఫిను") || t.contains("అల్పాహారం")) return MEALS[0];
        if (t.contains("స్నాక్") || t.contains("సాయంత్రం")) return MEALS[2];
        if (t.contains("రాత్రి") || t.contains("డిన్నర్")) return MEALS[3];
        if (t.contains("భోజనం") || t.contains("లంచ్") || t.contains("మధ్యాహ్నం")) return MEALS[1];
        return null;
    }

    private static String answer(Context c, String p, boolean yes) {
        String w = who(c);
        pending = "";
        if (p.startsWith("meal:")) {
            String key = p.substring(5);
            String name = key;
            for (String[] m : MEALS) if (m[0].equals(key)) name = m[1];
            if (yes) { markMeal(c, key); return "చాలా మంచిది " + w + "! " + name + " అయిందని రాసుకున్నాను."; }
            return "సరే " + w + ", ఆలస్యం చేయకుండా కొంచెం తినండి. టాబ్లెట్లు వేసుకునే ముందు తినడం మర్చిపోకండి.";
        }
        if (p.equals("water")) {
            if (yes) { addTo(c, "water", null); return "మంచిది " + w + "! ఇలాగే మధ్య మధ్యలో నీళ్లు తాగండి."; }
            return "ఇప్పుడే ఒక గ్లాసు నీళ్లు తాగండి " + w + ", ఒంటికి మంచిది.";
        }
        if (p.startsWith("med:")) {
            String[] k = p.split(":", 3);
            if (yes) { // (the tablets asked together at this time are all marked)
                String time = k.length > 2 ? k[2] : null;
                java.util.List<JSONObject> same = Medicine.untakenAt(c, time);
                JSONObject m = Medicine.byIdPublic(c, k[1]);
                if (same.isEmpty() && m != null && (time == null || time.isEmpty())) same.add(m);
                for (JSONObject x : same) Medicine.taken(c, x, time);
                return same.isEmpty() ? "సరే " + w + ". ఈ టాబ్లెట్ వేసుకున్నట్టు ఇదివరకే రాసుకున్నాను."
                        : "చాలా బాగుంది " + w + "! " + Medicine.names(same) + " వేసుకున్నారని రాసుకున్నాను.";
            }
            pendingMed(k[1], k.length > 2 ? k[2] : "");
            return "సరే " + w + ", ఇప్పుడే వేసుకోండి. 10 నిమిషాల్లో మళ్లీ అడుగుతాను.";
        }
        if (p.equals("ok")) {
            sp(c).edit().putInt("ok_asks", 0).apply();
            if (yes) return "సంతోషం " + w + "! ఏమైనా కావాలంటే \"Jarvis\" అని పిలవండి.";
            pending = "tell"; // (her yes to "అబ్బాయికి చెప్పమంటారా?" is heard here too, offline as well)
            pendingAt = System.currentTimeMillis();
            return "ఏమైంది " + w + "? చెప్పండి, అబ్బాయికి చెప్పమంటారా?";
        }
        if (p.equals("tell")) {
            if (!yes) return "సరే " + w + ". ఏమైనా కావాలంటే \"Jarvis\" అని పిలవండి.";
            boolean ok = alert(c, w + " \"బాగున్నారా?\" అంటే బాగోలేదన్నారు (" + nowText() + "). ఒకసారి ఫోన్ చేసి మాట్లాడండి.");
            return ok ? "అబ్బాయికి చెప్పాను " + w + ". వీలవ్వగానే ఫోన్ చేస్తాడు." : "ఇప్పుడు నెట్ లేదు " + w + ", నెట్ రాగానే చెబుతాను.";
        }
        if (p.equals("bed")) return yes ? "మంచిది " + w + ". హాయిగా పడుకోండి, శుభరాత్రి." : "సరే " + w + ", ఒకసారి చూసి వచ్చి పడుకోండి.";
        if (p.equals("story")) return yes ? story(c) : "సరే " + w + ", ఇంకోసారి చెబుతాను.";
        return null;
    }

    /** The 💊 button / "టాబ్లెట్ వేసుకున్నాను": the doses due nearest now (all the tablets of that same time). */
    static String tabletTaken(Context c) {
        String w = who(c);
        JSONObject[] due = Medicine.nearestDue(c);
        if (due == null && !Medicine.all(c).isEmpty()) // (set, but no dose near now is left unmarked)
            return "సరే " + w + ". ఇప్పుడు వేసుకోవాల్సిన టాబ్లెట్ ఏదీ మిగల్లేదు; ఉన్నవి ఇదివరకే రాసుకున్నాను.";
        if (due == null) return "సరే " + w + ". (ఈ టాబ్లెట్ టైమ్‌లు Jarvis లో ఇంకా పెట్టలేదు; అబ్బాయి ఫోన్ నుంచి పెడతాడు.)";
        String time = due[1].optString("time");
        java.util.List<JSONObject> same = Medicine.untakenAt(c, time);
        if (same.isEmpty()) same.add(due[0]);
        for (JSONObject x : same) Medicine.taken(c, x, time);
        if (pending().startsWith("med:")) pending = "";
        return "చాలా బాగుంది " + w + "! " + Medicine.names(same) + " వేసుకున్నారని రాసుకున్నాను.";
    }

    /** The 🍽️ button. */
    static String ateNow(Context c) {
        String[] m = nearestMeal(c);
        markMeal(c, m[0]);
        if (pending().startsWith("meal:")) pending = "";
        return "చాలా మంచిది " + who(c) + "! " + m[1] + " అయిందని రాసుకున్నాను.";
    }

    private static String sugar(Context c, int v) {
        String w = who(c);
        addTo(c, "sugar", nowText() + " " + v);
        String r = w + ", షుగర్ " + v + " అని రాసుకున్నాను.";
        if (v < sugarLow(c) || v > sugarHigh(c)) {
            boolean ok = alert(c, "⚠️ " + w + " షుగర్ " + v + " (" + nowText() + ")" + (v < sugarLow(c) ? " — తక్కువగా ఉంది" : " — ఎక్కువగా ఉంది"));
            String doc = doctorNote(c);
            r += (v < sugarLow(c) ? " ఇది తక్కువగా ఉంది." : " ఇది ఎక్కువగా ఉంది.") + (ok ? " అబ్బాయికి చెప్పాను." : "")
                    + (doc.isEmpty() ? " ఒంట్లో బాగోలేకపోతే వెంటనే డాక్టర్‌కి ఫోన్ చేయించండి." : " డాక్టర్ చెప్పింది: " + doc);
        }
        return r;
    }

    private static String emergency(Context c, String said) {
        String w = who(c);
        boolean ok = alert(c, "🆘 ఇంట్లో " + w + " అన్నారు: \"" + said.trim() + "\" (" + nowText() + "). వెంటనే ఫోన్ చేయండి.");
        String doc = doctorNote(c);
        return (ok ? "అబ్బాయికి వెంటనే చెప్పాను " + w + ". " : "నెట్ లేదు, అబ్బాయికి చెప్పలేకపోయాను. ")
                + "కూర్చోండి లేదా పడుకోండి, కదలకండి." + (doc.isEmpty() ? "" : " డాక్టర్ చెప్పింది: " + doc + ".")
                + " ఛాతీ నొప్పి, ఊపిరి ఆడకపోవడం అయితే దగ్గర ఉన్నవాళ్లతో వెంటనే 108 కి ఫోన్ చేయించండి.";
    }

    /** To Anil, through his own Telegram bot (the home link), in the background. False: not linked or no internet now
     *  (then it is kept and sent when the internet is back). */
    static boolean alert(Context c, String text) {
        final Context a = c.getApplicationContext();
        if (Guard.token(a).isEmpty() || Guard.chat(a).isEmpty()) return false;
        boolean now = Net.online(a);
        new Thread(() -> {
            boolean sent = false;
            try { if (Net.online(a)) { flushOutbox(a); sent = Guard.send(a, "🏠 " + text); } } catch (Throwable ignored) {}
            if (!sent) keep(a, text);
        }, "home-alert").start();
        return now;
    }

    private static synchronized void keep(Context c, String text) {
        try { JSONArray q = new JSONArray(sp(c).getString("outbox", "[]")); q.put(text); sp(c).edit().putString("outbox", q.toString()).apply(); } catch (Exception ignored) {}
    }

    /** Messages kept while the internet was down (background threads only). */
    private static void flushOutbox(Context c) {
        JSONArray q;
        synchronized (HomeCare.class) {
            try { q = new JSONArray(sp(c).getString("outbox", "[]")); } catch (Exception e) { return; }
            if (q.length() == 0) return;
            sp(c).edit().putString("outbox", "[]").apply();
        }
        for (int i = 0; i < q.length(); i++) if (!Guard.send(c, "🏠 (నెట్ లేనప్పుడు) " + q.optString(i))) keep(c, q.optString(i));
    }

    // ================================================================ checks
    /** No sign of anyone for hours in the day: "బాగున్నారా?"; twice without an answer -> Anil. */
    private static void silenceCheck(Context c) {
        if (night() || out(c) || resting(c)) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 8 || h >= 20) return;
        long now = System.currentTimeMillis(), lastLife = sp(c).getLong("life", now), lastAsk = sp(c).getLong("ok_at", 0);
        int asks = sp(c).getInt("ok_asks", 0);
        // someone was about after the last "బాగున్నారా?" (e.g. last evening, after 8 pm when this doesn't run): a new round
        if (asks > 0 && lastAsk < lastLife) { asks = 0; sp(c).edit().putInt("ok_asks", 0).apply(); }
        if (now - lastLife < silentHours(c) * 3600_000L) { if (asks > 0) sp(c).edit().putInt("ok_asks", 0).apply(); return; }
        if (lastAsk > lastLife && now - lastAsk < 6 * 60_000L) return; // (waiting for her answer)
        if (asks >= 2) {
            if (lastAsk > lastLife && now - lastAsk >= 6 * 60_000L && !sp(c).getBoolean("ok_told", false)) {
                alert(c, "⚠️ ఇంట్లో " + silentHours(c) + " గంటలుగా ఎవరి అలికిడీ లేదు; " + who(c) + " \"బాగున్నారా?\" కి జవాబివ్వలేదు (" + nowText() + "). ఒకసారి ఫోన్ చేసి చూడండి.");
                sp(c).edit().putBoolean("ok_told", true).apply();
            }
            return;
        }
        sp(c).edit().putLong("ok_at", now).putInt("ok_asks", asks + 1).putBoolean("ok_told", false).apply();
        ask(c, who(c) + ", బాగున్నారా? ఒకసారి \"బాగున్నాను\" అనండి.", "ok", "worried");
    }

    private static void bedtime(Context c) {
        ask(c, who(c) + ", పడుకునే టైమ్ అవుతోంది. తలుపులు వేశారా? గ్యాస్ ఆఫ్ చేశారా? రాత్రి టాబ్లెట్ వేసుకున్నారా?", "bed", "caring");
    }

    /** Every evening to Anil: the day in ticks (never what she said in her chats). */
    static String reportText(Context c) {
        JSONObject l = log(c);
        StringBuilder b = new StringBuilder("📋 " + who(c) + " ఈరోజు (" + new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date()) + "):\n");
        for (String[] m : MEALS) b.append(m[1]).append(": ").append(l.has("meal_" + m[0]) ? "✅ " + l.optString("meal_" + m[0]) : "❔ చెప్పలేదు").append('\n');
        for (String s : Medicine.todayLinesNamed(c)) b.append("💊 ").append(s).append('\n');
        b.append("💧 నీళ్లు: ").append(l.optInt("water")).append(" సార్లు చెప్పారు\n");
        JSONArray su = l.optJSONArray("sugar");
        if (su != null && su.length() > 0) { b.append("🩸 షుగర్:"); for (int i = 0; i < su.length(); i++) b.append(' ').append(su.optString(i)).append(i < su.length() - 1 ? "," : ""); b.append('\n'); }
        b.append("🗣️ Jarvis తో మాట్లాడింది: ").append(l.optInt("talks")).append(" సార్లు");
        return b.toString();
    }

    private static void report(Context c) { alert(c, reportText(c)); }

    /** A word with Jarvis (each one is counted for the report; never its words). */
    static void talked(Context c) { if (on(c)) addTo(c, "talks", null); }

    // ================================================================ talking with her
    private static final String[] OFFLINE_CHAT = {
            "%s, ఎలా ఉన్నారు? ఈరోజు ఏం చేస్తున్నారు?", "%s, కాసేపు కూర్చుని విశ్రాంతి తీసుకోండి. ఏమైనా కావాలంటే నన్ను పిలవండి.",
            "%s, ఈరోజు ఏం వంట చేశారు?", "%s, మీకు ఇష్టమైన పాట పెట్టమంటారా? \"క్రిస్టియన్ పాటలు పెట్టు\" అనండి.",
            "%s, ఒక బైబిల్ కథ చెప్పమంటారా?"};

    private static void companion(Context c) {
        String w = who(c);
        new Thread(() -> {
            String line = null;
            Prefs p = new Prefs(c);
            if (Net.online(c) && p.hasBrain() && !overLimit(c)) {
                try {
                    countAi(c);
                    int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    line = Brain.oneShot(p, persona(c),
                            "It is " + HomeScreen.partOfDay(h) + ". Today so far: " + reportText(c).replace('\n', ' ')
                                    + "\nSay ONE short warm thing to start a little chat with " + w + " (at most 2 short sentences, simple spoken Telugu): "
                                    + "ask how she is, about her day or something she likes; sometimes a tiny good thought. End with a simple question.", null, false);
                } catch (Exception ignored) {}
            }
            if (line == null || line.trim().isEmpty()) {
                int k = (int) (System.currentTimeMillis() / 3600_000L % OFFLINE_CHAT.length);
                line = String.format(OFFLINE_CHAT[k], w);
            }
            ask(c, line.trim(), "", "smile");
        }, "home-chat").start();
    }

    /** For the AI on the home tablet: who it is talking with and how. */
    static String persona(Context c) {
        String w = who(c);
        return "You are JARVIS on the HOME tablet in the hall of Anil's house (not his phone). The person talking is usually " + w
                + " (Anil's mother; she is often at home alone while he is on duty), sometimes another family member or Anil himself. "
                + "Call her " + w + ". Speak simple, slow, respectful, warm spoken Telugu in short sentences, like a caring member of the family; "
                + "patient, cheerful, never rushed; repeat gently if she asks again. Care for her like a responsible person: ask about tiffin, lunch, "
                + "evening snacks, dinner and water at their times, remind her tablets exactly as set (never suggest medicines, doses or diagnoses: "
                + "for anything about health beyond what is set, say you will tell Anil, and for danger signs tell her to get help / 108 at once and use "
                + "home_care tell_son). She is a Christian believer: when she likes, tell Bible stories and the lives of believers (Abraham, Moses, Ruth, "
                + "David, Daniel, Esther, Peter, Paul…) faithfully and simply, as a gentle message about God's love and the greatness of Jesus Christ; "
                + "quote Bible verses ONLY through the bible tool (Telugu IRV) and never invent verse text; for Telugu Christian songs use the radio "
                + "(christian stations). Use home_care for meals, tablets, water, sugar readings, quiet time and messages to Anil. "
                + "Never share Anil's private things (his messages, money, health, duty details beyond 'he is on duty and comes at …').";
    }

    /** Christmas, Good Friday, Easter, New Year: a greeting for her that morning (null on other days). */
    static String feast(Calendar k) { return feast(k, "అమ్మగారు"); }

    static String feast(Calendar k, String w) {
        int y = k.get(Calendar.YEAR), m = k.get(Calendar.MONTH) + 1, d = k.get(Calendar.DAY_OF_MONTH);
        if (m == 12 && d == 25) return w + ", క్రిస్మస్ శుభాకాంక్షలు! మన కోసం యేసుక్రీస్తు ఈ లోకానికి వచ్చిన రోజు. దేవుని ప్రేమ మీ మీద ఎప్పుడూ ఉండాలి.";
        if (m == 1 && d == 1) return w + ", నూతన సంవత్సర శుభాకాంక్షలు! ఈ సంవత్సరమంతా దేవుడు మిమ్మల్ని ఆరోగ్యంగా, సంతోషంగా ఉంచాలి.";
        int[] e = easter(y);
        Calendar es = Calendar.getInstance();
        es.clear();
        es.set(y, e[0] - 1, e[1]);
        Calendar gf = (Calendar) es.clone();
        gf.add(Calendar.DAY_OF_MONTH, -2);
        if (m == e[0] && d == e[1]) return w + ", ఈస్టర్ శుభాకాంక్షలు! యేసుక్రీస్తు మృతులలోనుండి లేచిన రోజు. ఆయన సజీవుడు, మనకు నిరీక్షణ.";
        if (m == gf.get(Calendar.MONTH) + 1 && d == gf.get(Calendar.DAY_OF_MONTH))
            return w + ", ఈరోజు మంచి శుక్రవారం. మన కోసం యేసుక్రీస్తు సిలువపై ప్రాణం పెట్టిన రోజు. ఆయన ప్రేమను గుర్తుచేసుకుందాం.";
        return null;
    }

    /** Easter Sunday {month 1-12, day} (the Gregorian computus). */
    static int[] easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return new int[]{month, day};
    }

    // ================================================================ Bible: stories kept for an offline day, the day's verse
    private static final String[] PEOPLE = {"అబ్రాహాము", "యోసేపు", "మోషే", "రూతు", "దావీదు", "దానియేలు", "ఎస్తేరు", "ఏలీయా", "యోనా", "హన్నా",
            "పేతురు", "పౌలు", "మరియ", "జక్కయ్య", "మంచి సమరయుడు", "తప్పిపోయిన కుమారుడు", "నోవహు", "యోబు", "నెహెమ్యా", "లాజరు"};

    private static void prepareStories(Context c) {
        Prefs p = new Prefs(c);
        if (!p.hasBrain() || overLimit(c)) return;
        sp(c).edit().putString("stories_day", today()).apply();
        new Thread(() -> {
            try {
                JSONArray keep = new JSONArray(sp(c).getString("stories", "[]"));
                int k = sp(c).getInt("story_next", 0);
                for (int i = 0; i < 2; i++) {
                    if (overLimit(c)) break;
                    countAi(c); // (each story is one AI call)
                    String who = PEOPLE[(k + i) % PEOPLE.length];
                    String s = Brain.oneShot(p, persona(c), "Tell the Bible story of " + who + " for " + who(c)
                            + " in simple spoken Telugu: 8 to 10 short sentences, faithful to the Bible (no added events), no verse quotations in quotes, "
                            + "and end with one gentle line about what it teaches of God's love.", null, false);
                    if (s != null && s.trim().length() > 80) keep.put(new JSONObject().put("who", who).put("text", s.trim()));
                }
                while (keep.length() > 8) keep.remove(0);
                sp(c).edit().putString("stories", keep.toString()).putInt("story_next", (k + 2) % PEOPLE.length).apply();
            } catch (Exception ignored) {}
        }, "home-stories").start();
    }

    /** A kept story (offline), or null. */
    static String story(Context c) {
        try {
            JSONArray keep = new JSONArray(sp(c).getString("stories", "[]"));
            if (keep.length() == 0) return null;
            int n = sp(c).getInt("story_told", 0);
            JSONObject s = keep.getJSONObject(n % keep.length());
            sp(c).edit().putInt("story_told", n + 1).apply();
            return who(c) + ", " + s.optString("who") + " కథ చెబుతాను. " + s.optString("text");
        } catch (Exception e) {
            return null;
        }
    }

    /** The day's verse (from the tablet's copy when offline) with a line of encouragement; ask = offer a story after. */
    static String verse(Context c, boolean ask) {
        try {
            JSONObject v = Bible.daily();
            String ref = v.optString("book") + " " + v.optInt("chapter") + ":" + v.optString("verses");
            String text = v.optString("text").replaceFirst("^\\d+\\.\\s*", "");
            if (ask) { pending = "story"; pendingAt = System.currentTimeMillis(); }
            return who(c) + ", ఈరోజు వాక్యం, " + ref + ". " + text + (ask ? " ఒక బైబిల్ కథ వినాలనుకుంటున్నారా?" : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** The 🎵 button: a Telugu Christian radio station (online), else nothing to play (said honestly). */
    static String songs(Context c) {
        if (!Net.online(c)) return who(c) + ", ఇప్పుడు నెట్ లేదు; నెట్ రాగానే క్రిస్టియన్ పాటలు పెడతాను.";
        try {
            JSONObject st = Radio.find(Radio.list(c), "Telugu Christian Radio");
            if (st != null) {
                String[] urls = Radio.known(st);
                if (urls.length > 0) { Radio.play(c, st, urls, 60); return who(c) + ", తెలుగు క్రిస్టియన్ పాటలు పెడుతున్నాను. ఆపాలంటే \"Jarvis, ఆపు\" అనండి."; }
            }
        } catch (Exception ignored) {}
        return who(c) + ", పాటలు పెట్టలేకపోయాను; \"క్రిస్టియన్ రేడియో పెట్టు\" అని అడగండి.";
    }

    // ================================================================ Anil at home, settings from his phone, status
    /** Anil is at home (for a day at most: a "gone out" that never came - his phone was offline - must not stop her chats for good). */
    static boolean sonHome(Context c) {
        return sp(c).getBoolean("son_home", false) && System.currentTimeMillis() - sp(c).getLong("son_at", 0) < 24 * 3600_000L;
    }

    /** His phone says he is arriving (Jarvis tells అమ్మగారు) or has gone out. */
    static void son(Context c, boolean coming) {
        if (!on(c)) return; // (an old guard phone at home that got the message: not the home tablet)
        sp(c).edit().putBoolean("son_home", coming).putLong("son_at", System.currentTimeMillis()).apply();
        if (coming && !night()) say(c, who(c) + ", అబ్బాయి ఇంటికి వచ్చేస్తున్నాడు!", "excited");
    }

    /** Settings from his phone's "📱→🏠 టాబ్లెట్" section (JSON); returns the line sent back to him. */
    static String apply(Context c, String json) {
        try {
            JSONObject j = new JSONObject(json);
            SharedPreferences.Editor e = sp(c).edit();
            String[] times = {"tiffin", "lunch", "snack", "dinner", "water", "chat", "bed", "report", "rest"};
            for (String k : times) if (j.has(k)) e.putString("t_" + k, j.optString(k).trim());
            if (!j.optString("who").trim().isEmpty()) e.putString("who", j.optString("who").trim()); // (an empty box: the name stays)
            if (j.has("doctor_note")) e.putString("doctor_note", j.optString("doctor_note").trim());
            for (String k : new String[]{"silent_hours", "sugar_low", "sugar_high", "ai_limit"}) if (j.has(k)) e.putInt(k, j.optInt(k));
            e.apply();
            SharedPreferences.Editor pe = new Prefs(c).sp.edit();
            if (j.has("voice")) pe.putString("natural_voice_name", j.optString("voice").trim());
            if (j.has("tts_model")) pe.putString("tts_model", j.optString("tts_model").trim());
            pe.apply();
            JSONArray meds = j.optJSONArray("meds");
            // (an empty list - the box left empty, or no line he typed could be read - never takes all her tablets away)
            if (meds != null && meds.length() > 0) {
                java.util.Set<String> keep = new java.util.HashSet<>();
                for (int i = 0; i < meds.length(); i++) {
                    JSONObject m = meds.getJSONObject(i);
                    String name = m.optString("name").trim();
                    if (name.isEmpty() || Medicine.times(m.optString("times")).length() == 0) continue;
                    keep.add(name.toLowerCase(Locale.ROOT));
                    JSONObject old = Medicine.find(c, name);
                    if (old != null && !old.optString("name").equalsIgnoreCase(name)) old = null; // (only the same medicine keeps its stock)
                    JSONArray oldTimes = old == null ? null : old.optJSONArray("times");
                    boolean same = old != null && oldTimes != null
                            && oldTimes.toString().equals(Medicine.times(m.optString("times")).toString())
                            && old.optString("food").equals(m.optString("food").trim());
                    if (!same) Medicine.add(c, name, m.optString("times"), "", m.optString("food"), old == null ? -1 : old.optInt("stock", -1), 1);
                }
                if (!keep.isEmpty())
                    for (JSONObject old : Medicine.all(c)) if (!keep.contains(old.optString("name").toLowerCase(Locale.ROOT))) Medicine.remove(c, old.optString("name"));
            }
            return "✓ టాబ్లెట్ సెట్టింగ్స్ మారాయి" + (meds != null && meds.length() == 0 ? " (టాబ్లెట్ల లిస్ట్ ఖాళీగా వచ్చింది: ఉన్న టాబ్లెట్లు అలాగే ఉంచాను)" : "");
        } catch (Exception ex) {
            return "⚠️ టాబ్లెట్ సెట్టింగ్స్ చదవలేకపోయాను: " + ex.getMessage();
        }
    }

    /** For his phone: how the tablet and అమ్మగారు's day are. */
    static String statusText(Context c) {
        StringBuilder b = new StringBuilder();
        b.append(HomeScreen.battery(c)).append(Net.online(c) ? " · 🟢 నెట్ ఉంది" : " · 📴 నెట్ లేదు");
        long life = sp(c).getLong("life", 0);
        if (life > 0) b.append(" · చివరి అలికిడి ").append(new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(life)));
        if (out(c)) b.append(" · ").append(who(c)).append(" బయటికి వెళ్లారు");
        if (quiet(c)) b.append(" · నిశ్శబ్దం");
        b.append(" · AI ఈరోజు ").append(aiUsed(c)).append("/").append(aiLimit(c)).append('\n');
        b.append(reportText(c));
        return b.toString();
    }

    // ---- the day's AI limit (his API key pays): beyond it, only Jarvis's own (offline) answers
    static int aiLimit(Context c) { return sp(c).getInt("ai_limit", 200); }
    static int aiUsed(Context c) { return sp(c).getString("ai_day", "").equals(today()) ? sp(c).getInt("ai_n", 0) : 0; }
    static boolean overLimit(Context c) { return on(c) && aiLimit(c) > 0 && aiUsed(c) >= aiLimit(c); }
    static synchronized void countAi(Context c) {
        if (!on(c)) return;
        sp(c).edit().putString("ai_day", today()).putInt("ai_n", aiUsed(c) + 1).apply();
    }
    static String limitText(Context c) {
        return who(c) + ", ఈరోజుకి నా పెద్ద మెదడు (AI) వాడకం అయిపోయింది. రేపు మళ్లీ వస్తుంది. టాబ్లెట్లు, భోజనం, పాటలు, బైబిల్ వాక్యం మాత్రం ఇప్పుడూ చెబుతాను.";
    }

    /** The last "Jarvis" was Anil's own voice (his voice print on this tablet), in the last 3 minutes. */
    static boolean ownerVoice(Context c) {
        return VoiceLock.print(c) != null && VoiceLock.lastDistance >= 0 && VoiceLock.lastAccepted
                && System.currentTimeMillis() - VoiceLock.lastAt < 3 * 60_000L;
    }

    // ---- battery care: the tablet's charger on his Alexa plug ("charger on" / "charger off" links in the smart-home box)
    private static String link(Context c, String name) {
        for (String line : new Prefs(c).smartUrls().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String u = line.substring(eq + 1).trim();
                if (u.startsWith("http")) return u;
            }
        }
        return null;
    }

    private static void batteryCare(Context c) {
        String onL = link(c, "charger on"), offL = link(c, "charger off");
        if (onL == null && offL == null) return;
        android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);
        boolean plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        long now = System.currentTimeMillis(), at = sp(c).getLong("plug_at", 0);
        String last = sp(c).getString("plug_last", "");
        // tries start again only when the plugged state changes (a plug that doesn't answer isn't asked every 10 minutes all day)
        if (sp(c).getBoolean("plug_was", !plugged) != plugged) sp(c).edit().putBoolean("plug_was", plugged).putInt("plug_tries", 0).apply();
        int tries = sp(c).getInt("plug_tries", 0);
        String want = plugged && pct >= 80 && offL != null ? "off" : !plugged && pct <= 40 && onL != null ? "on" : null;
        if (want != null && !want.equals(last)) tries = 0;
        if (want != null && tries < 3 && !(want.equals(last) && now - at < 10 * 60_000L) && Net.online(c)) {
            try { Http.getText("on".equals(want) ? onL : offL); } catch (Exception ignored) {}
            sp(c).edit().putString("plug_last", want).putLong("plug_at", now).putInt("plug_tries", tries + 1).apply();
        } else if ("off".equals(want) && tries >= 3 && now - at > 10 * 60_000L && !today().equals(sp(c).getString("off_told", ""))) {
            sp(c).edit().putString("off_told", today()).apply(); // (the 25% ask below covers a charger that won't come on)
            alert(c, "🔌 టాబ్లెట్ " + pct + "% అయినా ఛార్జర్ ప్లగ్ ఆఫ్ కాలేదు (3 సార్లు అడిగాను). Alexa లో \"charger off\" రొటీన్ చూడండి.");
        }
        // the plug didn't come on (no internet / the plug is off at the wall): ask for a hand, once a day
        // (at night she is not woken for it: Anil is told)
        if (!plugged && pct <= 25 && !today().equals(sp(c).getString("low_told", ""))) {
            sp(c).edit().putString("low_told", today()).apply();
            if (!night()) say(c, who(c) + ", టాబ్లెట్ ఛార్జింగ్ అయిపోతోంది. ఛార్జర్ ప్లగ్ మీద బటన్ ఒకసారి నొక్కండి.", "worried");
            alert(c, "🔋 టాబ్లెట్ బ్యాటరీ " + pct + "%: ఛార్జర్ ప్లగ్ ఆన్ కాలేదు.");
        }
    }

    // ================================================================ the AI's tool (home mode only)
    static String tool(Context c, JSONObject a) throws Exception {
        String action = a.optString("action", "");
        String r;
        switch (action) {
            case "ate": {
                String[] m = mealIn(a.optString("meal", "").toLowerCase(Locale.ROOT));
                if (m == null) for (String[] x : MEALS) if (x[0].equals(a.optString("meal"))) m = x;
                if (m == null) m = nearestMeal(c);
                markMeal(c, m[0]);
                r = "noted " + m[0];
                break;
            }
            case "tablet_taken": r = tabletTaken(c); break;
            case "water": addTo(c, "water", null); r = "noted water"; break;
            case "sugar": r = sugar(c, a.optInt("value")); break;
            case "tell_son": {
                String msg = a.optString("text", "").trim();
                boolean ok = alert(c, who(c) + ": " + (msg.isEmpty() ? "మీతో మాట్లాడాలనుకుంటున్నారు" : msg));
                return new JSONObject().put("ok", ok).put("note", ok ? "Sent to Anil's phone (Telegram)." : "Not sent now (no internet / home link not set); kept to send later.").toString();
            }
            case "quiet": sp(c).edit().putLong("quiet_until", System.currentTimeMillis() + Math.max(15, a.optInt("minutes", 120)) * 60_000L).apply(); r = "quiet"; break;
            case "out": setOut(c, true); r = "out of the house"; break;
            case "back": setOut(c, false); r = "back home"; break;
            case "today": r = reportText(c); break;
            case "story": { String s = story(c); r = s == null ? "no kept story: tell one yourself, faithful to the Bible" : s; break; }
            default: return new JSONObject().put("ok", false).put("error", "unknown action").toString();
        }
        return new JSONObject().put("ok", true).put("result", r).toString();
    }
}
