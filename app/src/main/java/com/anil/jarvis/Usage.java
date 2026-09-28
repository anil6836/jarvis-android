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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The API cost meter: every Gemini, OpenAI and Claude answer reports how many tokens it used; Jarvis
 * prices them (list prices, September 2026) and keeps a monthly total per company. If Anil enters his
 * balance (Gemini credit in rupees, OpenAI / Anthropic in dollars), Jarvis counts it down and warns at
 * 20% and 5% left. These are estimates: the exact figures are on each company's billing page.
 */
final class Usage {
    private Usage() {}

    static final String GEMINI = Prefs.GEMINI, OPENAI = Prefs.OPENAI, ANTHROPIC = Prefs.ANTHROPIC;
    static final String[] PROVIDERS = {GEMINI, OPENAI, ANTHROPIC};
    /** Rupees per dollar, roughly (Google bills the Gemini credit in rupees). */
    static final float INR = 88f;
    private static final double WEB_SEARCH = 0.01;      // $10 per 1,000 searches (OpenAI, Anthropic)
    private static final double TTS_PER_MIN = 0.015;    // gpt-4o-mini-tts, about

    private static volatile Context app;

    static void init(Context c) { if (app == null && c != null) app = c.getApplicationContext(); }

    private static SharedPreferences sp() { return app == null ? null : app.getSharedPreferences("jarvis_usage", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- prices (USD per million tokens: input, cached input, output)

    private static final Object[][] PRICES = {
            // Gemini (3.6-3.8 Flash at the discounted price until 31 Dec 2026)
            {"gemini-3.1-pro", 2.00, 0.20, 12.00}, {"gemini-3.5-flash-lite", 0.30, 0.03, 2.50}, {"gemini-3.1-flash-lite", 0.25, 0.025, 1.50},
            {"gemini-3.8-flash", 0.75, 0.075, 3.75}, {"gemini-3.7-flash", 0.75, 0.075, 3.75}, {"gemini-3.6-flash", 0.75, 0.075, 3.75},
            {"gemini-3.5-flash", 1.50, 0.15, 9.00}, {"gemini-3-flash", 0.50, 0.05, 3.00}, {"gemini-2.5-pro", 1.25, 0.125, 10.00},
            {"gemini-2.5-flash-lite", 0.10, 0.01, 0.40}, {"gemini-2.5-flash", 0.30, 0.03, 2.50}, {"gemini", 0.50, 0.05, 3.00},
            // OpenAI
            {"gpt-6-astra", 10.00, 1.00, 50.00}, {"gpt-6-sol", 2.00, 0.20, 10.00}, {"gpt-6-luna", 0.10, 0.01, 0.50},
            {"gpt-4o-mini-transcribe", 1.25, 1.25, 5.00}, {"gpt-", 2.00, 0.20, 10.00}, {"o", 2.00, 0.20, 10.00},
            // Anthropic
            {"claude-fable-5-1", 10.00, 0.25, 50.00}, {"claude-fable", 10.00, 1.00, 50.00}, {"claude-opus-5-5", 4.00, 0.20, 20.00},
            {"claude-opus", 5.00, 0.50, 25.00}, {"claude-sonnet-5", 2.00, 0.20, 10.00}, {"claude-sonnet", 3.00, 0.30, 15.00},
            {"claude-haiku", 1.00, 0.10, 5.00}, {"claude", 3.00, 0.30, 15.00},
    };

    private static double[] price(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT).replaceFirst("^models/", "");
        for (Object[] p : PRICES) if (m.startsWith((String) p[0])) return new double[]{(Double) p[1], (Double) p[2], (Double) p[3]};
        return new double[]{2.00, 0.20, 10.00};
    }

    // ---------------------------------------------------------------- what the APIs report

    /** Called by Http for every successful JSON answer. */
    static void fromResponse(String url, JSONObject res) {
        if (url == null || res == null || app == null) return;
        try {
            if (url.contains("generativelanguage.googleapis.com") && url.contains(":generateContent")) {
                JSONObject u = res.optJSONObject("usageMetadata");
                if (u == null) return;
                String model = res.optString("modelVersion", "");
                if (model.isEmpty()) {
                    int a = url.indexOf("models/"), b = url.indexOf(':', a + 7);
                    model = a >= 0 && b > a ? url.substring(a + 7, b) : "gemini";
                }
                long cached = u.optLong("cachedContentTokenCount");
                long in = u.optLong("promptTokenCount") - cached + u.optLong("toolUsePromptTokenCount");
                long out = u.optLong("candidatesTokenCount") + u.optLong("thoughtsTokenCount");
                add(GEMINI, model, in, cached, out, 0);
            } else if (url.contains("api.openai.com/v1/responses")) {
                JSONObject u = res.optJSONObject("usage");
                if (u == null) return;
                JSONObject d = u.optJSONObject("input_tokens_details");
                long cached = d == null ? 0 : d.optLong("cached_tokens");
                int searches = 0;
                JSONArray out = res.optJSONArray("output");
                for (int i = 0; out != null && i < out.length(); i++) {
                    JSONObject o = out.optJSONObject(i);
                    if (o != null && "web_search_call".equals(o.optString("type"))) searches++;
                }
                add(OPENAI, res.optString("model"), u.optLong("input_tokens") - cached, cached, u.optLong("output_tokens"), searches * WEB_SEARCH);
            } else if (url.contains("api.openai.com/v1/chat/completions")) {
                JSONObject u = res.optJSONObject("usage");
                if (u == null) return;
                add(OPENAI, res.optString("model"), u.optLong("prompt_tokens"), 0, u.optLong("completion_tokens"), 0);
            } else if (url.contains("api.anthropic.com/v1/messages")) {
                JSONObject u = res.optJSONObject("usage");
                if (u == null) return;
                String model = res.optString("model");
                long cached = u.optLong("cache_read_input_tokens");
                // cache writes cost 1.25x input: count them as a quarter more input tokens
                long in = u.optLong("input_tokens") + Math.round(u.optLong("cache_creation_input_tokens") * 1.25);
                JSONObject st = u.optJSONObject("server_tool_use");
                int searches = st == null ? 0 : st.optInt("web_search_requests");
                add(ANTHROPIC, model, in, cached, u.optLong("output_tokens"), searches * WEB_SEARCH);
            }
        } catch (Exception ignored) {}
    }

    /** A Live (realtime) reply: text and audio tokens are priced differently. */
    static void realtime(String model, JSONObject u) {
        if (u == null || app == null) return;
        try {
            boolean mini = model != null && model.contains("mini");
            // text in / cached / out, audio in / cached / out (USD per million)
            double[] t = mini ? new double[]{0.60, 0.06, 2.40} : new double[]{4.00, 0.40, 24.00};
            double[] a = mini ? new double[]{10.00, 0.30, 20.00} : new double[]{32.00, 0.40, 64.00};
            JSONObject in = u.optJSONObject("input_token_details"), out = u.optJSONObject("output_token_details");
            JSONObject cd = in == null ? null : in.optJSONObject("cached_tokens_details");
            long tIn = in == null ? u.optLong("input_tokens") : in.optLong("text_tokens") + in.optLong("image_tokens");
            long aIn = in == null ? 0 : in.optLong("audio_tokens");
            long cT = cd == null ? 0 : cd.optLong("text_tokens"), cA = cd == null ? 0 : cd.optLong("audio_tokens");
            long tOut = out == null ? u.optLong("output_tokens") : out.optLong("text_tokens");
            long aOut = out == null ? 0 : out.optLong("audio_tokens");
            double usd = ((tIn - cT) * t[0] + cT * t[1] + tOut * t[2] + (aIn - cA) * a[0] + cA * a[1] + aOut * a[2]) / 1e6;
            record(OPENAI, u.optLong("input_tokens"), u.optLong("output_tokens"), usd);
        } catch (Exception ignored) {}
    }

    /** Live mode's transcription of his words. */
    static void transcription(JSONObject u) {
        if (u == null || app == null || !"tokens".equals(u.optString("type", "tokens"))) return;
        add(OPENAI, "gpt-4o-mini-transcribe", u.optLong("input_tokens"), 0, u.optLong("output_tokens"), 0);
    }

    /** The natural voice (OpenAI text-to-speech): priced by the minute of audio (24 kHz frames). */
    static void tts(long frames) {
        if (frames <= 0 || app == null) return;
        record(OPENAI, 0, 0, frames / 24000.0 / 60.0 * TTS_PER_MIN);
    }

    private static void add(String provider, String model, long in, long cached, long out, double extraUsd) {
        double[] p = price(model);
        double usd = (Math.max(0, in) * p[0] + Math.max(0, cached) * p[1] + Math.max(0, out) * p[2]) / 1e6 + extraUsd;
        record(provider, in + cached, out, usd);
    }

    // ---------------------------------------------------------------- the ledger

    private static String month() { return new SimpleDateFormat("yyyy-MM", Locale.ENGLISH).format(new Date()); }

    private static synchronized void record(String p, long in, long out, double usd) {
        SharedPreferences sp = sp();
        if (sp == null) return;
        SharedPreferences.Editor e = sp.edit();
        String m = month();
        if (!m.equals(sp.getString("month", ""))) { // a new month: the monthly totals start again
            e.putString("month", m);
            for (String q : PROVIDERS) e.putFloat("m_usd_" + q, 0).putLong("m_in_" + q, 0).putLong("m_out_" + q, 0).putInt("m_calls_" + q, 0);
            e.apply();
            e = sp.edit();
        }
        e.putFloat("m_usd_" + p, sp.getFloat("m_usd_" + p, 0) + (float) usd)
                .putLong("m_in_" + p, sp.getLong("m_in_" + p, 0) + in)
                .putLong("m_out_" + p, sp.getLong("m_out_" + p, 0) + out)
                .putInt("m_calls_" + p, sp.getInt("m_calls_" + p, 0) + 1)
                .putFloat("total_" + p, sp.getFloat("total_" + p, 0) + (float) usd)
                .apply();
        warnIfLow(p);
    }

    static String currency(String p) { return GEMINI.equals(p) ? "₹" : "$"; }

    static String name(String p) { return GEMINI.equals(p) ? "Gemini" : ANTHROPIC.equals(p) ? "Claude (Anthropic)" : "OpenAI"; }

    /** His balance in that company's currency (₹ for Gemini, $ for the others), or 0 when not set. */
    static float balance(String p) { SharedPreferences sp = sp(); return sp == null ? 0 : sp.getFloat("bal_" + p, 0); }

    /** He entered a new balance (0 = stop tracking): count down from here. */
    static synchronized void setBalance(Context c, String p, float amount) {
        init(c);
        SharedPreferences sp = sp();
        if (sp == null) return;
        sp.edit().putFloat("bal_" + p, Math.max(0, amount)).putFloat("bal_base_" + p, sp.getFloat("total_" + p, 0))
                .putInt("warned_" + p, 0).apply();
    }

    /** What is left of his balance, in its currency (or -1 when no balance is set). */
    static float remaining(String p) {
        SharedPreferences sp = sp();
        if (sp == null) return -1;
        float bal = sp.getFloat("bal_" + p, 0);
        if (bal <= 0) return -1;
        float spentUsd = sp.getFloat("total_" + p, 0) - sp.getFloat("bal_base_" + p, 0);
        return bal - spentUsd * (GEMINI.equals(p) ? INR : 1f);
    }

    private static void warnIfLow(String p) {
        SharedPreferences sp = sp();
        float bal = sp.getFloat("bal_" + p, 0);
        if (bal <= 0) return;
        float left = remaining(p), pct = left / bal;
        int warned = sp.getInt("warned_" + p, 0);
        int level = pct <= 0.05f ? 2 : pct <= 0.20f ? 1 : 0;
        if (level <= warned) return;
        sp.edit().putInt("warned_" + p, level).apply();
        String amount = money(p, Math.max(0, left));
        String where = GEMINI.equals(p) ? "AI Studio → Billing" : ANTHROPIC.equals(p) ? "console.anthropic.com → Billing" : "platform.openai.com → Billing";
        notify(app, 7300 + java.util.Arrays.asList(PROVIDERS).indexOf(p),
                (level == 2 ? "⚠️ " : "💰 ") + name(p) + " balance సుమారు " + amount + " మాత్రమే మిగిలింది",
                "Jarvis లెక్క ప్రకారం (సుమారు). అయిపోకముందే " + where + " లో credit జోడించండి, లేదా సెట్టింగ్స్‌లో వేరే మెదడు ఎంచుకోండి.");
    }

    static String money(String p, double amount) {
        return GEMINI.equals(p) ? String.format(Locale.ENGLISH, "₹%.0f", amount) : String.format(Locale.ENGLISH, "$%.2f", amount);
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_money", "API ఖర్చు హెచ్చరికలు", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(id, new Notification.Builder(c, "jarvis_money").setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- reports

    /** One line per company for the Settings card. */
    static String line(String p) {
        SharedPreferences sp = sp();
        if (sp == null) return name(p) + ": —";
        boolean thisMonth = month().equals(sp.getString("month", ""));
        float usd = thisMonth ? sp.getFloat("m_usd_" + p, 0) : 0;
        int calls = thisMonth ? sp.getInt("m_calls_" + p, 0) : 0;
        long tokens = thisMonth ? sp.getLong("m_in_" + p, 0) + sp.getLong("m_out_" + p, 0) : 0;
        String s = name(p) + ": ఈ నెల సుమారు " + String.format(Locale.ENGLISH, "$%.2f (≈ ₹%.0f)", usd, usd * INR)
                + " · " + calls + " requests · " + tokensText(tokens);
        float left = remaining(p);
        if (left >= 0) s += "\nమిగిలింది సుమారు " + money(p, Math.max(0, left)) + " / " + money(p, balance(p));
        return s;
    }

    /** Colour for a company's line: green, amber when under 20%, red when under 5% (cyan without a balance). */
    static int color(String p) {
        float bal = balance(p), left = remaining(p);
        if (bal <= 0 || left < 0) return Ui.C_CYAN;
        float pct = left / bal;
        return pct <= 0.05f ? 0xFFF43F5E : pct <= 0.20f ? Ui.C_AMBER : Ui.C_GREEN;
    }

    private static String tokensText(long t) {
        if (t >= 1_000_000) return String.format(Locale.ENGLISH, "%.1fM tokens", t / 1e6);
        if (t >= 1000) return String.format(Locale.ENGLISH, "%.0fK tokens", t / 1e3);
        return t + " tokens";
    }

    /** For the api_usage tool: this month's spend per company and what is left. */
    static JSONObject summary() throws Exception {
        JSONObject o = new JSONObject().put("month", month());
        JSONArray a = new JSONArray();
        SharedPreferences sp = sp();
        for (String p : PROVIDERS) {
            boolean thisMonth = sp != null && month().equals(sp.getString("month", ""));
            float usd = thisMonth ? sp.getFloat("m_usd_" + p, 0) : 0;
            JSONObject r = new JSONObject().put("company", name(p))
                    .put("spent_this_month_usd", Math.round(usd * 100) / 100.0)
                    .put("spent_this_month_inr", Math.round(usd * INR))
                    .put("requests", thisMonth ? sp.getInt("m_calls_" + p, 0) : 0);
            float left = remaining(p);
            if (left >= 0) r.put("balance_entered", money(p, balance(p))).put("remaining_estimate", money(p, Math.max(0, left)));
            a.put(r);
        }
        return o.put("companies", a).put("note", "Estimates from token counts and list prices; exact figures are on each company's billing page. "
                + "Live mode and the natural voice use OpenAI.");
    }
}
