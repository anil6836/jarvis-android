package com.anil.jarvis;

/**
 * 🧩 The car screen's shapes (no Android here: desk-tested). Anil picks one in ⚙️; the same parts (Jarvis, the speed,
 * the clock, the road card, the six buttons, the song strip) are arranged around the map in the map shapes, and on
 * leaning 3D cards in ① ② ③ (our own designs). While a Google Maps trip runs, a shape without the map turns into a
 * map screen — when that switch is on — and turns back when the trip ends: ⑥ into the half map (G1), ① ② into ③.
 */
final class CarLayout {
    private CarLayout() {}

    static final String CLASSIC = "classic", MAPCARDS = "mapcards", HALFMAP = "halfmap", NAV = "nav",
            COCKPIT = "cockpit", CHROME = "chrome", MAP3D = "map3d",
            /** ⑧ parked with a song: Jarvis big on a lit stage (never picked by hand: it comes and goes by itself). */
            STAGE = "stage",
            /** G2: the Google Maps app in the box's own window beside Jarvis; Jarvis's column on the right. */
            SIDE = "side";
    /** The shapes he can pick (in this order in ⚙️), and their names. */
    static final String[] CHOICES = {COCKPIT, CHROME, MAP3D, MAPCARDS, CLASSIC, HALFMAP};
    static final String[] NAMES = {"① కాక్‌పిట్ 3D", "② క్రోమ్ డయల్స్", "③ 3D మ్యాప్ డాష్", "④ మ్యాప్ + కార్డులు", "⑥ ఇప్పటి స్క్రీన్ (క్లాసిక్)", "⑦ సగం మ్యాప్"};

    /** A shape he can pick (anything else: the classic one). Pure. */
    static String valid(String s) {
        for (String c : CHOICES) if (c.equals(s)) return c;
        return CLASSIC;
    }

    static String name(String s) {
        String v = valid(s);
        for (int i = 0; i < CHOICES.length; i++) if (CHOICES[i].equals(v)) return NAMES[i];
        return NAMES[0];
    }

    /** The shape on the screen now: his pick; one without the map becomes a map screen while a trip runs (if he wants). Pure. */
    static String now(String chosen, boolean trip, boolean mapOnTrip) {
        String c = valid(chosen);
        if (trip && mapOnTrip && !hasMap(c)) return deep(c) ? MAP3D : NAV;
        return c;
    }

    /**
     * The same, with G2 chosen (the Google Maps app beside Jarvis instead of Jarvis's map): a shape that would show the
     * map gives its room to Google Maps' window. Pure.
     */
    static String now(String chosen, boolean trip, boolean mapOnTrip, boolean g2) {
        String c = now(chosen, trip, mapOnTrip);
        return g2 && hasMap(c) ? SIDE : c;
    }

    private static final java.util.regex.Pattern G2_WORDS = java.util.regex.Pattern.compile(
            "^(google maps?|గూగుల్ మ్యాప్(స్)?|గూగుల్ మేప్(స్)?)\\s*(ని|ను|నే)?\\s*(పక్కన )?(పెట్టు|పెట్టండి|పెట్టవా|పెట్టరా|కి మారు|కు మారు|కి మార్చు|కు మార్చు)$"
                    + "|^(switch to|put) google maps?( beside)?$"); // (not "show google maps": that is often "open it")
    private static final java.util.regex.Pattern G1_WORDS = java.util.regex.Pattern.compile(
            "^(నీ|మన|నా|నీ సొంత)?\\s*(మ్యాప్|మ్యాపు|మేప్|map)\\s*(ని|ను)?\\s*(పెట్టు|పెట్టండి|పెట్టవా|కి మారు|కు మారు|కి మార్చు|కు మార్చు|చూపించు)$"
                    + "|^(switch to|put|show) (your |the )?map$"); // (his name is taken out first: "switch to Jarvis map" → "switch to map")

    /**
     * 🗺️ His words to switch the car's map: "g2" ("Jarvis, Google Maps పెట్టు"), "g1" ("Jarvis, నీ మ్యాప్ పెట్టు"), else
     * null. Only the whole short sentence counts ("Google Maps లో దారి పెట్టు" is not a switch). Pure.
     */
    static String mapWords(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\u200c\u200d]", "").replaceAll("(jarvis|జార్విస్|జర్విస్)", " ")
                .replaceAll("[,.!?।\"'“”]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 60) return null;
        if (G2_WORDS.matcher(t).matches()) return "g2";
        if (G1_WORDS.matcher(t).matches()) return "g1";
        return null;
    }

    /** This shape shows the map. Pure. */
    static boolean hasMap(String shape) { return MAPCARDS.equals(shape) || HALFMAP.equals(shape) || NAV.equals(shape) || MAP3D.equals(shape); }

    /** One of the 3D shapes (leaning cards, chrome round buttons). Pure. */
    static boolean deep(String shape) { return COCKPIT.equals(shape) || CHROME.equals(shape) || MAP3D.equals(shape); }
}
