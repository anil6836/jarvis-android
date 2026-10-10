package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;

import org.json.JSONObject;

import java.util.List;

/**
 * W64: coming home and leaving home, by a circle around his saved home (Android's own location alerts). Coming home:
 * "🏠 ఇంటికి వచ్చారు" with what came meanwhile (missed calls, messages kept for later) and one tap for the lights / to
 * pause the guard. Leaving: "🔒 తాళం వేశారా?" with one tap for the lights off / the guard on. Nothing happens without
 * his tap. On his watch too (the phone is in his pocket then).
 */
final class HomeArrival {
    private HomeArrival() {}

    static final String ACTION_GEO = "com.anil.jarvis.HOME_GEO", ACTION_DO = "com.anil.jarvis.HOME_DO";
    private static final int REQ = 281, NOTE = 282;
    private static final float RADIUS = 150f;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_home_arrival", Context.MODE_PRIVATE); }

    static boolean on(Context c) { return Travel.sp(c).getBoolean("home_welcome", true); }

    private static PendingIntent pi(Context c) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0); // (Android adds "entering")
        return PendingIntent.getBroadcast(c, REQ, new Intent(c, AlarmReceiver.class).setAction(ACTION_GEO), flags);
    }

    /** After a restart Android has forgotten the circle: set it again whatever was kept. */
    static void armAfterBoot(Context c) {
        sp(c).edit().remove("armed").apply();
        arm(c);
    }

    /** The circle around his home is set (again) when his home is known and he allowed location all the time. */
    static void arm(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return;
        JSONObject h = Travel.home(c);
        String key = h == null ? "" : h.optDouble("lat") + "," + h.optDouble("lon");
        if (!on(c) || h == null || !GeoReminders.canWatch(c)) {
            if (!sp(c).getString("armed", "").isEmpty()) { try { lm.removeProximityAlert(pi(c)); } catch (Exception ignored) {} sp(c).edit().remove("armed").apply(); }
            return;
        }
        if (key.equals(sp(c).getString("armed", "")) && System.currentTimeMillis() - sp(c).getLong("armed_at", 0) < 12 * 3600_000L) return;
        try {
            lm.removeProximityAlert(pi(c));
            lm.addProximityAlert(h.optDouble("lat"), h.optDouble("lon"), RADIUS, -1, pi(c));
            sp(c).edit().putString("armed", key).putLong("armed_at", System.currentTimeMillis()).putBoolean("just_armed", true).apply();
        } catch (SecurityException ignored) {}
    }

    /** Android says he crossed the circle. */
    static void crossed(Context c, Intent i) {
        if (!on(c)) return;
        boolean in = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        SharedPreferences s = sp(c);
        long now = System.currentTimeMillis();
        // (the same again: the circle set again while he is at home says "entering" once more; else a repeat within 6 h.
        // A missed "left" long ago doesn't swallow the next welcome)
        boolean first = s.getBoolean("just_armed", false);
        if (first) s.edit().putBoolean("just_armed", false).apply();
        if (in == s.getBoolean("in", false) && s.contains("at") && (first || now - s.getLong("at", 0) < 6 * 3600_000L)) return;
        if (now - s.getLong("at", 0) < 10 * 60_000L) { s.edit().putBoolean("in", in).putLong("at", now).apply(); return; } // GPS jitter at the gate
        long since = s.getLong("at", 0);
        s.edit().putBoolean("in", in).putLong("at", now).apply();
        if (in) home(c, since); else away(c);
    }

    private static void home(Context c, long since) {
        HomeLink.son(c, true); // the home tablet: "అబ్బాయి వచ్చేస్తున్నాడు!"
        StringBuilder b = new StringBuilder();
        int missed = missedCalls(c, since > 0 ? since : System.currentTimeMillis() - 12 * 3600_000L);
        if (missed > 0) b.append("📞 ").append(missed).append(" మిస్డ్ కాల్స్. ");
        try { int later = LaterMessages.list(c).size(); if (later > 0) b.append("💬 తర్వాత చదువుదామన్న ").append(later).append(" మెసేజ్‌లు. "); } catch (Exception ignored) {}
        String lights = smart(c, true);
        if (b.length() == 0) b.append("స్వాగతం! ");
        post(c, "🏠 ఇంటికి వచ్చారు", b.toString().trim(),
                lights == null ? null : new String[]{"💡 " + lights, "smart:" + lights},
                HomeLink.linked(c) ? new String[]{"🛡️ కాపలా ఆపు", "guard_off"} : null);
    }

    private static void away(Context c) {
        HomeLink.son(c, false);
        String off = smart(c, false);
        post(c, "🔒 తాళం వేశారా?", "ఇంటి నుంచి బయల్దేరారు. గ్యాస్, లైట్లు, తలుపు ఒకసారి చూసుకున్నారా?",
                off == null ? null : new String[]{"⚫ " + off, "smart:" + off},
                HomeLink.linked(c) ? new String[]{"🛡️ కాపలా పెట్టు", "guard_on"} : null);
    }

    /** His first saved light command that turns on (or off), else null. */
    private static String smart(Context c, boolean wantOn) {
        for (String[] s : WatchDo.smart(c)) {
            String n = s[0].toLowerCase(java.util.Locale.ROOT);
            boolean isOff = n.contains("ఆఫ్") || n.contains("off");
            if (wantOn != isOff && (n.contains("లైట్") || n.contains("light") || n.contains("ఫ్యాన్") || n.contains("fan"))) return s[0];
        }
        return null;
    }

    private static int missedCalls(Context c, long since) {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0;
        try (android.database.Cursor cur = c.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, new String[]{android.provider.CallLog.Calls._ID},
                android.provider.CallLog.Calls.TYPE + "=? AND " + android.provider.CallLog.Calls.DATE + ">?",
                new String[]{String.valueOf(android.provider.CallLog.Calls.MISSED_TYPE), String.valueOf(since)}, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) { return 0; }
    }

    private static void post(Context c, String title, String text, String[] a1, String[] a2) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_home", "ఇల్లు", NotificationManager.IMPORTANCE_HIGH));
            Notification.Builder n = new Notification.Builder(c, "jarvis_home").setSmallIcon(android.R.drawable.ic_menu_myplaces)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setTimeoutAfter(45 * 60_000L);
            int req = REQ + 10;
            for (String[] a : new String[][]{a1, a2, {"✓ సరే", "ok"}}) {
                if (a == null) continue;
                PendingIntent p = PendingIntent.getBroadcast(c, req++, new Intent(c, AlarmReceiver.class).setAction(ACTION_DO).putExtra("do", a[1]),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                n.addAction(new Notification.Action.Builder(null, a[0], p).build());
            }
            nm.notify(NOTE, n.build());
        } catch (Exception ignored) {}
    }

    /** One of its buttons (on the phone or the watch). Background work; the card goes away. */
    static void act(Context c, String what) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTE); } catch (Exception ignored) {}
        if (what == null || what.equals("ok")) return;
        new Thread(() -> {
            String r = null;
            if (what.startsWith("smart:")) {
                String name = what.substring(6);
                for (String[] s : WatchDo.smart(c)) {
                    if (!s[0].equals(name)) continue;
                    try { Http.getText(s[1]); } catch (Exception e) { r = name + ": Alexa లింక్ స్పందించలేదు"; }
                }
            } else if (what.equals("guard_off") || what.equals("guard_on")) {
                r = HomeLink.guard(c, what.equals("guard_on"));
                if (r.startsWith("ఇంటి కాపలా")) r = null; // (fine: nothing more to say)
            }
            if (r != null) Reminders.notify(c, "🏠 ఇల్లు", r, NOTE + 1);
        }, "home-act").start();
    }
}
