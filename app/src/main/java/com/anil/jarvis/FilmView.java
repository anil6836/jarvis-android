package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.Random;

/**
 * 🎬 The film Jarvis in his window on the car screen (31 Oct, Anil: "మన కారు మెయిన్ స్క్రీన్‌లో కొత్త Jarvis ఉండాలి …
 * సాంగ్స్ ప్లే చేసినప్పుడు కొత్త Jarvis మనం కొత్తగా చేసిన స్టెప్స్ వేయాలి"). His own clips from {@link FilmPack},
 * chosen by {@link FilmPlan}: the still picture of Jarvis on his stage when nothing plays; with a song, one dance clip
 * after another (each starts on the picture the one before ended on, and the two cross over a third of a second, so a
 * small difference of light never jumps). Two video layers take turns; the part of the picture with the dancers in it
 * fills the window. No sound of its own (the song is his music app's). Everything stops when the screen is away.
 */
final class FilmView extends FrameLayout {
    private static final String TAG = "FilmView";
    private static final int CROSS_MS = 320, NEXT_AHEAD_MS = 2600, OUT_MS = 520;

    private final ImageView still;
    private final Slot[] slots = new Slot[2];
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private FilmPack pack;
    private FilmPlan plan;
    private Bitmap stillBmp;
    private float[] stillBox = FilmPack.SOLO_BOX;
    private boolean running, dancing, sets = true, helloWanted;
    private int level = -1, talk = OrbView.IDLE;
    /** On the screen; loading / ready to come next; fading out under {@code now} (the crossover). */
    private Slot now, next, prev;
    /** Clips that failed one after another (a working one sets it back): tries stop after a few. */
    private int failsInRow;
    /** Players let go off the screen's thread (a release can take a moment on the box). */
    private static android.os.Handler releaser;
    private final Runnable tick = this::tick;
    /** Each frame while the view opens out / closes in (the set's first and last clips). */
    private final Runnable zoom = new Runnable() {
        @Override public void run() {
            Slot s = now;
            if (!running || s == null || s.clip == null || s.mp == null || !s.prepared) return;
            int pos, dur;
            try { pos = s.mp.getCurrentPosition(); dur = s.mp.getDuration(); } catch (Exception e) { return; }
            fit(s);
            if (s.clip.zooming(pos, dur)) postOnAnimation(this);
        }
    };

    /** One video layer: its view, its player, the clip on it. */
    private final class Slot implements TextureView.SurfaceTextureListener {
        final TextureView tv;
        Surface surface;
        MediaPlayer mp;
        FilmPack.Clip clip;
        boolean prepared, startWhenReady;
        int vw, vh;

        Slot(Context c) {
            tv = new TextureView(c);
            tv.setOpaque(false);
            tv.setAlpha(0f);
            tv.setSurfaceTextureListener(this);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
            surface = new Surface(st);
            if (mp != null) try { mp.setSurface(surface); } catch (Exception ignored) {}
            fit(this);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { fit(this); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (mp != null) try { mp.setSurface(null); } catch (Exception ignored) {}
            if (surface != null) surface.release();
            surface = null;
            return true;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

        /** Loads a clip (it starts by itself when {@code go}, else waits for {@link #go()}). */
        void load(FilmPack.Clip c, boolean go) {
            drop();
            clip = c;
            startWhenReady = go;
            File f = pack == null ? null : pack.file(c);
            if (f == null || !f.isFile()) { failed(this); return; }
            try {
                mp = new MediaPlayer();
                mp.setVolume(0f, 0f);
                if (surface != null) mp.setSurface(surface);
                mp.setDataSource(f.getAbsolutePath());
                mp.setLooping(c.loop && !"dance".equals(c.kind) && !"set".equals(c.kind)); // (a rest clip loops by itself; dances follow each other)
                // (each callback checks it is still this layer's player: a let-go one may still call back once)
                mp.setOnVideoSizeChangedListener((p, w, hh) -> { if (p == mp) { vw = w; vh = hh; fit(this); } });
                mp.setOnPreparedListener(p -> {
                    if (p != mp) return;
                    prepared = true;
                    vw = p.getVideoWidth(); vh = p.getVideoHeight();
                    fit(this);
                    if (startWhenReady) go();
                });
                mp.setOnCompletionListener(p -> { if (p == mp) ended(this); });
                mp.setOnErrorListener((p, what, extra) -> { if (p == mp) { Log.w(TAG, "clip " + c.id + " error " + what + "/" + extra); failed(this); } return true; });
                mp.prepareAsync();
            } catch (Exception e) {
                Log.w(TAG, "clip " + c.id + ": " + e);
                failed(this);
            }
        }

        /** Starts the loaded clip (still loading: the moment it is ready) and brings this layer in over the other one. */
        void go() {
            startWhenReady = true;
            if (!prepared || mp == null) return;
            try { mp.start(); } catch (Exception e) { failed(this); return; }
            failsInRow = 0;
            final Slot old = now;
            now = this;
            if (next == this) next = null;
            if (prev != null && prev != old && prev != this) { prev.drop(); } // (a crossover cut short: only one goes out at a time)
            prev = old != null && old != this ? old : null;
            tv.bringToFront();
            tv.animate().cancel();
            boolean fromStill = old == null;
            tv.setAlpha(fromStill ? 0f : 0.02f);
            tv.animate().alpha(1f).setDuration(fromStill ? OUT_MS : CROSS_MS).withEndAction(() -> {
                if (old != null && prev == old && old != now && old != next) { old.drop(); prev = null; }
                if (now == this) still.setVisibility(INVISIBLE); // (the picture under the film: saved drawing while it plays)
            }).start();
            if (old == null) still.setVisibility(VISIBLE);
            schedule();
        }

        int left() {
            try { return mp == null || !prepared ? Integer.MAX_VALUE : mp.getDuration() - mp.getCurrentPosition(); } catch (Exception e) { return Integer.MAX_VALUE; }
        }

        void drop() {
            tv.animate().cancel();
            tv.setAlpha(0f);
            if (mp != null) release(mp);
            mp = null;
            clip = null;
            prepared = false;
            startWhenReady = false;
        }
    }

    FilmView(Context c) {
        super(c);
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.MATRIX);
        addView(still, new LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) { slots[i] = new Slot(c); addView(slots[i].tv, new LayoutParams(-1, -1)); }
    }

    // ================================================================ what the car screen tells it

    /** The pack (null: none — the window shows nothing of its own, and no dance goes on in it). */
    void setPack(FilmPack p) {
        if (p == pack) return;
        stopAll();
        pack = p;
        if (p == null) dancing = false; // (hidden: the song's end never reaches it; the screen tells it again when it comes back)
        plan = p == null ? null : new FilmPlan(p);
        stillBox = p == null ? FilmPack.SOLO_BOX : p.stillBox;
        loadStill();
        if (running) decide(true);
    }

    FilmPack pack() { return pack; }

    void setSets(boolean on) { sets = on; }

    /** The screen is shown (true) or away (false): away, every player lets go. */
    void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        if (!on) stopAll();
        else decide(true);
    }

    /** A song's dance on or off. */
    void setDancing(boolean on, int lv) {
        level = lv;
        if (on == dancing) return;
        dancing = on;
        if (!running || plan == null) return;
        if (on) { helloWanted = false; decide(true); }
        else { plan.reset(); toStill(); decide(false); }
    }

    /** The song's loudness now (the next clip is chosen with it). */
    void setLevel(int lv) { level = lv; }

    /** A new song: one set again. */
    void songStart() { if (plan != null) plan.songStart(); }

    /** What Jarvis is doing (listening, thinking, talking): only matters when not dancing and when that clip exists. */
    void setTalk(int s) {
        if (s == talk) return;
        talk = s;
        if (running && !dancing && plan != null) decide(false);
    }

    /** 👋 The welcome clip once (when there is one), then back to rest. */
    void hello() {
        if (plan == null || dancing) return;
        helloWanted = true;
        if (running) decide(true);
    }

    // ================================================================ choosing and playing

    /** What should play now; {@code force}: even when the right kind already plays. */
    private void decide(boolean force) {
        if (plan == null || !running) return;
        FilmPack.Clip c;
        if (dancing) {
            if (!force && now != null && now.clip != null && ("dance".equals(now.clip.kind) || "set".equals(now.clip.kind))) return;
            c = plan.nextDance(level, sets, rnd);
        } else if (helloWanted && plan.forKind("hello") != null) {
            helloWanted = false;
            c = plan.forKind("hello");
        } else {
            c = plan.forKind(FilmPlan.kindFor(talk));
            if (c == null && talk != OrbView.IDLE) c = plan.forKind("idle");
            if (c != null && now != null && now.clip == c) return; // (the same rest clip plays on)
            if (c == null && !force && now != null && now.clip != null && "hello".equals(now.clip.kind)) return; // (the welcome plays to its end; then the still)
            if (c == null) { toStill(); return; }
        }
        if (c == null) { toStill(); return; }
        Slot s = free();
        s.load(c, true);
        if (s.mp != null) next = s; // (until it starts: nothing else is loaded over it)
    }

    /** The layer not showing now (a crossover still going: cut short, its layer taken). */
    private Slot free() {
        Slot s = slots[0] == now ? slots[1] : slots[0];
        if (s == prev) prev = null;
        if (s == next) next = null;
        return s;
    }

    /** Ten times a second while a clip plays: the next one ready before this ends, then crossed over. */
    private void tick() {
        if (!running || now == null || now.clip == null) return;
        if (now.clip.boxStart != null || now.clip.boxEnd != null) { removeCallbacks(zoom); post(zoom); } // (the camera move: drawn each frame while it lasts)
        int left = now.left();
        if (left == Integer.MAX_VALUE) { schedule(); return; }
        if (now.clip.loop && !dancing) { schedule(); return; } // (a rest loop plays on by itself)
        if (prev != null) { schedule(); return; } // (the crossover still going: its layer is not free yet)
        if (next == null && left < NEXT_AHEAD_MS) {
            FilmPack.Clip c = dancing ? plan.nextDance(level, sets, rnd) : restAfter();
            if (c != null) { Slot s = free(); s.load(c, false); if (s.mp != null) next = s; }
        }
        if (next != null && left <= CROSS_MS + 40) { next.go(); if (now == next) return; } // (not ready yet: it starts the moment it is)
        schedule();
    }

    /** After a non-dance clip (the welcome): the rest clip, or nothing (the still). */
    private FilmPack.Clip restAfter() {
        FilmPack.Clip c = plan.forKind(FilmPlan.kindFor(talk));
        if (c == null) c = plan.forKind("idle");
        return c;
    }

    private void schedule() { h.removeCallbacks(tick); if (running) h.postDelayed(tick, 100); }

    /** A clip played to its end (its last picture stays until the next one starts). */
    private void ended(Slot s) {
        if (s != now) return;
        if (next != null) { next.go(); return; } // (ready: now; still loading: the moment it is)
        if (plan == null) { toStill(); return; }
        FilmPack.Clip c = dancing ? plan.nextDance(level, sets, rnd) : restAfter();
        if (c != null && (dancing || s.clip == null || c != s.clip)) { Slot f = free(); f.load(c, true); if (f.mp != null) next = f; return; }
        plan.reset();
        toStill();
    }

    /**
     * A clip that couldn't play: the plan goes on from the picture on the screen (a second failure of the same clip and it
     * is never tried again this trip); nothing on the screen any more: the still, and another clip tried in a moment (a
     * few times at most).
     */
    private void failed(Slot s) {
        if (plan != null) plan.broken(s.clip, now != null && now != s && now.clip != null ? now.clip.to : FilmPlan.STAGE);
        failsInRow++;
        if (s == prev) { prev = null; s.drop(); return; }
        boolean wasNow = s == now;
        if (s == next) next = null;
        s.drop();
        if (wasNow) {
            now = null;
            if (prev != null) { prev.drop(); prev = null; }
            toStill();
        }
        if (now == null && next == null && running && failsInRow <= 6) h.postDelayed(() -> { if (now == null && next == null) decide(true); }, 400);
    }

    /** The still picture back (the film fades out over it; nothing else stays playing). */
    private void toStill() {
        h.removeCallbacks(tick);
        still.setVisibility(VISIBLE);
        if (next != null) { next.drop(); next = null; }
        if (prev != null) { prev.drop(); prev = null; } // (a crossover cut short: its end never comes)
        final Slot old = now;
        now = null;
        if (old != null) {
            old.tv.animate().cancel();
            old.tv.animate().alpha(0f).setDuration(OUT_MS).withEndAction(() -> { if (old != now && old != next) old.drop(); }).start();
        }
    }

    private void stopAll() {
        h.removeCallbacks(tick);
        removeCallbacks(zoom);
        for (Slot s : slots) s.drop();
        now = null;
        next = null;
        prev = null;
        still.setVisibility(VISIBLE);
        if (plan != null) plan.reset();
    }

    /** A player let go away from the screen's thread. */
    private static void release(final MediaPlayer m) {
        synchronized (FilmView.class) {
            if (releaser == null) {
                android.os.HandlerThread t = new android.os.HandlerThread("film-release");
                t.start();
                releaser = new Handler(t.getLooper());
            }
        }
        releaser.post(() -> { try { m.release(); } catch (Exception ignored) {} });
    }

    // ================================================================ pictures in the window

    private void loadStill() {
        final FilmPack p = pack;
        still.setImageDrawable(null);
        if (stillBmp != null) { stillBmp = null; }
        if (p == null) return;
        final File f = p.still(FilmPlan.STAGE);
        if (f == null) return;
        new Thread(() -> {
            Bitmap got = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 1280 || o.outHeight / (s * 2) >= 1280) s *= 2; // (both sides: a tall picture too)
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                got = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (Throwable e) { Log.w(TAG, "still: " + e); }
            final Bitmap b = got;
            post(() -> {
                if (pack != p || b == null) return;
                stillBmp = b;
                still.setImageBitmap(b);
                fitStill();
            });
        }, "film-still").start();
    }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        fitStill();
        for (Slot s : slots) fit(s);
    }

    private void fitStill() {
        if (stillBmp == null) return;
        Matrix m = new Matrix();
        float[] t = place(getWidth(), getHeight(), stillBmp.getWidth(), stillBmp.getHeight(), stillBox);
        if (t == null) return;
        m.setScale(t[0], t[0]);
        m.postTranslate(t[1], t[2]);
        still.setImageMatrix(m);
    }

    /** The video layer drawn so the clip's dancers fill the window (TextureView stretches to its size first: undone here). */
    private void fit(Slot s) {
        if (s.clip == null || s.vw <= 0 || s.vh <= 0) return;
        int w = s.tv.getWidth(), hh = s.tv.getHeight();
        int pos = 0, dur = 0;
        if (s.mp != null && s.prepared) { try { pos = s.mp.getCurrentPosition(); dur = s.mp.getDuration(); } catch (Exception ignored) {} }
        float[] t = place(w, hh, s.vw, s.vh, s.clip.boxAt(pos, dur));
        if (t == null) return;
        Matrix m = new Matrix();
        m.setScale(s.vw * t[0] / w, s.vh * t[0] / hh);
        m.postTranslate(t[1], t[2]);
        s.tv.setTransform(m);
    }

    /**
     * The scale and the left / top of a picture (pw × ph) in the window (w × h) so its box (fractions x0 y0 x1 y1) is
     * in view and as big as it can be; when a little more (up to 15 %) fills the whole window, that (no thin empty band:
     * the box has room round the dancers), the box's middle in the window's middle, never pulled past the picture's own
     * edges. Pure.
     */
    static float[] place(int w, int h, int pw, int ph, float[] box) {
        if (w <= 0 || h <= 0 || pw <= 0 || ph <= 0) return null;
        float bw = (box[2] - box[0]) * pw, bh = (box[3] - box[1]) * ph;
        float s = Math.min(w / bw, h / bh), cover = Math.max(w / (float) pw, h / (float) ph);
        if (cover > s && cover <= s * 1.15f) s = cover;
        float dw = pw * s, dh = ph * s;
        float cx = (box[0] + box[2]) / 2f * dw, cy = (box[1] + box[3]) / 2f * dh;
        float left = w / 2f - cx, top = h / 2f - cy;
        left = dw >= w ? Math.max(w - dw, Math.min(0, left)) : (w - dw) / 2f;
        top = dh >= h ? Math.max(h - dh, Math.min(0, top)) : (h - dh) / 2f;
        return new float[]{s, left, top};
    }

    /** Taken off the screen (the screen's shape changed: the window moves): everything lets go, the still shows. */
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAll();
    }

    /** Back on the screen: what should play plays again. */
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running && plan != null) post(() -> { if (now == null && next == null) decide(true); });
    }
}
