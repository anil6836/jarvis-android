package com.anil.jarvis;

import java.util.Locale;

/** A very small JSON writer for the pure-Java money engine (so it runs and is tested on a PC without org.json). */
final class Jw {
    private final StringBuilder b = new StringBuilder();
    private boolean first = true;
    private final boolean arr;

    private Jw(boolean arr) {
        this.arr = arr;
        b.append(arr ? '[' : '{');
    }

    static Jw obj() { return new Jw(false); }

    static Jw arr() { return new Jw(true); }

    private void sep() {
        if (!first) b.append(',');
        first = false;
    }

    private void key(String k) {
        sep();
        str(b, k);
        b.append(':');
    }

    Jw put(String k, String v) {
        if (v == null) return this;
        key(k);
        str(b, v);
        return this;
    }

    Jw put(String k, long v) {
        key(k);
        b.append(v);
        return this;
    }

    Jw put(String k, double v) {
        key(k);
        num(b, v);
        return this;
    }

    Jw put(String k, boolean v) {
        key(k);
        b.append(v);
        return this;
    }

    Jw put(String k, Jw v) {
        if (v == null) return this;
        key(k);
        b.append(v.done());
        return this;
    }

    Jw nul(String k) {
        key(k);
        b.append("null");
        return this;
    }

    /** A value already written as JSON. */
    Jw raw(String k, String json) {
        if (json == null) return this;
        key(k);
        b.append(json);
        return this;
    }

    Jw add(String v) {
        sep();
        if (v == null) b.append("null"); else str(b, v);
        return this;
    }

    Jw add(long v) {
        sep();
        b.append(v);
        return this;
    }

    Jw add(double v) {
        sep();
        num(b, v);
        return this;
    }

    Jw add(Jw v) {
        sep();
        b.append(v == null ? "null" : v.done());
        return this;
    }

    private String closed;

    String done() {
        if (closed == null) closed = b.toString() + (arr ? ']' : '}');
        return closed;
    }

    @Override public String toString() { return done(); }

    private static void num(StringBuilder b, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) { b.append("null"); return; }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) b.append((long) v);
        else b.append(String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", ""));
    }

    static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break; // (safe inside a page's script too)
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
