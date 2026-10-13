package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The camera's other on-device eyes (no internet, nothing leaves the phone): the words on signboards and shop boards,
 * and faces — a face gets a name only when it is someone Anil introduced; everyone else is just "a face". Number
 * plates, long numbers (phones, IDs) and card-like numbers are never read out. Background thread only.
 */
final class SceneSpots {
    private SceneSpots() {}

    private static TextRecognizer text;
    private static FaceDetector faces;
    private static FaceNet net;
    private static boolean netTried;

    // ---- words on boards

    /** An Indian number plate (TS 09 AB 1234, with or without spaces), an Aadhaar-like 12 digits, a card-like 16, a phone. */
    static boolean privateText(String s) {
        String t = s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (t.matches(".*[A-Z]{2}\\d{1,2}[A-Z]{1,3}\\d{3,4}.*")) return true; // plate
        if (t.matches(".*\\d{10,}.*")) return true;                          // phone / ID / card
        return t.matches(".*\\d{4}\\d{4}\\d{4}.*");
    }

    /** Words worth a tag: 3+ letters, not a plate / number, cut to 22 characters. */
    static String cleanText(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() < 3 || privateText(t)) return "";
        int letters = 0;
        for (char ch : t.toCharArray()) if (Character.isLetter(ch)) letters++;
        if (letters < 3) return "";
        return t.length() > 22 ? t.substring(0, 21) + "…" : t;
    }

    /** The boards / signs in the picture as findings (en "text:<words>", te the words), boxes 0..1. */
    static List<Detector.Det> signs(Bitmap pic) {
        List<Detector.Det> out = new ArrayList<>();
        if (pic == null) return out;
        try {
            if (text == null) text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Text r = Tasks.await(text.process(InputImage.fromBitmap(pic, 0)), 4, TimeUnit.SECONDS);
            int W = pic.getWidth(), H = pic.getHeight();
            for (Text.TextBlock b : r.getTextBlocks()) {
                if (out.size() >= 8) break;
                Rect box = b.getBoundingBox();
                String words = cleanText(b.getText());
                if (box == null || words.isEmpty()) continue;
                if (box.width() < W * 0.06f || box.height() < H * 0.015f) continue; // too small to be a board
                float[] bb = {Math.max(0f, box.left / (float) W), Math.max(0f, box.top / (float) H), Math.min(1f, box.right / (float) W), Math.min(1f, box.bottom / (float) H)};
                String key = words.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
                if (key.length() > 12) key = key.substring(0, 12); // (the same board keeps the same kind even when a word is misread)
                out.add(new Detector.Det("text:" + key, words, words, 0.9f, bb, ""));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ---- faces

    /** Faces in the picture: te = the introduced person's name, "" for anyone else. names: also try to recognise. */
    static List<Detector.Det> faces(Context c, Bitmap pic, boolean names) {
        List<Detector.Det> out = new ArrayList<>();
        if (pic == null) return out;
        try {
            if (faces == null) faces = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.08f).build());
            List<Face> found = Tasks.await(faces.process(InputImage.fromBitmap(pic, 0)), 3, TimeUnit.SECONDS);
            int W = pic.getWidth(), H = pic.getHeight();
            List<People.Person> people = null;
            if (names && People.count(c) > 0) {
                if (net == null && !netTried) { netTried = true; try { if (FaceNet.ready(c)) net = new FaceNet(c); } catch (Throwable ignored) {} }
                if (net != null) people = People.all(c);
            }
            int k = 0;
            for (Face f : found) {
                if (k++ >= 6) break;
                Rect box = f.getBoundingBox();
                if (box == null || box.width() < 10) continue;
                float[] bb = {Math.max(0f, box.left / (float) W), Math.max(0f, box.top / (float) H), Math.min(1f, box.right / (float) W), Math.min(1f, box.bottom / (float) H)};
                String name = "";
                if (people != null && !people.isEmpty() && box.width() >= W * 0.1f && Math.abs(f.getHeadEulerAngleY()) <= 28 && Math.abs(f.getHeadEulerAngleZ()) <= 25) {
                    Bitmap sq = square(pic, box);
                    if (sq != null) {
                        try {
                            People.Match m = People.match(people, net.embed(sq));
                            if (m.name != null) name = m.name;
                        } catch (Throwable ignored) {}
                        sq.recycle();
                    }
                }
                out.add(new Detector.Det("face", name, name, 0.9f, bb, ""));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Bitmap square(Bitmap frame, Rect box) {
        try {
            int side = Math.min(Math.max(box.width(), box.height()), Math.min(frame.getWidth(), frame.getHeight()));
            if (side < 24) return null;
            int x = Math.max(0, Math.min(frame.getWidth() - side, box.centerX() - side / 2));
            int y = Math.max(0, Math.min(frame.getHeight() - side, box.centerY() - side / 2));
            Bitmap sq = Bitmap.createBitmap(frame, x, y, side, side);
            Bitmap out = Bitmap.createScaledBitmap(sq, FaceNet.IN, FaceNet.IN, true);
            if (sq != out && sq != frame) sq.recycle();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the mesh: the picture's own corners and edges (drawn as drifting points)

    /**
     * Up to `max` strong corners in the picture (0..1 coordinates), spread out: the HUD draws them as a living mesh that
     * follows walls, edges and things, so the scene itself looks "read". Cheap: the picture is shrunk to ~120 px wide.
     */
    static float[] corners(Bitmap pic, int max) {
        if (pic == null) return new float[0];
        int w = 120, h = Math.max(20, Math.round(pic.getHeight() * 120f / pic.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(pic, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        if (s != pic) s.recycle();
        float[] g = new float[w * h];
        for (int i = 0; i < px.length; i++) { int c = px[i]; g[i] = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10f; }
        float[] score = new float[w * h];
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                float sxx = 0, syy = 0, sxy = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int i = (y + dy) * w + x + dx;
                    float ix = g[i + 1] - g[i - 1], iy = g[i + w] - g[i - w];
                    sxx += ix * ix; syy += iy * iy; sxy += ix * iy;
                }
                float det = sxx * syy - sxy * sxy, tr = sxx + syy;
                score[y * w + x] = det - 0.05f * tr * tr; // (Harris)
            }
        }
        // local maxima, best first, kept apart
        List<int[]> cand = new ArrayList<>();
        for (int y = 3; y < h - 3; y += 1) for (int x = 3; x < w - 3; x += 1) {
            float v = score[y * w + x];
            if (v < 2500f) continue;
            boolean top = true;
            for (int dy = -2; dy <= 2 && top; dy++) for (int dx = -2; dx <= 2; dx++) if (score[(y + dy) * w + x + dx] > v) { top = false; break; }
            if (top) cand.add(new int[]{(int) Math.min(Integer.MAX_VALUE, v), x, y});
        }
        cand.sort((a, b) -> b[0] - a[0]);
        float[] out = new float[Math.min(max, cand.size()) * 2];
        int n = 0;
        boolean[] taken = new boolean[w * h];
        for (int[] cnd : cand) {
            if (n >= max) break;
            int x = cnd[1], y = cnd[2];
            if (taken[y * w + x]) continue;
            for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && yy >= 0 && xx < w && yy < h) taken[yy * w + xx] = true;
            }
            out[n * 2] = (x + 0.5f) / w;
            out[n * 2 + 1] = (y + 0.5f) / h;
            n++;
        }
        if (n * 2 < out.length) { float[] t = new float[n * 2]; System.arraycopy(out, 0, t, 0, n * 2); out = t; }
        return out;
    }
}
