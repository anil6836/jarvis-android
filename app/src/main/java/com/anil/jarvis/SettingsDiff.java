package com.anil.jarvis;

/**
 * 7 Nov (v1.0.197): which settings a save of the Settings screen writes — only those whose control he changed since
 * the screen was built / last saved (pure Java, desk-tested). A setting changed elsewhere meanwhile (the panel, Jarvis's
 * tools, the watch, another Settings screen) is never written over by a switch he didn't touch.
 */
final class SettingsDiff {
    private SettingsDiff() {}

    /** The keys to write: changed since 'was' (no 'was' at all: every key, as before). */
    static java.util.List<String> changed(java.util.Map<String, Object> was, java.util.Map<String, Object> now) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (now == null) return out;
        for (java.util.Map.Entry<String, Object> e : now.entrySet())
            if (was == null || !java.util.Objects.equals(e.getValue(), was.get(e.getKey()))) out.add(e.getKey());
        return out;
    }

    /** What goes with a save of his: {key, value} pairs (the wake word is not left paused; his listening time is his). */
    static java.util.Map<String, Boolean> companions(java.util.List<String> keys) {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        if (!keys.isEmpty()) out.put("wake_paused", false); // (as before: a save of his un-pauses the wake word)
        if (keys.contains("listen_window")) out.put("listen_window_set", true);
        return out;
    }
}
