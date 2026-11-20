package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🏆 (20 Nov) A showpiece on the workshop's stage: its power bar (and a bike's grip, clutch, gears and brake), its ₹0 words
 * (power, heat, airflow, sound, slow-motion, hologram, lights, layers, the inside of a piece, size beside a person, hot day /
 * AC / hill / night runway, his per-unit rate, poster, photo, film), its games (quiz, find the fault, the assembly puzzle,
 * challenges), its service guide, and its ₹0 answers. Jarvis never speaks because a hand touched something: a tap shows the
 * piece's card quietly; only a double pinch or a question makes him explain. The stage's sound goes quiet while Jarvis talks
 * or listens, and silent during a phone call.
 */
final class WorkshopShow {
    /** What the workshop screen gives this. */
    interface Host {
        Activity screen();
        void send(JSONObject m);
        void speak(String s, boolean thenListen);
        void status(String s);
        void answer(String title, String text, String aboutId);
        void card(String title, String text);
        void command(String cmd, boolean byVoice);
        String partCode(String id);
        void replacePart(String id, String code);
        String picked();
        void askFile(String what, JSONObject msg);
        boolean handsOn();
    }

    private static volatile WorkshopShow live;
    private final Host h;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    Showpiece sp;
    private Showpiece.Quiz quiz;
    private Showpiece.Faults faults;
    private Showpiece.Challenge chal;
    private boolean puzzleOn, listenNext, controlsShown = true, timelineOn, speaking, calling, hasPower = true;
    private long puzzleT0;
    private double power, rate;
    private int gear = 1;
    private JSONObject rows, nums;
    private final List<Runnable> chainLeft = new ArrayList<>();

    // the panel
    private LinearLayout panel, bikeRow, powerRow, strip;
    private TextView stripText;
    private String stripLabel;
    private SeekBar powerBar;
    private TextView powerName, powerVal, gearView, clutchBtn, brakeBtn;
    private boolean dragging;

    WorkshopShow(Host h) {
        this.h = h;
        this.prefs = h.screen().getSharedPreferences(Workshop.PREFS, Context.MODE_PRIVATE);
    }

    boolean open() { return sp != null; }

    // ================================================================ on / off

    /** A showpiece just went onto the stage. */
    void start(Showpiece s) {
        stop();
        sp = s;
        live = this;
        sp.deep = prefs.getBoolean("sp_deep", false);
        rate = prefs.getFloat("sp_rate", Bike.unitRate(new Prefs(h.screen())));
        power = 0;
        gear = 1;
        hasPower = true;
        try {
            h.send(new JSONObject().put("t", "env").put("rate", rate).put("gear", 1).put("clutch", 0).put("brake", 0));
            h.send(new JSONObject().put("t", "sound").put("on", prefs.getBoolean("sp_sound", true)).put("duck", 1));
        } catch (Exception ignored) {}
        controlsShown = true;
        refreshPanel();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1200);
    }

    /** The stage has something else now (or the screen closes). */
    void stop() {
        if (sp == null) return;
        endGames(false);
        sp = null;
        if (live == this) live = null;
        ui.removeCallbacks(tick);
        chainLeft.clear();
        timelineOn = false;
        stripLabel = null;
        if (strip != null) strip.setVisibility(View.GONE);
        refreshPanel();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (sp == null) return;
            boolean c = CallCare.inCall(h.screen());
            if (c != calling) { calling = c; duck(); } // (📞 nothing is heard from the stage during a call)
            try { h.send(new JSONObject().put("t", "envask")); } catch (Exception ignored) {}
            ui.postDelayed(this, chal != null ? 500 : 1000);
        }
    };

    /** Jarvis talks or listens (or the phone rings): the machine's sound steps back. */
    void voiceState(boolean talking) { speaking = talking; duck(); }

    private void duck() {
        if (sp == null) return;
        try { h.send(new JSONObject().put("t", "sound").put("duck", calling ? 0 : speaking ? 0.22 : 1)); } catch (Exception ignored) {}
    }

    /** The throttle now (0..1), or NaN when this showpiece has none (✊ the fist's throttle only works with one). */
    double power() { return sp != null && hasPower ? power : Double.NaN; }

    /** ✊ The fist raised or lowered: the power (no words; the gauge shows it). */
    void handPower(double v) { if (sp != null) setPower(Math.max(0, Math.min(1, v)), false, false); }

    /** ⌚ (I) The watch's bezel turned: the power. */
    static void watchPower(double v) {
        WorkshopShow w = live;
        if (w == null || w.sp == null) return;
        w.ui.post(() -> { if (w.sp != null) w.setPower(Math.max(0, Math.min(1, v)), false, false); });
    }

    // ================================================================ his words (₹0)

    /** What he said, when it is for the showpiece: done here (true). Else the workshop's usual way (false). */
    boolean said(String text, boolean byVoice) { return said(text, byVoice, false); }

    /**
     * strict: heard by voice over this screen, where anything not for the model goes to Jarvis's brain instead: a fact or a
     * live number only when the words name the machine or a piece of it ("ఈ నెల ఖర్చు ఎంత" is not the bike's ₹), and no home
     * lights or the home AC taken for the stage's.
     */
    boolean said(String text, boolean byVoice, boolean strict) {
        if (sp == null || text == null) return false;
        String t = Workshop.norm(text);
        if (t == null || t.isEmpty()) return false;
        boolean playing = quiz != null || faults != null || chal != null || puzzleOn;
        if (playing && (Workshop.isCancel(text) || t.contains("ఆట ఆపు") || t.contains("క్విజ్ ఆపు") || t.contains("పజిల్ ఆపు") || t.contains("ఛాలెంజ్ ఆపు")
                || t.contains("stop game") || t.contains("stop quiz"))) { endGames(true); return true; }
        boolean order = sp.parse(text) != null || Workshop.command(text) != null; // ("ఫుల్ పవర్" in a quiz is an order, not an answer)
        if (quiz != null && !order) {
            Object[] r = quiz.check(text, null);
            if (r != null) { quizAnswered(r, byVoice); return true; }
        }
        if (faults != null && !order) {
            String g = faults.guess(text, null);
            if (g != null) { faultGuessed(g, byVoice); return true; }
        }
        List<String> chain = sp.chain(text);
        if (chain != null) { runChain(chain, byVoice); return true; }
        Showpiece.Act a = sp.parse(text);
        if (a != null && strict && !forStage(a, t)) a = null;
        if (a != null) { run(a, byVoice); return true; }
        if (strict && !sp.named(t)) return false;
        String raw = text.toLowerCase(Locale.ROOT);
        boolean now = raw.contains("ఇప్పుడు") || raw.contains(" now") || raw.contains("ప్రస్తుతం");
        String live1 = now ? Showpiece.liveRow(text, rows) : null;
        if (live1 != null) { sayLive(live1, byVoice); return true; }
        String ans = sp.answer(text, h.picked());
        if (ans != null) {
            String lab = sp.labelIn(t);
            h.answer(lab != null ? lab : sp.title, ans, null);
            h.status("❓ ₹0 · " + (lab != null ? lab : sp.title));
            h.speak(ans, true);
            return true;
        }
        String live2 = Showpiece.liveRow(text, rows);
        if (live2 != null) { sayLive(live2, byVoice); return true; }
        return false;
    }

    /** Heard by voice: lights and the AC are the stage's only when said so ("rgb", "40 డిగ్రీలు", "ఏసీ గది"). */
    private static boolean forStage(Showpiece.Act a, String t) {
        if ("rgb".equals(a.k)) return t.contains("rgb") || t.contains("ఆర్జీబీ") || t.contains("రెయిన్") || t.contains("rainbow") || t.contains("బ్రీత్");
        if ("cond".equals(a.k) && ("ac".equals(a.s) || "hot".equals(a.s))) return t.contains("డిగ్రీ") || t.contains("గది") || t.contains("డే") || t.contains("day") || t.contains("room");
        return true;
    }

    private void sayLive(String row, boolean byVoice) {
        String v = rows == null ? "" : rows.optString(row);
        String s = row + " ఇప్పుడు " + v + " Sir.";
        h.status("📟 " + row + ": " + v);
        h.speak(s, true);
    }

    private void runChain(List<String> parts, boolean byVoice) {
        chainLeft.clear();
        h.status("⛓️ " + parts.size() + " పనులు ఒకదాని తర్వాత ఒకటి · ₹0");
        long at = 0;
        for (String p : parts) {
            final String one = p;
            Runnable r = () -> {
                if (sp == null) return;
                Showpiece.Act a = sp.parse(one);
                if (a != null) run(a, false);
                else { String c = Workshop.command(one); if (c != null) h.command(c, false); }
            };
            chainLeft.add(r);
            ui.postDelayed(() -> { if (chainLeft.remove(r)) r.run(); }, at);
            at += 1600;
        }
        if (byVoice) h.speak("సరే Sir, " + parts.size() + " పనులు వరుసగా చేస్తున్నాను.", false);
    }

    /** One ₹0 thing. */
    void run(Showpiece.Act a, boolean byVoice) {
        if (sp == null) return;
        try {
            switch (a.k) {
                case "power": setPower(a.v, true, byVoice); break;
                case "power_more": setPower(Math.min(1, power + 0.2), true, byVoice); break;
                case "power_less": setPower(Math.max(0, power - 0.2), true, byVoice); break;
                case "over": {
                    h.send(new JSONObject().put("t", "power").put("v", 1.15));
                    String l = sp.line("over");
                    h.status("🚨 " + l);
                    h.speak(l, false);
                    break;
                }
                case "heat": onOff("heat", a.v > 0, "🌡️ వేడి చూపిస్తున్నాను Sir: ఎరుపు, పసుపు వేడి; నీలం చల్లగా. పవర్ పెంచితే రంగులు మారతాయి.", "వేడి చూపు ఆపాను Sir."); break;
                case "flow": onOff("flow", a.v > 0, "〰️ ప్రవాహాలు చూపిస్తున్నాను Sir: నీలం చల్లగా, నారింజ వేడిగా. పవర్ పెంచితే వేగం పెరుగుతుంది.", "ప్రవాహాలు ఆపాను Sir."); break;
                case "holo": onOff("holo", a.v > 0, "💠 హోలోగ్రామ్ Sir.", "మామూలు రంగులు Sir."); break;
                case "sound": {
                    boolean on = a.v > 0;
                    prefs.edit().putBoolean("sp_sound", on).apply();
                    h.send(new JSONObject().put("t", "sound").put("on", on));
                    say(on ? "🔊 శబ్దం ఆన్ Sir." : "🔇 శబ్దం ఆపాను Sir.", byVoice);
                    break;
                }
                case "listen": {
                    String p = h.picked();
                    if (p != null) h.send(new JSONObject().put("t", "listen").put("label", p));
                    else { listenNext = true; say("🩺 ఏ భాగం శబ్దం వినాలో దాన్ని తాకండి Sir.", byVoice); }
                    break;
                }
                case "speed": {
                    h.send(new JSONObject().put("t", "speed").put("v", a.v));
                    say(a.v == 0 ? "⏸ ఆపి ఉంచాను Sir. 'మామూలు వేగం' అంటే మళ్ళీ కదులుతుంది." : a.v < 1 ? "🐢 స్లో మోషన్ Sir: నాలుగు రెట్లు నెమ్మదిగా." : "▶️ మామూలు వేగం Sir.", byVoice);
                    break;
                }
                case "rgb": {
                    h.send(new JSONObject().put("t", "rgb").put("mode", a.s));
                    say(a.s.isEmpty() ? "🎨 లైట్లు మామూలుగా Sir." : "rainbow".equals(a.s) ? "🌈 రెయిన్‌బో లైట్లు Sir." : "breathe".equals(a.s) ? "🎨 లైట్లు ఊపిరి పీల్చినట్టు Sir." : "🎨 లైట్లు ఆ రంగులో Sir.", byVoice);
                    break;
                }
                case "peel": h.send(new JSONObject().put("t", "peel").put("dir", a.v)); break;
                case "timeline": timeline(true); break;
                case "hx": {
                    boolean on = a.v > 0;
                    if (on && !h.handsOn()) h.command("hands_on", false);
                    h.send(new JSONObject().put("t", "hx").put("on", on));
                    say(on ? "🖐️ చేతిని బొమ్మ మీద కదపండి Sir: చేయి ఉన్న చోట లోపలి భాగాలు కనిపిస్తాయి." : "🖐️ చేతి కిటికీ ఆపాను Sir.", byVoice);
                    break;
                }
                case "inside": inside(a.s, byVoice); break;
                case "inside_off": h.send(new JSONObject().put("t", "inside").put("off", true)); break;
                case "buddy": {
                    h.send(new JSONObject().put("t", "buddy").put("kind", a.s));
                    say("person".equals(a.s) ? "🧍 పక్కన 170 సెం.మీ. మనిషిని నిలబెట్టాను Sir: సైజు పోల్చి చూడండి." : "coin".equals(a.s) ? "🪙 పక్కన ఒక రూపాయి నాణెం పెట్టాను Sir." : "పోలిక తీసేశాను Sir.", byVoice);
                    break;
                }
                case "cond": cond(a.s, byVoice); break;
                case "rate": {
                    rate = a.v;
                    prefs.edit().putFloat("sp_rate", (float) rate).apply();
                    h.send(new JSONObject().put("t", "env").put("rate", rate));
                    say("🔋 యూనిట్ ధర ₹" + trim(rate) + " తో లెక్కపెడతాను Sir (ఈ షోపీస్‌లో మాత్రమే; మీ బైక్ సెట్టింగ్ మారదు).", byVoice);
                    break;
                }
                case "poster": h.askFile("poster", new JSONObject().put("t", "poster").put("title", sp.title)); h.status("🖼️ పోస్టర్ తయారు చేస్తున్నాను…"); break;
                case "photomode": h.askFile("photomode", new JSONObject().put("t", "photo")); h.status("📸 అందమైన ఫోటో తీస్తున్నాను…"); break;
                case "film": {
                    JSONArray lines = sp.sp.optJSONArray("film");
                    h.askFile("video", new JSONObject().put("t", "film").put("title", sp.title).put("lines", lines == null ? new JSONArray() : lines));
                    say("🎬 చిన్న సినిమా తీస్తున్నాను Sir, సుమారు 25 సెకన్లు. అయ్యాక గ్యాలరీలో దాచుతాను.", byVoice);
                    break;
                }
                case "quiz": startQuiz(); break;
                case "faults": startFaults(); break;
                case "puzzle": startPuzzle(); break;
                case "hint": if (puzzleOn) h.send(new JSONObject().put("t", "hint")); else say("🧩 ముందు 'పజిల్' అనండి Sir.", byVoice); break;
                case "challenge": startChallenge(); break;
                case "service": service(); break;
                case "mode": {
                    sp.deep = "deep".equals(a.s);
                    prefs.edit().putBoolean("sp_deep", sp.deep).apply();
                    say(sp.deep ? "👨‍🔧 ఇకపై ఇంజనీర్‌లా లోతుగా చెప్తాను Sir." : "🧒 ఇకపై సింపుల్‌గా చెప్తాను Sir.", byVoice);
                    break;
                }
                case "swap": { String v = sp.nextVariant(); if (v == null) say("ఈ బొమ్మలో మార్చే భాగం లేదు Sir.", byVoice); else variant(v, byVoice); break; }
                case "variant": variant(a.s, byVoice); break;
                case "gear": setGear((int) a.v, byVoice); break;
                case "gear_up": setGear(gear == 0 ? 1 : Math.min(4, gear + 1), byVoice); break;
                case "gear_down": setGear(Math.max(gear == 1 ? 0 : 1, gear - 1), byVoice); break;
                case "clutch": h.send(new JSONObject().put("t", "env").put("clutch", a.v)); say(a.v > 0 ? "క్లచ్ నొక్కాను Sir." : "క్లచ్ వదిలాను Sir.", byVoice); break;
                case "brake": h.send(new JSONObject().put("t", "env").put("brake", a.v)); say(a.v > 0 ? "బ్రేక్ వేశాను Sir." : "బ్రేక్ వదిలాను Sir.", byVoice); break;
                case "controls": controlsShown = !controlsShown; refreshPanel(); break;
                case "card": {
                    String p = h.picked();
                    String c = p == null ? null : sp.card(p);
                    if (c != null) h.answer("📋 " + p, c, null);
                    else h.answer("📋 " + sp.title, sp.sp.optString("line") + "\n\nఏ భాగం వివరాలు కావాలో దాన్ని తాకండి Sir.", null);
                    break;
                }
                case "bezel": {
                    boolean ok = Travel.openOnWatch(h.screen(), "bezel", sp.id);
                    say(ok ? "⌚ వాచ్‌లో పవర్ డయల్ తెరిచాను Sir: బెజెల్ తిప్పితే పవర్ మారుతుంది." : "⌚ వాచ్ దగ్గరలో లేదు Sir.", byVoice);
                    break;
                }
                case "menu": menu(); break;
                default: break;
            }
        } catch (Exception ignored) {}
    }

    private void say(String s, boolean byVoice) {
        h.status(s);
        h.speak(s, byVoice);
    }

    private void onOff(String kind, boolean on, String yes, String no) throws Exception {
        h.send(new JSONObject().put("t", kind).put("on", on));
        h.status(on ? yes : no);
        h.speak(on ? yes : no, false);
    }

    private void setPower(double v, boolean speak, boolean byVoice) {
        try { h.send(new JSONObject().put("t", "power").put("v", v)); } catch (Exception ignored) {}
        if (!hasPower) return; // (the stage answers "none": said there)
        power = Math.min(1, v);
        if (powerBar != null && !dragging) powerBar.setProgress((int) Math.round(power * 100));
        if (!speak) return;
        String key = v >= 0.95 ? "full" : v <= 0.001 ? "stop" : Math.abs(v - 0.5) < 0.06 ? "half" : v <= 0.35 ? "start" : "";
        String l = key.isEmpty() ? "" : sp.line(key);
        if (l.isEmpty()) l = "⚡ పవర్ " + Math.round(v * 100) + "% Sir.";
        h.status("⚡ " + Math.round(v * 100) + "% · " + l);
        h.speak(l, false);
        if (chal != null && "takeoff".equals(chal.kind) && chal.t0 < 0) chal.t0 = -1; // (the challenge's clock starts with the next numbers)
    }

    private void setGear(int g, boolean byVoice) throws Exception {
        if (!sp.bike()) { say("ఈ బొమ్మకి గేర్లు లేవు Sir.", byVoice); return; }
        gear = Math.max(0, Math.min(4, g));
        h.send(new JSONObject().put("t", "env").put("gear", gear));
        if (gearView != null) gearView.setText(gear == 0 ? "N" : String.valueOf(gear));
        h.status("⚙️ గేర్ " + (gear == 0 ? "న్యూట్రల్" : String.valueOf(gear)));
        if (byVoice) h.speak(gear == 0 ? "న్యూట్రల్ Sir." : gear + "వ గేర్ Sir.", false);
    }

    private void cond(String c, boolean byVoice) throws Exception {
        JSONObject m = new JSONObject().put("t", "env");
        String s;
        switch (c) {
            case "hot": m.put("ambient", 40); s = "🌞 ఎండాకాలం, 40°C గాలి Sir: అన్నీ కొంచెం ఎక్కువ వేడి, చల్లబరచడం కష్టం."; break;
            case "ac": m.put("ambient", 24); s = "❄️ ఏసీ గది, 24°C Sir: అన్నీ కొంచెం చల్లగా."; break;
            case "hill": m.put("slope", 0.08); s = "⛰️ ఘాట్ రోడ్డు, 8% ఎక్కుడు Sir: లాగడానికి ఎక్కువ పవర్, ఎక్కువ కరెంట్ కావాలి."; break;
            case "flat": m.put("slope", 0); s = "🛣️ చదునైన రోడ్డు Sir."; break;
            case "runway": m.put("scene", "runway"); s = "🌃 రాత్రి రన్‌వే Sir."; break;
            default: m.put("ambient", 30).put("slope", 0).put("scene", ""); s = "మామూలు వాతావరణం Sir: 30°C, చదునైన నేల."; break;
        }
        h.send(m);
        say(s, byVoice);
    }

    private void inside(String label, boolean byVoice) throws Exception {
        JSONObject ins = sp.sp.optJSONObject("inside");
        if (ins == null || ins.length() == 0) { say("ఈ బొమ్మకి లోపలి చూపు లేదు Sir.", byVoice); return; }
        String lab = label == null || label.isEmpty() ? h.picked() : label;
        if (lab == null || (!ins.has(lab) && !ins.has(sp.nowName(lab)))) {
            List<String> have = new ArrayList<>();
            for (java.util.Iterator<String> it = ins.keys(); it.hasNext(); ) have.add(it.next());
            if (have.size() == 1) lab = have.get(0);
            else { say("🔬 లోపలి చూపు ఉన్నవి: " + android.text.TextUtils.join(", ", have) + ". ఏది Sir?", byVoice); return; }
        }
        JSONObject x = ins.optJSONObject(ins.has(lab) ? lab : sp.nowName(lab));
        if (x == null) { say("దీనికి లోపలి చూపు లేదు Sir.", byVoice); return; }
        h.send(new JSONObject().put("t", "inside").put("label", lab).put("code", x.optString("code")));
        h.status("🔬 " + lab + " లోపల… · 'బయటికి' అంటే మళ్ళీ బొమ్మ");
        h.speak(x.optString("say"), true);
    }

    private void variant(String v, boolean byVoice) throws Exception {
        JSONObject va = sp.sp.optJSONObject("variants");
        if (va == null) { say("ఈ బొమ్మలో మార్చే భాగం లేదు Sir.", byVoice); return; }
        String part = va.optString("part");
        String[] r = sp.variantCode(v, h.partCode(part));
        if (r == null) { say("అది మార్చలేకపోయాను Sir.", byVoice); return; }
        h.replacePart(r[0], r[1]);
        sp.variant = v;
        String s = sp.variantSay(v);
        h.status("🔁 " + s);
        h.speak(s, false);
    }

    private void timeline(boolean on) {
        timelineOn = on;
        try { h.send(new JSONObject().put("t", "timeline").put("k", on ? 1 : -1)); } catch (Exception ignored) {}
        if (on) h.status("⏪ కింద బార్ జరిపితే బొమ్మ ఎలా కట్టారో వరుసగా కనిపిస్తుంది · ✕ అంటే మొత్తం బొమ్మ");
        refreshPanel();
    }

    // ================================================================ 🎮 games

    private void endGames(boolean said) {
        boolean any = quiz != null || faults != null || puzzleOn || chal != null;
        quiz = null;
        if (faults != null) { try { h.send(new JSONObject().put("t", "fault")); } catch (Exception ignored) {} }
        faults = null;
        puzzleOn = false;
        chal = null;
        if (said && any) { h.status("ఆట ఆపాను Sir."); h.speak("సరే Sir, ఆట ఆపాను.", false); }
    }

    private void startQuiz() {
        endGames(false);
        quiz = new Showpiece.Quiz(sp, System.currentTimeMillis());
        if (quiz.qs.isEmpty()) { quiz = null; h.status("ఈ బొమ్మకి క్విజ్ లేదు Sir."); return; }
        String q = quiz.next();
        h.answer("🧑‍🏫 క్విజ్", q + "\n\nనంబర్ చెప్పండి, పేరు చెప్పండి, లేదా బొమ్మలో ఆ భాగాన్ని తాకండి.", null);
        h.status("🧑‍🏫 క్విజ్ · ₹0");
        h.speak(q.replace("\n", ". "), true);
    }

    private void quizAnswered(Object[] r, boolean byVoice) {
        boolean ok = (Boolean) r[0];
        String want = (String) r[1];
        try { h.send(new JSONObject().put("t", "glow").put("label", want)); } catch (Exception ignored) {}
        String s = ok ? "✅ సరైన జవాబు Sir! " : "❌ కాదు Sir. జవాబు: " + want + ". ";
        String q = quiz.next();
        if (q == null) {
            String sc = quiz.score();
            quiz = null;
            String end = s + "క్విజ్ అయిపోయింది: " + sc + " సరైనవి.";
            h.answer("🧑‍🏫 క్విజ్", end, null);
            h.status("🧑‍🏫 " + sc);
            h.speak(end, false);
            return;
        }
        h.answer("🧑‍🏫 క్విజ్", s + "\n\n" + q, null);
        h.speak(s + q.replace("\n", ". "), true);
    }

    private void startFaults() throws Exception {
        endGames(false);
        faults = new Showpiece.Faults(sp, System.currentTimeMillis());
        if (faults.fs.isEmpty()) { faults = null; h.status("ఈ బొమ్మకి తప్పుల ఆట లేదు Sir."); return; }
        nextFault(null);
    }

    private void nextFault(String before) throws Exception {
        String[] f = faults.next();
        if (f == null) {
            String s = (before == null ? "" : before + " ") + "🔧 అన్ని తప్పులు అయిపోయాయి Sir: " + faults.score() + " మీరే కనిపెట్టారు!";
            faults = null;
            h.send(new JSONObject().put("t", "fault"));
            h.answer("🔧 తప్పు కనిపెట్టు", s, null);
            h.speak(s, false);
            return;
        }
        if (power < 0.5) setPower(0.6, false, false); // (a fault shows when it runs)
        h.send(new JSONObject().put("t", "fault").put("label", f[0]).put("kind", f[1]));
        String s = (before == null ? "" : before + "\n\n") + "🔧 ఈ బొమ్మలో ఒక తప్పు ఉంది. " + f[2];
        h.answer("🔧 తప్పు కనిపెట్టు", s + "\n\nఆ భాగాన్ని తాకండి లేదా దాని పేరు చెప్పండి.", null);
        h.status("🔧 తప్పు కనిపెట్టు · ₹0");
        h.speak(s, true);
    }

    private void faultGuessed(String g, boolean byVoice) {
        try {
            if ("right".equals(g)) { String w = faults.why(); h.send(new JSONObject().put("t", "fault")); nextFault("✅ కనిపెట్టారు Sir! " + w); }
            else if ("shown".equals(g)) {
                String want = faults.want(), w = faults.why();
                h.send(new JSONObject().put("t", "glow").put("label", want));
                h.send(new JSONObject().put("t", "fault"));
                nextFault("ఇది " + want + " Sir. " + w);
            } else { h.status("❌ అది కాదు Sir, ఇంకోసారి."); h.speak("అది కాదు Sir, ఇంకోసారి చూడండి.", true); }
        } catch (Exception ignored) {}
    }

    private void startPuzzle() throws Exception {
        endGames(false);
        puzzleOn = true;
        puzzleT0 = System.currentTimeMillis();
        h.send(new JSONObject().put("t", "scatter"));
        long best = prefs.getLong("sp_best_" + sp.id, 0);
        String s = "🧩 భాగాలన్నీ చెల్లాచెదురు చేశాను Sir. ఒక్కొక్కటి వేలితో (లేదా చిటికెతో) దాని చోటికి తీసుకెళ్ళండి, అది అతుక్కుంటుంది. 'హింట్' అంటే ఏది ముందో చెప్తాను."
                + (best > 0 ? " మీ బెస్ట్ టైమ్ " + clock(best) + "." : "");
        h.status("🧩 పజిల్ · ⏱️ మొదలు");
        h.speak(s, true);
    }

    private void startChallenge() throws Exception {
        JSONObject c = sp.sp.optJSONObject("challenge");
        if (c == null) { h.status("ఈ బొమ్మకి ఛాలెంజ్ లేదు Sir."); h.speak("ఈ బొమ్మకి ఛాలెంజ్ లేదు Sir.", false); return; }
        endGames(false);
        chal = new Showpiece.Challenge(c);
        if ("takeoff".equals(chal.kind)) setPower(0, false, false);
        h.answer(chal.title, chal.rules, null);
        h.status(chal.title + " · మొదలు");
        h.speak(chal.title + ". " + chal.rules, true);
    }

    private void service() {
        JSONObject s = sp.sp.optJSONObject("service");
        if (s == null) { h.status("ఈ బొమ్మకి సర్వీస్ గైడ్ లేదు Sir."); h.speak("ఈ బొమ్మకి సర్వీస్ గైడ్ లేదు Sir.", false); return; }
        StringBuilder b = new StringBuilder("⚠️ ").append(s.optString("safety"));
        JSONArray st = s.optJSONArray("steps");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject x = st.optJSONObject(i);
            if (x != null) b.append("\n\n").append(i + 1).append(". ").append(x.optString("say"));
        }
        h.card(s.optString("title"), b.toString());
        h.speak(s.optString("safety") + " అడుగులు కార్డ్‌లో ఉన్నాయి Sir.", false);
    }

    // ================================================================ what the stage says

    /** A message from the stage; true when it was this showpiece's (the screen does nothing more). */
    boolean fromStage(JSONObject m) {
        if (sp == null) return false;
        String t = m.optString("t");
        switch (t) {
            case "power":
                if ("none".equals(m.optString("label"))) { hasPower = false; h.status("ఈ బొమ్మకి పవర్ లేదు Sir."); return true; }
                hasPower = true;
                power = Math.min(1, m.optDouble("x", power));
                if (powerBar != null && !dragging) powerBar.setProgress((int) Math.round(power * 100));
                if (powerVal != null) powerVal.setText(Math.round(power * 100) + "%");
                return true;
            case "over": { String l = sp.line("cut"); if (!l.isEmpty()) { h.status("🛡️ " + l); h.speak(l, false); } return true; }
            case "listen": {
                String lb = m.optString("label");
                h.status(lb.isEmpty() ? "🩺 ఆ భాగానికి వేరే శబ్దం లేదు Sir." : "🩺 " + lb + " శబ్దం వింటున్నారు · మిగతావి నెమ్మదిగా");
                return true;
            }
            case "peel": {
                int n = m.optInt("n"), x = (int) m.optDouble("x", 0);
                h.status(n <= 0 ? "🧅 అన్ని పొరలూ తిరిగి పెట్టాను" : "🧅 పొర " + n + "/" + x + " తీశాను · " + m.optString("label") + " · 'పొర పెట్టు' అంటే తిరిగి వస్తుంది");
                return true;
            }
            case "timeline": h.status("⏪ " + m.optString("label")); return true;
            case "inside": if ("failed".equals(m.optString("label"))) h.status("🔬 లోపలి చూపు రాలేదు Sir."); else if (m.optString("id").isEmpty()) h.status("🔬 మళ్ళీ బొమ్మ బయటి నుంచి"); return true;
            case "buddy": return true;
            case "rgb": return true;
            case "hint": {
                String lb = m.optString("label");
                if (lb.isEmpty()) h.status("🧩 అన్నీ వాటి చోటిలో ఉన్నాయి Sir.");
                else { h.status("🧩 ఇప్పుడు: " + lb + " (వెలుగుతోంది)"); h.speak("ఇప్పుడు " + lb + " అమర్చండి Sir.", true); }
                return true;
            }
            case "env": {
                try {
                    JSONObject d = new JSONObject(m.optString("data", "{}"));
                    rows = d.optJSONObject("rows");
                    nums = d.optJSONObject("nums");
                    if (!dragging) { power = Math.min(1, m.optDouble("x", power)); if (powerBar != null) powerBar.setProgress((int) Math.round(power * 100)); if (powerVal != null) powerVal.setText(Math.round(power * 100) + "%"); }
                    if (chal != null && nums != null && nums.length() > 0) {
                        Object[] r = chal.step(nums, System.currentTimeMillis());
                        if (r != null) { String s = (String) r[1]; chal = null; h.answer("🎮 ఛాలెంజ్", s, null); h.status(((Boolean) r[0] ? "🏆 " : "🔁 ") + s); h.speak(s, false); }
                    }
                } catch (Exception ignored) {}
                return true;
            }
            case "snap": {
                if (!puzzleOn) return false;
                String[] dn = m.optString("label", "0/0").split("/");
                if (dn.length == 2 && dn[0].equals(dn[1]) && !"0".equals(dn[1])) {
                    long took = System.currentTimeMillis() - puzzleT0;
                    puzzleOn = false;
                    long best = prefs.getLong("sp_best_" + sp.id, 0);
                    boolean record = best == 0 || took < best;
                    if (record) prefs.edit().putLong("sp_best_" + sp.id, took).apply();
                    String s = "🎉 మొత్తం " + sp.title + " మీరే అమర్చారు Sir! సమయం " + clock(took) + (record ? " — కొత్త రికార్డ్!" : " · బెస్ట్ " + clock(best));
                    h.status(s);
                    h.speak(s, false);
                    return true;
                }
                if (dn.length == 2) h.status("🧩 " + m.optString("label") + " అమర్చారు · ⏱️ " + clock(System.currentTimeMillis() - puzzleT0));
                return true;
            }
            default: return false;
        }
    }

    /** A piece was tapped (or double-pinched): the games' answer, the 🩺, its card quietly; explained only for a double pinch. */
    void picked(String id, String label, boolean dbl) {
        if (sp == null) return;
        if (label == null || label.isEmpty()) { tipOff(); return; } // (a tap on empty space: the strip goes too)
        try {
            if (quiz != null) { Object[] r = quiz.check(null, label); if (r != null) { quizAnswered(r, false); return; } }
            if (faults != null) { String g = faults.guess(null, label); if (g != null) { faultGuessed(g, false); return; } }
            if (listenNext) { listenNext = false; h.send(new JSONObject().put("t", "listen").put("label", label)); return; }
        } catch (Exception ignored) {}
        if (sp.card(label) == null) return;
        tip(label); // (B: a one-line strip by the power bar, never over the model; ▸ opens the whole card)
        if (dbl) { String e = sp.explain(label); if (e != null) h.speak(e, true); }
    }

    /** 📖 (B) The tapped piece in one line above the power bar: its name and its first sentence; ▸ the whole card. */
    void tip(String label) {
        if (strip == null || sp == null) return;
        JSONObject x = sp.info(label);
        String first = x == null ? "" : x.optString("simple");
        int dot = first.indexOf(". ");
        if (dot > 0) first = first.substring(0, dot + 1);
        stripLabel = label;
        stripText.setText("📖 " + label + (first.isEmpty() ? "" : " · " + first));
        strip.setVisibility(View.VISIBLE);
        refreshPanel();
    }

    private void tipOff() {
        stripLabel = null;
        if (strip != null) strip.setVisibility(View.GONE);
        refreshPanel();
    }

    private void tipOpen() {
        if (sp == null || stripLabel == null) return;
        String c = sp.card(stripLabel);
        if (c != null) h.answer(stripLabel, c, null);
    }

    // ================================================================ the panel: power (a bike's grip, clutch, gears, brake), ⋯

    View panel() {
        if (panel != null) return panel;
        Activity a = h.screen();
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, dp(6));
        strip = new LinearLayout(a); // 📖 (B) the tapped piece, one line
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackground(Ui.round(a, 0xCC0B1118, Ui.CYAN_DIM, 12));
        strip.setPadding(dp(12), dp(6), dp(4), dp(6));
        stripText = Ui.text(a, "", 13.5f, Ui.TEXT);
        stripText.setSingleLine(true);
        stripText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        strip.addView(stripText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = Ui.text(a, "▸", 20f, Ui.CYAN);
        more.setPadding(dp(10), 0, dp(8), 0);
        more.setContentDescription("పూర్తి వివరాలు");
        strip.addView(more);
        TextView shut = Ui.text(a, "✕", 16f, Ui.MUTED);
        shut.setPadding(dp(8), 0, dp(8), 0);
        shut.setContentDescription("మూసేయి");
        shut.setOnClickListener(v -> tipOff());
        strip.addView(shut);
        strip.setOnClickListener(v -> tipOpen());
        more.setOnClickListener(v -> tipOpen());
        strip.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = dp(6);
        panel.addView(strip, sl);
        LinearLayout row = new LinearLayout(a);
        powerRow = row;
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        powerName = Ui.text(a, "⚡", 13f, Ui.MUTED);
        row.addView(powerName);
        powerBar = new SeekBar(a);
        powerBar.setMax(100);
        powerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean user) {
                if (!user) return;
                if (timelineOn) { try { h.send(new JSONObject().put("t", "timeline").put("k", v / 100.0)); } catch (Exception ignored) {} return; }
                if (powerVal != null) powerVal.setText(v + "%");
                setPower(v / 100.0, false, false);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { dragging = false; }
        });
        row.addView(powerBar, new LinearLayout.LayoutParams(0, -2, 1));
        powerVal = Ui.text(a, "0%", 13f, Ui.TEXT);
        powerVal.setMinWidth(dp(40));
        row.addView(powerVal);
        TextView dots = chip("⋯", v -> menu());
        dots.setContentDescription("షోపీస్ ఫీచర్లు");
        row.addView(dots);
        panel.addView(row);
        bikeRow = new LinearLayout(a);
        bikeRow.setOrientation(LinearLayout.HORIZONTAL);
        bikeRow.setGravity(Gravity.CENTER_VERTICAL);
        clutchBtn = hold("✋ క్లచ్", "clutch");
        bikeRow.addView(clutchBtn);
        bikeRow.addView(chip("−", v -> { try { setGear(gear == 2 ? 0 : gear == 0 ? 1 : Math.max(1, gear - 1), false); } catch (Exception ignored) {} }));
        gearView = Ui.text(a, "1", 17f, Ui.CYAN);
        gearView.setGravity(Gravity.CENTER);
        gearView.setMinWidth(dp(34));
        bikeRow.addView(gearView);
        bikeRow.addView(chip("+", v -> { try { setGear(gear == 0 ? 2 : gear == 1 ? 0 : Math.min(4, gear + 1), false); } catch (Exception ignored) {} }));
        brakeBtn = hold("🛑 బ్రేక్", "brake");
        bikeRow.addView(brakeBtn);
        panel.addView(bikeRow);
        panel.setVisibility(View.GONE);
        return panel;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = Ui.pill(h.screen(), s);
        t.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    /** A button that works while it is held (the clutch lever, the brake). */
    private TextView hold(String s, String what) {
        TextView t = chip(s, null);
        t.setOnTouchListener((v, ev) -> {
            int k = ev.getActionMasked();
            double on = k == MotionEvent.ACTION_DOWN ? 1 : k == MotionEvent.ACTION_UP || k == MotionEvent.ACTION_CANCEL ? 0 : -1;
            if (on < 0) return true;
            try { h.send(new JSONObject().put("t", "env").put(what, on)); } catch (Exception ignored) {}
            v.setAlpha(on > 0 ? 0.6f : 1f);
            if (on > 0) v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            return true;
        });
        return t;
    }

    /** The power bar as the showpiece wants it (after ⛶ full screen gave the bars back). */
    void refresh() { refreshPanel(); }

    private void refreshPanel() {
        if (panel == null) return;
        boolean tipOn = sp != null && strip != null && strip.getVisibility() == View.VISIBLE;
        boolean on = sp != null && (controlsShown || tipOn);
        panel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        powerRow.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        bikeRow.setVisibility(controlsShown && sp.bike() && !timelineOn ? View.VISIBLE : View.GONE);
        powerName.setText(timelineOn ? "⏪" : sp.bike() ? "🏍️ గ్రిప్" : "⚡ పవర్");
        powerVal.setText(timelineOn ? "✕" : Math.round(power * 100) + "%");
        powerVal.setOnClickListener(timelineOn ? v -> timeline(false) : null);
        if (timelineOn) powerBar.setProgress(100);
        else powerBar.setProgress((int) Math.round(power * 100));
        if (gearView != null) gearView.setText(gear == 0 ? "N" : String.valueOf(gear));
    }

    /** ⋯ Every feature of the showpiece, by name (₹0). */
    void menu() {
        if (sp == null) return;
        final String[][] items = {
                {"🎓 ఎలా పనిచేస్తుంది (షో)", "show_how"}, {"⛶ ఫుల్ స్క్రీన్", "full_on"}, {"🌡️ వేడి", "heat"}, {"〰️ ప్రవాహాలు", "flow"}, {"🐢 స్లో మోషన్", "slow"}, {"⏸ ఆపి చూపు / ▶️ మామూలు", "freeze"},
                {"🔊 / 🔇 శబ్దం", "sound"}, {"🩺 స్టెతస్కోప్", "listen"}, {"🧅 ఒక పొర తీయి", "peel"}, {"🧅 పొర పెట్టు", "unpeel"}, {"⏪ ఎలా కట్టారో (టైమ్‌లైన్)", "timeline"},
                {"🔬 లోపల చూపు", "inside"}, {"🖐️ చేతి ఎక్స్‌రే", "hx"}, {"🧍 మనిషితో సైజు పోలిక", "person"}, {"🪙 నాణెంతో పోలిక", "coin"},
                {"💠 హోలోగ్రామ్", "holo"}, {"🎨 రెయిన్‌బో లైట్లు", "rgb"}, {"🌞 ఎండాకాలం 40°", "hot"}, {"❄️ ఏసీ గది 24°", "ac"}, {"⛰️ ఘాట్ రోడ్డు", "hill"}, {"🌃 రాత్రి రన్‌వే", "runway"},
                {"🚨 హద్దు దాటించు (రక్షణ చూడు)", "over"}, {"🔧 తప్పు కనిపెట్టు", "faults"}, {"🧑‍🏫 క్విజ్", "quiz"}, {"🧩 అసెంబ్లీ పజిల్", "puzzle"}, {"🎮 ఛాలెంజ్", "challenge"},
                {"🛠️ సర్వీస్ గైడ్", "service"}, {"🧒 సింపుల్ / 👨‍🔧 లోతైన మాటలు", "mode"}, {"🔁 భాగం మార్చు (కార్డ్)", "swap"}, {"🖼️ పోస్టర్ (JPEG + PDF)", "poster"},
                {"📸 ఫోటో మోడ్", "photomode"}, {"🎬 ఒక్క నొక్కుతో ఫిల్మ్", "film"}, {"⌚ వాచ్ బెజెల్‌తో పవర్", "bezel"}, {"🎛️ కంట్రోల్స్ దాచు / చూపు", "controls"}};
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String[] it : items) {
            if ("swap".equals(it[1]) && sp.sp.optJSONObject("variants") == null) continue;
            if ("challenge".equals(it[1]) && sp.sp.optJSONObject("challenge") == null) continue;
            if ("runway".equals(it[1]) && !"runway".equals(sp.sp.optString("scene"))) continue;
            if ("hill".equals(it[1]) && !sp.bike()) continue;
            names.add(it[0]);
            keys.add(it[1]);
        }
        new AlertDialog.Builder(h.screen()).setTitle("🏆 " + sp.title + " · అన్నీ ₹0")
                .setItems(names.toArray(new String[0]), (d, i) -> menuPick(keys.get(i))).setNegativeButton("వద్దు", null).show();
    }

    private boolean heatOn, flowOn, holoOn, soundOn = true, frozen;

    private void menuPick(String k) {
        switch (k) {
            case "show_how": h.command("show_how", false); return;
            case "full_on": h.command("full_on", false); return;
            case "heat": heatOn = !heatOn; run(new Showpiece.Act("heat", heatOn ? 1 : 0, ""), false); return;
            case "flow": flowOn = !flowOn; run(new Showpiece.Act("flow", flowOn ? 1 : 0, ""), false); return;
            case "holo": holoOn = !holoOn; run(new Showpiece.Act("holo", holoOn ? 1 : 0, ""), false); return;
            case "sound": soundOn = !prefs.getBoolean("sp_sound", true); run(new Showpiece.Act("sound", soundOn ? 1 : 0, ""), false); return;
            case "slow": run(new Showpiece.Act("speed", 0.25, ""), false); return;
            case "freeze": frozen = !frozen; run(new Showpiece.Act("speed", frozen ? 0 : 1, ""), false); return;
            case "peel": run(new Showpiece.Act("peel", 1, ""), false); return;
            case "unpeel": run(new Showpiece.Act("peel", -1, ""), false); return;
            case "person": case "coin": run(new Showpiece.Act("buddy", Double.NaN, k), false); return;
            case "rgb": run(new Showpiece.Act("rgb", Double.NaN, "rainbow"), false); return;
            case "hot": case "ac": case "hill": case "runway": run(new Showpiece.Act("cond", Double.NaN, k), false); return;
            case "mode": run(new Showpiece.Act("mode", Double.NaN, sp.deep ? "simple" : "deep"), false); return;
            case "hx": run(new Showpiece.Act("hx", 1, ""), false); return;
            case "inside": run(new Showpiece.Act("inside", Double.NaN, ""), false); return;
            default: run(new Showpiece.Act(k, Double.NaN, ""), false);
        }
    }

    // ================================================================ little helpers

    private int dp(float v) { return Math.round(v * h.screen().getResources().getDisplayMetrics().density); }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 60 ? (s / 60) + " ని " + (s % 60) + " సె" : s + " సెకన్లు";
    }

    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ENGLISH, "%.2f", v).replaceAll("0+$", ""); }

    /** 🖼️ (R) A poster picture as a one-page A4 PDF (the picture centred on white, its title on top). */
    static byte[] posterPdf(byte[] jpeg, String title) throws Exception {
        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bm == null) throw new IllegalStateException("పోస్టర్ బొమ్మ రాలేదు");
        android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        try {
            int W = 595, H = 842;
            boolean wide = bm.getWidth() > bm.getHeight();
            if (wide) { int t = W; W = H; H = t; }
            android.graphics.pdf.PdfDocument.Page page = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(W, H, 1).create());
            android.graphics.Canvas c = page.getCanvas();
            c.drawColor(0xFF0B0F14);
            float m = 24, s = Math.min((W - 2 * m) / bm.getWidth(), (H - 2 * m) / bm.getHeight());
            float w = bm.getWidth() * s, hh = bm.getHeight() * s;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(bm, null, new android.graphics.RectF((W - w) / 2, (H - hh) / 2, (W + w) / 2, (H + hh) / 2), p);
            doc.finishPage(page);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            doc.writeTo(o);
            return o.toByteArray();
        } finally { doc.close(); bm.recycle(); }
    }
}
