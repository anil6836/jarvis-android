package com.anil.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🏆 The workshop's showpieces (20 Nov): hand-made machines that ship inside the app (assets/workshop/showpieces/*.json) and
 * cost ₹0 to open — a jet engine, a gaming PC, a geared electric bike… Each carries its own words: names, what each piece
 * is (simple / deep), facts for ₹0 answers, a quiz, faults to find, the inside of a piece, a service guide, a film's lines.
 * What he says to one is understood here without AI (pure: tested on a desk). Nothing here is saved or sent anywhere.
 */
final class Showpiece {
    static final String DIR = "workshop/showpieces";

    final JSONObject sp;
    final String id, title;
    /** 🧒 simple (false) / 👨‍🔧 deep explanations (AC). */
    boolean deep;
    /** 🔁 (S) the variant on the stage now (e.g. the graphics card), "" when this showpiece has none. */
    String variant = "";

    private Showpiece(JSONObject sp, String title) {
        this.sp = sp == null ? new JSONObject() : sp;
        this.id = this.sp.optString("id");
        this.title = title == null ? "" : title;
        JSONObject va = this.sp.optJSONObject("variants");
        if (va != null) variant = va.optString("default");
    }

    /** From a model's saved JSON (its "showpiece" extra); null when it is not a showpiece. */
    static Showpiece of(JSONObject model) {
        if (model == null) return null;
        JSONObject sp = model.optJSONObject("showpiece");
        if (sp == null || sp.optString("id").isEmpty()) return null;
        return new Showpiece(sp, model.optString("title"));
    }

    static Showpiece of(String showpieceJson, String title) {
        if (showpieceJson == null || showpieceJson.isEmpty()) return null;
        try { JSONObject sp = new JSONObject(showpieceJson); return sp.optString("id").isEmpty() ? null : new Showpiece(sp, title); }
        catch (Exception e) { return null; }
    }

    // ================================================================ the shelf (inside the app: ₹0)

    /** Every showpiece's model JSON, in the order they were made. */
    static List<JSONObject> shelf(Context c) {
        List<JSONObject> l = new ArrayList<>();
        try {
            String[] names = c.getAssets().list(DIR);
            if (names != null) for (String n : names) {
                if (!n.endsWith(".json")) continue;
                JSONObject b = read(c, n);
                if (b != null && b.optJSONObject("showpiece") != null) l.add(b);
            }
        } catch (Exception ignored) {}
        Collections.sort(l, (a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
        return l;
    }

    static JSONObject load(Context c, String id) {
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) return null;
        return read(c, id + ".json");
    }

    private static JSONObject read(Context c, String name) {
        try (InputStream in = c.getAssets().open(DIR + "/" + name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return new JSONObject(new String(o.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    /** Which showpiece his words name ("జెట్ ఇంజన్ చూపించు", "గేమింగ్ PC"), from the shelf: its id, or null. */
    static String match(List<JSONObject> shelf, String words) {
        String t = " " + low(words) + " ";
        String best = null;
        int bestLen = 0;
        for (JSONObject b : shelf) {
            JSONObject sp = b.optJSONObject("showpiece");
            if (sp == null) continue;
            JSONArray ns = sp.optJSONArray("names");
            for (int i = 0; ns != null && i < ns.length(); i++) {
                String n = low(ns.optString(i));
                if (n.length() < 2) continue;
                boolean hit = n.length() <= 3 ? t.contains(" " + n + " ") : t.contains(n); // (short names like "pc" only as a whole word)
                if (hit && n.length() > bestLen) { bestLen = n.length(); best = sp.optString("id"); }
            }
        }
        return best;
    }

    private static String low(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।॥“”‘’&&[^%.]]+", " ").replaceAll("\\s+", " ").trim();
    }

    // ================================================================ its words

    String say() { return sp.optString("say"); }

    String line(String key) {
        JSONObject p = sp.optJSONObject("power");
        return p == null ? "" : p.optString(key);
    }

    boolean bike() { return "bike".equals(sp.optString("controls")); }

    /** The pieces this showpiece knows about (its info's names). */
    List<String> labels() {
        List<String> l = new ArrayList<>();
        JSONObject inf = sp.optJSONObject("info");
        if (inf != null) for (java.util.Iterator<String> it = inf.keys(); it.hasNext(); ) l.add(it.next());
        return l;
    }

    /** The info of a piece (the variant's own info when the piece is the swapped one). */
    JSONObject info(String label) {
        if (label == null) return null;
        JSONObject va = sp.optJSONObject("variants");
        if (va != null && !variant.isEmpty() && label.equals(va.optString("label").replace("{v}", variant))) {
            JSONObject o = va.optJSONObject("options");
            JSONObject v = o == null ? null : o.optJSONObject(variant);
            if (v != null && v.optJSONObject("info") != null) return v.optJSONObject("info");
        }
        JSONObject inf = sp.optJSONObject("info");
        JSONObject x = inf == null ? null : inf.optJSONObject(label);
        if (x == null && va != null) { // (a piece of the default variant asked by its old name)
            String def = va.optString("label").replace("{v}", va.optString("default"));
            if (label.startsWith(va.optString("label").replace("{v}", ""))) x = inf == null ? null : inf.optJSONObject(def);
        }
        return x;
    }

    /** The current name of a piece that the words call by its default variant's name (faults, quiz, service). */
    String nowName(String label) {
        JSONObject va = sp.optJSONObject("variants");
        if (va == null || variant.isEmpty() || label == null) return label;
        String def = va.optString("label").replace("{v}", va.optString("default"));
        return label.equals(def) ? va.optString("label").replace("{v}", variant) : label;
    }

    /** What a piece is: simple, or deep when he asked for engineer's words (AC). */
    String explain(String label) {
        JSONObject x = info(label);
        if (x == null) return null;
        String s = x.optString(deep ? "deep" : "simple");
        return s.isEmpty() ? x.optString("simple") : s;
    }

    /** 📋 (E) The spec card of a piece: its name, a line, its numbers. */
    String card(String label) {
        JSONObject x = info(label);
        if (x == null) return null;
        StringBuilder b = new StringBuilder(x.optString("simple"));
        JSONObject specs = x.optJSONObject("specs");
        if (specs != null && specs.length() > 0) {
            b.append("\n");
            for (java.util.Iterator<String> it = specs.keys(); it.hasNext(); ) { String k = it.next(); b.append("\n• ").append(k).append(": ").append(specs.optString(k)); }
        }
        return b.toString();
    }

    /** A piece his words name (the most fully named one wins: "మోటార్" is the motor, not the motor's controller). */
    String labelIn(String text) {
        String t = " " + low(text) + " ";
        String best = null;
        double bestCover = 0;
        int bestChars = 0;
        List<String> all = labels();
        JSONObject va = sp.optJSONObject("variants");
        if (va != null && !variant.isEmpty()) all.add(va.optString("label").replace("{v}", variant));
        JSONArray pref = sp.optJSONArray("prefer");
        for (String lab : all) {
            String[] toks = low(lab).split(" ");
            int hit = 0, chars = 0, n = 0;
            for (String w : toks) {
                if (w.length() < 2) continue;
                n++;
                if (w.length() <= 3 ? t.contains(" " + w + " ") : t.contains(w)) { hit++; chars += w.length(); continue; }
                String stem = w.endsWith("ళ్ళు") ? w.substring(0, w.length() - 4) : w.endsWith("లు") ? w.substring(0, w.length() - 2) : null; // ("టర్బైన్" names "టర్బైన్లు")
                if (stem != null && stem.length() >= 4 && t.contains(stem)) { hit++; chars += stem.length(); }
            }
            if (n == 0 || hit == 0) continue;
            double cover = (double) hit / n;
            boolean preferred = false;
            for (int i = 0; pref != null && i < pref.length(); i++) if (lab.equals(pref.optString(i))) preferred = true;
            if (chars > bestChars || (chars == bestChars && cover > bestCover) || (chars == bestChars && cover == bestCover && preferred)) { best = lab; bestCover = cover; bestChars = chars; }
        }
        return best;
    }

    /** The words name this machine, a piece of it, or point at it ("ఇది", "ఈ బొమ్మ"). */
    boolean named(String text) {
        String t = " " + low(text) + " ";
        for (String w : new String[]{"ఇది", "దీని", "దీన్ని", "ఈ బొమ్మ", "బొమ్మ", "షోపీస్", "this", " it "}) if (t.contains(w)) return true;
        JSONArray ns = sp.optJSONArray("names");
        for (int i = 0; ns != null && i < ns.length(); i++) {
            String n = low(ns.optString(i));
            if (n.length() >= 2 && (n.length() <= 3 ? t.contains(" " + n + " ") : t.contains(n))) return true;
        }
        if (low(title).length() > 2 && t.contains(low(title))) return true;
        for (String w : new String[]{"ఫోన్", "phone", " నా ", " నాకు ", " మా ", "ఇంట్లో", "ఇంటి ", "వాచ్", "watch", "కారు", "car "}) if (t.contains(w)) return false; // ("ఫోన్ బ్యాటరీ ఎంత" is his phone's)
        return labelIn(text) != null;
    }

    // ================================================================ ❓ (N) ₹0 answers from its facts

    private static final String[] ASK_WHAT = {"ఏం చేస్తుంది", "ఏమి చేస్తుంది", "అంటే ఏమిటి", "అంటే ఏంటి", "అంటే", "ఏంటి", "ఏమిటి", "గురించి", "ఎందుకు",
            "పని ఏమిటి", "పని ఏంటి", "what is", "what does", "explain", "వివరించు", "ఎలా పనిచేస్తుంది", "ఎలా పని చేస్తుంది"};

    /** A ₹0 answer: a fact, or what a named piece is; null when the AI would have to be asked. */
    String answer(String text, String picked) {
        String t = low(text);
        if (t.isEmpty()) return null;
        JSONArray fs = sp.optJSONArray("facts");
        String best = null;
        int bestLen = 0;
        for (int i = 0; fs != null && i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            JSONArray ks = f == null ? null : f.optJSONArray("k");
            for (int j = 0; ks != null && j < ks.length(); j++) {
                String k = low(ks.optString(j));
                if (k.length() >= 2 && (k.length() <= 3 ? (" " + t + " ").contains(" " + k + " ") : t.contains(k)) && k.length() > bestLen) { bestLen = k.length(); best = f.optString("a"); }
            }
        }
        if (best != null) return best;
        boolean asks = false;
        for (String a : ASK_WHAT) if (t.contains(a)) { asks = true; break; }
        if (!asks) return null;
        String lab = labelIn(t);
        if (lab == null && picked != null && (t.contains("ఇది") || t.contains("దీని") || t.contains("this") || t.contains("ఈ భాగం"))) lab = picked;
        if (lab == null && picked != null && t.split(" ").length <= 3) lab = picked; // ("ఏం చేస్తుంది?" with a piece tapped)
        String e = explain(lab);
        return e == null ? null : e;
    }

    /** A live number he asked for ("స్పీడ్ ఎంత", "CPU టెంపరేచర్") from the stage's numbers now: the row's name, or null. */
    static String liveRow(String text, JSONObject rows) {
        if (rows == null || rows.length() == 0) return null;
        String t = low(text);
        boolean asks = t.contains("ఎంత") || t.contains("how much") || t.contains("how fast") || t.contains("how hot") || t.contains("ఎన్ని") || t.contains("ఇప్పుడు");
        if (!asks) return null;
        String[][] same = {{"వేగం", "స్పీడ్", "speed", "ఎంత వేగంగా"}, {"గేర్", "gear"}, {"పవర్", "power", "కిలోవాట్"}, {"రేంజ్", "range", "ఎంత దూరం"},
                {"cpu", "ప్రాసెసర్", "సీపీయూ"}, {"gpu", "గ్రాఫిక్స్", "జీపీయూ"}, {"కరెంట్", "వాట్", "watt"}, {"ఫ్యాన్", "fan", "rpm"},
                {"ఎగ్జాస్ట్", "egt", "వెనక వేడి"}, {"థ్రస్ట్", "thrust", "తోపుడు"}, {"n1", "ఫ్యాన్ వేగం"}, {"n2", "కోర్ వేగం"}, {"wh km", "whkm", "wh/km"}, {"₹", "రూపాయ", "ఖర్చు"}};
        for (java.util.Iterator<String> it = rows.keys(); it.hasNext(); ) {
            String k = it.next(), kl = low(k);
            for (String[] g : same) {
                boolean rowIs = false;
                for (String w : g) if (kl.contains(w)) { rowIs = true; break; }
                if (!rowIs) continue;
                for (String w : g) if (t.contains(w)) return k;
            }
            for (String w : kl.split(" ")) if (w.length() >= 3 && t.contains(w)) return k;
        }
        return null;
    }

    // ================================================================ his words → what to do (₹0, no AI)

    /** One thing to do: k = what; v = a number; s = a name / mode. */
    static final class Act {
        final String k, s;
        final double v;

        Act(String k, double v, String s) { this.k = k; this.v = v; this.s = s == null ? "" : s; }

        @Override public String toString() { return k + (Double.isNaN(v) ? "" : "(" + (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + ")") + (s.isEmpty() ? "" : "[" + s + "]"); }
    }

    private static Act act(String k) { return new Act(k, Double.NaN, ""); }
    private static Act act(String k, double v) { return new Act(k, v, ""); }
    private static Act act(String k, String s) { return new Act(k, Double.NaN, s); }

    /** {kind, words…} matched whole after the fillers come out (Workshop.norm). */
    private static final Object[][] WORDS = {
            {act("power", 0.3), "స్టార్ట్", "స్టార్ట్ చెయ్", "ఆన్", "ఆన్ చెయ్", "ఇంజన్ స్టార్ట్", "ఇంజిన్ స్టార్ట్", "ఇంజన్ ఆన్", "start", "start engine", "turn on", "power on",
                    "పవర్ ఆన్", "గ్రిప్ తిప్పు", "ఆట మొదలుపెట్టు", "గేమ్ స్టార్ట్", "నడుపు", "నడిపించు", "run"},
            {act("power", 1), "ఫుల్ పవర్", "ఫుల్", "పూర్తి పవర్", "full power", "full throttle", "full", "max", "మాక్స్", "ఫుల్ లోడ్", "ఫుల్ గ్రిప్", "గ్రిప్ ఫుల్", "ఫుల్ స్పీడ్",
                    "full speed", "full load", "టేకాఫ్ పవర్", "పవర్ ఫుల్"},
            {act("power", 0.5), "సగం పవర్", "హాఫ్", "హాఫ్ పవర్", "half", "half power", "సగం", "సగం లోడ్", "సగం గ్రిప్", "half throttle"},
            {act("power", 0), "ఆపేయ్", "ఆపేయి", "ఆఫ్", "ఇంజన్ ఆపు", "ఇంజిన్ ఆపు", "పవర్ ఆపు", "పవర్ ఆఫ్", "stop engine", "turn off", "power off", "engine off", "గ్రిప్ వదులు",
                    "గ్రిప్ వదిలేయ్", "గ్రిప్ వదిలేయి", "ఆట ఆపు", "గేమ్ ఆపు", "idle", "ఐడిల్", "పవర్ జీరో", "జీరో"},
            {act("power_more"), "పవర్ పెంచు", "పెంచు", "more", "more power", "faster", "వేగం పెంచు", "స్పీడ్ పెంచు", "లోడ్ పెంచు", "గ్రిప్ పెంచు", "increase", "speed up", "throttle up"},
            {act("power_less"), "పవర్ తగ్గించు", "తగ్గించు", "less", "less power", "slower", "వేగం తగ్గించు", "స్పీడ్ తగ్గించు", "లోడ్ తగ్గించు", "గ్రిప్ తగ్గించు", "decrease", "throttle down"},
            {act("over"), "ఓవర్‌లోడ్", "ఓవర్ లోడ్", "ఓవర్లోడ్", "హద్దు దాటించు", "overload", "over limit", "ఓవర్‌క్లాక్", "ఓవర్ క్లాక్", "overclock", "రెడ్ లైన్", "redline", "హద్దు దాటు"},
            {act("heat", 1), "వేడి", "హీట్", "heat", "heat view", "థర్మల్", "thermal", "వేడి వ్యూ", "వేడి రంగులు", "టెంపరేచర్ వ్యూ"},
            {act("heat", 0), "వేడి ఆపు", "వేడి తీసేయ్", "హీట్ ఆఫ్", "heat off", "వేడి వ్యూ ఆపు", "వేడి వద్దు"},
            {act("flow", 1), "గాలి", "గాలి దారి", "గాలి ప్రవాహం", "airflow", "air flow", "flow", "flow view", "కరెంట్ దారి", "ప్రవాహం", "ప్రవాహాలు", "దారులు", "కూలెంట్ దారి"},
            {act("flow", 0), "గాలి ఆపు", "గాలి వద్దు", "flow off", "ప్రవాహం ఆపు", "దారులు ఆపు", "ప్రవాహాలు ఆపు", "ప్రవాహాలు వద్దు"},
            {act("sound", 1), "శబ్దం", "సౌండ్", "sound", "sound on", "సౌండ్ ఆన్", "శబ్దం వినిపించు", "శబ్దం ఆన్"},
            {act("sound", 0), "శబ్దం ఆపు", "సౌండ్ ఆఫ్", "సౌండ్ ఆపు", "mute", "sound off", "నిశ్శబ్దం", "శబ్దం వద్దు", "మ్యూట్"},
            {act("listen"), "స్టెతస్కోప్", "stethoscope", "శబ్దం ఎక్కడ", "ఎక్కడి నుంచి శబ్దం", "విను", "వినిపించు", "listen", "దీని శబ్దం"},
            {act("speed", 0.25), "స్లో మోషన్", "స్లోమోషన్", "నెమ్మదిగా", "slow motion", "slow", "slow mo", "స్లో"},
            {act("speed", 0), "ఫ్రీజ్", "freeze", "pause", "పాజ్", "ఆపి ఉంచు", "ఆగు", "ఆగి చూపించు", "నిలిపేయ్"},
            {act("speed", 1), "మామూలు వేగం", "normal speed", "నార్మల్ స్పీడ్", "resume", "కదిలించు", "మళ్ళీ కదిలించు", "play"},
            {act("holo", 1), "హోలోగ్రామ్", "hologram", "హోలో", "holo", "హోలోగ్రామ్ ఆన్"},
            {act("holo", 0), "హోలోగ్రామ్ ఆపు", "hologram off", "హోలో ఆఫ్", "మామూలు రంగులు", "normal colours", "normal colors"},
            {act("rgb", "rainbow"), "రెయిన్‌బో", "రెయిన్బో", "rainbow", "rgb", "ఆర్జీబీ", "rgb లైట్లు", "రంగుల లైట్లు", "ఇంద్రధనుస్సు"},
            {act("rgb", "breathe"), "బ్రీత్", "breathe", "ఊపిరి లైట్లు", "breathing", "లైట్లు ఊపిరి"},
            {act("rgb", ""), "లైట్లు మామూలు", "rgb off", "rgb ఆఫ్", "rgb normal", "మామూలు లైట్లు"},
            {act("peel", 1), "పొర తీయ్", "పొర తీయి", "పొరలు తీయ్", "పొరలు తీయి", "peel", "peel layer", "ఒక పొర తీయ్", "పై పొర తీయ్", "ఉల్లిపాయ"},
            {act("peel", -1), "పొర పెట్టు", "పొరలు పెట్టు", "unpeel", "పొర తిరిగి పెట్టు"},
            {act("timeline"), "టైమ్‌లైన్", "టైమ్ లైన్", "timeline", "ఎలా కట్టారో", "ఎలా తయారు చేశారో", "కట్టడం చూపించు", "build order"},
            {act("hx", 1), "చేతి ఎక్స్‌రే", "చేతి ఎక్స్ రే", "hand xray", "hand x ray", "ఎక్స్‌రే చేయి", "చేతి కిటికీ"},
            {act("hx", 0), "చేతి ఎక్స్‌రే ఆపు", "hand xray off", "చేతి కిటికీ ఆపు"},
            {act("inside", ""), "లోపల", "లోపలికి", "లోపల చూపించు", "inside", "go inside", "లోపలికి వెళ్ళు", "లోపల ఏముంది", "దీని లోపల"},
            {act("inside_off"), "బయటికి", "బయటకి", "లోపల నుంచి బయటికి", "inside off", "close inside", "out", "బయటికి రా"},
            {act("buddy", "person"), "మనిషి పక్కన", "మనిషితో పోల్చు", "మనిషి", "person", "size compare", "సైజు పోల్చు", "మనిషితో", "మనిషితో పోలిక"},
            {act("buddy", "coin"), "నాణెం", "coin", "రూపాయి నాణెం", "నాణెంతో పోల్చు"},
            {act("buddy", ""), "పోలిక తీసేయ్", "పోలిక వద్దు", "మనిషిని తీసేయ్", "compare off"},
            {act("cond", "hot"), "హాట్ డే", "ఎండ", "ఎండాకాలం", "hot day", "hot", "వేసవి", "40 డిగ్రీలు", "ఎండలో"},
            {act("cond", "ac"), "ఏసీ", "ac", "ఏసీ గది", "చల్లని గది", "24 డిగ్రీలు", "air conditioned", "ac room"},
            {act("cond", "hill"), "కొండ", "ఘాట్", "ఘాట్ రోడ్డు", "hill", "ఎత్తు రోడ్డు", "ఎక్కుడు", "climb", "uphill", "కొండ ఎక్కు"},
            {act("cond", "flat"), "చదునైన రోడ్డు", "flat", "flat road", "మామూలు రోడ్డు", "చదును"},
            {act("cond", "runway"), "రన్‌వే", "రన్ వే", "రాత్రి రన్‌వే", "night runway", "runway", "విమానాశ్రయం"},
            {act("cond", "normal"), "మామూలు వాతావరణం", "normal weather", "మామూలు గది", "కండిషన్స్ ఆపు"},
            {act("poster"), "పోస్టర్", "poster", "పోస్టర్ pdf", "poster pdf", "పోస్టర్ తయారు"},
            {act("photomode"), "ఫోటో మోడ్", "photo mode", "అందమైన ఫోటో", "స్టూడియో ఫోటో"},
            {act("film"), "ఫిల్మ్", "సినిమా", "film", "చిన్న సినిమా", "మూవీ", "movie", "ఫిల్మ్ తీయ్", "short film"},
            {act("quiz"), "క్విజ్", "quiz", "ప్రశ్నలు అడుగు", "పరీక్ష", "test me", "నన్ను అడుగు"},
            {act("faults"), "తప్పు కనిపెట్టు", "ఫాల్ట్ గేమ్", "fault game", "ఏం పాడైందో కనిపెట్టు", "రిపేర్ గేమ్", "repair game", "find the fault", "తప్పుల ఆట", "ఫాల్ట్"},
            {act("puzzle"), "పజిల్", "puzzle", "అమర్చే ఆట", "assembly game", "జోడించే ఆట", "అసెంబ్లీ ఆట", "అమర్చు ఆట"},
            {act("hint"), "హింట్", "hint", "తర్వాత ఏది", "ఏది పెట్టాలి", "సహాయం", "ఏ భాగం"},
            {act("challenge"), "ఛాలెంజ్", "challenge", "టేకాఫ్", "takeoff", "ఎకో రైడ్", "eco ride", "ఎకో ఛాలెంజ్", "టేకాఫ్ ఛాలెంజ్"},
            {act("service"), "సర్వీస్ గైడ్", "సర్వీస్", "service", "service guide", "శుభ్రం చేయడం ఎలా", "maintenance", "మెయింటెనెన్స్", "క్లీనింగ్", "చూసుకోవడం ఎలా"},
            {act("mode", "simple"), "సింపుల్‌గా", "సింపుల్ గా", "సులభంగా", "simple", "simply", "పిల్లలకి చెప్పినట్టు", "సింపుల్"},
            {act("mode", "deep"), "లోతుగా", "డీప్", "detail", "deep", "ఇంజనీర్‌లా", "ఇంజనీర్ లాగా", "వివరంగా", "in detail"},
            {act("swap"), "గ్రాఫిక్స్ కార్డ్ మార్చు", "కార్డ్ మార్చు", "gpu మార్చు", "swap gpu", "gpu swap", "కార్డ్ మార్చేయ్", "వేరే కార్డ్", "change gpu"},
            {act("gear", 0), "న్యూట్రల్", "neutral", "గేర్ న్యూట్రల్", "న్యూట్రల్ లో పెట్టు"},
            {act("gear_up"), "గేర్ పెంచు", "గేర్ అప్", "gear up", "shift up", "పై గేర్", "next gear"},
            {act("gear_down"), "గేర్ తగ్గించు", "గేర్ డౌన్", "gear down", "shift down", "కింది గేర్"},
            {act("clutch", 1), "క్లచ్ నొక్కు", "క్లచ్", "clutch", "clutch in", "క్లచ్ పట్టు"},
            {act("clutch", 0), "క్లచ్ వదులు", "క్లచ్ వదిలేయ్", "clutch out", "release clutch"},
            {act("brake", 1), "బ్రేక్", "బ్రేక్ వేయ్", "బ్రేక్ వెయ్", "బ్రేక్ వేయి", "బ్రేక్ వేయండి", "brake", "బ్రేక్ నొక్కు"},
            {act("brake", 0), "బ్రేక్ వదులు", "brake off", "release brake", "బ్రేక్ వదిలేయ్"},
            {act("controls"), "కంట్రోల్స్", "controls", "బైక్ కంట్రోల్స్", "bike controls", "పవర్ బార్", "కంట్రోల్"},
            {act("card"), "స్పెక్స్", "specs", "spec card", "వివరాలు", "స్పెక్ కార్డ్", "నంబర్లు"},
            {act("bezel"), "వాచ్ బెజెల్", "వాచ్‌తో పవర్", "watch bezel", "వాచ్ తో పవర్", "బెజెల్"},
            {act("menu"), "షోపీస్ మెనూ", "అన్ని ఫీచర్లు", "ఏమేమి చేయొచ్చు", "what can i do", "features", "ఫీచర్లు", "మెనూ"},
            // 🕐 (Build 2) the 24-hour day, 🚶 walking inside, 🧅 roofs and walls (outdoor showpieces: power stations, the town)
            {act("day_play"), "రోజు మొత్తం", "24 గంటలు", "ఇరవై నాలుగు గంటలు", "రోజంతా", "రోజు మొత్తం నడిపించు", "full day", "day cycle", "24 hours", "టైమ్ నడిపించు",
                    "సమయం నడిపించు", "రోజు నడిపించు", "టైమ్ ప్లే", "time play", "గడియారం నడిపించు", "సమయం కదిలించు"},
            {act("day_stop"), "టైమ్ ఆపు", "సమయం ఆపు", "రోజు ఆపు", "గడియారం ఆపు", "stop time", "time stop", "pause time", "టైమ్ ఆగు", "సమయం ఆగు"},
            {act("hour", 6), "ఉదయం", "పొద్దున", "పొద్దున్నే", "సూర్యోదయం", "morning", "sunrise", "ఉదయం చేయి"},
            {act("hour", 12), "మధ్యాహ్నం", "మిట్ట మధ్యాహ్నం", "noon", "afternoon", "పగలు"},
            {act("hour", 18), "సాయంత్రం", "సూర్యాస్తమయం", "evening", "sunset"},
            {act("hour", 21), "రాత్రి", "night", "రాత్రి పూట", "రాత్రి చేయి"},
            {act("hour", 0), "అర్ధరాత్రి", "midnight"},
            {act("walk", ""), "లోపల నడువు", "లోపల నడవాలి", "నడువు", "నడవాలి", "నడక", "నడిచి", "walk", "walk inside", "నడుద్దాం", "లోపలికి నడువు", "లోపల నడక",
                    "నడవనివ్వు", "నడిచి చూడాలి", "లోపల తిరుగు", "లోపలికి నడిచి", "నడిచి వెళ్ళు"},
            {act("walk_off"), "నడక ఆపు", "నడవడం ఆపు", "నడక చాలు", "stop walking", "walk off", "బయటి నుంచి", "నడక వద్దు", "నడక నుంచి బయటికి"},
            {act("walk_next"), "తర్వాతి చోటు", "వేరే చోటు", "ఇంకో చోటు", "next place", "next spot", "ఇంకో చోటుకి"},
            {act("peel", 1), "పైకప్పు తీయి", "పైకప్పు తీయ్", "పైకప్పు లేపు", "రూఫ్ తీయి", "రూఫ్ తీయ్", "కవర్ తీయి", "కవర్ తీయ్", "గోడలు తీయి", "గోడలు తీయ్",
                    "roof off", "remove roof", "పైకప్పు తీసేయి", "పైకప్పు తీసేయ్", "పైకప్పు ఎత్తు", "గోడలు లేపు"},
            {act("peel", -1), "పైకప్పు పెట్టు", "రూఫ్ పెట్టు", "గోడలు పెట్టు", "కవర్ పెట్టు", "roof on"},
            {act("peel", 0), "అన్ని పొరలు పెట్టు", "పొరలన్నీ పెట్టు", "అన్నీ మూసేయి", "all layers back", "పైకప్పులన్నీ పెట్టు"},
    };

    /** 🕐 "సాయంత్రం 7", "రాత్రి 9 గంటలు", "6:30 am", "టైమ్ 18": an hour of the day (a part of the day, a marker or minutes is needed). */
    private static final Pattern HOUR = Pattern.compile("^(?:(టైమ్|సమయం|time|గడియారం)\\s*)?(ఉదయం|పొద్దున|తెల్లవారుజాము|మధ్యాహ్నం|సాయంత్రం|రాత్రి|morning|afternoon|evening|night)?\\s*"
            + "(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(గంటలు|గంటలకి|గంటకి|గంటల|గం|am|pm|a m|p m|o'clock|oclock)?"
            + "(?:\\s*(?:కి|కు|కి పెట్టు|పెట్టు|కి మార్చు|కి వెళ్ళు|అవ్వనివ్వు|చేసి))?$");
    /** 🚶 words that take him somewhere ("టర్బైన్ డెక్‌కి వెళ్ళు", "నాసెల్ పైకి తీసుకెళ్ళు"). */
    private static final String[] GO = {"వెళ్ళు", "వెళ్లు", "వెళ్దాం", "వెళ్ళాలి", "వెళ్లాలి", "తీసుకెళ్ళు", "తీసుకెళ్లు", "తీసుకువెళ్ళు", "నడువు", "నడవాలి", "నడిచి", "go to",
            "take me", "walk to", "నిలబడు", "నిలబెట్టు", "నిలబడాలి", "కూర్చో", "కూర్చుంటా", "కూర్చోబెట్టు", "sit in", "sit on"}; // ("డ్రైవర్ సీట్‌లో కూర్చో")

    /** The hour his words name, or −1. */
    static double hourOf(String t) {
        Matcher m = HOUR.matcher(t == null ? "" : t.trim());
        if (!m.find()) return -1;
        String pre = m.group(1), part = m.group(2), mm = m.group(4), mark = m.group(5);
        if (pre == null && part == null && mm == null && mark == null) return -1; // (a bare number is not a time)
        int h = Integer.parseInt(m.group(3));
        int min = mm == null ? 0 : Integer.parseInt(mm);
        if (h > 24 || min > 59) return -1;
        String p = part == null ? "" : part, k = mark == null ? "" : mark;
        if (k.startsWith("p") || p.equals("సాయంత్రం") || p.equals("evening")) { if (h < 12) h += 12; }
        else if (k.startsWith("a") || p.equals("ఉదయం") || p.equals("పొద్దున") || p.equals("తెల్లవారుజాము") || p.equals("morning")) { if (h == 12) h = 0; }
        else if (p.equals("మధ్యాహ్నం") || p.equals("afternoon")) { if (h >= 1 && h <= 6) h += 12; }
        else if (p.equals("రాత్రి") || p.equals("night")) { if (h >= 6 && h < 12) h += 12; else if (h == 12) h = 0; }
        return (h % 24) + min / 60.0;
    }

    /** "6:30 సాయంత్రం": the hour as he reads it (the stage's clock says it the same way). */
    static String hourText(double hr) {
        int H = ((int) Math.floor(hr)) % 24, M = (int) Math.floor((hr - Math.floor(hr)) * 60 / 5) * 5;
        String part = H < 4 ? "రాత్రి" : H < 12 ? "ఉదయం" : H < 16 ? "మధ్యాహ్నం" : H < 20 ? "సాయంత్రం" : "రాత్రి";
        return ((H + 11) % 12 + 1) + ":" + (M < 10 ? "0" : "") + M + " " + part;
    }

    /** 🕐 🚶 It stands outdoors: a sky, the hours, places to walk. */
    boolean outdoor() { return sp.optBoolean("outdoor"); }

    /** 🚶 The places he can walk to (their names). */
    List<String> walkSpots() {
        List<String> l = new ArrayList<>();
        JSONArray w = sp.optJSONArray("walk");
        for (int i = 0; w != null && i < w.length(); i++) if (!w.optString(i).isEmpty()) l.add(w.optString(i));
        return l;
    }

    /** 🚶 The walking place his words name ("టర్బైన్ డెక్‌కి వెళ్ళు" → "టర్బైన్ డెక్"), or null. */
    String walkSpot(String text) {
        String t = " " + low(text) + " ";
        String best = null;
        int bestChars = 0;
        double bestCover = 0;
        for (String spot : walkSpots()) {
            int hit = 0, chars = 0, n = 0;
            for (String w : low(spot).split(" ")) {
                if (w.length() < 2) continue;
                n++;
                if (w.length() <= 3 ? t.contains(" " + w + " ") : t.contains(w)) { hit++; chars += w.length(); }
            }
            if (n == 0 || hit == 0) continue;
            double cover = (double) hit / n;
            if (chars > bestChars || (chars == bestChars && cover > bestCover)) { best = spot; bestChars = chars; bestCover = cover; }
        }
        return best;
    }

    /** 🚩 Its own switches (a dam's flood gates, dust on the panels…): {id, menu, on-line, off-line}. */
    List<String[]> switches() {
        List<String[]> l = new ArrayList<>();
        JSONArray a = sp.optJSONArray("switches");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) l.add(new String[]{o.optString("id"), o.optString("menu"), o.optString("on"), o.optString("off")});
        }
        return l;
    }

    private Act switchIn(String t, String alt, String alt2) {
        JSONArray a = sp.optJSONArray("switches");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            for (String key : new String[]{"offWords", "words"}) {
                JSONArray ws = o.optJSONArray(key);
                for (int j = 0; ws != null && j < ws.length(); j++) {
                    String w = Workshop.norm(ws.optString(j));
                    if (w != null && !w.isEmpty() && (t.equals(w) || alt.equals(w) || alt2.equals(w))) return new Act("switch", "words".equals(key) ? 1 : 0, o.optString("id"));
                }
            }
        }
        return null;
    }

    private static final String GEAR_TAIL = "(?:\\s*(?:లో పెట్టు|లో పెట్టండి|కి మార్చు|కి మార్చండి|వేయి|వేయ్|వెయ్|వెయ్యి|వేయండి|లోకి|లో|కి|పెట్టు))?$";
    private static final Pattern PCT = Pattern.compile("^(?:(?:పవర్|power|లోడ్|load|గ్రిప్|grip|త్రోటిల్|throttle)\\s*)?(\\d{1,3})\\s*(?:%|శాతం|శాతానికి|percent)(?:\\s*(?:పవర్|power|లోడ్|load|గ్రిప్))?$"),
            PCT2 = Pattern.compile("^(?:పవర్|power|లోడ్|load|గ్రిప్|grip|త్రోటిల్|throttle)\\s*(\\d{1,3})$"),
            GEAR = Pattern.compile("^(?:గేర్|gear)\\s*(\\S+)" + GEAR_TAIL + "|^(\\d)\\s*(?:వ|st|nd|rd|th)?\\s*(?:గేర్|gear)" + GEAR_TAIL + "|^(\\S+)\\s+(?:గేర్|gear)" + GEAR_TAIL),
            RATE = Pattern.compile("(?:యూనిట్|unit)\\s*(?:ధర|రేటు|rate|price|కి)?\\s*₹?\\s*(\\d{1,2}(?:\\.\\d{1,2})?)|₹?\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s*(?:రూపాయలు|రూపాయిలు|rupees|rs)?\\s*(?:ఒక\\s*)?(?:యూనిట్|per unit)");

    private static final Map<String, Integer> NUM = new LinkedHashMap<>();
    static {
        String[][] n = {{"1", "ఒకటి", "ఒకటో", "ఫస్ట్", "first", "one", "మొదటి", "మొదటిది"}, {"2", "రెండు", "రెండో", "సెకండ్", "second", "two"},
                {"3", "మూడు", "మూడో", "థర్డ్", "third", "three"}, {"4", "నాలుగు", "నాలుగో", "ఫోర్త్", "fourth", "four", "టాప్", "top"}};
        for (String[] g : n) for (String w : g) NUM.put(w, Integer.parseInt(g[0]));
    }

    /** Colour words for the 🎨 lights (P). */
    private static final String[][] LIGHT = {{"#ff3b3b", "ఎరుపు", "ఎర్ర", "red"}, {"#3b82ff", "నీలం", "నీలి", "blue"}, {"#2ee66b", "పచ్చ", "ఆకుపచ్చ", "green"},
            {"#ffd23b", "పసుపు", "yellow"}, {"#f2f4f6", "తెలుపు", "తెల్ల", "white"}, {"#b06bff", "ఊదా", "purple", "violet"}, {"#ff6bd0", "గులాబీ", "pink"},
            {"#ff8a2a", "నారింజ", "orange"}, {"#4fd2ff", "సియాన్", "cyan", "ఆకాశ నీలం"}};

    /** One thing he asked (₹0), or null: then the usual workshop words, a fact, or the AI. */
    Act parse(String text) {
        String t = Workshop.norm(text);
        if (t == null || t.isEmpty()) return null;
        t = t.replace("ఈ ", "").replace("ఆ ", "").trim();
        String alt = t.replaceAll("యి(?= |$)", "య్"), alt2 = t.replaceAll("య్(?= |$)", "యి"); // ("తీయి" / "తీయ్": both spellings)
        Act sw = switchIn(t, alt, alt2); // (🚩 its own switches first: "స్పిల్‌వే", "దుమ్ము", "మబ్బులు")
        if (sw != null) return sw;
        for (Object[] row : WORDS) for (int i = 1; i < row.length; i++) if (t.equals(row[i]) || alt.equals(row[i]) || alt2.equals(row[i])) return (Act) row[0];
        if (outdoor()) { // 🕐 an hour; 🚶 a place to walk to
            double hr = hourOf(t);
            if (hr >= 0) return act("hour", hr);
            boolean go = false;
            for (String g : GO) if (t.contains(g)) { go = true; break; }
            if (go) {
                String spot = walkSpot(t);
                if (spot != null) return act("walk", spot);
                if (t.contains("లోపల") || t.contains("నడ") || t.contains("walk")) return act("walk", "");
            }
        }
        Matcher m = PCT.matcher(t);
        if (!m.find()) m = PCT2.matcher(t);
        else m.reset();
        if (m.find()) { int p = Integer.parseInt(m.group(1)); if (p <= 120) return act("power", p > 100 ? 1.15 : p / 100.0); }
        m = GEAR.matcher(t);
        if (m.find()) {
            String w = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            Integer g = NUM.get(w == null ? "" : w.trim());
            if (g != null) return act("gear", g);
        }
        m = RATE.matcher(t);
        if (m.find()) {
            double r = Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
            if (r > 0 && r <= 50) return act("rate", r);
        }
        // 🎨 the lights in one colour: "లైట్లు ఎరుపు", "rgb blue", "నీలం లైట్లు"
        if (t.contains("లైట్") || t.contains("rgb") || t.contains("light")) for (String[] c : LIGHT) for (int i = 1; i < c.length; i++) if (t.contains(c[i])) return act("rgb", c[0]);
        // 🔁 a variant by its name: "5070 పెట్టు", "rtx 5080"
        JSONObject va = sp.optJSONObject("variants");
        JSONObject vw = va == null ? null : va.optJSONObject("words");
        if (vw != null) for (java.util.Iterator<String> it = vw.keys(); it.hasNext(); ) {
            String v = it.next();
            JSONArray ws = vw.optJSONArray(v);
            for (int i = 0; ws != null && i < ws.length(); i++) if (!ws.optString(i).isEmpty() && t.contains(low(ws.optString(i)))) return act("variant", v);
        }
        // 🔬 "X లోపల" / "inside X"
        if (t.contains("లోపల") || t.contains("inside")) {
            String lab = labelIn(t);
            JSONObject ins = sp.optJSONObject("inside");
            if (lab != null && ins != null && ins.has(nowName(lab))) return act("inside", lab);
        }
        return null;
    }

    /** ⛓️ (X) Several things in one breath ("ఫుల్ పవర్, తర్వాత వేడి చూపించు, ఆ తర్వాత విడదీయి"): each part, or null when any part is not a ₹0 word. */
    List<String> chain(String text) {
        if (text == null) return null;
        String t = " " + text.trim() + " ";
        String join = "(?:ఆ\\s+)?(?:తర్వాత|తరువాత|ఆపై|అప్పుడు|and then|then|మరియు|ఆ పైన)";
        String[] parts = t.split("\\s*[,،;]\\s*(?:" + join + "\\s+)?|\\s+" + join + "\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) if (!p.trim().isEmpty()) out.add(p.trim());
        if (out.size() < 2 || out.size() > 6) return null;
        for (String p : out) if (parse(p) == null && Workshop.command(p) == null) return null;
        return out;
    }

    // ================================================================ 🎮 the games (pure)

    /** 🧑‍🏫 (O) The quiz: its questions in a mixed order, one at a time; answers by number, by name, or by tapping the piece. */
    static final class Quiz {
        final List<JSONObject> qs = new ArrayList<>();
        int at = -1, right;
        final Showpiece owner;

        Quiz(Showpiece owner, long seed) {
            this.owner = owner;
            JSONArray l = owner.sp.optJSONArray("quiz");
            for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null) qs.add(l.optJSONObject(i));
            Collections.shuffle(qs, new Random(seed));
        }

        boolean done() { return at >= qs.size(); }

        /** The next question, said with its three choices; null at the end. */
        String next() {
            at++;
            if (done()) return null;
            JSONObject q = qs.get(at);
            StringBuilder b = new StringBuilder("ప్రశ్న " + (at + 1) + "/" + qs.size() + ": " + q.optString("q"));
            JSONArray o = q.optJSONArray("opts");
            for (int i = 0; o != null && i < o.length(); i++) b.append("\n").append(i + 1).append(") ").append(owner.nowName(o.optString(i)));
            return b.toString();
        }

        /** His answer → {right?, the right name}; null when the words aren't an answer. */
        Object[] check(String said, String tapped) {
            if (at < 0 || done()) return null;
            JSONObject q = qs.get(at);
            JSONArray o = q.optJSONArray("opts");
            String want = owner.nowName(q.optString("a")), got = null;
            if (tapped != null) got = tapped;
            else {
                String t = low(said);
                for (Map.Entry<String, Integer> e : NUM.entrySet()) if ((" " + t + " ").contains(" " + e.getKey() + " ") && o != null && e.getValue() <= o.length()) { got = owner.nowName(o.optString(e.getValue() - 1)); break; }
                if (got == null) {
                    String lab = owner.labelIn(t);
                    if (lab != null) got = owner.nowName(lab);
                }
            }
            if (got == null) return null;
            boolean ok = got.equals(want);
            if (ok) right++;
            return new Object[]{ok, want};
        }

        String score() { return right + "/" + qs.size(); }
    }

    /** 🔧 (C) The fault game: one fault at a time; he finds the piece (tap it or name it); three tries, then Jarvis shows it. */
    static final class Faults {
        final List<JSONObject> fs = new ArrayList<>();
        int at = -1, tries, found;
        final Showpiece owner;

        Faults(Showpiece owner, long seed) {
            this.owner = owner;
            JSONArray l = owner.sp.optJSONArray("faults");
            for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null) fs.add(l.optJSONObject(i));
            Collections.shuffle(fs, new Random(seed));
        }

        boolean done() { return at >= fs.size(); }

        /** The next fault: {label (now), kind, clue}; null at the end. */
        String[] next() {
            at++;
            tries = 0;
            if (done()) return null;
            JSONObject f = fs.get(at);
            return new String[]{owner.nowName(f.optString("label")), f.optString("kind"), f.optString("clue")};
        }

        /** His guess → "right" / "wrong" / "shown" (third wrong: the answer is shown); null when the words name nothing. */
        String guess(String said, String tapped) {
            if (at < 0 || done()) return null;
            String lab = tapped != null ? tapped : owner.labelIn(said);
            if (lab == null) return null;
            String want = owner.nowName(fs.get(at).optString("label"));
            if (owner.nowName(lab).equals(want)) { found++; return "right"; }
            tries++;
            return tries >= 3 ? "shown" : "wrong";
        }

        String why() { return at >= 0 && !done() ? fs.get(at).optString("why") : ""; }
        String want() { return at >= 0 && !done() ? owner.nowName(fs.get(at).optString("label")) : ""; }
        String score() { return found + "/" + fs.size(); }
    }

    /** 🎮 (Y) A challenge from its live numbers: 🛫 takeoff (power to the goal in time, the exhaust under its limit), 🌱 eco ride (a distance under a Wh/km). */
    static final class Challenge {
        final String kind, title, rules;
        final double goal, limit, km, start, end, rate, power;
        final int secs;
        double startKm = -1, startWh, worst;
        long t0 = -1;
        int inBand, samples, shedMost;

        Challenge(JSONObject c) {
            kind = c.optString("kind");
            title = c.optString("title");
            rules = c.optString("rules");
            goal = c.optDouble("goal", 0.95);
            limit = c.optDouble("limit", 0);
            km = c.optDouble("km", 1);
            secs = c.optInt("secs", 12);
            start = c.optDouble("start", 17);
            end = c.optDouble("end", 22);
            rate = c.optDouble("rate", 0.1);
            power = c.optDouble("power", 0.35);
        }

        /** The live numbers now (nums from the stage) at ms → null (going on), or {won?, the line to say}. */
        Object[] step(JSONObject nums, long ms) {
            if (nums == null) return null;
            if (t0 < 0) { t0 = ms; startKm = nums.optDouble("km", 0); startWh = nums.optDouble("wh", 0); worst = 0; }
            double secsNow = (ms - t0) / 1000.0;
            if ("takeoff".equals(kind)) {
                double egt = nums.optDouble("egt", 0), p = nums.optDouble("power", 0);
                worst = Math.max(worst, egt);
                if (limit > 0 && egt > limit) return new Object[]{false, "ఎగ్జాస్ట్ వేడి " + Math.round(egt) + "°C, హద్దు " + Math.round(limit) + "°C దాటింది Sir. మళ్ళీ ప్రయత్నించండి: పవర్ మెల్లగా పెంచండి."};
                if (p >= goal) return new Object[]{true, "🛫 టేకాఫ్! " + String.format(Locale.ENGLISH, "%.1f", secsNow) + " సెకన్లలో " + Math.round(goal * 100) + "% పవర్, గరిష్ట వేడి " + Math.round(worst) + "°C. బాగా చేశారు Sir!"};
                if (secsNow > secs) return new Object[]{false, secs + " సెకన్లు అయిపోయాయి Sir, పవర్ " + Math.round(p * 100) + "% దగ్గరే ఉంది. మళ్ళీ ప్రయత్నించండి."};
                return null;
            }
            if ("grid".equals(kind)) { // ⚡ the evening peak: his hydel keeps the frequency in its band till 10 pm
                double f = nums.optDouble("freq", 50), hr = nums.optDouble("hour", -1);
                if (hr >= 0 && hr < start - 0.3) return null; // (the clock is not on its first hour yet)
                samples++;
                if (f >= 49.9 && f <= 50.1) inBand++;
                shedMost = Math.max(shedMost, nums.optInt("shed", 0));
                if (hr < end - 0.02 && secsNow <= secs) return null;
                double share = (double) inBand / Math.max(1, samples);
                boolean won = share >= goal;
                String pct = Math.round(share * 100) + "%";
                return new Object[]{won, (won ? "⚡ గెలిచారు Sir! " : "అయ్యో Sir, ") + "సాయంత్రం 5 నుంచి రాత్రి 10 దాకా " + pct + " సమయం ఫ్రీక్వెన్సీ 49.9–50.1 Hz లో ఉంచారు"
                        + (won ? " (కావాల్సింది " + Math.round(goal * 100) + "%). ఇక మీరే గ్రిడ్ ఆపరేటర్!"
                        : " (కావాల్సింది " + Math.round(goal * 100) + "%). సూర్యుడు అస్తమిస్తున్నప్పుడే (6–8) హైడల్ ముందే పెంచి మళ్ళీ ప్రయత్నించండి.")
                        + (shedMost > 0 ? " మధ్యలో " + shedMost + " కాలనీలు కొద్దిసేపు ఆగాయి." : "")};
            }
            if ("eco".equals(kind)) {
                double d = nums.optDouble("km", 0) - startKm, wh = nums.optDouble("wh", 0) - startWh;
                if (d >= km) {
                    double whkm = wh / Math.max(1e-6, d);
                    boolean won = whkm <= limit;
                    return new Object[]{won, (won ? "🌱 గెలిచారు Sir! " : "అయ్యో Sir, ") + String.format(Locale.ENGLISH, "%.1f", d) + " km ని సగటు " + Math.round(whkm) + " Wh/km తో వెళ్ళారు (హద్దు " + Math.round(limit) + ")."
                            + (won ? "" : " గ్రిప్ మెల్లగా, పై గేర్లు వాడి మళ్ళీ ప్రయత్నించండి.")};
                }
                if (secsNow > secs) return new Object[]{false, "సమయం అయిపోయింది Sir: " + String.format(Locale.ENGLISH, "%.2f", d) + " km మాత్రమే వెళ్ళారు. మళ్ళీ ప్రయత్నించండి."};
                return null;
            }
            return new Object[]{false, ""};
        }
    }

    /** 🔋 (Q) ₹ for 100 km at his per-unit rate, from Wh/km (approximate). */
    static long rupeesPer100(double whPerKm, double rate) { return Math.round(whPerKm * 100 / 1000 * rate); }

    /** The next variant after the one on the stage (🔁 S, "కార్డ్ మార్చు"). */
    String nextVariant() {
        JSONObject va = sp.optJSONObject("variants");
        JSONObject o = va == null ? null : va.optJSONObject("options");
        if (o == null) return null;
        List<String> l = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) l.add(it.next());
        if (l.isEmpty()) return null;
        int i = l.indexOf(variant);
        return l.get((i + 1) % l.size());
    }

    /** The part's code with the variant set (🔁 S): {part id, code}, or null. */
    String[] variantCode(String v, String code) {
        JSONObject va = sp.optJSONObject("variants");
        if (va == null || code == null || v == null || !v.matches("[A-Za-z0-9]{1,12}")) return null;
        JSONObject o = va.optJSONObject("options");
        if (o == null || !o.has(v)) return null;
        String line = va.optString("line"), pat = va.optString("pattern");
        java.util.regex.Matcher m = Pattern.compile(Pattern.quote(pat).replace("{v}", "\\E[A-Za-z0-9]{1,12}\\Q")).matcher(code);
        if (!m.find()) return code.contains(line) ? new String[]{va.optString("part"), code.replace(line, pat.replace("{v}", v))} : null;
        return new String[]{va.optString("part"), code.substring(0, m.start()) + pat.replace("{v}", v) + code.substring(m.end())};
    }

    String variantSay(String v) {
        JSONObject va = sp.optJSONObject("variants");
        JSONObject o = va == null ? null : va.optJSONObject("options");
        JSONObject x = o == null ? null : o.optJSONObject(v);
        return x == null ? "" : x.optString("say");
    }
}
