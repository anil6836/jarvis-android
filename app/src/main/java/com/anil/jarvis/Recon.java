package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "ఇక్కడ ఏం జరిగింది?": from a few photos (or a short video) of a place or thing, the AI works out the likely order of
 * events and tells it as numbered steps — a scratch on the bike, a broken wall, a spill. Always an estimate, said so;
 * people are described only by what they do, never who they are; no blame.
 */
final class Recon {
    private Recon() {}

    static String system() {
        return "You are JARVIS, reconstructing for Anil (Telangana) what probably happened at a place or to a thing, from his photos. "
                + "Answer only in simple spoken Telugu. Reply with ONLY this JSON: {\"title\":\"short Telugu title\",\"story\":\"3-5 Telugu sentences: the likely sequence\","
                + "\"steps\":[{\"n\":1,\"when\":\"మొదట / తర్వాత / చివరగా or a time if visible\",\"what\":\"one short Telugu line\",\"evidence\":\"what in which photo shows it (photo number)\",\"sure\":0-100}],"
                + "\"caution\":\"one Telugu line: what to check or do now (safety first)\",\"confidence\":0-100}. "
                + "4 to 8 steps. This is an estimate from pictures: say what the pictures show and what they suggest, never invent details. "
                + "People: only what they do or wear; never who they are, never blame a named person. Nothing about religion, caste, health. No ID numbers or number plates.";
    }

    static String prompt(int photos, String note) {
        return "This picture is a grid of " + photos + " numbered photos (1, 2, 3… left to right, top to bottom), taken in that order"
                + (note == null || note.trim().isEmpty() ? "" : ". Anil says: \"" + note.trim() + "\"") + ". Work out what happened and answer in the JSON.";
    }

    /** The photos as one numbered grid (2 across), each at most 640 px wide. */
    static Bitmap grid(List<Bitmap> pics) {
        int n = pics.size();
        return grid(pics, n == 1 ? 1 : n > 6 ? 3 : 2);
    }

    static Bitmap grid(List<Bitmap> pics, int cols) {
        int n = pics.size(), rows = (n + cols - 1) / cols;
        int cw = 640, ch = 480, gap = 10;
        Bitmap out = Bitmap.createBitmap(cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF202020);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
        t.setColor(0xFFFFFFFF);
        t.setTextSize(40);
        t.setFakeBoldText(true);
        bg.setColor(0xCC000000);
        for (int i = 0; i < n; i++) {
            Bitmap b = pics.get(i);
            int x = gap + (i % cols) * (cw + gap), y = gap + (i / cols) * (ch + gap);
            float s = Math.min(cw / (float) b.getWidth(), ch / (float) b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            RectF dst = new RectF(x + (cw - w) / 2, y + (ch - h) / 2, x + (cw + w) / 2, y + (ch + h) / 2);
            c.drawBitmap(b, null, dst, f);
            c.drawRect(x, y, x + 64, y + 54, bg);
            c.drawText(String.valueOf(i + 1), x + 16, y + 42, t);
        }
        return out;
    }

    /** Up to `max` frames spread over a video (its first and last moments included); each at most 900 px. */
    static List<Bitmap> videoFrames(Context c, Uri video, int max) {
        List<Bitmap> out = new ArrayList<>();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, video);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = d == null ? 0 : Long.parseLong(d);
            if (ms <= 0) return out;
            int n = Math.max(2, Math.min(max, (int) (ms / 1500) + 1));
            for (int i = 0; i < n; i++) {
                long at = n == 1 ? ms / 2 : (ms - 200) * i / (n - 1) + 100;
                Bitmap f = r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) out.add(JarvisCamera.scaleTo(f, 900));
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return out;
    }

    /** ID, policy, account and card numbers never get written: every word field of the story is masked (FloatBubble.noIds). */
    static void scrub(JSONObject o) {
        if (o == null) return;
        try {
            for (String k : new String[]{"title", "story", "caution", "say", "name", "what", "size_note", "material", "condition", "value", "keep", "light", "air", "layout", "fan_ac"})
                if (o.has(k) && o.opt(k) instanceof String) o.put(k, FloatBubble.noIds(o.optString(k)));
            for (String k : new String[]{"steps", "marks", "issues", "tips", "views", "items", "parts"}) {
                JSONArray a = o.optJSONArray(k);
                for (int i = 0; a != null && i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v instanceof String) a.put(i, FloatBubble.noIds((String) v));
                    else if (v instanceof JSONObject) {
                        JSONObject s = (JSONObject) v;
                        for (String f : new String[]{"when", "what", "evidence", "label", "name", "info", "section"}) if (s.has(f) && s.opt(f) instanceof String) s.put(f, FloatBubble.noIds(s.optString(f)));
                        scrub(s);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** The AI's reply read; null when there is nothing usable. */
    static JSONObject parse(String reply) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            JSONArray steps = o.optJSONArray("steps");
            if (steps == null || steps.length() == 0) { if (o.optString("story").isEmpty()) return null; o.put("steps", new JSONArray()); }
            if (o.optString("title").isEmpty()) o.put("title", "ఇక్కడ ఏం జరిగింది");
            scrub(o);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** The steps as one spoken text (for ▶️ play all). */
    static String spoken(JSONObject recon) {
        StringBuilder b = new StringBuilder(recon.optString("story"));
        JSONArray steps = recon.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            b.append(' ').append(s.optString("when").isEmpty() ? "" : s.optString("when") + ", ").append(s.optString("what"));
            if (!b.toString().endsWith(".")) b.append('.');
        }
        String caution = recon.optString("caution");
        if (!caution.isEmpty()) b.append(" జాగ్రత్త: ").append(caution);
        int conf = recon.optInt("confidence", -1);
        if (conf >= 0) b.append(" ఇది బొమ్మల నుంచి అంచనా, సుమారు ").append(conf).append(" శాతం నమ్మకం.");
        return b.toString().trim();
    }

    // ================================================================ a room, from a slow turn

    static String roomSystem() {
        return "You are JARVIS, looking at a room for Anil (Telangana) from a few photos taken while he turned slowly. Answer only in simple Telugu. "
                + "Reply with ONLY this JSON: {\"title\":\"ఏ గది\",\"size_m\":[length,width,height] (rough estimates from doors ≈2 m, tiles, beds, people),\"size_note\":\"how you guessed\","
                + "\"light\":\"natural light: where from, enough or not, 1 line\",\"air\":\"ventilation: windows / cross-breeze, 1 line\",\"layout\":\"what is where, 1-2 lines\","
                + "\"fan_ac\":\"best place for a fan / AC / cooler and why\",\"issues\":[\"up to 5 short lines: damp, cracks, clutter, cables, poor light, safety\"],"
                + "\"tips\":[\"up to 5 short lines: cheap improvements\"],\"confidence\":0-100}. Estimates only; never identify people; nothing about religion or private matters.";
    }

    static String roomPrompt(int photos) {
        return "This is a grid of " + photos + " numbered photos of one room, taken in order while turning. Describe the room in the JSON.";
    }

    static JSONObject parseRoom(String reply) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            scrub(o);
            if (o.optString("title").isEmpty()) o.put("title", "గది");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static String roomText(JSONObject r) {
        StringBuilder b = new StringBuilder();
        JSONArray sz = r.optJSONArray("size_m");
        if (sz != null && sz.length() >= 2) {
            double l = sz.optDouble(0), w = sz.optDouble(1);
            b.append("సుమారు ").append(fmt(l)).append(" × ").append(fmt(w)).append(" మీటర్లు");
            if (sz.length() >= 3) b.append(", ఎత్తు ").append(fmt(sz.optDouble(2)));
            b.append(" (సుమారు ").append(Math.round(l * w * 10.764)).append(" చ.అ).");
            if (!r.optString("size_note").isEmpty()) b.append(" ").append(r.optString("size_note")).append('.');
        }
        if (!r.optString("light").isEmpty()) b.append(" వెలుతురు: ").append(r.optString("light")).append('.');
        if (!r.optString("air").isEmpty()) b.append(" గాలి: ").append(r.optString("air")).append('.');
        if (!r.optString("layout").isEmpty()) b.append(" అమరిక: ").append(r.optString("layout")).append('.');
        if (!r.optString("fan_ac").isEmpty()) b.append(" ఫ్యాన్ / AC: ").append(r.optString("fan_ac")).append('.');
        JSONArray is = r.optJSONArray("issues");
        if (is != null && is.length() > 0) { b.append(" గమనించాల్సినవి: "); for (int i = 0; i < is.length(); i++) b.append(i == 0 ? "" : "; ").append(is.optString(i)); b.append('.'); }
        JSONArray tips = r.optJSONArray("tips");
        if (tips != null && tips.length() > 0) { b.append(" సలహాలు: "); for (int i = 0; i < tips.length(); i++) b.append(i == 0 ? "" : "; ").append(tips.optString(i)); b.append('.'); }
        return b.toString().replaceAll("\\.\\.", ".").trim();
    }

    // ================================================================ a space (room / machine) as a 3D model: parts per view

    static String spaceSystem() {
        return "You are JARVIS, mapping a space or a big machine for Anil (Telangana) from numbered photos taken while he turned around (a room) or walked around it (a bike, a machine). "
                + "Answer only in simple Telugu. Reply with ONLY this JSON: {\"title\":\"ఏమిటి (గది / బైక్ / యంత్రం పేరు)\",\"say\":\"2-3 Telugu lines about the whole\","
                + "\"views\":[{\"n\":1,\"label\":\"short Telugu name of this side / direction (ఉత్తరం గోడ, ముందు వైపు…)\",\"items\":[{\"name\":\"Telugu name\",\"box\":[x0,y0,x1,y1],"
                + "\"info\":\"one short Telugu line: what it is / does\",\"section\":\"group name (ఫర్నిచర్, కరెంట్, ఇంజిన్, బ్రేక్…)\",\"fault\":false,\"fault_why\":\"\"}]}]}. "
                + "box = integers 0-1000 of THAT numbered photo's own width and height (not of the whole grid). Up to 12 items per photo: the real, visible things (furniture, doors, windows, "
                + "switches, parts of the machine, pipes, wires). People only as 'మనిషి', never who. fault=true with fault_why only for something clearly wrong (crack, rust, leak, loose).";
    }

    static String spacePrompt(int photos, String note) {
        return "This picture is a grid of " + photos + " numbered photos (1, 2, 3… left to right, top to bottom), each 640×480 inside the grid, taken in order"
                + (note == null || note.trim().isEmpty() ? "" : "; Anil says: \"" + note.trim() + "\"") + ". Give one view per photo, with that photo's items and boxes.";
    }

    /** The AI's views read into layers for a hologram board: [{name, items:[…]}] (ScanBrain's item shape), at most `photos`. */
    static JSONArray parseSpace(String reply, int photos, String[] headings) {
        JSONArray layers = new JSONArray();
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return layers;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            scrub(o);
            JSONArray views = o.optJSONArray("views");
            for (int i = 0; i < photos; i++) {
                JSONObject v = null;
                for (int k = 0; views != null && k < views.length(); k++) { JSONObject vk = views.optJSONObject(k); if (vk != null && vk.optInt("n", k + 1) == i + 1) { v = vk; break; } }
                if (v == null && views != null && i < views.length()) v = views.optJSONObject(i);
                JSONArray items = v == null ? new JSONArray() : v.optJSONArray("items");
                if (items == null) items = new JSONArray();
                JSONArray clean = new JSONArray();
                for (int k = 0; k < items.length() && k < 14; k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it == null || it.optString("name").isEmpty()) continue;
                    it.put("n", clean.length() + 1);
                    clean.put(it);
                }
                String label = v == null ? "" : v.optString("label");
                String head = headings != null && i < headings.length && headings[i] != null ? headings[i] : "";
                String name = "దృశ్యం " + (i + 1) + (label.isEmpty() ? "" : " · " + label) + (head.isEmpty() ? "" : " (" + head + ")");
                layers.put(new JSONObject().put("name", name).put("photo", "view" + i + ".jpg").put("items", clean));
            }
            layers.put(new JSONObject().put("_title", o.optString("title")).put("_say", o.optString("say"))); // (the whole's words, last)
        } catch (Exception ignored) {}
        return layers;
    }

    // ================================================================ a thing from all round: views + a shape model (360°)

    static String objectSystem() {
        return "You are JARVIS, modelling ONE object for Anil (Telangana) from numbered photos he took walking around it (and maybe from above / below). "
                + "Answer only in simple Telugu words. Reply with ONLY this JSON: {\"title\":\"short Telugu name of the object\",\"say\":\"2-3 Telugu lines about it\","
                + "\"dims_cm\":{\"width\":0,\"depth\":0,\"height\":0,\"note\":\"how you estimated (reference object / rough)\"},"
                + "\"views\":[{\"n\":1,\"label\":\"short Telugu name of this side (ముందు వైపు, ఎడమ వైపు, పై నుంచి…)\",\"items\":[{\"name\":\"Telugu part name\",\"box\":[x0,y0,x1,y1],"
                + "\"info\":\"one short Telugu line\",\"section\":\"group\",\"fault\":false,\"fault_why\":\"\"}]}],"
                + "\"shape\":{\"parts\":[{\"id\":\"p1\",\"name\":\"Telugu part name\",\"type\":\"cylinder|box|ring|sphere|cone\",\"c\":[x,y,z],\"r\":0,\"r2\":0,\"h\":0,"
                + "\"s\":[sx,sy,sz],\"axis\":\"z\",\"color\":\"#rrggbb\",\"info\":\"one short Telugu line\",\"section\":\"group\",\"seen\":true}]},"
                + "\"top_seen\":true,\"bottom_seen\":false,\"confidence\":0-100}. "
                + "Rules: box = integers 0-1000 of THAT numbered photo's own width and height. Up to 10 items per photo (the real visible parts: bolts, flanges, holes, handles, labels, wheels). "
                + "shape = the object as 3 to 12 simple solids in centimetres, origin at the object's centre, z up. Frame: photo 1 is the FRONT - its camera stands on the -y side looking towards +y, "
                + "+x is to that camera's right; a photo whose heading is H degrees more than photo 1's was taken from a viewpoint moved H degrees clockwise (seen from above) round the object. "
                + "Solids: cylinder/cone (r, r2 top radius for cone, h along axis), ring (r outer, r2 inner, h), "
                + "box (s = full sizes), sphere (r); c = centre of each solid; axis x|y|z. Make the solids touch like the real thing (a cable drum = two big thin cylinders + a core cylinder + a centre hole as a ring). "
                + "seen=false for a solid you did not actually see (an unphotographed bottom). Colours like the real material. People only as 'మనిషి', never who. No ID numbers, phone numbers or plates.";
    }

    /** photos: the numbered grid; where: per photo "around 45°" / "from above" / "from below". */
    static String objectPrompt(int photos, String[] where, String note) {
        StringBuilder b = new StringBuilder("This picture is a grid of " + photos + " numbered photos (1, 2, 3… left to right, top to bottom) of one object: ");
        for (int i = 0; i < photos; i++) b.append(i == 0 ? "" : ", ").append(i + 1).append(" = ").append(where != null && i < where.length && where[i] != null ? where[i] : "around");
        b.append(". Give one view per photo with its items, the sizes, and the shape model.");
        if (note != null && !note.trim().isEmpty()) b.append(" Anil says: \"").append(note.trim()).append("\".");
        return b.toString();
    }

    static final java.util.Set<String> SHAPE_TYPES = new java.util.HashSet<>(java.util.Arrays.asList("cylinder", "box", "ring", "sphere", "cone"));

    /** The AI's object read: {title, say, dims_cm, views:[{label, items}], shape:{parts}, top_seen, bottom_seen, confidence}; null when unusable. Bad solids are dropped. */
    static JSONObject parseObject(String reply, int photos) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            scrub(o);
            JSONArray views = o.optJSONArray("views"), outViews = new JSONArray();
            for (int i = 0; i < photos; i++) {
                JSONObject v = null;
                for (int k = 0; views != null && k < views.length(); k++) { JSONObject vk = views.optJSONObject(k); if (vk != null && vk.optInt("n", k + 1) == i + 1) { v = vk; break; } }
                if (v == null && views != null && i < views.length()) v = views.optJSONObject(i);
                JSONArray items = v == null ? null : v.optJSONArray("items"), clean = new JSONArray();
                for (int k = 0; items != null && k < items.length() && k < 12; k++) {
                    JSONObject it = items.optJSONObject(k);
                    JSONArray bx = it == null ? null : it.optJSONArray("box");
                    if (it == null || it.optString("name").isEmpty() || bx == null || bx.length() != 4) continue;
                    it.put("n", clean.length() + 1);
                    clean.put(it);
                }
                outViews.put(new JSONObject().put("n", i + 1).put("label", v == null ? "" : v.optString("label")).put("photo", "view" + i + ".jpg").put("items", clean));
            }
            o.put("views", outViews);
            JSONObject shape = o.optJSONObject("shape");
            JSONArray parts = shape == null ? null : shape.optJSONArray("parts"), good = new JSONArray();
            for (int i = 0; parts != null && i < parts.length() && good.length() < 14; i++) {
                JSONObject pt = parts.optJSONObject(i);
                if (pt == null) continue;
                String type = pt.optString("type").toLowerCase(java.util.Locale.ROOT).trim();
                if (!SHAPE_TYPES.contains(type)) continue;
                JSONArray c = pt.optJSONArray("c");
                if (c == null || c.length() != 3) continue;
                double r = pt.optDouble("r", 0), h = pt.optDouble("h", 0);
                JSONArray sz = pt.optJSONArray("s");
                if (type.equals("box") && (sz == null || sz.length() != 3)) continue;
                if (!type.equals("box") && (r <= 0 || Double.isNaN(r))) continue;
                if ((type.equals("cylinder") || type.equals("ring") || type.equals("cone")) && h <= 0) continue;
                if (type.equals("ring") && !(pt.optDouble("r2", 0) > 0 && pt.optDouble("r2") < r)) pt.put("r2", r * 0.3);
                if (type.equals("cone") && !(pt.optDouble("r2", -1) >= 0)) pt.put("r2", 0);
                pt.put("type", type);
                if (pt.optString("id").isEmpty()) pt.put("id", "p" + (good.length() + 1));
                if (pt.optString("name").isEmpty()) pt.put("name", "భాగం " + (good.length() + 1));
                String ax = pt.optString("axis", "z").toLowerCase(java.util.Locale.ROOT);
                pt.put("axis", ax.equals("x") || ax.equals("y") ? ax : "z");
                if (!pt.has("seen")) pt.put("seen", true);
                good.put(pt);
            }
            if (good.length() == 0 && outViews.length() == 0) return null;
            o.put("shape", new JSONObject().put("parts", good));
            if (o.optString("title").isEmpty()) o.put("title", "వస్తువు");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ evidence: a thing from three sides

    static String evidenceSystem() {
        return "You are JARVIS, making an evidence card for Anil (Telangana) of one object photographed from three sides (front, side, top). "
                + "Answer only in simple Telugu. Reply with ONLY this JSON: {\"name\":\"short Telugu name\",\"what\":\"1-2 Telugu lines: what it is\","
                + "\"size_cm\":[length,width,height] (estimates; use a visible reference like a coin, phone or hand if any, else say rough),"
                + "\"size_note\":\"how sure, what reference\",\"material\":\"…\",\"colour\":\"…\",\"condition\":\"new / used / damaged and how\","
                + "\"marks\":[\"up to 5 short lines: brand names, numbers that are NOT personal (model numbers ok), scratches, dents\"],"
                + "\"value\":\"rough price range in rupees if it is a common product, else empty\",\"keep\":\"one line: what to note for a warranty / complaint / sale\"}. "
                + "Never read ID numbers, phone numbers, addresses or number plates; never say who owns it.";
    }

    static String evidencePrompt(String note) {
        return "Left = front, middle = side, right = top (or the three views Anil managed)." + (note == null || note.trim().isEmpty() ? "" : " Anil says: \"" + note.trim() + "\"") + " Make the card.";
    }

    static JSONObject parseEvidence(String reply) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            if (a < 0 || z <= a) return null;
            JSONObject o = new JSONObject(s.substring(a, z + 1));
            scrub(o);
            if (o.optString("name").isEmpty()) o.put("name", "వస్తువు");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** The evidence card's lines (for the scan's "say" and the case card). */
    static String evidenceText(JSONObject e) {
        StringBuilder b = new StringBuilder(e.optString("what"));
        JSONArray sz = e.optJSONArray("size_cm");
        if (sz != null && sz.length() == 3) {
            b.append(" కొలతలు (అంచనా): సుమారు ").append(fmt(sz.optDouble(0))).append(" × ").append(fmt(sz.optDouble(1))).append(" × ").append(fmt(sz.optDouble(2))).append(" సెం.మీ.");
            if (!e.optString("size_note").isEmpty()) b.append(" (").append(e.optString("size_note")).append(")");
        }
        if (!e.optString("material").isEmpty()) b.append(" పదార్థం: ").append(e.optString("material")).append('.');
        if (!e.optString("condition").isEmpty()) b.append(" స్థితి: ").append(e.optString("condition")).append('.');
        JSONArray m = e.optJSONArray("marks");
        if (m != null && m.length() > 0) { b.append(" గుర్తులు: "); for (int i = 0; i < m.length(); i++) b.append(i == 0 ? "" : ", ").append(m.optString(i)); b.append('.'); }
        if (!e.optString("value").isEmpty()) b.append(" సుమారు విలువ: ").append(e.optString("value")).append('.');
        if (!e.optString("keep").isEmpty()) b.append(" గుర్తుంచుకోవాల్సింది: ").append(e.optString("keep"));
        return b.toString().trim();
    }

    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.format(java.util.Locale.US, "%.1f", v); }

    /** Three views side by side with labels, for the AI and for the card. */
    static Bitmap threeViews(List<Bitmap> pics) {
        int n = Math.min(3, pics.size()), cw = 560, ch = 560, gap = 8;
        Bitmap out = Bitmap.createBitmap(n * cw + (n + 1) * gap, ch + 2 * gap + 40, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF1A1A1A);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTextSize(30);
        String[] names = {"ముందు / front", "పక్క / side", "పైన / top"};
        for (int i = 0; i < n; i++) {
            Bitmap b = pics.get(i);
            int x = gap + i * (cw + gap), y = gap;
            float s = Math.min(cw / (float) b.getWidth(), ch / (float) b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            c.drawBitmap(b, null, new RectF(x + (cw - w) / 2, y + (ch - h) / 2, x + (cw + w) / 2, y + (ch + h) / 2), f);
            c.drawText(names[i], x + 10, ch + gap + 32, t);
        }
        return out;
    }

    /** A square cut from the middle of a picture (the card's small picture). */
    static Bitmap centerSquare(Bitmap b, int side) {
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        Bitmap out = Bitmap.createScaledBitmap(sq, side, side, true);
        if (sq != out && sq != b) sq.recycle();
        return out;
    }

    static Rect rect(float[] box, int w, int h) { return new Rect(Math.round(box[0] * w), Math.round(box[1] * h), Math.round(box[2] * w), Math.round(box[3] * h)); }
}
