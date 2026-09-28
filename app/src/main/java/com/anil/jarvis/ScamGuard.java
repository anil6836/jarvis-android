package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scam guard: every new SMS / WhatsApp / Telegram / Gmail message is checked on the phone (nothing is
 * sent anywhere, no AI cost) for the usual Indian scam patterns: fake KYC, OTP / PIN asks, risky links,
 * lottery, electricity-cut threats, APK files, "enter PIN to receive money", parcel / police threats,
 * easy jobs and trading tips. New autopay / mandate SMS are reported too. Warnings are notifications;
 * tapping one lets Jarvis explain the message.
 */
final class ScamGuard {
    private ScamGuard() {}

    private static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging",
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "com.truecaller",
            "com.microsoft.android.smsorganizer", "com.google.android.gm", "com.jio.join"));

    static final class Verdict {
        boolean scam, mandate;
        int score;
        final List<String> reasons = new ArrayList<>();
        String merchant, maxAmount;
    }

    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\b[a-z0-9-]{2,}\\.(com|in|co|xyz|top|info|online|site|click|link|live|shop|app|ly|me|io|cc|vip|buzz|icu|tk|ml|ga|cf|gq)(/|\\b))");
    private static final Pattern IP_LINK = Pattern.compile("https?://\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern MOBILE = Pattern.compile("^(\\+?91)?[6-9]\\d{9}$");
    private static final Pattern MERCHANT = Pattern.compile("(?i)\\b(?:for|towards|merchant[:\\s]+)\\s*([A-Za-z0-9&.' -]{2,40}?)\\s*(?=\\b(?:starting|start|on|with|from|mandate|via|using|has|is|of|for)\\b|[.,:!\\n]|$)");
    private static final Pattern MAX_AMOUNT = Pattern.compile("(?i)(?:max(?:imum)?\\s*(?:amount|limit)?|limit|up\\s*to|upto)\\s*(?:of\\s*)?:?\\s*(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d+)?)");

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** Pure check of one message (from = sender name or number). */
    static Verdict judge(String from, String text) {
        Verdict v = new Verdict();
        String t = text == null ? "" : text;
        String l = t.toLowerCase(Locale.ROOT);
        if (l.trim().isEmpty()) return v;

        // ---- a new autopay / mandate on his card or account (a bank message, not a scam)
        boolean mandateWord = has(l, "mandate", "autopay", "auto pay", "auto-pay", "standing instruction", "e-mandate", "emandate", "si registered", "recurring payment");
        boolean active = has(l, "active", "activated", "registered", "created", "set up", "setup", "successful", "approved", "enabled");
        boolean off = has(l, "cancel", "revok", "paused", "deactivat", "expired", "ended", "declined", "failed", "rejected", "not active");
        if (mandateWord && active && !off) {
            v.mandate = true;
            Matcher m = MERCHANT.matcher(t);
            while (m.find()) {
                String name = m.group(1).trim();
                if (name.length() >= 2 && !name.toLowerCase(Locale.ROOT).matches("(your|you|the|card|a/c|account|rs\\.?|inr|autopay|mandate).*")) { v.merchant = name; break; }
            }
            Matcher a = MAX_AMOUNT.matcher(t);
            if (a.find()) v.maxAmount = "₹" + a.group(1);
            return v;
        }

        // ---- scam patterns (English, Telugu, Hinglish)
        boolean link = URL.matcher(t).find() || has(l, "t.me/", "wa.me/", "chat.whatsapp.com");
        boolean safeOtp = has(l, "do not share", "don't share", "dont share", "never share", "not share", "share mat", "shared with anyone",
                "షేర్ చేయకండి", "ఎవరికీ చెప్పకండి", "ఎవరితోనూ");
        if (has(l, ".apk")) add(v, 5, "APK ఫైల్ link (ఇలాంటి యాప్ ఇన్‌స్టాల్ చేస్తే ఫోన్, బ్యాంక్ ఖాతా హ్యాక్ అవ్వొచ్చు)");
        if (IP_LINK.matcher(t).find()) add(v, 3, "పేరు లేని IP అడ్రస్ link");
        if (has(l, "bit.ly", "tinyurl", "cutt.ly", "rb.gy", "is.gd", "t.ly", "shorturl", "goo.gl", "tiny.cc", "ow.ly", "s.id/"))
            add(v, 2, "అసలు site ని దాచే చిన్న (short) link");
        if (has(l, "kyc", "pan card", "pan update", "aadhaar update", "aadhar update", "ఆధార్", "కేవైసీ")
                && has(l, "update", "expire", "expired", "pending", "suspend", "block", "verify", "verification", "complete", "అప్‌డేట్"))
            add(v, 3, "KYC / PAN / ఆధార్ అప్‌డేట్ పేరుతో భయపెట్టడం");
        if (has(l, "account", "a/c", "card", "sim", "upi", "wallet", "ఖాతా", "అకౌంట్")
                && has(l, "will be blocked", "blocked today", "been blocked", "suspended", "deactivated", "will be closed", "freeze", "frozen",
                "block ho", "band ho", "బ్లాక్ అవుతుంది", "బ్లాక్ చేస్తాం"))
            add(v, 2, "ఖాతా / కార్డ్ / SIM బ్లాక్ అవుతుందని బెదిరింపు");
        if (has(l, "otp", "one time password", "ఓటీపీ") && !safeOtp
                && has(l, "share", "send", "tell", "forward", "call us", "reply with", "batao", "bataye", "bhejo", "చెప్పండి", "పంపండి"))
            add(v, 4, "OTP అడగడం (బ్యాంక్, కంపెనీలు ఎప్పుడూ OTP అడగవు)");
        if (has(l, "upi pin", "atm pin", "cvv", "card number", "net banking password", "mpin") && !safeOtp
                && has(l, "enter", "share", "send", "update", "verify", "provide", "చెప్పండి"))
            add(v, 4, "PIN / CVV / పాస్‌వర్డ్ అడగడం");
        if (has(l, "receive", "credited to you", "get rs", "get ₹", "refund", "cashback", "you will get")
                && has(l, "upi pin", "enter pin", "enter your pin", "approve the request", "collect request", "accept the request"))
            add(v, 4, "డబ్బు రావడానికి PIN / approve అడగడం (డబ్బు రావడానికి PIN అవసరమే లేదు)");
        if (has(l, "lottery", "lucky draw", "you have won", "you won", "winner", "congratulations you", "prize", "jackpot", "kbc",
                "free gift", "లాటరీ", "బహుమతి", "గెలిచారు"))
        {
            add(v, link || has(l, "claim", "call", "pay", "fee", "processing", "tax") ? 3 : 2, "లాటరీ / బహుమతి ఆశ చూపడం");
            if (has(l, "processing fee", "registration fee", "pay rs", "pay ₹", "pay the fee", "pay tax", "gst", "ఫీజు"))
                add(v, 2, "బహుమతి కోసం ముందు డబ్బు కట్టమనడం");
        }
        if (has(l, "electricity", "electric bill", "power supply", "bijli", "current bill", "విద్యుత్", "కరెంట్")
                && has(l, "disconnect", "disconnected", "cut off", "will be cut", "tonight", "9:30", "10:30", "కట్"))
            add(v, 4, "కరెంట్ కట్ చేస్తామని బెదిరింపు (కరెంట్ బిల్లు మోసం)");
        if (has(l, "part time", "part-time", "work from home", "daily income", "earn rs", "earn ₹", "earn upto", "per day", "daily salary",
                "like youtube", "youtube like", "telegram task", "prepaid task", "rating task", "ఇంటి నుంచే", "రోజుకు"))
            add(v, link || has(l, "telegram", "whatsapp", "t.me", "wa.me") ? 3 : 2, "ఈజీ ఉద్యోగం / రోజూ సంపాదన ఆశ");
        if (has(l, "double your money", "guaranteed return", "guaranteed profit", "100% profit", "crypto", "bitcoin", "forex", "trading tips",
                "stock tips", "ipo allotment", "investment plan") && (link || has(l, "join", "whatsapp", "telegram", "call")))
            add(v, 3, "పెట్టుబడి / ట్రేడింగ్ లాభాల ఆశ");
        if (has(l, "customs", "parcel", "courier", "fedex", "dhl", "narcotics", "drugs", "cbi", "police", "cyber crime", "cybercrime",
                "digital arrest", "ed officer", "trai", "సీబీఐ", "పోలీస్")
                && has(l, "seized", "held", "case", "arrest", "fir", "fine", "penalty", "pay", "illegal", "suspend", "blocked"))
            add(v, 4, "పార్శిల్ / పోలీస్ / CBI / డిజిటల్ అరెస్ట్ బెదిరింపు");
        if (link && has(l, "income tax refund", "it refund", "tax refund", "refund of rs", "refund of ₹", "gas subsidy", "electricity refund"))
            add(v, 3, "రీఫండ్ పేరుతో link");
        String f = from == null ? "" : from.replaceAll("[\\s-]", "");
        if (MOBILE.matcher(f).matches() && has(l, "bank", "sbi", "hdfc", "icici", "axis", "kotak", "paytm", "phonepe", "gpay", "upi", "kyc") && link)
            add(v, 2, "బ్యాంక్ పేరుతో మామూలు మొబైల్ నంబర్ నుంచి link");
        if (v.score > 0 && has(l, "urgent", "immediately", "within 24 hours", "within 2 hours", "today itself", "last warning", "final notice",
                "act now", "వెంటనే", "తక్షణం", "ఈరోజే"))
            add(v, 1, "తొందరపెట్టడం");
        if (v.score > 0 && link) add(v, 1, "నొక్కమని link");
        v.scam = v.score >= 4;
        return v;
    }

    private static void add(Verdict v, int points, String why) {
        v.score += points;
        if (!v.reasons.contains(why)) v.reasons.add(why);
    }

    // ---------------------------------------------------------------- from the notification listener

    private static final Map<String, Long> told = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 200; }
    };

    static void check(Context c, String pkg, String app, String from, String text) {
        try {
            if (pkg == null || !APPS.contains(pkg) || !new Prefs(c).scamGuard()) return;
            Verdict v = judge(from, text);
            if (!v.scam && !v.mandate) return;
            // one warning per sender and kind every 6 hours (chat notifications are posted again with each new line)
            String key = pkg + "|" + from + "|" + (v.mandate ? "m" + v.merchant : "s");
            long now = System.currentTimeMillis();
            synchronized (told) {
                Long at = told.get(key);
                if (at != null && now - at < 6 * 3600_000L) return;
                told.put(key, now);
            }
            String who = from == null || from.isEmpty() ? app : from;
            String snippet = text.length() > 160 ? text.substring(0, 160) + "…" : text;
            if (v.mandate) {
                String title = "🔔 కొత్త autopay: " + (v.merchant != null ? v.merchant : "మీ card / ఖాతా మీద") + (v.maxAmount != null ? " (గరిష్ఠం " + v.maxAmount + ")" : "");
                String body = "మీ card / ఖాతా మీద కొత్త autopay (mandate) పెట్టారు. మీరే పెడితే సరే. మీరు పెట్టకపోతే, లేదా అవసరం లేకపోతే bank app లో "
                        + "Standing Instructions / e-mandate లోకి వెళ్లి cancel చేయండి.\n\n" + who + ": " + snippet;
                notify(c, key.hashCode(), title, body, "ఈ autopay మెసేజ్ గురించి చెప్పు, ఇది ఏంటి, cancel చేయాలా? మెసేజ్: " + who + ": " + text);
            } else {
                StringBuilder b = new StringBuilder("ఎందుకు అనుమానం:");
                for (String r : v.reasons) b.append("\n• ").append(r);
                b.append("\n\nlink నొక్కకండి, OTP / PIN / CVV ఎవరికీ చెప్పకండి, డబ్బు పంపకండి. బ్యాంక్ ఇలా ఎప్పుడూ అడగదు. మోసపోతే వెంటనే 1930 (సైబర్ క్రైమ్ హెల్ప్‌లైన్) కి కాల్ చేయండి.\n\n")
                        .append(who).append(": ").append(snippet);
                notify(c, key.hashCode(), "⚠️ మోసం అనుమానం · " + app + " · " + who, b.toString(),
                        "ఈ మెసేజ్ మోసమా? జాగ్రత్తగా చెక్ చేసి చెప్పు, ఏం చేయాలో కూడా చెప్పు. మెసేజ్: " + who + ": " + text);
            }
        } catch (Exception ignored) {}
    }

    private static void notify(Context c, int id, String title, String body, String ask) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel("jarvis_guard", "మోసం గార్డ్ హెచ్చరికలు", NotificationManager.IMPORTANCE_HIGH));
        // tapping it: Jarvis explains the message
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_ASK, ask.length() > 1500 ? ask.substring(0, 1500) : ask);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify("guard", id, new Notification.Builder(c, "jarvis_guard")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(body.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }
}
