package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Phase 5 without internet, on the phone itself: who called (O20), a contact's number (O21), a voice recording (O22), a
 * WhatsApp message kept until the internet is back (O23: read back first, sent only after his "పంపు", and then WhatsApp
 * opens with it written for his last tap), and his saved pages read aloud (O31).
 */
final class PhoneOffline {
    private PhoneOffline() {}

    static final int NOTE_LATER = 269;

    /** The message read back, waiting for his "పంపు": {name, number, text}. */
    private static volatile String[] pending;

    /** What he said, handled here, or null. said: his words; t: lower case; last: what Jarvis said just before. */
    static String handle(Context c, String said, String t, String last) {
        String[] p = pending;
        pending = null;
        if (p != null && last.endsWith("పంపమంటారా?") && last.contains(p[2])) {
            if (yes(t)) { addLater(c, p); return "సరే, నెట్ రాగానే WhatsApp లో " + p[0] + " కి ఈ మెసేజ్ రాసి తెరుస్తాను; అక్కడ పంపు నొక్కండి."; }
            if (no(t)) return "సరే, పెట్టలేదు.";
        }
        String bare = t.replaceAll("[?.!,]+", " ").replaceAll("\\s+", " ").trim();
        // O23: "నెట్ వచ్చాక అమ్మకి 'చేరుకున్నాను' అని WhatsApp పంపు"
        if (bare.matches("(?s).*(నెట్ వచ్చాక|నెట్ వస్తే|నెట్ వచ్చిన తర్వాత|ఇంటర్నెట్ వచ్చాక|ఆన్‌లైన్ అయ్యాక|ఆన్లైన్ అయ్యాక).*")) {
            String s = said.replaceAll("(నెట్ వచ్చాక|నెట్ వస్తే|నెట్ వచ్చిన తర్వాత|ఇంటర్నెట్ వచ్చాక|ఆన్‌లైన్ అయ్యాక|ఆన్లైన్ అయ్యాక)", " ").trim();
            String[] sms = Offline.sms(s);
            if (sms != null && !sms[0].isEmpty() && !sms[1].isEmpty()) {
                String[] who = Sos.number(c, sms[0]);
                if (who == null) return sms[0] + " పేరుతో కాంటాక్ట్ దొరకలేదు.";
                pending = new String[]{who[0], who[1], sms[1]};
                return "నెట్ వచ్చాక " + who[0] + " కి WhatsApp మెసేజ్: \"" + sms[1] + "\". పంపమంటారా?";
            }
        }
        // O20: who called
        if (bare.matches("(?s).*(మిస్డ్ కాల్|మిస్డ్ కాల్స్|missed call).*|.*ఎవరు\\s*(ఫోన్|కాల్)\\s*(చేశారు|చేసారు|చేసింది|చేశాడు).*|.*ఎవరెవరు\\s*(ఫోన్|కాల్).*"))
            return missed(c);
        // O21: a contact's number
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*(గారి|గారు)?\\s*(ఫోన్\\s*)?(నంబర్|నెంబర్|నెంబరు|నంబరు|number)\\s*(ఏంటి|ఏమిటి|చెప్పు|చెప్పండి|ఎంత|కావాలి|ఇవ్వు)?$").matcher(bare);
        if (m.find() && !bare.matches("(?s).*(సేవ్|save|పాలసీ|అకౌంట్|ఆధార్|కార్డ్|pan|పాన్|బండి|వెహికల్|బైక్).*")) {
            String name = m.group(1).replaceAll("(నా|మా)\\s+", "").trim();
            if (!name.isEmpty() && name.split("\\s+").length <= 4) {
                String[] who = Sos.number(c, name);
                if (who == null) return name + " పేరుతో కాంటాక్ట్ దొరకలేదు.";
                return who[0] + " నంబర్: " + spoken(who[1]) + ".";
            }
        }
        // O22: a voice recording
        if (bare.matches("(?s).*(వాయిస్\\s*నోట్|రికార్డింగ్|రికార్డ్|record).*") && bare.matches("(?s).*(చెయ్|చేయి|మొదలు|స్టార్ట్|start|పెట్టు).*") && !bare.matches("(?s).*(ఆపు|stop|సారాంశం|ఏమున్నాయి).*")) {
            if (RecorderService.recording) return "ఇప్పటికే రికార్డ్ అవుతోంది. ఆపాలంటే \"రికార్డింగ్ ఆపు\" అనండి.";
            return RecorderService.start(c, "meeting", "వాయిస్ నోట్") ? "రికార్డ్ చేస్తున్నాను. ఆపాలంటే \"రికార్డింగ్ ఆపు\" అనండి. నెట్ వచ్చాక తెలుగులో రాసి సారాంశం ఇస్తాను."
                    : "రికార్డింగ్ మొదలవలేదు (మైక్ అనుమతి చూడండి).";
        }
        if (bare.matches("(?s).*(రికార్డింగ్|రికార్డ్|వాయిస్ నోట్|record).*(ఆపు|ఆపేయ్|ఆపండి|stop).*")) {
            if (!RecorderService.recording) return "ఇప్పుడు ఏమీ రికార్డ్ అవడం లేదు.";
            RecorderService.stop(c);
            return "రికార్డింగ్ ఆపాను, ఫోన్‌లో దాచాను. నెట్ వచ్చాక \"రికార్డింగ్ సారాంశం\" అంటే తెలుగులో రాసి చెబుతాను.";
        }
        // O31: saved pages
        if (bare.matches("(?s).*(సేవ్ చేసిన|సేవ్ చేసినవి|తర్వాత చదువు|saved page|read later).*")) {
            List<String> titles = ReadLater.titles(c);
            if (titles.isEmpty()) return "తర్వాత చదవడానికి సేవ్ చేసినవి ఏమీ లేవు.";
            if (bare.matches("(?s).*(ఏమున్నాయి|ఏవి|లిస్ట్|list|ఎన్ని).*")) {
                StringBuilder b = new StringBuilder(titles.size() + " ఉన్నాయి: ");
                for (int i = 0; i < titles.size() && i < 6; i++) b.append(i + 1).append(". ").append(titles.get(i)).append(". ");
                return b.append("\"2వది చదువు\" అనండి.").toString();
            }
            if (bare.matches("(?s).*(చదువు|చదివి|వినిపించు|read).*")) {
                java.util.regex.Matcher n = java.util.regex.Pattern.compile("(\\d{1,2})").matcher(bare);
                JSONObject e = ReadLater.find(c, n.find() ? n.group(1) : "1");
                if (e == null) return "అది దొరకలేదు.";
                String text = ReadLater.text(c, e.optString("id"));
                if (text.trim().isEmpty()) return "ఆ పేజీలో రాత లేదు.";
                ScreenReader.get(c).read(e.optString("title"), text);
                return "\"" + e.optString("title") + "\" చదువుతున్నాను.";
            }
        }
        return null;
    }

    private static boolean yes(String t) {
        return CardTalk.Words.kind(t, CardTalk.Words.CONFIRM) == CardTalk.Words.YES || t.matches("(?s).*(పంపు|పంపండి|send|సరే|అవును|ok).*");
    }

    private static boolean no(String t) {
        return CardTalk.Words.kind(t, CardTalk.Words.CONFIRM) == CardTalk.Words.NO || t.matches("(?s).*(వద్దు|క్యాన్సిల్|cancel).*");
    }

    /** "9848012345" -> "98480 12345" (said in two parts). */
    static String spoken(String number) {
        String d = number.replaceAll("[^0-9+]", "");
        if (d.startsWith("+91")) d = d.substring(3);
        if (d.length() == 10) return d.substring(0, 5) + " " + d.substring(5);
        return d;
    }

    /** O20: missed calls of the last day (names as saved). */
    static String missed(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "కాల్ లిస్ట్ చూడటానికి అనుమతి లేదు.";
        long since = System.currentTimeMillis() - 24 * 3600_000L;
        java.util.LinkedHashMap<String, Integer> who = new java.util.LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?", new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(since)},
                CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0);
                if (n == null || n.trim().isEmpty()) n = spoken(cur.getString(1) == null ? "తెలియని నంబర్" : cur.getString(1));
                who.merge(n, 1, Integer::sum);
            }
        } catch (Exception e) {
            return "కాల్ లిస్ట్ చూడలేకపోయాను.";
        }
        if (who.isEmpty()) return "గత 24 గంటల్లో మిస్డ్ కాల్స్ లేవు.";
        StringBuilder b = new StringBuilder("మిస్డ్ కాల్స్: ");
        int i = 0;
        for (java.util.Map.Entry<String, Integer> e : who.entrySet()) {
            if (i++ >= 6) break;
            b.append(e.getKey()).append(e.getValue() > 1 ? " (" + e.getValue() + " సార్లు)" : "").append(", ");
        }
        return b.substring(0, b.length() - 2) + ".";
    }

    // ---------------------------------------------------------------- O23: kept until the internet is back

    private static void addLater(Context c, String[] p) {
        try { Notes.add(c, "later_send", new JSONObject().put("name", p[0]).put("number", p[1]).put("text", p[2]).put("t", System.currentTimeMillis()), 10); }
        catch (Exception ignored) {}
    }

    /** The internet is back (JarvisApp): each kept message as a card that opens WhatsApp with it written (his tap sends it). */
    static void netBack(Context c) {
        List<JSONObject> l = Notes.list(c, "later_send");
        if (l.isEmpty()) return;
        int id = NOTE_LATER;
        for (JSONObject o : l) {
            if (System.currentTimeMillis() - o.optLong("t") > 3 * 24 * 3600_000L) continue;
            String num = o.optString("number").replaceAll("[^0-9]", "");
            if (num.length() == 10) num = "91" + num;
            Intent wa = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(o.optString("text")))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel("jarvis_later", "నెట్ వచ్చాక పంపాల్సినవి", NotificationManager.IMPORTANCE_HIGH));
                PendingIntent pi = PendingIntent.getActivity(c, id, wa, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(id++, new Notification.Builder(c, "jarvis_later").setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle("📤 నెట్ వచ్చింది: " + o.optString("name") + " కి WhatsApp")
                        .setContentText("\"" + o.optString("text") + "\" — నొక్కితే WhatsApp లో రాసి ఉంటుంది, అక్కడ పంపు నొక్కండి")
                        .setStyle(new Notification.BigTextStyle().bigText("\"" + o.optString("text") + "\"\nనొక్కితే WhatsApp లో రాసి ఉంటుంది, అక్కడ పంపు నొక్కండి."))
                        .setContentIntent(pi).setAutoCancel(true).build());
            } catch (Exception ignored) {}
        }
        Notes.save(c, "later_send", new java.util.ArrayList<>(), 10);
    }
}
