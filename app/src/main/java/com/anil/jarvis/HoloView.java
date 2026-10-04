package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The hologram: the scanned board (or engine, machine, map, a device's layers) as a 3D model in OpenGL ES 2.0, with
 * every part lifted off it and floating, glowing lines down to where it sits. Each part is a small 3D shape of its kind
 * (a chip with pins, a cylinder capacitor, a resistor with its colour bands, an LED dome, a map pin...) with the real
 * photo of that part on its top. Drag turns the model, pinch zooms; a tapped part comes forward, big, and turns with the
 * finger. It can show the connections, the sections in colours, the faults pulsing red, an X-ray look, test points,
 * the building order (each part flying into its place), and a device's layers one above the other to peel off.
 * The parts' screen places go to the label layer (HoloLabels) every frame.
 */
final class HoloView extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Listener {
        /** A part was tapped (its index), or -1 for empty space. Main thread. */
        void picked(int index);
        /** A frame is ready to record (recording only; the GL thread). */
        default void recordFrame(Bitmap b) {}
        /** Is a frame wanted now (the recorder is not behind)? Asked before reading one. */
        default boolean wantFrame() { return true; }
    }

    /** One part in the model. */
    static final class Part {
        ScanBrain.Item it;
        int layer;
        float cx, cz, w, dz, h; // where on its layer's plane (world units) and its size
        float[] uv;             // its photo on the layer's picture (u0, v0, u1, v1), or null
        int color;              // its section's colour
        int[] bands;            // a resistor's colour bands, or null
    }

    /** One plane: the board, or a layer of an opened device. */
    static final class Layer {
        Bitmap photo;
        float aspect = 1f;
        String name = "";
        int tex;
    }

    static final class Test { float cx, cz; int layer; String text = ""; }

    static final class Scene {
        final List<Layer> layers = new ArrayList<>();
        final List<Part> parts = new ArrayList<>();
        final List<int[]> links = new ArrayList<>();   // pairs of part indexes
        final List<Test> tests = new ArrayList<>();
        final List<Integer> order = new ArrayList<>(); // part indexes in building order
        boolean generic;                               // a general model (no photo of the parts)
    }

    private static final float LAYER_GAP = 1.15f;

    private final Context ctx;
    Listener listener;
    private volatile Scene scene;
    private boolean sceneLoaded;

    // the view, set from the touch (main thread), read each frame
    volatile float yaw = 0f, pitch = 52f, dist = 4.4f;
    volatile float partYaw, partPitch;
    volatile int selected = -1;
    volatile boolean exploded = true, showBoard = true, showParts = true, showLinks = false, showSections = false, xray = false, showTests = false, faultsOnly = false;
    volatile String sectionFilter = null;
    volatile int peel;                 // how many top layers are lifted away
    volatile int asmStep = -1;         // building order: the part being placed (-1 = off)
    volatile boolean spin;             // turning by itself (for the video)

    // animated values (GL thread)
    private float explodeCur = 0f, focusCur = 0f, peelCur = 0f, asmT = 1f;
    private int lastAsm = -1, lastSel = -1;

    // the label layer reads these (each frame: x, y, shown, for each part; then each test)
    volatile float[] screen = new float[0];
    volatile int viewW = 1, viewH = 1;

    private int litProg, lineProg;
    private int aPos, aNor, aUV, uMVP, uM, uTex, uUseTex, uColor, uUV, uEye, uRim, uTime, uGlow;
    private int lPos, lMVP, lColor;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16], model = new float[16], mvp = new float[16], eye = new float[3];
    private FloatBuffer boxSides, boxTop, cylSide, disc, dome, lineBuf;
    private int boxSidesN, boxTopN, cylSideN, discN, domeN;
    private long t0 = SystemClock.uptimeMillis();
    private volatile boolean recording;
    private int recEvery;

    HoloView(Context c) {
        super(c);
        ctx = c;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    void setScene(Scene s) {
        queueEvent(() -> {
            Scene old = scene;
            if (old != null) for (Layer l : old.layers) if (l.tex != 0) GLES20.glDeleteTextures(1, new int[]{l.tex}, 0);
            scene = s;
            sceneLoaded = false;
            if (litProg != 0) load();
        });
    }

    Scene scene() { return scene; }

    void record(boolean on) {
        recording = on;
        recEvery = 0;
        if (!on) { Bitmap b; while ((b = pool.poll()) != null) b.recycle(); } // (frames still being written come back and go)
    }

    // ================================================================ GL

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        GLES20.glClearColor(0.008f, 0.03f, 0.06f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        litProg = program(LIT_V, LIT_F);
        aPos = GLES20.glGetAttribLocation(litProg, "aPos");
        aNor = GLES20.glGetAttribLocation(litProg, "aNor");
        aUV = GLES20.glGetAttribLocation(litProg, "aUV");
        uMVP = GLES20.glGetUniformLocation(litProg, "uMVP");
        uM = GLES20.glGetUniformLocation(litProg, "uM");
        uTex = GLES20.glGetUniformLocation(litProg, "uTex");
        uUseTex = GLES20.glGetUniformLocation(litProg, "uUseTex");
        uColor = GLES20.glGetUniformLocation(litProg, "uColor");
        uUV = GLES20.glGetUniformLocation(litProg, "uUV");
        uEye = GLES20.glGetUniformLocation(litProg, "uEye");
        uRim = GLES20.glGetUniformLocation(litProg, "uRim");
        uTime = GLES20.glGetUniformLocation(litProg, "uTime");
        uGlow = GLES20.glGetUniformLocation(litProg, "uGlow");
        lineProg = program(LINE_V, LINE_F);
        lPos = GLES20.glGetAttribLocation(lineProg, "aPos");
        lMVP = GLES20.glGetUniformLocation(lineProg, "uMVP");
        lColor = GLES20.glGetUniformLocation(lineProg, "uColor");
        meshes();
        lineBuf = ByteBuffer.allocateDirect(4 * 3 * 2 * 4096).order(ByteOrder.nativeOrder()).asFloatBuffer();
        if (scene != null) {
            for (Layer l : scene.layers) l.tex = 0; // a new context: the old textures are gone
            load();
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        Matrix.perspectiveM(proj, 0, 40f, w / (float) Math.max(1, h), 0.05f, 60f);
    }

    /** The layers' pictures become textures. */
    private void load() {
        Scene s = scene;
        if (s == null) return;
        for (Layer l : s.layers) {
            if (l.photo == null || l.tex != 0) continue;
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, l.photo, 0);
            l.tex = t[0];
        }
        sceneLoaded = true;
    }

    /** Where a layer's plane is (its height). */
    private float layerY(int k) { return -k * LAYER_GAP; }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Scene s = scene;
        if (s == null || !sceneLoaded) return;
        float time = (SystemClock.uptimeMillis() - t0) / 1000f;
        // the animated values ease towards what was asked
        explodeCur += ((exploded ? 1f : 0f) - explodeCur) * 0.08f;
        focusCur += ((selected >= 0 ? 1f : 0f) - focusCur) * 0.12f;
        peelCur += (peel - peelCur) * 0.08f;
        if (asmStep != lastAsm) { lastAsm = asmStep; asmT = 0f; }
        asmT = Math.min(1f, asmT + 0.025f);
        if (selected >= 0) lastSel = selected;
        if (spin) yaw += 0.75f;

        // the camera looks at the middle of the layers
        float midY = s.layers.size() > 1 ? layerY(s.layers.size() - 1) / 2f : 0.15f;
        float pr = (float) Math.toRadians(Math.max(8, Math.min(88, pitch))), yr = (float) Math.toRadians(yaw);
        float dd = dist * (s.layers.size() > 1 ? 1f + 0.25f * (s.layers.size() - 1) : 1f);
        eye[0] = (float) (dd * Math.cos(pr) * Math.sin(yr));
        eye[1] = midY + (float) (dd * Math.sin(pr));
        eye[2] = (float) (dd * Math.cos(pr) * Math.cos(yr));
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0, midY, 0, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(litProg);
        GLES20.glUniform3f(uEye, eye[0], eye[1], eye[2]);
        GLES20.glUniform1f(uTime, time);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // ---- the planes
        for (int k = 0; k < s.layers.size(); k++) {
            Layer l = s.layers.get(k);
            float gone = Math.max(0f, Math.min(1f, peelCur - k)); // lifted away
            if (gone > 0.98f) continue;
            float y = layerY(k) + gone * 3f;
            float alpha = (xray ? 0.28f : 0.96f) * (1f - gone) * (showBoard ? 1f : 0.12f) * (s.generic ? 0.35f : 1f);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, 0, y - 0.04f, 0);
            Matrix.scaleM(model, 0, 2f, 0.04f, 2f * l.aspect);
            drawLit(boxSides, boxSidesN, 0, false, null, 0xFF0B3B4F, alpha, 0.6f, 0.3f);
            drawLit(boxTop, boxTopN, l.tex, l.tex != 0 && !s.generic, new float[]{0, 0, 1, 1}, 0xFF0B3B4F, alpha, 0.2f, 0.25f);
        }

        // ---- the parts
        int n = s.parts.size();
        float[] scr = new float[(n + s.tests.size()) * 3];
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 5f);
        if (xray) { GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE); GLES20.glDepthMask(false); }
        for (int i = 0; i < n; i++) {
            Part pt = s.parts.get(i);
            float gone = Math.max(0f, Math.min(1f, peelCur - pt.layer));
            if (gone > 0.98f || !showParts) continue;
            float[] pose = pose(s, i, pt, time, gone);
            if (pose == null) continue;
            float px = pose[0], py = pose[1], pz = pose[2], sc = pose[3], alpha = pose[4];
            boolean sel = i == selected || (selected < 0 && i == lastSel && focusCur > 0.02f);
            int col = pt.it.fault ? lerpColor(0xFFFF3B4D, 0xFFFFB3B9, pulse) : (showSections || sectionFilter != null ? pt.color : 0xFF1C5F78);
            if (faultsOnly && !pt.it.fault) alpha *= 0.15f;
            if (sectionFilter != null && !sectionFilter.equals(pt.it.section)) alpha *= 0.15f;
            float rim = sel ? 1.4f : pt.it.fault ? 1.2f : 0.7f, glow = sel ? 0.9f : 0.35f;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, px, py, pz);
            if (sel && focusCur > 0.02f) {
                Matrix.rotateM(model, 0, partYaw * focusCur, 0, 1, 0);
                Matrix.rotateM(model, 0, partPitch * focusCur, 1, 0, 0);
            }
            Matrix.scaleM(model, 0, sc, sc, sc);
            drawPart(s, pt, col, alpha, rim, glow);
            // its place on the screen, for the label (the top of the part)
            float[] top = {px, py + pt.h * sc + 0.03f, pz, 1f}, c = new float[4];
            Matrix.multiplyMV(c, 0, vp, 0, top, 0);
            if (c[3] > 0.01f) {
                scr[i * 3] = (c[0] / c[3] * 0.5f + 0.5f) * viewW;
                scr[i * 3 + 1] = (1f - (c[1] / c[3] * 0.5f + 0.5f)) * viewH;
                scr[i * 3 + 2] = alpha > 0.3f ? 1f : 0f;
            }
        }
        if (xray) { GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); GLES20.glDepthMask(true); }

        // ---- the lines: each part down to its place, the connections, the test points, a frame around each plane
        GLES20.glUseProgram(lineProg);
        GLES20.glDepthMask(false);
        lineBuf.clear();
        int segs = 0;
        for (int k = 0; k < s.layers.size(); k++) {
            float gone = Math.max(0f, Math.min(1f, peelCur - k));
            if (gone > 0.98f) continue;
            Layer l = s.layers.get(k);
            float y = layerY(k) + gone * 3f + 0.002f, a = l.aspect;
            segs += rect(-1, -a, 1, a, y);
        }
        drawLines(segs, Ui.CYAN, 0.55f);
        lineBuf.clear();
        segs = 0;
        if (showParts && explodeCur > 0.05f) {
            for (int i = 0; i < n && segs < 1500; i++) {
                Part pt = s.parts.get(i);
                if (pt.uv == null && s.generic) continue;
                float gone = Math.max(0f, Math.min(1f, peelCur - pt.layer));
                if (gone > 0.98f) continue;
                float[] pose = pose(s, i, pt, time, gone);
                if (pose == null || pose[4] < 0.3f || i == selected) continue;
                float by = layerY(pt.layer) + gone * 3f;
                seg(pt.cx, by, pt.cz, pose[0], pose[1], pose[2]);
                segs++;
            }
        }
        drawLines(segs, Ui.CYAN, 0.35f);
        if (showLinks || selected >= 0) {
            lineBuf.clear();
            segs = 0;
            for (int[] lk : s.links) {
                if (lk[0] >= n || lk[1] >= n) continue;
                if (selected >= 0 && lk[0] != selected && lk[1] != selected) continue;
                if (!showLinks && selected < 0) continue;
                float[] a = pose(s, lk[0], s.parts.get(lk[0]), time, 0), b = pose(s, lk[1], s.parts.get(lk[1]), time, 0);
                if (a == null || b == null) continue;
                seg(a[0], a[1] + 0.02f, a[2], b[0], b[1] + 0.02f, b[2]);
                segs++;
            }
            drawLines(segs, selected >= 0 ? 0xFFFFE27A : 0xFF6BE3A4, 0.85f);
        }
        if (showTests && !s.tests.isEmpty()) {
            lineBuf.clear();
            segs = 0;
            for (int i = 0; i < s.tests.size(); i++) {
                Test t = s.tests.get(i);
                float y = layerY(t.layer);
                seg(t.cx, y, t.cz, t.cx, y + 0.55f, t.cz);
                segs++;
                for (int k = 0; k < 12; k++) { // a small ring on the board
                    double a0 = k * Math.PI / 6, a1 = (k + 1) * Math.PI / 6;
                    seg(t.cx + 0.05f * (float) Math.cos(a0), y + 0.004f, t.cz + 0.05f * (float) Math.sin(a0),
                            t.cx + 0.05f * (float) Math.cos(a1), y + 0.004f, t.cz + 0.05f * (float) Math.sin(a1));
                    segs++;
                }
                float[] c = new float[4];
                Matrix.multiplyMV(c, 0, vp, 0, new float[]{t.cx, y + 0.58f, t.cz, 1f}, 0);
                int o = (n + i) * 3;
                if (c[3] > 0.01f) {
                    scr[o] = (c[0] / c[3] * 0.5f + 0.5f) * viewW;
                    scr[o + 1] = (1f - (c[1] / c[3] * 0.5f + 0.5f)) * viewH;
                    scr[o + 2] = 1f;
                }
            }
            drawLines(segs, 0xFFFFE27A, 0.9f);
        }
        GLES20.glDepthMask(true);
        screen = scr;
        if (recording && listener != null && (recEvery++ % 2 == 0) && listener.wantFrame()) listener.recordFrame(readPixels());
    }

    /**
     * Where part i is now: {x, y, z, scale, alpha}, from where it sits, how far the model is pulled apart, the building
     * order and whether it is the tapped one (which comes forward, big).
     */
    private float[] pose(Scene s, int i, Part pt, float time, float gone) {
        float base = layerY(pt.layer) + gone * 3f;
        float lift = explodeCur * (0.28f + 0.14f * (i % 3)) + explodeCur * 0.025f * (float) Math.sin(time * 1.6f + i);
        float alpha = 1f - gone;
        if (s.generic) lift = 0.05f + explodeCur * (0.2f + 0.1f * (i % 2));
        if (asmStep >= 0 && !s.order.isEmpty()) {
            int j = s.order.indexOf(i);
            if (j >= 0) {
                if (j < asmStep) { lift = 0; }
                else if (j == asmStep) { float e = 1f - asmT; lift = 0.6f * e * e; }
                else { lift = 0.75f + 0.05f * (float) Math.sin(time + i); alpha *= 0.28f; }
            }
        }
        float x = pt.cx, y = base + lift, z = pt.cz, sc = 1f;
        int focus = selected >= 0 ? selected : lastSel;
        if (i == focus && focusCur > 0.01f) {
            float big = Math.min(4.5f, 0.9f / Math.max(0.05f, Math.max(pt.w, Math.max(pt.dz, pt.h))));
            float midY = s.layers.size() > 1 ? layerY(s.layers.size() - 1) / 2f : 0.15f;
            // in front of the camera, a little below the middle of the screen
            float fx = eye[0] * 0.45f, fy = midY + 0.35f + (eye[1] - midY) * 0.45f, fz = eye[2] * 0.45f;
            float f = focusCur;
            x = x + (fx - x) * f;
            y = y + (fy - y) * f;
            z = z + (fz - z) * f;
            sc = 1f + (big - 1f) * f;
        } else if (selected >= 0) {
            alpha *= 1f - 0.75f * focusCur;
        }
        return new float[]{x, y, z, sc, alpha};
    }

    /** A part's shape: a box with its photo on top, a cylinder, a resistor lying down with its bands, a dome, a pin... */
    private void drawPart(Scene s, Part pt, int col, float alpha, float rim, float glow) {
        Layer l = pt.layer < s.layers.size() ? s.layers.get(pt.layer) : null;
        int tex = l == null ? 0 : l.tex;
        boolean photo = pt.uv != null && tex != 0 && !s.generic;
        float[] base = model.clone();
        String sh = pt.it.shape;
        switch (sh) {
            case "cap_e": case "battery": case "motor": case "speaker": case "crystal": case "inductor": {
                float r = Math.min(pt.w, pt.dz);
                Matrix.scaleM(model, 0, r, pt.h, r);
                drawLit(cylSide, cylSideN, 0, false, null, col, alpha, rim, glow);
                drawLit(disc, discN, tex, photo, pt.uv, col, alpha, rim * 0.5f, glow * 0.5f);
                break;
            }
            case "led": {
                float r = Math.min(pt.w, pt.dz);
                Matrix.scaleM(model, 0, r, pt.h * 0.55f, r);
                drawLit(cylSide, cylSideN, 0, false, null, col, alpha, rim, glow);
                System.arraycopy(base, 0, model, 0, 16);
                Matrix.translateM(model, 0, 0, pt.h * 0.55f, 0);
                Matrix.scaleM(model, 0, r, r, r);
                drawLit(dome, domeN, tex, photo, pt.uv, lerpColor(col, 0xFFFF6B5E, 0.4f), alpha * 0.9f, rim * 1.4f, glow * 1.5f);
                break;
            }
            case "resistor": case "diode": case "fuse": {
                // lying along its long side, with leads
                boolean alongX = pt.w >= pt.dz;
                float len = Math.max(pt.w, pt.dz), r = Math.max(0.012f, Math.min(pt.w, pt.dz) * 0.5f);
                Matrix.translateM(model, 0, 0, r, 0);
                if (alongX) Matrix.rotateM(model, 0, 90, 0, 0, 1); else Matrix.rotateM(model, 0, 90, 1, 0, 0);
                float[] lay = model.clone();
                Matrix.translateM(model, 0, 0, -len * 0.35f, 0);
                Matrix.scaleM(model, 0, r * 2f, len * 0.7f, r * 2f);
                int body = "resistor".equals(sh) ? 0xFFD8C9A3 : "diode".equals(sh) ? 0xFF2B2B2B : 0xFFBFD8E6;
                drawLit(cylSide, cylSideN, 0, false, null, lerpColor(body, col, 0.15f), alpha, rim, glow);
                if (pt.bands != null) {
                    for (int b = 0; b < pt.bands.length; b++) {
                        System.arraycopy(lay, 0, model, 0, 16);
                        Matrix.translateM(model, 0, 0, -len * 0.25f + b * len * 0.13f, 0);
                        Matrix.scaleM(model, 0, r * 2.08f, len * 0.06f, r * 2.08f);
                        drawLit(cylSide, cylSideN, 0, false, null, pt.bands[b], alpha, 0.2f, 0.1f);
                    }
                }
                // the leads
                System.arraycopy(lay, 0, model, 0, 16);
                Matrix.translateM(model, 0, 0, -len * 0.5f, 0);
                Matrix.scaleM(model, 0, r * 0.35f, len, r * 0.35f);
                drawLit(cylSide, cylSideN, 0, false, null, 0xFFB8C4CC, alpha, 0.3f, 0.1f);
                break;
            }
            case "pin": {
                float r = 0.05f;
                Matrix.scaleM(model, 0, r * 0.5f, pt.h * 0.25f, r * 0.5f);
                drawLit(cylSide, cylSideN, 0, false, null, 0xFFDDEFF5, alpha, 0.3f, 0.2f);
                System.arraycopy(base, 0, model, 0, 16);
                Matrix.translateM(model, 0, 0, pt.h * 0.25f, 0);
                Matrix.scaleM(model, 0, r * 2.4f, r * 2.4f, r * 2.4f);
                drawLit(dome, domeN, 0, false, null, col == 0xFF1C5F78 ? 0xFFFF4D5E : col, alpha, 1.2f, 1f);
                break;
            }
            case "screw": {
                float r = Math.max(0.02f, Math.min(pt.w, pt.dz) * 0.5f);
                Matrix.scaleM(model, 0, r * 2f, pt.h, r * 2f);
                drawLit(cylSide, cylSideN, 0, false, null, 0xFFC9D3DA, alpha, 0.8f, 0.4f);
                drawLit(disc, discN, tex, photo, pt.uv, 0xFFC9D3DA, alpha, 0.4f, 0.3f);
                break;
            }
            default: { // a block with the real photo on top (chips, modules, connectors, relays, anything)
                Matrix.scaleM(model, 0, Math.max(0.02f, pt.w), pt.h, Math.max(0.02f, pt.dz));
                drawLit(boxSides, boxSidesN, 0, false, null, col, alpha, rim, glow);
                drawLit(boxTop, boxTopN, tex, photo, pt.uv, col, alpha, rim * 0.4f, glow * 0.4f);
                if ("ic".equals(sh) || "chip".equals(sh)) { // its pins along the long sides
                    boolean alongX = pt.w >= pt.dz;
                    float len = alongX ? pt.w : pt.dz, side = alongX ? pt.dz : pt.w;
                    int pins = Math.max(3, Math.min(10, Math.round(len / 0.035f)));
                    for (int k = 0; k < pins; k++) {
                        float off = -len / 2 + (k + 0.5f) * len / pins;
                        for (int sgn = -1; sgn <= 1; sgn += 2) {
                            System.arraycopy(base, 0, model, 0, 16);
                            if (alongX) Matrix.translateM(model, 0, off, 0, sgn * (side / 2 + 0.012f));
                            else Matrix.translateM(model, 0, sgn * (side / 2 + 0.012f), 0, off);
                            Matrix.scaleM(model, 0, alongX ? len / pins * 0.45f : 0.024f, pt.h * 0.45f, alongX ? 0.024f : len / pins * 0.45f);
                            drawLit(boxSides, boxSidesN, 0, false, null, 0xFFB8C4CC, alpha, 0.3f, 0.1f);
                        }
                    }
                }
            }
        }
    }

    private void drawLit(FloatBuffer mesh, int count, int tex, boolean useTex, float[] uv, int color, float alpha, float rim, float glow) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uM, 1, false, model, 0);
        GLES20.glUniform4f(uColor, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Math.max(0f, Math.min(1f, alpha)));
        GLES20.glUniform1f(uUseTex, useTex ? 1f : 0f);
        if (useTex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform4f(uUV, uv[0], uv[1], uv[2], uv[3]);
        } else {
            GLES20.glUniform4f(uUV, 0, 0, 1, 1);
        }
        GLES20.glUniform1f(uRim, xray ? rim * 1.6f + 0.4f : rim);
        GLES20.glUniform1f(uGlow, glow);
        mesh.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, mesh);
        GLES20.glEnableVertexAttribArray(aPos);
        mesh.position(3);
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 32, mesh);
        GLES20.glEnableVertexAttribArray(aNor);
        mesh.position(6);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, mesh);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        mesh.position(0);
    }

    private void seg(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (lineBuf.remaining() < 6) return;
        lineBuf.put(x0).put(y0).put(z0).put(x1).put(y1).put(z1);
    }

    private int rect(float x0, float z0, float x1, float z1, float y) {
        seg(x0, y, z0, x1, y, z0); seg(x1, y, z0, x1, y, z1); seg(x1, y, z1, x0, y, z1); seg(x0, y, z1, x0, y, z0);
        return 4;
    }

    private void drawLines(int segs, int color, float alpha) {
        if (segs <= 0) return;
        GLES20.glUniformMatrix4fv(lMVP, 1, false, vp, 0);
        GLES20.glUniform4f(lColor, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, alpha);
        lineBuf.position(0);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, lineBuf);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glLineWidth(2.5f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, segs * 2);
    }

    private ByteBuffer readBuf;
    private final java.util.concurrent.ConcurrentLinkedQueue<Bitmap> pool = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The frame as a picture (one buffer, and a few pictures handed back by the recorder, are used again). */
    private Bitmap readPixels() {
        int w = viewW, h = viewH;
        if (readBuf == null || readBuf.capacity() != w * h * 4) readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        readBuf.rewind();
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf);
        Bitmap b = pool.poll();
        while (b != null && (b.isRecycled() || b.getWidth() != w || b.getHeight() != h)) b = pool.poll();
        if (b == null) b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        readBuf.rewind();
        b.copyPixelsFromBuffer(readBuf);
        return b; // (upside down: the recorder flips it)
    }

    /** The recorder is done with a frame's picture: kept for the next one. */
    void giveBack(Bitmap b) {
        if (b == null) return;
        if (recording && pool.size() < 3) pool.offer(b); else b.recycle();
    }

    /** The part under a tap (GL thread; the answer goes to the listener on the main thread). */
    void pick(float sx, float sy) {
        queueEvent(() -> {
            Scene s = scene;
            int hit = -1;
            if (s != null) {
                float[] inv = new float[16];
                if (Matrix.invertM(inv, 0, vp, 0)) {
                    float nx = sx / viewW * 2f - 1f, ny = 1f - sy / viewH * 2f;
                    float[] a = new float[4], b = new float[4];
                    Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1f, 1f}, 0);
                    Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1f, 1f}, 0);
                    for (int k = 0; k < 3; k++) { a[k] /= a[3]; b[k] /= b[3]; }
                    float[] dir = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
                    float best = Float.MAX_VALUE;
                    float time = (SystemClock.uptimeMillis() - t0) / 1000f;
                    for (int i = 0; i < s.parts.size(); i++) {
                        Part pt = s.parts.get(i);
                        float gone = Math.max(0f, Math.min(1f, peelCur - pt.layer));
                        if (gone > 0.9f || !showParts) continue;
                        float[] p = pose(s, i, pt, time, gone);
                        if (p == null || p[4] < 0.2f) continue;
                        float hw = Math.max(0.04f, pt.w * p[3] / 2), hd = Math.max(0.04f, pt.dz * p[3] / 2), hh = Math.max(0.05f, pt.h * p[3]);
                        float t = ray(a, dir, p[0] - hw, p[1], p[2] - hd, p[0] + hw, p[1] + hh, p[2] + hd);
                        if (t >= 0 && t < best) { best = t; hit = i; }
                    }
                }
            }
            final int h = hit;
            post(() -> { if (listener != null) listener.picked(h); });
        });
    }

    /** Where a ray first meets a box (its t), or -1. */
    private static float ray(float[] o, float[] d, float x0, float y0, float z0, float x1, float y1, float z1) {
        float tmin = 0, tmax = Float.MAX_VALUE;
        float[] lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-6f) { if (o[k] < lo[k] || o[k] > hi[k]) return -1; continue; }
            float t1 = (lo[k] - o[k]) / d[k], t2 = (hi[k] - o[k]) / d[k];
            if (t1 > t2) { float x = t1; t1 = t2; t2 = x; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    // ================================================================ building the scene from a scan

    /** The scene for a saved scan: its board (or layers) and parts, links, tests and building order. */
    static Scene build(Context c, org.json.JSONObject data) {
        Scene s = new Scene();
        String id = data.optString("id");
        org.json.JSONArray layers = data.optJSONArray("layers");
        List<org.json.JSONArray> itemSets = new ArrayList<>();
        if (layers != null && layers.length() > 0) {
            for (int k = 0; k < layers.length(); k++) {
                org.json.JSONObject lo = layers.optJSONObject(k);
                if (lo == null) continue;
                Layer l = new Layer();
                l.name = lo.optString("name");
                l.photo = ScanStore.photo(c, id, lo.optString("photo"), 1600);
                if (l.photo != null) l.aspect = l.photo.getHeight() / (float) l.photo.getWidth();
                s.layers.add(l);
                itemSets.add(lo.optJSONArray("items"));
            }
        } else {
            Layer l = new Layer();
            l.name = data.optString("title");
            l.photo = ScanStore.photo(c, id, "photo.jpg", 1800);
            if (l.photo != null) l.aspect = l.photo.getHeight() / (float) l.photo.getWidth();
            s.layers.add(l);
            itemSets.add(data.optJSONArray("items"));
        }
        // the sections' colours
        java.util.Map<String, Integer> colors = new java.util.HashMap<>();
        org.json.JSONArray secs = data.optJSONArray("sections");
        int[] pal = {0xFF22D3EE, 0xFFF2B24C, 0xFF6BE3A4, 0xFFB794F6, 0xFFFF8FAB, 0xFF60A5FA, 0xFFFACC15};
        for (int i = 0; secs != null && i < secs.length(); i++) {
            org.json.JSONObject so = secs.optJSONObject(i);
            if (so == null) continue;
            int col = pal[i % pal.length];
            try { col = Color.parseColor(so.optString("color", "#000000").trim()); } catch (Exception ignored) {}
            if (Color.red(col) + Color.green(col) + Color.blue(col) < 140) col = pal[i % pal.length]; // too dark to see
            colors.put(so.optString("name"), col | 0xFF000000);
        }
        java.util.Map<Integer, Integer> byN = new java.util.HashMap<>();
        boolean anyBox = false;
        for (int k = 0; k < itemSets.size(); k++) {
            org.json.JSONArray set = itemSets.get(k);
            for (int i = 0; set != null && i < set.length() && s.parts.size() < 120; i++) {
                ScanBrain.Item it = ScanBrain.item(set.optJSONObject(i), s.parts.size() + 1);
                if (it == null) continue;
                Part p = new Part();
                p.it = it;
                p.layer = k;
                Integer col = colors.get(it.section);
                if (col == null) { col = pal[Math.abs(it.section.hashCode()) % pal.length]; if (!it.section.isEmpty()) colors.put(it.section, col); }
                p.color = col;
                if (k == 0) byN.put(it.n, s.parts.size());
                if (it.box != null) anyBox = true;
                s.parts.add(p);
            }
        }
        s.generic = !anyBox;
        // places and sizes: from the boxes on the photo, or (a general model) in a neat grid on its plane
        int[] perLayer = new int[Math.max(1, s.layers.size())];
        for (Part p : s.parts) perLayer[Math.min(p.layer, perLayer.length - 1)]++;
        int[] seen = new int[perLayer.length];
        for (Part p : s.parts) {
            Layer l = s.layers.get(Math.min(p.layer, s.layers.size() - 1));
            float a = l.aspect;
            if (p.it.box != null) {
                float[] b = p.it.box;
                p.cx = -1f + (b[0] + b[2]);       // (x0 + x1) / 2 * 2 - 1
                p.cz = -a + a * (b[1] + b[3]);
                p.w = Math.max(0.03f, (b[2] - b[0]) * 2f);
                p.dz = Math.max(0.03f, (b[3] - b[1]) * 2f * a);
                p.uv = new float[]{b[0], b[1], b[2], b[3]};
            } else {
                int k = Math.min(p.layer, perLayer.length - 1), cnt = perLayer[k], idx = seen[k]++;
                int cols = Math.max(1, (int) Math.ceil(Math.sqrt(cnt)));
                int rows = (int) Math.ceil(cnt / (float) cols);
                float cw = 2f / cols, ch = 2f * a / Math.max(1, rows);
                p.cx = -1f + cw * (idx % cols + 0.5f);
                p.cz = -a + ch * (idx / cols + 0.5f);
                p.w = Math.min(cw, ch) * 0.55f;
                p.dz = p.w;
            }
            p.h = Math.min(0.7f, 0.035f * p.it.h * (p.it.box == null ? 3f : 1f) + 0.012f);
            if ("resistor".equals(p.it.shape)) p.bands = bands(p.it.value);
        }
        org.json.JSONArray links = data.optJSONArray("links");
        for (int i = 0; links != null && i < links.length(); i++) {
            org.json.JSONArray lk = links.optJSONArray(i);
            if (lk == null || lk.length() < 2) continue;
            Integer a0 = byN.get(lk.optInt(0)), b0 = byN.get(lk.optInt(1));
            if (a0 != null && b0 != null && !a0.equals(b0)) s.links.add(new int[]{a0, b0});
        }
        org.json.JSONArray order = data.optJSONArray("order");
        for (int i = 0; order != null && i < order.length(); i++) {
            Integer x = byN.get(order.optInt(i));
            if (x != null && !s.order.contains(x)) s.order.add(x);
        }
        org.json.JSONArray tests = data.optJSONArray("tests");
        for (int i = 0; tests != null && i < tests.length(); i++) {
            org.json.JSONObject to = tests.optJSONObject(i);
            if (to == null) continue;
            Test t = new Test();
            float[] b = ScanBrain.box(to.optJSONArray("box"));
            Integer pi = byN.get(to.optInt("n", -1));
            Layer l = s.layers.get(0);
            if (b != null) { t.cx = -1f + (b[0] + b[2]); t.cz = -l.aspect + l.aspect * (b[1] + b[3]); }
            else if (pi != null) { t.cx = s.parts.get(pi).cx; t.cz = s.parts.get(pi).cz; }
            else continue;
            t.text = "TP" + (s.tests.size() + 1) + ": " + to.optString("expect") + (to.optString("where").isEmpty() ? "" : " · " + to.optString("where"));
            s.tests.add(t);
        }
        return s;
    }

    /** A resistor's colour bands from its value ("10kΩ", "4.7K", "220 ohm", "1M"), or null. */
    static int[] bands(String value) {
        if (value == null) return null;
        String v = value.toLowerCase(Locale.ROOT).replace("ω", "").replace("ohm", "").replace("Ω", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([rkm]?)(\\d*)").matcher(v);
        if (!m.find()) return null;
        double ohms;
        try {
            String num = m.group(1), unit = m.group(2), tail = m.group(3);
            if (!unit.isEmpty() && !tail.isEmpty() && !num.contains(".")) num = num + "." + tail; // 4k7
            ohms = Double.parseDouble(num) * (unit.equals("k") ? 1e3 : unit.equals("m") ? 1e6 : 1);
        } catch (Exception e) {
            return null;
        }
        if (ohms < 1 || ohms > 1e9) return null;
        int exp = (int) Math.floor(Math.log10(ohms)) - 1;
        long digits = Math.round(ohms / Math.pow(10, exp));
        if (digits >= 100) { digits /= 10; exp++; }
        int d1 = (int) (digits / 10), d2 = (int) (digits % 10);
        int[] col = {0xFF111111, 0xFF7A4A1E, 0xFFE0322B, 0xFFF08A1C, 0xFFF2D21C, 0xFF35A84A, 0xFF2B63D9, 0xFF8A3CC9, 0xFF8E8E8E, 0xFFF5F5F5};
        int mult = exp >= 0 && exp <= 9 ? col[exp] : 0xFFD4AF37;
        return new int[]{col[d1 % 10], col[d2], mult, 0xFFD4AF37};
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ================================================================ meshes and shaders

    private void meshes() {
        List<Float> v = new ArrayList<>();
        // the 4 sides and the bottom of a unit box: x, z in [-0.5, 0.5], y in [0, 1]
        quad(v, -0.5f, 0, 0.5f, 0.5f, 0, 0.5f, 0.5f, 1, 0.5f, -0.5f, 1, 0.5f, 0, 0, 1);    // front (+z)
        quad(v, 0.5f, 0, -0.5f, -0.5f, 0, -0.5f, -0.5f, 1, -0.5f, 0.5f, 1, -0.5f, 0, 0, -1); // back
        quad(v, 0.5f, 0, 0.5f, 0.5f, 0, -0.5f, 0.5f, 1, -0.5f, 0.5f, 1, 0.5f, 1, 0, 0);    // right
        quad(v, -0.5f, 0, -0.5f, -0.5f, 0, 0.5f, -0.5f, 1, 0.5f, -0.5f, 1, -0.5f, -1, 0, 0); // left
        quad(v, -0.5f, 0, -0.5f, 0.5f, 0, -0.5f, 0.5f, 0, 0.5f, -0.5f, 0, 0.5f, 0, -1, 0);  // bottom
        boxSides = buf(v);
        boxSidesN = v.size() / 8;
        v.clear();
        // the top, with its picture: u along x, v along z (down the photo)
        float[] top = {-0.5f, 1, 0.5f, 0.5f, 1, 0.5f, 0.5f, 1, -0.5f, -0.5f, 1, -0.5f};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        int[] idx = {0, 1, 2, 0, 2, 3};
        for (int i : idx) add(v, top[i * 3], top[i * 3 + 1], top[i * 3 + 2], 0, 1, 0, uv[i][0], uv[i][1]);
        boxTop = buf(v);
        boxTopN = v.size() / 8;
        v.clear();
        int S = 24;
        for (int i = 0; i < S; i++) { // a cylinder's side, radius 0.5, y 0..1
            double a0 = 2 * Math.PI * i / S, a1 = 2 * Math.PI * (i + 1) / S;
            float x0 = 0.5f * (float) Math.cos(a0), z0 = 0.5f * (float) Math.sin(a0), x1 = 0.5f * (float) Math.cos(a1), z1 = 0.5f * (float) Math.sin(a1);
            float nx0 = x0 * 2, nz0 = z0 * 2, nx1 = x1 * 2, nz1 = z1 * 2;
            add(v, x0, 0, z0, nx0, 0, nz0, 0, 0); add(v, x1, 0, z1, nx1, 0, nz1, 0, 0); add(v, x1, 1, z1, nx1, 0, nz1, 0, 0);
            add(v, x0, 0, z0, nx0, 0, nz0, 0, 0); add(v, x1, 1, z1, nx1, 0, nz1, 0, 0); add(v, x0, 1, z0, nx0, 0, nz0, 0, 0);
        }
        cylSide = buf(v);
        cylSideN = v.size() / 8;
        v.clear();
        for (int i = 0; i < S; i++) { // its top, with the picture
            double a0 = 2 * Math.PI * i / S, a1 = 2 * Math.PI * (i + 1) / S;
            float x0 = 0.5f * (float) Math.cos(a0), z0 = 0.5f * (float) Math.sin(a0), x1 = 0.5f * (float) Math.cos(a1), z1 = 0.5f * (float) Math.sin(a1);
            add(v, 0, 1, 0, 0, 1, 0, 0.5f, 0.5f);
            add(v, x1, 1, z1, 0, 1, 0, x1 + 0.5f, z1 + 0.5f);
            add(v, x0, 1, z0, 0, 1, 0, x0 + 0.5f, z0 + 0.5f);
        }
        disc = buf(v);
        discN = v.size() / 8;
        v.clear();
        int R = 8;
        for (int r = 0; r < R; r++) { // a half sphere, radius 0.5, from y = 0 up
            double b0 = Math.PI / 2 * r / R, b1 = Math.PI / 2 * (r + 1) / R;
            for (int i = 0; i < S; i++) {
                double a0 = 2 * Math.PI * i / S, a1 = 2 * Math.PI * (i + 1) / S;
                float[][] pts = {sph(a0, b0), sph(a1, b0), sph(a1, b1), sph(a0, b0), sph(a1, b1), sph(a0, b1)};
                for (float[] q : pts) add(v, q[0], q[1], q[2], q[0] * 2, q[1] * 2, q[2] * 2, q[0] + 0.5f, q[2] + 0.5f);
            }
        }
        dome = buf(v);
        domeN = v.size() / 8;
    }

    private static float[] sph(double a, double b) {
        return new float[]{0.5f * (float) (Math.cos(b) * Math.cos(a)), 0.5f * (float) Math.sin(b), 0.5f * (float) (Math.cos(b) * Math.sin(a))};
    }

    private static void quad(List<Float> v, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                             float dx, float dy, float dz, float nx, float ny, float nz) {
        add(v, ax, ay, az, nx, ny, nz, 0, 0); add(v, bx, by, bz, nx, ny, nz, 0, 0); add(v, cx, cy, cz, nx, ny, nz, 0, 0);
        add(v, ax, ay, az, nx, ny, nz, 0, 0); add(v, cx, cy, cz, nx, ny, nz, 0, 0); add(v, dx, dy, dz, nx, ny, nz, 0, 0);
    }

    private static void add(List<Float> v, float x, float y, float z, float nx, float ny, float nz, float u, float w) {
        v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(w);
    }

    private static FloatBuffer buf(List<Float> v) {
        FloatBuffer b = ByteBuffer.allocateDirect(v.size() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (Float f : v) b.put(f);
        b.position(0);
        return b;
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static final String LIT_V =
            "uniform mat4 uMVP; uniform mat4 uM;\n"
                    + "attribute vec3 aPos; attribute vec3 aNor; attribute vec2 aUV;\n"
                    + "varying vec3 vN; varying vec3 vW; varying vec2 vUV;\n"
                    + "void main() {\n"
                    + "  vec4 w = uM * vec4(aPos, 1.0); vW = w.xyz; vN = normalize((uM * vec4(aNor, 0.0)).xyz); vUV = aUV;\n"
                    + "  gl_Position = uMVP * vec4(aPos, 1.0);\n"
                    + "}\n";

    private static final String LIT_F =
            "precision mediump float;\n"
                    + "uniform sampler2D uTex; uniform float uUseTex; uniform vec4 uColor; uniform vec4 uUV; uniform vec3 uEye;\n"
                    + "uniform float uRim; uniform float uTime; uniform float uGlow;\n"
                    + "varying vec3 vN; varying vec3 vW; varying vec2 vUV;\n"
                    + "void main() {\n"
                    + "  vec3 n = normalize(vN);\n"
                    + "  vec3 L = normalize(vec3(0.4, 1.0, 0.6));\n"
                    + "  float diff = 0.5 + 0.5 * max(dot(n, L), 0.0);\n"
                    + "  vec3 base = uColor.rgb;\n"
                    + "  if (uUseTex > 0.5) { base = texture2D(uTex, mix(uUV.xy, uUV.zw, vUV)).rgb; }\n"
                    + "  vec3 v = normalize(uEye - vW);\n"
                    + "  float rim = pow(1.0 - max(dot(n, v), 0.0), 2.0) * uRim;\n"
                    + "  float lines = step(0.5, fract(gl_FragCoord.y * 0.25 + uTime * 1.5)) * 0.05 * uGlow;\n"
                    + "  vec3 col = base * diff + vec3(0.13, 0.83, 0.93) * (rim + lines) + uColor.rgb * uGlow * 0.12;\n"
                    + "  gl_FragColor = vec4(col, uColor.a);\n"
                    + "}\n";

    private static final String LINE_V = "uniform mat4 uMVP; attribute vec3 aPos; void main() { gl_Position = uMVP * vec4(aPos, 1.0); }\n";
    private static final String LINE_F = "precision mediump float; uniform vec4 uColor; void main() { gl_FragColor = uColor; }\n";
}
