package com.anil.jarvis.watch;

import android.content.Context;

import org.json.JSONObject;

/**
 * Phase 5: the phone opens a screen here ({screen, target}), e.g. "బండి ఎక్కడ?" said to the phone opens the compass on
 * the wrist. Android may hold back a screen opened from the background: then nothing breaks, he opens it himself.
 */
final class Screens {
    private Screens() {}

    static void open(Context c, JSONObject o) {
        String s = o.optString("screen"), t = o.optString("target");
        try {
            switch (s) {
                case "compass": Compass.open(c, t); break;
                case "photos": Photos.open(c); break;
                default: if (!s.isEmpty()) Panel.open(c, s);
            }
        } catch (Exception ignored) {}
    }
}
