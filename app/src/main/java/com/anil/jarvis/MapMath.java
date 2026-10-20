package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🗺️ Jarvis Maps' small sums (no Android here, so the desk tests can check them): Google's encoded route lines, the
 * map's tiles and pixels (web mercator), distances and directions, "285s" durations, and the way ahead cut to so many km.
 */
final class MapMath {
    private MapMath() {}

    // ---------------------------------------------------------------- Google's encoded polyline

    /** A Google encoded polyline to {lat, lon} points. A broken string gives the points read so far. */
    static List<double[]> decode(String s) {
        List<double[]> out = new ArrayList<>();
        if (s == null) return out;
        int i = 0, n = s.length();
        long lat = 0, lon = 0;
        while (i < n) {
            long[] r = next(s, i);
            if (r == null) break;
            i = (int) r[1];
            long[] r2 = next(s, i);
            if (r2 == null) break;
            i = (int) r2[1];
            lat += r[0];
            lon += r2[0];
            out.add(new double[]{lat / 1e5, lon / 1e5});
        }
        return out;
    }

    /** One signed number from position i: {value, next position}; null when the string ends inside it. */
    private static long[] next(String s, int i) {
        long result = 0;
        int shift = 0, b;
        do {
            if (i >= s.length()) return null;
            b = s.charAt(i++) - 63;
            if (b < 0 || b > 63) return null;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && shift < 60);
        long v = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new long[]{v, i};
    }

    /** {lat, lon} points to a Google encoded polyline. */
    static String encode(List<double[]> pts) {
        StringBuilder b = new StringBuilder();
        long pLat = 0, pLon = 0;
        for (double[] p : pts) {
            long lat = Math.round(p[0] * 1e5), lon = Math.round(p[1] * 1e5);
            put(b, lat - pLat);
            put(b, lon - pLon);
            pLat = lat;
            pLon = lon;
        }
        return b.toString();
    }

    private static void put(StringBuilder b, long v) {
        long s = v < 0 ? ~(v << 1) : (v << 1);
        while (s >= 0x20) {
            b.append((char) ((0x20 | (s & 0x1f)) + 63));
            s >>= 5;
        }
        b.append((char) (s + 63));
    }

    // ---------------------------------------------------------------- the earth

    static double meters(double la1, double lo1, double la2, double lo2) {
        double r = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The point m metres from (lat, lon) going brg degrees. */
    static double[] ahead(double lat, double lon, double brg, double m) {
        double r = 6371000, d = m / r, b = Math.toRadians(brg), p1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
        double p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b));
        double l2 = l1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180};
    }

    /** The first km of a path (the last point cut exactly at km). */
    static List<double[]> cut(List<double[]> pts, double km) {
        List<double[]> out = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return out;
        out.add(pts.get(0));
        double run = 0, want = km * 1000;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double seg = meters(a[0], a[1], b[0], b[1]);
            if (run + seg >= want) {
                double t = seg <= 0 ? 0 : (want - run) / seg;
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                return out;
            }
            run += seg;
            out.add(b);
        }
        return out;
    }

    /** A path's length in km. */
    static double km(List<double[]> pts) {
        double run = 0;
        for (int i = 1; i < pts.size(); i++) run += meters(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        return run / 1000;
    }

    /** "285s" / "1234.5s" (Google's durations) to seconds; 0 when not one. */
    static long seconds(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
        try { return Math.round(Double.parseDouble(t)); } catch (Exception e) { return 0; }
    }

    /** "1 గం 25 ని" / "25 ని" for seconds. */
    static String duration(long sec) {
        long m = Math.max(0, Math.round(sec / 60.0));
        if (m < 60) return m + " ని";
        return (m / 60) + " గం" + (m % 60 == 0 ? "" : " " + (m % 60) + " ని");
    }

    /** "450 మీ" / "3.2 కి.మీ" / "48 కి.మీ". */
    static String distance(double meters) {
        if (meters < 950) return Math.round(meters / 10) * 10 + " మీ";
        double k = meters / 1000;
        return (k < 10 ? String.format(Locale.US, "%.1f", k) : String.valueOf(Math.round(k))) + " కి.మీ";
    }

    // ---------------------------------------------------------------- the map's tiles (web mercator, 256-px tiles at zoom z)

    /** The world pixel (at this zoom, 256 px tiles) of a point: {x, y}. */
    static double[] pixel(double lat, double lon, double zoom) {
        double n = Math.pow(2, zoom) * 256;
        double la = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double x = (lon + 180) / 360 * n;
        double y = (1 - Math.log(Math.tan(Math.toRadians(la)) + 1 / Math.cos(Math.toRadians(la))) / Math.PI) / 2 * n;
        return new double[]{x, y};
    }

    /** The point at a world pixel: {lat, lon}. */
    static double[] latLon(double x, double y, double zoom) {
        double n = Math.pow(2, zoom) * 256;
        double lon = x / n * 360 - 180;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        return new double[]{lat, lon};
    }

    /** The zoom (whole) that shows all points in a w×h px window with some room, at most maxZoom. */
    static int fitZoom(List<double[]> pts, int w, int h, int maxZoom) {
        if (pts == null || pts.size() < 2 || w <= 0 || h <= 0) return maxZoom;
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (double[] p : pts) { minLa = Math.min(minLa, p[0]); maxLa = Math.max(maxLa, p[0]); minLo = Math.min(minLo, p[1]); maxLo = Math.max(maxLo, p[1]); }
        for (int z = maxZoom; z >= 2; z--) {
            double[] a = pixel(maxLa, minLo, z), b = pixel(minLa, maxLo, z);
            if (Math.abs(b[0] - a[0]) <= w * 0.8 && Math.abs(b[1] - a[1]) <= h * 0.7) return z;
        }
        return 2;
    }
}
