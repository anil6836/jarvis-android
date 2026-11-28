package com.anil.jarvis;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 📐 Draws a real-scan surface (Tsdf.Mesh) with OpenGL ES 2: its own colours, lit from where one looks, both sides (an
 * open scan has no inside to hide). Also plain lines (the scan box, the size box, the floor ring). GL thread only.
 */
final class MeshGl {
    private int prog, aPos, aNrm, aCol, uMvp, uLight, uAlpha;
    private int lineProg, lPos, lMvp, lColor;
    private int vbo;
    private int count;
    private static final int STRIDE = 20; // x y z (float) · nx ny nz pad (byte) · r g b a (byte)

    /** After the surface (re)made: its programs. false = this phone's GL could not make them. */
    boolean init() {
        prog = program(
                "uniform mat4 u_Mvp; uniform vec3 u_Light;\n"
                        + "attribute vec3 a_Pos; attribute vec3 a_Nrm; attribute vec3 a_Col;\n"
                        + "varying vec3 v_Col;\n"
                        + "void main() {\n"
                        + "  gl_Position = u_Mvp * vec4(a_Pos, 1.0);\n"
                        + "  vec3 n = normalize(a_Nrm + vec3(0.0001));\n"
                        + "  float d = abs(dot(n, u_Light));\n"
                        + "  v_Col = a_Col * (0.38 + 0.72 * d);\n"
                        + "}\n",
                "precision mediump float;\nuniform float u_Alpha;\nvarying vec3 v_Col;\n"
                        + "void main() { gl_FragColor = vec4(v_Col, u_Alpha); }\n");
        lineProg = program(
                "uniform mat4 u_Mvp; attribute vec3 a_Pos; void main() { gl_Position = u_Mvp * vec4(a_Pos, 1.0); }\n",
                "precision mediump float; uniform vec4 u_Color; void main() { gl_FragColor = u_Color; }\n");
        if (prog == 0 || lineProg == 0) return false;
        aPos = GLES20.glGetAttribLocation(prog, "a_Pos");
        aNrm = GLES20.glGetAttribLocation(prog, "a_Nrm");
        aCol = GLES20.glGetAttribLocation(prog, "a_Col");
        uMvp = GLES20.glGetUniformLocation(prog, "u_Mvp");
        uLight = GLES20.glGetUniformLocation(prog, "u_Light");
        uAlpha = GLES20.glGetUniformLocation(prog, "u_Alpha");
        lPos = GLES20.glGetAttribLocation(lineProg, "a_Pos");
        lMvp = GLES20.glGetUniformLocation(lineProg, "u_Mvp");
        lColor = GLES20.glGetUniformLocation(lineProg, "u_Color");
        vbo = 0;
        count = 0;
        return true;
    }

    int triangles() { return count / 3; }

    /** The surface to draw from now on (null = nothing). */
    void upload(Tsdf.Mesh m) {
        if (m == null || m.tris == 0) { count = 0; return; }
        int n = m.tris * 3;
        ByteBuffer b = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder());
        for (int t = 0; t < m.tris * 3; t++) {
            int v = m.tri[t];
            b.putFloat(m.pos[v * 3]).putFloat(m.pos[v * 3 + 1]).putFloat(m.pos[v * 3 + 2]);
            b.put(m.nrm[v * 3]).put(m.nrm[v * 3 + 1]).put(m.nrm[v * 3 + 2]).put((byte) 0);
            b.put(m.col[v * 3]).put(m.col[v * 3 + 1]).put(m.col[v * 3 + 2]).put((byte) 255);
        }
        b.position(0);
        if (vbo == 0) { int[] id = new int[1]; GLES20.glGenBuffers(1, id, 0); vbo = id[0]; }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * STRIDE, b, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        count = n;
    }

    /** The surface: mvp = projection · view · model; light = the way the light comes, in the model's own terms (length 1). */
    void draw(float[] mvp, float[] light, float alpha) {
        if (count == 0 || vbo == 0) return;
        GLES20.glUseProgram(prog);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        if (alpha < 1f) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); }
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform3f(uLight, light[0], light[1], light[2]);
        GLES20.glUniform1f(uAlpha, alpha);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0);
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_BYTE, true, STRIDE, 12);
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_UNSIGNED_BYTE, true, STRIDE, 16);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNrm);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aNrm);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        if (alpha < 1f) GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Lines (pairs of points x, y, z) in one colour (ARGB). */
    void lines(float[] mvp, float[] xyz, int argb, boolean overEverything) {
        if (xyz == null || xyz.length < 6) return;
        FloatBuffer fb = ByteBuffer.allocateDirect(xyz.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(xyz).position(0);
        GLES20.glUseProgram(lineProg);
        if (overEverything) GLES20.glDisable(GLES20.GL_DEPTH_TEST); else GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glLineWidth(3f);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mvp, 0);
        GLES20.glUniform4f(lColor, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, fb);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, xyz.length / 3);
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /** The 12 edges of a box turned `deg` round the up axis: centre (cx, cy, cz), sizes (sx, sy, sz). */
    static float[] box(float cx, float cy, float cz, float sx, float sy, float sz, float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[][] p = new float[8][];
        for (int i = 0; i < 8; i++) {
            float x = ((i & 1) == 0 ? -0.5f : 0.5f) * sx, y = ((i & 2) == 0 ? -0.5f : 0.5f) * sy, z = ((i & 4) == 0 ? -0.5f : 0.5f) * sz;
            p[i] = new float[]{cx + x * c - z * s, cy + y, cz + x * s + z * c};
        }
        int[][] e = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        float[] out = new float[e.length * 6];
        for (int i = 0; i < e.length; i++) {
            System.arraycopy(p[e[i][0]], 0, out, i * 6, 3);
            System.arraycopy(p[e[i][1]], 0, out, i * 6 + 3, 3);
        }
        return out;
    }

    /** A ring on the floor (y), round (cx, cz), radius r: line pairs. */
    static float[] ring(float cx, float y, float cz, float r, int seg) {
        float[] out = new float[seg * 6];
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            out[i * 6] = cx + (float) Math.cos(a0) * r; out[i * 6 + 1] = y; out[i * 6 + 2] = cz + (float) Math.sin(a0) * r;
            out[i * 6 + 3] = cx + (float) Math.cos(a1) * r; out[i * 6 + 4] = y; out[i * 6 + 5] = cz + (float) Math.sin(a1) * r;
        }
        return out;
    }

    static int program(String vs, String fs) {
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
