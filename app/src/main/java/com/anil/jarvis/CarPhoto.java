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

    // ================================================================ Android: keeping the photo, reading it back

    static File file(Context c) { return new File(c.getFilesDir(), FILE); }

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
            return ok && tmp.renameTo(new File(c.getFilesDir(), name));
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
            int[] cr = crop(full.getWidth(), full.getHeight(), m);
            int cw = cr[2] - cr[0], ch = cr[3] - cr[1];
            float k = Math.min(1f, 900f / cw);
            Bitmap part = Bitmap.createBitmap(full, cr[0], cr[1], cw, ch);
            if (k < 1) { Bitmap s = Bitmap.createScaledBitmap(part, Math.max(1, Math.round(cw * k)), Math.max(1, Math.round(ch * k)), true); if (s != part) part.recycle(); part = s; }
            float[] at = inCrop(full.getWidth(), full.getHeight(), m, cr, k);
            if (part != full) full.recycle();
            return new Object[]{part, at, clear};
        } catch (Throwable e) {
            return null;
        }
    }
}
