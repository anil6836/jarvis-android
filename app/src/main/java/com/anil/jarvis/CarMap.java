package com.anil.jarvis;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🗺️ G1: the map on the car screen (the ④ / ⑦ shapes, and the map screen while a Google Maps trip runs). Jarvis
 * Maps' own map ({@link JarvisMapView}: Google's map pictures with his Maps key on the box, else the free map, its
 * credit always drawn) following his car, with: the quick "on my way" chips (☕ ⛽ 🍛 🏧 🚻 🔧 ➕ — they open Jarvis
 * Maps with that search), 📍 back to me, 🚦 traffic, 🛰️ satellite, ⤢ the real Google Maps, ☰ Jarvis Maps, ＋ －; the
 * Google Maps app's next turn and arrival (from its notification: the app keeps guiding and speaking behind); the speed
 * with the limit; and the blue way only when Jarvis started the trip (he knows where to) — one route asked, again only
 * when he leaves it. Nothing here starts a trip.
 */
final class CarMap extends FrameLayout implements JarvisMapView.Listener {

    interface Host {
        /** A quick chip: Jarvis Maps' "on my way" search for this (its tag, its label). */
        void along(String what, String label);
        /** ☰: Jarvis Maps with all its buttons. */
        void menu();
        /** ⤢: the Google Maps app itself. */
        void bigMaps();
        /** "🗺️ మ్యాప్ మార్చు": the Google Maps app beside Jarvis (G2) instead of this map. */
        void switchMap();
    }

    static final String[][] CHIPS = {{"☕ టీ", "tea", "☕ టీ / కాఫీ"}, {"⛽ బంక్", "petrol", "⛽ పెట్రోల్"}, {"🍛 హోటల్", "restaurant", "🍛 హోటల్ / దాబా"},
            {"🏧 ATM", "atm", "🏧 ATM"}, {"🚻 టాయిలెట్", "toilet", "🚻 టాయిలెట్"}, {"🔧 పంక్చర్", "puncture", "🔧 పంక్చర్"}};

    final JarvisMapView map;
    private final Host host;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final LinearLayout chipBar, side, turn, bottom;
    private final List<TextView> chipViews = new ArrayList<>(), sideViews = new ArrayList<>();
    private final CarArt.Icon turnIcon;
    private final TextView turn1, turn2, eta, speed, note;
    // 21: the limit sign with the speed; 22: the arrival as short pills (each his switch)
    private final CarLooks.SpeedBadge badge;
    private final LinearLayout pills;
    private final TextView[] pillViews = new TextView[3];
    private final TextView trafficBtn, satBtn, mapPill;
    private final HorizontalScrollView chipScroll;
    private boolean shown, googleOff, started, sourceBusy;
    private long googleOffAt, sourceAskedAt;
    private String session = "", keyTag = "", noteKey = "";
    private int sourceSeq, sourceTries, creditSeq;
    private long creditAt, renewedAt;
    private float heading = -1;
    private int accent = 0xFF5FF3FF, fillCol = 0xE6061420, textMain = 0xFFFFFFFF, textSoft = 0xFFB9CCDA;
    private float radius = 24;
    // the way (only for a trip Jarvis started)
    private double[] dest;
    private long destAt, routeAt;
    private List<double[]> way;
    private int routeAsks;
    private boolean routeBusy;
    private final Runnable retrySource = this::source;
    // 🌙 the map's colours (dark at night, or his choice) and its zoom (fixed, or with the speed)
    private boolean dark, darkStale;
    private long darkNoUntil; // (Google refused the dark colours: the day map until then)
    private String zoomSeen;
    private int zoomWant;
    private long zoomWantSince, zoomTouchedAt;

    CarMap(Context c, Prefs prefs, Host host) {
        super(c);
        this.p = prefs;
        this.host = host;
        setOutlineProvider(new android.view.ViewOutlineProvider() { // (a rounded card, like the screen's other cards)
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        setClipToOutline(true);
        map = new JarvisMapView(c);
        map.listener = this;
        map.zoom = 16;
        addView(map, new LayoutParams(-1, -1));

        // top: the quick chips (Jarvis Maps' "on my way")
        HorizontalScrollView hs = chipScroll = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chipBar = new LinearLayout(c);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] ch : CHIPS) {
            TextView t = pill(c, ch[0]);
            t.setOnClickListener(v -> tap(() -> host.along(ch[1], ch[2])));
            chipBar.addView(t);
            chipViews.add(t);
        }
        TextView all = pill(c, "➕ అన్నీ");
        all.setOnClickListener(v -> tap(host::menu));
        chipBar.addView(all);
        chipViews.add(all);
        hs.addView(chipBar);
        addView(hs, new LayoutParams(-1, -2, Gravity.TOP));
        // top right: "🗺️ మ్యాప్ మార్చు" → the Google Maps app beside Jarvis (G2)
        mapPill = pill(c, "🗺️ మ్యాప్ మార్చు");
        mapPill.setOnClickListener(v -> tap(host::switchMap));
        addView(mapPill, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // the turn (the Google Maps app's next step) under the chips
        turn = new LinearLayout(c);
        turn.setOrientation(LinearLayout.HORIZONTAL);
        turn.setGravity(Gravity.CENTER_VERTICAL);
        turnIcon = new CarArt.Icon(c, CarArt.Icon.AHEAD);
        turnIcon.setColour(0xFFFFFFFF);
        turn.addView(turnIcon);
        LinearLayout tw = new LinearLayout(c);
        tw.setOrientation(LinearLayout.VERTICAL);
        turn1 = line(c, true);
        turn2 = line(c, false);
        tw.addView(turn1);
        tw.addView(turn2);
        turn.addView(tw, new LinearLayout.LayoutParams(-2, -2));
        turn.setVisibility(GONE);
        turn.setOnClickListener(v -> tap(host::bigMaps));
        addView(turn, new LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // right: 📍 🚦 🛰️ ⤢ ☰ ＋ －
        side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        sideBtn(c, "📍", this::followMe);
        trafficBtn = sideBtn(c, "🚦", () -> { p.sp.edit().putBoolean("maps_traffic", !p.sp.getBoolean("maps_traffic", false)).apply(); source(); });
        satBtn = sideBtn(c, "🛰️", () -> { p.sp.edit().putBoolean("maps_sat", !p.sp.getBoolean("maps_sat", false)).apply(); source(); });
        sideBtn(c, "⤢", host::bigMaps);
        sideBtn(c, "☰", host::menu);
        sideBtn(c, "＋", () -> zoomBy(1));
        sideBtn(c, "－", () -> zoomBy(-1));
        addView(side, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));

        // bottom: the arrival (a trip) and the speed with the limit; a short note (the free map, Google refused)
        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        eta = line(c, true);
        eta.setVisibility(GONE);
        eta.setOnClickListener(v -> tap(host::bigMaps));
        speed = line(c, true);
        pills = new LinearLayout(c);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setVisibility(GONE);
        for (int i = 0; i < 3; i++) {
            pillViews[i] = line(c, true);
            pillViews[i].setOnClickListener(v -> tap(host::bigMaps));
            pills.addView(pillViews[i]);
        }
        badge = new CarLooks.SpeedBadge(c);
        badge.setVisibility(GONE);
        bottom.addView(eta);
        bottom.addView(pills);
        bottom.addView(speed);
        bottom.addView(badge);
        addView(bottom, new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> map.setInset(getHeight() - t + dp(4))); // (the credit sits above them)
        note = line(c, false);
        note.setVisibility(GONE);
        note.setMaxLines(2);
        note.setOnClickListener(v -> note.setVisibility(GONE));
        addView(note, new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        sizes(0, 0);
    }

    // ---------------------------------------------------------------- small views

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private TextView pill(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setSingleLine(true);
        t.setTextColor(textMain);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView line(Context c, boolean bold) {
        TextView t = new TextView(c);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textMain);
        return t;
    }

    private TextView sideBtn(Context c, String s, Runnable r) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textMain);
        t.setOnClickListener(v -> tap(r));
        side.addView(t);
        sideViews.add(t);
        return t;
    }

    private void tap(Runnable r) {
        try { r.run(); } catch (Exception e) { Toast.makeText(getContext(), "అది ఇప్పుడు కాలేదు", Toast.LENGTH_SHORT).show(); }
    }

    private GradientDrawable card(float r, boolean lit) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillCol);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, dp(1.2f)), CarTheme.withAlpha(accent, lit ? 0xCC : 0x77));
        return g;
    }

    /** Every size from the map's own size (any box, any screen). */
    private void sizes(int w, int h) {
        int s = Math.max(dp(240), Math.min(w <= 0 ? dp(600) : w, h <= 0 ? dp(400) : h));
        float txt = Math.max(13, s / 28f), big = Math.max(16, s / 17f);
        int pad = Math.max(dp(4), s / 60), btn = Math.max(dp(40), s / 9);
        radius = Math.max(dp(10), s / 22f);
        invalidateOutline();
        GradientDrawable rim = new GradientDrawable(); // (the card's lit edge, drawn over the map)
        rim.setColor(0);
        rim.setCornerRadius(radius);
        rim.setStroke(Math.max(1, dp(1.5f)), CarTheme.withAlpha(accent, 0x88));
        setForeground(rim);
        for (TextView t : chipViews) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, txt);
            t.setPadding(pad * 2, pad, pad * 2, pad);
            t.setBackground(card(999, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(pad / 2, pad, pad / 2, pad);
            t.setLayoutParams(lp);
        }
        chipBar.setPadding(pad, 0, pad, 0);
        mapPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, txt);
        mapPill.setPadding(pad * 2, pad, pad * 2, pad);
        mapPill.setBackground(card(999, true));
        LayoutParams pl = (LayoutParams) mapPill.getLayoutParams();
        pl.setMargins(0, pad, pad, 0);
        mapPill.setLayoutParams(pl);
        mapPill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        LayoutParams cl = (LayoutParams) chipScroll.getLayoutParams(); // (the chips scroll beside it, never under it)
        cl.setMargins(0, 0, mapPill.getMeasuredWidth() + pad * 2, 0);
        chipScroll.setLayoutParams(cl);
        for (TextView t : sideViews) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, btn * 0.42f);
            t.setBackground(card(btn * 0.28f, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btn, btn);
            lp.setMargins(0, pad / 2, 0, pad / 2);
            t.setLayoutParams(lp);
        }
        // the buttons between the chips and the bottom row + the map's credit (never over the credit), centred in that room;
        // too little room (a small screen): ＋ － go (a pinch still zooms, ⤢ has the big map)
        int topRoom = (int) (txt * 2.6f) + pad * 2, bottomRoom = (int) (txt * 1.15f * 1.4f) + pad * 3 + dp(44);
        int room = Math.max(0, (h <= 0 ? dp(400) : h) - topRoom - bottomRoom), each = btn + pad;
        boolean zoomFits = room >= sideViews.size() * each;
        int shownBtns = zoomFits ? sideViews.size() : sideViews.size() - 2;
        for (int i = 0; i < sideViews.size(); i++) sideViews.get(i).setVisibility(i >= sideViews.size() - 2 && !zoomFits ? GONE : VISIBLE);
        LayoutParams sl = (LayoutParams) side.getLayoutParams();
        sl.gravity = Gravity.END | Gravity.TOP;
        sl.setMargins(0, topRoom + Math.max(0, (room - shownBtns * each) / 2), pad, 0);
        side.setLayoutParams(sl);
        turn.setPadding(pad * 2, pad, pad * 3, pad);
        turn.setBackground(card(pad * 2.5f, true));
        LayoutParams tl = (LayoutParams) turn.getLayoutParams();
        tl.setMargins(pad, (int) (txt * 2.6f) + pad * 2, btn + pad * 3, 0);
        turn.setLayoutParams(tl);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (big * 2.2f), (int) (big * 2.2f));
        il.setMargins(0, 0, pad * 2, 0);
        turnIcon.setLayoutParams(il);
        turn1.setTextSize(TypedValue.COMPLEX_UNIT_PX, big);
        turn2.setTextSize(TypedValue.COMPLEX_UNIT_PX, txt);
        turn2.setTextColor(textSoft);
        turn1.setMaxWidth(Math.max(dp(120), (int) (w * 0.62f)));
        turn2.setMaxWidth(Math.max(dp(120), (int) (w * 0.62f)));
        for (TextView t : new TextView[]{eta, speed, pillViews[0], pillViews[1], pillViews[2]}) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, txt * 1.15f);
            t.setPadding(pad * 2, pad, pad * 2, pad);
            t.setBackground(card(t == eta || t == speed ? pad * 2 : 999, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, pad, 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            t.setLayoutParams(lp);
        }
        int bh = (int) (txt * 2.5f); // (the limit sign and the speed: a little taller than a pill)
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams((int) (bh * 2.5f), bh);
        bp.gravity = Gravity.CENTER_VERTICAL;
        badge.setLayoutParams(bp);
        badge.setColours(fillCol, textMain, false);
        LayoutParams bl = (LayoutParams) bottom.getLayoutParams();
        bl.setMargins(pad, 0, 0, pad);
        bottom.setLayoutParams(bl);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, txt * 0.9f);
        note.setTextColor(0xFFFFE0A0);
        note.setPadding(pad * 2, pad, pad * 2, pad);
        note.setBackground(card(pad * 2, false));
        note.setMaxWidth(Math.max(dp(160), (int) (w * 0.5f)));
        LayoutParams nl = (LayoutParams) note.getLayoutParams();
        nl.setMargins(0, 0, btn + pad * 3, pad);
        note.setLayoutParams(nl);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        main.post(() -> sizes(w, h)); // (after this layout pass: new sizes, one more pass)
    }

    /** The theme's colours on the map's cards and buttons. */
    void setLook(CarTheme.Look look) {
        if (look == null) return;
        accent = look.accent;
        fillCol = look.light ? 0xEEFFFFFF : 0xE6061420;
        textMain = look.light ? 0xFF0B1118 : 0xFFFFFFFF;
        textSoft = look.light ? 0xFF3A4656 : 0xFFB9CCDA;
        for (TextView t : chipViews) t.setTextColor(textMain);
        for (TextView t : sideViews) t.setTextColor(textMain);
        mapPill.setTextColor(textMain);
        for (TextView t : new TextView[]{turn1, eta, speed, pillViews[0], pillViews[1], pillViews[2]}) t.setTextColor(textMain);
        turnIcon.setColour(look.light ? 0xFF0B1118 : 0xFFFFFFFF);
        boolean low = p.carLowMotion();
        turnIcon.setChevrons(p.carTurnArrows() && !low); // 20
        map.setRouteGlow(p.carRouteGlow(), !low); // 19
        sizes(getWidth(), getHeight());
        boolean dk = CarDeck.darkMap(p.carMapNight(), look.night);
        if (dk != dark) { // (night came / went, or his choice changed: the map's colours with it — now if on the screen, else when it comes)
            dark = dk;
            if (started && shown) source(); else darkStale = started;
        }
    }

    /** The numbers' font (his choice) for the speed in the corner. */
    void setFont(Typeface t) { badge.setFont(t); }

    // ---------------------------------------------------------------- shown / hidden

    /** On the screen (a map shape, or a trip's map screen): pictures and the credit; off: the pictures let go. */
    void setShown(boolean on) {
        if (on == shown) return;
        shown = on;
        if (on) {
            String kt = GMaps.has(getContext()) ? Integer.toHexString(GMaps.key(getContext()).hashCode()) : "";
            if (!started) { started = true; keyTag = kt; source(); }
            else if (!kt.equals(keyTag)) { keyTag = kt; resetGoogle(); } // (the key put in / changed / taken out: a fresh start, nothing of the old kept)
            else if (googleOff && System.currentTimeMillis() - googleOffAt > 30 * 60_000L) { googleOff = false; sourceTries = 0; source(); } // (refused a while ago: Google tried again)
            else if (darkStale) source();
            darkStale = false;
            try { // 🏠 his home on the map (the box's own saved place)
                JSONObject hm = Travel.home(getContext());
                List<JarvisMapView.Pin> ps = new ArrayList<>();
                if (hm != null && hm.has("lat")) ps.add(new JarvisMapView.Pin(hm.optDouble("lat"), hm.optDouble("lon"), "🏠 ఇల్లు", 0xFF22C55E));
                map.setPins(ps);
            } catch (Exception ignored) {}
            map.follow = true;
            map.invalidate();
        } else {
            map.trim();
            main.removeCallbacks(retrySource);
        }
    }

    boolean shown() { return shown; }

    void shutdown() {
        main.removeCallbacksAndMessages(null);
        map.shutdown();
        work.shutdownNow();
    }

    private void run(Runnable r) {
        if (work.isShutdown()) return;
        try { work.execute(r); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    // ---------------------------------------------------------------- every second (from the car screen)

    /**
     * Where he is (the drive service's fix), the maps app's trip (null: none), the speed and the limit (km/h, -1 /
     * 0 unknown). Only while shown.
     */
    void tick(Location l, JSONObject nav, int kmh, int limit) {
        if (!shown) return;
        if (l == null && map.me() == null) l = lastKnown(); // (parked, no fresh drive fix yet: where the box last knew)
        if (!map.follow && kmh > 5 && System.currentTimeMillis() - touchedAt > 15_000) map.follow = true; // (moved by a finger on the road: back on the car after a while)
        if (l != null) {
            if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 2) heading = l.getBearing();
            map.setMe(l.getLatitude(), l.getLongitude(), heading, l.hasAccuracy() ? l.getAccuracy() : 0);
            if (map.follow && System.currentTimeMillis() - creditAt > 60_000) settled(); // (following him: the credit right for where the map is)
        }
        zoomCheck(kmh);
        // Google's map never came (no net when the box woke, Google slow): asked again once a minute while there is net
        long now0 = System.currentTimeMillis();
        if (session.isEmpty() && !googleOff && !sourceBusy && GMaps.has(getContext()) && now0 - sourceAskedAt > 60_000 && Net.online(getContext())) source();
        else if (dark && darkNoUntil != 0 && darkNoUntil != Long.MAX_VALUE && now0 >= darkNoUntil && !sourceBusy && googleMap()) { darkNoUntil = 0; source(); } // (night colours asked again)
        // the trip's turn and arrival
        boolean trip = nav != null && !(nav.optString("next").isEmpty() && nav.optString("detail").isEmpty());
        if (trip) {
            String next = nav.optString("next"), det = nav.optString("detail"), tr = nav.optString("trip");
            turnIcon.setKind(CarRoad.turnIcon(next + " " + det));
            setText(turn1, next.isEmpty() ? det : next);
            setText(turn2, next.isEmpty() ? "" : det);
            turn2.setVisibility(next.isEmpty() || det.isEmpty() ? GONE : VISIBLE);
            setText(eta, tr.isEmpty() ? "🏁 " + nav.optString("app") : "🏁 " + tr);
        }
        String[] pl = trip && p.carMapPills() ? CarStyle.tripPills(nav.optString("trip")) : null; // 22: when, how long, how far
        if (pl != null) for (int i = 0; i < 3; i++) { setText(pillViews[i], pl[i]); pillViews[i].setVisibility(pl[i].isEmpty() ? GONE : VISIBLE); }
        turn.setVisibility(trip ? VISIBLE : GONE);
        pills.setVisibility(pl != null ? VISIBLE : GONE);
        eta.setVisibility(trip && pl == null ? VISIBLE : GONE);
        // the speed and the limit (red over it): 21's sign and box, or the line
        boolean b = p.carMapLimit();
        badge.setVisibility(b ? VISIBLE : GONE);
        speed.setVisibility(b ? GONE : VISIBLE);
        if (b) badge.set(kmh, limit);
        String sp = kmh < 0 ? "📡 GPS…" : kmh + " కి.మీ/గం" + (limit > 0 ? "  ·  లిమిట్ " + limit : "");
        setText(speed, sp);
        speed.setTextColor(limit > 0 && kmh > limit ? 0xFFFF6B5E : textMain);
        // the way: only a trip Jarvis started, Google's map on (Google's way never on the free map)
        wayCheck(l, trip);
    }

    private static void setText(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }

    // ---------------------------------------------------------------- the way (a trip Jarvis started)

    private long lastAskedAt;

    /** The box's last known place (GPS, else network), at most once in 30 s; null when none or no permission. */
    private Location lastKnown() {
        long now = System.currentTimeMillis();
        if (now - lastAskedAt < 30_000) return null;
        lastAskedAt = now;
        try {
            android.location.LocationManager lm = getContext().getSystemService(android.location.LocationManager.class);
            Location a = lm == null ? null : lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (a == null && lm != null) a = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            return a;
        } catch (Exception e) { // (no location permission: nothing)
            return null;
        }
    }

    private long tripSeenAt, destAtSeen, destCheckAt;

    /** Everything of a trip's way forgotten (the trip ended, the place changed, Google went away). */
    private void clearWay() {
        dest = null;
        destAtSeen = 0;
        way = null;
        routeAsks = 0;
        routeAt = 0;
        map.setRoutes(null, 0);
    }

    /**
     * Where Jarvis set this trip to go (one place for it: Drive's settings, written when Jarvis starts a trip — the car
     * screen's 🧭, his voice, Jarvis Maps' ▶️ — and cleared when the maps app ends it). Only a place set within 15
     * minutes before this trip showed, or during it: a trip he started in Google Maps himself gets no line.
     */
    private void destRead() {
        android.content.SharedPreferences s = Drive.settings(getContext());
        final long at = s.getLong("dest_at", 0);
        if (at == destAtSeen) return;
        boolean fresh = at != 0 && at >= tripSeenAt - 15 * 60_000L;
        clearWay();
        destAtSeen = at;
        if (!fresh) return;
        double[] ll = null;
        String[] xy = s.getString("dest_ll", "").split(",");
        if (xy.length == 2) { try { ll = new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])}; } catch (Exception ignored) {} }
        if (ll != null) { dest = ll; return; }
        final Context app = getContext().getApplicationContext();
        run(() -> { // (a name only: found on the map once, off the main thread)
            JSONObject d = Drive.dest(app);
            if (d != null && d.has("lat")) main.post(() -> { if (destAtSeen == at && dest == null) dest = new double[]{d.optDouble("lat"), d.optDouble("lon")}; });
        });
    }

    private void wayCheck(Location l, boolean trip) {
        long now = System.currentTimeMillis();
        if (!trip) { // (no trip: nothing of the last one stays on the map)
            if (tripSeenAt != 0 || dest != null || way != null) clearWay();
            tripSeenAt = 0;
            return;
        }
        if (tripSeenAt == 0) tripSeenAt = now;
        if (now - destCheckAt >= 5000) { destCheckAt = now; destRead(); }
        if (dest == null || l == null || routeBusy || !googleMap() || !Net.online(getContext())) return;
        boolean off = way != null && MapMath.metersToLine(l.getLatitude(), l.getLongitude(), way) > 300;
        if (way != null && !off) return;
        if (routeAsks >= 8 || now - routeAt < (way == null ? 15_000 : 120_000)) return; // (Google's routes are counted: few, and not often)
        routeBusy = true;
        routeAt = now;
        routeAsks++;
        final double la = l.getLatitude(), lo = l.getLongitude();
        final double[] to = dest;
        final Context c = getContext().getApplicationContext();
        run(() -> {
            List<double[]> pts = null;
            try {
                List<GMaps.Route> rs = GMaps.routesAt(c, la, lo, to[0], to[1], false, 0, false, false);
                if (!rs.isEmpty()) pts = rs.get(0).pts;
            } catch (Exception ignored) {}
            final List<double[]> got = pts;
            main.post(() -> {
                routeBusy = false;
                if (got == null || got.size() < 2 || to != dest || !googleMap()) return; // (the place changed / Google went away meanwhile: dropped)
                way = got;
                List<List<double[]>> one = new ArrayList<>();
                one.add(got);
                map.setRoutes(one, 0);
            });
        });
    }

    // ---------------------------------------------------------------- the map's pictures and their credit

    private boolean googleMap() { return GMaps.has(getContext()) && !googleOff && map.google(); }

    private void source() {
        Context c = getContext();
        boolean sat = p.sp.getBoolean("maps_sat", false), tr = p.sp.getBoolean("maps_traffic", false);
        trafficBtn.setAlpha(tr ? 1f : 0.55f);
        satBtn.setAlpha(sat ? 1f : 0.55f);
        if (!GMaps.has(c) || googleOff) {
            map.setSource("", false, false, dark);
            way = null; // (nothing of Google's on the free map: its way, its credit)
            map.setRoutes(null, 0);
            map.setCredit("");
            if (!GMaps.has(c)) say("free", "ఉచిత మ్యాప్ (OpenStreetMap) · Google మ్యాప్, ట్రాఫిక్ కోసం: ⚙️ → 🗺️ Jarvis Maps లో Maps key");
            return;
        }
        main.removeCallbacks(retrySource);
        final int seq = ++sourceSeq;
        final Context app = c.getApplicationContext();
        sourceBusy = true;
        sourceAskedAt = System.currentTimeMillis();
        final boolean dk = dark && System.currentTimeMillis() >= darkNoUntil && !sat;
        run(() -> {
            String s = null, err = null;
            boolean refused = false, dayInstead = false;
            int dkStatus = 0;
            try { s = GMaps.session(app, sat, tr, false, dk); }
            catch (Exception e) {
                boolean four = e instanceof GMaps.Error && ((GMaps.Error) e).status >= 400 && ((GMaps.Error) e).status < 500;
                if (four) dkStatus = ((GMaps.Error) e).status;
                if (dk && four) { // (Google said no to the dark colours: its day map, said once — not the free map)
                    try { s = GMaps.session(app, sat, tr, false, false); dayInstead = true; }
                    catch (Exception e2) { err = GMaps.telugu(e2); refused = e2 instanceof GMaps.Error && ((GMaps.Error) e2).status >= 400 && ((GMaps.Error) e2).status < 500; }
                } else { err = GMaps.telugu(e); refused = four; }
            }
            final String ses = s, er = err;
            final boolean no = refused, day = dayInstead;
            final int st = dkStatus;
            main.post(() -> {
                if (seq == sourceSeq) sourceBusy = false;
                if (googleOff || seq != sourceSeq) return;
                if (ses == null && no) { googleFailed(er); return; }
                if (ses == null) { // (no net / Google slow: the free map's kept pictures meanwhile, tried again in a while)
                    if (session.isEmpty()) map.setSource("", false, false, dark);
                    if (sourceTries++ < 6) main.postDelayed(retrySource, 30_000);
                    return;
                }
                sourceTries = 0;
                session = ses;
                if (day) darkNoUntil = st == 400 ? Long.MAX_VALUE : System.currentTimeMillis() + 30 * 60_000L; // (a passing refusal: tried again later)
                map.setSource(ses, sat, tr, dk && !day);
                say(day ? "darkno" : "", day ? "🌙 రాత్రి రంగుల మ్యాప్ Google ఇవ్వలేదు: పగలు రంగులోనే" : "");
                settled();
            });
        });
    }

    /** A short note on the map (the free map, Google refused); "" takes it away. Each one once. */
    private void say(String key, String s) {
        if (key.equals(noteKey)) return;
        noteKey = key;
        note.setText(s);
        note.setVisibility(s.isEmpty() ? GONE : VISIBLE);
        if (!s.isEmpty()) main.postDelayed(() -> { if (key.equals(noteKey)) note.setVisibility(GONE); }, 20_000);
    }

    @Override public void sessionExpired() {
        if (googleOff) return;
        long since = System.currentTimeMillis() - renewedAt;
        if (since < 60_000) { main.postDelayed(this::sessionExpired, 60_000 - since); return; }
        renewedAt = System.currentTimeMillis();
        final Context app = getContext().getApplicationContext();
        run(() -> { GMaps.dropSessions(app); main.post(this::source); });
    }

    /** A new key (or none now): the old key's session, way and credit dropped; the map made again. */
    private void resetGoogle() {
        main.removeCallbacks(retrySource);
        sourceSeq++;
        creditSeq++;
        sourceBusy = false;
        session = "";
        googleOff = false;
        sourceTries = 0;
        clearWay();
        darkNoUntil = 0;
        map.setCredit("");
        map.setSource("", false, false, dark);
        final Context app = getContext().getApplicationContext();
        run(() -> { GMaps.dropSessions(app); main.post(this::source); });
    }

    @Override public void googleFailed(String why) {
        if (googleOff) return;
        googleOff = true;
        googleOffAt = System.currentTimeMillis();
        final Context app = getContext().getApplicationContext();
        run(() -> GMaps.dropSessions(app));
        main.removeCallbacks(retrySource);
        sourceSeq++;
        session = "";
        map.setSource("", false, false, dark);
        map.setRoutes(null, 0); // (nothing of Google's on the free map)
        map.setCredit("");
        way = null;
        say("refused", "⚠️ Google మ్యాప్ రాలేదు: " + why + " · ఇప్పుడు ఉచిత మ్యాప్");
    }

    @Override public void settled() {
        if (!googleMap() || session.isEmpty()) return;
        final double[] bb = map.bounds();
        if (bb == null) { main.postDelayed(this::settled, 400); return; }
        final int z = Math.round(map.zoom);
        final String ses = session;
        final int seq = ++creditSeq;
        creditAt = System.currentTimeMillis();
        final Context app = getContext().getApplicationContext();
        run(() -> {
            try {
                String cr = GMaps.copyright(app, ses, z, bb[0], bb[1], bb[2], bb[3]);
                main.post(() -> { if (seq == creditSeq) map.setCredit(cr); });
            } catch (Exception ignored) {}
        });
    }

    @Override public void placeTapped(int index) {}
    private long touchedAt;

    @Override public void moved() { touchedAt = System.currentTimeMillis(); zoomTouchedAt = touchedAt; } // (his pinch / drag: his zoom for a minute too)
    @Override public void longPressed(double lat, double lon) { host.menu(); }

    private void followMe() {
        map.follow = true;
        double[] m = map.me();
        if (m == null) { Toast.makeText(getContext(), "GPS ఇంకా రాలేదు", Toast.LENGTH_SHORT).show(); return; }
        map.centerOn(m[0], m[1], Math.max(map.zoom, 16));
        map.follow = true;
    }

    /**
     * The zoom: his fixed choice (put when chosen, or the first time the map shows), or with the speed while the map
     * follows him — a new zoom only after 5 s of that speed (no pumping at a band's edge), and his own ＋ / － wins a minute.
     */
    private void zoomCheck(int kmh) {
        String zp = p.carMapZoom();
        long now = System.currentTimeMillis();
        if (!zp.equals(zoomSeen)) {
            zoomSeen = zp;
            if (!"auto".equals(zp)) setZoom(CarDeck.fixedZoom(zp));
        }
        if (!"auto".equals(zp) || !map.follow || now - zoomTouchedAt < 60_000) { zoomWantSince = 0; return; }
        int want = CarDeck.autoZoom(kmh);
        if (want < 0 || want == Math.round(map.zoom)) { zoomWantSince = 0; return; }
        if (want != zoomWant || zoomWantSince == 0) { zoomWant = want; zoomWantSince = now; return; }
        if (now - zoomWantSince >= 5000) { zoomWantSince = 0; setZoom(want); }
    }

    private void setZoom(int z) {
        double[] m = map.me();
        if (map.follow && m != null) { map.centerOn(m[0], m[1], z); map.follow = true; }
        else { map.zoom = z; map.invalidate(); }
        main.postDelayed(this::settled, 500);
    }

    private void zoomBy(int d) {
        zoomTouchedAt = System.currentTimeMillis();
        double[] m = map.me();
        float z = Math.max(5f, Math.min(19f, Math.round(map.zoom) + d));
        if (map.follow && m != null) { map.centerOn(m[0], m[1], z); map.follow = true; }
        else { map.zoom = z; map.invalidate(); }
        main.postDelayed(this::settled, 500);
    }
}
