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

    static final String[] BUTTONS = {"talk", "maps", "way", "music", "fuel", "home", "sleep", "games", "apps", "stage",
            // ⑪ (26 Oct): ☕ a tea break on the way, 📷 the camera, 🌧️ rain on the way, 🔇 quiet for this trip
            "tea", "camera", "rain", "quiet"};
    static final String[] BUTTON_NAMES = {"🎤 మాట్లాడు", "🗺️ Jarvis Maps", "🧭 దారి (ఇల్లు, నా చోట్లు)", "🎵 పాటలు", "⛽ డీజిల్ కొట్టించాను",
            "🏠 ఇల్లు (లైట్లు, ఇంటికి)", "😴 నిద్ర అలర్ట్", "🎲 ప్రయాణ ఆటలు", "▦ box ఆప్స్", "🕺 Jarvis స్టేజ్ (ఆన్ / ఆఫ్)",
            "☕ బ్రేక్ (దారిలో టీ)", "📷 కెమెరా", "🌧️ దారిలో వాన", "🔇 నిశ్శబ్దం (ఈ ప్రయాణం)"};
    static final String[] BUTTON_LABELS = {"మాట్లాడు", "Jarvis Maps", "దారి", "పాటలు", "డీజిల్", "ఇల్లు", "నిద్ర", "ఆటలు", "ఆప్స్", "స్టేజ్",
            "బ్రేక్", "కెమెరా", "దారిలో వాన", "నిశ్శబ్దం"};
    /** ⑪'s own six (his own six, when he made them, as everywhere). */
    static final String[] SPECIAL_SIX = {"home", "fuel", "camera", "rain", "tea", "sleep"};
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
    static String[] six(String saved, boolean deep) { return six(saved, deep ? DEEP_SIX : CLASSIC_SIX); }

    /** His six for this shape: his own, else ⑪'s, the 3D shapes', or the classic six. */
    static String[] sixFor(String saved, String shape) {
        return six(saved, CarLayout.family(shape) ? SPECIAL_SIX : CarLayout.deep(shape) ? DEEP_SIX : CLASSIC_SIX); // (⑫ ⑬ ⑭: ⑪'s six)
    }

    // ================================================================ stage 2 (27 Oct): ⑫ ⑬ ⑭

    /** ⑫ white glass, ⑭ neon: the shape's own colours (his theme when it is already one of those), else his theme. Pure. */
    static int themeFor(String shape, int his) {
        if (CarLayout.GLASS.equals(shape)) return his == 5 || his == 12 ? his : 5;
        if (CarLayout.NEON.equals(shape)) return his == 3 || his == 11 || his == 13 ? his : 13;
        if (CarLayout.CHASE.equals(shape) || CarLayout.CITY.equals(shape)) return his == 5 || his == 12 ? 1 : his; // (⑮ ⑯: cards over the 3D world are never white glass: his own dark theme, else the blue glass)
        if (CarLayout.LIQUID.equals(shape)) return 17; // (⑰ ⑱ ⑲ ⑳: always their own)
        if (CarLayout.NAVYGLASS.equals(shape)) return 18;
        if (CarLayout.SOFTWHITE.equals(shape)) return 19;
        if (CarLayout.GLOWTILES.equals(shape)) return 20;
        return his;
    }

    /** ⑫ at night: dark only when he chose it ("dark"); else the glass stays white (his choice, never by itself). Pure. */
    static boolean glassDark(String pref, boolean night) { return night && "dark".equals(pref); }

    // ⑯ (stage 3): the 3D car's kinds (our six, or his own photos), the sky, the buildings
    static final String[] KINDS_3D = {"suv", "sedan", "hatch", "sports", "jeep", "concept", "photo", "coupe_r", "suv_r", "sports_r", "sedan_r"};
    static final String[] KIND_3D_NAMES = {"🚙 కాంపాక్ట్ SUV", "🚗 సెడాన్", "🚘 హ్యాచ్‌బ్యాక్", "🏎️ స్పోర్ట్స్ కూపే", "🛻 ఆఫ్-రోడ్ జీప్", "🛸 ఫ్యూచర్ కాన్సెప్ట్", "📷 నా కారు (ఫోటోలు)",
            "✨ కూపే (రియల్ లుక్)", "✨ SUV (రియల్ లుక్)", "✨ స్పోర్ట్స్ (రియల్ లుక్)", "✨ సెడాన్ (రియల్ లుక్)"};
    static final String[] SKIES_3D = {"", "day", "dusk", "night", "neon", "sunset", "white", "whitev", "highway", "dayar"};
    static final String[] SKY_3D_NAMES = {"🌗 స్క్రీన్ పగలు / రాత్రితో", "☀️ పగలు", "🌇 సాయంత్రం", "🌙 రాత్రి",
            "🌆 నియాన్ మూన్ సిటీ", "🌅 సూర్యాస్తమయం సిటీ", "🤍 తెల్లటి సిటీ (నీలి దారి)", "💜 తెల్లటి సిటీ (ఊదా దారి)", "🛣️ రాత్రి హైవే", "🌳 పగలు AR హైవే"};

    /** ⑯'s cards over the 3D world (31 Oct): as it is, the neon racing HUD, or the white ADAS HUD. */
    static final String[] HUDS_16 = {"", "neon", "white"};
    static final String[] HUD_16_NAMES = {"⑯ మామూలు (ఇప్పటిది)", "🏁 నియాన్ రేసింగ్ HUD", "🤍 తెల్లటి ADAS HUD"};

    /** ⑯'s colours with its HUD: the neon one his neon theme (else the synthwave), the white one ⑰'s light glass; else as before. Pure. */
    static int themeFor(String shape, int his, String hud16) {
        if (CarLayout.CHASE.equals(shape) && "white".equals(hud16)) return 17;
        if (CarLayout.CHASE.equals(shape) && "neon".equals(hud16)) return his == 3 || his == 11 || his == 13 ? his : 13;
        return themeFor(shape, his);
    }
    static final String[] CITIES_3D = {"", "toy"};
    static final String[] CITY_3D_NAMES = {"🏢 నిజమైన బిల్డింగ్‌లు (OSM)", "🧱 బొమ్మ బిల్డింగ్‌లు"};

    /** 🌅's next sky (his tap goes round: screen's, day, dusk, night). Pure. */
    static String nextSky(String now) {
        for (int i = 0; i < SKIES_3D.length; i++) if (SKIES_3D[i].equals(now)) return SKIES_3D[(i + 1) % SKIES_3D.length];
        return SKIES_3D[1];
    }

    // ⑮ (stage 4): 3D / 2D, the camera's height, the map's colours, north up, the game view — all his choice (parked, or his words)
    static final String[] D3_15 = {"", "2d"};
    static final String[] D3_15_NAMES = {"🏙️ 3D (వాలుగా, బిల్డింగ్‌లతో)", "🗺️ 2D (పై నుంచి)"};
    static final int[] EYES_15 = {0, 1, 2};
    static final String[] EYE_15_NAMES = {"👁️ కెమెరా దగ్గరగా", "👁️ కెమెరా మధ్యలో", "👁️ కెమెరా పైనుంచి దూరంగా"};
    static final String[] LOOKS_15 = {"", "day", "night"};
    static final String[] LOOK_15_NAMES = {"🌗 స్క్రీన్ పగలు / రాత్రితో", "☀️ ఎప్పుడూ తెల్లటి గాజు", "🌙 ఎప్పుడూ చీకటి"};
    static final String[] NORTH_15 = {"", "north"};
    static final String[] NORTH_15_NAMES = {"🚗 దారి పైకి (కారుతో తిరుగుతుంది)", "🧭 ఉత్తరం పైకి"};
    static final String[] GAME_15 = {"", "on"};
    static final String[] GAME_15_NAMES = {"🏙️ 3D సిటీ మ్యాప్", "🎮 గేమ్ వ్యూ (⑯ బొమ్మ)"};

    /** 🌙's next colours (his tap goes round: the screen's, day, night). Pure. */
    static String nextLook15(String now) {
        for (int i = 0; i < LOOKS_15.length; i++) if (LOOKS_15[i].equals(now)) return LOOKS_15[(i + 1) % LOOKS_15.length];
        return LOOKS_15[1];
    }
    /** ⑰ ⑲ ⑳'s day / night: only his tap (🌗, ⑳'s switch, his words); the hour never changes it. ⑱ is always the dark navy. */
    static final String[] NEW_NIGHTS = {"", "dark"};
    static final String[] NEW_NIGHT_NAMES = {"☀️ పగలు రూపం", "🌙 రాత్రి రూపం (ముదురు)"};

    /** ⑰–⑳ in their dark colours now. Pure. */
    static boolean newDark(String shape, String pref) { return CarLayout.NAVYGLASS.equals(shape) || CarLayout.newLook(shape) && "dark".equals(pref); }

    static final String[] GLASS_NIGHTS = {"", "dark"};
    static final String[] GLASS_NIGHT_NAMES = {"☀️ రాత్రి కూడా తెల్లటి గాజు", "🌙 రాత్రి ముదురు రంగు"};

    /**
     * ⑬'s wheel in a w × h box: Jarvis's round card on the left (its left, top, size) and n buttons on an arc beside
     * him (each one's centre), as big as the box lets them be with no button over another or over him — the biggest
     * size that fits, found by halving. Returns {orbX, orbY, orbD, tileW, tileH, cx0, cy0, cx1, cy1, …}. Pure.
     */
    static float[] wheel(int w, int h, int n) {
        float m = Math.max(2, Math.min(w, h) / 40f);
        float lo = 1, hi = Math.max(2, Math.min(w, h));
        float[] best = null;
        for (int it = 0; it < 40; it++) {
            float d = (lo + hi) / 2;
            float[] at = wheelAt(w, h, n, d, m);
            if (wheelFits(at, w, h, n, m)) { lo = d; best = at; } else hi = d;
        }
        return best != null ? best : wheelAt(w, h, n, 1, m);
    }

    /** The button's width (part of Jarvis's size), its gap from him (part of its width), the arc's spread (degrees). */
    private static final float WHEEL_TILE = 0.36f, WHEEL_GAP = 0.9f, WHEEL_SPREAD = 170;

    private static float[] wheelAt(int w, int h, int n, float d, float m) {
        float tw = d * WHEEL_TILE, th = tw * 1.1f, r = d / 2f + tw * WHEEL_GAP;
        float ox = m, oy = (h - d) / 2f, cx = ox + d / 2f, cy = h / 2f;
        float[] out = new float[5 + 2 * Math.max(0, n)];
        out[0] = ox; out[1] = oy; out[2] = d; out[3] = tw; out[4] = th;
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(n == 1 ? 0 : -WHEEL_SPREAD / 2 + WHEEL_SPREAD * i / (n - 1));
            out[5 + 2 * i] = cx + (float) (r * Math.cos(a));
            out[6 + 2 * i] = cy + (float) (r * Math.sin(a));
        }
        return out;
    }

    private static boolean wheelFits(float[] at, int w, int h, int n, float m) {
        float d = at[2], tw = at[3], th = at[4], cx = at[0] + d / 2f, cy = at[1] + d / 2f;
        if (at[1] < m - 0.01f || at[0] + d > w - m) return false;
        for (int i = 0; i < n; i++) {
            float x = at[5 + 2 * i], y = at[6 + 2 * i];
            if (x - tw / 2 < m || x + tw / 2 > w - m || y - th / 2 < m || y + th / 2 > h - m) return false;
            if (Math.hypot(x - cx, y - cy) < d / 2 + tw * 0.45f) return false; // (never over Jarvis)
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(x - at[5 + 2 * k]) < tw && Math.abs(y - at[6 + 2 * k]) < th) return false; // (never one over another)
            }
        }
        return true;
    }

    /** The same with a shape's own six as the default (⑪'s). */
    static String[] six(String saved, String[] def) {
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
    private static final java.util.regex.Pattern SHAPE_W = java.util.regex.Pattern.compile(
            "^((స్క్రీన్ )?ఆకారం (మార్చు|మార్చండి|మార్చవా)|తర్వాతి ఆకారం( పెట్టు)?|వేరే ఆకారం( పెట్టు)?|next (shape|layout)|change (the )?(shape|layout))$");
    private static final java.util.regex.Pattern ST_W = java.util.regex.Pattern.compile("^(డాన్స్ స్టేజ్|స్టేజ్|stage|dance stage)\\s*(ని|ను|మీద)?\\s*(.+)$");

    /**
     * His words for the song strip's equalizer, the beat lights and ⑧ the stage (any time, also while driving: words
     * only): {"eq", "off" | "dim" | "full"}, {"lights", "off" | "on"} or {"stage", "off" | "on"}; else null. The whole
     * short sentence only ("ఇంట్లో లైట్లు ఆపు" is the home's lights, never these). Pure.
     */
    static String[] lightWords(String text) {
        if (text == null || text.contains("?") || text.contains("？")) return null; // (a question goes to Jarvis)
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[\u200c\u200d]", "").replaceAll("(jarvis|జార్విస్|జర్విస్)", " ")
                .replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 50) return null;
        if (SHAPE_W.matcher(t).matches()) return new String[]{"shape", "next"};
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
            return null;
        }
        m = ST_W.matcher(t);
        if (m.matches()) {
            String rest = m.group(3).trim();
            if (rest.matches(OFF_W)) return new String[]{"stage", "off"};
            if (rest.matches(ON_W)) return new String[]{"stage", "on"};
        }
        return null;
    }

    // ================================================================ the rest of the look: the song's colour, the moving background, the clock, the night light

    /** 🧩 The next shape after this one (in ⚙️'s order, round and round). */
    static String nextShape(String cur) {
        String v = CarLayout.valid(cur);
        for (int i = 0; i < CarLayout.CHOICES.length; i++) if (CarLayout.CHOICES[i].equals(v)) return CarLayout.CHOICES[(i + 1) % CarLayout.CHOICES.length];
        return CarLayout.CHOICES[0];
    }

    static final String[] BG_MOTIONS = {"", "stars", "rain", "road", "clouds", "sunset", "waterfall", "space", "synth", "hud", "sport", "streaks", "teal"};
    static final String[] BG_MOTION_NAMES = {"కదలిక లేదు (థీమ్‌దే)", "✨ నక్షత్రాలు", "🌧️ గాజు మీద వర్షం చుక్కలు", "🛣️ కదిలే రోడ్డు గీతలు", "☁️ రంగుల మేఘాలు",
            "9 · 🌄 ఊదా సూర్యాస్తమయం", "10 · 🏞️ జలపాతం సాయంత్రం", "11 · 🪐 అంతరిక్షం", "12 · 🌆 సింథ్‌వేవ్ గ్రిడ్", "13 · 🔷 HUD గ్రిడ్",
            "14 · 🏁 స్పోర్ట్ మెష్", "15 · 💫 స్పీడ్ గీతలు", "16 · 🌈 టీల్-పర్పుల్ మెరుపు"};
    static final String[] CLOCKS = {"", "thin", "led", "analog"};
    static final String[] CLOCK_NAMES = {"పెద్ద అంకెలు", "సన్నని అంకెలు", "డిజిటల్ LED", "🕰️ ముళ్ల గడియారం"};
    static final String[] NIGHT_DIMS = {"", "mid", "low"};
    static final String[] NIGHT_DIM_NAMES = {"box దే (మార్చదు)", "రాత్రి కొంచెం తక్కువ", "రాత్రి బాగా తక్కువ"};

    static String nameOf(String[] keys, String[] names, String k) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(k)) return names[i];
        return names[0];
    }

    /** 🔆 The screen's light for Jarvis's window: -1 the box's own; at night his choice (0–1). Pure. */
    static float nightLight(String pref, boolean night) {
        if (!night) return -1f;
        if ("mid".equals(pref)) return 0.35f;
        if ("low".equals(pref)) return 0.12f;
        return -1f;
    }

    /**
     * 🌗 The day / night period now, as a mark (slot, day or night, and its date — a night past midnight keeps the date
     * it began): a theme goes on once per real day / night, again the next one. Pure.
     */
    static String dayNightMark(boolean night, int slot, long now, java.util.TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(now - 6 * 3_600_000L);
        return (night ? "n" : "d") + slot + "@" + c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** 🌗 "d,n": his day and night themes (1–3); the place for now (0: off / not set). Pure. */
    static int dayNightSlot(String pref, boolean night) {
        if (pref == null) return 0;
        String[] p = pref.split(",");
        if (p.length != 2) return 0;
        try {
            int s = Integer.parseInt(p[night ? 1 : 0].trim());
            return s >= 1 && s <= MINE ? s : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 🎨 The song picture's own colour (pixels as ARGB): the hue most of its vivid pixels share, made bright; 0 when the
     * picture has hardly any colour (grey, black and white). Pure.
     */
    static int artAccent(int[] px) {
        if (px == null || px.length == 0) return 0;
        float[] w = new float[36], sat = new float[36];
        float total = 0;
        for (int c : px) {
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            if (mx == 0) continue;
            float s = (mx - mn) / (float) mx, v = mx / 255f;
            if (s < 0.35f || v < 0.35f) continue;
            float h;
            int d = mx - mn;
            if (mx == r) h = 60f * (((g - b) / (float) d) % 6);
            else if (mx == g) h = 60f * ((b - r) / (float) d + 2);
            else h = 60f * ((r - g) / (float) d + 4);
            if (h < 0) h += 360;
            int k = Math.min(35, (int) (h / 10));
            float wt = s * v;
            w[k] += wt;
            sat[k] += s * wt;
            total += wt;
        }
        if (total < px.length * 0.03f) return 0; // (hardly any colour: the theme's own)
        int best = 0;
        for (int k = 1; k < 36; k++) if (w[k] + 0.5f * (w[(k + 35) % 36] + w[(k + 1) % 36]) > w[best] + 0.5f * (w[(best + 35) % 36] + w[(best + 1) % 36])) best = k;
        float hue = best * 10 + 5, s = Math.max(0.6f, Math.min(0.9f, sat[best] / Math.max(1e-6f, w[best])));
        return hsv(hue, s, 1f);
    }

    /** HSV (h 0–360, s, v 0–1) → an opaque colour. Pure. */
    static int hsv(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; } else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; } else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** Between two colours (k 0–1). Pure. */
    static int blend(int a, int b, float k) {
        k = Math.max(0, Math.min(1, k));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k), g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k),
                bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
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

    // ================================================================ ⑪ (26 Oct): Jarvis's side and glow, the car on the map, the map's pictures

    /** 🧍 Jarvis's side in ⑪ (left as in the picture, or right: the columns change places). */
    static final String[] SIDES = {"", "right"};
    static final String[] SIDE_NAMES = {"ఎడమ", "కుడి"};
    /** 🌟 Jarvis's light: the theme's, or his own colour. */
    static final String[] GLOWS = {"", "cyan", "violet", "gold", "green", "pink"};
    static final String[] GLOW_NAMES = {"థీమ్ రంగే", "నీలి", "ఊదా", "బంగారు", "ఆకుపచ్చ", "గులాబీ"};
    private static final int[] GLOW_COLOURS = {0, 0xFF3FE3FF, 0xFFB08CFF, 0xFFFFD24D, 0xFF5BF29A, 0xFFFF6FAE};

    /** The glow's colour (0: the theme's). Pure. */
    static int glowColour(String g) {
        for (int i = 0; i < GLOWS.length; i++) if (GLOWS[i].equals(g)) return GLOW_COLOURS[i];
        return 0;
    }

    /** 💫 The lit rings under Jarvis: "" in ⑪ only, "on" everywhere, "off" never. Pure. */
    static final String[] RINGS = {"", "on", "off"};
    static final String[] RING_NAMES = {"⑪ లో మాత్రమే", "అన్ని ఆకారాల్లో", "వద్దు"};

    static boolean ringOn(String pref, boolean special) { return "on".equals(pref) || !"off".equals(pref) && special; }

    /** A song's time as m:ss (h:mm:ss past an hour; "–:––" unknown). Pure. */
    static String songTime(long ms) {
        if (ms < 0) return "–:––";
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    /** Where a song is now from its app's numbers: the position then, plus the time since at its speed (playing), within its length. Pure. */
    static long songAt(long pos, long upd, float speed, boolean playing, long dur, long nowElapsed) {
        if (pos < 0) return -1;
        long at = playing && upd > 0 ? pos + Math.round((nowElapsed - upd) * speed) : pos;
        if (dur > 0) at = Math.min(at, dur);
        return Math.max(0, at);
    }

    /**
     * 🚙 His car on the map: our own top-down drawings (no maker's shape), the arrow, the old blue dot, or his own photo
     * from above (kept on the box only).
     */
    static final String[] MAP_CARS = {"", "arrow", "suv", "sedan", "hatch", "sports", "jeep", "concept", "photo"};
    static final String[] MAP_CAR_NAMES = {"🔵 నీలి చుక్క (ఇప్పటిది)", "➤ బాణం", "🚙 SUV", "🚗 సెడాన్", "🚘 హ్యాచ్‌బ్యాక్", "🏎️ స్పోర్ట్స్", "🛻 జీప్",
            "🛸 కాన్సెప్ట్", "📷 నా కారు ఫోటో (పై నుంచి)"};

    static int mapCarIndex(String k) {
        for (int i = 0; i < MAP_CARS.length; i++) if (MAP_CARS[i].equals(k)) return i;
        return -1;
    }

    /** The map's pictures with our map (G1): Google's (his key) or the free OpenStreetMap; G2 is the Google Maps app itself. */
    static final String[] MAP_SRCS = {"", "osm"};
    static final String[] MAP_SRC_NAMES = {"🗺️ Google మ్యాప్ (మీ key తో, ట్రాఫిక్ తో)", "🆓 ఉచిత మ్యాప్ (OpenStreetMap)"};

    // ================================================================ "నా థీమ్ 1–3"

    /** The settings a kept look holds: how the screen looks only (no photos, no places, nothing private). */
    static final String[] LOOK_KEYS = {"car_theme", "car_accent", "car_colour", "car_gauge", "car_layout", "car_nav_map", "car_buttons", "car_btn_style",
            "car_dials", "car_jarvis", "car_text", "car_map_night", "car_map_zoom", "car_map_tilt", "car_dance", "car_low_motion", "car_eq_level", "car_beat_lights",
            "car_art_colour", "car_bg_motion", "car_clock", "car_night_dim", "car_welcome",
            // v3 (25 Oct, his pictures' looks)
            "car_font", "car_card", "car_icons", "car_eq_style", "car_welcome_look", "car_jarvis_look", "car_platform", "car_vinyl", "car_wave",
            "car_route_glow", "car_turn_arrows", "car_map_limit", "car_map_pills", "car_cluster_dial",
            // v4 (26 Oct, ⑪): Jarvis's side and glow, his clothes and skin, the ring under him, the car on the map, the map turning with the car
            "car_jarvis_side", "car_jarvis_glow", "body_look", "body_skin", "car_jarvis_ring", "car_talk_bubble", "car_map_car", "car_map_rotate", "car_buttons_sp",
            // v5 (27 Oct, ⑫ ⑬ ⑭): ⑫'s glass at night
            "car_glass_night",
            // v6 (27 Oct, ⑯): the 3D car, its finish, headlights, glow, the sky, the buildings, his photo car's plate
            "car_3d_kind", "car_3d_finish", "car_3d_head", "car_3d_glow", "car_3d_sky", "car_3d_city", "car_plate",
            // v7 (27 Oct, ⑮): 3D / 2D, the camera's height, the map's colours, north up, the game view
            "car_15_3d", "car_15_eye", "car_15_look", "car_15_north", "car_15_game",
            // v8 (31 Oct, 🎬): the film Jarvis or the drawn one, the heroines' set with songs
            "car_jarvis_kind", "car_film_sets",
            // v9 (31 Oct, ⑰–⑳, ⑯'s worlds and HUDs, ㉑): the new looks' day / night, the player's look, 🎛️'s page, the 🎙️ orb, ⑯'s HUD, the car's roof, rims, LED bar
            "car_new_night", "car_player_look", "car_hub_look", "car_orb", "car_16_hud", "car_3d_roof", "car_3d_rim", "car_3d_led"};
    /** How many of LOOK_KEYS a kept look of each version knew (v1 kept only what it had; v2 knew the first 23, v3 the first 37, v4 the first 46, v5 47, v6 54, v7 59, v8 61). */
    private static int known(int v) { return v >= 9 ? LOOK_KEYS.length : v == 8 ? 61 : v == 7 ? 59 : v == 6 ? 54 : v == 5 ? 47 : v == 4 ? 46 : v == 3 ? 37 : v == 2 ? 23 : 0; }
    static final int MINE = 3;

    /** The look now (from all the settings) as one line to keep. */
    static String keep(Map<String, ?> all) {
        JSONObject o = new JSONObject();
        try { o.put("v", 9); } catch (Exception ignored) {} // (v9: every look key there; an older one only knew some)
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
        int knew = known(o.optInt("v", 1)); // (an older kept look never resets a setting it didn't know about)
        for (int i = 0; i < LOOK_KEYS.length; i++) {
            String k = LOOK_KEYS[i];
            if (i >= knew && !o.has(k)) continue;
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
