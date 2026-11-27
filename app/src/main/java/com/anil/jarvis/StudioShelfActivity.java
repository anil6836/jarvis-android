package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🗂️ One shelf for everything he made in the studios (Anil, 28 Nov: "I చేయి"): 3D models, apps, games, circuits and PCBs in
 * one list, newest first, with a search, a filter, what each cost to make, and one tap that opens it in its own studio. Reading
 * the saved files only: ₹0, nothing sent anywhere. Phone only.
 */
public class StudioShelfActivity extends Activity {
    static final String W3D = "3d", APP = "app", GAME = "game", LAB = "lab", PCB = "pcb";
    private static final String[][] KINDS = {{"", "అన్నీ"}, {W3D, "🛠️ 3D"}, {APP, "📲 యాప్‌లు"}, {GAME, "🎮 ఆటలు"}, {LAB, "⚡ సర్క్యూట్లు"}, {PCB, "🔌 PCB"}};

    /** One thing he made. */
    static final class Item {
        String kind, title, sub, key; // key: the save time (3d, game, lab), the slug (app) or the design id (pcb)
        long at;
        double cost = -1;            // -1: not known (made by hand / a ready one)
        File thumb;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> all = new ArrayList<>();
    private String kind = "", words = "";
    private LinearLayout list, chips;
    private TextView totals;
    private boolean destroyed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.loadTheme(this);
        getWindow().setStatusBarColor(0xFF07080B);
        getWindow().setNavigationBarColor(0xFF07080B);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF07080B);
        root.setPadding(dp(16), dp(14), dp(16), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "🗂️ నా స్టూడియో షెల్ఫ్", 20, Ui.TEXT));
        names.addView(Ui.text(this, "అన్ని స్టూడియోల్లో మీరు చేసినవి · తెరవడానికి ఖర్చు లేదు", 12.5f, Ui.MUTED));
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Ui.text(this, "✕", 22, Ui.MUTED);
        close.setPadding(dp(12), dp(6), dp(4), dp(6));
        close.setContentDescription("మూసేయి");
        close.setOnClickListener(v -> finish());
        top.addView(close);
        root.addView(top);

        totals = Ui.text(this, "చదువుతున్నాను…", 13, Ui.CYAN);
        totals.setPadding(0, dp(10), 0, dp(8));
        root.addView(totals);

        EditText search = new EditText(this);
        search.setHint("🔍 పేరుతో వెతకండి");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.FAINT);
        search.setTextSize(15f);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.LINE2, 14));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { words = e.toString().trim().toLowerCase(Locale.ROOT); draw(); }
        });
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));

        chips = new LinearLayout(this);
        chips.setPadding(0, dp(10), 0, dp(10));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        root.addView(hs);
        drawChips();

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        Context app = getApplicationContext();
        new Thread(() -> {
            List<Item> got = load(app);
            ui.post(() -> { if (destroyed) return; all.clear(); all.addAll(got); draw(); });
        }, "jarvis-studio-shelf").start();
    }

    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private int dp(float v) { return Ui.dp(this, v); }

    // ================================================================ what is on the shelf (read on a worker thread)

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM", Locale.ENGLISH);
        // 🛠️ 3D models
        for (File file : Workshop.files(c)) {
            JSONObject b = Workshop.read(file);
            if (b == null) continue;
            Item it = new Item();
            it.kind = W3D;
            it.at = b.optLong("at");
            it.key = String.valueOf(it.at);
            it.title = nameOr(b.optString("title"), "3D బొమ్మ");
            it.cost = b.optDouble("cost", -1);
            it.thumb = Workshop.thumb(c, it.at);
            it.sub = "🛠️ 3D వర్క్‌షాప్ · " + f.format(new java.util.Date(it.at));
            out.add(it);
        }
        // 📲 apps
        try {
            for (AppStudio.Made a : AppStudio.list(c)) {
                Item it = new Item();
                it.kind = APP;
                it.at = Math.max(a.changedAt, a.madeAt);
                it.key = a.slug;
                it.title = nameOr(a.title, "యాప్");
                it.cost = a.cost;
                it.sub = "📲 యాప్ స్టూడియో · v" + Math.max(1, a.version) + " · " + f.format(new java.util.Date(it.at > 0 ? it.at : System.currentTimeMillis()))
                        + ("ok".equals(a.buildState) ? " · 📲 ఫోన్‌లో" : "");
                out.add(it);
            }
        } catch (Exception ignored) {}
        // 🎮 games
        for (File file : GameStudio.files(c)) {
            JSONObject g = GameStudio.read(file);
            if (g == null || g.optJSONArray("parts") == null) continue;
            Item it = new Item();
            it.kind = GAME;
            it.at = g.optLong("at");
            it.key = String.valueOf(it.at);
            it.title = nameOr(g.optString("title"), "ఆట");
            it.cost = g.optDouble("cost", -1);
            it.thumb = GameStudio.thumb(c, it.at);
            int best = g.optInt("best", 0);
            it.sub = "🎮 గేమ్ స్టూడియో · " + f.format(new java.util.Date(it.at)) + (best > 0 ? " · 🏆 " + best : "") + (g.optString("hero", "").isEmpty() ? "" : " · 🏍️ " + g.optString("hero"));
            out.add(it);
        }
        // ⚡ circuits
        for (File file : CircuitLab.files(c)) {
            JSONObject g = Workshop.read(file);
            if (g == null || g.optJSONObject("board") == null) continue;
            Item it = new Item();
            it.kind = LAB;
            it.at = g.optLong("at");
            it.key = String.valueOf(it.at);
            it.title = nameOr(g.optString("title"), "సర్క్యూట్");
            it.cost = g.optBoolean("ai") ? g.optDouble("cost", 0) : -1;
            it.thumb = CircuitLab.thumb(c, it.at);
            it.sub = "⚡ సర్క్యూట్ ల్యాబ్ · " + f.format(new java.util.Date(it.at)) + " · " + CircuitLab.partCount(g.optJSONObject("board")) + " భాగాలు";
            out.add(it);
        }
        // 🔌 PCBs
        try {
            for (JSONObject d : Pcbs.summaries(c)) {
                Item it = new Item();
                it.kind = PCB;
                it.at = d.optLong("at");
                it.key = d.optString("id");
                if (it.key.isEmpty()) continue;
                it.title = nameOr(d.optString("name"), "PCB బోర్డ్");
                it.sub = "🔌 PCB స్టూడియో · " + f.format(new java.util.Date(it.at > 0 ? it.at : System.currentTimeMillis())) + " · "
                        + Math.round(d.optDouble("w")) + " × " + Math.round(d.optDouble("h")) + " mm · " + d.optInt("n") + " భాగాలు";
                out.add(it);
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private static String nameOr(String s, String or) { return s == null || s.trim().isEmpty() ? or : s.trim(); }

    /** "🛠️ 12 · 📲 3 · … · AI ఖర్చు మొత్తం ₹…". Pure (tested on a PC with its own items). */
    static String totals(List<Item> items) {
        int[] n = new int[5];
        double cost = 0;
        for (Item it : items) {
            for (int k = 1; k < KINDS.length; k++) if (KINDS[k][0].equals(it.kind)) n[k - 1]++;
            if (it.cost > 0) cost += it.cost;
        }
        if (items.isEmpty()) return "ఇంకా ఏమీ లేదు Sir: ఏ స్టూడియోలోనైనా ఒకటి తయారు చేస్తే ఇక్కడ కనిపిస్తుంది.";
        return "🛠️ " + n[0] + " · 📲 " + n[1] + " · 🎮 " + n[2] + " · ⚡ " + n[3] + " · 🔌 " + n[4] + " · AI ఖర్చు మొత్తం " + Workshop.money(cost);
    }

    /** The items the search and the filter keep. Pure. */
    static List<Item> pick(List<Item> items, String kind, String words) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (kind != null && !kind.isEmpty() && !kind.equals(it.kind)) continue;
            if (words != null && !words.isEmpty() && !(it.title + " " + it.sub).toLowerCase(Locale.ROOT).contains(words)) continue;
            out.add(it);
        }
        return out;
    }

    // ================================================================ the screen

    private void drawChips() {
        chips.removeAllViews();
        for (String[] k : KINDS) {
            TextView t = Ui.text(this, k[1], 14, k[0].equals(kind) ? 0xFFFFFFFF : Ui.MUTED);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            t.setBackground(k[0].equals(kind) ? Ui.grad(this, new int[]{Ui.alpha(Ui.C_BLUE, 0xCC), Ui.alpha(Ui.C_CYAN, 0x99)}, 999, null) : Ui.round(this, 0x14FFFFFF, Ui.LINE2, 999));
            t.setOnClickListener(v -> { kind = k[0]; drawChips(); draw(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(t, lp);
        }
    }

    private void draw() {
        totals.setText(totals(all));
        list.removeAllViews();
        List<Item> shown = pick(all, kind, words);
        if (shown.isEmpty() && !all.isEmpty()) {
            TextView e = Ui.text(this, "ఈ వెతుకులాటకి ఏమీ దొరకలేదు Sir.", 14, Ui.MUTED);
            e.setPadding(0, dp(20), 0, 0);
            list.addView(e);
            return;
        }
        for (Item it : shown) list.addView(row(it));
    }

    private View row(Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(12), dp(10));
        r.setBackground(Ui.round(this, 0xFF111720, Ui.LINE2, 14));
        FrameLayout pic = new FrameLayout(this);
        pic.setBackground(Ui.round(this, 0xFF1A2230, 0, 10));
        Bitmap bm = it.thumb != null && it.thumb.isFile() ? small(it.thumb) : null;
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
            iv.setClipToOutline(true);
            iv.setBackground(Ui.round(this, 0xFF1A2230, 0, 10));
            pic.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView em = Ui.text(this, emoji(it.kind), 24, Ui.TEXT);
            em.setGravity(Gravity.CENTER);
            pic.addView(em, new FrameLayout.LayoutParams(-1, -1));
        }
        r.addView(pic, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(12), 0, dp(8), 0);
        TextView t = Ui.text(this, it.title, 15.5f, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        w.addView(t);
        TextView s = Ui.text(this, it.sub, 12f, Ui.MUTED);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        w.addView(s);
        r.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
        TextView c = Ui.text(this, it.cost > 0 ? Workshop.money(it.cost) : "₹0", 13, it.cost > 0 ? Ui.GOLD : Ui.MUTED);
        r.addView(c);
        r.setOnClickListener(v -> open(it));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private static String emoji(String kind) {
        switch (kind) { case W3D: return "🛠️"; case APP: return "📲"; case GAME: return "🎮"; case LAB: return "⚡"; default: return "🔌"; }
    }

    private Bitmap small(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= dp(54)) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Exception e) { return null; }
    }

    /** One tap: the thing in its own studio (opening costs nothing). */
    private void open(Item it) {
        Intent i;
        int top = Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
        switch (it.kind) {
            case W3D: i = new Intent(this, WorkshopActivity.class).putExtra(WorkshopActivity.EXTRA_OPEN_AT, it.at); break;
            case APP: i = new Intent(this, AppStudioActivity.class).putExtra(AppStudioActivity.EXTRA_SLUG, it.key); break;
            case GAME: i = new Intent(this, GameStudioActivity.class).putExtra(GameStudioActivity.EXTRA_SHOW_AT, it.at); break;
            case LAB: i = new Intent(this, CircuitLabActivity.class).putExtra(CircuitLabActivity.EXTRA_OPEN_AT, it.at); break;
            default: i = new Intent(this, PcbActivity.class).putExtra(PcbActivity.EXTRA_OPEN_ID, it.key); break;
        }
        startActivity(i.addFlags(top));
    }

    static void open(Context c) {
        Intent i = new Intent(c, StudioShelfActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
