package com.anil.jarvis;

/**
 * The phone's main-screen looks (7 Nov, stage 2 of his plan): the old one ("పాత రూపం", exactly as before) and the new
 * ones, 🛸 హోలో స్టేజ్, 💎 లిక్విడ్ గాజు, 🪐 కక్ష్య (the other seven come in stage 3). Pure Java: the desk tests check the
 * choices, the status order, the rain line and the charging words. Rules: the Jarvis orb is never restyled by a look; a
 * look changes only on his "✓ ఇదే పెట్టు"; each device keeps its own (the home tablet and the car box have their own
 * screens and never take the phone's look).
 */
final class MainLook {
    private MainLook() {}

    static final String OLD = "old", HOLO = "holo", LIQUID = "liquid", ORBIT = "orbit";

    /** {id, emoji, name, what it looks like}: the ones he can choose now. */
    static final String[][] LOOKS = {
            {OLD, "🗂️", "పాత రూపం", "ఇప్పుడు ఉన్నది, ఇంతకుముందు లాగే"},
            {HOLO, "🛸", "హోలో స్టేజ్", "వెలిగే నేల, ప్రొజెక్టర్ వెలుగులో Jarvis"},
            {LIQUID, "💎", "లిక్విడ్ గాజు", "కదిలే రంగుల మీద మందపాటి మెరిసే గాజు"},
            {ORBIT, "🪐", "కక్ష్య", "అంతరిక్షం, గ్రహం అంచు, కక్ష్యలో మీ లెక్కలు"}};

    /** The seven of the next stage (shown in the chooser as coming, never chosen yet). */
    static final String[][] LATER = {
            {"🌊", "లోతు సముద్రం"}, {"🔌", "సర్క్యూట్ చిప్"}, {"🌌", "అరోరా"}, {"🌧️", "వర్షం కిటికీ"},
            {"🌀", "వార్ప్ సొరంగం"}, {"☁️", "తెల్లటి స్టూడియో"}, {"🎚️", "సౌండ్ సిటీ"}};

    static boolean known(String id) {
        for (String[] l : LOOKS) if (l[0].equals(id)) return true;
        return false;
    }

    /** His look, or the old one for anything unknown. */
    static String valid(String id) { return known(id) ? id : OLD; }

    static String label(String id) {
        for (String[] l : LOOKS) if (l[0].equals(id)) return l[1] + " " + l[2];
        return LOOKS[0][1] + " " + LOOKS[0][2];
    }

    /** The page theme a look brings to the pages (Settings, All Features, Duty, photos) when "📄 పేజీలు కూడా" is on. */
    static String pageStyle(String look) {
        if (HOLO.equals(look)) return "hud";
        if (LIQUID.equals(look)) return "liquid";
        if (ORBIT.equals(look)) return "space";
        return null; // (the old look: the pages keep the theme he chose)
    }

    /** "Jarvis, లుక్ మార్చు" / "మెయిన్ స్క్రీన్ లుక్ మార్చు" / "change look": a short command only (opens 🎨 on the phone). */
    static boolean lookWords(String said) {
        if (said == null) return false;
        String t = said.toLowerCase(java.util.Locale.ROOT).replace("\u200c", "").replaceAll("[\\p{Punct}।]+", " ").replace("jarvis", " ")
                .replace("జార్విస్", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > 50 || t.split(" ").length > 5) return false;
        if (t.contains("సెట్టింగ్") || t.contains("settings") || t.contains("ఫోటో") || t.contains("photo") || t.contains("నా లుక్")
                || t.matches(".*\\b(my|how|at)\\b.*")) return false; // ("how do I look", "look at this", "my look": not the screen)
        return t.matches(".*(లుక్స్|లుక్లు|లుక్) (మార్చు|మార్చండి|మార్చేయి|మార్చేయ్|పేజీ).*")
                || t.matches(".*\\b(change|switch) (the )?((main|home) screen )?looks?\\b.*") || t.matches(".*\\b(open )?looks? page\\b.*")
                || t.matches("^open looks$");
    }

    /** What Jarvis says as the 🎨 page opens. */
    static String lookReply(String name) {
        return "సరే " + name + ", 🎨 లుక్ మార్చు పేజీ తెరుస్తున్నాను. బొమ్మ నొక్కితే ముందుగా చూపిస్తాను; \"ఇదే పెట్టు\" అంటేనే మారుతుంది.";
    }

    // ================================================================ the status cards (H1 / H2)

    /** {key, emoji, name}: every status a card can show (the old eight first, then the extras he can 📌). */
    static final String[][] STATUS = {
            {"battery", "⚡", "ఫోన్ బ్యాటరీ"}, {"weather", "⛅", "వాతావరణం"}, {"bike", "🏍️", "బైక్"}, {"next", "⏰", "తర్వాత"},
            {"mail", "📬", "మెసేజ్‌లు, కాల్స్"}, {"duty", "🗓️", "డ్యూటీ"}, {"net", "📶", "నెట్"}, {"wake", "🎙️", "వినడం"},
            {"time", "🕐", "సమయం"}, {"watch", "⌚", "వాచ్ బ్యాటరీ"}, {"steps", "👣", "అడుగులు"}, {"car", "🚗", "కారు"},
            {"milk", "🥛", "పాల లెక్క"}, {"money", "💰", "ఈ నెల ఖర్చు"}, {"tablet", "🏡", "అమ్మగారి టాబ్లెట్"}};

    /** The order he sees first (the old eight plus 📬); the extras only when he 📌 them. */
    static final String DEFAULT_ORDER = "battery,weather,bike,next,mail,duty,net,wake,time";

    static boolean isStatus(String k) {
        for (String[] s : STATUS) if (s[0].equals(k)) return true;
        return false;
    }

    static String statusName(String k) {
        for (String[] s : STATUS) if (s[0].equals(k)) return s[1] + " " + s[2];
        return k;
    }

    /** His order, cleaned: known keys only, each once; every one of the old eight is always somewhere (none lost). */
    static java.util.List<String> order(String saved) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String src = saved == null || saved.trim().isEmpty() ? DEFAULT_ORDER : saved;
        for (String k : src.split(",")) { k = k.trim(); if (isStatus(k) && !out.contains(k)) out.add(k); }
        for (String k : DEFAULT_ORDER.split(",")) if (!out.contains(k)) out.add(k);
        return out;
    }

    /** 📌: slot i shows key now; what was there goes to key's old place (or the end), so nothing disappears. */
    static String put(String saved, int slot, String key) {
        java.util.List<String> o = order(saved);
        if (!isStatus(key) || slot < 0) return String.join(",", o);
        int had = o.indexOf(key);
        if (slot >= o.size()) slot = o.size() - 1;
        String was = o.get(slot);
        if (had == slot) return String.join(",", o);
        if (had >= 0) { o.set(had, was); o.set(slot, key); }
        else { o.set(slot, key); o.add(was); }
        return String.join(",", o);
    }

    // ================================================================ H5: rain within the hour

    /**
     * The next hour with rain from open-meteo's hourly lists (times "2026-11-07T14:00", their chance % and mm), from
     * nowMin (minutes into the local day of the first time): {minutes from now (0 = now), chance %}, or null (no rain
     * in the next hour). Rain means a chance of 50 % or more, or 0.2 mm or more.
     */
    static int[] rainSoon(String[] times, int[] chance, double[] mm, String nowIso) {
        if (times == null || nowIso == null || nowIso.length() < 16) return null;
        long now = minutes(nowIso);
        for (int i = 0; i < times.length; i++) {
            long at = minutes(times[i]);
            if (at < 0) continue;
            if (at + 60 <= now) continue;     // (that hour is over)
            if (at - now > 60) break;         // (more than an hour away)
            int ch = chance != null && i < chance.length ? chance[i] : 0;
            double m = mm != null && i < mm.length ? mm[i] : 0;
            if (ch >= 50 || m >= 0.2) return new int[]{(int) Math.max(0, at - now), Math.max(ch, 0)};
        }
        return null;
    }

    /** "2026-11-07T14:30" as minutes since 2000 (local); -1 when it is not such a time. */
    static long minutes(String iso) {
        try {
            int y = Integer.parseInt(iso.substring(0, 4)), mo = Integer.parseInt(iso.substring(5, 7)), d = Integer.parseInt(iso.substring(8, 10));
            int h = Integer.parseInt(iso.substring(11, 13)), mi = Integer.parseInt(iso.substring(14, 16));
            long days = java.time.LocalDate.of(y, mo, d).toEpochDay();
            return days * 1440 + h * 60 + mi;
        } catch (Exception e) { return -1; }
    }

    /** "🌧️ 40 ని.లో వర్షం" / "🌧️ ఇప్పుడే వర్షం" and "అవకాశం 70%". */
    static String[] rainWords(int[] r) {
        if (r == null) return null;
        String when = r[0] <= 5 ? "ఇప్పుడే వర్షం" : r[0] + " ని.లో వర్షం";
        return new String[]{"🌧️ " + when, r[1] > 0 ? "అవకాశం " + r[1] + "%" : "జల్లులు"};
    }

    /** H3: the bike's % now during a charge he told Jarvis about (from% at t, to% at eta); -1 when unknown. */
    static int chargingPct(int from, int to, long t, long eta, long now) {
        if (from < 0 || to < from) return -1;
        if (eta <= t || now >= eta) return to;
        double f = Math.max(0, Math.min(1, (now - t) / (double) (eta - t)));
        return (int) Math.round(from + (to - from) * f);
    }

    // ================================================================ D: the charger in and out

    /** Plugged in / pulled out within this long of the last change: a loose cable (said once, not again). */
    static final long LOOSE_MS = 60_000L;

    /** What Jarvis says when the charger goes in ("చార్జింగ్ ఎక్కుతోంది, ఇప్పుడు 34 శాతం"). */
    static String plugWords(int pct) { return "చార్జింగ్ ఎక్కుతోంది, ఇప్పుడు " + pct + " శాతం."; }

    /** ... and when it comes out: from how much to how much it went up (or just where it is). */
    static String unplugWords(int from, int to) {
        if (from >= 0 && to > from) return "చార్జర్ తీశారు, " + from + " నుంచి " + to + " శాతం ఎక్కింది.";
        return "చార్జర్ తీశారు, ఇప్పుడు " + to + " శాతం.";
    }

    /**
     * Whether to speak (and show) for a change of the charger at now, the change before it at lastChange: not while
     * the changes keep coming within LOOSE_MS of each other (a loose cable: told once, at its first change).
     */
    static boolean sayNow(long now, long lastChange) {
        return lastChange <= 0 || now - lastChange >= LOOSE_MS;
    }

    /** The liquid's colour for a battery level: red (15 or less) → amber → green. */
    static int liquidColour(int pct) {
        if (pct <= 15) return 0xFFFF4D4F;
        if (pct <= 40) return 0xFFFFB020;
        return 0xFF3BD671;
    }
}
