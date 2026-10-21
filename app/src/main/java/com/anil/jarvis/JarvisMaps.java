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
 * the free map (OpenStreetMap), and says so. Build 2: ➕ every kind of place (near / on his way / near where he is going /
 * in a town), 🎬 which theatres show a movie, 🗓️ a trip plan for later, ↩️ the best time to leave, 🌧️ rain on the
 * way, 🔋 the bike's battery for a trip, 🏞️ sights, 🆘 help on the road, 🧾 the trip diary, 🚗 the drive screen,
 * 📌 a finger held on a spot, 🗣️ the map by words, 📴 the free map kept for roads without signal.
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
    // build 2: what the panel shows now, notes under a list, the drive screen, offline
    private String foundMode = ""; // ("", "movie", "help", "point", "plan")
    private final List<String> foundNotes = new ArrayList<>();
    private boolean atHome, driveMode, driveExited, offlineFree;
    private int fastFixes;
    private long driveInfoAt, netCheckAt;
    private Location lastLoc;
    private TextView dSpeed, dRoad, dEta, dNext;
    private String driveNext = "";
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
        TextView all = chip("➕ అన్ని చోట్లు");
        all.setOnClickListener(v -> sheet());
        chips.addView(all);
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
        atHome = false;
        driveMode = false;
        panel.removeAllViews();
        line("⏳ " + s, 14.5f, 0xFFBFE9F5);
    }

    // ---------------------------------------------------------------- the panel's states

    /** Back to the home buttons with a warning under them (the drive screen never covers a warning). */
    private void homeWarn(String s) {
        home();
        line(s, 13, 0xFFFFB4B4);
        atHome = false;
    }

    private void home() {
        atHome = true;
        driveMode = false;
        foundMode = "";
        panel.removeAllViews();
        panel.addView(row(button("🛣️ ఈ రోడ్డు ఎటు?", 0xFF0E4A6B, v -> roadAhead()), button("😴 నిద్ర వస్తోంది", 0xFF6B3A0E, v -> sleepy())));
        panel.addView(row(small("🗓️ ప్రయాణం ప్లాన్", v -> planAsk()), small("🎬 సినిమా", v -> movieAsk("")), small("🆘 సహాయం", v -> helpNow())));
        panel.addView(row(small("🌧️ దారిలో వాన", v -> rainNow()), small("🏞️ చూడదగ్గవి", v -> sightsNow()), small("🧾 ప్రయాణాలు", v -> tripsView())));
        panel.addView(row(small("🔋 బైక్ ఛార్జ్", v -> bikeAsk()), small("↩️ ఎప్పుడు బయలుదేరాలి", v -> bestAsk()), small("🚗 డ్రైవ్ స్క్రీన్", v -> driveScreen(true))));
        if (!GMaps.has(this)) {
            line("ఉచిత మ్యాప్ (OpenStreetMap). ⚙️ → 🗺️ Jarvis Maps లో Google Maps key పెడితే: తెలుగు పేర్ల Google మ్యాప్, ట్రాఫిక్, దారిలో షాప్‌లు ఇంకా బాగా.", 12, 0xAAFFFFFF);
            TextView set = button("⚙️ Maps key పెట్టు", 0xFF1F2937, v -> startActivity(new Intent(this, SettingsActivity.class)));
            panel.addView(row(set));
        } else line("పైన వెతకండి, లేదా ఒక చిప్ నొక్కండి. 📍 = నేను ఎక్కడ, 🚦 = ట్రాఫిక్, 🛰️ = శాటిలైట్.", 12, 0xAAFFFFFF);
    }

    private void results() {
        atHome = false;
        driveMode = false;
        panel.removeAllViews();
        line(foundTitle, 15, 0xFFFFFFFF);
        if (found.isEmpty() && !"bike".equals(foundMode)) line("ఏమీ దొరకలేదు.", 13.5f, 0xCCFFFFFF);
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
        for (String n : foundNotes) line(n, 12, 0xFFFFD08A);
        if ("help".equals(foundMode)) panel.addView(row(button("🚑 108", 0xFF991B1B, v -> dial("108")), button("📤 లొకేషన్ పంపు", 0xFF0E4A6B, v -> shareHere())));
        if ("movie".equals(foundMode)) panel.addView(row(button("🎟️ BookMyShow", 0xFF7C2D12, v -> bookMyShow())));
        panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> { map.setPlaces(null); map.setRoutes(null, 0); home(); })));
    }

    /** "6.2 కి.మీ ముందు · 9 ని · తెరిచి ఉంది · ⭐ 4.3". */
    private static String facts(GMaps.Place x) {
        List<String> f = new ArrayList<>();
        if (x.aheadM >= 0) f.add(MapMath.distance(x.aheadM) + (x.aheadS > 0 ? " ముందు · " + MapMath.duration(x.aheadS) : ""));
        if (x.openThen != null) f.add(x.openThen ? "చేరేసరికి తెరిచి ఉంటుంది" : "చేరేసరికి మూసి ఉంటుంది");
        else if (x.open != null) f.add(x.open ? "తెరిచి ఉంది" : "ఇప్పుడు మూసి ఉంది");
        if (x.rating > 0) f.add("⭐ " + x.rating + (x.ratings > 0 ? " (" + x.ratings + ")" : ""));
        if (!x.note.isEmpty()) f.add(x.note);
        return TextUtils.join(" · ", f);
    }

    private void place(int i) {
        if (i < 0 || i >= found.size()) return;
        atHome = false;
        driveMode = false;
        target = found.get(i);
        map.selectPlace(i);
        map.centerOn(target.lat, target.lon, Math.max(map.zoom, 15));
        panel.removeAllViews();
        line(target.name, 17, 0xFFFFFFFF);
        if (!target.address.isEmpty()) line(target.address, 12.5f, 0xBBFFFFFF);
        String f = facts(target);
        if (!f.isEmpty()) line(f, 13.5f, 0xFFBFE9F5);
        if (!target.type.isEmpty()) line(target.type, 12, 0xAAFFFFFF);
        final GMaps.Place t0 = target;
        if (!t0.phone.isEmpty() || !t0.website.isEmpty()) {
            List<View> cs = new ArrayList<>();
            if (!t0.phone.isEmpty()) cs.add(button("📞 " + t0.phone, 0xFF1F2937, v -> dial(t0.phone))); // (the dialer opens: he presses call himself)
            if (!t0.website.isEmpty()) cs.add(button("🌐 వెబ్‌సైట్", 0xFF1F2937, v -> web(t0.website)));
            panel.addView(row(cs.toArray(new View[0])));
        }
        if ("movie".equals(foundMode)) panel.addView(row(button("🎟️ BookMyShow", 0xFF7C2D12, v -> bookMyShow())));
        if ("help".equals(foundMode)) panel.addView(row(button("🚑 108", 0xFF991B1B, v -> dial("108")), button("📤 లొకేషన్ పంపు", 0xFF0E4A6B, v -> shareHere())));
        if (t0.type.toLowerCase(Locale.ROOT).contains("hotel") || t0.type.toLowerCase(Locale.ROOT).contains("lodg") || "rooms".equals(lastKind))
            line("గది ధరలు Google ఇవ్వదు: ఫోన్ చేసి గానీ, మీ booking app లో గానీ చూడండి.", 12, 0xFFFFD08A);
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
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (lastLoc == null || gps || l.getTime() - lastLoc.getTime() > 10_000) lastLoc = l; // (a rough network fix never covers a fresh GPS one)
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 2) heading = l.getBearing();
        map.setMe(l.getLatitude(), l.getLongitude(), heading, l.hasAccuracy() ? l.getAccuracy() : 0);
        if (map.follow && System.currentTimeMillis() - creditAt > 60_000) settled(); // (following him: the credit kept right for where the map is)
        // 🚗 the drive screen by itself when he really drives (once he leaves it, not again this time)
        if (l.hasSpeed()) { if (l.getSpeed() > 7) fastFixes++; else fastFixes = 0; } // (only fixes that know the speed count)
        if (fastFixes >= 3 && !driveMode && !driveExited && atHome && p.sp.getBoolean("maps_drive_screen", true)) driveScreen(true);
        if (driveMode) updateDrive(l);
        netCheck();
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
        if (!GMaps.has(this) || googleOff || offlineFree) {
            map.setSource("", false, false);
            if (sat || tr) Toast.makeText(this, "ట్రాఫిక్ / శాటిలైట్ Google మ్యాప్‌తోనే" + (offlineFree ? ": ఇప్పుడు నెట్ లేదు." : googleOff ? " (అది ఇప్పుడు రాలేదు)." : ": ⚙️ → 🗺️ లో Maps key పెట్టండి."), Toast.LENGTH_LONG).show();
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
        if (command(w)) return; // 🗣️ "దగ్గరగా", "ట్రాఫిక్ చూపించు", "నేను ఎక్కడ", "డ్రైవ్ స్క్రీన్"…
        String mv = MapsPlus.movieName(w);
        if (mv != null) { movie(mv, moving()); return; } // 🎬 "OG సినిమా ఎక్కడ ఆడుతోంది"
        PlaceKinds.K pk = PlaceKinds.match(w);
        if (pk != null && Drive.kindOnly(w) && !moving()) { kindSearch(pk, "near", false, ""); return; } // (standing: as far as that kind makes sense — a mall 25 km, a beach 80 km)
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
                if (g && !googleMap()) { doSearch(w); return; } // (the map changed kind meanwhile: asked again for the map now shown)
                if (er != null) { homeWarn("⚠️ " + er); return; }
                alongStops = false;
                showFound("🔍 " + w + (g ? "" : " (ఉచిత మ్యాప్)"), res);
            });
        });
    }

    private void showFound(String title, List<GMaps.Place> ps) { showFound(title, ps, "", null); }

    /** mode: "movie" / "help" / … (extra buttons under the list); notes: lines under it (sources, what is not known). */
    private void showFound(String title, List<GMaps.Place> ps, String mode, List<String> notes) {
        foundMode = mode == null ? "" : mode;
        foundNotes.clear();
        if (notes != null) foundNotes.addAll(notes);
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
                if (g && !googleMap()) { along(what, label); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); return; }
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
            if (o.has("open_when_he_gets_there")) x.openThen = o.optBoolean("open_when_he_gets_there");
            x.rating = o.optDouble("rating", 0);
            x.ratings = o.optInt("ratings", 0);
            x.phone = o.optString("phone", "");
            x.website = o.optString("website", "");
            x.mapsUri = o.optString("maps_link", "");
            x.type = o.optString("type", "");
            List<String> n = new ArrayList<>();
            if (o.optBoolean("open_24h")) n.add("24 గంటలు");
            if (o.has("off_road_km") && o.optDouble("off_road_km") >= 0.5) n.add("దారి నుంచి " + o.optDouble("off_road_km") + " కి.మీ పక్కకి");
            if (o.has("km_on_the_way")) n.add("దారిలో " + o.optLong("km_on_the_way") + " కి.మీ దగ్గర");
            if (!o.optString("hours").isEmpty() && !o.has("open_now")) n.add("టైమింగ్: " + o.optString("hours"));
            x.note = TextUtils.join(" · ", n);
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
                if (g && !googleMap()) { sleepy(); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); return; }
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
        if (to == null || routes.isEmpty() || routeSel >= routes.size()) { home(); return; }
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
        if (x == null) return;
        String link = !x.mapsUri.isEmpty() ? x.mapsUri : String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", x.lat, x.lon);
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, x.name + "\n" + link);
        startActivity(Intent.createChooser(s, "📍 " + x.name));
    }

    // ================================================================ build 2: small helpers

    private static List<GMaps.Place> placesOf(JSONArray a) {
        try { return places(new JSONObject().put("places", a)); } catch (Exception e) { return new ArrayList<>(); }
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView t = button(s, 0xFF13324A, l);
        t.setTextSize(12.5f);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** The phone's dialer with the number in it: he presses call himself (Jarvis never calls from here). */
    private void dial(String number) {
        try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.replaceAll("[^0-9+]", "")))); }
        catch (Exception e) { toast("డయలర్ తెరవలేకపోయాను."); }
    }

    private void web(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("తెరవలేకపోయాను."); }
    }

    /** BookMyShow (its app if there, else its website): he books and pays himself. */
    private void bookMyShow() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.bt.bms");
        if (i != null) { try { startActivity(i); return; } catch (Exception ignored) {} }
        web("https://in.bookmyshow.com/");
    }

    /** His place now as a map link, through the share sheet (he picks who; nothing is sent by itself). */
    private void shareHere() {
        double[] m = map.me();
        if (m == null) { toast("లొకేషన్ ఇంకా రాలేదు."); return; }
        String link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", m[0], m[1]);
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "నేను ఇక్కడ ఉన్నాను: " + link), "📍 నా లొకేషన్"));
    }

    /** He is on his way somewhere (moving, or Jarvis knows the destination). */
    private boolean moving() {
        if (heading >= 0 && lastLoc != null && lastLoc.hasSpeed() && lastLoc.getSpeed() > 2) return true;
        android.content.SharedPreferences st = Drive.settings(this);
        return !st.getString("dest", "").isEmpty() && System.currentTimeMillis() - st.getLong("dest_at", 0) < 12 * 3600_000L;
    }

    private String lastKind = "";

    // ================================================================ 🗣️ the map by words (typed or 🎙️)

    /** The whole ask is one of these (never a word inside a search: "దగ్గరగా ఉన్న ATM", "traffic police", "train station" stay searches). */
    private static final String[][] COMMANDS = {
            {"in", "దగ్గరగా", "zoom in", "పెద్దగా", "ఇంకా దగ్గరగా"}, {"out", "దూరంగా", "zoom out", "చిన్నగా", "ఇంకా దూరంగా"},
            {"traffic_on", "ట్రాఫిక్", "traffic", "ట్రాఫిక్ ఆన్", "traffic on"}, {"traffic_off", "ట్రాఫిక్ తీసేయి", "ట్రాఫిక్ ఆపు", "ట్రాఫిక్ వద్దు", "traffic off"},
            {"sat_on", "శాటిలైట్", "satellite", "శాటిలైట్ మ్యాప్"}, {"sat_off", "శాటిలైట్ తీసేయి", "శాటిలైట్ ఆపు", "శాటిలైట్ వద్దు", "మామూలు మ్యాప్", "మామూలు", "normal map", "satellite off"},
            {"me", "నేను ఎక్కడ", "నేను ఎక్కడ ఉన్నాను", "where am i", "నా లొకేషన్", "నా చోటు"}, {"drive", "డ్రైవ్ స్క్రీన్", "drive screen", "డ్రైవ్ మోడ్"},
            {"trips", "ప్రయాణాలు", "నా ప్రయాణాలు", "ప్రయాణాల డైరీ", "trips", "my trips"},
            {"help", "సహాయం", "help", "పంక్చర్ అయింది", "బండి ఆగిపోయింది", "బండి పంక్చర్ అయింది", "యాక్సిడెంట్", "కారు ఆగిపోయింది"},
            {"rain", "వాన", "దారిలో వాన", "వాన పడుతుందా", "దారిలో వాన పడుతుందా", "rain", "rain on the way"},
            {"plan", "ప్లాన్", "ప్రయాణం ప్లాన్", "trip plan", "plan"}};

    private boolean command(String w) {
        String s = w.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ").replaceAll("^(jarvis|జార్విస్)\\s+", "")
                .replaceAll("\\s+(చూపించు|చూపు|చెయ్|చేయి|పెట్టు|చూడు|please)$", "").replaceAll("^మ్యాప్‌లో\\s+", "").replaceAll("\\s+", " ").trim();
        String cmd = null;
        for (String[] c : COMMANDS) for (int i = 1; i < c.length; i++) if (s.equals(c[i])) { cmd = c[0]; break; }
        if (cmd == null) return false;
        switch (cmd) {
            case "in": map.zoom = Math.min(20f, map.zoom + 1); map.invalidate(); settled(); return true;
            case "out": map.zoom = Math.max(3f, map.zoom - 1); map.invalidate(); settled(); return true;
            case "traffic_on": case "traffic_off": p.sp.edit().putBoolean("maps_traffic", cmd.equals("traffic_on")).apply(); source(); return true;
            case "sat_on": case "sat_off": p.sp.edit().putBoolean("maps_sat", cmd.equals("sat_on")).apply(); source(); return true;
            case "me": followMe(); return true;
            case "drive": driveScreen(true); return true;
            case "trips": tripsView(); return true;
            case "help": helpNow(); return true;
            case "rain": rainNow(); return true;
            case "plan": planAsk(); return true;
            default: return false;
        }
    }

    // ================================================================ ➕ every kind of place

    private String sheetWhere = "near";
    private boolean sheetGood;

    private void sheet() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(6), dp(14), dp(6));
        sv.addView(box);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("➕ అన్ని చోట్లు").setView(sv).setNegativeButton("మూసేయి", null).create();
        String[][] ws = {{"near", "📍 నా చుట్టూ"}, {"along", "🛣️ నా దారిలో"}, {"dest", "🏁 గమ్యం దగ్గర"}, {"town", "🏙️ వేరే ఊరిలో"}};
        if (moving() && "near".equals(sheetWhere)) sheetWhere = "along";
        LinearLayout whRow = new LinearLayout(this);
        List<TextView> whs = new ArrayList<>();
        EditText town = new EditText(this);
        town.setHint("ఏ ఊరు? (ఉదా: విశాఖపట్నం)");
        town.setSingleLine(true);
        town.setVisibility("town".equals(sheetWhere) ? View.VISIBLE : View.GONE);
        for (String[] w : ws) {
            TextView t = chip(w[1]);
            t.setAlpha(w[0].equals(sheetWhere) ? 1f : 0.45f);
            t.setOnClickListener(v -> {
                sheetWhere = w[0];
                for (int i = 0; i < whs.size(); i++) whs.get(i).setAlpha(ws[i][0].equals(sheetWhere) ? 1f : 0.45f);
                town.setVisibility("town".equals(sheetWhere) ? View.VISIBLE : View.GONE);
            });
            whs.add(t);
            whRow.addView(t);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(whRow);
        box.addView(hs);
        box.addView(town);
        TextView good = chip("⭐ మంచి రేటింగ్ ఉన్నవి మాత్రమే (4★+)");
        good.setAlpha(sheetGood ? 1f : 0.45f);
        good.setOnClickListener(v -> { sheetGood = !sheetGood; good.setAlpha(sheetGood ? 1f : 0.45f); });
        box.addView(good);
        for (String[] g : PlaceKinds.GROUPS) {
            TextView h = Ui.text(this, g[1], 14, 0xFF9FC5E8);
            h.setPadding(0, dp(10), 0, dp(2));
            box.addView(h);
            LinearLayout r = null;
            List<PlaceKinds.K> ks = PlaceKinds.inGroup(g[0]);
            for (int i = 0; i < ks.size(); i++) {
                if (i % 2 == 0) { r = new LinearLayout(this); box.addView(r); }
                PlaceKinds.K k = ks.get(i);
                TextView t = chip(k.label());
                t.setOnClickListener(v -> { d.dismiss(); kindSearch(k, sheetWhere, sheetGood, town.getText().toString()); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                r.addView(t, lp);
            }
        }
        d.show();
    }

    private void kindSearch(PlaceKinds.K k, String where, boolean good, String town) {
        if ("town".equals(where) && (town == null || town.trim().isEmpty())) { toast("ఏ ఊరు? పేరు రాయండి."); return; }
        if (("near".equals(where) || "along".equals(where)) && !hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        final String w = "town".equals(where) ? town.trim() : where;
        lastKind = k.id;
        busy(k.label() + " వెతుకుతున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.places(this, k.id, w, good, 0, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { kindSearch(k, where, good, town); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); return; }
                if ("along".equals(where)) aheadLine();
                alongStops = "along".equals(where);
                String wTe = "along".equals(where) ? "మీ దారిలో" : "dest".equals(where) ? "గమ్యం దగ్గర" : "town".equals(where) ? town.trim() + " లో" : "చుట్టుపక్కల";
                List<String> notes = new ArrayList<>();
                if (!res.optString("note").isEmpty()) notes.add("ℹ️ " + (res.optString("note").startsWith("None") || res.optString("note").startsWith("Nothing") ? "ఏమీ దొరకలేదు." : res.optString("note")));
                if (res.has("prices")) notes.add("గది ధరలు Google ఇవ్వదు: ఫోన్ / booking app లో చూడండి.");
                if (res.has("google_error")) notes.add("⚠️ Google: " + res.optString("google_error") + " (ఉచిత మ్యాప్ జవాబు)");
                String src = res.optString("source", "");
                showFound(k.label() + " · " + wTe + (src.startsWith("Google") ? " (Google Maps)" : " (ఉచిత మ్యాప్)") + (good ? " · ⭐4+" : ""), places(res), "", notes);
            });
        });
    }

    // ================================================================ 🎬 which theatres show a movie

    private void movieAsk(String preset) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        EditText name = new EditText(this);
        name.setHint("సినిమా పేరు (ఉదా: OG)");
        name.setSingleLine(true);
        name.setText(preset);
        box.addView(name);
        android.widget.CheckBox way = new android.widget.CheckBox(this);
        way.setText("🛣️ నా దారిలో ఉన్న థియేటర్లు ముందు");
        way.setChecked(moving());
        box.addView(way);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🎬 సినిమా ఎక్కడ ఆడుతోంది?").setView(box)
                .setPositiveButton("వెతుకు", (dd, i) -> { String n = name.getText().toString().trim(); if (!n.isEmpty()) movie(n, way.isChecked()); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void movie(String name, boolean along) {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        busy("\"" + name + "\" ఏ థియేటర్లలో ఆడుతోందో వెతుకుతున్నాను… (10–40 సెకన్లు)");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.movie(this, name, along, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { movie(name, along); return; }
                if (res == null || !res.optBoolean("ok", false)) {
                    home();
                    String why = er != null ? er : res == null ? "" : res.optString("error");
                    line("⚠️ " + ("no_brain".equals(why) ? "Jarvis మెదడు (AI) పెట్టలేదు: ⚙️ → Jarvis మెదడు." : "no_answer".equals(why) ? "షో టైమ్‌లు స్పష్టంగా దొరకలేదు. BookMyShow / District లో చూడండి."
                            : "ai_failed".equals(why) ? "AI ఇప్పుడు వెతకలేకపోయింది. కాసేపాగి మళ్ళీ." : notOk(res)), 13, 0xFFFFB4B4);
                    return;
                }
                List<GMaps.Place> pins = new ArrayList<>();
                List<String> notes = new ArrayList<>();
                JSONArray ths = res.optJSONArray("theatres");
                for (int i = 0; ths != null && i < ths.length(); i++) {
                    JSONObject t = ths.optJSONObject(i);
                    String shows = showsTe(t);
                    if (t.has("lat")) {
                        GMaps.Place x = new GMaps.Place();
                        x.name = t.optString("name");
                        x.lat = t.optDouble("lat");
                        x.lon = t.optDouble("lon");
                        x.aheadM = t.has("km_ahead") ? t.optDouble("km_ahead") * 1000 : t.optDouble("km", -1) * 1000;
                        x.rating = t.optDouble("rating", 0);
                        x.mapsUri = t.optString("maps_link", "");
                        x.phone = t.optString("phone", "");
                        x.address = t.optString("area", "");
                        x.note = (t.optBoolean("on_his_way") ? "🛣️ మీ దారిలో · " : "") + shows;
                        pins.add(x);
                    } else notes.add("🎬 " + t.optString("name") + (t.optString("area").isEmpty() ? "" : " (" + t.optString("area") + ")") + ": " + shows + " (మ్యాప్‌లో చోటు దొరకలేదు)");
                }
                if (ths == null || ths.length() == 0) notes.add("ఈ సినిమా దగ్గర్లో ఏ థియేటర్‌లోనూ దొరకలేదు (web లో లిస్ట్ లేదు, లేదా ఇక్కడ రిలీజ్ కాలేదు).");
                notes.add("షో టైమ్‌లు మీ AI web లో వెతికినవి: పాతవి కావచ్చు. వెళ్ళే ముందు BookMyShow / District లో చూడండి.");
                if (res.has("google_error")) notes.add("⚠️ Google: " + res.optString("google_error"));
                alongStops = false;
                showFound("🎬 " + res.optString("movie", name) + (along ? " · మీ దారిలోవి ముందు" : " · దగ్గర్లో"), pins, "movie", notes);
            });
        });
    }

    /** "ఈ రోజు 6:30 PM, 9:45 PM · 3D". */
    private static String showsTe(JSONObject t) {
        JSONArray sh = t.optJSONArray("shows");
        List<String> ss = new ArrayList<>();
        for (int i = 0; sh != null && i < sh.length() && i < 8; i++) ss.add(sh.optString(i));
        String day = t.optString("date", "today").toLowerCase(Locale.ROOT).contains("tomorrow") ? "రేపు" : "ఈ రోజు";
        return day + " " + (ss.isEmpty() ? "(టైమ్‌లు తెలియలేదు)" : TextUtils.join(", ", ss)) + (t.optString("format").isEmpty() ? "" : " · " + t.optString("format"));
    }

    // ================================================================ 🗓️ a trip plan, ↩️ the best time to leave

    private long planAt;
    private boolean planArrive;

    private void planAsk() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        EditText to = new EditText(this);
        to.setHint("ఎక్కడికి? (ఊరు / చోటు)");
        to.setSingleLine(true);
        box.addView(to);
        if (planAt < System.currentTimeMillis()) { // (default: tomorrow 6:00 AM)
            java.util.Calendar k = java.util.Calendar.getInstance();
            k.add(java.util.Calendar.DAY_OF_YEAR, 1);
            k.set(java.util.Calendar.HOUR_OF_DAY, 6);
            k.set(java.util.Calendar.MINUTE, 0);
            k.set(java.util.Calendar.SECOND, 0);
            planAt = k.getTimeInMillis();
        }
        TextView mode = chip(""), when = chip(""), by = chip("");
        final boolean[] bk = {Bike.riding(this)};
        Runnable show = () -> {
            mode.setText(planArrive ? "🏁 ఈ టైమ్‌కి చేరాలి" : "🕐 ఈ టైమ్‌కి బయలుదేరతాను");
            when.setText("📅 " + new java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(new java.util.Date(planAt)));
            by.setText(bk[0] ? "🏍️ బైక్" : "🚗 కారు");
        };
        show.run();
        mode.setOnClickListener(v -> { planArrive = !planArrive; show.run(); });
        by.setOnClickListener(v -> { bk[0] = !bk[0]; show.run(); });
        when.setOnClickListener(v -> {
            java.util.Calendar k = java.util.Calendar.getInstance();
            k.setTimeInMillis(planAt);
            new android.app.DatePickerDialog(this, (dp, y, m, dd) -> new android.app.TimePickerDialog(this, (tp, h, mi) -> {
                java.util.Calendar n = java.util.Calendar.getInstance();
                n.set(y, m, dd, h, mi, 0);
                planAt = n.getTimeInMillis();
                show.run();
            }, k.get(java.util.Calendar.HOUR_OF_DAY), k.get(java.util.Calendar.MINUTE), false).show(),
                    k.get(java.util.Calendar.YEAR), k.get(java.util.Calendar.MONTH), k.get(java.util.Calendar.DAY_OF_MONTH)).show();
        });
        box.addView(mode);
        box.addView(when);
        box.addView(by);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🗓️ ప్రయాణం ప్లాన్").setView(box)
                .setPositiveButton("ప్లాన్ చేయి", (dd, i) -> { String t = to.getText().toString().trim(); if (!t.isEmpty()) plan(t, planAt, planArrive, bk[0]); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void plan(String to, long at, boolean arrive, boolean bk) {
        if (!hasLoc()) { startLocation(); return; }
        if (at < System.currentTimeMillis() - 60_000) at = System.currentTimeMillis();
        final long when = at;
        final int my = ++gen;
        final boolean g = googleMap();
        busy(to + " ప్రయాణం ప్లాన్ చేస్తున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.plan(this, to, arrive ? 0 : when, arrive ? when : 0, bk, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { plan(to, when, arrive, bk); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : "not_found".equals(res == null ? "" : res.optString("error")) ? to + " మ్యాప్‌లో దొరకలేదు." : notOk(res))); return; }
                showPlan(res);
            });
        });
    }

    private void showPlan(JSONObject r) {
        atHome = false;
        driveMode = false;
        foundMode = "plan";
        alongStops = false;
        List<double[]> pts = MapMath.decode(r.optString("polyline"));
        List<List<double[]>> lines = new ArrayList<>();
        lines.add(pts);
        map.setRoutes(lines, 0);
        found.clear();
        JSONArray st = r.optJSONArray("stops");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            GMaps.Place x = new GMaps.Place();
            x.name = o.optString("name");
            x.lat = o.optDouble("lat");
            x.lon = o.optDouble("lon");
            x.rating = o.optDouble("rating", 0);
            x.ratings = o.optInt("ratings", 0);
            x.phone = o.optString("phone", "");
            x.mapsUri = o.optString("maps_link", "");
            if (o.has("open_when_he_gets_there")) x.openThen = o.optBoolean("open_when_he_gets_there");
            x.note = "మొదలు నుంచి " + o.optLong("km_from_start") + " కి.మీ" + (o.optString("reach_at").isEmpty() ? "" : " · " + o.optString("reach_at") + " కి చేరతారు");
            found.add(x);
        }
        map.setPlaces(found);
        if (pts.size() >= 2) map.fit(pts);
        panel.removeAllViews();
        String to = r.optString("to");
        line("🗓️ " + to + " (" + ("bike".equals(r.optString("by")) ? "🏍️" : "🚗") + ")", 16, 0xFFFFFFFF);
        line("🕐 బయలుదేరండి: " + r.optString("leave_at") + "\n🏁 చేరేది: " + r.optString("arrive_at"), 14.5f, 0xFFBFE9F5);
        long min = r.optLong("minutes");
        line(MapMath.duration(min * 60) + " · " + r.optLong("km") + " కి.మీ" + (r.optString("toll").isEmpty() ? "" : " · టోల్ " + r.optString("toll"))
                + (r.has("traffic_extra_minutes") ? " · ట్రాఫిక్ వల్ల +" + r.optLong("traffic_extra_minutes") + " ని" : "")
                + (r.optString("via").isEmpty() ? "" : "\n" + r.optString("via") + " మీదుగా"), 13.5f, 0xFFFFFFFF);
        if (r.has("late_minutes")) line("⚠️ ఇప్పుడే బయలుదేరినా సుమారు " + r.optLong("late_minutes") + " నిమిషాలు ఆలస్యం అవుతుంది.", 13.5f, 0xFFFFB4B4);
        else if (r.has("arrive_by")) line("(" + r.optLong("spare_minutes") + " నిమిషాలు ముందే చేరేలా)", 12, 0xAAFFFFFF);
        if (r.has("google_error")) line("⚠️ Google: " + r.optString("google_error") + " (ఉచిత దారి, ట్రాఫిక్ లేకుండా)", 12, 0xFFFFD08A);
        if (st != null && st.length() > 0) {
            line("🍛 " + r.optString("meal") + " కి ఆగొచ్చు (చేరేసరికి మూసి ఉండేవి తీసేశాను):", 13.5f, 0xFFFFE08A);
            for (int i = 0; i < found.size(); i++) {
                final int k = i;
                GMaps.Place x = found.get(i);
                TextView t = Ui.text(this, (i + 1) + ". " + x.name + "\n   " + facts(x), 13, 0xFFFFFFFF);
                t.setPadding(dp(6), dp(6), dp(6), dp(6));
                t.setOnClickListener(v -> place(k));
                panel.addView(t);
            }
        } else if (r.has("stops_error")) line("⚠️ ఆగే చోట్లు: " + r.optString("stops_error"), 12, 0xFFFFD08A);
        JSONArray rain = r.optJSONArray("rain");
        if (rain != null && rain.length() > 0) line(rainText(rain), 13, 0xFFBFE9F5);
        JSONObject b = r.optJSONObject("bike");
        if (b != null) line(bikeText(b), 13, 0xFFBFE9F5);
        line(r.optString("source").startsWith("Google") ? "Google Maps (ఆ టైమ్‌కి Google అంచనా వేసిన ట్రాఫిక్)" : "ఉచిత దారి (ట్రాఫిక్ తెలియదు)", 11.5f, 0x99FFFFFF);
        final long leave = r.optLong("leave_at_ms");
        final double tLat = r.optDouble("to_lat"), tLon = r.optDouble("to_lon");
        panel.addView(row(button("⏰ బయలుదేరే ముందు గుర్తు చేయి", 0xFF0E4A6B, v -> remindLeave(to, leave))));
        panel.addView(row(button("▶️ ఇప్పుడే వెళ్దాం", 0xFF15803D, v -> {
            GMaps.Place x = new GMaps.Place();
            x.name = to;
            x.lat = tLat;
            x.lon = tLon;
            alongStops = false;
            go(x);
        }), button("📴 దారి దాచు", 0xFF1F2937, v -> keepFreeWay(pts))));
        panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> { map.setRoutes(null, 0); map.setPlaces(null); home(); })));
        Announcer.say(this, to + " కి " + MapMath.duration(min * 60) + ". " + r.optString("leave_at").replace("today ", "ఈ రోజు ") + " కి బయలుదేరండి.");
    }

    private static String rainText(JSONArray rain) {
        StringBuilder b = new StringBuilder();
        int max = 0;
        for (int i = 0; i < rain.length(); i++) {
            JSONObject o = rain.optJSONObject(i);
            max = Math.max(max, o.optInt("chance"));
            if (o.optInt("chance") >= 40) b.append("\n• ").append(o.optLong("km")).append(" కి.మీ దగ్గర (").append(o.optString("at")).append("): వాన అవకాశం ").append(o.optInt("chance")).append("%");
        }
        return b.length() == 0 ? "🌤️ దారిలో వాన అవకాశం తక్కువ (" + max + "% లోపు)." : "🌧️ దారిలో వాన:" + b;
    }

    private static String bikeText(JSONObject b) {
        if (!b.optBoolean("battery_known")) return "🔋 బ్యాటరీ ఎంత ఉందో Jarvis కి తెలియదు: 🔋 బైక్ ఛార్జ్ లో % చెప్పండి.";
        String s = "🔋 బ్యాటరీ " + b.optInt("battery_pct") + "% · సుమారు " + b.optLong("range_km") + " కి.మీ వెళ్తుంది";
        if (b.has("trip_km")) s += b.optBoolean("reaches") ? " · చేరతారు (అక్కడ సుమారు " + b.optInt("left_pct_there") + "% మిగులుతుంది)" : " · ⚠️ " + b.optLong("trip_km") + " కి.మీ కి సరిపోదు: దారిలో ఛార్జ్ చేయాలి";
        return s;
    }

    /** ⏰ 15 minutes before leaving (his tap = his yes). */
    private void remindLeave(String to, long leave) {
        long at = leave - 15 * 60_000L;
        if (at <= System.currentTimeMillis() + 60_000) { toast("బయలుదేరే టైమ్ 15 నిమిషాల్లోపే ఉంది: ఇప్పుడే సిద్ధం అవ్వండి."); return; }
        try {
            JSONObject r = Store.get(this).addReminder("🗓️ " + to + " కి 15 నిమిషాల్లో బయలుదేరాలి", at);
            if (r == null) { toast("గుర్తు పెట్టలేకపోయాను."); return; }
            Reminders.schedule(this, r);
            try { JarvisWidget.refresh(this); } catch (Exception ignored) {}
            toast("⏰ " + new java.text.SimpleDateFormat("EEE h:mm a", Locale.ENGLISH).format(new java.util.Date(at)) + " కి గుర్తు చేస్తాను.");
        } catch (Exception e) { toast("గుర్తు పెట్టలేకపోయాను."); }
    }

    /** 📴 the free map's pictures of this way kept on the phone (asked first: it takes data). */
    private void keepFreeWay(List<double[]> pts) {
        if (pts == null || pts.size() < 2) { toast("దారి లేదు."); return; }
        if (!Net.online(this)) { toast("ఇప్పుడు నెట్ లేదు: నెట్ ఉన్నప్పుడు దాచండి."); return; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📴 దారి దాచాలా?")
                .setMessage("సిగ్నల్ లేని చోట కూడా మ్యాప్ కనిపించేలా, ఈ దారి ఉచిత మ్యాప్ (OpenStreetMap) ముక్కలు ఫోన్‌లో 30 రోజులు ఉంచుతాను (1,000 వరకు, కొన్ని MB). "
                        + "Wi-Fi లో చేస్తే మంచిది. Google మ్యాప్‌ని ఇలా దాచడానికి Google ఒప్పుకోదు: సిగ్నల్ లేనప్పుడు ఉచిత మ్యాప్ వస్తుంది.")
                .setPositiveButton("దాచు", (d, i) -> {
                    int n = map.keepFree(pts, (kept, total, finished) -> {
                        if (!finished || isDestroyed()) return;
                        toast(kept >= total ? "📴 దాచాను: " + total + " ముక్కలూ." : "📴 " + total + " లో " + kept + " ముక్కలు దాచాను (మిగతావి నెట్ / సర్వర్ వల్ల రాలేదు: మళ్ళీ నొక్కితే అవే తెస్తాను).");
                    });
                    toast(n < 0 ? "📴 ఇంకో దారి దాస్తున్నాను: అది అయ్యాక నొక్కండి." : "📴 " + n + " ముక్కలు దాస్తున్నాను… (మ్యాప్ వాడుతూనే ఉండొచ్చు)");
                }).setNegativeButton("వద్దు", null).show();
    }

    private void bestAsk() {
        EditText to = new EditText(this);
        to.setHint("ఎక్కడికి? (ఉదా: ఇంటికి, హైదరాబాద్)");
        to.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        box.addView(to, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("↩️ ఎప్పుడు బయలుదేరితే త్వరగా?")
                .setMessage("ఇప్పుడు, అర గంట, గంట, 2 గంటల తర్వాత బయలుదేరితే ఎంత సేపు పడుతుందో Google ట్రాఫిక్ అంచనాతో పోల్చి చెబుతాను.").setView(box)
                .setPositiveButton("చూడు", (d, i) -> { String t = to.getText().toString().trim(); if (!t.isEmpty()) best(t); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void best(String to) {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        busy("బయలుదేరే టైమ్‌లు పోలుస్తున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            String dest = to.equals("ఇంటికి") || to.equals("ఇల్లు") || to.equalsIgnoreCase("home") ? homeLL() : to;
            try { r = MapsPlus.bestTime(this, dest, System.currentTimeMillis(), Bike.riding(this), !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                panel.removeAllViews();
                if (res == null || !res.optBoolean("ok", false)) {
                    line("⚠️ " + (er != null ? er : "needs_google".equals(res == null ? "" : res.optString("error"))
                            ? (GMaps.has(this) ? "ఇది Google ట్రాఫిక్‌తోనే: Google మ్యాప్ కనిపిస్తున్నప్పుడు మళ్ళీ నొక్కండి (ఇప్పుడు ఉచిత మ్యాప్)." : "ఇది Google ట్రాఫిక్‌తోనే: ⚙️ → 🗺️ లో Maps key పెట్టండి.") : notOk(res)), 13, 0xFFFFB4B4);
                } else {
                    line("↩️ " + to + ": ఎప్పుడు బయలుదేరితే ఎంత సేపు", 15, 0xFFFFFFFF);
                    JSONArray sl = res.optJSONArray("slots");
                    for (int i = 0; sl != null && i < sl.length(); i++) {
                        JSONObject o = sl.optJSONObject(i);
                        boolean b = o.optString("leave").equals(res.optString("best_leave"));
                        line((b ? "⭐ " : "• ") + o.optString("leave").replace("today ", "") + " → " + o.optString("arrive") + " (" + MapMath.duration(o.optLong("minutes") * 60) + ")", 14, b ? 0xFFFFE08A : 0xFFFFFFFF);
                    }
                    line("Google అంచనా వేసిన ట్రాఫిక్: ఖచ్చితం కాదు.", 11.5f, 0x99FFFFFF);
                }
                panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> home())));
            });
        });
    }

    /** Home's "lat,lon" (his saved home), else the word itself. */
    private String homeLL() {
        try { JSONObject h = Travel.home(this); if (h != null && h.has("lat")) return h.optDouble("lat") + "," + h.optDouble("lon"); } catch (Exception ignored) {}
        return "home";
    }

    // ================================================================ 🌧️ rain on the way, 🏞️ sights, 🔋 the bike

    private void rainNow() {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        busy("దారిలో వాన చూస్తున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.rain(this, null); } catch (Exception e) { err = e.getMessage(); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                panel.removeAllViews();
                if (res == null || !res.optBoolean("ok", false)) line("⚠️ " + (res == null ? "వాతావరణం రాలేదు" + (er == null ? "." : " (" + er + ").") : notOk(res)), 13, 0xFFFFB4B4);
                else {
                    String s = res.optString("searched");
                    line("🌧️ " + (s.startsWith("on the way") ? "మీ దారిలో" : "మీరు వెళ్తున్న వైపు 100 కి.మీ"), 15, 0xFFFFFFFF);
                    String t = rainText(res.optJSONArray("rain"));
                    line(t, 14, 0xFFBFE9F5);
                    line("Open-Meteo వాతావరణం (ఉచితం) · అంచనా మాత్రమే", 11.5f, 0x99FFFFFF);
                    Announcer.say(this, t.replace("•", "").replace("\n", " "));
                }
                panel.addView(row(button("← వెనక్కి", 0xFF1F2937, v -> home())));
            });
        });
    }

    private void sightsNow() {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        busy("చూడదగ్గ ప్రదేశాలు వెతుకుతున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.sights(this, 0, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { sightsNow(); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); return; }
                boolean way = res.optString("searched").startsWith("along");
                if (way) aheadLine();
                alongStops = way;
                showFound("🏞️ చూడదగ్గవి · " + (way ? "మీ దారిలో" : "చుట్టుపక్కల") + (res.optString("source").startsWith("Google") ? " (Google Maps)" : " (ఉచిత మ్యాప్)"), places(res));
            });
        });
    }

    private void bikeAsk() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        EditText to = new EditText(this);
        to.setHint("ఎక్కడికి? (ఖాళీ = Jarvis కి తెలిసిన గమ్యం)");
        to.setSingleLine(true);
        EditText pct = new EditText(this);
        pct.setHint("బ్యాటరీ % (తెలియకపోతే ఖాళీ)");
        pct.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(to);
        box.addView(pct);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🔋 బైక్ ఛార్జ్ సరిపోతుందా?").setView(box)
                .setPositiveButton("చూడు", (d, i) -> {
                    int pc = 0;
                    try { pc = Integer.parseInt(pct.getText().toString().trim()); } catch (Exception ignored) {}
                    bikeNow(to.getText().toString().trim(), pc);
                }).setNegativeButton("వద్దు", null).show();
    }

    private void bikeNow(String to, int pct) {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        busy("బ్యాటరీ, దారి లెక్కిస్తున్నాను…");
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.bikeRange(this, to, pct, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { bikeNow(to, pct); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); return; }
                JSONObject b = res.optJSONObject("bike");
                List<GMaps.Place> ch = new ArrayList<>();
                JSONArray cs = res.optJSONArray("chargers_before_it_runs_low");
                if (cs != null) ch = placesOf(cs);
                List<String> notes = new ArrayList<>();
                if (b != null) notes.add(bikeText(b));
                if (res.has("chargers_before_it_runs_low") && ch.isEmpty()) notes.add("ఛార్జ్ తగ్గే చోటు దగ్గర EV ఛార్జర్లు దొరకలేదు: బయలుదేరే ముందే పూర్తిగా ఛార్జ్ చేయండి.");
                alongStops = false;
                showFound("🔋 బైక్" + (res.optString("to").isEmpty() ? "" : " · " + res.optString("to")) + (ch.isEmpty() ? "" : " · ఛార్జ్ తగ్గే లోపు ఛార్జర్లు"), ch, "bike", notes);
            });
        });
    }

    // ================================================================ 🆘 help on the road

    private void helpNow() {
        if (!hasLoc()) { startLocation(); return; }
        final int my = ++gen;
        final boolean g = googleMap();
        busy("దగ్గర్లో సహాయం వెతుకుతున్నాను… (ఎవరికైనా దెబ్బ తగిలితే వెంటనే 108)");
        panel.addView(row(button("🚑 108", 0xFF991B1B, v -> dial("108"))));
        run(() -> {
            JSONObject r = null;
            String err = null;
            try { r = MapsPlus.help(this, !g); } catch (Exception e) { err = GMaps.telugu(e); }
            final JSONObject res = r;
            final String er = err;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                if (g && !googleMap()) { helpNow(); return; }
                if (res == null || !res.optBoolean("ok", false)) { homeWarn("⚠️ " + (er != null ? er : notOk(res))); panel.addView(row(button("🚑 108", 0xFF991B1B, v -> dial("108")))); return; }
                List<GMaps.Place> ps = new ArrayList<>();
                for (String k : new String[]{"mechanics_open_now", "mechanics", "hospitals_open_now", "hospitals"}) {
                    JSONArray a = res.optJSONArray(k);
                    if (a == null) continue;
                    List<GMaps.Place> got = placesOf(a);
                    for (GMaps.Place x : got) x.name = (k.startsWith("hosp") ? "🏥 " : "🔧 ") + x.name;
                    ps.addAll(got);
                }
                List<String> where = new ArrayList<>();
                for (String k : new String[]{"road", "area", "town", "district"}) if (!res.optString(k).isEmpty()) where.add(res.optString(k));
                List<String> notes = new ArrayList<>();
                notes.add("హజార్డ్ లైట్లు వేయండి, రోడ్డుకి దూరంగా నిలబడండి. 📞 నొక్కితే డయలర్ తెరుస్తాను: కాల్ మీరే నొక్కాలి.");
                if (res.has("google_error")) notes.add("⚠️ Google: " + res.optString("google_error") + " (ఉచిత మ్యాప్)");
                alongStops = false;
                showFound("🆘 మీరు ఉన్నది: " + (where.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", res.optDouble("lat"), res.optDouble("lon")) : TextUtils.join(", ", where)), ps, "help", notes);
            });
        });
    }

    // ================================================================ 🧾 the trip diary

    private void tripsView() {
        final int my = ++gen;
        busy("ప్రయాణాల డైరీ…");
        run(() -> {
            List<JSONObject> ts = TripLog.latest(this, 40);
            JSONObject m = null;
            try { m = TripLog.month(TripLog.all(this), new java.text.SimpleDateFormat("yyyyMM", Locale.US).format(new java.util.Date())); } catch (Exception ignored) {}
            final JSONObject month = m;
            main.post(() -> {
                if (isDestroyed() || my != gen) return;
                foundMode = "trips";
                panel.removeAllViews();
                line("🧾 ప్రయాణాల డైరీ", 16, 0xFFFFFFFF);
                if (month != null) line("ఈ నెల: " + month.optInt("trips") + " ప్రయాణాలు · " + month.optLong("km") + " కి.మీ · " + month.optDouble("hours") + " గంటలు"
                        + (month.has("toll_rupees") ? " · టోల్ ₹" + month.optInt("toll_rupees") + " (" + month.optInt("toll_known_for_trips") + " ప్రయాణాలకి తెలిసింది)" : ""), 13.5f, 0xFFBFE9F5);
                if (!TripLog.on(this)) line("డైరీ ఆఫ్‌లో ఉంది (⚙️ → 🗺️ Jarvis Maps).", 12, 0xFFFFD08A);
                if (ts.isEmpty()) line("ఇంకా ప్రయాణాలు లేవు. డ్రైవ్ అలర్ట్స్ నడుస్తున్నప్పుడు 2 కి.మీ.కి పైగా ప్రయాణాలు ఇక్కడ ఉంటాయి (ఫోన్‌లోనే).", 13, 0xCCFFFFFF);
                for (JSONObject t : ts) {
                    TextView v = Ui.text(this, tripLine(t), 13, 0xFFFFFFFF);
                    v.setPadding(dp(6), dp(7), dp(6), dp(7));
                    v.setBackground(Ui.round(this, 0x33000000, 0x00000000, 10));
                    v.setOnClickListener(x -> tripShow(t));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.setMargins(0, dp(3), 0, dp(3));
                    panel.addView(v, lp);
                }
                List<View> bs = new ArrayList<>();
                bs.add(button("← వెనక్కి", 0xFF1F2937, v -> { map.setRoutes(null, 0); home(); }));
                if (!ts.isEmpty()) bs.add(button("🗑️ అన్నీ తీసేయి", 0xFF7F1D1D, v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("డైరీ మొత్తం తీసేయాలా?").setMessage("ఈ ఫోన్‌లోని ప్రయాణాలన్నీ పోతాయి. తిరిగి రావు.")
                        .setPositiveButton("తీసేయి", (d, i) -> { TripLog.clear(this); map.setRoutes(null, 0); tripsView(); }).setNegativeButton("వద్దు", null).show()));
                panel.addView(row(bs.toArray(new View[0])));
            });
        });
    }

    private static String tripLine(JSONObject t) {
        String when = new java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(new java.util.Date(t.optLong("start")));
        String from = t.optString("from"), to = t.optString("to", t.optString("dest"));
        return ("bike".equals(t.optString("by")) ? "🏍️ " : "🚗 ") + when + "\n   " + (from.isEmpty() && to.isEmpty() ? "" : from + " → " + to + " · ")
                + t.optDouble("km") + " కి.మీ · " + MapMath.duration(t.optLong("minutes") * 60) + (t.optString("toll").isEmpty() ? "" : " · టోల్ " + t.optString("toll"));
    }

    private void tripShow(JSONObject t) {
        List<double[]> pts = MapMath.decode(t.optString("line"));
        List<List<double[]>> ls = new ArrayList<>();
        ls.add(pts);
        map.setRoutes(ls, 0); // (his own GPS line: fine on either map)
        map.setPlaces(null);
        if (pts.size() >= 2) map.fit(pts);
        panel.removeAllViews();
        line(tripLine(t), 14, 0xFFFFFFFF);
        JSONArray st = t.optJSONArray("stops");
        line("అత్యధిక వేగం " + t.optLong("max_kmh") + " km/h" + (st == null || st.length() == 0 ? "" : " · " + st.length() + " సార్లు ఆగారు (5 ని పైగా)")
                + (t.optString("dest").isEmpty() ? "" : "\nగమ్యం: " + t.optString("dest")), 13, 0xFFBFE9F5);
        panel.addView(row(button("🗑️ ఇది తీసేయి", 0xFF7F1D1D, v -> { TripLog.remove(this, t.optString("id")); map.setRoutes(null, 0); tripsView(); }),
                button("← లిస్ట్", 0xFF1F2937, v -> tripsView())));
    }

    // ================================================================ 🚗 the drive screen

    private void driveScreen(boolean on) {
        if (!on) { driveExited = true; driveMode = false; home(); return; }
        driveExited = false;
        atHome = false;
        panel.removeAllViews();
        dSpeed = Ui.text(this, "– km/h", 40, 0xFFFFFFFF);
        dSpeed.setGravity(Gravity.CENTER);
        dRoad = Ui.text(this, "", 17, 0xFFBFE9F5);
        dRoad.setGravity(Gravity.CENTER);
        dEta = Ui.text(this, "", 17, 0xFFFFE08A);
        dEta.setGravity(Gravity.CENTER);
        dNext = Ui.text(this, driveNext, 15, 0xFFFFFFFF);
        dNext.setGravity(Gravity.CENTER);
        panel.addView(dSpeed);
        panel.addView(dRoad);
        panel.addView(dEta);
        panel.addView(dNext);
        panel.addView(row(button("🎙️ అడుగు", 0xFF0E4A6B, v -> voiceSearch()), button("⬅ మామూలు మ్యాప్", 0xFF1F2937, v -> driveScreen(false))));
        driveMode = true;
        followMe();
        driveInfoAt = 0;
        if (lastLoc != null) updateDrive(lastLoc);
    }

    private void updateDrive(Location l) {
        if (!driveMode || dSpeed == null) return;
        dSpeed.setText(l.hasSpeed() ? Math.round(l.getSpeed() * 3.6) + " km/h" : "– km/h");
        String road = DriveService.running ? DriveService.roadName + (DriveService.limitKmh > 0 ? " · లిమిట్ " + DriveService.limitKmh : "") : "";
        dRoad.setText(road.trim().isEmpty() ? "" : "🛣️ " + road);
        JSONObject nav = Drive.navState();
        String eta = nav != null && !nav.optString("trip").isEmpty() ? "🏁 " + nav.optString("trip") : "";
        if (eta.isEmpty()) {
            android.content.SharedPreferences st = Drive.settings(this);
            String ll = st.getString("dest_ll", ""), name = st.getString("dest", "");
            if (!ll.isEmpty() && !name.isEmpty()) {
                try { String[] xy = ll.split(","); eta = "🏁 " + name + " · సుమారు " + MapMath.distance(MapMath.meters(l.getLatitude(), l.getLongitude(), Double.parseDouble(xy[0]), Double.parseDouble(xy[1]))) + " (నేరుగా)"; } catch (Exception ignored) {}
            }
        }
        dEta.setText(eta);
        long now = System.currentTimeMillis();
        if (now - driveInfoAt > 4 * 60_000L) { // (the towns ahead: the free map, every few minutes)
            driveInfoAt = now;
            final Location at = new Location(l);
            run(() -> {
                String t = "";
                try {
                    Drive.Path p = Drive.ahead(this, at, 40);
                    if (p != null) {
                        JSONArray towns = Drive.townsAlong(p, 3);
                        List<String> ns = new ArrayList<>();
                        for (int i = 0; i < towns.length(); i++) ns.add(towns.optJSONObject(i).optString("name") + " " + towns.optJSONObject(i).optLong("km") + " కి.మీ");
                        if (!ns.isEmpty()) t = "తర్వాత: " + TextUtils.join(" · ", ns);
                    }
                } catch (Exception ignored) {}
                final String txt = t;
                main.post(() -> { driveNext = txt; if (driveMode && dNext != null) dNext.setText(txt); });
            });
        }
    }

    // ================================================================ 📴 no signal: the free map's kept pictures

    private void netCheck() {
        long now = System.currentTimeMillis();
        if (now - netCheckAt < 30_000) return;
        netCheckAt = now;
        boolean on = Net.online(this);
        if (!on && !offlineFree && GMaps.has(this) && !googleOff) {
            offlineFree = true;
            clearGoogle();
            if (!driveMode) home(); // (what was on the panel was Google's: gone with the map)
            map.maxFreeZoom = 15; // (the kept pictures go up to 15: closer is drawn from them, never blank)
            toast("📴 నెట్ లేదు: ఫోన్‌లో దాచిన ఉచిత మ్యాప్ చూపిస్తున్నాను. నెట్ రాగానే Google మ్యాప్.");
        } else if (on && offlineFree) {
            offlineFree = false;
            map.maxFreeZoom = 19;
            sourceTries = 0;
            source();
        }
    }

    // ================================================================ 📌 a finger held on a spot

    @Override public void longPressed(double lat, double lon) {
        GMaps.Place x = new GMaps.Place();
        x.name = "📌 ఈ చోటు";
        x.lat = lat;
        x.lon = lon;
        double[] m = map.me();
        if (m != null) x.aheadM = MapMath.meters(m[0], m[1], lat, lon);
        found.clear();
        found.add(x);
        foundTitle = "📌 ఈ చోటు";
        foundMode = "point";
        foundNotes.clear();
        map.setRoutes(null, 0);
        map.setPlaces(found);
        target = x;
        map.selectPlace(0);
        atHome = false;
        driveMode = false;
        panel.removeAllViews();
        line("📌 ఈ చోటు" + (x.aheadM >= 0 ? " · మీ నుంచి " + MapMath.distance(x.aheadM) + " (నేరుగా)" : ""), 16, 0xFFFFFFFF);
        TextView addr = Ui.text(this, "చిరునామా చూస్తున్నాను…", 13, 0xFFBFE9F5);
        panel.addView(addr);
        ++gen; // (an older answer never lands on this panel)
        run(() -> {
            String a = "";
            try {
                List<android.location.Address> as = new android.location.Geocoder(this, new Locale("te", "IN")).getFromLocation(lat, lon, 1);
                if (as != null && !as.isEmpty() && as.get(0).getAddressLine(0) != null) a = as.get(0).getAddressLine(0);
            } catch (Exception ignored) {}
            final String s = a;
            main.post(() -> { if (!isDestroyed() && addr.getParent() == panel) { addr.setText(s.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", lat, lon) : s); x.address = s; } });
        });
        List<View> r1 = new ArrayList<>();
        if (googleMap() && !p.geminiKey().trim().isEmpty() && !p.geminiModel().trim().isEmpty()) r1.add(button("ℹ️ ఇక్కడ ఏముంది?", 0xFF0E4A6B, v -> whatHere(lat, lon)));
        r1.add(button("💾 సేవ్", 0xFF1F2937, v -> savePoint(lat, lon)));
        panel.addView(row(r1.toArray(new View[0])));
        panel.addView(row(button("🧭 దారి", 0xFF0E4A6B, v -> route()), button("▶️ వెళ్దాం", 0xFF15803D, v -> { alongStops = false; go(x); })));
        panel.addView(row(button("🔗 షేర్", 0xFF1F2937, v -> share(x)), button("← వెనక్కి", 0xFF1F2937, v -> { map.setPlaces(null); home(); })));
    }

    private void savePoint(double lat, double lon) {
        EditText name = new EditText(this);
        name.setHint("పేరు (ఉదా: మామయ్య ఇల్లు)");
        name.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("💾 ఈ చోటు సేవ్").setView(box)
                .setPositiveButton("సేవ్", (d, i) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    GeoReminders.savePlace(this, n, lat, lon);
                    loadPins();
                    toast("💾 \"" + n + "\" సేవ్ చేశాను: 'Jarvis, " + n + " కి దారి' అంటే చాలు.");
                }).setNegativeButton("వద్దు", null).show();
    }

    /** ℹ️ What Google Maps knows here (Gemini with Google Maps, his model), said in Telugu, with Google's sources (its rule). */
    private void whatHere(double lat, double lon) {
        TextView wait = Ui.text(this, "⏳ ఇక్కడ ఏముందో చూస్తున్నాను…", 13, 0xFFBFE9F5);
        panel.addView(wait, 1);
        run(() -> {
            String text = null, err = null;
            GMaps.Notes n = null;
            try {
                n = GMaps.whatHere(this, lat, lon);
                text = n.text;
                if (p.hasBrain() && !text.isEmpty()) {
                    try {
                        String te = Brain.oneShot(p, "Translate to simple Telugu. Keep place names as they are. Short lines, no extra words.", text, null, false, 500);
                        if (te != null && !te.trim().isEmpty()) text = te.trim();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { err = GMaps.telugu(e); }
            final String t = text, er = err;
            final GMaps.Notes notes = n;
            main.post(() -> {
                if (isDestroyed() || wait.getParent() != panel) return; // (he left this panel meanwhile)
                wait.setText(er != null ? "⚠️ " + er : t == null || t.isEmpty() ? "Google Maps దగ్గర ఇక్కడ వివరాలు లేవు." : "ℹ️ " + t);
                if (notes != null) {
                    int at = panel.indexOfChild(wait) + 1;
                    for (int i = 0; i < notes.sources.size() && i < 4; i++) {
                        final String uri = notes.sources.get(i)[1];
                        TextView s = Ui.text(this, "Google Maps · " + notes.sources.get(i)[0], 12, 0xFF9FC5E8);
                        if (!uri.isEmpty()) s.setOnClickListener(v -> web(uri));
                        panel.addView(s, at++);
                    }
                }
            });
        });
    }
}
