package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📁 Case boards: one screen per problem with every scan, photo, note, PDF, expense and "what happened" story about it
 * as cards he can move, flick away and filter by voice ("గత వారం fault ఉన్నవి"); 🧠 asks Jarvis to link the cards and
 * sum the case up. Opened from All Features, the camera's answers (📁 chip), the scan history and the main screen.
 */
public class CaseActivity extends Activity {
    static final String EXTRA_CASE = "case_id";
    private static final int REQ_PHOTOS = 31, REQ_PDF = 32, REQ_VOICE = 33, REQ_RECON = 34, REQ_VIDEO = 35;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Prefs p;
    private String caseId;
    private JSONObject kase;
    private BoardView board;
    private TextView titleView, summaryView, hint;
    private EditText filterBox;
    private LinearLayout filterRow, summaryPanel;
    private FrameLayout root;
    private boolean reconFromVideo;

    static void open(Context c, String caseId) {
        Intent i = new Intent(c, CaseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (caseId != null) i.putExtra(EXTRA_CASE, caseId);
        c.startActivity(i);
    }

    /** "📁 కేస్‌కి కలుపు": he picks the case (or a new one) and the card goes in; then he may open the board. */
    static void addTo(Activity act, String kind, String ref, String title, String brief) {
        List<JSONObject> cases = CaseStore.all(act);
        String[] names = new String[cases.size() + 1];
        names[0] = "➕ కొత్త కేస్…";
        for (int i = 0; i < cases.size(); i++) names[i + 1] = "📁 " + cases.get(i).optString("title") + " (" + cards(cases.get(i)) + ")";
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ఏ కేస్‌కి కలపాలి?")
                .setItems(names, (d, w) -> {
                    if (w == 0) {
                        EditText e = new EditText(act);
                        e.setHint("కేస్ పేరు (ఉదా: బైక్ శబ్దం)");
                        e.setText(title == null ? "" : title.length() > 30 ? title.substring(0, 30) : title);
                        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("కొత్త కేస్").setView(e)
                                .setPositiveButton("సరే", (dd, ww) -> {
                                    JSONObject k = CaseStore.create(act, e.getText().toString());
                                    if (k != null) added(act, k.optString("id"), kind, ref, title, brief);
                                }).setNegativeButton("వద్దు", null).show();
                    } else added(act, cases.get(w - 1).optString("id"), kind, ref, title, brief);
                }).setNegativeButton("వద్దు", null).show();
    }

    private static void added(Activity act, String caseId, String kind, String ref, String title, String brief) {
        JSONObject card = CaseStore.addCard(act, caseId, kind, ref, title, brief);
        if (card == null) { Toast.makeText(act, "కలపలేకపోయాను (కేస్ నిండింది?)", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(act, "📁 కేస్‌కి కలిపాను", Toast.LENGTH_SHORT).show();
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("కేస్ బోర్డ్ తెరవనా?")
                .setPositiveButton("తెరువు", (d, w) -> open(act, caseId)).setNegativeButton("తర్వాత", null).show();
    }

    private static int cards(JSONObject k) {
        JSONArray a = k.optJSONArray("cards");
        int n = 0;
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null && !a.optJSONObject(i).optBoolean("hidden")) n++;
        return n;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        caseId = getIntent().getStringExtra(EXTRA_CASE);
        if (caseId == null) showList(); else showBoard();
    }

    @Override protected void onResume() {
        super.onResume();
        if (caseId != null && kase != null) { kase = CaseStore.get(this, caseId); if (kase != null) { board.setCase(kase.optJSONArray("cards"), kase.optJSONArray("links")); loadThumbs(); } }
    }

    @Override protected void onDestroy() { super.onDestroy(); work.shutdownNow(); }

    private int dp(float v) { return Ui.dp(this, v); }

    // ================================================================ the list of cases

    private void showList() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(icon("✕", v -> finish()));
        TextView t = Ui.mono(this, "📁 కేస్ బోర్డులు", 15, Ui.CYAN);
        t.setPadding(dp(6), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(icon("➕", v -> newCase()));
        page.addView(bar);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(24));
        TextView note = Ui.text(this, "ఒక సమస్య (బైక్ శబ్దం, ఇంట్లో లీక్, ఒక డాక్యుమెంట్ పని) గురించి ఉన్న స్కాన్‌లు, ఫోటోలు, నోట్స్, PDF లు, ఖర్చులు ఒకే బోర్డు మీద. "
                + "కెమెరా జవాబు కింద 📁 నొక్కినా, స్కాన్ చరిత్రలో పట్టుకున్నా కేస్‌కి కలుపుతుంది.", 13, Ui.MUTED);
        note.setPadding(0, dp(4), 0, dp(10));
        list.addView(note);
        List<JSONObject> cases = CaseStore.all(this);
        if (cases.isEmpty()) list.addView(Ui.text(this, "ఇంకా కేసులు లేవు. పైన ➕ నొక్కండి.", 15, Ui.TEXT));
        for (JSONObject k : cases) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(lp);
            TextView title = Ui.text(this, "📁 " + k.optString("title"), 16, 0xFFFFFFFF);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row.addView(title);
            String when = new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date(k.optLong("updated")));
            row.addView(Ui.text(this, cards(k) + " కార్డులు · " + when, 12.5f, Ui.MUTED));
            String sum = k.optString("summary");
            if (!sum.isEmpty()) { TextView s = Ui.text(this, sum.length() > 140 ? sum.substring(0, 140) + "…" : sum, 13, Ui.TEXT); s.setPadding(0, dp(4), 0, 0); row.addView(s); }
            final String id = k.optString("id");
            row.setOnClickListener(v -> open(this, id));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(k.optString("title"))
                        .setItems(new String[]{"✏️ పేరు మార్చు", "🗑️ కేస్ తీసేయి"}, (d, w) -> {
                            if (w == 0) rename(id, k.optString("title"), this::rebuild);
                            else new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("\"" + k.optString("title") + "\" కేస్ తీసేయనా? (స్కాన్‌లు, ఫోటోలు పోవు; బోర్డు మాత్రమే)")
                                    .setPositiveButton("తీసేయి", (dd, ww) -> { CaseStore.delete(this, id); rebuild(); }).setNegativeButton("వద్దు", null).show();
                        }).show();
                return true;
            });
            list.addView(row);
        }
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private void rebuild() { if (caseId == null) showList(); else showBoard(); }

    private void newCase() {
        EditText e = new EditText(this);
        e.setHint("కేస్ పేరు (ఉదా: బైక్ శబ్దం)");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("కొత్త కేస్").setView(e)
                .setPositiveButton("సరే", (d, w) -> { JSONObject k = CaseStore.create(this, e.getText().toString()); if (k != null) open(this, k.optString("id")); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void rename(String id, String old, Runnable then) {
        EditText e = new EditText(this);
        e.setText(old);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("కేస్ పేరు").setView(e)
                .setPositiveButton("సరే", (d, w) -> { CaseStore.rename(this, id, e.getText().toString()); if (then != null) then.run(); }).setNegativeButton("వద్దు", null).show();
    }

    private TextView icon(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 20, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    // ================================================================ the board

    private void showBoard() {
        kase = CaseStore.get(this, caseId);
        if (kase == null) { Toast.makeText(this, "ఆ కేస్ దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04101B);
        board = new BoardView(this);
        board.listener = new BoardView.Listener() {
            @Override public void open(JSONObject card) { openCard(card); }
            @Override public void menu(JSONObject card) { cardMenu(card); }
            @Override public void hidden(JSONObject card) {
                CaseStore.save(CaseActivity.this, kase);
                Toast.makeText(CaseActivity.this, "కార్డ్ పక్కన పెట్టాను (⋯ → పక్కన పెట్టినవి తిరిగి)", Toast.LENGTH_SHORT).show();
            }
            @Override public void moved() { CaseStore.save(CaseActivity.this, kase); }
        };
        root.addView(board, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE604101B, 0x0004101B}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(icon("✕", v -> finish()));
        titleView = Ui.mono(this, "📁 " + kase.optString("title"), 14, Ui.CYAN);
        titleView.setSingleLine(true);
        titleView.setPadding(dp(6), 0, 0, 0);
        titleView.setOnClickListener(v -> rename(caseId, kase.optString("title"), () -> { kase = CaseStore.get(this, caseId); titleView.setText("📁 " + kase.optString("title")); }));
        bar.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(icon("🔎", v -> filterRow.setVisibility(filterRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        bar.addView(icon("🧠", v -> linkCards()));
        bar.addView(icon("➕", v -> addMenu()));
        bar.addView(icon("⋯", v -> moreMenu()));
        top.addView(bar);
        filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(dp(10), 0, dp(10), dp(6));
        filterBox = new EditText(this);
        filterBox.setHint("వెతుకు: \"గత వారం fault ఉన్నవి\", \"ఫోటోలు\", \"బ్రేక్\"");
        filterBox.setTextColor(0xFFFFFFFF);
        filterBox.setHintTextColor(Ui.FAINT);
        filterBox.setTextSize(14);
        filterBox.setBackground(Ui.round(this, 0x33FFFFFF, 0x55FFFFFF, 20));
        filterBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        filterBox.setSingleLine(true);
        filterBox.setInputType(InputType.TYPE_CLASS_TEXT);
        filterBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        filterRow.addView(filterBox, new LinearLayout.LayoutParams(0, -2, 1));
        filterRow.addView(icon("🎙️", v -> {
            try {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "ఏవి చూపించాలి?");
                startActivityForResult(i, REQ_VOICE);
            } catch (Exception e) { Toast.makeText(this, "మాటతో వెతకడం ఈ ఫోన్‌లో లేదు", Toast.LENGTH_SHORT).show(); }
        }));
        filterRow.addView(icon("✕", v -> { filterBox.setText(""); filterRow.setVisibility(View.GONE); }));
        filterRow.setVisibility(View.GONE);
        top.addView(filterRow);
        hint = Ui.text(this, "", 12.5f, Ui.alpha(Ui.CYAN, 0xDD));
        hint.setPadding(dp(14), 0, dp(14), dp(6));
        top.addView(hint);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // the summary at the bottom (Jarvis's reading of the case)
        summaryPanel = new LinearLayout(this);
        summaryPanel.setOrientation(LinearLayout.VERTICAL);
        summaryPanel.setPadding(dp(14), dp(10), dp(14), dp(12));
        summaryPanel.setBackground(Ui.round(this, 0xF2081A2C, Ui.alpha(Ui.C_AMBER, 0xAA), 16));
        LinearLayout sb = new LinearLayout(this);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = Ui.text(this, "🧠 Jarvis చెప్పేది", 13.5f, 0xFFFFD08A);
        st.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sb.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
        TextView speak = Ui.text(this, "🔊", 18, 0xFFFFFFFF);
        speak.setPadding(dp(8), 0, dp(8), 0);
        speak.setOnClickListener(v -> Announcer.sayDirect(this, summaryView.getText().toString()));
        sb.addView(speak);
        TextView close = Ui.text(this, "✕", 18, 0xFFFFFFFF);
        close.setPadding(dp(8), 0, 0, 0);
        close.setOnClickListener(v -> summaryPanel.setVisibility(View.GONE));
        sb.addView(close);
        summaryPanel.addView(sb);
        summaryView = Ui.text(this, "", 14, 0xFFFFFFFF);
        summaryView.setPadding(0, dp(6), 0, 0);
        summaryPanel.addView(summaryView);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        slp.setMargins(dp(10), 0, dp(10), dp(12));
        root.addView(summaryPanel, slp);
        setContentView(root);
        board.setCase(kase.optJSONArray("cards"), kase.optJSONArray("links"));
        String sum = kase.optString("summary");
        if (sum.isEmpty()) summaryPanel.setVisibility(View.GONE); else summaryView.setText(sum);
        loadThumbs();
        if (cards(kase) == 0) hint.setText("➕ నొక్కి ఫోటోలు, స్కాన్‌లు, నోట్స్ కలపండి. కార్డ్ లాగితే కదులుతుంది; విసిరేస్తే పక్కకి వెళ్తుంది.");
    }

    private void applyFilter(String words) {
        if (words == null || words.trim().isEmpty()) { board.setFilter(null); hint.setText(""); return; }
        List<String> ids = CaseStore.filter(kase.optJSONArray("cards"), words, System.currentTimeMillis());
        board.setFilter(new HashSet<>(ids));
        hint.setText(ids.isEmpty() ? "🔎 ఏదీ సరిపోలేదు" : "🔎 " + ids.size() + " కార్డులు సరిపోయాయి");
    }

    private void loadThumbs() {
        JSONArray cards = kase.optJSONArray("cards");
        if (cards == null) return;
        for (int i = 0; i < cards.length(); i++) {
            final JSONObject c = cards.optJSONObject(i);
            if (c == null) continue;
            final String kind = c.optString("kind"), ref = c.optString("ref"), id = c.optString("id");
            if (!kind.equals("scan") && !kind.equals("photo") && !kind.equals("evidence") && !kind.equals("timeline")) continue;
            work.execute(() -> {
                Bitmap b = null;
                try {
                    if (kind.equals("scan") || kind.equals("evidence")) b = ScanStore.photo(this, ref, "photo.jpg", 240);
                    else if (kind.equals("photo")) b = decode(new File(ref), 240);
                    else if (kind.equals("timeline")) b = decode(new File(ref.replace(".json", ".jpg")), 240);
                } catch (Exception ignored) {}
                final Bitmap bb = b;
                if (bb != null) main.post(() -> board.setThumb(id, bb));
            });
        }
    }

    private static Bitmap decode(File f, int max) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return JarvisCamera.scaleTo(BitmapFactory.decodeFile(f.getPath(), o2), max);
    }

    private File caseDir() { File d = new File(new File(getFilesDir(), "cases"), caseId); d.mkdirs(); return d; }

    // ---- opening and card menus

    private void openCard(JSONObject c) {
        String kind = c.optString("kind"), ref = c.optString("ref");
        try {
            switch (kind) {
                case "scan":
                case "evidence": startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_SCAN, ref)); break;
                case "photo": {
                    Uri u = shareCopy(ref);
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    break;
                }
                case "pdf": startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ref), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); break;
                case "timeline": startActivity(new Intent(this, TimelineActivity.class).putExtra(TimelineActivity.EXTRA_FILE, ref)); break;
                case "note":
                case "ai":
                case "expense":
                default: {
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(CaseStore.icon(kind) + " " + c.optString("title"))
                            .setMessage(c.optString("brief").isEmpty() ? ref : c.optString("brief"))
                            .setPositiveButton("సరే", null).setNeutralButton("🔊", (d, w) -> Announcer.sayDirect(this, c.optString("title") + ". " + c.optString("brief"))).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    /** A file of ours shared through the photo provider (a copy in the cache), so a viewer app can open it. */
    private Uri shareCopy(String path) throws Exception {
        File f = new File(path);
        File out = PhotoProvider.shareFile(this);
        try (java.io.InputStream in = new java.io.FileInputStream(f); FileOutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return PhotoProvider.shareUri();
    }

    private void cardMenu(JSONObject c) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(CaseStore.icon(c.optString("kind")) + " " + c.optString("title"))
                .setItems(new String[]{"✏️ పేరు / గమనిక మార్చు", "⚠️ సమస్య గుర్తు " + (c.optBoolean("fault") ? "తీసేయి" : "పెట్టు"), "🙈 పక్కన పెట్టు", "🗑️ కేస్ నుంచి తీసేయి"}, (d, w) -> {
                    try {
                        if (w == 0) {
                            LinearLayout box = new LinearLayout(this);
                            box.setOrientation(LinearLayout.VERTICAL);
                            box.setPadding(dp(16), dp(8), dp(16), 0);
                            EditText t = new EditText(this); t.setHint("పేరు"); t.setText(c.optString("title"));
                            EditText br = new EditText(this); br.setHint("గమనిక"); br.setText(c.optString("brief"));
                            box.addView(t); box.addView(br);
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(box).setPositiveButton("సరే", (dd, ww) -> {
                                try { c.put("title", t.getText().toString().trim()).put("brief", br.getText().toString().trim()); } catch (Exception ignored) {}
                                CaseStore.save(this, kase); board.invalidate();
                            }).setNegativeButton("వద్దు", null).show();
                        } else if (w == 1) { c.put("fault", !c.optBoolean("fault")); CaseStore.save(this, kase); board.invalidate(); }
                        else if (w == 2) { c.put("hidden", true); CaseStore.save(this, kase); board.invalidate(); }
                        else {
                            JSONArray cards = kase.optJSONArray("cards"), fresh = new JSONArray();
                            int gone = -1;
                            for (int i = 0; i < cards.length(); i++) { if (cards.optJSONObject(i) == c) gone = i; else fresh.put(cards.optJSONObject(i)); }
                            JSONArray links = kase.optJSONArray("links"), nl = new JSONArray();
                            for (int i = 0; links != null && i < links.length(); i++) { // the links move with the numbers
                                JSONArray l = links.optJSONArray(i);
                                if (l == null) continue;
                                int a = l.optInt(0), b = l.optInt(1);
                                if (a == gone || b == gone) continue;
                                nl.put(new JSONArray().put(a > gone ? a - 1 : a).put(b > gone ? b - 1 : b).put(l.optString(2, "")));
                            }
                            kase.put("cards", fresh).put("links", nl);
                            CaseStore.save(this, kase);
                            board.setCase(fresh, nl);
                        }
                    } catch (Exception ignored) {}
                }).show();
    }

    private void moreMenu() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(new String[]{"🙈 పక్కన పెట్టినవి తిరిగి తెచ్చు", "🔍 బోర్డు మామూలు సైజుకి", "📷 కెమెరా తెరిచి ఈ కేస్‌కి స్కాన్ చేయి", "✏️ కేస్ పేరు మార్చు", "🗑️ ఈ కేస్ తీసేయి"}, (d, w) -> {
                    switch (w) {
                        case 0: {
                            JSONArray cards = kase.optJSONArray("cards");
                            int n = 0;
                            for (int i = 0; cards != null && i < cards.length(); i++) { JSONObject c = cards.optJSONObject(i); if (c != null && c.optBoolean("hidden")) { try { c.put("hidden", false); n++; } catch (Exception ignored) {} } }
                            CaseStore.save(this, kase); board.invalidate();
                            Toast.makeText(this, n + " కార్డులు తిరిగి వచ్చాయి", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 1: board.reset(); break;
                        case 2: startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_CASE, caseId)); break;
                        case 3: rename(caseId, kase.optString("title"), () -> { kase = CaseStore.get(this, caseId); titleView.setText("📁 " + kase.optString("title")); }); break;
                        case 4: new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("ఈ కేస్ తీసేయనా? (స్కాన్‌లు, ఫోటోలు పోవు)")
                                .setPositiveButton("తీసేయి", (dd, ww) -> { CaseStore.delete(this, caseId); finish(); }).setNegativeButton("వద్దు", null).show(); break;
                        default: break;
                    }
                }).show();
    }

    // ---- adding cards

    private void addMenu() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("➕ కేస్‌కి కలుపు")
                .setItems(new String[]{"📷 స్కాన్ చరిత్ర నుంచి", "🖼️ ఫోటోలు (గ్యాలరీ)", "📝 నోట్", "📄 PDF / ఫైల్", "💰 ఖర్చు (నా ఖర్చుల నుంచి)",
                        "🕵️ ఫోటోల నుంచి \"ఇక్కడ ఏం జరిగింది?\"", "🎬 వీడియో నుంచి \"ఇక్కడ ఏం జరిగింది?\"", "📷 కెమెరా తెరిచి స్కాన్ చేయి"}, (d, w) -> {
                    switch (w) {
                        case 0: pickScan(); break;
                        case 1: startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PHOTOS); break;
                        case 2: addNote(); break;
                        case 3: startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_PDF); break;
                        case 4: pickExpense(); break;
                        case 5: reconFromVideo = false; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_RECON); break;
                        case 6: reconFromVideo = true; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_VIDEO); break;
                        case 7: startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_CASE, caseId)); break;
                        default: break;
                    }
                }).show();
    }

    private void pickScan() {
        List<JSONObject> all = ScanStore.list(this, false);
        if (all.isEmpty()) { Toast.makeText(this, "ఇంకా స్కాన్‌లు లేవు", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[Math.min(60, all.size())];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH);
        for (int i = 0; i < names.length; i++) names[i] = (all.get(i).optBoolean("board") ? "🧊 " : "📷 ") + all.get(i).optString("title") + "\n   " + f.format(new java.util.Date(all.get(i).optLong("t")));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📷 ఏ స్కాన్?").setItems(names, (d, w) -> {
            JSONObject o = all.get(w);
            CaseStore.addCard(this, caseId, "scan", o.optString("id"), o.optString("title"), o.optString("brief"));
            refresh();
        }).setNegativeButton("వద్దు", null).show();
    }

    private void addNote() {
        EditText e = new EditText(this);
        e.setHint("ఉదా: బ్రేక్ వేసినప్పుడు కిర్ర్ శబ్దం, రెండు రోజుల నుంచి");
        e.setMinLines(2);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📝 నోట్").setView(e)
                .setPositiveButton("సరే", (d, w) -> {
                    String t = e.getText().toString().trim();
                    if (t.isEmpty()) return;
                    CaseStore.addCard(this, caseId, "note", t, t.length() > 28 ? t.substring(0, 28) + "…" : t, t);
                    refresh();
                }).setNegativeButton("వద్దు", null).show();
    }

    private void pickExpense() {
        List<JSONObject> ex = Money.expenses(this);
        if (ex.isEmpty()) { Toast.makeText(this, "ఖర్చులు ఏవీ లేవు", Toast.LENGTH_SHORT).show(); return; }
        int n = Math.min(40, ex.size());
        String[] names = new String[n];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH);
        for (int i = 0; i < n; i++) { JSONObject o = ex.get(ex.size() - 1 - i); names[i] = "₹" + Math.round(o.optDouble("amount")) + " · " + o.optString("what") + (o.optString("shop").isEmpty() ? "" : " · " + o.optString("shop")) + " · " + f.format(new java.util.Date(o.optLong("t"))); }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("💰 ఏ ఖర్చు?").setItems(names, (d, w) -> {
            JSONObject o = ex.get(ex.size() - 1 - w);
            CaseStore.addCard(this, caseId, "expense", o.toString(), "₹" + Math.round(o.optDouble("amount")) + " " + o.optString("what"), names[w]);
            refresh();
        }).setNegativeButton("వద్దు", null).show();
    }

    private void refresh() {
        kase = CaseStore.get(this, caseId);
        if (kase == null) return;
        board.setCase(kase.optJSONArray("cards"), kase.optJSONArray("links"));
        loadThumbs();
        hint.setText("");
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null) return;
        if (code == REQ_VOICE) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) { filterRow.setVisibility(View.VISIBLE); filterBox.setText(r.get(0)); }
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        if (code == REQ_PHOTOS) { addPhotos(uris); return; }
        if (code == REQ_PDF) {
            Uri u = uris.get(0);
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            String name = nameOf(u);
            CaseStore.addCard(this, caseId, "pdf", u.toString(), name, "");
            refresh();
            return;
        }
        if (code == REQ_RECON || code == REQ_VIDEO) reconstruct(uris);
    }

    private String nameOf(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return "ఫైల్";
    }

    private void addPhotos(List<Uri> uris) {
        Toast.makeText(this, uris.size() + " ఫోటోలు కలుపుతున్నాను…", Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            int n = 0;
            for (Uri u : uris) {
                try {
                    Bitmap b = load(u, 1200);
                    if (b == null) continue;
                    File f = new File(caseDir(), "p" + System.currentTimeMillis() + n + ".jpg");
                    try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 86, o); }
                    b.recycle();
                    CaseStore.addCard(this, caseId, "photo", f.getPath(), "ఫోటో " + (cards(CaseStore.get(this, caseId)) + 1), "");
                    n++;
                } catch (Exception ignored) {}
            }
            main.post(this::refresh);
        });
    }

    private Bitmap load(Uri u, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm;
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o2); }
        return bm == null ? null : JarvisCamera.scaleTo(bm, max);
    }

    // ---- 🕵️ what happened, from his photos or a video

    private void reconstruct(List<Uri> uris) {
        if (!Net.online(this) || !p.hasBrain()) { Toast.makeText(this, "దీనికి నెట్ + AI key కావాలి", Toast.LENGTH_LONG).show(); return; }
        EditText e = new EditText(this);
        e.setHint("ఏమైనా చెప్పాలంటే (ఉదా: ఉదయం బైక్ ఇలా ఉంది, సాయంత్రం గీత)");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("🕵️ ఇక్కడ ఏం జరిగింది?").setView(e)
                .setPositiveButton("చూడు", (d, w) -> runRecon(uris, e.getText().toString())).setNegativeButton("వద్దు", null).show();
    }

    private void runRecon(List<Uri> uris, String note) {
        Toast.makeText(this, "🕵️ చూస్తున్నాను… (కొన్ని సెకన్లు)", Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            String err = null;
            File jsonFile = null;
            try {
                List<Bitmap> pics = new ArrayList<>();
                if (reconFromVideo) pics.addAll(Recon.videoFrames(this, uris.get(0), 6));
                else for (Uri u : uris) { if (pics.size() >= 8) break; Bitmap b = load(u, 1000); if (b != null) pics.add(b); }
                if (pics.isEmpty()) throw new IllegalStateException("no pictures");
                Bitmap grid = Recon.grid(pics);
                for (Bitmap b : pics) b.recycle();
                String reply = Brain.oneShot(p, Recon.system(), Recon.prompt(pics.size(), note), JarvisCamera.jpeg(grid, 1600, 80), false, 2200);
                JSONObject r = Recon.parse(reply);
                if (r == null) throw new IllegalStateException("no story");
                r.put("photos", pics.size()).put("t", System.currentTimeMillis()).put("note", note == null ? "" : note);
                String base = "r" + System.currentTimeMillis();
                jsonFile = new File(caseDir(), base + ".json");
                try (FileOutputStream o = new FileOutputStream(new File(caseDir(), base + ".jpg"))) { grid.compress(Bitmap.CompressFormat.JPEG, 80, o); }
                grid.recycle();
                try (FileOutputStream o = new FileOutputStream(jsonFile)) { o.write(r.toString().getBytes("UTF-8")); }
                CaseStore.addCard(this, caseId, "timeline", jsonFile.getPath(), "🕵️ " + r.optString("title"), r.optString("story"));
            } catch (Http.ApiError ex) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, ex);
            } catch (Exception ex) {
                err = "చూడలేకపోయాను (ఫోటోలు తెరవలేదు / నెట్).";
            }
            final String er = err;
            final File jf = jsonFile;
            main.post(() -> {
                if (isFinishing()) return;
                if (er != null) { Toast.makeText(this, er, Toast.LENGTH_LONG).show(); return; }
                refresh();
                startActivity(new Intent(this, TimelineActivity.class).putExtra(TimelineActivity.EXTRA_FILE, jf.getPath()));
            });
        });
    }

    // ---- 🧠 Jarvis links the cards

    private void linkCards() {
        if (!Net.online(this) || !p.hasBrain()) { Toast.makeText(this, "దీనికి నెట్ + AI key కావాలి", Toast.LENGTH_LONG).show(); return; }
        JSONArray cards = kase.optJSONArray("cards");
        if (cards == null || cards(kase) < 2) { Toast.makeText(this, "కనీసం 2 కార్డులు కావాలి", Toast.LENGTH_SHORT).show(); return; }
        hint.setText("🧠 Jarvis చూస్తున్నాడు…");
        final String outline = CaseStore.outline(cards), title = kase.optString("title");
        final int n = cards.length();
        work.execute(() -> {
            String err = null;
            JSONObject res = null;
            try {
                String sys = "You are JARVIS helping Anil (Telangana) think through one case: \"" + title + "\". The numbered cards below are what he has (scans, photos, notes, PDFs, expenses). "
                        + "Reply with ONLY this JSON: {\"links\":[[a,b,\"why, in 2-5 Telugu words\"]...],\"summary\":\"4-6 simple Telugu sentences: what the cards together suggest is going on, the likely cause, what is still unknown\","
                        + "\"next\":[\"up to 4 short Telugu lines: what to check or do next\"]}. a and b are card numbers. Link only cards that really bear on each other (cause → effect, same part, same day, a bill for a repair). "
                        + "Be honest about uncertainty; never invent facts; never identify people.";
                String reply = Brain.oneShot(p, sys, "Cards:\n" + outline + "\nAnswer in the JSON.", null, false, 1400);
                res = CaseStore.parseLinks(reply, n);
            } catch (Http.ApiError ex) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, ex);
            } catch (Exception ex) {
                err = "చూడలేకపోయాను (నెట్).";
            }
            final String er = err;
            final JSONObject r = res;
            main.post(() -> {
                if (isFinishing()) return;
                if (er != null || r == null) { hint.setText(""); Toast.makeText(this, er == null ? "జవాబు రాలేదు" : er, Toast.LENGTH_LONG).show(); return; }
                StringBuilder sum = new StringBuilder(r.optString("summary"));
                JSONArray nx = r.optJSONArray("next");
                if (nx != null && nx.length() > 0) { sum.append("\n\nతర్వాత:"); for (int i = 0; i < nx.length(); i++) sum.append("\n• ").append(nx.optString(i)); }
                CaseStore.setLinks(this, caseId, r.optJSONArray("links"), sum.toString());
                kase = CaseStore.get(this, caseId);
                board.setCase(kase.optJSONArray("cards"), kase.optJSONArray("links"));
                summaryView.setText(sum.toString());
                summaryPanel.setVisibility(View.VISIBLE);
                hint.setText("🔗 " + (r.optJSONArray("links") == null ? 0 : r.optJSONArray("links").length()) + " లింకులు గీశాను");
                Announcer.sayDirect(this, r.optString("summary"));
            });
        });
    }
}
