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

/**
 * Downloads (once) and unpacks the small Vosk models Jarvis uses on the phone: English to hear the word "Jarvis", the
 * voice print. (The offline Telugu model was removed on 14 Oct at Anil's request; its old files are deleted once.)
 */
final class VoskModel {
    interface Progress { void update(String text); }

    private static final String URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    /** Speaker (voice print) model for "only my voice", about 13 MB. */
    private static final String SPK_ZIP = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip";


    private VoskModel() {}

    static File dir(Context c) { return new File(c.getFilesDir(), "vosk-en"); }

    static boolean ready(Context c) { return new File(dir(c), ".ready").exists(); }

    /** Returns the model folder, downloading about 40 MB the first time. */
    static File ensure(Context c, Progress progress) throws Exception {
        return ensure(c, URL_ZIP, dir(c), "\"Jarvis\" పదం", progress);
    }

    static File spkDir(Context c) { return new File(c.getFilesDir(), "vosk-spk"); }

    /** The voice-print model, downloading it the first time. */
    static File ensureSpk(Context c, Progress progress) throws Exception {
        return ensure(c, SPK_ZIP, spkDir(c), "గొంతు గుర్తింపు", progress);
    }

    static File teDir(Context c) { return new File(c.getFilesDir(), "vosk-te"); }


    /** The Telugu model, downloading it the first time (his button in Settings). */

    /** The model in this folder couldn't be opened: it is downloaded again next time. */
    static void unready(File dir) { new File(dir, ".ready").delete(); }

    /** One download at a time per model (the Telugu one takes minutes; the "Jarvis" word's must not wait for it). */
    private static final java.util.Map<String, Object> LOCKS = new java.util.HashMap<>();

    private static Object lock(File dir) {
        synchronized (LOCKS) {
            Object o = LOCKS.get(dir.getName());
            if (o == null) LOCKS.put(dir.getName(), o = new Object());
            return o;
        }
    }

    private static File ensure(Context c, String url, File dir, String what, Progress progress) throws Exception {
        if (new File(dir, ".ready").exists()) return dir;
        synchronized (lock(dir)) { return fetch(c, url, dir, what, progress); }
    }

    private static File fetch(Context c, String url, File dir, String what, Progress progress) throws Exception {
        File ok = new File(dir, ".ready");
        if (ok.exists()) return dir;
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("cannot create model folder");

        File zip = new File(c.getCacheDir(), dir.getName() + ".zip");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
                    progress.update(what + " సిద్ధం చేస్తున్నాను: " + (got >> 20) + (total > 0 ? "/" + (total >> 20) : "") + " MB");
                }
            }
        } finally {
            conn.disconnect();
        }

        progress.update(what + " సిద్ధం చేస్తున్నాను: అన్‌జిప్…");
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

    /** The offline Telugu model he had downloaded (about 58 MB) is deleted: the feature was removed on 14 Oct. */
    static void dropTelugu(Context c) {
        File d = teDir(c);
        if (!d.exists()) return;
        new Thread(() -> deleteTree(d), "jarvis-drop-te").start();
    }

    private static volatile com.sun.jna.Function findWord;
    private static volatile boolean noFind;

    /** 1 when the Vosk model knows this word, 0 when not, -1 when it can't tell (the "Jarvis" / his name grammar). */
    static int knows(org.vosk.Model m, String w) {
        if (noFind) return -1;
        try {
            if (findWord == null) findWord = com.sun.jna.Function.getFunction("vosk", "vosk_model_find_word");
            byte[] b = (w + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return findWord.invokeInt(new Object[]{m.getPointer(), b}) >= 0 ? 1 : 0;
        } catch (Throwable e) {
            noFind = true;
            return -1;
        }
    }
}
