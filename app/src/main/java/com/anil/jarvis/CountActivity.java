package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 🔢 The count on the picture: a numbered marker on each thing the AI counted, a chip per kind (tap = only that kind), the
 * phone's own bar count beside the AI's when they differ. He corrects by touch: a tap on an unmarked thing adds one of the
 * chosen kind, a tap on a marker removes it; the count is recounted and kept.
 */
public class CountActivity extends Activity {
    static final String EXTRA_ID = "scan";

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, CountActivity.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String id;
    private JSONObject data;
    private Bitmap photo;
    private final List<Counter.Mark> marks = new ArrayList<>();
    private String filter = null, addKind = "";
    private MarkView view;
    private TextView info;
    private LinearLayout chips;
    private final java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        id = getIntent().getStringExtra(EXTRA_ID);
        data = ScanStore.load(this, id);
        if (data == null) { Toast.makeText(this, "ఆ లెక్క దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        marks.addAll(Counter.marks(data.optJSONArray("items")));
        photo = ScanStore.photo(this, id, "photo.jpg", 1600);
        int pad = Ui.dp(this, 12);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        x.setOnClickListener(v -> finish());
        bar.addView(x);
        TextView t = Ui.mono(this, data.optString("title"), 14, Ui.CYAN);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = Ui.text(this, "⋯", 22, 0xFFFFFFFF);
        more.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 6));
        more.setOnClickListener(v -> menu());
        bar.addView(more);
        page.addView(bar);
        view = new MarkView(this);
        page.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        info = Ui.text(this, "", 14, 0xFFFFFFFF);
        info.setLineSpacing(0, 1.2f);
        info.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        info.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(pad, Ui.dp(this, 4), pad, 0);
        info.setLayoutParams(ilp);
        page.addView(info);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 10));
        hs.addView(chips);
        page.addView(hs);
        TextView hint = Ui.text(this, "మిస్ అయిన దాని మీద నొక్కితే +1 · గుర్తు మీద నొక్కితే −1 · చిప్ నొక్కితే ఆ రకం మాత్రమే", 11.5f, Ui.MUTED);
        hint.setPadding(pad, 0, pad, Ui.dp(this, 8));
        page.addView(hint);
        setContentView(page);
        Map<String, Integer> k = Counter.byKind(marks);
        if (!k.isEmpty()) addKind = k.keySet().iterator().next();
        refresh();
    }

    private void refresh() {
        Map<String, Integer> k = Counter.byKind(marks);
        chips.removeAllViews();
        TextView allChip = chip("అన్నీ " + marks.size(), filter == null);
        allChip.setOnClickListener(v -> { filter = null; refresh(); });
        chips.addView(allChip);
        for (Map.Entry<String, Integer> e : k.entrySet()) {
            TextView c = chip(e.getKey() + " " + e.getValue(), e.getKey().equals(filter));
            c.setOnClickListener(v -> { filter = e.getKey().equals(filter) ? null : e.getKey(); addKind = e.getKey(); refresh(); });
            chips.addView(c);
        }
        TextView plus = chip("➕ కొత్త రకం", false);
        plus.setOnClickListener(v -> newKind());
        chips.addView(plus);
        String unit = data.optString("unit");
        boolean all = data.optBoolean("all");
        StringBuilder b = new StringBuilder("🔢 " + Counter.sentence(marks, unit, all));
        JSONObject bars = data.optJSONObject("phone_bars");
        if (bars != null) {
            int phone = bars.optInt("use", Math.max(bars.optInt("v"), bars.optInt("h")));
            if (phone > 0) b.append("\n📱 ఫోన్ గీతల లెక్క (రెండో అభిప్రాయం): ").append(phone).append(phone == marks.size() ? " — AI తో సరిపోయింది ✓" : " — AI తో తేడా; గుర్తులు ఒకసారి చూడండి (స్తంభాలు కూడా గీతలుగా లెక్కకి రావొచ్చు)");
        }
        if (!data.optString("note").isEmpty()) b.append("\n").append(data.optString("note"));
        int conf = data.optInt("confidence", -1);
        if (conf >= 0 && conf < 60) b.append("\n(AI నమ్మకం ").append(conf).append("%: దట్టంగా / కప్పబడి ఉంది; గుర్తులు సరిచూడండి)");
        info.setText(b.toString());
        view.invalidate();
    }

    private void newKind() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setHint("రకం పేరు (ఉదా: బోల్టులు)");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("➕ కొత్త రకం").setView(e)
                .setPositiveButton("సరే", (d, w) -> { String s = e.getText().toString().trim(); if (!s.isEmpty()) { addKind = s; filter = null; Toast.makeText(this, "ఇప్పుడు నొక్కిన చోట \"" + s + "\" వస్తుంది", Toast.LENGTH_SHORT).show(); refresh(); } })
                .setNegativeButton("వద్దు", null).show();
    }

    private void menu() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(data.optString("title"))
                .setItems(new String[]{"📁 కేస్‌కి కలుపు", "🗑️ ఈ లెక్క తీసేయి"}, (d, w) -> {
                    if (w == 0) CaseActivity.addTo(this, "scan", id, data.optString("title"), Counter.sentence(marks, data.optString("unit"), data.optBoolean("all")));
                    else new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("ఈ లెక్క తీసేయనా?")
                            .setPositiveButton("తీసేయి", (a, c) -> { ScanStore.delete(this, id); finish(); }).setNegativeButton("వద్దు", null).show();
                }).setNegativeButton("మూసేయ్", null).show();
    }

    /** His correction is kept with the scan (its words too, so history and the case card show the new count). */
    private void save() {
        try {
            data.put("items", Counter.json(marks)).put("total", marks.size()).put("corrected", true);
            String s = Counter.sentence(marks, data.optString("unit"), data.optBoolean("all"));
            data.put("say", s + " (మీరు సరిచేసినది)").put("title", "🔢 " + data.optString("what") + ": " + s);
            final JSONObject copy = new JSONObject(data.toString());
            work.execute(() -> { try { ScanStore.save(this, copy, null, null); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    private TextView chip(String s, boolean on) {
        TextView t = Ui.text(this, s, 13.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        t.setBackground(Ui.round(this, on ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, on ? Ui.CYAN : 0x55FFFFFF, 999));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        work.shutdown();
        if (photo != null) { photo.recycle(); photo = null; }
    }

    static final int[] KIND_COLOURS = {0xFF74E4FF, 0xFFF59E0B, 0xFF10B981, 0xFFEC4899, 0xFF38BDF8, 0xFFF97316, 0xFFA78BFA, 0xFF34D399};

    /** The picture with its numbered markers. */
    final class MarkView extends View {
        private final Paint pf = new Paint(Paint.FILTER_BITMAP_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private float downX, downY;
        private long downAt;

        MarkView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 1.5f));
            txt.setColor(0xFF04101B);
            txt.setTextSize(Ui.dp(c, 10.5f));
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        private int colour(String kind) {
            int i = 0;
            for (String k : Counter.byKind(marks).keySet()) { if (k.equals(kind.isEmpty() ? "వస్తువు" : kind)) return KIND_COLOURS[i % KIND_COLOURS.length]; i++; }
            return KIND_COLOURS[0];
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF04101B);
            int w = getWidth(), h = getHeight();
            if (photo == null || w == 0 || h == 0) return;
            float s = Math.min(w / (float) photo.getWidth(), h / (float) photo.getHeight());
            float dw = photo.getWidth() * s, dh = photo.getHeight() * s;
            dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            c.drawBitmap(photo, null, dst, pf);
            float rad = Ui.dp(getContext(), marks.size() > 60 ? 7 : marks.size() > 25 ? 9 : 11);
            int n = 0;
            for (Counter.Mark m : marks) {
                n++;
                String kind = m.kind.isEmpty() ? "వస్తువు" : m.kind;
                boolean dim = filter != null && !filter.equals(kind);
                float x = dst.left + m.x * dw, y = dst.top + m.y * dh;
                int col = colour(m.kind);
                dot.setColor(Ui.alpha(col, dim ? 0x30 : 0xE6));
                c.drawCircle(x, y, rad, dot);
                ring.setColor(Ui.alpha(0xFF04101B, dim ? 0x30 : 0xCC));
                c.drawCircle(x, y, rad, ring);
                if (!dim && rad >= Ui.dp(getContext(), 9)) c.drawText(String.valueOf(n), x, y + txt.getTextSize() * 0.36f, txt);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); downAt = android.os.SystemClock.uptimeMillis(); return true;
                case MotionEvent.ACTION_UP: {
                    if (android.os.SystemClock.uptimeMillis() - downAt > 400 || Math.hypot(e.getX() - downX, e.getY() - downY) > Ui.dp(getContext(), 12)) return true;
                    if (photo == null || dst.width() <= 0) return true;
                    float fx = (e.getX() - dst.left) / dst.width(), fy = (e.getY() - dst.top) / dst.height();
                    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return true;
                    float rad = Ui.dp(getContext(), marks.size() > 60 ? 7 : marks.size() > 25 ? 9 : 11);
                    float hit = Math.max(rad + Ui.dp(getContext(), 3), Ui.dp(getContext(), 12));
                    Counter.Mark near = null;
                    double best = hit;
                    for (Counter.Mark m : marks) { // the nearest one, not the first in the list
                        if (filter != null && !filter.equals(m.kind.isEmpty() ? "వస్తువు" : m.kind)) continue;
                        double d = Math.hypot(dst.left + m.x * dst.width() - e.getX(), dst.top + m.y * dst.height() - e.getY());
                        if (d < best) { best = d; near = m; }
                    }
                    if (near != null) { marks.remove(near); Toast.makeText(getContext(), "−1 " + (near.kind.isEmpty() ? "" : near.kind), Toast.LENGTH_SHORT).show(); }
                    else { marks.add(new Counter.Mark(fx, fy, addKind == null ? "" : addKind)); Toast.makeText(getContext(), "+1 " + addKind, Toast.LENGTH_SHORT).show(); }
                    save();
                    refresh();
                    return true;
                }
                default: return true;
            }
        }
    }
}
