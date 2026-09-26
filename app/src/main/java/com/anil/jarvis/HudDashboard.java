package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Iron-Man style status strip on the chat screen: time, battery, network, weather, next reminder and
 * wake-word state in a 3x2 grid of bracketed tiles. Tapping the header collapses it to a one-line summary
 * (remembered). It ticks once a second only between {@link #start()} and {@link #stop()}; everything
 * slow (weather) runs off the main thread and is cached.
 */
final class HudDashboard extends LinearLayout {
    private static final String SP = "hud_dashboard";
    private static final long WEATHER_TTL = 30 * 60_000L;
    private static final long WEATHER_RETRY = 5 * 60_000L;
    private static final String NONE = "—";

    private static volatile boolean weatherInFlight;
    private static volatile long weatherLastTry;

    private final Prefs prefs;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH);
    private final SimpleDateFormat dayTimeFmt = new SimpleDateFormat("EEE h:mm a", Locale.ENGLISH);
    private final Date scratchDate = new Date();

    private final View dot;
    private final TextView chevron, summary;
    private final LinearLayout grid;
    private final Tile tTime, tBattery, tNet, tWeather, tNext, tWake;
    private final boolean netAllowed;

    private boolean running, collapsed, dotOn;
    private int seconds;
    private String battShort = NONE, weatherShort = NONE;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tick();
            main.postDelayed(this, 1000);
        }
    };

    HudDashboard(Context c, Prefs prefs) {
        super(c);
        this.prefs = prefs;
        this.sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        this.netAllowed = c.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED;
        setOrientation(VERTICAL);

        // ---- header: pulsing dot, title, collapsed summary, chevron
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(4), dp(2), dp(4));
        dot = new View(c);
        dot.setBackground(Ui.round(c, Ui.CYAN, 0, 999));
        head.addView(dot, new LayoutParams(dp(6), dp(6)));
        TextView title = Ui.mono(c, "SYSTEMS · స్థితి", 10.5f, Ui.CYAN2);
        title.setPadding(dp(8), 0, dp(8), 0);
        head.addView(title);
        summary = Ui.mono(c, "", 10.5f, Ui.MUTED);
        summary.setLetterSpacing(0.06f);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setGravity(Gravity.END);
        head.addView(summary, new LayoutParams(0, -2, 1));
        chevron = Ui.text(c, "", 12, Ui.CYAN);
        chevron.setPadding(dp(8), 0, dp(2), 0);
        head.addView(chevron);
        head.setOnClickListener(v -> setCollapsed(!collapsed, true));
        addView(head, new LayoutParams(-1, dp(26)));

        // ---- 3x2 grid
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        LinearLayout r1 = row(c), r2 = row(c);
        tTime = new Tile(c, "TIME · సమయం");
        tBattery = new Tile(c, "POWER · బ్యాటరీ");
        tNet = new Tile(c, "LINK · నెట్");
        tWeather = new Tile(c, "WX · వాతావరణం");
        tNext = new Tile(c, "NEXT · తర్వాత");
        tWake = new Tile(c, "WAKE · వినడం");
        addTile(r1, tTime, true);
        addTile(r1, tBattery, true);
        addTile(r1, tWeather, false);
        addTile(r2, tNext, true);
        if (netAllowed) addTile(r2, tNet, true);   // no ACCESS_NETWORK_STATE -> no network tile
        addTile(r2, tWake, false);
        grid.addView(r1);
        LayoutParams r2lp = new LayoutParams(-1, -2);
        r2lp.topMargin = dp(6);
        grid.addView(r2, r2lp);
        addView(grid, new LayoutParams(-1, -2));

        setCollapsed(sp.getBoolean("collapsed", false), false);
        showCachedWeather();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Refresh everything now and tick once a second until {@link #stop()}. */
    void start() {
        if (running) return;
        running = true;
        refreshAll();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    void stop() {
        running = false;
        main.removeCallbacks(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void tick() {
        seconds++;
        if (!isShown()) return;
        dotOn = !dotOn;
        dot.setAlpha(dotOn ? 1f : 0.3f);
        updateTime();
        if (seconds % 30 == 0) refreshCheap();
        if (seconds % 60 == 0) maybeFetchWeather();
    }

    private void refreshAll() {
        updateTime();
        refreshCheap();
        showCachedWeather();
        maybeFetchWeather();
    }

    private void refreshCheap() {
        updateBattery();
        updateNetwork();
        updateNext();
        updateWake();
        updateSummary();
    }

    private void setCollapsed(boolean c, boolean save) {
        collapsed = c;
        grid.setVisibility(c ? GONE : VISIBLE);
        summary.setVisibility(c ? VISIBLE : GONE);
        chevron.setText(c ? "▸" : "▾");
        setContentDescription(c ? "స్థితి ప్యానెల్ మూసి ఉంది, తెరవడానికి నొక్కండి" : "స్థితి ప్యానెల్");
        if (save) sp.edit().putBoolean("collapsed", c).apply();
        updateSummary();
    }

    // ------------------------------------------------------------------ tiles

    private void updateTime() {
        scratchDate.setTime(System.currentTimeMillis());
        tTime.set(timeFmt.format(scratchDate), dateFmt.format(scratchDate), Ui.TEXT);
        updateSummary();
    }

    private void updateBattery() {
        try {
            Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) throw new IllegalStateException();
            int pct = Math.round(level * 100f / scale);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            battShort = pct + "%" + (charging ? "⚡" : "");
            String sub = charging ? (st == BatteryManager.BATTERY_STATUS_FULL ? "పూర్తి · full" : "ఛార్జింగ్") : pct <= 15 ? "తక్కువ · low" : "బ్యాటరీపై";
            tBattery.set(battShort, sub, pct <= 15 && !charging ? Ui.RED : Ui.TEXT);
        } catch (Exception e) {
            battShort = NONE;
            tBattery.set(NONE, "", Ui.TEXT);
        }
    }

    private void updateNetwork() {
        if (!netAllowed) return;
        try {
            ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) { tNet.set("Offline", "నెట్ లేదు", Ui.MUTED); return; }
            String kind = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "LAN" : "Online";
            boolean ok = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            tNet.set(kind, ok ? "ఆన్‌లైన్" : "ఇంటర్నెట్ లేదు", ok ? Ui.TEXT : Ui.GOLD);
        } catch (Exception e) {
            tNet.set(NONE, "", Ui.TEXT);
        }
    }

    private void updateNext() {
        try {
            long now = System.currentTimeMillis();
            JSONObject best = null;
            long bestAt = Long.MAX_VALUE;
            List<JSONObject> all = Store.get(getContext()).reminders();
            for (JSONObject r : all) {
                if (r.optBoolean("done")) continue;
                long at = r.optLong("at", 0);
                if (at >= now && at < bestAt) { bestAt = at; best = r; }
            }
            if (best == null) { tNext.set(NONE, "రిమైండర్లు లేవు", Ui.MUTED); return; }
            scratchDate.setTime(bestAt);
            Calendar a = Calendar.getInstance(), n = Calendar.getInstance();
            a.setTimeInMillis(bestAt);
            boolean today = a.get(Calendar.YEAR) == n.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
            String when = (today ? timeFmt : dayTimeFmt).format(scratchDate);
            tNext.set(when, best.optString("text", ""), Ui.GOLD);
        } catch (Exception e) {
            tNext.set(NONE, "", Ui.TEXT);
        }
    }

    private void updateWake() {
        String screen;
        try { screen = JarvisAccessibility.enabled() ? "స్క్రీన్ ✓" : "స్క్రీన్ ✗"; } catch (Throwable t) { screen = ""; }
        try {
            if (prefs == null) throw new IllegalStateException();
            if (prefs.wakeReady()) tWake.set("ON", "Jarvis వింటున్నాడు · " + screen, Ui.OK);
            else if (prefs.wakePaused()) tWake.set("PAUSED", "ఆపారు · " + screen, Ui.GOLD);
            else tWake.set("OFF", "ఆఫ్ · " + screen, Ui.MUTED);
        } catch (Exception e) {
            tWake.set(NONE, screen, Ui.TEXT);
        }
    }

    private void updateSummary() {
        if (!collapsed) return;
        scratchDate.setTime(System.currentTimeMillis());
        String t = timeFmt.format(scratchDate) + "  ·  " + battShort + "  ·  " + weatherShort;
        if (!t.contentEquals(summary.getText())) summary.setText(t);
    }

    // ------------------------------------------------------------------ weather

    private void showCachedWeather() {
        long at = sp.getLong("w_at", 0);
        if (at == 0) { weatherShort = NONE; tWeather.set(NONE, "", Ui.TEXT); return; }
        int temp = Math.round(sp.getFloat("w_temp", 0));
        int code = sp.getInt("w_code", -1);
        weatherShort = temp + "°C";
        boolean stale = System.currentTimeMillis() - at > 3 * 3600_000L;
        tWeather.set(weatherShort, sky(code), stale ? Ui.MUTED : Ui.TEXT);
        updateSummary();
    }

    private void maybeFetchWeather() {
        long age = System.currentTimeMillis() - sp.getLong("w_at", 0);
        if (age < WEATHER_TTL || weatherInFlight) return;
        long nowUp = SystemClock.elapsedRealtime();
        if (weatherLastTry != 0 && nowUp - weatherLastTry < WEATHER_RETRY) return;
        final Context app = getContext().getApplicationContext();
        weatherInFlight = true;
        weatherLastTry = nowUp;
        Thread t = new Thread(() -> {
            try {
                Location loc = Tools.lastLocation(app);
                if (loc == null) return;
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                        loc.getLatitude(), loc.getLongitude());
                JSONObject cur = Http.get(url).optJSONObject("current");
                if (cur == null || !cur.has("temperature_2m")) return;
                app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                        .putFloat("w_temp", (float) cur.optDouble("temperature_2m"))
                        .putInt("w_code", cur.optInt("weather_code", -1))
                        .putLong("w_at", System.currentTimeMillis())
                        .apply();
                main.post(() -> { if (running) showCachedWeather(); });
            } catch (Throwable ignored) {
                // offline / no location / API trouble: keep showing the cached value
            } finally {
                weatherInFlight = false;
            }
        }, "hud-weather");
        t.setDaemon(true);
        t.start();
    }

    private static String sky(int code) {
        if (code < 0) return NONE;
        if (code == 0) return "నిర్మలం · clear";
        if (code <= 2) return "కొంత మబ్బు";
        if (code == 3) return "మేఘావృతం";
        if (code == 45 || code == 48) return "పొగమంచు · fog";
        if (code >= 51 && code <= 57) return "తుంపర";
        if (code >= 61 && code <= 67) return "వర్షం · rain";
        if (code >= 71 && code <= 77) return "మంచు · snow";
        if (code >= 80 && code <= 86) return "జల్లులు";
        if (code >= 95) return "ఉరుములు · storm";
        return NONE;
    }

    // ------------------------------------------------------------------ views

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        return r;
    }

    private void addTile(LinearLayout row, Tile t, boolean gapAfter) {
        LayoutParams lp = new LayoutParams(0, dp(60), 1);
        if (gapAfter) lp.rightMargin = dp(6);
        row.addView(t, lp);
    }

    /** One bracketed tile: small cyan label, white value, muted sub line. */
    private static final class Tile extends LinearLayout {
        private final TextView value, sub;

        Tile(Context c, String label) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(c, 8);
            setPadding(p, Ui.dp(c, 5), p, Ui.dp(c, 5));
            setBackground(new Frame(c));
            TextView l = Ui.mono(c, label, 9, Ui.CYAN2);
            l.setLetterSpacing(0.08f);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            addView(l);
            value = Ui.text(c, NONE, 15, Ui.TEXT);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setLineSpacing(0, 1f);
            addView(value);
            sub = Ui.text(c, "", 10.5f, Ui.MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setLineSpacing(0, 1f);
            addView(sub);
        }

        void set(String v, String s, int color) {
            String vv = TextUtils.isEmpty(v) ? NONE : v;
            String ss = s == null ? "" : s;
            if (!vv.contentEquals(value.getText())) value.setText(vv);
            if (value.getCurrentTextColor() != color) value.setTextColor(color);
            if (!ss.contentEquals(sub.getText())) sub.setText(ss);
            String cd = vv + " " + ss;
            if (!cd.contentEquals(getContentDescription() == null ? "" : getContentDescription())) setContentDescription(cd);
        }
    }

    /** Translucent panel with a hairline border and cyan corner brackets. No allocation in draw(). */
    private static final class Frame extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float len;

        Frame(Context c) {
            fill.setColor(0xB30C1D29);   // PANEL at ~70%
            edge.setColor(0x9923495E);   // LINE2, faint
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.dp(c, 1) * 0.75f);
            corner.setColor(Ui.CYAN);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(Ui.dp(c, 1.5f));
            corner.setStrokeCap(Paint.Cap.SQUARE);
            len = Ui.dp(c, 7);
        }

        @Override public void draw(Canvas cv) {
            float l = getBounds().left + 1, t = getBounds().top + 1, r = getBounds().right - 1, b = getBounds().bottom - 1;
            cv.drawRect(l, t, r, b, fill);
            cv.drawRect(l, t, r, b, edge);
            cv.drawLine(l, t, l + len, t, corner);
            cv.drawLine(l, t, l, t + len, corner);
            cv.drawLine(r, t, r - len, t, corner);
            cv.drawLine(r, t, r, t + len, corner);
            cv.drawLine(l, b, l + len, b, corner);
            cv.drawLine(l, b, l, b - len, corner);
            cv.drawLine(r, b, r - len, b, corner);
            cv.drawLine(r, b, r, b - len, corner);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); edge.setAlpha(a); corner.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); edge.setColorFilter(cf); corner.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
