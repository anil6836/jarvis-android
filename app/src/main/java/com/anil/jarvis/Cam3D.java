package com.anil.jarvis;

/**
 * 🎥 Stage 3 (27 Oct): the 3D view's moving parts as plain maths (no Android: desk-tested) — the car gliding between
 * the GPS's once-a-second places, its nose turning first and the camera following a moment later (the bend is seen
 * coming), the camera behind it or going round it (🔄, his tap, parked), the sky's colours, and which of his photos
 * shows his car from where the camera is. Headings: radians, 0 = north, clockwise; x east, z south.
 */
final class Cam3D {
    private Cam3D() {}

    /** The nose's way (unit x, z) for a heading. Pure. */
    static float dirX(float h) { return (float) Math.sin(h); }

    static float dirZ(float h) { return (float) -Math.cos(h); }

    /** An angle moved part of the way (k: 0–1) towards another, the short way round. Pure. */
    static float turnTowards(float from, float to, float k) {
        return wrap(from + World3D.signedDiff(from, to) * Math.max(0, Math.min(1, k)));
    }

    static float wrap(float a) {
        double t = a % (2 * Math.PI);
        return (float) (t < 0 ? t + 2 * Math.PI : t);
    }

    /** How much of the way to go in dt seconds when easing at rate (per second): 1 − e^(−rate·dt). Pure. */
    static float ease(float rate, float dt) { return 1 - (float) Math.exp(-rate * Math.max(0, dt)); }

    /**
     * The car's shown place, eased towards where the GPS's last place says it is now (that place moved on at its speed
     * for the time since, at most 2 s): {x, z}. Far off (over 60 m: a new fix after a gap): straight there. Pure.
     */
    static float[] glide(float x, float z, float fx, float fz, float heading, float speed, float sinceFix, float dt) {
        float t = Math.min(2f, Math.max(0, sinceFix));
        float px = fx + dirX(heading) * speed * t, pz = fz + dirZ(heading) * speed * t;
        float dx = px - x, dz = pz - z;
        if (dx * dx + dz * dz > 60 * 60) return new float[]{px, pz};
        float k = ease(4f, dt);
        return new float[]{x + dx * k, z + dz * k};
    }

    /**
     * The chase camera: behind the car along the camera's own heading (it follows the car's nose a little later),
     * further back and higher with speed; {eyeX, eyeY, eyeZ, atX, atY, atZ}. Pure.
     */
    static float[] chase(float cx, float cz, float camHeading, float speedMps) {
        float kmh = speedMps * 3.6f;
        float back = 7.6f + Math.min(2.6f, kmh * 0.03f), up = 3.5f + Math.min(1.2f, kmh * 0.015f); // (a little high: the bend ahead is seen coming)
        float ux = dirX(camHeading), uz = dirZ(camHeading);
        return new float[]{cx - ux * back, up, cz - uz * back, cx + ux * 7f, 0.5f, cz + uz * 7f};
    }

    /** 🔄 Round the car: the camera r away from it in the world's way yaw (radians: 0 north of it, clockwise), up high, looking at it. Pure. */
    static float[] orbit(float cx, float cz, float yaw, float r, float up) {
        return new float[]{cx + (float) Math.sin(yaw) * r, up, cz - (float) Math.cos(yaw) * r, cx, 0.75f, cz};
    }

    // ================================================================ his photos: which one from here

    /** The photo slots and the side each shows (degrees round the car from its nose, clockwise: 0 front, 90 right…). */
    static final String[] SLOTS = {"front", "rear", "left", "right", "top", "rear_left", "rear_right"};
    static final float[] SLOT_DEG = {0, 180, 270, 90, -1, 225, 135};

    /** Where the camera is round the car: degrees from its nose, clockwise (0 in front, 90 on its right, 180 behind). Pure. */
    static float around(float carX, float carZ, float carHeading, float eyeX, float eyeZ) {
        float dx = eyeX - carX, dz = eyeZ - carZ;
        float fwd = dx * dirX(carHeading) + dz * dirZ(carHeading);
        float right = dx * -dirZ(carHeading) + dz * dirX(carHeading);
        double a = Math.toDegrees(Math.atan2(right, fwd));
        return (float) (a < 0 ? a + 360 : a);
    }

    /**
     * Which photo to show from deg (round the car): the nearest of those he gave (have[i]); the one now shown stays
     * until another is nearer by more than 10° (no flicker at the edge). The top photo (only seen from above) is never
     * picked here. −1 when none. Pure.
     */
    static int pickPhoto(float deg, boolean[] have, int now) {
        int best = -1;
        float bd = 999;
        for (int i = 0; i < SLOTS.length; i++) {
            if (!have[i] || SLOT_DEG[i] < 0) continue;
            float d = degDiff(deg, SLOT_DEG[i]);
            if (d < bd) { bd = d; best = i; }
        }
        if (now >= 0 && now < have.length && have[now] && SLOT_DEG[now] >= 0 && best != now && degDiff(deg, SLOT_DEG[now]) < bd + 10) return now;
        return best;
    }

    static float degDiff(float a, float b) {
        float d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    // ================================================================ the sky

    /**
     * Sky kinds: day, dusk, night; and the worlds (31 Oct, his pictures' styles, our own): a neon city under a big moon,
     * the same at sunset, a white city with a blue or a violet way, a night highway with hills and a green way, a day
     * highway with green fields and the way lit on the lane.
     */
    static final int DAY = 0, DUSK = 1, NIGHT = 2, NEON = 3, SUNSET = 4, WHITE = 5, WHITE_VIOLET = 6, HIGHWAY = 7, DAY_AR = 8;

    /** His "car_3d_sky": "" (with the screen's day / night), "day", "dusk", "night", or a world. Pure. */
    static int sky(String pref, boolean screenNight) {
        if ("day".equals(pref)) return DAY;
        if ("dusk".equals(pref)) return DUSK;
        if ("night".equals(pref)) return NIGHT;
        if ("neon".equals(pref)) return NEON;
        if ("sunset".equals(pref)) return SUNSET;
        if ("white".equals(pref)) return WHITE;
        if ("whitev".equals(pref)) return WHITE_VIOLET;
        if ("highway".equals(pref)) return HIGHWAY;
        if ("dayar".equals(pref)) return DAY_AR;
        return screenNight ? NIGHT : DAY;
    }

    /**
     * Each sky's light, 22 numbers: sun way (3), sun colour (3), ambient (1), sky top (3), horizon / fog (3), fog
     * thickness (1), night (1: lamps, windows, headlights), ground colour (3), road colour (3), spare (1).
     */
    static float[] light(int sky) {
        switch (sky) {
            case DUSK: return new float[]{-0.55f, 0.32f, -0.6f, 0.70f, 0.43f, 0.29f, 0.34f, 0.20f, 0.16f, 0.40f, 0.96f, 0.56f, 0.40f, 0.0042f, 0.45f,
                    0.27f, 0.25f, 0.27f, 0.25f, 0.25f, 0.29f, 0};
            case NIGHT: return new float[]{0.3f, 0.75f, 0.25f, 0.14f, 0.16f, 0.22f, 0.17f, 0.015f, 0.025f, 0.07f, 0.06f, 0.07f, 0.15f, 0.0052f, 1f,
                    0.16f, 0.17f, 0.20f, 0.20f, 0.21f, 0.25f, 0};
            case NEON: return new float[]{0.3f, 0.75f, 0.25f, 0.30f, 0.20f, 0.45f, 0.30f, 0.10f, 0.04f, 0.24f, 0.42f, 0.12f, 0.45f, 0.0048f, 1f,
                    0.10f, 0.08f, 0.16f, 0.12f, 0.10f, 0.18f, 0};
            case SUNSET: return new float[]{-0.2f, 0.15f, -0.9f, 1.0f, 0.55f, 0.45f, 0.32f, 0.45f, 0.05f, 0.30f, 1.0f, 0.45f, 0.42f, 0.0045f, 0.75f,
                    0.20f, 0.10f, 0.16f, 0.18f, 0.12f, 0.18f, 0};
            case WHITE: case WHITE_VIOLET: return new float[]{0.35f, 0.85f, 0.4f, 0.55f, 0.55f, 0.56f, 0.62f, 0.88f, 0.90f, 0.94f, 0.94f, 0.95f, 0.97f, 0.006f, 0f,
                    0.86f, 0.88f, 0.91f, 0.82f, 0.84f, 0.88f, 0};
            case HIGHWAY: return new float[]{0.3f, 0.75f, 0.25f, 0.12f, 0.14f, 0.20f, 0.15f, 0.01f, 0.015f, 0.04f, 0.04f, 0.05f, 0.09f, 0.006f, 1f,
                    0.07f, 0.08f, 0.10f, 0.14f, 0.15f, 0.18f, 0};
            case DAY_AR: return new float[]{0.45f, 0.8f, 0.35f, 0.78f, 0.76f, 0.70f, 0.50f, 0.36f, 0.60f, 0.90f, 0.80f, 0.88f, 0.95f, 0.0035f, 0f,
                    0.38f, 0.52f, 0.25f, 0.32f, 0.33f, 0.36f, 0};
            default: return new float[]{0.45f, 0.8f, 0.35f, 0.75f, 0.73f, 0.68f, 0.46f, 0.30f, 0.52f, 0.86f, 0.74f, 0.83f, 0.92f, 0.0034f, 0f,
                    0.40f, 0.45f, 0.38f, 0.30f, 0.32f, 0.36f, 0};
        }
    }

    /** How many numbers a world's own look has ({@link #world}). */
    static final int WORLD_N = 37;

    /**
     * A world's own look over its light, 37 numbers (all 0 for day / dusk / night: everything as it always was): the
     * walls' tint (rgb, how much), the windows' two colours (rgb, rgb) and their kind (0 the warm ones, 1 neon, 2 none),
     * the road's tint (rgb, how much), the lane's colour (rgb, how much: the gold lane, its edges and the boards), wet
     * (0–1), the grid on the road (0–1), rings round the car (0–1), the ground's tint (rgb, how much), the moon / sun
     * (x on the screen 0–1, height over the horizon, size, on), its colour (rgb), the hills (rgb, on). The rings and the
     * grid are decoration only: we have no sensors. Pure.
     */
    static float[] world(int sky) {
        switch (sky) {
            case NEON: return new float[]{0.07f, 0.06f, 0.16f, 0.85f, 1.0f, 0.31f, 0.85f, 0.21f, 0.94f, 1.0f, 1, 0.10f, 0.09f, 0.17f, 0.7f, 0.21f, 0.94f, 1.0f, 0.9f,
                    1, 0, 0, 0.08f, 0.06f, 0.14f, 0.8f, 0.5f, 0.16f, 0.13f, 1, 0.98f, 0.92f, 1.0f, 0, 0, 0, 0};
            case SUNSET: return new float[]{0.16f, 0.07f, 0.20f, 0.8f, 1.0f, 0.35f, 0.7f, 0.25f, 0.85f, 1.0f, 1, 0.14f, 0.09f, 0.15f, 0.65f, 0.21f, 0.94f, 1.0f, 0.85f,
                    0.8f, 0, 0, 0.16f, 0.08f, 0.14f, 0.75f, 0.5f, 0.1f, 0.14f, 1, 1.0f, 0.88f, 0.55f, 0, 0, 0, 0};
            case WHITE: case WHITE_VIOLET: {
                boolean v = sky == WHITE_VIOLET;
                return new float[]{0.95f, 0.96f, 0.97f, 0.92f, 0, 0, 0, 0, 0, 0, 2, 0.84f, 0.86f, 0.89f, 0.85f, v ? 0.55f : 0.23f, v ? 0.36f : 0.51f, 0.96f, 0.95f,
                        0, 1, 1, 0.90f, 0.91f, 0.93f, 0.9f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            }
            case HIGHWAY: return new float[]{0.10f, 0.11f, 0.14f, 0.6f, 0, 0, 0, 0, 0, 0, 0, 0.13f, 0.14f, 0.17f, 0.6f, 0.13f, 0.77f, 0.37f, 0.95f,
                    0.3f, 0, 0, 0.05f, 0.06f, 0.07f, 0.8f, 0, 0, 0, 0, 0, 0, 0, 0.075f, 0.09f, 0.13f, 1};
            case DAY_AR: return new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.33f, 0.34f, 0.37f, 0.4f, 0.18f, 0.9f, 0.65f, 0.9f,
                    0, 0, 0, 0.40f, 0.56f, 0.24f, 0.9f, 0.3f, 0.13f, 0.05f, 1, 1.0f, 1.0f, 0.96f, 0.30f, 0.45f, 0.30f, 1};
            default: return new float[WORLD_N];
        }
    }

    /** The same in place (no new array each frame): now moves part of the way to the target. Pure. */
    static void mixInto(float[] now, float[] target, float k) {
        for (int i = 0; i < now.length && i < target.length; i++) now[i] += (target[i] - now[i]) * k;
    }

    /** One sky's numbers part of the way to another's (k 0–1): the change is smooth. Pure. */
    static float[] mix(float[] a, float[] b, float k) {
        float[] o = new float[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] + (b[i] - a[i]) * k;
        return o;
    }
}
