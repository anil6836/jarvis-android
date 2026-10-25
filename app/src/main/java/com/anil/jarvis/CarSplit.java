package com.anil.jarvis;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 🪟 G2: can the box put the Google Maps app in a window of its own beside Jarvis? Its split screen, or its freeform
 * windows (Developer options → "Enable freeform windows", after a restart). Read honestly: what it says, or "can't tell".
 */
final class CarSplit {
    private CarSplit() {}

    static final String MAPS = "com.google.android.apps.maps";

    /** 1: the box gives apps windows of their own; 0: it doesn't (the setting is off); -1: it won't say. */
    static int freeform(Context c) {
        try { if (c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return 1; } catch (Exception ignored) {}
        try { return android.provider.Settings.Global.getInt(c.getContentResolver(), "enable_freeform_support", 0) != 0 ? 1 : 0; }
        catch (Exception e) { return -1; }
    }

    static boolean hasMaps(Context c) {
        try { return c.getPackageManager().getLaunchIntentForPackage(MAPS) != null; } catch (Exception e) { return false; }
    }

    /** 🧪 What this box allows, in plain words, and how to try it. */
    static String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append(hasMaps(c) ? "✓ Google Maps యాప్ ఉంది\n" : "✗ Google Maps యాప్ లేదు: Play Store లో ఇన్‌స్టాల్ చేయండి\n");
        int f = freeform(c);
        b.append(f == 1 ? "✓ పక్క కిటికీలు (freeform windows): ఆన్\n"
                : f == 0 ? "✗ పక్క కిటికీలు (freeform windows): ఆఫ్\n"
                : "? పక్క కిటికీలు (freeform windows): ఈ box చెప్పడం లేదు (ప్రయత్నిస్తేనే తెలుస్తుంది)\n");
        if (f != 1) b.append("\nఆన్ చేయడానికి: box Settings → System → Developer options → \"Enable freeform windows\" ఆన్ → box ని ఒకసారి రీస్టార్ట్ చేయండి.\n");
        b.append("\nప్రయత్నం: కారు స్క్రీన్ మీద మ్యాప్ పైన \"🗺️ మ్యాప్ మార్చు\" నొక్కండి (లేదా \"Jarvis, Google Maps పెట్టు\"). "
                + "Google Maps ఎడమవైపు కిటికీలో, Jarvis కుడివైపు వస్తే G2 పనిచేస్తోంది. Google Maps పూర్తి స్క్రీన్‌లో వస్తే ఈ box పక్క కిటికీ ఇవ్వదు: "
                + "అప్పుడు 🫧 బుడగ నొక్కితే Jarvis, మన మ్యాప్ (G1) అలాగే పనిచేస్తుంది.");
        return b.toString();
    }
}
