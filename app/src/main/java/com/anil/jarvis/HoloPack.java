package com.anil.jarvis;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 🎬 The car Jarvis on the phone's 🛸 holo projector (10 Nov, stage 4): Anil's five waist-up clips, made by him in Kling
 * from his Jarvis picture on a plain black background (👋 hello, 😌 idle, 👂 listen, 🗣️ talk, 🤔 think), brought in as ONE
 * zip ("Jarvis-holo-pack.zip", 📦 in 🎨). His rule: the clips never go into the APK, GitHub or a backup — they live only
 * here, in files/holo_show (left out of every backup). pack.json says what each clip is:
 * {"pack":"jarvis-holo","version":1,"body":{"centreX":0.5,"topOfHair":0.18},"clips":{"idle":{"file":"idle.mp4","ms":5042,"loop":true},
 * "talk":{"file":"talk.mp4","ms":10042,"loop":true,"loopEndMs":7250,"fadeMs":300}, ...}}. No Android here: desk-tested.
 */
final class HoloPack {
    static final String DIR = "holo_show", PART = "holo_show.part", OLD = "holo_show.old", MANIFEST = "pack.json", NAME = "jarvis-holo";
    static final int VERSION = 1;
    static final String[] KINDS = {"hello", "idle", "listen", "think", "talk"};
    static final String[] FACES = {"👋", "😌", "👂", "🤔", "🗣️"};
    static final long MAX_FILE = 60L * 1024 * 1024, MAX_ALL = 200L * 1024 * 1024;
    /** Entries read from a zip at most (his has 7): a zip of thousands of tiny files is not his pack. */
    static final int MAX_ENTRIES = 64;
    /** The welcome again only when the main screen was away at least this long. */
    static final long HELLO_AFTER_MS = 5 * 60_000L;

    /** A new pack came in or went: the screen loads it again (it compares this number). */
    private static volatile int gen;
    static int gen() { return gen; }
    static void bump() { gen++; }

    /** A pack is going in or out now (📦 / 🗑️ wait for it). */
    static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();

    /** One clip: what it is, its file, its length, whether it plays round, and (talk) where it goes back to its start. */
    static final class Clip {
        final String kind, file;
        final int ms, loopEndMs, fadeMs;
        final boolean loop;

        Clip(String kind, String file, int ms, boolean loop, int loopEndMs, int fadeMs) {
            this.kind = kind; this.file = file; this.ms = ms; this.loop = loop; this.loopEndMs = loopEndMs; this.fadeMs = fadeMs;
        }

        /** Where (ms into it) the next copy of this clip starts crossing over it; -1: it plays round by itself / to its end. */
        int switchAt() { return loopEndMs > 0 ? Math.max(0, loopEndMs - fadeMs) : -1; }

        @Override public String toString() { return kind; }
    }

    final Map<String, Clip> clips;
    /** Where his body's middle is across the picture, and the top of his hair down it (fractions). */
    final float centreX, top;
    final File dir;

    private HoloPack(Map<String, Clip> clips, float centreX, float top, File dir) {
        this.clips = clips; this.centreX = centreX; this.top = top; this.dir = dir;
    }

    Clip get(String kind) { return kind == null ? null : clips.get(kind); }

    File file(Clip c) { return c == null || dir == null ? null : new File(dir, c.file); }

    /** "5 క్లిప్స్: 👋 😌 👂 🤔 🗣️" — what the pack holds, for his eyes. */
    String summary() {
        StringBuilder b = new StringBuilder();
        b.append(clips.size()).append(" క్లిప్స్:");
        for (int i = 0; i < KINDS.length; i++) if (clips.containsKey(KINDS[i])) b.append(' ').append(FACES[i]);
        return b.toString();
    }

    /** The clip for what Jarvis is doing (the orb's state): listening, thinking, talking, else resting. */
    static String kindFor(int state) {
        switch (state) {
            case OrbView.LISTENING: return "listen";
            case OrbView.THINKING: return "think";
            case OrbView.SPEAKING: return "talk";
            default: return "idle";
        }
    }

    /** The clip to play for a state: its own, else the resting one (a pack may leave some out). */
    Clip forState(int state) {
        Clip c = clips.get(kindFor(state));
        return c != null ? c : clips.get("idle");
    }

    /** 👋 Due when the screen comes back: the first time, or after it was away a while (not after a quick look elsewhere). */
    static boolean helloDue(long now, long awaySince) { return awaySince <= 0 || now - awaySince >= HELLO_AFTER_MS; }

    /** How much of the picture across must stay in view: room for the waving hand on both sides of his body. */
    static final float WIDE = 0.72f;

    /**
     * The scale, left and top of a clip's picture (pw × ph) in the projector's window (w × h): his body's middle
     * ({@code centreX}) on the window's middle, the top of his hair a little below the window's top, as big as fits with
     * his hands' room ({@link #WIDE}) across and down to the picture's bottom (his waist, which fades into the light).
     * Null for an empty size. Pure.
     */
    static float[] place(int w, int h, int pw, int ph, float centreX, float top) {
        if (w <= 0 || h <= 0 || pw <= 0 || ph <= 0) return null;
        float from = Math.max(0, top - 0.02f);
        float s = Math.min(w / (WIDE * pw), h / ((1f - from) * ph));
        float dw = pw * s, dh = ph * s;
        return new float[]{s, w / 2f - centreX * dw, -from * dh};
    }

    // ================================================================ reading pack.json

    /** The pack kept on the phone (null: none or broken). A swap cut off half way (the phone switched off): the old pack back. */
    static HoloPack load(File filesDir) {
        File d = new File(filesDir, DIR), old = new File(filesDir, OLD);
        if (!d.exists() && old.isDirectory() && !BUSY.get()) //noinspection ResultOfMethodCallIgnored
            old.renameTo(d);
        File m = new File(d, MANIFEST);
        if (!m.isFile()) return null;
        try { return parse(readAll(m), d); } catch (Exception e) { return null; }
    }

    /**
     * pack.json's words → the pack (when {@code dir} is given, only clips whose file is there). Throws, with his words for
     * why, on one that isn't a Jarvis holo pack, is newer than this app reads, or has no resting (idle) clip.
     */
    static HoloPack parse(String json, File dir) throws Exception {
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { throw new Exception("ప్యాక్ జాబితా (pack.json) చదవలేకపోయాను"); }
        if (!NAME.equals(o.optString("pack", ""))) throw new Exception("ఇది Jarvis హోలో ప్యాక్ కాదు");
        if (o.optInt("version", 1) > VERSION) throw new Exception("ఇది కొత్త రకం ప్యాక్: ముందు Jarvis యాప్ అప్‌డేట్ చేయండి");
        JSONObject cs = o.optJSONObject("clips");
        if (cs == null) throw new Exception("ప్యాక్‌లో క్లిప్స్ జాబితా లేదు");
        Map<String, Clip> out = new LinkedHashMap<>();
        for (String kind : KINDS) {
            JSONObject c = cs.optJSONObject(kind);
            if (c == null) continue;
            String file = c.optString("file", "").trim();
            if (!FilmPack.safeName(file) || !file.toLowerCase(Locale.ROOT).endsWith(".mp4")) continue;
            if (dir != null && !new File(dir, file).isFile()) continue;
            int ms = Math.max(0, Math.min(120_000, c.optInt("ms", 0)));
            int fade = Math.max(0, Math.min(1500, c.optInt("fadeMs", 300)));
            int end = c.optInt("loopEndMs", 0);
            if (end < 1000 || end <= fade || (ms > 0 && end > ms)) end = 0; // (no sensible place: it plays round as it is)
            out.put(kind, new Clip(kind, file, ms, c.optBoolean("loop", !"hello".equals(kind)), end, fade));
        }
        if (!out.containsKey("idle")) throw new Exception("ప్యాక్‌లో 😌 (idle) క్లిప్ లేదు");
        JSONObject body = o.optJSONObject("body");
        float cx = body == null ? 0.5f : (float) Math.max(0.3, Math.min(0.7, body.optDouble("centreX", 0.5)));
        float top = body == null ? 0.18f : (float) Math.max(0, Math.min(0.5, body.optDouble("topOfHair", 0.18)));
        return new HoloPack(Collections.unmodifiableMap(out), cx, top, dir);
    }

    // ================================================================ the zip he picked

    /**
     * His zip → files/holo_show: every file into holo_show.part first (only plain names: mp4 / json, one folder level at
     * most, each ≤ 60 MB, all ≤ 200 MB), then pack.json is read and every clip it names must be there; only then the old
     * pack goes and the new one takes its place (all of it, or nothing: the old one stays whole). Returns the new pack, or
     * null with the reason in {@code why[0]}.
     */
    static HoloPack importZip(File filesDir, InputStream in, String[] why) {
        if (!BUSY.compareAndSet(false, true)) { say(why, "ఇంకో ప్యాక్ ఇప్పుడే కలుస్తోంది: అది అయ్యాక"); return null; }
        try { return importNow(filesDir, in, why); } finally { BUSY.set(false); }
    }

    private static HoloPack importNow(File filesDir, InputStream in, String[] why) {
        File part = new File(filesDir, PART), dir = new File(filesDir, DIR);
        FilmPack.deleteTree(part);
        if (!part.mkdirs()) { say(why, "ఫోన్‌లో చోటు లేదు"); return null; }
        long all = 0;
        int files = 0, entries = 0;
        List<String> names = new ArrayList<>();
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) { FilmPack.deleteTree(part); say(why, "ఆ zip లో చాలా ఫైళ్లు ఉన్నాయి: ఇది హోలో ప్యాక్ కాదు"); return null; }
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                int slash = n.lastIndexOf('/');
                String folder = slash < 0 ? "" : n.substring(0, slash);
                if (folder.contains("/") || folder.startsWith(".") || folder.startsWith("_")) continue; // (deeper than one folder, "..", Mac's own folder: not ours)
                String base = n.substring(slash + 1);
                String low = base.toLowerCase(Locale.ROOT);
                if (!FilmPack.safeName(base) || base.startsWith("._") || !(low.endsWith(".mp4") || base.equals(MANIFEST))) continue;
                if (names.contains(base)) { FilmPack.deleteTree(part); say(why, "ఒకే పేరుతో రెండు ఫైళ్లు (" + base + ")"); return null; }
                names.add(base);
                File out = new File(part, base);
                long size = 0;
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int r;
                    while ((r = z.read(buf)) > 0) {
                        size += r;
                        all += r;
                        if (size > MAX_FILE || all > MAX_ALL) { FilmPack.deleteTree(part); say(why, "ప్యాక్ చాలా పెద్దది"); return null; }
                        os.write(buf, 0, r);
                    }
                }
                files++;
            }
        } catch (Exception ex) {
            FilmPack.deleteTree(part);
            say(why, filesDir.getUsableSpace() < 64L * 1024 * 1024 ? "ఫోన్‌లో చోటు సరిపోలేదు" : "ఆ ఫైల్ zip కాదు లేదా పాడైంది");
            return null;
        }
        if (files == 0) { FilmPack.deleteTree(part); say(why, "ఆ zip లో క్లిప్స్ లేవు"); return null; }
        File m = new File(part, MANIFEST);
        if (!m.isFile()) { FilmPack.deleteTree(part); say(why, "ప్యాక్‌లో pack.json లేదు: ఇది Jarvis హోలో ప్యాక్ కాదు"); return null; }
        HoloPack named;
        try {
            String json = readAll(m);
            named = parse(json, null);
            List<String> missing = new ArrayList<>();
            for (Clip c : named.clips.values()) if (!new File(part, c.file).isFile()) missing.add(c.file);
            if (!missing.isEmpty()) throw new Exception(String.join(", ", missing) + " లేదు");
            parse(json, part);
        } catch (Exception ex) {
            FilmPack.deleteTree(part);
            say(why, ex.getMessage() == null ? "ప్యాక్ చదవలేకపోయాను" : ex.getMessage());
            return null;
        }
        File old = new File(filesDir, OLD);
        FilmPack.deleteTree(old);
        if (dir.exists() && !dir.renameTo(old)) { FilmPack.deleteTree(part); say(why, "పాత ప్యాక్ పక్కకు జరపలేకపోయాను: పాతదే ఉంది"); return null; }
        if (!part.renameTo(dir)) {
            if (old.isDirectory()) //noinspection ResultOfMethodCallIgnored
                old.renameTo(dir); // (the old one back)
            FilmPack.deleteTree(part);
            say(why, "ప్యాక్ దాచలేకపోయాను: పాతదే ఉంది");
            return null;
        }
        FilmPack.deleteTree(old);
        bump();
        HoloPack p = load(filesDir);
        if (p == null) say(why, "ప్యాక్ చదవలేకపోయాను");
        return p;
    }

    /** Takes the pack away (🗑️). False: a pack is going in just now (try after it). */
    static boolean remove(File filesDir) {
        if (!BUSY.compareAndSet(false, true)) return false;
        try {
            FilmPack.deleteTree(new File(filesDir, DIR));
            FilmPack.deleteTree(new File(filesDir, OLD));
            FilmPack.deleteTree(new File(filesDir, PART));
            bump();
            return true;
        } finally { BUSY.set(false); }
    }

    /** True for the files and folders of the pack (they never go into a backup, and a restore leaves them alone). */
    static boolean isPackFile(String name) { return DIR.equals(name) || PART.equals(name) || OLD.equals(name); }

    private static void say(String[] why, String s) { if (why != null && why.length > 0) why[0] = s; }

    private static String readAll(File f) throws Exception {
        if (f.length() > 256 * 1024) throw new Exception("ప్యాక్ జాబితా (pack.json) చాలా పెద్దది");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        }
    }
}
