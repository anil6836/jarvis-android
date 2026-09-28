package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The big Live-mode core: a holographic sphere of glowing nodes wired together like a neural network,
 * slowly turning. It breathes when idle, ripples with his voice while listening, swirls while thinking,
 * and pulses with Jarvis's own voice while speaking; sparks of light run along the wires.
 */
final class HoloOrb extends View {
    // the same numbers as OrbView's IDLE, LISTENING, THINKING, SPEAKING, OFFLINE, so either can show Jarvis's state
    static final int CONNECTING = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, MUTED = 4;
    static final int IDLE = CONNECTING, OFFLINE = MUTED;

    private final int N;                       // nodes on the sphere
    private static final int LINKS = 3;        // wires from each node to its nearest neighbours
    private final int SPARKS;
    private static final int BUCKETS = 5;      // depth layers for the wires (back = faint, front = bright)

    private final float[] ux, uy, uz, seed;
    private final float[] px, py, pz;
    private int[] ea, eb;
    private int[][] nodeEdges;                 // the wires at each node, for sparks to travel on
    private final float[][] lines = new float[BUCKETS][];
    private final int[] lineCount = new int[BUCKETS];
    private final int[] sparkEdge;
    private final float[] sparkPos;
    private final boolean[] sparkFwd;
    private final Random rnd = new Random(7);

    private final Paint wire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int state = CONNECTING;
    private volatile float mic, voice;         // loudness 0..1 of his mic / Jarvis's voice
    private volatile long voiceAt;             // when Jarvis's voice level last came in
    private float lvl;                         // smoothed level that drives the motion
    private float rotY, ringA, ringB;
    private float cr = 0x3B, cg = 0xB7, cb = 0xD6; // the colour now (blends to the state's colour)
    private final long start = SystemClock.uptimeMillis();
    private long last;

    HoloOrb(Context c) { this(c, 150); }

    /** nodes: 150 for the big Live core, fewer for a small one. */
    HoloOrb(Context c, int nodes) {
        super(c);
        N = nodes;
        SPARKS = nodes >= 120 ? 16 : 8;
        ux = new float[N]; uy = new float[N]; uz = new float[N]; seed = new float[N];
        px = new float[N]; py = new float[N]; pz = new float[N];
        sparkEdge = new int[SPARKS];
        sparkPos = new float[SPARKS];
        sparkFwd = new boolean[SPARKS];
        wire.setStyle(Paint.Style.STROKE);
        wire.setStrokeCap(Paint.Cap.ROUND);
        dot.setStyle(Paint.Style.FILL);
        glow.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        build();
    }

    void setState(int s) { state = s; }
    void setMic(float l) { mic = clamp(l); }
    /** Same as setMic (OrbView's name for the mic level). */
    void setLevel(float l) { mic = clamp(l); }
    void setVoice(float l) { voice = clamp(l); voiceAt = SystemClock.uptimeMillis(); }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Nodes spread evenly over a sphere (Fibonacci spiral), each wired to its nearest neighbours. */
    private void build() {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < N; i++) {
            double y = 1 - 2 * (i + 0.5) / N, r = Math.sqrt(1 - y * y), phi = i * golden;
            ux[i] = (float) (Math.cos(phi) * r);
            uy[i] = (float) y;
            uz[i] = (float) (Math.sin(phi) * r);
            seed[i] = rnd.nextFloat();
        }
        java.util.List<int[]> edges = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < N; i++) {
            int[] best = new int[LINKS];
            float[] bestDot = new float[LINKS];
            java.util.Arrays.fill(bestDot, -2f);
            for (int j = 0; j < N; j++) {
                if (j == i) continue;
                float d = ux[i] * ux[j] + uy[i] * uy[j] + uz[i] * uz[j];
                for (int k = 0; k < LINKS; k++) {
                    if (d > bestDot[k]) {
                        for (int m = LINKS - 1; m > k; m--) { bestDot[m] = bestDot[m - 1]; best[m] = best[m - 1]; }
                        bestDot[k] = d;
                        best[k] = j;
                        break;
                    }
                }
            }
            for (int k = 0; k < LINKS; k++) {
                int a = Math.min(i, best[k]), b = Math.max(i, best[k]);
                if (seen.add((long) a * N + b)) edges.add(new int[]{a, b});
            }
        }
        ea = new int[edges.size()];
        eb = new int[edges.size()];
        int[] count = new int[N];
        for (int e = 0; e < edges.size(); e++) {
            ea[e] = edges.get(e)[0];
            eb[e] = edges.get(e)[1];
            count[ea[e]]++;
            count[eb[e]]++;
        }
        nodeEdges = new int[N][];
        for (int i = 0; i < N; i++) nodeEdges[i] = new int[count[i]];
        int[] fill = new int[N];
        for (int e = 0; e < ea.length; e++) {
            nodeEdges[ea[e]][fill[ea[e]]++] = e;
            nodeEdges[eb[e]][fill[eb[e]]++] = e;
        }
        for (int b = 0; b < BUCKETS; b++) lines[b] = new float[ea.length * 4];
        for (int s = 0; s < SPARKS; s++) {
            sparkEdge[s] = rnd.nextInt(ea.length);
            sparkPos[s] = rnd.nextFloat();
            sparkFwd[s] = rnd.nextBoolean();
        }
    }

    private int targetColor() {
        switch (state) {
            case LISTENING: return 0xFF74E4FF; // cyan
            case THINKING: return 0xFF9D86FF;  // violet
            case SPEAKING: return 0xFF4FA3FF;  // electric blue
            case MUTED: return 0xFF62788A;     // grey-blue
            default: return 0xFF38BDF8;        // idle / connecting: sky blue
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float t = (now - start) / 1000f;
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * 0.29f;
        float px1 = getResources().getDisplayMetrics().density;

        // level: quick to rise, slower to fall, like a VU meter
        // speaking without a live voice level (the phone's own voice): a gentle talking pulse
        float talk = now - voiceAt > 400 ? 0.28f + 0.3f * (float) Math.abs(Math.sin(t * 8.5) * Math.sin(t * 3.1 + 1)) : voice;
        float target = state == LISTENING ? mic : state == SPEAKING ? talk : 0f;
        lvl += (target - lvl) * Math.min(1f, dt * (target > lvl ? 14f : 5f));
        int tc = targetColor();
        float k = Math.min(1f, dt * 4f);
        cr += (Color.red(tc) - cr) * k;
        cg += (Color.green(tc) - cg) * k;
        cb += (Color.blue(tc) - cb) * k;
        int r = (int) cr, g = (int) cg, b = (int) cb;

        float spin = state == THINKING ? 1.5f : state == SPEAKING ? 0.45f + 0.9f * lvl : state == MUTED ? 0.12f : 0.3f + 0.5f * lvl;
        rotY += spin * dt;
        ringA += (state == THINKING ? 2.2f : 0.5f + lvl) * dt;
        ringB -= (state == THINKING ? 1.6f : 0.35f + 0.6f * lvl) * dt;
        float tilt = 0.38f + 0.12f * (float) Math.sin(t * 0.4);
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 1.7);
        float scale = 1f + 0.035f * breathe + 0.1f * lvl;

        // ---- halo and inner core
        float haloR = R * (1.55f + 0.25f * lvl);
        glow.setShader(new RadialGradient(cx, cy, haloR,
                new int[]{Color.argb((int) (70 + 90 * lvl), r, g, b), Color.argb((int) (28 + 30 * lvl), r, g, b), Color.argb(0, r, g, b)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, glow);
        float coreR = R * (0.5f + 0.08f * breathe + 0.3f * lvl);
        glow.setShader(new RadialGradient(cx, cy, coreR,
                new int[]{Color.argb((int) (150 + 100 * lvl), 235, 250, 255), Color.argb((int) (90 + 80 * lvl), r, g, b), Color.argb(0, r, g, b)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, glow);
        glow.setShader(null);

        // ---- hologram rings (tilted ellipses with dashes, turning)
        drawRing(canvas, cx, cy, R * 1.28f, 0.30f, -18f, ringA, r, g, b, 9, 0.55f, 1.4f * px1);
        drawRing(canvas, cx, cy, R * 1.42f, 0.22f, 24f, ringB, r, g, b, 22, 0.35f, 1f * px1);

        // ---- place the nodes: rotate, ripple with the voice, perspective
        float cosY = (float) Math.cos(rotY), sinY = (float) Math.sin(rotY);
        float cosX = (float) Math.cos(tilt), sinX = (float) Math.sin(tilt);
        for (int i = 0; i < N; i++) {
            float ripple = 1f + 0.018f * (float) Math.sin(t * 1.3f + seed[i] * 6.28f)
                    + lvl * 0.2f * (0.5f + 0.5f * (float) Math.sin(t * 9f + seed[i] * 12.6f + uy[i] * 4f));
            if (state == THINKING) ripple += 0.07f * (float) Math.sin(t * 6f + uy[i] * 7f + rotY * 2f);
            float x = ux[i] * ripple, y = uy[i] * ripple, z = uz[i] * ripple;
            float x1 = x * cosY + z * sinY, z1 = -x * sinY + z * cosY;
            float y1 = y * cosX - z1 * sinX, z2 = y * sinX + z1 * cosX;
            float persp = 4.2f / (4.2f - z2);
            px[i] = cx + x1 * R * scale * persp;
            py[i] = cy + y1 * R * scale * persp;
            pz[i] = z2; // -1.3 (back) .. 1.3 (front)
        }

        // ---- wires, batched by depth
        java.util.Arrays.fill(lineCount, 0);
        for (int e = 0; e < ea.length; e++) {
            int a = ea[e], c = eb[e];
            float depth = ((pz[a] + pz[c]) * 0.5f + 1.2f) / 2.4f;
            int bk = Math.max(0, Math.min(BUCKETS - 1, (int) (depth * BUCKETS)));
            float[] L = lines[bk];
            int n = lineCount[bk];
            L[n] = px[a]; L[n + 1] = py[a]; L[n + 2] = px[c]; L[n + 3] = py[c];
            lineCount[bk] = n + 4;
        }
        wire.setStrokeWidth(Math.max(1f, 0.9f * px1));
        for (int bk = 0; bk < BUCKETS; bk++) {
            if (lineCount[bk] == 0) continue;
            float f = (bk + 0.5f) / BUCKETS;
            wire.setColor(Color.argb((int) (18 + 150 * f * f + 60 * lvl * f), r, g, b));
            canvas.drawLines(lines[bk], 0, lineCount[bk], wire);
        }

        // ---- nodes
        for (int i = 0; i < N; i++) {
            float f = (pz[i] + 1.2f) / 2.4f;
            float rad = px1 * (0.6f + 1.4f * f) * (1f + 0.5f * lvl);
            int a = (int) (40 + 215 * f * f);
            // front nodes glow whiter
            int wr = (int) (r + (255 - r) * f * 0.6f), wg = (int) (g + (255 - g) * f * 0.6f), wb = (int) (b + (255 - b) * f * 0.6f);
            dot.setColor(Color.argb(Math.min(255, a), wr, wg, wb));
            canvas.drawCircle(px[i], py[i], rad, dot);
        }

        // ---- sparks running along the wires, hopping to the next wire at each node
        float sparkSpeed = state == THINKING ? 2.4f : 0.7f + 2.2f * lvl;
        for (int s = 0; s < SPARKS; s++) {
            sparkPos[s] += sparkSpeed * dt * (0.7f + 0.6f * ((s * 37) % 10) / 10f);
            if (sparkPos[s] >= 1f) {
                int e = sparkEdge[s];
                int at = sparkFwd[s] ? eb[e] : ea[e];
                int[] next = nodeEdges[at];
                int ne = next[rnd.nextInt(next.length)];
                sparkEdge[s] = ne;
                sparkFwd[s] = ea[ne] == at;
                sparkPos[s] = 0f;
            }
            int e = sparkEdge[s];
            int from = sparkFwd[s] ? ea[e] : eb[e], to = sparkFwd[s] ? eb[e] : ea[e];
            float p = sparkPos[s];
            float sx = px[from] + (px[to] - px[from]) * p, sy = py[from] + (py[to] - py[from]) * p;
            float f = ((pz[from] + (pz[to] - pz[from]) * p) + 1.2f) / 2.4f;
            float gr = px1 * (5f + 4f * lvl);
            dot.setColor(Color.argb((int) (45 * f), r, g, b));
            canvas.drawCircle(sx, sy, gr, dot);
            dot.setColor(Color.argb((int) (110 * f), r, g, b));
            canvas.drawCircle(sx, sy, gr * 0.5f, dot);
            dot.setColor(Color.argb((int) (240 * f), 240, 252, 255));
            canvas.drawCircle(sx, sy, gr * 0.22f, dot);
        }

        if (isAttachedToWindow() && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    private void drawRing(Canvas canvas, float cx, float cy, float rr, float squash, float angle, float turn,
                          int r, int g, int b, int dashes, float fill, float width) {
        canvas.save();
        canvas.rotate(angle, cx, cy);
        oval.set(cx - rr, cy - rr * squash, cx + rr, cy + rr * squash);
        ring.setStrokeWidth(width);
        ring.setColor(Color.argb((int) (70 + 80 * lvl), r, g, b));
        float sweep = 360f / dashes;
        float rot = (float) Math.toDegrees(turn) % 360f;
        for (int i = 0; i < dashes; i++) canvas.drawArc(oval, rot + i * sweep, sweep * fill, false, ring);
        canvas.restore();
    }

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) { last = 0; invalidate(); }
    }
}
