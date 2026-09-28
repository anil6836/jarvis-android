package com.anil.jarvis;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Document scanner → one clean PDF: Google's scanner (finds the page edges, straightens, cleans, several
 * pages, photos from the gallery too). Where it isn't available, photos picked from the gallery are put
 * into a PDF instead. The PDF is saved in Downloads/Jarvis/scans.
 */
final class Scanner {
    private Scanner() {}

    static final int REQ_SCAN = 41, REQ_PICK = 42;

    interface Done { void pdf(String name, byte[] bytes, int pages, Uri saved, String where); }

    static void start(Activity a) {
        try {
            GmsDocumentScannerOptions o = new GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(30)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build();
            GmsDocumentScanner scanner = GmsDocumentScanning.getClient(o);
            scanner.getStartScanIntent(a)
                    .addOnSuccessListener(sender -> {
                        try { a.startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0); }
                        catch (Exception e) { pickPhotos(a); }
                    })
                    .addOnFailureListener(e -> pickPhotos(a));
        } catch (Throwable t) {
            pickPhotos(a); // no Google Play services scanner on this phone
        }
    }

    /** Fallback: choose page photos from the gallery. */
    static void pickPhotos(Activity a) {
        a.runOnUiThread(() -> android.widget.Toast.makeText(a, "స్కానర్ తెరవలేకపోయాను: పేజీల ఫోటోలు ఎంచుకోండి, వాటిని PDF గా చేస్తాను", android.widget.Toast.LENGTH_LONG).show());
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { a.startActivityForResult(Intent.createChooser(i, "పేజీల ఫోటోలు ఎంచుకోండి"), REQ_PICK); } catch (Exception ignored) {}
    }

    /** Result of the scanner or the gallery pick (runs the slow part in the background). */
    static void onResult(Activity a, int req, Intent data, Done done) {
        new Thread(() -> {
            try {
                byte[] pdf;
                int pages;
                if (req == REQ_SCAN) {
                    GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
                    if (r == null || r.getPdf() == null) return;
                    pdf = readAll(a, r.getPdf().getUri());
                    pages = r.getPdf().getPageCount();
                } else {
                    List<Uri> uris = new ArrayList<>();
                    ClipData clip = data.getClipData();
                    if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
                    else if (data.getData() != null) uris.add(data.getData());
                    if (uris.isEmpty()) return;
                    pdf = photosToPdf(a, uris);
                    pages = uris.size();
                }
                String name = "Scan_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(new Date()) + ".pdf";
                Coder.Made m = Coder.save(a, "Jarvis/scans", name, "application/pdf", pdf);
                a.runOnUiThread(() -> done.pdf(name, pdf, pages, m.uri, m.where));
            } catch (Exception e) {
                a.runOnUiThread(() -> android.widget.Toast.makeText(a, "PDF తయారు కాలేదు: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "jarvis-scan").start();
    }

    /** Each photo on its own A4 page (fitted, centred, white margins). */
    private static byte[] photosToPdf(Activity a, List<Uri> uris) throws Exception {
        PdfDocument doc = new PdfDocument();
        try {
            int pw = 595, ph = 842, margin = 18; // A4 in points
            for (int i = 0; i < uris.size(); i++) {
                Bitmap bm = load(a, uris.get(i), 2000);
                if (bm == null) continue;
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                Canvas c = page.getCanvas();
                c.drawColor(0xFFFFFFFF);
                float sc = Math.min((pw - 2f * margin) / bm.getWidth(), (ph - 2f * margin) / bm.getHeight());
                int w = Math.round(bm.getWidth() * sc), h = Math.round(bm.getHeight() * sc);
                c.drawBitmap(bm, null, new Rect((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), null);
                doc.finishPage(page);
                bm.recycle();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.writeTo(out);
            return out.toByteArray();
        } finally {
            doc.close();
        }
    }

    private static Bitmap load(Activity a, Uri u, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = a.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        try (InputStream in = a.getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o2); }
    }

    private static byte[] readAll(Activity a, Uri u) throws Exception {
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IllegalStateException("స్కాన్ ఫైల్ తెరవలేకపోయాను");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
