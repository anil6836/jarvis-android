package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🗺️ The map board: where his scans were taken, the parked bike, home and saved places, the spots he marked as
 * dangerous, and where his rides began and ended (a soft heat of the places he goes most). Map tiles come from
 * OpenStreetMap (© OpenStreetMap contributors; cached on the phone, shown as a dot grid when offline). Tap a pin for
 * what it is and to open it.
 */
public class MapActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private MapView map;
    private TextView hint;
    private final Set<String> kinds = new HashSet<>(java.util.Arrays.asList("scan", "parking", "hazard", "place", "ride"));

    static void open(Context c) { c.startActivity(new Intent(c, MapActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    /** A pin. */
    static final class Pin {
        String kind, title, sub, ref;
        double lat, lon;
        long t;
        Pin(String kind, String title, String sub, double lat, double lon, long t, String ref) { this.kind = kind; this.title = title; this.sub = sub; this.lat = lat; this.lon = lon; this.t = t; this.ref = ref; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04101B);
        map = new MapView(this);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE604101B, 0x0004101B}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(icon("✕", v -> finish()));
        TextView t = Ui.mono(this, "🗺️ మ్యాప్ బోర్డ్", 15, Ui.CYAN);
        t.setPadding(dp(6), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(icon("📍", v -> map.goHere()));
        top.addView(bar);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(8), 0, dp(8), dp(4));
        String[][] f = {{"scan", "📷 స్కాన్‌లు"}, {"parking", "🅿️ బండి"}, {"hazard", "⚠️ ప్రమాదాలు"}, {"place", "🏠 చోట్లు"}, {"ride", "🏍️ రైడ్‌లు (వేడి)"}};
        for (String[] k : f) {
            TextView c = chip(k[1], true);
            c.setOnClickListener(v -> { if (kinds.contains(k[0])) kinds.remove(k[0]); else kinds.add(k[0]); c.setBackground(Ui.round(this, kinds.contains(k[0]) ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, kinds.contains(k[0]) ? Ui.CYAN : 0x55FFFFFF, 999)); map.invalidate(); });
            chips.addView(c);
        }
        hs.addView(chips);
        top.addView(hs);
        hint = Ui.text(this, "", 12.5f, Ui.alpha(Ui.CYAN, 0xDD));
        hint.setPadding(dp(14), 0, dp(14), dp(6));
        top.addView(hint);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        TextView attr = Ui.text(this, "© OpenStreetMap contributors", 10.5f, 0xFFDDEFF5);
        attr.setBackgroundColor(0x8804101B);
        attr.setPadding(dp(6), dp(2), dp(6), dp(2));
        root.addView(attr, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        setContentView(root);
        loadPins();
    }

    @Override protected void onDestroy() { super.onDestroy(); work.shutdownNow(); }

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView icon(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 20, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private TextView chip(String s, boolean lit) {
        TextView t = Ui.text(this, s, 12.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        t.setBackground(Ui.round(this, lit ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, lit ? Ui.CYAN : 0x55FFFFFF, 999));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    // ---- what goes on the map

    private void loadPins() {
        work.execute(() -> {
            List<Pin> pins = new ArrayList<>();
            List<double[]> heat = new ArrayList<>();
            try {
                for (JSONObject o : ScanStore.list(this, false)) {
                    if (!o.has("lat")) continue;
                    pins.add(new Pin("scan", o.optString("title"), new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date(o.optLong("t"))), o.optDouble("lat"), o.optDouble("lon"), o.optLong("t"), o.optString("id")));
                }
            } catch (Exception ignored) {}
            try {
                JSONObject pk = Travel.parking(this);
                if (pk != null && pk.has("lat")) pins.add(new Pin("parking", "🅿️ బండి పెట్టిన చోటు", new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date(pk.optLong("at"))), pk.optDouble("lat"), pk.optDouble("lon"), pk.optLong("at"), ""));
            } catch (Exception ignored) {}
            try {
                JSONObject hm = Travel.home(this);
                if (hm != null && hm.has("lat")) pins.add(new Pin("place", "🏠 ఇల్లు", "", hm.optDouble("lat"), hm.optDouble("lon"), 0, ""));
                org.json.JSONArray names = GeoReminders.places(this);
                for (int i = 0; i < names.length(); i++) {
                    String n = names.optString(i);
                    double[] ll = GeoReminders.place(this, n);
                    if (ll != null && !GeoReminders.key(n).equals("home")) pins.add(new Pin("place", "📌 " + n, "", ll[0], ll[1], 0, ""));
                }
            } catch (Exception ignored) {}
            try {
                for (JSONObject h : Hazards.all(this)) pins.add(new Pin("hazard", "⚠️ " + h.optString("what"), new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(new java.util.Date(h.optLong("t"))), h.optDouble("lat"), h.optDouble("lon"), h.optLong("t"), h.optString("id")));
            } catch (Exception ignored) {}
            try {
                for (JSONObject r : Bike.list(this, "rides", System.currentTimeMillis() - 120L * 86_400_000L)) {
                    for (String k : new String[]{"from", "to"}) {
                        String[] p = r.optString(k).split(",");
                        if (p.length == 2) { try { heat.add(new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())}); } catch (Exception ignored) {} }
                    }
                }
            } catch (Exception ignored) {}
            final List<Pin> ps = pins;
            final List<double[]> ht = heat;
            main.post(() -> {
                map.setData(ps, ht);
                hint.setText(ps.isEmpty() && ht.isEmpty() ? "ఇంకా గుర్తులు లేవు: స్కాన్‌లు, పార్కింగ్, ప్రమాద చోట్లు, రైడ్‌లు ఇక్కడ కనిపిస్తాయి (ఇప్పటి నుంచి తీసే స్కాన్‌లకు చోటు కూడా గుర్తుంటుంది)."
                        : ps.size() + " గుర్తులు · " + ht.size() / 2 + " రైడ్‌లు. పిన్ నొక్కితే వివరాలు.");
                map.goHere();
            });
        });
    }

    private void pinTapped(Pin p) {
        List<String> items = new ArrayList<>();
        items.add("🧭 దారి (Google Maps)");
        if (p.kind.equals("scan")) items.add("📷 స్కాన్ తెరువు");
        if (p.kind.equals("hazard")) items.add("🗑️ ఈ ప్రమాద గుర్తు తీసేయి");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(p.title).setMessage(p.sub.isEmpty() ? null : p.sub)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + p.lat + "," + p.lon + "(" + Uri.encode(p.title) + ")"))); } catch (Exception e) { Toast.makeText(this, "మ్యాప్ యాప్ లేదు", Toast.LENGTH_SHORT).show(); } }
                    else if (p.kind.equals("scan")) startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_SCAN, p.ref));
                    else if (p.kind.equals("hazard")) { Hazards.remove(this, p.ref); loadPins(); }
                }).setNegativeButton("మూసేయ్", null).show();
    }

    // ================================================================ the map itself

    final class MapView extends View {
        private final List<Pin> pins = new ArrayList<>();
        private final List<double[]> heat = new ArrayList<>();
        private double cLat = 17.385, cLon = 78.4867; // (Hyderabad until a fix comes)
        private float zoom = 13f;
        private final java.util.Map<String, Bitmap> tiles = new java.util.LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) { return size() > 60; }
        };
        private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaler;
        private final GestureDetector taps;
        private double[] me;
        private boolean offline;

        MapView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            txt.setTextSize(11 * getResources().getDisplayMetrics().density);
            txt.setColor(0xFFFFFFFF);
            txt.setShadowLayer(3, 0, 0, 0xFF000000);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    double[] under = lonLatAt(g.getFocusX(), g.getFocusY());
                    zoom = Math.max(4f, Math.min(19f, zoom + (float) (Math.log(g.getScaleFactor()) / Math.log(2))));
                    double[] after = lonLatAt(g.getFocusX(), g.getFocusY());
                    cLon += under[0] - after[0];
                    cLat += under[1] - after[1];
                    invalidate();
                    return true;
                }
            });
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    double n = Math.pow(2, zoom) * 256;
                    cLon += dx / n * 360;
                    double my = (1 - Math.log(Math.tan(Math.toRadians(cLat)) + 1 / Math.cos(Math.toRadians(cLat))) / Math.PI) / 2 * n + dy;
                    cLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * my / n))));
                    cLat = Math.max(-85, Math.min(85, cLat));
                    invalidate();
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    Pin best = null;
                    float bd = 28 * getResources().getDisplayMetrics().density;
                    for (Pin p : pins) {
                        if (!kinds.contains(p.kind)) continue;
                        float[] xy = xy(p.lat, p.lon);
                        float d = (float) Math.hypot(xy[0] - e.getX(), xy[1] - e.getY() + 10 * getResources().getDisplayMetrics().density);
                        if (d < bd) { bd = d; best = p; }
                    }
                    if (best != null) pinTapped(best);
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) { zoom = Math.min(19f, zoom + 1); invalidate(); return true; }
            });
        }

        void setData(List<Pin> p, List<double[]> h) {
            pins.clear(); pins.addAll(p);
            heat.clear(); heat.addAll(h);
            if (!pins.isEmpty() && me == null) { cLat = pins.get(0).lat; cLon = pins.get(0).lon; }
            invalidate();
        }

        void goHere() {
            work.execute(() -> {
                android.location.Location l = null;
                try { l = Travel.here(MapActivity.this); } catch (Exception ignored) {}
                final android.location.Location ll = l;
                main.post(() -> {
                    if (ll == null) { Toast.makeText(MapActivity.this, "లొకేషన్ రాలేదు (ఆన్ ఉందా?)", Toast.LENGTH_SHORT).show(); return; }
                    me = new double[]{ll.getLatitude(), ll.getLongitude()};
                    cLat = ll.getLatitude(); cLon = ll.getLongitude();
                    if (zoom < 14) zoom = 15;
                    invalidate();
                });
            });
        }

        // ---- web mercator
        private float[] xy(double lat, double lon) {
            double n = Math.pow(2, zoom) * 256;
            double x = (lon + 180) / 360 * n, cx = (cLon + 180) / 360 * n;
            double y = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n;
            double cy = (1 - Math.log(Math.tan(Math.toRadians(cLat)) + 1 / Math.cos(Math.toRadians(cLat))) / Math.PI) / 2 * n;
            return new float[]{(float) (getWidth() / 2 + x - cx), (float) (getHeight() / 2 + y - cy)};
        }

        private double[] lonLatAt(float sx, float sy) {
            double n = Math.pow(2, zoom) * 256;
            double cx = (cLon + 180) / 360 * n, cy = (1 - Math.log(Math.tan(Math.toRadians(cLat)) + 1 / Math.cos(Math.toRadians(cLat))) / Math.PI) / 2 * n;
            double x = cx + sx - getWidth() / 2.0, y = cy + sy - getHeight() / 2.0;
            return new double[]{x / n * 360 - 180, Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))};
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) taps.onTouchEvent(e);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            int z = Math.max(0, Math.min(19, Math.round(zoom)));
            double scale = Math.pow(2, zoom - z); // (between tile levels the tiles are stretched)
            double n = Math.pow(2, z);
            double cx = (cLon + 180) / 360 * n, cy = (1 - Math.log(Math.tan(Math.toRadians(cLat)) + 1 / Math.cos(Math.toRadians(cLat))) / Math.PI) / 2 * n;
            float size = (float) (256 * scale);
            int tx0 = (int) Math.floor(cx - W / 2f / size), tx1 = (int) Math.floor(cx + W / 2f / size), ty0 = (int) Math.floor(cy - H / 2f / size), ty1 = (int) Math.floor(cy + H / 2f / size);
            boolean any = false;
            for (int tx = tx0; tx <= tx1; tx++) for (int ty = ty0; ty <= ty1; ty++) {
                if (ty < 0 || ty >= n) continue;
                int wx = (int) (((tx % (long) n) + (long) n) % (long) n);
                float left = (float) (W / 2f + (tx - cx) * size), top = (float) (H / 2f + (ty - cy) * size);
                String key = z + "/" + wx + "/" + ty;
                Bitmap b = tiles.get(key);
                if (b == null) { fetch(key); drawGrid(c, left, top, size); continue; }
                any = true;
                c.drawBitmap(b, null, new RectF(left, top, left + size + 0.5f, top + size + 0.5f), pt);
            }
            if (!any) { // a dark map background with a faint grid (offline / loading)
                ring.setColor(0x22FFFFFF); ring.setStrokeWidth(1);
            }
            // a dim wash so the pins and heat stand out
            c.drawColor(0x5504101B);
            // heat: where his rides begin and end
            if (kinds.contains("ride") && !heat.isEmpty()) {
                float r = Math.max(18, 40 * (zoom - 10) / 6f) * getResources().getDisplayMetrics().density;
                for (double[] h : heat) {
                    float[] xy = xy(h[0], h[1]);
                    if (xy[0] < -r || xy[1] < -r || xy[0] > W + r || xy[1] > H + r) continue;
                    pt.setShader(new RadialGradient(xy[0], xy[1], r, new int[]{0x66FF8A3D, 0x22FF8A3D, 0x00FF8A3D}, null, Shader.TileMode.CLAMP));
                    c.drawCircle(xy[0], xy[1], r, pt);
                    pt.setShader(null);
                }
            }
            // pins
            float d = getResources().getDisplayMetrics().density;
            for (Pin p : pins) {
                if (!kinds.contains(p.kind)) continue;
                float[] xy = xy(p.lat, p.lon);
                if (xy[0] < -40 || xy[1] < -40 || xy[0] > W + 40 || xy[1] > H + 40) continue;
                int col = p.kind.equals("scan") ? Ui.CYAN : p.kind.equals("parking") ? 0xFF60A5FA : p.kind.equals("hazard") ? 0xFFFF4D5E : Ui.C_GREEN;
                ring.setColor(Ui.alpha(col, 70)); ring.setStrokeWidth(6 * d);
                c.drawCircle(xy[0], xy[1], 9 * d, ring);
                pt.setColor(col);
                c.drawCircle(xy[0], xy[1], 6 * d, pt);
                pt.setColor(0xFF04101B);
                c.drawCircle(xy[0], xy[1], 2.5f * d, pt);
                if (zoom >= 14) {
                    String s = p.title.length() > 18 ? p.title.substring(0, 17) + "…" : p.title;
                    c.drawText(s, xy[0] + 10 * d, xy[1] + 4 * d, txt);
                }
            }
            if (me != null) {
                float[] xy = xy(me[0], me[1]);
                pt.setColor(0xFFFFFFFF);
                c.drawCircle(xy[0], xy[1], 7 * d, pt);
                pt.setColor(0xFF2563EB);
                c.drawCircle(xy[0], xy[1], 5 * d, pt);
            }
            if (offline) c.drawText("📴 నెట్ లేదు: దాచిన మ్యాప్ ముక్కలే", 12 * d, H - 30 * d, txt);
        }

        private void drawGrid(Canvas c, float left, float top, float size) {
            pt.setColor(0xFF071A2B);
            c.drawRect(left, top, left + size, top + size, pt);
            ring.setColor(0x14FFFFFF); ring.setStrokeWidth(1);
            c.drawRect(left, top, left + size, top + size, ring);
        }

        private void fetch(String key) {
            if (loading.contains(key) || failed.contains(key)) return;
            loading.add(key);
            work.execute(() -> {
                Bitmap b = null;
                try {
                    File dir = new File(getCacheDir(), "tiles/" + key.substring(0, key.lastIndexOf('/')));
                    File f = new File(dir, key.substring(key.lastIndexOf('/') + 1) + ".png");
                    if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) b = BitmapFactory.decodeFile(f.getPath());
                    if (b == null && Net.online(MapActivity.this)) {
                        byte[] bytes = Coder.download("https://tile.openstreetmap.org/" + key + ".png", "User-Agent", "Jarvis-Android personal assistant (one user)", "Accept", "image/png");
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (b != null) { dir.mkdirs(); try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); } }
                    } else if (b == null) offline = true;
                } catch (Exception ignored) {}
                final Bitmap bb = b;
                main.post(() -> { loading.remove(key); if (bb != null) tiles.put(key, bb); else failed.add(key); invalidate(); });
            });
        }
    }
}
