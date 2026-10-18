package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 👁️ The car eye check: a camera watches Anil's eyes while he drives, with Google's on-phone face finder (ML Kit) —
 * eyes closed for about 2 seconds, or the head dropped -> {@link CarCare#eyes} rings the alarm. Pictures are only looked
 * at, about 6 a second: never saved, never sent anywhere, never shown to any AI.
 * The camera: the phone's front camera, an outside camera on the phone's USB (when Android offers it as a camera), or a
 * Wi-Fi camera's MJPEG / JPEG link (a night-vision car camera, later). For an outside camera its way up isn't known, so
 * the four turns are tried until his face is found.
 * A foreground service (camera), started only from {@link CarEyesActivity} while it is on screen (Android's rule), so
 * the check goes on when Maps comes back on top.
 */
public class CarEyes extends Service {
    static volatile boolean running, test, watching;
    static volatile String status = "", lastEvent = "";
    /** A small grey picture of what the camera sees, while the check screen is open. */
    static volatile Bitmap preview;
    static final String ACTION_STOP = "com.anil.jarvis.CAR_EYES_STOP";
    private static final int NOTE = 249;
    private static final long GAP_MS = 150;

    static void start(Context c, boolean test) {
        Intent i = new Intent(c, CarEyes.class).putExtra("test", test);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, CarEyes.class)); } catch (Exception ignored) {}
    }

    private HandlerThread thread;
    private Handler h;
    private Executor exec;
    private volatile boolean alive, busy;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private FaceDetector detector;
    private Thread urlThread;
    private final CarSense.Eyes eyes = new CarSense.Eyes();
    private boolean front, outside, rotLocked;
    private int sensor = 270, rotation, fails;
    /** The phone's own turn (degrees, from its motion sensor: right even with auto-rotate off), for the front camera's guess. */
    private volatile int deviceDeg = -1;
    private android.view.OrientationEventListener orient;
    /** Where his (the driver's) face was last (0–1 of the picture): the same face is followed, not just the biggest one. */
    private float faceX = -1, faceY = -1;
    /** The upright picture's size the face finder works on now (its boxes are in it). */
    private volatile int frameW = 1, frameH = 1;
    private long lastFrame, lastFaceAt, rotTriedAt, lastPreview, lastNote, startedAt;
    private static long noFaceTold;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        boolean t = i != null && i.getBooleanExtra("test", false);
        if (running) { // already watching: a real start over a test makes it real
            if (!t) test = false;
            note(status, true); // (each start asks for the foreground again)
            return START_NOT_STICKY;
        }
        test = t;
        status = "కెమెరా తెరుస్తున్నాను…";
        lastEvent = "";
        failed = false;
        if (!note("👁️ కళ్ళ చెక్ మొదలవుతోంది", true)) { failed = true; status = "Android కెమెరాని అనుమతించలేదు: కళ్ళ చెక్ స్క్రీన్ నుంచి మళ్ళీ మొదలుపెట్టండి"; stopSelf(); return START_NOT_STICKY; }
        running = true;
        alive = true;
        startedAt = SystemClock.elapsedRealtime();
        try {
            orient = new android.view.OrientationEventListener(this) {
                @Override public void onOrientationChanged(int o) {
                    if (o == ORIENTATION_UNKNOWN) return;
                    deviceDeg = ((o + 45) / 90 * 90) % 360;
                }
            };
            if (orient.canDetectOrientation()) orient.enable(); else orient = null;
        } catch (Exception e) { orient = null; }
        lastFaceAt = startedAt;
        thread = new HandlerThread("jarvis-car-eyes");
        thread.start();
        h = new Handler(thread.getLooper());
        exec = h::post;
        h.post(this::open);
        return START_NOT_STICKY;
    }

    private boolean note(String text, boolean foreground) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_car_eyes", "కారు కళ్ళ చెక్", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 249, new Intent(this, CarEyes.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent open = PendingIntent.getActivity(this, 250, new Intent(this, CarEyesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "jarvis_car_eyes").setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("👁️ కళ్ళ చెక్ ఆన్ (ఫోన్‌లోనే, ఏదీ సేవ్ / పంపదు)").setContentText(text).setOngoing(true).setShowWhen(false)
                    .setOnlyAlertOnce(true).setContentIntent(open)
                    .addAction(new Notification.Action.Builder(null, "⏹ ఆపు", stop).build()).build();
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
                else startForeground(NOTE, n);
            } else nm.notify(NOTE, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The status line, also in the notification (not more than every 5 seconds). */
    private void say(String s) {
        status = s;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNote > 5000) { lastNote = now; note(s, false); }
    }

    private volatile boolean failed;
    private int urlMisses;

    private void fail(String why) {
        if (failed) return; // (said once: a second failure on the way down is the same stop)
        failed = true;
        status = why;
        if (!test && CarCare.on(this)) Announcer.sayPhone(this, "కళ్ళ చెక్ ఆగింది: " + why + ". మాటల చెక్ కొనసాగుతుంది.");
        alive = false;
        new Handler(getMainLooper()).post(this::stopSelf);
    }

    // ---------------------------------------------------------------- opening the camera

    @SuppressWarnings("MissingPermission")
    private void open() {
        if (!alive) return;
        try {
            detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setMinFaceSize(0.15f)
                    .build());
        } catch (Throwable e) {
            fail("ముఖాలు చూసే Google మోడల్ పనిచేయడం లేదు");
            return;
        }
        String src = CarCare.eyesSource(this);
        if ("url".equals(src)) {
            outside = true;
            String url = CarCare.eyesUrl(this);
            if (url.isEmpty()) { fail("Wi-Fi కెమెరా లింక్ ఇంకా పెట్టలేదు (సెట్టింగ్స్ → 🚗 కారు నిద్ర అలర్ట్)"); return; }
            urlThread = new Thread(() -> urlLoop(url), "jarvis-car-eyes-url");
            urlThread.start();
            return;
        }
        boolean usb = "usb".equals(src);
        outside = usb;
        front = !usb;
        rotation = front ? frontGuess() : 0;
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String pick = null;
            Size size = null;
            Range<Integer> fps = null;
            int aeMax = 0;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                int want = usb ? CameraCharacteristics.LENS_FACING_EXTERNAL : CameraCharacteristics.LENS_FACING_FRONT;
                if (facing == null || facing != want) continue;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                pick = id;
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensor = so;
                size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
                Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) for (Range<Integer> r : ranges) // up to 10+ a second, as slow as it may go in the dark (longer light)
                    if (r.getUpper() >= 10 && (fps == null || r.getLower() < fps.getLower() || (r.getLower().equals(fps.getLower()) && r.getUpper() < fps.getUpper()))) fps = r;
                Range<Integer> ae = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (ae != null && front) aeMax = ae.getUpper(); // a dark car at night: as bright as the camera may make it
                break;
            }
            if (pick == null || size == null) {
                fail(usb ? "బయటి USB కెమెరా కనబడలేదు: ఈ ఫోన్ దాన్ని కెమెరాగా ఇవ్వడం లేదు (లేదా ప్లగ్ కాలేదు)" : "ముందు కెమెరా దొరకలేదు");
                return;
            }
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, h);
            final Range<Integer> rate = fps;
            final int bright = aeMax;
            cm.openCamera(pick, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (!alive) { c.close(); return; }
                    cam = c;
                    session(rate, bright);
                }
                @Override public void onDisconnected(CameraDevice c) {
                    c.close();
                    if (cam == c) cam = null;
                    fail("కెమెరాని ఇంకో యాప్ తీసుకుంది: కళ్ళ చెక్ ఆగింది");
                }
                @Override public void onError(CameraDevice c, int error) {
                    c.close();
                    if (cam == c) cam = null;
                    fail("కెమెరా తెరవలేకపోయాను (" + error + ")");
                }
            }, h);
        } catch (Throwable e) {
            fail("కెమెరా తెరవలేకపోయాను");
        }
    }

    private void session(Range<Integer> fps, int bright) {
        try {
            final Surface s = reader.getSurface();
            final CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(s);
            if (fps != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            if (bright > 0) rb.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, bright * 2 / 3);
            cam.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (!alive || cam == null) { cs.close(); return; }
                    session = cs;
                    try {
                        cs.setRepeatingRequest(rb.build(), null, h);
                        say("👁️ చూస్తున్నాను: మీ ముఖం కోసం…");
                    } catch (Exception e) { fail("కెమెరా తెరవలేకపోయాను"); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) { fail("కెమెరా తెరవలేకపోయాను"); }
            }, h);
        } catch (Exception e) {
            fail("కెమెరా తెరవలేకపోయాను");
        }
    }

    private int lastGuess = -1;

    /**
     * The front camera's turn for the face finder: ML Kit's rule (the sensor's turn + the device's turn) with the device's
     * turn from its motion sensor (the screen may not turn with it: auto-rotate off), else the screen's.
     */
    private int frontGuess() {
        int dev = deviceDeg >= 0 ? (360 - deviceDeg) % 360 : displayDeg();
        return (sensor + dev) % 360;
    }

    /** The screen's turn now, in degrees. */
    private int displayDeg() {
        try {
            android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
            switch (dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRotation()) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- every picture

    private void onImage(ImageReader r) {
        final Image img;
        try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
        if (img == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!alive || busy || detector == null || now - lastFrame < GAP_MS) { img.close(); return; }
        busy = true;
        lastFrame = now;
        if (front && !rotLocked) { // the phone turned in its holder: the front camera's new guess first
            int g = frontGuess();
            if (g != lastGuess) { lastGuess = g; rotation = g; }
        }
        int rot = rotation;
        if (watching && now - lastPreview > 300) {
            lastPreview = now;
            try { preview = grey(img, rot); } catch (Throwable ignored) {}
        }
        frameW = rot % 180 == 0 ? img.getWidth() : img.getHeight();
        frameH = rot % 180 == 0 ? img.getHeight() : img.getWidth();
        final InputImage in;
        try {
            in = InputImage.fromMediaImage(img, rot);
        } catch (Exception e) {
            img.close();
            busy = false;
            return;
        }
        look(in, img::close);
    }

    /** One picture to the face finder; done runs when it has finished with it. */
    private void look(InputImage in, Runnable done) {
        try {
            detector.process(in)
                    .addOnSuccessListener(exec, faces -> {
                        done.run();
                        try { if (alive) handle(faces, SystemClock.elapsedRealtime()); } catch (Throwable ignored) {} finally { busy = false; }
                    })
                    .addOnFailureListener(exec, e -> {
                        done.run();
                        busy = false;
                        // the face model may still be on its way from Google Play services: keep trying a while
                        if (++fails >= 80) fail("ముఖాలు చూసే Google మోడల్ ఇంకా ఫోన్‌కి రాలేదు: నెట్‌తో కాసేపాగి మళ్ళీ ప్రయత్నించండి");
                    });
        } catch (Throwable t) {
            done.run();
            busy = false;
            if (++fails >= 80) fail("ముఖాలు చూసే Google మోడల్ పనిచేయడం లేదు");
        }
    }

    private void handle(List<Face> faces, long now) {
        fails = 0;
        if (!test && !CarCare.on(this) && now - startedAt > 120_000) { // the car mode ended (or never began): no camera left on
            alive = false;
            new Handler(getMainLooper()).post(this::stopSelf);
            return;
        }
        // the driver's face: the one where his face has been (by place in the picture, never by who it is); at first the biggest
        Face best = null;
        float area = 0, near = Float.MAX_VALUE;
        boolean follow = faceX >= 0 && now - lastFaceAt < 60_000; // (a minute: a passenger's face is never taken for his while he looks away)
        for (Face f : faces) {
            Rect b = f.getBoundingBox();
            float a = (float) b.width() * b.height();
            if (follow) {
                float dx = b.exactCenterX() / Math.max(1, frameW) - faceX, dy = b.exactCenterY() / Math.max(1, frameH) - faceY;
                float d = dx * dx + dy * dy;
                if (d < near) { near = d; best = f; }
            } else if (a > area) { area = a; best = f; }
        }
        if (best != null && follow && near > 0.09f) best = null; // (only someone else is there: not his eyes)
        int ev;
        if (best != null) {
            lastFaceAt = now;
            rotLocked = true;
            Rect fb = best.getBoundingBox();
            faceX = fb.exactCenterX() / Math.max(1, frameW);
            faceY = fb.exactCenterY() / Math.max(1, frameH);
            ev = eyes.look(now, best.getLeftEyeOpenProbability(), best.getRightEyeOpenProbability(), best.getHeadEulerAngleX(),
                    best.getHeadEulerAngleY(), true);
            say(eyes.closedNow() ? "😑 కళ్ళు మూసుకున్నాయి" : "👁️ కళ్ళు తెరిచి ఉన్నాయి ✓");
        } else {
            ev = eyes.look(now, null, null, 0, 0, false);
            boolean dark = front && CarCare.on(this) && CarSense.night(java.time.LocalTime.now().getHour());
            say("🙂 ముఖం కనబడడం లేదు" + (dark ? " (చీకట్లో ఫోన్ కెమెరాకి కనబడదు: రాత్రికి IR కెమెరా)" : ""));
            if ((!rotLocked || now - lastFaceAt > 20_000) && now - rotTriedAt > (rotLocked ? 3000 : 4000)) { // no face: try the next turn
                rotTriedAt = now;
                rotLocked = false;
                rotation = CarSense.nextRotation(rotation);
            }
            if (!test && CarCare.on(this) && now - lastFaceAt > 60_000 && now - noFaceTold > 30 * 60_000L) {
                noFaceTold = now;
                Announcer.sayPhone(this, "కళ్ళ చెక్: మీ ముఖం కనబడడం లేదు. కెమెరా మీ ముఖం వైపు ఉందో చూడండి.");
            }
        }
        if (ev == CarSense.Eyes.NONE) return;
        String what = ev == CarSense.Eyes.CLOSED ? "😴 కళ్ళు 2 సెకన్లు మూసుకున్నాయి" : ev == CarSense.Eyes.HEAD_DOWN ? "😴 తల కిందకి వాలింది" : "🥱 కళ్ళు బరువెక్కుతున్నాయి";
        lastEvent = new java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.ENGLISH).format(new java.util.Date()) + " · " + what;
        if (test || !CarCare.on(this)) { // the check screen's test: shown and a soft beep, never the alarm
            lastEvent += ev == CarSense.Eyes.HEAVY ? " → కారులో ఇక్కడ హెచ్చరిక చెబుతాను" : " → కారులో ఇక్కడ అలారం మోగుతుంది";
            try {
                android.media.ToneGenerator g = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60);
                g.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300);
                h.postDelayed(g::release, 600);
            } catch (Exception ignored) {}
            return;
        }
        CarCare.eyes(this, ev);
    }

    /** A small grey picture, upright, from the camera's brightness plane (for the check screen only). */
    private static Bitmap grey(Image img, int rot) {
        Image.Plane yp = img.getPlanes()[0];
        ByteBuffer yb = yp.getBuffer();
        int row = yp.getRowStride(), pix = yp.getPixelStride(), W = img.getWidth(), H = img.getHeight();
        int uw = rot % 180 == 0 ? W : H, uh = rot % 180 == 0 ? H : W;
        int ow = 160, oh = Math.max(1, ow * uh / uw);
        int[] px = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int uy = oy * uh / oh;
            for (int ox = 0; ox < ow; ox++) {
                int ux = ox * uw / ow, sx, sy;
                switch (rot) {
                    case 90: sx = uy; sy = H - 1 - ux; break;
                    case 180: sx = W - 1 - ux; sy = H - 1 - uy; break;
                    case 270: sx = W - 1 - uy; sy = ux; break;
                    default: sx = ux; sy = uy; break;
                }
                int v = yb.get(sy * row + sx * pix) & 0xFF;
                px[oy * ow + ox] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888);
    }

    /** 640x480 if it can, else the smallest size that is still big enough. */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size z : sizes) {
            int w = Math.max(z.getWidth(), z.getHeight()), hh = Math.min(z.getWidth(), z.getHeight());
            if (w == 640 && hh == 480) return z;
            if (w < 480 || hh < 320 || w > 1280) continue;
            if (best == null || w * hh < best.getWidth() * best.getHeight()) best = z;
        }
        if (best == null) for (Size z : sizes) if (best == null || z.getWidth() * z.getHeight() < best.getWidth() * best.getHeight()) best = z;
        return best;
    }

    // ---------------------------------------------------------------- a Wi-Fi camera (MJPEG / JPEG link)

    private void urlLoop(String url) {
        while (alive) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                urlOnce(url);
                if (alive && SystemClock.elapsedRealtime() - t0 < 1000) SystemClock.sleep(150); // (a snapshot link: asked again)
            } catch (IllegalArgumentException e) {
                fail(e.getMessage());
                return;
            } catch (java.net.URISyntaxException e) {
                fail("Wi-Fi కెమెరా లింక్ సరిగ్గా లేదు");
                return;
            } catch (Exception e) {
                if (!alive) return;
                say("Wi-Fi కెమెరాకి కనెక్ట్ కాలేదు: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + " (5 సెకన్లలో మళ్ళీ)");
                if (++urlMisses == 3 && !test && CarCare.on(this)) // (said once: the car's own check must not stay silently blind)
                    Announcer.sayPhone(this, "Wi-Fi కెమెరాకి కనెక్ట్ కావడం లేదు. ఫోన్ ఆ కెమెరా Wi-Fi కి కనెక్ట్ అయ్యిందో చూడండి. మాటల చెక్ కొనసాగుతుంది.");
                SystemClock.sleep(5000);
            }
        }
    }

    /** One connection: plain HTTP over the camera's Wi-Fi (its own network, often without internet). */
    private void urlOnce(String url) throws Exception {
        java.net.URI u = new java.net.URI(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme.startsWith("rtsp")) throw new IllegalArgumentException("RTSP లింక్‌తో ఇంకా పనిచేయదు: కెమెరా యాప్‌లో MJPEG / JPEG (http://…) లింక్ చూసి పెట్టండి");
        if (!"http".equals(scheme)) throw new IllegalArgumentException("లింక్ http://… తో మొదలవ్వాలి (MJPEG / JPEG)");
        String host = u.getHost();
        if (host == null) throw new IllegalArgumentException("లింక్ సరిగ్గా లేదు");
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        javax.net.SocketFactory f = wifiSockets();
        try (java.net.Socket s = f.createSocket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 4000);
            s.setSoTimeout(6000);
            StringBuilder req = new StringBuilder("GET ").append(path).append(" HTTP/1.0\r\nHost: ").append(host)
                    .append("\r\nUser-Agent: Jarvis\r\nConnection: close\r\n");
            String user = u.getRawUserInfo();
            if (user != null) req.append("Authorization: Basic ")
                    .append(java.util.Base64.getEncoder().encodeToString(java.net.URLDecoder.decode(user.replace("+", "%2B"), "UTF-8").getBytes(StandardCharsets.UTF_8))).append("\r\n");
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = new BufferedInputStream(s.getInputStream());
            String first = line(in);
            if (!first.matches("HTTP/\\d(\\.\\d)? 200.*")) throw new IOException("కెమెరా జవాబు: " + first);
            while (!line(in).isEmpty()) { /* headers */ }
            CarSense.Jpegs j = new CarSense.Jpegs();
            byte[] b = new byte[16384];
            int n;
            if (status.startsWith("కెమెరా తెరుస్తున్నాను") || status.startsWith("Wi-Fi")) say("👁️ చూస్తున్నాను: మీ ముఖం కోసం…");
            while (alive && (n = in.read(b)) > 0) {
                byte[] pic = j.add(b, n);
                if (pic != null) frame(pic);
            }
        }
    }

    /** The camera's Wi-Fi (it may have no internet: Android would otherwise go by mobile data). */
    private javax.net.SocketFactory wifiSockets() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network any = null;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n.getSocketFactory(); // (a camera's own Wi-Fi)
                if (any == null) any = n;
            }
            if (any != null) return any.getSocketFactory();
        } catch (Exception ignored) {}
        return javax.net.SocketFactory.getDefault();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') { if (c != '\r') b.write(c); if (b.size() > 8192) break; }
        if (c < 0 && b.size() == 0) throw new IOException("కెమెరా కనెక్షన్ ఆగిపోయింది");
        return b.toString("ISO-8859-1");
    }

    /** A whole picture from the Wi-Fi camera (its own thread): to the face finder when it is free. */
    private void frame(byte[] jpg) {
        long now = SystemClock.elapsedRealtime();
        if (busy || detector == null || now - lastFrame < GAP_MS) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (o.outWidth <= 0) return;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 640) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        final Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (bmp == null) return;
        urlMisses = 0; // (the camera answers: only misses in a row count)
        busy = true;
        lastFrame = now;
        int rot = rotation;
        if (watching && now - lastPreview > 300) {
            lastPreview = now;
            try {
                android.graphics.Matrix m = new android.graphics.Matrix();
                float sc = 160f / Math.max(1, bmp.getWidth());
                m.postScale(sc, sc);
                m.postRotate(rot);
                preview = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            } catch (Throwable ignored) {}
        }
        frameW = rot % 180 == 0 ? bmp.getWidth() : bmp.getHeight();
        frameH = rot % 180 == 0 ? bmp.getHeight() : bmp.getWidth();
        look(InputImage.fromBitmap(bmp, rot), bmp::recycle);
    }

    // ---------------------------------------------------------------- the end

    @Override public void onDestroy() {
        alive = false;
        running = false;
        try { if (orient != null) orient.disable(); } catch (Exception ignored) {}
        test = false;
        preview = null;
        Thread u = urlThread;
        urlThread = null;
        if (u != null) u.interrupt();
        if (h != null) {
            h.post(() -> {
                try { if (session != null) session.close(); } catch (Exception ignored) {}
                try { if (cam != null) cam.close(); } catch (Exception ignored) {}
                session = null;
                cam = null;
                final long until = SystemClock.elapsedRealtime() + 2000;
                h.postDelayed(new Runnable() { // (after ML Kit has finished with the last picture, or 2 s at most)
                    @Override public void run() {
                        if (busy && SystemClock.elapsedRealtime() < until) { h.postDelayed(this, 100); return; }
                        try { if (detector != null) detector.close(); } catch (Exception ignored) {}
                        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
                        detector = null;
                        reader = null;
                        if (thread != null) thread.quitSafely();
                    }
                }, 100);
            });
        }
        if (!failed) status = "ఆగింది";
        super.onDestroy();
    }
}
