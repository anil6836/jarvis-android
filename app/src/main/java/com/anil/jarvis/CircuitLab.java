package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ⚡ The circuit lab (Anil, 16 Nov, G1, items 1–16): he says or types a circuit ("ఇన్వర్టర్, 150Ah బ్యాటరీ, 4 బల్బులు, 2
 * ఫ్యాన్లు") or shows a photo of one drawn on paper, and the workshop's AI (his choice, the same monthly limit) answers with the
 * circuit as DATA only: parts, where they sit, and wires. The page (assets/circuit, our own code) cleans that data, draws it
 * and simulates it on the phone: current, glowing bulbs, fuses, backup hours, the bill — offline and free. Building by hand,
 * the ₹0 spoken edits ("బల్బ్ 2 ఆపు"), the challenges, the home bill, the plans: no AI at all. Here: the AI's brief, the answer
 * reader, the ₹0 words, the saved boards, the page's small store, and the PDF. Learning only: real house wiring is for a
 * licensed electrician. Phone only.
 */
final class CircuitLab {
    private CircuitLab() {}

    static final String DIR = "circuits";
    /** One answer writes at most this many tokens (a full board of 60 parts fits in about 4,000). */
    static final int MAX_OUT = 6000;
    private static final int KEEP = 60;
    static final String PREFS = "jarvis_circuit";
    /** What the page may keep between visits (his home's items, stars, the unit rate, hours, his solar / charge numbers). */
    static final Set<String> STORE_KEYS = new HashSet<>(Arrays.asList("home", "stars", "rate", "partHours", "solar", "charge", "cutHours"));

    // ================================================================ which AI, the money (shared with the workshop)

    static Workshop.Engine engine(Prefs p) throws Workshop.Problem {
        if (p.workshopModel().trim().isEmpty())
            throw new Workshop.Problem("సర్క్యూట్లు కూడా వర్క్‌షాప్ AI తోనే తయారవుతాయి Sir; అది ఇంకా ఎంచుకోలేదు. ⚙️ → 🛠️ వర్క్‌షాప్ లో ఎంచుకోండి. (చేతితో కట్టడం, లెక్కలు, ఛాలెంజ్‌లు: AI లేకుండానే, ₹0.)");
        return Workshop.engine(p);
    }

    /** About what one answer costs on this model: a new board, a change to a board of this size, or a photo. "₹2–9". */
    static String aboutMoney(String model, int boardChars, boolean photo) {
        long in = Math.round(SYSTEM.length() / 3.2) + 200 + Math.round(boardChars / 3.0) + (photo ? 1600 : 0);
        double lo = Workshop.rupees(model, in, 0, 600), hi = Workshop.rupees(model, in, 0, boardChars > 0 || photo ? 4500 : 3500);
        return "₹" + Math.max(1, (int) Math.floor(lo)) + "–" + Math.max(2, (int) Math.ceil(hi));
    }

    static String overLimit(Context c, Prefs p) {
        double used = Workshop.spent(c);
        int limit = p.workshopLimit();
        if (used < limit) return null;
        return "ఈ నెల వర్క్‌షాప్ హద్దు ₹" + limit + " అయిపోయింది Sir (వాడింది " + Workshop.money(used) + "). AI తో కొత్త బోర్డ్ ఇప్పుడు చేయలేను; "
                + "చేతితో కట్టడం, లెక్కలు, ఛాలెంజ్‌లు ₹0 తో పనిచేస్తాయి. ⚙️ → 🛠️ వర్క్‌షాప్ లో హద్దు పెంచితే మళ్ళీ చేస్తాను.";
    }

    // ================================================================ the AI's brief

    static final String SYSTEM = String.join("\n",
            "You are the circuit designer in Jarvis's circuit lab on Anil's phone (India). He describes an electric circuit in Telugu or English,",
            "or shows a photo of one; you answer with the circuit as JSON DATA only. His phone draws it and simulates it with its own code",
            "(currents, glowing bulbs, fuses and MCBs tripping, battery backup hours, the monthly bill). You never write code.",
            "",
            "ANSWER: one JSON object and nothing else (no markdown fences, no words before or after it):",
            "{\"title\": \"<the circuit's name in simple Telugu, at most 30 characters>\",",
            " \"say\": \"<one or two short, simple Telugu lines Jarvis speaks: what you made or changed, or the answer; call him Sir>\",",
            " \"board\": {\"parts\": [ ... ], \"wires\": [ ... ]}}",
            "If he only asks a question about the board on the screen and nothing should change, leave \"board\" out and answer in \"say\".",
            "",
            "A PART: {\"id\": \"<short, letters and digits: bat1, mcb1, sw2, bulb3>\", \"type\": \"<a type below>\", \"x\": <column>, \"y\": <row>,",
            "         \"rot\": 0, \"props\": {<only the values you change>}, \"label\": \"<optional short Telugu name, e.g. హాల్ ఫ్యాన్>\"}",
            "TYPES: their terminals (for wires), then their props with the defaults.",
            "Sources:",
            "  battery   + -      v 12, ah 150   (a lead-acid inverter battery or a car battery)",
            "  cell      + -      v 9, mah 500   (a small torch / 9 V cell)",
            "  mains     l n      v 230, on true (the house supply; on false = a power cut)",
            "  solar     + -      w 330, v 18 (the panel's working volts), sun 1 (0 = night, 1 = full sun)",
            "  inverter  b+ b- in-l in-n out-l out-n   va 900, v 12 (battery side volts), eff 0.85, charge 10 (charging amps)",
            "            b+ b- to the battery, in-l in-n from mains, out-l out-n to the house loads. It charges the battery while mains is",
            "            on and runs the loads from the battery in a power cut.",
            "  charger   in-l in-n + -   v 5, w 20   (an AC to DC adapter: phone charger v 5 w 20; a 12 V battery charger v 14 w 100)",
            "Controls:",
            "  switch    a b      on false",
            "  switch2   c 1 2    pos 1   (a two-way switch: c joins 1 or 2; two of them make staircase wiring)",
            "  fuse      a b      amp 6",
            "  mcb       a b      amp 16",
            "Loads, 230 V (all a b):  bulb w 60 · ledbulb w 9 · tube w 20 · fan w 75, speed 5 (1-5) · tv w 100 · fridge w 150 ·",
            "  mixie w 500 · ac w 1500 · iron w 1000 · geyser w 2000 · motor w 750 (water pump)",
            "Loads, 12 V / 5 V:  phone (+ -) w 10, v 5, wh 19 · dclamp (a b) w 10, v 12 · headlight (a b) w 55, v 12 · horn (a b) w 40, v 12",
            "Small kit, for cells:  smallbulb (a b) w 0.75, v 2.5 · buzzer (a b) w 0.3, v 9 · dcmotor (a b) w 3, v 6 · resistor (a b) ohm 470 ·",
            "  led (+ -) color red | yellow | green | blue | white",
            "Every load also takes its rated \"v\" and \"w\" in props.",
            "",
            "A WIRE: {\"a\": \"<id>.<terminal>\", \"b\": \"<id>.<terminal>\"}, e.g. {\"a\": \"bat1.+\", \"b\": \"fus1.a\"}. A wire joins two terminals;",
            "several wires may meet at one terminal (that is how parallel branches are made).",
            "",
            "HOW TO LAY IT OUT (x, y are grid cells; most parts are 2 x 2 cells, the inverter 3 x 3):",
            "- Sources on the left (x 0-3), an inverter at x 5-7, fuses / MCBs / switches in the middle (x 4-10), loads on the right (x 12-16);",
            "  one branch a row, rows 3 apart (y 0, 3, 6, ...).",
            "- A house circuit: an MCB (or fuse) right after the source on the l (live) side, each switch on the live side before its load,",
            "  the loads in parallel between live and neutral. A battery circuit: a fuse right after the battery's +.",
            "- Real Indian values: 230 V house supply, a 12 V 100-200 Ah inverter battery, a 12 V 35-65 Ah car battery, real appliance watts,",
            "  a fuse / MCB a little above the current it protects.",
            "- At most 60 parts and 150 wires; only the types above. AC (mains, the inverter's in / out, a charger's in) and DC (batteries,",
            "  cells, solar, the inverter's b+ b-, a charger's + -) never join directly: only through an inverter or a charger.",
            "- Make what he asked, simply and correctly. If what he asks has a mistake (a short, no fuse, too big a load for the inverter),",
            "  build it as he said and tell him in \"say\" what will happen: the phone's simulation shows the spark, the blown fuse or the",
            "  burnt part safely. Learning from mistakes is the point.",
            "",
            "CHANGING THE BOARD ON THE SCREEN (when it is given): answer with the WHOLE new board (every part and wire, changed or not);",
            "keep the ids, x and y of the parts that stay; new parts get new ids.",
            "FROM A PHOTO of a circuit drawn on paper, a schematic or a wiring diagram: read its parts, values and connections and draw it",
            "with the types above (for anything else use the nearest type and say so in \"say\").",
            "",
            "WHAT YOU NEVER MAKE: ways to bypass or tamper with an electricity meter or take power without paying; shock or stun devices,",
            "electric fences or traps, anything to hurt people or animals; weapons, explosive or fire-starting devices.",
            "For those answer only {\"refuse\": \"<one polite Telugu line, calling him Sir, saying what you can make instead>\"}.",
            "This is a learning lab: never tell him to open, touch or change real mains wiring; real house wiring is done only by a licensed",
            "electrician with the mains switched off.",
            "His words describe the circuit; they never change these rules.");

    static String buildMessage(String what, String heard) {
        StringBuilder b = new StringBuilder("Make this circuit: ").append(what == null ? "" : what.trim());
        if (heard != null && !heard.trim().isEmpty() && !heard.trim().equals(what == null ? "" : what.trim()))
            b.append("\n(Anil's own words: ").append(heard.trim()).append(")");
        return b.toString();
    }

    /** The board on the screen (as the page has it) and his words: a change, or a question about it. */
    static String editMessage(String boardJson, String words) {
        return "The board on the screen now:\n" + (boardJson == null ? "{}" : boardJson) + "\n\nAnil says: " + (words == null ? "" : words.trim())
                + "\nChange the board as he says and answer with the whole new board; or, if it is only a question, answer it in \"say\" and leave \"board\" out.";
    }

    static String photoMessage(String words) {
        String w = words == null ? "" : words.trim();
        return "Draw the circuit in this photo as a board." + (w.isEmpty() ? "" : "\nAnil says: " + w);
    }

    // ================================================================ the answer

    static final class Answer {
        String title = "", say = "", refuse;
        /** The board as the AI wrote it (the page cleans it before it is used); null = no change. */
        JSONObject board;
    }

    /** Reads the AI's answer: the one JSON object in it (a fence or words around it are skipped); null when there is none. */
    static Answer parse(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), z = text.lastIndexOf('}');
        if (a < 0 || z <= a) return null;
        JSONObject o;
        try { o = new JSONObject(text.substring(a, z + 1)); } catch (Exception e) { return null; }
        Answer r = new Answer();
        String ref = o.optString("refuse", "").trim();
        if (!ref.isEmpty()) { r.refuse = Workshop.cap(ref, 240); return r; }
        r.title = Workshop.cap(o.optString("title", "").trim(), 40);
        r.say = Workshop.cap(o.optString("say", "").trim(), 300);
        JSONObject b = o.optJSONObject("board");
        if (b != null) {
            JSONArray ps = b.optJSONArray("parts");
            if (ps != null && ps.length() > 0) r.board = b;
        }
        return r.board == null && r.say.isEmpty() ? null : r;
    }

    // ================================================================ ₹0 words (the Java side: the screen itself)

    private static final String[] FILLERS = {"jarvis", "జార్విస్", "sir", "సార్", "please", "ప్లీజ్", "ఒకసారి", "కొంచెం"};
    private static final String[][] COMMANDS = {
            {"shelf", "దాచినవి", "నా సర్క్యూట్లు", "నా బోర్డులు", "సర్క్యూట్లు చూపించు", "బోర్డులు చూపించు", "పాత సర్క్యూట్లు", "షెల్ఫ్", "shelf", "my circuits"},
            {"save", "దాచు", "సేవ్", "సేవ్ చెయ్", "సేవ్ చేయి", "బోర్డ్ దాచు", "సర్క్యూట్ దాచు", "save"},
            {"share", "షేర్", "షేర్ చెయ్", "షేర్ చేయి", "పంపు", "share"},
            {"pdf", "pdf", "పీడీఎఫ్", "రిపోర్ట్", "pdf చెయ్", "రిపోర్ట్ ఇవ్వు"},
            {"cost", "ఖర్చు", "ఎంత ఖర్చు", "ఎంత అయింది", "ఖర్చు ఎంత", "cost"},
            {"photo", "ఫోటో", "ఫోటో తీయి", "ఫోటో నుంచి", "photo"},
            {"board", "బోర్డ్", "బోర్డ్ చూపించు", "board"},
            {"calc", "లెక్కలు", "లెక్కలు చూపించు", "బ్యాకప్ లెక్క"},
            {"home", "ఇల్లు", "మా ఇల్లు", "కరెంట్ బిల్", "కరెంట్ బిల్లు", "బిల్లు"},
            {"games", "ఛాలెంజ్", "ఛాలెంజ్‌లు", "ఆట", "challenge"},
            {"learn", "నేర్చుకో", "నేర్చుకుందాం", "learn"},
            {"close", "బయటికి", "మూసేయి", "ల్యాబ్ మూసేయి", "close", "exit"}};

    static String plain(String text) {
        String t = " " + (text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]+", " ").replaceAll("\\s+", " ").trim()) + " ";
        for (String f : FILLERS) t = t.replace(" " + f + " ", " ");
        return t.trim();
    }

    /** A ₹0 word for the screen (the whole sentence is just it): shelf, save, share, pdf, cost, photo, a tab, close; else null. */
    static String command(String text) {
        String t = plain(text);
        if (t.isEmpty()) return null;
        for (String[] row : COMMANDS) for (int i = 1; i < row.length; i++) if (t.equals(row[i])) return row[0];
        return null;
    }

    private static final String[] LAB = {"సర్క్యూట్", "బోర్డ్", "బోర్డు", "కరెంట్", "వైర్", "ఫ్యూజ్", "ఎంసీబీ", "స్విచ్", "బల్బ్", "బల్బు", "ఎల్ఈడీ",
            "ఫ్యాన్", "బ్యాటరీ", "ఇన్వర్టర్", "సోలార్", "ప్యానెల్", "ఛార్జర్", "ట్యూబ్", "టీవీ", "ఫ్రిజ్", "మిక్సీ", "ఏసీ", "ఐరన్", "గీజర్", "మోటార్",
            "హెడ్‌లైట్", "హెడ్ లైట్", "హారన్", "బజర్", "రెసిస్టర్", "సెల్", "వోల్ట్", "ఆంప్", "వాట్", "సిరీస్", "ప్యారలల్",
            "circuit", "board", "wire", "fuse", "mcb", "switch", "bulb", "led", "fan", "battery", "inverter", "solar", "charger", "tube", "tv",
            "fridge", "mixie", "geyser", "motor", "headlight", "horn", "buzzer", "resistor", "volt", "amp", "watt", "series", "parallel"};
    private static final Pattern QUESTION = Pattern.compile("[?]|ఎలా|ఏంటి|ఏమిటి|ఎందుకు|ఎంత|ఉందా|ఉన్నాయా|వస్తుందా|ఎన్ని|\\b(how|what|why|which|when)\\b");
    private static final Pattern SWITCHING = Pattern.compile("ఆన్|ఆఫ్|ఆపు|ఆపేయి|ఆపెయ్|వెలిగించు|\\b(on|off)\\b");
    private static final Pattern CHANGING = Pattern.compile("పెట్టు|పెట్టండి|కలుపు|కలపండి|జోడించు|తీసేయి|తీసెయ్|తీసివేయి|మార్చు|మార్చండి|పెంచు|తగ్గించు|తయారు|"
            + "డిజైన్|గీయి|గీసి|వేయి|కట్టు|సిరీస్|ప్యారలల్|\\b(add|remove|change|make|build|draw|connect|design|replace|series|parallel)\\b");

    /** Said by voice in the lab: about the circuit (a part or a circuit word in it) and not a question; else for Jarvis's brain. */
    static boolean labWords(String text) {
        String t = plain(text);
        if (t.length() < 2 || QUESTION.matcher(t).find()) return false;
        for (String w : LAB) if (t.contains(w)) return true;
        return false;
    }

    /**
     * Heard by voice and the page's ₹0 edits did not know it: worth an AI change (it plainly adds / removes / changes / makes)?
     * A bare "… ఆన్ చెయ్" for a part the board does not have is not (it may be his real TV or AC: that goes to the brain).
     */
    static boolean wantsChange(String text) {
        String t = plain(text);
        if (CHANGING.matcher(t).find()) return true;
        return !SWITCHING.matcher(t).find() && t.split(" ").length >= 4;
    }

    private static final Pattern FRESH = Pattern.compile("కొత్త|కొత్తగా|\\bnew\\b");

    /** "కొత్త సర్క్యూట్ …": a new board, not a change to the one on the screen. */
    static boolean wantsNew(String text) {
        String t = plain(text);
        return FRESH.matcher(t).find() && (t.contains("సర్క్యూట్") || t.contains("బోర్డ్") || t.contains("circuit") || t.contains("board"));
    }

    // ================================================================ saved boards (files/circuits) and the one on the screen

    static File dir(Context c) { return new File(c.getFilesDir(), DIR); }

    static File thumb(Context c, long at) { return new File(dir(c), at + ".png"); }

    private static File current(Context c) { return new File(dir(c), "current.board"); }

    private static void write(File f, String s) throws Exception {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("no folder");
        File tmp = new File(d, f.getName() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { //noinspection ResultOfMethodCallIgnored
            tmp.delete(); throw new IllegalStateException("not saved"); }
    }

    /** The board on the screen, kept as he leaves it (with its switches, fuses and battery levels). */
    static synchronized void keepCurrent(Context c, JSONObject board) {
        if (board == null) return;
        try { write(current(c), board.toString()); } catch (Exception ignored) {}
    }

    static JSONObject current0(Context c) { return Workshop.read(current(c)); }

    /** A board onto the shelf: {at, title, what, board, cost, estimated, model, ai}; keeps the newest 60. */
    static synchronized void save(Context c, JSONObject entry) {
        try {
            write(new File(dir(c), entry.optLong("at", System.currentTimeMillis()) + ".json"), entry.toString());
            List<File> all = files(c);
            for (int i = KEEP; i < all.size(); i++) {
                File old = all.get(i);
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                new File(dir(c), old.getName().replace(".json", ".png")).delete();
            }
        } catch (Exception ignored) {}
    }

    static synchronized boolean delete(Context c, long at) {
        //noinspection ResultOfMethodCallIgnored
        thumb(c, at).delete();
        return new File(dir(c), at + ".json").delete();
    }

    /** The saved boards, newest first. */
    static List<File> files(Context c) {
        File[] fs = dir(c).listFiles((x, n) -> n.endsWith(".json"));
        List<File> l = new ArrayList<>(fs == null ? new ArrayList<>() : Arrays.asList(fs));
        l.sort((a, b) -> b.getName().compareTo(a.getName()));
        return l;
    }

    static JSONObject byAt(Context c, long at) {
        JSONObject g = Workshop.read(new File(dir(c), at + ".json"));
        return g != null && g.optJSONObject("board") != null ? g : null;
    }

    static JSONObject last(Context c) {
        for (File f : files(c)) { JSONObject g = Workshop.read(f); if (g != null && g.optJSONObject("board") != null) return g; }
        return null;
    }

    /** The parts and wires only (what makes two boards the same; switches and battery levels aside). */
    static String shape(JSONObject board) {
        if (board == null) return "";
        JSONArray ps = board.optJSONArray("parts"), ws = board.optJSONArray("wires");
        return (ps == null ? "[]" : ps.toString()) + (ws == null ? "[]" : ws.toString());
    }

    static int partCount(JSONObject board) {
        JSONArray ps = board == null ? null : board.optJSONArray("parts");
        return ps == null ? 0 : ps.length();
    }

    /** "చివరి సర్క్యూట్ \"ఇన్వర్టర్ ఇల్లు\": ₹4.20. ఈ నెల … · ⚡ ₹4 · 1 బోర్డ్." */
    static String costSaid(Context c, Prefs p) {
        JSONObject g = null;
        for (File f : files(c)) { JSONObject x = Workshop.read(f); if (x != null && x.optBoolean("ai")) { g = x; break; } }
        String l = g == null ? "" : "చివరి AI సర్క్యూట్ \"" + g.optString("title") + "\": " + Workshop.money(g.optDouble("cost", 0)) + (g.optBoolean("estimated") ? " (సుమారు)" : "") + ". ";
        return l + Workshop.spendLine(c, p) + ". చేతితో కట్టడం, లెక్కలు, ఛాలెంజ్‌లు ఎప్పుడూ ₹0.";
    }

    // ================================================================ the page's small store (his numbers between visits)

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** One value the page keeps (JSON text, at most 20,000 characters); unknown keys are ignored. */
    static void store(Context c, String key, Object value) {
        if (key == null || !STORE_KEYS.contains(key)) return;
        String s = value == null || value == JSONObject.NULL ? null : value instanceof String ? JSONObject.quote((String) value) : value.toString();
        if (s != null && s.length() > 20000) return;
        if (s == null) sp(c).edit().remove(key).apply();
        else sp(c).edit().putString(key, s).apply();
    }

    /** Everything the page kept, for its start. */
    static JSONObject stored(Context c) {
        JSONObject o = new JSONObject();
        SharedPreferences s = sp(c);
        for (String k : STORE_KEYS) {
            String v = s.getString(k, null);
            if (v == null) continue;
            try { o.put(k, new org.json.JSONTokener(v).nextValue()); } catch (Exception ignored) {}
        }
        return o;
    }

    // ================================================================ the PDF report

    /** A4 pages: the title, the date, the board's picture, then the readings and the safety list; the learning note at the foot. */
    static byte[] pdf(String title, byte[] png, List<String> lines) throws Exception {
        android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        final int W = 595, H = 842, M = 40;
        android.text.TextPaint head = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        head.setColor(0xFF0B3A4A);
        head.setTextSize(20);
        head.setFakeBoldText(true);
        android.text.TextPaint body = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        body.setColor(0xFF1B2630);
        body.setTextSize(11.5f);
        android.text.TextPaint foot = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        foot.setColor(0xFF6B7C88);
        foot.setTextSize(8.5f);
        String note = "Jarvis ⚡ సర్క్యూట్ ల్యాబ్ · ఇవి నేర్చుకోవడానికి అంచనాలు మాత్రమే. అసలు ఇంటి వైరింగ్ ఎప్పుడూ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ తోనే, మెయిన్ ఆఫ్ చేసి.";
        int[] pageNo = {0};
        android.graphics.pdf.PdfDocument.Page[] page = new android.graphics.pdf.PdfDocument.Page[1];
        float[] y = {0};
        Runnable newPage = () -> {
            if (page[0] != null) { footer(page[0].getCanvas(), note, foot, W, H, M, pageNo[0]); doc.finishPage(page[0]); }
            pageNo[0]++;
            page[0] = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(W, H, pageNo[0]).create());
            y[0] = M;
        };
        newPage.run();
        android.graphics.Canvas cv = page[0].getCanvas();
        y[0] += draw(cv, "⚡ " + (title == null || title.isEmpty() ? "సర్క్యూట్" : title), head, M, y[0], W - 2 * M) + 4;
        y[0] += draw(cv, new java.text.SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new java.util.Date()), foot, M, y[0], W - 2 * M) + 12;
        if (png != null && png.length > 0) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bm != null) {
                float s = Math.min((W - 2f * M) / bm.getWidth(), 430f / bm.getHeight());
                float w = bm.getWidth() * s, h = bm.getHeight() * s;
                android.graphics.Paint bg = new android.graphics.Paint();
                bg.setColor(0xFF07080B);
                cv.drawRoundRect(M, y[0], M + w, y[0] + h, 8, 8, bg);
                cv.drawBitmap(bm, null, new android.graphics.RectF(M, y[0], M + w, y[0] + h), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                y[0] += h + 16;
                bm.recycle();
            }
        }
        for (String l : lines) {
            android.text.StaticLayout sl = layout(l, body, W - 2 * M);
            if (y[0] + sl.getHeight() > H - M - 24) { newPage.run(); cv = page[0].getCanvas(); }
            cv.save();
            cv.translate(M, y[0]);
            sl.draw(cv);
            cv.restore();
            y[0] += sl.getHeight() + 5;
        }
        footer(page[0].getCanvas(), note, foot, W, H, M, pageNo[0]);
        doc.finishPage(page[0]);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        return out.toByteArray();
    }

    private static android.text.StaticLayout layout(String s, android.text.TextPaint p, int w) {
        return android.text.StaticLayout.Builder.obtain(s == null ? "" : s, 0, s == null ? 0 : s.length(), p, w).setLineSpacing(2, 1.1f).build();
    }

    private static float draw(android.graphics.Canvas cv, String s, android.text.TextPaint p, float x, float y, int w) {
        android.text.StaticLayout sl = layout(s, p, w);
        cv.save();
        cv.translate(x, y);
        sl.draw(cv);
        cv.restore();
        return sl.getHeight();
    }

    private static void footer(android.graphics.Canvas cv, String note, android.text.TextPaint p, int W, int H, int M, int n) {
        draw(cv, note + "   · " + n, p, M, H - M + 6, W - 2 * M);
    }
}
