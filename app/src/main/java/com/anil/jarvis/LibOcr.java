package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 🆓 Telugu pages read on the phone, ₹0 (Anil, 22 Nov: "ఫ్రీగా pdf చదివే telugu pdf reader … C రెండు చేయి").
 * First the PDF's own letters (Android 15+ gives them; good only when the PDF was typed in Unicode), else Tesseract's
 * free Telugu reader (tessdata_best tel, about 9 MB, fetched once on his tap from Tesseract's GitHub; no AI, no internet after).
 * More mistakes than the AI, more on old scans: the form lets him try one page first.
 */
final class LibOcr {
    private LibOcr() {}

    static final String URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/tel.traineddata";
    static final long SIZE = 9_098_795L; // (its size on GitHub, 22 Nov 2026)

    /** filesDir/ocr (holds tessdata/tel.traineddata; left out of Google's backup). */
    static File dir(Context c) { File d = new File(c.getFilesDir(), "ocr"); new File(d, "tessdata").mkdirs(); return d; }

    static File data(Context c) { return new File(new File(dir(c), "tessdata"), "tel.traineddata"); }

    static boolean ready(Context c) { File f = data(c); return f.isFile() && f.length() > 5_000_000L; }

    /** The Telugu file, once (about 9 MB). */
    static synchronized void fetch(Context c, Library.Progress p) throws IOException {
        if (ready(c)) return;
        File part = new File(data(c).getPath() + ".part");
        p.at("⬇️ ఫోన్ తెలుగు గుర్తింపు ఫైల్ (సుమారు 9 MB, ఒక్కసారే)…");
        Library.getTo(URL, part, 30L << 20, p);
        if (part.length() < 5_000_000L) { //noinspection ResultOfMethodCallIgnored
            part.delete(); throw new IOException("తెలుగు గుర్తింపు ఫైల్ పూర్తిగా రాలేదు; మళ్ళీ ప్రయత్నించండి"); }
        if (!part.renameTo(data(c))) throw new IOException("తెలుగు గుర్తింపు ఫైల్ దాచలేకపోయాను");
    }

    /** Tesseract with the Telugu file (one per job; not shared across threads). */
    static final class Reader implements AutoCloseable {
        private final TessBaseAPI api = new TessBaseAPI();
        Reader(Context c) throws IOException {
            if (!ready(c)) throw new IOException("ఫోన్ తెలుగు గుర్తింపు ఫైల్ ఇంకా లేదు");
            if (!api.init(dir(c).getAbsolutePath(), "tel")) { api.recycle(); throw new IOException("ఫోన్ తెలుగు గుర్తింపు మొదలవ్వలేదు"); }
        }
        String read(Bitmap bm) {
            api.setImage(bm);
            String t = api.getUTF8Text();
            return t == null ? "" : t.trim();
        }
        @Override public void close() { try { api.recycle(); } catch (Throwable ignored) {} }
    }

    /** The PDF page's own letters (Android 15+: PdfRenderer.Page.getTextContents()); null when the phone cannot give them. */
    static String pageText(PdfRenderer.Page pg) {
        if (android.os.Build.VERSION.SDK_INT < 35) return null;
        try {
            Method m = pg.getClass().getMethod("getTextContents");
            Object list = m.invoke(pg);
            if (!(list instanceof List)) return null;
            StringBuilder b = new StringBuilder();
            for (Object o : (List<?>) list) {
                if (o == null) continue;
                Object t = o.getClass().getMethod("getText").invoke(o);
                if (t != null) b.append(t).append('\n');
            }
            return b.toString().trim();
        } catch (Throwable e) { return null; }
    }
}
