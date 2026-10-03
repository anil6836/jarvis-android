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
 * Face fingerprints on the phone: the FaceNet model (Apache-2.0 TFLite conversion by shubham0204 of the MIT-licensed
 * keras-facenet weights) turns a 160x160 face into 128 numbers; two pictures of the same person give close numbers.
 * The model (about 23 MB) is downloaded once, only when Anil first asks Jarvis to remember someone, and is checked
 * against its known SHA-256 before use. Nothing about a face ever leaves the phone.
 */
final class FaceNet implements AutoCloseable {
    interface Progress { void update(String text); }

    static final String SHA256 = "d7c1f7f130376982c7004920ddc41925ac2e5aecf6522f476c8bbb3669db7013";
    static final long SIZE = 23705216L;
    private static final String[] URLS = {
            "https://github.com/anil6836/jarvis-android/releases/latest/download/facenet.tflite",
            "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/master/app/src/main/assets/facenet.tflite"};
    static final int IN = 160;

    static File file(Context c) { return new File(c.getFilesDir(), "facenet.tflite"); }

    /** Downloaded and verified (the file is only put in place after its SHA-256 matched). */
    static boolean ready(Context c) { return file(c).length() == SIZE; }

    /** Downloads the model once (from Jarvis's own release, else its original home), verified by SHA-256. */
    static synchronized void ensure(Context c, Progress progress) throws Exception {
        if (ready(c)) return;
        Exception last = null;
        for (String u : URLS) {
            File tmp = new File(c.getCacheDir(), "facenet.part");
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
                    if (progress != null && got - shown > 6_000_000) {
                        shown = got;
                        progress.update("ముఖాలు గుర్తుపట్టే మోడల్ తెస్తున్నాను: " + (got >> 20) + "/" + (SIZE >> 20) + " MB");
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

    private final org.tensorflow.lite.Interpreter tfl;
    private final ByteBuffer input = ByteBuffer.allocateDirect(IN * IN * 3 * 4).order(ByteOrder.nativeOrder());
    private final int[] px = new int[IN * IN];
    private final float[] vals = new float[IN * IN * 3];

    FaceNet(Context c) throws Exception {
        MappedByteBuffer model;
        try (FileInputStream in = new FileInputStream(file(c)); FileChannel ch = in.getChannel()) {
            model = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        org.tensorflow.lite.Interpreter.Options o = new org.tensorflow.lite.Interpreter.Options();
        o.setNumThreads(4);
        tfl = new org.tensorflow.lite.Interpreter(model, o);
    }

    /** The fingerprint (unit length) of a face picture; it is scaled to 160x160 here. */
    float[] embed(Bitmap face) {
        Bitmap b = face.getWidth() == IN && face.getHeight() == IN ? face : Bitmap.createScaledBitmap(face, IN, IN, true);
        b.getPixels(px, 0, IN, 0, 0, IN, IN);
        if (b != face) b.recycle();
        double sum = 0, sq = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            vals[i * 3] = r; vals[i * 3 + 1] = g; vals[i * 3 + 2] = bl;
            sum += r + g + bl;
            sq += r * r + g * g + bl * bl;
        }
        int n = vals.length;
        double mean = sum / n, std = Math.max(Math.sqrt(Math.max(0, sq / n - mean * mean)), 1.0 / Math.sqrt(n)); // the model's "prewhitening"
        input.rewind();
        for (float v : vals) input.putFloat((float) ((v - mean) / std));
        input.rewind();
        float[][] out = new float[1][128];
        tfl.run(input, out);
        return People.unit(out[0]);
    }

    @Override public void close() {
        try { tfl.close(); } catch (Exception ignored) {}
    }
}
