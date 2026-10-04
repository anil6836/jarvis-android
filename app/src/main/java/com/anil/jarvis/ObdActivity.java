package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🔌 OBD scanner: Jarvis reads the car's computer through a small ELM327 Bluetooth adapter: live numbers (RPM, speed,
 * engine temperature, load, throttle, battery) and the error codes, explained in Telugu (what, how serious, can he
 * drive, likely causes, rough cost). Codes are cleared only when he confirms (clearing does not fix the fault).
 */
public class ObdActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Obd obd = new Obd();
    private Prefs p;
    private TextView status, answer;
    private final TextView[] tiles = new TextView[6];
    private boolean live;
    private static final String[] NAMES = {"⚙️ RPM", "🚗 వేగం", "🌡️ ఇంజిన్ వేడి", "🏋️ లోడ్", "🦶 థ్రాటిల్", "🔋 బ్యాటరీ"};

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView sc = new ScrollView(this);
        sc.setBackground(new Ui.Aurora());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(20), dp(18), dp(30));
        TextView t = Ui.text(this, "🔌 OBD స్కానర్ (కార్)", 22, 0xFFFFFFFF);
        box.addView(t);
        box.addView(Ui.text(this, "1) స్టీరింగ్ కింద ఉన్న OBD పోర్ట్‌కి ELM327 Bluetooth అడాప్టర్ పెట్టండి.\n2) కీ ON చేయండి (ఇంజిన్ స్టార్ట్ చేసినా సరే).\n"
                + "3) ఫోన్ Bluetooth సెట్టింగ్స్‌లో దాన్ని ఒక్కసారి జత చేయండి (PIN సాధారణంగా 1234 లేదా 0000).\n4) కింద 'కనెక్ట్' నొక్కండి.", 14, Ui.MUTED));
        status = Ui.text(this, "కనెక్ట్ కాలేదు", 15, Ui.GOLD);
        status.setPadding(0, dp(12), 0, dp(8));
        box.addView(status);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("🔗 కనెక్ట్", v -> pick()));
        row.addView(button("⚙️ Bluetooth", v -> { try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) {} }));
        box.addView(row);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(2);
        for (int i = 0; i < tiles.length; i++) {
            TextView x = Ui.text(this, NAMES[i] + "\n—", 16, 0xFFFFFFFF);
            x.setGravity(Gravity.CENTER);
            x.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.alpha(Ui.CYAN, 0x55), 14));
            x.setPadding(dp(8), dp(12), dp(8), dp(12));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(i / 2, 1f), GridLayout.spec(i % 2, 1f));
            lp.width = 0;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            g.addView(x, lp);
            tiles[i] = x;
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(12);
        box.addView(g, glp);
        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(10), 0, 0);
        row2.addView(button("⚠️ ఎర్రర్ కోడ్‌లు చదువు", v -> readCodes()));
        row2.addView(button("🧹 క్లియర్", v -> confirmClear()));
        box.addView(row2);
        answer = Ui.text(this, "", 15.5f, 0xFFFFFFFF);
        answer.setPadding(0, dp(14), 0, 0);
        answer.setLineSpacing(0, 1.2f);
        box.addView(answer);
        sc.addView(box);
        setContentView(sc);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 81);
    }

    private TextView button(String s, android.view.View.OnClickListener l) {
        TextView t = Ui.pill(this, s);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        live = false;
        work.execute(obd::close);
        work.shutdown();
        Announcer.stop();
    }

    private void pick() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 81);
            return;
        }
        List<String[]> l = Obd.paired(this);
        if (l.isEmpty()) { status.setText("జత చేసిన Bluetooth పరికరాలు లేవు. ముందు ⚙️ Bluetooth లో అడాప్టర్ జత చేయండి."); return; }
        String[] names = new String[l.size()];
        for (int i = 0; i < names.length; i++) names[i] = l.get(i)[0];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ఏ అడాప్టర్?").setItems(names, (d, w) -> connect(l.get(w)[1], l.get(w)[0])).show();
    }

    private void connect(String addr, String name) {
        status.setText("🔗 " + name + " కి కనెక్ట్ అవుతున్నాను… (కొన్ని సెకన్లు)");
        work.execute(() -> {
            String err = null;
            try { obd.connect(this, addr); } catch (Exception e) { err = "కనెక్ట్ కాలేదు: అడాప్టర్ పెట్టారా, కీ ON ఉందా, Bluetooth ఆన్ ఉందా చూడండి."; obd.close(); }
            final String er = err;
            main.post(() -> {
                if (isFinishing()) return;
                if (er != null) { status.setText(er); return; }
                status.setText("✅ " + name + " కనెక్ట్ అయింది");
                live = true;
                tick();
            });
        });
    }

    private void tick() {
        if (!live || isFinishing()) return;
        work.execute(() -> {
            String[] v = null;
            try { if (obd.connected()) v = obd.live(); } catch (Exception ignored) {}
            final String[] vals = v;
            main.post(() -> {
                if (vals == null) { if (live) { live = false; status.setText("కనెక్షన్ పోయింది. మళ్ళీ 🔗 కనెక్ట్ నొక్కండి."); } return; }
                for (int i = 0; i < tiles.length; i++) tiles[i].setText(NAMES[i] + "\n" + (vals[i].isEmpty() ? "—" : vals[i]));
                main.postDelayed(this::tick, 1500);
            });
        });
    }

    private void readCodes() {
        if (!obd.connected()) { status.setText("ముందు 🔗 కనెక్ట్ చేయండి."); return; }
        answer.setText("⚠️ ఎర్రర్ కోడ్‌లు చదువుతున్నాను…");
        work.execute(() -> {
            String text;
            List<String> codes = null;
            int[] mil = null;
            try {
                mil = obd.mil();
                codes = obd.codes();
            } catch (Exception ignored) {}
            if (codes == null) text = "కోడ్‌లు చదవలేకపోయాను.";
            else if (codes.isEmpty()) text = "✅ ఎర్రర్ కోడ్‌లు ఏవీ లేవు" + (mil != null && mil[0] == 1 ? " (కానీ చెక్-ఇంజిన్ లైట్ ఆన్ అని కారు చెబుతోంది: మెకానిక్‌కి చూపించండి)." : ".");
            else {
                text = "కోడ్‌లు: " + android.text.TextUtils.join(", ", codes) + (mil != null && mil[0] == 1 ? "\nచెక్-ఇంజిన్ లైట్: ఆన్" : "");
                if (p.hasBrain()) {
                    try {
                        String r = Brain.oneShot(p, "You are Jarvis, " + p.name() + "'s assistant and a car mechanic friend. Explain these OBD-II error codes in simple Telugu "
                                        + "(Telugu script), plain text for reading aloud: for each code what it means, how serious it is (can he keep driving or must he stop), "
                                        + "the likely causes from most to least likely, what to check first, and the rough repair cost in India. Use web search for exact meanings. "
                                        + "Never say a brake, steering or overheating problem is safe to drive. No markdown.",
                                "Codes: " + codes + (mil != null ? "; check-engine light " + (mil[0] == 1 ? "ON" : "OFF") : ""), null, p.webSearch(), 2500);
                        text = text + "\n\n" + ScanBrain.clean(r);
                    } catch (Exception e) {
                        text = text + "\n\n(వివరణ కోసం AI జవాబు రాలేదు.)";
                    }
                }
                try {
                    ScanStore.save(this, new JSONObject().put("title", "🔌 OBD: " + android.text.TextUtils.join(", ", codes)).put("kind", "obd").put("mode", "vehicle")
                            .put("say", text), null, null);
                } catch (Exception ignored) {}
            }
            final String t = text;
            main.post(() -> {
                if (isFinishing()) return;
                answer.setText(t);
                Announcer.say(this, t);
            });
        });
    }

    private void confirmClear() {
        if (!obd.connected()) { status.setText("ముందు 🔗 కనెక్ట్ చేయండి."); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("కోడ్‌లు క్లియర్ చేయనా?")
                .setMessage("ఇది చెక్-ఇంజిన్ లైట్‌ని, కోడ్‌లని తీసేస్తుంది, కానీ సమస్యని సరిచేయదు. మెకానిక్ చూసే ముందు క్లియర్ చేస్తే వాళ్ళకి సమాచారం పోతుంది. ఇంజిన్ ఆపి, కీ ON లో ఉండాలి.")
                .setPositiveButton("క్లియర్ చేయి", (d, w) -> work.execute(() -> {
                    boolean ok = false;
                    try { ok = obd.clear(); } catch (Exception ignored) {}
                    final boolean done = ok;
                    main.post(() -> answer.setText(done ? "🧹 కోడ్‌లు క్లియర్ అయ్యాయి. సమస్య మళ్ళీ వస్తే లైట్ మళ్ళీ వెలుగుతుంది." : "క్లియర్ కాలేదు (ఇంజిన్ ఆపి, కీ ON లో ప్రయత్నించండి)."));
                }))
                .setNegativeButton("వద్దు", null).show();
    }
}
