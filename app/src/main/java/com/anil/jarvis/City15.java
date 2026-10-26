package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🏙️ Stage 4 (27 Oct): ⑮'s 3D city map. MapLibre draws OpenFreeMap's free map (OpenStreetMap) leaning back up to 60°:
 * the buildings standing in 3D (white glass by day, dark at night — 🌙 his choice), our free way on the road (hidden
 * behind the buildings in front of it), a big arrow floating over the next turn, our 3D car (or his own photo) where he
 * is, the chips' places as standing pins. The camera follows him each second, gliding; touching the map does nothing
 * (it is for glancing). OpenStreetMap only: no Google picture is ever drawn on it (Google's turn card stays in its own
 * card). Made the first time ⑮ shows; drawn only while it is on the screen.
 */
final class City15 {

    interface Host {
        /** Something didn't come (each said once a while). */
        void failed(String why);
    }

    static final String EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}";

    final FrameLayout view;
    private final Context app;
    private final Prefs prefs;
    private final FreeWay way;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ImageView photo;
    private MapView map;
    private MapLibreMap ml;
    private Style style;
    private boolean made, broken, shown, closed, alive, asking;
    private String loaded = "", loading = "", base, said = "", photoKey = "", topKey = "";
    private long askedAt = -600_000, saidAt, carAt;
    private int accent = 0xFF5FF3FF;
    // where he is
    private double lat = Double.NaN, lon;
    private float headingDeg;
    private int kmh;
    private boolean night, brake;
    private double zoomNow = 18;
    // what the map holds now (set again only when it changed)
    private String routeNow, arrowNow, carNow, placesNow;
    private int routeGen = -1, ptsGen = -1, fps;
    private long routeAt;
    private boolean routeBusy;
    private List<double[]> pts;
    // the camera last asked for (asked again only when something really changed)
    private double camLat = Double.NaN, camLon, camZoom, camTilt, camBearing;
    private int camH;
    private List<OsmPlaces.Place> places;
    private String placesKind = "";
    private Bitmap rear, top;
    private int insetLeft, insetBottom;

    City15(Context c, Prefs p, FreeWay way, Host host) {
        app = c.getApplicationContext();
        prefs = p;
        this.way = way;
        this.host = host;
        view = new FrameLayout(c);
        view.setBackgroundColor(0xFF0B1018);
        photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.FIT_END);
        photo.setVisibility(View.GONE);
        view.addView(photo, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        view.addOnLayoutChangeListener((v, l0, t0, r0, b0, ol, ot, or, ob) -> { if (b0 - t0 != ob - ot || r0 - l0 != or - ol) main.post(() -> { if (!closed) { photoKey = ""; placePhoto(); } }); });
    }

    // ================================================================ life

    /** On the screen (and the screen awake) or not: the map drawn only while it shows. */
    void setShown(boolean on) {
        if (closed) return;
        shown = on;
        if (on) make();
        if (map == null) return;
        if (on && !alive) { map.onStart(); map.onResume(); alive = true; }
        else if (!on && alive) { map.onPause(); map.onStop(); alive = false; }
    }

    void close() {
        if (closed) return;
        setShown(false);
        closed = true;
        if (ml != null) ml.removeOnCameraMoveListener(follow);
        if (map != null) { try { map.onDestroy(); } catch (Exception ignored) {} }
        work.shutdownNow();
    }

    /**
     * 🎛️ "🗑️ 3D మ్యాప్ ముక్కలు తీసేయి": MapLibre's kept map pieces (round where he drove) and the free style's copy gone;
     * they come again from the net when needed.
     */
    static void clearCache(Context c) {
        Context app = c.getApplicationContext();
        try {
            MapLibre.getInstance(app);
            org.maplibre.android.offline.OfflineManager.getInstance(app).clearAmbientCache(null);
        } catch (Throwable ignored) {} // (the library can't load on this box: nothing kept either)
        new Thread(() -> {
            File d = new File(app.getFilesDir(), "map15");
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (!f.delete()) f.deleteOnExit();
        }, "map15-clear").start();
    }

    /** It couldn't be made on this box (said once). */
    boolean broken() { return broken; }

    /** The route's glow in his accent. */
    void setAccent(int a) {
        if (a == accent) return;
        accent = a;
        loaded = ""; // (the style made again with it)
        loading = ""; // (one being made with the old accent: dropped)
        if (ml != null) styleNow();
    }

    /** Room the cards take at the left and the bottom: the map's © OpenStreetMap credit stays seen beside them. */
    void setInsets(int left, int bottom) {
        insetLeft = left;
        insetBottom = bottom;
        credit();
    }

    /** His rear photo made ready (the plate as he chose; ⑯'s loader); null: none. */
    void setPhoto(Bitmap rearPhoto) {
        rear = rearPhoto;
        photoKey = "";
        carNow = null;
        placePhoto();
        refresh();
    }

    private void make() {
        if (made || broken || closed) return;
        made = true;
        if (!gles3()) { // (MapLibre draws with OpenGL ES 3.0: a box without it would crash when the map starts drawing)
            unusable("🏙️ ఈ box లో ⑮ 3D మ్యాప్ రాదు Sir (దీని GPU కి OpenGL ES 3 లేదు). 🎮 (గేమ్ వ్యూ) లేదా వేరే ఆకారం పెట్టండి.");
            return;
        }
        try {
            MapLibre.getInstance(app);
            MapLibreMapOptions o = MapLibreMapOptions.createFromAttributes(view.getContext()).compassEnabled(false).logoEnabled(false)
                    .attributionEnabled(true).foregroundLoadColor(0xFF0B1018);
            map = new MapView(view.getContext(), o);
            map.onCreate(null);
            fps = prefs.carLowMotion() ? 20 : 30;
            map.setMaximumFps(fps);
            map.addOnDidFailLoadingMapListener(this::styleFailed);
            view.addView(map, 0, new FrameLayout.LayoutParams(-1, -1));
            map.getMapAsync(m -> {
                if (closed) return;
                ml = m;
                UiSettings u = m.getUiSettings();
                u.setAllGesturesEnabled(false); // (for glancing: the camera follows him)
                u.setCompassEnabled(false);
                u.setLogoEnabled(false);
                u.setAttributionEnabled(true); // (© OpenStreetMap, OpenMapTiles: always seen)
                credit();
                m.addOnCameraMoveListener(follow);
                styleNow();
            });
            if (shown && !alive) { map.onStart(); map.onResume(); alive = true; }
        } catch (Throwable t) { // (the library can't load on this box)
            if (map != null) { view.removeView(map); map = null; }
            unusable("🏙️ ⑮ 3D మ్యాప్ ఈ box లో రాలేదు Sir. 🎮 (గేమ్ వ్యూ) లేదా వేరే ఆకారం పెట్టండి.");
        }
    }

    /** OpenGL ES 3.0 or newer on this box. */
    private boolean gles3() {
        try {
            android.app.ActivityManager am = app.getSystemService(android.app.ActivityManager.class);
            android.content.pm.ConfigurationInfo ci = am == null ? null : am.getDeviceConfigurationInfo();
            return ci != null && ci.reqGlEsVersion >= 0x30000;
        } catch (Exception e) { return false; }
    }

    /** ⑮ can't be drawn here: said once, and written on the map's place so he sees why (the cards keep working). */
    private void unusable(String why) {
        broken = true;
        android.widget.TextView t = new android.widget.TextView(view.getContext());
        t.setText(why);
        t.setTextColor(0xFFDFE7F0);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        int pad = Math.round(24 * app.getResources().getDisplayMetrics().density);
        t.setPadding(pad * 6, pad, pad * 6, pad);
        view.addView(t, 0, new FrameLayout.LayoutParams(-1, -1));
        host.failed(why);
    }

    /** The style didn't load (a bad kept copy, the free server's answer): our plain land with our car and way, the free style asked for again later. */
    private void styleFailed(String why) {
        if (closed) return;
        boolean wasFree = base != null;
        loading = "";
        loaded = "";
        if (wasFree) {
            base = null; // (asked for again in a minute)
            askedAt = System.currentTimeMillis();
            final File f = styleFile();
            work.execute(() -> { if (f.exists() && !f.delete()) f.deleteOnExit(); });
        }
        else loaded = lookNow() + ":plain"; // (even the plain land failed: not asked again and again)
        say("style", "🏙️ ఉచిత 3D మ్యాప్ రాలేదు Sir: మన దారి, కారు మాత్రం చూపిస్తున్నాను. కాసేపట్లో మళ్ళీ ప్రయత్నిస్తాను.");
        if (ml != null && wasFree) styleNow();
    }

    private File styleFile() { return new File(new File(app.getFilesDir(), "map15"), "style.json"); }

    private void credit() {
        if (ml == null) return;
        UiSettings u = ml.getUiSettings();
        int m = Math.max(4, view.getHeight() / 80);
        u.setAttributionGravity(Gravity.BOTTOM | Gravity.START);
        u.setAttributionMargins(insetLeft + m, 0, 0, insetBottom + m);
    }

    private void say(String what, String words) {
        long now = System.currentTimeMillis();
        if (what.equals(said) && now - saidAt < 300_000) return; // (not again and again while he drives)
        said = what;
        saidAt = now;
        host.failed(words);
    }

    // ================================================================ the style (OpenFreeMap's, made ours)

    /** "glass" by day, "dark" at night; 🌙 his choice: always day or always night. */
    String lookNow() {
        String p = prefs.sp.getString("car_15_look", "");
        return "day".equals(p) ? "glass" : "night".equals(p) ? "dark" : night ? "dark" : "glass";
    }

    private void styleNow() {
        if (ml == null || closed) return;
        String look = lookNow();
        String key = look + (base == null ? ":plain" : "");
        if (key.equals(loaded) || key.equals(loading)) return;
        if (base == null) fetch();
        loading = key;
        final String b = base, lk = look;
        final int acc = accent;
        work.execute(() -> {
            String js;
            try { js = Map15Style.make(b == null ? Map15Style.plain(lk) : b, lk, acc); }
            catch (Exception e) { try { js = Map15Style.make(Map15Style.plain(lk), lk, acc); } catch (Exception e2) { js = Map15Style.plain(lk); } }
            final String styled = js;
            main.post(() -> {
                if (closed || ml == null || !key.equals(loading)) return;
                ml.setStyle(new Style.Builder().fromJson(styled), s -> {
                    if (closed || !key.equals(loading)) return;
                    style = s;
                    loaded = key;
                    loading = "";
                    pins(s);
                    routeNow = arrowNow = carNow = placesNow = null; // (a new style: all of ours put on it again)
                    routeGen = -1;
                    refresh();
                });
            });
        });
    }

    /** OpenFreeMap's style: the kept copy (fresh for a week), else asked for (no key; at most once a minute). */
    private void fetch() {
        long now = System.currentTimeMillis();
        if (asking || base != null || now - askedAt < 60_000) return;
        asking = true;
        askedAt = now;
        final File f = styleFile();
        work.execute(() -> {
            String got = null;
            try { if (f.exists()) got = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
            boolean old = got == null || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600_000;
            if (old && Net.online(app)) {
                try {
                    String s = FreeWay.get(Map15Style.STYLE_URL);
                    new JSONObject(s); // (a real style, not an error page)
                    File d = f.getParentFile();
                    if (d != null && !d.isDirectory() && !d.mkdirs()) throw new java.io.IOException("no folder");
                    File part = new File(d, "style.json.part");
                    try (FileOutputStream o = new FileOutputStream(part)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
                    if (!part.renameTo(f)) throw new java.io.IOException("not kept");
                    got = s;
                } catch (Exception ignored) {} // (the kept copy, when there is one, does)
            }
            final String g = got;
            main.post(() -> {
                asking = false;
                if (closed) return;
                if (g == null) { say("style", "🏙️ ఉచిత 3D మ్యాప్ రాలేదు Sir (నెట్ లేదు): మన దారి, కారు మాత్రం చూపిస్తున్నాను. నెట్ వచ్చాక మ్యాప్ వస్తుంది."); return; }
                base = g;
                styleNow();
            });
        });
    }

    /** The chips' pins: a round dark sign on a stick, in his accent (drawn here, put on each new style). */
    private void pins(Style s) {
        float d = app.getResources().getDisplayMetrics().density;
        int w = Math.max(36, Math.round(44 * d)), h = Math.round(w * 1.33f);
        for (int i = 0; i < OsmPlaces.KINDS.length; i++) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float r = w * 0.42f, cx = w / 2f, cy = r + w * 0.06f;
            p.setColor(accent | 0xFF000000);
            c.drawRect(cx - w * 0.03f, cy, cx + w * 0.03f, h, p);
            p.setColor(0xF00B1621);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.06f);
            p.setColor(accent | 0xFF000000);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(r * 1.05f);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(OsmPlaces.SIGNS[i], cx, cy - (fm.ascent + fm.descent) / 2, p);
            try { s.addImage("j-pin-" + OsmPlaces.KINDS[i], b); } catch (Exception ignored) {}
        }
    }

    // ================================================================ every second

    /** Every second while ⑮ shows: where he is (null: not known yet), the speed (km/h, −1 unknown), night, braking. */
    void tick(Location l, int kmh, boolean night, boolean brake) {
        this.kmh = kmh;
        this.night = night;
        this.brake = brake;
        if (!shown || closed) return;
        make();
        if (ml == null) return;
        styleNow();
        if (base == null) fetch();
        if (l == null) return; // (the camera stays where it was)
        boolean first = Double.isNaN(lat);
        double la = l.getLatitude(), lo = l.getLongitude();
        float mps = kmh >= 0 ? kmh / 3.6f : l.hasSpeed() ? l.getSpeed() : 0;
        if (l.hasBearing() && mps > 2) headingDeg = l.getBearing();
        // standing: the GPS wanders a few metres — the car and the camera stay where they are (the map rests, no drawing)
        if (!first && mps < 1 && Drive.meters(la, lo, lat, lon) < 12) { la = lat; lo = lon; }
        lat = la;
        lon = lo;
        camera(first);
        refresh();
    }

    private boolean threeD() { return !"2d".equals(prefs.sp.getString("car_15_3d", "")); }

    private int eyeLevel() { return Math.max(0, Math.min(2, prefs.sp.getInt("car_15_eye", 1))); }

    private boolean northUp() { return "north".equals(prefs.sp.getString("car_15_north", "")); }

    /**
     * The camera behind and above him (3D), or over him (2D); the way ahead up the screen (🧭: north up); gliding. Asked
     * again only when something really changed (else the map would draw all the time, even parked).
     */
    private void camera(boolean jump) {
        boolean d3 = threeD();
        double[] e = Map15Style.eye(eyeLevel(), d3);
        double zoom = Map15Style.speedZoom(e[0], Math.max(0, kmh)), bearing = northUp() ? 0 : headingDeg;
        int h = view.getHeight();
        boolean same = !Double.isNaN(camLat) && Drive.meters(lat, lon, camLat, camLon) < 0.3 && Math.abs(zoom - camZoom) < 0.01 && e[1] == camTilt
                && Math.abs(((bearing - camBearing) % 360 + 540) % 360 - 180) < 0.5 && h == camH;
        if (same && !jump) return;
        zoomNow = zoom;
        camLat = lat; camLon = lon; camZoom = zoom; camTilt = e[1]; camBearing = bearing; camH = h;
        double top = h * (d3 ? 0.45 : 0.3); // (his car low on the screen: the way ahead seen)
        CameraPosition p = new CameraPosition.Builder().target(new LatLng(lat, lon)).zoom(zoom).tilt(e[1])
                .bearing(bearing).padding(0, top, 0, 0).build();
        if (jump || prefs.carLowMotion()) ml.moveCamera(CameraUpdateFactory.newCameraPosition(p));
        else ml.easeCamera(CameraUpdateFactory.newCameraPosition(p), 1000, false); // (straight, not slowing at each second's end)
        placePhoto();
    }

    /** While the camera glides: our car stays where the camera looks (a few times a second), so it never jumps. */
    private final MapLibreMap.OnCameraMoveListener follow = () -> {
        long now = System.currentTimeMillis();
        if (now - carAt < 66 || ml == null) return;
        CameraPosition c = ml.getCameraPosition();
        if (c == null || c.target == null) return;
        car(c.target.getLatitude(), c.target.getLongitude(), northUp() ? headingDeg : c.bearing);
    };

    /** The way's points, read once for each new way (a long trip has thousands). */
    private List<double[]> points() {
        if (way.gen() != ptsGen) { ptsGen = way.gen(); pts = way.points(); }
        return pts;
    }

    /** Ours on the map again where it changed: the car, the way ahead, the arrow, the places. */
    private void refresh() {
        if (style == null || Double.isNaN(lat)) return;
        CameraPosition c = ml == null ? null : ml.getCameraPosition();
        boolean atCam = c != null && c.target != null;
        double cla = atCam ? c.target.getLatitude() : lat, clo = atCam ? c.target.getLongitude() : lon;
        car(cla, clo, northUp() || !atCam ? headingDeg : c.bearing);
        // the way still ahead of the car (the part behind it not drawn; only the next 6 km — nothing further shows), anew
        // when the way changed or every 3 s; made off the main thread
        List<double[]> p = points();
        long now = System.currentTimeMillis();
        if (p == null) { routeGen = way.gen(); put(Map15Style.SRC_ROUTE, EMPTY, 0); }
        else if ((way.gen() != routeGen || now - routeAt > 3000) && !routeBusy && !closed) {
            routeGen = way.gen();
            routeAt = now;
            routeBusy = true;
            final int g = routeGen;
            work.execute(() -> {
                String js;
                try { js = Map15Style.routeAhead(p, cla, clo, 6000); } catch (Exception e) { js = null; }
                final String r = js;
                main.post(() -> { routeBusy = false; if (!closed && r != null && g == way.gen()) put(Map15Style.SRC_ROUTE, r, 0); });
            });
        }
        try { put(Map15Style.SRC_TURN, Map15Style.arrow(way.has() ? way.nextTurn() : null, Map15Style.arrowScale(lat, zoomNow)), 1); } catch (Exception ignored) {}
        if (placesNow == null) {
            try { put(Map15Style.SRC_PLACES, Map15Style.places(places, placesKind), 3); } catch (Exception ignored) {}
        }
    }

    /** Our 3D car at (la, lo) heading hDeg (or nothing on the map when his photo stands there). */
    private void car(double la, double lo, double hDeg) {
        if (style == null) return;
        carAt = System.currentTimeMillis();
        String kind = prefs.sp.getString("car_3d_kind", "suv");
        String json = EMPTY;
        if (!photoOn()) {
            String k = "photo".equals(kind) ? "suv" : kind;
            try { json = Map15Style.car(la, lo, Math.toRadians(hDeg), k, prefs.carColour(), brake, night, Map15Style.carScale(la, zoomNow, k)); } catch (Exception ignored) {}
        }
        put(Map15Style.SRC_CAR, json, 2);
    }

    /** One of our sources set (only when it changed). which: 0 route, 1 arrow, 2 car, 3 places. */
    private void put(String id, String json, int which) {
        String was = which == 0 ? routeNow : which == 1 ? arrowNow : which == 2 ? carNow : placesNow;
        if (json.equals(was) || style == null) return;
        GeoJsonSource s;
        try { s = style.getSourceAs(id); } catch (Exception e) { s = null; } // (a newer style loading)
        if (s == null) return;
        s.setGeoJson(json);
        if (which == 0) routeNow = json; else if (which == 1) arrowNow = json; else if (which == 2) carNow = json; else placesNow = json;
    }

    // ================================================================ his photo car

    /** His photo standing where the camera looks: 3D his rear photo; 2D his photo from above (else our car). */
    private boolean photoOn() {
        if (!"photo".equals(prefs.sp.getString("car_3d_kind", "suv"))) return false;
        return threeD() ? rear != null : top != null;
    }

    /** His photo over the map where the camera looks (laid out again only when something about it changed). */
    private void placePhoto() {
        if (closed) return;
        boolean on = "photo".equals(prefs.sp.getString("car_3d_kind", "suv"));
        if (on && !threeD()) topCheck();
        if (!on || !photoOn() || view.getWidth() <= 0) {
            if (photo.getVisibility() != View.GONE) photo.setVisibility(View.GONE);
            photoKey = "";
            return;
        }
        boolean d3 = threeD();
        int w = view.getWidth(), h = view.getHeight();
        Bitmap b = d3 ? rear : top;
        float rot = !d3 && northUp() ? Math.round(headingDeg) : 0;
        String key = d3 + "|" + w + "|" + h + "|" + eyeLevel() + "|" + System.identityHashCode(b) + "|" + rot;
        if (key.equals(photoKey)) return;
        photoKey = key;
        float share = d3 ? (eyeLevel() == 0 ? 0.15f : eyeLevel() == 2 ? 0.09f : 0.12f) : 0.06f;
        int pw = Math.max(8, Math.round(w * share)), ph = Math.max(8, Math.round(pw * b.getHeight() / (float) Math.max(1, b.getWidth())));
        float top0 = h * (d3 ? 0.45f : 0.3f), focus = top0 + (h - top0) / 2f; // (where the camera looks: the middle of the room under the padding)
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph, Gravity.TOP | Gravity.START);
        lp.leftMargin = (w - pw) / 2;
        lp.topMargin = Math.round(d3 ? focus - ph * 0.82f : focus - ph / 2f); // (3D: standing on the road there)
        photo.setLayoutParams(lp);
        photo.setImageBitmap(b);
        photo.setRotation(rot);
        if (photo.getVisibility() != View.VISIBLE) { photo.setVisibility(View.VISIBLE); photo.bringToFront(); }
    }

    /** His photo from above (the 2D maps' own: its nose turned up as he set it, cut to the car), read again when it changed. */
    private void topCheck() {
        File f = CarPhoto.topFile(app);
        String key = f.lastModified() + "|" + prefs.sp.getString("car_top_turn", "0");
        if (key.equals(topKey) || closed) return;
        topKey = key;
        work.execute(() -> {
            Bitmap b = CarPhoto.loadTop(app, prefs);
            main.post(() -> { if (closed) return; top = b; photoKey = ""; carNow = null; placePhoto(); refresh(); });
        });
    }

    /** His look choices changed (🎛️, the map's buttons, his words): the camera, the car, the style again. */
    void lookChanged() {
        if (closed) return;
        int f = prefs.carLowMotion() ? 20 : 30;
        if (map != null && f != fps) { fps = f; map.setMaximumFps(f); }
        photoKey = "";
        if (ml == null) return;
        styleNow();
        if (!Double.isNaN(lat)) camera(true);
        carNow = null;
        refresh();
        placePhoto();
    }

    // ================================================================ 📍 the chips' places

    /** The places of a chip's kind as standing pins (null: none). */
    void setPlaces(String kind, List<OsmPlaces.Place> list) {
        places = list;
        placesKind = kind == null ? "" : kind;
        placesNow = null;
        refresh();
    }

    boolean hasPlaces() { return places != null && !places.isEmpty(); }

    /** Where the camera looks now: {lat, lon}, or null. */
    double[] here() { return Double.isNaN(lat) ? null : new double[]{lat, lon}; }
}
