package com.anil.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 🏠 The car box's Home button when Jarvis is its Home (the .CarHome alias points here, never at MainActivity itself):
 * Android 10+ keeps a Home activity in a Home task of its own, so pointing Home straight at MainActivity would make a
 * second Jarvis beside the one the car start opens. This screenless step only brings the one Jarvis to the front, on
 * its car screen, and is gone.
 */
public class CarHomeActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_CAR_HOME, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {}
        finish();
    }
}
