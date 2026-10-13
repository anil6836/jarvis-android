package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 🔢 Counting things in a picture: "ఈ గేట్‌కి ఎన్ని రాడ్లు ఉన్నాయి?" (one kind, named), "గేట్ లెక్కపెట్టు" (every kind of part of
 * one thing). The AI marks each one with a point; the phone draws numbered markers he can correct by touch. For bars (rods,
 * pipes, fence posts) the phone makes its own second count from the picture's brightness profile, said beside the AI's.
 * The words, the reply reader, the tile merge and the bar counter are pure (tested on a desk).
 */
final class Counter {
    private Counter() {}

    /** What he asked: `what` = the thing(s) to count in his words; `all` = every kind of part of `what`. */
    static final class Ask {
        final String what;
        final boolean all;
        Ask(String what, boolean all) { this.what = what; this.all = all; }
    }

    private static final String COUNT_WORDS = "(లెక్కపెట్టు|లెక్క పెట్టు|లెక్కించు|లెక్కపెట్టి చెప్పు|లెక్క చెప్పు|\\bcount\\b)";
    private static final String PLURAL = ".*(లు|ళ్లు|ళ్ళు|ల్లు|వి|s)$";

    /** "ఈ గేట్‌కి ఎన్ని రాడ్లు ఉన్నాయి" → rods; "గేట్ లెక్కపెట్టు" → all parts of the gate; "అన్నీ లెక్కపెట్టు" → all; null when not a count. */
    static Ask parse(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[?？!.,]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        if (t.matches(".*(ఎలా|how to|how do|ఎక్కడ|where|ఎందుకు|why).*")) return null;   // a question about counting, not a count
        boolean counting = t.matches(".*" + COUNT_WORDS + ".*");
        String[] w = t.split(" ");
        // "ఎన్ని X" / "X ఎన్ని" / "how many X"
        String near = "";
        for (int i = 0; i < w.length; i++) {
            if (w[i].equals("ఎన్ని") || w[i].equals("many")) {
                String next = i + 1 < w.length ? w[i + 1] : "";
                boolean verb = next.isEmpty() || next.matches("ఉన్నాయి|ఉన్నాయ్|ఉన్నారు|ఉంది|ఉన్నవి|కనిపిస్తున్నాయి|కనిపిస్తున్నారు|ఉంటాయి|వచ్చాయి|are|is|there|do|does");
                if (!verb) near = next;
                else if (i > 0 && !w[i - 1].matches("ఇక్కడ|అక్కడ|మొత్తం|here|there|total")) near = w[i - 1];
            }
        }
        if (!near.isEmpty()) {
            if (near.matches("రూపాయలు|రూపాయిలు|డబ్బు|ఖర్చు|రేటు|రేట్|rupees|rs|price|cost|years|సంవత్సరాలు|గంటలు|నిమిషాలు|రోజులు|కిలోమీటర్లు|కి\\.మీ|hours|minutes|days|km|మీటర్లు|meters")) return null; // a price / a time, not a count
            if (near.matches("మంది|people|persons")) return null; // the phone's own people count handles it
            return new Ask(near, false);
        }
        if (!counting) return null;
        // "X లెక్కపెట్టు": X = the words before the count word
        String before = t.replaceAll(COUNT_WORDS + ".*$", "").replaceAll("(ఇక్కడ|అక్కడ|ఈ|ఆ|దయచేసి|జార్విస్|jarvis|ఒకసారి|please|the|these|those|this|that)", " ").replaceAll("\\s+", " ").trim();
        if (before.isEmpty()) { // "count the bottles": the thing comes after the word in English
            String after = t.replaceAll("^.*?" + COUNT_WORDS, " ").replaceAll("\\b(the|these|those|all|everything|here|please)\\b|అన్నీ|అన్ని|అంతా|మొత్తం|ఇక్కడ|దయచేసి", " ").replaceAll("\\s+", " ").trim();
            if (!after.isEmpty()) before = after;
        }
        if (before.isEmpty() || before.matches("(అన్నీ|అన్ని|అంతా|మొత్తం|everything|all)( .*)?")) return new Ask("", true);
        if (before.matches(".*(లో|లోని|కి|కు) (అన్నీ|అన్ని|అంతా|మొత్తం)$")) return new Ask(before.replaceAll(" (అన్నీ|అన్ని|అంతా|మొత్తం)$", "").replaceAll("(లో|లోని|కి|కు)$", ""), true); // "గేట్‌లో అన్నీ"
        String last = before.substring(before.lastIndexOf(' ') + 1);
        boolean plural = last.matches(PLURAL) || last.matches(".*(రాడ్|పైప|ఇటుక|టైల్|బోల్ట|నట్|స్క్రూ|సీసా|బాటిల్|కుర్చీ|కిటికీ|తలుపు|మెట్|పలక|రేకు|గొర్ర|మేక|కోడి|చెట్|కాయ|పండు|పండ్).*");
        return new Ask(before, !plural);
    }

    // ---- the AI

    static String system() {
        return "You are JARVIS, counting things in ONE photo for Anil (Telangana). Answer only in simple Telugu words. Reply with ONLY this JSON: "
                + "{\"unit\":\"Telugu plural name of what is counted (రాడ్లు, పైపులు, ఇటుకలు…)\",\"items\":[{\"x\":0-1000,\"y\":0-1000,\"kind\":\"short Telugu kind name\"}],"
                + "\"kinds\":[{\"name\":\"kind\",\"count\":0}],\"note\":\"one Telugu line: what could not be counted (hidden, cut off, too far) or empty\",\"dense\":false,\"confidence\":0-100}. "
                + "items = ONE point (the centre, as integers 0-1000 of the photo's width and height) for EVERY single visible thing counted - no more, no less: this is the count. "
                + "Go systematically left to right, top to bottom; count things cut by the photo's edge if more than half is visible. kinds = the same items summed by kind. "
                + "dense=true when there are more than about 40 or they overlap heavily. Never identify people (a person is 'మనిషి'); no ID numbers, no number plates.";
    }

    static String prompt(Ask a, String part) {
        String where = part == null ? "this photo" : "this " + part + " of the photo (count only what is inside it)";
        if (a.all) return "Count EVERY kind of part of " + (a.what.isEmpty() ? "the main thing" : "the " + a.what) + " in " + where + ": e.g. for a gate the vertical rods, horizontal pipes, hinges, bolts, the latch - each kind separately in items (kind = its Telugu name) and summed in kinds.";
        return "Count the " + a.what + " in " + where + ". items = one point per " + a.what + "; kind = '" + a.what + "'.";
    }

    /** One counted thing, in photo fractions 0–1. */
    static final class Mark {
        float x, y;
        String kind;
        Mark(float x, float y, String kind) { this.x = x; this.y = y; this.kind = kind == null ? "" : kind; }
    }

    /** The AI's reply read: {unit, marks, kinds, note, dense, confidence}; null when unusable. Marks come back in 0–1 of the photo (or of its tile). */
    static JSONObject reply(String reply) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            JSONArray items = o.optJSONArray("items"), clean = new JSONArray();
            for (int i = 0; items != null && i < items.length() && i < 400; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || !it.has("x") || !it.has("y")) continue;
                float x = (float) it.optDouble("x") / 1000f, y = (float) it.optDouble("y") / 1000f;
                if (x < -0.02f || x > 1.02f || y < -0.02f || y > 1.02f) continue;
                clean.put(new JSONObject().put("x", Math.max(0, Math.min(1, x))).put("y", Math.max(0, Math.min(1, y))).put("kind", FloatBubble.noIds(it.optString("kind"))));
            }
            o.put("items", clean);
            if (o.optString("unit").isEmpty()) o.put("unit", clean.length() > 0 ? clean.optJSONObject(0).optString("kind") : "");
            o.put("note", FloatBubble.noIds(o.optString("note")));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static List<Mark> marks(JSONArray items) {
        List<Mark> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) out.add(new Mark((float) it.optDouble("x"), (float) it.optDouble("y"), it.optString("kind")));
        }
        return out;
    }

    static JSONArray json(List<Mark> marks) {
        JSONArray a = new JSONArray();
        try { for (Mark m : marks) a.put(new JSONObject().put("x", m.x).put("y", m.y).put("kind", m.kind)); } catch (Exception ignored) {}
        return a;
    }

    /** Marks of a tile (fractions of the tile) placed into the whole photo: tile = {x0, y0, x1, y1} fractions. */
    static List<Mark> place(List<Mark> tileMarks, float[] tile) {
        List<Mark> out = new ArrayList<>();
        for (Mark m : tileMarks) out.add(new Mark(tile[0] + m.x * (tile[2] - tile[0]), tile[1] + m.y * (tile[3] - tile[1]), m.kind));
        return out;
    }

    /** Two tiles' marks joined: a mark within `tol` of one already kept (the overlap band seen twice) is the same thing. */
    static List<Mark> merge(List<Mark> a, List<Mark> b, float tol) {
        List<Mark> out = new ArrayList<>(a);
        for (Mark m : b) {
            boolean dup = false;
            for (Mark k : a) if (Math.hypot(m.x - k.x, m.y - k.y) < tol && (k.kind.isEmpty() || m.kind.isEmpty() || k.kind.equals(m.kind))) { dup = true; break; }
            if (!dup) out.add(m);
        }
        return out;
    }

    /** The marks counted by kind, in first-seen order. */
    static Map<String, Integer> byKind(List<Mark> marks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Mark m : marks) out.merge(m.kind.isEmpty() ? "వస్తువు" : m.kind, 1, Integer::sum);
        return out;
    }

    /** "24 రాడ్లు" / "నిలువు రాడ్లు 24 · అడ్డ పైపులు 3 · కీళ్లు 2 (మొత్తం 29)". */
    static String sentence(List<Mark> marks, String unit, boolean all) {
        Map<String, Integer> k = byKind(marks);
        if (marks.isEmpty()) return "ఏవీ లెక్కకి రాలేదు.";
        if (!all || k.size() <= 1) return marks.size() + " " + (unit == null || unit.isEmpty() ? (k.isEmpty() ? "" : k.keySet().iterator().next()) : unit);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : k.entrySet()) b.append(b.length() == 0 ? "" : " · ").append(e.getKey()).append(" ").append(e.getValue());
        return b + " (మొత్తం " + marks.size() + ")";
    }

    /** Rods / pipes / posts: the phone's own line count makes sense for these words. */
    static boolean barLike(String what) {
        String t = what == null ? "" : what.toLowerCase(Locale.ROOT);
        return t.matches(".*(రాడ్|రాడ్లు|పైప|కడ్డీ|కమ్మ|స్తంభ|గ్రిల్|కంచె|ఊచ|rod|pipe|bar|post|grill|rail|fence|picket|slat).*");
    }

    // ---- the phone's own bar count: the brightness profile across the picture, its regular dips (or bumps)

    /**
     * (pure) Bars across a grey picture: columns' mean brightness → smoothed → the regular dips (dark bars on light) or bumps
     * (light bars on dark), whichever set is more evenly spaced. gray: w*h bytes (0-255) row-major. Returns {vertical bars,
     * horizontal bars}; 0 when nothing regular is found.
     */
    static int[] bars(int[] gray, int w, int h) {
        return new int[]{countProfile(profile(gray, w, h, true)), countProfile(profile(gray, w, h, false))};
    }

    /** Mean brightness per column (vertical = true) or per row, over the middle 70 % (the frame's edges often hold other things). */
    static float[] profile(int[] gray, int w, int h, boolean columns) {
        int n = columns ? w : h, m = columns ? h : w;
        int from = (int) (m * 0.15f), to = (int) (m * 0.85f);
        float[] p = new float[n];
        for (int i = 0; i < n; i++) {
            long s = 0;
            for (int j = from; j < to; j++) s += columns ? gray[j * w + i] : gray[i * w + j];
            p[i] = s / (float) Math.max(1, to - from);
        }
        return p;
    }

    /** The count of regular dips or bumps in a profile; 0 when there is no regular pattern (fewer than 3, or spacing too uneven). */
    static int countProfile(float[] p) {
        int n = p.length;
        if (n < 20) return 0;
        // smooth over about 1 % of the width, then take out the slow trend (shading across the picture)
        int r = Math.max(1, n / 100);
        float[] s = box(p, r), trend = box(p, Math.max(3, n / 12));
        float[] d = new float[n];
        float var = 0;
        for (int i = 0; i < n; i++) { d[i] = s[i] - trend[i]; var += d[i] * d[i]; }
        float sd = (float) Math.sqrt(var / n);
        if (sd < 2.5f) return 0; // flat: nothing to count
        List<Integer> dips = peaks(d, -1, sd, r), bumps = peaks(d, 1, sd, r);
        float cvD = spacingCv(dips), cvB = spacingCv(bumps);
        boolean dipOk = dips.size() >= 3 && cvD < 0.45f, bumpOk = bumps.size() >= 3 && cvB < 0.45f;
        if (dipOk && bumpOk) return cvD <= cvB ? dips.size() : bumps.size();
        if (dipOk) return dips.size();
        if (bumpOk) return bumps.size();
        return 0;
    }

    private static float[] box(float[] p, int r) {
        int n = p.length;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - r), b = Math.min(n - 1, i + r);
            float s = 0;
            for (int j = a; j <= b; j++) s += p[j];
            out[i] = s / (b - a + 1);
        }
        return out;
    }

    /** Local extremes of sign `sign` deeper than 0.6 sd, at least 2r+2 apart (the stronger one wins). */
    private static List<Integer> peaks(float[] d, int sign, float sd, int r) {
        List<Integer> out = new ArrayList<>();
        int n = d.length, minGap = 2 * r + 2;
        float thr = 0.6f * sd;
        for (int i = 1; i < n - 1; i++) {
            float v = d[i] * sign;
            if (v < thr) continue;
            if (v < d[i - 1] * sign || v < d[i + 1] * sign) continue;
            if (!out.isEmpty() && i - out.get(out.size() - 1) < minGap) { if (v > d[out.get(out.size() - 1)] * sign) out.set(out.size() - 1, i); continue; }
            out.add(i);
        }
        return out;
    }

    /** How uneven the gaps between peaks are (std / mean); 9 when there are too few. */
    private static float spacingCv(List<Integer> pk) {
        if (pk.size() < 3) return 9;
        float mean = 0;
        for (int i = 1; i < pk.size(); i++) mean += pk.get(i) - pk.get(i - 1);
        mean /= pk.size() - 1;
        float var = 0;
        for (int i = 1; i < pk.size(); i++) { float g = pk.get(i) - pk.get(i - 1) - mean; var += g * g; }
        return (float) Math.sqrt(var / (pk.size() - 1)) / Math.max(1, mean);
    }
}
