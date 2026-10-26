package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 📷 Stage 3 (27 Oct): "నా కారు (ఫోటోలు)" — his own car in the 3D view as his own photos, never a 3D model of it. Seven
 * places for his cut-out photos (the phone's Gallery: hold the car → "Save as image"): front, rear, left side, right
 * side, from above, rear-left corner, rear-right corner; the view shows the one nearest to where the camera is
 * ({@link Cam3D#pickPhoto}). Each is kept only in the app's folder on this box (never in a backup, the app or anywhere
 * else). The number plate is found by itself and shown as "NEXON" (his choice, the default), blurred, or as it is; the
 * tail lights are found by themselves for the brake / indicator / night glows. The finding is plain maths (desk-tested).
 */
final class CarPhotos3D {
    private CarPhotos3D() {}

    static final String[] SLOTS = Cam3D.SLOTS;
    static final String[] SLOT_NAMES = {"ముందు నుంచి", "వెనక నుంచి", "ఎడమ పక్క (కారు ఎడమ వైపు)", "కుడి పక్క (కారు కుడి వైపు)", "పై నుంచి",
            "వెనక-ఎడమ మూల నుంచి", "వెనక-కుడి మూల నుంచి"};
    /** The plate: "" NEXON (his choice), "blur", "asis". */
    static final String[] PLATES = {"", "blur", "asis"};
    static final String[] PLATE_NAMES = {"🔤 NEXON అని", "🌫️ మసకగా", "📷 ఫోటోలో ఉన్నట్టే"};

    static String fileName(int slot) { return "car3d_" + SLOTS[slot]; }

    static File file(Context c, int slot) { return new File(c.getFilesDir(), fileName(slot)); }

    /** Any of his 3D photos is there (the "నా కారు" choice can be offered). */
    static boolean any(Context c) {
        for (int i = 0; i < SLOTS.length; i++) if (i != 4 && file(c, i).exists()) return true;
        return CarPhoto.rearFile(c).exists();
    }

    // ================================================================ PNGs whose extra parts are broken

    /**
     * A PNG with only the parts a picture needs (its header, palette, see-through table, the picture itself, the end):
     * the phone's cut-outs carry extra parts (a broken eXIf, a caBX) some decoders refuse. Not a PNG: as it was. Pure.
     */
    static byte[] cleanPng(byte[] b) {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (b == null || b.length < 8) return b;
        for (int i = 0; i < 8; i++) if (b[i] != sig[i]) return b;
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        o.write(b, 0, 8);
        int p = 8;
        while (p + 12 <= b.length) {
            int len = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            if (len < 0 || p + 12 + len > b.length) break; // (cut short: what came before is kept)
            String type = new String(b, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            boolean keep = type.equals("IHDR") || type.equals("PLTE") || type.equals("tRNS") || type.equals("IDAT") || type.equals("IEND");
            if (keep) o.write(b, p, 12 + len);
            p += 12 + len;
            if (type.equals("IEND")) break;
        }
        return o.toByteArray();
    }

    // ================================================================ finding the plate and the lights (pure)

    /** The box round the picture's seen part (alpha ≥ 128): {x0, y0, x1, y1}; the whole picture when it has no clear parts. Pure. */
    static int[] seen(int[] px, int w, int h) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((px[y * w + x] >>> 24) >= 128) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
        }
        return x1 < 0 ? new int[]{0, 0, w, h} : new int[]{x0, y0, x1 + 1, y1 + 1};
    }

    static boolean white(int c) {
        if ((c >>> 24) < 128) return false;
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx > 170 && (mx - mn) < 0.24f * mx;
    }

    static boolean red(int c) {
        if ((c >>> 24) < 128) return false;
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 105 && r > g * 1.5f && r > b * 1.35f;
    }

    /** One joined patch: its pixel count, box and the four corner-most points (TL, TR, BR, BL). */
    static final class Patch {
        int n, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        final float[] q = new float[8];
        int sTL = Integer.MAX_VALUE, sBR = Integer.MIN_VALUE, dTR = Integer.MIN_VALUE, dBL = Integer.MIN_VALUE;
        long sx, sy;
        void add(int x, int y) {
            n++; sx += x; sy += y;
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
            if (x + y < sTL) { sTL = x + y; q[0] = x; q[1] = y; }
            if (x - y > dTR) { dTR = x - y; q[2] = x; q[3] = y; }
            if (x + y > sBR) { sBR = x + y; q[4] = x; q[5] = y; }
            if (y - x > dBL) { dBL = y - x; q[6] = x; q[7] = y; }
        }
        int bw() { return x1 - x0 + 1; }
        int bh() { return y1 - y0 + 1; }
    }

    /** The joined patches of a mask (4 neighbours), the small ones (under min pixels) left out. Pure. */
    static java.util.List<Patch> patches(boolean[] m, int w, int h, int min) {
        java.util.List<Patch> out = new java.util.ArrayList<>();
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            if (!m[i] || seen[i]) continue;
            Patch p = new Patch();
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int k = stack[--sp], x = k % w, y = k / w;
                p.add(x, y);
                if (x > 0 && m[k - 1] && !seen[k - 1]) { seen[k - 1] = true; stack[sp++] = k - 1; }
                if (x + 1 < w && m[k + 1] && !seen[k + 1]) { seen[k + 1] = true; stack[sp++] = k + 1; }
                if (y > 0 && m[k - w] && !seen[k - w]) { seen[k - w] = true; stack[sp++] = k - w; }
                if (y + 1 < h && m[k + w] && !seen[k + w]) { seen[k + w] = true; stack[sp++] = k + w; }
            }
            if (p.n >= min) out.add(p);
        }
        return out;
    }

    /** A mask grown then shrunk by r (closing: the letters' gaps inside a white plate filled). Pure. */
    static boolean[] close(boolean[] m, int w, int h, int r) {
        return grow(grow(m, w, h, r, true), w, h, r, false);
    }

    private static boolean[] grow(boolean[] m, int w, int h, int r, boolean on) {
        // separable box: rows, then columns (on: any within r → on; off: any off within r → off)
        boolean[] a = new boolean[w * h], b = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int cnt = 0;
            for (int x = -r; x < w; x++) {
                int add = x + r, rem = x - r - 1;
                if (add < w && m[y * w + add] == on) cnt++;
                if (rem >= 0 && m[y * w + rem] == on) cnt--;
                if (x >= 0) a[y * w + x] = on ? cnt > 0 : cnt == 0;
            }
        }
        for (int x = 0; x < w; x++) {
            int cnt = 0;
            for (int y = -r; y < h; y++) {
                int add = y + r, rem = y - r - 1;
                if (add < h && a[add * w + x] == on) cnt++;
                if (rem >= 0 && a[rem * w + x] == on) cnt--;
                if (y >= 0) b[y * w + x] = on ? cnt > 0 : cnt == 0;
            }
        }
        return b;
    }

    /**
     * The number plate in a car photo (pixels ARGB, w × h, best about 400–700 px wide): the white, filled, plate-shaped
     * patch in the car's lower half, centred-ish; its four corners (TL, TR, BR, BL) a little outside its edge, in the
     * picture's pixels; null when none looks like a plate (a side photo). Pure.
     */
    static float[] findPlate(int[] px, int w, int h) {
        int[] box = seen(px, w, h);
        int cw = box[2] - box[0], ch = box[3] - box[1];
        if (cw < 40 || ch < 20) return null;
        boolean[] m = new boolean[w * h];
        for (int i = 0; i < w * h; i++) m[i] = white(px[i]);
        int r = Math.max(1, Math.round(cw / 160f));
        m = close(m, w, h, r);
        Patch best = null;
        float bestScore = 0;
        for (Patch p : patches(m, w, h, Math.max(12, cw * ch / 3000))) {
            float aspect = p.bw() / (float) p.bh(), fill = p.n / (float) (p.bw() * p.bh());
            float cx = ((p.x0 + p.x1) / 2f - box[0]) / cw, cy = ((p.y0 + p.y1) / 2f - box[1]) / ch;
            float part = p.n / (float) (cw * ch);
            if (aspect < 1.7f || aspect > 7.5f || fill < 0.62f || part < 0.0015f || part > 0.06f) continue;
            if (cy < 0.42f || cy > 0.93f || cx < 0.08f || cx > 0.92f) continue;
            // a real plate: a filled box, plate-shaped, low on the car; the bigger the better
            float score = fill * (1 - Math.abs(aspect - 4.2f) / 6f) * (float) Math.sqrt(part) * (0.6f + cy);
            if (score > bestScore) { bestScore = score; best = p; }
        }
        if (best == null) return null;
        float[] q = best.q.clone();
        float mx = (q[0] + q[2] + q[4] + q[6]) / 4, my = (q[1] + q[3] + q[5] + q[7]) / 4;
        for (int i = 0; i < 8; i += 2) { q[i] = mx + (q[i] - mx) * 1.07f; q[i + 1] = my + (q[i + 1] - my) * 1.12f; }
        return q;
    }

    /**
     * The tail lights in a photo: the two biggest red patches at about one height (the left one first), as fractions of
     * the picture {x, y, x, y}; one only {x, y} (a side photo); null when none. Pure.
     */
    static float[] findLamps(int[] px, int w, int h) {
        int[] box = seen(px, w, h);
        int cw = box[2] - box[0], ch = box[3] - box[1];
        if (cw < 40 || ch < 20) return null;
        boolean[] m = new boolean[w * h];
        for (int i = 0; i < w * h; i++) m[i] = red(px[i]);
        m = close(m, w, h, 1);
        java.util.List<Patch> ps = patches(m, w, h, Math.max(6, cw * ch / 5000));
        // tail lights sit in the upper part of the body (the red reflectors low on the bumper and the high brake light: not these)
        ps.removeIf(p -> { float cy = (p.sy / (float) p.n - box[1]) / ch; return cy < 0.14f || cy > 0.62f; });
        if (ps.isEmpty()) return null;
        Patch a = null, b = null;
        int bestPair = 0;
        for (int i = 0; i < ps.size(); i++) for (int j = i + 1; j < ps.size(); j++) {
            Patch p = ps.get(i), q = ps.get(j);
            float dy = Math.abs(p.sy / (float) p.n - q.sy / (float) q.n) / ch, dx = Math.abs(p.sx / (float) p.n - q.sx / (float) q.n) / cw;
            if (dy < 0.1f && dx > 0.22f && Math.min(p.n, q.n) * 5 > Math.max(p.n, q.n) && p.n + q.n > bestPair) { bestPair = p.n + q.n; a = p; b = q; }
        }
        if (a == null) for (Patch p : ps) if (a == null || p.n > a.n) a = p;
        float ax = a.sx / (float) a.n / w, ay = a.sy / (float) a.n / h;
        if (b == null) return new float[]{ax, ay};
        float bx = b.sx / (float) b.n / w, by = b.sy / (float) b.n / h;
        return ax <= bx ? new float[]{ax, ay, bx, by} : new float[]{bx, by, ax, ay};
    }

    // ================================================================ Android: the photos ready for the view

    /** What the 3D view shows: each slot's picture (ready: plate done, ≤ 1024 px), its lamps (fractions) or null. */
    static final class Set {
        final Bitmap[] img = new Bitmap[SLOTS.length];
        final float[][] lamps = new float[SLOTS.length][];
        /** Photos whose plate wasn't found, so its likely place was blurred (said once). */
        int blurred;
        boolean any() { for (int i = 0; i < img.length; i++) if (img[i] != null && i != 4) return true; return false; }
        boolean[] have() { boolean[] h = new boolean[img.length]; for (int i = 0; i < img.length; i++) h[i] = img[i] != null; return h; }
    }

    /**
     * His photos made ready (off the main thread): each slot's own photo, else for the rear his older rear photo
     * (car_rear.jpg, its marked lights) and for the left side his side photo; the plate as he chose; null when none.
     */
    static Set load(Context c, Prefs prefs) {
        Set s = new Set();
        String plate = prefs.sp.getString("car_plate", "");
        for (int i = 0; i < SLOTS.length; i++) {
            if (i == 4) continue; // (from above: the 2D maps' own photo)
            File f = file(c, i);
            float[] marked = null;
            if (!f.exists() && i == 1 && CarPhoto.rearFile(c).exists()) { // (his older rear photo, its marked lights)
                f = CarPhoto.rearFile(c);
                float[] rm = CarPhoto.rearMarks(prefs);
                if (rm != null) marked = new float[]{rm[CarPhoto.LX], rm[CarPhoto.LY], rm[CarPhoto.QX], rm[CarPhoto.QY]};
            }
            if (!f.exists()) continue;
            Bitmap b = decode(f, 1024);
            if (b == null) continue;
            int fw = b.getWidth(), fh = b.getHeight();
            int[] cut = {0, 0, fw, fh};
            b = trim(b, cut);
            if (b == null) continue;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            float[] lamps = findLamps(px, w, h);
            if (lamps == null && marked != null) { // (none found: his marks, moved into the trimmed picture)
                lamps = new float[4];
                for (int k = 0; k < 4; k += 2) { lamps[k] = (marked[k] * fw - cut[0]) / w; lamps[k + 1] = (marked[k + 1] * fh - cut[1]) / h; }
            }
            if (!"asis".equals(plate)) {
                float[] q = findPlate(px, w, h);
                if (q != null) b = "blur".equals(plate) ? blur(b, q) : nexon(b, q);
                else if (plateSide(i)) { b = blur(b, guessPlate(w, h, i)); s.blurred++; } // (not found where a plate must be: its place made unreadable — never shown as it is)
            }
            s.img[i] = b;
            s.lamps[i] = lamps;
        }
        return s.any() ? s : null;
    }

    /** A side that shows a plate: front, rear and the two rear corners (not the sides, not from above). Pure. */
    static boolean plateSide(int slot) { return slot == 0 || slot == 1 || slot == 5 || slot == 6; }

    /**
     * Where a plate most likely is when it wasn't found (corners TL, TR, BR, BL in pixels): low in the middle for the front
     * and the rear, a wider band for the corners (the plate sits towards the rear face's side). Pure.
     */
    static float[] guessPlate(int w, int h, int slot) {
        float x0 = slot == 5 ? 0.45f : slot == 6 ? 0.08f : 0.26f, x1 = slot == 5 ? 0.92f : slot == 6 ? 0.55f : 0.74f, y0 = 0.58f, y1 = 0.9f;
        return new float[]{x0 * w, y0 * h, x1 * w, y0 * h, x1 * w, y1 * h, x0 * w, y1 * h};
    }

    /** A photo read whole (its extra PNG parts left out when the plain read fails), at most max px on its long side. */
    static Bitmap decode(File f, int max) {
        try {
            byte[] all;
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(f.length(), 32_000_000L));
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                all = o.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(all, 0, all.length, o);
            if (o.outWidth <= 0) { all = cleanPng(all); BitmapFactory.decodeByteArray(all, 0, all.length, o); }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(all, 0, all.length, o2);
            if (b == null) return null;
            float k = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
            if (k < 1) { Bitmap t = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true); if (t != b) b.recycle(); b = t; }
            if (!b.isMutable() || b.getConfig() != Bitmap.Config.ARGB_8888) { Bitmap t = b.copy(Bitmap.Config.ARGB_8888, true); b.recycle(); b = t; }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The clear margins of a cut-out cut off (the car fills its picture: its size on the screen is right); cut gets the box kept. */
    static Bitmap trim(Bitmap b, int[] cut) {
        if (!CarPhoto.cutout(b)) return b;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] box = seen(px, w, h);
        if (box[2] - box[0] < 8 || box[3] - box[1] < 8) return null;
        System.arraycopy(box, 0, cut, 0, 4);
        if (box[0] == 0 && box[1] == 0 && box[2] == w && box[3] == h) return b;
        Bitmap t = Bitmap.createBitmap(b, box[0], box[1], box[2] - box[0], box[3] - box[1]);
        if (t != b) b.recycle();
        if (t.isMutable()) return t;
        Bitmap m = t.copy(Bitmap.Config.ARGB_8888, true);
        t.recycle();
        return m;
    }

    /** "NEXON" on a white plate drawn over the found plate, in its slant. */
    static Bitmap nexon(Bitmap b, float[] q) {
        int pw = 1000, ph = 220;
        Bitmap plate = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Canvas pc = new Canvas(plate);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF121212);
        pc.drawRoundRect(new RectF(0, 0, pw, ph), 22, 22, p);
        p.setColor(0xFFF6F6F2);
        pc.drawRoundRect(new RectF(9, 9, pw - 9, ph - 9), 16, 16, p);
        p.setColor(0xFF0F0F0F);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(170);
        Paint.FontMetrics fm = p.getFontMetrics();
        pc.drawText("NEXON", pw / 2f, ph / 2f - (fm.ascent + fm.descent) / 2, p);
        android.graphics.Matrix mx = new android.graphics.Matrix();
        mx.setPolyToPoly(new float[]{0, 0, pw, 0, pw, ph, 0, ph}, 0, q, 0, 4);
        Bitmap out = b.isMutable() ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(out).drawBitmap(plate, mx, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        plate.recycle();
        return out;
    }

    /** The found plate made unreadable: big soft squares inside its outline. */
    static Bitmap blur(Bitmap b, float[] q) {
        Bitmap out = b.isMutable() ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        float x0 = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6])), x1 = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        float y0 = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7])), y1 = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        int l = Math.max(0, (int) x0), t = Math.max(0, (int) y0), r = Math.min(out.getWidth(), (int) Math.ceil(x1)), bt = Math.min(out.getHeight(), (int) Math.ceil(y1));
        if (r - l < 4 || bt - t < 4) return out;
        Bitmap part = Bitmap.createBitmap(out, l, t, r - l, bt - t);
        Bitmap tiny = Bitmap.createScaledBitmap(part, Math.max(2, (r - l) / 14), Math.max(1, (bt - t) / 14), true);
        Bitmap big = Bitmap.createScaledBitmap(tiny, r - l, bt - t, true);
        Canvas c = new Canvas(out);
        Path path = new Path();
        path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close();
        c.save();
        c.clipPath(path);
        c.drawBitmap(big, l, t, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.restore();
        part.recycle(); tiny.recycle(); big.recycle();
        return out;
    }

    /**
     * A picked photo kept for a slot (off the main thread): read whole (a PNG's broken extra parts left out when the
     * phone can't read it as it is), upright by the camera's turn, at most 1600 px; a cut-out stays a clear PNG, else a
     * JPEG. Only in the app's folder on this box. True when kept.
     */
    static boolean keep(Context c, android.net.Uri uri, int slot) {
        try {
            byte[] all;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int k;
                long total = 0;
                while ((k = in.read(buf)) > 0) { o.write(buf, 0, k); total += k; if (total > 48_000_000L) return false; }
                all = o.toByteArray();
            }
            int turn = 0;
            try {
                android.media.ExifInterface ex = new android.media.ExifInterface(new java.io.ByteArrayInputStream(all));
                int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                turn = or == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Throwable ignored) {}
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(all, 0, all.length, o);
            if (o.outWidth <= 0) { all = cleanPng(all); BitmapFactory.decodeByteArray(all, 0, all.length, o); }
            if (o.outWidth <= 0) return false;
            int max = 1600, sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > max * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(all, 0, all.length, o2);
            if (b == null) return false;
            float k = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
            if (k < 1 || turn != 0) {
                android.graphics.Matrix mx = new android.graphics.Matrix();
                mx.postScale(k, k);
                mx.postRotate(turn);
                Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                if (t != b) b.recycle();
                b = t;
            }
            File tmp = new File(c.getFilesDir(), fileName(slot) + ".part");
            boolean ok, clear = CarPhoto.cutout(b);
            try (FileOutputStream out = new FileOutputStream(tmp)) { ok = b.compress(clear ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, clear ? 100 : 90, out); }
            b.recycle();
            boolean done = ok && tmp.renameTo(file(c, slot));
            if (!done && !tmp.delete()) tmp.deleteOnExit();
            return done;
        } catch (Throwable e) {
            return false;
        }
    }

    /** A slot's photo removed. */
    static void remove(Context c, int slot) {
        File f = file(c, slot);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** For a backup: his 3D photos are never in one. */
    static boolean isPhoto(String name) { return name != null && name.startsWith("car3d_"); }
}
