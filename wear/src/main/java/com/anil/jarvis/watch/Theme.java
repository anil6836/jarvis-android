package com.anil.jarvis.watch;

import android.content.Context;

/** W5: the watch's colours follow the phone's theme (Settings → థీమ్ on the phone): blue + gold, ice blue, or golden amber. */
final class Theme {
    private Theme() {}

    private static volatile String id = "";
    /** The ring colour, the marks' colour, the text accent. */
    static volatile int main = 0xFF38BDF8, mark = 0xFFF2B24C, accent = 0xFF74E4FF;

    /** Looked at again when the phone sends its settings or news. */
    static void refresh(Context c) {
        String t = Link.theme(c);
        if (t.equals(id)) return;
        id = t;
        switch (t) {
            case "blue": main = 0xFF22D3EE; mark = 0xFF74E4FF; accent = 0xFF74E4FF; break;
            case "gold": main = 0xFFF2B24C; mark = 0xFFFDE68A; accent = 0xFFFFD27A; break;
            default: main = 0xFF38BDF8; mark = 0xFFF2B24C; accent = 0xFF74E4FF;
        }
    }
}
