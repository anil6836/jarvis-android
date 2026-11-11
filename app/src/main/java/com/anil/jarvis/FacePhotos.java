package com.anil.jarvis;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 👤 A (Anil, 12 Nov "A,B,C,D,E చేయి"): "అమ్మతో ఉన్న ఫోటోలు చూపించు". While the phone charges with the screen off (like the 📷
 * text reading, taking turns with it), faces in his photos are compared on the phone with the people HE introduced to Jarvis
 * (People: their face fingerprints). Only "this photo has అమ్మ" is kept; a face that is no one he introduced is never named,
 * kept or counted — its numbers are dropped at once. Out of every backup; switching it off, or a change in the people he
 * introduced (someone forgotten), clears it all.
 */
final class FacePhotos {
    private FacePhotos() {}

    static final String FILE = "photo_faces.idx", PART = FILE + ".part";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static int gen;

    static boolean on(Context c) { return new Prefs(c).sp.getBoolean("photo_faces", true); }

    static boolean busy() { return RUNNING.get(); }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_photo_faces", Context.MODE_PRIVATE); }

    static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    static boolean isIndexFile(String n) { return FILE.equals(n) || PART.equals(n); }

    /** Ready to look: his switch, photos allowed, at least one person introduced and the face model on the phone. */
    static boolean ready(Context c) { return on(c) && PhotoText.allowed(c) && People.count(c) > 0 && FaceNet.ready(c); }

    static synchronized void clear(Context c) {
        file(c).delete();
        new File(c.getFilesDir(), PART).delete();
        gen++;
        sp(c).edit().clear().apply();
    }

    /** The people he introduced, as one text: when it changes (someone added or forgotten) the photos are looked at again. */
    static String peopleKey(Context c) {
        StringBuilder b = new StringBuilder();
        for (People.Person p : People.all(c)) b.append(p.name).append(':').append(p.samples.size()).append(';');
        return b.toString();
    }

    // ---------------------------------------------------------------- the index: id \t taken \t names (| between)

    static synchronized Map<Long, String[]> load(Context c, Map<Long, Long> taken) {
        Map<Long, String[]> m = new HashMap<>();
        File f = file(c);
        if (!f.isFile()) return m;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                try {
                    long id = Long.parseLong(p[0]);
                    m.put(id, p[2].isEmpty() ? new String[0] : p[2].split("\\|"));
                    if (taken != null) taken.put(id, Long.parseLong(p[1]));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return m;
    }

    private static synchronized void append(Context c, List<String> lines, int started) {
        if (lines.isEmpty() || started != gen || !on(c)) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), StandardCharsets.UTF_8)) {
            for (String l : lines) w.write(l + "\n");
        } catch (Exception ignored) {}
    }

    /** {photos on the phone, looked at so far}. */
    static int[] progress(Context c) {
        int all = 0;
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            if (cur != null) all = cur.getCount();
        } catch (Exception ignored) {}
        return new int[]{all, Math.min(all, load(c, null).size())};
    }

    // ---------------------------------------------------------------- finding

    /** His word for a person → the name as introduced ("నేను" / "me" → himself); null when no one he introduced. Pure-ish. */
    static String known(List<People.Person> people, String said) {
        String s = said == null ? "" : said.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        for (People.Person p : people) if (p.owner && s.matches("(నేను|నన్ను|నా|me|myself|i|నాతో)")) return p.name;
        for (People.Person p : people) if (p.name.trim().toLowerCase(Locale.ROOT).equals(s)) return p.name;
        for (People.Person p : people) { String n = p.name.trim().toLowerCase(Locale.ROOT); if (n.length() >= 2 && (s.startsWith(n) || n.startsWith(s) && s.length() >= 2)) return p.name; }
        return null;
    }

    /** The photos with ALL these people in them, newest first (at most max). Pure. */
    static List<Long> match(Map<Long, String[]> idx, Map<Long, Long> taken, List<String> names, int max) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, String[]> e : idx.entrySet()) {
            Set<String> in = new LinkedHashSet<>(java.util.Arrays.asList(e.getValue()));
            if (!names.isEmpty() && in.containsAll(names)) out.add(e.getKey());
        }
        out.sort((a, b) -> Long.compare(taken.getOrDefault(b, 0L), taken.getOrDefault(a, 0L)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    static List<Uri> find(Context c, List<String> names, int max) {
        Map<Long, Long> taken = new HashMap<>();
        Map<Long, String[]> idx = load(c, taken);
        List<Uri> out = new ArrayList<>();
        for (long id : match(idx, taken, names, max)) out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        return out;
    }

    // ---------------------------------------------------------------- looking (on the charger, screen off)

    /** From Proactive's regular check, taking turns with the 📷 text reading (never both at once). */
    static void tick(Context c) {
        if (!on(c)) { if (file(c).isFile()) clear(c); return; }
        if (!ready(c) || PhotoText.busy() || new Prefs(c).homeMode() || CarBox.on(c) || !PhotoText.goodTimeNow(c)) return;
        String key = peopleKey(c);
        if (!key.equals(sp(c).getString("people", ""))) { clear(c); sp(c).edit().putString("people", key).apply(); } // (someone added / forgotten)
        if (!PhotoText.anyMissing(c, load(c, null).keySet())) return; // (all looked at: the turn left to 📷)
        if (!RUNNING.compareAndSet(false, true)) return;
        android.os.PowerManager.WakeLock wl = null;
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null) { wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:photo-faces"); wl.setReferenceCounted(false); wl.acquire(5 * 60_000L); }
        } catch (Exception ignored) {}
        final android.os.PowerManager.WakeLock lock = wl;
        try {
            new Thread(() -> {
                try {
                    int n = lookSome(c, 4 * 60_000L);
                    if (n >= 0) sp(c).edit().putLong("last_at", System.currentTimeMillis()).putInt("last_n", n).apply();
                } catch (Throwable ignored) {
                } finally {
                    RUNNING.set(false);
                    try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
                }
            }, "photo-faces").start();
        } catch (Throwable e) {
            RUNNING.set(false);
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        }
    }

    static long lastAt(Context c) { return sp(c).getLong("last_at", 0); }

    /** Photos looked at in this run; -1 when nothing was left to look at. */
    private static int lookSome(Context c, long budgetMs) throws Exception {
        long until = System.currentTimeMillis() + budgetMs;
        int started;
        synchronized (FacePhotos.class) { started = gen; }
        Map<Long, String[]> idx = load(c, null);
        List<long[]> todo = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED},
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return -1;
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                if (!idx.containsKey(id)) todo.add(new long[]{id, cur.isNull(1) ? cur.getLong(2) * 1000 : cur.getLong(1)});
            }
        }
        if (todo.isEmpty()) return -1;
        List<People.Person> people = People.all(c);
        FaceDetector det = FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.08f).build());
        int done = 0, failed = 0;
        List<String> lines = new ArrayList<>();
        try (FaceNet net = new FaceNet(c)) {
            for (long[] p : todo) {
                if (System.currentTimeMillis() > until || !PhotoText.goodTimeNow(c) || !on(c)) break;
                synchronized (FacePhotos.class) { if (started != gen) break; }
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p[0]);
                List<String> names = who(c, det, net, people, u);
                if (names == null) { if (++failed >= 3) break; continue; } // (the face finder failing: tried again next time)
                failed = 0;
                done++;
                lines.add(p[0] + "\t" + p[1] + "\t" + String.join("|", names));
                if (lines.size() >= 20) { append(c, lines, started); lines.clear(); }
            }
            append(c, lines, started);
        } finally {
            try { det.close(); } catch (Exception ignored) {}
        }
        return done;
    }

    /** The introduced people in one photo ([] none / no faces / not a picture); null when the face finder failed. */
    private static List<String> who(Context c, FaceDetector det, FaceNet net, List<People.Person> people, Uri u) {
        Bitmap b = upright(c, u, 1280);
        if (b == null) return new ArrayList<>();
        try {
            List<Face> faces;
            try { faces = Tasks.await(det.process(InputImage.fromBitmap(b, 0)), 15, TimeUnit.SECONDS); }
            catch (Exception e) { return null; }
            Set<String> out = new LinkedHashSet<>();
            int n = 0;
            for (Face f : faces) {
                if (++n > 12) break; // (a crowd: the first dozen faces)
                Rect box = f.getBoundingBox();
                if (Math.min(box.width(), box.height()) < 48) continue; // (too small to tell)
                Bitmap face = crop(b, box);
                if (face == null) continue;
                float[] e = net.embed(face);
                face.recycle();
                People.Match m = People.match(people, e); // (e: this face's numbers — used here and dropped, never kept)
                if (m.name != null) out.add(m.name);
            }
            return new ArrayList<>(out);
        } finally {
            b.recycle();
        }
    }

    private static Bitmap crop(Bitmap frame, Rect box) {
        int side = Math.min(Math.max(box.width(), box.height()), Math.min(frame.getWidth(), frame.getHeight()));
        if (side < 24) return null;
        int x = Math.max(0, Math.min(frame.getWidth() - side, box.centerX() - side / 2));
        int y = Math.max(0, Math.min(frame.getHeight() - side, box.centerY() - side / 2));
        Bitmap sq = Bitmap.createBitmap(frame, x, y, side, side);
        Bitmap out = Bitmap.createScaledBitmap(sq, FaceNet.IN, FaceNet.IN, true);
        if (sq != out) sq.recycle();
        return out;
    }

    /** The photo turned the way the camera meant (EXIF), at most maxSide; null when it can't be opened. */
    private static Bitmap upright(Context c, Uri u, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;
            int rot = 0;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) {
                int ori = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                rot = ori == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : ori == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {}
            if (rot == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (Throwable e) {
            return null;
        }
    }
}
