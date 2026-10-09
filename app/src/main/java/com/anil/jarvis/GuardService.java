package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The guard's eyes (see {@link Guard}): the back camera at a small size and a low frame rate, looked at about every
 * 1.5 seconds. Motion = many parts of the picture changing brightness twice in a row (one flicker is ignored). Then
 * Jarvis checks the picture with the AI he chose (20 seconds at most), and a person, an animal or a vehicle (or
 * anything, when the AI can't be asked) goes to his Telegram; after a sent photo it waits 2 minutes, after a
 * "nothing there" 15 seconds. If the camera is taken away or fails, it tries again (and the regular check tells him
 * if it stays stopped). A foreground service with a notification and a stop button; the phone stays on its charger.
 */
public class GuardService extends Service {
    private static final int NOTE = 271, GRID_W = 40, GRID_H = 30;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private android.os.PowerManager.WakeLock lock;
    private int[] last;
    private long lastLook, quietUntil, started, lastBeat;
    private int moving, retries;
    private volatile boolean checking, destroyed, opening;
    private final ExecutorService ai = Executors.newSingleThreadExecutor();
    private java.util.concurrent.ScheduledExecutorService poll;
    /** The guard now running (for a picture of the moment a house sound was heard). */
    private static volatile GuardService self;
    /** Alerts waiting for the next picture as a JPEG (two sounds at once each get it). */
    private final java.util.List<java.util.function.Consumer<byte[]>> snapWant =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * The camera's next picture as a JPEG, for a house sound's alert ({@link HomeGuard}); null when the camera isn't running
     * or nothing came within waitMs. Blocks: never on the main thread.
     */
    static byte[] snap(long waitMs) {
        GuardService g = self;
        if (g == null || g.camera == null) return null;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final byte[][] out = new byte[1][];
        java.util.function.Consumer<byte[]> me = jpeg -> { out[0] = jpeg; done.countDown(); };
        g.snapWant.add(me);
        try { done.await(waitMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        g.snapWant.remove(me);
        return out[0];
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Guard.running(this)) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("jarvis_guard", "కాపలా మోడ్", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getBroadcast(this, 272, new Intent(this, AlarmReceiver.class).setAction(Guard.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent talk = PendingIntent.getActivity(this, 273, new Intent(this, HomeTalkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "jarvis_guard").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("🛡️ కాపలా మోడ్ ఆన్").setContentText("కదలిక కనిపిస్తే మీ Telegram కి ఫోటో వస్తుంది").setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "🎤 " + new Prefs(this).name() + " కి చెప్పు", talk).build()) // W65: a voice clip to his phone
                .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", stop).build()).build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(NOTE, n);
        } catch (Exception e) { // Android did not allow the camera now (e.g. restarted in the background): say so, here and on Telegram
            Reminders.notify(this, "🛡️ కాపలా మోడ్ మొదలవలేదు", "Jarvis తెరిచి సెట్టింగ్స్ → కాపలా మోడ్ లో మళ్ళీ మొదలుపెట్టండి.", NOTE + 1);
            new Thread(() -> Guard.send(getApplicationContext(), "⚠️ Jarvis కాపలా మొదలవలేదు. ఇంట్లో ఫోన్‌లో Jarvis తెరిచి మళ్ళీ మొదలుపెట్టండి.")).start();
            Guard.stop(this);
            return START_NOT_STICKY;
        }
        if (lock == null) {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jarvis:guard");
            lock.acquire(); // held for as long as the guard runs (a 48-hour duty); let go in onDestroy
            thread = new HandlerThread("jarvis-guard");
            thread.start();
            bg = new Handler(thread.getLooper());
            started = SystemClock.elapsedRealtime();
        }
        if (camera == null && !opening) bg.post(this::open);
        self = this;
        if (poll == null) { // W41 / W64 / W65: his main phone's words through the bot (a pinned message), every 20 seconds
            poll = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            poll.scheduleWithFixedDelay(() -> { try { if (!destroyed) HomeLink.homePoll(getApplicationContext()); } catch (Throwable ignored) {} },
                    20, 20, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    private void open() {
        if (destroyed || camera != null || opening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Reminders.notify(this, "🛡️ కాపలా మోడ్", "కెమెరా అనుమతి లేదు. Jarvis కి కెమెరా అనుమతి ఇచ్చి మళ్ళీ మొదలుపెట్టండి.", NOTE + 1);
            Guard.stop(this);
            return;
        }
        opening = true;
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String pick = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { pick = id; break; }
                if (pick == null) pick = id;
            }
            if (pick == null) throw new IllegalStateException("no camera");
            CameraCharacteristics ch = cm.getCameraCharacteristics(pick);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = new Size(640, 480);
            if (map != null) {
                long want = 640L * 480, bd = Long.MAX_VALUE;
                for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    long d = Math.abs((long) s.getWidth() * s.getHeight() - want);
                    if (d < bd) { bd = d; best = s; }
                }
            }
            Range<Integer> slow = null; // the lowest frame rate the camera allows: it is looked at only every 1.5 s
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) for (Range<Integer> r : ranges) if (slow == null || r.getUpper() < slow.getUpper()) slow = r;
            final Range<Integer> fps = slow;
            if (reader != null) reader.close();
            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            cm.openCamera(pick, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    opening = false;
                    if (destroyed) { d.close(); return; }
                    camera = d;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try { s.setRepeatingRequest(b.build(), null, bg); retries = 0; } catch (Exception e) { lost(); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { lost(); }
                        }, bg);
                    } catch (Exception e) { lost(); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); if (camera == d) camera = null; opening = false; lost(); }
                @Override public void onError(CameraDevice d, int error) { d.close(); if (camera == d) camera = null; opening = false; lost(); }
            }, bg);
        } catch (Exception e) {
            opening = false;
            lost();
        }
    }

    /** The camera was taken by another app or failed: try again, waiting longer each time (30 s up to 5 min). */
    private void lost() {
        if (destroyed) return;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        last = null;
        long wait = Math.min(300_000L, 30_000L * (1L << Math.min(4, retries++)));
        if (bg != null) bg.postDelayed(this::open, wait);
    }

    /** Every picture comes here; one in 1.5 seconds is looked at, the rest are let go at once. */
    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (!snapWant.isEmpty()) { // a house sound was heard: this moment's picture
                java.util.List<java.util.function.Consumer<byte[]>> want;
                synchronized (snapWant) { want = new java.util.ArrayList<>(snapWant); snapWant.clear(); }
                byte[] pic = null;
                try { pic = jpeg(img); } catch (Exception ignored) {}
                for (java.util.function.Consumer<byte[]> w : want) w.accept(pic);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastLook < 1500) return;
            lastLook = now;
            if (now - lastBeat > 30_000) { // "the camera is working" for Settings and the watchdog
                lastBeat = now;
                Guard.sp(this).edit().putLong("beat", System.currentTimeMillis()).apply();
            }
            int[] grid = luma(img);
            boolean moved = false;
            if (last != null) {
                int changed = 0;
                for (int i = 0; i < grid.length; i++) if (Math.abs(grid[i] - last[i]) > 18) changed++;
                moved = changed > grid.length * 8 / 100;
            }
            last = grid;
            moving = moved ? moving + 1 : 0;
            if (moving < 2 || now - started < 15_000 || now < quietUntil || checking) return; // settle, twice in a row, not right after an alert
            if (Guard.dutyOnly(this) && !onDuty()) return; // he asked for alerts only while he is away on duty
            if (HomeLink.paused) return; // (W64: he is home: alerts paused from his phone)
            byte[] jpeg = jpeg(img);
            checking = true;
            new Thread(() -> { try { check(jpeg); } finally { checking = false; } }, "jarvis-guard-check").start();
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    private boolean onDuty() {
        Duty.Roster r = Duty.load(this);
        return !Duty.ready(r) || Duty.onDutyAt(r, java.time.LocalDateTime.now()); // no calendar on this phone: always
    }

    /** The average brightness of each cell of a 40 x 30 grid. */
    private static int[] luma(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
        int[] g = new int[GRID_W * GRID_H];
        for (int gy = 0; gy < GRID_H; gy++) {
            for (int gx = 0; gx < GRID_W; gx++) {
                int sum = 0, n = 0;
                int x0 = gx * w / GRID_W, y0 = gy * h / GRID_H, x1 = (gx + 1) * w / GRID_W, y1 = (gy + 1) * h / GRID_H;
                for (int yy = y0; yy < y1; yy += 4) for (int xx = x0; xx < x1; xx += 4) { sum += buf.get(yy * stride + xx) & 0xFF; n++; }
                g[gy * GRID_W + gx] = n == 0 ? 0 : sum / n;
            }
        }
        return g;
    }

    /** The picture as a JPEG (YUV_420_888 -> NV21 -> JPEG). */
    private static byte[] jpeg(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        int ys = p[0].getRowStride();
        for (int r = 0; r < h; r++) { yb.position(r * ys); yb.get(nv21, r * w, w); }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int us = p[1].getRowStride(), up = p[1].getPixelStride(), vs = p[2].getRowStride(), vp = p[2].getPixelStride();
        int o = w * h;
        for (int r = 0; r < h / 2; r++) {
            for (int col = 0; col < w / 2; col++) {
                nv21[o++] = vb.get(r * vs + col * vp);
                nv21[o++] = ub.get(r * us + col * up);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 80, out);
        return out.toByteArray();
    }

    /** The AI says what moved (20 s at most); a person, an animal or a vehicle (or when it can't tell) goes to his Telegram. */
    private void check(byte[] jpeg) {
        String what = "కదలిక కనిపించింది";
        boolean send = true;
        Prefs p = new Prefs(this);
        if (!p.apiKey().isEmpty()) {
            Future<String> f = ai.submit(() -> Brain.oneShot(p, "You check a home security camera picture. Reply with JSON only.",
                    "Is there a person, an animal or a vehicle in this picture? JSON: {\"alert\": true/false, \"what\": \"one short Telugu line on what is seen and where\"}. "
                            + "Light changes, curtains, shadows, rain alone = false.",
                    android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), false, 300));
            try {
                String r = f.get(20, TimeUnit.SECONDS);
                int s = r.indexOf('{'), e = r.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JSONObject o = new JSONObject(r.substring(s, e + 1));
                    send = o.optBoolean("alert", true);
                    if (!o.optString("what").trim().isEmpty()) what = o.optString("what").trim();
                }
            } catch (Exception e) { f.cancel(true); } // slow or failed: send it anyway (better a needless photo than a missed one)
        }
        if (!send) { quietUntil = SystemClock.elapsedRealtime() + 15_000; return; } // nothing there: look again soon
        String time = new java.text.SimpleDateFormat("h:mm a, d MMM", Locale.ENGLISH).format(new java.util.Date());
        boolean sent = Guard.sendPhoto(this, jpeg, "🚨 Jarvis కాపలా: " + what + " · " + time);
        quietUntil = SystemClock.elapsedRealtime() + (sent ? 120_000 : 15_000); // not sent: try with the next movement
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (self == this) self = null;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        ai.shutdownNow();
        if (poll != null) poll.shutdownNow();
        if (lock != null && lock.isHeld()) lock.release();
        super.onDestroy();
    }
}
