package com.anil.jarvis;

import android.text.Layout;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Keeps the line Jarvis is saying in view (scrolls along) and, for stories and jokes, highlights the word,
 * like reading along with a karaoke line. The spoken text is the reply with markdown symbols and
 * links taken out, so its positions are mapped back onto the text shown.
 */
final class Karaoke {
    private final BackgroundColorSpan bg = new BackgroundColorSpan(0x5548D1FF);
    private final ForegroundColorSpan fg = new ForegroundColorSpan(0xFFFFFFFF);
    private TextView view;
    private ScrollView scroll;
    private String spoken;
    private int[] map;          // index in spoken text -> index in the shown text
    private int lastLine = -1;

    /** Finds which reply (newest first) is being spoken, the first time a new spoken text comes in. */
    void word(String spokenText, int start, int end, boolean highlight, List<TextView> candidates, ScrollView sv) {
        if (spokenText == null || start < 0 || end <= start) return;
        if (!spokenText.equals(spoken) || view == null) {
            clear();
            spoken = spokenText;
            for (int i = candidates.size() - 1; i >= 0 && i >= candidates.size() - 4; i--) {
                TextView tv = candidates.get(i);
                int[] m = align(spokenText, tv.getText().toString());
                if (m != null) { view = tv; map = m; scroll = sv; break; }
            }
            if (view == null) return;
            // make the text spannable once (keeps selection working)
            if (!(view.getText() instanceof Spannable)) view.setText(view.getText(), TextView.BufferType.SPANNABLE);
        }
        if (view == null || map == null) return;
        int s = map[Math.min(start, map.length - 1)];
        int e = map[Math.min(end - 1, map.length - 1)] + 1;
        CharSequence cs = view.getText();
        if (!(cs instanceof Spannable) || e > cs.length() || s >= e) return;
        Spannable sp = (Spannable) cs;
        sp.removeSpan(bg);
        sp.removeSpan(fg);
        if (highlight) { // stories and jokes; other answers only scroll along
            sp.setSpan(bg, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(fg, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        follow(s);
    }

    /** Takes the highlight off (speech finished or stopped). */
    void clear() {
        if (view != null && view.getText() instanceof Spannable) {
            Spannable sp = (Spannable) view.getText();
            sp.removeSpan(bg);
            sp.removeSpan(fg);
        }
        view = null;
        map = null;
        spoken = null;
        scroll = null;
        lastLine = -1;
    }

    /** Scrolls so the line being read stays in the upper-middle of the screen. */
    private void follow(int offset) {
        if (scroll == null || view == null) return;
        Layout layout = view.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(offset);
        if (line == lastLine) return;
        lastLine = line;
        int y = layout.getLineTop(line) + view.getPaddingTop();
        View v = view;
        while (v != scroll) {             // position inside the scrolled content
            y += v.getTop();
            if (!(v.getParent() instanceof View)) return;
            v = (View) v.getParent();
        }
        int h = scroll.getHeight();
        int top = scroll.getScrollY();
        if (y < top + h / 8 || y > top + h * 3 / 5) scroll.smoothScrollTo(0, Math.max(0, y - h / 3));
    }

    /**
     * Spoken text is the shown text with some characters (markdown, links) removed: match it up
     * character by character. null when it isn't this text.
     */
    static int[] align(String spoken, String shown) {
        if (spoken.isEmpty() || shown.isEmpty()) return null;
        int[] m = new int[spoken.length()];
        int j = 0, missed = 0;
        for (int i = 0; i < spoken.length(); i++) {
            char c = spoken.charAt(i);
            int k = j;
            // look ahead a little for the same character (skipping removed symbols or a link)
            int limit = Math.min(shown.length(), j + 200);
            while (k < limit && shown.charAt(k) != c) k++;
            if (k < limit) { m[i] = k; j = k + 1; }
            else { m[i] = Math.max(0, Math.min(shown.length() - 1, j)); missed++; }
        }
        return missed * 10 > spoken.length() ? null : m;
    }
}
