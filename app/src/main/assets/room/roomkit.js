// 📐 Jarvis ఇంటి కొలతలు: the numbers only (no camera, no screen) — lengths in Telugu units, the room's plan, will a bed fit,
// paint and tiles. Everything here is plain math on the phone: nothing is sent anywhere.
(function (root) {
  'use strict';
  const FT = 0.3048, IN = 0.0254, SQFT = FT * FT;

  const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
  const dist3 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
  const dist2 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  const num = (x, lo, hi, d) => { x = +x; return isFinite(x) ? Math.min(hi, Math.max(lo, x)) : d; };

  // ---------------------------------------------------------------- words for a length / an area

  /** 10′ 9″ (feet and inches, rounded to the inch). */
  function ftIn(m) {
    if (!isFinite(m) || m < 0) return '—';
    const t = Math.round(m / IN), f = Math.floor(t / 12), i = t % 12;
    return f + '′ ' + i + '″';
  }
  /** "3.27 మీ · 10′ 9″"; under a metre "84 సెం.మీ · 2′ 9″". */
  function len(m) {
    if (!isFinite(m) || m < 0) return '—';
    return (m < 1 ? Math.round(m * 100) + ' సెం.మీ' : (Math.round(m * 100) / 100).toFixed(2) + ' మీ') + ' · ' + ftIn(m);
  }
  /** "142 sq ft · 13.2 m²" */
  function area(m2) {
    if (!isFinite(m2) || m2 < 0) return '—';
    return Math.round(m2 / SQFT) + ' sq ft · ' + (Math.round(m2 * 10) / 10).toFixed(1) + ' m²';
  }

  // ---------------------------------------------------------------- the room's outline on the floor (x, z in metres)

  function polyArea(p) {
    let s = 0;
    for (let i = 0; i < p.length; i++) { const a = p[i], b = p[(i + 1) % p.length]; s += a[0] * b[1] - b[0] * a[1]; }
    return s / 2; // (signed: + = counter-clockwise)
  }
  function walls(p) { return p.map((a, i) => dist2(a, p[(i + 1) % p.length])); }
  function perimeter(p) { return walls(p).reduce((s, x) => s + x, 0); }

  /**
   * The corners as the camera found them ([x, y, z] in metres) → a plan: the first corner at 0, 0, the first wall along +x,
   * the corners going round counter-clockwise (so the area is positive). y (the height) is left out: they are all on the floor.
   */
  function normalize(pts3) {
    const p = pts3.map((q) => [q[0], q[2]]);
    if (p.length < 2) return p.map(() => [0, 0]);
    const o = p[0], ang = Math.atan2(p[1][1] - o[1], p[1][0] - o[0]), c = Math.cos(-ang), s = Math.sin(-ang);
    let out = p.map((q) => { const x = q[0] - o[0], y = q[1] - o[1]; return [x * c - y * s, x * s + y * c]; });
    if (polyArea(out) < 0) out = out.map((q) => [q[0], -q[1]]); // (mirror, not reorder: the walls keep their numbers)
    return out.map((q) => [Math.round(q[0] * 1000) / 1000, Math.round(q[1] * 1000) / 1000]);
  }

  function pointIn(poly, p) {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const a = poly[i], b = poly[j];
      if ((a[1] > p[1]) !== (b[1] > p[1]) && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    return inside;
  }
  function cross(o, a, b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }
  function segsCross(a, b, c, d) {
    const d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
    return ((d1 > 0) !== (d2 > 0)) && ((d3 > 0) !== (d4 > 0));
  }
  /** The shortest way from a point to a segment. */
  function segDist(p, a, b) {
    const dx = b[0] - a[0], dy = b[1] - a[1], L = dx * dx + dy * dy;
    const t = L > 0 ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L)) : 0;
    return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
  }

  /** A box's footprint on the floor: centre (x, z), width across, depth along f (a unit vector on the floor). */
  function foot(cx, cz, w, d, f) {
    const r = [f[1], -f[0]], hw = w / 2, hd = d / 2;
    return [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([i, j]) => [cx + r[0] * hw * i + f[0] * hd * j, cz + r[1] * hw * i + f[1] * hd * j]);
  }
  /** Does the footprint stand inside the room? And the smallest gap to a wall (metres; 0 when it is through one). */
  function fits(poly, ft) {
    if (!poly || poly.length < 3) return null;
    let ok = ft.every((p) => pointIn(poly, p));
    for (let i = 0; ok && i < 4; i++) for (let j = 0; j < poly.length; j++) if (segsCross(ft[i], ft[(i + 1) % 4], poly[j], poly[(j + 1) % poly.length])) ok = false;
    let gap = Infinity;
    if (ok) for (const p of ft) for (let j = 0; j < poly.length; j++) gap = Math.min(gap, segDist(p, poly[j], poly[(j + 1) % poly.length]));
    return { ok, gap: ok ? gap : 0 };
  }

  // ---------------------------------------------------------------- paint and tiles (estimates, his numbers can change them)

  const TILES = {
    '600x600': { te: '2′ × 2′ (600 × 600 mm)', w: 0.6, h: 0.6, box: 4 },
    '800x800': { te: '2′8″ × 2′8″ (800 × 800 mm)', w: 0.8, h: 0.8, box: 3 },
    '600x1200': { te: '2′ × 4′ (600 × 1200 mm)', w: 0.6, h: 1.2, box: 2 },
    '300x300': { te: '1′ × 1′ (300 × 300 mm)', w: 0.3, h: 0.3, box: 0 },
  };
  const DOOR = { w: 3 * FT, h: 7 * FT }, WINDOW = { w: 4 * FT, h: 3 * FT };

  /** A saved room's numbers made safe (what the plan uses). */
  function roomDefaults(r) {
    return {
      h_ft: num(r.h_ft, 6, 30, 10), doors: Math.round(num(r.doors, 0, 20, 1)), windows: Math.round(num(r.windows, 0, 30, 1)),
      cover: num(r.cover, 20, 400, 100), tile: TILES[r.tile] ? r.tile : '600x600', waste: num(r.waste, 0, 40, 10),
      perBox: Math.round(num(r.perBox, 0, 50, TILES[r.tile] ? TILES[r.tile].box : 4)), ceil: !!r.ceil,
    };
  }

  /** Floor, walls, paint (litres, two coats), tiles (with the cutting extra), skirting. */
  function estimate(r) {
    const c = r.corners || [], o = roomDefaults(r);
    const floor = Math.abs(polyArea(c)), per = perimeter(c), h = o.h_ft * FT;
    const holes = o.doors * DOOR.w * DOOR.h + o.windows * WINDOW.w * WINDOW.h;
    const wall = Math.max(0, per * h - holes), paintArea = wall + (o.ceil ? floor : 0);
    const litres = Math.ceil((paintArea / SQFT) / o.cover * 2) / 2; // (to the half litre)
    const t = TILES[o.tile], tileA = t.w * t.h;
    const tiles = c.length >= 3 ? Math.ceil(floor / tileA * (1 + o.waste / 100)) : 0;
    const boxes = o.perBox > 0 ? Math.ceil(tiles / o.perBox) : 0;
    const skirt = Math.max(0, per - o.doors * DOOR.w);
    return { floor, per, h, wall, paintArea, litres, tiles, boxes, skirt, walls: walls(c), o };
  }

  // ---------------------------------------------------------------- things to try in the room (real sizes, his own can change them)

  const ITEMS = [
    { id: 'bed1', te: '🛏️ సింగిల్ బెడ్', w: 3, d: 6.25, h: 1.5 },
    { id: 'bed2', te: '🛏️ డబుల్ బెడ్ (క్వీన్)', w: 5, d: 6.5, h: 1.5 },
    { id: 'bed3', te: '🛏️ కింగ్ బెడ్', w: 6, d: 6.5, h: 1.5 },
    { id: 'almirah', te: '🚪 బీరువా (2 తలుపులు)', w: 3, d: 1.75, h: 6.5 },
    { id: 'sofa', te: '🛋️ 3 సీటర్ సోఫా', w: 6.5, d: 3, h: 3 },
    { id: 'fridge', te: '🧊 ఫ్రిజ్ (డబుల్ డోర్)', w: 2.25, d: 2.25, h: 5.5 },
    { id: 'dining', te: '🍽️ 4 సీటర్ డైనింగ్', w: 4, d: 2.5, h: 2.5 },
    { id: 'tv', te: '📺 TV యూనిట్', w: 6, d: 1.5, h: 1.75 },
    { id: 'washer', te: '🧺 వాషింగ్ మెషిన్', w: 2, d: 2, h: 3 },
    { id: 'own', te: '✏️ మీ కొలతలు', w: 4, d: 2, h: 3 },
  ];

  // ---------------------------------------------------------------- the camera's numbers → the screen (ARCore gives column-major 4 × 4)

  function mul(m, v) {
    return [m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12] * v[3], m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13] * v[3],
      m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14] * v[3], m[3] * v[0] + m[7] * v[1] + m[11] * v[2] + m[15] * v[3]];
  }
  /** A world point → clip space (x, y, z, w). */
  function clip(P, V, X) { return mul(P, mul(V, [X[0], X[1], X[2], 1])); }
  /** Clip space → screen pixels (null behind the camera). */
  function toScreen(c, W, H) { return c[3] <= 1e-4 ? null : [(c[0] / c[3] + 1) / 2 * W, (1 - c[1] / c[3]) / 2 * H]; }
  /** A line between two clip points, cut where it goes behind the camera; null = all behind. */
  function lineOnScreen(ca, cb, W, H) {
    const E = 0.02;
    if (ca[3] < E && cb[3] < E) return null;
    let a = ca, b = cb;
    if (a[3] < E) { const t = (E - a[3]) / (b[3] - a[3]); a = a.map((x, i) => x + (b[i] - x) * t); }
    else if (b[3] < E) { const t = (E - b[3]) / (a[3] - b[3]); b = b.map((x, i) => x + (a[i] - x) * t); }
    return [toScreen(a, W, H), toScreen(b, W, H)];
  }
  /** Where the camera looks, on the floor (a unit vector [x, z]) and its height above it. */
  function camForward(V) {
    const fx = -V[2], fz = -V[10], L = Math.hypot(fx, fz);
    return L > 1e-6 ? [fx / L, fz / L] : [0, -1];
  }
  function camPos(V) { // (the inverse of a rigid view matrix: -Rᵀ t)
    return [-(V[0] * V[12] + V[1] * V[13] + V[2] * V[14]), -(V[4] * V[12] + V[5] * V[13] + V[6] * V[14]), -(V[8] * V[12] + V[9] * V[13] + V[10] * V[14])];
  }
  /** A box standing on the floor: its 8 corners (bottom 4, then top 4). */
  function boxCorners(c, w, d, h, f) {
    const ft = foot(c[0], c[2], w, d, f);
    return ft.map((p) => [p[0], c[1], p[1]]).concat(ft.map((p) => [p[0], c[1] + h, p[1]]));
  }
  const rotate = (f, deg) => { const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a); return [f[0] * c - f[1] * s, f[0] * s + f[1] * c]; };

  const RoomKit = { FT, IN, SQFT, ftIn, len, area, dist3, dist2, sub, polyArea, walls, perimeter, normalize, pointIn, segsCross, segDist, foot, fits,
    TILES, DOOR, WINDOW, roomDefaults, estimate, ITEMS, mul, clip, toScreen, lineOnScreen, camForward, camPos, boxCorners, rotate };
  root.RoomKit = RoomKit;
  if (typeof module !== 'undefined' && module.exports) module.exports = RoomKit;
})(typeof window !== 'undefined' ? window : globalThis);
