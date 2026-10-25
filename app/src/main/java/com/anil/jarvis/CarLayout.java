package com.anil.jarvis;

/**
 * 🧩 The car screen's shapes (no Android here: desk-tested). Anil picks one in ⚙️; the same parts (Jarvis, the speed,
 * the clock, the road card, the six buttons, the song strip) are arranged around the map in the map shapes. While a
 * Google Maps trip runs, the classic screen turns into the map screen (G1) — when that switch is on — and turns back
 * when the trip ends.
 */
final class CarLayout {
    private CarLayout() {}

    static final String CLASSIC = "classic", MAPCARDS = "mapcards", HALFMAP = "halfmap", NAV = "nav";
    /** The shapes he can pick (in this order in ⚙️), and their names. */
    static final String[] CHOICES = {CLASSIC, MAPCARDS, HALFMAP};
    static final String[] NAMES = {"⑥ ఇప్పటి స్క్రీన్ (క్లాసిక్)", "④ మ్యాప్ + కార్డులు", "⑦ సగం మ్యాప్"};

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

    /** The shape on the screen now: his pick; the classic one becomes the map screen while a trip runs (if he wants). Pure. */
    static String now(String chosen, boolean trip, boolean mapOnTrip) {
        String c = valid(chosen);
        return trip && mapOnTrip && CLASSIC.equals(c) ? NAV : c;
    }

    /** This shape shows the map. Pure. */
    static boolean hasMap(String shape) { return !CLASSIC.equals(shape); }
}
