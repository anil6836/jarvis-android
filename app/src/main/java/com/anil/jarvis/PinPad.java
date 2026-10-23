package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A big number pad for the home tablet's Settings PIN (TabletLock): check it, or set a new one (typed twice). The digits
 * are never shown, only dots; nothing is kept but TabletLock's hash.
 */
final class PinPad {
    interface Done { void done(boolean ok); }

    private PinPad() {}

    /** Asks for the PIN; done(true) when it is right, done(false) when he closes the pad. */
    static void check(Activity a, String title, Done d) { show(a, title, false, d); }

    /** A new PIN, typed twice; done(true) once it is saved. */
    static void set(Activity a, Done d) { show(a, "కొత్త 4 అంకెల PIN", true, d); }

    private static void show(Activity a, String title, boolean setting, Done d) {
        if (a == null || a.isFinishing()) { d.done(false); return; }
        final float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(18 * dp);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF13233A);
        bg.setCornerRadius(22 * dp);
        root.setBackground(bg);

        TextView head = text(a, "🔐 " + title, 22, 0xFFF4F8FC, true);
        root.addView(head);
        TextView dots = text(a, "", 34, 0xFFF2B544, true);
        dots.setPadding(0, Math.round(10 * dp), 0, Math.round(4 * dp));
        root.addView(dots);
        TextView msg = text(a, "", 16, 0xFFB4C5D8, false);
        msg.setPadding(0, 0, 0, Math.round(10 * dp));
        root.addView(msg);

        final StringBuilder typed = new StringBuilder();
        final String[] first = {null};
        final boolean[] finished = {false};
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable showDots = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) s.append(i < typed.length() ? "● " : "○ ");
            dots.setText(s.toString().trim());
        };
        showDots.run();
        if (!setting) {
            long w = TabletLock.waitLeft(a);
            msg.setText(w > 0 ? "చాలా సార్లు తప్పు: " + w + " సెకన్లు ఆగి మళ్ళీ ప్రయత్నించండి" : "PIN నొక్కండి");
        } else {
            msg.setText("గుర్తుండే 4 అంకెలు నొక్కండి");
        }

        final Runnable entered = () -> {
            String pin = typed.toString();
            typed.setLength(0);
            if (setting) {
                if (first[0] == null) { first[0] = pin; msg.setText("ఇంకోసారి అదే PIN నొక్కండి"); showDots.run(); return; }
                if (!first[0].equals(pin)) { first[0] = null; msg.setText("రెండూ ఒకటి కాలేదు: మళ్ళీ మొదటి నుంచి"); showDots.run(); return; }
                TabletLock.set(a, pin);
                finished[0] = true;
                dlg[0].dismiss();
                d.done(true);
                return;
            }
            if (TabletLock.check(a, pin)) {
                finished[0] = true;
                dlg[0].dismiss();
                d.done(true);
                return;
            }
            long w = TabletLock.waitLeft(a);
            msg.setText(w > 0 ? "చాలా సార్లు తప్పు: " + w + " సెకన్లు ఆగి మళ్ళీ ప్రయత్నించండి" : "తప్పు PIN. మళ్ళీ నొక్కండి");
            showDots.run();
        };

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫"};
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(a);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                rl.topMargin = Math.round(8 * dp);
                root.addView(row, rl);
            }
            final String k = keys[i];
            TextView b = text(a, k, 28, 0xFFF4F8FC, true);
            b.setGravity(Gravity.CENTER);
            GradientDrawable kb = new GradientDrawable();
            kb.setColor("✕".equals(k) ? 0xFF4A2030 : "⌫".equals(k) ? 0xFF2A3A55 : 0xFF1F3B5C);
            kb.setCornerRadius(18 * dp);
            b.setBackground(kb);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                if ("✕".equals(k)) { dlg[0].dismiss(); return; }
                if ("⌫".equals(k)) { if (typed.length() > 0) typed.setLength(typed.length() - 1); showDots.run(); return; }
                if (!setting && TabletLock.waitLeft(a) > 0) { msg.setText("చాలా సార్లు తప్పు: " + TabletLock.waitLeft(a) + " సెకన్లు ఆగి మళ్ళీ ప్రయత్నించండి"); return; }
                if (typed.length() >= 4) return;
                typed.append(k);
                showDots.run();
                if (typed.length() == 4) v.postDelayed(entered, 120);
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.round(84 * dp), Math.round(68 * dp));
            if (i % 3 != 0) bl.leftMargin = Math.round(10 * dp);
            row.addView(b, bl);
        }

        dlg[0] = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setView(root).create();
        dlg[0].setOnDismissListener(x -> { if (!finished[0]) { finished[0] = true; d.done(false); } });
        dlg[0].show();
        try {
            if (dlg[0].getWindow() != null) dlg[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        } catch (Exception ignored) {}
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
