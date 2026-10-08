package com.anil.jarvis.watch;

import android.content.ComponentName;
import android.content.Context;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The short texts Jarvis gives to watch faces (W1: the Jarvis face's duty and weather; any face can use them):
 * time to the next duty, and the weather, from the phone's news (sent every half hour).
 */
final class Complications {
    private Complications() {}

    /** The phone's news changed: the faces ask again. */
    static void update(Context c) {
        try {
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, DutySource.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, WeatherSource.class)).requestUpdateAll();
        } catch (Exception ignored) {}
    }

    /** {text, title} for the next duty: "5:20" (hours:minutes to go), "2రో" (days), "లో" while on duty; null: not known. */
    static String[] duty(Context c, long now) { return duty(Link.info(c), now); }

    static String[] duty(JSONObject info, long now) {
        JSONObject d = info.optJSONObject("duty");
        if (d == null) return null;
        long at = d.optLong("at", 0), end = d.optLong("end", 0);
        if (d.optBoolean("on") && (at == 0 || now < at) || at > 0 && at <= now && now < end) return new String[]{"ఇప్పుడు", "డ్యూటీ"};
        if (at <= now) return null;
        long min = (at - now) / 60_000L;
        if (min < 24 * 60) return new String[]{String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60), "డ్యూటీ"};
        return new String[]{(min / (24 * 60)) + "రో", "డ్యూటీ"};
    }

    /** {text, title}: "28°" and the sky in a word (or the rain chance); null when there is no recent weather. */
    static String[] weather(Context c, long now) { return weather(Link.info(c), now); }

    static String[] weather(JSONObject info, long now) {
        JSONObject w = info.optJSONObject("weather");
        if (w == null || now - w.optLong("at", 0) > 3 * 3600_000L) return null;
        int code = w.optInt("code"), rain = w.optInt("rain");
        String sky = code == 0 ? "ఎండ" : code <= 3 ? "మేఘం" : code == 45 || code == 48 ? "మంచు" : code >= 95 ? "ఉరుము"
                : code >= 71 && code <= 77 ? "మంచు" : "వాన";
        return new String[]{w.optInt("t") + "°", rain >= 40 ? "వాన " + rain + "%" : sky};
    }
}
