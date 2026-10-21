package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 📞 Calls (Anil, 21 Oct): who is calling said on the phone AND the watch while his own ringtone keeps ringing (softer
 * only while Jarvis talks and listens, never off); "ఎత్తు" answers on the loudspeaker (earphones / car Bluetooth: there);
 * "కట్ చేయి" cuts; "సైలెంట్" quiets this call only; "కట్ చేసి … అని మెసేజ్ పెట్టు" sends the caller an SMS after his
 * "పంపు" (his ready messages he allowed: at once); "తర్వాత చేస్తా" offers a reminder. His important people and a
 * second call within 5 minutes are said even when the phone is silent; unknown / foreign / 140 / 1600 / his spam
 * numbers are named. Every ring is noted (⚙️ → 📞) with what Jarvis did, so a call that wasn't said shows why.
 * The ring volume is always put back as it was (also after a crash: kept in the settings until restored).
 */
final class CallCare {
    private CallCare() {}

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context appCtx;

    // ---------------------------------------------------------------- the call ringing now (main thread writes)

    static volatile String key = "", who = "", app = "", number = "", warn = "";
    static volatile boolean phone = true, silenced, vip, repeat, unknown, phoneSpam;
    /** When this call began ringing (also the id of its line in the notes). */
    static volatile long ringAt;
    private static final Map<String, Long> lastRing = new HashMap<>();

    static boolean on(Context c, String k, boolean def) { return new Prefs(c).sp.getBoolean(k, def); }

    /** Who called and what Jarvis did, his spam marks, the ring's saved volume: on this phone only (never in a backup). */
    static final String PRIVATE = "calls_private";

    static SharedPreferences priv(Context c) { return c.getSharedPreferences(PRIVATE, Context.MODE_PRIVATE); }

    private static AudioManager audio(Context c) { return c.getSystemService(AudioManager.class); }

    /** Why the phone is quiet now (silent / vibrate / Do Not Disturb / night / recording), null when it rings aloud. */
    static String quietWhy(Context c, Prefs p) {
        try {
            AudioManager am = audio(c);
            if (am != null && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return "ఫోన్ సైలెంట్‌లో ఉంది";
            if (am != null && am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) return "ఫోన్ వైబ్రేట్‌లో ఉంది";
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return "Do Not Disturb ఆన్‌లో ఉంది";
        } catch (Exception ignored) {}
        if (p.night()) return "రాత్రి మోడ్ ఆన్";
        if (RecorderService.recording) return "రికార్డింగ్ జరుగుతోంది";
        return null;
    }

    /**
     * A call started ringing (NotifyListener, main thread). name: the name on the call (a saved contact's name, or the
     * number); appLabel: "WhatsApp" etc. for app calls.
     */
    static void incoming(Context svc, String sbnKey, Notification n, boolean isPhone, String name, String appLabel) {
        Context c = svc.getApplicationContext();
        appCtx = c;
        Prefs p = new Prefs(c);
        // the same SIM ring already said from the phone's own state a moment ago: only its notification noted (for its end / buttons)
        if (n != null && STATE_KEY.equals(key) && CallControl.phoneRingingNow() && System.currentTimeMillis() - ringAt < 60_000L) {
            key = sbnKey;
            phone = isPhone; // (WhatsApp's calls move the phone state too: now it is known which it was)
            if (appLabel != null && !isPhone) app = appLabel;
            log(c, "(ఫోన్ యాప్ నోటిఫికేషన్ కూడా వచ్చింది)");
            return;
        }
        main.removeCallbacks(stateFallback); // (the notification came: no need of the phone-state way)
        if (!CallControl.isRinging() || !sbnKey.equals(key)) restoreRing(c); // (a ring an earlier call left low)
        long now = System.currentTimeMillis();
        key = sbnKey;
        phone = isPhone;
        app = appLabel == null ? "" : appLabel;
        silenced = false;
        ringAt = now;
        String w = name == null ? "" : name.trim();
        number = "";
        unknown = false;
        if (CallWords.isNumber(w)) {
            number = w;
            String saved = CallHistory.nameFor(c, w);
            if (saved.isEmpty()) { unknown = true; w = ""; } else w = saved;
        } else if (w.isEmpty() || w.matches("(?i).*(unknown|private|hidden|no caller id|restricted|తెలియని|ప్రైవేట్).*")) {
            unknown = true;
            w = "";
        }
        who = w;
        // ⚠️ what kind of number
        String kind = "";
        if (on(c, "call_scam", true)) {
            if (!number.isEmpty() && unknown) kind = CallWords.kind(number, spamSet(c));
            if (kind.isEmpty() && phoneSaysSpam(n)) kind = "phone_spam";
        }
        warn = CallWords.warning(kind);
        phoneSpam = "phone_spam".equals(kind);
        // 🔁 the same caller again within 5 minutes
        String rk = who.isEmpty() ? CallWords.key(number) : CallWords.norm(who);
        boolean again = false;
        synchronized (lastRing) {
            Long prev = rk.isEmpty() ? null : lastRing.get(rk);
            again = prev != null && now - prev > 15_000 && now - prev < 5 * 60_000L;
            if (lastRing.size() > 40) lastRing.clear();
            if (!rk.isEmpty()) lastRing.put(rk, now);
        }
        if (!again && !rk.isEmpty()) again = calledJustNow(c, who, number);
        repeat = again;
        vip = !who.isEmpty() && CallWords.vip(who, p.sp.getString("call_vip", ""));

        if (!p.announceCalls()) { log(c, "📴 పేరు చెప్పడం ఆఫ్‌లో ఉంది (⚙️ → 📞 కాల్స్)"); return; }
        if (inCall(c)) { log(c, "📞 మీరు కాల్‌లో ఉన్నారు: పైకి చెప్పలేదు, విననూ లేదు (కాల్ స్క్రీన్ చూడండి)"); return; } // (call waiting: his words are for the call)
        String quiet = quietWhy(c, p);
        boolean pass = vip || repeat && on(c, "call_repeat", true);
        if (quiet != null && !pass) { log(c, "🔕 చెప్పలేదు: " + quiet + " (⭐ ముఖ్యమైన వాళ్ళు, 🔁 మళ్ళీ చేసినవాళ్ళు మాత్రమే)"); return; }
        if (HomeCare.on(c)) return; // (amma's tablet takes no calls)

        String say = line(p);
        String route;
        boolean panel = false;
        if (p.callByVoice() && android.provider.Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(new Intent(c, SheetActivity.class).putExtra(SheetActivity.EXTRA_CALL, say).putExtra(SheetActivity.EXTRA_CALL_SHORT, shortLine())
                        .putExtra(SheetActivity.EXTRA_CALL_AT, ringAt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                route = "📢 ఫోన్ ప్యానెల్ తెరిచాను (పేరు చెప్పి, మీ మాట వింటుంది)";
                panel = true;
            } catch (Exception e) {
                sayOverRing(c, say);
                route = "📢 ఫోన్‌లో చెప్పాను (ప్యానెల్ తెరవలేకపోయాను)";
            }
        } else {
            sayOverRing(c, say);
            route = "📢 ఫోన్‌లో చెప్పాను (మాట వినలేదు: " + (p.callByVoice() ? "'ఇతర యాప్‌ల పైన చూపడం' అనుమతి లేదు" : "మాటతో ఎత్తడం ఆఫ్") + ")";
        }
        if (quiet != null) route += " · " + (vip ? "⭐ ముఖ్యమైన వాళ్ళు" : "🔁 మళ్ళీ చేశారు") + ", " + quiet + " అయినా";
        if (on(c, "call_watch_voice", true) && WatchHub.msgsOn(c) && WatchHub.watchHere(c) && !Boolean.FALSE.equals(WatchHub.worn(c)) && !WatchHub.riding(c)) {
            // (the watch says it too; it listens only when the phone's panel doesn't, so one mic never hears the other's voice)
            WatchHub.call(c, sbnKey, say, () -> {}, !panel && p.callByVoice(), ringAt); // (his "by voice" switch off: the watch's buttons only)
            route += panel ? " · ⌚ వాచ్‌లో కూడా (వాచ్ బటన్లు)" : " · ⌚ వాచ్‌లో కూడా (వాచ్ వింటుంది)";
        }
        log(c, route);
    }

    // ---------------------------------------------------------------- a SIM call from the phone's own state (not only its notification)

    /** The key of a ring said from the phone's state (no notification seen yet). */
    static final String STATE_KEY = "phone-state";
    private static volatile String stateNumber = "";

    /**
     * The system's phone-state news (NotifyListener): RINGING → if the phone app's notification hasn't said it within a
     * moment, Jarvis says it from here (the number with the call-log permission, its saved name from contacts); OFFHOOK /
     * IDLE → the ring ended (answered / cut / missed): the ring back as it was.
     */
    static void phoneState(Context c, String state, String num) {
        final Context app = c.getApplicationContext();
        appCtx = app;
        if (android.telephony.TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            boolean first = !CallControl.phoneRingingNow();
            CallControl.phoneRinging(true);
            if (num != null && !num.trim().isEmpty()) stateNumber = num.trim();
            else if (first) stateNumber = "";
            seenLine(app, "📶 ఫోన్ స్థితి: SIM కాల్ మోగుతోంది" + (num != null && !num.isEmpty() ? " (నంబర్ వచ్చింది)" : ""));
            if (first) { main.removeCallbacks(stateFallback); main.postDelayed(stateFallback, 1800); }
            return;
        }
        boolean was = CallControl.phoneRingingNow();
        CallControl.phoneRinging(false);
        main.removeCallbacks(stateFallback);
        if (was) seenLine(app, "📶 ఫోన్ స్థితి: " + (android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) ? "కాల్ ఎత్తారు" : "రింగ్ ఆగింది"));
        if (was && phone && !CallControl.isRinging()) ringEnded(app);
    }

    /** The phone app's notification didn't say the SIM call in 1.8 s: said from the phone's state. */
    private static final Runnable stateFallback = () -> {
        Context c = appCtx;
        if (c == null || !CallControl.phoneRingingNow()) return;
        if (System.currentTimeMillis() - ringAt < 5000) return; // (a notification - the phone's or WhatsApp's, whose calls move the phone state too - said it meanwhile)
        String num = stateNumber;
        String name = num.isEmpty() ? "" : CallHistory.nameFor(c, num);
        incoming(c, STATE_KEY, null, true, name.isEmpty() ? num : name, "");
        log(c, "(ఫోన్ స్థితి నుంచి గుర్తించాను: ఫోన్ యాప్ నోటిఫికేషన్ రాలేదు)");
    };

    /** The phone is ringing now (its audio mode). */
    static boolean ringtoneMode(Context c) {
        try { AudioManager am = audio(c); return am != null && am.getMode() == AudioManager.MODE_RINGTONE; } catch (Exception e) { return false; }
    }

    /** The notification has an Answer button (or the call style's answer action): it rings. */
    static boolean hasAnswerButton(Notification n) {
        if (n == null) return false;
        try {
            if (n.extras != null && n.extras.getParcelable("android.answerIntent") != null) return true;
            if (n.actions != null) for (Notification.Action a : n.actions) {
                String t = a == null || a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
                if (t.matches("(?s).*(answer|accept|ఆన్సర్|స్వీకరించు|ఎత్తు).*") && !t.contains("decline") && !t.contains("reject")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 👀 What Jarvis saw of a call notification (⚙️ → 📞, technical: the app, its mark, full screen, its buttons; never the
     * caller's name), so a call that wasn't said shows why.
     */
    static void seen(Context c, android.service.notification.StatusBarNotification sbn, Notification n) {
        try {
            StringBuilder b = new StringBuilder(sbn.getPackageName().replace("com.", "").replace("android.", ""))
                    .append(" · ").append(n.category == null ? "మార్క్ లేదు" : n.category)
                    .append(" · full screen ").append(n.fullScreenIntent != null ? "✓" : "✗");
            if (Build.VERSION.SDK_INT >= 31 && n.extras != null) b.append(" · type ").append(n.extras.getInt("android.callType", 0)); // (Notification.EXTRA_CALL_TYPE)
            b.append(" · రింగ్ ").append(ringtoneMode(c) ? "✓" : "✗").append(" · ongoing ").append(sbn.isOngoing() ? "✓" : "✗");
            if (n.actions != null) {
                StringBuilder a = new StringBuilder();
                for (Notification.Action x : n.actions) if (x != null && x.title != null && a.length() < 40) a.append(a.length() > 0 ? "/" : "").append(x.title);
                if (a.length() > 0) b.append(" · [").append(a).append("]");
            }
            seenLine(c, b.toString());
        } catch (Exception ignored) {}
    }

    private static synchronized void seenLine(Context c, String line) {
        try {
            SharedPreferences sp = priv(c);
            JSONArray a = new JSONArray(sp.getString("call_seen", "[]"));
            String t = new java.text.SimpleDateFormat("h:mm:ss a", Locale.ENGLISH).format(new java.util.Date());
            if (a.length() > 0 && a.optString(a.length() - 1).endsWith(line)) return; // (the same news again)
            a.put(t + " · " + line);
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, a.length() - 8); i < a.length(); i++) out.put(a.get(i));
            sp.edit().putString("call_seen", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    static List<String> seenLines(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(priv(c).getString("call_seen", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) out.add(a.optString(i));
        } catch (Exception ignored) {}
        return out;
    }

    /** A call is already going on (phone or WhatsApp): a new one is call waiting, not for Jarvis's voice. */
    static boolean inCall(Context c) {
        try {
            AudioManager am = audio(c);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            // (a phone call: the audio says so; an app call: its notification AND the audio, so a missed "call ended" never mutes every later call)
            return mode == AudioManager.MODE_IN_CALL || CallControl.onCall() && mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Exception e) { return false; }
    }

    /** "Anil, రవి నుంచి కాల్ వస్తోంది. మళ్ళీ చేస్తున్నారు, అర్జెంట్ కావచ్చు." */
    private static String line(Prefs p) {
        String via = phone || app.isEmpty() ? "" : app + " ";
        String base = p.name() + ", " + (who.isEmpty() ? (number.isEmpty() ? "తెలియని నంబర్" : "తెలియని నంబర్, చివరి అంకెలు " + CallWords.lastFour(number)) : who)
                + " నుంచి " + via + "కాల్ వస్తోంది";
        if (repeat) base += ". మళ్ళీ చేస్తున్నారు, అర్జెంట్ కావచ్చు";
        if (!warn.isEmpty()) base += ". " + warn.replaceAll("[.]$", "");
        return base;
    }

    /** "రవి కాల్ చేస్తున్నారు" (the second time he is asked). */
    static String shortLine() { return (who.isEmpty() ? "తెలియని నంబర్ నుంచి కాల్" : who + " కాల్ చేస్తున్నారు"); }

    private static boolean phoneSaysSpam(Notification n) {
        if (n == null || n.extras == null) return false;
        StringBuilder b = new StringBuilder();
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT}) {
            CharSequence s = n.extras.getCharSequence(k);
            if (s != null) b.append(' ').append(s);
        }
        return b.toString().matches("(?is).*(spam|scam|fraud|suspected|స్పామ్|మోసం).*");
    }

    /** A missed / cut call from the same caller in the call log in the last 5 minutes (15 s or more ago). */
    private static boolean calledJustNow(Context c, String name, String num) {
        if (!CallHistory.can(c)) return false;
        long now = System.currentTimeMillis();
        String nk = CallWords.norm(name), k = CallWords.key(num);
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                CallLog.Calls.DATE + ">? AND (" + CallLog.Calls.TYPE + "=? OR " + CallLog.Calls.TYPE + "=?)",
                new String[]{String.valueOf(now - 5 * 60_000L), String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(CallLog.Calls.REJECTED_TYPE)}, null)) {
            while (cur != null && cur.moveToNext()) {
                if (now - cur.getLong(2) < 15_000) continue;
                if (!nk.isEmpty() && nk.equals(CallWords.norm(cur.getString(0))) || !k.isEmpty() && k.equals(CallWords.key(cur.getString(1)))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** No panel (no overlay permission / switched off): said in the voice of the announcements, the ring softer meanwhile. */
    private static void sayOverRing(Context c, String say) {
        ringLow(c, true);
        Announcer.say(c, say);
        main.postDelayed(() -> ringLow(c, false), 7000);
    }

    // ---------------------------------------------------------------- the ring's volume: softer, silent, always put back

    private static int ringVol = -1, ringMode = -1, setVol = -1, modeAfter = -1;
    private static boolean low, muted;

    private static final Runnable safety = new Runnable() {
        @Override public void run() {
            Context c = appCtx;
            if (c == null) return;
            if (CallControl.isRinging() && System.currentTimeMillis() - ringAt < 3 * 60_000L) { main.postDelayed(this, 20_000); return; }
            restoreRing(c);
        }
    };

    private static void remember(Context c, AudioManager am) {
        appCtx = c.getApplicationContext();
        if (ringVol >= 0) return;
        ringVol = am.getStreamVolume(AudioManager.STREAM_RING);
        ringMode = am.getRingerMode();
        priv(c).edit().putInt("call_ring_vol", ringVol).putInt("call_ring_mode", ringMode).apply(); // (put back even after a crash)
        main.removeCallbacks(safety);
        main.postDelayed(safety, 90_000);
    }

    /** Jarvis set the ring to v (remembered, so a change of his own afterwards is left as he made it). */
    private static void didSet(Context c, AudioManager am, int v) {
        setVol = v;
        modeAfter = am.getRingerMode();
        priv(c).edit().putInt("call_ring_set", v).putInt("call_ring_mode_after", modeAfter).apply();
    }

    /** He moved the ring himself after Jarvis did (a volume key, the silent switch): leave it as he made it. */
    private static boolean heChanged(AudioManager am) {
        if (setVol < 0) return false;
        try {
            if (am.getRingerMode() != modeAfter) return true;
            int v = am.getStreamVolume(AudioManager.STREAM_RING);
            return !muted && v != setVol;
        } catch (Exception e) { return false; }
    }

    /** Softer while Jarvis says who it is and listens (never off); his own volume between asks. Main thread. */
    static void ringLow(Context c, boolean down) {
        if (silenced) return;
        AudioManager am = audio(c);
        if (am == null) return;
        try {
            if (down) {
                if (low || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL || !CallControl.isRinging() || heChanged(am)) return;
                int v = am.getStreamVolume(AudioManager.STREAM_RING);
                if (v <= 2 && ringVol < 0) return; // already soft
                remember(c, am);
                int to = Math.max(1, Math.round(ringVol * 0.2f)); // (a fifth: heard, yet his words are heard over it)
                am.setStreamVolume(AudioManager.STREAM_RING, to, 0);
                didSet(c, am, to);
                low = true;
            } else if (low) {
                low = false;
                if (heChanged(am)) return;
                int to = Math.max(1, ringVol);
                am.setStreamVolume(AudioManager.STREAM_RING, to, 0);
                didSet(c, am, to);
            }
        } catch (Exception ignored) {}
    }

    /** "సైలెంట్": this call's ring goes quiet (it still rings silently: the screen can answer). His sound back when it ends. */
    static boolean silence(Context c) {
        if (silenced) return true;
        AudioManager am = audio(c);
        if (am == null) return false;
        try {
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) { silenced = true; return true; } // (already quiet)
            remember(c, am);
            boolean ok = false;
            try {
                am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
                muted = true;
                ok = am.isStreamMute(AudioManager.STREAM_RING) || am.getStreamVolume(AudioManager.STREAM_RING) == 0;
            } catch (SecurityException ignored) {} // (some phones want the Do Not Disturb permission for this)
            if (!ok) {
                try { am.setStreamVolume(AudioManager.STREAM_RING, 0, 0); ok = am.getStreamVolume(AudioManager.STREAM_RING) == 0; } catch (Exception ignored) {}
            }
            if (!ok) { muted = false; return false; } // (nothing changed: the softer ring is still put back as before)
            didSet(c, am, 0);
            low = false;
            silenced = true;
            return true;
        } catch (Exception e) { return false; }
    }

    /**
     * The ring is put back as it was (when the ring ends; at start if an earlier ring was left touched) — only what Jarvis
     * changed: if he switched to vibrate / silent or moved the volume himself meanwhile, his choice stays.
     */
    static void restoreRing(Context c) {
        SharedPreferences sp = priv(c);
        int v = ringVol >= 0 ? ringVol : sp.getInt("call_ring_vol", -1);
        int m = ringMode >= 0 ? ringMode : sp.getInt("call_ring_mode", -1);
        if (setVol < 0) { setVol = sp.getInt("call_ring_set", -1); modeAfter = sp.getInt("call_ring_mode_after", -1); }
        if (v < 0) return;
        AudioManager am = audio(c);
        if (am != null) {
            boolean his = setVol >= 0 && heChanged(am);
            if (his && setVol > 0 && am.getRingerMode() != modeAfter) {
                // he switched to vibrate / silent himself during the ring: his mode stays, and when he turns the sound back on the
                // ring comes back at his own volume, not at Jarvis's softer one (ringerChanged)
                sp.edit().putInt("call_ring_pending_vol", v).putInt("call_ring_pending_set", setVol).apply();
            }
            if (!his) {
                try { if (muted || am.isStreamMute(AudioManager.STREAM_RING)) am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) {}
                try { am.setStreamVolume(AudioManager.STREAM_RING, v, 0); } catch (Exception ignored) {}
                try { if (m >= 0 && am.getRingerMode() != m) am.setRingerMode(m); } catch (Exception ignored) {} // (only Jarvis's own volume 0 changed the mode)
            } else if (muted) {
                try { if (am.isStreamMute(AudioManager.STREAM_RING)) am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0); } catch (Exception ignored) {}
            }
        }
        ringVol = ringMode = setVol = modeAfter = -1;
        low = muted = false;
        sp.edit().remove("call_ring_vol").remove("call_ring_mode").remove("call_ring_set").remove("call_ring_mode_after").apply();
        main.removeCallbacks(safety);
    }

    /** Jarvis (re)started: a ring left touched by a call that ended while Jarvis was away is put back. */
    static void restoreIfLeft(Context c) {
        if (CallControl.isRinging()) return;
        restoreRing(c.getApplicationContext());
        ringerChanged(c.getApplicationContext());
    }

    /**
     * The ringer mode changed (NotifyListener): he turned the sound back on after switching to vibrate during a ring Jarvis
     * had made softer: the ring back at his own volume (only if it is still Jarvis's softer one).
     */
    static void ringerChanged(Context c) {
        SharedPreferences sp = priv(c);
        int v = sp.getInt("call_ring_pending_vol", -1), set = sp.getInt("call_ring_pending_set", -1);
        if (v < 0 || CallControl.isRinging()) return;
        AudioManager am = audio(c);
        if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        try { if (am.getStreamVolume(AudioManager.STREAM_RING) == set) am.setStreamVolume(AudioManager.STREAM_RING, v, 0); } catch (Exception ignored) {}
        sp.edit().remove("call_ring_pending_vol").remove("call_ring_pending_set").apply();
    }

    /** The ring stopped (answered, cut, missed, ended). */
    static void ringEnded(Context c) {
        restoreRing(c.getApplicationContext());
        silenced = false;
    }

    /** A call notification went (NotifyListener). wasOngoing: the call in progress ended. */
    static void removed(Context c, String sbnKey, boolean wasOngoing) {
        if (sbnKey != null && sbnKey.equals(key) && !CallControl.isRinging()) ringEnded(c);
    }

    // ---------------------------------------------------------------- answered / cut: what the other side (watch / panel) did

    private static volatile String lastDone = "";
    private static volatile long lastDoneAt;

    /** The phone panel and the watch both listen: the second one to hear "ఎత్తు" finds the call already answered. */
    static boolean doneJustNow(boolean answer) {
        return (answer ? "a" : "d").equals(lastDone) && System.currentTimeMillis() - lastDoneAt < 8000;
    }

    /** Answered. byVoice (or from the watch): the phone isn't in his hand, so the loudspeaker. */
    static void answered(Context c, boolean byVoice) {
        if (answeredFor == ringAt && ringAt > 0) return; // (the other side answered this ring already: once, and the speaker pressed once)
        answeredFor = ringAt;
        lastDone = "a";
        lastDoneAt = System.currentTimeMillis();
        ringEnded(c);
        log(c, "✅ ఎత్తాను" + (byVoice ? " (మాటతో)" : ""));
        if (!warn.isEmpty()) safetyNote(c);
        if (byVoice && on(c, "call_speaker", true)) speaker(c);
    }

    private static volatile long answeredFor;

    static void declined(Context c) {
        lastDone = "d";
        lastDoneAt = System.currentTimeMillis();
        ringEnded(c);
        log(c, "✂️ కట్ చేశాను");
    }

    /** "🔊 స్పీకర్": he wants the loudspeaker (the earphones / car's Bluetooth: there, not the speaker). */
    static boolean speakerWanted(Context c) { return on(c, "call_speaker", true) && earDevice(audio(c)) == null; }

    private static void speaker(Context c) {
        final Context app = c.getApplicationContext();
        final AudioManager am = audio(app);
        String ear = earDevice(am);
        if (ear != null) { log(app, "🎧 " + ear + " లో (స్పీకర్ కాదు)"); return; }
        final boolean appCall = !phone;
        final long ring = ringAt; // (the result goes on this call's own line)
        // the phone's own audio says whether the call is on the loudspeaker (also for WhatsApp's calls)
        final java.util.function.BooleanSupplier onNow = () -> { try { return am != null && am.isSpeakerphoneOn(); } catch (Exception e) { return false; } };
        new Thread(() -> {
            // Jarvis's own panel must be gone first (it sits at the bottom, where the call screen's buttons are)
            for (int i = 0; i < 15 && SheetActivity.open; i++) { try { Thread.sleep(100); } catch (InterruptedException ignored) {} }
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            String r = "";
            if (appCall) {
                if (CallControl.pressSpeakerButton(app)) r = settledAudio(onNow) ? "on|నోటిఫికేషన్|Speaker" : "pressed_not_on|నోటిఫికేషన్ Speaker"; // its call notification's own button
                else {
                    r = JarvisAccessibility.pressSpeaker(1500, onNow); // (its call screen, if it is up)
                    if (r.startsWith("no_button") && CallControl.pressSpeakerButton(app)) r = settledAudio(onNow) ? "on|నోటిఫికేషన్|Speaker" : "pressed_not_on|నోటిఫికేషన్ Speaker";
                    // answered from its notification, its call screen may not be up: brought up, then its Speaker pressed
                    if (r.startsWith("no_button")) {
                        String how = CallControl.openCallScreen(app);
                        if (!how.isEmpty()) r = JarvisAccessibility.pressSpeaker(7000, onNow);
                        if (r.startsWith("no_button")) r = r + " · తెరిచే దారి: " + (how.isEmpty() ? "లేదు" : how);
                    }
                }
            } else {
                r = JarvisAccessibility.pressSpeaker(7000, null); // (the phone app's own button: one press, as it has always worked)
                if (r.startsWith("on") && !settledAudio(onNow)) r = r + "|సౌండ్ మారిందో తెలియలేదు";
            }
            final String res = r;
            final String how = res.startsWith("on|") ? res.substring(3).replace('|', ' ') : "";
            main.post(() -> log(app, (res.startsWith("on") ? "🔊 స్పీకర్ ఆన్ చేశాను" + (how.isEmpty() ? "" : " (" + how + ")")
                    : res.startsWith("pressed_not_on") ? "⚠️ స్పీకర్ బటన్ నొక్కాను, కానీ సౌండ్ స్పీకర్‌కి మారలేదు: మీరే నొక్కండి (" + res.substring(res.indexOf('|') + 1) + ")"
                    : "already".equals(res) ? "🔊 స్పీకర్ అప్పటికే ఆన్‌లో ఉంది"
                    : "no_accessibility".equals(res) ? "⚠️ స్పీకర్ ఆన్ చేయలేదు: Jarvis యాక్సెసిబిలిటీ ఆఫ్ (⚙️ → 📞 లో ♿)"
                    : "⚠️ కాల్ స్క్రీన్‌లో స్పీకర్ బటన్ దొరకలేదు: మీరే నొక్కండి" + (res.startsWith("no_button") && res.length() > 9 ? " (" + res.substring(9).trim() + ")" : "")), ring));
        }, "jarvis-call-speaker").start();
    }

    /** The loudspeaker on within 1.2 s (the phone's audio). */
    private static boolean settledAudio(java.util.function.BooleanSupplier on) {
        for (int i = 0; i < 8; i++) {
            if (on.getAsBoolean()) return true;
            try { Thread.sleep(150); } catch (InterruptedException e) { break; }
        }
        return on.getAsBoolean();
    }

    /** Earphones / earbuds / a car or helmet on Bluetooth (not his watch): its name, else null. */
    static String earDevice(AudioManager am) {
        if (am == null) return null;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                boolean ear = t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == 23 /* hearing aid */ || t == 26 /* BLE headset */;
                if (!ear) continue;
                String name = d.getProductName() == null ? "" : d.getProductName().toString().trim();
                if (name.toLowerCase(Locale.ROOT).contains("watch")) continue; // (the watch is no earphone)
                return name.isEmpty() ? "ఇయర్‌ఫోన్స్ / బ్లూటూత్" : name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** An unknown / foreign / spam caller answered: the rule, quietly on the screen (never said into the call). */
    private static void safetyNote(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("jarvis_call_safe", "కాల్ జాగ్రత్తలు", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
            String t = "OTP, PIN, UPI PIN, కార్డ్ నంబర్ ఎవరికీ చెప్పకండి: బ్యాంకులు ఫోన్‌లో ఎప్పుడూ అడగవు. అనుమానంగా ఉంటే కాల్ పెట్టేసి బ్యాంక్ నంబర్‌కి మీరే చేయండి.";
            nm.notify(7101, new Notification.Builder(c, "jarvis_call_safe").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ " + warn).setContentText(t).setStyle(new Notification.BigTextStyle().bigText(t)).setAutoCancel(true)
                    .setTimeoutAfter(20 * 60_000L).build());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- the talk after a cut call

    private static volatile String replyBy = "";
    private static volatile long replyAt;

    /** Only one side (the phone's panel or the watch) talks the message after the cut. */
    static synchronized boolean claimReply(String by) {
        long now = System.currentTimeMillis();
        if (!replyBy.isEmpty() && !replyBy.equals(by) && now - replyAt < 120_000) return false;
        replyBy = by;
        replyAt = now;
        return true;
    }

    static synchronized void releaseReply(String by) { if (replyBy.equals(by)) replyBy = ""; }

    /** The ringing call's caller, kept for the message after it is cut. */
    static CallWords.Reply newReply() {
        CallWords.Reply r = new CallWords.Reply(who);
        r.number = number;
        r.phone = phone;
        r.ringAt = ringAt;
        r.phoneSpam = phoneSpam;
        return r;
    }

    static final String[][] READY = {
            {"meeting", "🗓️ మీటింగ్", "మీటింగ్‌లో ఉన్నాను, తర్వాత కాల్ చేస్తాను."},
            {"driving", "🚗 డ్రైవింగ్", "డ్రైవ్ చేస్తున్నాను, తర్వాత కాల్ చేస్తాను."},
            {"busy", "⏳ బిజీ", "ఇప్పుడు మాట్లాడలేను, కొద్దిసేపట్లో కాల్ చేస్తాను."}};

    static String readyText(Context c, String q) {
        for (String[] r : READY) if (r[0].equals(q)) return new Prefs(c).sp.getString("call_q_" + q, r[2]);
        return "";
    }

    static boolean readyOk(Context c, String q) { return !q.isEmpty() && on(c, "call_q_" + q + "_ok", false); }

    static boolean driving(Context c) { return new Prefs(c).driving() || Bike.riding(c); }

    /**
     * After the cut: the message (his words / his ready one), or (driving) his driving message offered, or the reminder
     * when he will call back; null when there is nothing more to talk. tapped: he tapped a ready message (that tap is
     * his yes).
     */
    static CallWords.Step afterCut(Context c, CallWords.Reply r, CallWords.Word w, boolean tapped) {
        boolean hidden = r.who.isEmpty() && r.number.isEmpty();
        boolean offerDrive = w.kind != CallWords.MESSAGE && driving(c) && on(c, "call_drive_offer", true);
        if (w.kind == CallWords.MESSAGE || offerDrive) {
            String no = noSmsWhy(c, r);
            if (no == null) {
                if (w.kind == CallWords.MESSAGE) return CallWords.start(r, w, readyText(c, w.quick), tapped || readyOk(c, w.quick));
                return CallWords.offerDriving(r, readyText(c, "driving"), w.later, w.minutes);
            }
            if (w.kind == CallWords.MESSAGE) { // he asked for one: why not, said
                if (w.later && !hidden) { r.minutes = w.minutes; r.later = true; return CallWords.askRemind(r, "కట్ చేశాను. " + no + " "); }
                return CallWords.over("కట్ చేశాను. " + no);
            }
        }
        if (w.later && !hidden) { r.minutes = w.minutes; r.later = true; return CallWords.askRemind(r, "కట్ చేశాను. "); }
        return null;
    }

    /** Why no SMS can go to this caller (a hidden number; a foreign / 140 / 1600 / spam number: costs money, tells a cheat the number works), else null. */
    static String noSmsWhy(Context c, CallWords.Reply r) {
        if (r.who.isEmpty() && r.number.isEmpty()) return "ఇది దాచిన నంబర్: మెసేజ్ పంపలేను.";
        if (r.phoneSpam) return "ఫోన్ స్పామ్ అనుమానం అన్న నంబర్‌కి మెసేజ్ పంపను.";
        String k = r.number.isEmpty() ? "" : CallWords.kind(r.number, spamSet(c));
        if (!k.isEmpty()) return ("foreign".equals(k) ? "విదేశీ నంబర్" : "promo".equals(k) ? "ప్రకటనల నంబర్" : "service".equals(k) ? "1600 సర్వీస్ నంబర్" : "మీరు స్పామ్ అన్న నంబర్")
                + " కి మెసేజ్ పంపను.";
        return null;
    }

    /** ⏰ "రవికి తిరిగి కాల్ చేయాలి" in N minutes. */
    static void remind(Context c, CallWords.Reply r) {
        try {
            JSONObject x = Store.get(c).addReminder("📞 " + (r.who.isEmpty() || CallWords.isNumber(r.who) ? "ఆ నంబర్" : r.who) + " కి తిరిగి కాల్ చేయాలి",
                    System.currentTimeMillis() + r.minutes * 60_000L);
            if (x != null) Reminders.schedule(c, x);
            log(c, "⏰ " + CallWords.inMinutes(r.minutes) + " గుర్తు", r.ringAt);
        } catch (Exception ignored) {}
    }

    /** The SMS to the caller of the call just cut (off the main thread); done(null = sent, else why not) on the main thread. */
    static void send(Context c, CallWords.Reply r, Consumer<String> done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = noSmsWhy(app, r);
            if (err == null) {
                String to = r.number.isEmpty() ? numberOf(app, r) : r.number;
                if (to.isEmpty()) err = "ఆ కాల్ నంబర్ దొరకలేదు, పంపలేదు";
                else if (!CallWords.kind(to, spamSet(app)).isEmpty()) err = "ఆ నంబర్‌కి (విదేశీ / ప్రకటన / స్పామ్) పంపను";
                else err = sms(app, to, r.text);
            } else err = err.replaceAll("[.]$", "");
            final String e = err;
            main.post(() -> {
                log(app, e == null ? "💬 SMS పంపాను" : "⚠️ SMS: " + e, r.ringAt); // (on that call's own line)
                done.accept(e);
            });
        }, "jarvis-call-sms").start();
    }

    /**
     * The caller's number: this very ring's row in the call log (it may take a moment to appear), with exactly the name
     * the call showed; else the saved contact of that exact name (only when one contact has it). Never a guess.
     */
    private static String numberOf(Context c, CallWords.Reply r) {
        if (r.who.isEmpty()) return "";
        if (r.phone) {
            for (int i = 0; i < 6; i++) {
                String n = CallHistory.numberOfRing(c, r.ringAt, r.who);
                if (!n.isEmpty()) return n;
                try { Thread.sleep(700); } catch (InterruptedException e) { break; }
            }
        }
        return CallHistory.numberFor(c, r.who);
    }

    private static final String SENT = "com.anil.jarvis.CALL_SMS_SENT";

    /** The SIM chosen for messages; null when none is ("Ask every time" with two SIMs: Jarvis never picks one itself). */
    @SuppressWarnings("deprecation")
    private static SmsManager smsManager() {
        int sub = SubscriptionManager.getDefaultSmsSubscriptionId();
        return sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID ? null : SmsManager.getSmsManagerForSubscriptionId(sub);
    }

    /** A SIM is chosen for messages (⚙️ → 📞 check). */
    static boolean smsSimSet() { return SubscriptionManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID; }

    /** Sends and waits for the network's answer (worker thread): null = sent, else why not (Telugu). */
    static String sms(Context c, String to, String text) {
        if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "SMS అనుమతి లేదు (⚙️ → 📞 కాల్స్ → 💬)";
        if (text == null || text.trim().isEmpty()) return "మెసేజ్ ఖాళీగా ఉంది";
        try {
            SmsManager sm = smsManager();
            if (sm == null) return "మెసేజ్‌లకి SIM ఎంచుకోలేదు (ఫోన్ Settings → SIM manager → Messages)";
            ArrayList<String> parts = sm.divideMessage(text.trim());
            final int id = (int) (System.nanoTime() & 0xFFFFFF);
            final CountDownLatch left = new CountDownLatch(parts.size());
            final int[] worst = {Activity.RESULT_OK};
            BroadcastReceiver rcv = new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) {
                    if (i.getIntExtra("id", -1) != id) return;
                    int code = getResultCode();
                    if (code != Activity.RESULT_OK) worst[0] = code;
                    left.countDown();
                }
            };
            c.registerReceiver(rcv, new IntentFilter(SENT), Build.VERSION.SDK_INT >= 33 ? 4 /* RECEIVER_NOT_EXPORTED */ : 0);
            try {
                ArrayList<PendingIntent> sent = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++)
                    sent.add(PendingIntent.getBroadcast(c, (id % 100000) * 10 + i, new Intent(SENT).setPackage(c.getPackageName()).putExtra("id", id).putExtra("part", i),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                if (parts.size() == 1) sm.sendTextMessage(to, null, parts.get(0), sent.get(0), null);
                else sm.sendMultipartTextMessage(to, null, parts, sent, null);
                if (!left.await(40, TimeUnit.SECONDS)) return "SMS వెళ్ళిందో తెలియలేదు: Messages లో చూడండి";
            } finally {
                try { c.unregisterReceiver(rcv); } catch (Exception ignored) {}
            }
            switch (worst[0]) {
                case Activity.RESULT_OK: return null;
                case SmsManager.RESULT_ERROR_RADIO_OFF: return "ఫ్లైట్ మోడ్ / సిగ్నల్ లేదు";
                case SmsManager.RESULT_ERROR_NO_SERVICE: return "సిగ్నల్ లేదు";
                default: return "SMS వెళ్ళలేదు (SIM, బ్యాలెన్స్ చూడండి)";
            }
        } catch (SecurityException e) {
            return "SMS అనుమతి లేదు (⚙️ → 📞 కాల్స్ → 💬)";
        } catch (Exception e) {
            return "SMS పంపలేకపోయాను";
        }
    }

    // ---------------------------------------------------------------- 🚫 his spam marks, ⭐ important people

    static Set<String> spamSet(Context c) {
        Set<String> s = new HashSet<>();
        for (String k : priv(c).getString("call_spam", "").split(",")) if (!k.trim().isEmpty()) s.add(k.trim());
        return s;
    }

    /** "ఇది స్పామ్": marks (or unmarks) a number; returns its key, "" when there is no number. */
    static String markSpam(Context c, String num, boolean spam) {
        String k = CallWords.key(num);
        if (k.length() < 5) return "";
        Set<String> s = spamSet(c);
        if (spam) s.add(k); else s.remove(k);
        priv(c).edit().putString("call_spam", android.text.TextUtils.join(",", s)).apply();
        return k;
    }

    static String vipList(Context c) { return new Prefs(c).sp.getString("call_vip", ""); }

    static void vipSet(Context c, String list) { new Prefs(c).sp.edit().putString("call_vip", list == null ? "" : list.trim()).apply(); }

    // ---------------------------------------------------------------- 📵 missed calls when he unlocks the phone

    static void unlocked(Context c) {
        final Context app = c.getApplicationContext();
        main.postDelayed(() -> new Thread(() -> missedOnUnlock(app), "jarvis-missed").start(), 2500); // (the call log read off the main thread)
    }

    private static void missedOnUnlock(Context c) {
        try {
            Prefs p = new Prefs(c);
            if (!on(c, "call_missed_unlock", true) || p.homeMode() || HomeCare.on(c)) return;
            if (quietWhy(c, p) != null || CallControl.busyWithCall() || MainActivity.busyTalking() || p.driving() || Bike.riding(c) || Rest.resting(c)) return;
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            if (h < 7 || h >= 22) return;
            long told = CallHistory.toldAt(c);
            List<CallHistory.Group> g = CallHistory.unseen(c, told);
            g.addAll(CallHistory.appMissed(c, told));
            if (g.isEmpty()) return;
            CallHistory.markTold(c, System.currentTimeMillis());
            int total = CallHistory.total(g);
            Announcer.say(c, p.name() + ", మీరు చూడని " + total + " మిస్డ్ " + (total == 1 ? "కాల్" : "కాల్స్") + ": " + CallHistory.sayList(g) + ".");
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- 🔍 what Jarvis did with each call (⚙️ → 📞)

    /** One line per ring (the newest 6), "→" between the steps. Main thread mostly; any thread is fine. */
    static void log(Context c, String what) { log(c, what, ringAt); }

    /** id: the ring the step belongs to (the message after a cut goes on that call's line, even if another rang meanwhile). */
    static synchronized void log(Context c, String what, long id) {
        try {
            SharedPreferences sp = priv(c);
            JSONArray a = new JSONArray(sp.getString("call_events", "[]"));
            JSONObject line = null;
            for (int i = a.length() - 1; i >= 0 && id > 0; i--) { JSONObject o = a.optJSONObject(i); if (o != null && o.optLong("id") == id) { line = o; break; } }
            if (line != null) line.put("did", line.optString("did") + " → " + what);
            else {
                String w = who.isEmpty() ? (number.isEmpty() ? "తెలియని నంబర్" : "తెలియని నంబర్ …" + CallWords.lastFour(number).replace(" ", "")) : who;
                a.put(new JSONObject().put("id", id).put("at", id > 0 ? id : System.currentTimeMillis()).put("who", w + (phone || app.isEmpty() ? "" : " (" + app + ")")).put("did", what));
            }
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, a.length() - 6); i < a.length(); i++) out.put(a.get(i));
            sp.edit().putString("call_events", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** "10:15 AM · రవి: 📢 … → ✅ …", newest first. */
    static List<String> events(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(priv(c).getString("call_events", "[]"));
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(f.format(new java.util.Date(o.optLong("at"))) + " · " + o.optString("who") + ": " + o.optString("did"));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
