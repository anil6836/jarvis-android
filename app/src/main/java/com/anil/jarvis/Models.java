package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every model each AI company offers to his key, fetched live from the company, so he chooses the brain
 * himself (Jarvis never picks one on its own), and plain words for "limit / balance used up" errors.
 */
final class Models {
    private Models() {}

    /** OpenAI's live voice (Realtime) models, for Live mode. */
    static final String REALTIME = "openai_realtime";

    /** Thrown by the brain when Gemini is chosen but no Gemini model is. */
    static final String NO_GEMINI_MODEL = "No Gemini model chosen";

    static final class Item {
        final String provider, id, name;
        final double rank; // bigger = shown first
        Item(String provider, String id, String name, double rank) { this.provider = provider; this.id = id; this.name = name; this.rank = rank; }
    }

    static String company(String provider) {
        return Prefs.GEMINI.equals(provider) ? "Google Gemini" : Prefs.ANTHROPIC.equals(provider) ? "Anthropic Claude"
                : REALTIME.equals(provider) ? "OpenAI Live" : "OpenAI";
    }

    // ---------------------------------------------------------------- the live lists

    /** The models this key can use as Jarvis's brain (text + tools), newest first. */
    static List<Item> list(String provider, String key) throws Exception {
        List<Item> out = new ArrayList<>();
        if (Prefs.GEMINI.equals(provider)) gemini(key, out);
        else if (Prefs.ANTHROPIC.equals(provider)) anthropic(key, out);
        else openAi(key, out, REALTIME.equals(provider));
        out.sort((a, b) -> a.rank != b.rank ? Double.compare(b.rank, a.rank) : a.id.compareTo(b.id));
        return out;
    }

    private static void openAi(String key, List<Item> out, boolean live) throws Exception {
        JSONObject res = Http.get("https://api.openai.com/v1/models", "Authorization", "Bearer " + key);
        JSONArray data = res.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String id = o.optString("id");
            String low = id.toLowerCase(Locale.ROOT);
            if (live) { // the voice-to-voice models Live mode talks to
                if (low.contains("realtime") && !low.contains("transcri")) out.add(new Item(REALTIME, id, "", o.optLong("created")));
                continue;
            }
            boolean chat = low.startsWith("gpt-") || low.startsWith("chatgpt-") || low.startsWith("codex-") || low.matches("^o\\d.*");
            // speech, pictures, embeddings, live audio and the like cannot be the brain
            if (!chat || has(low, "embed", "whisper", "tts", "dall-e", "moderation", "image", "audio", "realtime",
                    "transcribe", "search", "instruct", "computer-use", "sora", "babbage", "davinci")) continue;
            out.add(new Item(Prefs.OPENAI, id, "", o.optLong("created")));
        }
    }

    private static void anthropic(String key, List<Item> out) throws Exception {
        String after = null;
        for (int page = 0; page < 10; page++) {
            String url = "https://api.anthropic.com/v1/models?limit=1000" + (after == null ? "" : "&after_id=" + URLEncoder.encode(after, "UTF-8"));
            JSONObject res = Http.get(url, "x-api-key", key, "anthropic-version", "2023-06-01");
            JSONArray data = res.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                // the list comes newest first: keep that order
                out.add(new Item(Prefs.ANTHROPIC, o.optString("id"), o.optString("display_name"), -(out.size())));
            }
            if (!res.optBoolean("has_more") || res.optString("last_id").isEmpty()) break;
            after = res.optString("last_id");
        }
    }

    private static final Pattern GEM_VERSION = Pattern.compile("^gemini-(\\d+(?:\\.\\d+)?)");

    private static void gemini(String key, List<Item> out) throws Exception {
        String token = null;
        for (int page = 0; page < 10; page++) {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
                    + (token == null ? "" : "&pageToken=" + URLEncoder.encode(token, "UTF-8"));
            JSONObject res = Http.get(url, "x-goog-api-key", key);
            JSONArray list = res.optJSONArray("models");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                JSONArray ways = o.optJSONArray("supportedGenerationMethods");
                if (ways == null || !ways.toString().contains("\"generateContent\"")) continue;
                String id = o.optString("name").replaceFirst("^models/", "");
                String low = id.toLowerCase(Locale.ROOT);
                if (!low.startsWith("gemini") || has(low, "image", "tts", "live", "audio", "embed", "robotics", "computer-use",
                        "transcribe", "translate", "aqa")) continue;
                double rank = 0;
                Matcher m = GEM_VERSION.matcher(low);
                if (m.find()) try { rank = Double.parseDouble(m.group(1)) * 100; } catch (Exception ignored) {}
                if (low.contains("preview") || low.contains("exp")) rank -= 1; // a finished model before its preview
                out.add(new Item(Prefs.GEMINI, id, o.optString("displayName"), rank));
            }
            token = res.optString("nextPageToken", "");
            if (token.isEmpty()) break;
        }
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the picker

    interface Picked { void on(Item it); }

    /**
     * Fetches the lists (one company, or every company he has a key for) in the background and shows
     * them with a search box; tapping one hands it back.
     */
    static void pick(Activity a, String title, String[] providers, String[] keys, String current, Picked done) {
        List<String> tried = new ArrayList<>();
        for (int i = 0; i < providers.length; i++) if (!keys[i].trim().isEmpty()) tried.add(providers[i]);
        if (tried.isEmpty()) {
            android.widget.Toast.makeText(a, providers.length == 1
                    ? "ముందు పైన " + company(providers[0]) + " API key పెట్టండి"
                    : "ముందు పైన కనీసం ఒక API key పెట్టండి", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.Toast.makeText(a, "మోడల్స్ లిస్ట్ తెస్తున్నాను…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Item> all = new ArrayList<>();
            StringBuilder problems = new StringBuilder();
            for (int i = 0; i < providers.length; i++) {
                String k = keys[i].trim();
                if (k.isEmpty()) continue;
                try {
                    all.addAll(list(providers[i], k));
                } catch (Exception e) {
                    problems.append(company(providers[i])).append(": ").append(why(e)).append("\n");
                }
            }
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (all.isEmpty()) {
                    new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("మోడల్స్ లిస్ట్ రాలేదు")
                            .setMessage(problems.length() > 0 ? problems.toString().trim() : "ఈ key కి మోడల్స్ ఏవీ కనిపించలేదు.")
                            .setPositiveButton("సరే", null).show();
                    return;
                }
                show(a, title, all, problems.toString().trim(), current, providers.length > 1, done);
            });
        }, "jarvis-models").start();
    }

    private static String why(Exception e) {
        if (e instanceof Http.ApiError) {
            Http.ApiError x = (Http.ApiError) e;
            if (x.status == 401 || x.status == 403 || x.status == 400) return "key పనిచేయడం లేదు (" + x.status + "). key సరిగ్గా పెట్టారో చూడండి.";
            return "(" + x.status + ") " + x.getMessage();
        }
        if (e instanceof java.net.UnknownHostException) return "ఇంటర్నెట్ లేదు.";
        return String.valueOf(e.getMessage());
    }

    private static void show(Activity a, String title, List<Item> all, String problems, String current, boolean withCompany, Picked done) {
        int pad = Ui.dp(a, 16);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.PANEL);
        box.setPadding(pad, pad, pad, Ui.dp(a, 6));

        TextView head = Ui.text(a, title, 18, Ui.TEXT);
        box.addView(head);
        TextView info = Ui.text(a, all.size() + " మోడల్స్ · మీ key కి కంపెనీ ఇచ్చిన లిస్ట్. ఒకటి నొక్కితే అదే వాడతాను."
                + (problems.isEmpty() ? "" : "\n⚠ " + problems), 13, Ui.MUTED);
        info.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
        box.addView(info);

        EditText search = new EditText(a);
        search.setHint("వెతకండి (ఉదా: flash, pro, mini, opus)");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.FAINT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Ui.round(a, Ui.DEEP, Ui.LINE2, 10));
        int sp = Ui.dp(a, 10);
        search.setPadding(sp, sp, sp, sp);
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));

        List<Item> shown = new ArrayList<>(all);
        String cur = current == null ? "" : current.trim();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View reuse, ViewGroup parent) {
                TextView t = reuse instanceof TextView ? (TextView) reuse : Ui.text(a, "", 15.5f, Ui.TEXT);
                t.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10));
                Item it = shown.get(p);
                boolean now = it.id.equals(cur);
                SpannableStringBuilder s = new SpannableStringBuilder();
                s.append(now ? "✓ " : "").append(it.id);
                s.setSpan(new ForegroundColorSpan(now ? Ui.CYAN : Ui.TEXT), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                String sub = (withCompany ? company(it.provider) : "") + (it.name.isEmpty() || it.name.equals(it.id) ? "" : (withCompany ? " · " : "") + it.name)
                        + (now ? (withCompany || !it.name.isEmpty() ? " · " : "") + "ఇప్పుడు ఇదే" : "");
                if (!sub.isEmpty()) {
                    int at = s.length();
                    s.append("\n").append(sub);
                    s.setSpan(new ForegroundColorSpan(Ui.MUTED), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new RelativeSizeSpan(0.82f), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                t.setText(s);
                return t;
            }
        };
        ListView lv = new ListView(a);
        lv.setAdapter(adapter);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(Ui.LINE));
        lv.setDividerHeight(Ui.dp(a, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f));
        lp.topMargin = Ui.dp(a, 8);
        box.addView(lv, lp);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (Item it : all) {
                    String hay = (it.id + " " + it.name + " " + company(it.provider)).toLowerCase(Locale.ROOT);
                    boolean ok = true;
                    for (String w : q.split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) { ok = false; break; }
                    if (ok) shown.add(it);
                }
                adapter.notifyDataSetChanged();
            }
        });

        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setView(box)
                .setNegativeButton("వద్దు", null)
                .create();
        lv.setOnItemClickListener((parent, v, p, id) -> {
            d.dismiss();
            done.on(shown.get(p));
        });
        d.show();
    }

    // ---------------------------------------------------------------- errors in plain words

    private static final Pattern QUOTA_MODEL = Pattern.compile("model: ([A-Za-z0-9._-]+)");

    /**
     * "Limit / balance used up" and "no model chosen", said for the company it really came from
     * (a Gemini free-tier limit is not a money balance). Null when the error is something else.
     */
    static String explain(Prefs prefs, Http.ApiError e) {
        String m = String.valueOf(e.getMessage());
        String low = m.toLowerCase(Locale.ROOT);
        if (m.contains(NO_GEMINI_MODEL))
            return "Gemini మోడల్ ఇంకా ఎంచుకోలేదు. సెట్టింగ్స్ → Jarvis మెదడు → Gemini మోడల్ కింద 'అన్ని మోడల్స్ చూపించు' నొక్కి ఒకటి ఎంచుకోండి.";
        boolean gemini = low.contains("generativelanguage") || low.contains("ai.google.dev") || low.contains("freetier")
                || low.contains("resource_exhausted") || (prefs.isGemini() && e.status == 429 && !low.contains("openai") && !low.contains("anthropic"));
        if (gemini && (e.status == 429 || low.contains("quota"))) {
            Matcher mm = QUOTA_MODEL.matcher(m);
            String model = mm.find() ? mm.group(1) : prefs.isGemini() ? prefs.model() : "Gemini";
            String change = " సెట్టింగ్స్ → Jarvis మెదడు → Gemini మోడల్ → 'అన్ని మోడల్స్ చూపించు' లో వేరే మోడల్ ఎంచుకోండి (ఒక్కో మోడల్‌కి వేరే లిమిట్ ఉంటుంది).";
            if (low.contains("limit: 0"))
                return "\"" + model + "\" మోడల్ Gemini ఉచిత ప్లాన్‌లో లేదు (billing ఆన్ చేస్తేనే పనిచేస్తుంది)." + change;
            if (low.contains("perday") || low.contains("per day"))
                return "\"" + model + "\" కి ఈ రోజు Gemini ఉచిత లిమిట్ అయిపోయింది (ఇది డబ్బు బ్యాలెన్స్ కాదు; మధ్యాహ్నం సుమారు 12:30–1:30 కి మళ్లీ వస్తుంది)." + change;
            if (low.contains("perminute") || low.contains("per minute"))
                return "\"" + model + "\" కి నిమిషం లిమిట్ దాటింది. ఒక నిమిషం ఆగి మళ్లీ అడగండి. తరచూ వస్తే" + change;
            return "\"" + model + "\" కి Gemini ఉచిత లిమిట్ అయిపోయింది (నిమిషం లేదా రోజు లిమిట్; ఇది డబ్బు బ్యాలెన్స్ కాదు). నిమిషం ఆగి అడగండి, మళ్లీ వస్తే" + change;
        }
        if (low.contains("credit") || low.contains("quota") || low.contains("billing") || low.contains("balance")) {
            boolean claude = low.contains("anthropic") || low.contains("credit balance") || (!low.contains("openai") && !prefs.isOpenAi() && !prefs.isGemini());
            return claude
                    ? "Anthropic (Claude) అకౌంట్‌లో బ్యాలెన్స్ అయిపోయింది. console.anthropic.com → Billing లో క్రెడిట్ జోడించండి, లేదా సెట్టింగ్స్‌లో వేరే మెదడు/మోడల్ ఎంచుకోండి."
                    : "OpenAI అకౌంట్‌లో బ్యాలెన్స్ అయిపోయింది. platform.openai.com → Billing లో క్రెడిట్ జోడించండి, లేదా సెట్టింగ్స్‌లో వేరే మెదడు/మోడల్ ఎంచుకోండి.";
        }
        return null;
    }
}
