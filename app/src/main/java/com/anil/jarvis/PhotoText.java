package com.anil.jarvis;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
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

/**
 * 📷 B7 (10 Nov): finding his photos by the words printed on them ("బైక్ ఇన్సూరెన్స్ ఫోటో చూపించు", a bill, a ticket, a
 * certificate) — read on the phone with ML Kit (his choice "ఫోన్‌లోనే": no photo goes anywhere). The reading happens a few
 * at a time while the phone charges, newest first, and is kept as words only in files/photo_text.idx (left out of every
 * backup). Long runs of digits are never kept (phone, account, card, ID, policy numbers), nor letter-and-number codes
 * (PAN, vehicle, serial), only plain words and short numbers (a year, an amount). English print only (ML Kit's Latin).
 */
final class PhotoText {
    private PhotoText() {}

    static final String FILE = "photo_text.idx", PART = "photo_text.idx.part";
    /** Words kept per photo at most. */
    static final int MAX_WORDS = 160;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The index in memory: photo id → its words (loaded once, kept up to date by the reader). */
    private static Map<Long, String[]> index;

    static boolean on(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("photo_text", true); }

    // ================================================================ the words kept (pure)

    /**
     * The words worth keeping from a photo's printed text: small letters, each once, 3+ letters, numbers of up to 6 digits;
     * never a longer run of digits (even split by spaces / dashes: 9876 543 210 is one phone number) or a code mixing letters
     * and digits 6+ long. Pure.
     */
    static List<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(out);
        // runs of digits joined by spaces / dashes / dots first: a long one is a number to drop whole
        String t = text.toLowerCase(Locale.ROOT).replaceAll("(?<=\\d)[ \\-./](?=\\d)", "");
        for (String w : t.split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty()) continue;
            boolean digits = w.matches("\\d+"), letters = w.matches("\\p{L}+");
            if (digits) { if (w.length() <= 6) out.add(w); continue; }
            if (letters) { if (w.codePointCount(0, w.length()) >= 3) out.add(w); continue; }
            if (w.length() < 6) out.add(w); // (mixed, short: "4g", "a4")
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

    /** id \t taken (ms) \t words (space-separated); a photo read with no words keeps its line too (not read again). */
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
        index = m;
        return m;
    }

    private static synchronized void append(Context c, List<String> lines, Map<Long, String[]> add) {
        if (lines.isEmpty()) return;
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
    }

    static boolean isIndexFile(String name) { return FILE.equals(name) || PART.equals(name); }

    // ================================================================ reading while it charges

    static boolean allowed(Context c) {
        String perm = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : android.Manifest.permission.READ_EXTERNAL_STORAGE; // (the 13+ name as text: same value)
        return c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static boolean charging(Context c) {
        try {
            Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int plug = b == null ? 0 : b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            return plug != 0;
        } catch (Exception e) { return false; }
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
        if (!allowed(c) || !charging(c) || new Prefs(c).homeMode() || CarBox.on(c)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { readSome(c, 4 * 60_000L); } catch (Throwable ignored) {} finally { RUNNING.set(false); }
        }, "photo-text").start();
    }

    private static void readSome(Context c, long budgetMs) {
        long until = System.currentTimeMillis() + budgetMs;
        Map<Long, String[]> idx = load(c, null);
        Set<Long> alive = new java.util.HashSet<>();
        List<long[]> todo = new ArrayList<>(); // {id, taken}
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED},
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                long id = cur.getLong(0), taken = cur.isNull(1) ? cur.getLong(2) * 1000 : cur.getLong(1);
                alive.add(id);
                if (!idx.containsKey(id)) todo.add(new long[]{id, taken});
            }
        } catch (Exception e) { return; }
        if (idx.size() > alive.size() + 200) prune(c, alive); // (many deleted since: their words go)
        List<String> lines = new ArrayList<>();
        Map<Long, String[]> add = new HashMap<>();
        for (long[] p : todo) {
            if (System.currentTimeMillis() > until || !charging(c) || !on(c)) break;
            Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p[0]);
            List<String> w = words(readText(c, u));
            lines.add(p[0] + "\t" + p[1] + "\t" + String.join(" ", w));
            add.put(p[0], w.toArray(new String[0]));
            if (lines.size() >= 20) { append(c, lines, add); lines.clear(); add.clear(); }
        }
        append(c, lines, add);
    }

    /** One photo's printed text, read on the phone ("" when none or not readable). */
    private static String readText(Context c, Uri u) {
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
            try { return OfflineRead.text(b); } finally { b.recycle(); }
        } catch (Throwable e) {
            return "";
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
