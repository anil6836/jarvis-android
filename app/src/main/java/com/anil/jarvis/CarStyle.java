package com.anil.jarvis;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✨ The car screen's newer looks (Anil, 25 Oct: "వాటిని వాడి మన కారు Jarvis ని ఇంకా థీమ్స్, డిజైన్, గ్రాఫిక్స్,
 * animation, కలర్స్, ఐకాన్స్…" — his pictures were the idea; everything is drawn by us): the choices, their names as he
 * saw them on the sheets (1–8 speedometers, 9–16 backgrounds, 17–32 pieces), and the small sums behind them. No Android
 * here: desk-tested. Every one of them is his choice only — nothing here switches by itself.
 */
final class CarStyle {
    private CarStyle() {}

    // ================================================================ 🧭 the speedometers (the old four, then the sheet's 1–8)

    static final String[] GAUGES = {"", "arc", "needle", "digits", "tick", "fan", "blocks", "ring", "neon", "half", "cyber", "scope"};
    static final String[] GAUGE_NAMES = {"థీమ్‌దే (ఆకారానిదే)", "వెలిగే వంపు (ఆర్క్)", "క్లాసిక్ సూది డయల్", "పెద్ద అంకెలు మాత్రమే",
            "1 · టిక్ డయల్", "2 · రేడియల్ ఫ్యాన్", "3 · రేసింగ్ బ్లాక్స్ + LED అంకెలు", "4 · గ్రేడియంట్ వలయం",
            "5 · నియాన్ క్రోమ్ సూది", "6 · హాఫ్ ఆర్క్", "7 · సైబర్ బ్లాక్స్", "8 · HUD స్కోప్"};

    /** His gauge choice as the drawing's number (CarTheme.G_…), or -1 for "the theme's / the shape's". Pure. */
    static int gaugeCode(String g) {
        if (g == null || g.isEmpty()) return -1;
        for (int i = 1; i < GAUGES.length; i++) if (GAUGES[i].equals(g)) return i - 1;
        return -1;
    }

    /** The speedometer's scale (km/h at its end). */
    static final float MAX_KMH = 160;

    /** How much of a scale is lit for this speed (0–1). Pure. */
    static float frac(float kmh) { return Math.max(0, Math.min(1, kmh / MAX_KMH)); }

    /** How many of n blocks / ticks are lit for this speed (a block lights when the speed reaches its start). Pure. */
    static int lit(float kmh, int n) {
        if (n <= 0 || kmh <= 0.5f) return 0;
        return Math.max(1, Math.min(n, (int) Math.ceil(frac(kmh) * n - 1e-4)));
    }

    /** A block's colour on a racing bar: green, then yellow, then red near its end (n blocks). Pure. */
    static int blockColour(int j, int n) {
        float f = n <= 1 ? 0 : j / (float) (n - 1);
        return f < 0.6f ? 0xFF2BFF88 : f < 0.85f ? 0xFFFFD23A : 0xFFFF4D4D;
    }

    /** The seven segments lit for a digit (a b c d e f g, as bits 0–6); -1 for a dash. Pure. */
    static int segments(char d) {
        switch (d) {
            case '0': return 0b0111111;
            case '1': return 0b0000110;
            case '2': return 0b1011011;
            case '3': return 0b1001111;
            case '4': return 0b1100110;
            case '5': return 0b1101101;
            case '6': return 0b1111101;
            case '7': return 0b0000111;
            case '8': return 0b1111111;
            case '9': return 0b1101111;
            default: return 0b1000000; // (– : the middle one)
        }
    }

    // ================================================================ 🔢 the numbers' font (free fonts, their licences beside them)

    static final String[] FONTS = {"", "orb", "raj", "sai"};
    static final String[] FONT_NAMES = {"మామూలు అంకెలు", "Orbitron (స్పోర్టీ, వెడల్పుగా)", "Rajdhani (సన్నగా, పొడవుగా)", "Saira Condensed (రేసింగ్)"};
    private static final String[] FONT_FILES = {null, "fonts/Orbitron.ttf", "fonts/Rajdhani-Bold.ttf", "fonts/SairaCondensed-SemiBold.ttf"};

    /** The font's file in the app (null: the phone's own). Pure. */
    static String fontFile(String k) {
        for (int i = 0; i < FONTS.length; i++) if (FONTS[i].equals(k)) return FONT_FILES[i];
        return null;
    }

    // ================================================================ 🃏 the cards' frames (17, 18)

    static final int C_GLASS = 0, C_HUD = 1, C_GLOW = 2,
            /** ⑰ ⑱ ⑲ ⑳'s own frames (CarGlass; never in his card list: those looks keep their own). */
            C_LIQUID = 3, C_NAVY = 4, C_SOFT = 5, C_TILES = 6;
    static final String[] CARDS = {"", "glass", "hud", "glow"};
    static final String[] CARD_NAMES = {"థీమ్‌దే", "గాజు కార్డులు", "17 · HUD మూలల కార్డులు", "18 · మెరుపు అంచు కార్డులు"};

    /** The card frame on the screen: his choice, else the theme's own. Pure. */
    static int cardCode(String pref, int themeCard) {
        if ("glass".equals(pref)) return C_GLASS;
        if ("hud".equals(pref)) return C_HUD;
        if ("glow".equals(pref)) return C_GLOW;
        return themeCard;
    }

    // ================================================================ 🎵 the player's looks (31 Oct, the players' sheet)

    /** "": the shape's own (⑫ round, ⑰ warm glass, ⑱ the dark stack, ⑲ ⑳ the wave, the rest lying); else his pick for every shape. */
    static final String[] PLAYERS = {"", "old", "round", "cover", "warm", "stack", "wave"};
    static final String[] PLAYER_NAMES = {"ఆకారానిదే", "పాతది (పడుకున్న పట్టీ)", "⑫ గుండ్రని బొమ్మ", "1 · కవర్ వెలుగు", "2 · వెచ్చని గాజు", "3 · చీకటి స్టాక్ (తర్వాతి పాటలతో)", "4 · తెల్లటి తరంగం"};

    /** The player's look on this shape: his pick, else the shape's own. Pure. */
    static String playerFor(String pref, String shape) {
        for (int i = 1; i < PLAYERS.length; i++) if (PLAYERS[i].equals(pref)) return pref;
        if (CarLayout.GLASS.equals(shape)) return "round";
        if (CarLayout.LIQUID.equals(shape)) return "warm";
        if (CarLayout.NAVYGLASS.equals(shape)) return "stack";
        if (CarLayout.SOFTWHITE.equals(shape) || CarLayout.GLOWTILES.equals(shape)) return "wave";
        return "old";
    }

    /**
     * The wave look's bar i of n (0.15–1): the same song always the same wave (from its name), louder in the middle like a
     * song's own picture, never two bars alike side by side. Pure.
     */
    static float waveBar(int seed, int i, int n) {
        int s = (seed & 0x7FFFFFFF) % 997;
        double a = Math.abs(Math.sin(i * 0.61 + s * 0.13)) * 0.6 + Math.abs(Math.sin(i * 0.23 + s * 0.071)) * 0.4;
        double env = 0.55 + 0.45 * Math.sin(Math.PI * (i + 0.5) / Math.max(1, n));
        return (float) Math.max(0.15, Math.min(1.0, a * env * 1.15));
    }

    /** 🎛️'s own page: the new liquid glass (as on his sheet) or the old one. */
    static final String[] HUB_LOOKS = {"", "old"};
    static final String[] HUB_LOOK_NAMES = {"✨ కొత్త గాజు", "పాతది"};

    /** 🎙️ the orb in "Jarvis, మాట్లాడు": in ⑰–⑳ (their own), in every ⑪–⑳ shape, or not at all. */
    static final String[] ORBS = {"", "all", "off"};
    static final String[] ORB_NAMES = {"⑰ ⑱ ⑲ ⑳ లో", "⑪ నుంచి ⑳ అన్నిట్లో", "వద్దు (పాత బటన్)"};

    /** The orb shows in this shape. Pure. */
    static boolean orbOn(String pref, String shape) {
        if ("off".equals(pref)) return false;
        return CarLayout.newLook(shape) || "all".equals(pref) && CarLayout.family(shape);
    }

    // ================================================================ 🎛️ the buttons' icons (30, 31, 32)

    static final int I_GLOW = 0, I_LINE = 1, I_NEON = 2, I_TILE = 3;
    static final String[] ICONS = {"", "line", "neon", "tile"};
    static final String[] ICON_NAMES = {"మెరిసే గీతలు (ఇప్పటివి)", "30 · సన్నని గీతలు", "31 · నియాన్ గ్లో (ఒక్కో బటన్ ఒక్కో రంగు)", "32 · రంగుల టైల్స్"};

    static int iconCode(String pref) {
        for (int i = 0; i < ICONS.length; i++) if (ICONS[i].equals(pref)) return i;
        return I_GLOW;
    }

    /** Each button's own colour in 31 / 32 (the same button always the same colour). Pure. */
    static int kindColour(String button) {
        switch (button == null ? "" : button) {
            case "talk": return 0xFF2FE0FF;
            case "maps": return 0xFF4DFF9A;
            case "way": return 0xFF2FD6C8;
            case "music": return 0xFFFF4FA3;
            case "fuel": return 0xFFFFC23A;
            case "home": return 0xFFB28DFF;
            case "sleep": return 0xFF6F8BFF;
            case "games": return 0xFFFF8A3C;
            case "apps": return 0xFF9FB6C9;
            case "stage": return 0xFFFFD166;
            case "tea": return 0xFFE0A46E;
            case "camera": return 0xFF7FC8FF;
            case "rain": return 0xFF4F9DFF;
            case "quiet": return 0xFFA3ADBE;
            default: return 0xFF5FF3FF;
        }
    }

    // ================================================================ 🎚️ the equalizer's look (25, 26)

    static final int EQ_BARS = 0, EQ_BLOCKS = 1, EQ_MIRROR = 2;
    static final String[] EQ_STYLES = {"", "blocks", "mirror"};
    static final String[] EQ_STYLE_NAMES = {"రంగుల కడ్డీలు (ఇప్పటిది)", "25 · LED బ్లాక్స్ (ఆకుపచ్చ-పసుపు-ఎరుపు)", "26 · అద్దం (కింద నీడ ప్రతిబింబం)"};

    static int eqStyle(String pref) {
        for (int i = 0; i < EQ_STYLES.length; i++) if (EQ_STYLES[i].equals(pref)) return i;
        return EQ_BARS;
    }

    // ================================================================ 🌌 the big moving pictures (9–16: whole scenes, ours)

    static final String[] SCENES = {"sunset", "waterfall", "space", "synth", "hud", "sport", "streaks", "teal"};

    /** A whole picture (it covers the theme's background), not one of the light movements over it. Pure. */
    static boolean scene(String bg) {
        for (String s : SCENES) if (s.equals(bg)) return true;
        return false;
    }

    /** A bright picture: a veil over it keeps the words readable. Pure. */
    static boolean brightScene(String bg) { return "waterfall".equals(bg) || "teal".equals(bg) || "sunset".equals(bg); }

    // ================================================================ 🚗 the welcome (28), 🧍 Jarvis (29)

    static final String[] WELCOMES = {"", "neon"};
    static final String[] WELCOME_NAMES = {"కార్డులు 3D లో తిరిగి వస్తాయి", "28 · నియాన్ గీతల కారు గీసుకుంటూ వస్తుంది, తర్వాత కార్డులు"};
    static final String[] JARVIS_LOOKS = {"", "holo"};
    static final String[] JARVIS_LOOK_NAMES = {"మామూలు గాజు కిటికీ", "29 · హోలోగ్రామ్ (నీలి కాంతి, వలయాల వేదిక)"};

    // ================================================================ 🎵 the song (23, 24)

    /**
     * How far through the song (0–1), from the music app's own numbers: its position when it last told, that moment
     * (the phone's elapsed clock), its speed, playing or not; -1 when the app gives no length. Pure.
     */
    static float songFraction(long posMs, long updatedAt, float speed, boolean playing, long now, long durMs) {
        if (durMs <= 0 || posMs < 0) return -1;
        double p = posMs + (playing && updatedAt > 0 && now > updatedAt ? (now - updatedAt) * (double) (speed <= 0 ? 1 : speed) : 0);
        return (float) Math.max(0, Math.min(1, p / durMs));
    }

    /** "2:14" for a time in ms (-1: ""). Pure. */
    static String clock(long ms) {
        if (ms < 0) return "";
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * 24's little waves: n heights (0.15–1) made from the song's name, the same every time for the same song — a
     * decoration only (Android doesn't give a song's real sound shape). Pure.
     */
    static float[] waves(String seed, int n) {
        float[] out = new float[Math.max(0, n)];
        long h = 1125899906842597L;
        String s = seed == null ? "" : seed;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        double a = (h & 0xFFFF) / 65535.0 * 6.28, b = ((h >> 16) & 0xFFFF) / 65535.0 * 6.28;
        long r = h ^ 0x5DEECE66DL;
        for (int i = 0; i < out.length; i++) {
            r = (r * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            double noise = (r >>> 17) / (double) (1L << 31);
            double v = Math.abs(Math.sin(i * 0.45 + a) * Math.sin(i * 0.13 + b)) * 0.8 + noise * 0.2;
            out[i] = (float) Math.max(0.15, Math.min(1, 0.15 + v * 0.85));
        }
        return out;
    }

    // ================================================================ 🗺️ the map's pieces (19–22)

    /** 20's arrows: how bright arrow i (0 nearest, 2 farthest) is at time t (seconds): they light one after another. Pure. */
    static float chevron(int i, float t) {
        float step = (t * 2.4f) % 4f; // (three arrows light up in turn, then a short rest)
        return step >= i + 1 ? 1f : step >= i ? 0.45f + 0.55f * (step - i) : 0.45f; // (never fainter than about half: the way always reads)
    }

    // (a time: "5:42", "5:42 PM", "5.42 pm" — a dot only with am / pm; never a distance like "12.50 km")
    private static final Pattern ARRIVE = Pattern.compile("(\\d{1,2}):(\\d{2})(?![\\d.,])(?!\\s*(?:km|m\\b|కి|మీ))\\s*(am|pm|a\\.m\\.|p\\.m\\.)?|(\\d{1,2})\\.(\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIST = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|కి\\.?\\s?మీ|m\\b|మీ)", Pattern.CASE_INSENSITIVE);

    /**
     * 22: the maps app's trip line ("1 hr 5 min · 52 km · 5:42 PM") as three short pills — {when he gets there, how
     * long, how far} ("" for what it doesn't say); null when it says none of them (the line is shown as it is). Pure.
     */
    static String[] tripPills(String trip) {
        if (trip == null || trip.trim().isEmpty()) return null;
        String at = "", left = "", far = "";
        Matcher a = ARRIVE.matcher(trip);
        String rest = trip;
        if (a.find()) {
            boolean colon = a.group(1) != null;
            at = "🕐 " + Integer.parseInt(colon ? a.group(1) : a.group(4)) + ":" + (colon ? a.group(2) : a.group(5)) + " కి చేరతాం";
            rest = trip.substring(0, a.start()) + " " + trip.substring(a.end()); // (its minutes are not the trip's minutes)
        }
        int m = CarRoad.minutesIn(rest);
        if (m >= 0) left = "⏱️ " + (m >= 60 ? (m / 60) + " గం " + (m % 60 == 0 ? "" : (m % 60) + " ని") : m + " ని").trim();
        Matcher d = DIST.matcher(trip);
        if (d.find()) {
            String u = d.group(2).toLowerCase(Locale.ROOT);
            far = "📍 " + d.group(1).replace(',', '.') + (u.startsWith("km") || u.startsWith("కి") ? " కి.మీ" : " మీ");
        }
        if (at.isEmpty() && left.isEmpty() && far.isEmpty()) return null;
        return new String[]{at, left, far};
    }

    // ================================================================ ✨ my suggested set (one tap in ⚙️ — his tap)

    /** The set suggested on 25 Oct: ⑩, 5, D, 11, 29, 31, 19–22, 23, 26 (the rest of his look stays). */
    static Map<String, Object> suggested() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("car_layout", CarLayout.DOCK);
        m.put("car_gauge", "neon");
        m.put("car_theme", 14);
        m.put("car_bg_motion", "space");
        m.put("car_jarvis_look", "holo");
        m.put("car_icons", "neon");
        m.put("car_route_glow", true);
        m.put("car_turn_arrows", true);
        m.put("car_map_limit", true);
        m.put("car_map_pills", true);
        m.put("car_vinyl", true);
        m.put("car_eq_style", "mirror");
        return m;
    }
}
