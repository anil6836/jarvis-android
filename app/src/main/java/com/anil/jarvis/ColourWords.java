package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Colours in words, both ways: the main colour of a patch of pixels → a name ("red"), and his words ("ఆ ఎర్ర చొక్కా
 * అబ్బాయి") → what to look for (colour, kind, side, near / far). Pure Java (tested on a desk); the camera uses it to lock
 * on at once, before the AI says anything.
 */
final class ColourWords {
    private ColourWords() {}

    /** The colour names the phone can tell apart. */
    static final String[] NAMES = {"red", "orange", "yellow", "green", "blue", "purple", "pink", "white", "black", "grey", "brown"};

    private static final String[][] TE = {
            {"red", "ఎరుపు", "ఎర్ర"}, {"orange", "నారింజ", "నారింజ"}, {"yellow", "పసుపు", "పసుపు"}, {"green", "ఆకుపచ్చ", "పచ్చ"},
            {"blue", "నీలం", "నీలి"}, {"purple", "ఊదా", "ఊదా"}, {"pink", "గులాబీ", "గులాబీ"}, {"white", "తెలుపు", "తెల్ల"},
            {"black", "నలుపు", "నల్ల"}, {"grey", "బూడిద", "బూడిద"}, {"brown", "గోధుమ", "గోధుమ"}};

    /** The Telugu adjective ("ఎర్ర") for a colour name; "" when unknown. */
    static String te(String name) {
        for (String[] t : TE) if (t[0].equals(name)) return t[2];
        return "";
    }

    /** The name of the colour most of these pixels share (ARGB ints); "" when too mixed or too few. */
    static String name(int[] px, int n) {
        if (px == null || n < 12) return "";
        int[] votes = new int[NAMES.length];
        float[] hsv = new float[3];
        int counted = 0;
        for (int i = 0; i < n; i++) {
            int c = px[i];
            rgbToHsv((c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff, hsv);
            int k = index(hsv);
            if (k >= 0) { votes[k]++; counted++; }
        }
        if (counted < 12) return "";
        int best = 0;
        for (int k = 1; k < votes.length; k++) if (votes[k] > votes[best]) best = k;
        return votes[best] * 100 / counted >= 40 ? NAMES[best] : ""; // (a good part of the patch agrees)
    }

    /** One pixel's colour name (for tests). */
    static String name(int rgb) {
        float[] hsv = new float[3];
        rgbToHsv((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, hsv);
        int k = index(hsv);
        return k < 0 ? "" : NAMES[k];
    }

    private static int index(float[] hsv) {
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (v < 0.16f) return 8;                       // black
        if (s < 0.17f) return v > 0.78f ? 7 : v > 0.42f ? 9 : 8; // white / grey / black
        if (s < 0.30f && v < 0.55f) return 9;           // dull grey
        if (h < 14 || h >= 345) return v < 0.45f && s > 0.4f ? 10 : 0; // red (dark red → brown)
        if (h < 40) return v < 0.6f ? 10 : 1;           // orange / brown
        if (h < 68) return 2;                           // yellow
        if (h < 165) return 3;                          // green
        if (h < 255) return 4;                          // blue
        if (h < 290) return 5;                          // purple
        if (h < 345) return s < 0.55f || v > 0.8f ? 6 : 5; // pink / purple
        return -1;
    }

    private static void rgbToHsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf)), d = max - min;
        float h = 0;
        if (d > 1e-5f) {
            if (max == rf) h = 60 * (((gf - bf) / d) % 6);
            else if (max == gf) h = 60 * ((bf - rf) / d + 2);
            else h = 60 * ((rf - gf) / d + 4);
            if (h < 0) h += 360;
        }
        out[0] = h;
        out[1] = max <= 0 ? 0 : d / max;
        out[2] = max;
    }

    // ================================================================ his words

    /** What he described: any of these may be empty / null. */
    static final class Want {
        String colour = "";   // "red" …
        String kind = "";     // COCO name: person, car, motorcycle, dog … ("" any)
        String group = "";    // person / vehicle / animal ("" any)
        String side = "";     // ఎడమ / మధ్య / కుడి
        int size;             // -1 far / small, +1 near / big, 0 either
        boolean any() { return !colour.isEmpty() || !kind.isEmpty() || !group.isEmpty() || !side.isEmpty() || size != 0; }
    }

    private static final String[][] KINDS = {
            {"person", "అబ్బాయి", "అమ్మాయి", "మనిషి", "ఆయన", "ఆవిడ", "అతను", "ఆమె", "పిల్లవాడు", "పిల్లాడు", "పాప", "వ్యక్తి", "అతన్ని", "ఆమెని", "ఆవిడని", "పిల్లని", "man", "woman", "boy", "girl", "person", "guy", "lady", "kid", "child"},
            {"car", "కారు", "కారుని", "కార్", "car"}, {"motorcycle", "బైక్", "బైకుని", "బైక్‌ని", "స్కూటర్", "స్కూటీ", "bike", "scooter", "motorcycle"},
            {"bicycle", "సైకిల్", "సైకిల్‌ని", "bicycle", "cycle"}, {"bus", "బస్సు", "బస్సుని", "bus"}, {"truck", "లారీ", "లారీని", "ట్రక్", "truck", "lorry"},
            {"dog", "కుక్క", "కుక్కని", "dog"}, {"cat", "పిల్లి", "పిల్లిని", "cat"}, {"cow", "ఆవు", "ఆవుని", "ఎద్దు", "cow", "bull"},
            {"bird", "పక్షి", "bird"}, {"horse", "గుర్రం", "horse"}, {"boat", "పడవ", "boat"}, {"train", "రైలు", "train"}};

    /** Reads "ఆ ఎర్ర చొక్కా అబ్బాయిని", "the white car on the left", "కుడి వైపు దూరంగా ఉన్న బైక్". */
    static Want parse(String text) {
        Want w = new Want();
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]", " ").replaceAll("\\s+", " ").trim();
        String sp = " " + t + " ";
        for (String[] c : TE) {
            if (sp.contains(" " + c[1]) || sp.contains(" " + c[2]) || sp.contains(" " + c[0] + " ")) { w.colour = c[0]; break; }
        }
        if (w.colour.isEmpty()) { // english colours not in the table's first column spelling
            String[][] en = {{"red", "red"}, {"blue", "blue"}, {"green", "green"}, {"yellow", "yellow"}, {"white", "white"}, {"black", "black"}, {"orange", "orange"}, {"pink", "pink"}, {"purple", "purple"}, {"grey", "grey"}, {"grey", "gray"}, {"brown", "brown"}};
            for (String[] e : en) if (sp.contains(" " + e[1] + " ")) { w.colour = e[0]; break; }
        }
        outer:
        for (String[] k : KINDS) for (int i = 1; i < k.length; i++) if (sp.contains(" " + k[i] + " ") || sp.contains(" " + k[i])) { w.kind = k[0]; break outer; }
        if (!w.kind.isEmpty()) w.group = Detector.Labels.group(w.kind);
        else if (t.matches(".*(బండి|బండిని|వాహనం|vehicle).*")) w.group = "vehicle";
        else if (t.matches(".*(జంతువు|animal).*")) w.group = "animal";
        if (t.matches(".*(ఎడమ|left).*")) w.side = "ఎడమ";
        else if (t.matches(".*(కుడి|right).*")) w.side = "కుడి";
        else if (t.matches(".*(మధ్య|middle|centre|center).*")) w.side = "మధ్య";
        if (t.matches(".*(దూరంగా|దూరంలో|చిన్నగా|far|small).*")) w.size = -1;
        else if (t.matches(".*(దగ్గరగా ఉన్న|దగ్గర ఉన్న|ముందు ఉన్న|పెద్ద|near|big|closest|nearest).*")) w.size = 1;
        return w;
    }

    /** How well a track fits what he described (0 = no; higher better). */
    static int score(Want w, LiveTracker.Track t) {
        if (t == null || "text".equals(t.group) || "face".equals(t.group)) return 0;
        int s = 1;
        if (!w.kind.isEmpty()) { if (!w.kind.equals(t.en)) return 0; s += 4; }
        else if (!w.group.isEmpty()) { if (!w.group.equals(t.group)) return 0; s += 2; }
        if (!w.colour.isEmpty()) {
            if (t.colour.isEmpty()) s += 0; // (unknown colour: allowed, but a known match wins)
            else if (w.colour.equals(t.colour)) s += 4;
            else return 0;
        }
        if (!w.side.isEmpty()) { if (!w.side.equals(t.side())) return 0; s += 2; }
        if (w.size < 0) s += t.far() ? 2 : -1;
        if (w.size > 0) s += t.far() ? -1 : 2;
        return s;
    }

    /** The best-fitting tracks (best first), only those that fit. */
    static List<LiveTracker.Track> pick(Want w, List<LiveTracker.Track> ts) {
        List<LiveTracker.Track> out = new ArrayList<>();
        if (w == null || ts == null || !w.any()) return out;
        List<int[]> sc = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) { int s = score(w, ts.get(i)); if (s > 0) sc.add(new int[]{s, i}); }
        sc.sort((a, b) -> b[0] - a[0]);
        for (int[] x : sc) out.add(ts.get(x[1]));
        return out;
    }
}
