package com.anil.jarvis;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads (once) and unpacks the small English Vosk model used to hear the word "Jarvis". */
final class VoskModel {
    interface Progress { void update(String text); }

    private static final String URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    private VoskModel() {}

    static File dir(Context c) { return new File(c.getFilesDir(), "vosk-en"); }

    static boolean ready(Context c) { return new File(dir(c), ".ready").exists(); }

    /** Returns the model folder, downloading about 40 MB the first time. */
    static synchronized File ensure(Context c, Progress progress) throws Exception {
        File dir = dir(c);
        File ok = new File(dir, ".ready");
        if (ok.exists()) return dir;
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("cannot create model folder");

        File zip = new File(c.getCacheDir(), "vosk-en.zip");
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_ZIP).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        if (status >= 400) throw new IllegalStateException("model download HTTP " + status);
        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            byte[] b = new byte[65536];
            long got = 0, lastShown = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                if (got - lastShown > 2_000_000) {
                    lastShown = got;
                    progress.update("\"Jarvis\" పదం సిద్ధం చేస్తున్నాను: " + (got >> 20) + (total > 0 ? "/" + (total >> 20) : "") + " MB");
                }
            }
        } finally {
            conn.disconnect();
        }

        progress.update("\"Jarvis\" పదం సిద్ధం చేస్తున్నాను: అన్‌జిప్…");
        String root = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] b = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                name = name.substring(slash + 1); // drop the top-level "vosk-model-small-en-us-0.15/" folder
                if (name.isEmpty()) continue;
                File f = new File(dir, name);
                if (!f.getCanonicalPath().startsWith(root)) continue; // never write outside the folder
                if (e.isDirectory()) { f.mkdirs(); continue; }
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(b)) > 0) out.write(b, 0, n);
                }
            }
        } finally {
            zip.delete();
        }
        if (!ok.createNewFile() && !ok.exists()) throw new IllegalStateException("cannot finish model");
        return dir;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
