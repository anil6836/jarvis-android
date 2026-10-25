package com.anil.jarvis;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * 🎛️ The car screen's own choices, kept pure (no Android here: desk-tested). Which six buttons and in what order, what
 * ②'s three small chrome dials show (and their words), the map's colour and its zoom with the speed, Jarvis's and the
 * words' size, the 3D shapes' lean (a true perspective from a card's rectangle to its leaning outline — and back, so a
 * finger lands where it sees a button), and "నా థీమ్ 1–3": the look's settings kept and put back (the look only:
 * no photos, no places, nothing private).
 */
final class CarDeck {
    private CarDeck() {}

    // ================================================================ the six buttons

    static final String[] BUTTONS = {"talk", "maps", "way", "music", "fuel", "home", "sleep", "games", "apps"};
    static final String[] BUTTON_NAMES = {"🎤 మాట్లాడు", "🗺️ Jarvis Maps", "🧭 దారి (ఇల్లు, నా చోట్లు)", "🎵 పాటలు", "⛽ డీజిల్ కొట్టించాను",
            "🏠 ఇల్లు (లైట్లు, ఇంటికి)", "😴 నిద్ర అలర్ట్", "🎲 ప్రయాణ ఆటలు", "▦ box ఆప్స్"};
    static final String[] BUTTON_LABELS = {"మాట్లాడు", "Jarvis Maps", "దారి", "పాటలు", "డీజిల్", "ఇల్లు", "నిద్ర", "ఆటలు", "ఆప్స్"};
    /** ⑥ ④ ⑦ as they always were; the 3D shapes (① ② ③) have Jarvis Maps in దారి's place. */
    static final String[] CLASSIC_SIX = {"talk", "way", "music", "fuel", "home", "sleep"};
    static final String[] DEEP_SIX = {"talk", "maps", "music", "fuel", "home", "sleep"};

    static int buttonIndex(String k) {
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].equals(k)) return i;
        return -1;
    }

    static String buttonLabel(String k) { int i = buttonIndex(k); return i < 0 ? "" : BUTTON_LABELS[i]; }

    static String buttonName(String k) { int i = buttonIndex(k); return i < 0 ? "" : BUTTON_NAMES[i]; }

    /** The six on the screen: his own (saved "a,b,c,d,e,f") if he made them, else the shape's. Never a repeat, never an unknown one. */
    static String[] six(String saved, boolean deep) {
        String[] def = deep ? DEEP_SIX : CLASSIC_SIX;
        if (saved == null || saved.trim().isEmpty()) return def.clone();
        String[] out = new String[6];
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int n = 0;
        for (String s : saved.split(",")) {
            s = s.trim();
            if (n < 6 && buttonIndex(s) >= 0 && seen.add(s)) out[n++] = s;
        }
        for (String s : def) if (n < 6 && seen.add(s)) out[n++] = s;
        for (String s : BUTTONS) if (n < 6 && seen.add(s)) out[n++] = s;
        return out;
    }

    /** Slot i gets button k; if k was already in another slot, the two change places (still six different ones). */
    static String[] put(String[] six, int slot, String k) {
        String[] out = six.clone();
        if (slot < 0 || slot >= out.length || buttonIndex(k) < 0) return out;
        for (int i = 0; i < out.length; i++) if (k.equals(out[i])) out[i] = out[slot];
        out[slot] = k;
        return out;
    }

    static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    // ================================================================ ②'s small dials

    static final String[] DIALS = {"fuel", "drive", "engine", "battery", "weather", "clock", "trip", "mileage", "compass", "altitude", "limit", "speed"};
    static final String[] DIAL_NAMES = {"⛽ డీజిల్‌తో ఇంకా ఎంత దూరం", "⏱️ ఎంతసేపు నడిపారు", "🌡️ ఇంజిన్ వేడి (OBD ఉంటే)", "🔋 బ్యాటరీ వోల్ట్స్ (OBD ఉంటే)",
            "☁️ బయట వాతావరణం", "🕐 గడియారం", "🛣️ ఈ ట్రిప్ కి.మీ", "📊 మైలేజ్ (కి.మీ/లీ)", "🧭 కారు ఏ దిశలో", "⛰️ ఎత్తు (సముద్రం నుంచి)",
            "🚫 స్పీడ్ లిమిట్", "🚗 స్పీడ్"};
    static final String[] DEF_DIALS = {"fuel", "drive", "engine"};

    static int dialIndex(String k) {
        for (int i = 0; i < DIALS.length; i++) if (DIALS[i].equals(k)) return i;
        return -1;
    }

    static String dialName(String k) { int i = dialIndex(k); return i < 0 ? "" : DIAL_NAMES[i]; }

    /** The three dials (saved "a,b,c"; an unknown one: that place's first choice). A dial may repeat: his choice. */
    static String[] three(String saved) {
        String[] out = DEF_DIALS.clone();
        if (saved == null) return out;
        String[] p = saved.split(",");
        for (int i = 0; i < 3 && i < p.length; i++) if (dialIndex(p[i].trim()) >= 0) out[i] = p[i].trim();
        return out;
    }

    /** What the dials read (NaN / -1 / MIN_VALUE: not known). */
    static final class Facts {
        double rangeKm = Double.NaN, volts = Double.NaN, tempC = Double.NaN, tripKm = Double.NaN, kmpl = Double.NaN, altitude = Double.NaN;
        long driveMin = -1;
        int coolant = Integer.MIN_VALUE, limit, speed = -1, hour = -1, minute;
        float bearing = -1;
        String sky = "";
    }

    static final String[] DIRS = {"ఉత్తరం", "ఈశాన్యం", "తూర్పు", "ఆగ్నేయం", "దక్షిణం", "నైరుతి", "పడమర", "వాయవ్యం"};

    /** A bearing (degrees from north) as one of the eight directions in Telugu. */
    static String compass(float b) {
        int i = Math.round((((b % 360f) + 360f) % 360f) / 45f) % 8;
        return DIRS[i];
    }

    /** A dial's lines: {sign, the number, the words, "!" when it warns (hot engine, little diesel, weak battery, over the limit, long drive)}. */
    static String[] dial(String kind, Facts f) {
        switch (kind) {
            case "fuel":
                if (Double.isNaN(f.rangeKm)) return new String[]{"⛽", "–", "డీజిల్ తెలీదు", ""};
                return new String[]{"⛽", String.valueOf(Math.max(0, Math.round(f.rangeKm))), "కి.మీ వస్తుంది", f.rangeKm < 80 ? "!" : ""};
            case "drive":
                if (f.driveMin < 0) return new String[]{"⏱️", "–", "డ్రైవ్", ""};
                String d = f.driveMin < 60 ? f.driveMin + " ని" : String.format(Locale.ROOT, "%d:%02d గం", f.driveMin / 60, f.driveMin % 60);
                return new String[]{"⏱️", d, "డ్రైవ్", f.driveMin >= 120 ? "!" : ""};
            case "engine":
                if (f.coolant == Integer.MIN_VALUE) return new String[]{"🌡️", "–", "ఇంజిన్ (OBD లేదు)", ""};
                return new String[]{"🌡️", f.coolant + "°", "ఇంజిన్", f.coolant >= 105 ? "!" : ""};
            case "battery":
                if (Double.isNaN(f.volts)) return new String[]{"🔋", "–", "బ్యాటరీ (OBD లేదు)", ""};
                return new String[]{"🔋", String.format(Locale.ROOT, "%.1fV", f.volts), "బ్యాటరీ", f.volts < 11.8 ? "!" : ""};
            case "weather":
                if (Double.isNaN(f.tempC)) return new String[]{"☁️", "–", "వాతావరణం", ""};
                return new String[]{"☁️", Math.round(f.tempC) + "°", f.sky == null || f.sky.isEmpty() ? "బయట" : f.sky, ""};
            case "clock":
                if (f.hour < 0) return new String[]{"🕐", "–", "సమయం", ""};
                int h12 = f.hour % 12 == 0 ? 12 : f.hour % 12;
                return new String[]{"🕐", String.format(Locale.ROOT, "%d:%02d", h12, f.minute), "సమయం", ""};
            case "trip":
                if (Double.isNaN(f.tripKm) || f.tripKm < 0) return new String[]{"🛣️", "–", "ఈ ట్రిప్", ""};
                return new String[]{"🛣️", f.tripKm < 10 ? String.format(Locale.ROOT, "%.1f", f.tripKm) : String.valueOf(Math.round(f.tripKm)), "కి.మీ ఈ ట్రిప్", ""};
            case "mileage":
                if (Double.isNaN(f.kmpl) || f.kmpl <= 0) return new String[]{"📊", "–", "మైలేజ్", ""};
                return new String[]{"📊", String.format(Locale.ROOT, "%.1f", f.kmpl), "కి.మీ/లీ", ""};
            case "compass":
                if (f.bearing < 0) return new String[]{"🧭", "–", "దిశ", ""};
                return new String[]{"🧭", compass(f.bearing), "దిశ", ""};
            case "altitude":
                if (Double.isNaN(f.altitude)) return new String[]{"⛰️", "–", "ఎత్తు", ""};
                return new String[]{"⛰️", String.valueOf(Math.round(f.altitude)), "మీ ఎత్తు", ""};
            case "limit":
                if (f.limit <= 0) return new String[]{"🚫", "–", "లిమిట్ తెలీదు", ""};
                return new String[]{"🚫", String.valueOf(f.limit), "లిమిట్", f.speed > f.limit + 5 ? "!" : ""};
            case "speed":
                if (f.speed < 0) return new String[]{"🚗", "–", "GPS…", ""};
                return new String[]{"🚗", String.valueOf(f.speed), "కి.మీ/గం", f.limit > 0 && f.speed > f.limit + 5 ? "!" : ""};
            default:
                return new String[]{"", "–", "", ""};
        }
    }

    // ================================================================ the map: colour and zoom

    static final String[] MAP_COLOURS = {"auto", "day", "night"};
    static final String[] MAP_COLOUR_NAMES = {"🌗 ఆటో (రాత్రి ముదురు రంగు)", "☀️ ఎప్పుడూ పగలు రంగు", "🌙 ఎప్పుడూ ముదురు రంగు"};

    /** The map in its dark colours now. */
    static boolean darkMap(String pref, boolean night) {
        if ("night".equals(pref)) return true;
        if ("day".equals(pref)) return false;
        return night;
    }

    static final String[] ZOOMS = {"auto", "near", "", "far"};
    static final String[] ZOOM_NAMES = {"🚗 స్పీడ్‌ని బట్టి (ఆటో)", "🔍 దగ్గరగా", "మామూలు", "🗺️ దూరంగా"};

    static int fixedZoom(String z) { return "near".equals(z) ? 17 : "far".equals(z) ? 14 : 16; }

    /** The zoom for this speed (faster: more of the road ahead); -1: no speed known (the zoom stays). */
    static int autoZoom(int kmh) {
        if (kmh < 0) return -1;
        if (kmh <= 25) return 17;
        if (kmh <= 55) return 16;
        if (kmh <= 85) return 15;
        return 14;
    }

    // ================================================================ sizes, the buttons' look

    static final String[] SIZES = {"s", "", "l"};
    static final String[] SIZE_NAMES = {"చిన్నగా", "మామూలు", "పెద్దగా"};

    static String sizeName(String s) {
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i].equals(s)) return SIZE_NAMES[i];
        return SIZE_NAMES[1];
    }

    static float jarvisScale(String s) { return "s".equals(s) ? 0.8f : "l".equals(s) ? 1.3f : 1f; }

    static float textScale(String s) { return "s".equals(s) ? 0.88f : "l".equals(s) ? 1.18f : 1f; }

    static final String[] STYLES = {"", "glass", "round"};
    static final String[] STYLE_NAMES = {"ఆకారాన్ని బట్టి (①②③ గుండ్రం, మిగతావి గాజు)", "🟦 గాజు చతురస్రం", "⚪ క్రోమ్ గుండ్రం"};

    /** The buttons round and chrome (else glass squares). */
    static boolean round(String style, boolean deep) {
        if ("round".equals(style)) return true;
        if ("glass".equals(style)) return false;
        return deep;
    }

    // ================================================================ 🎚️ the equalizer and 💡 the beat lights: his words

    static final String[] EQ_LEVELS = {"off", "dim", "full"};
    static final String[] EQ_NAMES = {"ఆఫ్", "మసకగా", "పూర్తి రంగుల్లో"};

    static String eqName(String level) {
        for (int i = 0; i < EQ_LEVELS.length; i++) if (EQ_LEVELS[i].equals(level)) return EQ_NAMES[i];
        return EQ_NAMES[2];
    }

    private static final String OFF_W = "(ఆపు|ఆపేయ్|ఆపేయి|ఆఫ్ చేయి|ఆఫ్ చెయ్|ఆఫ్|వద్దు|తీసేయి|తీసేయ్|off|stop)";
    private static final String ON_W = "(పెట్టు|పెట్టండి|ఆన్ చేయి|ఆన్ చెయ్|ఆన్|వేయి|వెయ్|కావాలి|on|start)";
    private static final String DIM_W = "(మసకగా పెట్టు|మసకగా|మసక|తగ్గించు|dim)";
    private static final java.util.regex.Pattern EQ_W = java.util.regex.Pattern.compile("^(ఈక్వలైజర్|ఈక్వలైజరు|ఇక్వలైజర్|equali[sz]er|రంగుల గీతలు)\\s*(ని|ను)?\\s*(.+)$");
    private static final java.util.regex.Pattern BL_W = java.util.regex.Pattern.compile("^(బీట్ లైట్లు|బీట్ లైట్స్|బీట్ లైట్|beat lights?)\\s*(ని|ను)?\\s*(.+)$");

    /**
     * His words for the song strip's equalizer and the beat lights (any time, also while driving: words only): {"eq",
     * "off" | "dim" | "full"} or {"lights", "off" | "on"}; else null. The whole short sentence only ("ఇంట్లో లైట్లు ఆపు" is
     * the home's lights, never these). Pure.
     */
    static String[] lightWords(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[\u200c\u200d]", "").replaceAll("(jarvis|జార్విస్|జర్విస్)", " ")
                .replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 50) return null;
        java.util.regex.Matcher m = EQ_W.matcher(t);
        if (m.matches()) {
            String rest = m.group(3).trim();
            if (rest.matches(DIM_W)) return new String[]{"eq", "dim"};
            if (rest.matches(OFF_W)) return new String[]{"eq", "off"};
            if (rest.matches(ON_W) || rest.matches("(పూర్తిగా|పూర్తి రంగుల్లో|full)( పెట్టు)?")) return new String[]{"eq", "full"};
            return null;
        }
        m = BL_W.matcher(t);
        if (m.matches()) {
            String rest = m.group(3).trim();
            if (rest.matches(OFF_W)) return new String[]{"lights", "off"};
            if (rest.matches(ON_W)) return new String[]{"lights", "on"};
        }
        return null;
    }

    // ================================================================ the lean (① ② ③'s 3D cards)

    static final int FLAT = 0, WING_L = 1, WING_R = 2, BACK = 3;

    /**
     * The leaning card's four corners (top-left, top-right, bottom-right, bottom-left: 8 numbers) for a card w × h.
     * WING_L: the left wing, its edge towards the middle farther away (shorter, a little in); WING_R the same mirrored;
     * BACK: the top edge farther away (narrower). amt: how far (0 flat … 0.3).
     */
    static float[] lean(int kind, float w, float h, float amt) {
        float a = Math.max(0, Math.min(0.3f, amt));
        switch (kind) {
            case WING_L: return new float[]{0, 0, w * (1 - a * 0.35f), h * a, w * (1 - a * 0.35f), h * (1 - a), 0, h};
            case WING_R: return new float[]{w * a * 0.35f, h * a, w, 0, w, h, w * a * 0.35f, h * (1 - a)};
            case BACK: return new float[]{w * a, h * a * 0.25f, w * (1 - a), h * a * 0.25f, w, h, 0, h};
            default: return new float[]{0, 0, w, 0, w, h, 0, h};
        }
    }

    /**
     * The perspective taking the rectangle (0,0)–(w,h) to the quad q (corners in the order above), as 9 numbers row by
     * row (x' = (m0 x + m1 y + m2) / (m6 x + m7 y + m8), the same for y with m3 m4 m5): Matrix.setValues takes them as
     * they are. Null when the quad is folded flat.
     */
    static float[] persp(float w, float h, float[] q) {
        if (w <= 0 || h <= 0 || q == null || q.length < 8) return null;
        double x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3], x2 = q[4], y2 = q[5], x3 = q[6], y3 = q[7];
        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        double a, b, c, d, e, f, g, hh;
        if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) { // (a plain stretch / slant: no perspective)
            a = x1 - x0; b = x2 - x1; c = x0; d = y1 - y0; e = y2 - y1; f = y0; g = 0; hh = 0;
            if (Math.abs(a * e - b * d) < 1e-9) return null; // (folded to a line / a point)
        } else {
            double det = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(det) < 1e-12) return null;
            g = (dx3 * dy2 - dx2 * dy3) / det;
            hh = (dx1 * dy3 - dx3 * dy1) / det;
            a = x1 - x0 + g * x1; b = x3 - x0 + hh * x3; c = x0;
            d = y1 - y0 + g * y1; e = y3 - y0 + hh * y3; f = y0;
        }
        // from the unit square to the rectangle's own size: u = x / w, v = y / h
        return new float[]{(float) (a / w), (float) (b / h), (float) c, (float) (d / w), (float) (e / h), (float) f, (float) (g / w), (float) (hh / h), 1f};
    }

    /** The way back (a finger's spot on the leaning card → the spot on the flat card). Null when it has none. */
    static float[] invert(float[] m) {
        if (m == null) return null;
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) return null;
        double k = 1 / det;
        return new float[]{(float) (A * k), (float) (-(b * i - c * h) * k), (float) ((b * f - c * e) * k),
                (float) (B * k), (float) ((a * i - c * g) * k), (float) (-(a * f - c * d) * k),
                (float) (C * k), (float) (-(a * h - b * g) * k), (float) ((a * e - b * d) * k)};
    }

    /** A point through the perspective. */
    static float[] apply(float[] m, float x, float y) {
        double z = m[6] * x + m[7] * y + m[8];
        if (Math.abs(z) < 1e-12) z = 1e-12;
        return new float[]{(float) ((m[0] * x + m[1] * y + m[2]) / z), (float) ((m[3] * x + m[4] * y + m[5]) / z)};
    }

    // ================================================================ "నా థీమ్ 1–3"

    /** The settings a kept look holds: how the screen looks only (no photos, no places, nothing private). */
    static final String[] LOOK_KEYS = {"car_theme", "car_accent", "car_colour", "car_gauge", "car_layout", "car_nav_map", "car_buttons", "car_btn_style",
            "car_dials", "car_jarvis", "car_text", "car_map_night", "car_map_zoom", "car_map_tilt", "car_dance", "car_low_motion", "car_eq_level", "car_beat_lights",
            "car_stage"};
    static final int MINE = 3;

    /** The look now (from all the settings) as one line to keep. */
    static String keep(Map<String, ?> all) {
        JSONObject o = new JSONObject();
        for (String k : LOOK_KEYS) {
            Object v = all == null ? null : all.get(k);
            try { if (v instanceof Integer || v instanceof Boolean || v instanceof String) o.put(k, v); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    /**
     * A kept look back: every look key with its value (Integer / Boolean / String) — null for a key the kept look did
     * not have (that setting goes back to its default). Nothing but the look keys, ever. Null when it can't be read.
     */
    static Map<String, Object> back(String kept) {
        if (kept == null || kept.isEmpty()) return null;
        JSONObject o;
        try { o = new JSONObject(kept); } catch (Exception e) { return null; }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : LOOK_KEYS) {
            Object v = o.opt(k);
            if (v instanceof Integer || v instanceof Boolean || v instanceof String) out.put(k, v);
            else if (v instanceof Long && (Long) v >= Integer.MIN_VALUE && (Long) v <= Integer.MAX_VALUE) out.put(k, (int) (long) (Long) v);
            else out.put(k, null);
        }
        return out;
    }

    /** A kept look in a few words (its shape · its theme), "" when none. */
    static String summary(String kept) {
        Map<String, Object> m = back(kept);
        if (m == null) return "";
        Object lay = m.get("car_layout"), th = m.get("car_theme");
        String shape = CarLayout.name(lay instanceof String ? (String) lay : null);
        String theme = CarTheme.of(th instanceof Integer ? (Integer) th : 1).name;
        return shape + " · " + theme;
    }
}
