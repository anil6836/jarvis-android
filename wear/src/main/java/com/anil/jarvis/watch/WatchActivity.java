package com.anil.jarvis.watch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * The Jarvis watch screen: the HUD ring (time, battery), the living orb (W2), what he said and Jarvis's answer in
 * Telugu (W11), "are you sure?" with two taps, and a small check of what works. The bezel scrolls (W7). Tap the orb:
 * talk / done / stop. The palm over the watch while it speaks stops it (W13). Opening it starts listening (W10).
 */
public class WatchActivity extends Activity implements Talk.Screen {
    private static final int CYAN = 0xFF74E4FF, TEXT = 0xFFDCEEF5, MUTED = 0xFF8FA9B5, FAINT = 0xFF5B7380;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HudView hud;
    private OrbView orb;
    private ScrollView scroll;
    private TextView status, heard, reply, action, check, cTitle, cMsg, cYes, cNo;
    private LinearLayout confirmBox;
    private boolean resumed, keepOn;
    private float rotary;
    private int confirmShown = -1, confirmLeft;
    private Typeface te;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        te = telugu();
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        hud = new HudView(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Math.round(w * 0.13f);
        col.setPadding(side, 0, side, Math.round(h * 0.32f));
        orb = new OrbView(this);
        orb.setOnClickListener(v -> act());
        col.addView(orb, new LinearLayout.LayoutParams(-1, Math.round(h * 0.6f)));
        status = text(13, CYAN, true);
        col.addView(status);
        heard = text(13, MUTED, true);
        col.addView(heard);
        reply = text(15, TEXT, false);
        reply.setLineSpacing(0, 1.12f);
        col.addView(reply);

        confirmBox = new LinearLayout(this);
        confirmBox.setOrientation(LinearLayout.VERTICAL);
        confirmBox.setPadding(0, dp(8), 0, dp(4));
        cTitle = text(14, CYAN, true);
        confirmBox.addView(cTitle);
        cMsg = text(13, TEXT, true);
        confirmBox.addView(cMsg);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        cNo = pill("✗ వద్దు", 0xFF7F1D1D);
        cYes = pill("✓", 0xFF166534);
        row.addView(cNo, new LinearLayout.LayoutParams(0, -2, 1));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(cYes, new LinearLayout.LayoutParams(0, -2, 1));
        confirmBox.addView(row);
        confirmBox.setVisibility(View.GONE);
        col.addView(confirmBox, new LinearLayout.LayoutParams(-1, -2));

        action = pill("🎙️ మాట్లాడు", 0xFF0E3A4A);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(10);
        col.addView(action, ap);
        action.setOnClickListener(v -> act());

        check = text(10.5f, FAINT, true);
        check.setPadding(0, dp(18), 0, 0);
        col.addView(check);

        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> hud.setFade(1f - scroll.getScrollY() / (h * 0.18f)));
        setContentView(root);

        Talk.screen = this;
        boolean launcher = Intent.ACTION_MAIN.equals(getIntent().getAction());
        if (b == null && launcher && Talk.state == Talk.IDLE) {
            orb.setAppear(0f);
            hud.boot(() -> { if (Link.openListen(this) && Talk.state == Talk.IDLE && Talk.micAllowed(this)) Talk.listen(this, "open"); });
        }
        askPermissions();
        changed();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // the icon / Home double press / a watch-face shortcut while it is already open: talk now
        if (Intent.ACTION_MAIN.equals(i.getAction()) && Link.openListen(this) && Talk.state == Talk.IDLE && Talk.micAllowed(this)) Talk.listen(this, "open");
    }

    private void askPermissions() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (Talk.micAllowed(this)) { EarService.startIfWanted(this); Talk.hello(this); }
        changed();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Talk.screen = this;
        Notes.talk(this); // (the screen shows the talk now: no notification)
        if (Talk.micAllowed(this)) EarService.startIfWanted(this);
        Talk.hello(this);
        scroll.requestFocus();
        restartCountdown();
        changed();
        main.post(tick);
    }

    @Override protected void onPause() {
        // W13: the screen going dark while Jarvis speaks (it is kept lit then) means his palm covered the watch
        PowerManager pm = getSystemService(PowerManager.class);
        if (Talk.state == Talk.SPEAKING && keepOn && pm != null && !pm.isInteractive()) Talk.stop(this);
        resumed = false;
        Talk.screenUp = false;
        main.removeCallbacks(tick);
        restartCountdown(); // never "yes" unseen: back on the screen, it counts from the top again
        super.onPause();
    }

    @Override protected void onStart() {
        super.onStart();
        Talk.screen = this;
    }

    @Override protected void onStop() {
        // not on the screen any more: a talk from the raised wrist shows as a notification (and brings this back)
        if (Talk.screen == this) Talk.screen = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (Talk.screen == this) Talk.screen = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** The orb / the button: talk, done talking, or stop. */
    private void act() {
        switch (Talk.state) {
            case Talk.LISTENING: Talk.finishListening(this); break;
            case Talk.UNDERSTANDING:
            case Talk.THINKING:
            case Talk.SPEAKING: Talk.stop(this); break;
            default:
                if (!Talk.micAllowed(this)) { askPermissions(); return; }
                Talk.listen(this, "tap");
        }
    }

    /** Talk changed (main thread). */
    @Override public void changed() {
        if (isDestroyed()) return;
        int s = Talk.state;
        status.setText(Talk.status);
        status.setVisibility(Talk.status.isEmpty() ? View.GONE : View.VISIBLE);
        heard.setText(Talk.heard.isEmpty() ? "" : "“" + Talk.heard + "”");
        heard.setVisibility(Talk.heard.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hadReply = reply.getText().length() > 0;
        reply.setText(Talk.reply);
        reply.setGravity(Talk.reply.length() <= 70 ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        reply.setVisibility(Talk.reply.isEmpty() ? View.GONE : View.VISIBLE);
        if (!hadReply && !Talk.reply.isEmpty()) scroll.post(() -> scroll.smoothScrollTo(0, Math.round(orb.getHeight() * 0.45f)));
        if (Talk.reply.isEmpty() && s == Talk.LISTENING) scroll.smoothScrollTo(0, 0);
        action.setText(s == Talk.LISTENING ? "✓ అయిపోయింది" : s == Talk.IDLE || s == Talk.ERROR ? "🎙️ మాట్లాడు" : "■ ఆపు");
        JSONObject q = Talk.confirm;
        if (q == null) { confirmBox.setVisibility(View.GONE); confirmShown = -1; }
        else {
            int id = q.optInt("id");
            if (confirmShown != id) {
                confirmShown = id;
                confirmLeft = q.optInt("auto");
                lastCount = SystemClock.elapsedRealtime();
                cTitle.setText(q.optString("title"));
                cMsg.setText(q.optString("msg"));
                cYes.setOnClickListener(v -> Talk.answer(this, id, true));
                cNo.setOnClickListener(v -> Talk.answer(this, id, false));
                scroll.post(() -> scroll.smoothScrollTo(0, confirmBox.getTop() - dp(20)));
            }
            cYes.setText("✓ " + q.optString("yes", "సరే") + (confirmLeft > 0 ? " (" + confirmLeft + ")" : ""));
            confirmBox.setVisibility(View.VISIBLE);
        }
        boolean on = s != Talk.IDLE || q != null;
        if (on != keepOn) {
            keepOn = on;
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Talk.screenUp = resumed && keepOn;
        check.setText(checkText());
    }

    /** Every second: the boot's orb, the confirm countdown (only while he can see it), the check lines. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            orb.setAppear(hud.bootProgress() < 0.6f ? 0f : (hud.bootProgress() - 0.6f) / 0.4f);
            JSONObject q = Talk.confirm;
            long now = SystemClock.elapsedRealtime();
            PowerManager pm = getSystemService(PowerManager.class);
            if (q != null && q.optInt("auto") > 0 && (!resumed || pm == null || !pm.isInteractive())) {
                // never "yes" unseen: away from the screen, the countdown starts again from the top
                restartCountdown();
                changed();
            } else if (q != null && confirmLeft > 0 && resumed && now - lastCount >= 1000) {
                lastCount = now;
                if (--confirmLeft <= 0) { Talk.answer(WatchActivity.this, q.optInt("id"), true); }
                changed();
            } else if (now - lastCheck > 2000) { lastCheck = now; check.setText(checkText()); }
            main.postDelayed(this, hud.bootProgress() < 1f ? 50 : 250);
        }
    };
    private long lastCount, lastCheck;

    private void restartCountdown() {
        JSONObject q = Talk.confirm;
        if (q != null) confirmLeft = q.optInt("auto");
        lastCount = SystemClock.elapsedRealtime();
    }

    private String checkText() {
        StringBuilder b = new StringBuilder();
        long ver = 0;
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception ignored) {}
        b.append("⌚ Jarvis వాచ్ 1.0.").append(ver);
        long ago = Link.heardAt == 0 ? -1 : (SystemClock.elapsedRealtime() - Link.heardAt) / 1000;
        b.append("\n📱 ఫోన్: ").append(!Link.ok ? "అందడం లేదు" : ago < 0 ? "ఇంకా మాట్లాడలేదు" : "కనెక్ట్ అయింది (" + (ago < 90 ? ago + " సె" : ago / 60 + " ని") + " క్రితం)");
        b.append("\n🎙️ మైక్: ").append(Talk.micAllowed(this) ? "అనుమతి ఉంది" : "అనుమతి లేదు (పైన మాట్లాడు నొక్కండి)");
        b.append("\n✋ చేయి ఎత్తి వినడం: ").append(!Link.raise(this) ? "ఆఫ్" : Talk.listenBroken ? "ఆగింది (Jarvis తెరిచాక మళ్లీ మొదలవుతుంది)" : EarService.running ? "ఆన్" : "మొదలవుతోంది…");
        if (Link.hours(this)) b.append("\n🕐 ఎప్పుడూ వినే సమయం: ").append(Link.from(this)).append(":00 – ").append(Link.to(this)).append(":00").append(Link.inHours(this) ? " (ఇప్పుడు)" : "");
        String e = Link.ears(this);
        b.append("\n🎧 మాటలు రాసేది: ").append("openai".equals(e) ? "OpenAI (ఫోన్ ద్వారా)" : "gemini".equals(e) ? "Gemini (ఫోన్ ద్వారా)"
                : "watch".equals(e) ? "వాచ్ Google వాయిస్ టైపింగ్" + (Hear.available(this) ? "" : " (ఈ వాచ్‌లో లేదు!)") : "ఫోన్ నిర్ణయిస్తుంది");
        b.append("\n🌐 వాచ్ ఇంటర్నెట్: ").append(Talk.online(this) ? "ఉంది" : "లేదు").append(Talk.phoneOnline ? "" : " · ఫోన్‌కి నెట్ లేదు (offline పనులు మాత్రమే)");
        if (!Mic.lastError.isEmpty()) b.append("\n· ").append(Mic.lastError);
        if (!Link.ok && !Link.lastError.isEmpty()) b.append("\n· ").append(Link.lastError);
        return b.toString();
    }

    // ---------------------------------------------------------------- W7: the bezel scrolls

    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            scroll.scrollBy(0, Math.round(delta));
            rotary += Math.abs(delta);
            if (rotary > dp(28)) { rotary = 0; scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ---------------------------------------------------------------- looks

    private TextView text(float sp, int color, boolean center) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (te != null) t.setTypeface(te);
        if (center) t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private TextView pill(String label, int color) {
        TextView t = text(14, 0xFFFFFFFF, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(color);
        g.setStroke(dp(1), 0x6674E4FF);
        t.setBackground(g);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Telugu letters: the watch's own font when it has them, else the one that comes with this app (if any). */
    private Typeface telugu() {
        try { if (new Paint().hasGlyph("తె")) return null; } catch (Exception ignored) {}
        try { return Typeface.createFromAsset(getAssets(), "NotoSansTelugu-Regular.ttf"); } catch (Exception e) { return null; }
    }
}
