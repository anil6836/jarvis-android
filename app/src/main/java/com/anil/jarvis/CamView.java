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
                if (r != null) { ratioZoom = true; maxZoom = Math.min(10f, r.getUpper()); minZoom = Math.max(1f, r.getLower()); }
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
            applyZoom();
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

    /** Zoom (1 = none), within what this camera can. */
    void setZoom(float z) {
        zoom = Math.max(minZoom, Math.min(maxZoom, z));
        if (bg == null) return;
        bg.post(() -> { if (req != null) { applyZoom(); repeat(); } });
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
        main.post(this::fit);
    }

    /**
     * What he sees now, as a picture at most maxDim on its long side (main thread; null when the camera is not showing).
     * It is exactly the screen's camera area.
     */
    Bitmap frame(int maxDim) {
        if (!tex.isAvailable() || size == null) return null;
        int W = getWidth(), H = getHeight(), tw = tex.getWidth(), th = tex.getHeight();
        if (W == 0 || H == 0 || tw == 0 || th == 0) return null;
        boolean turned = sensorDeg == 90 || sensorDeg == 270;
        float bufH = turned ? size.getWidth() : size.getHeight(); // the camera's own height in pixels (portrait)
        float s = Math.min(bufH / th, maxDim / (float) Math.max(W, H));
        s = Math.max(0.05f, s);
        int bw = Math.max(1, Math.round(tw * s)), bh = Math.max(1, Math.round(th * s));
        Bitmap all;
        try { all = tex.getBitmap(bw, bh); } catch (Exception e) { return null; }
        if (all == null) return null;
        int left = (tw - W) / 2, top = (th - H) / 2; // the part on the screen, in the texture's own pixels
        int x = Math.max(0, Math.round(left * s)), y = Math.max(0, Math.round(top * s));
        int w = Math.min(bw - x, Math.round(W * s)), h = Math.min(bh - y, Math.round(H * s));
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
