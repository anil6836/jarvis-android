package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

/**
 * 📄 "చదివి చెప్పు" on the home tablet (Anil, 24 Oct): అమ్మగారు holds a letter, a bill, an invitation or a medicine box in
 * front of the tablet's camera and presses one big button; the picture goes to the AI (his key, his chosen model) only on
 * that press, and Jarvis reads it out in simple Telugu on his screen. Never read aloud: one-time codes, PINs, card / account /
 * ID numbers; for medicines only the name and the expiry date (never doses or advice). No internet: the tablet reads English
 * print itself (ML Kit, on the tablet) and says so. Nothing is saved.
 */
public class HomeReadActivity extends Activity implements TextureView.SurfaceTextureListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextureView view;
    private TextView info, shoot, flip;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Size size = new Size(1280, 960);
    private boolean front = true, busy, open;
    private static final int REQ_CAM = 41;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        front = getSharedPreferences("home_read", MODE_PRIVATE).getBoolean("front", true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1A2C);
        view = new TextureView(this);
        view.setSurfaceTextureListener(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        // a soft frame in the middle: "the paper here"
        View guide = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(dp(4), 0xCCF2B544, dp(18), dp(10));
        g.setCornerRadius(dp(18));
        guide.setBackground(g);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, -1);
        gl.setMargins(dp(120), dp(90), dp(120), dp(150));
        root.addView(guide, gl);

        info = new TextView(this);
        info.setTextColor(0xFFFFFFFF);
        info.setTextSize(26);
        info.setTypeface(Typeface.DEFAULT_BOLD);
        info.setGravity(Gravity.CENTER);
        info.setShadowLayer(8, 0, 2, 0xFF000000);
        info.setPadding(dp(24), dp(18), dp(24), dp(18));
        info.setText("📄 కాగితాన్ని ఈ పసుపు గీతల మధ్యలో, దగ్గరగా, నిటారుగా పట్టుకోండి");
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(20), 0, dp(20), dp(20));
        TextView close = button("✕ మూసేయి", 0xFF3A4658);
        close.setOnClickListener(v -> finish());
        row.addView(close, new LinearLayout.LayoutParams(0, dp(96), 1));
        shoot = button("📸 చదువు", 0xFFF2B544);
        shoot.setTextColor(0xFF1A1206);
        shoot.setTextSize(34);
        shoot.setOnClickListener(v -> take());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(110), 2);
        sl.setMargins(dp(18), 0, dp(18), 0);
        row.addView(shoot, sl);
        flip = button("", 0xFF1F3B5C);
        flip.setOnClickListener(v -> {
            if (busy) return;
            front = !front;
            getSharedPreferences("home_read", MODE_PRIVATE).edit().putBoolean("front", front).apply();
            showFlip();
            closeCamera();
            openCamera();
        });
        showFlip();
        row.addView(flip, new LinearLayout.LayoutParams(0, dp(96), 1));
        root.addView(row, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        main.postDelayed(idleClose, 3 * 60_000L);
    }

    private void showFlip() { flip.setText(front ? "🔄 వెనుక కెమెరా" : "🔄 ముందు కెమెరా"); }

    /** Left alone for 3 minutes: back to Jarvis. */
    private final Runnable idleClose = () -> { if (!busy) finish(); };

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        openCamera();
    }

    @Override protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_CAM) return;
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else info.setText("కెమెరా అనుమతి లేదు: అబ్బాయికి చెప్పండి");
    }

    // ================================================================ the camera
    private void openCamera() {
        if (open) return;
        open = true;
        thread = new HandlerThread("home-read");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (view.isAvailable()) startCamera();
    }

    private void closeCamera() {
        open = false;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    @SuppressWarnings("MissingPermission")
    private void startCamera() {
        if (!open || camera != null) return;
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String pick = null, any = null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (any == null) any = id;
                if (f != null && f == want) { pick = id; break; }
            }
            if (pick == null) pick = any;
            if (pick == null) { info.setText("ఈ టాబ్లెట్‌లో కెమెరా కనిపించలేదు"); return; }
            size = bestSize(cm.getCameraCharacteristics(pick).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            cm.openCamera(pick, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (!open) { c.close(); return; }
                    camera = c;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                @Override public void onError(CameraDevice c, int error) {
                    c.close();
                    camera = null;
                    main.post(() -> info.setText("కెమెరా తెరవలేకపోయాను. ఒకసారి మూసి మళ్ళీ తెరవండి."));
                }
            }, bg);
        } catch (Exception e) {
            info.setText("కెమెరా తెరవలేకపోయాను.");
        }
    }

    /** The biggest 4:3 (else any) preview size up to 1920 wide: enough for small print. */
    private static Size bestSize(StreamConfigurationMap map) {
        Size best = null;
        if (map == null) return new Size(1280, 960);
        for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            boolean four3 = Math.abs(s.getWidth() * 3 - s.getHeight() * 4) < 8;
            if (best == null || four3 && (!fourThree(best) || s.getWidth() > best.getWidth())
                    || !fourThree(best) && !four3 && s.getWidth() > best.getWidth()) best = s;
        }
        return best == null ? new Size(1280, 960) : best;
    }

    private static boolean fourThree(Size s) { return Math.abs(s.getWidth() * 3 - s.getHeight() * 4) < 8; }

    private void startPreview() {
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            main.post(() -> fit(view.getWidth(), view.getHeight()));
            Surface surface = new Surface(st);
            CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(surface);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), null, bg); } catch (Exception ignored) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {}
            }, bg);
        } catch (Exception ignored) {}
    }

    /** The preview upright and filling the screen (the camera's picture turned with the screen, not stretched). */
    private void fit(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        RectF v = new RectF(0, 0, vw, vh);
        float cx = v.centerX(), cy = v.centerY();
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            RectF buf = new RectF(0, 0, size.getHeight(), size.getWidth());
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(v, buf, Matrix.ScaleToFit.FILL);
            float s = Math.max((float) vh / size.getHeight(), (float) vw / size.getWidth());
            m.postScale(s, s, cx, cy);
            m.postRotate(90 * (rot - 2), cx, cy);
        } else {
            float bw = size.getWidth(), bh = size.getHeight(); // (the screen's natural way: fill, keep the shape)
            float s = Math.max(vw / bw, vh / bh);
            m.setScale(bw * s / vw, bh * s / vh, cx, cy);
            if (rot == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        }
        view.setTransform(m);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { if (open) startCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(w, h); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ================================================================ reading
    private void take() {
        if (busy || camera == null || !view.isAvailable()) return;
        busy = true;
        main.removeCallbacks(idleClose);
        final Bitmap pic = view.getBitmap();
        if (pic == null) { busy = false; return; }
        shoot.setText("⏳ చదువుతున్నాను…");
        info.setText("కాసేపు అలాగే ఉండండి… చదువుతున్నాను");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String said = read(app, pic);
            main.post(() -> {
                MainActivity.homeRead(app, said); // (Jarvis says it on his screen, his words in the bubble)
                finish();
            });
        }, "home-read").start();
    }

    /** What Jarvis says about the picture (background thread). */
    static String read(Context c, Bitmap pic) {
        String w = HomeCare.who(c);
        Prefs p = new Prefs(c);
        if (Net.online(c) && p.hasBrain() && !HomeCare.overLimit(c)) {
            try {
                HomeCare.countAi(c);
                String r = Brain.oneShot(p, SYSTEM.replace("%W", w), "Read this for " + w + " now.", jpeg(pic), false, 700);
                if (r != null && !r.trim().isEmpty()) return safe(r.trim());
            } catch (Exception ignored) {}
            // (the AI didn't answer: the tablet reads it itself, and says so)
            return w + ", ఇప్పుడు పెద్ద మెదడు (AI) జవాబివ్వలేదు; టాబ్లెట్ తానే చదివింది (ఇంగ్లీష్ అక్షరాలు మాత్రమే). " + offline(c, pic);
        }
        String why = !Net.online(c) ? "నెట్ లేదు" : !p.hasBrain() ? "AI key పెట్టలేదు" : "ఈరోజు AI పరిమితి అయిపోయింది";
        return w + ", " + why + ": టాబ్లెట్ తానే చదివింది (ఇంగ్లీష్ అక్షరాలు మాత్రమే). " + offline(c, pic);
    }

    /** The tablet's own reading (ML Kit): a medicine strip / box / prescription gives only its expiry, never its doses. */
    private static String offline(Context c, Bitmap pic) {
        String en = OfflineRead.text(pic);
        if (medicine(en)) return medicineLine(en);
        return safe(OfflineRead.readAloud(c, pic));
    }

    private static final java.util.regex.Pattern MED = java.util.regex.Pattern.compile(
            "(?i)\\b(\\d+\\s?mg|mcg|tablets?|capsules?|syrup|suspension|injection|rx|dose|dosage|"
                    + "composition|each (film[- ]coated )?tablet|schedule h|prescription|ointment|eye drops|ear drops)\\b"); // (not "B. P. Rao")
    private static final java.util.regex.Pattern EXP = java.util.regex.Pattern.compile(
            "(?i)\\bexp(?:iry)?\\.?(?:\\s*date)?\\s*[:.]?\\s*((?:\\d{1,2}[/.-])?\\d{1,2}[/.-]\\d{2,4}|[a-z]{3}[ ./-]?\\d{2,4})");

    /** The text looks like a medicine's (strength, tablet, syrup, Rx, I.P.…). Pure. */
    static boolean medicine(String en) { return en != null && MED.matcher(en).find(); }

    /** What is said for a medicine read offline: what it is and its expiry, never how to take it. Pure. */
    static String medicineLine(String en) {
        java.util.regex.Matcher m = EXP.matcher(en == null ? "" : en);
        String exp = m.find() ? m.group(1).trim() : "";
        return "ఇది మందుల డబ్బా లేదా స్ట్రిప్ లాగా ఉంది. " + (exp.isEmpty() ? "గడువు తేదీ కనిపించలేదు. " : "గడువు తేదీ: " + exp + ". ")
                + "ఎలా వేసుకోవాలో డాక్టర్‌ని లేదా అబ్బాయిని అడగండి.";
    }

    private static String jpeg(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), max = 1600;
        Bitmap s = b;
        if (Math.max(w, h) > max) {
            float k = max / (float) Math.max(w, h);
            s = Bitmap.createScaledBitmap(b, Math.round(w * k), Math.round(h * k), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** Never aloud: codes (Otp), and long numbers (accounts, cards, ID numbers, phone numbers). Pure. */
    static String safe(String s) {
        if (s == null) return "";
        String t = Otp.spoken(s.replaceAll("[*#`_]+", " ").replaceAll("\\s+", " ").trim());
        // (10 digits or more, spaces / dashes between: phone, account, card, Aadhaar; a date "12-03-2026" has only 8)
        return t.replaceAll("(?<![\\p{Nd}])\\p{Nd}(?:[\\s-]?\\p{Nd}){9,}(?![\\p{Nd}])", "(ఒక పెద్ద నంబర్ ఉంది, చదవను)");
    }

    static final String SYSTEM = "You read a paper aloud for %W, an elderly Telugu lady at home, on her home tablet. The picture is what she "
            + "holds up to the camera: a letter, a bill, a notice, an invitation, a newspaper cutting, a medicine box or strip, a label. "
            + "Reply ONLY in simple, warm, spoken Telugu (it is read aloud), short sentences, no markdown, no lists, no emojis. "
            + "First say in one short line what it is (for example: ఇది కరెంట్ బిల్లు). Then the important parts: for a bill — whose, how much, the last date; "
            + "for a letter, notice or invitation — who sent it, what it says, any date, time and place; a long text — its main points in at most 8 short sentences. "
            + "English, Hindi or other languages: say it in Telugu. "
            + "Medicines: say ONLY the medicine's name and the expiry date, and clearly if it has expired; never read or suggest doses or how to take it, never "
            + "advise about medicines (say: ఎలా వేసుకోవాలో డాక్టర్‌ని లేదా అబ్బాయిని అడగండి). "
            + "Never read out one-time codes (OTP), PINs, passwords, card numbers, bank account numbers, Aadhaar / PAN or any ID numbers, or phone "
            + "numbers: say 'ఒక నంబర్ ఉంది' instead. Never say who a person in a photo is. "
            + "If it is blurry, too dark, cut off or has no writing, say so kindly and ask her to hold it a little closer, steady and in good light.";

    // ================================================================ small parts
    private int dp(float v) { return Ui.dp(this, v); }

    private TextView button(String s, int bg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(26);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(22));
        t.setBackground(d);
        t.setClickable(true);
        return t;
    }
}
