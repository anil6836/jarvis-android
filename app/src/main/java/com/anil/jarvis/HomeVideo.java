package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;

/**
 * 📹 అమ్మగారు's big "అబ్బాయికి వీడియో కాల్" button on the home tablet (Anil, 24 Oct): asked once ("వీడియో కాల్ చేయనా?"),
 * then a WhatsApp video call to his number (set from his phone: 🔊 tablet card). Before WhatsApp opens, Jarvis lets go of
 * the mic and the camera and stays silent for the whole call (her reminders wait and come after); when the call ends,
 * Jarvis's screen comes back. When WhatsApp can't call straight away, his chat opens (she presses 📹 there); with no
 * WhatsApp or no number set, he is told on Telegram to call her, and she hears that honestly.
 */
final class HomeVideo {
    private HomeVideo() {}

    private static final String[] WA = {"com.whatsapp", "com.whatsapp.w4b"};
    private static final String VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call";
    private static Handler mainH;

    private static synchronized Handler main() {
        if (mainH == null) mainH = new Handler(Looper.getMainLooper());
        return mainH;
    }

    /** Her son's number for the call (his phone sends it), digits only, with the country code. "" when not set. Pure. */
    static String digits(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) d = "91" + d; // (an Indian mobile written without +91)
        return d.length() >= 11 && d.length() <= 15 ? d : "";
    }

    static String number(Context c) { return digits(HomeCare.sp(c).getString("video_number", "")); }

    private static String waPackage(Context c) {
        for (String p : WA) {
            try { c.getPackageManager().getPackageInfo(p, 0); return p; } catch (Exception ignored) {}
        }
        return null;
    }

    // ================================================================ the call hold (mic, voice, camera)
    private static volatile long holdFrom, holdUntil;
    private static volatile boolean sawCall;

    /**
     * The home tablet is in a call now (ours, starting or going on; or any app call its notifications show): the "Jarvis"
     * word and the sound watch let go of the mic and nothing is said. Any thread.
     */
    static boolean holding(Context c) {
        if (c == null || !HomeCare.on(c)) return false;
        long now = System.currentTimeMillis();
        // an app call its notification shows (ringing / going on): only while the audio says so too, and at most 2 hours
        // (EMUI may drop the "call ended" notice: Jarvis must never stay deaf and silent because of it)
        if (CallControl.busyWithCall() && now - callSeenAt < 2 * 3600_000L && (inCallAudio(c) || ringAudio(c))) return true;
        if (now > holdUntil) return false;
        if (now - holdFrom < 3 * 60_000L) return true; // (ringing him: WhatsApp may not have taken the audio yet)
        return inCallAudio(c);
    }

    private static volatile long callSeenAt;

    private static boolean ringAudio(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            return am != null && am.getMode() == AudioManager.MODE_RINGTONE;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean inCallAudio(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            int m = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            return m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_IN_CALL;
        } catch (Exception e) {
            return false;
        }
    }

    /** Jarvis lets go before the call: the mic (wake word, sound watch), songs; the camera stops with the screen. */
    private static void begin(Context c) {
        holdFrom = System.currentTimeMillis();
        holdUntil = holdFrom + 2 * 3600_000L;
        sawCall = false;
        try { HomeSongs.stop(); } catch (Exception ignored) {}
        try { if (SoundService.radioOn) SoundService.stop(c); } catch (Exception ignored) {}
        WakeService.pauseNow();
        WakeService.recheck(c); // (its hold watch looks every 30 s: the mic stays free while the call goes on)
        watch(c.getApplicationContext());
    }

    /** Every 5 s while held: the call's audio seen, then gone for a few seconds → the call is over: Jarvis comes back. */
    private static void watch(Context app) {
        main().postDelayed(new Runnable() {
            int quiet;
            @Override public void run() {
                if (System.currentTimeMillis() > holdUntil) return;
                boolean call = inCallAudio(app) || CallControl.busyWithCall() && ringAudio(app); // (a lost "call ended" notice never keeps it on)
                if (call) { sawCall = true; quiet = 0; }
                else if (sawCall && ++quiet >= 2) { end(app, true); return; }
                else if (!sawCall && System.currentTimeMillis() - holdFrom > 3 * 60_000L) { end(app, false); return; } // (never placed)
                main().postDelayed(this, 5000);
            }
        }, 5000);
    }

    /** The call is over (or never started): the mic and Jarvis's voice are back; after a call, his screen too. */
    static void end(Context app, boolean afterCall) {
        holdUntil = 0;
        WakeService.recheck(app);
        if (afterCall) {
            try { // (Android 8 lets the tablet's Jarvis come back to the front by itself)
                app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) {}
        }
        MainActivity.homeAfterCall();
    }

    /** Jarvis's screen is in front again with no call going on: the hold ends. */
    static void screenBack(Context c) {
        if (holdUntil == 0) return;
        if (!inCallAudio(c) && !(CallControl.busyWithCall() && ringAudio(c)) && System.currentTimeMillis() - holdFrom > 8000) end(c.getApplicationContext(), false);
    }

    /** A call notification (an app call ringing / going on) on the home tablet: the mic is let go at once. */
    static void callNoticed(Context c) {
        if (!HomeCare.on(c)) return;
        callSeenAt = System.currentTimeMillis();
        main().post(() -> { WakeService.pauseNow(); WakeService.recheck(c); });
    }

    // ================================================================ the call
    /** After her yes: calls him. Returns what Jarvis says (shown; said only when the call could not start). */
    static String call(Activity a) {
        String w = HomeCare.who(a), num = number(a), pkg = waPackage(a);
        if (num.isEmpty() || pkg == null) {
            boolean told = HomeCare.alert(a, "📹 " + w + " మీతో వీడియో కాల్ మాట్లాడాలనుకుంటున్నారు. వీలైనప్పుడు WhatsApp వీడియో కాల్ చేయండి."
                    + (pkg == null ? " (టాబ్లెట్‌లో WhatsApp లేదు)" : " (టాబ్లెట్‌కి మీ నంబర్ పెట్టలేదు: ఫోన్ సెట్టింగ్స్ → 🔊 టాబ్లెట్ కార్డ్ → 📹 నంబర్)"));
            String why = pkg == null ? "ఈ టాబ్లెట్‌లో WhatsApp లేదు " + w + ". " : "వీడియో కాల్ నంబర్ ఇంకా పెట్టలేదు " + w + ". ";
            return why + (told ? "అబ్బాయికి చెప్పాను, వీలవ్వగానే ఆయనే కాల్ చేస్తాడు." : "నెట్ లేదు, నెట్ రాగానే అబ్బాయికి చెబుతాను.");
        }
        if (!Net.online(a)) return "ఇప్పుడు నెట్ లేదు " + w + ". నెట్ వచ్చాక మళ్ళీ నొక్కండి.";
        String mime = mimeFor(pkg);
        long row = videoRow(a, num, mime);
        try {
            begin(a);
            if (row > 0) { // straight into the video call
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row), mime)
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return "📹 అబ్బాయికి వీడియో కాల్ చేస్తున్నాను…";
            }
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "📹 అబ్బాయి WhatsApp చాట్ తెరిచాను. పైన కుడివైపు 📹 బొమ్మ నొక్కండి.";
        } catch (Exception e) {
            end(a.getApplicationContext(), false);
            boolean told = HomeCare.alert(a, "📹 " + w + " మీతో వీడియో కాల్ మాట్లాడాలనుకుంటున్నారు (టాబ్లెట్ నుంచి WhatsApp తెరవలేకపోయాను). వీలైనప్పుడు కాల్ చేయండి.");
            return "WhatsApp తెరవలేకపోయాను " + w + ". " + (told ? "అబ్బాయికి చెప్పాను, ఆయనే కాల్ చేస్తాడు." : "");
        }
    }

    /** WhatsApp's own "video call" row for his number in the tablet's contacts (-1: none / no contacts permission). */
    private static String mimeFor(String pkg) { return "com.whatsapp".equals(pkg) ? VIDEO_MIME : VIDEO_MIME.replace("com.whatsapp", pkg); }

    private static long videoRow(Context c, String num, String mime) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return -1;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data._ID, ContactsContract.Data.DATA1},
                ContactsContract.Data.MIMETYPE + "=?", new String[]{mime}, null)) {
            if (cur == null) return -1;
            String tail = num.length() > 10 ? num.substring(num.length() - 10) : num;
            while (cur.moveToNext()) {
                String d = cur.getString(1);
                if (d != null && d.replaceAll("[^0-9]", "").endsWith(tail)) return cur.getLong(0);
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
