package com.anil.jarvis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 🎬 Which clip the film Jarvis plays next (no Android here: desk-tested). A song plays: a dance clip for the song's
 * loudness now (calm / mid / high, from {@link CarDance}), never the same one twice in a row; after the first dance of a
 * song, once a song, a song set with the heroines (his switch) in its own order — she walks in, they dance, the second
 * heroine joins, all three dance, she leaves, then she leaves — each clip starting on the picture the one before ended
 * on, so nothing jumps. Not dancing: the clip for what Jarvis is doing (listening, thinking, talking: the half-body
 * clips, once they are made), else the idle one, else nothing (the still picture).
 */
final class FilmPlan {
    static final String STAGE = "stage";
    private final FilmPack pack;
    /** The picture the last clip ended on. */
    private String at = STAGE;
    private final ArrayDeque<FilmPack.Clip> queue = new ArrayDeque<>();
    private boolean setDone;
    private int solos, setTurn;
    private String last = "", before = "";
    private final Set<String> bad = new HashSet<>();

    FilmPlan(FilmPack pack) { this.pack = pack; }

    String at() { return at; }
    boolean inSet() { return !queue.isEmpty() || !STAGE.equals(at); }

    /** A new song: one set again, the first dance first. */
    void songStart() { setDone = false; solos = 0; }

    /** Back on the still picture (the dance ended, a clip broke): from the stage again, no set half done. */
    void reset() { at = STAGE; queue.clear(); }

    private final java.util.Map<String, Integer> fails = new java.util.HashMap<>();

    /**
     * A clip that couldn't play: after its second time, never again this trip (once may be the box busy for a moment).
     * {@code shownTo}: where the picture on the screen ends now (the clip still playing, or the stage when none): the
     * plan goes on from there (back to the stage the shortest way), so nobody vanishes in a jump.
     */
    void broken(FilmPack.Clip c, String shownTo) {
        if (c != null) {
            int n = fails.containsKey(c.id) ? fails.get(c.id) + 1 : 1;
            fails.put(c.id, n);
            if (n >= 2) bad.add(c.id);
        }
        queue.clear();
        at = shownTo == null || shownTo.isEmpty() ? STAGE : shownTo;
    }

    /** Never to be played again this trip. */
    boolean banned(FilmPack.Clip c) { return c != null && bad.contains(c.id); }

    /** The clip that plays now (it ends where its "to" says). */
    private FilmPack.Clip played(FilmPack.Clip c) {
        if (c == null) return null;
        before = last;
        last = c.id;
        at = c.to;
        return c;
    }

    /**
     * The next dance clip. {@code level}: 0 calm, 1 mid, 2 high, -1 not known (the lively ones). {@code sets}: his switch
     * for the song sets. Null: none at all (the still picture).
     */
    FilmPack.Clip nextDance(int level, boolean sets, Random rnd) {
        if (pack == null) return null;
        if (!queue.isEmpty()) return played(queue.poll());
        if (!STAGE.equals(at)) { // (left in the middle of a set: the shortest way back to the stage)
            List<FilmPack.Clip> back = path(at, STAGE);
            if (back == null || back.isEmpty()) { at = STAGE; } else { queue.addAll(back); return played(queue.poll()); }
        }
        if (sets && !setDone && solos >= 1 && level != CarDance.CALM) {
            List<String> names = pack.sets();
            for (int k = 0; k < names.size(); k++) {
                String s = names.get((setTurn + k) % names.size());
                List<FilmPack.Clip> p = setPath(s);
                if (p.isEmpty()) continue;
                setTurn = (setTurn + k + 1) % Math.max(1, names.size());
                setDone = true;
                queue.addAll(p);
                return played(queue.poll());
            }
        }
        FilmPack.Clip c = pickSolo(level, rnd);
        if (c != null) solos++;
        return played(c);
    }

    /** A solo dance for the level (the nearest level when the pack has none of it), not the last two. */
    FilmPack.Clip pickSolo(int level, Random rnd) {
        List<FilmPack.Clip> all = new ArrayList<>();
        for (FilmPack.Clip c : pack.clips) if (c.solo() && !bad.contains(c.id)) all.add(c);
        if (all.isEmpty()) return null;
        List<FilmPack.Clip> lv = new ArrayList<>();
        if (level < 0) { for (FilmPack.Clip c : all) if (c.levelNo() >= 1 || c.levelNo() < 0) lv.add(c); }
        else for (int d = 0; d <= 2 && lv.isEmpty(); d++) for (FilmPack.Clip c : all) if (Math.abs(c.levelNo() - level) == d || (d == 2 && c.levelNo() < 0)) lv.add(c);
        if (lv.isEmpty()) lv = all;
        List<FilmPack.Clip> ok = new ArrayList<>();
        for (FilmPack.Clip c : lv) if (!c.id.equals(last) && !c.id.equals(before)) ok.add(c);
        if (ok.isEmpty()) for (FilmPack.Clip c : lv) if (!c.id.equals(last)) ok.add(c);
        if (ok.isEmpty()) ok = lv;
        return ok.get(rnd.nextInt(ok.size()));
    }

    /**
     * A song set in order, each clip repeated {@code times}, checked as one chain from the stage back to the stage (each
     * clip starts where the one before ended). Empty: not a good chain (then it is never played).
     */
    List<FilmPack.Clip> setPath(String set) {
        List<FilmPack.Clip> parts = new ArrayList<>();
        for (FilmPack.Clip c : pack.clips) if ("set".equals(c.kind) && set.equals(c.set) && !bad.contains(c.id)) parts.add(c);
        Collections.sort(parts, Comparator.comparingInt(c -> c.order));
        List<FilmPack.Clip> out = new ArrayList<>();
        String now = STAGE;
        for (FilmPack.Clip c : parts) {
            if (!c.from.equals(now)) return Collections.emptyList();
            int n = c.from.equals(c.to) ? c.times : 1;
            for (int i = 0; i < n; i++) out.add(c);
            now = c.to;
        }
        return STAGE.equals(now) ? out : Collections.<FilmPack.Clip>emptyList();
    }

    /** The fewest set clips from one picture to another (null: no way). */
    List<FilmPack.Clip> path(String from, String to) {
        if (from.equals(to)) return new ArrayList<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        java.util.Map<String, FilmPack.Clip> how = new java.util.HashMap<>();
        java.util.Map<String, String> prev = new java.util.HashMap<>();
        todo.add(from);
        prev.put(from, "");
        while (!todo.isEmpty()) {
            String f = todo.poll();
            for (FilmPack.Clip c : pack.clips) {
                if (!"set".equals(c.kind) || bad.contains(c.id) || !c.from.equals(f) || c.to.equals(f) || prev.containsKey(c.to)) continue;
                prev.put(c.to, f);
                how.put(c.to, c);
                if (c.to.equals(to)) {
                    List<FilmPack.Clip> out = new ArrayList<>();
                    for (String s = to; !s.equals(from); s = prev.get(s)) out.add(0, how.get(s));
                    return out;
                }
                todo.add(c.to);
            }
        }
        return null;
    }

    /** Not dancing: the clip for this kind ("listen", "think", "talk", "idle", "hello"...), null: none (the still). */
    FilmPack.Clip forKind(String kind) {
        if (pack == null || kind == null) return null;
        for (FilmPack.Clip c : pack.clips) if (c.kind.equals(kind) && !bad.contains(c.id)) return c;
        return null;
    }

    /** What Jarvis is doing (OrbView's states) → the half-body clip's kind. */
    static String kindFor(int state) {
        switch (state) {
            case OrbView.LISTENING: return "listen";
            case OrbView.THINKING: return "think";
            case OrbView.SPEAKING: return "talk";
            default: return "idle";
        }
    }
}
