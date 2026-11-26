package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🌱 మొక్కలకి నీళ్ళు: his plant-watering board. ① the program onto the ESP32 over a USB OTG cable (MicroPython and
 * Jarvis's own board program, no laptop), ② the home Wi-Fi (he types the password on the board's own page), then the
 * soil, the bucket, 💧 water now (after his confirming tap), ⏹ stop, the board's settings, the sensors' calibration and
 * a daily time. Every order goes locked through {@link PlantLink}; the safety is on the board.
 */
public class PlantActivity extends Activity {
    private static final String ACTION_USB = "com.anil.jarvis.PLANT_USB";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private TextView boardLine, flashLine, wifiBox, setLine, calLine, schedLine, msg;
    private ProgressBar bar;
    private GridLayout tiles;
    private LinearLayout liveCard, wifiCard, setCard, calCard, schedCard;
    private boolean busy;
    private BroadcastReceiver usbRcv;
    private boolean pendingFresh;
    private JSONObject st = new JSONObject();

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setBackground(new Ui.Aurora());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(36));
        box.addView(Ui.text(this, "🌱 మొక్కలకి నీళ్ళు", 22, 0xFFFFFFFF));
        TextView sub = Ui.text(this, "ఇంట్లో మీ నీళ్ళ బోర్డ్: మట్టి తేమ, బకెట్, ఇప్పుడే పోయడం, రోజూ టైమ్‌కి పోయడం. భద్రత బోర్డ్ లోపలే: "
                + "ఒక్కసారికి 2 నిమిషాలు మించదు, బకెట్ ఖాళీ అయితే పంప్ ఆన్ అవ్వదు, రోజుకి హద్దు, Wi-Fi పోతే ఆగుతుంది.", 13.5f, Ui.MUTED);
        sub.setPadding(0, dp(4), 0, dp(8));
        box.addView(sub);
        msg = Ui.text(this, "", 15, Ui.GOLD);
        msg.setPadding(0, dp(4), 0, dp(4));
        box.addView(msg);

        // the board now
        liveCard = card(box, "బోర్డ్");
        boardLine = Ui.text(this, "", 14.5f, Ui.TEXT);
        liveCard.addView(boardLine);
        tiles = new GridLayout(this);
        tiles.setColumnCount(2);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(8);
        liveCard.addView(tiles, tl);
        LinearLayout row = row(liveCard);
        row.addView(button("🔄 చూడు", v -> refresh(true)));
        row.addView(button("💧 నీళ్ళు పోయి", v -> askWater()));
        row.addView(button("⏹ ఆపు", v -> stopNow()));

        // ① the program over USB
        LinearLayout fc = card(box, "① ప్రోగ్రామ్ ఎక్కించు (USB, మొదటిసారి)");
        fc.addView(Ui.text(this, "1) ESP32 ని నీళ్ళ బోర్డ్ నుంచి తీసి (పంప్ ఏమీ కలపకుండా), USB డేటా కేబుల్ + OTG అడాప్టర్‌తో ఫోన్‌కి పెట్టండి.\n"
                + "2) కింద బటన్ నొక్కండి. Android “USB పరికరం వాడనా?” అంటే సరే అనండి.\n"
                + "3) మొదటిసారి 1–2 నిమిషాలు పడుతుంది (MicroPython + మన ప్రోగ్రామ్). స్క్రీన్ ఆన్‌లోనే ఉంటుంది. తర్వాత ప్రోగ్రామ్ మార్పులకి కొన్ని సెకన్లే.", 13.5f, Ui.MUTED));
        LinearLayout frow = row(fc);
        frow.addView(button("🔌 ప్రోగ్రామ్ ఎక్కించు", v -> flashStart(false)));
        frow.addView(button("♻️ పూర్తిగా మళ్ళీ", v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("పూర్తిగా మళ్ళీ ఎక్కించనా?")
                .setMessage("MicroPython ని కూడా మళ్ళీ రాస్తాను (1–2 నిమిషాలు). బోర్డ్ సరిగా పనిచేయనప్పుడు మాత్రమే. ఇంటి Wi-Fi పేరు బోర్డ్‌లో ఉంటే అలాగే ఉంటుంది.")
                .setPositiveButton("అవును", (d, w) -> flashStart(true)).setNegativeButton("వద్దు", null).show()));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(10));
        bl.topMargin = dp(10);
        fc.addView(bar, bl);
        flashLine = Ui.text(this, "", 14, Ui.TEXT);
        flashLine.setPadding(0, dp(6), 0, 0);
        fc.addView(flashLine);

        // ② the home Wi-Fi
        wifiCard = card(box, "② ఇంటి Wi-Fi (మొదటిసారి)");
        wifiBox = Ui.mono(this, "", 16, 0xFFFFFFFF);
        wifiBox.setBackground(Ui.round(this, 0x22000000, Ui.alpha(Ui.CYAN, 0x66), 10));
        wifiBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        wifiBox.setTextIsSelectable(true);
        wifiCard.addView(wifiBox);
        TextView ws = Ui.text(this, "1) ESP32 ని నీళ్ళ బోర్డ్ మీద పెట్టి పవర్ ఇవ్వండి. నీలం లైట్ నెమ్మదిగా మెరుస్తుంది.\n"
                + "2) ఫోన్ Wi-Fi లో పై పేరు ఎంచుకుని, పై పాస్‌వర్డ్ పెట్టండి.\n"
                + "3) “Wi-Fi కి సైన్ ఇన్ చేయండి” అని వస్తుంది: నొక్కండి. (రాకపోతే మొబైల్ డేటా కాసేపు ఆపి, బ్రౌజర్‌లో 192.168.4.1 తెరవండి.)\n"
                + "4) ఆ పేజీలో మీ ఇంటి Wi-Fi ఎంచుకుని, దాని పాస్‌వర్డ్ మీరే టైప్ చేసి “సేవ్ చేయి”. అది బోర్డ్‌లోనే ఉంటుంది; Jarvis చూడదు.\n"
                + "5) ఫోన్‌ని మళ్ళీ ఇంటి Wi-Fi (లేదా డేటా) కి మార్చి, “🔗 బోర్డ్ కలిసిందా?” నొక్కండి. నీలం లైట్ ఆగకుండా వెలిగితే కలిసినట్టు.", 13.5f, Ui.MUTED);
        ws.setPadding(0, dp(8), 0, 0);
        wifiCard.addView(ws);
        LinearLayout wrow = row(wifiCard);
        wrow.addView(button("📶 Wi-Fi సెట్టింగ్స్", v -> { try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {} }));
        wrow.addView(button("🔗 బోర్డ్ కలిసిందా?", v -> refresh(true)));

        // the board's settings
        setCard = card(box, "⚙️ బోర్డ్ సెట్టింగ్స్");
        setLine = Ui.text(this, "", 14, Ui.TEXT);
        setCard.addView(setLine);
        LinearLayout srow = row(setCard);
        srow.addView(button("🪴 కుండీలు", v -> pickSet("n", "ఎన్ని మట్టి సెన్సార్లు కలిపారు?", new String[]{"1", "2", "3"}, new int[]{1, 2, 3})));
        srow.addView(button("⏱️ ఒక్కసారికి", v -> pickSet("s", "ఒక్కసారికి ఎన్ని సెకన్లు?", new String[]{"10 సె.", "20 సె.", "30 సె.", "45 సె.", "60 సె."}, new int[]{10, 20, 30, 45, 60})));
        LinearLayout srow2 = row(setCard);
        srow2.addView(button("🏜️ పొడి అంటే", v -> pickSet("dry", "మట్టి ఎంత % కంటే తక్కువ అయితే పొడి?", new String[]{"30%", "40%", "50%", "60%"}, new int[]{30, 40, 50, 60})));
        srow2.addView(button("📅 రోజుకి హద్దు", v -> pickSet("cap", "రోజుకి మొత్తం ఎంతసేపు పోయొచ్చు?", new String[]{"2 నిమిషాలు", "5 నిమిషాలు", "10 నిమిషాలు", "20 నిమిషాలు"}, new int[]{120, 300, 600, 1200})));
        LinearLayout srow3 = row(setCard);
        srow3.addView(button("🪣 బకెట్ సెన్సార్", v -> pickTank()));

        // calibration
        calCard = card(box, "📏 సెన్సార్ కొలత");
        calCard.addView(Ui.text(this, "సెన్సార్‌ని పొడిగా గాలిలో పట్టుకుని “పొడి” నొక్కండి. తర్వాత నీళ్ళ గ్లాసులో గీత వరకు మాత్రమే ముంచి “తడి” నొక్కండి "
                + "(పైన ఉన్న చిన్న భాగాలు తడవకూడదు). ఒక్కసారి చేస్తే చాలు.", 13.5f, Ui.MUTED));
        calLine = Ui.text(this, "", 14, Ui.TEXT);
        calLine.setPadding(0, dp(6), 0, 0);
        calCard.addView(calLine);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout cr = row(calCard);
            cr.setTag("cal" + i);
            cr.addView(button("కుండీ " + (i + 1) + ": పొడి", v -> cal(k, true)));
            cr.addView(button("తడి", v -> cal(k, false)));
        }

        // the daily time
        schedCard = card(box, "⏰ రోజూ పోయడం");
        schedLine = Ui.text(this, "", 14, Ui.TEXT);
        schedCard.addView(schedLine);
        schedCard.addView(Ui.text(this, "బోర్డ్ తనే టైమ్‌కి పోస్తుంది (ఫోన్ ఆఫ్‌లో ఉన్నా). తర్వాత ఫోన్‌కి నోటిఫికేషన్ వస్తుంది.", 13, Ui.MUTED));
        LinearLayout prow = row(schedCard);
        prow.addView(button("➕ టైమ్ పెట్టు", v -> addTime()));
        prow.addView(button("🗑️ టైమ్‌లు తీసేయి", v -> clearTimes()));

        LinearLayout fo = row(box);
        fo.addView(button("🗑️ ఈ బోర్డ్‌ని మర్చిపో", v -> forget()));

        sc.addView(box);
        setContentView(sc);
        show();
        Plant.onChange = () -> main.post(() -> { if (!isFinishing()) { st = Plant.last(this); show(); } });
        if (Plant.paired(this)) refresh(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Plant.onChange = null;
        if (usbRcv != null) try { unregisterReceiver(usbRcv); } catch (Exception ignored) {}
        work.shutdownNow();
    }

    // ================================================================ the screen

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.alpha(Ui.CYAN, 0x44), 14));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView t = Ui.text(this, title, 16.5f, 0xFFFFFFFF);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(10), 0, 0);
        parent.addView(r);
        return r;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView t = Ui.pill(this, s);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void tile(String label, String value, String note, int color) {
        TextView x = Ui.text(this, label + "\n" + value + (note.isEmpty() ? "" : "\n" + note), 15, 0xFFFFFFFF);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.round(this, Ui.alpha(color, 0x22), Ui.alpha(color, 0x77), 12));
        x.setPadding(dp(6), dp(10), dp(6), dp(10));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        tiles.addView(x, lp);
    }

    private void show() {
        boolean ready = Plant.paired(this);
        boolean keyMade = Plant.key(this) != null;
        if (st.length() == 0 && ready) st = Plant.last(this);
        wifiCard.setVisibility(keyMade ? View.VISIBLE : View.GONE);
        wifiBox.setText("బోర్డ్ Wi-Fi:  " + Plant.apName(this) + "\nపాస్‌వర్డ్:     " + Plant.apPassword(this));
        for (LinearLayout c : new LinearLayout[]{liveCard, setCard, calCard, schedCard}) c.setVisibility(ready ? View.VISIBLE : View.GONE);
        tiles.removeAllViews();
        if (!ready) return;
        long at = Plant.sp(this).getLong("last_at", 0);
        boardLine.setText(st.length() == 0 ? "బోర్డ్ ఇంకా మాట్లాడలేదు. ② ఇంటి Wi-Fi చేసి “🔗 బోర్డ్ కలిసిందా?” నొక్కండి."
                : "చివరి మాట: " + Plant.hhmm(at) + (st.optBoolean("pump") ? " · 💧 ఇప్పుడు పోస్తోంది" : ""));
        if (st.length() == 0) return;
        JSONArray m = st.optJSONArray("m");
        JSONObject set = st.optJSONObject("set");
        int dry = set == null ? 40 : set.optInt("dry", 40);
        if (m != null) for (int i = 0; i < m.length(); i++) {
            boolean none = m.isNull(i);
            int p = m.optInt(i);
            tile("🪴 కుండీ " + (i + 1), none ? "—" : p + "%", none ? "సెన్సార్ చదవలేదు" : p < dry ? "పొడి" : "తడి",
                    none ? Ui.MUTED : p < dry ? Ui.GOLD : Ui.OK);
        }
        String tk = st.optString("tank");
        tile("🪣 బకెట్", "ok".equals(tk) ? "నీళ్ళు ఉన్నాయి" : "empty".equals(tk) ? "ఖాళీ!" : "none".equals(tk) ? "సెన్సార్ లేదు" : "—", "",
                "empty".equals(tk) ? Ui.RED : Ui.CYAN);
        tile("⏱️ ఈరోజు", st.optInt("today") + " / " + st.optInt("cap") + " సె.", "", Ui.CYAN);
        tile("🕖 చివరిసారి", Plant.lastLine(st), "", Ui.CYAN);
        if (set != null) {
            setLine.setText("కుండీలు " + set.optInt("n") + " · ఒక్కసారికి " + set.optInt("s") + " సె. (గరిష్టం " + set.optInt("max") + ") · పొడి < " + set.optInt("dry")
                    + "% · రోజుకి " + set.optInt("cap") / 60 + " నిమి. · బకెట్ సెన్సార్: " + tankMode(set.optString("tank")));
            JSONArray cal = set.optJSONArray("cal");
            JSONArray mv = st.optJSONArray("mv");
            StringBuilder cl = new StringBuilder();
            for (int i = 0; i < set.optInt("n", 1); i++) {
                JSONArray c = cal == null ? null : cal.optJSONArray(i);
                cl.append("కుండీ ").append(i + 1).append(": ఇప్పుడు ").append(mv == null ? "—" : mv.optInt(i) + " mV")
                        .append(c == null ? "" : " · పొడి " + c.optInt(0) + " / తడి " + c.optInt(1)).append("\n");
            }
            calLine.setText(cl.toString().trim());
            for (int i = 0; i < 3; i++) {
                View r = calCard.findViewWithTag("cal" + i);
                if (r != null) r.setVisibility(i < set.optInt("n", 1) ? View.VISIBLE : View.GONE);
            }
            schedLine.setText(Plant.schedLine(set.optJSONArray("sched")));
        }
    }

    private static String tankMode(String t) {
        switch (t) {
            case "open": return "నీళ్ళు ఉంటే తెరుచుకుంటుంది";
            case "none": return "లేదు";
            default: return "నీళ్ళు ఉంటే మూసుకుంటుంది";
        }
    }

    private void say(String s) {
        msg.setText(s);
    }

    // ================================================================ the board's answers

    private interface Job { JSONObject run() throws Exception; }
    private interface Then { void with(JSONObject o); }

    /** One order on the worker, the answer on the screen. */
    private void order(String doing, Job job, Then then) {
        if (busy) { say("ఒక్క నిమిషం: ముందటి పని అవుతోంది…"); return; }
        busy = true;
        say(doing);
        work.execute(() -> {
            JSONObject o = null;
            String err = null;
            try { o = job.run(); } catch (Exception e) { err = "నెట్ సమస్య: " + e.getMessage(); }
            final JSONObject res = o;
            final String er = err;
            main.post(() -> {
                busy = false;
                if (isFinishing()) return;
                if (er != null) { say(er); return; }
                if (res == null) { say(Plant.NO_ANSWER); return; }
                st = res;
                show();
                then.with(res);
            });
        });
    }

    private void refresh(boolean loud) {
        if (!Plant.paired(this)) {
            say(Plant.key(this) == null ? "ముందు ① ప్రోగ్రామ్ ఎక్కించండి." : "ముందు ① ప్రోగ్రామ్ ఎక్కించడం పూర్తి చేయండి.");
            return;
        }
        order("🔄 బోర్డ్‌ని అడుగుతున్నాను… (20 సెకన్ల వరకు)", () -> Plant.status(this), o -> say(loud ? "✅ బోర్డ్ కలిసింది. " + Plant.tankLine(o) + "." : ""));
    }

    private void askWater() {
        JSONObject set = st.optJSONObject("set");
        int usual = set == null ? 20 : set.optInt("s", 20);
        int mx = set == null ? 120 : set.optInt("max", 120);
        int[] opts = {usual, 10, 30, 60};
        String[] names = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            opts[i] = Math.min(opts[i], mx);
            names[i] = opts[i] + " సెకన్లు" + (i == 0 ? " (మామూలు)" : "");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("💧 ఇప్పుడు ఎంతసేపు పోయనా?")
                .setItems(names, (d, w) -> {
                    final int secs = opts[w];
                    order("💧 బోర్డ్‌కి చెబుతున్నాను…", () -> Plant.water(this, secs), o -> {
                        if ("start".equals(o.optString("st"))) say("💧 " + o.optInt("s") + " సెకన్లు పోస్తోంది… అయ్యాక చెబుతాను."
                                + (o.optBoolean("capped") ? " (ఈరోజు హద్దు వరకే)" : ""));
                        else say("పోయలేదు: " + Plant.why(o.optString("why"), o) + ".");
                    });
                }).setNegativeButton("వద్దు", null).show();
    }

    private void stopNow() {
        order("⏹ ఆపమంటున్నాను…", () -> Plant.stop(this), o -> say(o.optBoolean("stopped") ? "⏹ పంప్ ఆపేశాను." : "పంప్ ఇప్పుడు ఆన్‌లో లేదు."));
    }

    private void pickSet(String key, String title, String[] names, int[] vals) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
                .setItems(names, (d, w) -> sendSet(key, vals[w])).setNegativeButton("వద్దు", null).show();
    }

    private void sendSet(String key, Object value) {
        order("⚙️ బోర్డ్‌లో మారుస్తున్నాను…", () -> Plant.settings(this, new JSONObject().put(key, value)), o -> say("✅ బోర్డ్‌లో మార్చాను."));
    }

    private void pickTank() {
        String[] names = {"నీళ్ళు ఉన్నప్పుడు స్విచ్ మూసుకుంటుంది (మామూలు)", "నీళ్ళు ఉన్నప్పుడు స్విచ్ తెరుచుకుంటుంది (ఫ్లోట్ తిరగేసి పెట్టారు)", "బకెట్ సెన్సార్ లేదు"};
        String[] vals = {"closed", "open", "none"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🪣 బకెట్ సెన్సార్ (float switch)")
                .setItems(names, (d, w) -> {
                    if (w == 2) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("సెన్సార్ లేకుండానా?")
                                .setMessage("బకెట్ ఖాళీ అయినా బోర్డ్‌కి తెలియదు: పంప్ గాలిలో తిరిగి పాడవ్వొచ్చు. రోజుకి హద్దు మాత్రమే కాపాడుతుంది. సరేనా?")
                                .setPositiveButton("సరే", (d2, w2) -> sendSet("tank", "none")).setNegativeButton("వద్దు", null).show();
                    } else {
                        sendSet("tank", vals[w]);
                    }
                }).setNegativeButton("వద్దు", null).show();
    }

    private void cal(int sensor, boolean dry) {
        order("📏 సెన్సార్ చదువుతున్నాను…", () -> Plant.calibrate(this, sensor, dry), o -> {
            if (o.optBoolean("ok")) say("✅ కుండీ " + (sensor + 1) + " " + (dry ? "పొడి" : "తడి") + " కొలత: " + o.optInt("mv_now") + " mV.");
            else if ("close".equals(o.optString("why"))) say("పొడి, తడి కొలతలు చాలా దగ్గరగా ఉన్నాయి (" + o.optInt("mv_now") + " mV). సెన్సార్ సరిగా పొడిగా / తడిగా ఉందా చూసి మళ్ళీ.");
            else if ("sensor".equals(o.optString("why"))) say("ఆ సెన్సార్ నుంచి రీడింగ్ రాలేదు: దాని 3 వైర్లు (GND, VCC, AOUT) సరిగా పెట్టారా చూడండి.");
            else say("కొలత కాలేదు.");
        });
    }

    private JSONArray times() {
        JSONObject set = st.optJSONObject("set");
        JSONArray a = set == null ? null : set.optJSONArray("sched");
        return a == null ? new JSONArray() : a;
    }

    private void addTime() {
        new TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert, (tp, h, mi) -> {
            String[] names = {"10 సెకన్లు", "20 సెకన్లు", "30 సెకన్లు", "60 సెకన్లు"};
            int[] secs = {10, 20, 30, 60};
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(String.format(Locale.ROOT, "%d:%02d కి ఎంతసేపు?", h, mi))
                    .setItems(names, (d, w) -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("మట్టి పొడిగా ఉంటేనే పోయనా?")
                            .setPositiveButton("అవును, పొడిగా ఉంటేనే", (d2, w2) -> saveTime(h, mi, secs[w], true))
                            .setNegativeButton("ఎప్పుడూ పోయి", (d2, w2) -> saveTime(h, mi, secs[w], false)).show())
                    .show();
        }, 7, 0, false).show();
    }

    private void saveTime(int h, int mi, int secs, boolean dryOnly) {
        try {
            JSONArray old = times(), a = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject e = old.optJSONObject(i);
                if (e != null && !(e.optInt("h") == h && e.optInt("m") == mi)) a.put(e);
            }
            if (a.length() >= 4) { say("రోజుకి 4 టైమ్‌లు మాత్రమే. ముందు ఒకటి తీసేయండి."); return; }
            a.put(new JSONObject().put("h", h).put("m", mi).put("s", secs).put("dry", dryOnly).put("on", true));
            sendSet("sched", a);
        } catch (Exception e) {
            say("టైమ్ పెట్టలేకపోయాను.");
        }
    }

    private void clearTimes() {
        if (times().length() == 0) { say("రోజూ టైమ్‌లు ఏవీ లేవు."); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("రోజూ పోయడం ఆపనా?")
                .setMessage(Plant.schedLine(times()))
                .setPositiveButton("ఆపు", (d, w) -> sendSet("sched", new JSONArray())).setNegativeButton("వద్దు", null).show();
    }

    private void forget() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("ఈ బోర్డ్‌ని మర్చిపోనా?")
                .setMessage("ఈ ఫోన్ నుంచి బోర్డ్‌కి మాట వెళ్ళదు (బోర్డ్ బటన్, దాని రోజూ టైమ్, భద్రత అలాగే పనిచేస్తాయి). మళ్ళీ కావాలంటే ① ప్రోగ్రామ్ USB తో మళ్ళీ ఎక్కించాలి.")
                .setPositiveButton("మర్చిపో", (d, w) -> { Plant.forget(this); st = new JSONObject(); show(); say("మర్చిపోయాను."); })
                .setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ ① USB

    private void flashStart(boolean fresh) {
        if (busy) { say("ఒక్క నిమిషం: ముందటి పని అవుతోంది…"); return; }
        UsbManager um = getSystemService(UsbManager.class);
        List<UsbSerialDriver> ds = um == null ? null : UsbSerialProber.getDefaultProber().findAllDrivers(um);
        if (ds == null || ds.isEmpty()) {
            say("USB లో బోర్డ్ కనిపించలేదు. OTG అడాప్టర్ + డేటా కేబుల్ గట్టిగా పెట్టారా చూడండి (కొన్ని కేబుళ్ళు చార్జింగ్‌కి మాత్రమే).");
            return;
        }
        UsbSerialDriver d = ds.get(0);
        if (!um.hasPermission(d.getDevice())) {
            pendingFresh = fresh;
            if (usbRcv == null) {
                usbRcv = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) flashStart(pendingFresh);
                        else say("USB కి అనుమతి ఇవ్వలేదు. మళ్ళీ నొక్కి “సరే” అనండి.");
                    }
                };
                IntentFilter f = new IntentFilter(ACTION_USB);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbRcv, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(usbRcv, f);
            }
            Intent i = new Intent(ACTION_USB).setPackage(getPackageName());
            um.requestPermission(d.getDevice(), PendingIntent.getBroadcast(this, 0, i, Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            say("Android అనుమతి అడుగుతోంది: “సరే” అనండి.");
            return;
        }
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        say("");
        flashLine.setText("మొదలుపెడుతున్నాను…");
        work.execute(() -> {
            String result = null, problem = null;
            UsbLine ln = null;
            try {
                UsbDeviceConnection conn = um.openDevice(d.getDevice());
                if (conn == null) throw new EspFlash.Failed("USB తెరవలేకపోయాను: కేబుల్ తీసి మళ్ళీ పెట్టి ప్రయత్నించండి.", "openDevice");
                UsbSerialPort port = d.getPorts().get(0);
                port.open(conn);
                port.setParameters(EspFlash.ROM_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                ln = new UsbLine(port);
                EspFlash f = new EspFlash(ln, (te, pct) -> main.post(() -> { flashLine.setText(te); bar.setProgress(pct); }));
                result = f.run(Plant.firmware(this), Plant.MP_VER, Plant.files(this), fresh);
                Plant.uploaded(this);
            } catch (EspFlash.Failed x) {
                problem = x.te;
            } catch (Exception x) {
                problem = "ఆగిపోయింది: " + x.getMessage() + ". కేబుల్ గట్టిగా పెట్టి మళ్ళీ ప్రయత్నించండి.";
            } finally {
                if (ln != null) ln.close();
            }
            final String ok = result, bad = problem;
            main.post(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing()) return;
                if (bad != null) {
                    bar.setVisibility(View.GONE);
                    flashLine.setText("❌ " + bad);
                    return;
                }
                bar.setProgress(100);
                flashLine.setText("flashed".equals(ok) ? "✅ MicroPython, మన ప్రోగ్రామ్ రెండూ ఎక్కాయి. USB తీసేసి ② ఇంటి Wi-Fi చేయండి."
                        : "✅ ప్రోగ్రామ్ మార్చాను. USB తీసేసి బోర్డ్‌ని మళ్ళీ పెట్టండి.");
                show();
            });
        });
    }
}
