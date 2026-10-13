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
        int n = pics.size(), cols = n == 1 ? 1 : 2, rows = (n + cols - 1) / cols;
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
