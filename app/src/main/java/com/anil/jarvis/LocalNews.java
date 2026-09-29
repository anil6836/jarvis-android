package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * News from Anil's own places in Telugu (his states, district towns): Google News in Telugu for each place,
 * newest first, the same story once. Read out when he asks, and three times a day (8 am, 1 pm, 7 pm) the new
 * ones are read out by themselves. The list of places is in Settings (or he tells Jarvis). No AI cost.
 */
final class LocalNews {
    private LocalNews() {}

    static List<String> places(Prefs p) {
        List<String> out = new ArrayList<>();
        for (String s : p.newsPlaces().split("\\s*[,،\\n]\\s*")) if (!s.trim().isEmpty() && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    private static final Pattern ITEM = Pattern.compile("<item>([\\s\\S]*?)</item>");

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>([\\s\\S]*?)</" + name + ">").matcher(xml);
        if (!m.find()) return "";
        return m.group(1).replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /** Headlines about these places from the last day: {place, headline, source, minutes_ago}, newest first. */
    static List<JSONObject> fetch(List<String> places, int perPlace, int max) {
        List<JSONObject> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        SimpleDateFormat in = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
        long now = System.currentTimeMillis();
        for (String place : places) {
            try {
                String xml = Http.getText("https://news.google.com/rss/search?q=" + URLEncoder.encode(place + " when:1d", "UTF-8") + "&hl=te&gl=IN&ceid=IN:te");
                Matcher m = ITEM.matcher(xml);
                int n = 0;
                while (m.find() && n < perPlace) {
                    String it = m.group(1), title = tag(it, "title"), source = tag(it, "source");
                    if (title.isEmpty()) continue;
                    if (!source.isEmpty() && title.endsWith(" - " + source)) title = title.substring(0, title.length() - source.length() - 3);
                    String key = title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue;
                    long ago = 9999;
                    try { ago = (now - in.parse(tag(it, "pubDate")).getTime()) / 60000; } catch (Exception ignored) {}
                    all.add(new JSONObject().put("place", place).put("headline", title).put("source", source).put("minutes_ago", ago));
                    n++;
                }
            } catch (Exception ignored) {}
        }
        all.sort((a, b) -> Long.compare(a.optLong("minutes_ago"), b.optLong("minutes_ago")));
        return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
    }

    static JSONObject forTool(Prefs p, int count) throws Exception {
        List<String> pl = places(p);
        JSONArray a = new JSONArray();
        for (JSONObject o : fetch(pl, Math.max(2, count / Math.max(1, pl.size()) + 2), count)) a.put(o);
        return new JSONObject().put("ok", true).put("places", new JSONArray(pl)).put("headlines", a)
                .put("next", a.length() == 0 ? "No news found for these places today (or no internet)."
                        : "Read them one by one in short Telugu with the place, e.g. 'మిర్యాలగూడ: …'. Then ask if he wants details on one (web search).");
    }

    // ---------------------------------------------------------------- three times a day, by itself

    private static SharedPreferences st(Context c) { return c.getSharedPreferences("jarvis_localnews", Context.MODE_PRIVATE); }

    /** At 8 am, 1 pm and 7 pm (from Proactive's regular check): the new headlines, read out (or only a note when quiet). */
    static void tick(Context c, Prefs p, boolean quiet) {
        if (!p.newsAuto() || places(p).isEmpty()) return;
        Calendar k = Calendar.getInstance();
        int h = k.get(Calendar.HOUR_OF_DAY);
        if (h != 8 && h != 13 && h != 19) return;
        String slot = k.get(Calendar.YEAR) + "-" + k.get(Calendar.DAY_OF_YEAR) + "-" + h;
        SharedPreferences s = st(c);
        if (slot.equals(s.getString("slot", ""))) return;
        s.edit().putString("slot", slot).apply();
        Set<String> said = new HashSet<>(s.getStringSet("said", new HashSet<>()));
        List<JSONObject> fresh = new ArrayList<>();
        for (JSONObject o : fetch(places(p), 3, 20)) {
            String key = o.optString("headline").replaceAll("\\s+", "");
            if (said.contains(key) || o.optLong("minutes_ago") > 12 * 60) continue;
            fresh.add(o);
            said.add(key);
            if (fresh.size() == 5) break;
        }
        if (said.size() > 400) said.clear(); // keep the memory small; old stories are not in the feed anyway
        s.edit().putStringSet("said", said).apply();
        if (fresh.isEmpty()) return;
        StringBuilder text = new StringBuilder(), voice = new StringBuilder(p.name() + ", మీ ప్రాంతాల కొత్త వార్తలు. ");
        for (int i = 0; i < fresh.size(); i++) {
            JSONObject o = fresh.get(i);
            text.append(i == 0 ? "" : "\n").append("• ").append(o.optString("place")).append(": ").append(o.optString("headline"));
            voice.append(o.optString("place")).append(": ").append(o.optString("headline")).append(". ");
        }
        notify(c, "📰 మీ ప్రాంతాల వార్తలు (" + fresh.size() + ")", text.toString());
        if (!quiet) Announcer.say(c, voice.toString());
    }

    private static void notify(Context c, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_news", "లోకల్ వార్తలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 83, new Intent(c, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_ASK, "నా ప్రాంతాల తాజా వార్తలు చదివి వినిపించు (local_news).")
                            .putExtra(MainActivity.EXTRA_LABEL, "📰 లోకల్ వార్తలు").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(83, new Notification.Builder(c, "jarvis_news").setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
