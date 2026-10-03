package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
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
import android.util.Base64;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Jarvis's eyes: the front camera, only while the home screen with his face is on the screen (and he switched it
 * on). A few times a second the phone itself finds faces in the picture (Google ML Kit, on the phone): the face on
 * the screen looks at Anil and smiles back when he smiles. About once a second, people Anil introduced are recognised
 * by their face fingerprints (FaceNet, on the phone). When "see me" is on, the newest picture (only while a face is
 * in view) goes with his next question to the AI he chose. Nothing is recorded or saved; after 10 minutes with no one
 * in front of the phone the camera switches itself off.
 */
final class FaceSight {
    interface Listener {
        /** Someone in front (x, y: where, -1..1 as he sees the screen; smile 0..1), or no one. Main thread. */
        void onSight(boolean present, float x, float y, float smile);
        /** A person Anil introduced has just come into view. Main thread. */
        void onKnown(String name, boolean owner);
        /** The camera stopped by itself: no one for a long time (idle), or it could not run (why is shown to him). */
        void onSightStopped(String why, boolean idle);
    }

    // ---- settings (all switch at once)
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE); }
    static boolean faceOn(Context c) { return sp(c).getBoolean("face_on", true); }
    static boolean holo(Context c) { return sp(c).getBoolean("face_holo", false); }
    static boolean big(Context c) { return sp(c).getBoolean("face_big", true); }
    static boolean camOn(Context c) { return sp(c).getBoolean("face_cam", false); }
    static boolean seeMe(Context c) { return sp(c).getBoolean("face_seeme", false); }
    /** He has answered the one-time question about the camera (until then it stays off). */
    static boolean asked(Context c) { return sp(c).getBoolean("face_asked", false); }
    static boolean greetOn(Context c) { return sp(c).getBoolean("face_greet", true); }
    static void set(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }

    // ---- what the camera sees now (read by the brain and by "see me")
    static volatile FaceSight current;
    /** Set by the home screen: starts the camera again if it went to sleep (for "remember me", "look at me"). */
    static volatile Runnable wake;

    static void wakeUp() { Runnable w = wake; if (w != null) w.run(); }

    /** Forgets that a person was just seen (after "forget"). */
    static void forgetSeen(String name) {
        synchronized (seenAt) { seenAt.keySet().removeIf(k -> k.equalsIgnoreCase(name)); }
        synchronized (lastConfirmed) { lastConfirmed.keySet().removeIf(k -> k.equalsIgnoreCase(name)); }
        synchronized (firstHit) { firstHit.keySet().removeIf(k -> k.equalsIgnoreCase(name)); }
    }
    private static volatile String latestJpeg;
    private static volatile long jpegAt, faceAt;
    private static final Map<String, Long> seenAt = Collections.synchronizedMap(new HashMap<>());
    private static volatile int unknownNow;
    private static volatile long unknownAt;
    /** How alike the last face was to the closest person he introduced (shown in Settings, to judge it). */
    static volatile String lastScore = "";

    /** His picture from a moment ago, while a face is in view (base64 JPEG), or null. */
    static String picture() {
        long now = SystemClock.elapsedRealtime();
        return current != null && now - jpegAt < 3000 && now - faceAt < 2000 ? latestJpeg : null;
    }

    /** Who is in front of the phone now, for the brain ("Anil, Ramu; 1 person he has not introduced"), or "". */
    static String whoNow() {
        if (current == null) return "";
        long now = SystemClock.elapsedRealtime();
        if (now - faceAt > 3000) return "";
        StringBuilder b = new StringBuilder();
        synchronized (seenAt) {
            for (Map.Entry<String, Long> e : seenAt.entrySet())
                if (now - e.getValue() < 15000) b.append(b.length() > 0 ? ", " : "").append(e.getKey());
        }
        int u = now - unknownAt < 5000 ? unknownNow : 0;
        if (u > 0) b.append(b.length() > 0 ? "; " : "").append(u).append(u == 1 ? " person he has not introduced" : " people he has not introduced");
        if (b.length() == 0) b.append("someone (not recognised yet)");
        return b.toString();
    }

    private final Activity act;
    private final Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run run;                       // the camera now (a new one each time it starts)
    private volatile Enroll enroll;
    // kept across camera runs (so opening Jarvis again is not a new "arrival")
    private static final Map<String, Long> firstHit = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Long> lastConfirmed = Collections.synchronizedMap(new HashMap<>());

    FaceSight(Activity act, Listener l) {
        this.act = act;
        this.l = l;
    }

    boolean isOn() { return run != null; }

    static boolean allowed(Context c) { return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    /** Starts the front camera (main thread). */
    void start() {
        if (run != null || !allowed(act)) return;
        int disp = 0;
        try {
            @SuppressWarnings("deprecation") int r = act.getWindowManager().getDefaultDisplay().getRotation();
            disp = r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
        } catch (Exception ignored) {}
        current = this;
        latestJpeg = null;
        run = new Run(disp);
    }

    /** Stops and waits (up to ms) until the front camera is really closed: before the back camera opens. */
    void stopAndWait(long ms) {
        Run r = run;
        stop();
        if (r != null) { try { r.closed.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {} }
    }

    /** Stops the camera and forgets the picture (main thread). */
    void stop() {
        Run r = run;
        run = null;
        if (current == this) current = null;
        latestJpeg = null;
        Enroll en = enroll;
        if (en != null) finish(en, "stopped");
        if (r != null) r.end();
    }

    private void stopBecause(Run r, String why) { stopBecause(r, why, false); }

    private void stopBecause(Run r, String why, boolean idle) {
        main.post(() -> {
            if (run != r) return;
            stop();
            l.onSightStopped(why, idle);
        });
    }

    /** One time the camera is on: its own thread, camera, reader and face finder. */
    private final class Run {
        volatile boolean alive = true;
        final HandlerThread thread = new HandlerThread("jarvis-face");
        final Handler h;
        final Executor exec;
        CameraDevice cam;
        CameraCaptureSession session;
        ImageReader reader;
        FaceDetector detector;
        FaceNet net;
        boolean netFailed, busy;
        int rotation, fails;
        long lastFrame, lastBitmap, lastFace = SystemClock.elapsedRealtime();

        Run(int displayDeg) {
            thread.start();
            h = new Handler(thread.getLooper());
            exec = h::post;
            h.post(() -> open(displayDeg));
        }

        final CountDownLatch closed = new CountDownLatch(1);

        void end() {
            alive = false;
            h.post(() -> {
                try { if (session != null) session.close(); } catch (Exception ignored) {}
                try { if (cam != null) cam.close(); } catch (Exception ignored) {}
                session = null;
                cam = null;
                closed.countDown(); // the camera itself is free now (the back camera may open)
                if (!busy) closeAll();
                else h.postDelayed(this::closeAll, 1500); // ML Kit is still reading a picture: closed when it finishes (or after 1.5 s)
            });
        }

        /** Frees the reader, the face finder and the model (once ML Kit is done with the last picture). */
        void closeAll() {
            if (reader == null && detector == null && net == null && !thread.isAlive()) return;
            try { if (detector != null) detector.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            if (net != null) net.close();
            reader = null;
            detector = null;
            net = null;
            thread.quitSafely();
        }

        @SuppressWarnings("MissingPermission")
        void open(int displayDeg) {
            if (!alive) return;
            try {
                detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setMinFaceSize(0.12f)
                        .build());
                CameraManager cm = act.getSystemService(CameraManager.class);
                String pick = null;
                int sensor = 270;
                Size size = null;
                Range<Integer> fps = null;
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                    StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) continue;
                    pick = id;
                    Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (so != null) sensor = so;
                    size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
                    Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (ranges != null) for (Range<Integer> r : ranges) // the slowest steady rate of 10+ (saves battery)
                        if (r.getUpper() >= 10 && (fps == null || r.getUpper() < fps.getUpper() || (r.getUpper().equals(fps.getUpper()) && r.getLower() > fps.getLower()))) fps = r;
                    break;
                }
                if (pick == null || size == null) { stopBecause(this, "ఈ ఫోన్‌లో ముందు కెమెరా దొరకలేదు"); return; }
                rotation = (sensor + displayDeg) % 360; // ML Kit's rule for a front camera
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                reader.setOnImageAvailableListener(this::onImage, h);
                final Range<Integer> rate = fps;
                cm.openCamera(pick, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice c) {
                        if (!alive) { c.close(); return; }
                        cam = c;
                        session(rate);
                    }
                    @Override public void onDisconnected(CameraDevice c) {
                        c.close();
                        if (cam == c) cam = null;
                        stopBecause(Run.this, "ముందు కెమెరాని ఇంకో యాప్ తీసుకుంది");
                    }
                    @Override public void onError(CameraDevice c, int error) {
                        c.close();
                        if (cam == c) cam = null;
                        stopBecause(Run.this, "ముందు కెమెరా తెరవలేకపోయాను");
                    }
                }, h);
            } catch (Throwable e) {
                stopBecause(this, "ముందు కెమెరా తెరవలేకపోయాను");
            }
        }

        @SuppressWarnings("deprecation")
        void session(Range<Integer> fps) {
            try {
                final Surface s = reader.getSurface();
                final CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                rb.addTarget(s);
                if (fps != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                cam.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession cs) {
                        if (!alive || cam == null) { cs.close(); return; }
                        session = cs;
                        try { cs.setRepeatingRequest(rb.build(), null, h); } catch (Exception e) { stopBecause(Run.this, "ముందు కెమెరా తెరవలేకపోయాను"); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession cs) { stopBecause(Run.this, "ముందు కెమెరా తెరవలేకపోయాను"); }
                }, h);
            } catch (Exception e) {
                stopBecause(this, "ముందు కెమెరా తెరవలేకపోయాను");
            }
        }

        void onImage(ImageReader r) {
            final Image img;
            try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            long now = SystemClock.elapsedRealtime();
            if (!alive || busy || detector == null || now - lastFrame < 180) { img.close(); return; }
            busy = true;
            lastFrame = now;
            Enroll en = enroll;
            boolean want = now - lastBitmap > (en != null ? 350 : 900) && (en != null || People.count(act) > 0 || seeMe(act));
            Bitmap bmp = null;
            if (want) {
                try { bmp = upright(img, rotation); lastBitmap = now; } catch (Throwable t) { bmp = null; }
            }
            final Bitmap frame = bmp;
            final int iw = rotation % 180 == 0 ? img.getWidth() : img.getHeight(), ih = rotation % 180 == 0 ? img.getHeight() : img.getWidth();
            final InputImage in;
            try {
                in = InputImage.fromMediaImage(img, rotation);
            } catch (Exception e) {
                img.close();
                recycle(frame);
                busy = false;
                return;
            }
            try {
                detector.process(in)
                        .addOnSuccessListener(exec, faces -> {
                            img.close();
                            try { if (alive) handle(faces, frame, iw, ih); } catch (Throwable ignored) {} finally { recycle(frame); busy = false; }
                            if (!alive) closeAll();
                        })
                        .addOnFailureListener(exec, e -> {
                            img.close();
                            recycle(frame);
                            busy = false;
                            if (!alive) { closeAll(); return; }
                            // the face model may still be on its way from Google Play services: keep trying a while, then stop
                            if (++fails >= 60) stopBecause(Run.this, "ముఖాలు చూసే Google మోడల్ ఇంకా ఫోన్‌కి రాలేదు. కాసేపాగి నా ముఖం మీద నొక్కండి.");
                        });
            } catch (Throwable t) {
                img.close();
                recycle(frame);
                busy = false;
                if (++fails >= 60) stopBecause(this, "ముఖాలు చూసే Google మోడల్ పనిచేయడం లేదు.");
            }
        }

        void handle(List<Face> faces, Bitmap frame, int iw, int ih) {
            fails = 0;
            long now = SystemClock.elapsedRealtime();
            Face best = null;
            float area = 0;
            for (Face f : faces) {
                Rect b = f.getBoundingBox();
                float a = (float) b.width() * b.height();
                if (a > area) { area = a; best = f; }
            }
            if (best != null) {
                lastFace = now;
                faceAt = now;
                Rect b = best.getBoundingBox();
                float cx = b.exactCenterX() / iw, cy = b.exactCenterY() / ih;
                final float x = FaceRig.clamp(-(cx - 0.5f) * 3.2f, -1, 1), y = FaceRig.clamp((cy - 0.45f) * 2.4f, -1, 1); // mirrored: as he sees the screen
                Float sp = best.getSmilingProbability();
                final float smile = sp == null ? 0f : sp;
                main.post(() -> { if (run == Run.this) l.onSight(true, x, y, smile); });
            } else {
                main.post(() -> { if (run == Run.this) l.onSight(false, 0, 0, 0); });
            }
            if (frame != null) {
                Enroll en = enroll;
                if (en != null) enrollStep(this, en, faces, frame, iw);
                else if (!faces.isEmpty() && People.count(act) > 0) recognize(this, faces, frame, iw);
                if (best != null && seeMe(act) && alive) keepPicture(frame);
            }
            if (now - lastFace > 10 * 60_000L && enroll == null)
                stopBecause(this, "10 నిమిషాలు ఎవరూ కనిపించలేదు, కెమెరా ఆపాను. నా ముఖం మీద నొక్కితే మళ్ళీ చూస్తాను.", true);
        }

        FaceNet net() {
            if (net != null) return net;
            if (netFailed || !FaceNet.ready(act)) return null;
            try { net = new FaceNet(act); } catch (Throwable t) { netFailed = true; }
            return net;
        }
    }

    /** 640x480 if it can, else the smallest 4:3-ish size that is still big enough. */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size z : sizes) {
            int w = Math.max(z.getWidth(), z.getHeight()), h = Math.min(z.getWidth(), z.getHeight());
            if (w == 640 && h == 480) return z;
            if (w < 480 || h < 320 || w > 1280) continue;
            if (best == null || w * h < best.getWidth() * best.getHeight()) best = z;
        }
        if (best == null) {
            for (Size z : sizes) if (best == null || z.getWidth() * z.getHeight() < best.getWidth() * best.getHeight()) best = z;
        }
        return best;
    }

    // ================================================================ knowing people

    /** A clear, front-facing face, big enough to recognise. */
    private static boolean usable(Face f, int iw) {
        return f.getBoundingBox().width() >= iw * 0.12f && Math.abs(f.getHeadEulerAngleY()) <= 28 && Math.abs(f.getHeadEulerAngleZ()) <= 25;
    }

    private void recognize(Run r, List<Face> faces, Bitmap frame, int iw) {
        FaceNet n = r.net();
        if (n == null) return;
        List<People.Person> people = People.all(act);
        long now = SystemClock.elapsedRealtime();
        int unknown = 0, k = 0;
        for (Face f : faces) {
            if (k++ >= 3) break;
            if (!usable(f, iw)) continue;
            Bitmap c = crop(frame, f.getBoundingBox());
            if (c == null) continue;
            float[] e = n.embed(c);
            c.recycle();
            People.Match m = People.match(people, e);
            lastScore = String.format(java.util.Locale.ENGLISH, "%.2f", Math.max(0, m.score)) + (m.name != null ? " (" + m.name + ")" : "");
            if (m.name == null) { unknown++; continue; }
            Long first = firstHit.get(m.name);
            if (first == null || now - first > 5000) { firstHit.put(m.name, now); continue; } // twice within 5 s to be sure
            firstHit.remove(m.name);
            Long before = lastConfirmed.put(m.name, now);
            seenAt.put(m.name, now);
            if (before == null || now - before > 120_000L) { // just arrived (not seen for 2 minutes)
                final String name = m.name;
                final boolean owner = m.owner;
                main.post(() -> { if (run == r) l.onKnown(name, owner); });
            }
        }
        unknownNow = unknown;
        unknownAt = now;
    }

    /** A square around the face, cut from the picture. */
    private static Bitmap crop(Bitmap frame, Rect box) {
        int side = Math.min(Math.max(box.width(), box.height()), Math.min(frame.getWidth(), frame.getHeight()));
        if (side < 24) return null;
        int x = Math.max(0, Math.min(frame.getWidth() - side, box.centerX() - side / 2));
        int y = Math.max(0, Math.min(frame.getHeight() - side, box.centerY() - side / 2));
        Bitmap sq = Bitmap.createBitmap(frame, x, y, side, side);
        Bitmap out = Bitmap.createScaledBitmap(sq, FaceNet.IN, FaceNet.IN, true);
        if (sq != out && sq != frame) sq.recycle();
        return out;
    }

    private static final class Enroll {
        final String name;
        final boolean owner;
        final long from, until;
        int steady;   // frames in a row with one clear face
        final List<float[]> got = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;
        String why = "no_face";
        Enroll(String name, boolean owner, long from, long until) { this.name = name; this.owner = owner; this.from = from; this.until = until; }
    }

    /**
     * Learns the one face in front of the camera under this name (from a worker thread; waits up to ~15 s).
     * Returns "ok", "same_as:<name>", or why not: no_face, many_faces, not_frontal, no_model, stopped, timeout.
     */
    String learn(String name, boolean owner) {
        if (run == null) return "stopped";
        long now = SystemClock.elapsedRealtime();
        Enroll en = new Enroll(name, owner, now + 1800, now + 14000); // a moment to turn the phone to the person first
        enroll = en;
        try {
            if (!en.done.await(18, TimeUnit.SECONDS)) { if (enroll == en) enroll = null; return "timeout"; }
        } catch (InterruptedException e) {
            if (enroll == en) enroll = null;
            return "stopped";
        }
        return en.result;
    }

    private void enrollStep(Run r, Enroll en, List<Face> faces, Bitmap frame, int iw) {
        long now = SystemClock.elapsedRealtime();
        if (now > en.until) { finish(en, en.got.size() >= 3 ? save(en) : en.why); return; }
        if (now < en.from) return;
        if (faces.size() != 1) { en.why = faces.isEmpty() ? "no_face" : "many_faces"; en.steady = 0; en.got.clear(); return; }
        Face f = faces.get(0);
        if (!usable(f, iw)) { en.why = "not_frontal"; en.steady = 0; return; }
        if (++en.steady < 2) return; // the same one face for a moment before the pictures count
        FaceNet n = r.net();
        if (n == null) { finish(en, "no_model"); return; }
        Bitmap c = crop(frame, f.getBoundingBox());
        if (c == null) return;
        en.got.add(n.embed(c));
        c.recycle();
        if (en.got.size() >= 5) finish(en, save(en));
    }

    private String save(Enroll en) {
        float[] mean = People.mean(en.got);
        for (People.Person p : People.all(act)) {
            float s = People.best(p, mean);
            boolean same = p.name.equalsIgnoreCase(en.name) || (en.owner && p.owner);
            if (same && s < People.SAME - 0.1f) return en.owner && p.owner ? "not_owner" : "not_same:" + p.name; // another face under a known name
            if (!same && s >= People.SAME) return "same_as:" + p.name;
        }
        People.add(act, en.name, en.owner, en.got);
        long now = SystemClock.elapsedRealtime();
        lastConfirmed.put(en.name, now); // just met: no "hello" for this visit
        seenAt.put(en.name, now);
        return "ok";
    }

    private void finish(Enroll en, String result) {
        en.result = result;
        if (enroll == en) enroll = null;
        en.done.countDown();
    }

    // ================================================================ pictures

    private static void keepPicture(Bitmap frame) {
        float s = 480f / Math.max(frame.getWidth(), frame.getHeight());
        Bitmap small = s < 1 ? Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * s), Math.round(frame.getHeight() * s), true) : frame;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 65, out);
        if (small != frame) small.recycle();
        latestJpeg = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        jpegAt = SystemClock.elapsedRealtime();
    }

    private static void recycle(Bitmap b) { if (b != null && !b.isRecycled()) b.recycle(); }

    /** The camera picture (YUV) as an upright colour picture (as the camera sees it, not mirrored). */
    private static Bitmap upright(Image img, int rot) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride(), uvRow = pl[1].getRowStride(), uvPix = pl[1].getPixelStride();
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int yo = y * yRow, uvo = (y >> 1) * uvRow, row = y * w;
            for (int x = 0; x < w; x++) {
                int Y = (yb.get(yo + x * yPix) & 0xFF) - 16;
                int uv = uvo + (x >> 1) * uvPix;
                int U = (ub.get(uv) & 0xFF) - 128, V = (vb.get(uv) & 0xFF) - 128;
                int c = 1192 * Math.max(0, Y);
                int r = (c + 1634 * V) >> 10, g = (c - 833 * V - 400 * U) >> 10, b = (c + 2066 * U) >> 10;
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                argb[row + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        if (rot == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap up = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
        if (up != bmp) bmp.recycle();
        return up;
    }
}
