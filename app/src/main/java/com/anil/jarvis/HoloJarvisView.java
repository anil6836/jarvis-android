package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * 🎬 The car Jarvis standing on the phone's 🛸 holo projector (10 Nov, stage 4, his choice 🎬 in 🎨): his own clips from
 * {@link HoloPack}, picked by what Jarvis is doing — resting 😌, listening 👂, thinking 🤔 (round and round while the
 * answer takes long), talking 🗣️ (back to its start before the clip's still end), 👋 once when the screen comes back after
 * a while. Two video layers take turns and cross over softly. The clips' black background becomes see-through (only
 * Jarvis shows, standing in the projector's light) and his body fades out at the bottom into the light; 💠 makes him
 * hologram blue with faint scan lines, 🎨 keeps his own colours. No sound of its own (the clips have none; only the
 * chosen voice speaks). Nothing plays when the screen is away, behind Live, in battery saver (a still picture of him
 * then) or on another tab. A clip that can't play a few times: the orb comes back ({@link Showing}).
 */
final class HoloJarvisView extends FrameLayout {
    private static final String TAG = "HoloJarvis";
    private static final int CROSS_MS = 320, AHEAD_MS = 1500, IN_MS = 420, MAX_FAILS = 4;

    /** Jarvis is on the projector (true) or not (false: the orb shows instead). */
    interface Showing { void showing(boolean on); }

    private final ImageView still;
    private final Slot[] slots = new Slot[2];
    private final Handler h = new Handler(Looper.getMainLooper());
    private HoloPack pack;
    private boolean running, motion = true, blue = true, helloWanted, shown, gaveUp, released;
    private int state = OrbView.IDLE;
    /** On the screen; loading / ready to come next; fading out under {@code now} (the crossover). */
    private Slot now, next, prev;
    private int failsInRow;
    private Bitmap stillBmp;
    private Showing listener;
    private final Paint layer = new Paint(), fade = new Paint(), scan = new Paint();
    private final RectF all = new RectF();
    private float fadeH = -1;
    private final Runnable tick = this::tick;
    /** Players let go off the screen's thread (a release can take a moment). */
    private static Handler releaser;

    /** One video layer: its view, its player, the clip on it. */
    private final class Slot implements TextureView.SurfaceTextureListener {
        final TextureView tv;
        Surface surface;
        MediaPlayer mp;
        HoloPack.Clip clip;
        boolean prepared, startWhenReady;
        /** The new clip's first picture is on the layer (until then the layer may still hold the last clip's last picture). */
        boolean rendered, fadePending;
        int vw, vh, crossMs = CROSS_MS;
        private Slot fadeOld;
        private boolean fadeFromStill;
        /** No word of the first picture (some phones don't say): the layer comes in anyway. */
        private final Runnable fadeLate = () -> { if (fadePending && now == this) fadeIn(); };

        Slot(Context c) {
            tv = new TextureView(c);
            tv.setOpaque(false);
            tv.setAlpha(0f);
            tv.setLayerPaint(layer);
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

        /** Loads a clip (it starts by itself when {@code go}, else waits for {@link #go(int)}). */
        void load(HoloPack.Clip c, boolean go) {
            drop();
            clip = c;
            startWhenReady = go;
            crossMs = CROSS_MS;
            File f = pack == null ? null : pack.file(c);
            if (f == null || !f.isFile()) { failed(this); return; }
            try {
                mp = new MediaPlayer();
                mp.setVolume(0f, 0f);
                if (surface != null) mp.setSurface(surface);
                mp.setDataSource(f.getAbsolutePath());
                mp.setLooping(c.loop && c.loopEndMs <= 0); // (a resting clip plays round by itself; talk goes back to its start before its still end)
                // (each callback checks it is still this layer's player: a let-go one may still call back once)
                mp.setOnVideoSizeChangedListener((p, w, hh) -> { if (p == mp) { vw = w; vh = hh; fit(this); } });
                mp.setOnPreparedListener(p -> {
                    if (p != mp) return;
                    prepared = true;
                    vw = p.getVideoWidth(); vh = p.getVideoHeight();
                    fit(this);
                    if (startWhenReady) go(crossMs);
                });
                mp.setOnCompletionListener(p -> { if (p == mp) ended(this); });
                mp.setOnErrorListener((p, what, extra) -> { if (p == mp) { Log.w(TAG, "clip " + c.kind + " error " + what + "/" + extra); failed(this); } return true; });
                mp.setOnInfoListener((p, what, extra) -> {
                    if (p == mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { rendered = true; if (fadePending) fadeIn(); }
                    return false;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                Log.w(TAG, "clip " + c.kind + ": " + e);
                failed(this);
            }
        }

        /** Starts the loaded clip (still loading: the moment it is ready); this layer comes in over the other one with its first picture. */
        void go(int cross) {
            startWhenReady = true;
            crossMs = cross;
            if (!prepared || mp == null) return;
            if (!running || !motion || released) return;
            try { mp.start(); } catch (Exception e) { failed(this); return; }
            final Slot old = now;
            if (old != null && old != this && old.fadePending) { // (the one before never came in: it stays out, under nothing)
                old.fadePending = false;
                h.removeCallbacks(old.fadeLate);
                old.tv.animate().cancel();
                old.tv.setAlpha(0f);
            }
            now = this;
            if (next == this) next = null;
            if (prev != null && prev != old && prev != this) prev.drop(); // (a crossover cut short: only one goes out at a time)
            prev = old != null && old != this ? old : null;
            tv.bringToFront();
            tv.animate().cancel();
            tv.setAlpha(0f); // (never the last clip's last picture: in only with this clip's own first one)
            fadeOld = old;
            fadeFromStill = old == null;
            fadePending = true;
            h.removeCallbacks(fadeLate);
            if (rendered) fadeIn(); else h.postDelayed(fadeLate, 300);
            h.removeCallbacks(steady);
            h.postDelayed(steady, 1200);
            refreshShown();
            schedule();
        }

        private void fadeIn() {
            fadePending = false;
            h.removeCallbacks(fadeLate);
            final Slot old = fadeOld;
            fadeOld = null;
            tv.animate().cancel();
            tv.animate().alpha(1f).setDuration(fadeFromStill ? IN_MS : crossMs).withEndAction(() -> {
                if (old != null && prev == old && old != now && old != next) { old.drop(); prev = null; }
                if (now == this) still.setVisibility(INVISIBLE); // (the picture under the clip: away while it plays, else it shows through)
            }).start();
        }

        /** Where the clip is now (ms), -1 unknown. */
        int pos() { try { return mp == null || !prepared ? -1 : mp.getCurrentPosition(); } catch (Exception e) { return -1; } }

        int dur() { try { return mp == null || !prepared ? -1 : mp.getDuration(); } catch (Exception e) { return -1; } }

        void drop() {
            tv.animate().cancel();
            tv.setAlpha(0f);
            h.removeCallbacks(fadeLate);
            fadePending = false;
            fadeOld = null;
            if (mp != null) {
                // (let go of the layer first, here and now: the next player takes it at once, and only one may hold it)
                try { mp.setSurface(null); } catch (Exception ignored) {}
                release(mp);
            }
            mp = null;
            clip = null;
            prepared = false;
            rendered = false;
            startWhenReady = false;
        }
    }

    /** A clip that played a while: the failures before it are forgotten (one that breaks again and again still ends in the orb). */
    private final Runnable steady = () -> { if (now != null && now.prepared && now.mp != null) failsInRow = 0; };

    HoloJarvisView(Context c) {
        super(c);
        layer.setColorFilter(filter(true));
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.MATRIX);
        still.setColorFilter(filter(true));
        addView(still, new LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) { slots[i] = new Slot(c); addView(slots[i].tv, new LayoutParams(-1, -1)); }
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        scan.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        int step = Math.max(3, Ui.dp(c, 3));
        Bitmap lines = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888);
        lines.setPixel(0, 0, 0x30000000); // (one faint line in every few: see-through, like light)
        scan.setShader(new BitmapShader(lines, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        setContentDescription("Jarvis");
    }

    // ================================================================ what the look tells it

    void setListener(Showing l) { listener = l; }

    /** The pack (null: none — nothing shows, the orb stays). A new one starts afresh. */
    void setPack(HoloPack p) {
        if (p == pack) return;
        stopAll();
        pack = p;
        gaveUp = false;
        failsInRow = 0;
        stillBmp = null;
        still.setImageDrawable(null);
        loadStill();
        refreshShown();
        if (running) decide();
    }

    HoloPack pack() { return pack; }

    /** 💠 hologram blue (true) or 🎨 his own colours (false). */
    void setBlue(boolean b) {
        if (b == blue) return;
        blue = b;
        ColorMatrixColorFilter f = filter(b);
        layer.setColorFilter(f);
        for (Slot s : slots) s.tv.setLayerPaint(layer);
        still.setColorFilter(f);
        invalidate();
    }

    /** On the screen and to be seen (true), or away / hidden / behind Live (false: every player lets go). */
    void setRunning(boolean on) {
        if (!on) helloWanted = false; // (the welcome is for coming back to him, not for later)
        if (on == running) return;
        running = on;
        if (!on) { stopAll(); return; }
        if (gaveUp) { gaveUp = false; failsInRow = 0; still.setVisibility(VISIBLE); refreshShown(); } // (another try each time the screen comes back)
        decide();
    }

    /** Motion allowed (false: battery saver / his switch — the still picture only). */
    void setMotion(boolean m) {
        if (m == motion) return;
        motion = m;
        if (!m) stopAll();
        else decide();
    }

    /** What Jarvis is doing (the orb's state). */
    void setState(int s) {
        if (s == state) return;
        state = s;
        if (s != OrbView.IDLE) helloWanted = false; // (he is already talking to Jarvis: no welcome now)
        decide();
    }

    /** 👋 The welcome once (when the pack has one: it may still be loading), then the resting clip. */
    void hello() {
        helloWanted = true;
        decide();
    }

    /** True while Jarvis (a clip or his still picture) is on the projector. */
    boolean shown() { return shown; }

    /** The screen goes for good: every player and the picture let go. */
    void release() {
        released = true;
        stopAll();
        stillBmp = null;
        still.setImageDrawable(null);
        listener = null;
    }

    // ================================================================ choosing and playing

    private boolean canPlay() { return pack != null && running && motion && !gaveUp && !released; }

    /** The clip for now: the welcome when wanted (only while resting), else the one for what Jarvis is doing. */
    private HoloPack.Clip want() {
        if (helloWanted) {
            HoloPack.Clip hc = pack.get("hello");
            if (hc != null && state == OrbView.IDLE) return hc;
            helloWanted = false;
        }
        return pack.forState(state);
    }

    private final Runnable decideLater = this::decide;

    private void decide() {
        if (!canPlay()) return;
        h.removeCallbacks(decideLater);
        if (prev != null) { h.postDelayed(decideLater, 120); return; } // (a crossover going: the clip for now comes right after it — never a blank moment)
        HoloPack.Clip c = want();
        if (c == null) return;
        if ("hello".equals(c.kind)) helloWanted = false;
        if (now != null && now.clip == c) { // (it plays on; a different one on its way in is not wanted now)
            if (next != null && next.clip != c) { next.drop(); next = null; }
            return;
        }
        if (now != null && now.clip != null && "hello".equals(now.clip.kind) && "idle".equals(c.kind)) return; // (the welcome plays to its end, then rest)
        if (next != null && next.clip == c) { if (!next.startWhenReady) next.go(CROSS_MS); return; } // (already coming)
        Slot s = free();
        s.load(c, true);
        if (s.mp != null) next = s;
    }

    /** The layer not showing now (a crossover still going: cut short, its layer taken). */
    private Slot free() {
        Slot s = slots[0] == now ? slots[1] : slots[0];
        if (s == prev) { prev = null; s.drop(); }
        if (s == next) { next = null; s.drop(); }
        return s;
    }

    /** Ten times a second while a clip plays: talk goes back to its start before its still end; the welcome hands over to rest. */
    private void tick() {
        if (!canPlay() || now == null || now.clip == null) return;
        HoloPack.Clip c = now.clip;
        int pos = now.pos(), dur = now.dur();
        int at = c.switchAt();
        int cross = c.fadeMs > 0 ? c.fadeMs : CROSS_MS;
        if (at < 0 && !c.loop && dur > 0) { at = Math.max(0, dur - CROSS_MS - 60); cross = CROSS_MS; } // (the welcome: rest comes in over its last moment)
        if (at < 0 && c.loop) return; // (it plays round by itself: nothing to watch until what Jarvis does changes)
        if (at < 0 || pos < 0 || prev != null) { schedule(); return; } // (not ready yet / a crossover going)
        if (next == null && pos >= at - AHEAD_MS) {
            HoloPack.Clip n = pack.forState(state);
            if (n != null) { Slot s = free(); s.load(n, false); if (s.mp != null) next = s; }
        }
        if (next != null && pos >= at) { next.go(cross); return; }
        schedule();
    }

    private void schedule() { h.removeCallbacks(tick); if (canPlay()) h.postDelayed(tick, 100); }

    /** A clip played to its end: the next one (ready: now; still loading: the moment it is), or the one for now. */
    private void ended(Slot s) {
        if (s != now || !canPlay()) return;
        if (next != null) { next.go(CROSS_MS); return; }
        HoloPack.Clip c = pack.forState(state);
        if (c == null) return;
        Slot f = free();
        f.load(c, true);
        if (f.mp != null) next = f;
    }

    /** A clip that couldn't play: tried again in a moment; a few in a row and the orb comes back. */
    private void failed(Slot s) {
        failsInRow++;
        boolean wasNow = s == now;
        if (s == prev) prev = null;
        if (s == next) next = null;
        if (wasNow) {
            now = null;
            if (prev != null) { prev.drop(); prev = null; } // (the one it was crossing over: out too, never left on the screen)
        }
        s.drop();
        if (failsInRow >= MAX_FAILS) {
            gaveUp = true;
            Log.w(TAG, "clips won't play: the orb back");
            stopAll();
            refreshShown();
            return;
        }
        if (wasNow) { still.setVisibility(VISIBLE); refreshShown(); }
        if (canPlay()) h.postDelayed(() -> { if (next == null) decide(); }, 400); // (the clip for now tried again: even when another one still plays)
    }

    private void stopAll() {
        h.removeCallbacks(tick);
        h.removeCallbacks(steady);
        h.removeCallbacks(decideLater);
        for (Slot s : slots) s.drop();
        now = null;
        next = null;
        prev = null;
        still.setVisibility(VISIBLE);
        refreshShown();
    }

    /** A player let go away from the screen's thread. */
    private static void release(final MediaPlayer m) {
        synchronized (HoloJarvisView.class) {
            if (releaser == null) {
                android.os.HandlerThread t = new android.os.HandlerThread("holo-release");
                t.start();
                releaser = new Handler(t.getLooper());
            }
        }
        releaser.post(() -> { try { m.release(); } catch (Exception ignored) {} });
    }

    /** Tells the look whether Jarvis is there to be seen (the orb hides only then). */
    private void refreshShown() {
        boolean on = pack != null && !gaveUp && !released && (stillBmp != null || now != null);
        if (gaveUp) still.setVisibility(INVISIBLE);
        if (on == shown) return;
        shown = on;
        if (listener != null) listener.showing(on);
    }

    // ================================================================ the picture

    /** His still picture (the resting clip's first moment): shown before a clip comes, in battery saver, between clips. */
    private void loadStill() {
        final HoloPack p = pack;
        if (p == null) return;
        final File f = p.file(p.get("idle"));
        if (f == null) return;
        new Thread(() -> {
            Bitmap b = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b != null && Math.max(b.getWidth(), b.getHeight()) > 720) {
                    float k = 720f / Math.max(b.getWidth(), b.getHeight());
                    Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                    if (s != b) b.recycle();
                    b = s;
                }
            } catch (Throwable e) {
                Log.w(TAG, "still: " + e);
            } finally {
                try { r.release(); } catch (Exception ignored) {}
            }
            final Bitmap got = b;
            h.post(() -> {
                if (released || pack != p || got == null) return;
                stillBmp = got;
                still.setImageBitmap(got);
                fitStill();
                refreshShown();
            });
        }, "holo-still").start();
    }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        fitStill();
        for (Slot s : slots) fit(s);
    }

    private void fitStill() {
        if (stillBmp == null || pack == null) return;
        float[] t = HoloPack.place(getWidth(), getHeight(), stillBmp.getWidth(), stillBmp.getHeight(), pack.centreX, pack.top);
        if (t == null) return;
        Matrix m = new Matrix();
        m.setScale(t[0], t[0]);
        m.postTranslate(t[1], t[2]);
        still.setImageMatrix(m);
    }

    /** The video layer drawn so his body's middle is on the projector's middle (TextureView stretches to its size first: undone here). */
    private void fit(Slot s) {
        if (pack == null || s.vw <= 0 || s.vh <= 0) return;
        int w = s.tv.getWidth(), hh = s.tv.getHeight();
        float[] t = HoloPack.place(w, hh, s.vw, s.vh, pack.centreX, pack.top);
        if (t == null) return;
        Matrix m = new Matrix();
        m.setScale(s.vw * t[0] / w, s.vh * t[0] / hh);
        m.postTranslate(t[1], t[2]);
        s.tv.setTransform(m);
    }

    /**
     * A clip file in a pack he picks: a video with no sound track (a silent sound track still keeps the phone's music
     * "playing" — songs, the cough listener and the door sound would think something plays). His words, or null: fine.
     */
    static String clipProblem(File f) {
        android.media.MediaExtractor x = new android.media.MediaExtractor();
        try {
            x.setDataSource(f.getAbsolutePath());
            boolean video = false;
            for (int i = 0; i < x.getTrackCount(); i++) {
                String mime = x.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("audio/")) return f.getName() + " లో శబ్దం ఉంది: శబ్దం లేని క్లిప్ కావాలి (Kling లో Native Audio ఆఫ్)";
                if (mime.startsWith("video/")) video = true;
            }
            return video ? null : f.getName() + " వీడియో కాదు";
        } catch (Exception e) {
            return f.getName() + " చదవలేకపోయాను";
        } finally {
            try { x.release(); } catch (Exception ignored) {}
        }
    }

    /** Black → see-through: 💠 his light turned hologram blue (darker parts fainter), 🎨 his own colours (only the black goes). */
    static ColorMatrixColorFilter filter(boolean blue) {
        float lr = 0.299f, lg = 0.587f, lb = 0.114f;
        if (blue) {
            float ka = 2.4f;
            return new ColorMatrixColorFilter(new float[]{
                    0.62f * lr, 0.62f * lg, 0.62f * lb, 0, 0,
                    1.22f * lr, 1.22f * lg, 1.22f * lb, 0, 0,
                    1.40f * lr, 1.40f * lg, 1.40f * lb, 0, 6,
                    ka * lr, ka * lg, ka * lb, 0, -8});
        }
        return new ColorMatrixColorFilter(new float[]{
                1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 0,
                3, 3, 3, 0, -30});
    }

    /** His body fades out at the bottom into the projector's light; 💠 adds faint scan lines over him. */
    @Override protected void dispatchDraw(Canvas c) {
        int w = getWidth(), hh = getHeight();
        if (w <= 0 || hh <= 0) { super.dispatchDraw(c); return; }
        all.set(0, 0, w, hh);
        int sc = c.saveLayer(all, null);
        super.dispatchDraw(c);
        if (fadeH != hh) {
            fadeH = hh;
            fade.setShader(new LinearGradient(0, hh * 0.68f, 0, hh, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, hh * 0.68f, w, hh, fade);
        if (blue) c.drawRect(all, scan);
        c.restoreToCount(sc);
    }

    /** Taken off the screen: everything lets go, the still shows. */
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAll();
    }

    /** Back on the screen: what should play plays again. */
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (canPlay()) post(() -> { if (now == null && next == null) decide(); });
    }
}
