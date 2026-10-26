package com.anil.jarvis;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 🎡 ⑬'s wheel (27 Oct): Jarvis's round card on the left (child 0) and his six buttons on an arc beside him (the rest),
 * as big as the box lets them be with no button over another ({@link CarDeck#wheel}: the places, desk-tested). Only
 * where they sit: every button keeps its own tap and long press.
 */
final class CarWheel extends ViewGroup {

    CarWheel(Context c) { super(c); }

    private float[] at = new float[0];

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        int n = Math.max(0, getChildCount() - 1);
        at = CarDeck.wheel(w, h, n);
        int d = Math.max(1, Math.round(at[2])), tw = Math.max(1, Math.round(at[3])), th = Math.max(1, Math.round(at[4]));
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int cw = i == 0 ? d : tw, ch = i == 0 ? d : th;
            v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (at.length < 5) return;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
            if (i == 0) { int x = Math.round(at[0]), y = Math.round(at[1]); v.layout(x, y, x + cw, y + ch); continue; }
            int k = 5 + 2 * (i - 1);
            if (k + 1 >= at.length) continue;
            int x = Math.round(at[k] - cw / 2f), y = Math.round(at[k + 1] - ch / 2f);
            v.layout(x, y, x + cw, y + ch);
        }
    }

    @Override public boolean shouldDelayChildPressedState() { return false; }
}
