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
            COCKPIT = "cockpit", CHROME = "chrome", MAP3D = "map3d";
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

    /** This shape shows the map. Pure. */
    static boolean hasMap(String shape) { return MAPCARDS.equals(shape) || HALFMAP.equals(shape) || NAV.equals(shape) || MAP3D.equals(shape); }

    /** One of the 3D shapes (leaning cards, chrome round buttons). Pure. */
    static boolean deep(String shape) { return COCKPIT.equals(shape) || CHROME.equals(shape) || MAP3D.equals(shape); }
}
