package com.anil.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 🛣️ Stage 3 (27 Oct): the way ahead in the 3D view (no Android: desk-tested) — on our own free route (OSM search,
 * the OSRM route, his tap the yes) or, without one, simply the road he is on: our yellow lane on his side of it, white
 * arrows painted before the next turn, yellow chevron boards on the outside of the bend, and our own turn card's
 * words. Routes come only from OSRM (OpenStreetMap); a Google trip's turn stays in Google's own card.
 */
final class Way3D {
    private Way3D() {}

    /** OSRM's route ("geometry": GeoJSON [lon, lat] points) as a line in metres; null when broken. Pure. */
    static float[] line(JSONObject route, World3D.Proj p) {
        JSONObject g = route == null ? null : route.optJSONObject("geometry");
        JSONArray c = g == null ? null : g.optJSONArray("coordinates");
        if (c == null || c.length() < 2) return null;
        float[] xz = new float[c.length() * 2];
        int k = 0;
        for (int i = 0; i < c.length(); i++) {
            JSONArray q = c.optJSONArray(i);
            if (q == null || q.length() < 2) continue;
            float x = p.x(q.optDouble(0)), z = p.z(q.optDouble(1));
            if (k >= 2 && Math.abs(x - xz[k - 2]) < 0.05f && Math.abs(z - xz[k - 1]) < 0.05f) continue; // (the same point twice)
            xz[k++] = x;
            xz[k++] = z;
        }
        return k < 4 ? null : java.util.Arrays.copyOf(xz, k);
    }

    /** The road he is on, facing his way: its line turned round when it runs against him. Pure. */
    static float[] facing(float[] xz, float px, float pz, float heading) {
        float[] al = World3D.along(xz, px, pz, heading);
        return heading >= 0 && World3D.angleDiff(al[2], heading) > Math.PI / 2 ? Mesh3D.reverse(xz) : xz;
    }

    /**
     * The way's paint round (cx, cz), from at metres along it: our lane for the next 400 m, white arrows 60 m and 35 m
     * before the next turn (within 300 m), chevron boards at a real bend (over 50°). Pure.
     */
    static Car3DView.Marks marks(float[] way, float at, float roadHalf, boolean oneway, float cx, float cz) {
        Mesh3D flat = new Mesh3D(), solid = new Mesh3D();
        float total = World3D.length(way);
        float s0 = Math.max(0, at - 15), s1 = Math.min(total, at + 400);
        if (s1 - s0 > 2) flat.add(World3D.lane(World3D.part(way, s0, s1), roadHalf, oneway), 0, 0, 0, 0);
        float[] t = World3D.nextTurn(way, at, 300);
        if (t != null) {
            int bend = t[1] > 0 ? 1 : -1;
            float off = World3D.laneOffset(roadHalf, oneway);
            for (float back : new float[]{60, 35}) {
                float s = at + t[0] - back;
                if (s < at + 4) continue;
                float[] p = World3D.pointAt(way, s);
                float lx = (float) -Math.cos(p[2]), lz = (float) -Math.sin(p[2]); // (left of the way: the lane's middle)
                World3D.arrow(flat, p[0] + lx * off, p[1] + lz * off, p[2], bend);
            }
            if (Math.abs(t[1]) > Math.toRadians(50)) {
                float h = World3D.pointAt(way, Math.max(0, at + t[0] - 25))[2];
                World3D.boards(solid, t[2], t[3], h, bend, roadHalf);
            }
        }
        flat.shift(-cx, 0, -cz);
        solid.shift(-cx, 0, -cz);
        return new Car3DView.Marks(cx, cz, flat, solid);
    }

    /**
     * Our turn card from at metres along the way: {words, distance words, "1" right / "-1" left / "0" straight on};
     * near the end "🏁 చేరుకుంటున్నాం"; null when the way runs on straight (the card shows how far is left). Pure.
     */
    static String[] card(float[] way, float at) {
        float total = World3D.length(way), left = total - at;
        float[] t = World3D.nextTurn(way, at, 2000);
        if (left < 250 && (t == null || t[0] > left - 20)) return new String[]{"🏁 చేరుకుంటున్నాం", World3D.distWords(left), "0"};
        if (t == null) return new String[]{"⬆️ ఇలాగే నేరుగా", World3D.distWords(Math.min(left, 2000)), "0"};
        return new String[]{World3D.turnWords(t[1]), World3D.distWords(t[0]), t[1] > 0 ? "1" : "-1"};
    }

    /** How much is left: "4.2 కి.మీ · 9 ని" (the time scaled from OSRM's for the whole way). Pure. */
    static String left(float total, float at, float durationS) {
        float m = Math.max(0, total - at);
        int min = total <= 0 ? 0 : Math.max(1, Math.round(durationS * (m / total) / 60f));
        return World3D.distWords(m).replace("ఇప్పుడు", "0 మీ") + " · " + min + " ని";
    }

    /** Off the way: more than 45 m from it (the GPS's own error is allowed for). Pure. */
    static boolean off(float[] way, float px, float pz, float heading, float accuracy) {
        float[] al = World3D.along(way, px, pz, heading);
        return al[0] > 45 + Math.min(40, Math.max(0, accuracy));
    }
}
