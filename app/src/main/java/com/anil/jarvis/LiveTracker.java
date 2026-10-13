package com.anil.jarvis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps what the camera's detector finds steady between pictures: the same person keeps the same ring (matched by
 * overlap), boxes move smoothly, a thing seen only once doesn't flash up, and one that left fades after a moment.
 * No Android here (tested on a desk).
 */
final class LiveTracker {
    static final class Track {
        final int id;
        final String en, plural, group;
        String te;            // (a face's name can arrive later)
        final float[] box = new float[4];
        int hits;
        long seen, born;
        float score;
        String colour = "";   // the main colour seen on it (ColourWords names), "" unknown
        float vx, vy;         // how it moves on the screen (fractions of the screen per second)
        float distM;          // about how far (metres), 0 unknown (the camera fills it in for the tags)
        Track(int id, Detector.Det d, long now) {
            this.id = id;
            en = d.en;
            te = d.te;
            plural = d.plural;
            group = Detector.Labels.group(d.en);
            System.arraycopy(d.box, 0, box, 0, 4);
            hits = 1;
            seen = now;
            born = now;
            score = d.score;
            colour = d.colour == null ? "" : d.colour;
        }
        float cx() { return (box[0] + box[2]) / 2; }
        float cy() { return (box[1] + box[3]) / 2; }
        float h() { return box[3] - box[1]; }
        float speed() { return (float) Math.hypot(vx, vy); }
        /** ఎడమ / మధ్య / కుడి by where it is. */
        String side() { float x = cx(); return x < 0.36f ? "ఎడమ" : x > 0.64f ? "కుడి" : "మధ్య"; }
        boolean far() { return h() < 0.14f; }
    }

    /** A thing must be seen this many times before its ring shows. */
    static final int STEADY = 2;
    /** Not seen for this long: gone. */
    static final long GONE_MS = 900;

    private final List<Track> tracks = new ArrayList<>();
    private int next = 1;

    static float iou(float[] a, float[] b) {
        float x0 = Math.max(a[0], b[0]), y0 = Math.max(a[1], b[1]), x1 = Math.min(a[2], b[2]), y1 = Math.min(a[3], b[3]);
        float inter = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
        float u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
        return u <= 0 ? 0 : inter / u;
    }

    /** One picture's findings. */
    synchronized void update(List<Detector.Det> dets, long now) {
        boolean[] used = new boolean[dets.size()];
        // the best overlaps first: each track takes at most one finding of its own kind
        List<float[]> pairs = new ArrayList<>(); // {iou, track index, det index}
        for (int t = 0; t < tracks.size(); t++) {
            for (int d = 0; d < dets.size(); d++) {
                if (!tracks.get(t).en.equals(dets.get(d).en)) continue;
                float o = iou(tracks.get(t).box, dets.get(d).box);
                if (o > 0.25f) pairs.add(new float[]{o, t, d});
            }
        }
        pairs.sort((p, q) -> Float.compare(q[0], p[0]));
        boolean[] took = new boolean[tracks.size()];
        for (float[] p : pairs) {
            int t = (int) p[1], d = (int) p[2];
            if (took[t] || used[d]) continue;
            took[t] = true;
            used[d] = true;
            Track tr = tracks.get(t);
            Detector.Det dd = dets.get(d);
            float[] b = dd.box;
            float ocx = tr.cx(), ocy = tr.cy();
            for (int i = 0; i < 4; i++) tr.box[i] = tr.box[i] * 0.45f + b[i] * 0.55f; // (smooth, but follows)
            long dt = Math.max(16, now - tr.seen);
            float nvx = (tr.cx() - ocx) * 1000f / dt, nvy = (tr.cy() - ocy) * 1000f / dt;
            tr.vx = tr.vx * 0.6f + nvx * 0.4f;
            tr.vy = tr.vy * 0.6f + nvy * 0.4f;
            tr.hits++;
            tr.seen = now;
            tr.score = dd.score;
            if (dd.colour != null && !dd.colour.isEmpty()) tr.colour = dd.colour;
            if (dd.te != null && !dd.te.isEmpty() && (tr.te == null || tr.te.isEmpty() || "face".equals(tr.en))) tr.te = dd.te;
        }
        for (int d = 0; d < dets.size(); d++) if (!used[d]) tracks.add(new Track(next++, dets.get(d), now));
        for (Track t : tracks) if (now - t.seen > goneMs(t) || t.hits < STEADY && now - t.seen > 400) gone.add(t);
        tracks.removeIf(t -> now - t.seen > goneMs(t) || t.hits < STEADY && now - t.seen > 400);
        if (gone.size() > 40) gone.subList(0, gone.size() - 40).clear();
    }

    /** Small far things (found only by the slower close look) and signs may skip a look or two. */
    private static long goneMs(Track t) {
        if ("text".equals(t.group) || "face".equals(t.group)) return 2600;
        return t.h() < 0.12f ? 1600 : GONE_MS;
    }

    private final List<Track> gone = new ArrayList<>();

    /** The steady things that left since the last call (for the event log). */
    synchronized List<Track> drainGone() {
        List<Track> out = new ArrayList<>();
        for (Track t : gone) if (t.hits >= STEADY) out.add(t);
        gone.clear();
        return out;
    }

    /** The rings to draw: steady ones, seen lately. */
    synchronized List<Track> shown(long now) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks) if (t.hits >= STEADY && now - t.seen <= goneMs(t)) out.add(copy(t));
        return out;
    }

    private static Track copy(Track t) {
        Track c = new Track(t.id, new Detector.Det(t.en, t.te, t.plural, t.score, t.box.clone(), t.colour), t.seen);
        c.hits = t.hits;
        c.born = t.born;
        c.vx = t.vx;
        c.vy = t.vy;
        c.distM = t.distM;
        return c;
    }

    /** A steady track by its id (a copy), or null. */
    synchronized Track byId(int id) {
        for (Track t : tracks) if (t.id == id && t.hits >= STEADY) return copy(t);
        return null;
    }

    synchronized void clear() { tracks.clear(); }

    /** How many of each kind are steady now (kind's English name → count), most first. */
    synchronized Map<String, Integer> counts(long now) {
        Map<String, Integer> m = new HashMap<>();
        for (Track t : tracks) if (t.hits >= STEADY && now - t.seen <= goneMs(t) && !"text".equals(t.group) && !"face".equals(t.group)) m.merge(t.en, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** The steady track whose middle is nearest this point (of this group, or any when group is null); null if none. */
    synchronized Track nearest(float x, float y, String group, float within) {
        Track best = null;
        float bd = within * within;
        for (Track t : tracks) {
            if (t.hits < STEADY || group != null && !group.equals(t.group)) continue;
            float dx = t.cx() - x, dy = t.cy() - y, dd = dx * dx + dy * dy;
            if (dd < bd) { bd = dd; best = copy(t); }
        }
        return best;
    }

    /** The steady track that overlaps this box most (null when none overlaps). */
    synchronized Track overlapping(float[] box) {
        Track best = null;
        float bo = 0.15f;
        for (Track t : tracks) {
            if (t.hits < STEADY) continue;
            float o = iou(t.box, box);
            if (o > bo) { bo = o; best = copy(t); }
        }
        return best;
    }

    // ================================================================ in words

    /** "2 మనుషులు, 1 బైక్, 1 కుక్క కనిపిస్తున్నాయి" (up to 4 kinds); "" when nothing. */
    static String sentence(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        int k = 0, total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            total += e.getValue();
            if (k++ >= 4) continue;
            String[] te = Detector.Labels.te(e.getKey());
            if (b.length() > 0) b.append(", ");
            b.append(e.getValue()).append(' ').append(e.getValue() == 1 ? te[0] : te[1]);
        }
        boolean onlyOnePerson = counts.size() == 1 && counts.containsKey("person") && total == 1;
        return b + (onlyOnePerson ? " కనిపిస్తున్నారు" : total == 1 ? " కనిపిస్తోంది" : " కనిపిస్తున్నాయి");
    }

    /**
     * What is where, in his words: "ఎడమ వైపు ఇద్దరు కదులుతున్నారు, మధ్యలో ఒక తెల్ల కారు ఆగి ఉంది, కుడి వైపు దూరంగా ఒక బైక్ వెళ్తోంది".
     * Up to 3 sides × 2 kinds; "" when nothing. Pure words from the phone's own finder (no AI).
     */
    static String sides(List<Track> ts) {
        if (ts == null || ts.isEmpty()) return "";
        String[] order = {"ఎడమ", "మధ్య", "కుడి"};
        List<String> parts = new ArrayList<>();
        for (String side : order) {
            // kind → tracks on this side
            Map<String, List<Track>> kinds = new LinkedHashMap<>();
            for (Track t : ts) {
                if ("text".equals(t.group) || "face".equals(t.group) || !side.equals(t.side())) continue;
                kinds.computeIfAbsent(t.en, k -> new ArrayList<>()).add(t);
            }
            if (kinds.isEmpty()) continue;
            List<Map.Entry<String, List<Track>>> l = new ArrayList<>(kinds.entrySet());
            l.sort((a, b) -> b.getValue().size() - a.getValue().size());
            StringBuilder b = new StringBuilder(side.equals("మధ్య") ? "మధ్యలో " : side + " వైపు ");
            int k = 0;
            for (Map.Entry<String, List<Track>> e : l) {
                if (k++ >= 2) break;
                List<Track> g = e.getValue();
                int n = g.size();
                boolean person = "person".equals(e.getKey());
                boolean moving = false, far = true;
                String colour = "";
                for (Track t : g) { if (t.speed() > 0.06f) moving = true; if (!t.far()) far = false; if (n == 1) colour = t.colour; }
                String[] te = Detector.Labels.te(e.getKey());
                if (k > 1) b.append(", ");
                if (far) b.append("దూరంగా ");
                String cw = colour.isEmpty() || person && n > 1 ? "" : ColourWords.te(colour) + " ";
                if (person) b.append(n == 1 ? "ఒకరు" : n == 2 ? "ఇద్దరు" : n == 3 ? "ముగ్గురు" : n + " మంది");
                else b.append(n == 1 ? "ఒక " + cw + te[0] : n + " " + te[1]);
                String group = Detector.Labels.group(e.getKey());
                if (person) b.append(moving ? " కదులుతున్నారు" : " ఉన్నారు");
                else if ("vehicle".equals(group)) b.append(moving ? (n == 1 ? " వెళ్తోంది" : " వెళ్తున్నాయి") : (n == 1 ? " ఆగి ఉంది" : " ఆగి ఉన్నాయి"));
                else if ("animal".equals(group)) b.append(moving ? (n == 1 ? " కదులుతోంది" : " కదులుతున్నాయి") : (n == 1 ? " ఉంది" : " ఉన్నాయి"));
                else b.append(n == 1 ? " ఉంది" : " ఉన్నాయి");
            }
            parts.add(b.toString());
        }
        if (parts.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) out.append(i == 0 ? "" : ", ").append(parts.get(i));
        return out.toString();
    }

    /**
     * "ఎంత మంది ఉన్నారు?", "ఎన్ని బండ్లు?", "ఎన్ని కుక్కలు?": the kinds he asks to count ({} = not a counting question;
     * {"*"} = everything).
     */
    static List<String> countAsked(String text) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[?？!.,]", " ").replaceAll("\\s+", " ").trim();
        boolean people = t.matches(".*(ఎంత మంది|ఎంతమంది|how many people).*");
        boolean counting = t.matches(".*(లెక్కపెట్టు|లెక్క పెట్టు|లెక్కించు|\\bcount\\b).*");
        // the words right next to "ఎన్ని" / "how many" are what he counts ("ఎన్ని బండ్లు", "కార్లు ఎన్ని"): "ఈ ఫోన్ ఎన్ని రూపాయలు" is a price
        String near = "";
        String[] w = t.split(" ");
        for (int i = 0; i < w.length; i++) {
            if (w[i].equals("ఎన్ని") || w[i].equals("many")) {
                // the word after it ("ఎన్ని బండ్లు", "how many cars"); when that is only "are there" / "ఉన్నాయి", the word before ("కుక్కలు ఎన్ని ఉన్నాయి")
                String next = i + 1 < w.length ? w[i + 1] : "";
                boolean verb = next.isEmpty() || next.matches("ఉన్నాయి|ఉన్నాయ్|ఉన్నారు|ఉంది|ఉన్నవి|కనిపిస్తున్నాయి|కనిపిస్తున్నారు|ఉంటాయి|are|is|there");
                if (!verb) near += " " + next;
                else if (i > 0) near += " " + w[i - 1];
            }
        }
        if (!people && !counting && near.isEmpty()) return out;
        // about what is here now ("ఉన్నారు", "కనిపిస్తున్నాయి", "are there"), or a short ask ("ఎంత మంది?"): "ఈ బస్సులో ఎంత మంది కూర్చోవచ్చు?" is not
        boolean now = t.matches(".*(ఉన్నారు|ఉన్నాయి|ఉన్నాయ్|ఉంది|ఉన్నవి|కనిపిస్తున్నాయి|కనిపిస్తున్నారు|కనిపిస్తోంది|ఇక్కడ|అక్కడ|here|there|can you see|do you see|visible).*");
        if (!now && w.length > 3) return out;
        for (int i = 0; i < w.length; i++) { // "ఇండియాలో ఎంత మంది ఉన్నారు" is not about the camera ("ఈ బస్సులో …" is)
            if (w[i].length() > 3 && w[i].endsWith("లో") && !w[i].matches("ఇందులో|అందులో|ఫోటోలో|బొమ్మలో|కెమెరాలో|స్క్రీన్‌లో|ఇక్కడ")
                    && (i == 0 || !w[i - 1].matches("ఈ|ఆ"))) return out;
        }
        String look = counting ? t : near;
        if (people || look.matches(".*(మనుషులు|జనం|people|persons).*")) out.add("person");
        if (look.matches(".*(బండ్లు|వాహనాలు|vehicles).*")) { out.add("car"); out.add("motorcycle"); out.add("bus"); out.add("truck"); out.add("bicycle"); }
        for (String[] te : TE_FIND) {
            if (out.contains(te[0])) continue;
            if (look.contains(te[1]) || look.contains(te[2]) || look.matches(".*\\b" + te[0] + "s?\\b.*")) out.add(te[0]);
        }
        if (out.isEmpty() && counting) { // "అన్నీ లెక్కపెట్టు" (only that: "ఈ నోట్లు అన్నీ లెక్కపెట్టు" is for the AI)
            boolean onlyThat = true;
            for (String x : w) if (!x.matches("అన్నీ|అన్ని|అంతా|లెక్కపెట్టు|లెక్క|పెట్టు|లెక్కించు|count|everything|all|ఇక్కడ|here")) onlyThat = false;
            if (onlyThat) out.add("*");
        }
        return out;
    }

    /** {English, Telugu singular stem, Telugu plural} for the kinds he may count by name. */
    private static final String[][] TE_FIND = {
            {"car", "కారు", "కార్లు"}, {"motorcycle", "బైక్", "బైక్‌లు"}, {"bicycle", "సైకిల్", "సైకిళ్లు"}, {"bus", "బస్సు", "బస్సులు"},
            {"truck", "లారీ", "లారీలు"}, {"dog", "కుక్క", "కుక్కలు"}, {"cat", "పిల్లి", "పిల్లులు"}, {"cow", "ఆవు", "ఆవులు"},
            {"bird", "పక్షి", "పక్షులు"}, {"chair", "కుర్చీ", "కుర్చీలు"}, {"bottle", "సీసా", "సీసాలు"}, {"cup", "కప్పు", "కప్పులు"},
            {"book", "పుస్తకం", "పుస్తకాలు"}, {"cell phone", "ఫోన్", "ఫోన్లు"}, {"laptop", "ల్యాప్‌టాప్", "ల్యాప్‌టాప్‌లు"}, {"tv", "టీవీ", "టీవీలు"}};

    /** The answer to a counting question from what is steady now. */
    static String countAnswer(List<String> kinds, Map<String, Integer> counts) {
        if (kinds.contains("*")) {
            String s = sentence(counts);
            return s.isEmpty() ? "ఇప్పుడు నాకు ఏమీ స్పష్టంగా కనిపించడం లేదు." : s + ".";
        }
        int n = 0;
        for (String k : kinds) n += counts.getOrDefault(k, 0);
        boolean people = kinds.size() == 1 && kinds.get(0).equals("person");
        boolean vehicles = kinds.size() > 1 && kinds.contains("car") && kinds.contains("motorcycle");
        if (people) return n == 0 ? "ఇప్పుడు ఎవరూ కనిపించడం లేదు." : n == 1 ? "ఒక్కరు కనిపిస్తున్నారు." : n + " మంది కనిపిస్తున్నారు.";
        if (vehicles) return n == 0 ? "ఇప్పుడు బండ్లు ఏవీ కనిపించడం లేదు." : n == 1 ? "ఒక్క బండి కనిపిస్తోంది." : n + " బండ్లు కనిపిస్తున్నాయి.";
        if (kinds.size() > 1 && !vehicles) { // "కుక్కలు, పిల్లులు ఎన్ని?": each one
            Map<String, Integer> some = new LinkedHashMap<>();
            for (String k : kinds) { int c = counts.getOrDefault(k, 0); if (c > 0) some.put(k, c); }
            String s = sentence(some);
            return s.isEmpty() ? "ఇప్పుడు అవి ఏవీ కనిపించడం లేదు." : s + ".";
        }
        String[] te = Detector.Labels.te(kinds.get(0));
        return n == 0 ? "ఇప్పుడు " + te[1] + " ఏవీ కనిపించడం లేదు." : n == 1 ? "ఒక్క " + te[0] + " కనిపిస్తోంది." : n + " " + te[1] + " కనిపిస్తున్నాయి.";
    }
}
