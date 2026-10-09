package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Phase 3 on the watch: its screens ask the phone for what to show (P_ASK -> P_PANEL) and its buttons ask the phone to
 * do things (P_DO): the status (W22), the duty with its bag and handover notes (W24), habits and missions to tick (W34),
 * the home's lights and fans (W35, his saved Alexa routine links), the cooker count (W33) and the morning (W23).
 * A timer asked on the watch runs on the watch (P_TIMER). Everything here is quick and needs no AI. Phase 4: the body
 * scan (W25), today's walking (W31) and the breathing done (W30).
 */
final class WatchDo {
    private WatchDo() {}

    // watch -> phone
    static final String P_ASK = "/jarvis/ask", P_DO = "/jarvis/do";
    // phone -> watch
    static final String P_PANEL = "/jarvis/panel", P_TIMER = "/jarvis/timer";

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The watch opened a screen: its data (off the main thread). */
    static void ask(Context app, JSONObject o) {
        new Thread(() -> {
            try { WatchHub.send(app, P_PANEL, panel(app, o.optString("kind"))); }
            catch (Exception e) { toast(app, "ఫోన్‌లో చూడలేకపోయాను: " + e.getMessage()); }
        }, "watch-ask").start();
    }

    static JSONObject panel(Context c, String kind) throws Exception {
        switch (kind) {
            case "status": return Status.json(c);
            case "nav": return Travel.navPanel(c);   // W52 / W51: his places for the compass
            case "here": return Travel.herePanel(c); // the compass: where he is now (the phone's GPS)
            case "music": return Music.panel(c);     // W50: what plays on the phone
            case "radio": return Music.radioPanel(c); // W36: his stations
            case "water": return Day5.waterPanel(c);   // W70
            case "money": return Day5.moneyPanel(c);   // W82 (counted here, never sent to any AI)
            case "protocols": {                         // W58
                JSONObject a = Protocols.all(c);
                JSONArray names = new JSONArray();
                for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) names.put(it.next());
                return new JSONObject().put("kind", "protocols").put("names", names);
            }
            case "journey": return new JSONObject().put("kind", "journey").put("on", Journey.on(c)); // W81
            case "duty": return duty(c);
            case "tasks": return tasks(c);
            case "home": return home(c);
            case "cooker": {
                JSONObject k = Sounds.cookerStatus(c);
                return new JSONObject().put("kind", "cooker").put("counting", Sounds.cookerActive(c))
                        .put("heard", k.optInt("whistles_heard")).put("target", k.optInt("target"));
            }
            default: return new JSONObject().put("kind", "toast").put("text", "ఈ స్క్రీన్ ఈ ఫోన్ Jarvis లో లేదు: ఫోన్ యాప్ అప్‌డేట్ చేయండి.");
        }
    }

    private static JSONObject duty(Context c) throws Exception {
        JSONObject o = new JSONObject().put("kind", "duty");
        Duty.Roster r = Duty.load(c);
        if (!Duty.ready(r)) return o.put("set", false);
        o.put("set", true);
        LocalDateTime[] d = Duty.nowOrNext(c);
        if (d != null) {
            o.put("start", d[0].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).put("end", d[1].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .put("on", !LocalDateTime.now().isBefore(d[0])).put("when", Offline.sayWhen(d[0], LocalDateTime.now()));
        }
        o.put("leave", r.leaveTime(r.timeOf(Duty.ME))).put("time", r.timeOf(Duty.ME));
        JSONArray bag = new JSONArray();
        for (String x : Duty.checklist(c).split("\\s*,\\s*")) if (!x.trim().isEmpty()) bag.put(x.trim());
        o.put("bag", bag);
        o.put("notes", new JSONArray(Plans.notes(c)));
        try {
            JSONObject rep = Duty.report(c, r, java.time.YearMonth.now());
            o.put("month", rep.optString("month") + ": " + rep.optInt("duty_days") + " రోజులు డ్యూటీ");
        } catch (Exception ignored) {}
        o.put("mode", DutyMode.on(c));
        return o;
    }

    private static JSONObject tasks(Context c) throws Exception {
        JSONArray h = new JSONArray();
        String today = LocalDate.now().toString();
        for (JSONObject x : list(Everyday.habitsJson(c)))
            h.put(new JSONObject().put("name", x.optString("name")).put("done", x.optBoolean("done_today")).put("streak", x.optInt("streak_days")));
        JSONArray m = new JSONArray();
        for (JSONObject x : Store.get(c).missions()) if (!x.optBoolean("done")) m.put(new JSONObject().put("id", x.optString("id")).put("text", x.optString("text")));
        JSONArray buy = new JSONArray();
        try {
            JSONArray b = Shopping.listJson(c).optJSONArray("to_buy");
            for (int i = 0; b != null && i < b.length() && i < 20; i++) buy.put(b.optString(i));
        } catch (Exception ignored) {}
        return new JSONObject().put("kind", "tasks").put("habits", h).put("missions", m).put("buy", buy).put("day", today);
    }

    private static List<JSONObject> list(JSONArray a) {
        List<JSONObject> l = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(o); }
        return l;
    }

    /** His saved smart-home commands ("హాల్ లైట్ ఆన్" = an Alexa routine link). */
    static List<String[]> smart(Context c) {
        List<String[]> out = new java.util.ArrayList<>();
        for (String line : new Prefs(c).smartUrls().split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim(), url = line.substring(eq + 1).trim();
            if (!name.isEmpty() && url.startsWith("http")) out.add(new String[]{name, url});
        }
        return out;
    }

    private static JSONObject home(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (String[] s : smart(c)) a.put(s[0]);
        return new JSONObject().put("kind", "home").put("cmds", a).put("online", Net.online(c));
    }

    /** A button on the watch (off the main thread). */
    static void act(Context app, JSONObject o) {
        new Thread(() -> {
            try { run(app, o); } catch (Exception e) { toast(app, "చేయలేకపోయాను: " + e.getMessage()); }
        }, "watch-do").start();
    }

    private static void run(Context app, JSONObject o) throws Exception {
        String what = o.optString("what");
        switch (what) {
            case "morning": {
                WatchHub.state(app, "thinking", null, null, "☀️ ఈరోజు సంగతులు చూస్తున్నాను…"); // (the watch knows it is coming)
                String t = Morning.text(app);
                main.post(() -> WatchHub.say(app, t));
                return;
            }
            case "scan": { // W25: the body scan (the heart rate just read on the watch, with his day)
                WatchHub.state(app, "thinking", null, null, "🩺 మీ రోజు చూస్తున్నాను…");
                String t = Wellness.scan(app, o.optInt("bpm"));
                main.post(() -> WatchHub.say(app, t));
                return;
            }
            case "walk_today": { // W31: "🚶 నడక": today's steps, metres / km, walks
                WatchHub.state(app, "thinking", null, null, "🚶 ఈరోజు నడక చూస్తున్నాను…");
                WatchHealth.daySteps(app, o);
                String t = Wellness.walkToday(app);
                main.post(() -> WatchHub.say(app, t));
                return;
            }
            case "nav_phone": { // W51 / W62: the walking route on the phone (the watch has no Maps, or he asked)
                double lat = o.optDouble("lat"), lon = o.optDouble("lon");
                android.content.Intent i = Life.walkTo(lat, lon).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!WatchHub.phoneIdle(app) || android.provider.Settings.canDrawOverlays(app)) {
                    try { app.startActivity(i); toast(app, "📱 ఫోన్‌లో దారి తెరిచాను"); return; } catch (Exception ignored) {}
                }
                ShowOnPhone.note(app, "🗺️ " + o.optString("name", "దారి"), "నొక్కితే Google Maps లో నడక దారి తెరుస్తుంది", i);
                toast(app, "📱 ఫోన్ లాక్‌లో ఉంది: నోటిఫికేషన్ నొక్కితే దారి తెరుస్తుంది");
                return;
            }
            case "media": { // W50: the phone's music from the watch
                String r = Music.control(app, o.optString("action"));
                Thread.sleep(400); // (the app updates what it shows)
                WatchHub.send(app, P_PANEL, Music.panel(app).put("toast", r));
                return;
            }
            case "song": { // "ఈ పాట ఏది?"
                String t = Music.nowLine(app);
                main.post(() -> WatchHub.say(app, t));
                return;
            }
            case "sleep_timer": toast(app, Music.sleepAfter(app, o.optInt("minutes", 30))); return; // O46
            case "radio_watch": { // W36: the watch plays it itself
                String r = Music.radioOnWatch(app, o.optString("name"));
                if (!r.isEmpty()) toast(app, r);
                return;
            }
            case "radio_phone": toast(app, Music.radioOnPhone(app, o.optString("name"))); return;
            case "radio_tap": // W36: Android held the watch's radio back (started from the background): one tap on the wrist
                Reminders.notify(app, "📻 వాచ్ రేడియో", "వాచ్‌లో వచ్చిన 📻 నోటిఫికేషన్ నొక్కితే మొదలవుతుంది.", 1077);
                return;
            case "show_phone": toast(app, ShowOnPhone.lastAnswer(app)); return; // W62
            case "water": { // W70
                String r = Day5.waterAdd(app, o.optInt("n", 1));
                WatchHub.send(app, P_PANEL, Day5.waterPanel(app).put("toast", r));
                return;
            }
            case "mood": Day5.mood(app, o.optString("m")); return; // W72
            case "focus": toast(app, Day5.focusStart(app, o.optInt("min", 25), o.optString("what"))); return; // W73
            case "focus_stop": toast(app, Day5.focusEnd(app)); return;
            case "find_phone": FindPhone.start(app); toast(app, "📱 ఫోన్ మోగుతోంది"); return; // W66
            case "nap": toast(app, Day5.nap(app, o.optInt("min", 20))); return; // W67
            case "debt": { String t = Day5.sleepDebtText(app); main.post(() -> WatchHub.say(app, t)); return; }
            case "smart_wake": Day5.smartWake(app, o.optString("id")); return; // W68: he stirred in light sleep
            case "reps": { // W71: an exercise counted on the watch
                Notes.add(app, "reps", new JSONObject().put("t", System.currentTimeMillis()).put("name", o.optString("name")).put("count", o.optInt("count")), 200);
                toast(app, "✓ " + o.optString("name") + " " + o.optInt("count") + " సార్లు రాశాను");
                return;
            }
            case "protocol": { // W58
                WatchHub.state(app, "thinking", null, null, "⚡ " + o.optString("name") + "…");
                String t = Protocols.run(app, o.optString("name"));
                main.post(() -> WatchHub.say(app, t));
                return;
            }
            case "journey": { // W81
                String t = o.optBoolean("on") ? Journey.start(app, o.optString("place"), o.optInt("min")) : Journey.stop(app, false);
                WatchHub.send(app, P_PANEL, new JSONObject().put("kind", "journey").put("on", Journey.on(app)).put("toast", t.length() > 90 ? t.substring(0, 90) + "…" : t));
                return;
            }
            case "sos": CrashAlert.sosNow(app, "వాచ్‌లో 🆘 నొక్కారు, సహాయం కావాలి"); return; // W40 (after the watch's own 5 seconds)
            case "camera": { // W38 / W57: the phone's camera looks (the phone on a stand), the answer comes here
                if (app.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    toast(app, "ఫోన్‌లో Jarvis కి కెమెరా అనుమతి లేదు."); return;
                }
                android.app.KeyguardManager km = app.getSystemService(android.app.KeyguardManager.class);
                if (km != null && km.isKeyguardLocked()) { toast(app, "📷 ఫోన్ లాక్‌లో ఉంది: అన్‌లాక్ చేసి స్టాండ్‌లో పెట్టి మళ్లీ నొక్కండి."); return; }
                JarvisCamera.fromWatch = true;
                String q = o.optString("ask", "ఇది ఏమిటి? క్లుప్తంగా చెప్పు.");
                Intent ci = new Intent(app, JarvisCamera.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(JarvisCamera.EXTRA_MODE, o.optString("mode", "auto"))
                        .putExtra(JarvisCamera.EXTRA_ASK, q);
                try { app.startActivity(ci); toast(app, "📷 ఫోన్ కెమెరా చూస్తోంది… జవాబు ఇక్కడ వస్తుంది"); }
                catch (Exception e) { JarvisCamera.fromWatch = false; toast(app, "ఫోన్‌లో కెమెరా తెరవలేకపోయాను: " + e.getMessage()); }
                return;
            }
            case "fall_ok": CrashAlert.ok(app); return;          // W42: "బాగున్నాను" on the wrist
            case "fall_send": // W42: "సహాయం" on the wrist (the phone's check may be over, or never began: the SOS all the same)
                if (CrashAlert.active) CrashAlert.send(app, true); else CrashAlert.sosNow(app, "వాచ్‌లో 'సహాయం కావాలి' నొక్కారు (పడిపోయినట్టు వాచ్ గుర్తించింది)");
                return;
            case "breathed": // W30: the breathing done on the watch (for his week)
                Wellness.breathed(app, o);
                return;
            case "habit": {
                JSONObject h = Everyday.mark(app, o.optString("name"), LocalDate.now(), o.optBoolean("done", true));
                if (h == null) { toast(app, "ఆ అలవాటు దొరకలేదు."); return; }
                WatchHub.send(app, P_PANEL, tasks(app));
                return;
            }
            case "mission": {
                JSONObject m = Store.get(app).setMissionDone(o.optString("id"), true);
                if (m == null) { toast(app, "ఆ మిషన్ దొరకలేదు."); return; }
                WatchHub.send(app, P_PANEL, tasks(app).put("toast", "✓ " + m.optString("text")));
                return;
            }
            case "bought": {
                Shopping.mark(app, o.optString("item"), true);
                WatchHub.send(app, P_PANEL, tasks(app));
                return;
            }
            case "smart": {
                String name = o.optString("name");
                for (String[] s : smart(app)) {
                    if (!s[0].equals(name)) continue;
                    if (!Net.online(app)) { toast(app, "ఫోన్‌కి నెట్ లేదు: లైట్లు మార్చలేను."); return; }
                    try {
                        Http.getText(s[1]);
                        toast(app, "✓ " + name);
                    } catch (Exception e) {
                        toast(app, name + ": Alexa లింక్ స్పందించలేదు (" + e.getMessage() + ")");
                    }
                    return;
                }
                toast(app, "ఆ కమాండ్ ఫోన్‌లో లేదు.");
                return;
            }
            case "cooker": {
                JSONObject r = Sounds.startCooker(app, o.optInt("n", 3));
                if (!r.optBoolean("ok")) { toast(app, "ఫోన్‌లో \"Jarvis\" వేక్ వర్డ్ మైక్ ఆఫ్‌లో ఉంది: అది ఆన్ చేస్తేనే విజిల్స్ వినగలను."); return; }
                WatchHub.send(app, P_PANEL, panel(app, "cooker").put("toast", "🍲 " + r.optInt("counting") + " విజిల్స్ లెక్కపెడుతున్నాను. ఫోన్ వంటింట్లో ఉంచండి."));
                return;
            }
            case "cooker_stop": {
                Sounds.stopCooker(app);
                WatchHub.send(app, P_PANEL, panel(app, "cooker").put("toast", "సరే, కుక్కర్ లెక్క ఆపాను."));
                return;
            }
            case "duty_mode": {
                String s = o.optBoolean("on") ? DutyMode.start(app, false) : DutyMode.off(app, false);
                WatchHub.send(app, P_PANEL, duty(app).put("toast", s));
                return;
            }
            default: toast(app, "ఈ బటన్ ఈ ఫోన్ Jarvis లో లేదు: ఫోన్ యాప్ అప్‌డేట్ చేయండి.");
        }
    }

    static void toast(Context app, String text) {
        try { WatchHub.send(app, P_PANEL, new JSONObject().put("kind", "toast").put("text", text)); } catch (Exception ignored) {}
    }
}
