package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 🎮 Stage 3 (27 Oct): the car screen's own 3D view (⑯ and the car chooser's turntable) — our own engine on OpenGL ES
 * 2: our 3D car (or his own photos) driving on the real road shapes from OpenStreetMap, the buildings raised from
 * their real footprints (or our toy blocks), our trees and street lamps, the way's yellow lane, arrows and chevron
 * boards; the car turns first and the camera follows; brake and indicator lights, headlight beams at night, rain,
 * the glow under the car with the music's beat; the sky by day, dusk or night. At most 30 frames a second (20 with
 * "fewer movements", 4 when nothing moves), nothing drawn while hidden. Google content never comes here.
 */
final class Car3DView extends GLSurfaceView implements GLSurfaceView.Renderer {

    interface Listener {
        /** A tap on the view (not a drag). */
        void tapped();
        /** This box's GPU couldn't make our shaders (said honestly; the view stays dark). */
        default void failed() {}
    }

    /** The world round the car, ready to draw: its pieces kept round (ox, oz) (world metres). */
    static final class World {
        final float ox, oz;
        final Mesh3D kerbs, asphalt, lines, solid;
        World(float ox, float oz, Mesh3D kerbs, Mesh3D asphalt, Mesh3D lines, Mesh3D solid) {
            this.ox = ox; this.oz = oz; this.kerbs = kerbs; this.asphalt = asphalt; this.lines = lines; this.solid = solid;
        }
    }

    /** The way's paint (our lane, arrows: flat) and its boards (standing), kept round (ox, oz). */
    static final class Marks {
        final float ox, oz;
        final Mesh3D flat, solid;
        Marks(float ox, float oz, Mesh3D flat, Mesh3D solid) { this.ox = ox; this.oz = oz; this.flat = flat; this.solid = solid; }
    }

    private Listener listener;
    static final int GROUND = 0x56604F;

    // ---- set from the main thread, picked up by the GL thread
    private volatile String kind = "suv", finish = "", head = "";
    private volatile int paint = 0x2F6FE4, glowRgb = 0;
    private volatile int carGen = 1;
    private volatile CarPhotos3D.Set photos;
    private volatile boolean usePhotos;
    private volatile int photoGen;
    private volatile World world;
    private volatile int worldGen;
    private volatile Marks marks;
    private volatile int marksGen;
    private volatile int skyKind = Cam3D.DAY;
    private volatile boolean rain, low, brake, showroom, orbit, moving;
    private volatile int blink; // −1 left, 1 right, 0 none
    private volatile CarEq eq;
    // the GPS's last place (world metres) and when
    private final Object poseLock = new Object();
    private float fixX, fixZ, fixHeading, fixSpeed;
    private long fixAt;
    private boolean haveFix;
    /** Nothing moves (parked, no rain, no blinking): few frames. */
    private volatile boolean still;

    // ---- the GL thread's own
    private int prog, sky, photo, line;
    private int aPos, aNor, aCol, aMat, uVP, uM, uEye, uSun, uSunCol, uAmb, uSkyTop, uFog, uFogK, uNight, uPaint, uFinish, uHead, uNeon, uNeonOn,
            uBrake, uIndL, uIndR, uCar, uCarSize, uRain, uGround, uOff,
            uWall, uWinA, uWinB, uWinM, uRoadT, uLane, uWet, uGrid, uRings, uGroundT, uFlat;
    private int sP, sTop, sFog, sNight, sYaw, sHorizon, sSize, sDisc, sDiscC, sHills;
    private int pPos, pUV, pVP, pTex, pDark, pMode, pColor;
    private int lPos, lVP, lColor;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16], model = new float[16], tmp = new float[16];
    private int vw = 1, vh = 1;
    private Vbo floorVbo, carVbo, neonVbo, beamVbo, underVbo, groundVbo, kerbVbo, asphaltVbo, lineVbo, solidVbo, flatMarkVbo, solidMarkVbo;
    private float wOx, wOz, mOx, mOz;
    private int carGenGl, photoGenGl = -1, worldGenGl, marksGenGl;
    private String kindGl = "suv";
    private final int[] tex = new int[CarPhotos3D.SLOTS.length];
    private final float[] texAspect = new float[CarPhotos3D.SLOTS.length];
    private float[][] lampsGl = new float[CarPhotos3D.SLOTS.length][];
    private boolean[] haveGl = new boolean[CarPhotos3D.SLOTS.length];
    private int shownPhoto = -1;
    private FloatBuffer quadBuf, rainBuf, skyBuf;
    private float[] rainDrops;
    // the shown car, the cameras
    private float carX, carZ, carH, camH, speed;
    private boolean placed;
    private float orbitYaw, orbitSpin = 1;
    private long lastFrame, t0 = SystemClock.uptimeMillis();
    private float[] skyNow = Cam3D.light(Cam3D.DAY);
    /** The world's own look now (Cam3D.world), eased like the sky. */
    private float[] worldNow = Cam3D.world(Cam3D.DAY);
    private float beatGl;

    Car3DView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new Depth()); // (24-bit depth when the box has it: far buildings don't flicker; else 16)
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setListener(Listener l) { listener = l; }

    // ================================================================ what to show (main thread)

    /** Our car: its kind, finish, headlight colour, glow under it ("" off, "blue", "pink"), paint (RGB). */
    void setCar(String kind, String finish, String head, String glow, int paintRgb) {
        String k = Car3D.kindIndex(kind) < 0 ? "suv" : kind;
        int g = "blue".equals(glow) ? 0x2E8BFF : "pink".equals(glow) ? 0xFF3FA4 : 0;
        int pr = paintRgb & 0xFFFFFF;
        if (k.equals(this.kind) && finish.equals(this.finish) && head.equals(this.head) && g == glowRgb && pr == paint) return;
        this.kind = k; this.finish = finish == null ? "" : finish; this.head = head == null ? "" : head; glowRgb = g; paint = pr;
        carGen++;
        poke();
    }

    /** His photos instead of our car (null: our car). */
    void setPhotos(CarPhotos3D.Set s) {
        photos = s;
        usePhotos = s != null && s.any();
        photoGen++;
        poke();
    }

    void setWorld(World w) { world = w; worldGen++; poke(); }

    void setMarks(Marks m) { marks = m; marksGen++; poke(); }

    /** The GPS's place for the car (world metres), its heading (radians, 0 north, clockwise), speed (m/s). */
    void setFix(float x, float z, float heading, float speedMps) {
        synchronized (poseLock) {
            fixX = x; fixZ = z; fixHeading = heading; fixSpeed = speedMps; fixAt = SystemClock.uptimeMillis(); haveFix = true;
        }
        moving = speedMps > 1.2f;
        poke();
    }

    void setSky(int k) { if (k != skyKind) { skyKind = k; poke(); } }

    void setLights(boolean brake, int blink, boolean rain) {
        if (brake == this.brake && blink == this.blink && rain == this.rain) return;
        this.brake = brake; this.blink = blink; this.rain = rain;
        poke();
    }

    void setEq(CarEq e) { eq = e; }

    /** "Fewer movements": 20 frames a second, no falling rain. */
    void setLow(boolean on) { low = on; }

    /** 🔄 round the car (his tap, parked) — off: behind it again. */
    void setOrbit(boolean on) { orbit = on; poke(); }

    boolean orbit() { return orbit; }

    /** The chooser's turntable: the car alone on a round floor, turning slowly. */
    void setShowroom(boolean on) { showroom = on; poke(); }

    /** RGBA 8888 with the deepest depth buffer the box offers (24, else 16), for OpenGL ES 2. */
    private static final class Depth implements GLSurfaceView.EGLConfigChooser {
        @Override public EGLConfig chooseConfig(javax.microedition.khronos.egl.EGL10 egl, javax.microedition.khronos.egl.EGLDisplay d) {
            for (int depth : new int[]{24, 16}) {
                int[] spec = {javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                        javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, depth, 0x3040 /* EGL_RENDERABLE_TYPE */, 4 /* EGL_OPENGL_ES2_BIT */,
                        javax.microedition.khronos.egl.EGL10.EGL_NONE};
                int[] n = new int[1];
                EGLConfig[] cs = new EGLConfig[1];
                if (egl.eglChooseConfig(d, spec, cs, 1, n) && n[0] > 0 && cs[0] != null) return cs[0];
            }
            throw new IllegalArgumentException("no OpenGL ES 2 config");
        }
    }

    // ================================================================ frames: only while shown

    /** Drawing wanted (shown, the screen on); while not, the GL thread is kept paused — also a new one after a re-attach. */
    private boolean live;
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            if (!live) return;
            requestRender();
            int every = still && !orbit && !showroom ? 250 : low ? 50 : 33;
            postDelayed(this, every);
        }
    };

    private void poke() {
        if (live) { removeCallbacks(pump); post(pump); }
    }

    /** Drawing on (shown, the screen on) or off. */
    void setRunning(boolean on) {
        if (on == live) return;
        live = on;
        removeCallbacks(pump);
        if (on) { onResume(); post(pump); } else onPause();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); // (a new GL thread starts here after a re-attach: kept paused unless drawing is wanted)
        removeCallbacks(pump);
        if (!live) onPause(); else post(pump);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(pump);
        super.onDetachedFromWindow();
    }

    // ================================================================ his finger: round the car

    private float downX, lastX;
    private boolean dragged;

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = lastX = e.getX(); dragged = false; return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > 12 * getResources().getDisplayMetrics().density) dragged = true;
                if (dragged && (orbit || showroom)) {
                    final float dx = e.getX() - lastX;
                    queueEvent(() -> { orbitYaw -= dx * 0.008f; orbitSpin = 0; });
                    poke();
                }
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged && listener != null) listener.tapped();
                if (dragged) queueEvent(() -> orbitSpin = 0.0001f); // (it turns on by itself again after a while)
                return true;
            default: return true;
        }
    }

    // ================================================================ GL

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        prog = program(LIT_V, LIT_F);
        aPos = GLES20.glGetAttribLocation(prog, "aPos"); aNor = GLES20.glGetAttribLocation(prog, "aNor");
        aCol = GLES20.glGetAttribLocation(prog, "aCol"); aMat = GLES20.glGetAttribLocation(prog, "aMat");
        uVP = u(prog, "uVP"); uM = u(prog, "uM"); uEye = u(prog, "uEye"); uSun = u(prog, "uSun"); uSunCol = u(prog, "uSunCol"); uAmb = u(prog, "uAmb");
        uSkyTop = u(prog, "uSkyTop"); uFog = u(prog, "uFog"); uFogK = u(prog, "uFogK"); uNight = u(prog, "uNight"); uPaint = u(prog, "uPaint");
        uFinish = u(prog, "uFinish"); uHead = u(prog, "uHead"); uNeon = u(prog, "uNeon"); uNeonOn = u(prog, "uNeonOn"); uBrake = u(prog, "uBrake");
        uIndL = u(prog, "uIndL"); uIndR = u(prog, "uIndR"); uCar = u(prog, "uCar"); uCarSize = u(prog, "uCarSize"); uRain = u(prog, "uRain");
        uGround = u(prog, "uGround"); uOff = u(prog, "uOff");
        uWall = u(prog, "uWall"); uWinA = u(prog, "uWinA"); uWinB = u(prog, "uWinB"); uWinM = u(prog, "uWinM"); uRoadT = u(prog, "uRoadT"); uLane = u(prog, "uLane");
        uWet = u(prog, "uWet"); uGrid = u(prog, "uGrid"); uRings = u(prog, "uRings"); uGroundT = u(prog, "uGroundT"); uFlat = u(prog, "uFlat");
        sky = program(SKY_V, SKY_F);
        sP = GLES20.glGetAttribLocation(sky, "aP"); sTop = u(sky, "uTop"); sFog = u(sky, "uFog"); sNight = u(sky, "uNight"); sYaw = u(sky, "uYaw");
        sHorizon = u(sky, "uHorizon"); sSize = u(sky, "uSize"); sDisc = u(sky, "uDisc"); sDiscC = u(sky, "uDiscC"); sHills = u(sky, "uHills");
        photo = program(PHOTO_V, PHOTO_F);
        pPos = GLES20.glGetAttribLocation(photo, "aPos"); pUV = GLES20.glGetAttribLocation(photo, "aUV");
        pVP = u(photo, "uVP"); pTex = u(photo, "uTex"); pDark = u(photo, "uDark"); pMode = u(photo, "uMode"); pColor = u(photo, "uColor");
        line = program(LINE_V, LINE_F);
        lPos = GLES20.glGetAttribLocation(line, "aPos"); lVP = u(line, "uVP"); lColor = u(line, "uColor");
        quadBuf = ByteBuffer.allocateDirect(4 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        skyBuf = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        skyBuf.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        // a new context: everything uploaded again
        floorVbo = carVbo = neonVbo = beamVbo = underVbo = groundVbo = kerbVbo = asphaltVbo = lineVbo = solidVbo = flatMarkVbo = solidMarkVbo = null;
        carGenGl = 0; photoGenGl = -1; worldGenGl = 0; marksGenGl = 0;
        java.util.Arrays.fill(tex, 0);
        Mesh3D g = new Mesh3D(); // the ground: a big square that moves with the camera (it looks the same everywhere)
        g.quadFacing(new float[]{-900, 0, -900}, new float[]{900, 0, -900}, new float[]{900, 0, 900}, new float[]{-900, 0, 900}, 0, 1, 0, GROUND, Mesh3D.M_MATTE);
        groundVbo = Vbo.of(g);
        floorVbo = Vbo.of(showFloor());
    }

    /** The chooser's turntable: a dark round floor with a thin lit ring. Pure. */
    static Mesh3D showFloor() {
        Mesh3D m = new Mesh3D();
        int seg = 48;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            m.triFacing(new float[]{0, 0.005f, 0}, new float[]{c0 * 4.2f, 0.005f, s0 * 4.2f}, new float[]{c1 * 4.2f, 0.005f, s1 * 4.2f}, 0, 1, 0, 0x262B33, Mesh3D.M_MATTE);
            m.quadFacing(new float[]{c0 * 4.2f, 0.006f, s0 * 4.2f}, new float[]{c1 * 4.2f, 0.006f, s1 * 4.2f}, new float[]{c1 * 4.35f, 0.006f, s1 * 4.35f},
                    new float[]{c0 * 4.35f, 0.006f, s0 * 4.35f}, 0, 1, 0, 0x5FF3FF, Mesh3D.M_LIGHT);
        }
        return m;
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        vw = Math.max(1, w);
        vh = Math.max(1, h);
        Matrix.perspectiveM(proj, 0, 50f, vw / (float) vh, 1.0f, 900f); // (nothing comes nearer than a metre: more depth for the far buildings)
    }

    @Override public void onDrawFrame(GL10 gl) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float time = (now - t0) / 1000f;
        uploads();
        step(dt, now);

        // ---- the camera (eye-relative world: x, z less the eye's, so the numbers stay small)
        float[] cam;
        if (showroom) {
            if (orbitSpin != 0) orbitYaw += dt * 0.35f;
            cam = Cam3D.orbit(carX, carZ, orbitYaw, 6.4f, 2.0f);
        } else if (orbit) {
            if (orbitSpin != 0) orbitYaw += dt * (low ? 0.12f : 0.25f);
            cam = Cam3D.orbit(carX, carZ, orbitYaw, 8.5f, 3.2f);
        } else {
            cam = Cam3D.chase(carX, carZ, camH, speed);
            orbitYaw = Cam3D.wrap(camH + (float) Math.PI); // (🔄 starts from behind the car)
        }
        float ex = cam[0], ez = cam[2];
        Matrix.setLookAtM(view, 0, 0, cam[1], 0, cam[3] - ex, cam[4], cam[5] - ez, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        // ---- the sky's light, eased towards his choice
        skyNow = Cam3D.mix(skyNow, Cam3D.light(showroom ? Cam3D.DUSK : skyKind), Cam3D.ease(1.5f, dt));
        worldNow = Cam3D.mix(worldNow, Cam3D.world(showroom ? Cam3D.DUSK : skyKind), Cam3D.ease(1.5f, dt)); // (a world's own colours, the same smooth way)
        float[] wn = worldNow;
        float night = skyNow[14];
        GLES20.glClearColor(skyNow[10], skyNow[11], skyNow[12], 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        // ---- the sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(sky);
        GLES20.glUniform3f(sTop, skyNow[7], skyNow[8], skyNow[9]);
        GLES20.glUniform3f(sFog, skyNow[10], skyNow[11], skyNow[12]);
        GLES20.glUniform1f(sNight, night);
        float camYaw = (float) Math.atan2(cam[3] - ex, -(cam[5] - ez));
        GLES20.glUniform1f(sYaw, camYaw);
        float pitch = (float) Math.atan2(cam[4] - cam[1], (float) Math.hypot(cam[3] - ex, cam[5] - ez));
        GLES20.glUniform1f(sHorizon, 0.5f - (float) Math.tan(pitch) / (2 * (float) Math.tan(Math.toRadians(25))));
        GLES20.glUniform2f(sSize, vw, vh);
        GLES20.glUniform4f(sDisc, wn[26], wn[27], wn[28], wn[29]); // (the moon / the sun)
        GLES20.glUniform3f(sDiscC, wn[30], wn[31], wn[32]);
        GLES20.glUniform4f(sHills, wn[33], wn[34], wn[35], wn[36]);
        skyBuf.position(0);
        GLES20.glVertexAttribPointer(sP, 2, GLES20.GL_FLOAT, false, 8, skyBuf);
        GLES20.glEnableVertexAttribArray(sP);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(sP);

        // ---- the lit program's shared numbers
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        GLES20.glUniform3f(uEye, 0, cam[1], 0);
        float sl = (float) Math.sqrt(skyNow[0] * skyNow[0] + skyNow[1] * skyNow[1] + skyNow[2] * skyNow[2]);
        GLES20.glUniform3f(uSun, skyNow[0] / sl, skyNow[1] / sl, skyNow[2] / sl);
        GLES20.glUniform3f(uSunCol, skyNow[3], skyNow[4], skyNow[5]);
        GLES20.glUniform1f(uAmb, skyNow[6]);
        GLES20.glUniform3f(uSkyTop, skyNow[7], skyNow[8], skyNow[9]);
        GLES20.glUniform3f(uFog, skyNow[10], skyNow[11], skyNow[12]);
        GLES20.glUniform1f(uFogK, showroom ? 0.02f : skyNow[13]);
        GLES20.glUniform1f(uNight, night);
        GLES20.glUniform3f(uPaint, ((paint >> 16) & 255) / 255f, ((paint >> 8) & 255) / 255f, (paint & 255) / 255f);
        GLES20.glUniform1f(uFinish, "matte".equals(finish) ? 1 : "neon".equals(finish) ? 2 : 0);
        if ("yellow".equals(head)) GLES20.glUniform3f(uHead, 1f, 0.82f, 0.45f); else GLES20.glUniform3f(uHead, 0.95f, 0.97f, 1f);
        int glow = glowRgb != 0 ? glowRgb : "neon".equals(finish) ? 0x35F0FF : 0;
        GLES20.glUniform3f(uNeon, ((glow >> 16) & 255) / 255f, ((glow >> 8) & 255) / 255f, (glow & 255) / 255f);
        CarEq e = eq;
        float bass = 0;
        if (e != null) { for (int i = 0; i < 4; i++) bass += e.level[i]; bass /= 4; }
        beatGl += (bass - beatGl) * Cam3D.ease(bass > beatGl ? 18f : 4f, dt);
        GLES20.glUniform1f(uNeonOn, glow == 0 ? 0 : 0.45f + 0.55f * (low ? 0.5f : beatGl));
        GLES20.glUniform1f(uBrake, brake ? 1 : 0);
        boolean on = (now / 400) % 2 == 0; // (the indicators blink about once a second)
        GLES20.glUniform1f(uIndL, blink < 0 && on ? 1 : 0);
        GLES20.glUniform1f(uIndR, blink > 0 && on ? 1 : 0);
        Car3D.Size cs = Car3D.size(kindGl);
        GLES20.glUniform4f(uCar, carX - ex, carZ - ez, Cam3D.dirX(carH), Cam3D.dirZ(carH));
        GLES20.glUniform2f(uCarSize, cs.w / 2 + 0.35f, cs.l / 2 + 0.4f);
        GLES20.glUniform1f(uRain, rain ? 1 : 0);
        GLES20.glUniform3f(uGround, skyNow[15], skyNow[16], skyNow[17]);
        GLES20.glUniform4f(uWall, wn[0], wn[1], wn[2], wn[3]); // (the world's own colours: 0 = as always)
        GLES20.glUniform3f(uWinA, wn[4], wn[5], wn[6]);
        GLES20.glUniform3f(uWinB, wn[7], wn[8], wn[9]);
        GLES20.glUniform1f(uWinM, wn[10]);
        GLES20.glUniform4f(uRoadT, wn[11], wn[12], wn[13], wn[14]);
        GLES20.glUniform4f(uLane, wn[15], wn[16], wn[17], wn[18]);
        GLES20.glUniform1f(uWet, wn[19]);
        GLES20.glUniform1f(uGrid, wn[20]);
        GLES20.glUniform1f(uRings, wn[21]);
        GLES20.glUniform4f(uGroundT, wn[22], wn[23], wn[24], wn[25]);
        GLES20.glUniform1f(uFlat, 0);

        // ---- the flat things on the ground, in order (no depth: they never hide each other wrongly)
        if (!showroom) {
            ident(0, 0, ex, ez); // (the ground moves with the eye; its place in the world for the white world's grid)
            GLES20.glUniform1f(uFlat, 1);
            groundVbo.draw(this);
            GLES20.glUniform1f(uFlat, 0);
            if (kerbVbo != null) { ident(wOx - ex, wOz - ez, wOx, wOz); draw(kerbVbo); draw(asphaltVbo); draw(lineVbo); }
            if (flatMarkVbo != null) { ident(mOx - ex, mOz - ez, mOx, mOz); flatMarkVbo.draw(this); }
        } else { // the showroom's floor
            ident(carX - ex, carZ - ez, 0, 0);
            groundVbo.draw(this);
            draw(floorVbo);
        }
        // the shadow under the car, then its glow
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        carModel(ex, ez);
        if (underVbo != null) {
            underVbo.drawFirst(this, 6);
            if (glow != 0) { GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); underVbo.drawFrom(this, 6); }
        }
        GLES20.glDisable(GLES20.GL_BLEND);

        // ---- the standing things, with depth
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
        if (!showroom) {
            if (solidVbo != null) { ident(wOx - ex, wOz - ez, wOx, wOz); solidVbo.draw(this); }
            if (solidMarkVbo != null) { ident(mOx - ex, mOz - ez, mOx, mOz); solidMarkVbo.draw(this); }
        }
        boolean photoCar = usePhotos && anyTex();
        if (!photoCar) {
            carModel(ex, ez);
            if (carVbo != null) carVbo.draw(this);
            if (neonVbo != null && "neon".equals(finish)) neonVbo.draw(this);
        } else drawPhoto(ex, ez, cam, night);

        // ---- light added: the beams at night, the rain
        if (night > 0.3f && !photoCar && beamVbo != null) {
            GLES20.glUseProgram(prog);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glDepthMask(false);
            carModel(ex, ez);
            beamVbo.draw(this);
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        if (rain && !low && !showroom) drawRain(dt, cam);
        still = !moving && !rain && blink == 0 && !orbit && !showroom && (glow == 0 || beatGl < 0.02f) && Math.abs(World3D.signedDiff(camH, carH)) < 0.01f;
    }

    /** The newest car / photos / world / way handed over: uploaded (old buffers freed). */
    private void uploads() {
        if (carGen != carGenGl) {
            carGenGl = carGen;
            kindGl = kind;
            Vbo.free(carVbo); Vbo.free(neonVbo); Vbo.free(beamVbo); Vbo.free(underVbo);
            carVbo = Vbo.of(Car3D.build(kindGl));
            neonVbo = Vbo.of(Car3D.neon(kindGl));
            beamVbo = Vbo.of(Car3D.beams(kindGl));
            Car3D.Size s = Car3D.size(kindGl);
            Mesh3D u = new Mesh3D();
            float sw = s.w / 2 + 0.35f, sl = s.l / 2 + 0.4f, gw = sw + 0.9f, gl = sl + 0.9f;
            u.quadFacing(new float[]{-sw, 0.01f, -sl}, new float[]{sw, 0.01f, -sl}, new float[]{sw, 0.01f, sl}, new float[]{-sw, 0.01f, sl}, 0, 1, 0, 0, Mesh3D.M_SHADOW);
            u.quadFacing(new float[]{-gw, 0.015f, -gl}, new float[]{gw, 0.015f, -gl}, new float[]{gw, 0.015f, gl}, new float[]{-gw, 0.015f, gl}, 0, 1, 0, 0, Mesh3D.M_GLOW);
            underVbo = Vbo.of(u);
        }
        if (photoGen != photoGenGl) {
            photoGenGl = photoGen;
            for (int i = 0; i < tex.length; i++) if (tex[i] != 0) { GLES20.glDeleteTextures(1, new int[]{tex[i]}, 0); tex[i] = 0; }
            CarPhotos3D.Set s = photos;
            haveGl = new boolean[tex.length];
            lampsGl = new float[tex.length][];
            shownPhoto = -1;
            for (int i = 0; s != null && i < tex.length; i++) {
                Bitmap b = s.img[i];
                if (b == null || b.isRecycled() || i == 4) continue;
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                tex[i] = t[0];
                texAspect[i] = b.getWidth() / (float) Math.max(1, b.getHeight());
                lampsGl[i] = s.lamps[i];
                haveGl[i] = true;
            }
        }
        if (worldGen != worldGenGl) {
            worldGenGl = worldGen;
            Vbo.free(kerbVbo); Vbo.free(asphaltVbo); Vbo.free(lineVbo); Vbo.free(solidVbo);
            kerbVbo = asphaltVbo = lineVbo = solidVbo = null;
            World w = world;
            if (w != null) {
                wOx = w.ox; wOz = w.oz;
                kerbVbo = Vbo.of(w.kerbs); asphaltVbo = Vbo.of(w.asphalt); lineVbo = Vbo.of(w.lines); solidVbo = Vbo.of(w.solid);
            }
        }
        if (marksGen != marksGenGl) {
            marksGenGl = marksGen;
            Vbo.free(flatMarkVbo); Vbo.free(solidMarkVbo);
            flatMarkVbo = solidMarkVbo = null;
            Marks m = marks;
            if (m != null) { mOx = m.ox; mOz = m.oz; flatMarkVbo = Vbo.of(m.flat); solidMarkVbo = Vbo.of(m.solid); }
        }
    }

    /** The shown car glides to the GPS's place; its nose turns first, the camera follows a moment later. */
    private void step(float dt, long now) {
        if (showroom) { carX = carZ = 0; placed = true; speed = 0; return; }
        float fx, fz, fh, fs;
        long at;
        boolean have;
        synchronized (poseLock) { fx = fixX; fz = fixZ; fh = fixHeading; fs = fixSpeed; at = fixAt; have = haveFix; }
        if (!have) return;
        if (!placed) { carX = fx; carZ = fz; carH = camH = fh; placed = true; }
        float[] p = Cam3D.glide(carX, carZ, fx, fz, fh, fs, (now - at) / 1000f, dt);
        carX = p[0];
        carZ = p[1];
        speed = fs;
        carH = Cam3D.turnTowards(carH, fh, Cam3D.ease(3.2f, dt));
        camH = Cam3D.turnTowards(camH, carH, Cam3D.ease(1.3f, dt));
    }

    /** A world piece's place (its middle less the eye's) and its middle itself (the windows' pattern keeps to the world). */
    private void ident(float dx, float dz, float ox, float oz) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, dx, 0, dz);
        GLES20.glUniformMatrix4fv(uM, 1, false, model, 0);
        GLES20.glUniform2f(uOff, ox, oz);
    }

    private void carModel(float ex, float ez) {
        GLES20.glUniform2f(uOff, 0, 0);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, carX - ex, 0, carZ - ez);
        Matrix.rotateM(model, 0, (float) Math.toDegrees(-carH), 0, 1, 0);
        GLES20.glUniformMatrix4fv(uM, 1, false, model, 0);
    }

    private void draw(Vbo v) { if (v != null) v.draw(this); }

    private boolean anyTex() {
        for (int i = 0; i < tex.length; i++) if (tex[i] != 0) return true;
        return false;
    }

    /**
     * His photo car: the photo nearest to where the camera is, standing up facing the camera (its foot on the road,
     * a little towards the camera), darker at night, its tail lights glowing (brake, night) and blinking (indicators).
     */
    private void drawPhoto(float ex, float ez, float[] cam, float night) {
        float deg = Cam3D.around(carX, carZ, carH, cam[0], cam[2]);
        int k = Cam3D.pickPhoto(deg, haveGl, shownPhoto);
        if (k < 0 || tex[k] == 0) return;
        shownPhoto = k;
        float h = 1.62f, w = h * texAspect[k];
        float dx = ex - carX, dz = ez - carZ, d = (float) Math.hypot(dx, dz);
        if (d < 1e-3f) return;
        float tx = dx / d, tz = dz / d; // towards the camera
        float rx = tz, rz = -tx; // the camera's right, along the ground: (towards the camera) turned
        float cx = carX - ex + tx * 0.9f, cz = carZ - ez + tz * 0.9f;
        // its corners (camera's left to right), the picture's v from the top
        float lx = cx - rx * w / 2, lz = cz - rz * w / 2, qx = cx + rx * w / 2, qz = cz + rz * w / 2;
        quadBuf.position(0);
        quadBuf.put(new float[]{lx, 0, lz, 0, 1, qx, 0, qz, 1, 1, lx, h, lz, 0, 0, qx, h, qz, 1, 0}).position(0);
        GLES20.glUseProgram(photo);
        GLES20.glUniformMatrix4fv(pVP, 1, false, vp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[k]);
        GLES20.glUniform1i(pTex, 0);
        GLES20.glUniform1f(pDark, 1f - 0.5f * night);
        GLES20.glUniform1f(pMode, 0);
        quad();
        // the lights' glows on it
        float[] lamps = lampsGl[k];
        if (lamps == null) return;
        boolean blinkOn = (SystemClock.uptimeMillis() / 400) % 2 == 0;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(pMode, 1);
        for (int i = 0; i + 1 < lamps.length; i += 2) {
            float u = lamps[i], v = lamps[i + 1];
            float px = lx + (qx - lx) * u + tx * 0.03f, pz = lz + (qz - lz) * u + tz * 0.03f, py = h * (1 - v);
            // which side is this light (the car's left or right)? Seen from behind, the picture's left is the car's left
            boolean carLeft = lamps.length < 4 ? deg >= 180 : i == 0;
            float red = (brake ? 1f : 0) + night * 0.45f;
            if (red > 0) spot(px, py, pz, rx, rz, brake ? 0.42f : 0.3f, red, 0.08f * red, 0.06f * red);
            if (blinkOn && ((blink < 0 && carLeft) || (blink > 0 && !carLeft))) spot(px, py, pz, rx, rz, 0.36f, 1f, 0.62f, 0.1f);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void spot(float x, float y, float z, float rx, float rz, float s, float r, float g, float b) {
        quadBuf.position(0);
        quadBuf.put(new float[]{x - rx * s, y - s, z - rz * s, 0, 1, x + rx * s, y - s, z + rz * s, 1, 1, x - rx * s, y + s, z - rz * s, 0, 0,
                x + rx * s, y + s, z + rz * s, 1, 0}).position(0);
        GLES20.glUniform3f(pColor, r, g, b);
        quad();
    }

    private void quad() {
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(pPos, 3, GLES20.GL_FLOAT, false, 20, quadBuf);
        GLES20.glEnableVertexAttribArray(pPos);
        quadBuf.position(3);
        GLES20.glVertexAttribPointer(pUV, 2, GLES20.GL_FLOAT, false, 20, quadBuf);
        GLES20.glEnableVertexAttribArray(pUV);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pPos);
        GLES20.glDisableVertexAttribArray(pUV);
    }

    /** 🌧️ Falling streaks round the camera (they move with it). */
    private void drawRain(float dt, float[] cam) {
        int n = 260;
        if (rainDrops == null) {
            rainDrops = new float[n * 3];
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < n; i++) { rainDrops[i * 3] = r.nextFloat() * 40 - 20; rainDrops[i * 3 + 1] = r.nextFloat() * 14; rainDrops[i * 3 + 2] = r.nextFloat() * 40 - 20; }
            rainBuf = ByteBuffer.allocateDirect(n * 2 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        rainBuf.position(0); // (the drops round a point ahead of the camera: they fill the view)
        for (int i = 0; i < n; i++) {
            float y = rainDrops[i * 3 + 1] - dt * 13f;
            if (y < 0) y += 14;
            rainDrops[i * 3 + 1] = y;
            float x = rainDrops[i * 3] + (cam[3] - cam[0]) * 0.5f, z = rainDrops[i * 3 + 2] + (cam[5] - cam[2]) * 0.5f;
            rainBuf.put(x).put(y).put(z).put(x + 0.05f).put(y + 0.55f).put(z);
        }
        rainBuf.position(0);
        GLES20.glUseProgram(line);
        GLES20.glUniformMatrix4fv(lVP, 1, false, vp, 0);
        GLES20.glUniform4f(lColor, 0.75f, 0.82f, 0.92f, 0.38f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 12, rainBuf);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, n * 2);
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ================================================================ buffers

    /** A mesh in the GPU's memory. */
    static final class Vbo {
        final int id, count;
        private Vbo(int id, int count) { this.id = id; this.count = count; }

        static Vbo of(Mesh3D m) {
            if (m == null || m.n == 0) return null;
            FloatBuffer b = ByteBuffer.allocateDirect(m.n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(m.v, 0, m.n).position(0);
            int[] id = new int[1];
            GLES20.glGenBuffers(1, id, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.n * 4, b, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            return new Vbo(id[0], m.corners());
        }

        static void free(Vbo v) { if (v != null) GLES20.glDeleteBuffers(1, new int[]{v.id}, 0); }

        void draw(Car3DView g) { drawRange(g, 0, count); }

        void drawFirst(Car3DView g, int n) { drawRange(g, 0, Math.min(n, count)); }

        void drawFrom(Car3DView g, int from) { if (from < count) drawRange(g, from, count - from); }

        private void drawRange(Car3DView g, int first, int n) {
            GLES20.glUseProgram(g.prog);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id);
            int st = Mesh3D.STRIDE * 4;
            GLES20.glVertexAttribPointer(g.aPos, 3, GLES20.GL_FLOAT, false, st, 0);
            GLES20.glEnableVertexAttribArray(g.aPos);
            GLES20.glVertexAttribPointer(g.aNor, 3, GLES20.GL_FLOAT, false, st, 12);
            GLES20.glEnableVertexAttribArray(g.aNor);
            GLES20.glVertexAttribPointer(g.aCol, 3, GLES20.GL_FLOAT, false, st, 24);
            GLES20.glEnableVertexAttribArray(g.aCol);
            GLES20.glVertexAttribPointer(g.aMat, 1, GLES20.GL_FLOAT, false, st, 36);
            GLES20.glEnableVertexAttribArray(g.aMat);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, n);
            GLES20.glDisableVertexAttribArray(g.aPos);
            GLES20.glDisableVertexAttribArray(g.aNor);
            GLES20.glDisableVertexAttribArray(g.aCol);
            GLES20.glDisableVertexAttribArray(g.aMat);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }
    }

    private static int u(int p, String n) { return GLES20.glGetUniformLocation(p, n); }

    private boolean failSaid;

    /** A program from two shaders; when the GPU refuses one, its reason goes to the log and he is told once (no black mystery). */
    private int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            android.util.Log.w("Car3DView", "program: " + GLES20.glGetProgramInfoLog(p));
            if (!failSaid && listener != null) { failSaid = true; post(listener::failed); }
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) android.util.Log.w("Car3DView", "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    // ================================================================ the shaders (GLSL ES 1.00; checked on the desk in WebGL)

    static final String LIT_V =
            "uniform mat4 uVP; uniform mat4 uM;\n"
                    + "attribute vec3 aPos; attribute vec3 aNor; attribute vec3 aCol; attribute float aMat;\n"
                    + "varying vec3 vN; varying vec3 vW; varying vec3 vC; varying vec3 vL; varying float vM;\n"
                    + "void main() {\n"
                    + "  vec4 w = uM * vec4(aPos, 1.0);\n"
                    + "  vW = w.xyz; vN = (uM * vec4(aNor, 0.0)).xyz; vC = aCol; vL = aPos; vM = aMat;\n"
                    + "  gl_Position = uVP * w;\n"
                    + "}\n";

    static final String LIT_F =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#define NOWIN\n#endif\n"
                    + "uniform vec3 uEye, uSun, uSunCol, uSkyTop, uFog, uPaint, uHead, uNeon, uGround;\n"
                    + "uniform float uAmb, uFogK, uNight, uFinish, uNeonOn, uBrake, uIndL, uIndR, uRain;\n"
                    + "uniform vec4 uCar; uniform vec2 uCarSize; uniform vec2 uOff;\n"
                    + "uniform vec4 uWall, uRoadT, uLane, uGroundT; uniform vec3 uWinA, uWinB; uniform float uWinM, uWet, uGrid, uRings, uFlat;\n"
                    + "varying vec3 vN; varying vec3 vW; varying vec3 vC; varying vec3 vL; varying float vM;\n"
                    + "float hash(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }\n"
                    + "void main() {\n"
                    + "  float m = vM + 0.5;\n"
                    + "  vec2 d = vW.xz - uCar.xy; vec2 f = uCar.zw; vec2 rt = vec2(-f.y, f.x);\n"
                    + "  if (m >= 13.0) {\n" // 13 the shadow, 14 the glow: soft ovals round the car
                    + "    float a = dot(d, rt) / uCarSize.x, b = dot(d, f) / uCarSize.y;\n"
                    + "    float q = clamp(1.0 - (a * a + b * b), 0.0, 1.0);\n"
                    + "    if (m < 14.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 0.5 * q); return; }\n"
                    + "    gl_FragColor = vec4(uNeon * uNeonOn * q * q * 0.9, 1.0); return;\n"
                    + "  }\n"
                    + "  if (m >= 10.0 && m < 11.0) {\n" // the headlights' beams: added light, fading along
                    + "    float along = dot(d, f) - uCarSize.y;\n"
                    + "    float k = clamp(1.0 - along / 14.0, 0.0, 1.0);\n"
                    + "    gl_FragColor = vec4(uHead * 0.07 * k * k * uNight, 1.0); return;\n"
                    + "  }\n"
                    + "  vec3 n = normalize(vN); if (!gl_FrontFacing) n = -n;\n"
                    + "  vec3 v = normalize(uEye - vW);\n"
                    + "  vec3 c = vC; vec3 col;\n"
                    + "  if (uFlat > 0.5) c = mix(c, uGroundT.rgb, uGroundT.a);\n" // (a world's ground: green fields, the white floor)
                    + "  float dist = length(uEye - vW);\n"
                    + "  if (m >= 1.0 && m < 2.0) {\n" // lights, road paint (a world's lane colour on the yellow: the lane's edges, the boards)
                    + "    c = mix(c, min(uLane.rgb * 1.15, vec3(1.0)), uLane.a * step(0.45, c.r - c.b) * step(0.5, c.g));\n"
                    + "    col = c * (1.0 - 0.25 * uNight * step(c.r + c.g + c.b, 2.0));\n"
                    + "  }\n"
                    + "  else if (m >= 8.0 && m < 9.0) col = mix(vec3(0.82, 0.88, 0.95), uHead * 1.25, uNight);\n" // headlights
                    + "  else if (m >= 5.0 && m < 6.0) col = c * (0.45 + 0.4 * uNight + 0.75 * uBrake) + vec3(0.25, 0.0, 0.0) * uBrake;\n" // tail lights
                    + "  else if (m >= 6.0 && m < 8.0) col = c * (0.3 + 1.0 * (m < 7.0 ? uIndL : uIndR));\n" // indicators
                    + "  else if (m >= 9.0 && m < 10.0) col = uNeon * (0.15 + 1.1 * uNeonOn);\n" // neon edges
                    + "  else {\n"
                    + "    float lam = max(dot(n, uSun), 0.0);\n"
                    + "    vec3 amb = uAmb * mix(uGround * 1.6, uSkyTop * 1.2 + vec3(0.25), 0.5 + 0.5 * n.y);\n"
                    + "    vec3 h = normalize(uSun + v);\n"
                    + "    float nh = max(dot(n, h), 0.0), ndv = max(dot(n, v), 0.0), fr = pow(1.0 - ndv, 3.0);\n"
                    + "    vec3 lit = amb + uSunCol * lam;\n"
                    + "    if (m < 1.0) {\n" // his paint
                    + "      vec3 p = uPaint;\n"
                    + "      if (uFinish > 0.5 && uFinish < 1.5) col = p * lit + vec3(0.04) * pow(nh, 6.0);\n"
                    + "      else col = p * lit + uSunCol * pow(nh, 40.0) * 0.7 + uSkyTop * fr * 0.45;\n"
                    + "      col += p * 0.16 * uNight;\n" // (the street's light on his paint at night: the car stays seen)
                    + "    } else if (m < 3.0) {\n" // glass
                    + "      col = vec3(0.04, 0.06, 0.09) * lit + uSunCol * pow(nh, 60.0) * 0.9 + (uSkyTop * 0.6 + vec3(0.08)) * (0.25 + 0.75 * fr);\n"
                    + "    } else if (m >= 11.0 && m < 12.0) {\n" // chrome
                    + "      col = c * 0.55 * lit + uSkyTop * 0.35 + uSunCol * pow(nh, 20.0) * 0.8;\n"
                    + "    } else if (m >= 3.0 && m < 4.0) {\n" // walls: windows on the walls, lit at night
                    + "      c = mix(c, uWall.rgb, uWall.a);\n" // (a world's walls: white clay, dark neon towers)
                    + "      col = c * lit;\n"
                    + "#ifndef NOWIN\n" // (windows need the GPU's fine numbers: without them, plain walls)
                    + "      if (abs(n.y) < 0.5 && vL.y > 1.0 && uWinM < 1.5) {\n"
                    + "        vec2 wp = vL.xz + uOff;\n"
                    + "        float al = dot(wp, vec2(-n.z, n.x));\n"
                    + "        vec2 cell = mod(vec2(floor(al / 2.8), floor(vL.y / 3.2)), 256.0);\n"
                    + "        vec2 fc = vec2(fract(al / 2.8), fract(vL.y / 3.2));\n"
                    + "        float win = step(0.22, fc.x) * step(fc.x, 0.78) * step(0.3, fc.y) * step(fc.y, 0.8);\n"
                    + "        float on = step(0.55, hash(cell + mod(floor(wp / 41.0), 64.0)));\n"
                    + "        vec3 glass = vec3(0.16, 0.22, 0.3) * lit + uSkyTop * 0.18 + uSunCol * pow(nh, 30.0) * 0.4;\n"
                    + "        vec3 warm = vec3(1.0, 0.8, 0.48) * (0.75 + 0.25 * hash(cell * 1.7));\n"
                    + "        if (uWinM > 0.5) { vec3 nw = mix(uWinA, uWinB, step(0.5, hash(cell * 2.3 + 7.0))); col = mix(col, mix(glass, mix(glass * 0.4, nw * 1.1, on), max(uNight, 0.6)), win); }\n" // (neon windows)
                    + "        else col = mix(col, mix(glass, mix(glass * 0.5, warm, on), uNight), win);\n"
                    + "      }\n"
                    + "#endif\n"
                    + "    } else if (m >= 12.0 && m < 13.0) {\n" // the road: wet in the rain, the headlights' pool at night
                    + "      float gold = step(0.15, c.r - c.b);\n" // (the gold lane: a world's lane colour; the rest its road)
                    + "      c = mix(c, uRoadT.rgb, uRoadT.a * (1.0 - gold));\n"
                    + "      c = mix(c, uLane.rgb, uLane.a * gold);\n"
                    + "      float wet = max(uRain, uWet);\n"
                    + "      col = c * lit * (1.0 - 0.25 * wet) + uSunCol * pow(nh, 30.0) * 0.35 * wet + uFog * fr * 0.55 * uWet;\n" // (wet: the sky's colour on the road)
                    + "      vec2 fd = d - f * uCarSize.y; float al = dot(fd, f), sd = abs(dot(fd, rt));\n"
                    + "      float pool = step(0.0, al) * clamp(1.0 - sd / (0.9 + al * 0.35), 0.0, 1.0) * exp(-al / 16.0);\n"
                    + "      col += uHead * pool * 0.55 * uNight;\n"
                    + "    } else col = c * lit;\n" // matte
                    + "  }\n"
                    + "  if (uFlat > 0.5 || (m >= 12.0 && m < 13.0)) {\n" // the white world's floor: rings round the car, a grid (decoration: no sensors)
                    + "    float r = length(d);\n"
                    + "    float ring = (1.0 - smoothstep(0.03, 0.12, abs(r - 4.4))) + (1.0 - smoothstep(0.03, 0.12, abs(r - 7.6)));\n"
                    + "    col = mix(col, vec3(0.98), clamp(ring, 0.0, 1.0) * uRings * 0.75 * step(r, 9.0));\n"
                    + "#ifndef NOWIN\n"
                    + "    vec2 gq = abs(fract((vL.xz + uOff) / 3.0) - 0.5);\n"
                    + "    col = mix(col, col * 0.84, smoothstep(0.465, 0.49, max(gq.x, gq.y)) * uGrid);\n"
                    + "#endif\n"
                    + "  }\n"
                    + "  float fog = 1.0 - exp(-dist * uFogK);\n"
                    + "  gl_FragColor = vec4(mix(col, uFog, clamp(fog, 0.0, 1.0)), 1.0);\n"
                    + "}\n";

    static final String SKY_V = "attribute vec2 aP; void main() { gl_Position = vec4(aP, 0.999, 1.0); }\n";

    static final String SKY_F =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
                    + "uniform vec3 uTop, uFog; uniform float uNight, uYaw, uHorizon; uniform vec2 uSize;\n"
                    + "uniform vec4 uDisc, uHills; uniform vec3 uDiscC;\n"
                    + "float hash(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }\n"
                    + "void main() {\n"
                    + "  float t = gl_FragCoord.y / uSize.y;\n"
                    + "  float k = smoothstep(uHorizon, 1.0, t);\n"
                    + "  vec3 col = mix(uFog, uTop, sqrt(k));\n"
                    + "  float a = uYaw * 57.3 + (gl_FragCoord.x / uSize.x - 0.5) * 60.0 * uSize.x / uSize.y;\n"
                    + "  vec2 cell = mod(floor(vec2(a * 3.0, (t - uHorizon) * 220.0)), 512.0);\n"
                    + "  float star = step(0.996, hash(cell)) * smoothstep(uHorizon + 0.06, uHorizon + 0.3, t) * uNight;\n"
                    + "  col += vec3(star * 0.8);\n"
                    + "  if (uDisc.w > 0.01) {\n" // a world's moon / sun: a disc with a soft glow round it
                    + "    vec2 p = vec2((gl_FragCoord.x / uSize.x - uDisc.x) * uSize.x / uSize.y, t - (uHorizon + uDisc.y));\n"
                    + "    float r = length(p);\n"
                    + "    float disc = 1.0 - smoothstep(uDisc.z - 0.004, uDisc.z, r);\n"
                    + "    float halo = exp(-max(r - uDisc.z, 0.0) * 9.0) * 0.5;\n"
                    + "    col = mix(col, uDiscC, disc * uDisc.w) + uDiscC * halo * (1.0 - disc) * uDisc.w * 0.5;\n"
                    + "  }\n"
                    + "  if (uHills.a > 0.01) {\n" // a world's hills along the horizon
                    + "    float ra = radians(a);\n"
                    + "    float hh = uHorizon + 0.03 + 0.025 * sin(ra * 3.0) + 0.018 * sin(ra * 7.3 + 1.0) + 0.01 * sin(ra * 17.0 + 2.0);\n"
                    + "    col = mix(col, uHills.rgb, uHills.a * (1.0 - smoothstep(hh - 0.002, hh + 0.002, t)) * step(uHorizon - 0.02, t));\n"
                    + "  }\n"
                    + "  gl_FragColor = vec4(col, 1.0);\n"
                    + "}\n";

    static final String PHOTO_V =
            "uniform mat4 uVP; attribute vec3 aPos; attribute vec2 aUV; varying vec2 vUV;\n"
                    + "void main() { vUV = aUV; gl_Position = uVP * vec4(aPos, 1.0); }\n";

    static final String PHOTO_F =
            "precision mediump float;\n"
                    + "uniform sampler2D uTex; uniform float uDark; uniform float uMode; uniform vec3 uColor; varying vec2 vUV;\n"
                    + "void main() {\n"
                    + "  if (uMode > 0.5) { float r = length(vUV * 2.0 - 1.0); float k = clamp(1.0 - r, 0.0, 1.0); gl_FragColor = vec4(uColor * k * k, 1.0); return; }\n"
                    + "  vec4 t = texture2D(uTex, vUV);\n"
                    + "  if (t.a < 0.4) discard;\n"
                    + "  gl_FragColor = vec4(t.rgb * uDark, 1.0);\n"
                    + "}\n";

    static final String LINE_V = "uniform mat4 uVP; attribute vec3 aPos; void main() { gl_Position = uVP * vec4(aPos, 1.0); }\n";
    static final String LINE_F = "precision mediump float; uniform vec4 uColor; void main() { gl_FragColor = uColor; }\n";
}
