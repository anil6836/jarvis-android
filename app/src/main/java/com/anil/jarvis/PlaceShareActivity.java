package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * "Share → Jarvis" from Google Maps: asks what to call the place ("సిస్టర్ ఇల్లు") and saves it, so later
 * "సిస్టర్ వాళ్ల లొకేషన్ చూపించు" opens it on the map. Other shared text goes to Jarvis's message box.
 */
public class PlaceShareActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String text = in == null ? null : in.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = "";
        String url = Places.firstUrl(text);
        if (url == null || !Places.isMapsLink(url)) {
            // not a map: let him ask Jarvis about it
            String t = text.trim();
            if (!t.isEmpty()) {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(MainActivity.EXTRA_FILL, (t.length() > 2000 ? t.substring(0, 2000) : t) + "\n\n"));
            }
            finish();
            return;
        }
        final String link = url, suggested = Places.nameFromShare(text, url);
        final EditText name = new EditText(this);
        name.setHint("ఉదా: సిస్టర్ ఇల్లు, ఆఫీస్");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        int p = Ui.dp(this, 20);
        name.setPadding(p, p / 2, p, p / 2);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📍 ఈ చోటుని ఏ పేరుతో గుర్తుపెట్టుకోవాలి?")
                .setMessage(suggested.isEmpty() ? "తర్వాత \"Jarvis, … లొకేషన్ చూపించు\" అంటే మ్యాప్‌లో చూపిస్తాను." : suggested)
                .setView(name)
                .setPositiveButton("సేవ్", (d, w) -> save(name.getText().toString().trim(), suggested, link))
                .setNegativeButton("వద్దు", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void save(String typed, String suggested, String link) {
        final String name = !typed.isEmpty() ? typed : !suggested.isEmpty() ? suggested : "సేవ్ చేసిన చోటు";
        final Context app = getApplicationContext();
        Toast.makeText(app, "📍 " + name + " సేవ్ చేస్తున్నాను…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                Places.Resolved r = Places.fromLink(link);
                String address = !suggested.isEmpty() ? suggested : r.title;
                JSONObject saved = Places.save(app, name, r.lat, r.lon, address, link);
                msg = "📍 " + name + " సేవ్ అయింది ✓" + (saved.has("lat") ? "" : " (మ్యాప్ లింక్‌తో)")
                        + "\n\"Jarvis, " + name + " లొకేషన్ చూపించు\" అనండి";
            } catch (Exception e) {
                msg = "సేవ్ కాలేదు: " + e.getMessage();
            }
            final String m = msg;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
        }, "jarvis-place").start();
        finish();
    }
}
