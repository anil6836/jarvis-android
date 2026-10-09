package com.anil.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** W54: any picture shared to "⌚ వాచ్‌కి పంపు" (a list, a ticket, a photo) goes to his watch. No screen of its own. */
public class ShareToWatchActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        Uri u = i == null ? null : i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) { finish(); return; }
        final Uri uri = u;
        new Thread(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while (in != null && (n = in.read(buf)) > 0 && out.size() < 30_000_000) out.write(buf, 0, n);
                msg = WatchPhoto.send(this, out.toByteArray(), "📤 షేర్ చేసినది") ? "⌚ వాచ్‌కి పంపాను" : "వాచ్ ఫోన్ దగ్గర లేదు (లేదా ఆ ఫోటో చదవలేకపోయాను)";
            } catch (Exception e) {
                msg = "పంపలేకపోయాను: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); finish(); });
        }, "share-watch").start();
    }
}
