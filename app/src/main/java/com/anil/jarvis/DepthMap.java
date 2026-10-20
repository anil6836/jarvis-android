package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

/**
 * ✨ 11: how near or far each part of the camera picture is, worked out on the phone itself (nothing leaves it). The
 * model is MiDaS v2.1 small (Intel ISL, MIT licence; the official mobile TFLite file "model_opt.tflite", about 63 MB),
 * downloaded once when Anil turns ✨ 11 on and checked against its known SHA-256 before use. It gives relative depth
 * only (nearer / farther), not metres. Never on the home tablet (too slow there).
 */
final class DepthMap implements AutoCloseable {
    interface Progress { void update(String text); }

    static final String SHA256 = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd";
    static final long SIZE = 66338288L;
    private static final String[] URLS = {"https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"};
    /** The map Jarvis keeps: GRID×GRID cells over the picture. */
    static final int GRID = 64;

    static File file(Context c) { return new File(c.getFilesDir(), "midas.tflite"); }

    /** Downloaded and verified (the file is only put in place after its SHA-256 matched). */
    static boolean ready(Context c) { return file(c).length() == SIZE; }

    static long sizeMb() { return SIZE >> 20; }

    /** Downloads the model once from its original home, verified by SHA-256. */
    static synchronized void ensure(Context c, Progress progress) throws Exception {
        if (ready(c)) return;
        Exception last = null;
        for (String u : URLS) {
            File tmp = new File(c.getCacheDir(), "midas.part");
            try {
                download(u, tmp, progress);
                if (tmp.length() != SIZE || !SHA256.equals(sha256(tmp))) throw new IllegalStateException("file check failed");
                File dst = file(c);
                if (!tmp.renameTo(dst)) throw new IllegalStateException("cannot save model");
                return;
            } catch (Exception e) {
                last = e;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        throw last != null ? last : new IllegalStateException("download failed");
    }

    private static void download(String url, File to, Progress progress) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);
        try {
            if (con.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[65536];
                long got = 0, shown = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    got += n;
                    if (got > SIZE + 1_000_000) throw new IllegalStateException("too big");
                    if (progress != null && got - shown > 8_000_000) {
                        shown = got;
                        progress.update("✨ లోతు మోడల్ తెస్తున్నాను: " + (got >> 20) + "/" + sizeMb() + " MB");
                    }
                }
            }
        } finally {
            con.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }

    // ImageNet's colour average and spread (what MiDaS was trained with)
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f}, STD = {0.229f, 0.224f, 0.225f};

    private final org.tensorflow.lite.Interpreter tfl;
    private final int inW, inH;
    private final ByteBuffer input, output;
    private final int[] px;
    private final float[] raw;

    DepthMap(Context c) throws Exception {
        MappedByteBuffer model;
        try (FileInputStream in = new FileInputStream(file(c)); FileChannel ch = in.getChannel()) {
            model = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        org.tensorflow.lite.Interpreter.Options o = new org.tensorflow.lite.Interpreter.Options();
        o.setNumThreads(2); // (the finder keeps its own threads: the rings must not slow down)
        tfl = new org.tensorflow.lite.Interpreter(model, o);
        int[] s = tfl.getInputTensor(0).shape(); // [1, H, W, 3]
        if (s.length != 4 || s[3] != 3) { tfl.close(); throw new IllegalStateException("unexpected model input"); }
        inH = s[1];
        inW = s[2];
        int outN = 1;
        for (int v : tfl.getOutputTensor(0).shape()) outN *= v; // ([1, H, W, 1] or [1, H, W])
        if (outN != inW * inH || inW % GRID != 0 || inH % GRID != 0) { tfl.close(); throw new IllegalStateException("unexpected model output"); }
        input = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(ByteOrder.nativeOrder());
        output = ByteBuffer.allocateDirect(inW * inH * 4).order(ByteOrder.nativeOrder());
        px = new int[inW * inH];
        raw = new float[inW * inH];
    }

    /** The depth map of this picture: GRID×GRID cells, 0..1, 1 = nearest (one thread at a time). */
    float[] run(Bitmap pic) {
        Bitmap b = pic.getWidth() == inW && pic.getHeight() == inH ? pic : Bitmap.createScaledBitmap(pic, inW, inH, true);
        b.getPixels(px, 0, inW, 0, 0, inW, inH);
        if (b != pic) b.recycle();
        input.rewind();
        for (int p : px) {
            input.putFloat((((p >> 16) & 0xFF) / 255f - MEAN[0]) / STD[0]);
            input.putFloat((((p >> 8) & 0xFF) / 255f - MEAN[1]) / STD[1]);
            input.putFloat(((p & 0xFF) / 255f - MEAN[2]) / STD[2]);
        }
        input.rewind();
        output.rewind();
        tfl.run(input, output);
        output.rewind();
        output.asFloatBuffer().get(raw);
        float[] n = HudMath.normalizeDepth(raw);
        if (inW == inH) return HudMath.shrink(n, inW, inH, inW / GRID);
        // (a model that is not square: shrink each way to GRID)
        float[] g = new float[GRID * GRID];
        int fx = inW / GRID, fy = inH / GRID;
        for (int gy = 0; gy < GRID; gy++)
            for (int gx = 0; gx < GRID; gx++) {
                float sum = 0;
                for (int y = gy * fy; y < gy * fy + fy; y++) for (int x = gx * fx; x < gx * fx + fx; x++) sum += n[y * inW + x];
                g[gy * GRID + gx] = sum / (fx * fy);
            }
        return g;
    }

    @Override public void close() {
        try { tfl.close(); } catch (Exception ignored) {}
    }
}
