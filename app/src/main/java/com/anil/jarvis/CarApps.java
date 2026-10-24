package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ▦ The car box's apps for the car screen's drawer (C: Jarvis as the box's home), and the box's own Home button
 * (an activity alias, off until he turns it on in Settings, and only in car mode: the phone never changes).
 */
final class CarApps {
    private CarApps() {}

    /** The favourites until he picks his own: the maps, the music apps, YouTube, the box's settings. */
    static final String[] DEFAULTS = {"com.google.android.apps.maps", "com.google.android.apps.youtube.music", "com.spotify.music",
            "com.jio.media.jiobeats", "com.gaana", "com.google.android.youtube", "com.android.settings"};

    /** His favourites (package names, in order). Pure on the stored text: "" → the defaults. */
    static List<String> favourites(String stored) {
        if (stored == null) return new ArrayList<>(Arrays.asList(DEFAULTS));
        List<String> out = new ArrayList<>();
        for (String p : stored.split(",")) if (!p.trim().isEmpty() && !out.contains(p.trim())) out.add(p.trim());
        return out;
    }

    /** Adds the app to his favourites, or takes it out; true when it is one now. */
    static boolean toggleFavourite(Prefs prefs, String pkg) {
        List<String> f = favourites(prefs.sp.contains("car_favs") ? prefs.sp.getString("car_favs", "") : null);
        boolean now;
        if (f.remove(pkg)) now = false;
        else { f.add(pkg); now = true; }
        prefs.sp.edit().putString("car_favs", String.join(",", f)).apply();
        return now;
    }

    /**
     * {package, label, icon, favourite?} for the drawer: the installed favourites first (in his order), then — unless
     * favouritesOnly — every other app with a launcher icon, by name. Jarvis itself is left out. Off the main thread.
     */
    static List<Object[]> list(Context c, Prefs prefs, boolean favouritesOnly) {
        PackageManager pm = c.getPackageManager();
        List<String> favs = favourites(prefs.sp.contains("car_favs") ? prefs.sp.getString("car_favs", "") : null);
        List<Object[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(c.getPackageName());
        for (String p : favs) {
            if (seen.contains(p) || pm.getLaunchIntentForPackage(p) == null) continue;
            try {
                out.add(new Object[]{p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(), pm.getApplicationIcon(p), true});
                seen.add(p);
            } catch (Exception ignored) {}
        }
        if (favouritesOnly) return out;
        List<Object[]> rest = new ArrayList<>();
        try {
            List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (ResolveInfo ri : all) {
                String p = ri.activityInfo == null ? null : ri.activityInfo.packageName;
                if (p == null || !seen.add(p)) continue;
                try { rest.add(new Object[]{p, ri.loadLabel(pm).toString(), ri.loadIcon(pm), false}); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        Collator col = Collator.getInstance(Locale.getDefault());
        rest.sort((a, b) -> col.compare((String) a[1], (String) b[1]));
        out.addAll(rest);
        return out;
    }

    // ================================================================ 🏠 Jarvis as the box's Home

    private static android.content.ComponentName alias(Context c) { return new android.content.ComponentName(c, "com.anil.jarvis.CarHome"); }

    static boolean homeOn(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(alias(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Turns Jarvis's Home entry on / off (Android then asks once which Home to use; off: the box's own launcher again). */
    static void setHome(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(alias(c),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }
}
