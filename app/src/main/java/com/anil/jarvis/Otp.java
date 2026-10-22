package com.anil.jarvis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔐 One-time codes (OTP, verification / login / security codes, PINs, passwords): shown on Anil's own screen, never read
 * aloud (people around would hear) and never handed to the AI. Pure, for tests. When in doubt it hides: a code said is
 * worse than a number left out.
 */
final class Otp {
    private Otp() {}

    /**
     * The code itself: 4-10 digits, or digits in groups (123-456, 123 456, 12 34 56, 4829-13); any script's digits; not part
     * of a longer number or an amount (Telugu letters next to it are fine).
     */
    private static final String DIG = "(?<![\\p{Nd}.,])(?:\\p{Nd}{2,5}(?:[- ]\\p{Nd}{2,5}){1,3}|\\p{Nd}{4,10})(?![\\p{Nd}.,]\\p{Nd}|\\p{Nd})";
    /** Words that say "a code" (English as whole words, so "shopping" isn't "pin"). */
    private static final String KEY = "(?:\\b(?:otp|one[ -]?time|verification|verify|passcode|pass code|security code|confirmation code|login code"
            + "|auth(?:entication)? code|2fa|code|pin|mpin|password|passwd)\\b|ఓటీపీ|ఓ\\.?టి\\.?పి|కోడ్|పిన్|పాస్‌?వర్డ్)";
    /** After the digits: those words, or "…sign in / log in" ("Use 482913 to sign in"). */
    private static final String AFTER = "(?:" + KEY + "|\\b(?:sign[ -]?in|log[ -]?in)\\b)";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern KEY_P = Pattern.compile(KEY, FLAGS);
    private static final Pattern AFTER_P = Pattern.compile(AFTER, FLAGS);
    private static final Pattern DIGITS = Pattern.compile(DIG);

    /**
     * A message that carries a one-time code: a code word and then digits, or digits and then a code word (LaterMessages
     * never keeps one either). Linear: searched, not matched with ".*" (it runs for every message and every spoken line).
     */
    static boolean has(String t) {
        if (t == null || t.isEmpty()) return false;
        Matcher k = KEY_P.matcher(t);
        if (k.find() && DIGITS.matcher(t).find(k.end())) return true;
        Matcher d = DIGITS.matcher(t);
        return d.find() && AFTER_P.matcher(t).find(d.end());
    }

    /** On his screen: as it is. */
    static String shown(String t) { return t == null ? "" : t; }

    /** Said aloud / sent to the AI: the code's digits are left out ("కోడ్ స్క్రీన్‌పై చూడండి"). */
    static String spoken(String t) {
        if (!has(t)) return t == null ? "" : t;
        return mask(t);
    }

    /** Every code-like number left out, whatever the words around it (a summary of a message that had a code). */
    static String mask(String t) {
        if (t == null) return "";
        Matcher m = DIGITS.matcher(t);
        return m.replaceAll("(కోడ్ స్క్రీన్‌పై చూడండి)");
    }
}
