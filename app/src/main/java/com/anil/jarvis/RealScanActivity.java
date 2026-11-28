package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 📐 నిజమైన 3D స్కాన్ (A, 28 Nov): the phone itself measures the thing. Google's ARCore gives, a few times a second, how far
 * every point of the picture is (depth) and where the phone is; each such picture is poured into a box of small cubes round
 * the thing (Tsdf), with the colour seen there. While he walks round slowly, the surface already made shows on the camera
 * picture in its own colours, and a ring shows which sides are done. ✅ makes the final surface, cuts the floor away,
 * measures it (cm) and keeps it with up to eight photos (for names on his tap, or a workshop model). ₹0: nothing leaves the
 * phone. Things and room corners only — never people.
 */
public class RealScanActivity extends Activity implements GLSurfaceView.Renderer {
    static void open(Context c) { c.startActivity(new Intent(c, RealScanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }

    private static final float[] SIZES = {0.6f, 1.2f, 2.5f, 4.0f};
    private static final String[] SIZE_NAMES = {"📦 చిన్నది 60 సెం.మీ", "🪑 మధ్యది 1.2 మీ", "🏍️ పెద్దది 2.5 మీ", "🏠 గది మూల 4 మీ"};
    private static final int N = 128;          // cubes per side
    private static final long GRAB_MS = 220;   // a depth picture about this often
    private static final long MAX_MS = 4 * 60_000;
    private static final int REQ_CAM = 91;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private Prefs p;
    private GLSurfaceView gl;
    private TextView status, hint, startBtn, pauseBtn, doneBtn;
    private LinearLayout sizeRow;
    private final List<TextView> sizeChips = new ArrayList<>();
    private Coverage cover;
    private boolean destroyed;

    // 0 aiming · 1 scanning · 2 paused · 3 making · 4 finished
    private volatile int phase;
    private volatile boolean startAsked;
    private volatile int sizeIdx = 1;

    // GL thread
    private Session session;
    private int camTex = -1, bgProg, bgPos, bgUv, bgTex, width, height, fails;
    private boolean texSet, geometryDirty = true;
    private final FloatBuffer quad, uv;
    private final FloatBuffer tc = buf(new float[]{0, 0, 1, 1}), ic = buf(new float[4]), irot = buf(new float[]{0, 0, 100, 0}), vrot = buf(new float[4]);
    private final MeshGl meshGl = new MeshGl();
    private boolean glOk;
    private final float[] view = new float[16], proj = new float[16], vp = new float[16];
    private long lastGrab, lastDepthTs, lastUi;
    private volatile String track = "";
    private float[] aim;              // (aiming) where the + points now
    private int frameNo;

    // the scan
    private volatile Tsdf vol;
    private volatile float[] center;  // the box's middle (world)
    private volatile float boxSize;
    private volatile float floorY = Float.NaN;
    private volatile Tsdf.Mesh preview;
    private Tsdf.Mesh uploaded;
    private final int[] sectors = new int[12];
    private volatile boolean topSeen;
    private volatile int integrated;
    private long startedAt, lastPreview;
    private final List<Key> keys = new ArrayList<>();
    private float[] lastPose;
    private long lastPoseAt;

    /** One kept photo: upright picture, where the phone was, its own pinhole (in the camera's own picture), its turn to upright. */
    static final class Key {
        Bitmap upright;
        float[] pose;
        float fx, fy, cx, cy, az;
        int iw, ih, rot, sector;
    }

    /** One depth picture with its colour picture, copied out of ARCore for the worker. */
    private static final class Grab {
        float[] depth, w;
        int dw, dh;
        float fx, fy, cx, cy;
        float[] pose = new float[16], map = new float[4];
        byte[] y, u, v;
        int iw, ih, yRow, uvRow, uvPix, rot;
        float ifx, ify, icx, icy;
        long t;
    }

    public RealScanActivity() {
        quad = buf(new float[]{-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f});
        uv = buf(new float[]{0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
    }

    private static FloatBuffer buf(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        build();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        gl = new GLSurfaceView(this);
        gl.setPreserveEGLContextOnPause(true);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        gl.setRenderer(this);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(gl, new FrameLayout.LayoutParams(-1, -1));

        TextView plus = Ui.text(this, "＋", 34, Ui.alpha(Ui.CYAN, 0xDD));
        plus.setGravity(Gravity.CENTER);
        root.addView(plus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        plusMark = plus;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0x99000000);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(dp(10), dp(6), dp(10), dp(6));
        x.setOnClickListener(v -> leave());
        bar.addView(x);
        TextView name = Ui.mono(this, "📐 నిజమైన 3D స్కాన్", 14, Ui.CYAN);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        cover = new Coverage(this);
        bar.addView(cover, new LinearLayout.LayoutParams(dp(58), dp(58)));
        top.addView(bar);
        status = Ui.text(this, "📷 కెమెరా మొదలవుతోంది…", 13, Ui.TEXT);
        status.setPadding(dp(14), 0, dp(14), dp(8));
        top.addView(status);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xAA000000);
        bottom.setPadding(0, dp(8), 0, dp(12));
        hint = Ui.text(this, "", 14, 0xFFFFFFFF);
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(dp(14), 0, dp(14), dp(8));
        bottom.addView(hint);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        sizeRow = new LinearLayout(this);
        sizeRow.setPadding(dp(8), 0, dp(8), dp(6));
        for (int i = 0; i < SIZES.length; i++) {
            final int k = i;
            TextView c = chip(SIZE_NAMES[i], i == sizeIdx);
            c.setOnClickListener(v -> pickSize(k));
            sizeChips.add(c);
            sizeRow.addView(c);
        }
        hs.addView(sizeRow);
        bottom.addView(hs);
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER);
        acts.setPadding(dp(8), dp(4), dp(8), 0);
        startBtn = chip("🎯 ఇక్కడ మొదలు", true);
        startBtn.setOnClickListener(v -> startAsked = true);
        acts.addView(startBtn);
        pauseBtn = chip("⏸ ఆపు", false);
        pauseBtn.setOnClickListener(v -> togglePause());
        acts.addView(pauseBtn);
        doneBtn = chip("✅ అయింది", true);
        doneBtn.setOnClickListener(v -> done());
        acts.addView(doneBtn);
        TextView again = chip("🔁 మళ్ళీ", false);
        again.setOnClickListener(v -> restart());
        acts.addView(again);
        bottom.addView(acts);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        refresh();
    }

    private TextView plusMark;

    private TextView chip(String s, boolean on) {
        TextView t = Ui.text(this, s, 13.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        lit(t, on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void lit(TextView t, boolean on) { t.setBackground(Ui.round(this, on ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, on ? Ui.CYAN : 0x55FFFFFF, 999)); }

    private void pickSize(int k) {
        if (phase != 0) { Toast.makeText(this, "సైజు మార్చాలంటే 🔁 మళ్ళీ నొక్కండి", Toast.LENGTH_SHORT).show(); return; }
        sizeIdx = k;
        for (int i = 0; i < sizeChips.size(); i++) lit(sizeChips.get(i), i == k);
    }

    /** The buttons and the words for where the scan is now. */
    private void refresh() {
        int ph = phase;
        startBtn.setVisibility(ph == 0 ? View.VISIBLE : View.GONE);
        sizeRow.setVisibility(ph == 0 ? View.VISIBLE : View.GONE);
        pauseBtn.setVisibility(ph == 1 || ph == 2 ? View.VISIBLE : View.GONE);
        pauseBtn.setText(ph == 2 ? "▶️ కొనసాగించు" : "⏸ ఆపు");
        doneBtn.setVisibility(ph == 1 || ph == 2 ? View.VISIBLE : View.GONE);
        plusMark.setVisibility(ph == 0 ? View.VISIBLE : View.GONE);
        cover.setVisibility(ph == 0 ? View.INVISIBLE : View.VISIBLE);
        if (ph == 0) hint.setText("➕ ని వస్తువు మధ్యలో పెట్టి 🎯 నొక్కండి. బైక్ లాంటి పెద్దవాటికి 🏍️, గది మూలకి 🏠.\nవస్తువులు, గది మూలలకే: మనుషులను స్కాన్ చేయను.");
        else if (ph == 1) hint.setText("వస్తువు చుట్టూ నెమ్మదిగా నడవండి (30 సెం.మీ నుంచి 3 మీ దూరంలో). రంగు వచ్చిన చోట్లు స్కాన్ అయ్యాయి; ఖాళీగా ఉన్న చోటుకి ఫోన్ చూపించండి. పై నుంచి కూడా ఒకసారి. అయ్యాక ✅.");
        else if (ph == 2) hint.setText("⏸ ఆపాను. ▶️ తో కొనసాగించండి, లేదా ✅.");
        else if (ph == 3) hint.setText("🧊 3D తయారు చేస్తున్నాను, కొలుస్తున్నాను… (కొన్ని సెకన్లు)");
    }

    private void togglePause() {
        if (phase == 1) phase = 2;
        else if (phase == 2) phase = 1;
        refresh();
    }

    private void restart() {
        if (phase == 3) return;
        phase = 0;
        vol = null;
        center = null;
        preview = null;
        integrated = 0;
        topSeen = false;
        synchronized (keys) { for (Key k : keys) if (k.upright != null) k.upright.recycle(); keys.clear(); }
        java.util.Arrays.fill(sectors, 0);
        lastPose = null;
        cover.set(sectors, false);
        refresh();
    }

    private void leave() {
        if (phase == 1 || phase == 2) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("స్కాన్ వదిలేయనా? ఇప్పటివరకు స్కాన్ అయింది పోతుంది.")
                    .setPositiveButton("వదిలేయి", (d, w) -> finish()).setNegativeButton("వద్దు", null).show();
            return;
        }
        finish();
    }

    @Override public void onBackPressed() { leave(); }

    // ================================================================ the camera (ARCore)

    private boolean front;

    @Override protected void onResume() {
        super.onResume();
        front = true;
        startAr(0);
    }

    /** The AR camera; tries: a second try a moment later when the Jarvis camera is still letting the camera go. */
    private void startAr(int tries) {
        if (destroyed || !front) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { status.setText("📷 కెమెరా అనుమతి కావాలి"); return; }
        if (session == null) {
            String r = RealFloor.ready(this, true);
            if ("install".equals(r)) { status.setText("Google Play Services for AR ఇన్‌స్టాల్ అవుతోంది… అయ్యాక మళ్ళీ తెరవండి."); return; }
            if ("checking".equals(r)) { main.postDelayed(() -> { if (session == null) startAr(tries); }, 800); return; }
            if ("no".equals(r)) { cannot("ఈ ఫోన్‌లో Google AR (ARCore) లేదు."); return; }
            if (!"ok".equals(r)) { cannot(r); return; }
            try {
                Session s = new Session(this);
                if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) { s.close(); cannot("ఈ ఫోన్ AR లోతు (Depth) ఇవ్వదు."); return; }
                Config c = s.getConfig();
                c.setFocusMode(Config.FocusMode.AUTO);
                c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                c.setDepthMode(Config.DepthMode.AUTOMATIC);
                s.configure(c);
                session = s;
            } catch (Throwable t) {
                String n = t.getClass().getSimpleName();
                cannot(n.contains("ApkTooOld") ? "Google Play Services for AR పాతది; Play Store లో అప్డేట్ చేయండి" : n.contains("CameraNotAvailable") ? "కెమెరా వేరే యాప్ వాడుతోంది" : "AR మొదలవలేదు (" + n + ")");
                return;
            }
        }
        try { session.resume(); }
        catch (Throwable t) {
            if (tries < 3) { main.postDelayed(() -> startAr(tries + 1), 700); return; } // (the Jarvis camera may still be closing)
            cannot("కెమెరా వేరే యాప్ వాడుతోంది");
            return;
        }
        texSet = false;
        geometryDirty = true;
        gl.onResume();
        status.setText(phase == 0 ? "🎯 గురి పెట్టండి" : status.getText());
    }

    @Override protected void onPause() {
        super.onPause();
        front = false;
        gl.onPause();
        try { if (session != null) session.pause(); } catch (Throwable ignored) {}
        if (phase == 1) { phase = 2; refresh(); } // (back: nothing is lost, it waits)
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        Session s = session;
        session = null;
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
        work.shutdownNow();
        synchronized (keys) { for (Key k : keys) if (k.upright != null) k.upright.recycle(); keys.clear(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CAM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startAr(0);
        else if (req == REQ_CAM) cannot("కెమెరా అనుమతి లేకుండా స్కాన్ చేయలేను.");
    }

    /** Honest: this phone / this moment cannot do it; what else he can use. */
    private void cannot(String why) {
        status.setText("📐 " + why);
        hint.setText("నిజమైన 3D స్కాన్ ఇప్పుడు కుదరదు: " + why + "\nకెమెరాలో 🧊 3D → 🔄 360° ఫోటో స్పిన్ లేదా 🧱 వర్క్‌షాప్ బొమ్మ వాడవచ్చు.");
        startBtn.setVisibility(View.GONE);
        sizeRow.setVisibility(View.GONE);
        plusMark.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- GL thread

    @Override public void onSurfaceCreated(GL10 g, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        camTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        bgProg = MeshGl.program(
                "attribute vec4 a_Position;\nattribute vec2 a_TexCoord;\nvarying vec2 v_TexCoord;\n"
                        + "void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }\n",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 v_TexCoord;\n"
                        + "uniform samplerExternalOES sTexture;\nvoid main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }\n");
        bgPos = GLES20.glGetAttribLocation(bgProg, "a_Position");
        bgUv = GLES20.glGetAttribLocation(bgProg, "a_TexCoord");
        bgTex = GLES20.glGetUniformLocation(bgProg, "sTexture");
        glOk = bgProg != 0 && meshGl.init();
        uploaded = null;
        texSet = false;
        if (!glOk) main.post(() -> cannot("కెమెరా బొమ్మ గీయలేకపోయాను"));
    }

    @Override public void onSurfaceChanged(GL10 g, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = w;
        height = h;
        geometryDirty = true;
    }

    @Override public void onDrawFrame(GL10 g) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null || width == 0 || !glOk) return;
        Frame frame;
        try {
            if (!texSet) { s.setCameraTextureNames(new int[]{camTex}); texSet = true; }
            if (geometryDirty) {
                geometryDirty = false;
                int turn = 0;
                try { turn = getWindowManager().getDefaultDisplay().getRotation(); } catch (Throwable ignored) {}
                s.setDisplayGeometry(turn, width, height);
            }
            frame = s.update();
            fails = 0;
        } catch (Throwable t) {
            if (++fails == 60) main.post(() -> status.setText("📷 కెమెరా బొమ్మ రావడం లేదు (" + t.getClass().getSimpleName() + ")"));
            return;
        }
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv);
            quad.position(0);
            uv.position(0);
        }
        if (frame.getTimestamp() == 0) return;
        drawCamera();
        Camera cam = frame.getCamera();
        long now = System.currentTimeMillis();
        if (cam.getTrackingState() != TrackingState.TRACKING) {
            TrackingFailureReason why = cam.getTrackingFailureReason();
            setTrack(why == TrackingFailureReason.INSUFFICIENT_LIGHT ? "🌙 వెలుతురు తక్కువ: లైట్ వేయండి" : why == TrackingFailureReason.EXCESSIVE_MOTION ? "🐢 నెమ్మదిగా కదపండి"
                    : why == TrackingFailureReason.INSUFFICIENT_FEATURES ? "🔎 గీతలు / వస్తువులు ఉన్న చోటు చూపించండి" : "📱 ఫోన్‌ని కొంచెం కదపండి: చోటు గుర్తుపడుతున్నాను");
            return;
        }
        setTrack("");
        cam.getProjectionMatrix(proj, 0, 0.05f, 30f);
        cam.getViewMatrix(view, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        if (frameNo++ % 15 == 0) floorY = floorBelow(s, cam.getPose(), center, boxSize);

        if (phase == 0) {
            if (frameNo % 4 == 0 || aim == null) aim = aimAt(frame, cam);
            float size = SIZES[sizeIdx];
            if (aim != null) {
                float[] c = boxCentre(aim, cam.getPose(), size);
                meshGl.lines(vp, MeshGl.box(c[0], c[1], c[2], size, size, size, 0), Ui.alpha(Ui.CYAN, 0x88), false);
            }
            if (startAsked) {
                startAsked = false;
                if (aim == null) main.post(() -> Toast.makeText(this, "➕ వస్తువు మీద లేదు: దాని మీద పెట్టి మళ్ళీ నొక్కండి", Toast.LENGTH_SHORT).show());
                else begin(boxCentre(aim, cam.getPose(), size), size);
            }
        } else if (phase == 1 && vol != null && now - lastGrab >= GRAB_MS && !busy.get()) {
            if (now - startedAt > MAX_MS) { main.post(this::done); }
            else grab(frame, cam, now);
        }
        Tsdf.Mesh pv = preview;
        if (pv != uploaded) { meshGl.upload(pv); uploaded = pv; }
        if (phase >= 1 && phase <= 3) {
            meshGl.draw(vp, new float[]{view[2], view[6], view[10]}, 0.92f);
            float[] c = center;
            if (c != null) meshGl.lines(vp, MeshGl.box(c[0], c[1], c[2], boxSize, boxSize, boxSize, 0), Ui.alpha(Ui.CYAN, 0x66), false);
        }
        if (now - lastUi > 400) { lastUi = now; main.post(this::showStatus); }
    }

    private void setTrack(String s) {
        if (s.equals(track)) return;
        track = s;
        main.post(this::showStatus);
    }

    private void showStatus() {
        if (destroyed) return;
        String t = track;
        int ph = phase, sides = 0;
        for (int c : sectors) if (c >= 2) sides++;
        String base = ph == 0 ? "🎯 గురి పెట్టండి" : ph == 3 ? "🧊 తయారవుతోంది…"
                : "చుట్టూ " + sides + "/12 వైపులు · " + integrated + " బొమ్మలు" + (topSeen ? " · ⬆️ పై నుంచి ✓" : "") + (ph == 2 ? " · ⏸" : "");
        status.setText(t.isEmpty() ? base : t + "\n" + base);
        if (ph != 0) cover.set(sectors, topSeen);
    }

    private void begin(float[] c, float size) {
        try {
            Tsdf v = new Tsdf(N, size, c[0], c[1], c[2]);
            v.floorY = floorY;
            boxSize = size;
            center = c;
            vol = v;
            startedAt = System.currentTimeMillis();
            lastPreview = 0;
            phase = 1;
            main.post(() -> { refresh(); Sfx.hud(this, p, "lock"); });
        } catch (OutOfMemoryError e) {
            main.post(() -> Toast.makeText(this, "ఫోన్ మెమరీ సరిపోలేదు; ఇతర యాప్‌లు మూసి మళ్ళీ ప్రయత్నించండి", Toast.LENGTH_LONG).show());
        }
    }

    /** The box's middle: a little behind the point he aims at (it is on the thing's front), standing on the floor when there is one. */
    private float[] boxCentre(float[] at, Pose cam, float size) {
        float fx = at[0] - cam.tx(), fz = at[2] - cam.tz();
        float l = (float) Math.sqrt(fx * fx + fz * fz);
        if (l > 1e-3f) { fx /= l; fz /= l; }
        float push = size * 0.22f;
        float y = at[1];
        float fl = floorY;
        if (!Float.isNaN(fl) && at[1] - fl < size * 0.75f) y = fl + size / 2 - 0.02f; // (sits on the floor, a little below it to catch the base)
        return new float[]{at[0] + fx * push, y, at[2] + fz * push};
    }

    /** What the + in the middle of the screen points at: a surface ARCore measured, else a learned point. */
    private float[] aimAt(Frame frame, Camera cam) {
        List<HitResult> hits;
        try { hits = frame.hitTest(width / 2f, height / 2f); } catch (Throwable t) { return null; }
        Pose cp = cam.getPose();
        HitResult depth = null, point = null, plane = null;
        for (HitResult h : hits) {
            Pose hp = h.getHitPose();
            float dx = hp.tx() - cp.tx(), dy = hp.ty() - cp.ty(), dz = hp.tz() - cp.tz();
            if (dx * dx + dy * dy + dz * dz > 25f) continue;
            Trackable t = h.getTrackable();
            if (depth == null && t instanceof DepthPoint) depth = h;
            else if (point == null && t instanceof Point) point = h;
            else if (plane == null && t instanceof Plane && ((Plane) t).isPoseInPolygon(hp)) plane = h;
        }
        HitResult h = depth != null ? depth : plane != null ? plane : point;
        if (h == null) return null;
        Pose hp = h.getHitPose();
        return new float[]{hp.tx(), hp.ty(), hp.tz()};
    }

    /**
     * What the thing stands on. While aiming: the floor (the biggest upward flat surface well below the phone). Once the box
     * is placed: the highest upward surface inside the box's lower half (the floor, or the table top the thing is on), so it
     * is cut away. NaN = not found.
     */
    private static float floorBelow(Session s, Pose cp, float[] box, float size) {
        float best = 0, y = Float.NaN;
        try {
            for (Plane pl : s.getAllTrackables(Plane.class)) {
                if (pl.getTrackingState() != TrackingState.TRACKING || pl.getSubsumedBy() != null || pl.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
                float py = pl.getCenterPose().ty();
                if (box != null) {
                    if (py > box[1] || py < box[1] - size / 2 - 0.08f) continue; // (only a surface the box stands on)
                    if (Float.isNaN(y) || py > y) y = py;
                    continue;
                }
                if (cp.ty() - py < 0.3f) continue; // (a table top at hand height is not the floor)
                float area = pl.getExtentX() * pl.getExtentZ();
                if (Float.isNaN(y) || area > best || py < y - 0.25f) { best = area; y = py; }
            }
        } catch (Throwable ignored) {}
        return y;
    }

    /** A new depth picture (and its colour picture) copied out for the worker. */
    private void grab(Frame frame, Camera cam, long now) {
        Image raw = null, conf = null, smooth = null, col = null;
        try {
            raw = frame.acquireRawDepthImage16Bits();
            if (raw.getTimestamp() == lastDepthTs) return; // (the same depth as before, moved: nothing new)
            lastDepthTs = raw.getTimestamp();
            conf = frame.acquireRawDepthConfidenceImage();
            try { smooth = frame.acquireDepthImage16Bits(); } catch (Throwable ignored) {}
            try { col = frame.acquireCameraImage(); } catch (Throwable ignored) {}
            Grab gr = new Grab();
            gr.t = now;
            int dw = raw.getWidth(), dh = raw.getHeight();
            gr.dw = dw;
            gr.dh = dh;
            gr.depth = new float[dw * dh];
            gr.w = new float[dw * dh];
            Image.Plane rp = raw.getPlanes()[0], cp = conf.getPlanes()[0];
            ByteBuffer rb = rp.getBuffer().order(ByteOrder.nativeOrder()), cb = cp.getBuffer();
            int rRow = rp.getRowStride(), rPix = Math.max(2, rp.getPixelStride()), cRow = cp.getRowStride(), cPix = Math.max(1, cp.getPixelStride());
            ByteBuffer sb = null;
            int sRow = 0, sPix = 2;
            if (smooth != null && smooth.getWidth() == dw && smooth.getHeight() == dh) {
                Image.Plane spl = smooth.getPlanes()[0];
                sb = spl.getBuffer().order(ByteOrder.nativeOrder());
                sRow = spl.getRowStride();
                sPix = Math.max(2, spl.getPixelStride());
            }
            for (int y = 0; y < dh; y++)
                for (int x = 0; x < dw; x++) {
                    int i = y * dw + x;
                    int mm = rb.getShort(y * rRow + x * rPix) & 0xFFFF;
                    float c = (cb.get(y * cRow + x * cPix) & 0xFF) / 255f;
                    if (mm > 0 && c >= 0.45f) { gr.depth[i] = mm / 1000f; gr.w[i] = Math.max(0.4f, c); }
                    else if (sb != null) {
                        int sm = sb.getShort(y * sRow + x * sPix) & 0xFFFF;
                        if (sm > 0) { gr.depth[i] = sm / 1000f; gr.w[i] = 0.25f; }
                    }
                }
            CameraIntrinsics ti = cam.getTextureIntrinsics();
            float[] f = ti.getFocalLength(), pp = ti.getPrincipalPoint();
            int[] dim = ti.getImageDimensions();
            gr.fx = f[0] * dw / dim[0];
            gr.fy = f[1] * dh / dim[1];
            gr.cx = pp[0] * dw / dim[0];
            gr.cy = pp[1] * dh / dim[1];
            cam.getPose().toMatrix(gr.pose, 0);
            if (col != null) {
                gr.iw = col.getWidth();
                gr.ih = col.getHeight();
                Image.Plane[] pl = col.getPlanes();
                gr.y = copy(pl[0].getBuffer());
                gr.u = copy(pl[1].getBuffer());
                gr.v = copy(pl[2].getBuffer());
                gr.yRow = pl[0].getRowStride();
                gr.uvRow = pl[1].getRowStride();
                gr.uvPix = pl[1].getPixelStride();
                tc.position(0);
                ic.position(0);
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, tc, Coordinates2d.IMAGE_PIXELS, ic);
                gr.map = new float[]{ic.get(0), ic.get(1), ic.get(2) - ic.get(0), ic.get(3) - ic.get(1)};
                irot.position(0);
                vrot.position(0);
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, irot, Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, vrot);
                float ddx = vrot.get(2) - vrot.get(0), ddy = -(vrot.get(3) - vrot.get(1)); // (the screen's own way: y down)
                gr.rot = Math.abs(ddx) >= Math.abs(ddy) ? (ddx >= 0 ? 0 : 180) : (ddy >= 0 ? 90 : 270);
                CameraIntrinsics ii = cam.getImageIntrinsics();
                float[] f2 = ii.getFocalLength(), p2 = ii.getPrincipalPoint();
                int[] d2 = ii.getImageDimensions();
                float sx = gr.iw / (float) d2[0], sy = gr.ih / (float) d2[1];
                gr.ifx = f2[0] * sx;
                gr.ify = f2[1] * sy;
                gr.icx = p2[0] * sx;
                gr.icy = p2[1] * sy;
            }
            lastGrab = now;
            busy.set(true);
            work.execute(() -> take(gr));
        } catch (Throwable ignored) {
            // (depth not ready yet at the start, or a picture missed: the next one comes)
        } finally {
            if (raw != null) raw.close();
            if (conf != null) conf.close();
            if (smooth != null) smooth.close();
            if (col != null) col.close();
        }
    }

    private static byte[] copy(ByteBuffer b) {
        b.rewind();
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    private void drawCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(bgProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
        GLES20.glUniform1i(bgTex, 0);
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(bgUv, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnableVertexAttribArray(bgPos);
        GLES20.glEnableVertexAttribArray(bgUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgPos);
        GLES20.glDisableVertexAttribArray(bgUv);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    // ================================================================ the worker: into the box, the sides done, the photos kept

    private void take(Grab g) {
        try {
            Tsdf v = vol;
            float[] c = center;
            if (v == null || c == null || phase == 0) return;
            v.floorY = floorY;
            int[] argb = g.y == null ? null : argb(g);
            v.integrate(g.depth, g.w, g.dw, g.dh, g.fx, g.fy, g.cx, g.cy, g.pose, argb, g.iw, g.ih, g.map);
            integrated++;
            float dx = g.pose[12] - c[0], dz = g.pose[14] - c[2];
            float az = (float) Math.toDegrees(Math.atan2(dz, dx));
            int sec = ((int) Math.floor(((az % 360) + 360) % 360 / 30f)) % 12;
            sectors[sec]++;
            if (-g.pose[9] < -0.55f && g.pose[13] > c[1]) topSeen = true; // (looking down from above: the camera's -z points down)
            if (argb != null) maybeKeep(g, argb, az);
            long now = System.currentTimeMillis();
            if (now - lastPreview > 1800) { lastPreview = now; preview = Tsdf.keepMain(v.extract(2), 0.02f); }
        } catch (OutOfMemoryError e) {
            phase = 2;
            main.post(() -> { refresh(); Toast.makeText(this, "ఫోన్ మెమరీ సరిపోలేదు: ✅ నొక్కండి", Toast.LENGTH_LONG).show(); });
        } catch (Throwable ignored) {
        } finally {
            busy.set(false);
        }
    }

    /** The colour picture as ARGB (YUV 4:2:0 → RGB). */
    private static int[] argb(Grab g) {
        int w = g.iw, h = g.ih;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int yr = y * g.yRow, cr = (y >> 1) * g.uvRow;
            for (int x = 0; x < w; x++) {
                int yi = yr + x;
                int ci = cr + (x >> 1) * g.uvPix;
                if (yi >= g.y.length || ci >= g.u.length || ci >= g.v.length) continue;
                float Y = (g.y[yi] & 0xFF), U = (g.u[ci] & 0xFF) - 128, V = (g.v[ci] & 0xFF) - 128;
                int r = clamp(Y + 1.402f * V), gg = clamp(Y - 0.344f * U - 0.714f * V), b = clamp(Y + 1.772f * U);
                out[y * w + x] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        }
        return out;
    }

    private static int clamp(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    /** Keeps one steady photo per 45° side round the thing (up to eight): for names on his tap and for a workshop model. */
    private void maybeKeep(Grab g, int[] argb, float az) {
        boolean steady = true;
        if (lastPose != null) {
            float dt = Math.max(0.05f, (g.t - lastPoseAt) / 1000f);
            float mx = g.pose[12] - lastPose[12], my = g.pose[13] - lastPose[13], mz = g.pose[14] - lastPose[14];
            float speed = (float) Math.sqrt(mx * mx + my * my + mz * mz) / dt;
            float dot = g.pose[8] * lastPose[8] + g.pose[9] * lastPose[9] + g.pose[10] * lastPose[10];
            float turn = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dot)))) / dt;
            steady = speed < 0.30f && turn < 22f;
        }
        lastPose = g.pose.clone();
        lastPoseAt = g.t;
        if (!steady) return;
        int sector = ((int) Math.floor((((az + 22.5f) % 360) + 360) % 360 / 45f)) % 8;
        synchronized (keys) {
            for (Key k : keys) if (k.sector == sector) return;
            if (keys.size() >= 8) return;
        }
        Bitmap b = Bitmap.createBitmap(argb, g.iw, g.ih, Bitmap.Config.ARGB_8888);
        Bitmap up = b;
        if (g.rot != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(g.rot);
            up = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (up != b) b.recycle();
        }
        Key k = new Key();
        k.upright = up;
        k.pose = g.pose.clone();
        k.fx = g.ifx;
        k.fy = g.ify;
        k.cx = g.icx;
        k.cy = g.icy;
        k.iw = g.iw;
        k.ih = g.ih;
        k.rot = g.rot;
        k.az = az;
        k.sector = sector;
        synchronized (keys) { keys.add(k); }
    }

    // ================================================================ ✅ the final surface, measured and kept

    private void done() {
        if (phase != 1 && phase != 2) return;
        if (integrated < 12) { Toast.makeText(this, "ఇంకా కొంచెం స్కాన్ చేయండి (చుట్టూ నెమ్మదిగా)", Toast.LENGTH_SHORT).show(); return; }
        phase = 3;
        refresh();
        final long secs = (System.currentTimeMillis() - startedAt) / 1000;
        work.execute(() -> {
            String err = null, id = null;
            try {
                Tsdf v = vol;
                if (v == null) throw new IllegalStateException();
                Tsdf.Mesh m = Tsdf.keepMain(v.extract(1), 0.03f);
                if (m.tris < 400) err = "సరిగా రాలేదు: ఇంకా నెమ్మదిగా, దగ్గరగా (30 సెం.మీ – 2 మీ) తిరిగి స్కాన్ చేయండి.";
                else id = save(m, secs);
            } catch (OutOfMemoryError e) {
                err = "ఫోన్ మెమరీ సరిపోలేదు. చిన్న సైజుతో మళ్ళీ ప్రయత్నించండి.";
            } catch (Throwable t) {
                err = "3D సేవ్ కాలేదు (" + t.getClass().getSimpleName() + ").";
            }
            final String er = err, sid = id;
            main.post(() -> {
                if (destroyed) return;
                if (er != null) { phase = 2; refresh(); hint.setText(er); return; }
                phase = 4;
                RealModelActivity.open(this, sid);
                finish();
            });
        });
    }

    private String save(Tsdf.Mesh m, long secs) throws Exception {
        float[] dims = Tsdf.dimsCm(m);
        int W = Math.round(dims[0]), D = Math.round(dims[1]), H = Math.round(dims[2]);
        String id = ScanStore.newId();
        File dir = ScanStore.dir(this, id);
        Tsdf.write(m, new File(dir, "mesh.bin"));
        JSONArray kf = new JSONArray();
        List<Key> ks;
        synchronized (keys) { ks = new ArrayList<>(keys); }
        if (!ks.isEmpty()) { // round the thing from the side he started at (the first kept photo is the front)
            final float a0 = ks.get(0).az;
            java.util.Collections.sort(ks, (a, b) -> Float.compare(((a.az - a0) % 360 + 360) % 360, ((b.az - a0) % 360 + 360) % 360));
        }
        for (int i = 0; i < ks.size(); i++) {
            Key k = ks.get(i);
            String name = "kf" + i + ".jpg";
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { k.upright.compress(Bitmap.CompressFormat.JPEG, 88, o); }
            JSONArray pose = new JSONArray();
            for (float x : k.pose) pose.put((double) x);
            kf.put(new JSONObject().put("file", name).put("pose", pose).put("fx", k.fx).put("fy", k.fy).put("cx", k.cx).put("cy", k.cy)
                    .put("iw", k.iw).put("ih", k.ih).put("rot", k.rot).put("az", Math.round(k.az)));
        }
        int sides = 0;
        for (int c : sectors) if (c >= 2) sides++;
        String say = "📐 నిజమైన 3D స్కాన్: సుమారు " + W + " × " + D + " × " + H + " సెం.మీ (వెడల్పు × లోతు × ఎత్తు; ఫోన్ కొలిచింది, కొన్ని సెం.మీ తేడా రావచ్చు). "
                + "చుట్టూ " + sides + "/12 వైపులు" + (topSeen ? ", పై నుంచి కూడా" : "") + ". చూడని చోట్లు ఖాళీగా ఉంటాయి.";
        JSONObject data = new JSONObject().put("id", id).put("title", "📐 3D స్కాన్ " + W + "×" + D + "×" + H + " సెం.మీ").put("kind", "real3d").put("mode", "auto")
                .put("say", say).put("dims_cm", new JSONObject().put("width", W).put("depth", D).put("height", H).put("note", "ఫోన్ కొలిచింది (ARCore లోతు)"))
                .put("tris", m.tris).put("frames", integrated).put("secs", secs).put("size_m", boxSize).put("sides", sides).put("top_seen", topSeen)
                .put("kf", kf).put("estimate", false).put("noboxes", true);
        ScanStore.save(this, data, ks.isEmpty() ? null : ks.get(0).upright, "photo.jpg");
        return id;
    }

    // ================================================================ the ring of sides done

    /** Twelve sides round the thing: lit once scanned; the middle says how many; ⬆️ when the top was seen. */
    static final class Coverage extends View {
        private int[] s = new int[12];
        private boolean top;
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Coverage(Context c) {
            super(c);
            on.setStyle(Paint.Style.STROKE);
            off.setStyle(Paint.Style.STROKE);
            on.setColor(Ui.CYAN);
            off.setColor(0x44FFFFFF);
            txt.setColor(0xFFFFFFFF);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        void set(int[] sectors, boolean topSeen) { s = sectors.clone(); top = topSeen; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sw = Math.min(w, h) * 0.14f;
            on.setStrokeWidth(sw);
            off.setStrokeWidth(sw);
            r.set(sw, sw, w - sw, h - sw);
            int done = 0;
            for (int i = 0; i < 12; i++) {
                boolean lit = s[i] >= 2;
                if (lit) done++;
                c.drawArc(r, i * 30 + 2, 26, false, lit ? on : off);
            }
            txt.setTextSize(Math.min(w, h) * 0.26f);
            c.drawText(top ? "⬆️" : String.valueOf(done), w / 2, h / 2 + txt.getTextSize() * 0.35f, txt);
        }
    }
}
