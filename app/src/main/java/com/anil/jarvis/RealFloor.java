package com.anil.jarvis;

import android.app.Activity;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 📏 (8) The model at its real size on his real floor: Google's ARCore (Google Play Services for AR, on the phone) finds
 * the floor with the back camera and tells, every picture, where the phone is. This draws the camera's picture; the
 * see-through workshop stage on top draws the model there. Nothing is saved or sent anywhere; the camera is open only
 * while this is on and the workshop is in front.
 */
final class RealFloor implements GLSurfaceView.Renderer {
    interface Listener {
        /** (GL thread) the camera's view and lens, where the model stands (null: not placed), the ring where it would go (null: no floor there). */
        void onPose(float[] view, float[] lens, float[] anchor, float[] ring, boolean fresh);

        /** (GL thread) what he should do now: "look" (move slowly to find the floor), "floor", "placed", "dark", "fast", "plain", "lost", "nofloor". */
        void onState(String state);

        /** (GL thread) something went wrong, in his words. */
        void onProblem(String problem);
    }

    final GLSurfaceView view;
    private final Activity act;
    private final Listener listener;
    private Session session;
    private Anchor anchor;
    private volatile boolean geometryDirty = true;
    private volatile float tapX = -1, tapY = -1;
    private int width, height, tex = -1, prog, aPos, aTex, uTex;
    private boolean texSet, fresh;
    private String state = "";
    private int frames, fails;
    private boolean hasFloor;
    private final float[] v = new float[16], p = new float[16], a = new float[16], r = new float[16], axis = new float[3];
    private final FloatBuffer quad, uv;

    RealFloor(Activity a, Listener l) {
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

    /**
     * Can this phone do it now? "ok"; "install" (Google's "Play Services for AR" page was opened for him); "checking"
     * (ask again in a moment); "no" (this phone has no AR); or a problem in his words.
     */
    static String ready(Activity a, boolean heAsked) {
        try {
            ArCoreApk.Availability av = ArCoreApk.getInstance().checkAvailability(a);
            String n = av == null ? "UNKNOWN_ERROR" : av.name();
            if (n.equals("UNKNOWN_CHECKING")) return "checking";
            if (!n.startsWith("SUPPORTED")) return "no";
            if (av != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                ArCoreApk.InstallStatus s = ArCoreApk.getInstance().requestInstall(a, heAsked);
                if (s == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return "install";
            }
            return "ok";
        } catch (Throwable t) {
            String n = t.getClass().getSimpleName();
            if (n.contains("Declined")) return "Google Play Services for AR ఇన్‌స్టాల్ చేయలేదు";
            if (n.contains("NotCompatible")) return "no";
            return "AR మొదలవలేదు (" + n + ")";
        }
    }

    /** Starts the camera and the floor finding; null = fine, else the problem in his words. */
    String begin() {
        try {
            session = new Session(act);
            Config c = session.getConfig(); // (ARCore's own way already: floors, one picture each time; only the focus follows near things)
            c.setFocusMode(Config.FocusMode.AUTO);
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
        anchor = null;
        texSet = false;
        geometryDirty = true;
        state = "";
        return null; // (the caller puts the view on the screen, then view.onResume())
    }

    /** The workshop went behind: the camera is let go (the session stays for when he is back). */
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

    /** Off: everything let go. */
    void end() {
        view.onPause();
        Session s = session;
        session = null;
        try { if (anchor != null) anchor.detach(); } catch (Throwable ignored) {}
        anchor = null;
        try { if (s != null) { s.pause(); s.close(); } } catch (Throwable ignored) {}
    }

    /** A tap on the floor (0..1 of the screen): the model goes there. */
    void tap(double x, double y) {
        if (x < 0 || y < 0 || x > 1 || y > 1) return;
        tapY = (float) y;
        tapX = (float) x;
    }

    /** The screen turned (from one side straight to the other is no new size). */
    void turned() { geometryDirty = true; }

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
        if (frame.getTimestamp() == 0) return; // (no picture yet)
        drawCamera();

        Camera cam = frame.getCamera();
        if (cam.getTrackingState() != TrackingState.TRACKING) {
            TrackingFailureReason why = cam.getTrackingFailureReason();
            say(why == TrackingFailureReason.INSUFFICIENT_LIGHT ? "dark" : why == TrackingFailureReason.EXCESSIVE_MOTION ? "fast"
                    : why == TrackingFailureReason.INSUFFICIENT_FEATURES ? "plain" : "look");
            return;
        }
        cam.getProjectionMatrix(p, 0, 0.05f, 60f);
        cam.getViewMatrix(v, 0);

        if (frames++ % 15 == 0) hasFloor = floorSeen(s);

        // his tap on the floor: the model goes there
        float tx = tapX, ty = tapY;
        if (tx >= 0 && ty >= 0) {
            tapX = tapY = -1;
            HitResult h = floorAt(frame, cam, tx * width, ty * height);
            if (h != null) {
                try {
                    Anchor n = h.createAnchor();
                    if (anchor != null) anchor.detach();
                    anchor = n;
                    fresh = true;
                } catch (Throwable ignored) {}
            } else say("nofloor");
        }

        boolean placed = false;
        if (anchor != null) {
            TrackingState as = anchor.getTrackingState();
            if (as == TrackingState.TRACKING) { anchor.getPose().toMatrix(a, 0); placed = true; }
            else if (as == TrackingState.STOPPED) { anchor = null; }
        }
        boolean ring = false;
        if (!placed) {
            HitResult h = floorAt(frame, cam, width / 2f, height / 2f);
            if (h != null) { h.getHitPose().toMatrix(r, 0); ring = true; }
        }
        say(placed ? "placed" : hasFloor || ring ? "floor" : "look");
        listener.onPose(v, p, placed ? a : null, ring ? r : null, placed && fresh);
        if (placed) fresh = false;
    }

    /** The stage's message for one picture (built here, 30 times a second, without copies). */
    static String poseJson(float[] v, float[] p, float[] a, float[] r, boolean fresh) {
        StringBuilder sb = new StringBuilder(1500).append("{\"t\":\"xrpose\",\"v\":");
        arr(sb, v);
        sb.append(",\"p\":");
        arr(sb, p);
        sb.append(",\"a\":");
        if (a == null) sb.append("null"); else arr(sb, a);
        if (r != null) { sb.append(",\"r\":"); arr(sb, r); }
        if (fresh) sb.append(",\"fresh\":true");
        return sb.append('}').toString();
    }

    private static void arr(StringBuilder sb, float[] m) {
        sb.append('[');
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            float f = m != null && i < m.length ? m[i] : 0f;
            sb.append(Float.isFinite(f) ? f : 0f);
        }
        sb.append(']');
    }

    private void say(String s) {
        if (s.equals(state)) return;
        if ("nofloor".equals(s)) { listener.onState(s); return; } // (said once; the steady state stays)
        state = s;
        listener.onState(s);
    }

    /** The first floor (a flat surface facing up, below the phone) under that point of the screen. */
    private HitResult floorAt(Frame frame, Camera cam, float x, float y) {
        List<HitResult> hits;
        try { hits = frame.hitTest(x, y); } catch (Throwable t) { return null; }
        Pose cp = cam.getPose();
        for (HitResult h : hits) {
            Trackable t = h.getTrackable();
            if (!(t instanceof Plane)) continue;
            Pose hp = h.getHitPose();
            if (!((Plane) t).isPoseInPolygon(hp)) continue;
            hp.getTransformedAxis(1, 1f, axis, 0); // (its "up")
            if (axis[1] < 0.7f) continue;
            float above = (cp.tx() - hp.tx()) * axis[0] + (cp.ty() - hp.ty()) * axis[1] + (cp.tz() - hp.tz()) * axis[2];
            if (above > 0) return h;
        }
        return null;
    }

    private static boolean floorSeen(Session s) {
        try {
            for (Plane pl : s.getAllTrackables(Plane.class)) {
                if (pl.getTrackingState() == TrackingState.TRACKING && pl.getSubsumedBy() == null) return true;
            }
        } catch (Throwable ignored) {}
        return false;
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
