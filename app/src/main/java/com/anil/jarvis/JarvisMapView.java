package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🗺️ Jarvis Maps' map: Google's map pictures (Map Tiles API, Telugu names, traffic / satellite) with his Maps key,
 * else OpenStreetMap's; his blue dot with the way he faces, routes, the places found, his saved places, and the road
 * ahead. Pan, pinch, double tap; a tap on a place tells the screen. Google's tiles are kept only as long as Google's
 * own cache time says; the credit ("Google Maps" + the map data's copyright, or © OpenStreetMap contributors) is always
 * drawn at the corner.
 */
final class JarvisMapView extends View {
    interface Listener {
        void placeTapped(int index);
        void moved(); // (he moved the map himself: following stops)
        void settled(); // (the map stopped moving: time to ask for the credit line)
        void googleFailed(String why); // (Google refused the map's pictures: the screen goes to the free map and says why)
        void sessionExpired(); // (Google's picture session ran out: the screen asks for a new one)
        void longPressed(double lat, double lon); // (📌 a finger held on a spot: what is here, save it, the way there)
    }

    /** A pin of his own (saved place, parking, a danger spot). */
    static final class Pin {
        final double lat, lon;
        final String title;
        final int color;
        Pin(double lat, double lon, String title, int color) { this.lat = lat; this.lon = lon; this.title = title; this.color = color; }
    }

    Listener listener;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;

    double cLat = 17.385, cLon = 78.4867; // (Hyderabad until a fix comes)
    float zoom = 14f;
    boolean follow = true;
    private double[] me;
    private float meHeading = -1, meAcc;

    // the map's pictures
    private boolean google, satellite, traffic;
    private String session = "";
    /** As many pictures kept as twice what the screen shows (never one pushing out another that is on the screen). */
    private int maxTiles = 60;
    private final Map<String, Bitmap> tiles = new LinkedHashMap<String, Bitmap>(96, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > maxTiles; }
    };
    private boolean failSaid;
    /** Which picture source this is (each change bumps it): a picture of an older source is never fetched or drawn. */
    private volatile int srcGen;
    /** Set once the screen said which source (no pictures fetched before: never the free map's under "Google Maps"). */
    private boolean ready;
    private List<double[]> lastFit; // (re-fitted when the panel's height changes)
    /** The bottom panel's height: the credit and the middle of the map sit above it. */
    private int inset;
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> failedAt = new java.util.HashMap<>();
    private String credit = "";
    private boolean offline;

    // what is drawn on it
    private final List<List<double[]>> routes = new ArrayList<>();
    private int routeSel;
    private List<double[]> ahead = new ArrayList<>();
    private final List<GMaps.Place> places = new ArrayList<>();
    private int placeSel = -1;
    private final List<Pin> pins = new ArrayList<>();
    boolean showPins = true;
    /** The closest zoom the free map's pictures are fetched for (15 with no signal: the kept ones are drawn bigger). */
    int maxFreeZoom = 19;
    private final java.util.concurrent.atomic.AtomicBoolean keeping = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile ExecutorService keepPool;

    JarvisMapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        txt.setTextSize(12 * d);
        txt.setColor(0xFFFFFFFF);
        txt.setShadowLayer(3 * d, 0, 0, 0xEE000000);
        small.setTextSize(10 * d);
        small.setColor(0xFFFFFFFF);
        small.setShadowLayer(3 * d, 0, 0, 0xEE000000);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                double[] under = latLonAt(g.getFocusX(), g.getFocusY());
                zoom = Math.max(3f, Math.min(20f, zoom + (float) (Math.log(g.getScaleFactor()) / Math.log(2))));
                double[] after = latLonAt(g.getFocusX(), g.getFocusY());
                cLat += under[0] - after[0];
                cLon += under[1] - after[1];
                moved();
                return true;
            }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                double[] p = world(cLat, cLon);
                double[] ll = MapMath.latLon((p[0] + dx) / unit(), (p[1] + dy) / unit(), zoom);
                cLat = Math.max(-85, Math.min(85, ll[0]));
                cLon = ll[1];
                moved();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int best = -1;
                float bd = 30 * d;
                for (int i = 0; i < places.size(); i++) {
                    float[] xy = xy(places.get(i).lat, places.get(i).lon);
                    float dd = (float) Math.hypot(xy[0] - e.getX(), xy[1] - 14 * d - e.getY());
                    if (dd < bd) { bd = dd; best = i; }
                }
                if (best >= 0 && listener != null) listener.placeTapped(best);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (scaler.isInProgress()) return;
                double[] ll = latLonAt(e.getX(), e.getY());
                if (listener != null) listener.longPressed(ll[0], ll[1]);
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                double[] under = latLonAt(e.getX(), e.getY());
                zoom = Math.min(20f, zoom + 1);
                double[] after = latLonAt(e.getX(), e.getY());
                cLat += under[0] - after[0];
                cLon += under[1] - after[1];
                moved();
                return true;
            }
        });
    }

    private void moved() {
        follow = false;
        lastFit = null;
        invalidate();
        if (listener != null) listener.moved();
        main.removeCallbacks(settle);
        main.postDelayed(settle, 700);
    }

    private final Runnable settle = () -> { if (listener != null) listener.settled(); };

    void shutdown() {
        work.shutdownNow();
        ExecutorService k = keepPool;
        if (k != null) k.shutdownNow(); // (the screen is gone: the keeping stops too)
        main.removeCallbacksAndMessages(null);
    }

    /** The screen went to the background: the pictures are let go (they come back from the phone's own copies). */
    void trim() { tiles.clear(); loading.clear(); }

    void setInset(int px) {
        if (px == inset) return;
        inset = px;
        if (follow && me != null) { double[] c = centerFor(me[0], me[1]); cLat = c[0]; cLon = c[1]; }
        else if (lastFit != null) fit(lastFit); // (the panel grew / shrank after a fit: fitted again in the part above it)
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float size = 256 * unit() * 0.7f; // (the smallest a tile is drawn, between zoom levels)
        int visible = (int) ((w / size + 2) * (h / size + 2));
        maxTiles = Math.max(60, visible * 2);
    }

    /** The map's middle that puts (lat, lon) in the middle of the part above the panel. */
    private double[] centerFor(double lat, double lon) {
        double[] p = world(lat, lon);
        return MapMath.latLon(p[0] / unit(), (p[1] + inset / 2.0) / unit(), zoom);
    }

    // ---------------------------------------------------------------- the picture source

    /** Google's pictures (session made by the screen) or OpenStreetMap's (session empty). */
    void setSource(String googleSession, boolean satellite, boolean traffic) { setSource(googleSession, satellite, traffic, false); }

    /**
     * dark: the car screen at night — Google's pictures came dark from Google itself (its session's own styles; kept apart
     * from the day ones), the free map's pictures are dimmed here (OpenStreetMap's may be).
     */
    void setSource(String googleSession, boolean satellite, boolean traffic, boolean dark) {
        boolean g = googleSession != null && !googleSession.isEmpty();
        img.setColorFilter(dark && !g ? DIM : null);
        if (!ready || g != google || !String.valueOf(googleSession).equals(session) || satellite != this.satellite || traffic != this.traffic || darkTiles != (dark && g && !satellite)) {
            srcGen++; // (pictures on their way for the old source are dropped)
            tiles.clear();
            loading.clear();
            failedAt.clear();
        }
        ready = true;
        google = g;
        session = g ? googleSession : "";
        failSaid = false;
        this.satellite = satellite;
        this.traffic = traffic;
        darkTiles = dark && g && !satellite;
        invalidate();
    }

    private boolean darkTiles;
    /** The free map at night: dimmed, a little blue (never used on Google's pictures). */
    private static final android.graphics.ColorMatrixColorFilter DIM = new android.graphics.ColorMatrixColorFilter(new float[]{
            0.38f, 0, 0, 0, 0,
            0, 0.40f, 0, 0, 0,
            0, 0, 0.46f, 0, 4,
            0, 0, 0, 1, 0});

    boolean google() { return google; }
    void setCredit(String s) { credit = s == null ? "" : s; invalidate(); }

    // ---------------------------------------------------------------- things on the map

    void setMe(double lat, double lon, float heading, float acc) {
        me = new double[]{lat, lon};
        if (heading >= 0) meHeading = heading;
        meAcc = acc;
        if (follow) { double[] c = centerFor(lat, lon); cLat = c[0]; cLon = c[1]; }
        invalidate();
    }

    double[] me() { return me; }

    void setRoutes(List<List<double[]>> rs, int sel) { routes.clear(); if (rs != null) routes.addAll(rs); routeSel = sel; invalidate(); }

    // 19 (the car screen only): the chosen way lit in gold with arrows flowing along it towards where he goes
    private boolean routeGlow, routeFlow;
    private final Path arrow = new Path();
    private final Runnable flowFrame = this::invalidate;
    void setRouteGlow(boolean on, boolean flow) {
        if (on == routeGlow && flow == routeFlow) return;
        routeGlow = on;
        routeFlow = flow;
        invalidate();
    }
    void selectRoute(int i) { routeSel = i; invalidate(); }
    void setAhead(List<double[]> pts) { ahead = pts == null ? new ArrayList<>() : pts; invalidate(); }
    void setPlaces(List<GMaps.Place> ps) { places.clear(); if (ps != null) places.addAll(ps); placeSel = -1; invalidate(); }
    void selectPlace(int i) { placeSel = i; invalidate(); }
    void setPins(List<Pin> ps) { pins.clear(); if (ps != null) pins.addAll(ps); invalidate(); }

    /** Shows all these points (a route, the places) with some room around them. */
    void fit(List<double[]> pts) {
        if (pts == null || pts.isEmpty()) return;
        lastFit = new ArrayList<>(pts);
        if (getWidth() == 0) return;
        double la = 0, lo = 0;
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (double[] p : pts) { minLa = Math.min(minLa, p[0]); maxLa = Math.max(maxLa, p[0]); minLo = Math.min(minLo, p[1]); maxLo = Math.max(maxLo, p[1]); }
        la = (minLa + maxLa) / 2;
        lo = (minLo + maxLo) / 2;
        follow = false;
        int vw = (int) (getWidth() / (unit())), vh = (int) (Math.max(getHeight() / 3, getHeight() - inset) / (unit()));
        if (pts.size() > 1) zoom = Math.min(17, MapMath.fitZoom(pts, vw, vh, 18));
        else if (zoom < 15) zoom = 16;
        double[] c = centerFor(la, lo);
        cLat = c[0];
        cLon = c[1];
        invalidate();
    }

    void centerOn(double lat, double lon, float z) {
        follow = false;
        zoom = z;
        List<double[]> one = new ArrayList<>();
        one.add(new double[]{lat, lon});
        lastFit = one; // (kept in the part above the panel when the panel changes)
        double[] c = centerFor(lat, lon);
        cLat = c[0];
        cLon = c[1];
        invalidate();
    }

    // ---------------------------------------------------------------- web mercator, with tiles drawn bigger on a sharp screen

    /** Screen pixels per map pixel: a 256-px tile is drawn about 256·density/2 px, so the names read at a normal size. */
    private float unit() { return Math.max(1f, d / 2f); }

    private double[] world(double lat, double lon) {
        double[] p = MapMath.pixel(lat, lon, zoom);
        return new double[]{p[0] * unit(), p[1] * unit()};
    }

    float[] xy(double lat, double lon) {
        double[] p = world(lat, lon), c = world(cLat, cLon);
        return new float[]{(float) (getWidth() / 2.0 + p[0] - c[0]), (float) (getHeight() / 2.0 + p[1] - c[1])};
    }

    double[] latLonAt(float sx, float sy) {
        double[] c = world(cLat, cLon);
        return MapMath.latLon((c[0] + sx - getWidth() / 2.0) / unit(), (c[1] + sy - getHeight() / 2.0) / unit(), zoom);
    }

    /** {north, south, east, west} of what is on the screen (null before it is laid out). */
    double[] bounds() {
        if (getWidth() == 0 || getHeight() == 0) return null;
        double[] nw = latLonAt(0, 0), se = latLonAt(getWidth(), getHeight());
        return new double[]{nw[0], se[0], se[1], nw[1]};
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) taps.onTouchEvent(e);
        return true;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        c.drawColor(0xFF0B1A26);
        int z = Math.max(0, Math.min(google ? 22 : maxFreeZoom, Math.round(zoom)));
        double scale = Math.pow(2, zoom - z) * unit(); // (between levels the tiles are stretched a little)
        double n = Math.pow(2, z);
        double[] cp = MapMath.pixel(cLat, cLon, z);
        double cx = cp[0] / 256, cy = cp[1] / 256;
        float size = (float) (256 * scale);
        int tx0 = (int) Math.floor(cx - W / 2f / size), tx1 = (int) Math.floor(cx + W / 2f / size), ty0 = (int) Math.floor(cy - H / 2f / size), ty1 = (int) Math.floor(cy + H / 2f / size);
        visZ = z; visX0 = tx0; visX1 = tx1; visY0 = ty0; visY1 = ty1; // (what is on the screen now: a picture asked for earlier and no longer in sight is not fetched)
        RectF r = new RectF();
        for (int tx = tx0; tx <= tx1; tx++) for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            int wx = (int) (((tx % (long) n) + (long) n) % (long) n);
            float left = (float) (W / 2f + (tx - cx) * size), top = (float) (H / 2f + (ty - cy) * size);
            String key = z + "/" + wx + "/" + ty;
            Bitmap b = tiles.get(key);
            r.set(left, top, left + size + 0.5f, top + size + 0.5f);
            if (b == null) {
                if (ready) fetch(key, z, wx, ty);
                Bitmap up = parent(z, wx, ty); // (the level above, stretched, while this one loads)
                if (up != null) drawPart(c, up, z, wx, ty, r);
                else { fill.setColor(0xFF10212F); c.drawRect(r, fill); }
                continue;
            }
            c.drawBitmap(b, null, r, img);
        }
        drawAhead(c);
        drawRoutes(c);
        if (showPins) drawPins(c);
        drawPlaces(c);
        drawMe(c);
        drawCredit(c, W, H);
    }

    /** The tile one level up that holds this one (stretched while this one loads); null if not here. */
    private Bitmap parent(int z, int x, int y) {
        if (z <= 0) return null;
        return tiles.get((z - 1) + "/" + (x / 2) + "/" + (y / 2));
    }

    private void drawPart(Canvas c, Bitmap up, int z, int x, int y, RectF dst) {
        int half = up.getWidth() / 2;
        android.graphics.Rect src = new android.graphics.Rect((x % 2) * half, (y % 2) * half, (x % 2) * half + half, (y % 2) * half + half);
        c.drawBitmap(up, src, dst, img);
    }

    private void drawAhead(Canvas c) {
        if (ahead.size() < 2) return;
        path(ahead);
        line.setColor(0x66F59E0B);
        line.setStrokeWidth(9 * d);
        c.drawPath(pathBuf, line);
        line.setColor(0xFFF59E0B);
        line.setStrokeWidth(3.5f * d);
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * d, 7 * d}, 0));
        c.drawPath(pathBuf, line);
        line.setPathEffect(null);
    }

    private void drawRoutes(Canvas c) {
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < routes.size(); i++) {
            boolean sel = i == routeSel;
            if (sel != (pass == 1)) continue; // (the chosen one on top)
            path(routes.get(i));
            if (sel && routeGlow) { litRoute(c); continue; }
            line.setColor(sel ? 0xFF0B3D91 : 0xFF5B6B7A);
            line.setStrokeWidth((sel ? 9 : 7) * d);
            c.drawPath(pathBuf, line);
            line.setColor(sel ? 0xFF3B82F6 : 0xFFB8C4CF);
            line.setStrokeWidth((sel ? 6 : 4.5f) * d);
            c.drawPath(pathBuf, line);
        }
    }

    /** 19: the way as a lit gold road — a soft wide glow, the bright band, a white core, arrows flowing along it. */
    private void litRoute(Canvas c) {
        line.setColor(0x55FFC21A);
        line.setStrokeWidth(18 * d);
        c.drawPath(pathBuf, line);
        line.setColor(0xFFFFD23A);
        line.setStrokeWidth(8 * d);
        c.drawPath(pathBuf, line);
        line.setColor(0xB3FFFFFF);
        line.setStrokeWidth(2.2f * d);
        c.drawPath(pathBuf, line);
        if (arrow.isEmpty()) { // (a small chevron, pointing along the way)
            arrow.moveTo(-4 * d, -4.5f * d); arrow.lineTo(2 * d, 0); arrow.lineTo(-4 * d, 4.5f * d); arrow.lineTo(-1.5f * d, 0); arrow.close();
        }
        float gap = 34 * d, phase = routeFlow ? (android.os.SystemClock.uptimeMillis() % 100_000L) / 1000f * 46 * d % gap : 0;
        line.setPathEffect(new android.graphics.PathDashPathEffect(arrow, gap, -phase, android.graphics.PathDashPathEffect.Style.ROTATE));
        line.setColor(0xF2FFFFFF);
        line.setStyle(Paint.Style.FILL);
        c.drawPath(pathBuf, line);
        line.setStyle(Paint.Style.STROKE);
        line.setPathEffect(null);
        removeCallbacks(flowFrame);
        if (routeFlow && isShown()) postOnAnimationDelayed(flowFrame, 66);
    }

    private final Path pathBuf = new Path();

    private void path(List<double[]> pts) {
        pathBuf.reset();
        boolean first = true;
        float lx = 0, ly = 0;
        for (double[] p : pts) {
            float[] xy = xy(p[0], p[1]);
            if (first) { pathBuf.moveTo(xy[0], xy[1]); first = false; lx = xy[0]; ly = xy[1]; continue; }
            if (Math.abs(xy[0] - lx) + Math.abs(xy[1] - ly) < 1.5f) continue; // (points closer than a pixel)
            pathBuf.lineTo(xy[0], xy[1]);
            lx = xy[0];
            ly = xy[1];
        }
    }

    private void drawPins(Canvas c) {
        for (Pin p : pins) {
            float[] xy = xy(p.lat, p.lon);
            if (xy[0] < -40 || xy[1] < -40 || xy[0] > getWidth() + 40 || xy[1] > getHeight() + 40) continue;
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(xy[0], xy[1], 7 * d, fill);
            fill.setColor(p.color);
            c.drawCircle(xy[0], xy[1], 5 * d, fill);
            if (zoom >= 14) c.drawText(p.title, xy[0] + 9 * d, xy[1] + 4 * d, small);
        }
    }

    private void drawPlaces(Canvas c) {
        for (int i = 0; i < places.size(); i++) {
            GMaps.Place p = places.get(i);
            float[] xy = xy(p.lat, p.lon);
            if (xy[0] < -60 || xy[1] < -60 || xy[0] > getWidth() + 60 || xy[1] > getHeight() + 60) continue;
            boolean sel = i == placeSel;
            float s = sel ? 1.35f : 1f;
            // a drop pin: a round head on a point
            Path pin = new Path();
            float hx = xy[0], hy = xy[1] - 22 * d * s;
            pin.addCircle(hx, hy, 10 * d * s, Path.Direction.CW);
            pin.moveTo(xy[0] - 6 * d * s, hy + 6 * d * s);
            pin.lineTo(xy[0], xy[1]);
            pin.lineTo(xy[0] + 6 * d * s, hy + 6 * d * s);
            pin.close();
            fill.setColor(sel ? 0xFFDC2626 : 0xFFEA4335);
            c.drawPath(pin, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(hx, hy, 4 * d * s, fill);
            String num = String.valueOf(i + 1);
            small.setColor(0xFF7F1D1D);
            small.setShadowLayer(0, 0, 0, 0);
            c.drawText(num, hx - small.measureText(num) / 2, hy + 3.5f * d, small);
            small.setColor(0xFFFFFFFF);
            small.setShadowLayer(3 * d, 0, 0, 0xEE000000);
            if (sel || zoom >= 15) {
                String t = p.name.length() > 22 ? p.name.substring(0, 21) + "…" : p.name;
                c.drawText(t, hx + 13 * d * s, hy + 4 * d, txt);
            }
        }
    }

    private void drawMe(Canvas c) {
        if (me == null) return;
        float[] xy = xy(me[0], me[1]);
        if (meAcc > 0) { // how sure the fix is
            double mPerPx = 156543.03392 * Math.cos(Math.toRadians(me[0])) / Math.pow(2, zoom) / unit();
            float rr = (float) (meAcc / mPerPx);
            if (rr > 12 * d && rr < getWidth()) {
                fill.setColor(0x223B82F6);
                c.drawCircle(xy[0], xy[1], rr, fill);
            }
        }
        if (meHeading >= 0) { // the way he faces: a soft wedge
            Path w = new Path();
            w.moveTo(xy[0], xy[1]);
            double a1 = Math.toRadians(meHeading - 28 - 90), a2 = Math.toRadians(meHeading + 28 - 90);
            float L = 34 * d;
            w.lineTo(xy[0] + (float) Math.cos(a1) * L, xy[1] + (float) Math.sin(a1) * L);
            w.lineTo(xy[0] + (float) Math.cos(a2) * L, xy[1] + (float) Math.sin(a2) * L);
            w.close();
            fill.setColor(0x553B82F6);
            c.drawPath(w, fill);
        }
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(xy[0], xy[1], 9 * d, fill);
        fill.setColor(0xFF2563EB);
        c.drawCircle(xy[0], xy[1], 6.5f * d, fill);
    }

    private void drawCredit(Canvas c, int W, int H) {
        float y = H - inset - 8 * d; // (above the panel: always in sight)
        // the map data's credit in full (wrapped, never cut), then "Google Maps" exactly as Google writes it (not translated, not split)
        List<String> lines = new ArrayList<>();
        if (google && !credit.isEmpty()) {
            String rest = credit;
            float max = W - 20 * d;
            while (!rest.isEmpty()) {
                int n = small.breakText(rest, true, max, null);
                if (n <= 0) break;
                int cut = n < rest.length() ? Math.max(rest.lastIndexOf(' ', n), 1) : n;
                if (cut <= 0 || cut > n) cut = n;
                lines.add(0, rest.substring(0, cut).trim());
                rest = rest.substring(cut).trim();
            }
        }
        for (String ln : lines) { c.drawText(ln, 10 * d, y, small); y -= 13 * d; }
        txt.setFakeBoldText(google);
        c.drawText(google ? "Google Maps" : "© OpenStreetMap contributors", 10 * d, y, txt);
        txt.setFakeBoldText(false);
        if (offline) c.drawText("📴 నెట్ లేదు: దాచిన ముక్కలే", W - small.measureText("📴 నెట్ లేదు: దాచిన ముక్కలే") - 10 * d, H - inset - 8 * d, small);
    }

    // ---------------------------------------------------------------- the pictures, fetched and kept

    private volatile int visZ = -1, visX0, visX1, visY0, visY1;

    /** This square is on the screen now (or just next to it). */
    private boolean inSight(int z, int x, int y) {
        if (z != visZ || y < visY0 - 1 || y > visY1 + 1) return false;
        long n = 1L << z;
        for (int tx = visX0 - 1; tx <= visX1 + 1; tx++) if (((tx % n) + n) % n == x) return true;
        return false;
    }

    private void fetch(String key, int z, int x, int y) {
        if (loading.contains(key)) return;
        Long fa = failedAt.get(key);
        if (fa != null && System.currentTimeMillis() - fa < 30_000) return;
        if (work.isShutdown()) return;
        loading.add(key);
        final boolean g = google;
        final String ses = session;
        final String kind = g ? (satellite ? "gs" : "gr") + (traffic ? "t" : "") + (darkTiles ? "d" : "") : "osm";
        final int gen = srcGen;
        final Context app = getContext().getApplicationContext();
        try {
            work.execute(() -> {
                Bitmap b = null;
                boolean noNet = false, expired = false;
                String refused = null;
                if (gen != srcGen) return; // (the source changed while it waited: not fetched, not counted; setSource already cleared its mark)
                BitmapFactory.Options opaque = new BitmapFactory.Options();
                opaque.inPreferredConfig = Bitmap.Config.RGB_565; // (map pictures have no see-through parts: half the memory)
                try {
                    File f = new File(app.getCacheDir(), "jmaps/" + kind + "/" + z + "_" + x + "_" + y + ".t");
                    byte[] bytes = null;
                    if (f.exists()) { // (kept: its own "good until" time first, then the picture)
                        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                            long until = in.readLong();
                            if (System.currentTimeMillis() < until) { bytes = new byte[(int) f.length() - 8]; in.readFully(bytes); }
                        } catch (Exception e) { //noinspection ResultOfMethodCallIgnored
                            f.delete(); // (a broken copy: fetched again)
                        }
                    }
                    if (bytes == null && !inSight(z, x, y)) { // (he zoomed / moved past it while it waited: no paid fetch for a square not seen)
                        main.post(() -> { if (gen == srcGen) loading.remove(key); });
                        return;
                    }
                    if (bytes == null && Net.online(app)) {
                        long until;
                        if (g) {
                            long[] age = {0};
                            bytes = GMaps.tile(app, ses, z, x, y, age);
                            until = System.currentTimeMillis() + Math.min(age[0], 30L * 86400) * 1000; // (Google's own time; never more than 30 days)
                        } else {
                            bytes = Coder.download("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png", "User-Agent", "Jarvis-Android personal assistant (one user)", "Accept", "image/png");
                            until = System.currentTimeMillis() + 30L * 86_400_000L;
                        }
                        if (bytes != null) b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opaque);
                        if (b != null && until > System.currentTimeMillis()) { // (kept only when it really is a picture; written whole, then put in place)
                            File dir = f.getParentFile();
                            if (dir != null) dir.mkdirs();
                            File tmp = new File(f.getPath() + ".part");
                            try (DataOutputStream o = new DataOutputStream(new FileOutputStream(tmp))) { o.writeLong(until); o.write(bytes); }
                            if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                                tmp.delete();
                        }
                    } else if (bytes == null) noNet = true;
                    else {
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opaque);
                        if (b == null) //noinspection ResultOfMethodCallIgnored
                            f.delete(); // (a broken copy: fetched again next time)
                    }
                } catch (GMaps.Error ge) {
                    // a refusal of the key (401 / 403 / a bad key) → the free map; a session that ran out → a new one;
                    // the day's limit used up (429) → the free map too, said why; anything else (no tile there: 404) → this square only, tried again later
                    String m = ge.getMessage() == null ? "" : ge.getMessage().toLowerCase(java.util.Locale.ROOT);
                    if (ge.status == 401 || ge.status == 403 || ge.status == 429 || ge.status == 400 && (ge.reason.contains("API_KEY") || m.contains("api key"))) refused = GMaps.telugu(ge);
                    else if (ge.status == 400 && m.contains("session")) expired = true;
                } catch (Exception ignored) {}
                final Bitmap bb = b;
                final boolean off = noNet, exp = expired;
                final String why = refused;
                main.post(() -> {
                    if (gen != srcGen) return; // (the source changed meanwhile: never drawn on the other map; its mark was cleared then)
                    loading.remove(key);
                    offline = off;
                    if (why != null && g && !failSaid) { failSaid = true; if (listener != null) listener.googleFailed(why); return; }
                    if (exp && g && !failSaid) { failSaid = true; if (listener != null) listener.sessionExpired(); return; }
                    if (bb != null) tiles.put(key, bb); else failedAt.put(key, System.currentTimeMillis());
                    invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            loading.remove(key);
        }
    }

    // ---------------------------------------------------------------- 📴 the free map's pictures of a way, kept for no signal

    /**
     * Keeps the free map's (OpenStreetMap) pictures along a way for a month, for roads without signal: zoom 9–14 with a
     * square each side, 15 on the line itself; at most 1,000 pictures, two at a time (the map's fair-use rules: no
     * bulk taking, nothing closer than zoom 15). One keeping at a time. progress(kept, total, finished) on the main
     * thread (kept = saved now or already kept; a picture that failed is not counted); returns the total, or -1 when a
     * keeping is already running. The closest pictures first (they are what the drive screen shows); a long way drops
     * the farthest-out zooms, not the road itself. Google's map pictures are never kept like this (Google's rules).
     */
    int keepFree(List<double[]> way, KeepProgress progress) {
        if (!keeping.compareAndSet(false, true)) return -1;
        java.util.LinkedHashSet<String> want = new java.util.LinkedHashSet<>();
        for (int z = 15; z >= 9; z--) {
            double step = Math.max(150, 40075016.0 * Math.cos(Math.toRadians(way.isEmpty() ? 17 : way.get(0)[0])) / Math.pow(2, z) / 3); // (a third of a tile)
            List<double[]> pts = denser(way, step);
            int side = z <= 14 ? 1 : 0;
            for (double[] p : pts) {
                double[] px = MapMath.pixel(p[0], p[1], z);
                int tx = (int) Math.floor(px[0] / 256), ty = (int) Math.floor(px[1] / 256);
                for (int dx = -side; dx <= side; dx++) for (int dy = -side; dy <= side; dy++) want.add(z + "_" + (tx + dx) + "_" + (ty + dy));
            }
            if (want.size() > 1000) break; // (a very long way: the closer pictures left out)
        }
        final List<String> list = new ArrayList<>(want);
        if (list.size() > 1000) list.subList(1000, list.size()).clear();
        final Context app = getContext().getApplicationContext();
        final int total = list.size();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(), kept = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService two = Executors.newFixedThreadPool(2);
        keepPool = two;
        for (String k : list) {
            two.execute(() -> {
                try {
                    String[] zxy = k.split("_");
                    int z = Integer.parseInt(zxy[0]), x = Integer.parseInt(zxy[1]), y = Integer.parseInt(zxy[2]);
                    long n = 1L << z;
                    x = (int) (((x % n) + n) % n);
                    if (y >= 0 && y < n) {
                        File f = new File(app.getCacheDir(), "jmaps/osm/" + z + "_" + x + "_" + y + ".t");
                        boolean fresh = false;
                        if (f.exists()) { try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { fresh = System.currentTimeMillis() < in.readLong(); } catch (Exception ignored) {} }
                        if (fresh) kept.incrementAndGet();
                        else if (Net.online(app)) {
                            byte[] bytes = Coder.download("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png", "User-Agent", "Jarvis-Android personal assistant (one user)", "Accept", "image/png");
                            if (bytes != null && BitmapFactory.decodeByteArray(bytes, 0, bytes.length) != null) {
                                File dir = f.getParentFile();
                                if (dir != null) dir.mkdirs();
                                File tmp = new File(f.getPath() + ".part");
                                try (DataOutputStream o = new DataOutputStream(new FileOutputStream(tmp))) { o.writeLong(System.currentTimeMillis() + 30L * 86_400_000L); o.write(bytes); }
                                if (tmp.renameTo(f)) kept.incrementAndGet();
                                else //noinspection ResultOfMethodCallIgnored
                                    tmp.delete();
                            }
                        }
                    }
                } catch (Exception ignored) {}
                int d2 = done.incrementAndGet();
                if (d2 == total) keeping.set(false);
                final int kk = kept.get();
                if (progress != null && (d2 % 50 == 0 || d2 == total)) main.post(() -> progress.on(kk, total, d2 == total));
            });
        }
        two.shutdown();
        if (total == 0) keeping.set(false);
        return total;
    }

    interface KeepProgress { void on(int kept, int total, boolean finished); }

    /** The way with a point at least every `step` metres (so no picture along it is missed). */
    private static List<double[]> denser(List<double[]> pts, double step) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] b = pts.get(i);
            if (i > 0) {
                double[] a = pts.get(i - 1);
                double m = MapMath.meters(a[0], a[1], b[0], b[1]);
                for (double t = step; t < m; t += step) out.add(new double[]{a[0] + (b[0] - a[0]) * t / m, a[1] + (b[1] - a[1]) * t / m});
            }
            out.add(b);
        }
        return out;
    }

    /** Old kept pictures (past their time) are cleared now and then, so the folder never grows. Off the main thread. */
    static void tidy(Context c) {
        File root = new File(c.getCacheDir(), "jmaps");
        File[] kinds = root.listFiles();
        if (kinds == null) return;
        long now = System.currentTimeMillis();
        for (File k : kinds) {
            File[] fs = k.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                    if (in.readLong() < now) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                } catch (Exception e) { //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }
}
