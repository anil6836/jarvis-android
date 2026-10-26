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
    private static final Pattern CAR = Pattern.compile(
            "^(మ్యాప్\\s*(లో|మీద)\\s*)?(.+?)\\s*(కారు|car)?\\s*(బొమ్మ)?\\s*(ని|ను)?\\s*" + PUT + "$");
    private static final Pattern QUIET_ON = Pattern.compile(
            "^(ఈ (ప్రయాణం|ట్రిప్)\\s*)?(నిశ్శబ్దంగా ఉండు|నిశ్శబ్దంగా ఉండండి|నిశ్శబ్దం (పెట్టు|ఆన్ చేయి|ఆన్)|సైలెంట్ గా ఉండు|quiet mode( on)?|be quiet for (this|the) trip)$");
    private static final Pattern QUIET_OFF = Pattern.compile(
            "^(నిశ్శబ్దం (ఆపు|ఆపేయ్|ఆపేయి|ఆఫ్ చేయి|ఆఫ్|వద్దు|తీసేయి|తీసేయ్)|మళ్ళీ మాట్లాడు|మళ్లీ మాట్లాడు|quiet mode off)$");
    private static final Pattern BIG_MAP = Pattern.compile(
            "^(మ్యాప్ (పెద్దది|పెద్దగా) (చేయి|చెయ్|పెట్టు)|పెద్ద మ్యాప్ (పెట్టు|చూపించు)|full map|big map)$");
    private static final Pattern SMALL_MAP = Pattern.compile("^(మ్యాప్ (చిన్నది|చిన్నగా) (చేయి|చెయ్|పెట్టు)|small map)$");

    /**
     * {"src", "osm" | "google"}, {"car", one of CarDeck.MAP_CARS}, {"quiet", "on" | "off"}, {"map", "big" | "small"};
     * null when it isn't one of these. Pure.
     */
    static String[] parse(String text) {
        if (text == null || text.contains("?") || text.contains("？")) return null;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[‌‍]", "").replaceAll("(jarvis|జార్విస్|జర్విస్)", " ")
                .replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 60) return null;
        if (OSM.matcher(t).matches()) return new String[]{"src", "osm"};
        if (GTILES.matcher(t).matches()) return new String[]{"src", "google"};
        if (QUIET_ON.matcher(t).matches()) return new String[]{"quiet", "on"};
        if (QUIET_OFF.matcher(t).matches()) return new String[]{"quiet", "off"};
        if (BIG_MAP.matcher(t).matches()) return new String[]{"map", "big"};
        if (SMALL_MAP.matcher(t).matches()) return new String[]{"map", "small"};
        java.util.regex.Matcher m = CAR.matcher(t);
        if (m.matches()) {
            String w = m.group(3).trim();
            String k = (w.equals("నా") || w.equals("మన")) && m.group(4) != null ? "photo" : carKind(w); // ("నా కారు బొమ్మ పెట్టు": his own photo)
            if (k != null) return new String[]{"car", k};
        }
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
            case "నా కారు ఫోటో": case "నా కారు ఫొటో": case "నా ఫోటో": case "my car photo": case "నా కారు": return "photo";
            default: return null;
        }
    }
}
