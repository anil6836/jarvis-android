package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 🎬 The new film Jarvis's clips on the car box (31 Oct): Anil's own AI dance clips (made in Kling / Flow), brought to the
 * box as ONE zip file he picks ("📦 Jarvis క్లిప్స్ ప్యాక్ కలుపు"). His rule: the clips never go into the APK, GitHub or
 * a backup — they live only here, in files/car_show (out of every backup). The zip holds a manifest.json that says what
 * each clip is: a dance (calm / mid / high), a part of a song set with the heroines (in, duet, trio in, trio dance, trio
 * out, out — played in that order, each one starting where the one before ended), and later the half-body Jarvis
 * (idle, listen, think, talk, hello ...). No Android here: desk-tested.
 */
final class FilmPack {
    static final String DIR = "car_show", PART = "car_show.part", MANIFEST = "manifest.json";
    /** A new pack came in: the screen loads it again (it compares this number). */
    private static volatile int gen;
    static int gen() { return gen; }
    static void bump() { gen++; }

    static final String[] KINDS = {"idle", "hello", "listen", "think", "talk", "happy", "worried", "dance", "set"};
    static final long MAX_FILE = 300L * 1024 * 1024, MAX_ALL = 900L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,80}");
    private static final Pattern EXT = Pattern.compile("(?i).*\\.(mp4|jpg|jpeg|png|json)");

    /** One clip: what it is, where it starts and ends (a "frame": stage, pair, trio, half), and where the dancers are. */
    static final class Clip {
        final String id, file, kind, level, set, from, to;
        final int order, times;
        final boolean loop;
        /** The part of the picture to keep in view (fractions: x0, y0, x1, y1). */
        final float[] box;
        /**
         * Where the view starts / ends when it differs (null: the box all along): the set's first clip begins on him alone
         * (as close as his own dances) and opens out to both of them in its first {@link #ZOOM_MS}; the last one closes in
         * on him again in its last — like a camera, so nothing jumps between a dance and the set.
         */
        final float[] boxStart, boxEnd;

        Clip(String id, String file, String kind, String level, String set, String from, String to, int order, int times, boolean loop, float[] box, float[] boxStart, float[] boxEnd) {
            this.id = id; this.file = file; this.kind = kind; this.level = level; this.set = set; this.from = from; this.to = to;
            this.order = order; this.times = times; this.loop = loop; this.box = box; this.boxStart = boxStart; this.boxEnd = boxEnd;
        }

        /** The view's box at this moment of the clip (ms into it, its length). */
        float[] boxAt(int pos, int dur) {
            if (boxStart != null && pos < ZOOM_MS) return lerp(boxStart, box, smooth(pos / (float) ZOOM_MS));
            if (boxEnd != null && dur > 0 && pos > dur - ZOOM_MS) return lerp(box, boxEnd, smooth((pos - (dur - ZOOM_MS)) / (float) ZOOM_MS));
            return box;
        }

        /** True while the view moves (it is drawn again each frame then). */
        boolean zooming(int pos, int dur) { return boxStart != null && pos < ZOOM_MS || boxEnd != null && dur > 0 && pos > dur - ZOOM_MS; }

        int levelNo() { return "calm".equals(level) ? 0 : "high".equals(level) ? 2 : "mid".equals(level) ? 1 : -1; }
        boolean solo() { return "dance".equals(kind) && "stage".equals(from) && "stage".equals(to); }
        @Override public String toString() { return id; }
    }

    final String name;
    final List<Clip> clips;
    /** The still picture of a frame ("stage" → "stage.jpg"), and its view box. */
    final JSONObject stills;
    final float[] stillBox;
    final File dir;

    private FilmPack(String name, List<Clip> clips, JSONObject stills, float[] stillBox, File dir) {
        this.name = name; this.clips = clips; this.stills = stills; this.stillBox = stillBox; this.dir = dir;
    }

    static final float[] SOLO_BOX = {0.28f, 0.14f, 0.72f, 0.97f}, GROUP_BOX = {0.10f, 0.08f, 0.90f, 0.97f};
    /** How long the view opens out / closes in at a set's start / end. */
    static final int ZOOM_MS = 2200;

    static float smooth(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    static float[] lerp(float[] a, float[] b, float k) {
        float[] o = new float[4];
        for (int i = 0; i < 4; i++) o[i] = a[i] + (b[i] - a[i]) * k;
        return o;
    }

    /** The pack's clips of one kind (in the manifest's order). */
    List<Clip> of(String kind) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) if (c.kind.equals(kind)) out.add(c);
        return out;
    }

    boolean hasDance() { for (Clip c : clips) if (c.solo()) return true; return false; }

    /** The names of the song sets (the heroines' set, a friend's set later), in the manifest's order. */
    List<String> sets() {
        List<String> out = new ArrayList<>();
        for (Clip c : clips) if ("set".equals(c.kind) && !c.set.isEmpty() && !out.contains(c.set)) out.add(c.set);
        return out;
    }

    /** A clip's file (null: none). */
    File file(Clip c) { return c == null || dir == null ? null : new File(dir, c.file); }

    /** The still of a frame (null: none). */
    File still(String frame) {
        String f = stills == null ? "" : stills.optString(frame, "");
        if (f.isEmpty() || dir == null) return null;
        File out = new File(dir, f);
        return out.exists() ? out : null;
    }

    /** "N క్లిప్స్: 4 డాన్స్, 1 పాట సెట్ (6 భాగాలు)" — what the pack holds, for his eyes. */
    String summary() {
        int dance = 0, set = 0, other = 0;
        for (Clip c : clips) { if (c.solo()) dance++; else if ("set".equals(c.kind)) set++; else other++; }
        StringBuilder b = new StringBuilder();
        b.append(clips.size()).append(" క్లిప్స్: ").append(dance).append(" డాన్స్");
        if (set > 0) b.append(", ").append(sets().size()).append(" పాట సెట్ (").append(set).append(" భాగాలు)");
        if (other > 0) b.append(", ").append(other).append(" ఇతర");
        return b.toString();
    }

    // ================================================================ reading the manifest

    /** The pack kept on the box (null: none or broken). */
    static FilmPack load(File filesDir) {
        File d = new File(filesDir, DIR);
        File m = new File(d, MANIFEST);
        if (!m.isFile()) return null;
        try { return parse(readAll(m), d); } catch (Exception e) { return null; }
    }

    /** A manifest's words → the pack (only clips whose file is there when {@code dir} is given). Throws on a broken one. */
    static FilmPack parse(String json, File dir) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray a = o.getJSONArray("clips");
        List<Clip> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            String id = c.optString("id", "").trim(), file = c.optString("file", "").trim(), kind = c.optString("kind", "").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || ids.contains(id) || !safeName(file) || !EXT.matcher(file).matches() || !java.util.Arrays.asList(KINDS).contains(kind)) continue;
            if (dir != null && !new File(dir, file).isFile()) continue;
            String level = c.optString("level", "").toLowerCase(Locale.ROOT), set = c.optString("set", "");
            String from = c.optString("from", "stage"), to = c.optString("to", from);
            float[] box = box(c.optJSONArray("box"), "set".equals(kind) ? GROUP_BOX : SOLO_BOX);
            float[] bs = c.has("box_start") ? box(c.optJSONArray("box_start"), box) : null, be = c.has("box_end") ? box(c.optJSONArray("box_end"), box) : null;
            int times = Math.max(1, Math.min(4, c.optInt("times", 1)));
            ids.add(id);
            out.add(new Clip(id, file, kind, level, set, from, to, c.optInt("order", i), times, c.optBoolean("loop", false), box, bs, be));
        }
        if (out.isEmpty()) throw new Exception("no clips");
        return new FilmPack(o.optString("name", ""), Collections.unmodifiableList(out), o.optJSONObject("stills"), box(o.optJSONArray("still_box"), SOLO_BOX), dir);
    }

    private static float[] box(JSONArray a, float[] def) {
        if (a == null || a.length() != 4) return def.clone();
        float[] b = new float[4];
        for (int i = 0; i < 4; i++) b[i] = (float) Math.max(0, Math.min(1, a.optDouble(i, def[i])));
        if (b[2] - b[0] < 0.05f || b[3] - b[1] < 0.05f) return def.clone();
        return b;
    }

    static boolean safeName(String n) { return n != null && NAME.matcher(n).matches() && !n.startsWith(".") && !n.contains(".."); }

    // ================================================================ the zip he picked

    /**
     * His zip → files/car_show: every file into car_show.part first (only plain names: mp4 / jpg / png / json, one folder
     * level at most, each ≤ 300 MB, all ≤ 900 MB), then the manifest is read and every clip it names must be there; only
     * then the old pack goes and the new one takes its place. Returns the new pack, or null with the reason in {@code why[0]}.
     */
    static FilmPack importZip(File filesDir, InputStream in, String[] why) {
        File part = new File(filesDir, PART), dir = new File(filesDir, DIR);
        deleteTree(part);
        if (!part.mkdirs()) { say(why, "బాక్స్‌లో చోటు లేదు"); return null; }
        long all = 0;
        int files = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                int slash = n.lastIndexOf('/');
                String folder = slash < 0 ? "" : n.substring(0, slash);
                if (folder.contains("/") || folder.startsWith(".")) continue; // (deeper than one folder, or "..": not ours)
                String base = n.substring(slash + 1);
                if (!safeName(base) || !EXT.matcher(base).matches() || base.startsWith("._")) continue; // (no odd names, no Mac shadows)
                File out = new File(part, base);
                long size = 0;
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int r;
                    while ((r = z.read(buf)) > 0) {
                        size += r;
                        all += r;
                        if (size > MAX_FILE || all > MAX_ALL) { deleteTree(part); say(why, "ప్యాక్ చాలా పెద్దది"); return null; }
                        os.write(buf, 0, r);
                    }
                }
                files++;
            }
        } catch (Exception ex) {
            deleteTree(part);
            say(why, "ఆ ఫైల్ zip కాదు లేదా పాడైంది");
            return null;
        }
        if (files == 0) { deleteTree(part); say(why, "ఆ zip లో క్లిప్స్ లేవు"); return null; }
        FilmPack p;
        try { p = parse(readAll(new File(part, MANIFEST)), part); } catch (Exception ex) { deleteTree(part); say(why, "ప్యాక్‌లో క్లిప్స్ జాబితా (manifest) లేదు"); return null; }
        if (!p.hasDance() && p.of("idle").isEmpty()) { deleteTree(part); say(why, "ప్యాక్‌లో డాన్స్ క్లిప్స్ లేవు"); return null; }
        File old = new File(filesDir, DIR + ".old");
        deleteTree(old);
        if (dir.exists() && !dir.renameTo(old)) deleteTree(dir);
        if (!part.renameTo(dir)) { deleteTree(part); say(why, "క్లిప్స్ దాచలేకపోయాను"); return null; }
        deleteTree(old);
        bump();
        return load(filesDir);
    }

    /** Takes the pack away (🗑️). */
    static void remove(File filesDir) {
        deleteTree(new File(filesDir, DIR));
        deleteTree(new File(filesDir, PART));
        bump();
    }

    private static void say(String[] why, String s) { if (why != null && why.length > 0) why[0] = s; }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String readAll(File f) throws Exception {
        if (f.length() > 1024 * 1024) throw new Exception("too big");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        }
    }
}
