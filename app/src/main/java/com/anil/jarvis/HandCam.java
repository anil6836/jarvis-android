package com.anil.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ✋ (7) The front camera, only for his hand signs: a small picture a few times a second goes to Google's on-phone hand
 * model (MediaPipe, inside the app) and only the hand's 21 points come back. The picture is never shown, saved or sent;
 * the camera is open only while the workshop is in front and the signs are on.
 */
final class HandCam {
    interface Listener {
        /** (main thread) One picture's hands; w × h = the picture (upright, mirrored like a selfie). */
        void onHands(List<HandSigns.Hand> hands, long ms, int w, int h);

        /** (main thread) Something went wrong, in his words. */
        void onProblem(String problem);
    }

    static final String MODEL = "gesture_recognizer.task";
    private static final long EVERY_MS = 66; // (about 15 pictures a second)

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private HandlerThread thread;
    private Handler bg;
    private volatile boolean on;
    private volatile int token;
    private volatile int screenDeg;
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private ImageReader reader;
    private GestureRecognizer rec;
    private ByteBuffer model;
    private int sensorDeg = 270;
    private long lastRun, lastTs;
    private int fails;
    private byte[] yb, ub, vb;
    private int[] px;

    HandCam(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    /** Is the hand model inside this app (the build downloads it; without it the signs say so)? */
    static boolean hasModel(Context c) {
        try (InputStream in = c.getAssets().open(MODEL)) { return in.read() >= 0; } catch (Exception e) { return false; }
    }

    /** Has the phone a front camera? */
    static boolean hasFront(Context c) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    boolean isOn() { return on; }

    /** The screen's turn (Surface.ROTATION_*), so the picture is made upright the way he holds the phone. */
    void setScreenTurn(int turn) { screenDeg = turn == Surface.ROTATION_90 ? 90 : turn == Surface.ROTATION_180 ? 180 : turn == Surface.ROTATION_270 ? 270 : 0; }

    void start(int screenTurn) {
        if (on) return;
        setScreenTurn(screenTurn);
        on = true;
        final int my = ++token;
        if (thread == null) {
            thread = new HandlerThread("jarvis-hands");
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        bg.post(() -> open(my));
    }

    /** The camera is let go (and the model with it). */
    void stop() {
        on = false;
        token++;
        final Handler h = bg;
        if (h != null) h.post(this::closeAll);
    }

    /** The screen is gone. */
    void release() {
        stop();
        final HandlerThread t = thread;
        final Handler h = bg;
        thread = null;
        bg = null;
        if (t != null && h != null) h.post(t::quitSafely);
    }

    // ---------------------------------------------------------------- the camera (its own thread)

    @SuppressLint("MissingPermission")
    private void open(int my) {
        if (!on || my != token) return;
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { problem("కెమెరా అనుమతి లేదు"); return; }
        if (!makeRec()) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) { id = c; break; }
            }
            if (id == null) { problem("ముందు కెమెరా దొరకలేదు"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorDeg = so == null ? 270 : so;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size s = pick(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(s.getWidth(), s.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!on || my != token) { d.close(); return; }
                    dev = d;
                    session(my);
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); if (dev == d) dev = null; }
                @Override public void onError(CameraDevice d, int e) {
                    d.close();
                    if (dev == d) dev = null;
                    problem(e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE ? "ముందు కెమెరా వేరే యాప్ వాడుతోంది" : "ముందు కెమెరా తెరవలేకపోయాను");
                }
            }, bg);
        } catch (Throwable t) {
            problem("ముందు కెమెరా తెరవలేకపోయాను");
        }
    }

    @SuppressWarnings("deprecation")
    private void session(int my) {
        try {
            final Surface target = reader.getSurface();
            dev.createCaptureSession(Collections.singletonList(target), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (!on || my != token || dev == null) { cs.close(); return; }
                    ses = cs;
                    try {
                        CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(target);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        cs.setRepeatingRequest(b.build(), null, bg);
                    } catch (Throwable t) { problem("ముందు కెమెరా మొదలవలేదు"); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) { problem("ముందు కెమెరా మొదలవలేదు"); }
            }, bg);
        } catch (Throwable t) {
            problem("ముందు కెమెరా మొదలవలేదు");
        }
    }

    private void closeAll() {
        try { if (ses != null) ses.close(); } catch (Throwable ignored) {}
        try { if (dev != null) dev.close(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (rec != null) rec.close(); } catch (Throwable ignored) {}
        ses = null;
        dev = null;
        reader = null;
        rec = null;
        model = null; // (8 MB: read again next time)
    }

    /** About 640 × 480: enough for a hand an arm's length away, light on the battery. */
    static Size pick(Size[] all) {
        Size best = null;
        if (all != null) for (Size s : all) {
            if (s.getWidth() < 320 || s.getHeight() < 240) continue;
            if (best == null || away(s.getWidth(), s.getHeight()) < away(best.getWidth(), best.getHeight())) best = s;
        }
        return best != null ? best : new Size(640, 480);
    }

    /** How far a size is from 640 × 480 (4:3 preferred). */
    static long away(int w, int h) {
        return Math.abs((long) w * h - 640L * 480) + (Math.abs(w * 3 - h * 4) < 8 ? 0 : 200_000);
    }

    private boolean makeRec() {
        if (rec != null) return true;
        try {
            if (model == null) model = readAsset(ctx, MODEL);
            BaseOptions base = BaseOptions.builder().setModelAssetBuffer(model).build();
            GestureRecognizer.GestureRecognizerOptions o = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build();
            rec = GestureRecognizer.createFromOptions(ctx, o);
            return true;
        } catch (Throwable t) {
            problem("చేతి మోడల్ మొదలవలేదు (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    static ByteBuffer readAsset(Context c, String name) throws java.io.IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(9 << 20);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            byte[] all = out.toByteArray();
            ByteBuffer b = ByteBuffer.allocateDirect(all.length);
            b.put(all);
            ((java.nio.Buffer) b).position(0);
            return b;
        }
    }

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (Throwable t) { return; }
        if (img == null) return;
        Bitmap pic = null;
        try {
            long now = SystemClock.uptimeMillis();
            if (!on || rec == null || now - lastRun < EVERY_MS) return;
            lastRun = now;
            pic = upright(img, (sensorDeg + screenDeg) % 360);
        } catch (Throwable t) {
            pic = null;
        } finally {
            img.close();
        }
        if (pic == null || !on || rec == null) return;
        try {
            long ts = Math.max(SystemClock.uptimeMillis(), lastTs + 1);
            lastTs = ts;
            MPImage mp = new BitmapImageBuilder(pic).build(); // (MediaPipe copies the pixels; the picture is never kept)
            GestureRecognizerResult res = rec.recognizeForVideo(mp, ts);
            final List<HandSigns.Hand> hands = read(res);
            final int w = pic.getWidth(), h = pic.getHeight();
            fails = 0;
            main.post(() -> { if (on && listener != null) listener.onHands(hands, ts, w, h); });
        } catch (Throwable t) {
            if (++fails == 30) problem("చేతి సైగలు చదవలేకపోతున్నాను (" + t.getClass().getSimpleName() + ")");
        }
    }

    static List<HandSigns.Hand> read(GestureRecognizerResult res) {
        List<HandSigns.Hand> out = new ArrayList<>();
        if (res == null) return out;
        List<List<NormalizedLandmark>> lm = res.landmarks();
        List<List<Category>> gs = res.gestures();
        if (lm == null) return out;
        for (int i = 0; i < lm.size() && i < 2; i++) {
            List<NormalizedLandmark> pts = lm.get(i);
            if (pts == null || pts.size() < 21) continue;
            float[] xy = new float[42];
            for (int k = 0; k < 21; k++) { xy[2 * k] = pts.get(k).x(); xy[2 * k + 1] = pts.get(k).y(); }
            String sign = "";
            float score = 0;
            if (gs != null && i < gs.size() && gs.get(i) != null && !gs.get(i).isEmpty()) {
                Category c = gs.get(i).get(0);
                sign = c.categoryName();
                score = c.score();
            }
            out.add(new HandSigns.Hand(sign, score, xy));
        }
        return out;
    }

    /**
     * The camera's picture at half size, turned upright (turn = clockwise degrees) and mirrored like a selfie, so his
     * right hand moving right moves right. YUV → colours by hand: no libraries, nothing stored.
     */
    private Bitmap upright(Image img, int turn) {
        Image.Plane[] p = img.getPlanes();
        int sw = img.getWidth() / 2, sh = img.getHeight() / 2;
        yb = grab(p[0].getBuffer(), yb);
        ub = grab(p[1].getBuffer(), ub);
        vb = grab(p[2].getBuffer(), vb);
        int yr = p[0].getRowStride(), yp = p[0].getPixelStride(), cr = p[1].getRowStride(), cp = p[1].getPixelStride();
        int vr = p[2].getRowStride(), vp = p[2].getPixelStride();
        boolean across = turn == 90 || turn == 270;
        int ow = across ? sh : sw, oh = across ? sw : sh;
        if (px == null || px.length != ow * oh) px = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int ux = ow - 1 - ox, uy = oy; // (mirrored)
                int sx, sy;
                switch (turn) {
                    case 90: sx = uy; sy = sh - 1 - ux; break;
                    case 180: sx = sw - 1 - ux; sy = sh - 1 - uy; break;
                    case 270: sx = sw - 1 - uy; sy = ux; break;
                    default: sx = ux; sy = uy; break;
                }
                int yi = (2 * sy) * yr + (2 * sx) * yp, ui = sy * cr + sx * cp, vi = sy * vr + sx * vp;
                int Y = yi < yb.length ? yb[yi] & 0xFF : 0;
                int U = (ui < ub.length ? ub[ui] & 0xFF : 128) - 128, V = (vi < vb.length ? vb[vi] & 0xFF : 128) - 128;
                int R = Y + ((1436 * V) >> 10), G = Y - ((352 * U + 731 * V) >> 10), B = Y + ((1815 * U) >> 10);
                R = R < 0 ? 0 : Math.min(R, 255);
                G = G < 0 ? 0 : Math.min(G, 255);
                B = B < 0 ? 0 : Math.min(B, 255);
                px[oy * ow + ox] = 0xFF000000 | (R << 16) | (G << 8) | B;
            }
        }
        Bitmap b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, ow, 0, 0, ow, oh);
        return b;
    }

    private static byte[] grab(ByteBuffer b, byte[] into) {
        int n = b.remaining();
        if (into == null || into.length != n) into = new byte[n];
        b.get(into);
        return into;
    }

    private void problem(String s) {
        main.post(() -> { if (listener != null) listener.onProblem(s); });
    }

    // ---------------------------------------------------------------- his little cyan picture of the hand

    /** A small dark glass card with the hand's bones in cyan (never the camera's picture). */
    static final class HoloHand extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), bone = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private List<HandSigns.Hand> hands = new ArrayList<>();
        private String label = "✋ చేయి చూపించండి";
        private final float d;

        HoloHand(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            card.setColor(0xCC06101A);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * d);
            edge.setColor(0xFF4FD2FF);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setStrokeWidth(6f * d);
            glow.setColor(0x554FD2FF);
            bone.setStyle(Paint.Style.STROKE);
            bone.setStrokeCap(Paint.Cap.ROUND);
            bone.setStrokeWidth(2f * d);
            bone.setColor(0xFF4FD2FF);
            dot.setColor(0xFFE6FAFF);
            text.setColor(0xFFCDEFFF);
            text.setTextSize(10.5f * d);
        }

        void show(List<HandSigns.Hand> h, String l) {
            hands = h == null ? new ArrayList<>() : h;
            label = l == null ? "" : l;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = 12 * d;
            box.set(0, 0, w, h);
            c.drawRoundRect(box, r, r, card);
            box.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
            c.drawRoundRect(box, r, r, edge);
            float top = 6 * d, bottom = h - 34 * d, left = 6 * d, right = w - 6 * d;
            for (HandSigns.Hand hand : hands) {
                float[] p = hand.pts;
                if (p == null || p.length < 42) continue;
                for (int k = 0; k + 1 < HandSigns.BONES.length; k += 2) {
                    int a = HandSigns.BONES[k], b = HandSigns.BONES[k + 1];
                    float ax = left + p[2 * a] * (right - left), ay = top + p[2 * a + 1] * (bottom - top);
                    float bx = left + p[2 * b] * (right - left), by = top + p[2 * b + 1] * (bottom - top);
                    c.drawLine(ax, ay, bx, by, glow);
                    c.drawLine(ax, ay, bx, by, bone);
                }
                for (int k = 0; k < 21; k++) c.drawCircle(left + p[2 * k] * (right - left), top + p[2 * k + 1] * (bottom - top), 2.2f * d, dot);
            }
            int tw = Math.max(1, Math.round(w - 10 * d));
            if (said == null || !label.equals(saidText) || saidW != tw) {
                said = android.text.StaticLayout.Builder.obtain(label, 0, label.length(), text, tw)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(2)
                        .setEllipsize(android.text.TextUtils.TruncateAt.END)
                        .build();
                saidText = label;
                saidW = tw;
            }
            c.save();
            c.translate(5 * d, h - 4 * d - said.getHeight());
            said.draw(c);
            c.restore();
        }

        private android.text.StaticLayout said;
        private String saidText;
        private int saidW;
    }
}
