package com.anil.jarvis;

import java.util.Locale;

/** Picks the phone voice's language from the script of the text (for translations). */
final class Lang {
    private Lang() {}

    static Locale of(String text) {
        int te = 0, hi = 0, ta = 0, kn = 0, ml = 0, ar = 0, latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0x0C00 && c <= 0x0C7F) te++;
                else if (c >= 0x0900 && c <= 0x097F) hi++;
                else if (c >= 0x0B80 && c <= 0x0BFF) ta++;
                else if (c >= 0x0C80 && c <= 0x0CFF) kn++;
                else if (c >= 0x0D00 && c <= 0x0D7F) ml++;
                else if (c >= 0x0600 && c <= 0x06FF) ar++;
                else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            }
        }
        int max = Math.max(te, Math.max(hi, Math.max(ta, Math.max(kn, Math.max(ml, Math.max(ar, latin))))));
        if (max == 0 || max == te) return Locale.forLanguageTag("te-IN");
        if (max == hi) return Locale.forLanguageTag("hi-IN");
        if (max == ta) return Locale.forLanguageTag("ta-IN");
        if (max == kn) return Locale.forLanguageTag("kn-IN");
        if (max == ml) return Locale.forLanguageTag("ml-IN");
        if (max == ar) return Locale.forLanguageTag("ur-IN");
        // mostly English letters with a few Telugu words: still Telugu if Telugu is present
        return te > 0 ? Locale.forLanguageTag("te-IN") : Locale.forLanguageTag("en-IN");
    }
}
