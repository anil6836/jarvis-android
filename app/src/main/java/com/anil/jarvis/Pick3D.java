package com.anil.jarvis;

import java.util.List;
import java.util.Locale;

/**
 * 🧊 The camera's 3D kinds, and Jarvis's own pick among them (no Android here: desk-tested). Anil chooses from the list;
 * "🤖 Jarvis ఎంచుకోనీ" lets the phone pick from what it already sees (the last scan's kind, the on-phone finder's rings)
 * — no AI, ₹0 — and when it is not sure it shows the list instead of guessing. The paid workshop model is never started
 * by Jarvis's pick: its cost sheet always waits for his 🔨.
 */
final class Pick3D {
    private Pick3D() {}

    static final String HOLO = "holo", REAL = "real", MODEL = "model", PHOTO = "photo3d", SPIN = "spin", CHOOSE = "choose";

    /** id, name, what it gives, cost. */
    static final String[][] KINDS = {
            {HOLO, "🧊 హోలోగ్రామ్", "బోర్డు / ఇంజిన్: parts పైకి తేలుతూ", "AI, సుమారు ₹1–3"},
            {REAL, "📐 నిజమైన 3D స్కాన్", "ఫోన్ కొలుస్తుంది: అసలు ఆకారం, cm కొలతలు", "₹0"},
            {MODEL, "🧱 వర్క్‌షాప్ బొమ్మ", "parts విడదీసే చక్కని బొమ్మ (AI అంచనా)", "సుమారు ₹15–80, ముందే చూపిస్తుంది"},
            {PHOTO, "🖼️ 3D ఫోటో", "ఒక్క ఫోటో పైకి లేస్తుంది (ముందు వైపు మాత్రమే)", "₹0"},
            {SPIN, "🔄 360° ఫోటో స్పిన్", "చుట్టూ ఫోటోలు + parts పేర్లు", "AI, సుమారు ₹2–5"}};

    static String name(String kind) {
        for (String[] k : KINDS) if (k[0].equals(kind)) return k[1];
        return "";
    }

    /** The list's rows: "🤖 Jarvis ఎంచుకోనీ" first (with the ⭐ on the one Jarvis would pick, if any), then the five. */
    static String[] rows(String suggested) {
        String[] out = new String[KINDS.length + 1];
        out[0] = "🤖 Jarvis ఎంచుకోనీ" + (suggested == null ? " (కెమెరాలో చూసి)" : " → " + name(suggested));
        for (int i = 0; i < KINDS.length; i++)
            out[i + 1] = KINDS[i][1] + (KINDS[i][0].equals(suggested) ? "  ⭐ సూచన" : "") + "\n   " + KINDS[i][2] + " · " + KINDS[i][3];
        return out;
    }

    /** The kind of row `w` of rows() (0 = Jarvis's pick). */
    static String kindOfRow(int w) { return w <= 0 || w > KINDS.length ? CHOOSE : KINDS[w - 1][0]; }

    /** One thing the on-phone finder sees: its English label, how much of the screen it covers (0..1), its middle (0..1). */
    static final class Seen {
        final String en;
        final float area, cx, cy;
        Seen(String en, float area, float cx, float cy) { this.en = en == null ? "" : en; this.area = area; this.cx = cx; this.cy = cy; }
    }

    private static final java.util.Set<String> BOARD_KINDS = set("pcb", "electronics", "wiring", "diagram", "inside", "engine");
    private static final java.util.Set<String> WALK_ROUND = set("motorcycle", "bicycle", "chair", "couch", "bed", "dining table", "bench",
            "refrigerator", "oven", "microwave", "tv", "toilet", "sink", "potted plant", "suitcase", "fire hydrant", "parking meter",
            "desk", "table", "cabinet", "barrel", "drum", "machine", "laptop");
    private static final java.util.Set<String> TOO_BIG = set("car", "bus", "truck", "train", "boat", "airplane");
    private static final java.util.Set<String> LIVING = set("person", "face", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe");

    /**
     * Jarvis's own pick, or null when it is not sure (then the list shows). lastKind: the kind of the last answer's scan
     * (pcb, engine…), lastAgeMs: how long ago; seen: the finder's steady rings now.
     */
    static String auto(String lastKind, long lastAgeMs, List<Seen> seen) {
        if (lastKind != null && BOARD_KINDS.contains(lastKind) && lastAgeMs >= 0 && lastAgeMs < 5 * 60_000) return HOLO;
        Seen main = null;
        float best = 0;
        if (seen != null) for (Seen s : seen) {
            if (s.en.startsWith("text:")) continue;
            float centre = 1f - Math.min(1f, (float) Math.hypot(s.cx - 0.5f, s.cy - 0.5f) * 1.6f);
            float score = s.area * (0.35f + centre);
            if (score > best) { best = score; main = s; }
        }
        if (main == null || main.area < 0.02f) return null; // nothing clear in the middle
        if (LIVING.contains(main.en)) return null;          // people and animals: not ours to model; he chooses
        if (TOO_BIG.contains(main.en)) return SPIN;         // a car is bigger than the scan box: photos round it
        if (WALK_ROUND.contains(main.en)) return REAL;
        return main.area < 0.25f ? SPIN : PHOTO;            // a small thing on its own: photos round it; a near one: its 3D photo
    }

    /** Why Jarvis picked it (one short Telugu line). */
    static String why(String kind) {
        switch (kind) {
            case HOLO: return "బోర్డు / ఇంజిన్ కాబట్టి హోలోగ్రామ్";
            case REAL: return "పెద్ద వస్తువు కాబట్టి నిజమైన 3D స్కాన్ (కొలతలతో)";
            case SPIN: return "చుట్టూ ఫోటోలతో 360°";
            case PHOTO: return "దగ్గరగా ఉన్న వస్తువు కాబట్టి 3D ఫోటో";
            default: return "";
        }
    }

    /** What his words ask for: a kind, CHOOSE (just "3D": the list or Jarvis's pick), or null (not a 3D command). */
    static String voice(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]", " ").replaceAll("\\s+", " ").trim();
        t = t.replace("త్రీ డీ", "3d").replace("త్రీడీ", "3d").replace("3డి", "3d").replace("3 d", "3d").replace("త్రీడి", "3d");
        if (t.isEmpty()) return null;
        if (t.matches("(హోలోగ్రామ్|హోలో|hologram)( చూపించు| చేయి| చెయ్)?")) return HOLO;
        if (t.matches("(నిజమైన|అసలు|real) ?(3d )?(స్కాన్|scan)( చేయి| చెయ్)?") || t.matches("(కొలతలతో|depth) ?(3d )?(స్కాన్|scan)( చేయి| చెయ్)?")
                || t.matches("నిజమైన 3d( చేయి| చెయ్)?|real 3d( scan)?")) return REAL;
        if (t.matches("(వర్క్‌షాప్|వర్క్ షాప్|workshop) ?(బొమ్మ|బొమ్మగా|model)( చేయి| చెయ్| కట్టు)?")) return MODEL;
        if (t.matches("3d ?(ఫోటో|photo)( చేయి| చెయ్| చూపించు)?")) return PHOTO;
        if (t.matches("(ఫోటో స్పిన్|photo spin)( చేయి| చెయ్| చూపించు)?")) return SPIN;
        if (t.matches("3d( చూపించు| స్కాన్| స్కాన్ చేయి| స్కాన్ చెయ్| చేయి| చెయ్| scan)?")) return CHOOSE;
        return null;
    }

    // ================================================================ which side each photo shows (for the workshop model)

    private static final String[] EN = {"front", "front-left corner", "left side", "back-left corner", "back", "back-right corner", "right side", "front-right corner"};
    private static final String[] TE = {"ముందు", "ముందు-ఎడమ మూల", "ఎడమ వైపు", "వెనక-ఎడమ మూల", "వెనక", "వెనక-కుడి మూల", "కుడి వైపు", "ముందు-కుడి మూల"};

    /**
     * The photos' sides for the AI (English) and for him (Telugu): {english, telugu}. az: each photo's heading in degrees
     * (growing as he walks clockwise round it, seen from above), null = not known (gallery photos); tilt: 1 from above,
     * -1 from below, 0 round (null = all round). Photo 1 is the front.
     */
    static String[] viewNote(float[] az, int[] tilt, int count) {
        int n = Math.max(1, count);
        if (az == null) {
            if (n == 1) return new String[]{"1 = the front (only one photo: every side it does not show is a guess)", "1 ఫోటో (ముందు వైపు; మిగతావి అంచనా)"};
            return new String[]{n + " photos of the same object from different sides (which side each is, is not known: work it out from the photos)", n + " ఫోటోలు (ఏ వైపో AI చూసి తెలుసుకుంటుంది)"};
        }
        StringBuilder en = new StringBuilder(), te = new StringBuilder();
        boolean[] seen = new boolean[4]; // front, left, back, right
        boolean top = false, bottom = false;
        for (int i = 0; i < n; i++) {
            int tl = tilt != null && i < tilt.length ? tilt[i] : 0;
            String e, t;
            if (tl > 0) { e = "from above"; t = "పై నుంచి"; top = true; }
            else if (tl < 0) { e = "from below"; t = "కింద నుంచి"; bottom = true; }
            else {
                float rel = (((i < az.length ? az[i] : 0) - az[0]) % 360 + 360) % 360;
                int s = Math.round(rel / 45f) % 8;
                e = EN[s];
                t = TE[s];
                for (int k = 0; k < 4; k++) { float dd = Math.abs(((rel - k * 90) % 360 + 540) % 360 - 180); if (dd <= 50) seen[k] = true; }
            }
            en.append(i == 0 ? "" : ", ").append(i + 1).append(" = ").append(e);
            te.append(i == 0 ? "" : ", ").append(t);
        }
        StringBuilder missEn = new StringBuilder(), missTe = new StringBuilder();
        String[] sideEn = {"front", "left side", "back", "right side"}, sideTe = {"ముందు", "ఎడమ వైపు", "వెనక", "కుడి వైపు"};
        for (int k = 0; k < 4; k++) if (!seen[k]) { missEn.append(missEn.length() == 0 ? "" : ", ").append(sideEn[k]); missTe.append(missTe.length() == 0 ? "" : ", ").append(sideTe[k]); }
        if (!top) { missEn.append(missEn.length() == 0 ? "" : ", ").append("top"); missTe.append(missTe.length() == 0 ? "" : ", ").append("పై భాగం"); }
        if (!bottom) { missEn.append(missEn.length() == 0 ? "" : ", ").append("underneath"); missTe.append(missTe.length() == 0 ? "" : ", ").append("కింది భాగం"); }
        return new String[]{en + (missEn.length() == 0 ? "" : "; not seen in any photo: " + missEn),
                n + " వైపులు: " + te + (missTe.length() == 0 ? "" : " · చూడనివి (అంచనా): " + missTe)};
    }

    private static java.util.Set<String> set(String... s) { return new java.util.HashSet<>(java.util.Arrays.asList(s)); }
}
