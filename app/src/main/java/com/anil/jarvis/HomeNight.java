package com.anil.jarvis;

import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/**
 * 🌙 The good-night scene on the home tablet (Anil, 24 Oct): అమ్మగారు says "పడుకుంటాను" (or presses 🌙 at her bedtime) and
 * one word does it all — her AC on (asked first: "AC ఆన్ చేయమంటారా?", by voice or the big ✓ / ✕; never on a cold night,
 * never her son's AC), soft sleep songs that stop by themselves in 30 minutes, the screen dims and Jarvis lies down under
 * his blanket. The bedtime check (doors, gas) is said now if it wasn't yet, so it never wakes her later. In the day the
 * same words are a nap: Jarvis keeps quiet for an hour (her tablets are still reminded).
 */
final class HomeNight {
    private HomeNight() {}

    /** Her words: she is going to sleep ("పడుకుంటాను", "గుడ్ నైట్", "శుభరాత్రి"); not "నిద్ర పాటలు", not someone else. Pure. */
    static boolean asks(String t) {
        if (t == null) return false;
        String s = t.toLowerCase(Locale.ROOT);
        if (s.matches("(?s).*(పాట|అబ్బాయి|ఆయన|వాడు|ఆమె|పిల్లలు|పడుకున్నా[వా]|పడుకున్నారా|ఎప్పుడు).*")) return false;
        return s.matches("(?s).*(పడుకుంటా|పడుకుంటున్నా|పడుకోబోతున్నా|పడుకోవాలి|పడుకుందాం|నిద్రపోతా|నిద్ర పోతా|నిద్రపోతున్నా|నిద్రొస్తోంది|నిద్ర వస్తోంది"
                + "|గుడ్ ?నైట్|గుడ్‌నైట్|శుభరాత్రి|good ?night).*");
    }

    /** The scene's hours (else her words are a nap): 7 in the evening to 5 in the morning. */
    static boolean nightNow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 5;
    }

    /** Her AC's "on" link in the smart-home box: a "… ac on" line that is not her son's ("anil ac", "son ac"). */
    static String acOn(Context c) {
        String pick = null;
        for (String line : new Prefs(c).smartUrls().split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), url = line.substring(eq + 1).trim();
            if (!url.startsWith("http") || !name.endsWith(" on") || !name.matches(".*\\bac\\b.*") || name.matches(".*\\b(anil|son)\\b.*")) continue;
            if (name.matches(".*\\b(amma|mom|mother)\\b.*")) return url; // (hers by name first)
            if (pick == null) pick = url;
        }
        return pick;
    }

    /** The AC is offered: her AC has a link and it is not a cold night (unknown weather: offered; she can say no). */
    static boolean offerAc(Context c) {
        if (acOn(c) == null) return false;
        float temp = c.getSharedPreferences("home_screen", Context.MODE_PRIVATE).getFloat("w_temp", Float.NaN);
        long at = c.getSharedPreferences("home_screen", Context.MODE_PRIVATE).getLong("w_at", 0);
        boolean fresh = System.currentTimeMillis() - at < 6 * 3600_000L;
        return !(fresh && !Float.isNaN(temp) && temp < 20f);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean asking = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long askToken;

    /** The 🌙 button or her words: the AC is asked first (screen and voice), else the scene now. What Jarvis says. */
    static String start(Context c) {
        final Context a = c.getApplicationContext();
        String w = HomeCare.who(a);
        if (!nightNow()) { // an afternoon nap
            HomeCare.sp(a).edit().putLong("quiet_until", System.currentTimeMillis() + 60 * 60_000L).apply();
            return "సరే " + w + ", గంటసేపు నిశ్శబ్దంగా ఉంటాను. టాబ్లెట్ టైమ్ అయితే మాత్రం గుర్తుచేస్తాను. హాయిగా విశ్రాంతి తీసుకోండి.";
        }
        if (!offerAc(a)) return run(a, false);
        asking.set(true);
        final long token = ++askToken;
        HomeCare.setPending("night_ac");
        String q = w + ", పడుకునే ముందు AC ఆన్ చేయమంటారా?";
        MainActivity.homeNightAsk(q); // (big ✓ అవును / ✕ వద్దు on the screen too)
        HomeCare.later(() -> { // no answer in a while: the rest of the scene without the AC
            if (!asking.get() || token != askToken) return;
            String line = answer(a, false);
            if (line != null) HomeCare.say(a, line, "calm");
        }, 40_000L);
        return q;
    }

    /** Her answer to the AC question (voice or the screen's button); null when nothing was being asked. */
    static String answer(Context c, boolean ac) {
        if (!asking.compareAndSet(true, false)) return null; // (her ✓ and her voice at once: only the first one)
        if ("night_ac".equals(HomeCare.pending())) HomeCare.clearPending();
        MainActivity.homeNightAskGone();
        return run(c.getApplicationContext(), ac);
    }

    static boolean asking() { return asking.get(); }

    /** The scene itself; returns the good-night line. */
    static String run(Context c, boolean ac) {
        String w = HomeCare.who(c);
        StringBuilder b = new StringBuilder("శుభరాత్రి " + w + "! ");
        if (ac) {
            final String url = acOn(c);
            if (url != null) {
                new Thread(() -> { try { Http.getText(url); } catch (Exception ignored) {} }, "home-night-ac").start();
                b.append(Net.online(c) ? "AC ఆన్ చేస్తున్నాను. " : "నెట్ లేదు, AC ఆన్ చేయలేకపోయాను. ");
            }
        }
        boolean songs = false;
        try {
            if (!HomeSongs.playing() && !SoundService.radioOn) {
                HomeCare.sleepSongs(c); // (soft, and they stop by themselves in 30 minutes)
                songs = HomeSongs.playing() || SoundService.radioOn || Net.online(c);
            }
        } catch (Exception ignored) {}
        if (songs) b.append("మెల్లగా పాటలు పెడుతున్నాను, అరగంటలో అవే ఆగిపోతాయి. ");
        // (after midnight it is the next day's check: that one stays for its evening)
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 12 && HomeCare.markDoneToday(c, "bed")) b.append("తలుపులు వేశారా, గ్యాస్ ఆఫ్ చేశారా ఒకసారి చూసుకోండి. "); // (the 21:30 check: now, not later)
        b.append("దేవుడు మిమ్మల్ని కాపాడును, హాయిగా పడుకోండి.");
        MainActivity.homeGoodNight();
        return b.toString();
    }
}
