package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 💻 ల్యాప్‌టాప్ జత: this phone ↔ Jarvis PC on his laptop. The laptop shows a QR (Jarvis PC → ⚙️ → 📱 ఫోన్ జత → QR
 * తయారుచేయి); this screen reads it with the phone's own QR reader (never an AI; screenshots blocked while the camera is on),
 * says hello and waits for "జత అయింది". Then: "is the laptop on?" and unpairing. Phone only; ₹0, no AI.
 */
public class PcLinkActivity extends Activity {
    static final String EXTRA_CHECK = "check", EXTRA_JUST_SCANNED = "scanned";
    private static final int REQ_CAM = 83;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(), light = Executors.newSingleThreadExecutor();
    private TextView msg, state, scanBtn;
    private LinearLayout pairedRow, retryRow, camBox;
    private CamView cam;
    private boolean scanning, busy, decoding;

    private int dp(float v) { return Ui.dp(this, v); }

    static void open(Context c, boolean check) {
        c.startActivity(new Intent(c, PcLinkActivity.class).putExtra(EXTRA_CHECK, check)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setBackground(new Ui.Aurora());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(36));
        box.addView(Ui.text(this, "💻 ల్యాప్‌టాప్ జత", 22, 0xFFFFFFFF));
        TextView sub = Ui.text(this, "మీ లాప్‌టాప్‌లోని Jarvis PC తో ఈ ఫోన్‌ని QR తో కలపండి. మెసేజ్‌లు మన తాళంతో వెళ్తాయి: ntfy.sh కి తాళం వేసిన అక్షరాలే కనిపిస్తాయి. "
                + "తాళం ఈ ఫోన్‌లో మాత్రమే (బ్యాకప్‌లోకి వెళ్లదు, AI కి వెళ్లదు). ₹0, AI లేదు.", 13.5f, Ui.MUTED);
        sub.setPadding(0, dp(4), 0, dp(8));
        box.addView(sub);
        msg = Ui.text(this, "", 15, Ui.GOLD);
        msg.setPadding(0, dp(4), 0, dp(4));
        box.addView(msg);

        // the pairing now
        LinearLayout st = card(box, "స్థితి");
        state = Ui.text(this, "", 15, Ui.TEXT);
        st.addView(state);
        pairedRow = row(st);
        pairedRow.addView(button("🟢 ఆన్‌లో ఉందా?", v -> ping()));
        pairedRow.addView(button("🔗 జత తీసేయి", v -> askUnpair()));
        retryRow = row(st);
        retryRow.addView(button("🔁 మళ్లీ ప్రయత్నించు", v -> hello()));
        retryRow.addView(button("🗑️ ఈ QR వద్దు", v -> { Laptop.forget(this); say(""); refresh(); }));

        // the QR
        LinearLayout qc = card(box, "📷 QR తో జత");
        qc.addView(Ui.text(this, "1) లాప్‌టాప్‌లో Jarvis PC తెరిచి ⚙️ Settings → 📱 ఫోన్ జత → \"QR తయారుచేయి\" నొక్కండి.\n"
                + "2) కింద \"📷 QR స్కాన్ చేయి\" నొక్కి, ఫోన్ కెమెరాని లాప్‌టాప్ స్క్రీన్ మీద QR కి చూపించండి.\n"
                + "3) ఫోన్‌లో, లాప్‌టాప్‌లో రెండింట్లో \"జత అయింది\" వస్తుంది.\n"
                + "⚠️ ఆ QR ఒక తాళం: దాని ఫోటో తీయకండి, ఎవరికీ పంపకండి.", 13.5f, Ui.MUTED));
        camBox = new LinearLayout(this);
        camBox.setOrientation(LinearLayout.VERTICAL);
        camBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(10);
        qc.addView(camBox, cl);
        LinearLayout qrow = row(qc);
        scanBtn = button("📷 QR స్కాన్ చేయి", v -> { if (scanning) stopScan(); else startScan(); });
        qrow.addView(scanBtn);

        // what it does
        LinearLayout wc = card(box, "ఇది ఏం చేస్తుంది");
        wc.addView(Ui.text(this, "✅ ఇప్పుడు: జత చేయడం, \"ల్యాప్‌టాప్ ఆన్‌లో ఉందా?\" (మాటతో కూడా), జత తీసేయడం.\n"
                + "🔜 త్వరలో (దశ 2): ఫోన్ / వాచ్ నుంచి లాప్‌టాప్ పనులు (లాక్ చేయి, యాప్ తెరువు…), మిమ్మల్ని అడిగాకే.\n"
                + "🔜 త్వరలో (దశ 3): నోట్స్ / జ్ఞాపకాలు రెండింట్లో ఒకేలా.", 13.5f, Ui.MUTED));

        sc.addView(box);
        setContentView(sc);
        refresh();
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra(EXTRA_JUST_SCANNED, false)) { i.removeExtra(EXTRA_JUST_SCANNED); hello(); return; }
        if (i.getBooleanExtra(EXTRA_CHECK, false)) { i.removeExtra(EXTRA_CHECK); if (Laptop.paired(this)) { ping(); return; } }
        if (Laptop.hasKey(this)) work.execute(() -> { Laptop.check(this); main.post(this::refresh); }); // (a bye from the laptop meanwhile)
    }

    @Override protected void onPause() {
        super.onPause();
        stopScan();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (cam != null) cam.release();
        work.shutdownNow();
        light.shutdownNow();
    }

    private void refresh() {
        boolean key = Laptop.hasKey(this), ok = Laptop.paired(this);
        if (ok) {
            StringBuilder s = new StringBuilder("✅ జత అయింది: ").append(Laptop.name(this));
            if (!Laptop.pcApp(this).isEmpty()) s.append("\nJarvis PC ").append(Laptop.pcApp(this));
            if (Laptop.pairedAt(this) > 0) s.append("\nజత చేసింది: ").append(when(Laptop.pairedAt(this)));
            if (Laptop.lastSeen(this) > 0) s.append("\nచివరిసారి జవాబు: ").append(when(Laptop.lastSeen(this)));
            state.setText(s);
            state.setTextColor(Ui.OK);
        } else if (key) {
            state.setText("⏳ QR చదివాను (" + Laptop.name(this) + "). లాప్‌టాప్ జవాబు కోసం…");
            state.setTextColor(Ui.GOLD);
        } else {
            state.setText("ఇంకా ఏ లాప్‌టాప్‌తోనూ జత కాలేదు. కింద QR స్కాన్ చేయండి.");
            state.setTextColor(Ui.TEXT);
        }
        pairedRow.setVisibility(ok ? View.VISIBLE : View.GONE);
        retryRow.setVisibility(key && !ok && !busy ? View.VISIBLE : View.GONE);
        scanBtn.setText(scanning ? "✖ స్కాన్ ఆపు" : ok ? "📷 కొత్త QR తో మళ్లీ జత చేయి" : "📷 QR స్కాన్ చేయి");
    }

    private void say(String s) { msg.setText(s); }

    // ================================================================ the QR, read on this phone

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        if (cam == null) {
            cam = new CamView(this);
            cam.listener = (open, problem) -> { if (problem != null) say("📷 " + problem); };
            FrameLayout f = new FrameLayout(this);
            f.addView(cam, new FrameLayout.LayoutParams(-1, -1));
            f.addView(new Frame(this), new FrameLayout.LayoutParams(-1, -1));
            camBox.addView(f, new LinearLayout.LayoutParams(-1, dp(300)));
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // (the QR is a key: no screenshots of it)
        camBox.setVisibility(View.VISIBLE);
        scanning = true;
        cam.open();
        say("లాప్‌టాప్ స్క్రీన్ మీద QR ని ఈ చతురస్రంలోకి తీసుకురండి…");
        main.postDelayed(tick, 900);
        refresh();
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        main.removeCallbacks(tick);
        if (cam != null) cam.close();
        camBox.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        refresh();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!scanning) return;
            main.postDelayed(this, 700);
            if (decoding || cam == null) return;
            final Bitmap b = cam.frame(1000);
            if (b == null) return;
            decoding = true;
            light.execute(() -> {
                String code = QrReader.read(b);
                b.recycle();
                main.post(() -> { decoding = false; if (scanning && code != null) seen(code); });
            });
        }
    };

    private String lastWrong;

    private void seen(String code) {
        if (!PcLink.isQr(code)) { // (some other QR: not shown, it may be anything)
            if (!code.equals(lastWrong)) { lastWrong = code; say("ఇది Jarvis PC QR కాదు. లాప్‌టాప్‌లో \"QR తయారుచేయి\" నొక్కి వచ్చిన QR చూపించండి."); }
            return;
        }
        PcLink.Qr q = PcLink.parseQr(code);
        if (q == null) { say("ఈ QR ఈ Jarvis వెర్షన్‌కి సరిపోలేదు. ఫోన్ Jarvis అప్‌డేట్ చేసి మళ్లీ ప్రయత్నించండి."); return; }
        stopScan();
        Laptop.adopt(this, q); // (a new QR replaces the old laptop, which gets a quiet bye)
        hello();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScan();
            else say("QR చదవడానికి కెమెరా అనుమతి కావాలి.");
        }
    }

    // ================================================================ hello / ping / bye

    private void hello() {
        if (busy) return;
        busy = true;
        say("⏳ లాప్‌టాప్‌కి hello పంపాను. జవాబు కోసం చూస్తున్నాను (" + Laptop.HELLO_WAIT_S + " సెకన్ల వరకు)…");
        refresh();
        final Context app = getApplicationContext();
        work.execute(() -> {
            Laptop.Result r = Laptop.hello(app);
            main.post(() -> { busy = false; say(r.line); refresh(); });
        });
    }

    private void ping() {
        if (busy) return;
        busy = true;
        say("⏳ " + Laptop.label(this) + " ని అడుగుతున్నాను (" + Laptop.PING_WAIT_S + " సెకన్ల వరకు)…");
        refresh();
        final Context app = getApplicationContext();
        work.execute(() -> {
            Laptop.Result r = Laptop.ping(app);
            main.post(() -> { busy = false; say(r.line); refresh(); });
        });
    }

    private void askUnpair() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("జత తీసేయనా?")
                .setMessage(Laptop.label(this) + " తో జత తీసేస్తాను. మళ్లీ కావాలంటే లాప్‌టాప్‌లో కొత్త QR తో జత చేయాలి.")
                .setPositiveButton("తీసేయి", (d, w) -> {
                    busy = true;
                    final Context app = getApplicationContext();
                    work.execute(() -> {
                        Laptop.Result r = Laptop.unpair(app);
                        main.post(() -> { busy = false; say(r.line); refresh(); });
                    });
                })
                .setNegativeButton("వద్దు", null).show();
    }

    private static String when(long unix) {
        return new SimpleDateFormat("d MMM, h:mm a", new Locale("te", "IN")).format(new Date(unix * 1000));
    }

    // ================================================================ looks

    /** The square the QR should sit in. */
    private static final class Frame extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Frame(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 3));
            p.setColor(Ui.CYAN);
        }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) * 0.72f, x = (getWidth() - s) / 2f, y = (getHeight() - s) / 2f, k = s * 0.18f;
            float[][] corners = {{x, y, 1, 1}, {x + s, y, -1, 1}, {x, y + s, 1, -1}, {x + s, y + s, -1, -1}};
            for (float[] q : corners) {
                c.drawLine(q[0], q[1], q[0] + k * q[2], q[1], p);
                c.drawLine(q[0], q[1], q[0], q[1] + k * q[3], p);
            }
        }
    }

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
}
