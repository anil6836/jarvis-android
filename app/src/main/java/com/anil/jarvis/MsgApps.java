package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 💬 (Anil, 12 Nov: "వాట్సాప్, telegram, x, discord … ఒక్కొక్క దానికి on, off buttons … నాకు వద్దు అనుకున్నది మాత్రం ఆఫ్"):
 * one switch per messaging app for Jarvis telling its new messages. Off = Jarvis says nothing of that app's messages (no
 * spoken line, no card, no 📬, not on the watch); the phone's own notification stays as it is, and asking Jarvis about them
 * still works. The list: the chat apps installed, the phone's SMS app, and any other app that has sent him a message.
 */
final class MsgApps {
    private MsgApps() {}

    static final String OFF = "msg_off_apps", SEEN = "msg_seen_apps";

    /** The chat apps Jarvis knows (exact package names), in the order shown. */
    static final String[] KNOWN = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "com.twitter.android", "com.discord", "com.instagram.android", "com.facebook.orca",
            "com.facebook.katana", "com.facebook.mlite", "com.snapchat.android", "org.thoughtcrime.securesms", "com.linkedin.android",
            "in.mohalla.sharechat", "jp.naver.line.android", "com.viber.voip", "com.imo.android.imoim", "com.truecaller",
            "com.microsoft.teams", "com.Slack", "com.skype.raider", "com.kakao.talk", "com.google.android.apps.dynamite",
            "com.google.android.apps.messaging", "com.samsung.android.messaging"};

    static boolean off(Context c, String pkg) { return pkg != null && set(c, OFF).contains(pkg); }

    static void setOff(Context c, String pkg, boolean off) {
        Set<String> s = set(c, OFF);
        if (off) s.add(pkg); else s.remove(pkg);
        save(c, OFF, s);
    }

    /** A messaging app that just sent him something: kept so its switch shows (at most 40). */
    static void seen(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName())) return;
        for (String k : KNOWN) if (k.equals(pkg)) return;
        Set<String> s = set(c, SEEN);
        if (s.contains(pkg) || s.size() >= 40) return;
        s.add(pkg);
        save(c, SEEN, s);
    }

    /** {package, name shown} for every switch: the known chat apps that are installed, the SMS app, then the others seen. */
    static List<String[]> list(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> launcher = new java.util.HashSet<>();
        try {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
                if (r.activityInfo != null) launcher.add(r.activityInfo.packageName);
        } catch (Exception ignored) {}
        Set<String> pkgs = new LinkedHashSet<>();
        for (String k : KNOWN) if (launcher.contains(k)) pkgs.add(k);
        try {
            String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(c);
            if (sms != null) pkgs.add(sms);
        } catch (Exception ignored) {}
        pkgs.addAll(set(c, SEEN));
        pkgs.addAll(set(c, OFF)); // (one switched off is always shown, so it can be put back)
        List<String[]> out = new ArrayList<>();
        for (String p : pkgs) {
            String label = p;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); } catch (Exception e) { if (!launcher.contains(p) && !set(c, OFF).contains(p)) continue; }
            out.add(new String[]{p, emoji(p) + " " + label});
        }
        return out;
    }

    /** A small sign for the app (our own emoji, no logos). */
    static String emoji(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.startsWith("com.whatsapp.w4b")) return "💼";
        if (l.startsWith("com.whatsapp")) return "🟢";
        if (l.startsWith("org.telegram") || l.contains("challegram")) return "✈️";
        if (l.startsWith("com.twitter")) return "✖️";
        if (l.startsWith("com.discord")) return "🎮";
        if (l.startsWith("com.instagram")) return "📸";
        if (l.startsWith("com.facebook")) return "💙";
        if (l.startsWith("com.snapchat")) return "👻";
        if (l.contains("securesms")) return "🔵";
        if (l.contains("linkedin")) return "👔";
        if (l.contains("messaging")) return "✉️";
        return "💬";
    }

    private static Set<String> set(Context c, String k) {
        Set<String> s = new LinkedHashSet<>();
        for (String x : new Prefs(c).sp.getString(k, "").split(",")) if (!x.trim().isEmpty()) s.add(x.trim());
        return s;
    }

    private static void save(Context c, String k, Set<String> s) { new Prefs(c).sp.edit().putString(k, android.text.TextUtils.join(",", s)).apply(); }
}
