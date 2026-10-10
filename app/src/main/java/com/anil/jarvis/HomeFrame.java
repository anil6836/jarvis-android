package com.anil.jarvis;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen family photo frame for the home tablet. Shows photos Anil sends from his phone (files/frame) and
 * gallery albums named Jarvis / Frame / Family / ఫోటోలు, newest first, with a cross-fade and a small clock.
 * Touching it anywhere runs {@link #onTap} (the host then hides the frame).
 */
final class HomeFrame extends FrameLayout {

    private static final long SHOW_MS = 12_000L;
    private static final long FADE_MS = 1_200L;
    private static final long CLOCK_MS = 30_000L;
    private static final int MAX_FILES = 200;
    private static final int MAX_LIST = 1000;
    private static final long MAX_PIXELS = 1920L * 1200L * 2L;
    private static final String DIR = "frame";
    private static final String[] BUCKETS = {"jarvis", "frame", "family", "ఫోటోలు"};
    private static final String HINT = "ఫోటోలు లేవు. అబ్బాయి ఫోన్ నుంచి పంపితే ఇక్కడ కనిపిస్తాయి.";

    /** Called when the frame is touched anywhere. */
    public Runnable onTap;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView[] views = new ImageView[2];
    private final Bitmap[] shown = new Bitmap[2];
    private final TextView clock;
    private final TextView hint;
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("h:mm", Locale.US);

    private ExecutorService exec;
    private List<Item> items = new ArrayList<>();
    private int front = 0;      // slot that is on screen
    private int idx = -1;       // last item handed to the decoder
    private int gen = 0;        // bumps on start/stop; stale results are dropped
    private boolean running;
    private boolean due;        // time for the next photo
    private boolean decoding;
    private Bitmap ready;       // next photo, already decoded
    private int fails;
    private long lastTap;

    /** One photo: a file in files/frame or a MediaStore image. */
    private static final class Item {
        final File file;
        final Uri uri;
        final long time;
        final int orient;

        Item(File file, Uri uri, long time, int orient) {
            this.file = file;
            this.uri = uri;
            this.time = time;
            this.orient = orient;
        }
    }

    HomeFrame(Context c) {
        super(c);
        Context a = c.getApplicationContext();
        ctx = a != null ? a : c;
        setBackgroundColor(Color.BLACK);
        setContentDescription("ఫోటో ఫ్రేమ్");
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            views[i] = v;
        }

        hint = new TextView(c);
        hint.setText(HINT);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0f, 1.2f);
        int pad = dp(48);
        hint.setPadding(pad, pad, pad, pad);
        hint.setVisibility(GONE);
        hint.setTranslationZ(2f);
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        clock = new TextView(c);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(8f, 0f, 2f, 0xCC000000);
        clock.setAlpha(0.85f);
        clock.setTranslationZ(2f);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(36);
        lp.setMarginStart(dp(36));
        lp.bottomMargin = dp(28);
        addView(clock, lp);
        updateClock();
    }

    /** Same as setting {@link #onTap}. */
    void setOnTap(Runnable r) { onTap = r; }

    // ================================================================ slideshow

    /** Begins or resumes the slideshow; reloads the photo list every time. */
    void start() {
        try {
            main.removeCallbacks(nextTick);
            main.removeCallbacks(clockTick);
            running = true;
            due = false;
            decoding = false;
            recycle(ready);
            ready = null;
            fails = 0;
            final int g = ++gen;
            hint.setVisibility(GONE);
            clockTick.run();
            ensureExec().execute(() -> {
                List<Item> l;
                try {
                    l = list(ctx, MAX_LIST);
                } catch (Throwable t) {
                    l = new ArrayList<>();
                }
                final List<Item> found = l;
                main.post(() -> onListed(g, found));
            });
        } catch (Throwable ignored) {
        }
    }

    /** Stops the timers and frees the bitmaps. */
    void stop() {
        try {
            running = false;
            gen++;
            due = false;
            decoding = false;
            main.removeCallbacks(nextTick);
            main.removeCallbacks(clockTick);
            for (ImageView v : views) {
                v.animate().cancel();
                v.setImageDrawable(null);
                v.setAlpha(0f);
            }
            for (int i = 0; i < 2; i++) {
                recycle(shown[i]);
                shown[i] = null;
            }
            recycle(ready);
            ready = null;
            if (exec != null) exec.shutdownNow();
            exec = null;
        } catch (Throwable ignored) {
        }
    }

    private void onListed(int g, List<Item> found) {
        try {
            if (g != gen || !running) return;
            items = found;
            idx = -1;
            fails = 0;
            if (found.isEmpty()) {
                showHint();
                return;
            }
            due = true;
            prefetch();
        } catch (Throwable ignored) {
        }
    }

    private final Runnable nextTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (!running) return;
                // One photo only: keep it, no need to decode it again.
                if (items.size() <= 1 && shown[front] != null) return;
                due = true;
                if (ready != null) showReady();
                else prefetch();
            } catch (Throwable ignored) {
            }
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (running) main.postDelayed(this, CLOCK_MS);
        }
    };

    private void updateClock() {
        try {
            clock.setText(clockFmt.format(new Date()));
        } catch (Throwable ignored) {
        }
    }

    /** Decodes the next photo in the background. */
    private void prefetch() {
        if (!running || decoding || ready != null || items.isEmpty()) return;
        decoding = true;
        idx = (idx + 1) % items.size();
        final Item it = items.get(idx);
        final int g = gen;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            w = 1920;
            h = 1200;
        }
        final int tw = w, th = h;
        try {
            ensureExec().execute(() -> {
                final Bitmap b = decode(ctx, it, tw, th);
                main.post(() -> onDecoded(g, b));
            });
        } catch (Throwable t) {
            decoding = false;
        }
    }

    private void onDecoded(int g, Bitmap b) {
        try {
            if (g != gen || !running) {
                recycle(b);
                return;
            }
            decoding = false;
            if (b == null) {
                fails++;
                if (fails >= items.size()) {
                    // Nothing could be decoded; try again later.
                    fails = 0;
                    if (shown[front] == null) showHint();
                    main.removeCallbacks(nextTick);
                    main.postDelayed(nextTick, SHOW_MS);
                    return;
                }
                prefetch();
                return;
            }
            fails = 0;
            ready = b;
            if (due) showReady();
        } catch (Throwable ignored) {
        }
    }

    /** Cross-fades the decoded photo in over the current one. */
    private void showReady() {
        Bitmap b = ready;
        ready = null;
        due = false;
        if (b == null || !running) {
            recycle(b);
            return;
        }
        settle();
        final int back = 1 - front;
        ImageView in = views[back];
        shown[back] = b;
        in.setImageBitmap(b);
        in.setAlpha(0f);
        in.setTranslationZ(1f);
        views[front].setTranslationZ(0f);
        hint.setVisibility(GONE);
        final int old = front;
        front = back;
        in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> clearSlot(old)).start();
        main.removeCallbacks(nextTick);
        main.postDelayed(nextTick, SHOW_MS);
        if (items.size() > 1) prefetch();
    }

    /** Ends any running fade: the front photo fully shown, the other slot emptied. */
    private void settle() {
        views[0].animate().cancel();
        views[1].animate().cancel();
        views[front].setAlpha(shown[front] != null ? 1f : 0f);
        clearSlot(1 - front);
    }

    private void clearSlot(int i) {
        try {
            if (i == front) return;
            views[i].setImageDrawable(null);
            views[i].setAlpha(0f);
            recycle(shown[i]);
            shown[i] = null;
        } catch (Throwable ignored) {
        }
    }

    private void showHint() {
        hint.setVisibility(VISIBLE);
    }

    private ExecutorService ensureExec() {
        if (exec == null || exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {
                    }
                    r.run();
                }, "HomeFrame");
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // ================================================================ touch

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        try {
            super.performClick();
        } catch (Throwable ignored) {
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTap < 600) return true;
        lastTap = now;
        Runnable r = onTap;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    // ================================================================ photo list

    /** How many photos are available now (both sources). Never throws. */
    static int count(Context c) {
        if (c == null) return 0;
        int n = 0;
        try {
            n += frameFiles(c).length;
        } catch (Throwable ignored) {
        }
        try {
            n += scanStore(c, null);
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * Up to n different family photos (picked at random from the frame's photos), each cut to a square of px × px —
     * the cards of the pairs game. Background thread (it decodes pictures). Never throws; may return fewer (or none).
     */
    static List<Bitmap> thumbs(Context c, int n, int px) {
        ArrayList<Bitmap> out = new ArrayList<>();
        if (c == null || n <= 0) return out;
        try {
            List<Item> all = list(c, 400);
            Collections.shuffle(all);
            for (Item it : all) {
                if (out.size() >= n) break;
                Bitmap b = decode(c, it, px, px);
                if (b == null) continue;
                try {
                    int s = Math.min(b.getWidth(), b.getHeight());
                    Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
                    Bitmap sc = Bitmap.createScaledBitmap(sq, px, px, true);
                    if (sq != b && sq != sc) sq.recycle();
                    if (b != sc) b.recycle();
                    out.add(sc);
                } catch (Throwable t) {
                    recycle(b);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Short Telugu line about the frame. */
    static String status(Context c) {
        int n = count(c);
        if (n <= 0) return "ఫోటో ఫ్రేమ్‌కి ఇంకా ఫోటోలు లేవు";
        if (n == 1) return "ఫోటో ఫ్రేమ్‌లో ఒక ఫోటో ఉంది";
        return "ఫోటో ఫ్రేమ్‌లో " + n + " ఫోటోలు ఉన్నాయి";
    }

    /** All photos, newest first. */
    private static List<Item> list(Context c, int max) {
        ArrayList<Item> out = new ArrayList<>();
        try {
            for (File f : frameFiles(c)) out.add(new Item(f, null, f.lastModified(), 0));
        } catch (Throwable ignored) {
        }
        try {
            scanStore(c, out);
        } catch (Throwable ignored) {
        }
        try {
            Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        } catch (Throwable ignored) {
        }
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
    }

    private static boolean isPhotoName(String n) {
        if (n == null) return false;
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png");
    }

    private static File[] frameFiles(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        File[] fs = dir.listFiles((d, name) -> isPhotoName(name));
        if (fs == null) return new File[0];
        ArrayList<File> ok = new ArrayList<>();
        for (File f : fs) if (f.isFile() && f.length() > 0) ok.add(f);
        return ok.toArray(new File[0]);
    }

    private static boolean canReadImages(Context c) {
        try {
            String p = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean bucketOk(String b) {
        if (b == null) return false;
        String t = b.trim();
        for (String k : BUCKETS) if (k.equalsIgnoreCase(t)) return true;
        return false;
    }

    /** Adds the album photos to out (when not null); returns how many there are. */
    private static int scanStore(Context c, List<Item> out) {
        if (!canReadImages(c)) return 0;
        ContentResolver cr = c.getContentResolver();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = out == null ? new String[]{"bucket_display_name"}
                : new String[]{"_id", "bucket_display_name", "datetaken", "date_added", "orientation"};
        Cursor cur = null;
        try {
            try {
                cur = cr.query(base, proj, "LOWER(bucket_display_name) IN (?,?,?) OR bucket_display_name = ?",
                        new String[]{"jarvis", "frame", "family", "ఫోటోలు"}, null);
            } catch (Throwable t) {
                cur = null;
            }
            // Some providers reject LOWER(); then filter here instead.
            if (cur == null) cur = cr.query(base, proj, null, null, null);
            if (cur == null) return 0;
            int n = 0;
            int cBucket = cur.getColumnIndex("bucket_display_name");
            int cId = cur.getColumnIndex("_id");
            int cTaken = cur.getColumnIndex("datetaken");
            int cAdded = cur.getColumnIndex("date_added");
            int cOrient = cur.getColumnIndex("orientation");
            while (cur.moveToNext()) {
                if (cBucket < 0 || !bucketOk(cur.getString(cBucket))) continue;
                n++;
                if (out == null || cId < 0) continue;
                long id = cur.getLong(cId);
                long time = cTaken >= 0 ? cur.getLong(cTaken) : 0;
                if (time <= 0 && cAdded >= 0) time = cur.getLong(cAdded) * 1000L;
                int orient = cOrient >= 0 ? cur.getInt(cOrient) : 0;
                out.add(new Item(null, ContentUris.withAppendedId(base, id), time, orient));
            }
            return n;
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (cur != null) cur.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================ decoding

    /** Down-sampled, EXIF-rotated bitmap, or null when it cannot be decoded. */
    private static Bitmap decode(Context c, Item it, int tw, int th) {
        Bitmap b = null;
        try {
            int exif = exifOf(c, it);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decodeInto(c, it, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            boolean swap = exif >= ExifInterface.ORIENTATION_TRANSPOSE && exif <= ExifInterface.ORIENTATION_ROTATE_270;
            int sw = swap ? o.outHeight : o.outWidth;
            int sh = swap ? o.outWidth : o.outHeight;
            // Big enough to fill the screen (a little upscale is fine), but not huge.
            int s = 1;
            while (sw / (s * 2) >= tw * 3 / 4 && sh / (s * 2) >= th * 3 / 4) s *= 2;
            while ((long) (sw / s) * (long) (sh / s) > MAX_PIXELS) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            String mime = o.outMimeType;
            d.inPreferredConfig = mime != null && mime.toLowerCase(Locale.US).contains("jpeg")
                    ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            b = decodeInto(c, it, d);
            if (b == null) return null;
            Matrix m = matrixFor(exif);
            if (m != null) {
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) {
            recycle(b);
            return null;
        }
    }

    private static Bitmap decodeInto(Context c, Item it, BitmapFactory.Options o) throws Exception {
        if (it.file != null) return BitmapFactory.decodeFile(it.file.getAbsolutePath(), o);
        try (InputStream in = c.getContentResolver().openInputStream(it.uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(new BufferedInputStream(in, 64 * 1024), null, o);
        }
    }

    /** EXIF orientation; falls back to the MediaStore orientation column. */
    private static int exifOf(Context c, Item it) {
        int v = ExifInterface.ORIENTATION_UNDEFINED;
        try {
            if (it.file != null) {
                v = new ExifInterface(it.file.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } else {
                try (InputStream in = c.getContentResolver().openInputStream(it.uri)) {
                    if (in != null) {
                        v = new ExifInterface(in)
                                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (v <= ExifInterface.ORIENTATION_NORMAL) {
            switch (((it.orient % 360) + 360) % 360) {
                case 90: return ExifInterface.ORIENTATION_ROTATE_90;
                case 180: return ExifInterface.ORIENTATION_ROTATE_180;
                case 270: return ExifInterface.ORIENTATION_ROTATE_270;
                default: return ExifInterface.ORIENTATION_NORMAL;
            }
        }
        return v;
    }

    private static Matrix matrixFor(int exif) {
        Matrix m = new Matrix();
        switch (exif) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1f, -1f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90f); break;
            default: return null;
        }
        return m;
    }

    private static void recycle(Bitmap b) {
        try {
            if (b != null && !b.isRecycled()) b.recycle();
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ saving photos from Anil's phone

    /** Saves a received photo as files/frame/frame_<millis>.jpg; keeps the newest 200. */
    static boolean save(Context c, byte[] jpeg) {
        if (c == null || jpeg == null || jpeg.length < 64) return false;
        synchronized (HomeFrame.class) {
            File tmp = null;
            try {
                // Only real images.
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return false;

                File dir = new File(c.getFilesDir(), DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                long ms = System.currentTimeMillis();
                File dst = new File(dir, "frame_" + ms + ".jpg");
                while (dst.exists()) dst = new File(dir, "frame_" + (++ms) + ".jpg");
                // Write to a temp name first so the slideshow never sees half a file.
                tmp = new File(dir, dst.getName() + ".part");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(jpeg);
                    out.flush();
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                }
                if (!tmp.renameTo(dst)) return false;
                tmp = null;
                prune(dir);
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                try {
                    if (tmp != null && tmp.exists()) tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Deletes the oldest photos beyond 200 and stale temp files. */
    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null) return;
            long now = System.currentTimeMillis();
            ArrayList<File> photos = new ArrayList<>();
            for (File f : all) {
                String n = f.getName();
                if (n.endsWith(".part")) {
                    if (now - f.lastModified() > 10 * 60_000L) f.delete();
                } else if (isPhotoName(n)) {
                    photos.add(f);
                }
            }
            if (photos.size() <= MAX_FILES) return;
            File[] arr = photos.toArray(new File[0]);
            Arrays.sort(arr, (a, b) -> {
                int k = Long.compare(a.lastModified(), b.lastModified());
                return k != 0 ? k : a.getName().compareTo(b.getName());
            });
            for (int i = 0; i < arr.length - MAX_FILES; i++) {
                try {
                    arr[i].delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
