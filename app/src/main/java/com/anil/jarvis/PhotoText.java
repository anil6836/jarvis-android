package com.anil.jarvis;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.mlkit.vision.text.TextRecognizer;

/**
 * 📷 B7 (10 Nov): finding his photos by the words printed on them ("బైక్ ఇన్సూరెన్స్ ఫోటో చూపించు", a bill, a ticket, a
 * certificate) — read on the phone with ML Kit (his choice "ఫోన్‌లోనే": no photo goes anywhere). The reading happens a few
 * at a time while the phone charges, newest first, and is kept as words only in files/photo_text.idx (left out of every
 * backup). Numbers of 4+ digits are never kept, even split by spaces / dashes / commas / lines (phone, account, card, ID,
 * policy numbers, OTPs, amounts) — only a year (19xx / 20xx) — nor the word after OTP / CVV / PIN / password, nor letter-and-
 * number codes (PAN, vehicle, serial). English print only (ML Kit's Latin). It reads only with the screen off, on the charger,
 * while the battery is below 40 °C.
 */
final class PhotoText {
    private PhotoText() {}

    static final String FILE = "photo_text.idx", PART = "photo_text.idx.part";
    /** Words kept per photo at most. */
    static final int MAX_WORDS = 160;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The index in memory: photo id → its words (loaded once, kept up to date by the reader). */
    private static Map<Long, String[]> index;
    /** Bumped by {@link #clear}: a reading started before it never writes after it. */
    private static int gen;

    static boolean on(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("photo_text", true); }

    // ================================================================ the words kept (pure)

    /**
     * The words worth keeping from a photo's printed text: small letters, each once, words of 3+ letters, numbers of up to 3
     * digits and years (19xx / 20xx). Never a number of 4+ digits, even split by spaces / dashes / dots / commas / colons / lines
     * (98765 - 43210, 1234 5678 9012, 1,250 are whole numbers), never the word after OTP / CVV / PIN / MPIN / password
     * (whatever it is), never a code mixing letters and digits 6+ long. Pure.
     */
    static List<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(out);
        // runs of digits joined across spaces / dashes / dots / commas / colons / line breaks first: a long one is a number to drop whole
        String t = text.toLowerCase(Locale.ROOT).replaceAll("(?<=\\d)[\\s\\-\u2013./,:]+(?=\\d)", "");
        int secret = 0; // (tokens still to look past after "otp" / "cvv" / …)
        for (String w : t.split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.isEmpty()) continue;
            if (SECRET.contains(w)) { secret = 3; continue; }
            if (secret > 0) { // the code itself goes, and the little words before it ("otp is 4829")
                secret = FILLER.contains(w) ? secret - 1 : 0;
                continue;
            }
            boolean digits = w.matches("\\d+"), letters = w.matches("[\\p{L}\\p{M}]+");
            if (digits) { if (w.length() <= 3 || w.length() == 4 && (w.startsWith("19") || w.startsWith("20"))) out.add(w); continue; }
            if (letters) { if (w.codePointCount(0, w.length()) >= 3) out.add(w); continue; }
            if (w.length() < 6 && w.replaceAll("\\D", "").length() <= 3) out.add(w); // (mixed, short: "4g", "a4")
            if (out.size() >= MAX_WORDS) break;
        }
        List<String> l = new ArrayList<>(out);
        return l.size() > MAX_WORDS ? l.subList(0, MAX_WORDS) : l;
    }

    /** His search words the same way (small letters, no long numbers), each 2+ letters. Pure. */
    static List<String> query(String said) {
        List<String> out = new ArrayList<>();
        for (String w : words(said)) if (!STOP.contains(w)) out.add(w);
        return out;
    }

    private static final Set<String> SECRET = new java.util.HashSet<>(java.util.Arrays.asList("otp", "cvv", "cvc", "pin", "mpin", "tpin", "upipin", "password", "passcode", "pwd"));
    private static final Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList("is", "no", "num", "number", "code", "your", "the", "for", "to"));
    private static final Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList("the", "and", "photo", "photos", "picture", "image", "of", "for", "with", "my"));

    /**
     * How well a photo's words fit the search: the number of search words found (a word matches a kept word that starts with it,
     * so "insur" finds "insurance"; 4+ letters for that). Pure.
     */
    static int score(String[] kept, List<String> q) {
        int n = 0;
        for (String w : q) {
            for (String k : kept) {
                if (k.equals(w) || (w.length() >= 4 && k.startsWith(w))) { n++; break; }
            }
        }
        return n;
    }

    /** The best photos for the search: all the words found first, else most of them (at least half); newest first among equals. Pure. */
    static List<Long> best(Map<Long, String[]> idx, Map<Long, Long> taken, List<String> q, int max) {
        List<Long> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        int need = q.size(), floor = Math.max(1, (q.size() + 1) / 2);
        for (int want = need; want >= floor && out.isEmpty(); want--) {
            List<Long> hit = new ArrayList<>();
            for (Map.Entry<Long, String[]> e : idx.entrySet()) if (score(e.getValue(), q) >= want) hit.add(e.getKey());
            hit.sort((a, b) -> Long.compare(taken.getOrDefault(b, 0L), taken.getOrDefault(a, 0L)));
            out.addAll(hit.size() > max ? hit.subList(0, max) : hit);
        }
        return out;
    }

    // ================================================================ the index file

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    /**
     * id \t taken (ms) \t words (space-separated); a photo read with no words keeps its line too (not read again). With a
     * {@code taken} map (the search): a fresh copy read from the file, the shared index left alone.
     */
    static synchronized Map<Long, String[]> load(Context c, Map<Long, Long> taken) {
        if (index != null && taken == null) return index;
        Map<Long, String[]> m = new HashMap<>();
        File f = file(c);
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", 3);
                    if (p.length < 2) continue;
                    try {
                        long id = Long.parseLong(p[0]);
                        m.put(id, p.length > 2 && !p[2].isEmpty() ? p[2].split(" ") : new String[0]);
                        if (taken != null) taken.put(id, Long.parseLong(p[1]));
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }
        if (taken == null) index = m;
        return m;
    }

    private static synchronized void append(Context c, List<String> lines, Map<Long, String[]> add, int started) {
        if (lines.isEmpty() || started != gen || !on(c)) return; // (switched off / cleared meanwhile: nothing written back)
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            for (String l : lines) w.write(l + "\n");
        } catch (Exception ignored) {}
        if (index != null) index.putAll(add);
    }

    /** Photos gone from the phone: their lines go too (once in a while). */
    private static synchronized void prune(Context c, Set<Long> alive) {
        File f = file(c), part = new File(c.getFilesDir(), PART);
        if (!f.isFile()) return;
        int dropped = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
             Writer w = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                long id;
                try { id = Long.parseLong(tab < 0 ? line : line.substring(0, tab)); } catch (NumberFormatException e) { dropped++; continue; }
                if (alive.contains(id)) w.write(line + "\n"); else dropped++;
            }
        } catch (Exception e) { //noinspection ResultOfMethodCallIgnored
            part.delete(); return; }
        if (dropped == 0 || !part.renameTo(f)) { //noinspection ResultOfMethodCallIgnored
            part.delete(); return; }
        index = null;
    }

    /** Forget everything read (his switch off, or 🗑️). */
    static synchronized void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), PART).delete();
        index = null;
        gen++;
        runs(c).edit().clear().apply();
    }

    private static synchronized int gen() { return gen; }

    static boolean isIndexFile(String name) { return FILE.equals(name) || PART.equals(name); }

    // ================================================================ reading while it charges

    /** The permission names (as text: the 13+ / 14+ names are the same values). */
    static final String ALL = "android.permission.READ_MEDIA_IMAGES", SOME = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";

    static String[] perms() {
        if (Build.VERSION.SDK_INT >= 34) return new String[]{ALL, SOME};
        return new String[]{Build.VERSION.SDK_INT >= 33 ? ALL : android.Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** Any photos it may read: all of them, or (Android 14 "Select photos") the ones he picked. */
    static boolean allowed(Context c) { return allowedAll(c) || Build.VERSION.SDK_INT >= 34 && granted(c, SOME); }

    static boolean allowedAll(Context c) { return granted(c, Build.VERSION.SDK_INT >= 33 ? ALL : android.Manifest.permission.READ_EXTERNAL_STORAGE); }

    private static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    /** On the charger, the screen off (he isn't using it) and the battery below 40 °C: the only time it reads. */
    private static boolean goodTime(Context c) { return whyNot(c) == null; }

    /** Null when it may read now; else why not: "" not on the charger, "screen" the screen is on, "hot:41" the battery's °C. */
    private static String whyNot(Context c) {
        try {
            Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null || b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) == 0) return "";
            int t = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 999); // (tenths of a °C)
            if (t >= 400) return "hot:" + t / 10;
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            return pm != null && !pm.isInteractive() ? null : "screen";
        } catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- what the 📷 line in Settings says about the last tries

    static final String RUNS = "jarvis_photo_text_runs";

    private static SharedPreferences runs(Context c) { return c.getSharedPreferences(RUNS, Context.MODE_PRIVATE); }

    /** Settings' line: when it last read and how many, or (never yet) why it couldn't on the charger. */
    static String status(Context c) {
        SharedPreferences s = runs(c);
        return statusText(s.getLong("last_at", 0), s.getInt("last_n", 0), s.getBoolean("last_fail", false), s.getString("skip", ""), s.getLong("skip_at", 0));
    }

    /** Pure (desk-tested). */
    static String statusText(long lastAt, int lastN, boolean lastFail, String skip, long skipAt) {
        String why = skip == null ? "" : skip.startsWith("hot:") ? "ఫోన్ " + skip.substring(4) + "°C వేడిగా ఉంది (40 కంటే తక్కువ అయ్యాక చదువుతాడు)"
                : "screen".equals(skip) ? "ఛార్జింగ్‌లో ఉన్నప్పుడు స్క్రీన్ ఆన్‌లో ఉంది" : "";
        String fail = lastFail ? " · ఫోటోలు చదవడం కుదరలేదు, మళ్ళీ ప్రయత్నిస్తాడు" : "";
        if (lastAt > 0) {
            String when = java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
                    .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(lastAt), java.time.ZoneId.systemDefault()));
            String later = !why.isEmpty() && skipAt > lastAt ? "\nతర్వాత ఆగింది: " + why : "";
            return "🕘 చివరిగా చదివింది: " + when + " (" + lastN + " ఫోటోలు)" + fail + later;
        }
        if (!why.isEmpty()) return "🕘 ఇంకా మొదలవలేదు: " + why;
        return "🕘 ఇంకా మొదలవలేదు: ఛార్జర్ పెట్టి స్క్రీన్ ఆఫ్ చేసిన తర్వాత 15 నిమిషాల్లో మొదలవుతుంది";
    }

    /** {photos on the phone, read so far}. */
    static int[] progress(Context c) {
        int all = 0;
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            if (cur != null) all = cur.getCount();
        } catch (Exception ignored) {}
        return new int[]{all, Math.min(all, load(c, null).size())};
    }

    /** From the regular check: while it charges, a few minutes of reading the photos not read yet (newest first). */
    static void tick(Context c) {
        if (!on(c)) { if (file(c).isFile()) clear(c); return; } // (his switch off: what was read is forgotten)
        if (!allowed(c) || new Prefs(c).homeMode() || CarBox.on(c)) return;
        String why = whyNot(c);
        if (why != null) { // (on the charger but not now: the reason kept for Settings' line)
            if (!why.isEmpty()) runs(c).edit().putString("skip", why).putLong("skip_at", System.currentTimeMillis()).apply();
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        // With the screen off the phone sleeps a few seconds after the alarm unless something holds it awake: held only for
        // this run (on the charger, at most 5 minutes), let go as soon as it ends.
        android.os.PowerManager.WakeLock wl = null;
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null) {
                wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:photo-text");
                wl.setReferenceCounted(false);
                wl.acquire(5 * 60_000L);
            }
        } catch (Exception ignored) {}
        final android.os.PowerManager.WakeLock lock = wl;
        try {
            new Thread(() -> {
                try {
                    int[] r = readSome(c, 4 * 60_000L);
                    if (r != null) runs(c).edit().putLong("last_at", System.currentTimeMillis()).putInt("last_n", r[0]).putBoolean("last_fail", r[1] != 0).apply();
                } catch (Throwable ignored) {
                } finally {
                    RUNNING.set(false);
                    try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
                }
            }, "photo-text").start();
        } catch (Throwable e) {
            RUNNING.set(false);
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        }
    }

    /** Reads for up to budgetMs; {photos read, 1 when the reader kept failing} — null when there was nothing to read. */
    private static int[] readSome(Context c, long budgetMs) {
        long until = System.currentTimeMillis() + budgetMs;
        int started = gen();
        Map<Long, String[]> idx = load(c, null);
        Set<Long> alive = new java.util.HashSet<>();
        List<long[]> todo = new ArrayList<>(); // {id, taken}
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED},
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                long id = cur.getLong(0), taken = cur.isNull(1) ? cur.getLong(2) * 1000 : cur.getLong(1);
                alive.add(id);
                if (!idx.containsKey(id)) todo.add(new long[]{id, taken});
            }
        } catch (Exception e) { return null; }
        if (idx.size() > alive.size() + 200) prune(c, alive); // (many deleted since: their words go)
        if (todo.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        Map<Long, String[]> add = new HashMap<>();
        TextRecognizer r = OfflineRead.recognizer(); // (one for the whole run)
        int read = 0, failed = 0;
        try {
            for (long[] p : todo) {
                if (System.currentTimeMillis() > until || !goodTime(c) || !on(c) || started != gen()) break;
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p[0]);
                String text = readText(c, r, u);
                if (text == null) { if (++failed >= 3) break; continue; } // (the reader failing: not remembered as read — tried again next time)
                failed = 0;
                read++;
                List<String> w = words(text);
                lines.add(p[0] + "\t" + p[1] + "\t" + String.join(" ", w));
                add.put(p[0], w.toArray(new String[0]));
                if (lines.size() >= 20) { append(c, lines, add, started); lines.clear(); add.clear(); }
            }
            append(c, lines, add, started);
        } finally {
            try { r.close(); } catch (Exception ignored) {}
        }
        return new int[]{read, failed >= 3 ? 1 : 0};
    }

    /** One photo's printed text, read on the phone: "" when it has none or isn't a readable picture, null when the reading failed. */
    private static String readText(Context c, TextRecognizer r, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return "";
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            Bitmap b;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, d); }
            if (b == null) return "";
            try { return OfflineRead.text(r, b); } finally { b.recycle(); }
        } catch (Throwable e) {
            return ""; // (gone, or not a picture it can open: not a reader failure to try again — that one is null from the reader)
        }
    }

    // ================================================================ the search

    /** The photos whose printed words fit the search (newest first), as content addresses. */
    static List<Uri> find(Context c, String said, int max) {
        Map<Long, Long> taken = new HashMap<>();
        Map<Long, String[]> idx = load(c, taken);
        List<Uri> out = new ArrayList<>();
        for (long id : best(idx, taken, query(said), max)) out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        return out;
    }
}
