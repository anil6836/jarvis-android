package com.anil.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Jarvis camera's full-screen picture (Camera2, no libraries): back, front and any plugged-in (external) camera,
 * torch, zoom, tap to focus. The preview fills the screen without stretching (its edges are cut off, like any camera
 * app), and a "photo" is exactly the part he sees, so what Jarvis finds can be drawn right on the screen.
 */
final class CamView extends FrameLayout implements TextureView.SurfaceTextureListener {
    interface Listener { void onCamera(boolean open, String problem); }

    final TextureView tex;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager cm;
    private final List<String> ids = new ArrayList<>();
    private final List<Integer> facings = new ArrayList<>();
    private int which;
    private HandlerThread thread;
    private Handler bg;
    private volatile CameraDevice dev;
    private volatile CameraCaptureSession ses;
    private volatile CaptureRequest.Builder req;
    private volatile int token; // each opening; a late answer from an older one closes itself
    private Size size;
    private int sensorDeg = 90;
    private boolean wanted, torch, flash;
    private float zoom = 1f, maxZoom = 1f, minZoom = 1f;
    /**
     * Jarvis's own zoom on top of the camera's (when he goes past what the phone gives other apps): the screen shows the
     * part of the camera's picture around (cx, cy) (0..1 of it), digital times bigger. Total zoom = zoom × digital, up to MAX_TOTAL.
     */
    private float digital = 1f, cx = 0.5f, cy = 0.5f;
    static final float MAX_TOTAL = 100f;
    private boolean night;
    private Rect active;
    private boolean ratioZoom;
    Listener listener;

    CamView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        tex = new TextureView(c);
        tex.setSurfaceTextureListener(this);
        addView(tex, new LayoutParams(-1, -1, Gravity.CENTER));
        tex.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyDigital()); // (its size follows the camera's shape)
        try {
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                int face = f == null ? CameraCharacteristics.LENS_FACING_BACK : f;
                // one of each: the first back, the first front, then plugged-in cameras (endoscope / thermal that the phone supports)
                if (face != CameraCharacteristics.LENS_FACING_EXTERNAL && facings.contains(face)) continue;
                ids.add(id);
                facings.add(face);
            }
        } catch (Exception ignored) {}
        int back = facings.indexOf(CameraCharacteristics.LENS_FACING_BACK);
        which = Math.max(0, back);
    }

    boolean hasCamera() { return !ids.isEmpty(); }
    boolean isFront() { return !facings.isEmpty() && facings.get(which) == CameraCharacteristics.LENS_FACING_FRONT; }
    boolean isExternal() { return !facings.isEmpty() && facings.get(which) == CameraCharacteristics.LENS_FACING_EXTERNAL; }
    int cameraCount() { return ids.size(); }
    boolean hasFlash() { return flash; }
    boolean torchOn() { return torch; }
    float zoom() { return zoom; }
    boolean isOpen() { return dev != null && ses != null; }

    /** Which camera, in words. */
    String which() { return isFront() ? "ముందు కెమెరా" : isExternal() ? "బయటి కెమెరా (USB)" : "వెనక కెమెరా"; }

    void open() {
        if (wanted) return;
        wanted = true;
        if (thread != null && bg == null) thread = null;
        if (thread == null) {
            thread = new HandlerThread("jarvis-cam");
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        if (tex.isAvailable()) start();
    }

    /** The camera is let go (the thread stays, so a camera still opening is closed when it arrives). */
    void close() {
        wanted = false;
        stopCamera();
    }

    /** The screen is gone: the thread too. */
    void release() {
        close();
        final HandlerThread t = thread;
        thread = null;
        if (t != null && bg != null) bg.postDelayed(t::quitSafely, 3000); // after any late "opened" has closed itself
        bg = null;
    }

    private void stopCamera() {
        token++;
        CameraCaptureSession s = ses;
        CameraDevice d = dev;
        ses = null;
        dev = null;
        req = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        try { if (d != null) d.close(); } catch (Exception ignored) {}
    }

    /** The next camera (back → front → external → back). */
    void switchCamera() {
        if (ids.size() < 2) return;
        stopCamera();
        which = (which + 1) % ids.size();
        torch = false;
        zoom = 1f;
        digital = 1f;
        cx = cy = 0.5f;
        main.post(this::applyDigital);
        if (wanted && tex.isAvailable()) start();
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (!wanted || ids.isEmpty() || bg == null) return;
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            tell(false, "కెమెరా అనుమతి లేదు");
            return;
        }
        final int my = ++token;
        try {
            String id = ids.get(which);
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorDeg = so == null ? 90 : so;
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flash = fl != null && fl;
            active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            ratioZoom = false;
            maxZoom = 1f;
            minZoom = 1f;
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> r = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (r != null) { ratioZoom = true; maxZoom = Math.max(1f, r.getUpper()); minZoom = Math.max(1f, r.getLower()); } // (all the phone gives)
            }
            if (!ratioZoom) {
                Float m = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                maxZoom = m == null ? 1f : Math.min(8f, m);
            }
            size = pick(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            main.post(this::fit);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!wanted || my != token) { d.close(); return; } // closed or switched meanwhile
                    dev = d;
                    if (my != token) { d.close(); if (dev == d) dev = null; return; } // (closed just now)
                    session(d, my);
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (dev == d) { dev = null; ses = null; }
                    if (my == token) tell(false, "కెమెరా ఆగిపోయింది (వేరే యాప్ తీసుకుంది)");
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close();
                    if (dev == d) { dev = null; ses = null; }
                    if (my == token) tell(false, e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE ? "కెమెరా వేరే యాప్ వాడుతోంది" : "కెమెరా తెరవలేకపోయాను");
                }
            }, bg);
        } catch (Exception e) {
            tell(false, "కెమెరా తెరవలేకపోయాను");
        }
    }

    /** The biggest 4:3 picture up to 1920 long (good for reading small parts), else the closest to it. */
    private static Size pick(Size[] all) {
        if (all == null || all.length == 0) return new Size(1440, 1080);
        Size best = null;
        for (Size s : all) {
            int l = Math.max(s.getWidth(), s.getHeight()), sh = Math.min(s.getWidth(), s.getHeight());
            if (l > 1920 || Math.abs(l * 3 - sh * 4) > 8) continue;
            if (best == null || l > Math.max(best.getWidth(), best.getHeight())) best = s;
        }
        if (best != null) return best;
        for (Size s : all) {
            int l = Math.max(s.getWidth(), s.getHeight());
            if (l > 1920) continue;
            if (best == null || l > Math.max(best.getWidth(), best.getHeight())) best = s;
        }
        return best != null ? best : all[0];
    }

    private void session(CameraDevice dv, int my) {
        try {
            SurfaceTexture st = tex.getSurfaceTexture();
            if (my != token) { dv.close(); if (dev == dv) dev = null; return; }
            if (st == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface s = new Surface(st);
            req = dv.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(s);
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            fps0 = req.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE); // (the camera's own choice, kept for when 🌙 goes off)
            applyZoom();
            if (night) applyNight();
            //noinspection deprecation
            dv.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (dev != dv || !wanted || my != token) { cs.close(); return; }
                    ses = cs;
                    repeat();
                    tell(true, null);
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) { tell(false, "కెమెరా మొదలవలేదు"); }
            }, bg);
        } catch (Exception e) {
            tell(false, "కెమెరా మొదలవలేదు");
        }
    }

    private void repeat() {
        try { if (ses != null && req != null) ses.setRepeatingRequest(req.build(), null, bg); } catch (Exception ignored) {}
    }

    private void tell(boolean open, String problem) {
        main.post(() -> { if (listener != null) listener.onCamera(open, problem); });
    }

    void setTorch(boolean on) {
        if (!flash || req == null || bg == null) return;
        torch = on;
        bg.post(() -> {
            if (req == null) return;
            req.set(CaptureRequest.FLASH_MODE, on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            repeat();
        });
    }

    /** The camera's own (optical + the phone's) zoom it gives other apps. */
    float opticalMax() { return maxZoom; }
    float digital() { return digital; }

    /** Total zoom (1 = none, up to MAX_TOTAL): the camera's own first, then Jarvis's (around the same spot). */
    void setZoom(float z) {
        float total = Math.max(minZoom, Math.min(MAX_TOTAL, z));
        float o = Math.min(total, maxZoom);
        setParts(o, total / o, cx, cy);
    }

    /**
     * Zoom so the thing at (tx, ty) (0..1 of the screen now), half-sized hw × hh, comes to the middle and is k times
     * bigger. The camera's own zoom only goes as far as keeps it in the picture (it zooms around the middle); Jarvis's
     * zoom does the rest around it. Returns {camera zoom, digital, cx, cy} to animate to.
     */
    float[] planZoomTo(float tx, float ty, float hw, float hh, float k) { return plan(zoom, digital, cx, cy, minZoom, maxZoom, tx, ty, hw, hh, k); }

    /** (the sums of planZoomTo, on their own so they can be checked) */
    static float[] plan(float o, float dg, float cx, float cy, float minZoom, float maxZoom, float tx, float ty, float hw, float hh, float k) {
        float ux = cx + (tx - 0.5f) / dg, uy = cy + (ty - 0.5f) / dg; // where it is in the camera's own picture
        float hx = hw / dg, hy = hh / dg;
        float total = Math.max(minZoom, Math.min(MAX_TOTAL, o * dg * k));
        float lim = 0.5f * o / Math.max(1e-3f, Math.max(Math.abs(ux - 0.5f) + hx * 1.15f, Math.abs(uy - 0.5f) + hy * 1.15f));
        float o2 = Math.max(minZoom, Math.min(Math.min(maxZoom, total), Math.max(o * 0.999f, Math.min(lim, maxZoom))));
        if (o2 < o) o2 = o; // (never pulls the camera's own zoom back: Jarvis's zoom moves instead)
        float ux2 = 0.5f + (ux - 0.5f) * (o2 / o), uy2 = 0.5f + (uy - 0.5f) * (o2 / o);
        float dg2 = Math.max(1f, total / o2);
        return new float[]{o2, dg2, ux2, uy2};
    }

    /** The camera's zoom, Jarvis's zoom and where (cx, cy: 0..1 of the camera's own picture). */
    void setParts(float o, float dg, float x, float y) {
        o = Math.max(minZoom, Math.min(maxZoom, o));
        dg = Math.max(1f, Math.min(MAX_TOTAL / o, dg));
        float half = 0.5f / dg;
        cx = Math.max(half, Math.min(1 - half, x));
        cy = Math.max(half, Math.min(1 - half, y));
        digital = dg;
        boolean camChanged = Math.abs(o - zoom) > 1e-3f;
        zoom = o;
        applyDigital();
        if (camChanged && bg != null) bg.post(() -> { if (req != null) { applyZoom(); repeat(); } });
    }

    /** Where (cx, cy) now is. */
    float[] digitalCenter() { return new float[]{cx, cy}; }

    /** Jarvis's zoom: the picture made bigger on the screen around (cx, cy). */
    private void applyDigital() {
        int W = getWidth(), H = getHeight(), tw = tex.getWidth(), th = tex.getHeight();
        if (W == 0 || H == 0 || tw == 0 || th == 0) return;
        float lx = (W - tw) / 2f, ly = (H - th) / 2f; // (the texture's own place, when it is bigger than the screen)
        float x0 = (cx - 0.5f / digital) * W, y0 = (cy - 0.5f / digital) * H;
        tex.setPivotX(0);
        tex.setPivotY(0);
        tex.setScaleX(digital);
        tex.setScaleY(digital);
        tex.setTranslationX(lx * (digital - 1) - x0 * digital);
        tex.setTranslationY(ly * (digital - 1) - y0 * digital);
    }

    /** 🌙 night look: the camera keeps its picture brighter (longer exposure), for dark streets and rooms. */
    void setNight(boolean on) {
        night = on;
        if (bg == null) return;
        bg.post(() -> { if (req != null) { applyNight(); repeat(); } });
    }

    boolean night() { return night; }

    private Range<Integer> fps0;

    private void applyNight() {
        if (req == null) return;
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(ids.get(which));
            Range<Integer> ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int comp = night && ev != null ? ev.getUpper() : 0;
            req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, comp);
            if (!night) { if (fps0 != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps0); return; } // (back to the camera's own)
            Range<Integer>[] fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fps != null && fps.length > 0) { // the slowest range: longer exposures, a brighter picture
                Range<Integer> pick = null;
                for (Range<Integer> r : fps) if (pick == null || r.getLower() < pick.getLower() || r.getLower().equals(pick.getLower()) && r.getUpper() > pick.getUpper()) pick = r;
                if (pick != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick);
            }
        } catch (Exception ignored) {}
    }

    private void applyZoom() {
        if (req == null) return;
        if (ratioZoom && Build.VERSION.SDK_INT >= 30) {
            req.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        } else if (active != null && maxZoom > 1f) {
            int w = Math.round(active.width() / zoom), h = Math.round(active.height() / zoom);
            int x = active.left + (active.width() - w) / 2, y = active.top + (active.height() - h) / 2;
            req.set(CaptureRequest.SCALER_CROP_REGION, new Rect(x, y, x + w, y + h));
        }
    }

    /** Tap: focus again on what is in the middle now. */
    void focus() {
        if (req == null || ses == null || bg == null) return;
        bg.post(() -> {
            try {
                if (req == null || ses == null) return;
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                ses.capture(req.build(), null, bg);
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                repeat();
                bg.postDelayed(() -> { // back to following by itself
                    if (req == null) return;
                    req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    repeat();
                }, 2500);
            } catch (Exception ignored) {}
        });
    }

    /** The texture is sized to the camera's own shape and centred, so the screen shows it without stretching (edges cut). */
    private void fit() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0 || size == null) return;
        boolean turned = sensorDeg == 90 || sensorDeg == 270;
        float pw = turned ? size.getHeight() : size.getWidth(), ph = turned ? size.getWidth() : size.getHeight();
        float s = Math.max(W / pw, H / ph);
        int tw = Math.round(pw * s), th = Math.round(ph * s);
        LayoutParams lp = (LayoutParams) tex.getLayoutParams();
        if (lp.width != tw || lp.height != th) {
            lp.width = tw;
            lp.height = th;
            lp.gravity = Gravity.CENTER;
            tex.setLayoutParams(lp);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        main.post(() -> { fit(); applyDigital(); });
    }

    /**
     * What he sees now, as a picture at most maxDim on its long side (main thread; null when the camera is not showing).
     * It is exactly the screen's camera area.
     */
    Bitmap frame(int maxDim) { return frame(maxDim, true); }

    /** A small picture of what the screen shows, quickly, for the camera's own finder (a few times a second; not sharpened for Jarvis's zoom). */
    Bitmap frameFast(int maxDim) { return frame(maxDim, false); }

    private Bitmap fastAll; // (the finder's whole-texture picture, reused)

    private Bitmap frame(int maxDim, boolean sharp) {
        if (!tex.isAvailable() || size == null) return null;
        int W = getWidth(), H = getHeight(), tw = tex.getWidth(), th = tex.getHeight();
        if (W == 0 || H == 0 || tw == 0 || th == 0) return null;
        boolean turned = sensorDeg == 90 || sensorDeg == 270;
        float bufH = turned ? size.getWidth() : size.getHeight(); // the camera's own height in pixels (portrait)
        float s = Math.min(bufH / th, maxDim * (sharp ? digital : 1f) / (float) Math.max(W, H)); // (with Jarvis's zoom: the part shown, as sharp as it can be)
        s = Math.max(0.05f, s);
        int bw = Math.max(1, Math.round(tw * s)), bh = Math.max(1, Math.round(th * s));
        Bitmap all;
        try {
            if (sharp) all = tex.getBitmap(bw, bh);
            else {
                if (fastAll == null || fastAll.isRecycled() || fastAll.getWidth() != bw || fastAll.getHeight() != bh) fastAll = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                all = tex.getBitmap(fastAll);
            }
        } catch (Exception e) { return null; }
        if (all == null) return null;
        float left = (tw - W) / 2f + (cx - 0.5f / digital) * W, top = (th - H) / 2f + (cy - 0.5f / digital) * H; // the part on the screen, in the texture's own pixels
        int x = Math.max(0, Math.round(left * s)), y = Math.max(0, Math.round(top * s));
        int w = Math.min(bw - x, Math.round(W / digital * s)), h = Math.min(bh - y, Math.round(H / digital * s));
        if (!sharp) { // (the reused picture stays here: a copy of the part goes out)
            if (w <= 0 || h <= 0) return all.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap cut = Bitmap.createBitmap(all, x, y, w, h);
            return cut == all ? all.copy(Bitmap.Config.ARGB_8888, false) : cut;
        }
        if (w <= 0 || h <= 0) return all;
        Bitmap cut = Bitmap.createBitmap(all, x, y, w, h);
        if (cut != all) all.recycle();
        return cut;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { if (wanted) start(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { stopCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
}
