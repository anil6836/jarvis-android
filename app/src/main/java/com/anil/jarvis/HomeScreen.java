package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The home Jarvis's screen (a tablet in the hall, landscape): the new Jarvis (BodyView) on the left with what he says
 * in a bubble; on the right the clock with the part of the day, the Telugu date, the weather, what comes next, the
 * smart-home buttons (his Alexa links) and big buttons to talk. At night the screen dims and Jarvis rests (sleepy); it
 * may go dark and lights up again when someone says "Jarvis" or touches it.
 */
final class HomeScreen extends FrameLayout {
    interface Host {
        void talk();
        void openChat();
        void openSettings();
        /** అమ్మగారు's big buttons: tablet, ate, bible, songs, son, help. */
        void care(String what);
    }

    static final int NIGHT_FROM = 22, NIGHT_TO = 6;
    private static final String SP = "home_screen";
    private static final String[] DAYS = {"ఆదివారం", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం"};
    private static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు",
            "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    private final Activity act;
    private final Prefs prefs;
    private final Host host;
    final BodyView body;
    private final TextView bubble, clock, part, date, weather, next, net, batt;
    private final LinearLayout smart;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running, night, weatherBusy, nightSet;
    private long bubbleAt, weatherTry;
    private String lastWord, smartShown;
    private int lastStart = -1;

    HomeScreen(Activity a, Prefs prefs, Host host) {
        super(a);
        this.act = a;
        this.prefs = prefs;
        this.host = host;
        setBackgroundColor(0xFF0E1A2C);
        setClickable(true); // (touches never reach the chat screen under it)
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(-1, -1));

        // ---- left: Jarvis
        FrameLayout stage = new FrameLayout(a);
        row.addView(stage, new LinearLayout.LayoutParams(0, -1, 58));
        stage.addView(new Spot(a), new LayoutParams(-1, -1));
        body = new BodyView(a);
        body.rig.setLook(prefs.bodyLook());
        body.rig.setSkin(prefs.bodySkin());
        body.setContentDescription("Jarvis: మాట్లాడటానికి నొక్కండి");
        body.setOnClickListener(v -> host.talk());
        LayoutParams blp = new LayoutParams(-1, -1);
        blp.topMargin = dp(84);
        stage.addView(body, blp);
        bubble = new TextView(a);
        bubble.setTextColor(0xFFF4F8FC);
        bubble.setTextSize(23);
        bubble.setLineSpacing(0, 1.15f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setMaxLines(4);
        bubble.setEllipsize(TextUtils.TruncateAt.END);
        bubble.setMaxWidth(dp(620));
        bubble.setPadding(dp(22), dp(12), dp(22), dp(12));
        bubble.setBackground(round(0xFF1B3050, 0xFF2E4C74, 18));
        bubble.setVisibility(GONE);
        LayoutParams bb = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bb.setMargins(dp(24), dp(18), dp(24), 0);
        stage.addView(bubble, bb);

        // ---- right: the day
        LinearLayout side = new LinearLayout(a);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(0xFF13233A);
        side.setPadding(dp(30), dp(22), dp(30), dp(22));
        row.addView(side, new LinearLayout.LayoutParams(0, -1, 42));

        LinearLayout chips = new LinearLayout(a);
        chips.setGravity(Gravity.END);
        net = chip(a);
        batt = chip(a);
        chips.addView(net);
        LinearLayout.LayoutParams cg = new LinearLayout.LayoutParams(-2, -2);
        cg.leftMargin = dp(8);
        chips.addView(batt, cg);
        side.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout time = new LinearLayout(a);
        time.setGravity(Gravity.BOTTOM);
        clock = text(a, "", 84, 0xFFFFFFFF, true);
        clock.setIncludeFontPadding(false);
        part = text(a, "", 24, 0xFFF2B544, true);
        part.setPadding(dp(12), 0, 0, dp(12));
        time.addView(clock);
        time.addView(part);
        side.addView(time);
        date = text(a, "", 23, 0xFFB4C5D8, false);
        side.addView(date);
        weather = text(a, "", 21, 0xFFDCE7F3, false);
        weather.setPadding(0, dp(6), 0, 0);
        side.addView(weather);

        View line = new View(a);
        line.setBackgroundColor(0xFF2A4468);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(1));
        ll.setMargins(0, dp(16), 0, dp(14));
        side.addView(line, ll);
        side.addView(text(a, "తర్వాత", 15, 0xFF8FB4D9, true));
        next = text(a, "", 21, 0xFFF4F8FC, false);
        next.setMaxLines(2);
        next.setEllipsize(TextUtils.TruncateAt.END);
        side.addView(next);

        // అమ్మగారు's big buttons
        String[][] care = {{"tablet", "💊 వేసుకున్నాను"}, {"ate", "🍽️ తిన్నాను"}, {"bible", "🙏 బైబిల్"},
                {"songs", "🎵 పాటలు"}, {"son", "🎤 అబ్బాయికి చెప్పు"}, {"help", "🆘 సహాయం"}};
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(16);
        side.addView(grid, gl);
        LinearLayout gr = null;
        for (int i = 0; i < care.length; i++) {
            if (i % 3 == 0) {
                gr = new LinearLayout(a);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? 0 : dp(8);
                grid.addView(gr, rl);
            }
            final String what = care[i][0];
            TextView b = button(a, care[i][1], "help".equals(what) ? 0xFF8B1E1E : 0xFF1F3B5C, 0xFFFFFFFF, 17);
            b.setOnClickListener(v -> host.care(what));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(62), 1);
            if (i % 3 != 0) bl.leftMargin = dp(8);
            gr.addView(b, bl);
        }

        smart = new LinearLayout(a);
        smart.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(12);
        side.addView(smart, sl);

        side.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1)); // (the buttons stay at the bottom)

        LinearLayout buttons = new LinearLayout(a);
        TextView talk = button(a, "🎤  మాట్లాడు", 0xFFF2B544, 0xFF1A1206, 22);
        talk.setOnClickListener(v -> host.talk());
        buttons.addView(talk, new LinearLayout.LayoutParams(0, dp(64), 1));
        TextView chat = button(a, "💬", 0xFF1B3050, 0xFFDCE7F3, 24);
        chat.setContentDescription("చాట్ తెరువు");
        chat.setOnClickListener(v -> host.openChat());
        LinearLayout.LayoutParams sq = new LinearLayout.LayoutParams(dp(64), dp(64));
        sq.leftMargin = dp(10);
        buttons.addView(chat, sq);
        TextView set = button(a, "⚙️", 0xFF1B3050, 0xFFDCE7F3, 24);
        set.setContentDescription("సెట్టింగ్స్");
        set.setOnClickListener(v -> host.openSettings());
        LinearLayout.LayoutParams sq2 = new LinearLayout.LayoutParams(dp(64), dp(64));
        sq2.leftMargin = dp(10);
        buttons.addView(set, sq2);
        side.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
    }

    // ================================================================ what Jarvis does (from MainActivity)
    void setState(int s) {
        body.rig.setMode(s);
        if (s == BodyRig.LISTENING) show("వింటున్నాను…");
        else if (s == BodyRig.THINKING) show("ఆలోచిస్తున్నాను…");
    }

    /** What he says (kept on the screen ~30 s). */
    void say(String text) { if (text != null && !text.trim().isEmpty()) show(text.trim()); }

    void setMic(float l) { body.rig.setMic(l); }
    void setVoice(float l) { body.rig.setVoice(l); }
    void setFeeling(String f) { body.rig.setFeeling(f); }
    void look(boolean present, float x, float y) { body.rig.look(present, x, y); }
    void greet() { body.rig.greet(); }

    /** The word being spoken now (start..end inside the text); the same word again is ignored. */
    void word(String text, int start, int end) {
        if (text == null || start < 0 || end > text.length() || start >= end) return;
        if (start == lastStart && text.equals(lastWord)) return;
        lastWord = text;
        lastStart = start;
        body.rig.word(text.substring(start, end));
    }

    private void show(String s) {
        bubble.setText(s);
        bubble.setVisibility(VISIBLE);
        bubbleAt = System.currentTimeMillis();
    }

    private long touchedAt;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        long now = System.currentTimeMillis();
        if (now - touchedAt > 60_000L) { touchedAt = now; HomeCare.life(getContext()); } // someone is about
        return super.dispatchTouchEvent(e);
    }

    /** The screen stays on (day) or may go dark (night). */
    boolean keepOn() { return !night; }

    // ================================================================ running
    void start() {
        if (running) return;
        running = true;
        refreshSmart();
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { update(); } catch (Exception ignored) {}
            main.postDelayed(this, 15_000);
        }
    };

    private void update() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        clock.setText(String.format(Locale.US, "%d:%02d", h12, m));
        part.setText(partOfDay(h));
        date.setText(DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]);
        setNight(h >= NIGHT_FROM || h < NIGHT_TO);
        if (bubble.getVisibility() == VISIBLE && System.currentTimeMillis() - bubbleAt > 30_000
                && body.rig.mode() != BodyRig.SPEAKING && body.rig.mode() != BodyRig.LISTENING) bubble.setVisibility(GONE);
        next.setText(nextThing(getContext()));
        boolean online = online(getContext());
        net.setText(online ? "🟢 ఆన్‌లైన్" : "📴 ఆఫ్‌లైన్");
        batt.setText(battery(getContext()));
        showWeather();
        fetchWeather();
        if (!prefs.smartUrls().equals(smartShown)) refreshSmart();
    }

    static String partOfDay(int h) {
        if (h >= 4 && h < 12) return "ఉదయం";
        if (h >= 12 && h < 16) return "మధ్యాహ్నం";
        if (h >= 16 && h < 19) return "సాయంత్రం";
        return "రాత్రి";
    }

    private void setNight(boolean n) {
        if (nightSet && n == night) return;
        nightSet = true;
        night = n;
        body.setSlow(n);
        body.rig.setBase(n ? "sleepy" : "smile");
        try {
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            lp.screenBrightness = n ? 0.12f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            act.getWindow().setAttributes(lp);
            if (n) act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) {}
    }

    // ---- what comes next: the nearest reminder
    static String nextThing(Context c) {
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
        String what = null;
        try {
            for (JSONObject r : Store.get(c).reminders()) {
                if (r.optBoolean("done")) continue;
                long at = r.optLong("at");
                if (at > now && at < best) { best = at; what = r.optString("text"); }
            }
        } catch (Exception ignored) {}
        try { // her tablets
            Object[] d = Medicine.nextDose(c);
            if (d != null && (Long) d[1] > now && (Long) d[1] < best) { best = (Long) d[1]; what = "💊 " + d[0]; }
        } catch (Exception ignored) {}
        for (String[] m : HomeCare.MEALS) { // her meals still to come today
            long at = todayAt(HomeCare.time(c, m[0], m[2]));
            if (at > now && at < best && !HomeCare.ate(c, m[0])) { best = at; what = "🍽️ " + m[1]; }
        }
        if (what == null) return "ఇప్పుడు ఏమీ లేవు";
        return what + " · " + when(best, now);
    }

    /** Today at HH:mm in millis (-1 when it can't be read). */
    private static long todayAt(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            Calendar k = Calendar.getInstance();
            k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
            k.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
            return k.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    static String when(long at, long now) {
        Calendar a = Calendar.getInstance(), n = Calendar.getInstance();
        a.setTimeInMillis(at);
        n.setTimeInMillis(now);
        int h = a.get(Calendar.HOUR_OF_DAY), h12 = h % 12 == 0 ? 12 : h % 12;
        String t = partOfDay(h) + " " + String.format(Locale.US, "%d:%02d", h12, a.get(Calendar.MINUTE));
        int days = (int) ((startOfDay(a) - startOfDay(n)) / 86_400_000L);
        if (days == 0) return t;
        if (days == 1) return "రేపు " + t;
        return a.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[a.get(Calendar.MONTH)] + " " + t;
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    static String battery(Context c) {
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "";
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            return (plugged ? "⚡ " : "🔋 ") + (pct < 0 ? "" : pct + "%");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- the weather (Open-Meteo, every ~30 minutes; the last one is kept for offline)
    private void showWeather() {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long at = sp.getLong("w_at", 0);
        if (at == 0) { weather.setText(""); return; }
        String old = System.currentTimeMillis() - at > 3 * 3600_000L ? " (పాతది)" : "";
        weather.setText(Math.round(sp.getFloat("w_temp", 0)) + "° · " + HudDashboard.sky(sp.getInt("w_code", -1)) + old);
    }

    private void fetchWeather() {
        android.content.SharedPreferences sp = getContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (weatherBusy || now - sp.getLong("w_at", 0) < 30 * 60_000L || now - weatherTry < 10 * 60_000L) return;
        weatherBusy = true;
        weatherTry = now;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                Location l = Devices.freshFix(app); // (a SIM-less tablet may have no last place yet: a fix is asked for)
                if (l == null) return;
                JSONObject cur = Http.get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code",
                        l.getLatitude(), l.getLongitude())).optJSONObject("current");
                if (cur == null || !cur.has("temperature_2m")) return;
                app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putFloat("w_temp", (float) cur.optDouble("temperature_2m"))
                        .putInt("w_code", cur.optInt("weather_code", -1)).putLong("w_at", System.currentTimeMillis()).apply();
                main.post(() -> { if (running) showWeather(); });
            } catch (Throwable ignored) {
                // offline / no location: the last weather stays
            } finally {
                weatherBusy = false;
            }
        }, "home-weather").start();
    }

    // ---- smart-home buttons: his saved Alexa links ("ac on = https://…")
    private void refreshSmart() {
        smartShown = prefs.smartUrls();
        smart.removeAllViews();
        List<String[]> cmds = new ArrayList<>();
        for (String line : smartShown.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim(), url = line.substring(eq + 1).trim();
            if (name.toLowerCase(Locale.ROOT).startsWith("charger")) continue; // (the battery-care links work by themselves)
            if (!name.isEmpty() && url.startsWith("http")) cmds.add(new String[]{name, url});
            if (cmds.size() == 4) break;
        }
        LinearLayout r = null;
        for (int i = 0; i < cmds.size(); i++) {
            if (i % 2 == 0) {
                r = new LinearLayout(getContext());
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? 0 : dp(8);
                smart.addView(r, rl);
            }
            final String[] cmd = cmds.get(i);
            final String label = label(cmd[0]);
            TextView b = button(getContext(), label, 0xFF1B3050, 0xFFDCE7F3, 18);
            b.setOnClickListener(v -> run(label, cmd[1]));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i % 2 == 1) bl.leftMargin = dp(8);
            r.addView(b, bl);
        }
        if (cmds.size() % 2 == 1 && r != null) r.addView(new View(getContext()), new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    /** "ac on" -> "AC ఆన్", "fan off" -> "Fan ఆఫ్". */
    static String label(String name) {
        StringBuilder out = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            String l = w.toLowerCase(Locale.ROOT), s;
            if (l.equals("on")) s = "ఆన్";
            else if (l.equals("off")) s = "ఆఫ్";
            else if (l.length() <= 2 || l.equals("tv")) s = w.toUpperCase(Locale.ROOT);
            else s = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (out.length() > 0) out.append(' ');
            out.append(s);
        }
        return out.toString();
    }

    private void run(String label, String url) {
        show(label + "…");
        new Thread(() -> {
            boolean ok;
            try { Http.getText(url); ok = true; } catch (Exception e) { ok = false; }
            final boolean done = ok;
            main.post(() -> {
                if (done) { show("✓ " + label + " చేశాను"); body.rig.show("happy", 2500); body.rig.gesture("thumb", 2500); }
                else { show("⚠️ " + label + ": " + (online(getContext()) ? "లింక్ జవాబివ్వలేదు" : "నెట్ లేదు")); body.rig.show("worried", 2500); }
            });
        }, "home-smart").start();
    }

    // ================================================================ small parts
    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(Context c) {
        TextView t = text(c, "", 15, 0xFFDCE7F3, false);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setBackground(round(0xFF1B3050, 0xFF2A4468, 14));
        return t;
    }

    private TextView button(Context c, String s, int bg, int fg, float sp) {
        TextView t = text(c, s, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, bg == 0xFFF2B544 ? bg : 0xFF2A4468, 16));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), stroke);
        return g;
    }

    /** The soft round light behind Jarvis. */
    private static final class Spot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Spot(Context c) { super(c); p.setColor(0xFF15294A); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.36f;
            cv.drawCircle(w / 2f, h * 0.46f, r, p);
        }
    }
}
