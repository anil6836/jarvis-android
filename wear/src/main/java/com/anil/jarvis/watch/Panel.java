package com.anil.jarvis.watch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 3 screens on the watch, opened from the Jarvis screen's buttons or the Jarvis tile:
 *   status (W22) · duty: countdown, time to leave, the bag to tick, handover notes, duty mode (W24) · tasks: habits,
 *   missions and the shopping list with one tap (W34) · home: his lights and fans (W35) · timer (W33) · cooker (W33).
 * What to show comes from the phone (P_ASK -> P_PANEL; the last one is kept, so it shows at once); buttons go to the
 * phone as P_DO. Timers are the watch's own. A tap that can't be undone (a mission done, a thing bought) asks a second tap.
 */
public class Panel extends Activity {
    static final String EXTRA_KIND = "kind";

    private static Panel shown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String kind = "status";
    private ScrollView scroll;
    private LinearLayout col;
    private TextView toast;
    private JSONObject data;
    private float rotary;
    private int cookerN = 3;
    private String armed = "";
    private long armedAt;
    /** Lines that count down: {view, end (wall ms), words before}. */
    private final List<Object[]> ticking = new ArrayList<>();

    static void open(Context c, String kind) {
        c.startActivity(new Intent(c, Panel.class).putExtra(EXTRA_KIND, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_panels", Context.MODE_PRIVATE); }

    private static JSONObject saved(Context c, String kind) {
        try { return new JSONObject(sp(c).getString(kind, "")); } catch (Exception e) { return null; }
    }

    /** The phone's answer (main thread): kept, shown if that screen is open; a short word shown there (or on the Jarvis screen). */
    static void got(Context app, JSONObject o) {
        String k = o.optString("kind");
        if ("here".equals(k)) { Compass.got(app, o); return; } // (the compass's GPS: every few seconds, not kept)
        if ("nav".equals(k)) Compass.got(app, o);
        String t = "toast".equals(k) ? o.optString("text") : o.optString("toast");
        if (!"toast".equals(k) && !k.isEmpty()) {
            sp(app).edit().putString(k, o.toString()).apply();
            if ("tasks".equals(k) || "duty".equals(k)) JarvisTile.refresh(app);
        }
        Panel p = shown;
        if (p != null && !"toast".equals(k) && (k.equals(p.kind) || "protocol".equals(k) && "protocols".equals(p.kind))) { p.data = o; if ("protocol".equals(k)) p.kind = "protocol"; p.render(); }
        else if ("protocol".equals(k) && p == null) { // (started by voice: the steps show here)
            Screens.launch(app, new Intent(app, Panel.class).putExtra(EXTRA_KIND, "protocol"), "⚡ ప్రోటోకాల్", false);
        }
        if (!t.isEmpty()) {
            if (p != null) p.showToast(t);
            else { Talk.status = t; Talk.changed(); }
            Talk.buzz(app, 25);
        }
    }

    /** Draws the open screen again (the watch's radio started / stopped). */
    static void redraw() {
        Panel p = shown;
        if (p != null) p.main.post(p::render);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Screens.seen(this);
        Theme.refresh(this);
        kind = getIntent().getStringExtra(EXTRA_KIND);
        if (kind == null) kind = "status";
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Math.round(w * 0.12f);
        col.setPadding(side, Math.round(h * 0.13f), side, Math.round(h * 0.25f));
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        data = saved(this, kind);
        render();
        ask();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String k = i.getStringExtra(EXTRA_KIND);
        if (k != null && !k.equals(kind)) { kind = k; data = saved(this, kind); render(); ask(); }
    }

    @Override protected void onResume() {
        super.onResume();
        shown = this;
        scroll.requestFocus();
        main.post(tick);
    }

    @Override protected void onPause() {
        if (shown == this) shown = null;
        main.removeCallbacks(tick);
        super.onPause();
    }

    /** The phone's data for this screen (the timer is the watch's own). */
    private void ask() {
        if ("timer".equals(kind) || "diary".equals(kind) || "medical".equals(kind) || "focus".equals(kind) || "sleep".equals(kind)) return; // (nothing to fetch)
        if ("protocol".equals(kind)) return;
        try { Link.send(this, Link.P_ASK, new JSONObject().put("kind", kind)); } catch (Exception ignored) {}
    }

    private void act(JSONObject o) { Link.send(this, Link.P_DO, o); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (updateTicks()) render();
            main.postDelayed(this, "timer".equals(kind) ? 1000 : 30_000);
        }
    };

    /** The countdowns now; true when a timer ended (the list is drawn again). */
    private boolean updateTicks() {
        long now = System.currentTimeMillis();
        boolean ended = false;
        for (Object[] t : ticking) {
            long left = (long) t[1] - now;
            if (left <= 0 && "timer".equals(kind)) ended = true;
            ((TextView) t[0]).setText(t[2] + ("duty".equals(kind) ? span(left) : Timers.left(left)));
        }
        return ended;
    }

    /** 90061000 -> "1రో 1గం 1ని". */
    static String span(long ms) {
        long m = Math.max(0, ms) / 60_000L, d = m / 1440, h = (m % 1440) / 60, mm = m % 60;
        return (d > 0 ? d + "రో " : "") + (d > 0 || h > 0 ? h + "గం " : "") + mm + "ని";
    }

    // ---------------------------------------------------------------- drawing

    private void render() {
        if (isDestroyed()) return;
        col.removeAllViews();
        ticking.clear();
        String title;
        switch (kind) {
            case "duty": title = "🏍️ డ్యూటీ"; break;
            case "tasks": title = "✅ పనులు"; break;
            case "home": title = "💡 ఇల్లు"; break;
            case "timer": title = "⏱️ టైమర్"; break;
            case "cooker": title = "🍲 కుక్కర్"; break;
            case "music": title = "🎵 పాటలు"; break;
            case "radio": title = "📻 రేడియో"; break;
            case "water": title = "💧 నీళ్లు"; break;
            case "money": title = "💰 ఈ నెల ఖర్చు"; break;
            case "protocols": case "protocol": title = "⚡ ప్రోటోకాల్స్"; break;
            case "journey": title = "🛡️ నాతో ఉండు"; break;
            case "diary": title = "📔 ఈరోజు ఎలా గడిచింది?"; break;
            case "medical": title = "🩸 మెడికల్ కార్డ్"; break;
            case "focus": title = "🎯 ఫోకస్"; break;
            case "sleep": title = "😴 నిద్ర"; break;
            default: title = "📊 స్టేటస్";
        }
        TextView t = WUi.text(this, title, 15, Theme.accent, true);
        col.addView(t);
        toast = WUi.text(this, "", 12, WUi.TEXT, true);
        toast.setVisibility(View.GONE);
        col.addView(toast);
        try {
            switch (kind) {
                case "duty": duty(); break;
                case "tasks": tasks(); break;
                case "home": home(); break;
                case "timer": timer(); break;
                case "cooker": cooker(); break;
                case "music": music(); break;
                case "radio": radio(); break;
                case "water": water(); break;
                case "money": money(); break;
                case "protocols": case "protocol": protocols(); break;
                case "journey": journey(); break;
                case "diary": diary(); break;
                case "medical": medical(); break;
                case "focus": focus(); break;
                case "sleep": sleep(); break;
                default: status();
            }
        } catch (Exception e) {
            line("చూపించలేకపోయాను: " + e.getMessage(), WUi.MUTED);
        }
        updateTicks();
    }

    private void showToast(String s) {
        if (toast == null) return;
        toast.setText(s);
        toast.setVisibility(View.VISIBLE);
        scroll.smoothScrollTo(0, 0);
    }

    private TextView line(String s, int color) {
        TextView v = WUi.text(this, s, 13, color, true);
        col.addView(v);
        return v;
    }

    private void head(String s) {
        TextView v = WUi.text(this, s, 12.5f, Theme.accent, true);
        v.setPadding(0, WUi.dp(this, 10), 0, WUi.dp(this, 2));
        col.addView(v);
    }

    private TextView button(String label, int color, View.OnClickListener l) {
        TextView p = WUi.pill(this, label, color);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = WUi.dp(this, 5);
        col.addView(p, lp);
        return p;
    }

    private void waiting() { line(Link.ok ? "ఫోన్ నుంచి తెస్తున్నాను…" : "ఫోన్ అందడం లేదు (Bluetooth?)", WUi.MUTED); }

    /** A second tap within 4 seconds does it. */
    private boolean second(String key, TextView v, String ask) {
        long now = System.currentTimeMillis();
        if (key.equals(armed) && now - armedAt < 4000) { armed = ""; return true; }
        armed = key;
        armedAt = now;
        v.setText(ask);
        Talk.buzz(this, 15);
        main.postDelayed(() -> { if (key.equals(armed)) { armed = ""; render(); } }, 4200); // (not tapped again: back as it was)
        return false;
    }

    private void status() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        int bat = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (bat >= 0) line("⌚ ఈ వాచ్ బ్యాటరీ " + bat + "%", WUi.TEXT);
        line(Link.ok ? "📱 ఫోన్ కనెక్ట్ అయింది" : "📱 ఫోన్ అందడం లేదు", Link.ok ? WUi.TEXT : 0xFFFCA5A5);
        if (data == null) { waiting(); }
        else {
            JSONArray l = data.optJSONArray("lines");
            for (int i = 0; l != null && i < l.length(); i++) line(l.optString(i), WUi.TEXT);
            long ago = (System.currentTimeMillis() - data.optLong("at")) / 60_000L;
            if (ago >= 2) line("(" + ago + " ని క్రితం)", WUi.FAINT);
        }
        button("🔄 మళ్లీ చూడు", 0xFF0E3A4A, v -> { ask(); showToast("ఫోన్‌ని అడుగుతున్నాను…"); });
    }

    private void duty() {
        if (data == null) { waiting(); return; }
        if (!data.optBoolean("set")) { line("ఫోన్‌లో డ్యూటీ క్యాలెండర్ ఇంకా సెట్ చేయలేదు. Jarvis కి చెప్పి పెట్టండి.", WUi.MUTED); return; }
        long start = data.optLong("start"), end = data.optLong("end"), now = System.currentTimeMillis();
        if (start > 0 && now >= start && now < end) {
            line("ఇప్పుడు డ్యూటీలో ఉన్నారు", WUi.TEXT);
            TextView v = WUi.text(this, "", 19, Theme.accent, true);
            col.addView(v);
            ticking.add(new Object[]{v, end, "అయిపోవడానికి "});
        } else if (start > now) {
            line("తర్వాతి డ్యూటీ: " + data.optString("when"), WUi.TEXT);
            TextView v = WUi.text(this, "", 19, Theme.accent, true);
            col.addView(v);
            ticking.add(new Object[]{v, start, "ఇంకా "});
            line("🚀 " + hm(data.optString("leave")) + " కి బయలుదేరాలి", WUi.TEXT);
        } else line("వచ్చే 12 రోజుల్లో డ్యూటీ లేదు", WUi.MUTED);
        if (!data.optString("month").isEmpty()) line(data.optString("month"), WUi.MUTED);
        boolean mode = data.optBoolean("mode");
        button(mode ? "🛡️ డ్యూటీ మోడ్ ఆన్ ✓ (ఆఫ్ చేయి)" : "🛡️ డ్యూటీ మోడ్ ఆన్ చేయి", mode ? 0xFF166534 : 0xFF0E3A4A, v -> {
            try { act(new JSONObject().put("what", "duty_mode").put("on", !mode)); } catch (Exception ignored) {}
            showToast("ఫోన్‌కి చెప్పాను…");
        });
        JSONArray bag = data.optJSONArray("bag");
        if (bag != null && bag.length() > 0) {
            head("🎒 బ్యాగ్ (నొక్కి టిక్ చేయండి)");
            String key = "bag_" + start;
            Set<String> done = new HashSet<>(sp(this).getStringSet(key, new HashSet<>()));
            for (int i = 0; i < bag.length(); i++) {
                String item = bag.optString(i);
                boolean d = done.contains(item);
                button((d ? "✓ " : "○ ") + item, d ? 0xFF166534 : 0xFF0B2230, v -> {
                    Set<String> s = new HashSet<>(sp(this).getStringSet(key, new HashSet<>()));
                    if (!s.remove(item)) s.add(item);
                    sp(this).edit().putStringSet(key, s).apply();
                    Talk.buzz(this, 15);
                    render();
                });
            }
        }
        head("📝 హ్యాండోవర్ నోట్స్");
        JSONArray notes = data.optJSONArray("notes");
        if (notes == null || notes.length() == 0) line("ఏమీ లేవు", WUi.FAINT);
        for (int i = 0; notes != null && i < notes.length(); i++) line("• " + notes.optString(i), WUi.TEXT);
        button("🎙️ నోట్ చెప్పు", 0xFF0E3A4A, v -> listen("handover"));
    }

    private static String hm(String t) {
        try {
            String[] p = t.split(":");
            int h = Integer.parseInt(p[0]);
            return (h % 12 == 0 ? 12 : h % 12) + ":" + p[1] + (h < 12 ? " ఉదయం" : h < 16 ? " మధ్యాహ్నం" : h < 19 ? " సాయంత్రం" : " రాత్రి");
        } catch (Exception e) {
            return t;
        }
    }

    /** The Jarvis screen listens (the answer shows there). */
    private void listen(String why) {
        startActivity(new Intent(this, WatchActivity.class).putExtra(WatchActivity.EXTRA_LISTEN, why).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void tasks() throws Exception {
        if (data == null) { waiting(); return; }
        JSONArray h = data.optJSONArray("habits"), m = data.optJSONArray("missions"), buy = data.optJSONArray("buy");
        boolean any = false;
        if (h != null && h.length() > 0) {
            any = true;
            head("అలవాట్లు (ఈరోజు)");
            for (int i = 0; i < h.length(); i++) {
                JSONObject x = h.getJSONObject(i);
                boolean d = x.optBoolean("done");
                String name = x.optString("name");
                int streak = x.optInt("streak");
                button((d ? "✓ " : "○ ") + name + (streak > 1 ? "  🔥" + streak : ""), d ? 0xFF166534 : 0xFF0B2230, v -> {
                    try {
                        x.put("done", !d);
                        act(new JSONObject().put("what", "habit").put("name", name).put("done", !d));
                    } catch (Exception ignored) {}
                    Talk.buzz(this, 15);
                    render();
                });
            }
        }
        if (m != null && m.length() > 0) {
            any = true;
            head("🎯 మిషన్లు");
            for (int i = 0; i < m.length(); i++) {
                JSONObject x = m.getJSONObject(i);
                String id = x.optString("id");
                TextView[] self = new TextView[1];
                self[0] = button("○ " + x.optString("text"), 0xFF0B2230, v -> {
                    if (!second("m" + id, self[0], "✓ పూర్తయిందా? మళ్లీ నొక్కండి")) return;
                    try { act(new JSONObject().put("what", "mission").put("id", id)); } catch (Exception ignored) {}
                    showToast("పూర్తి చేస్తున్నాను…");
                });
            }
        }
        if (buy != null && buy.length() > 0) {
            any = true;
            head("🛒 కొనాల్సినవి");
            for (int i = 0; i < buy.length(); i++) {
                String item = buy.optString(i);
                TextView[] self = new TextView[1];
                self[0] = button("○ " + item, 0xFF0B2230, v -> {
                    if (!second("b" + item, self[0], "✓ కొన్నారా? మళ్లీ నొక్కండి")) return;
                    try { act(new JSONObject().put("what", "bought").put("item", item)); } catch (Exception ignored) {}
                    showToast("టిక్ పెడుతున్నాను…");
                });
            }
        }
        if (!any) line("అలవాట్లు, మిషన్లు, షాపింగ్ లిస్ట్ ఏమీ లేవు. ఫోన్‌లో Jarvis కి చెప్పి పెట్టండి.", WUi.MUTED);
    }

    private void home() {
        if (data == null) { waiting(); return; }
        JSONArray a = data.optJSONArray("cmds");
        if (!data.optBoolean("online", true)) line("ఫోన్‌కి నెట్ లేదు: లైట్లు మారవు", 0xFFFCA5A5);
        if (a == null || a.length() == 0) {
            line("ఫోన్ Jarvis Settings → స్మార్ట్ హోమ్ లో Alexa routine లింక్‌లు పెడితే ఇక్కడ బటన్లుగా వస్తాయి. "
                    + "లేదా \"హాల్ లైట్ ఆఫ్ చెయ్\" అని Jarvis కి చెప్పండి.", WUi.MUTED);
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            String name = a.optString(i);
            boolean off = name.contains("ఆఫ్") || name.toLowerCase(java.util.Locale.ROOT).contains("off");
            button((off ? "⚫ " : "💡 ") + name, off ? 0xFF1F2937 : 0xFF0E3A4A, v -> {
                try { act(new JSONObject().put("what", "smart").put("name", name)); } catch (Exception ignored) {}
                showToast(name + ": పంపుతున్నాను…");
            });
        }
    }

    private void timer() {
        List<JSONObject> run = Timers.running(this);
        long now = System.currentTimeMillis();
        for (JSONObject o : run) {
            int id = o.optInt("id");
            long at = o.optLong("at");
            if (at <= now) continue;
            TextView[] self = new TextView[1];
            self[0] = button("", 0xFF0E3A4A, v -> {
                if (!second("t" + id, self[0], "✗ ఆపేయాలా? మళ్లీ నొక్కండి")) return;
                Timers.cancel(this, id);
                render();
            });
            ticking.add(new Object[]{self[0], at, o.optString("label") + " · "});
            self[0].setText(o.optString("label") + " · " + Timers.left(at - now));
        }
        if (!run.isEmpty()) head("కొత్త టైమర్");
        int[] mins = {1, 2, 3, 5, 10, 15, 20, 30, 45, 60};
        LinearLayout row = null;
        for (int i = 0; i < mins.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = WUi.dp(this, 5);
                col.addView(row, lp);
            }
            int m = mins[i];
            TextView p = WUi.pill(this, m + " ని", 0xFF0B2230);
            p.setOnClickListener(v -> { Timers.start(this, m * 60, m + " నిమిషాల టైమర్"); showToast("⏱️ " + m + " నిమిషాలు మొదలయ్యాయి"); render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) lp.leftMargin = WUi.dp(this, 6);
            row.addView(p, lp);
        }
        line("\"Jarvis, 5 నిమిషాల టైమర్\" అని వాచ్‌కి చెప్పినా ఇక్కడే పెడతాను. అయిపోతే చేతికి వైబ్రేషన్.", WUi.FAINT);
    }

    private void cooker() {
        if (data != null && data.optBoolean("counting")) {
            line("లెక్కపెడుతున్నాను: " + data.optInt("heard") + " / " + data.optInt("target") + " విజిల్స్", WUi.TEXT);
            button("✓ స్టవ్ ఆపాను / లెక్క ఆపు", 0xFF166534, v -> {
                try { act(new JSONObject().put("what", "cooker_stop")); } catch (Exception ignored) {}
            });
            button("🔄 మళ్లీ చూడు", 0xFF0E3A4A, v -> ask());
            return;
        }
        line("ఎన్ని విజిల్స్?", WUi.TEXT);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView minus = WUi.pill(this, "−", 0xFF0B2230), plus = WUi.pill(this, "+", 0xFF0B2230);
        TextView n = WUi.text(this, String.valueOf(cookerN), 24, Theme.accent, true);
        n.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> { cookerN = Math.max(1, cookerN - 1); n.setText(String.valueOf(cookerN)); });
        plus.setOnClickListener(v -> { cookerN = Math.min(10, cookerN + 1); n.setText(String.valueOf(cookerN)); });
        row.addView(minus, new LinearLayout.LayoutParams(WUi.dp(this, 46), -2));
        row.addView(n, new LinearLayout.LayoutParams(WUi.dp(this, 56), -2));
        row.addView(plus, new LinearLayout.LayoutParams(WUi.dp(this, 46), -2));
        col.addView(row);
        button("🍲 లెక్క మొదలుపెట్టు", 0xFF0E3A4A, v -> {
            try { act(new JSONObject().put("what", "cooker").put("n", cookerN)); } catch (Exception ignored) {}
            showToast("ఫోన్‌కి చెప్పాను…");
        });
        line("ఫోన్ వంటింట్లో కుక్కర్ దగ్గర ఉండాలి. ప్రతి విజిల్ వాచ్‌లో వైబ్రేట్ అవుతుంది.", WUi.FAINT);
    }

    // ---------------------------------------------------------------- phase 5: music (W50), radio (W36)

    /** A row of small buttons side by side. */
    private void row(String[] labels, View.OnClickListener[] taps) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = WUi.dp(this, 5);
        col.addView(r, lp);
        for (int i = 0; i < labels.length; i++) {
            TextView p = WUi.pill(this, labels[i], 0xFF0B2230);
            p.setOnClickListener(taps[i]);
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) q.leftMargin = WUi.dp(this, 5);
            r.addView(p, q);
        }
    }

    private void media(String action) {
        try { act(new JSONObject().put("what", "media").put("action", action)); } catch (Exception ignored) {}
        Talk.buzz(this, 15);
    }

    /** A key to whatever plays on the watch itself (its YouTube Music, its player). */
    private void watchKey(int code) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        long t = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0));
        Talk.buzz(this, 15);
    }

    private void music() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (RadioPlayer.playing()) {
            head("⌚ వాచ్ రేడియో");
            line("📻 " + RadioPlayer.name + " · " + RadioPlayer.status, WUi.TEXT);
            button("⏹ రేడియో ఆపు", 0xFF7F1D1D, v -> { RadioPlayer.stop(this); render(); });
        } else if (am != null && am.isMusicActive()) {
            head("⌚ వాచ్‌లో ప్లే అవుతోంది");
            row(new String[]{"⏮", "⏯", "⏭"}, new View.OnClickListener[]{
                    v -> watchKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS), v -> watchKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                    v -> watchKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)});
        }
        head("📱 ఫోన్‌లో");
        if (data == null) waiting();
        else if (data.optBoolean("none")) line(data.optBoolean("access", true) ? "ఫోన్‌లో ఏ పాటా ప్లే అవడం లేదు" : "ఫోన్ Jarvis కి నోటిఫికేషన్ యాక్సెస్ కావాలి", WUi.MUTED);
        else {
            line((data.optBoolean("playing") ? "▶ " : "⏸ ") + data.optString("app"), WUi.MUTED);
            if (!data.optString("title").isEmpty()) line(data.optString("title"), WUi.TEXT);
            if (!data.optString("artist").isEmpty()) line(data.optString("artist"), WUi.FAINT);
        }
        row(new String[]{"⏮", "⏯", "⏭"}, new View.OnClickListener[]{v -> media("previous"), v -> media("toggle"), v -> media("next")});
        row(new String[]{"🔉", "🔊"}, new View.OnClickListener[]{v -> media("volume_down"), v -> media("volume_up")});
        if (data != null && data.has("vol")) line("ఫోన్ సౌండ్ " + data.optInt("vol") + "%", WUi.FAINT);
        button("🎵 ఈ పాట ఏది?", 0xFF0E3A4A, v -> {
            try { Talk.phoneDo(this, new JSONObject().put("what", "song"), "🎵 చూస్తున్నాను…"); } catch (Exception ignored) {}
            finish();
        });
        row(new String[]{"🌙 30 ని", "🌙 60 ని"}, new View.OnClickListener[]{
                v -> { try { act(new JSONObject().put("what", "sleep_timer").put("minutes", 30)); } catch (Exception ignored) {} },
                v -> { try { act(new JSONObject().put("what", "sleep_timer").put("minutes", 60)); } catch (Exception ignored) {} }});
        line("🌙 = అన్ని పాటలూ (ఫోన్, వాచ్ రేడియో) ఆ తర్వాత ఆగుతాయి", WUi.FAINT);
        button("🔄 మళ్లీ చూడు", 0xFF0E3A4A, v -> ask());
    }

    private void radio() {
        if (RadioPlayer.playing()) {
            line("▶ " + RadioPlayer.name, Theme.accent);
            line(RadioPlayer.status, WUi.MUTED);
            String now = RadioPlayer.name;
            row(new String[]{"⏹ ఆపు", "📱 ఫోన్‌లో"}, new View.OnClickListener[]{
                    v -> { RadioPlayer.stop(this); render(); },
                    v -> { try { act(new JSONObject().put("what", "radio_phone").put("name", now)); } catch (Exception ignored) {} RadioPlayer.stop(this); render(); }});
        }
        if (data == null) { waiting(); return; }
        if (!data.optBoolean("online", true)) line("ఫోన్‌కి నెట్ లేదు: కొత్త లింక్‌లు దొరకవు", 0xFFFCA5A5);
        JSONArray st = data.optJSONArray("stations");
        if (st == null || st.length() == 0) { line("ఫోన్ Jarvis లో రేడియో స్టేషన్లు లేవు", WUi.MUTED); return; }
        head("వాచ్‌లోనే వినండి (నొక్కండి)");
        for (int i = 0; i < st.length(); i++) {
            JSONObject x = st.optJSONObject(i);
            if (x == null) continue;
            String n = x.optString("name");
            button((x.optBoolean("fav") ? "⭐ " : "📻 ") + n, n.equals(RadioPlayer.name) ? 0xFF166534 : 0xFF0B2230, v -> {
                try { act(new JSONObject().put("what", "radio_watch").put("name", n)); } catch (Exception ignored) {}
                showToast("📻 " + n + ": లింక్ తెస్తున్నాను…");
            });
        }
        line("వాచ్‌కి బ్లూటూత్ ఇయర్‌బడ్స్ కలిపితే వాటిలో, లేకపోతే వాచ్ స్పీకర్‌లో. నెట్ ఫోన్ ద్వారా లేదా Wi-Fi. బ్యాటరీ కొంచెం ఎక్కువ ఖర్చవుతుంది.", WUi.FAINT);
    }

    // ---------------------------------------------------------------- phase 5: W70 water, W82 money, W58 protocols, W81 journey, W72 diary, W79 medical, W73 focus, W67 sleep

    private void water() {
        int n = data == null ? Link.info(this).optInt("water") : data.optInt("n"), goal = data == null ? Link.info(this).optInt("water_goal", 8) : data.optInt("goal", 8);
        TextView big = WUi.text(this, n + " / " + goal, 30, Theme.accent, true);
        col.addView(big);
        line(n >= goal ? "ఈరోజు లక్ష్యం అయిపోయింది 👍" : "ఇంకా " + (goal - n) + " గ్లాసులు" + (goal > 8 ? " (ఎండ ఎక్కువ)" : ""), WUi.MUTED);
        button("💧 +1 గ్లాసు", 0xFF0E3A4A, v -> {
            try { act(new JSONObject().put("what", "water").put("n", 1)); } catch (Exception ignored) {}
            Talk.buzz(this, 20);
            showToast("💧 +1");
        });
        button("− 1 (తప్పు నొక్కాను)", 0xFF1F2937, v -> { try { act(new JSONObject().put("what", "water").put("n", -1)); } catch (Exception ignored) {} });
    }

    private void money() {
        if (data == null) { waiting(); return; }
        if (data.optBoolean("off")) { line("ఖర్చుల రింగ్ ఫోన్ సెట్టింగ్స్‌లో ఆఫ్‌లో ఉంది.", WUi.MUTED); return; }
        long spent = data.optLong("spent", -1), budget = data.optLong("budget");
        if (spent < 0) { line("ఫోన్ Jarvis కి SMS చదివే అనుమతి లేదు.", WUi.MUTED); return; }
        Ring r = new Ring(this, budget > 0 ? Math.min(1.5f, spent / (float) budget) : -1, data.optInt("day") / (float) Math.max(1, data.optInt("days", 30)));
        col.addView(r, new LinearLayout.LayoutParams(-1, WUi.dp(this, 110)));
        line("₹" + String.format(java.util.Locale.ENGLISH, "%,d", spent) + (budget > 0 ? " / ₹" + String.format(java.util.Locale.ENGLISH, "%,d", budget) : ""), WUi.TEXT);
        if (budget <= 0) line("బడ్జెట్ పెట్టలేదు: \"నెల బడ్జెట్ 20000\" అని Jarvis కి చెప్పండి.", WUi.FAINT);
        else if (spent > budget) line("బడ్జెట్ దాటింది", 0xFFFCA5A5);
        line("బ్యాంక్ / UPI SMS నుంచి ఫోన్‌లోనే లెక్క (ఏ AI కీ పంపను). లోపలి గీత = నెలలో గడిచిన రోజులు.", WUi.FAINT);
    }

    /** W82's ring: spent against the budget, and a thin ring for how much of the month has gone. */
    private static final class Ring extends View {
        private final float part, month;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Ring(Context c, float part, float month) { super(c); this.part = part; this.month = month; }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float s = Math.min(getWidth(), getHeight()) * 0.42f, cx = getWidth() / 2f, cy = getHeight() / 2f, w = s * 0.18f;
            android.graphics.RectF o = new android.graphics.RectF(cx - s, cy - s, cx + s, cy + s), in = new android.graphics.RectF(cx - s * 0.68f, cy - s * 0.68f, cx + s * 0.68f, cy + s * 0.68f);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(w);
            p.setColor(0xFF1F2937);
            cv.drawArc(o, 0, 360, false, p);
            if (part >= 0) { p.setColor(part > 1 ? 0xFFF87171 : part > month + 0.1f ? 0xFFFBBF24 : Theme.accent); cv.drawArc(o, -90, 360 * Math.min(1, part), false, p); }
            p.setStrokeWidth(w * 0.35f);
            p.setColor(0xFF64748B);
            cv.drawArc(in, -90, 360 * Math.min(1, month), false, p);
        }
    }

    private void protocols() {
        if ("protocol".equals(kind) && data != null && data.has("steps")) { // running: each step ticks
            head("⚡ " + data.optString("name"));
            JSONArray st = data.optJSONArray("steps");
            for (int i = 0; st != null && i < st.length(); i++) {
                JSONObject x = st.optJSONObject(i);
                if (x == null) continue;
                line((x.optBoolean("done") ? "✓ " : "◌ ") + x.optString("label"), x.optBoolean("done") ? WUi.TEXT : WUi.FAINT);
                if (!x.optString("line").isEmpty()) line(x.optString("line"), WUi.MUTED);
            }
            if (data.optBoolean("done")) { Talk.buzz(this, 40, 60, 40); button("సరే", 0xFF166534, v -> finish()); }
            return;
        }
        if (data == null || !data.has("names")) { waiting(); return; }
        JSONArray names = data.optJSONArray("names");
        for (int i = 0; names != null && i < names.length(); i++) {
            String n = names.optString(i);
            button("⚡ " + n, 0xFF0B2230, v -> {
                try { act(new JSONObject().put("what", "protocol").put("name", n)); } catch (Exception ignored) {}
                kind = "protocol";
                data = null;
                col.removeAllViews();
                line("⚡ " + n + " మొదలవుతోంది…", Theme.accent);
            });
        }
        line("మీ సొంతది: \"ప్రోటోకాల్ జిమ్: సైలెంట్, వాతావరణం\" అని Jarvis కి చెప్పండి.", WUi.FAINT);
    }

    private void journey() {
        if (data == null) { waiting(); return; }
        if (data.optBoolean("on")) {
            line("🛡️ మీతో ఉన్నాను: ఆలస్యమైతే, రాత్రి అయితే \"అంతా బాగుందా?\" అని అడుగుతాను.", WUi.TEXT);
            button("✓ చేరుకున్నాను / ఆపు", 0xFF166534, v -> { try { act(new JSONObject().put("what", "journey").put("on", false)); } catch (Exception ignored) {} });
            return;
        }
        line("ఎక్కడికి? (ఆలస్యమైతే / రాత్రి అడుగుతాను; జవాబు లేకపోతే SOS)", WUi.MUTED);
        button("🏠 ఇంటికి", 0xFF0E3A4A, v -> { try { act(new JSONObject().put("what", "journey").put("on", true).put("place", "ఇల్లు")); } catch (Exception ignored) {} showToast("మొదలుపెడుతున్నాను…"); });
        button("🏍️ డ్యూటీకి", 0xFF0E3A4A, v -> { try { act(new JSONObject().put("what", "journey").put("on", true).put("place", "డ్యూటీ")); } catch (Exception ignored) {} showToast("మొదలుపెడుతున్నాను…"); });
        button("🎙️ చెప్పండి", 0xFF0B2230, v -> listen("journey"));
    }

    private void diary() {
        line("మూడ్:", WUi.MUTED);
        String[] moods = {"😀", "🙂", "😐", "😟", "😣"};
        View.OnClickListener[] taps = new View.OnClickListener[moods.length];
        for (int i = 0; i < moods.length; i++) {
            String m = moods[i];
            taps[i] = v -> { try { act(new JSONObject().put("what", "mood").put("m", m)); } catch (Exception ignored) {} showToast(m + " ✓"); Talk.buzz(this, 15); };
        }
        row(moods, taps);
        button("🎙️ 1 నిమిషం చెప్పండి", 0xFF0E3A4A, v -> listen("diary"));
        line("ఫోన్ డైరీలో రాస్తాను, ఈరోజు ఒత్తిడితో పాటు.", WUi.FAINT);
    }

    private void medical() {
        JSONObject m = Link.info(this).optJSONObject("medical");
        if (m == null || !m.optBoolean("on")) { line("ఫోన్ Settings → ⌚ వాచ్ లో \"🩸 మెడికల్ కార్డ్ వాచ్‌లో\" ఆన్ చేస్తే ఇక్కడ వస్తుంది.", WUi.MUTED); return; }
        TextView n = WUi.text(this, m.optString("name"), 18, 0xFFFFFFFF, true);
        col.addView(n);
        if (!m.optString("blood").isEmpty()) col.addView(WUi.text(this, "🩸 " + m.optString("blood"), 26, 0xFFF87171, true));
        if (!m.optString("allergy").isEmpty()) line("⚠️ అలర్జీ: " + m.optString("allergy"), WUi.TEXT);
        if (!m.optString("notes").isEmpty()) line(m.optString("notes"), WUi.TEXT);
        if (!m.optString("contact").isEmpty()) line("📞 " + m.optString("contact"), Theme.accent);
    }

    private void focus() {
        line("ఎంత సేపు? అనవసర నోటిఫికేషన్లు ఆగుతాయి; అయ్యాక రిపోర్ట్.", WUi.MUTED);
        int[] mins = {25, 45, 60, 90};
        String[] labels = new String[mins.length];
        View.OnClickListener[] taps = new View.OnClickListener[mins.length];
        for (int i = 0; i < mins.length; i++) {
            int m = mins[i];
            labels[i] = m + "";
            taps[i] = v -> { try { act(new JSONObject().put("what", "focus").put("min", m)); } catch (Exception ignored) {} showToast("🎯 " + m + " ని…"); };
        }
        row(labels, taps);
        button("⏹ ఫోకస్ ఆపు", 0xFF1F2937, v -> { try { act(new JSONObject().put("what", "focus_stop")); } catch (Exception ignored) {} });
        line("టైమర్ ఈ వాచ్‌లో నడుస్తుంది (⏱️ టైమర్ లో చూడండి).", WUi.FAINT);
    }

    private void sleep() {
        button("😴 నిద్ర ఎంత తక్కువైంది?", 0xFF0E3A4A, v -> { try { Talk.phoneDo(this, new JSONObject().put("what", "debt"), "😴 చూస్తున్నాను…"); } catch (Exception ignored) {} finish(); });
        line("కునుకు (వాచ్ నిశ్శబ్దంగా వైబ్రేషన్‌తో లేపుతుంది):", WUi.MUTED);
        int[] mins = {20, 45, 90};
        String[] labels = {"20 ని", "45 ని", "90 ని"};
        View.OnClickListener[] taps = new View.OnClickListener[3];
        for (int i = 0; i < 3; i++) {
            int m = mins[i];
            taps[i] = v -> { Timers.start(this, m * 60, "😴 కునుకు అయిపోయింది"); showToast("😴 " + m + " నిమిషాలు. పడుకోండి."); };
        }
        row(labels, taps);
    }

    // ---------------------------------------------------------------- the bezel scrolls

    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            scroll.scrollBy(0, Math.round(delta));
            rotary += Math.abs(delta);
            if (rotary > WUi.dp(this, 28)) { rotary = 0; scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }
}
