package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * "✋ గాలిలో": the front camera watches for a hand waved in the air in front of the phone (left / right / up / down), so
 * the hologram turns without touching the screen. Only movement is measured on small grey pictures, on the phone;
 * nothing is saved or sent. An experiment: in poor light or with a busy background it may miss or mistake a wave.
 */
final class AirGesture {
    interface Listener { void swipe(int dir); }
    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    private static final int GW = 40, GH = 30;
    private final Context ctx;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private ImageReader reader;
    private int sensorDeg = 270;
    private int[] prev;
    private boolean moving;
    private float startX, startY, lastX, lastY;
    private long startT, lastFire;
    private volatile boolean on;

    AirGesture(Context c, Listener l) { ctx = c.getApplicationContext(); this.l = l; }

    boolean isOn() { return on; }

    @SuppressLint("MissingPermission")
    boolean start() {
        if (on) return true;
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String id = null;
        try {
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) { id = c; break; }
            }
            if (id == null) return false;
            Integer so = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorDeg = so == null ? 270 : so;
        } catch (Exception e) {
            return false;
        }
        on = true;
        prev = null;
        moving = false;
        thread = new HandlerThread("jarvis-air");
        thread.start();
        bg = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img != null) frame(img);
            } catch (Exception ignored) {
            } finally {
                if (img != null) img.close();
            }
        }, bg);
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!on) { d.close(); return; }
                    dev = d;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        //noinspection deprecation
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                if (!on) { s.close(); return; }
                                ses = s;
                                try { s.setRepeatingRequest(b.build(), null, bg); } catch (Exception ignored) {}
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {}
                        }, bg);
                    } catch (Exception ignored) {}
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); }
            }, bg);
        } catch (Exception e) {
            stop();
            return false;
        }
        return true;
    }

    void stop() {
        on = false;
        try { if (ses != null) ses.close(); } catch (Exception ignored) {}
        try { if (dev != null) dev.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        ses = null;
        dev = null;
        reader = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    /** A small grey picture; where it changed since the last one; a wave is the middle of the change travelling across. */
    private void frame(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int rs = y.getRowStride(), ps = y.getPixelStride(), w = img.getWidth(), h = img.getHeight();
        int[] g = new int[GW * GH];
        for (int j = 0; j < GH; j++) {
            int sy = j * h / GH;
            for (int i = 0; i < GW; i++) {
                int sx = i * w / GW;
                int at = sy * rs + sx * ps;
                g[j * GW + i] = at < buf.limit() ? buf.get(at) & 0xff : 0;
            }
        }
        int[] p = prev;
        prev = g;
        if (p == null) return;
        long sumX = 0, sumY = 0;
        int count = 0;
        for (int k = 0; k < g.length; k++) {
            if (Math.abs(g[k] - p[k]) > 28) { sumX += k % GW; sumY += k / GW; count++; }
        }
        long now = System.currentTimeMillis();
        boolean active = count > g.length / 25;
        if (active) {
            float cx = sumX / (float) count / GW, cy = sumY / (float) count / GH;
            if (!moving) { moving = true; startX = cx; startY = cy; startT = now; }
            lastX = cx;
            lastY = cy;
            if (now - startT > 900) moving = false; // too slow to be a wave
            return;
        }
        if (!moving) return;
        moving = false;
        if (now - lastFire < 700) return;
        float dx = lastX - startX, dy = lastY - startY;
        // from the sensor's picture to what he sees (turned upright, then mirrored like a mirror for the front camera)
        float rx, ry;
        switch (sensorDeg) {
            case 90: rx = -dy; ry = dx; break;
            case 180: rx = -dx; ry = -dy; break;
            case 270: rx = dy; ry = -dx; break;
            default: rx = dx; ry = dy;
        }
        rx = -rx;
        float ax = Math.abs(rx), ay = Math.abs(ry);
        if (Math.max(ax, ay) < 0.3f) return;
        lastFire = now;
        final int dir = ax > ay ? (rx > 0 ? RIGHT : LEFT) : (ry > 0 ? DOWN : UP);
        main.post(() -> { if (on) l.swipe(dir); });
    }
}
