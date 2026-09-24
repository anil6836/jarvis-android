package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.view.Surface;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

/**
 * The "Live camera": a back-camera preview inside Jarvis. About once a second the
 * current picture is kept as a small JPEG so Jarvis can look at what the camera sees.
 */
final class CameraPanel implements TextureView.SurfaceTextureListener {
    /** Newest camera picture (base64 JPEG), or null when the camera is closed. */
    static volatile String latestFrame;

    private final Activity act;
    final TextureView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private boolean wanted;

    CameraPanel(Activity act) {
        this.act = act;
        view = new TextureView(act);
        view.setSurfaceTextureListener(this);
    }

    boolean isOpen() { return wanted; }

    void open() {
        if (wanted) return;
        if (act.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        wanted = true;
        thread = new HandlerThread("jarvis-camera");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (view.isAvailable()) startCamera();
        main.postDelayed(grab, 1200);
    }

    void close() {
        wanted = false;
        latestFrame = null;
        main.removeCallbacks(grab);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    private final Runnable grab = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            if (view.isAvailable() && camera != null) {
                Bitmap b = view.getBitmap(480, 640);
                if (b != null && bg != null) {
                    bg.post(() -> {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.JPEG, 70, out);
                        b.recycle();
                        if (wanted) latestFrame = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    });
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void startCamera() {
        if (!wanted || camera != null) return;
        CameraManager cm = act.getSystemService(CameraManager.class);
        try {
            String pick = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { pick = id; break; }
                if (pick == null) pick = id;
            }
            if (pick == null) return;
            cm.openCamera(pick, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (!wanted) { c.close(); return; }
                    camera = c;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                @Override public void onError(CameraDevice c, int error) { c.close(); camera = null; }
            }, bg);
        } catch (CameraAccessException | SecurityException e) {
            wanted = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void startPreview() {
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(640, 480);
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

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { if (wanted) startCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { close(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
