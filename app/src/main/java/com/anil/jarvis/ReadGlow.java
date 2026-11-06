package com.anil.jarvis;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

/**
 * The one way Jarvis lights what he is reading out, everywhere it shows (6 Nov, Anil): only the word being said, in
 * yellow and bold — no colour behind the sentence or the word. Used by the floating button's card, the "Jarvis" panel,
 * the cards at the top (a message, an answer) and the chat in the app. One painter per text view: it moves its two
 * spans from word to word.
 */
final class ReadGlow {
    /** The word being said. */
    static final int WORD = 0xFFFFE27A;

    private final ForegroundColorSpan colour = new ForegroundColorSpan(WORD);
    private final StyleSpan bold = new StyleSpan(Typeface.BOLD);

    /** Lights [start, end) in sp (anything lit before goes dark); nothing when the range is not in the text. */
    void word(Spannable sp, int start, int end) {
        if (sp == null) return;
        clear(sp);
        int a = Math.max(0, Math.min(start, sp.length())), b = Math.max(a, Math.min(end, sp.length()));
        if (b <= a) return;
        sp.setSpan(colour, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(bold, a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Takes the light off. */
    void clear(Spannable sp) {
        if (sp == null) return;
        sp.removeSpan(colour);
        sp.removeSpan(bold);
    }
}
