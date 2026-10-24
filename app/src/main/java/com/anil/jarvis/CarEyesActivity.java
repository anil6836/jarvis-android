package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 👁️ The car eye check's screen. Opened by the car alert itself ("auto": starts the check, then gets out of the way so
 * Maps is back on top after a few seconds) or from Settings as a test (the camera's grey picture and the eyes' state
 * live; closed eyes beep softly instead of the alarm). Asks his OK once before the camera is ever used for this, and the
 * camera permission if it isn't there. Android lets a camera service start only from a screen he can see — hence this.
 */
public class CarEyesActivity extends Activity {
    static final String EXTRA_AUTO = "auto";
    /** Started by his own word or tap (not by the car alert alone): Android may ask him about the camera's Wi-Fi now. */
    static final String EXTRA_ASKED = "asked";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean auto, started;
    private TextView statusT, eventT, tip;
    private boolean realShown;
    private ImageView pic;
    private Bitmap shown;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int p = Ui.dp(this, 20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p * 2, p, p);
        col.setBackgroundColor(0xFF02070D);
        col.addView(Ui.text(this, "👁️ కారు కళ్ళ చెక్", 24, Ui.TEXT));
        TextView info = Ui.text(this, "కెమెరా మీ కళ్ళు చూస్తుంది: 2 సెకన్లకు మించి కళ్ళు మూసుకున్నా, తల వాలిపోయినా వెంటనే అలారం. "
                + "ఫోటోలు సేవ్ చేయదు, ఎక్కడికీ పంపదు; అంతా " + CarEyes.here(this) + ".", 14, Ui.MUTED);
        info.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        col.addView(info);
        statusT = Ui.text(this, "…", 22, Ui.CYAN);
        col.addView(statusT);
        pic = new ImageView(this);
        pic.setAdjustViewBounds(true);
        pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 260));
        lp.topMargin = Ui.dp(this, 12);
        col.addView(pic, lp);
        eventT = Ui.text(this, "", 16, Ui.GOLD);
        eventT.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(eventT);
        tip = Ui.text(this, auto ? "కొన్ని సెకన్లలో ఈ తెర తనంతట తానే వెళ్ళిపోతుంది; చెక్ వెనక నడుస్తుంది (నోటిఫికేషన్‌లో ⏹ ఆపు)."
                : "టెస్ట్: కెమెరా ముందు 2–3 సెకన్లు కళ్ళు మూసుకోండి. ఇక్కడ మెల్లగా బీప్ వస్తుంది; కారులో అయితే అలారం మోగుతుంది.", 14, Ui.MUTED);
        tip.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        col.addView(tip);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView ok = button(auto ? "✓ సరే" : "✓ మూసేయి");
        ok.setOnClickListener(v -> finish());
        TextView stop = button("⏹ కళ్ళ చెక్ ఆపు");
        stop.setOnClickListener(v -> { CarEyes.stop(this); finish(); });
        row.addView(ok);
        row.addView(stop);
        col.addView(row);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF02070D);
        sv.addView(col);
        setContentView(sv);
        consent();
    }

    private TextView button(String s) {
        TextView t = Ui.text(this, s, 16, Ui.TEXT);
        int p = Ui.dp(this, 14);
        t.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        t.setBackground(Ui.round(this, Ui.PANEL2, Ui.LINE2, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** His OK once, before the camera is ever used for this. */
    private void consent() {
        if (CarCare.eyesAgreed(this)) { permission(); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("👁️ కళ్ళ చెక్ పెట్టాలా?")
                .setMessage("కారు నడుపుతున్నప్పుడు కెమెరా మీ కళ్ళు చూస్తుంది. 2 సెకన్లకు మించి కళ్ళు మూసుకుపోతే, లేదా తల కిందకి వాలిపోతే వెంటనే గట్టిగా అలారం మోగుతుంది.\n\n"
                        + "ఫోటోలు, వీడియోలు సేవ్ చేయదు. ఎక్కడికీ (ఏ AI కీ కూడా) పంపదు. అంతా " + CarEyes.here(this) + ". ఎప్పుడైనా సెట్టింగ్స్‌లో ఆపొచ్చు.")
                .setPositiveButton("సరే, పెట్టు", (d, w) -> { CarCare.agreeEyes(this); permission(); })
                .setNegativeButton("వద్దు", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void permission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { begin(); return; }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 71);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 71) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) begin();
        else { Toast.makeText(this, "కెమెరా అనుమతి లేకుండా కళ్ళ చెక్ కుదరదు", Toast.LENGTH_LONG).show(); finish(); }
    }

    private void begin() {
        started = true;
        CarEyes.watching = true;
        try {
            // (from Settings: a test, soft beeps only; a check already on is only watched)
            if (!CarEyes.running) CarEyes.start(this, !auto, !auto || getIntent().getBooleanExtra(EXTRA_ASKED, false));
        } catch (Exception e) {
            statusT.setText("కెమెరా చెక్ మొదలవ్వలేదు: " + e.getMessage());
            return;
        }
        main.post(tick);
        if (auto) main.postDelayed(() -> { if (!isFinishing()) finish(); }, 5000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            statusT.setText(CarEyes.status.isEmpty() ? "…" : CarEyes.status);
            if (!auto && !realShown && CarEyes.running && !CarEyes.test) { // (car mode began while the test was open: now it is real)
                realShown = true;
                tip.setText("ఇప్పుడు కారు మోడ్‌లో ఉంది: కళ్ళు మూసుకుంటే నిజంగా అలారం మోగుతుంది. ఈ తెర మూసినా చెక్ కొనసాగుతుంది.");
                tip.setTextColor(Ui.GOLD);
            }
            eventT.setText(CarEyes.lastEvent);
            Bitmap b = CarEyes.preview;
            if (b != null && b != shown) { shown = b; pic.setImageBitmap(b); }
            main.postDelayed(this, 250);
        }
    };

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        CarEyes.watching = false;
        CarEyes.preview = null;
        if (started && !auto && CarEyes.test && !isChangingConfigurations()) CarEyes.stop(this); // the test ends with its screen (a real check goes on)
        super.onDestroy();
    }
}
