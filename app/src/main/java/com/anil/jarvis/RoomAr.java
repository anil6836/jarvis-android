package com.anil.jarvis;

import android.app.Activity;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 📐 ఇంటి కొలతలు: Google's ARCore on the phone finds the floor and the walls with the back camera and tells, every picture,
 * where the phone is. This draws the camera's picture, finds what the + mark in the middle of the screen points at (any
 * surface for 📏, the floor for 🏠 / 🛏️), and keeps the points he puts (ARCore anchors, so they stay where they were put).
 * Nothing is saved or sent: the camera is open only while a measuring tab is on the screen.
 */
final class RoomAr implements GLSurfaceView.Renderer {
    interface Listener {
        /** (GL thread) one picture's numbers for the page, as a ready message. */
        void onFrame(String json);
        /** (GL thread) "look", "dark", "fast", "plain" (ARCore lost its place) or "ok". */
        void onState(String state);
        /** (GL thread) a point was put: its number, what it is on, where. Or none (-1): nothing under the mark. */
        void onAdded(int id, String kind, float[] at);
        /** (GL thread) something went wrong, in his words. */
        void onProblem(String problem);
    }

    final GLSurfaceView view;
    private final Activity act;
    private final Listener listener;
    private Session session;
    private volatile String mode = "measure"; // measure (any surface) | floor
    private volatile boolean geometryDirty = true, addAsked;
    private final ConcurrentLinkedQueue<Integer> drop = new ConcurrentLinkedQueue<>();
    private volatile boolean dropAll;
    private final Map<Integer, Anchor> anchors = new LinkedHashMap<>();
    private int nextId = 1, width, height, tex = -1, prog, aPos, aTex, uTex, frames, fails;
    private boolean texSet;
    private String state = "";
    private float floorY = Float.NaN;
    private final float[] v = new float[16], p = new float[16], axis = new float[3];
    private final FloatBuffer quad, uv;

    RoomAr(Activity a, Listener l) {
        act = a;
        listener = l;
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f});
        ((java.nio.Buffer) quad).position(0);
        uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        uv.put(new float[]{0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
        ((java.nio.Buffer) uv).position(0);
        view = new GLSurfaceView(a);
        view.setPreserveEGLContextOnPause(true);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        view.setRenderer(this);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        view.setWillNotDraw(false);
    }

    /** Starts the camera: floors and walls, and depth where the phone has it (better marks on plain things). null = fine. */
    String begin() {
        try {
            session = new Session(act);
            Config c = session.getConfig();
            c.setFocusMode(Config.FocusMode.AUTO);
            c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            try { if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.setDepthMode(Config.DepthMode.AUTOMATIC); } catch (Throwable ignored) {}
            session.configure(c);
            session.resume();
        } catch (Throwable t) {
            String n = t.getClass().getSimpleName();
            try { if (session != null) session.close(); } catch (Throwable ignored) {}
            session = null;
            if (n.contains("ApkTooOld")) return "Google Play Services for AR పాతది; Play Store లో అప్డేట్ చేయండి";
            if (n.contains("SdkTooOld")) return "ఈ Jarvis కి కొత్త AR కావాలి";
            if (n.contains("NotInstalled")) return "Google Play Services for AR లేదు";
            if (n.contains("CameraNotAvailable")) return "కెమెరా వేరే యాప్ వాడుతోంది";
            return "AR మొదలవలేదు (" + n + ")";
        }
        texSet = false;
        geometryDirty = true;
        state = "";
        return null;
    }

    void mode(String m) { mode = "floor".equals(m) ? "floor" : "measure"; }
    void add() { addAsked = true; }
    void remove(int id) { drop.add(id); }
    void removeAll() { dropAll = true; }
    void turned() { geometryDirty = true; }

    /** Behind (another screen, the plan tab): the camera is let go; the points stay for when he is back. */
    void pause() {
        view.onPause();
        try { if (session != null) session.pause(); } catch (Throwable ignored) {}
    }

    /** null = fine. */
    String resume() {
        if (session == null) return "AR లేదు";
        try { session.resume(); } catch (Throwable t) { return "కెమెరా వేరే యాప్ వాడుతోంది"; }
        geometryDirty = true;
        view.onResume();
        return null;
    }

    void end() {
        view.onPause();
        Session s = session;
        session = null;
        try { if (s != null) { s.pause(); s.close(); } } catch (Throwable ignored) {}
        anchors.clear(); // (the GL thread is stopped; closing the session let go of them)
    }

    // ---------------------------------------------------------------- GL thread

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        prog = program(
                "attribute vec4 a_Position;\nattribute vec2 a_TexCoord;\nvarying vec2 v_TexCoord;\n"
                        + "void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }\n",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 v_TexCoord;\n"
                        + "uniform samplerExternalOES sTexture;\nvoid main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }\n");
        aPos = GLES20.glGetAttribLocation(prog, "a_Position");
        aTex = GLES20.glGetAttribLocation(prog, "a_TexCoord");
        uTex = GLES20.glGetUniformLocation(prog, "sTexture");
        texSet = false;
        if (prog == 0) listener.onProblem("కెమెరా బొమ్మ గీయలేకపోయాను");
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = w;
        height = h;
        geometryDirty = true;
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null || width == 0 || prog == 0) return;
        Frame frame;
        try {
            if (!texSet) { s.setCameraTextureNames(new int[]{tex}); texSet = true; }
            if (geometryDirty) {
                geometryDirty = false;
                int turn = 0;
                try { turn = act.getWindowManager().getDefaultDisplay().getRotation(); } catch (Throwable ignored) {}
                s.setDisplayGeometry(turn, width, height);
            }
            frame = s.update();
            fails = 0;
        } catch (Throwable t) {
            if (++fails == 60) listener.onProblem("కెమెరా బొమ్మ రావడం లేదు (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv);
            ((java.nio.Buffer) quad).position(0);
            ((java.nio.Buffer) uv).position(0);
        }
        if (frame.getTimestamp() == 0) return;
        drawCamera();

        // the points he took back (↶ / 🗑️)
        if (dropAll) { dropAll = false; drop.clear(); for (Anchor a : anchors.values()) try { a.detach(); } catch (Throwable ignored) {} anchors.clear(); }
        for (Integer id; (id = drop.poll()) != null; ) { Anchor a = anchors.remove(id); if (a != null) try { a.detach(); } catch (Throwable ignored) {} }

        Camera cam = frame.getCamera();
        if (cam.getTrackingState() != TrackingState.TRACKING) {
            TrackingFailureReason why = cam.getTrackingFailureReason();
            say(why == TrackingFailureReason.INSUFFICIENT_LIGHT ? "dark" : why == TrackingFailureReason.EXCESSIVE_MOTION ? "fast"
                    : why == TrackingFailureReason.INSUFFICIENT_FEATURES ? "plain" : "look");
            if (addAsked) { addAsked = false; listener.onAdded(-1, "", null); }
            return;
        }
        say("ok");
        cam.getProjectionMatrix(p, 0, 0.05f, 60f);
        cam.getViewMatrix(v, 0);
        if (frames++ % 15 == 0) floorY = floorHeight(s, cam.getPose());

        // what the + in the middle points at
        Aim aim = "floor".equals(mode) ? floorAim(frame, cam) : anyAim(frame, cam);
        if (addAsked) {
            addAsked = false;
            Anchor a = null;
            if (aim != null) {
                try { a = aim.hit != null ? aim.hit.createAnchor() : s.createAnchor(aim.pose); } catch (Throwable ignored) {}
            }
            if (a == null) listener.onAdded(-1, "", null);
            else {
                int id = nextId++;
                anchors.put(id, a);
                listener.onAdded(id, aim.kind, new float[]{aim.pose.tx(), aim.pose.ty(), aim.pose.tz()});
            }
        }
        listener.onFrame(json(aim));
    }

    private static final class Aim {
        HitResult hit;  // (null: the floor found by its height, past where ARCore has seen it)
        Pose pose;
        String kind;
    }

    /** 📏 Any surface: a floor / wall ARCore has mapped, else the depth picture, else a point of the room it has learned. */
    private Aim anyAim(Frame frame, Camera cam) {
        List<HitResult> hits;
        try { hits = frame.hitTest(width / 2f, height / 2f); } catch (Throwable t) { return null; }
        Pose cp = cam.getPose();
        HitResult depth = null, point = null;
        for (HitResult h : hits) {
            if (far(cp, h.getHitPose(), 10f)) continue;
            Trackable t = h.getTrackable();
            if (t instanceof Plane) {
                Plane pl = (Plane) t;
                if (!pl.isPoseInPolygon(h.getHitPose())) continue;
                Aim a = new Aim();
                a.hit = h;
                a.pose = h.getHitPose();
                a.kind = pl.getType() == Plane.Type.VERTICAL ? "wall" : "floor";
                return a;
            }
            if (depth == null && t instanceof DepthPoint) depth = h;
            if (point == null && t instanceof Point && ((Point) t).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) point = h;
        }
        HitResult h = depth != null ? depth : point;
        if (h == null) return null;
        Aim a = new Aim();
        a.hit = h;
        a.pose = h.getHitPose();
        a.kind = depth != null ? "depth" : "point";
        return a;
    }

    /** 🏠 / 🛏️ The floor only: a mapped floor under the mark, else the line of sight down to the floor's height (even past its edge). */
    private Aim floorAim(Frame frame, Camera cam) {
        List<HitResult> hits;
        try { hits = frame.hitTest(width / 2f, height / 2f); } catch (Throwable t) { hits = new ArrayList<>(); }
        Pose cp = cam.getPose();
        for (HitResult h : hits) {
            if (far(cp, h.getHitPose(), 12f)) continue;
            Trackable t = h.getTrackable();
            if (!(t instanceof Plane) || ((Plane) t).getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
            Pose hp = h.getHitPose();
            if (cp.ty() - hp.ty() < 0.3f) continue; // (a table top under the phone is not the floor)
            if (!Float.isNaN(floorY) && Math.abs(hp.ty() - floorY) > 0.12f) continue;
            Aim a = new Aim();
            a.hit = h;
            a.pose = hp;
            a.kind = "floor";
            return a;
        }
        if (Float.isNaN(floorY)) return null;
        // the middle of the screen's line of sight, down to the floor's height
        Pose dp = cam.getDisplayOrientedPose();
        dp.getTransformedAxis(2, -1f, axis, 0); // (where the camera looks)
        float dy = axis[1];
        if (dy > -0.05f) return null; // (looking up or level: it never meets the floor)
        float t = (floorY - dp.ty()) / dy;
        if (t <= 0 || t > 12f) return null;
        Aim a = new Aim();
        a.pose = Pose.makeTranslation(dp.tx() + axis[0] * t, floorY, dp.tz() + axis[2] * t);
        a.kind = "floor";
        return a;
    }

    private static boolean far(Pose a, Pose b, float m) {
        float dx = a.tx() - b.tx(), dy = a.ty() - b.ty(), dz = a.tz() - b.tz();
        return dx * dx + dy * dy + dz * dz > m * m;
    }

    /** The floor's height: the biggest flat surface facing up, well below the phone. NaN = not found yet. */
    private static float floorHeight(Session s, Pose cp) {
        float best = 0, y = Float.NaN;
        try {
            for (Plane pl : s.getAllTrackables(Plane.class)) {
                if (pl.getTrackingState() != TrackingState.TRACKING || pl.getSubsumedBy() != null || pl.getType() != Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
                float py = pl.getCenterPose().ty();
                if (cp.ty() - py < 0.3f) continue;
                float size = pl.getExtentX() * pl.getExtentZ();
                if (Float.isNaN(y) || size > best || py < y - 0.25f) { best = size; y = py; }
            }
        } catch (Throwable ignored) {}
        return y;
    }

    /** One picture for the page: the camera, the + mark's point and kind, every kept point where ARCore has it now. */
    private String json(Aim aim) {
        StringBuilder sb = new StringBuilder(2000).append("{\"t\":\"f\",\"v\":");
        arr(sb, v);
        sb.append(",\"p\":");
        arr(sb, p);
        sb.append(",\"aim\":");
        if (aim == null) sb.append("null");
        else sb.append('[').append(n(aim.pose.tx())).append(',').append(n(aim.pose.ty())).append(',').append(n(aim.pose.tz())).append(",\"").append(aim.kind).append("\"]");
        sb.append(",\"pts\":{");
        boolean first = true;
        for (Map.Entry<Integer, Anchor> e : anchors.entrySet()) {
            Anchor a = e.getValue();
            if (a.getTrackingState() != TrackingState.TRACKING) continue;
            Pose q = a.getPose();
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":[").append(n(q.tx())).append(',').append(n(q.ty())).append(',').append(n(q.tz())).append(']');
        }
        return sb.append("}}").toString();
    }

    private static String n(float f) { return Float.isFinite(f) ? String.valueOf(Math.round(f * 10000f) / 10000f) : "0"; }

    private static void arr(StringBuilder sb, float[] m) {
        sb.append('[');
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            float f = m[i];
            sb.append(Float.isFinite(f) ? f : 0f);
        }
        sb.append(']');
    }

    private void say(String s) {
        if (s.equals(state)) return;
        state = s;
        listener.onState(s);
    }

    private void drawCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        GLES20.glDepthMask(true);
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(pr, v);
        GLES20.glAttachShader(pr, f);
        GLES20.glLinkProgram(pr);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteProgram(pr); return 0; }
        return pr;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteShader(s); return 0; }
        return s;
    }
}
