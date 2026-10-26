package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 📷 His own photo of his own car for the car screen (Settings → 🚙 కారు: మన బొమ్మ / నా ఫోటో): kept only in the
 * app's folder on the box (never in a backup, never sent anywhere), with the five places he marked on it once —
 * the two wheels' centres, the wheels' size, the headlight and the tail light — so the screen can turn his own
 * wheels and light his own lamps. The maths here needs no Android (desk-tested).
 */
final class CarPhoto {
    private CarPhoto() {}

    static final String FILE = "car_photo.jpg";

    /** Marks, as fractions of the photo: front wheel x, y; rear wheel x, y; the wheels' radius (of the width); headlight x, y; tail light x, y. */
    static final int FX = 0, FY = 1, RX = 2, RY = 3, R = 4, HX = 5, HY = 6, TX = 7, TY = 8, N = 9;

    static String format(float[] m) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < N; i++) { if (i > 0) b.append(','); b.append(String.format(Locale.ROOT, "%.5f", m[i])); }
        return b.toString();
    }

    /** The stored marks, or null when missing / broken. Pure. */
    static float[] parse(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length != N) return null;
        float[] m = new float[N];
        try {
            for (int i = 0; i < N; i++) {
                m[i] = Float.parseFloat(p[i].trim());
                if (Float.isNaN(m[i]) || m[i] < 0 || m[i] > 1) return null;
            }
        } catch (Exception e) { return null; }
        if (m[R] <= 0.005f || Math.abs(m[FX] - m[RX]) < 0.05f) return null; // (no size, or both wheels in one place)
        return m;
    }

    /** A first guess of the wheels' radius (of the width) from where the two centres are: about 0.13 of the wheelbase. Pure. */
    static float guessRadius(float fx, float fy, float rx, float ry, float aspect /* height / width */) {
        float dx = fx - rx, dy = (fy - ry) * aspect;
        return (float) Math.sqrt(dx * dx + dy * dy) * 0.13f;
    }

    /**
     * The part of the photo the screen shows: the car with a little room round it — from beyond the tail light to
     * beyond the headlight, from just under the tyres up to about the car's length × 0.6 — clamped to the photo.
     * Returns {left, top, right, bottom} in pixels. Pure.
     */
    static int[] crop(int w, int h, float[] m) {
        float fx = m[FX] * w, rx = m[RX] * w, hx = m[HX] * w, tx = m[TX] * w, rad = m[R] * w;
        float fy = m[FY] * h, ry = m[RY] * h;
        float minX = Math.min(Math.min(fx, rx), Math.min(hx, tx)), maxX = Math.max(Math.max(fx, rx), Math.max(hx, tx));
        float len = Math.max(1, maxX - minX), pad = len * 0.06f + rad * 0.2f;
        float bottom = Math.max(fy, ry) + rad * 1.2f, top = bottom - len * 0.62f;
        int l = clamp(Math.round(minX - pad), 0, w - 1), r = clamp(Math.round(maxX + pad), l + 1, w);
        int b = clamp(Math.round(bottom), 1, h), t = clamp(Math.round(top), 0, b - 1);
        return new int[]{l, t, r, b};
    }

    /** The marks in the cropped (and scaled by k) picture's pixels: {fx, fy, rx, ry, r, hx, hy, tx, ty}. Pure. */
    static float[] inCrop(int w, int h, float[] m, int[] c, float k) {
        return new float[]{(m[FX] * w - c[0]) * k, (m[FY] * h - c[1]) * k, (m[RX] * w - c[0]) * k, (m[RY] * h - c[1]) * k, m[R] * w * k,
                (m[HX] * w - c[0]) * k, (m[HY] * h - c[1]) * k, (m[TX] * w - c[0]) * k, (m[TY] * h - c[1]) * k};
    }

    private static int clamp(int v, int a, int b) { return v < a ? a : v > b ? b : v; }

    // ================================================================ 🚗 the rear photo (the chase view while he drives)

    static final String REAR = "car_rear.jpg";
    /** Rear marks, as fractions of the photo: left tail light x, y; right tail light x, y; the top brake light x, y (-1, -1: none). */
    static final int LX = 0, LY = 1, QX = 2, QY = 3, TOPX = 4, TOPY = 5, RN = 6;

    static String formatRear(float[] m) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < RN; i++) { if (i > 0) b.append(','); b.append(String.format(Locale.ROOT, "%.5f", m[i])); }
        return b.toString();
    }

    /** The stored rear marks, or null. The top light may be (-1, -1). Pure. */
    static float[] parseRear(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length != RN) return null;
        float[] m = new float[RN];
        try {
            for (int i = 0; i < RN; i++) {
                m[i] = Float.parseFloat(p[i].trim());
                boolean top = i >= TOPX;
                if (Float.isNaN(m[i]) || m[i] > 1 || m[i] < (top ? -1 : 0)) return null;
                if (top && m[i] < 0 && m[i] != -1) return null;
            }
        } catch (Exception e) { return null; }
        if (m[QX] - m[LX] < 0.15f) return null; // (the right light must be right of the left one, and apart)
        if ((m[TOPX] < 0) != (m[TOPY] < 0)) return null;
        return m;
    }

    /** The part of a rear photo shown: the car round its two tail lights, clamped. {left, top, right, bottom} px. Pure. */
    static int[] rearCrop(int w, int h, float[] m) {
        float lx = m[LX] * w, rx = m[QX] * w, ly = Math.min(m[LY], m[QY]) * h, low = Math.max(m[LY], m[QY]) * h, span = Math.max(1, rx - lx);
        float top = ly - span * 0.35f;
        if (m[TOPY] >= 0) top = Math.min(top, m[TOPY] * h);
        top -= span * 0.08f;
        int l = clamp(Math.round(lx - span * 0.14f), 0, w - 1), r = clamp(Math.round(rx + span * 0.14f), l + 1, w);
        int b = clamp(Math.round(low + span * 0.64f), 1, h), t = clamp(Math.round(top), 0, b - 1);
        return new int[]{l, t, r, b};
    }

    /** Rear marks in the cropped (and scaled by k) picture's pixels; the top light stays (-1, -1) when none. Pure. */
    static float[] rearInCrop(int w, int h, float[] m, int[] c, float k) {
        boolean top = m[TOPX] >= 0;
        return new float[]{(m[LX] * w - c[0]) * k, (m[LY] * h - c[1]) * k, (m[QX] * w - c[0]) * k, (m[QY] * h - c[1]) * k,
                top ? (m[TOPX] * w - c[0]) * k : -1, top ? (m[TOPY] * h - c[1]) * k : -1};
    }

    static File rearFile(Context c) { return new File(c.getFilesDir(), REAR); }

    static float[] rearMarks(Prefs prefs) { return parseRear(prefs.sp.getString("car_rear_marks", "")); }

    /** The rear photo is there and marked. */
    static boolean rearReady(Context c, Prefs prefs) { return rearFile(c).exists() && rearMarks(prefs) != null; }

    /** While driving, the car seen from behind ("rear", the default once a rear photo is there) or from the side ("side"). */
    static boolean driveFromBehind(Context c, Prefs prefs) { return rearReady(c, prefs) && !"side".equals(prefs.sp.getString("car_drive_view", "rear")); }

    /** For the screen: {the rear photo's car part (≤ 700 px tall), the rear marks in it, a cut-out?}; null when off / unreadable. */
    static Object[] loadRear(Context c, Prefs prefs) {
        float[] m = rearMarks(prefs);
        File f = rearFile(c);
        if (m == null || !f.exists()) return null;
        try {
            Bitmap full = BitmapFactory.decodeFile(f.getPath());
            if (full == null) return null;
            boolean clear = cutout(full);
            int fw = full.getWidth(), fh = full.getHeight();
            int[] cr = rearCrop(fw, fh, m);
            int cw = cr[2] - cr[0], ch = cr[3] - cr[1];
            float k = Math.min(1f, 700f / ch);
            Bitmap part = Bitmap.createBitmap(full, cr[0], cr[1], cw, ch);
            if (k < 1) { Bitmap s = Bitmap.createScaledBitmap(part, Math.max(1, Math.round(cw * k)), Math.max(1, Math.round(ch * k)), true); if (s != part) part.recycle(); part = s; }
            float[] at = rearInCrop(fw, fh, m, cr, k);
            if (part != full) full.recycle();
            return new Object[]{part, at, clear};
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ Android: keeping the photo, reading it back

    static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    // ---- 🚙 ⑪ (26 Oct): his car from above, for the map (kept only on this box, never in a backup)
    static final String TOP = "car_top.jpg";

    static File topFile(Context c) { return new File(c.getFilesDir(), TOP); }

    /**
     * His photo from above for the map (off the main thread): turned so the car's nose is up (his "car_top_turn": the
     * nose was right / down / left), its clear margins cut off when it is a cut-out, at most 256 px long; null when none.
     */
    static Bitmap loadTop(Context c, Prefs prefs) {
        File f = topFile(c);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
            if (b == null) return null;
            if (cutout(b)) { // (the car alone: the clear part around it cut off, so the drawing is the car's own size)
                int[] box = opaqueBox(b);
                if (box != null && (box[2] - box[0] < b.getWidth() || box[3] - box[1] < b.getHeight())) {
                    Bitmap t = Bitmap.createBitmap(b, box[0], box[1], box[2] - box[0], box[3] - box[1]);
                    if (t != b) b.recycle();
                    b = t;
                }
            }
            int turn = topTurn(prefs.sp.getString("car_top_turn", "0"));
            float k = Math.min(1f, 256f / Math.max(b.getWidth(), b.getHeight()));
            if (k < 1 || turn != 0) {
                Matrix mx = new Matrix();
                mx.postScale(k, k);
                mx.postRotate(turn);
                Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                if (t != b) b.recycle();
                b = t;
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** How far to turn his photo so the nose is up: the nose was "0" up, "90" left (turned right), "180" down, "270" right. Pure. */
    static int topTurn(String s) { return "90".equals(s) ? 90 : "180".equals(s) ? 180 : "270".equals(s) ? 270 : 0; }

    /** The box round the picture's seen part (alpha ≥ 40), every 2nd pixel looked at; null when nothing seen. */
    private static int[] opaqueBox(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y += 2) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += 2) if ((row[x] >>> 24) >= 40) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
        }
        if (x1 < 0) return null;
        return new int[]{Math.max(0, x0 - 2), Math.max(0, y0 - 2), Math.min(w, x1 + 3), Math.min(h, y1 + 3)};
    }

    static float[] marks(Prefs prefs) { return parse(prefs.sp.getString("car_photo_marks", "")); }

    /** "photo" only when the photo and its marks are there; else the drawing. */
    static boolean on(Context c, Prefs prefs) { return "photo".equals(prefs.sp.getString("car_hero", "drawing")) && file(c).exists() && marks(prefs) != null; }

    /**
     * A picked picture kept in the app's own folder: turned upright (the camera's EXIF turn), at most maxPx on its long
     * side; a JPEG, or a PNG (same name) when it is a cut-out with a see-through background (the phone's "Save as
     * image" of the car alone), so the clear part stays clear. Off the main thread. True when saved.
     */
    static boolean keep(Context c, Uri uri, String name, int maxPx) {
        try {
            int turn = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                turn = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Throwable ignored) {}
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxPx * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return false;
            float k = Math.min(1f, (float) maxPx / Math.max(b.getWidth(), b.getHeight()));
            if (k < 1 || turn != 0) {
                Matrix mx = new Matrix();
                mx.postScale(k, k);
                mx.postRotate(turn);
                Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                if (t != b) b.recycle();
                b = t;
            }
            File tmp = new File(c.getFilesDir(), name + ".part");
            boolean ok, clear = cutout(b);
            try (FileOutputStream out = new FileOutputStream(tmp)) { ok = b.compress(clear ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, clear ? 100 : 88, out); }
            b.recycle();
            boolean done = ok && tmp.renameTo(new File(c.getFilesDir(), name));
            if (!done && !tmp.delete()) tmp.deleteOnExit(); // (no half-written file left behind)
            return done;
        } catch (Throwable e) {
            return false;
        }
    }

    /** A cut-out: the picture has a see-through background (its four corners are clear). */
    static boolean cutout(Bitmap b) {
        if (b == null || !b.hasAlpha()) return false;
        int w = b.getWidth() - 1, h = b.getHeight() - 1, clear = 0;
        int[][] at = {{0, 0}, {w, 0}, {0, h}, {w, h}, {w / 2, 0}};
        for (int[] p : at) if ((b.getPixel(p[0], p[1]) >>> 24) < 40) clear++;
        return clear >= 3;
    }

    /** For the screen: {the car's part of his photo (≤ 900 px wide), the marks in it, a cut-out?}; null when off or unreadable. */
    static Object[] load(Context c, Prefs prefs) {
        float[] m = marks(prefs);
        File f = file(c);
        if (m == null || !f.exists()) return null;
        try {
            Bitmap full = BitmapFactory.decodeFile(f.getPath());
            if (full == null) return null;
            boolean clear = cutout(full); // (before the cut: the full picture's corners)
            int fw = full.getWidth(), fh = full.getHeight(); // (kept: the full picture may be recycled below)
            int[] cr = crop(fw, fh, m);
            int cw = cr[2] - cr[0], ch = cr[3] - cr[1];
            float k = Math.min(1f, 900f / cw);
            Bitmap part = Bitmap.createBitmap(full, cr[0], cr[1], cw, ch);
            if (k < 1) { Bitmap s = Bitmap.createScaledBitmap(part, Math.max(1, Math.round(cw * k)), Math.max(1, Math.round(ch * k)), true); if (s != part) part.recycle(); part = s; }
            float[] at = inCrop(fw, fh, m, cr, k);
            if (part != full) full.recycle();
            return new Object[]{part, at, clear};
        } catch (Throwable e) {
            return null;
        }
    }
}
