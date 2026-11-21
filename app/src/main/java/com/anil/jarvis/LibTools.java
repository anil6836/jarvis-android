package com.anil.jarvis;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 📚 The library's tools beyond reading a book: 📊 how much he reads, 🖍️ his marks and notes (a PDF on his tap),
 * 🎧 a chapter as a sound file (the phone's own reading voice), the AI book tools (💬 ask the book, 📝 a chapter in short,
 * ❓ a chapter's quiz, 🗺️ its people: cost said first, only on his tap, the library's AI, the month's limit; what was
 * made once is kept, ₹0 again), 📚 what to read next and 🏠 his home bookshelf from a photo (the photo is never kept).
 */
final class LibTools {
    private LibTools() {}

    // ================================================================ 📊 how much he reads

    private static final Object SL = new Object();

    private static File statsFile(Context c) { return new File(Library.root(c), "stats.json"); }

    private static String day(Date d) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d); }

    private static JSONObject loadStats(Context c) {
        try {
            File f = statsFile(c);
            if (f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private static void saveStats(Context c, JSONObject j) {
        try { Files.write(statsFile(c).toPath(), j.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    private static JSONObject obj(JSONObject j, String k) throws Exception {
        JSONObject o = j.optJSONObject(k);
        if (o == null) { o = new JSONObject(); j.put(k, o); }
        return o;
    }

    /** Seconds read aloud: to today and to the book ("" = a file that isn't a library book). */
    static void readFor(Context c, String id, double secs) {
        if (secs <= 0 || secs > 900) return;
        synchronized (SL) {
            try {
                JSONObject j = loadStats(c), days = obj(j, "days");
                String d = day(new Date());
                days.put(d, Math.round((days.optDouble(d, 0) + secs) * 10) / 10.0);
                if (id != null && !id.isEmpty()) { JSONObject b = obj(j, "books"); b.put(id, Math.round((b.optDouble(id, 0) + secs) * 10) / 10.0); }
                saveStats(c, j);
            } catch (Exception ignored) {}
        }
    }

    /** A chapter read to its end today. */
    static void chapterDone(Context c, String id) {
        synchronized (SL) {
            try {
                JSONObject j = loadStats(c), ch = obj(j, "ch");
                String d = day(new Date());
                ch.put(d, ch.optInt(d, 0) + 1);
                saveStats(c, j);
            } catch (Exception ignored) {}
        }
    }

    private static final String[] WEEKDAYS = {"ఆది", "సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని"};

    /** For the 📊 page: today, the last 7 days, all of it, days in a row, chapters this week, finished books, his top books. */
    static JSONObject statsView(Context c) throws Exception {
        JSONObject j;
        synchronized (SL) { j = loadStats(c); }
        JSONObject days = j.optJSONObject("days"), ch = j.optJSONObject("ch"), books = j.optJSONObject("books");
        if (days == null) days = new JSONObject();
        if (ch == null) ch = new JSONObject();
        if (books == null) books = new JSONObject();
        Calendar cal = Calendar.getInstance();
        JSONArray week = new JSONArray();
        int chWeek = 0;
        double weekSecs = 0;
        Calendar d = (Calendar) cal.clone();
        d.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            String k = day(d.getTime());
            double s = days.optDouble(k, 0);
            weekSecs += s;
            chWeek += ch.optInt(k, 0);
            week.put(new JSONObject().put("d", i == 6 ? "ఈ రోజు" : WEEKDAYS[d.get(Calendar.DAY_OF_WEEK) - 1]).put("m", Math.round(s / 60)));
            d.add(Calendar.DAY_OF_MONTH, 1);
        }
        double all = 0;
        for (java.util.Iterator<String> it = days.keys(); it.hasNext(); ) all += days.optDouble(it.next(), 0);
        JSONArray top = new JSONArray();
        List<String> ids = new ArrayList<>();
        for (java.util.Iterator<String> it = books.keys(); it.hasNext(); ) ids.add(it.next());
        final JSONObject bk = books;
        ids.sort((a, b) -> Double.compare(bk.optDouble(b, 0), bk.optDouble(a, 0)));
        for (String id : ids) {
            if (top.length() >= 6) break;
            JSONObject b = Library.book(c, id);
            if (b == null) continue;
            top.put(new JSONObject().put("id", id).put("t", b.optString("t")).put("m", Math.round(books.optDouble(id, 0) / 60)));
        }
        int done = 0;
        JSONArray sh = Library.shelf(c);
        for (int i = 0; i < sh.length(); i++) { JSONObject b = sh.optJSONObject(i); if (b != null && b.optBoolean("done")) done++; }
        return new JSONObject().put("t", "stats").put("today", Math.round(days.optDouble(day(cal.getTime()), 0) / 60)).put("week", week)
                .put("week_m", Math.round(weekSecs / 60)).put("all_h", Math.round(all / 360) / 10.0).put("streak", LibText.streak(days, cal))
                .put("ch_week", chWeek).put("done", done).put("top", top);
    }

    // ================================================================ 🖍️ his marks and notes

    private static File marksFile(Context c, String id) { return new File(Library.bookDir(c, id), "marks.json"); }

    static synchronized JSONArray marks(Context c, String id) {
        if (!LibText.goodId(id)) return new JSONArray();
        try {
            File f = marksFile(c, id);
            if (f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optJSONArray("m");
        } catch (Exception ignored) {}
        return new JSONArray();
    }

    /** A paragraph marked (with its Telugu as shown, and his note), or its mark taken off; the book's marks back. */
    static synchronized JSONArray mark(Context c, String id, int unit, int part, String text, String te, String note, boolean on) throws Exception {
        if (!LibText.goodId(id) || Library.book(c, id) == null) throw new IOException("పుస్తకం ఫోన్‌లో లేదు");
        JSONArray a = marks(c, id), out = new JSONArray();
        JSONObject was = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            if (x.optInt("u") == unit && x.optInt("k") == part) was = x; else out.put(x);
        }
        if (on) {
            JSONObject m = new JSONObject().put("u", unit).put("k", part).put("x", LibText.cut(text == null ? "" : text, 1500))
                    .put("te", LibText.cut(te == null ? "" : te, 1500)).put("n", LibText.cut(note == null ? (was == null ? "" : was.optString("n")) : note, 800))
                    .put("at", was == null ? System.currentTimeMillis() : was.optLong("at", System.currentTimeMillis()));
            out.put(m);
        }
        // in book order
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < out.length(); i++) l.add(out.getJSONObject(i));
        l.sort((x, y) -> x.optInt("u") != y.optInt("u") ? Integer.compare(x.optInt("u"), y.optInt("u")) : Integer.compare(x.optInt("k"), y.optInt("k")));
        JSONArray sorted = new JSONArray();
        for (JSONObject x : l) sorted.put(x);
        Library.bookDir(c, id).mkdirs();
        Files.write(marksFile(c, id).toPath(), new JSONObject().put("m", sorted).toString().getBytes(StandardCharsets.UTF_8));
        return sorted;
    }

    /** Every book with marks: [{id, t, m: [...]}]. */
    static JSONArray allMarks(Context c) throws Exception {
        JSONArray out = new JSONArray(), sh = Library.shelf(c);
        for (int i = 0; i < sh.length(); i++) {
            JSONObject b = sh.optJSONObject(i);
            if (b == null || !"ready".equals(b.optString("state"))) continue;
            JSONArray m = marks(c, b.optString("id"));
            if (m.length() > 0) out.put(new JSONObject().put("id", b.optString("id")).put("t", b.optString("t")).put("tt", b.optString("tt")).put("m", m));
        }
        return out;
    }

    /** 📄 The book's marks and notes as a PDF in Downloads/Jarvis/library (shared only when he taps 📤). */
    static Coder.Made marksPdf(Context c, String id) throws Exception {
        JSONObject b = Library.book(c, id);
        JSONArray m = marks(c, id);
        if (b == null || m.length() == 0) throw new IOException("ఈ పుస్తకంలో గుర్తులు లేవు");
        JSONArray titles = Library.titles(c, id);
        List<String> lines = new ArrayList<>();
        lines.add(b.optString("t") + (b.optString("a").isEmpty() ? "" : " · " + b.optString("a")));
        lines.add(m.length() + " గుర్తులు · " + new SimpleDateFormat("d MMM yyyy", new Locale("te", "IN")).format(new Date()));
        lines.add("");
        int lastU = -1;
        for (int i = 0; i < m.length(); i++) {
            JSONObject x = m.getJSONObject(i);
            int u = x.optInt("u");
            if (u != lastU) { lines.add("📖 అధ్యాయం " + (u + 1) + (titles != null && titles.optString(u, "").length() > 0 ? ": " + titles.optString(u) : "")); lastU = u; }
            lines.add("🖍️ " + x.optString("x"));
            if (!x.optString("te").isEmpty()) lines.add("    " + x.optString("te"));
            if (!x.optString("n").isEmpty()) lines.add("📝 " + x.optString("n"));
            lines.add("");
        }
        byte[] pdf = CircuitLab.pdf("🖍️ ", b.optString("t"), null, 0xFFFFFFFF, lines, "Jarvis 📚 గ్రంథాలయం · మీ గుర్తులు, మీ నోట్స్ (మీ ఫోన్‌లోనే తయారైంది).");
        return Coder.save(c, "Jarvis/library", Workshop.fileName("గుర్తులు " + b.optString("t"), ".pdf"), "application/pdf", pdf);
    }

    // ================================================================ 🎧 a chapter as a sound file (the phone's own reading voice)

    interface Prog { void at(String said, int pct); }

    static volatile boolean audioStop;
    static volatile String audioBusy = "";

    static final class Saved { Uri uri; String where = "", name = ""; }

    /**
     * The chapter read by the same phone voice and speed as 📖 reading ("en" English, "te" the phone's Telugu, "ai" his AI
     * Telugu, lines it doesn't have in English) into an .m4a in Music/Jarvis Library. ₹0, no internet. Background thread.
     */
    static Saved audio(Context c, String id, int unit, String lang, Prog p) throws Exception {
        JSONObject b = Library.book(c, id);
        if (b == null || !"ready".equals(b.optString("state"))) throw new IOException("పుస్తకం ఫోన్‌లో లేదు");
        String text = Library.chapter(c, id, unit);
        List<String> parts = ReaderService.split(text);
        if (parts.isEmpty()) throw new IOException("ఈ అధ్యాయంలో అక్షరాలు లేవు");
        String[] ai = "ai".equals(lang) ? Library.kept(c, id, "ai", unit, parts.size()) : null;
        if ("ai".equals(lang) && ai == null) throw new IOException("ఈ అధ్యాయానికి AI తెలుగు ఇంకా లేదు Sir. ముందు ✨ చేయించండి, లేదా English / ఫోన్ తెలుగు ఎంచుకోండి.");
        if ("te".equals(lang) && !OfflineRead.ready(c)) throw new IOException("ఫోన్ తెలుగు ప్యాక్ ఫోన్‌లో లేదు Sir (⬇️ తెలుగు ప్యాక్ ఒకసారి దించండి).");
        audioStop = false;
        audioBusy = id;
        File dir = new File(c.getCacheDir(), "libaudio");
        wipe(dir);
        dir.mkdirs();
        File out = new File(dir, "out.m4a");
        TextToSpeech tts = null;
        Enc enc = null;
        try {
            tts = openTts(c);
            float own = ReaderService.mark(c).getFloat("rate", 0f);
            tts.setSpeechRate(own > 0.3f ? own : new Prefs(c).speechRate());
            for (int k = 0; k < parts.size(); k++) {
                if (audioStop) throw new IOException("ఆపేశాను");
                String line = parts.get(k);
                if ("te".equals(lang)) { String te = Library.tePart(c, id, unit, parts, k); if (te != null) line = te; }
                else if (ai != null && ai[k] != null) line = ai[k];
                File w = new File(dir, "p.wav");
                if (!synth(tts, line, w)) continue;
                byte[] bytes = Files.readAllBytes(w.toPath());
                int[] info = LibText.wavInfo(bytes);
                if (info == null) continue;
                if (enc == null) enc = new Enc(out, info[0]);
                enc.add(LibText.mono16(bytes, info, enc.rate));
                enc.add(new short[enc.rate / 4]); // (a breath between sentences)
                if (p != null) p.at("🎧 " + (k + 1) + " / " + parts.size() + " వాక్యాలు", (k + 1) * 100 / parts.size());
            }
            if (enc == null) throw new IOException("ఫోన్ వాయిస్ ఇంజిన్ ఆడియో ఇవ్వలేదు");
            enc.finish();
            enc.release();
            enc = null;
            JSONArray titles = Library.titles(c, id);
            String ct = titles == null ? "" : titles.optString(unit, "");
            String title = (unit + 1) + ". " + (ct.isEmpty() ? "అధ్యాయం " + (unit + 1) : ct);
            return saveMusic(c, Workshop.fileName(b.optString("t") + " - " + title + ("te".equals(lang) || "ai".equals(lang) ? " (తెలుగు)" : ""), ".m4a"),
                    out, b.optString("t"), title);
        } finally {
            if (enc != null) enc.release();
            if (tts != null) try { tts.shutdown(); } catch (Exception ignored) {}
            wipe(dir);
            audioBusy = "";
        }
    }

    private static void wipe(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) wipe(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static volatile CountDownLatch synthDone;

    private static TextToSpeech openTts(Context c) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        final int[] st = {TextToSpeech.ERROR};
        TextToSpeech t = new TextToSpeech(c.getApplicationContext(), s -> { st[0] = s; ready.countDown(); });
        if (!ready.await(15, TimeUnit.SECONDS) || st[0] != TextToSpeech.SUCCESS) { t.shutdown(); throw new IOException("ఫోన్ వాయిస్ ఇంజిన్ తెరవలేకపోయాను"); }
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) {}
            @Override public void onDone(String u) { CountDownLatch l = synthDone; if (l != null) l.countDown(); }
            @Override public void onError(String u) { CountDownLatch l = synthDone; if (l != null) l.countDown(); }
        });
        return t;
    }

    /** One sentence into a WAV file (the same words and language the reader would say); false when the engine gave nothing. */
    private static boolean synth(TextToSpeech tts, String line, File w) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        w.delete();
        try { tts.setLanguage(Lang.of(line)); } catch (Exception ignored) {}
        String said = Spoken.say(line);
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (said.length() > max) said = said.substring(0, max);
        CountDownLatch l = new CountDownLatch(1);
        synthDone = l;
        if (tts.synthesizeToFile(said, new Bundle(), w, "a" + System.nanoTime()) != TextToSpeech.SUCCESS) return false;
        l.await(2, TimeUnit.MINUTES);
        return w.exists() && w.length() > 44;
    }

    /** 16-bit mono sound → AAC in an .m4a (Android's own encoder). */
    private static final class Enc {
        final int rate;
        private final MediaCodec codec;
        private final MediaMuxer mux;
        private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        private int track = -1;
        private boolean muxing, released;
        private long frames;

        Enc(File out, int rate) throws IOException {
            this.rate = rate;
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void add(short[] s) {
            int i = 0;
            while (i < s.length) {
                int idx = codec.dequeueInputBuffer(10_000);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(idx);
                    if (buf == null) continue;
                    buf.clear();
                    buf.order(ByteOrder.LITTLE_ENDIAN);
                    int n = Math.min(s.length - i, buf.remaining() / 2);
                    buf.asShortBuffer().put(s, i, n);
                    codec.queueInputBuffer(idx, 0, n * 2, frames * 1_000_000L / rate, 0);
                    frames += n;
                    i += n;
                }
                drain(false);
            }
        }

        void finish() {
            int idx, tries = 0;
            while ((idx = codec.dequeueInputBuffer(10_000)) < 0 && tries++ < 500) drain(false);
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        }

        private void drain(boolean end) {
            int idle = 0;
            while (true) {
                int o = codec.dequeueOutputBuffer(bi, end ? 10_000 : 0);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || ++idle > 300) return; continue; }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxing) { track = mux.addTrack(codec.getOutputFormat()); mux.start(); muxing = true; }
                    continue;
                }
                if (o < 0) continue;
                ByteBuffer ob = codec.getOutputBuffer(o);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                if (ob != null && bi.size > 0 && muxing) {
                    ob.position(bi.offset);
                    ob.limit(bi.offset + bi.size);
                    mux.writeSampleData(track, ob, bi);
                }
                codec.releaseOutputBuffer(o, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        void release() {
            if (released) return;
            released = true;
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            try { if (muxing) mux.stop(); } catch (Exception ignored) {}
            try { mux.release(); } catch (Exception ignored) {}
        }
    }

    /** Into Music/Jarvis Library (his music apps see it there). */
    private static Saved saveMusic(Context c, String name, File f, String album, String title) throws Exception {
        Saved s = new Saved();
        s.name = name;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Jarvis Library");
            v.put(MediaStore.Audio.Media.TITLE, title);
            v.put(MediaStore.Audio.Media.ALBUM, album);
            v.put(MediaStore.Audio.Media.ARTIST, "Jarvis 📚");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("ఆడియో ఫైల్ సేవ్ కాలేదు");
            try (OutputStream o = cr.openOutputStream(uri); InputStream in = Files.newInputStream(f.toPath())) {
                if (o == null) throw new IOException("ఆడియో ఫైల్ సేవ్ కాలేదు");
                byte[] buf = new byte[65536];
                for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            s.uri = uri;
            s.where = "Music/Jarvis Library/" + name;
        } else {
            File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Jarvis Library");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File to = new File(dir, name);
            Files.copy(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            s.where = to.getPath();
        }
        return s;
    }

    // ================================================================ 💬 📝 ❓ 🗺️ the AI book tools (cost first, his tap)

    static final String SUM_SYS = "You write a short version of one chapter of a book for a Telugu reader. Write in simple, natural Telugu "
            + "(the names in Telugu letters): first 4-6 sentences that tell what happens, then 3-5 key points, each on its own line "
            + "starting with '• '. Only what the chapter says; add nothing. Output only the Telugu text.";
    static final String QUIZ_SYS = "You make a 5-question quiz in simple Telugu about one chapter of a book, to check the reader "
            + "remembers it. Each question has 4 choices and exactly one right answer, all from what the chapter says. Output JSON only: "
            + "{\"q\":[{\"q\":\"question\",\"o\":[\"choice\",\"choice\",\"choice\",\"choice\"],\"a\":0,\"why\":\"one Telugu line on why\"}]} "
            + "where a is the number (0-3) of the right choice.";
    static final String ASK_SYS = "You answer Anil's question about a book using ONLY the parts of the book given (each starts with "
            + "[అధ్యాయం N]). Answer in simple Telugu, 2-6 sentences, the names in Telugu letters, and say which chapter it comes from as "
            + "(అధ్యాయం N). If these parts don't hold the answer, say plainly in Telugu that the book (these parts) doesn't say it. "
            + "Never make things up; no outside knowledge.";
    static final String KEY_SYS = "Turn Anil's question (often in Telugu) about an English book into 6-12 English search words that would "
            + "appear in the book's text: names, places, things, key nouns and verbs. Output only the words, separated by spaces.";
    static final String MAP_SYS = "From this chapter of a book, list its characters (people, and animals or beings that act) and how they "
            + "are linked. Output JSON only: {\"c\":[{\"n\":\"the name as written in the text\",\"te\":\"the name in Telugu letters\","
            + "\"w\":\"who they are, one short Telugu line\"}],\"r\":[[\"name\",\"name\",\"the link in Telugu, 2-4 words\"]],"
            + "\"e\":\"what happens in this chapter, one Telugu sentence\"}. Only what the chapter says.";
    static final String NEXT_SYS = "Suggest 6 books for a reader in India (he listens in Telugu) who read and liked the books listed. Prefer "
            + "books that are free and legal to read (old, out of copyright, e.g. on Project Gutenberg or Telugu Wikisource); no books from "
            + "piracy sites. Output JSON only: {\"b\":[{\"t\":\"the book's title in its own language\",\"a\":\"author\",\"why\":\"one Telugu line on why he may like it\"}]}";
    static final String SHELF_SYS = "This is a photo of Anil's own bookshelf at home. Read the titles and authors on the book spines and "
            + "covers you can see clearly. Output JSON only: {\"b\":[{\"t\":\"title as printed\",\"a\":\"author if printed, else empty\"}]}. "
            + "Leave out what you can't read; never guess a title. No people's details.";

    private static File aiDir(Context c, String id) { File d = new File(Library.bookDir(c, id), "ai"); d.mkdirs(); return d; }

    private static JSONObject load(File f) {
        try { if (f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        return null;
    }

    private static void store(File f, JSONObject o) { try { Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {} }

    private static double cost(String m, long in, long out) { return m.isEmpty() ? 0 : Workshop.rupees(m, in, 0, out); }

    /** What was made before for this chapter (₹0 again): "sum" {text} / "quiz" {q} / "map" {c, r, e} of the book; null when none. */
    static JSONObject kept(Context c, String kind, String id, int unit) {
        if (!LibText.goodId(id)) return null;
        if ("map".equals(kind)) return load(new File(aiDir(c, id), "map.json"));
        return load(new File(aiDir(c, id), kind + "_" + unit + ".json"));
    }

    /** The AI calls a tool needs, as {tokens in, tokens out} each (the same for every model; the ₹ comes from the model's price). */
    static List<long[]> need(Context c, String kind, String id, int unit, String q) {
        List<long[]> l = new ArrayList<>();
        long chars = 0;
        try { chars = Library.chapter(c, id, unit).length(); } catch (Exception ignored) {}
        int qTok = q == null ? 0 : (int) (q.length() / 1.5);
        switch (kind) {
            case "sum": l.add(new long[]{LibText.tokensIn(chars) + 120, 1500}); break;
            case "quiz": l.add(new long[]{LibText.tokensIn(chars) + 160, 2200}); break;
            case "ask_ch": l.add(new long[]{LibText.tokensIn(chars) + 200 + qTok, 700}); break;
            case "ask_book":
                if (!latin(q)) l.add(new long[]{160 + qTok, 80});
                l.add(new long[]{LibText.tokensIn(22000) + 200 + qTok, 700});
                break;
            case "map": {
                int n = 0;
                try { n = Library.count(c, id); } catch (Exception ignored) {}
                for (int u = 0; u < n; u++) {
                    if (mapDone(c, id, u)) continue;
                    long cc = 0;
                    try { cc = Library.chapter(c, id, u).length(); } catch (Exception ignored) {}
                    l.add(new long[]{LibText.tokensIn(Math.min(cc, 120_000)) + 160, 900});
                }
                break;
            }
            case "next": l.add(new long[]{600, 1000}); break;
            case "shelf": l.add(new long[]{1900, 1200}); break;
            default:
        }
        return l;
    }

    static double rupees(String m, List<long[]> need) { double r = 0; for (long[] x : need) r += cost(m, x[0], x[1]); return r; }

    /** {t: tquote, kind, id, unit, model, known, rupees, have, left (map: chapters still to do), used, limit, why?} before an AI tool. */
    static JSONObject quote(Context c, String kind, String id, int unit, String q) throws Exception {
        Prefs p = new Prefs(c);
        String m = Library.model(p);
        JSONObject o = new JSONObject().put("t", "tquote").put("kind", kind).put("id", id).put("unit", unit).put("model", m)
                .put("known", !m.isEmpty() && Usage.knownPrice(m)).put("used", Math.round(Workshop.spent(c))).put("limit", p.workshopLimit());
        long chars = 0;
        try { chars = Library.chapter(c, id, unit).length(); } catch (Exception ignored) {}
        List<long[]> need = need(c, kind, id, unit, q);
        double r = rupees(m, need);
        boolean have = false;
        int left = 0;
        switch (kind) {
            case "sum": case "quiz": have = kept(c, kind, id, unit) != null; break;
            case "map": left = need.size(); have = left == 0 && kept(c, "map", id, 0) != null; break;
            default:
        }
        o.put("rupees", Math.round(r * 100) / 100.0).put("have", have).put("left", left).put("chars", chars);
        if (m.isEmpty()) o.put("why", "లైబ్రరీకి AI ఇంకా ఎంచుకోలేదు Sir. ✨ AI తెలుగు → 🤖 మోడల్ మార్చు లో, లేదా ⚙️ → 🛠️ లో 📚 కింద ఎంచుకోండి.");
        String over = Workshop.overLimit(c, p);
        if (over != null && !have) o.put("why", over);
        return o;
    }

    private static boolean latin(String q) { return q != null && LibText.searchWords(q).size() >= 2 && !q.matches("(?s).*[\\u0C00-\\u0C7F].*"); }

    /** The words as they come (the page shows them while the AI writes). */
    interface Live { void text(String sofar); }

    private static String book(Context c, String id) { JSONObject b = Library.book(c, id); return b == null ? "" : b.optString("t") + (b.optString("a").isEmpty() ? "" : " (" + b.optString("a") + ")"); }

    private static String chapterHead(Context c, String id, int unit) {
        JSONArray t = Library.titles(c, id);
        String ct = t == null ? "" : t.optString(unit, "");
        return "Chapter " + (unit + 1) + (ct.isEmpty() ? "" : ": " + ct);
    }

    private static Workshop.Engine ready(Context c) throws Exception {
        Prefs p = new Prefs(c);
        Workshop.Engine e = Library.engine(p);
        String over = Workshop.overLimit(c, p);
        if (over != null) throw new Workshop.Problem(over);
        return e;
    }

    private static String ask(Context c, Workshop.Engine e, String sys, String user, int maxOut, Live live, double[] spent) throws Exception {
        StringBuilder sb = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        long[] last = {0};
        try {
            Workshop.stream(e, sys, user, maxOut, s -> {
                sb.append(s);
                long now = System.currentTimeMillis();
                if (live != null && now - last[0] > 250) { last[0] = now; live.text(sb.toString()); }
            }, bill);
        } finally {
            Workshop.spendBooks(c, bill.rupees, false);
            spent[0] += bill.rupees;
        }
        if (live != null) live.text(sb.toString());
        return sb.toString().trim();
    }

    /** 📝 The chapter in short, in Telugu (kept: ₹0 next time). {text, model, rs, kept?} */
    static JSONObject summary(Context c, String id, int unit, Live live) throws Exception {
        JSONObject k = kept(c, "sum", id, unit);
        if (k != null) return k.put("kept", true);
        Workshop.Engine e = ready(c);
        double[] rs = {0};
        String text = Library.chapter(c, id, unit);
        String out = ask(c, e, SUM_SYS, "Book: " + book(c, id) + "\n" + chapterHead(c, id, unit) + "\n\n" + LibText.cut(text, 200_000), 2200, live, rs);
        if (out.isEmpty()) throw new IOException("AI ఏమీ రాయలేదు (ఖర్చు " + Workshop.money(rs[0]) + ")");
        JSONObject o = new JSONObject().put("text", out).put("model", e.model).put("rs", Math.round(rs[0] * 100) / 100.0).put("at", System.currentTimeMillis());
        store(new File(aiDir(c, id), "sum_" + unit + ".json"), o);
        Workshop.spendBooks(c, 0, true);
        return o;
    }

    /** ❓ Five questions on the chapter (kept). {q: [...], model, rs} */
    static JSONObject quiz(Context c, String id, int unit) throws Exception {
        JSONObject k = kept(c, "quiz", id, unit);
        if (k != null) return k.put("kept", true);
        Workshop.Engine e = ready(c);
        double[] rs = {0};
        String text = Library.chapter(c, id, unit);
        String out = ask(c, e, QUIZ_SYS, "Book: " + book(c, id) + "\n" + chapterHead(c, id, unit) + "\n\n" + LibText.cut(text, 200_000), 3200, null, rs);
        JSONArray q = LibText.quiz(out);
        if (q.length() == 0) throw new IOException("క్విజ్ సరిగ్గా రాలేదు Sir (ఖర్చు " + Workshop.money(rs[0]) + "). మళ్ళీ నొక్కితే మళ్ళీ అడుగుతాను.");
        JSONObject o = new JSONObject().put("q", q).put("model", e.model).put("rs", Math.round(rs[0] * 100) / 100.0).put("at", System.currentTimeMillis());
        store(new File(aiDir(c, id), "quiz_" + unit + ".json"), o);
        Workshop.spendBooks(c, 0, true);
        return o;
    }

    private static File qaFile(Context c, String id) { return new File(aiDir(c, id), "qa.json"); }

    /** 💬 What he asked this book before (newest first). */
    static JSONArray asked(Context c, String id) {
        JSONObject j = LibText.goodId(id) ? load(qaFile(c, id)) : null;
        JSONArray a = j == null ? null : j.optJSONArray("a");
        return a == null ? new JSONArray() : a;
    }

    /**
     * 💬 His question answered from the book itself: this chapter ("ch"), or the whole book ("book": his question turned into
     * English search words by the AI when it is in Telugu, then the best parts found on the phone go with it).
     */
    static JSONObject askBook(Context c, String id, int unit, String scope, String q, Live live) throws Exception {
        String qq = q == null ? "" : q.trim();
        if (qq.isEmpty()) throw new IOException("ప్రశ్న రాయండి Sir");
        JSONArray before = asked(c, id);
        for (int i = 0; i < before.length(); i++) {
            JSONObject x = before.optJSONObject(i);
            if (x != null && x.optString("q").equals(qq) && x.optString("scope").equals(scope) && ("book".equals(scope) || x.optInt("unit") == unit))
                return new JSONObject(x.toString()).put("kept", true);
        }
        Workshop.Engine e = ready(c);
        double[] rs = {0};
        String context;
        if ("book".equals(scope)) {
            List<String> words = LibText.searchWords(qq);
            if (!latin(qq)) words = LibText.searchWords(ask(c, e, KEY_SYS, "Book: " + book(c, id) + "\nQuestion: " + qq, 120, null, rs) + " " + qq);
            List<String> chs = new ArrayList<>();
            int n = Library.count(c, id);
            for (int u = 0; u < n; u++) { try { chs.add(Library.chapter(c, id, u)); } catch (Exception x) { chs.add(""); } }
            context = LibText.topPassages(chs, words, 1400, 22_000);
            if (context.isEmpty()) {
                JSONObject o = new JSONObject().put("q", qq).put("a", "ఈ పుస్తకంలో దీని గురించి నాకు ఏమీ దొరకలేదు Sir. వేరే మాటలతో అడగండి, లేదా అధ్యాయం తెరిచి \"ఈ అధ్యాయంలో\" అడగండి.")
                        .put("scope", scope).put("unit", unit).put("rs", Math.round(rs[0] * 100) / 100.0).put("model", e.model).put("at", System.currentTimeMillis());
                keepAsked(c, id, o);
                return o;
            }
        } else {
            context = "[అధ్యాయం " + (unit + 1) + "]\n" + LibText.cut(Library.chapter(c, id, unit), 200_000);
        }
        String a = ask(c, e, ASK_SYS, "Book: " + book(c, id) + "\n\nParts of the book:\n" + context + "\n\nAnil's question: " + qq, 1400, live, rs);
        if (a.isEmpty()) throw new IOException("AI జవాబు రాయలేదు (ఖర్చు " + Workshop.money(rs[0]) + ")");
        JSONObject o = new JSONObject().put("q", qq).put("a", a).put("scope", scope).put("unit", unit).put("rs", Math.round(rs[0] * 100) / 100.0)
                .put("model", e.model).put("at", System.currentTimeMillis());
        keepAsked(c, id, o);
        Workshop.spendBooks(c, 0, true);
        return o;
    }

    private static synchronized void keepAsked(Context c, String id, JSONObject o) throws Exception {
        JSONArray a = asked(c, id), out = new JSONArray().put(o);
        for (int i = 0; i < a.length() && out.length() < 60; i++) out.put(a.get(i));
        store(qaFile(c, id), new JSONObject().put("a", out));
    }

    /** 🗺️ One chapter's people (part of the map job): kept as map_<u>.json; the rupees it cost. */
    static boolean mapDone(Context c, String id, int unit) { return LibText.goodId(id) && new File(aiDir(c, id), "map_" + unit + ".json").exists(); }

    static double mapChapter(Context c, Workshop.Engine e, String id, int unit) throws Exception {
        File f = new File(aiDir(c, id), "map_" + unit + ".json");
        if (f.exists()) return 0;
        double[] rs = {0};
        String text = Library.chapter(c, id, unit);
        String out = ask(c, e, MAP_SYS, "Book: " + book(c, id) + "\n" + chapterHead(c, id, unit) + "\n\n" + LibText.cut(text, 120_000), 1800, null, rs);
        JSONObject j = LibText.jsonBlock(out);
        if (j == null) j = new JSONObject();
        j.put("u", unit);
        store(f, j);
        return rs[0];
    }

    /** 🗺️ The chapters' people as one map (map.json), from the chapters done so far. */
    static JSONObject mapMerge(Context c, String id) {
        List<JSONObject> l = new ArrayList<>();
        int n = Library.count(c, id);
        for (int u = 0; u < n; u++) { JSONObject j = load(new File(aiDir(c, id), "map_" + u + ".json")); if (j != null) l.add(j); }
        JSONObject m = LibText.mergeMap(l);
        try { m.put("done", l.size()).put("n", n); } catch (Exception ignored) {}
        store(new File(aiDir(c, id), "map.json"), m);
        return m;
    }

    // ================================================================ 📚 what to read next

    /** Free books by the authors he read (Open Library: no key, no money), not on his racks yet. */
    static JSONArray nextByAuthors(Context c, JSONArray authors) {
        JSONArray out = new JSONArray();
        java.util.Set<String> have = new java.util.HashSet<>();
        JSONArray sh = Library.shelf(c), st = Library.starter(c);
        for (int i = 0; i < sh.length(); i++) have.add(LibText.nameKey(sh.optJSONObject(i).optString("t")));
        for (int i = 0; i < authors.length() && i < 4; i++) {
            String a = authors.optString(i, "").trim();
            if (a.isEmpty()) continue;
            try {
                JSONArray docs = Library.getJson("https://openlibrary.org/search.json?limit=15&fields=key,title,author_name,first_publish_year,"
                        + "id_project_gutenberg,id_standard_ebooks,ebook_access,cover_i,language,subject&author=" + java.net.URLEncoder.encode(a, "UTF-8")).optJSONArray("docs");
                int got = 0;
                for (int k = 0; docs != null && k < docs.length() && got < 4; k++) {
                    JSONObject o = LibText.fromOpenLibrary(docs.getJSONObject(k));
                    if (!o.optBoolean("free") || have.contains(LibText.nameKey(o.optString("t")))) continue;
                    have.add(LibText.nameKey(o.optString("t")));
                    out.put(o.put("why", a + " రాసినది (మీరు చదివిన రచయిత)"));
                    got++;
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** ✨ The AI's suggestions from the books he read (only their titles go): [{t, a, why}]. */
    static JSONArray nextByAi(Context c, JSONArray titles) throws Exception {
        Workshop.Engine e = ready(c);
        double[] rs = {0};
        StringBuilder u = new StringBuilder("Books he read and liked:\n");
        for (int i = 0; i < titles.length() && i < 20; i++) u.append("- ").append(LibText.cut(titles.optString(i), 160)).append('\n');
        JSONObject j = LibText.jsonBlock(ask(c, e, NEXT_SYS, u.toString(), 1400, null, rs));
        JSONArray b = j == null ? null : j.optJSONArray("b"), out = new JSONArray();
        for (int i = 0; b != null && i < b.length() && out.length() < 8; i++) {
            JSONObject x = b.optJSONObject(i);
            if (x == null || x.optString("t").trim().isEmpty()) continue;
            out.put(new JSONObject().put("t", LibText.cut(x.optString("t").trim(), 160)).put("a", LibText.cut(x.optString("a").trim(), 100))
                    .put("why", LibText.cut(x.optString("why").trim(), 240)));
        }
        Workshop.spendBooks(c, 0, true);
        if (out.length() == 0) throw new IOException("AI సూచనలు రాలేదు (ఖర్చు " + Workshop.money(rs[0]) + ")");
        return out;
    }

    static double nextQuote(Context c) { return rupees(Library.model(new Prefs(c)), need(c, "next", "", 0, null)); }

    // ================================================================ 🏠 his home bookshelf from one photo (never kept)

    static double shelfQuote(Context c) { return rupees(Library.model(new Prefs(c)), need(c, "shelf", "", 0, null)); }

    /** The titles the AI reads on his shelf: [{t, a}]. The photo goes only to his library AI for this, and is dropped. */
    static JSONArray shelfRead(Context c, String jpegB64) throws Exception {
        Workshop.Engine e = ready(c);
        StringBuilder sb = new StringBuilder();
        Workshop.Bill bill = new Workshop.Bill();
        try { Workshop.stream(e, SHELF_SYS, "Read the books on this shelf.", jpegB64, 2000, sb::append, bill); }
        finally { Workshop.spendBooks(c, bill.rupees, true); }
        JSONObject j = LibText.jsonBlock(sb.toString());
        JSONArray b = j == null ? null : j.optJSONArray("b"), out = new JSONArray();
        for (int i = 0; b != null && i < b.length() && out.length() < 60; i++) {
            JSONObject x = b.optJSONObject(i);
            if (x == null || x.optString("t").trim().length() < 2) continue;
            out.put(new JSONObject().put("t", LibText.cut(x.optString("t").trim(), 160)).put("a", LibText.cut(x.optString("a").trim(), 100)));
        }
        if (out.length() == 0) throw new IOException("ఫోటోలో పుస్తకాల పేర్లు చదవలేకపోయింది Sir (ఖర్చు " + Workshop.money(bill.rupees) + "). దగ్గరగా, వెలుతురులో మళ్ళీ తీయండి.");
        return out;
    }

    /** The books he ticked, on his racks as 🏠 (details from Open Library when found; a free copy can be fetched later). */
    static int homeAdd(Context c, JSONArray list) throws Exception {
        int n = 0;
        for (int i = 0; i < list.length() && i < 60; i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            String t = LibText.cut(x.optString("t").trim(), 160), a = LibText.cut(x.optString("a").trim(), 100);
            if (t.length() < 2) continue;
            JSONObject b = new JSONObject().put("t", t).put("a", a).put("home", true).put("sec", "mine").put("era", "now").put("lang", t.matches("(?s).*[\\u0C00-\\u0C7F].*") ? "te" : "en");
            try {
                JSONArray docs = Library.getJson("https://openlibrary.org/search.json?limit=5&fields=key,title,author_name,first_publish_year,id_project_gutenberg,"
                        + "id_standard_ebooks,ebook_access,cover_i,language,subject&q=" + java.net.URLEncoder.encode(t + (a.isEmpty() ? "" : " " + a), "UTF-8")).optJSONArray("docs");
                JSONObject best = null;
                double bs = 0;
                for (int k = 0; docs != null && k < docs.length(); k++) {
                    JSONObject o = LibText.fromOpenLibrary(docs.getJSONObject(k));
                    double s = LibText.score(t, o.optString("t"), "") + 0.3 * LibText.score(a, o.optString("a"), "");
                    if (s > bs) { bs = s; best = o; }
                }
                if (best != null && bs >= 0.6) {
                    for (String key : new String[]{"y", "ol", "sec", "era", "cover_url", "summary", "lang"}) if (best.has(key)) b.put(key, best.get(key));
                    if ("pg".equals(best.optString("src")) || "se".equals(best.optString("src"))) b.put("src", best.optString("src")).put("src_id", best.optString("src_id")).put("free", true);
                }
            } catch (Exception ignored) {}
            if (!b.has("src")) b.put("src", "home");
            b.put("id", LibText.idFrom("h", LibText.nameKey(t) + "|" + LibText.nameKey(a))).put("state", "none").put("at", System.currentTimeMillis());
            if (Library.book(c, b.optString("id")) != null) continue;
            String cover = b.optString("cover_url", "");
            if (cover.startsWith("https://")) {
                try { byte[] img = Library.getCover(cover, 1_500_000); if (img.length > 500) { Files.write(Library.coverFile(c, b.optString("id")).toPath(), img); b.put("cover", true); } } catch (Exception ignored) {}
            }
            Library.put(c, b);
            n++;
        }
        return n;
    }
}
