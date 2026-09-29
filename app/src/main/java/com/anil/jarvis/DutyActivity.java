package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The duty calendar inside Jarvis: a month with the batch on duty coloured on each date and the names of
 * the people on duty; Anil's own days glow gold. Tap a date for details and changes (extra duty, leave,
 * doing someone's duty); ⚙️ sets the batches, first duty dates, times and members.
 */
public class DutyActivity extends Activity {
    static final String EXTRA_MONTH = "month"; // "yyyy-MM"
    static final String EXTRA_SETUP = "setup";

    private static final int GOLD = 0xFFFBBF24;
    private LinearLayout content;
    private YearMonth month = YearMonth.now();
    private Duty.Roster roster;

    private int dp(float v) { return Ui.dp(this, v); }

    static void open(Context c, String month) {
        c.startActivity(new Intent(c, DutyActivity.class).putExtra(EXTRA_MONTH, month == null ? "" : month).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG_TOP);
        getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        try { String m = getIntent().getStringExtra(EXTRA_MONTH); if (m != null && !m.isEmpty()) month = YearMonth.parse(m); } catch (Exception ignored) {}
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new Ui.Aurora());
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        if (getIntent().getBooleanExtra(EXTRA_SETUP, false)) content.post(this::setup);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private List<Holidays.Day> hols = new ArrayList<>();

    private void render() {
        roster = Duty.load(this);
        hols = Holidays.between(this, month.atDay(1).minusDays(7), month.atEndOfMonth().plusDays(14));
        content.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "🗓️ డ్యూటీ క్యాలెండర్", 21, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        Ui.gradientText(title, Ui.C_CYAN, GOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        IconView gear = new IconView(this, IconView.GEAR, 0xFFFFFFFF);
        gear.setBackground(Ui.glass(this, 20));
        gear.setContentDescription("బ్యాచ్‌లు, టైమింగ్స్");
        gear.setOnClickListener(v -> setup());
        head.addView(gear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        IconView close = new IconView(this, IconView.CLOSE, 0xFFFFFFFF);
        close.setBackground(Ui.glass(this, 20));
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
        clp.leftMargin = dp(8);
        head.addView(close, clp);
        content.addView(head);

        if (!Duty.ready(roster)) {
            TextView t = Ui.text(this, "ఇంకా సెటప్ చేయలేదు. ⚙️ నొక్కి మీ బ్యాచ్, మొదటి డ్యూటీ తేదీ, రిలీవ్ టైమ్ (11:30) పెట్టండి; వేరే బ్యాచ్‌ల పేర్లు కూడా చేర్చొచ్చు.\n\n"
                    + "లేదా Jarvis కి చెప్పండి: \"నా బ్యాచ్ A, అక్టోబర్ 1 న 11:30 కి నా డ్యూటీ, 2 రోజులు డ్యూటీ 4 రోజులు సెలవు\".", 14.5f, Ui.MUTED);
            t.setPadding(dp(2), dp(14), dp(2), dp(10));
            content.addView(t);
            TextView go = Ui.text(this, "⚙️ ఇప్పుడే సెటప్ చేయి", 16, 0xFFFFFFFF);
            go.setGravity(Gravity.CENTER);
            go.setPadding(0, dp(14), 0, dp(14));
            go.setBackground(Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 16, null));
            go.setOnClickListener(v -> setup());
            content.addView(go);
            TextView paste = Ui.text(this, "📋 టెక్స్ట్ పేస్ట్ చేసి సెటప్ (బ్యాచ్‌లు, తేదీలు ఒకేసారి)", 15, 0xFFFFFFFF);
            paste.setGravity(Gravity.CENTER);
            paste.setPadding(0, dp(13), 0, dp(13));
            paste.setBackground(Ui.glass(this, 16));
            paste.setOnClickListener(v -> fromText());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = dp(10);
            content.addView(paste, plp);
        } else {
            nextCard();
        }
        monthBar();
        grid(content);
        legend(content);
        TextView share = Ui.text(this, "📤 ఈ నెల క్యాలెండర్ ఫోటోగా షేర్ చెయ్ (WhatsApp, అందరికీ)", 15, 0xFFFFFFFF);
        share.setGravity(Gravity.CENTER);
        share.setPadding(dp(10), dp(13), dp(10), dp(13));
        share.setBackground(Ui.grad(this, new int[]{Ui.alpha(GOLD, 0xAA), Ui.alpha(Ui.C_ORANGE, 0x88)}, 16, null));
        share.setOnClickListener(v -> shareImage());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(16);
        content.addView(share, slp);
    }

    /** "మీ తర్వాతి డ్యూటీ" at the top. */
    private void nextCard() {
        LocalDate today = LocalDate.now();
        List<LocalDate[]> bl = roster.blocks(Duty.ME, today, today.plusDays(60));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = Ui.grad(this, new int[]{Ui.alpha(GOLD, 0x40), Ui.alpha(GOLD, 0x10)}, 18,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setStroke(dp(1), Ui.alpha(GOLD, 0x88));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        boolean onNow = roster.isOn(Duty.ME, today);
        card.addView(Ui.text(this, onNow ? "⭐ ఈరోజు మీరు డ్యూటీలో ఉన్నారు" : "⭐ మీ తర్వాతి డ్యూటీ", 13, GOLD));
        LocalDate[] next = null;
        for (LocalDate[] b : bl) if (!b[0].isBefore(today) || (onNow && !b[1].isBefore(today))) { next = b; break; }
        TextView t = Ui.text(this, next == null ? "రాబోయే 60 రోజుల్లో లేదు" : Duty.blockText(roster, Duty.ME, next), 17, 0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);
        StringBuilder more = new StringBuilder();
        int n = 0;
        for (LocalDate[] b : bl) {
            if (b == next || b[0].isBefore(today)) continue;
            more.append(n == 0 ? "తర్వాత: " : " · ").append(b[0].getDayOfMonth()).append(" ").append(Duty.monthName(b[0].getMonthValue()));
            if (++n == 3) break;
        }
        if (next != null) card.addView(Ui.text(this, "🏍️ ఇంటి నుంచి " + roster.leaveTime(roster.timeOf(Duty.ME)) + " కల్లా బయలుదేరండి", 13.5f, 0xFFFFFFFF));
        if (more.length() > 0) card.addView(Ui.text(this, more.toString(), 13, 0xCCFFFFFF));
        if (next != null && !next[0].isBefore(today)) {
            LocalDate first = next[0];
            TextView check = Ui.text(this, "🌧️ ప్రయాణ చెక్: దారిలో వర్షం, బైక్ ఛార్జ్ ▸", 13.5f, Ui.C_CYAN);
            check.setPadding(0, dp(6), 0, 0);
            check.setOnClickListener(v -> tripCheck(first));
            card.addView(check);
        }
        content.addView(card, lp);
    }

    /** Rain on the way and the bike's charge for that duty, worked out in the background. */
    private void tripCheck(LocalDate d) {
        Toast.makeText(this, "చూస్తున్నాను…", Toast.LENGTH_SHORT).show();
        Duty.Roster r = roster;
        new Thread(() -> {
            String[] hm = r.timeOf(Duty.ME).split(":");
            java.time.LocalDateTime start = d.atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
            String s = Duty.tripCheck(this, r, start.minusMinutes(r.leaveBefore), start, !d.equals(LocalDate.now()));
            if (s.isEmpty()) s = "ఇంకా ఏమీ తెలియలేదు. వాతావరణానికి ఇంటర్నెట్, లొకేషన్ కావాలి; బైక్ ఛార్జ్ కోసం చివరిసారి ఛార్జ్ చేసినప్పుడు Jarvis కి చెప్పండి (\"ఛార్జింగ్ రాసుకో\").";
            if (d.isAfter(LocalDate.now().plusDays(2))) s += "\n\n(వాతావరణ సూచన 2-3 రోజుల ముందు మాత్రమే సరిగ్గా ఉంటుంది.)";
            String msg = s;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🌧️ " + Duty.day(d) + " డ్యూటీ ప్రయాణం")
                        .setMessage(msg).setPositiveButton("సరే", null).show();
            });
        }, "duty-trip").start();
    }

    private void monthBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(14), 0, dp(6));
        TextView prev = Ui.text(this, "‹", 28, 0xFFFFFFFF);
        prev.setGravity(Gravity.CENTER);
        prev.setBackground(Ui.glass(this, 18));
        prev.setOnClickListener(v -> { month = month.minusMonths(1); render(); });
        bar.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(40)));
        TextView m = Ui.text(this, Duty.monthName(month.getMonthValue()) + " " + month.getYear(), 18, 0xFFFFFFFF);
        m.setGravity(Gravity.CENTER);
        m.setTypeface(Typeface.DEFAULT_BOLD);
        m.setOnClickListener(v -> { month = YearMonth.now(); render(); });
        bar.addView(m, new LinearLayout.LayoutParams(0, -2, 1));
        TextView next = Ui.text(this, "›", 28, 0xFFFFFFFF);
        next.setGravity(Gravity.CENTER);
        next.setBackground(Ui.glass(this, 18));
        next.setOnClickListener(v -> { month = month.plusMonths(1); render(); });
        bar.addView(next, new LinearLayout.LayoutParams(dp(44), dp(40)));
        content.addView(bar);
    }

    private int colorOf(Duty.Batch b) {
        int i = roster.batches.indexOf(b);
        return Duty.COLORS[Math.max(0, i) % Duty.COLORS.length];
    }

    /** The month: Monday first, each date with the batch colour and the names on duty. */
    private void grid(LinearLayout content) {
        LinearLayout week = new LinearLayout(this);
        for (String d : new String[]{"సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని", "ఆది"}) {
            TextView t = Ui.text(this, d, 11.5f, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            week.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(week);
        LocalDate first = month.atDay(1), today = LocalDate.now();
        int lead = first.getDayOfWeek().getValue() - 1;
        LocalDate d = first.minusDays(lead);
        for (int row = 0; row < 6; row++) {
            if (row > 0 && d.getMonthValue() != month.getMonthValue() && d.isAfter(first)) break;
            LinearLayout r = new LinearLayout(this);
            for (int col = 0; col < 7; col++) {
                r.addView(cell(d, d.getMonthValue() == month.getMonthValue(), d.equals(today)), cellParams(col));
                d = d.plusDays(1);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(84));
            rlp.topMargin = dp(4);
            content.addView(r, rlp);
        }
    }

    private LinearLayout.LayoutParams cellParams(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (col > 0) lp.leftMargin = dp(3);
        return lp;
    }

    private View cell(LocalDate d, boolean inMonth, boolean today) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(3), dp(3), dp(3), dp(3));
        List<Duty.Batch> bs = roster.batchesOn(d);
        boolean mine = Duty.ready(roster) && roster.isOn(Duty.ME, d);
        int col = bs.isEmpty() ? 0x22FFFFFF : colorOf(bs.get(0));
        android.graphics.drawable.GradientDrawable bg = Ui.round(this, bs.isEmpty() ? 0x10FFFFFF : Ui.alpha(col, 0x38), 0, 10);
        if (mine) bg.setStroke(dp(2), GOLD);
        else if (today) bg.setStroke(dp(1.5f), 0xFFFFFFFF);
        c.setBackground(bg);
        if (!inMonth) c.setAlpha(0.35f);
        LinearLayout top = new LinearLayout(this);
        TextView num = Ui.text(this, String.valueOf(d.getDayOfMonth()), 13, today ? Ui.C_CYAN : 0xFFFFFFFF);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(num, new LinearLayout.LayoutParams(0, -2, 1));
        if (!bs.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (Duty.Batch b : bs) ids.append(b.id);
            TextView id = Ui.text(this, ids.toString(), 10.5f, col);
            id.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(id);
        }
        List<Holidays.Day> today_h = new ArrayList<>();
        for (Holidays.Day h : hols) if (h.date.equals(d)) today_h.add(h);
        Holidays.Day hol = Holidays.shown(today_h);
        if (hol != null) {
            TextView star = Ui.text(this, "🎉", 9.5f, 0xFFFB923C);
            top.addView(star);
        }
        c.addView(top);
        if (hol != null) {
            TextView hn = Ui.text(this, hol.name, 9.5f, 0xFFFDBA74);
            hn.setSingleLine(true);
            hn.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(hn);
        }
        if (mine) {
            TextView me = Ui.text(this, "★ నేను", 10.5f, GOLD);
            me.setTypeface(Typeface.DEFAULT_BOLD);
            me.setSingleLine(true);
            c.addView(me);
        }
        List<String> names = new ArrayList<>();
        for (String p : roster.onDuty(d)) if (!Duty.ME.equals(p)) names.add(p);
        if (!names.isEmpty()) {
            TextView n = Ui.text(this, TextUtils.join(", ", names), 10, 0xDDFFFFFF);
            n.setMaxLines(Math.max(1, (mine ? 2 : 3) - (hol != null ? 1 : 0)));
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setLineSpacing(0, 1.0f);
            c.addView(n);
        }
        boolean changed = false;
        for (Duty.Change ch : roster.changes) if (ch.date.equals(d)) { changed = true; break; }
        if (changed) {
            TextView dot = Ui.text(this, "✎", 10, GOLD);
            c.addView(dot);
        }
        c.setOnClickListener(v -> dayMenu(d));
        return c;
    }

    private void legend(LinearLayout content) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(4), dp(12), dp(4), 0);
        StringBuilder s = new StringBuilder();
        for (Duty.Batch b : roster.batches) {
            TextView t = Ui.text(this, "■ " + b.name + (b.id.equalsIgnoreCase(roster.mine) ? " (మీ బ్యాచ్)" : "")
                    + (b.start == null ? " · తేదీ పెట్టలేదు" : " · " + b.time + " కి రిలీవ్")
                    + (b.members.isEmpty() ? "" : " · " + TextUtils.join(", ", b.members)), 12.5f, colorOf(b));
            t.setPadding(0, dp(2), 0, dp(2));
            l.addView(t);
        }
        l.addView(Ui.text(this, "★ బంగారు అంచు = మీ డ్యూటీ · ✎ = ఆ రోజు మార్పు ఉంది · 🎉 = పండుగ / సెలవు · " + roster.on + " రోజులు డ్యూటీ, " + roster.off + " రోజులు సెలవు · తేదీ నొక్కితే వివరాలు, మార్పులు", 12, Ui.MUTED));
        content.addView(l);
    }

    /** The month (dates, batches, names, legend) drawn as a picture and shared, e.g. on WhatsApp to the other batches. */
    private void shareImage() {
        try {
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setPadding(dp(14), dp(14), dp(14), dp(14));
            v.setBackgroundColor(Ui.BG_TOP);
            TextView t = Ui.text(this, "🗓️ డ్యూటీ క్యాలెండర్ · " + Duty.monthName(month.getMonthValue()) + " " + month.getYear(), 20, 0xFFFFFFFF);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(2), 0, 0, dp(8));
            v.addView(t);
            grid(v);
            legend(v);
            TextView by = Ui.text(this, "Jarvis తో తయారైంది", 11, Ui.MUTED);
            by.setGravity(Gravity.END);
            by.setPadding(0, dp(8), dp(2), 0);
            v.addView(by);
            int w = Math.max(dp(380), getResources().getDisplayMetrics().widthPixels);
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            v.layout(0, 0, w, v.getMeasuredHeight());
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, v.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(bmp));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            Coder.Made m = Coder.save(this, "Jarvis/duty", "Duty_" + month + ".png", "image/png", out.toByteArray());
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, m.uri)
                    .putExtra(Intent.EXTRA_TEXT, "🗓️ " + Duty.monthName(month.getMonthValue()) + " " + month.getYear() + " డ్యూటీ క్యాలెండర్")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "క్యాలెండర్ షేర్ చేయండి"));
        } catch (Exception e) {
            Toast.makeText(this, "ఫోటో తయారు కాలేదు: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- a date

    private void dayMenu(LocalDate d) {
        if (!Duty.ready(roster)) { setup(); return; }
        String myName = new Prefs(this).name();
        StringBuilder info = new StringBuilder();
        List<String> on = roster.onDuty(d);
        if (on.isEmpty()) info.append("ఎవరూ డ్యూటీలో లేరు (సెటప్ చూడండి).");
        else {
            info.append("డ్యూటీలో: ");
            List<String> shown = new ArrayList<>();
            for (String p : on) shown.add(Duty.name(this, p));
            info.append(TextUtils.join(", ", shown));
            List<Duty.Batch> bs = roster.batchesOn(d);
            if (!bs.isEmpty()) info.append("\n").append(bs.get(0).name).append(" · ").append(bs.get(0).time).append(" నుంచి");
        }
        boolean mine = roster.isOn(Duty.ME, d);
        info.append("\n\nమీరు: ").append(mine ? "⭐ డ్యూటీ" : "🏠 సెలవు");
        for (Holidays.Day h : Holidays.on(this, d)) info.append("\n🎉 ").append(h.name).append(" (").append(h.kindTe()).append(")");
        for (Duty.Change ch : roster.changes)
            if (ch.date.equals(d)) info.append("\n✎ ").append(ch.who.startsWith("batch:") ? ch.who.substring(6) + " బ్యాచ్" : Duty.name(this, ch.who))
                    .append(": ").append(ch.duty ? "డ్యూటీ" : "సెలవు").append(ch.note.isEmpty() ? "" : " (" + ch.note + ")");

        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (!mine) {
            items.add("⭐ ఈ రోజు నాకు డ్యూటీ (ఎక్స్‌ట్రా)");
            acts.add(() -> { roster.set(Duty.ME, d, true, "ఎక్స్‌ట్రా"); saveAndRender(); });
        } else {
            items.add("🏠 ఈ రోజు నాకు సెలవు");
            acts.add(() -> { roster.set(Duty.ME, d, false, "సెలవు"); saveAndRender(); });
        }
        for (String p : on) {
            if (Duty.ME.equals(p)) continue;
            items.add("🔁 " + p + " బదులు నేను చేస్తా (4 రోజులు + 8 సెలవు)");
            acts.add(() -> cover(p, d, myName));
        }
        List<Duty.Batch> bs = roster.batchesOn(d);
        for (Duty.Batch b : bs) {
            if (b.id.equalsIgnoreCase(roster.mine) || !b.members.isEmpty()) continue;
            items.add("🔁 " + b.name + " బదులు నేను చేస్తా (4 రోజులు + 8 సెలవు)");
            acts.add(() -> cover("batch:" + b.id, d, myName));
        }
        if (mine) {
            for (Duty.Batch b : roster.batches) {
                if (b.id.equalsIgnoreCase(roster.mine)) continue;
                List<String> ppl = new ArrayList<>(b.members);
                if (ppl.isEmpty()) ppl.add("batch:" + b.id);
                for (String p : ppl) {
                    String shown = p.startsWith("batch:") ? b.name : p;
                    items.add("🙋 నా డ్యూటీ " + shown + " చేస్తారు (వాళ్లకి 4 రోజులు, నాకు 8 సెలవు)");
                    acts.add(() -> {
                        LocalDate[] r = roster.swap(p, Duty.ME, d, shown, myName);
                        if (r == null) { Toast.makeText(this, "కుదరలేదు: " + shown + " డ్యూటీ కనిపించలేదు", Toast.LENGTH_LONG).show(); return; }
                        saveAndRender();
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🙋 మార్చాను ✓")
                                .setMessage(Duty.day(r[0]) + " – " + Duty.day(r[1]) + " మీ డ్యూటీ " + shown + " చేస్తారు."
                                        + (r[2] == null ? "" : " " + Duty.day(r[2]) + " – " + Duty.day(r[3]) + " " + shown + " డ్యూటీ మీరు చేస్తారు."))
                                .setPositiveButton("సరే", null).show();
                    });
                }
            }
        }
        items.add("👤 వేరేవాళ్ల డ్యూటీ మార్చు");
        acts.add(() -> otherPerson(d));
        items.add("🎉 ఈ రోజుకి పండుగ / సెలవు పేరు చేర్చు");
        acts.add(() -> {
            EditText e = text("ఉదా: ఊరి జాతర, పెళ్లి, ప్రత్యేక సెలవు", "");
            LinearLayout box = new LinearLayout(this);
            box.setPadding(dp(20), dp(8), dp(20), 0);
            box.addView(e, new LinearLayout.LayoutParams(-1, -2));
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🎉 " + Duty.day(d))
                    .setView(box)
                    .setPositiveButton("చేర్చు", (x, w) -> {
                        try { if (Holidays.add(this, d.toString(), e.getText().toString()) != null) render(); } catch (Exception ignored) {}
                    })
                    .setNegativeButton("వద్దు", null).show();
        });
        for (Holidays.Day h : Holidays.on(this, d)) {
            if (!h.kind.equals("mine")) continue;
            items.add("🗑️ " + h.name + " తీసేయి");
            acts.add(() -> { Holidays.removeMine(this, h.date, h.name); render(); });
        }
        boolean changed = false;
        for (Duty.Change ch : roster.changes) if (ch.date.equals(d)) { changed = true; break; }
        if (changed) {
            items.add("↩️ ఈ రోజు మార్పులు తీసేయి");
            acts.add(() -> { roster.clear(d, null); saveAndRender(); });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🗓️ " + Duty.day(d) + " " + d.getYear())
                .setMessage(info.toString())
                .setPositiveButton("మార్పులు", (dlg, w) -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(Duty.day(d))
                        .setItems(items.toArray(new String[0]), (d2, i) -> acts.get(i).run())
                        .setNegativeButton("వద్దు", null).show())
                .setNegativeButton("సరే", null)
                .show();
    }

    private void cover(String who, LocalDate d, String myName) {
        LocalDate[] r = roster.cover(who, d, myName);
        if (r == null) { Toast.makeText(this, "ఆ తేదీ దగ్గర వాళ్ల డ్యూటీ కనిపించలేదు", Toast.LENGTH_LONG).show(); return; }
        saveAndRender();
        String name = who.startsWith("batch:") ? who.substring(6) + " బ్యాచ్" : who;
        String msg = Duty.day(r[0]) + " – " + Duty.day(r[1]) + " మీరు " + name + " బదులు చేస్తారు"
                + (r[2] == null ? "." : ". " + Duty.day(r[2]) + " – " + Duty.day(r[3]) + " మీ డ్యూటీ " + name + " చేస్తారు, మీకు సెలవు.");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🔁 మార్చాను ✓").setMessage(msg).setPositiveButton("సరే", null).show();
    }

    private void otherPerson(LocalDate d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("పేరు (ఉదా: Ravi)");
        name.setSingleLine(true);
        box.addView(name);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(Duty.day(d) + ": ఎవరి డ్యూటీ?")
                .setView(box)
                .setPositiveButton("డ్యూటీ", (x, w) -> otherSet(name.getText().toString(), d, true))
                .setNeutralButton("సెలవు", (x, w) -> otherSet(name.getText().toString(), d, false))
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private void otherSet(String typed, LocalDate d, boolean duty) {
        String n = typed.trim();
        if (n.isEmpty()) return;
        String p = roster.person(n);
        roster.set(p != null ? p : n, d, duty, "");
        saveAndRender();
    }

    private void saveAndRender() {
        Duty.save(this, roster);
        render();
    }

    // ---------------------------------------------------------------- setup

    private void setup() {
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(10), dp(20), dp(10));
        sv.addView(f);

        f.addView(label("డ్యూటీ రోజులు, సెలవు రోజులు (మీది: 2 డ్యూటీ, 4 సెలవు)"));
        LinearLayout cyc = new LinearLayout(this);
        EditText on = number(String.valueOf(roster.on)), off = number(String.valueOf(roster.off));
        cyc.addView(on, new LinearLayout.LayoutParams(0, -2, 1));
        TextView plus = new TextView(this);
        plus.setText("  డ్యూటీ  +  ");
        cyc.addView(plus);
        cyc.addView(off, new LinearLayout.LayoutParams(0, -2, 1));
        TextView end = new TextView(this);
        end.setText("  సెలవు");
        cyc.addView(end);
        f.addView(cyc);

        RadioGroup mine = new RadioGroup(this);
        List<EditText> names = new ArrayList<>(), members = new ArrayList<>();
        List<TextView> dates = new ArrayList<>(), times = new ArrayList<>();
        final LocalDate[] picked = new LocalDate[roster.batches.size()];
        final String[] pickedTime = new String[roster.batches.size()];
        for (int i = 0; i < roster.batches.size(); i++) {
            Duty.Batch b = roster.batches.get(i);
            picked[i] = b.start;
            pickedTime[i] = b.time;
            TextView h = label("━━ " + b.id + " బ్యాచ్ ━━");
            h.setTextColor(colorOf(b));
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(0, dp(16), 0, dp(2));
            f.addView(h);
            EditText nm = text("బ్యాచ్ పేరు", b.name);
            f.addView(nm);
            names.add(nm);
            final int idx = i;
            TextView date = pickButton(b.start == null ? "📅 ఒక డ్యూటీ మొదటి రోజు ఎంచుకోండి" : "📅 డ్యూటీ మొదటి రోజు: " + Duty.day(b.start) + " " + b.start.getYear());
            date.setOnClickListener(v -> {
                LocalDate s = picked[idx] == null ? LocalDate.now() : picked[idx];
                new DatePickerDialog(this, (dp, y, m, dd) -> {
                    picked[idx] = LocalDate.of(y, m + 1, dd);
                    date.setText("📅 డ్యూటీ మొదటి రోజు: " + Duty.day(picked[idx]) + " " + y);
                }, s.getYear(), s.getMonthValue() - 1, s.getDayOfMonth()).show();
            });
            f.addView(date);
            dates.add(date);
            TextView time = pickButton("🕗 డ్యూటీ మొదలయ్యే టైమ్: " + b.time);
            time.setOnClickListener(v -> {
                String[] hm = pickedTime[idx].split(":");
                new TimePickerDialog(this, (tp, hh, mm) -> {
                    pickedTime[idx] = String.format(Locale.ENGLISH, "%02d:%02d", hh, mm);
                    time.setText("🕗 డ్యూటీ మొదలయ్యే టైమ్: " + pickedTime[idx]);
                }, Integer.parseInt(hm[0]), Integer.parseInt(hm[1]), false).show();
            });
            f.addView(time);
            times.add(time);
            EditText ms = text("ఈ బ్యాచ్‌లో ఎవరెవరు (కామాతో, ఉదా: Ravi, Suresh)", TextUtils.join(", ", b.members));
            f.addView(ms);
            members.add(ms);
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText("ఇది నా బ్యాచ్");
            mine.addView(rb);
            if (b.id.equalsIgnoreCase(roster.mine)) rb.setChecked(true);
        }
        f.addView(label("మీ బ్యాచ్ ఏది?"));
        f.addView(mine);
        TextView hint = label("ఒక్క బ్యాచ్ తేదీ పెడితే చాలు: మిగతావి " + roster.on + " రోజుల తేడాతో తనంతట తానే వస్తాయి. "
                + "టైమ్ = ముందు బ్యాచ్‌ని రిలీవ్ చేసే టైమ్ (మీది 11:30).");
        f.addView(hint);
        f.addView(label("ఇంటి నుంచి డ్యూటీకి వెళ్లడానికి ఎంత సేపు (నిమిషాలు) · 90 = 11:30 డ్యూటీకి 10:00 కి బయలుదేరాలి"));
        EditText travel = number(String.valueOf(roster.leaveBefore));
        f.addView(travel);
        f.addView(label("ఇంటి నుంచి డ్యూటీకి దూరం, ఒక వైపు (కి.మీ) · బైక్ ఛార్జ్ వెళ్లి రావడానికి సరిపోతుందా చెప్పడానికి · 0 = తెలియదు"));
        EditText tripKm = number(String.valueOf(roster.tripKm));
        f.addView(tripKm);
        Switch remind = new Switch(this);
        remind.setText("గుర్తు చేయి: ఎల్లుండి, ముందు రోజు రాత్రి 8కి, బయలుదేరే గంట ముందు, బయలుదేరే టైమ్‌కి (దారిలో వర్షం, బైక్ ఛార్జ్ కూడా చెప్తాను)");
        remind.setChecked(roster.remind);
        remind.setPadding(0, dp(12), 0, dp(6));
        f.addView(remind);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("⚙️ బ్యాచ్‌లు, టైమింగ్స్")
                .setView(sv)
                .setNeutralButton("📋 టెక్స్ట్‌తో", (d, w) -> fromText())
                .setPositiveButton("సేవ్", (d, w) -> {
                    try { roster.on = Math.max(1, Math.min(10, Integer.parseInt(on.getText().toString().trim()))); } catch (Exception ignored) {}
                    try { roster.off = Math.max(0, Math.min(30, Integer.parseInt(off.getText().toString().trim()))); } catch (Exception ignored) {}
                    for (int i = 0; i < roster.batches.size(); i++) {
                        Duty.Batch b = roster.batches.get(i);
                        String n = names.get(i).getText().toString().trim();
                        b.name = n.isEmpty() ? b.id + " బ్యాచ్" : n;
                        b.start = picked[i];
                        b.time = pickedTime[i];
                        b.members.clear();
                        for (String m : members.get(i).getText().toString().split("\\s*[,،]\\s*")) if (!m.trim().isEmpty()) b.members.add(m.trim());
                    }
                    int chk = mine.getCheckedRadioButtonId();
                    if (chk >= 1000) roster.mine = roster.batches.get(chk - 1000).id;
                    roster.remind = remind.isChecked();
                    try { roster.leaveBefore = Math.max(0, Math.min(600, Integer.parseInt(travel.getText().toString().trim()))); } catch (Exception ignored) {}
                    try { roster.tripKm = Math.max(0, Math.min(500, Integer.parseInt(tripKm.getText().toString().trim()))); } catch (Exception ignored) {}
                    roster.fillStarts();
                    saveAndRender();
                    Toast.makeText(this, "సేవ్ చేశాను ✓", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    /** Batches from pasted lines ("నా బ్యాచ్: నేను, సోమయ్య | 2026-10-06 | 11:30", one per batch). */
    private void fromText() {
        EditText box = new EditText(this);
        box.setMinLines(5);
        box.setGravity(Gravity.TOP);
        box.setTextSize(14);
        box.setHint("నా బ్యాచ్: నేను, సోమయ్య | 2026-10-06 | 11:30\nబ్యాచ్: శ్రీను, రామకృష్ణ | 2026-10-08 | 11:30\nబ్యాచ్: సాయి, వీరేంద్ర | 2026-10-10 | 11:30\nసైకిల్: 2 డ్యూటీ, 4 సెలవు");
        StringBuilder now = new StringBuilder();
        for (Duty.Batch b : roster.batches) {
            if (b.start == null) continue;
            boolean mine = b.id.equalsIgnoreCase(roster.mine);
            List<String> ppl = new ArrayList<>();
            if (mine) ppl.add("నేను");
            ppl.addAll(b.members);
            now.append(mine ? "నా బ్యాచ్: " : "బ్యాచ్: ").append(TextUtils.join(", ", ppl)).append(" | ").append(b.start).append(" | ").append(b.time).append("\n");
        }
        if (now.length() > 0) box.setText(now.append("సైకిల్: ").append(roster.on).append(" డ్యూటీ, ").append(roster.off).append(" సెలవు").toString());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(18), dp(6), dp(18), 0);
        wrap.addView(box, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📋 బ్యాచ్‌లు టెక్స్ట్‌తో")
                .setMessage("ఒక్కో బ్యాచ్‌కి ఒక లైన్, రిలీవ్ చేసే వరుసలో: పేర్లు | ఒక డ్యూటీ మొదటి రోజు | రిలీవ్ టైమ్. మీ బ్యాచ్ లైన్ \"నా బ్యాచ్\" తో మొదలుపెట్టండి.")
                .setView(wrap)
                .setPositiveButton("సెట్ చెయ్", (d, w) -> {
                    int n = Duty.fromText(roster, box.getText().toString(), new Prefs(this).name());
                    if (n <= 0) { Toast.makeText(this, "అర్థం కాలేదు: పేర్లు | తేదీ | టైమ్ ఫార్మాట్‌లో రాయండి", Toast.LENGTH_LONG).show(); return; }
                    saveAndRender();
                    Toast.makeText(this, n + " బ్యాచ్‌లు సెట్ చేశాను ✓", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private EditText text(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private EditText number(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        return e;
    }

    private TextView pickButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setTextColor(Ui.C_CYAN);
        return t;
    }
}
