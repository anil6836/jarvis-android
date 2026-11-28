package com.anil.jarvis;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 📐 The real 3D scan's own maths (no Android here: desk-tested). The phone measures how far every point of the picture is
 * (Google ARCore's depth) and knows where it is itself; every such depth picture is poured into a box of small cubes
 * (voxels) around the thing: each cube keeps how far it is from the nearest surface (signed: + in front, - behind, cut at a
 * few cubes: a "truncated signed distance"), averaged over all the pictures that saw it, with the colour seen there. The
 * surface is where that distance is zero; marching cubes turns it into triangles. Nothing here guesses: what was never
 * seen stays empty.
 *
 * World: metres, y up (ARCore's own world). Camera: x right, y up, looking along -z; picture rows go down.
 */
final class Tsdf {
    /** Cubes per side. */
    final int n;
    /** One cube's side, and the box's lowest corner (metres). */
    final float vox, ox, oy, oz;
    /** How far from a surface the distance is kept (metres). */
    final float trunc;
    /** Distance / trunc (1 = empty or unknown) and how much it was seen (0 = never). */
    final float[] d, w;
    final byte[] cr, cg, cb, cw;
    /** The floor under the thing (world height); NaN = no floor known. Nothing up to CUT above it is kept. */
    volatile float floorY = Float.NaN;
    static final float CUT = 0.012f;
    int frames;

    static final float WMAX = 60f, WMIN = 1.5f;
    private static final int CWMAX = 24;
    /** Depth farther than this is not trusted (metres). */
    static final float FAR = 4.0f;

    Tsdf(int n, float size, float cx, float cy, float cz) {
        this.n = n;
        vox = size / n;
        ox = cx - size / 2;
        oy = cy - size / 2;
        oz = cz - size / 2;
        trunc = Math.max(3 * vox, 0.012f);
        int all = n * n * n;
        d = new float[all];
        Arrays.fill(d, 1f);
        w = new float[all];
        cr = new byte[all];
        cg = new byte[all];
        cb = new byte[all];
        cw = new byte[all];
    }

    float size() { return vox * n; }

    int at(int i, int j, int k) { return (k * n + j) * n + i; }

    /**
     * One depth picture into the box. depth: metres per pixel (0 = none), dw × dh, row by row; conf: each pixel's weight
     * 0..1 (null = all 1); pose: camera → world, 4×4 column-major (ARCore's Pose.toMatrix); fx, fy, cx, cy: the depth
     * picture's pinhole in its own pixels. col: the colour picture (ARGB, iw × ih; null = none) and map = {ax, ay, bx, by}:
     * a depth-picture fraction (u, v) is colour pixel (ax + u·bx, ay + v·by). Returns how many cubes it changed.
     */
    int integrate(float[] depth, float[] conf, int dw, int dh, float fx, float fy, float cx, float cy, float[] pose,
                  int[] col, int iw, int ih, float[] map) {
        final float r00 = pose[0], r10 = pose[1], r20 = pose[2];   // the camera's x axis in the world
        final float r01 = pose[4], r11 = pose[5], r21 = pose[6];   // its y axis
        final float r02 = pose[8], r12 = pose[9], r22 = pose[10];  // its z axis (it looks along -z)
        final float tx = pose[12], ty = pose[13], tz = pose[14];
        final float sx = r00 * vox, sy = r01 * vox, sz = r02 * vox; // one cube along world x, in camera terms
        final float cut = floorY + CUT, tr = trunc, colBand = trunc * 0.6f;
        final boolean hasCol = col != null && map != null && iw > 0 && ih > 0;
        int changed = 0;
        for (int k = 0; k < n; k++) {
            float pz = oz + (k + 0.5f) * vox, dz = pz - tz;
            for (int j = 0; j < n; j++) {
                float py = oy + (j + 0.5f) * vox;
                if (!Float.isNaN(cut) && py <= cut) continue;
                float dy = py - ty, dx0 = ox + 0.5f * vox - tx;
                float xc = r00 * dx0 + r10 * dy + r20 * dz, yc = r01 * dx0 + r11 * dy + r21 * dz, zc = r02 * dx0 + r12 * dy + r22 * dz;
                int row = (k * n + j) * n;
                for (int i = 0; i < n; i++, xc += sx, yc += sy, zc += sz) {
                    if (zc > -0.08f) continue; // behind the camera or right on it
                    float dist = -zc, inv = 1f / dist;
                    float u = fx * xc * inv + cx, v = cy - fy * yc * inv;
                    if (u < 0 || v < 0) continue;
                    int ui = (int) u, vi = (int) v;
                    if (ui >= dw || vi >= dh) continue;
                    int px = vi * dw + ui;
                    float dd = depth[px];
                    if (dd <= 0 || dd > FAR) continue;
                    float sdf = dd - dist;
                    if (sdf < -tr) continue; // hidden behind the surface: not seen
                    float wo = conf == null ? 1f : conf[px];
                    if (wo <= 0) continue;
                    float f = sdf >= tr ? 1f : sdf / tr;
                    int idx = row + i;
                    float ww = w[idx];
                    d[idx] = (d[idx] * ww + f * wo) / (ww + wo);
                    w[idx] = Math.min(WMAX, ww + wo);
                    changed++;
                    if (hasCol && sdf < colBand && sdf > -colBand) {
                        int X = (int) (map[0] + (u / dw) * map[2]), Y = (int) (map[1] + (v / dh) * map[3]);
                        if (X >= 0 && Y >= 0 && X < iw && Y < ih) {
                            int c = col[Y * iw + X];
                            int cwi = cw[idx] & 0xFF;
                            int r = ((cr[idx] & 0xFF) * cwi + ((c >> 16) & 0xFF)) / (cwi + 1);
                            int g = ((cg[idx] & 0xFF) * cwi + ((c >> 8) & 0xFF)) / (cwi + 1);
                            int b = ((cb[idx] & 0xFF) * cwi + (c & 0xFF)) / (cwi + 1);
                            cr[idx] = (byte) r;
                            cg[idx] = (byte) g;
                            cb[idx] = (byte) b;
                            if (cwi < CWMAX) cw[idx] = (byte) (cwi + 1);
                        }
                    }
                }
            }
        }
        frames++;
        return changed;
    }

    // ================================================================ the surface as triangles

    /** Triangles: per vertex a place (x, y, z), a colour (r, g, b) and a normal (-127..127 each); three vertex numbers a triangle. */
    static final class Mesh {
        float[] pos = new float[0];
        byte[] col = new byte[0], nrm = new byte[0];
        int[] tri = new int[0];
        int verts, tris;
        float floorY = Float.NaN;

        float[] bounds() {
            float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int v = 0; v < verts; v++)
                for (int a = 0; a < 3; a++) { float x = pos[v * 3 + a]; if (x < b[a]) b[a] = x; if (x > b[a + 3]) b[a + 3] = x; }
            return b;
        }
    }

    private boolean valid(int idx, float y) {
        if (w[idx] < WMIN) return false;
        float c = floorY;
        return Float.isNaN(c) || y > c + CUT;
    }

    /** The surface (step 1 = every cube; 2 = a lighter one for the live look). */
    Mesh extract(int step) {
        step = Math.max(1, step);
        final int m = (n - 1) / step + 1; // points per side
        FloatList pos = new FloatList(1 << 16);
        ByteList col = new ByteList(1 << 16), nrm = new ByteList(1 << 16);
        IntList tri = new IntList(1 << 16);
        int[] exA = new int[m * m], eyA = new int[m * m], exB = new int[m * m], eyB = new int[m * m], ez = new int[m * m];
        Arrays.fill(exA, -1);
        Arrays.fill(eyA, -1);
        float[] val = new float[8];
        int[] cid = new int[8];
        int[] ev = new int[12];
        float[] gx = new float[3];
        for (int c = 0; c + 1 < m; c++) {
            Arrays.fill(exB, -1);
            Arrays.fill(eyB, -1);
            Arrays.fill(ez, -1);
            for (int b = 0; b + 1 < m; b++) {
                for (int a = 0; a + 1 < m; a++) {
                    int cube = 0;
                    boolean ok = true;
                    for (int q = 0; q < 8; q++) {
                        int i = (a + McTables.CORNER[q][0]) * step, j = (b + McTables.CORNER[q][1]) * step, k = (c + McTables.CORNER[q][2]) * step;
                        int idx = at(i, j, k);
                        if (!valid(idx, oy + (j + 0.5f) * vox)) { ok = false; break; }
                        val[q] = d[idx];
                        cid[q] = idx;
                        if (val[q] < 0) cube |= 1 << q;
                    }
                    if (!ok || cube == 0 || cube == 255) continue;
                    int[] t = McTables.TRI[cube];
                    if (t.length == 0) continue;
                    Arrays.fill(ev, -1);
                    for (int e : t) {
                        if (ev[e] >= 0) continue;
                        int q0 = McTables.EDGE[e][0], q1 = McTables.EDGE[e][1];
                        int la = a + Math.min(McTables.CORNER[q0][0], McTables.CORNER[q1][0]);
                        int lb = b + Math.min(McTables.CORNER[q0][1], McTables.CORNER[q1][1]);
                        int lc = Math.min(McTables.CORNER[q0][2], McTables.CORNER[q1][2]); // 0: this layer, 1: the next
                        int axis = McTables.CORNER[q0][0] != McTables.CORNER[q1][0] ? 0 : McTables.CORNER[q0][1] != McTables.CORNER[q1][1] ? 1 : 2;
                        int[] store = axis == 0 ? (lc == 0 ? exA : exB) : axis == 1 ? (lc == 0 ? eyA : eyB) : ez;
                        int key = lb * m + la;
                        int id = store[key];
                        if (id < 0) {
                            float v0 = val[q0], v1 = val[q1];
                            float f = v0 == v1 ? 0.5f : v0 / (v0 - v1);
                            if (f < 0) f = 0;
                            if (f > 1) f = 1;
                            int i0 = (a + McTables.CORNER[q0][0]) * step, j0 = (b + McTables.CORNER[q0][1]) * step, k0 = (c + McTables.CORNER[q0][2]) * step;
                            int i1 = (a + McTables.CORNER[q1][0]) * step, j1 = (b + McTables.CORNER[q1][1]) * step, k1 = (c + McTables.CORNER[q1][2]) * step;
                            float x = ox + (i0 + 0.5f + (i1 - i0) * f) * vox, y = oy + (j0 + 0.5f + (j1 - j0) * f) * vox, z = oz + (k0 + 0.5f + (k1 - k0) * f) * vox;
                            id = pos.size() / 3;
                            pos.add(x); pos.add(y); pos.add(z);
                            int ia = cid[q0], ib = cid[q1];
                            int wa = cw[ia] & 0xFF, wb = cw[ib] & 0xFF;
                            float fa = wa == 0 ? 0 : wb == 0 ? 1 : 1 - f, fb = wb == 0 ? 0 : wa == 0 ? 1 : f;
                            if (wa == 0 && wb == 0) { col.add((byte) 0x58); col.add((byte) 0xC8); col.add((byte) 0xE0); } // never seen in colour: our cyan
                            else {
                                col.add((byte) Math.round((cr[ia] & 0xFF) * fa + (cr[ib] & 0xFF) * fb));
                                col.add((byte) Math.round((cg[ia] & 0xFF) * fa + (cg[ib] & 0xFF) * fb));
                                col.add((byte) Math.round((cb[ia] & 0xFF) * fa + (cb[ib] & 0xFF) * fb));
                            }
                            float[] ga = grad(i0, j0, k0, step, gx).clone(), gb = grad(i1, j1, k1, step, gx);
                            float nx = ga[0] * (1 - f) + gb[0] * f, ny = ga[1] * (1 - f) + gb[1] * f, nz = ga[2] * (1 - f) + gb[2] * f;
                            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            if (len < 1e-6f) { nx = 0; ny = 1; nz = 0; len = 1; }
                            nrm.add((byte) Math.round(nx / len * 127)); nrm.add((byte) Math.round(ny / len * 127)); nrm.add((byte) Math.round(nz / len * 127));
                            store[key] = id;
                        }
                        ev[e] = id;
                    }
                    for (int s = 0; s + 2 < t.length; s += 3) {
                        int v0 = ev[t[s]], v1 = ev[t[s + 1]], v2 = ev[t[s + 2]];
                        if (v0 == v1 || v1 == v2 || v0 == v2) continue;
                        // the triangle faces out (the way the distance grows)
                        float ax = pos.get(v1 * 3) - pos.get(v0 * 3), ay = pos.get(v1 * 3 + 1) - pos.get(v0 * 3 + 1), az = pos.get(v1 * 3 + 2) - pos.get(v0 * 3 + 2);
                        float bx = pos.get(v2 * 3) - pos.get(v0 * 3), by = pos.get(v2 * 3 + 1) - pos.get(v0 * 3 + 1), bz = pos.get(v2 * 3 + 2) - pos.get(v0 * 3 + 2);
                        float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
                        float sn = 0;
                        for (int vv : new int[]{v0, v1, v2}) sn += cx * nrm.get(vv * 3) + cy * nrm.get(vv * 3 + 1) + cz * nrm.get(vv * 3 + 2);
                        tri.add(v0);
                        if (sn < 0) { tri.add(v2); tri.add(v1); } else { tri.add(v1); tri.add(v2); }
                    }
                }
            }
            int[] s;
            s = exA; exA = exB; exB = s;
            s = eyA; eyA = eyB; eyB = s;
        }
        Mesh out = new Mesh();
        out.pos = pos.toArray();
        out.col = col.toArray();
        out.nrm = nrm.toArray();
        out.tri = tri.toArray();
        out.verts = out.pos.length / 3;
        out.tris = out.tri.length / 3;
        out.floorY = floorY;
        return out;
    }

    /** The way the distance grows at a cube (outwards), from its neighbours `step` away (one-sided at the edges or unseen ones). */
    private float[] grad(int i, int j, int k, int step, float[] g) {
        int idx = at(i, j, k);
        float c = d[idx];
        g[0] = diff(i - step >= 0 ? at(i - step, j, k) : -1, i + step < n ? at(i + step, j, k) : -1, c);
        g[1] = diff(j - step >= 0 ? at(i, j - step, k) : -1, j + step < n ? at(i, j + step, k) : -1, c);
        g[2] = diff(k - step >= 0 ? at(i, j, k - step) : -1, k + step < n ? at(i, j, k + step) : -1, c);
        return g;
    }

    private float diff(int lo, int hi, float c) {
        boolean l = lo >= 0 && w[lo] >= WMIN, h = hi >= 0 && w[hi] >= WMIN;
        if (l && h) return (d[hi] - d[lo]) * 0.5f;
        if (h) return d[hi] - c;
        if (l) return c - d[lo];
        return 0;
    }

    // ================================================================ tidy: only the thing, not the specks around it

    /** Keeps the pieces that are a real part of the thing: at least minPart of the biggest piece's triangles (and never specks under 40). */
    static Mesh keepMain(Mesh m, float minPart) {
        if (m.tris == 0) return m;
        int[] parent = new int[m.verts];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int t = 0; t < m.tris; t++) { union(parent, m.tri[t * 3], m.tri[t * 3 + 1]); union(parent, m.tri[t * 3], m.tri[t * 3 + 2]); }
        int[] count = new int[m.verts];
        int biggest = 0;
        for (int t = 0; t < m.tris; t++) { int r = find(parent, m.tri[t * 3]); count[r]++; if (count[r] > biggest) biggest = count[r]; }
        int keepAt = Math.max(40, Math.round(biggest * minPart));
        int[] remap = new int[m.verts];
        Arrays.fill(remap, -1);
        IntList tri = new IntList(m.tri.length);
        FloatList pos = new FloatList(m.pos.length);
        ByteList col = new ByteList(m.col.length), nrm = new ByteList(m.nrm.length);
        for (int t = 0; t < m.tris; t++) {
            if (count[find(parent, m.tri[t * 3])] < keepAt) continue;
            for (int s = 0; s < 3; s++) {
                int v = m.tri[t * 3 + s];
                if (remap[v] < 0) {
                    remap[v] = pos.size() / 3;
                    for (int a = 0; a < 3; a++) { pos.add(m.pos[v * 3 + a]); col.add(m.col[v * 3 + a]); nrm.add(m.nrm[v * 3 + a]); }
                }
                tri.add(remap[v]);
            }
        }
        Mesh out = new Mesh();
        out.pos = pos.toArray();
        out.col = col.toArray();
        out.nrm = nrm.toArray();
        out.tri = tri.toArray();
        out.verts = out.pos.length / 3;
        out.tris = out.tri.length / 3;
        out.floorY = m.floorY;
        return out;
    }

    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
    private static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[a] = b; }

    // ================================================================ sizes

    /**
     * The thing's sizes in centimetres: {width (the longer side across), depth, height, the turn (degrees) of that across
     * rectangle}. Height from the floor when the thing stands on it.
     */
    static float[] dimsCm(Mesh m) {
        float[] f = fit(m);
        float sa = f[2] - f[1], sb = f[4] - f[3];
        return new float[]{Math.max(sa, sb) * 100, Math.min(sa, sb) * 100, (f[6] - f[5]) * 100, f[0]};
    }

    /**
     * The smallest box round the thing standing upright: {turn (degrees round the up axis), a0, a1, b0, b1 (its extent
     * along the turned x and z: a = x·cos + z·sin, b = -x·sin + z·cos), bottom, top (world heights)}.
     */
    static float[] fit(Mesh m) {
        if (m.verts == 0) return new float[]{0, 0, 0, 0, 0, 0, 0};
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int v = 0; v < m.verts; v++) { float y = m.pos[v * 3 + 1]; if (y < minY) minY = y; if (y > maxY) maxY = y; }
        float bottom = !Float.isNaN(m.floorY) && minY - m.floorY < 0.06f ? Math.min(minY, m.floorY) : minY;
        float best = Float.MAX_VALUE;
        float[] out = new float[7];
        int every = Math.max(1, m.verts / 20000);
        for (int deg = 0; deg < 90; deg++) {
            double r = Math.toRadians(deg);
            float c = (float) Math.cos(r), s = (float) Math.sin(r);
            float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE;
            for (int v = 0; v < m.verts; v += every) {
                float x = m.pos[v * 3], z = m.pos[v * 3 + 2];
                float a = x * c + z * s, b = -x * s + z * c;
                if (a < a0) a0 = a;
                if (a > a1) a1 = a;
                if (b < b0) b0 = b;
                if (b > b1) b1 = b;
            }
            float area = (a1 - a0) * (b1 - b0);
            if (area < best) { best = area; out = new float[]{deg, a0, a1, b0, b1, bottom, maxY}; }
        }
        return out;
    }

    // ================================================================ a ray from a photo onto the surface (tags)

    /**
     * Where a photo's pixel looks, in the world: {origin x, y, z, direction x, y, z} (direction of length 1). pose: camera →
     * world (column-major); fx, fy, cx, cy: that photo's pinhole in its own pixels (rows go down).
     */
    static float[] ray(float[] pose, float fx, float fy, float cx, float cy, float u, float v) {
        float xc = (u - cx) / fx, yc = (cy - v) / fy, zc = -1f;
        float dx = pose[0] * xc + pose[4] * yc + pose[8] * zc;
        float dy = pose[1] * xc + pose[5] * yc + pose[9] * zc;
        float dz = pose[2] * xc + pose[6] * yc + pose[10] * zc;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new float[]{pose[12], pose[13], pose[14], dx / l, dy / l, dz / l};
    }

    /** The nearest place the ray meets the surface: {x, y, z, nx, ny, nz, distance}, or null. */
    static float[] hit(Mesh m, float[] ray) {
        float best = Float.MAX_VALUE;
        int bt = -1;
        float ox = ray[0], oy = ray[1], oz = ray[2], dx = ray[3], dy = ray[4], dz = ray[5];
        for (int t = 0; t < m.tris; t++) {
            int a = m.tri[t * 3] * 3, b = m.tri[t * 3 + 1] * 3, c = m.tri[t * 3 + 2] * 3;
            float e1x = m.pos[b] - m.pos[a], e1y = m.pos[b + 1] - m.pos[a + 1], e1z = m.pos[b + 2] - m.pos[a + 2];
            float e2x = m.pos[c] - m.pos[a], e2y = m.pos[c + 1] - m.pos[a + 1], e2z = m.pos[c + 2] - m.pos[a + 2];
            float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            float det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12f) continue;
            float inv = 1f / det;
            float sx = ox - m.pos[a], sy = oy - m.pos[a + 1], sz = oz - m.pos[a + 2];
            float u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) continue;
            float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            float v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) continue;
            float dist = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (dist > 0.01f && dist < best) { best = dist; bt = t; }
        }
        if (bt < 0) return null;
        int a = m.tri[bt * 3] * 3, b = m.tri[bt * 3 + 1] * 3, c = m.tri[bt * 3 + 2] * 3;
        float e1x = m.pos[b] - m.pos[a], e1y = m.pos[b + 1] - m.pos[a + 1], e1z = m.pos[b + 2] - m.pos[a + 2];
        float e2x = m.pos[c] - m.pos[a], e2y = m.pos[c + 1] - m.pos[a + 1], e2z = m.pos[c + 2] - m.pos[a + 2];
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l > 0) { nx /= l; ny /= l; nz /= l; }
        if (nx * dx + ny * dy + nz * dz > 0) { nx = -nx; ny = -ny; nz = -nz; } // (towards the one who looks)
        return new float[]{ox + dx * best, oy + dy * best, oz + dz * best, nx, ny, nz, best};
    }

    /**
     * A pixel of a photo that was turned upright (rot degrees clockwise from the camera's own picture, iw × ih) back in the
     * camera's own picture: {x, y}.
     */
    static float[] fromUpright(float ux, float uy, int rot, int iw, int ih) {
        switch (((rot % 360) + 360) % 360) {
            case 90: return new float[]{uy, ih - 1 - ux};
            case 180: return new float[]{iw - 1 - ux, ih - 1 - uy};
            case 270: return new float[]{iw - 1 - uy, ux};
            default: return new float[]{ux, uy};
        }
    }

    /**
     * A point of a kept photo (fractions fu, fv of the UPRIGHT photo) onto the surface: {x, y, z, nx, ny, nz, distance} or
     * null. pose / fx… / iw × ih / rot: the photo's camera, its pinhole and size in the camera's own picture, its turn to upright.
     */
    static float[] pin(Mesh m, float[] pose, float fx, float fy, float cx, float cy, int iw, int ih, int rot, float fu, float fv) {
        boolean turned = rot == 90 || rot == 270;
        float uw = turned ? ih : iw, uh = turned ? iw : ih;
        float[] s = fromUpright(fu * uw, fv * uh, rot, iw, ih);
        return hit(m, ray(pose, fx, fy, cx, cy, s[0], s[1]));
    }

    // ================================================================ files

    private static final int MAGIC = 0x4A4D3344; // "JM3D"

    static void write(Mesh m, File f) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            o.writeInt(MAGIC);
            o.writeInt(1);
            o.writeInt(m.verts);
            o.writeInt(m.tris);
            o.writeFloat(m.floorY);
            ByteBuffer b = ByteBuffer.allocate(m.verts * 12).order(ByteOrder.LITTLE_ENDIAN);
            b.asFloatBuffer().put(m.pos, 0, m.verts * 3);
            o.write(b.array());
            o.write(m.col, 0, m.verts * 3);
            o.write(m.nrm, 0, m.verts * 3);
            ByteBuffer t = ByteBuffer.allocate(m.tris * 12).order(ByteOrder.LITTLE_ENDIAN);
            t.asIntBuffer().put(m.tri, 0, m.tris * 3);
            o.write(t.array());
        }
    }

    static Mesh read(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not a Jarvis 3D file");
            in.readInt();
            Mesh m = new Mesh();
            m.verts = in.readInt();
            m.tris = in.readInt();
            if (m.verts < 0 || m.tris < 0 || m.verts > 5_000_000 || m.tris > 10_000_000) throw new IOException("bad sizes");
            m.floorY = in.readFloat();
            byte[] b = new byte[m.verts * 12];
            in.readFully(b);
            m.pos = new float[m.verts * 3];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(m.pos);
            m.col = new byte[m.verts * 3];
            in.readFully(m.col);
            m.nrm = new byte[m.verts * 3];
            in.readFully(m.nrm);
            byte[] t = new byte[m.tris * 12];
            in.readFully(t);
            m.tri = new int[m.tris * 3];
            ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(m.tri);
            for (int x : m.tri) if (x < 0 || x >= m.verts) throw new IOException("bad triangle");
            return m;
        }
    }

    /** A .ply file (colours kept) that other 3D apps open (MeshLab, Blender, 3D viewers): binary, little-endian. */
    static void writePly(Mesh m, OutputStream out) throws IOException {
        String head = "ply\nformat binary_little_endian 1.0\ncomment made by Jarvis (real 3D scan)\nelement vertex " + m.verts
                + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\n"
                + "element face " + m.tris + "\nproperty list uchar int vertex_indices\nend_header\n";
        OutputStream o = new BufferedOutputStream(out, 1 << 16);
        o.write(head.getBytes("US-ASCII"));
        ByteBuffer v = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < m.verts; i++) {
            v.clear();
            v.putFloat(m.pos[i * 3]).putFloat(m.pos[i * 3 + 1]).putFloat(m.pos[i * 3 + 2]);
            v.put(m.col[i * 3]).put(m.col[i * 3 + 1]).put(m.col[i * 3 + 2]);
            o.write(v.array(), 0, 15);
        }
        ByteBuffer f = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        for (int t = 0; t < m.tris; t++) {
            f.clear();
            f.put((byte) 3).putInt(m.tri[t * 3]).putInt(m.tri[t * 3 + 1]).putInt(m.tri[t * 3 + 2]);
            o.write(f.array(), 0, 13);
        }
        o.flush();
    }

    // ================================================================ small growing arrays

    static final class FloatList {
        float[] a; int n;
        FloatList(int cap) { a = new float[Math.max(16, cap)]; }
        void add(float x) { if (n == a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = x; }
        float get(int i) { return a[i]; }
        int size() { return n; }
        float[] toArray() { return Arrays.copyOf(a, n); }
    }

    static final class IntList {
        int[] a; int n;
        IntList(int cap) { a = new int[Math.max(16, cap)]; }
        void add(int x) { if (n == a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = x; }
        int[] toArray() { return Arrays.copyOf(a, n); }
    }

    static final class ByteList {
        byte[] a; int n;
        ByteList(int cap) { a = new byte[Math.max(16, cap)]; }
        void add(byte x) { if (n == a.length) a = Arrays.copyOf(a, a.length * 2); a[n++] = x; }
        byte get(int i) { return a[i]; }
        byte[] toArray() { return Arrays.copyOf(a, n); }
    }
}
