package com.anil.jarvis;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 🗣️ His short words on the car box for ⑪'s new choices (no Android here: desk-tested), done at once any time — also
 * while driving, words only: the map's pictures ("ఉచిత మ్యాప్ పెట్టు" / "మ్యాప్‌లో గూగుల్ పెట్టు"), the car on the map
 * ("Jarvis, జీప్ పెట్టు"), 🔇 quiet for this trip ("నిశ్శబ్దంగా ఉండు" / "నిశ్శబ్దం ఆపు") and the big map ("మ్యాప్ పెద్దది
 * చేయి"). Only the whole short sentence counts; a question goes to Jarvis. Never the G1 / G2 words ({@link CarLayout#mapWords}).
 */
final class CarWords {
    private CarWords() {}

    private static final String PUT = "(పెట్టు|పెట్టండి|పెట్టవా|పెట్టరా|కి మారు|కు మారు|కి మార్చు|కు మార్చు|చూపించు|వేయి|వెయ్)";
    private static final Pattern OSM = Pattern.compile(
            "^(ఉచిత|ఫ్రీ|free|osm|ఓఎస్ఎమ్|ఓపెన్ ?స్ట్రీట్|open ?street)\\s*(మ్యాప్|మ్యాపు|మేప్|map)\\s*(ని|ను)?\\s*" + PUT + "$"
                    + "|^(switch to |put |use )?(the )?(free|osm|open ?street) map$");
    private static final Pattern GTILES = Pattern.compile(
            "^(నీ |మన |నా )?(మ్యాప్|మ్యాపు|మేప్)\\s*(లో|మీద)\\s*(గూగుల్|google)\\s*(బొమ్మలు|మ్యాప్)?\\s*" + PUT + "$"
                    + "|^(use |put )?google tiles$");
    /** (no "చూపించు" here: "నా కారు చూపించు" is a question for Jarvis, never a change of the map's car) */
    private static final String PUT_CAR = "(పెట్టు|పెట్టండి|పెట్టవా|పెట్టరా|కి మారు|కు మారు|కి మార్చు|కు మార్చు)";
    private static final Pattern CAR = Pattern.compile(
            "^(మ్యాప్\\s*(లో|మీద)\\s*)?(.+?)\\s*(కారు|car)?\\s*(బొమ్మ)?\\s*(ని|ను)?\\s*" + PUT_CAR + "$");
    private static final Pattern ROT_ON = Pattern.compile("^((మ్యాప్ )?దారి పైకి (పెట్టు|పెట్టండి|తిప్పు)|మ్యాప్ (తిప్పు|తిరగనివ్వు)|heading up)$");
    private static final Pattern ROT_OFF = Pattern.compile("^((మ్యాప్ )?ఉత్తరం పైకి (పెట్టు|పెట్టండి)|north up)$");
    private static final Pattern QUIET_ON = Pattern.compile(
            "^(ఈ (ప్రయాణం|ట్రిప్)\\s*)?(నిశ్శబ్దంగా ఉండు|నిశ్శబ్దంగా ఉండండి|నిశ్శబ్దం (పెట్టు|ఆన్ చేయి|ఆన్)|సైలెంట్ గా ఉండు|quiet mode( on)?|be quiet for (this|the) trip)$");
    private static final Pattern QUIET_OFF = Pattern.compile(
            "^(నిశ్శబ్దం (ఆపు|ఆపేయ్|ఆపేయి|ఆఫ్ చేయి|ఆఫ్|వద్దు|తీసేయి|తీసేయ్)|మళ్ళీ మాట్లాడు|మళ్లీ మాట్లాడు|quiet mode off)$");
    private static final Pattern BIG_MAP = Pattern.compile(
            "^(మ్యాప్ (పెద్దది|పెద్దగా) (చేయి|చెయ్|పెట్టు)|పెద్ద మ్యాప్ (పెట్టు|చూపించు)|full map|big map)$");
    private static final Pattern SMALL_MAP = Pattern.compile("^(మ్యాప్ (చిన్నది|చిన్నగా) (చేయి|చెయ్|పెట్టు)|small map)$");
    /** ⑯ (stage 3): "3D లో విజయవాడ కి దారి", "3డి లో ఇంటికి దారి పెట్టు" — our own free way in the 3D view (a list; his tap starts it). */
    private static final Pattern WAY3D = Pattern.compile(
            "^(3d|3డి|త్రీడీ|త్రీ డీ)\\s*(లో|మ్యాప్ లో|మ్యాప్‌లో)?\\s*(.+?)\\s*(కి|కు)?\\s*దారి\\s*(పెట్టు|పెట్టండి|చూపించు|చూపు|వేయి)?$"
                    + "|^(3d )?(way|route) to (.+?) in 3d$");

    /** ⑮ (stage 4): "2D మ్యాప్ పెట్టు", "3D మ్యాప్ పెట్టు", "గేమ్ వ్యూ", "మ్యాప్ వ్యూ", "కెమెరా పైకి / దగ్గరగా / మధ్యలో", "చీకటి మ్యాప్ పెట్టు", "తెల్లటి మ్యాప్ పెట్టు". */
    private static final String DO = "(పెట్టు|పెట్టండి|చేయి|చెయ్|చూపించు)";
    private static final Pattern C2D = Pattern.compile("^(మ్యాప్ )?(2d|2డి|టూడీ|టూ డీ)( మ్యాప్)?( " + DO + ")?$|^(2d|flat) map$");
    private static final Pattern C3D = Pattern.compile("^(మ్యాప్ )?(3d|3డి|త్రీడీ|త్రీ డీ)( మ్యాప్)? " + DO + "$|^3d map$");
    private static final Pattern CGAME = Pattern.compile("^(గేమ్ వ్యూ|గేమ్ లాగా|game view)( " + DO + ")?$");
    private static final Pattern CMAP = Pattern.compile("^(మ్యాప్ వ్యూ|map view)( " + DO + ")?$");
    private static final Pattern CNEAR = Pattern.compile("^కెమెరా (దగ్గరగా|దగ్గరికి|కిందికి|కిందకి)( (పెట్టు|రా|తీసుకురా))?$|^camera (closer|near|down)$");
    private static final Pattern CMID = Pattern.compile("^కెమెరా మధ్యలో( పెట్టు)?$|^camera middle$");
    private static final Pattern CFAR = Pattern.compile("^కెమెరా (పైకి|దూరంగా|ఎత్తుగా|ఎత్తులో)( (పెట్టు|వెళ్ళు|తీసుకెళ్ళు))?$|^camera (higher|far|up)$");
    private static final Pattern CDARK = Pattern.compile("^(మ్యాప్ )?(చీకటి|నలుపు|నైట్) (మ్యాప్ )?(రంగు )?" + DO + "$|^dark map$");
    private static final Pattern CLIGHT = Pattern.compile("^(మ్యాప్ )?(తెల్లగా|తెల్లటి|తెలుపు|వైట్) (మ్యాప్ )?(రంగు )?" + DO + "$|^(white|light) map$");

    /** 🎬 (31 Oct) "కొత్త Jarvis పెట్టు" / "సినిమా Jarvis పెట్టు" ↔ "పాత Jarvis పెట్టు"; "హీరోయిన్లు వద్దు" / "హీరోయిన్లు పెట్టు". */
    private static final String JV = "(jarvis|జార్విస్|జర్విస్|జార్వీస్)";
    private static final Pattern FILM_ON = Pattern.compile("^(కొత్త|సినిమా|ఏఐ|ai|వీడియో|new|film)\\s*" + JV + "\\s*(ని|ను)?\\s*" + PUT_CAR + "$|^(put |show )?(the )?(new|film) jarvis$");
    private static final Pattern FILM_OFF = Pattern.compile("^(పాత|2d|2డి|టూడీ|బొమ్మ|old)\\s*" + JV + "\\s*(ని|ను)?\\s*" + PUT_CAR + "$|^(put |show )?(the )?(old|2d) jarvis$");
    private static final String HEROINES = "(హీరోయిన్లు|హీరోయిన్స్|హీరోయిన్లను|హీరోయిన్ సెట్|హీరోయిన్ల సెట్|heroines?|heroine set)";
    private static final Pattern SETS_OFF = Pattern.compile("^" + HEROINES + "\\s*(వద్దు|ఆపు|ఆపేయ్|ఆపేయి|ఆఫ్|off)$");
    private static final Pattern SETS_ON = Pattern.compile("^" + HEROINES + "\\s*(పెట్టు|పెట్టండి|రావాలి|కావాలి|ఆన్|on)$");
    /** ✨ (31 Oct) ⑰ ⑲ ⑳'s day / night: "రాత్రి రూపం పెట్టు" ↔ "పగలు రూపం పెట్టు" (his words: the hour never changes them). */
    private static final Pattern NEW_DARK = Pattern.compile("^(రాత్రి|చీకటి|డార్క్|ముదురు|నైట్) (రూపం|థీమ్|లుక్) " + DO + "$|^(dark|night) (look|theme)$");
    /** 🏁 (31 Oct) ⑯'s HUD: "నియాన్ HUD పెట్టు", "తెల్లటి HUD పెట్టు", "మామూలు HUD పెట్టు"; 🌆 its world: "నియాన్ ప్రపంచం పెట్టు". */
    private static final String HUD = "(hud|హడ్|హెచ్ యు డి|హెచ్‌యూడీ)";
    private static final Pattern HUD_NEON = Pattern.compile("^(నియాన్|రేసింగ్|గేమ్) " + HUD + " " + DO + "$|^(neon|racing) hud$");
    private static final Pattern HUD_WHITE = Pattern.compile("^(తెల్లటి|తెల్ల|వైట్|adas|ఏడాస్) " + HUD + " " + DO + "$|^(white|adas) hud$");
    private static final Pattern HUD_OFF = Pattern.compile("^(మామూలు|పాత) " + HUD + " " + DO + "$|^(normal|old) hud$");
    private static final Pattern WORLD = Pattern.compile("^(.{2,24}?) (ప్రపంచం|వరల్డ్) " + DO + "$|^(.{2,24}?) world$");
    private static final Pattern NEW_LIGHT = Pattern.compile("^(పగలు|పగటి|తెల్లటి|తెల్ల|లైట్|డే) (రూపం|థీమ్|లుక్) " + DO + "$|^(light|day) (look|theme)$");

    /**
     * {"jarvis", "film" | "2d"}, {"sets", "on" | "off"}, {"newnight", "dark" | "day"}, {"hud16", "" | "neon" | "white"}, {"world", a CarDeck.SKIES_3D key},
     * {"src", "osm" | "google"}, {"car", one of CarDeck.MAP_CARS}, {"quiet", "on" | "off"}, {"map", "big" | "small"}, {"rotate", "on" | "off"},
     * {"way3d", the place ("home" for home)}, {"city", "2d" | "3d" | "gameon" | "gameoff" | "near" | "mid" | "far" | "dark" | "light"};
     * null when it isn't one of these. Pure.
     */
    static String[] parse(String text) {
        if (text == null || text.contains("?") || text.contains("？")) return null;
        String jw = text.toLowerCase(Locale.ROOT).replaceAll("[‌‍]", "").replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^" + JV + "\\s+", ""); // (only the call in front taken off: "Jarvis" is a word of these)
        if (FILM_ON.matcher(jw).matches()) return new String[]{"jarvis", "film"};
        if (FILM_OFF.matcher(jw).matches()) return new String[]{"jarvis", "2d"};
        if (SETS_OFF.matcher(jw).matches()) return new String[]{"sets", "off"};
        if (SETS_ON.matcher(jw).matches()) return new String[]{"sets", "on"};
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[‌‍]", "").replaceAll("(jarvis|జార్విస్|జర్విస్)", " ")
                .replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 60) return null;
        if (OSM.matcher(t).matches()) return new String[]{"src", "osm"};
        if (GTILES.matcher(t).matches()) return new String[]{"src", "google"};
        if (QUIET_ON.matcher(t).matches()) return new String[]{"quiet", "on"};
        if (QUIET_OFF.matcher(t).matches()) return new String[]{"quiet", "off"};
        if (BIG_MAP.matcher(t).matches()) return new String[]{"map", "big"};
        if (SMALL_MAP.matcher(t).matches()) return new String[]{"map", "small"};
        if (ROT_ON.matcher(t).matches()) return new String[]{"rotate", "on"};
        if (ROT_OFF.matcher(t).matches()) return new String[]{"rotate", "off"};
        if (C2D.matcher(t).matches()) return new String[]{"city", "2d"};
        if (C3D.matcher(t).matches()) return new String[]{"city", "3d"};
        if (CGAME.matcher(t).matches()) return new String[]{"city", "gameon"};
        if (CMAP.matcher(t).matches()) return new String[]{"city", "gameoff"};
        if (CNEAR.matcher(t).matches()) return new String[]{"city", "near"};
        if (CMID.matcher(t).matches()) return new String[]{"city", "mid"};
        if (CFAR.matcher(t).matches()) return new String[]{"city", "far"};
        if (CDARK.matcher(t).matches()) return new String[]{"city", "dark"};
        if (CLIGHT.matcher(t).matches()) return new String[]{"city", "light"};
        if (HUD_NEON.matcher(t).matches()) return new String[]{"hud16", "neon"};
        if (HUD_WHITE.matcher(t).matches()) return new String[]{"hud16", "white"};
        if (HUD_OFF.matcher(t).matches()) return new String[]{"hud16", ""};
        java.util.regex.Matcher wm = WORLD.matcher(t);
        if (wm.matches()) {
            String k = worldKey(wm.group(1) != null ? wm.group(1) : wm.group(4));
            if (k != null) return new String[]{"world", k};
        }
        if (NEW_DARK.matcher(t).matches()) return new String[]{"newnight", "dark"};
        if (NEW_LIGHT.matcher(t).matches()) return new String[]{"newnight", "day"};
        java.util.regex.Matcher w3 = WAY3D.matcher(t);
        if (w3.matches()) {
            String place = (w3.group(3) != null ? w3.group(3) : w3.group(8)).trim();
            if (place.equals("ఇంటి") || place.equals("ఇల్లు") || place.equals("ఇంటికి")) place = "home";
            if (!place.isEmpty()) return new String[]{"way3d", place};
        }
        java.util.regex.Matcher m = CAR.matcher(t);
        if (m.matches()) {
            String w = m.group(3).trim();
            String k = (w.equals("నా") || w.equals("మన")) && m.group(4) != null ? "photo" : carKind(w); // ("నా కారు బొమ్మ పెట్టు": his own photo)
            if (k != null) return new String[]{"car", k};
        }
        return null;
    }

    /** ⑯'s world from its name ("నియాన్", "సూర్యాస్తమయం", "తెల్లటి", "ఊదా", "రాత్రి హైవే", "పగలు హైవే"); null: none. Pure. */
    static String worldKey(String w0) {
        String w = w0 == null ? "" : w0.trim();
        if (w.contains("ఊదా") || w.contains("purple") || w.contains("violet")) return "whitev";
        if (w.contains("నియాన్") || w.contains("neon")) return "neon";
        if (w.contains("సూర్యాస్తమయ") || w.contains("సన్‌సెట్") || w.contains("సన్ సెట్") || w.contains("sunset")) return "sunset";
        if (w.contains("తెల్ల") || w.contains("వైట్") || w.contains("white")) return "white";
        boolean ar = w.matches("(?s).*\\bar\\b.*") || w.contains("ఏఆర్");
        if (w.contains("పగలు") || ar || w.matches("(?s).*\\bday\\b.*")) return w.contains("హైవే") || w.contains("highway") || ar ? "dayar" : null;
        if (w.contains("హైవే") || w.contains("highway")) return "highway";
        return null;
    }

    /** The car on the map from its name ("జీప్", "SUV", "నా కారు ఫోటో", "బాణం", "నీలి చుక్క"); null: none. */
    static String carKind(String w0) {
        String w = w0 == null ? "" : w0.replaceAll("[\u200c\u200d]", "").trim(); // (the words without their joiners: as he said them, typed or heard)
        switch (w) {
            case "జీప్": case "jeep": case "థార్ లాంటి జీప్": return "jeep";
            case "suv": case "ఎస్ యూ వీ": case "ఎస్యూవీ": return "suv";
            case "సెడాన్": case "sedan": return "sedan";
            case "హ్యాచ్బ్యాక్": case "హ్యాచ్ బ్యాక్": case "హ్యాచ్": case "hatchback": case "hatch": return "hatch";
            case "స్పోర్ట్స్": case "sports": case "స్పోర్ట్": case "రేసింగ్": return "sports";
            case "కాన్సెప్ట్": case "concept": case "ఫ్యూచర్": return "concept";
            case "బాణం": case "బాణాన్ని": case "arrow": case "నావిగేషన్ బాణం": return "arrow";
            case "నీలి చుక్క": case "నీలం చుక్క": case "చుక్క": case "blue dot": case "dot": return "";
            case "నా కారు ఫోటో": case "నా కారు ఫొటో": case "my car photo": return "photo"; // (always with "కారు": "నా ఫోటో" is not this)
            default: return null;
        }
    }
}
