package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🗺️ Jarvis Maps: a map of his own, like Jarvis camera. With his Google Maps key: Google's map with Telugu names,
 * traffic and satellite, search, places along his way with km ahead and open-now, routes with today's traffic, km
 * and toll, and the way set in Google Maps on his tap. Two big buttons for the car: "🛣️ ఈ రోడ్డు ఎటు?" (where this
 * road goes, the towns and tolls ahead, what Google Maps knows about it — said in Telugu) and "😴 నిద్ర వస్తోంది"
 * (the nearest open tea / coffee stop ahead, offered; the way set only on his tap). Without a key it still works on
 * the free map (OpenStreetMap), and says so.
 */
public class JarvisMaps extends Activity implements JarvisMapView.Listener {
    static final String EXTRA_Q = "jmaps_q";
    private static final int REQ_LOC = 71, REQ_VOICE = 72;

    static void open(Context c, String query) { c.startActivity(intent(c, query)); }

    /** Opens it (or brings it up again with a new search: one Jarvis Maps at a time). */
    static Intent intent(Context c, String query) {
        return new Intent(c, JarvisMaps.class).putExtra(EXTRA_Q, query == null ? "" : query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private Prefs p;
    private JarvisMapView map;
    private EditText search;
    private LinearLayout panel;
    private TextView trafficBtn, satBtn;
    private LocationManager lm;
    private boolean bike;
    private float heading = -1;

    private final List<GMaps.Place> found = new ArrayList<>();
    private String foundTitle = "";
    private List<GMaps.Route> routes = new ArrayList<>();
    private int routeSel;
    private GMaps.Place target;
    private int gen; // (a newer ask makes an older answer drop)
    private boolean askedLoc, askedFine, hadKey, alongStops; // (asked for location once; the key when last seen; the places shown are stops on his way)
    private boolean googleOff; // (Google's map refused this time: the free map, and nothing of Google's drawn on it)
    private int creditSeq, sourceTries, sourceSeq;
    private long creditAt, renewedAt;
    private String keyTag = ""; // (which key the map was made with: a new key tries Google again)
    private final Runnable retrySource = this::source;
    private final Runnable renewLater = this::sessionExpired;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        bike = Bike.riding(this);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04101B);
        map = new JarvisMapView(this);
        map.listener = this;
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // top: ✕, search, 🎙️, then the quick chips
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF204101B, 0x0004101B}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(icon("✕", v -> finish()));
        search = new EditText(this);
        search.setHint("ఎక్కడికి? / ఏం వెతకాలి?");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(Ui.round(this, 0x66000000, 0x55FFFFFF, 999));
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER) { doSearch(search.getText().toString()); return true; }
            return false;
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(icon("🎙️", v -> voiceSearch()));
        top.addView(bar);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(8), 0, dp(8), dp(6));
        String[][] cs = {{"☕ టీ / కాఫీ", "tea"}, {"⛽ పెట్రోల్", "petrol"}, {"⚡ EV ఛార్జర్", "ev charger"}, {"🍛 హోటల్ / దాబా", "restaurant"},
                {"🏧 ATM", "atm"}, {"🏥 హాస్పిటల్", "hospital"}, {"💊 మెడికల్", "medical"}, {"🚻 టాయిలెట్", "toilet"}, {"🅿️ పార్కింగ్", "parking"}, {"🔧 పంక్చర్", "puncture"}};
        for (String[] c : cs) {
            TextView t = chip(c[0]);
            t.setOnClickListener(v -> along(c[1], c[0]));
            chips.addView(t);
        }
        hs.addView(chips);
        top.addView(hs);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // right: follow me, traffic, satellite
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(round("📍", v -> followMe()));
        trafficBtn = round("🚦", v -> { p.sp.edit().putBoolean("maps_traffic", !p.sp.getBoolean("maps_traffic", false)).apply(); source(); });
        satBtn = round("🛰️", v -> { p.sp.edit().putBoolean("maps_sat", !p.sp.getBoolean("maps_sat", false)).apply(); source(); });
        side.addView(trafficBtn);
        side.addView(satBtn);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        sl.setMargins(0, 0, dp(8), dp(60));
        root.addView(side, sl);

        // bottom: the panel (buttons, results, a place, a route, an answer)
        ScrollView sv = new ScrollView(this) { // (never taller than about half the screen: the map stays in sight)
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (getResources().getDisplayMetrics().heightPixels * 0.46f), MeasureSpec.AT_MOST));
            }
        };
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(28));
        panel.setBackground(Ui.round(this, 0xF2071624, 0x3322D3EE, 18));
        sv.addView(panel);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pl.setMargins(dp(6), 0, dp(6), dp(6));
        root.addView(sv, pl);
        sv.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> map.setInset(v.getHeight() + dp(6))); // (the credit and the map's middle above the panel)
        setContentView(root);

        lm = getSystemService(LocationManager.class);
        hadKey = GMaps.has(this);
        keyTag = keyTag();
        home();
        source();
        loadPins();
        run(() -> { try { JarvisMapView.tidy(this); } catch (Exception ignored) {} });
        if (!hasLoc()) { askedLoc = true; askLoc(); }
        searchFrom(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (googleOff && GMaps.has(this)) { googleOff = false; sourceTries = 0; source(); home(); } // (opened again: Google tried again, as the message said)
        searchFrom(i);
    }

    /** The key's tag (never the key): "" without one. */
    private String keyTag() { return GMaps.has(this) ? Integer.toHexString(GMaps.key(this).hashCode()) : ""; }

    private void searchFrom(Intent i) {
        String q = i == null ? null : i.getStringExtra(EXTRA_Q);
        if (q != null && !q.trim().isEmpty()) main.postDelayed(() -> { if (!isDestroyed()) { search.setText(q); doSearch(q); } }, 600);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasLoc()) startLocation(); // (asked once, in onCreate: never again on every return)
        String kt = keyTag();
        if (!kt.equals(keyTag)) { // (the key was put in / changed / taken out in Settings: a fresh start, nothing of the old map kept)
            keyTag = kt;
            hadKey = GMaps.has(this);
            clearGoogle();
            googleOff = false;
            sourceTries = 0;
            final Context app = getApplicationContext();
            run(() -> { GMaps.dropSessions(app); main.post(() -> { if (!isDestroyed()) source(); }); }); // (the old key's session never used for the new one)
            home();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (lm != null) lm.removeUpdates(loc); } catch (Exception ignored) {}
    }

    @Override protected void onStop() {
        super.onStop();
        map.trim(); // (the map's pictures let go while it is not seen)
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        map.shutdown();
        work.shutdownNow();
    }

    /** Work off the main thread; nothing once the screen is gone. */
    private void run(Runnable r) {
        if (work.isShutdown()) return;
        try { work.execute(r); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /** Google's map is on the screen (so Google's places and routes may be drawn on it). */
    private boolean googleMap() { return GMaps.has(this) && !googleOff && map.google(); }

    private int dp(float v) { return Ui.dp(this, v); }

    // ---------------------------------------------------------------- small views

    private TextView icon(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 20, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private TextView round(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 19, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, 0xCC071624, 0x5522D3EE, 999));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(0, dp(5), 0, dp(5));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView chip(String s) {
        TextView t = Ui.text(this, s, 13, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(Ui.round(this, 0x99071624, 0x6622D3EE, 999));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView button(String s, int fillCol, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        t.setBackground(Ui.round(this, fillCol, 0x44FFFFFF, 14));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            r.addView(v, lp);
        }
        return r;
    }

    private void line(String s, float sp, int col) {
        TextView t = Ui.text(this, s, sp, col);
        t.setPadding(dp(2), dp(3), dp(2), dp(3));
        panel.addView(t);
    }

    private void busy(String s) {
        panel.removeAllViews();
        line("⏳ " + s, 14.5f, 0xFFBFE9F5);
    }

    // ---------------------------------------------------------------- the panel's states

    private void home() {
        panel.removeAllViews();
        panel.addView(row(button("🛣️ ఈ రోడ్డు ఎటు?", 0xFF0E4A6B, v -> roadAhead()), button("😴 నిద్ర వస్తోంది", 0xFF6B3A0E, v -> sleepy())));
        if (!GMaps.has(this)) {
            line("ఉచిత మ్యాప్ (OpenStreetMap). ⚙️ → 🗺️ Jarvis Maps లో Google Maps key పెడితే: తెలుగు పేర్ల Google మ్యాప్, ట్రాఫిక్, దారిలో షాప్‌లు ఇంకా బాగా.", 12, 0xAAFFFFFF);
            TextView set = button("⚙️ Maps key పెట్టు", 0xFF1F2937, v -> startActivity(new Intent(this, SettingsActivity.class)));
            panel.addView(row(set));
        } else line("పైన వెతకండి, లేదా ఒక చిప్ నొక్కండి. 📍 = నేను ఎక్కడ, 🚦 = ట్రాఫిక్, 🛰️ = శాటిలైట్.", 12, 0xAAFFFFFF);
    }

    private void results() {
        panel.removeAllViews();
        line(foundTitle, 15, 0xFFFFFFFF);
        if (found.isEmpty()) line("ఏమీ దొరకలేదు.", 13.5f, 0xCCFFFFFF);
        for (int i = 0; i < found.size() && i < 8; i++) {
            final int k = i;
            GMaps.Place x = found.get(i);
            StringBuilder s = new StringBuilder();
            s.append(i + 1).append(". ").append(x.name);
            String facts = facts(x);
            TextView t = Ui.text(this, s + (facts.isEmpty() ? "" : "\n   " + facts), 14, 0xFFFFFFFF);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            t.setBackground(Ui.round(this, 0x33000000, 0x00000000, 10));
            t.setOnClickListener(v -> place(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, dp(3));
            panel.addView(t, lp);
        }
        panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> { map.setPlaces(null); map.setRoutes(null, 0); home(); })));
    }

    /** "6.2 కి.మీ ముందు · 9 ని · తెరిచి ఉంది · ⭐ 4.3". */
    private static String facts(GMaps.Place x) {
        List<String> f = new ArrayList<>();
        if (x.aheadM >= 0) f.add(MapMath.distance(x.aheadM) + (x.aheadS > 0 ? " ముందు · " + MapMath.duration(x.aheadS) : ""));
        if (x.open != null) f.add(x.open ? "తెరిచి ఉంది" : "ఇప్పుడు మూసి ఉంది");
        if (x.rating > 0) f.add("⭐ " + x.rating + (x.ratings > 0 ? " (" + x.ratings + ")" : ""));
        return TextUtils.join(" · ", f);
    }

    private void place(int i) {
        if (i < 0 || i >= found.size()) return;
        target = found.get(i);
        map.selectPlace(i);
        map.centerOn(target.lat, target.lon, Math.max(map.zoom, 15));
        panel.removeAllViews();
        line(target.name, 17, 0xFFFFFFFF);
        if (!target.address.isEmpty()) line(target.address, 12.5f, 0xBBFFFFFF);
        String f = facts(target);
        if (!f.isEmpty()) line(f, 13.5f, 0xFFBFE9F5);
        panel.addView(row(button("🧭 దారి చూపు", 0xFF0E4A6B, v -> route()), button("▶️ వెళ్దాం", 0xFF15803D, v -> go(target))));
        panel.addView(row(button("🔗 షేర్", 0xFF1F2937, v -> share(target)), button("← లిస్ట్", 0xFF1F2937, v -> { map.selectPlace(-1); results(); })));
    }

    // ---------------------------------------------------------------- where he is

    private final LocationListener loc = new LocationListener() {
        @Override public void onLocationChanged(Location l) { here(l); }
        @Override public void onProviderEnabled(String s) {}
        @Override public void onProviderDisabled(String s) {}
        @Override public void onStatusChanged(String s, int st, Bundle b) {}
    };

    private void here(Location l) {
        if (l == null) return;
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 2) heading = l.getBearing();
        map.setMe(l.getLatitude(), l.getLongitude(), heading, l.hasAccuracy() ? l.getAccuracy() : 0);
        if (map.follow && System.currentTimeMillis() - creditAt > 60_000) settled(); // (following him: the credit kept right for where the map is)
    }

    /** Either kind of location (an "about here" one is enough to show the map around him). */
    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLoc() { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC); }

    private void startLocation() {
        if (!hasLoc()) {
            if (!askedLoc) { askedLoc = true; askLoc(); }
            else Toast.makeText(this, "లొకేషన్ అనుమతి లేదు: ఫోన్ Settings → Apps → Jarvis → Permissions → Location.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        try { // (GPS only with the exact permission; the network one with either)
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 2, loc, Looper.getMainLooper());
        } catch (Exception ignored) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 5, loc, Looper.getMainLooper());
        } catch (Exception ignored) {}
        try {
            Location last = fine ? lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) here(last);
        } catch (Exception ignored) {}
        if (!fine && !askedFine) { askedFine = true; Toast.makeText(this, "సుమారు లొకేషన్ మాత్రమే ఉంది: దారిలో షాప్‌లు సరిగ్గా రావాలంటే Location → \"Precise\" ఆన్ చేయండి.", Toast.LENGTH_LONG).show(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC && hasLoc()) startLocation();
        else if (code == REQ_LOC) Toast.makeText(this, "లొకేషన్ అనుమతి ఇస్తేనే మీరు ఎక్కడ ఉన్నారో చూపిస్తాను.", Toast.LENGTH_LONG).show();
    }

    private void followMe() {
        map.follow = true;
        double[] m = map.me();
        if (m == null) { Toast.makeText(this, "లొకేషన్ ఇంకా రాలేదు (ఆన్ ఉందా?)", Toast.LENGTH_SHORT).show(); startLocation(); return; }
        map.centerOn(m[0], m[1], Math.max(map.zoom, 16));
        map.follow = true;
    }

    // ---------------------------------------------------------------- the map's pictures and their credit

    private String session = "";

    private void source() {
        boolean sat = p.sp.getBoolean("maps_sat", false), tr = p.sp.getBoolean("maps_traffic", false);
        trafficBtn.setAlpha(tr ? 1f : 0.55f);
        satBtn.setAlpha(sat ? 1f : 0.55f);
        if (!GMaps.has(this) || googleOff) {
            map.setSource("", false, false);
            if (sat || tr) Toast.makeText(this, "ట్రాఫిక్ / శాటిలైట్ Google మ్యాప్‌తోనే" + (googleOff ? " (అది ఇప్పుడు రాలేదు)." : ": ⚙️ → 🗺️ లో Maps key పెట్టండి."), Toast.LENGTH_LONG).show();
            return;
        }
        main.removeCallbacks(retrySource);
        final int seq = ++sourceSeq; // (two quick 🚦 / 🛰️ taps: only the last one's map is shown)
        run(() -> {
            String s = null, err = null;
            boolean refused = false;
            try { s = GMaps.session(this, sat, tr); }
            catch (Exception e) { err = GMaps.telugu(e); refused = e instanceof GMaps.Error && ((GMaps.Error) e).status >= 400 && ((GMaps.Error) e).status < 500; }
            final String ses = s, er = err;
            final boolean no = refused;
            main.post(() -> {
                if (isDestroyed() || googleOff || seq != sourceSeq) return;
                if (ses == null && no) { googleFailed(er); return; } // (Google said no: key, billing, the day's limit)
                if (ses == null) { // (no internet / Google slow: the map as it is, tried again in a while)
                    if (session.isEmpty()) map.setSource("", false, false); // (nothing yet: the free map's kept pictures meanwhile)
                    if (sourceTries++ < 6) main.postDelayed(retrySource, 30_000);
                    Toast.makeText(this, "Google మ్యాప్ ఇంకా రాలేదు (" + er + "). కాసేపట్లో మళ్ళీ ప్రయత్నిస్తాను.", Toast.LENGTH_SHORT).show();
                    return;
                }
                sourceTries = 0;
                session = ses;
                map.setSource(ses, sat, tr);
                settled();
            });
        });
    }

    /** Google's picture session ran out (or was refused as old): a new one, at most once a minute. */
    @Override public void sessionExpired() {
        if (isDestroyed() || googleOff) return;
        long since = System.currentTimeMillis() - renewedAt;
        if (since < 60_000) { main.removeCallbacks(renewLater); main.postDelayed(renewLater, 60_000 - since); return; } // (just renewed: once more after the minute)
        renewedAt = System.currentTimeMillis();
        final Context app = getApplicationContext();
        run(() -> {
            GMaps.dropSessions(app);
            main.post(() -> { if (!isDestroyed()) source(); });
        });
    }

    @Override public void placeTapped(int index) { place(index); }

    @Override public void moved() {}

    /** The free map, with nothing of Google's left on it (places, routes, the road line, the credit); answers on their way dropped. */
    private void clearGoogle() {
        main.removeCallbacks(retrySource);
        sourceSeq++;
        session = "";
        map.setSource("", false, false);
        map.setRoutes(null, 0);
        map.setAhead(null);
        map.setPlaces(null);
        map.setCredit("");
        found.clear();
        routes = new ArrayList<>();
        target = null;
        gen++;
    }

    /** Google refused (key, billing, quota): its kept sessions dropped, the free map shown, the reason said; nothing of Google's drawn on it. */
    @Override public void googleFailed(String why) {
        if (isDestroyed() || googleOff) return;
        googleOff = true;
        run(() -> GMaps.dropSessions(getApplicationContext()));
        clearGoogle();
        home();
        line("⚠️ Google మ్యాప్ రాలేదు: " + why + " ఇప్పుడు ఉచిత మ్యాప్ (OpenStreetMap). మళ్ళీ తెరిస్తే Google మళ్ళీ ప్రయత్నిస్తాను.", 12.5f, 0xFFFFD08A);
    }

    @Override public void settled() {
        if (!googleMap() || session.isEmpty()) return;
        final double[] bb = map.bounds();
        if (bb == null) { main.postDelayed(this::settled, 400); return; } // (not laid out yet)
        final int z = Math.round(map.zoom);
        final String ses = session;
        final int seq = ++creditSeq;
        creditAt = System.currentTimeMillis();
        run(() -> {
            try {
                String cr = GMaps.copyright(this, ses, z, bb[0], bb[1], bb[2], bb[3]);
                main.post(() -> { if (seq == creditSeq) map.setCredit(cr); }); // (an older answer arriving late is dropped)
            } catch (Exception ignored) {}
        });
    }

    // ---------------------------------------------------------------- his own pins

    private void loadPins() {
        run(() -> {
            List<JarvisMapView.Pin> ps = new ArrayList<>();
            try {
                JSONObject hm = Travel.home(this);
                if (hm != null && hm.has("lat")) ps.add(new JarvisMapView.Pin(hm.optDouble("lat"), hm.optDouble("lon"), "🏠 ఇల్లు", 0xFF22C55E));
            } catch (Exception ignored) {}
            try {
                JSONObject pk = Travel.parking(this);
                if (pk != null && pk.has("lat")) ps.add(new JarvisMapView.Pin(pk.optDouble("lat"), pk.optDouble("lon"), "🅿️ బండి", 0xFF60A5FA));
            } catch (Exception ignored) {}
            try {
                JSONArray names = GeoReminders.places(this);
                for (int i = 0; i < names.length(); i++) {
                    String n = names.optString(i);
                    double[] ll = GeoReminders.place(this, n);
                    if (ll != null && !GeoReminders.key(n).equals("home") && !GeoReminders.key(n).equals("parking")) ps.add(new JarvisMapView.Pin(ll[0], ll[1], "📌 " + n, 0xFF22C55E));
                }
            } catch (Exception ignored) {}
            try {
                for (JSONObject h : Hazards.all(this)) ps.add(new JarvisMapView.Pin(h.optDouble("lat"), h.optDouble("lon"), "⚠️ " + h.optString("what"), 0xFFFF4D5E));
            } catch (Exception ignored) {}
            main.post(() -> { if (!isDestroyed()) map.setPins(ps); });
        });
    }

    // ---------------------------------------------------------------- searching

    private void hideKeys() {
        try { getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0); } catch (Exception ignored) {}
    }

    private void voiceSearch() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "ఎక్కడికి? / ఏం వెతకాలి?"), REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "ఈ ఫోన్‌లో మాట వెతకడం లేదు; టైప్ చేయండి.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null) return;
        ArrayList<String> got = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (got == null || got.isEmpty()) return;
        search.setText(got.get(0));
        doSearch(got.get(0));
    }

    /** A kind of place (tea, petrol…) → along his way; a name / address → Google's search near him (or the phone's address finder without a key). */
    private void doSearch(String q) {
        String w = q == null ? "" : q.trim();
        if (w.isEmpty()) return;
        hideKeys();
        if (Drive.kindOnly(w)) { along(w, "🔍 " + w); return; } // ("టీ", "petrol bunk": a kind; "Apollo hospital", "Coffee Day": a name)
        final int my = ++gen;
        busy("\"" + w + "\" వెతుకుతున్నాను…");
        double[] at = map.me() != null ? map.me() : new double[]{map.cLat, map.cLon};
        final boolean g = googleMap(); // (Google's places only for Google's map: fixed now, checked again when the answer comes)
        run(() -> {
            List<GMaps.Place> ps = new ArrayList<>();
            String err = null;
            try {
                if (g) ps = GMaps.search(this, w, at[0], at[1], 30000, null, false, 10);
                else {
                    double[] ll = Places.geocode(this, w);
                    if (ll != null) {
                        GMaps.Place x = new GMaps.Place();
                        x.name = w; x.lat = ll[0]; x.lon = ll[1]; x.aheadM = MapMath.meters(at[0], at[1], ll[0], ll[1]);
                        ps.add(x);
                    }
                }
            } catch (Exception e) { err = GMaps.telugu(e); }
            final List<GMaps.Place> res = ps;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g != googleMap()) { doSearch(w); return; } // (the map changed kind meanwhile: asked again for the map now shown)
                if (er != null) { home(); line("⚠️ " + er, 13, 0xFFFFB4B4); return; }
                alongStops = false;
                showFound("🔍 " + w + (g ? "" : " (ఉచిత మ్యాప్)"), res);
            });
        });
    }

    private void showFound(String title, List<GMaps.Place> ps) {
        found.clear();
        found.addAll(ps);
        foundTitle = title;
        map.setRoutes(null, 0);
        map.setPlaces(found);
        List<double[]> pts = new ArrayList<>();
        for (GMaps.Place x : found) pts.add(new double[]{x.lat, x.lon});
        if (map.me() != null && !pts.isEmpty()) pts.add(map.me());
        map.fit(pts);
        results();
        if (found.size() == 1) place(0);
    }

    /** Places of a kind along his way (Google with the key, the free map without; said which). */
    private void along(String what, String label) {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        busy(label + " వెతుకుతున్నాను…");
        final boolean g = googleMap();
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = Drive.along(this, what, 30, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g != googleMap()) { along(what, label); return; }
                if (res == null || !res.optBoolean("ok", false)) { home(); line("⚠️ " + (er != null ? er : notOk(res)), 13, 0xFFFFB4B4); return; }
                aheadLine();
                alongStops = true;
                showFound(label + " · " + searchedTe(res), places(res));
                if (res.has("google_error")) line("⚠️ Google: " + res.optString("google_error") + " (ఉచిత మ్యాప్ జవాబు)", 12, 0xFFFFD08A);
            });
        });
    }

    /** The drive helper's "not ok" (its words are for the brain, in English) said in Telugu. */
    private static String notOk(JSONObject r) {
        String e = r == null ? "" : r.optString("error");
        switch (e) {
            case "no_location": return "ఫోన్ లొకేషన్ రాలేదు. Location ఆన్‌లో ఉందా? (బయట / కిటికీ దగ్గర కాసేపు ఆగి మళ్ళీ నొక్కండి.)";
            case "missing": return "ఏం వెతకాలో చెప్పండి (టీ, పెట్రోల్, హోటల్…).";
            case "no_direction": return "మీరు ఏ వైపు వెళ్తున్నారో ఇంకా తెలియడం లేదు: కొంచెం కదిలాక మళ్ళీ నొక్కండి.";
            default: return r == null ? "జవాబు రాలేదు." : "జవాబు రాలేదు" + (e.isEmpty() ? "." : " (" + e + ").");
        }
    }

    private static String searchedTe(JSONObject r) {
        String s = r.optString("searched");
        String src = r.optString("source", "");
        String where = s.startsWith("along his route") ? "మీ దారిలో" : s.startsWith("along the road") || s.startsWith("straight ahead") ? "ముందు దారిలో" : "చుట్టుపక్కల";
        return where + (src.startsWith("Google") ? " (Google Maps)" : src.isEmpty() ? "" : " (ఉచిత మ్యాప్)");
    }

    /** The drive helper's answer → places for the map. */
    private static List<GMaps.Place> places(JSONObject r) {
        List<GMaps.Place> out = new ArrayList<>();
        JSONArray ps = r.optJSONArray("places");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject o = ps.optJSONObject(i);
            if (o == null || !o.has("lat")) continue;
            GMaps.Place x = new GMaps.Place();
            x.name = o.optString("name");
            x.address = o.optString("address", o.optString("side", ""));
            x.lat = o.optDouble("lat");
            x.lon = o.optDouble("lon");
            x.aheadM = o.has("km_ahead") ? o.optDouble("km_ahead") * 1000 : o.has("km") ? o.optDouble("km") * 1000 : -1;
            x.aheadS = o.has("minutes_ahead") ? o.optLong("minutes_ahead") * 60 : -1;
            if (o.has("open_now")) x.open = o.optBoolean("open_now");
            x.rating = o.optDouble("rating", 0);
            x.ratings = o.optInt("ratings", 0);
            out.add(x);
        }
        return out;
    }

    /** The road ahead Google gave (the last few minutes), drawn dashed. */
    private void aheadLine() {
        android.content.SharedPreferences st = Drive.settings(this);
        if (googleMap() && System.currentTimeMillis() - st.getLong("g_ahead_at", 0) < 5 * 60_000) map.setAhead(MapMath.decode(st.getString("g_ahead", ""))); // (Google's line only on Google's map)
        else map.setAhead(null);
    }

    // ---------------------------------------------------------------- 🛣️ where does this road go

    private void roadAhead() {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        busy("ఈ రోడ్డు ఎటు వెళ్తుందో చూస్తున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null, said = null;
            try {
                r = Drive.route(this);
                said = r.optBoolean("ok", false) ? tell(r) : notOk(r);
            } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err, text = said;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                panel.removeAllViews();
                if (er != null || text == null) { line("⚠️ " + (er != null ? er : "జవాబు రాలేదు."), 13.5f, 0xFFFFB4B4); panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> home()))); return; }
                aheadLine();
                List<double[]> ah = MapMath.decode(Drive.settings(this).getString("g_ahead", ""));
                if (googleMap() && map.me() != null && ah.size() >= 2 && System.currentTimeMillis() - Drive.settings(this).getLong("g_ahead_at", 0) < 5 * 60_000) map.fit(MapMath.cut(ah, 40));
                line("🛣️ " + text, 15, 0xFFFFFFFF);
                JSONObject g = res == null ? null : res.optJSONObject("google");
                JSONArray src = g == null ? null : g.optJSONArray("notes_from_google_maps"), links = g == null ? null : g.optJSONArray("notes_links");
                if (src != null && src.length() > 0) { // (Google's rule: the sources right after the words, named, each with its link)
                    for (int i = 0; i < src.length() && i < 5; i++) {
                        final String uri = links == null ? "" : links.optString(i, "");
                        TextView s = Ui.text(this, "Google Maps · " + src.optString(i), 12.5f, 0xFF9FC5E8);
                        s.setPadding(dp(2), dp(2), dp(2), dp(2));
                        if (!uri.isEmpty()) s.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))); } catch (Exception ignored) {} });
                        panel.addView(s);
                    }
                } else if (res != null && res.has("google_error")) line("⚠️ Google: " + res.optString("google_error") + " (ఉచిత మ్యాప్ జవాబు)", 12, 0xFFFFD08A);
                panel.addView(row(button("🔁 మళ్ళీ", 0xFF0E4A6B, v -> roadAhead()), button("← వెనక్కి", 0xFF1F2937, v -> { map.setAhead(null); home(); })));
                Announcer.say(this, text);
            });
        });
    }

    /** The drive helper's facts → a few short Telugu sentences (his brain), or plain ones made here when that fails. */
    private String tell(JSONObject r) {
        try {
            if (p.hasBrain()) {
                String s = Brain.oneShot(p, "You are Jarvis, Anil's assistant. Reply ONLY in simple spoken Telugu, 2 to 4 short sentences, no lists, no English except road numbers.",
                        "He is driving and asked 'ఈ రోడ్డు ఎటు వెళ్తుంది?'. From these facts only, say where this road goes (the next towns with km), "
                                + "time / km left if known, the toll if any, and one useful stop if the notes give one. Facts: " + r, null, false, 400);
                if (s != null && !s.trim().isEmpty()) return s.trim();
            }
        } catch (Exception ignored) {}
        // (no brain: the facts said plainly)
        StringBuilder b = new StringBuilder();
        JSONObject road = r.optJSONObject("road_now");
        if (road != null && !road.optString("ref").isEmpty()) b.append("మీరు ").append(road.optString("ref")).append(" మీద ఉన్నారు. ");
        JSONArray towns = r.optJSONArray("towns_ahead");
        if (towns != null && towns.length() > 0) {
            b.append("ముందు ");
            for (int i = 0; i < towns.length() && i < 4; i++) {
                JSONObject t = towns.optJSONObject(i);
                if (i > 0) b.append(", ");
                b.append(t.optString("name")).append(' ').append(t.optLong("km")).append(" కి.మీ.");
            }
            b.append(". ");
        }
        JSONObject g = r.optJSONObject("google");
        if (g != null) {
            if (!g.optString("via").isEmpty()) b.append(g.optString("via")).append(" మీదుగా. ");
            if (g.has("km_left")) b.append(g.optString("to")).append(" కి ఇంకా ").append(g.optLong("km_left")).append(" కి.మీ")
                    .append(g.has("minutes_left_with_traffic") ? ", ట్రాఫిక్‌తో " + MapMath.duration(g.optLong("minutes_left_with_traffic") * 60) : "").append(". ");
            JSONArray ra = g.optJSONArray("roads_ahead");
            if (ra != null && ra.length() > 0 && towns == null) b.append("ముందు: ").append(ra.optString(0)).append(". ");
            if (!g.optString("toll").isEmpty()) b.append("టోల్ ").append(g.optString("toll")).append(". ");
        }
        return b.length() == 0 ? "ఇప్పుడు దిశ తెలియడం లేదు: కొంచెం కదిలాక మళ్ళీ అడగండి." : b.toString().trim();
    }

    // ---------------------------------------------------------------- 😴 sleepy: the nearest open tea / coffee stop

    private void sleepy() {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        busy("దగ్గర్లో తెరిచి ఉన్న టీ / కాఫీ షాప్ వెతుకుతున్నాను…");
        final boolean g = googleMap();
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = g ? Drive.restStop(this) : Drive.along(this, "tea", 30, true); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g != googleMap()) { sleepy(); return; }
                if (res == null || !res.optBoolean("ok", false)) { home(); line("⚠️ " + (er != null ? er : notOk(res)), 13, 0xFFFFB4B4); return; }
                aheadLine();
                List<GMaps.Place> ps = places(res);
                alongStops = true;
                showFound("☕ ఆగే చోటు · " + searchedTe(res), ps);
                if (ps.isEmpty()) {
                    String t = "దగ్గర్లో " + (g ? "తెరిచి ఉన్న " : "") + "టీ షాప్ దొరకలేదు. మొదట వచ్చే సురక్షితమైన చోట ఆగి కాసేపు విశ్రాంతి తీసుకోండి.";
                    line(t, 14, 0xFFFFD08A);
                    Announcer.say(this, t);
                    return;
                }
                place(0);
                GMaps.Place x = ps.get(0);
                Announcer.say(this, "దగ్గర్లో " + (x.aheadM >= 0 ? MapMath.distance(x.aheadM) + " ముందు " : "") + x.name + (Boolean.TRUE.equals(x.open) ? " తెరిచి ఉంది" : " ఉంది")
                        + (g ? "" : " (ఇప్పుడు తెరిచి ఉందో ఉచిత మ్యాప్‌కి తెలియదు)") + ". వెళ్దాం అంటే ▶️ నొక్కండి.");
            });
        });
    }

    // ---------------------------------------------------------------- routes and going

    private void route() {
        if (target == null) return;
        if (!googleMap()) { Toast.makeText(this, "దారి గీత Google మ్యాప్‌తో చూపిస్తాను (Maps key). ▶️ నొక్కితే Google Maps లో దారి.", Toast.LENGTH_LONG).show(); return; }
        double[] m = map.me();
        if (m == null) { Toast.makeText(this, "లొకేషన్ ఇంకా రాలేదు.", Toast.LENGTH_SHORT).show(); return; }
        final GMaps.Place to = target;
        final int my = ++gen;
        final float h = heading;
        final boolean bk = bike;
        busy(to.name + " కి దారి చూస్తున్నాను…");
        run(() -> {
            List<GMaps.Route> rs = null;
            String err = null;
            try { rs = GMaps.routes(this, m[0], m[1], h, to.lat, to.lon, bk, true); } catch (Exception e) { err = GMaps.telugu(e); }
            final List<GMaps.Route> got = rs;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen || !googleMap()) return; // (Google's line only on Google's map)
                if (got == null || got.isEmpty()) { panel.removeAllViews(); line("⚠️ " + (er != null ? er : "దారి దొరకలేదు."), 13.5f, 0xFFFFB4B4); panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> place(found.indexOf(to))))); return; }
                routes = got;
                routeSel = 0;
                showRoute(to);
            });
        });
    }

    private void showRoute(GMaps.Place to) {
        List<List<double[]>> lines = new ArrayList<>();
        for (GMaps.Route r : routes) lines.add(r.pts);
        map.setRoutes(lines, routeSel);
        map.fit(routes.get(routeSel).pts);
        panel.removeAllViews();
        GMaps.Route r = routes.get(routeSel);
        line("🧭 " + to.name, 16, 0xFFFFFFFF);
        long extra = r.seconds - r.staticSeconds;
        line(MapMath.duration(r.seconds) + " · " + MapMath.distance(r.meters) + (extra > 120 ? " · ట్రాఫిక్ వల్ల +" + MapMath.duration(extra) : "")
                + (r.toll.isEmpty() ? "" : " · టోల్ " + r.toll) + (r.via.isEmpty() ? "" : "\n" + r.via + " మీదుగా"), 14, 0xFFBFE9F5);
        if (routes.size() > 1) {
            LinearLayout alts = new LinearLayout(this);
            for (int i = 0; i < routes.size(); i++) {
                final int k = i;
                TextView c = chip((i + 1) + ". " + MapMath.duration(routes.get(i).seconds) + (routes.get(i).via.isEmpty() ? "" : " · " + routes.get(i).via));
                c.setAlpha(i == routeSel ? 1f : 0.6f);
                c.setOnClickListener(v -> { routeSel = k; showRoute(to); });
                alts.addView(c);
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.addView(alts);
            panel.addView(hs);
        }
        panel.addView(row(button("▶️ Google Maps లో వెళ్దాం", 0xFF15803D, v -> go(to))));
        panel.addView(row(button(bike ? "🏍️ బైక్ దారి" : "🚗 కారు దారి", 0xFF1F2937, v -> { bike = !bike; route(); }),
                button("← వెనక్కి", 0xFF1F2937, v -> { map.setRoutes(null, 0); place(found.indexOf(to)); })));
    }

    /** His tap on ▶️: the way set in Google Maps; Jarvis now knows where he is going (the drive answers use it). */
    private void go(GMaps.Place to) {
        if (to == null) return;
        final String ll = to.lat + "," + to.lon;
        final boolean stop = alongStops, bk = bike;
        try { Drive.startDrive(this); } catch (Exception ignored) {}
        run(() -> { // (where he is going is looked up off the main thread; a stop on his way keeps the trip's own destination)
            JSONObject d = stop ? Drive.dest(this) : null;
            main.post(() -> {
                if (isDestroyed()) return;
                if (d != null) {
                    try { startActivity(Drive.viaStop(Drive.destWhere(d), ll, bk)); Toast.makeText(this, "☕ " + to.name + " దారిలో ఆగే చోటుగా పెట్టాను (" + d.optString("name") + " కి వెళ్తూ).", Toast.LENGTH_LONG).show(); return; }
                    catch (Exception ignored) {}
                }
                Drive.settings(this).edit().putString("dest", to.name).putString("dest_ll", ll).putLong("dest_at", System.currentTimeMillis()).apply();
                navigateTo(to);
            });
        });
    }

    private void navigateTo(GMaps.Place to) {
        try { startActivity(Drive.navigate(to.lat + "," + to.lon, "", bike)); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + to.lat + "," + to.lon + "(" + Uri.encode(to.name) + ")"))); }
            catch (Exception e2) { Toast.makeText(this, "మ్యాప్ యాప్ లేదు", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void share(GMaps.Place x) {
        String link = !x.mapsUri.isEmpty() ? x.mapsUri : String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", x.lat, x.lon);
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, x.name + "\n" + link);
        startActivity(Intent.createChooser(s, "📍 " + x.name));
    }
}
