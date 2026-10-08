package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * W47: an ECG (or another health report) shared to Jarvis as a PDF, e.g. from Samsung Health Monitor's "Share":
 * after his tap, kept in Downloads/Jarvis/health with the date in its name, and listed in the doctor PDF. Nothing is
 * read out of it and it goes nowhere else (no AI).
 */
public class HealthShareActivity extends Activity {
    private static final int MAX = 25 * 1024 * 1024;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        Uri uri = i == null ? null : i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) { Toast.makeText(this, "PDF రాలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        String[] kinds = {"❤️ ECG", "📄 ఇతర ఆరోగ్య రిపోర్ట్"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("ఈ PDF ని ఆరోగ్య రిపోర్ట్‌గా దాచమంటారా?")
                .setItems(kinds, (d, w) -> save(uri, w == 0 ? "ecg" : "report"))
                .setNegativeButton("వద్దు", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void save(Uri uri, String kind) {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                byte[] bytes;
                try (InputStream in = app.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) throw new IllegalStateException("ఫైల్ తెరవలేకపోయాను");
                    byte[] buf = new byte[64 * 1024];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX) throw new IllegalStateException("ఫైల్ చాలా పెద్దది (25 MB దాటింది)");
                        out.write(buf, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') throw new IllegalStateException("ఇది PDF కాదు");
                long now = System.currentTimeMillis();
                String name = ("ecg".equals(kind) ? "ECG_" : "Health_report_") + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ENGLISH).format(new Date(now)) + ".pdf";
                Coder.Made m = Coder.save(app, "Jarvis/health", name, "application/pdf", bytes);
                Notes.add(app, Wellness.FILES, new JSONObject().put("t", now).put("kind", kind).put("where", m.where), 200);
                msg = ("ecg".equals(kind) ? "ECG" : "రిపోర్ట్") + " దాచాను: " + m.where + ". డాక్టర్ PDF లో కూడా చూపిస్తాను.";
            } catch (Exception e) {
                msg = "దాచలేకపోయాను: " + e.getMessage();
            }
            final String say = msg;
            runOnUiThread(() -> { Toast.makeText(app, say, Toast.LENGTH_LONG).show(); finish(); });
        }, "jarvis-health-share").start();
    }
}
