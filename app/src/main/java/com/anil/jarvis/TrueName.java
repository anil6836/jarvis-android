package com.anil.jarvis;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 📞 (Anil, 11 Nov: "Truecaller … పాప్-అప్‌లో … పేరు చూపిస్తుంది. ఆ పేరు Jarvis చదివి చెప్పేలా చేయాలి"): while a number he
 * hasn't saved rings, the name the Truecaller app itself shows on his screen (its caller pop-up, or its notification) is read
 * through Jarvis's screen access and said instead of "తెలియని నంబర్". Only what is already on his screen is read, on the
 * phone; nothing is looked up, sent or kept anywhere else (no Truecaller keys, no web search). Its "spam" mark is said too.
 * The text picking is pure and desk-tested; the real pop-up's words may need a tune once his screenshot is seen.
 */
final class TrueName {
    private TrueName() {}

    static final String PKG = "com.truecaller";

    static final class Seen {
        String name;
        boolean spam;
    }

    /** His switch (⚙️ → 📞, on by default). */
    static boolean on(Context c) { return CallCare.on(c, "call_truecaller", true); }

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    /** What Truecaller shows now (its windows on the screen, then its notifications); null when nothing of it is there. */
    static Seen look(String number) {
        List<String> t = new ArrayList<>(JarvisAccessibility.windowTexts(PKG));
        NotifyListener nl = NotifyListener.self;
        if (nl != null) {
            try {
                StatusBarNotification[] all = nl.getActiveNotifications();
                if (all != null) for (StatusBarNotification s : all) {
                    if (s == null || s.getPackageName() == null || !s.getPackageName().startsWith(PKG)) continue;
                    Notification n = s.getNotification();
                    if (n == null || n.extras == null) continue;
                    for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT}) {
                        CharSequence x = n.extras.getCharSequence(k);
                        if (x != null) t.add(x.toString());
                    }
                }
            } catch (Exception ignored) {}
        }
        return t.isEmpty() ? null : parse(t, number);
    }

    // ================================================================ picking the name (pure)

    /** Its own words, buttons, the phone companies, places and times: never a person's name. */
    private static final Pattern LABEL = Pattern.compile("(?i)(?<![\\p{L}])(truecaller|true caller|verified|identified|unknown|private|hidden|spam|report(s|ed)?|block(ed)?"
            + "|call(s|ing)?|calls? back|message|sms|whatsapp|chat|save|saved|edit|view|profile|search|premium|gold|ads?|sponsored|business|priority"
            + "|mobile|landline|phone|number|jio|airtel|vodafone|vi|bsnl|idea|mtnl|reliance|operator|ago|mins?|minutes?|secs?|seconds?|hours?|today"
            + "|yesterday|incoming|outgoing|missed|ringing|hd|volte|vowifi|close|dismiss|more|options?|button|icon|image|logo|avatar|photo|picture"
            + "|location|telangana|andhra pradesh|karnataka|tamil nadu|maharashtra|caller ?id|answer|decline|reject|mute|speaker|swipe|tap)(?![\\p{L}])");
    /** A place line: "Telangana, India", "India". */
    private static final Pattern PLACE = Pattern.compile("(?i)(^|,\\s*)india\\s*$");
    private static final Pattern SPAM = Pattern.compile("(?i)(?<![\\p{L}])(spam|scam|fraud|fraudster|likely spam|telemarketer|telemarketing)(?![\\p{L}])");

    /**
     * The caller's name among the texts Truecaller shows (in screen order): the first that has letters, 2–40 long, at most
     * six words, no run of 5+ digits (the number) and none of Truecaller's own words, buttons, phone companies or states.
     * Spam: any of its texts says spam / fraud / telemarketer. Pure.
     */
    static Seen parse(List<String> texts, String number) {
        Seen s = new Seen();
        for (String raw : texts) {
            if (raw == null) continue;
            String t = raw.replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            if (SPAM.matcher(t).find()) { s.spam = true; continue; } // (a spam line is never the name)
            if (s.name != null) continue;
            if (t.replaceAll("\\D", "").length() >= 5) continue; // (the number, or a part of it)
            if (!t.matches(".*\\p{L}.*") || t.codePointCount(0, t.length()) < 2 || t.length() > 40 || t.split(" ").length > 6) continue;
            if (LABEL.matcher(t).find() || PLACE.matcher(t).find()) continue;
            if (t.matches("(?i)[\\p{L}]?\\s*[·•|,.-]?\\s*")) continue; // (a lone letter: an avatar's initial)
            s.name = t;
        }
        return s.name == null && !s.spam ? null : s;
    }

    /** "Ramesh Kumar" as Jarvis says it: trimmed, a stray "~" (a self-given name mark) dropped. */
    static String clean(String name) {
        if (name == null) return "";
        return name.replaceAll("^[~\\s·•|-]+", "").replaceAll("[\\s·•|-]+$", "").trim();
    }

    /** For the ring's note: what was read, short. */
    static String note(Seen s) {
        if (s == null) return "🔎 Truecaller: ఏ పేరూ కనిపించలేదు";
        return "🔎 Truecaller: " + (s.name == null ? "పేరు లేదు" : clean(s.name)) + (s.spam ? " · స్పామ్ అని చూపింది" : "");
    }
}
