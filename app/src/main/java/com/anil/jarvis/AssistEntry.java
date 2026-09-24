package com.anil.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Opened by a Bluetooth earphone's long-press (VOICE_COMMAND): shows the small Jarvis panel
 * and closes itself. Kept separate so the panel itself stays private to the app.
 */
public class AssistEntry extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Prefs(this).compactPanel()
                ? new Intent(this, SheetActivity.class)
                : new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_WAKE, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }
}
