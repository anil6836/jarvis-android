// 🔌 Jarvis PCB studio — the board's own engine (our code, ₹0, no network): where every pad sits, auto-placing the parts, a
// 2-layer grid router (A* over a 0.25 mm grid with layer preference and vias), the ground fill (with thermal spokes, no dead
// copper), the checks against a factory's limits with simple Telugu reasons, the silkscreen (our own stroke font) and the
// IPC-2221 track width for a current. Units: millimetres, board origin top-left, y downwards; layer 0 = top (F), 1 = bottom (B).
'use strict';
(function (root) {
  const P = root.PcbParts || (typeof require !== 'undefined' ? require('./pcbparts.js') : null);

  // ------------------------------------------------------------------ rules

  /** The factories he can send to (their published minimums, read 23 Nov 2026) and the button that opens their site. */
  const FABS = {
    jlc: { name: 'JLCPCB', te: 'JLCPCB (చైనా; చవక, 1–2 వారాల్లో ఇంటికి)', track: 0.10, space: 0.10, drill: 0.15, ring: 0.18, edge: 0.20, silkW: 0.15, silkH: 1.0 },
    pcbway: { name: 'PCBWay', te: 'PCBWay (చైనా)', track: 0.10, space: 0.10, drill: 0.15, ring: 0.15, edge: 0.25, silkW: 0.15, silkH: 0.8 },
    lion: { name: 'Lion Circuits', te: 'Lion Circuits (భారత్; GST బిల్, త్వరగా)', track: 0.152, space: 0.152, drill: 0.35, ring: 0.152, edge: 0.30, silkW: 0.152, silkH: 0.6 },
    pcbpower: { name: 'PCB Power', te: 'PCB Power (భారత్)', track: 0.15, space: 0.15, drill: 0.20, ring: 0.10, edge: 0.20, silkW: 0.10, silkH: 1.0 }
  };
  /** Our own drawing rules: wider than every factory's minimum, easy to solder by hand. */
  const RULES = { track: 0.4, clr: 0.3, edge: 0.5, via: 0.8, viaDrill: 0.4, pclr: 0.4, gap: 0.4, spoke: 0.5, silkW: 0.18, textH: 1.2, holeKeep: 3.2, holeD: 3.2 };
  function rules(D) { return Object.assign({}, RULES, (D && D.rules) || {}); }

  /** IPC-2221 outer-layer track width for a current (A), temperature rise dT (°C) and copper (oz): millimetres. */
  function widthFor(amps, dT, oz) {
    if (!(amps > 0)) return 0;
    const area = Math.pow(amps / (0.048 * Math.pow(dT || 10, 0.44)), 1 / 0.725); // mil²
    return area / ((oz || 1) * 1.378) * 0.0254;
  }
  function netWidth(D, net) {
    const R = rules(D);
    let w = Math.max(R.track, net && net.a ? Math.ceil(widthFor(net.a, 10, (D.board && D.board.cu) || 1) * 20 + 0.0001) / 20 : 0);
    if (net && isPower(net.n)) w = Math.max(w, 0.6);
    return Math.min(2.5, w);
  }
  function isPower(n) { return /^(GND|VCC|VDD|VIN|VBUS|VBAT|BAT|\+?\d+(\.\d+)?V\d*|3V3|5V|12V|\+\w+)$/i.test(String(n || '')); }
  function isGnd(n) { return /^(GND|0V)$/i.test(String(n || '')); }

  // ------------------------------------------------------------------ geometry

  function rot(x, y, r) {
    switch (((r % 360) + 360) % 360) { case 90: return [-y, x]; case 180: return [-x, -y]; case 270: return [y, -x]; default: return [x, y]; }
  }
  function partPads(part, x, y, r) {
    const f = P.fpOf(part);
    if (!f) return [];
    const sw = ((r || 0) % 180) !== 0;
    return f.pads.map((p) => { const q = rot(p.x, p.y, r || 0); return { ref: part.ref, n: p.n, x: x + q[0], y: y + q[1], s: p.s, w: sw ? p.h : p.w, h: sw ? p.w : p.h, d: p.d || 0, smd: !p.d }; });
  }
  function courtOf(part, x, y, r) {
    const f = P.fpOf(part);
    if (!f) return [x - 1, y - 1, x + 1, y + 1];
    const c = f.court, a = rot(c[0], c[1], r || 0), b = rot(c[2], c[3], r || 0);
    return [Math.min(a[0], b[0]) + x, Math.min(a[1], b[1]) + y, Math.max(a[0], b[0]) + x, Math.max(a[1], b[1]) + y];
  }
  /** Which way (N E S W) a footprint side faces after turning. */
  function sideAfter(side, r) {
    const v = { N: [0, -1], E: [1, 0], S: [0, 1], W: [-1, 0] }[side];
    if (!v) return null;
    const q = rot(v[0], v[1], r || 0);
    return q[1] < -0.5 ? 'N' : q[0] > 0.5 ? 'E' : q[1] > 0.5 ? 'S' : 'W';
  }
  /** Signed distance from the board's (rounded) outline: negative inside. */
  function boardSdf(B, x, y) {
    const r = Math.min(B.r || 0, B.w / 2, B.h / 2);
    const qx = Math.abs(x - B.w / 2) - (B.w / 2 - r), qy = Math.abs(y - B.h / 2) - (B.h / 2 - r);
    return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
  }
  function outline(B, steps) {
    const r = Math.min(B.r || 0, B.w / 2, B.h / 2), n = r > 0 ? (steps || 8) : 0, pts = [];
    const corner = (cx, cy, a0) => {
      for (let i = 0; i <= n; i++) { const a = (a0 + 90 * i / n) * Math.PI / 180; pts.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]); }
    };
    if (!n) return [[0, 0], [B.w, 0], [B.w, B.h], [0, B.h], [0, 0]];
    corner(B.w - r, r, 270); corner(B.w - r, B.h - r, 0); corner(r, B.h - r, 90); corner(r, r, 180);
    pts.push(pts[0].slice());
    return pts;
  }
  /** Mounting holes (M3, no copper) in the corners when asked. */
  function holes(D) {
    const B = D.board, R = rules(D);
    if (!B || !B.holes) return [];
    const i = Math.max(3.5, (B.r || 0) * 0.6 + 1.5);
    return [[i, i], [B.w - i, i], [B.w - i, B.h - i], [i, B.h - i]].map((q) => ({ x: q[0], y: q[1], d: R.holeD, keep: R.holeKeep }));
  }

  // copper shapes: {k:'c', x1,y1,x2,y2, r} (a round-ended stroke; a circle when the ends meet) or {k:'r', cx,cy,hw,hh}
  function padObj(p) {
    if (p.s === 'r') return { k: 'r', cx: p.x, cy: p.y, hw: p.w / 2, hh: p.h / 2 };
    if (p.s === 'o' && Math.abs(p.w - p.h) > 1e-6) {
      if (p.w > p.h) { const e = (p.w - p.h) / 2; return { k: 'c', x1: p.x - e, y1: p.y, x2: p.x + e, y2: p.y, r: p.h / 2 }; }
      const e = (p.h - p.w) / 2; return { k: 'c', x1: p.x, y1: p.y - e, x2: p.x, y2: p.y + e, r: p.w / 2 };
    }
    return { k: 'c', x1: p.x, y1: p.y, x2: p.x, y2: p.y, r: p.w / 2 };
  }
  function segPt(x1, y1, x2, y2, px, py) {
    const dx = x2 - x1, dy = y2 - y1, L = dx * dx + dy * dy;
    let t = L ? ((px - x1) * dx + (py - y1) * dy) / L : 0;
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    return Math.hypot(px - x1 - t * dx, py - y1 - t * dy);
  }
  function dist(o, x, y) {
    if (o.k === 'r') { const dx = Math.abs(x - o.cx) - o.hw, dy = Math.abs(y - o.cy) - o.hh; return Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0); }
    return segPt(o.x1, o.y1, o.x2, o.y2, x, y) - o.r;
  }
  function bbox(o) {
    return o.k === 'r' ? [o.cx - o.hw, o.cy - o.hh, o.cx + o.hw, o.cy + o.hh]
      : [Math.min(o.x1, o.x2) - o.r, Math.min(o.y1, o.y2) - o.r, Math.max(o.x1, o.x2) + o.r, Math.max(o.y1, o.y2) + o.r];
  }
  function orient(ax, ay, bx, by, cx, cy) { const v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax); return v > 1e-12 ? 1 : v < -1e-12 ? -1 : 0; }
  function segsCross(a, b, c, d) {
    const o1 = orient(a[0], a[1], b[0], b[1], c[0], c[1]), o2 = orient(a[0], a[1], b[0], b[1], d[0], d[1]);
    const o3 = orient(c[0], c[1], d[0], d[1], a[0], a[1]), o4 = orient(c[0], c[1], d[0], d[1], b[0], b[1]);
    return o1 !== o2 && o3 !== o4;
  }
  function segSeg(a, b, c, d) {
    if (segsCross(a, b, c, d)) return 0;
    return Math.min(segPt(c[0], c[1], d[0], d[1], a[0], a[1]), segPt(c[0], c[1], d[0], d[1], b[0], b[1]),
      segPt(a[0], a[1], b[0], b[1], c[0], c[1]), segPt(a[0], a[1], b[0], b[1], d[0], d[1]));
  }
  function rectSeg(o, a, b) {
    const r0 = { k: 'r', cx: o.cx, cy: o.cy, hw: o.hw, hh: o.hh };
    if (dist(r0, a[0], a[1]) <= 0 || dist(r0, b[0], b[1]) <= 0) return 0;
    const cs = [[o.cx - o.hw, o.cy - o.hh], [o.cx + o.hw, o.cy - o.hh], [o.cx + o.hw, o.cy + o.hh], [o.cx - o.hw, o.cy + o.hh]];
    let m = Math.min(dist(r0, a[0], a[1]), dist(r0, b[0], b[1]));
    for (let i = 0; i < 4; i++) {
      const c = cs[i], d = cs[(i + 1) % 4];
      if (segsCross(a, b, c, d)) return 0;
      m = Math.min(m, segPt(a[0], a[1], b[0], b[1], c[0], c[1]));
    }
    return m;
  }
  /** The gap between two copper shapes (≤ 0: touching). */
  function gap(a, b) {
    if (a.k === 'c' && b.k === 'c') return segSeg([a.x1, a.y1], [a.x2, a.y2], [b.x1, b.y1], [b.x2, b.y2]) - a.r - b.r;
    if (a.k === 'r' && b.k === 'r') return Math.hypot(Math.max(0, Math.abs(a.cx - b.cx) - a.hw - b.hw), Math.max(0, Math.abs(a.cy - b.cy) - a.hh - b.hh));
    const r = a.k === 'r' ? a : b, c = a.k === 'r' ? b : a;
    return rectSeg(r, [c.x1, c.y1], [c.x2, c.y2]) - c.r;
  }

  // ------------------------------------------------------------------ the board as copper

  /** Every pad on the board with its net (index into D.nets; −1 = no net). */
  function model(D) {
    const nets = D.nets || [], pinNet = new Map();
    nets.forEach((n, i) => (n.p || []).forEach((p) => pinNet.set(p, i)));
    const pads = [];
    (D.parts || []).forEach((pt) => partPads(pt, pt.x, pt.y, pt.rot || 0).forEach((p) => {
      const k = p.ref + '.' + p.n;
      p.net = pinNet.has(k) ? pinNet.get(k) : -1;
      pads.push(p);
    }));
    // a copper id per shape: net + 1, or a number of its own for a pad with no net
    pads.forEach((p, i) => { p.id = p.net >= 0 ? p.net + 1 : nets.length + 1 + i; });
    return { nets, pinNet, pads };
  }

  /** All copper as shapes: {o (shape), L: [layers], id, net, kind, ref}. */
  function copper(D, M) {
    M = M || model(D);
    const out = [];
    M.pads.forEach((p) => out.push({ o: padObj(p), L: p.smd ? [0] : [0, 1], id: p.id, net: p.net, kind: 'pad', pad: p }));
    const R = rules(D), idx = new Map(M.nets.map((n, i) => [n.n, i]));
    (D.tracks || []).forEach((t, ti) => {
      const net = idx.has(t.n) ? idx.get(t.n) : -1;
      for (let i = 1; i < t.p.length; i++) out.push({ o: { k: 'c', x1: t.p[i - 1][0], y1: t.p[i - 1][1], x2: t.p[i][0], y2: t.p[i][1], r: (t.w || R.track) / 2 },
        L: [t.l === 'B' ? 1 : 0], id: net + 1, net, kind: 'track', ti });
    });
    (D.vias || []).forEach((v, vi) => {
      const net = idx.has(v.n) ? idx.get(v.n) : -1;
      out.push({ o: { k: 'c', x1: v.x, y1: v.y, x2: v.x, y2: v.y, r: (v.d || R.via) / 2 }, L: [0, 1], id: net + 1, net, kind: 'via', vi });
    });
    return out;
  }

  // ------------------------------------------------------------------ the router

  function Heap() { this.k = new Float64Array(1024); this.v = new Int32Array(1024); this.n = 0; }
  Heap.prototype.push = function (key, val) {
    if (this.n === this.k.length) { const k = new Float64Array(this.n * 2), v = new Int32Array(this.n * 2); k.set(this.k); v.set(this.v); this.k = k; this.v = v; }
    let i = this.n++;
    const K = this.k, V = this.v;
    while (i > 0) { const p = (i - 1) >> 1; if (K[p] <= key) break; K[i] = K[p]; V[i] = V[p]; i = p; }
    K[i] = key; V[i] = val;
  };
  Heap.prototype.pop = function () {
    const K = this.k, V = this.v, top = V[0], n = --this.n;
    if (n > 0) {
      const key = K[n], val = V[n];
      let i = 0;
      for (;;) { let c = 2 * i + 1; if (c >= n) break; if (c + 1 < n && K[c + 1] < K[c]) c++; if (K[c] >= key) break; K[i] = K[c]; V[i] = V[c]; i = c; }
      K[i] = key; V[i] = val;
    }
    return top;
  };

  /** Order: small nets first, ground last (the ground fill joins most ground pads anyway). */
  function netOrder(D, M) {
    const sz = M.nets.map((n, i) => {
      const ps = M.pads.filter((p) => p.net === i);
      if (ps.length < 2) return null;
      let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
      ps.forEach((p) => { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); });
      return { i, s: (x1 - x0) + (y1 - y0) + (isGnd(n.n) ? 1e5 : 0) + (isPower(n.n) ? -5 : 0) };
    }).filter(Boolean);
    sz.sort((a, b) => a.s - b.s);
    return sz.map((o) => o.i);
  }

  /**
   * Routes every net from nothing (old tracks and vias are replaced). Returns {tracks, vias, failed: [{net, pins}], ms}.
   * opt: {g: grid mm, attempts, maxExpand}
   */
  function route(D, opt) {
    opt = opt || {};
    const t0 = Date.now(), M = model(D);
    let order = netOrder(D, M), best = null;
    const att = opt.attempts || 3;
    for (let a = 0; a < att; a++) {
      const r = routeOnce(D, M, order, opt);
      const miss = r.failed.reduce((s, f) => s + f.pins.length, 0);
      if (!best || miss < best.miss) { best = r; best.miss = miss; }
      if (!miss) break;
      const bad = r.failed.map((f) => f.net);
      order = bad.concat(order.filter((n) => bad.indexOf(n) < 0));
    }
    D.tracks = best.tracks;
    D.vias = best.vias;
    best.ms = Date.now() - t0;
    return best;
  }

  function routeOnce(D, M, order, opt) {
    const B = D.board, R = rules(D), g = opt.g || 0.25;
    const nx = Math.floor(B.w / g) + 1, ny = Math.floor(B.h / g) + 1, N = nx * ny;
    const own = [new Int32Array(N), new Int32Array(N)], infl = g * 0.71;
    const mark = (o, layers, val, extra) => {
      const bb = bbox(o), e = extra == null ? infl : extra;
      const i0 = Math.max(0, Math.floor((bb[0] - e) / g)), i1 = Math.min(nx - 1, Math.ceil((bb[2] + e) / g));
      const j0 = Math.max(0, Math.floor((bb[1] - e) / g)), j1 = Math.min(ny - 1, Math.ceil((bb[3] + e) / g));
      for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) {
        if (dist(o, i * g, j * g) > e) continue;
        const c = j * nx + i;
        for (const L of layers) if (val === -1 || own[L][c] === 0) own[L][c] = val;
      }
    };
    holes(D).forEach((h) => mark({ k: 'c', x1: h.x, y1: h.y, x2: h.x, y2: h.y, r: h.keep }, [0, 1], -1));
    M.pads.forEach((p) => mark(padObj(p), p.smd ? [0] : [0, 1], p.id));

    const S2 = 2 * N, gs = new Float32Array(S2), came = new Int32Array(S2), dirOf = new Int8Array(S2), closed = new Uint8Array(S2);
    const pass = new Int8Array(S2), viaOk = new Int8Array(N), target = new Int32Array(S2);
    const tracks = [], vias = [], failed = [];
    const DX = [1, 1, 0, -1, -1, -1, 0, 1], DY = [0, 1, 1, 1, 0, -1, -1, -1];
    const maxExpand = opt.maxExpand || 600000;

    for (const ni of order) {
      const net = M.nets[ni], v = ni + 1, w = netWidth(D, net), hw = w / 2;
      const pins = M.pads.filter((p) => p.net === ni);
      if (pins.length < 2) continue;
      const discR = (hw + R.clr + g) / g, viaR = (R.via / 2 + R.clr + g) / g;
      const offs = disc(discR), voffs = disc(viaR);
      const margin = R.edge + hw;
      pass.fill(0); viaOk.fill(0);
      const passable = (L, c) => {
        const s = L * N + c;
        if (pass[s]) return pass[s] === 1;
        const i = c % nx, j = (c - i) / nx;
        let ok = boardSdf(B, i * g, j * g) <= -margin;
        if (ok) {
          const O = own[L];
          for (let k = 0; k < offs.length; k += 2) {
            const ii = i + offs[k], jj = j + offs[k + 1];
            if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
            const o = O[jj * nx + ii];
            if (o !== 0 && o !== v) { ok = false; break; }
          }
        }
        pass[s] = ok ? 1 : 2;
        return ok;
      };
      const viaFits = (c) => {
        if (viaOk[c]) return viaOk[c] === 1;
        const i = c % nx, j = (c - i) / nx;
        let ok = boardSdf(B, i * g, j * g) <= -(R.edge + R.via / 2);
        for (let L = 0; ok && L < 2; L++) {
          const O = own[L];
          for (let k = 0; k < voffs.length; k += 2) {
            const ii = i + voffs[k], jj = j + voffs[k + 1];
            if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
            const o = O[jj * nx + ii];
            if (o !== 0 && o !== v) { ok = false; break; }
          }
        }
        viaOk[c] = ok ? 1 : 2;
        return ok;
      };
      const cellsIn = (p) => {
        const o = padObj(p), bb = bbox(o), out = [];
        for (let j = Math.max(0, Math.floor(bb[1] / g)); j <= Math.min(ny - 1, Math.ceil(bb[3] / g)); j++)
          for (let i = Math.max(0, Math.floor(bb[0] / g)); i <= Math.min(nx - 1, Math.ceil(bb[2] / g)); i++)
            if (dist(o, i * g, j * g) <= -0.05) { const c = j * nx + i; if (!p.smd) out.push(c, N + c); else out.push(c); }
        return out;
      };
      const padCells = pins.map(cellsIn);
      const tree = padCells[0].slice();
      const left = new Set(pins.map((_, k) => k).slice(1));
      target.fill(0);
      left.forEach((k) => padCells[k].forEach((s) => { target[s] = k + 1; }));

      while (left.size) {
        gs.fill(Infinity); came.fill(-1); closed.fill(0); dirOf.fill(-1);
        const tg = [];
        left.forEach((k) => tg.push(pins[k].x / g, pins[k].y / g, Math.max(pins[k].w, pins[k].h) / 2 / g));
        const h = (c) => {
          const i = c % nx, j = (c - i) / nx;
          let m = 1e9;
          for (let k = 0; k < tg.length; k += 3) {
            const dx = Math.abs(i - tg[k]), dy = Math.abs(j - tg[k + 1]);
            const d = Math.max(dx, dy) + 0.4142 * Math.min(dx, dy) - tg[k + 2];
            if (d < m) m = d;
          }
          return m > 0 ? m : 0;
        };
        const heap = new Heap();
        for (const s of tree) { if (gs[s] === 0) continue; gs[s] = 0; heap.push(h(s % N), s); }
        let found = -1, n = 0;
        while (heap.n) {
          const s = heap.pop();
          if (closed[s]) continue;
          closed[s] = 1;
          if (target[s]) { found = s; break; }
          if (++n > maxExpand) break;
          const L = s >= N ? 1 : 0, c = s - L * N, i = c % nx, j = (c - i) / nx, g0 = gs[s], pd = dirOf[s];
          for (let d = 0; d < 8; d++) {
            const ii = i + DX[d], jj = j + DY[d];
            if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
            const c2 = jj * nx + ii, s2 = L * N + c2;
            if (closed[s2]) continue;
            if (!passable(L, c2)) continue;
            let cost = (d & 1) ? 1.4142 : 1;
            if (!(d & 1)) { if (L === 0 && DY[d] !== 0) cost *= 1.25; else if (L === 1 && DX[d] !== 0) cost *= 1.25; }
            if (pd >= 0 && pd !== d) cost += pd === 8 ? 0 : 0.35;
            const g2 = g0 + cost;
            if (g2 < gs[s2]) { gs[s2] = g2; came[s2] = s; dirOf[s2] = d; heap.push(g2 + h(c2), s2); }
          }
          const s3 = (1 - L) * N + c;
          if (!closed[s3] && passable(1 - L, c) && viaFits(c)) {
            const g3 = g0 + 7;
            if (g3 < gs[s3]) { gs[s3] = g3; came[s3] = s; dirOf[s3] = 8; heap.push(g3 + h(c), s3); }
          }
        }
        if (found < 0) {
          failed.push({ net: ni, n: net.n, pins: Array.from(left).map((k) => pins[k].ref + '.' + pins[k].n) });
          break;
        }
        // the path, back to the tree
        const path = [];
        for (let s = found; s >= 0; s = came[s]) path.push(s);
        path.reverse();
        const k = target[found] - 1;
        const pts = path.map((s) => { const L = s >= N ? 1 : 0, c = s - L * N; return [c % nx, Math.floor(c / nx), L]; });
        emit(pts, g, net.n, w, pins, ni, tracks, vias);
        // copper now on the board (blocks the other nets), and the tree grows
        for (const t of tracks) if (t._new) {
          for (let q = 1; q < t.p.length; q++) mark({ k: 'c', x1: t.p[q - 1][0], y1: t.p[q - 1][1], x2: t.p[q][0], y2: t.p[q][1], r: hw }, [t.l === 'B' ? 1 : 0], v);
          delete t._new;
        }
        for (const vv of vias) if (vv._new) { mark({ k: 'c', x1: vv.x, y1: vv.y, x2: vv.x, y2: vv.y, r: R.via / 2 }, [0, 1], v); delete vv._new; }
        for (const s of path) tree.push(s);
        for (const s of padCells[k]) { tree.push(s); }
        left.delete(k);
        padCells[k].forEach((s) => { target[s] = 0; });
      }
    }
    return { tracks, vias, failed };
  }

  /** Cell offsets within r cells (flat [di, dj, …]). */
  const DISCS = new Map();
  function disc(r) {
    const key = Math.round(r * 100);
    if (DISCS.has(key)) return DISCS.get(key);
    const out = [], n = Math.ceil(r);
    for (let j = -n; j <= n; j++) for (let i = -n; i <= n; i++) if (i * i + j * j <= r * r) out.push(i, j);
    const a = Int16Array.from(out);
    DISCS.set(key, a);
    return a;
  }

  /** A grid path → tracks (straight runs joined) and vias, ending in the pad centres it starts / ends in. */
  function emit(pts, g, name, w, pins, ni, tracks, vias) {
    const at = (p) => [Math.round(p[0] * g * 1e4) / 1e4, Math.round(p[1] * g * 1e4) / 1e4];
    const inPad = (x, y, L) => pins.find((p) => (!p.smd || L === 0) && dist(padObj(p), x, y) <= 0);
    let run = [pts[0]];
    const flush = (r) => {
      if (!r.length) return;
      const L = r[0][2];
      let q = r.map(at);
      // start in a pad: from its centre; end in a pad: to its centre (the grid cells inside the pad are dropped, never cut across)
      const s = inPad(q[0][0], q[0][1], L), e = inPad(q[q.length - 1][0], q[q.length - 1][1], L);
      if (s) { let k = 0; while (k + 1 < q.length && dist(padObj(s), q[k + 1][0], q[k + 1][1]) <= 0) k++; q = [[s.x, s.y]].concat(q.slice(k)); }
      if (e && e !== s) { let k = q.length - 1; while (k - 1 > 0 && dist(padObj(e), q[k - 1][0], q[k - 1][1]) <= 0) k--; q = q.slice(0, k + 1).concat([[e.x, e.y]]); }
      const line = [q[0]];
      for (let i = 1; i < q.length; i++) {
        const p2 = q[i], b = line[line.length - 1];
        if (Math.abs(p2[0] - b[0]) < 1e-9 && Math.abs(p2[1] - b[1]) < 1e-9) continue;
        if (line.length >= 2) {
          const a = line[line.length - 2];
          const ux = b[0] - a[0], uy = b[1] - a[1], vx = p2[0] - b[0], vy = p2[1] - b[1];
          if (Math.abs(ux * vy - uy * vx) < 1e-6 * Math.hypot(ux, uy) * Math.hypot(vx, vy) && ux * vx + uy * vy > 0) { line[line.length - 1] = p2; continue; }
        }
        line.push(p2);
      }
      if (line.length >= 2) tracks.push({ n: name, l: L ? 'B' : 'F', w, p: line, _new: true });
    };
    for (let i = 1; i < pts.length; i++) {
      if (pts[i][2] !== pts[i - 1][2]) {
        flush(run);
        const q = at(pts[i]);
        vias.push({ n: name, x: q[0], y: q[1], _new: true });
        run = [pts[i]];
      } else run.push(pts[i]);
    }
    flush(run);
  }

  // ------------------------------------------------------------------ the ground fill (a grid of small squares)

  /**
   * The copper fill of one layer for one net (normally GND on the bottom): keeps R.pclr from every other net, thermal spokes
   * to its own pads, no slivers, no islands. Returns {g, nx, ny, cells: Uint8Array, rects: [[x1,y1,x2,y2]], layer, net}.
   */
  function pour(D, layer, netName, M) {
    M = M || model(D);
    const B = D.board, R = rules(D), g = 0.25, nx = Math.floor(B.w / g) + 1, ny = Math.floor(B.h / g) + 1, N = nx * ny, infl = g * 0.71;
    const ni = M.nets.findIndex((n) => n.n === netName);
    if (ni < 0) return null;
    const cu = copper(D, M).filter((c) => c.L.indexOf(layer) >= 0);
    const ok = new Uint8Array(N), ring = new Uint8Array(N);
    for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) if (boardSdf(B, i * g, j * g) <= -(R.edge + infl)) ok[j * nx + i] = 1;
    const each = (o, e, fn) => {
      const bb = bbox(o);
      const i0 = Math.max(0, Math.floor((bb[0] - e) / g)), i1 = Math.min(nx - 1, Math.ceil((bb[2] + e) / g));
      const j0 = Math.max(0, Math.floor((bb[1] - e) / g)), j1 = Math.min(ny - 1, Math.ceil((bb[3] + e) / g));
      for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) { const d = dist(o, i * g, j * g); if (d <= e) fn(j * nx + i, d, i * g, j * g); }
    };
    holes(D).forEach((h) => each({ k: 'c', x1: h.x, y1: h.y, x2: h.x, y2: h.y, r: h.keep }, infl, (c) => { ok[c] = 0; }));
    for (const c of cu) {
      if (c.net === ni) {
        if (c.kind === 'pad') each(c.o, R.gap + infl, (q) => { ring[q] = 1; }); // (its own pads: a ring with thermal spokes, added below)
        continue;
      }
      each(c.o, R.pclr + infl, (q) => { ok[q] = 0; });
    }
    // the plain fill without the thermal rings, opened (no thin slivers), then the spokes
    const base = new Uint8Array(N);
    for (let c = 0; c < N; c++) base[c] = ok[c] && !ring[c] ? 1 : 0;
    const er = new Uint8Array(N);
    for (let j = 1; j < ny - 1; j++) for (let i = 1; i < nx - 1; i++) {
      const c = j * nx + i;
      er[c] = base[c] && base[c - 1] && base[c + 1] && base[c - nx] && base[c + nx] ? 1 : 0;
    }
    const fill = new Uint8Array(N);
    for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
      const c = j * nx + i;
      if (!base[c]) continue;
      if (er[c] || (i > 0 && er[c - 1]) || (i < nx - 1 && er[c + 1]) || (j > 0 && er[c - nx]) || (j < ny - 1 && er[c + nx])) fill[c] = 1;
    }
    const half = R.spoke / 2;
    for (const c of cu) {
      if (c.net !== ni || c.kind !== 'pad') continue;
      const p = c.pad;
      each(c.o, R.gap + infl, (q, d, x, y) => { if (d > 0 && ok[q] && (Math.abs(x - p.x) <= half || Math.abs(y - p.y) <= half)) fill[q] = 1; });
    }
    // islands: keep only the parts that touch this net's copper
    const lab = new Int32Array(N), seed = new Uint8Array(N);
    for (const c of cu) if (c.net === ni) each(c.o, infl, (q) => { seed[q] = 1; });
    let nl = 0;
    const keep = [0], stack = new Int32Array(N);
    for (let c0 = 0; c0 < N; c0++) {
      if (!fill[c0] || lab[c0]) continue;
      nl++;
      let sp = 0, touch = 0;
      stack[sp++] = c0; lab[c0] = nl;
      while (sp) {
        const c = stack[--sp];
        if (seed[c]) touch = 1;
        const i = c % nx;
        if (i > 0 && fill[c - 1] && !lab[c - 1]) { lab[c - 1] = nl; stack[sp++] = c - 1; }
        if (i < nx - 1 && fill[c + 1] && !lab[c + 1]) { lab[c + 1] = nl; stack[sp++] = c + 1; }
        if (c >= nx && fill[c - nx] && !lab[c - nx]) { lab[c - nx] = nl; stack[sp++] = c - nx; }
        if (c < N - nx && fill[c + nx] && !lab[c + nx]) { lab[c + nx] = nl; stack[sp++] = c + nx; }
      }
      keep.push(touch);
    }
    for (let c = 0; c < N; c++) if (fill[c] && !keep[lab[c]]) { fill[c] = 0; lab[c] = 0; }
    return { g, nx, ny, cells: fill, lab, rects: rects(fill, nx, ny, g), layer, net: ni, name: netName };
  }

  /** Filled cells → rectangles (runs in a row, rows with the same runs joined). */
  function rects(fill, nx, ny, g) {
    const out = [];
    let open = new Map();
    for (let j = 0; j <= ny; j++) {
      const runs = new Map();
      if (j < ny) {
        let i = 0;
        while (i < nx) {
          if (!fill[j * nx + i]) { i++; continue; }
          const s = i;
          while (i < nx && fill[j * nx + i]) i++;
          runs.set(s + ':' + (i - 1), [s, i - 1]);
        }
      }
      const next = new Map();
      open.forEach((o, key) => {
        if (runs.has(key)) { next.set(key, o); runs.delete(key); }
        else out.push([(o.i0 - 0.5) * g, (o.j0 - 0.5) * g, (o.i1 + 0.5) * g, (j - 0.5) * g]);
      });
      runs.forEach((r, key) => next.set(key, { i0: r[0], i1: r[1], j0: j }));
      open = next;
    }
    return out.map((r) => r.map((v) => Math.round(v * 1e4) / 1e4));
  }

  // ------------------------------------------------------------------ what is joined to what

  function UF(n) { const p = new Int32Array(n); for (let i = 0; i < n; i++) p[i] = i; return { f(x) { while (p[x] !== x) { p[x] = p[p[x]]; x = p[x]; } return x; }, u(a, b) { a = this.f(a); b = this.f(b); if (a !== b) p[a] = b; } }; }

  /**
   * Which pads are really joined (tracks, vias, the fill). Returns {groups: per net [[pad indexes]…], open: [{net, a, b}]}
   * where open holds the shortest missing link between the groups of each net (the "rat's nest" lines).
   */
  function connectivity(D, M, fills) {
    M = M || model(D);
    const cu = copper(D, M), n = cu.length, fl = (fills || []).filter(Boolean);
    const extra = fl.reduce((s, f) => s + f.lab.reduce((m, v) => (v > m ? v : m), 0) + 1, 0);
    const uf = UF(n + extra + 1);
    const bb = cu.map((c) => bbox(c.o));
    for (let a = 0; a < n; a++) for (let b = a + 1; b < n; b++) {
      const A = cu[a], Bc = cu[b];
      if (A.id !== Bc.id) continue;
      if (!A.L.some((l) => Bc.L.indexOf(l) >= 0)) continue;
      if (bb[a][0] > bb[b][2] + 0.01 || bb[b][0] > bb[a][2] + 0.01 || bb[a][1] > bb[b][3] + 0.01 || bb[b][1] > bb[a][3] + 0.01) continue;
      if (gap(A.o, Bc.o) <= 0.001) uf.u(a, b);
    }
    let base = n;
    for (const f of fl) {
      const g = f.g;
      for (let a = 0; a < n; a++) {
        const c = cu[a];
        if (c.net !== f.net || c.L.indexOf(f.layer) < 0) continue;
        const b2 = bb[a];
        for (let j = Math.max(0, Math.floor(b2[1] / g) - 1); j <= Math.min(f.ny - 1, Math.ceil(b2[3] / g) + 1); j++)
          for (let i = Math.max(0, Math.floor(b2[0] / g) - 1); i <= Math.min(f.nx - 1, Math.ceil(b2[2] / g) + 1); i++) {
            const q = j * f.nx + i;
            if (f.cells[q] && f.lab[q] && dist(c.o, i * g, j * g) <= g * 0.71) uf.u(a, base + f.lab[q]);
          }
      }
      base += f.lab.reduce((m, v) => (v > m ? v : m), 0) + 1;
    }
    const groups = [], open = [];
    M.nets.forEach((net, ni) => {
      const ps = [];
      cu.forEach((c, k) => { if (c.kind === 'pad' && c.net === ni) ps.push(k); });
      const by = new Map();
      ps.forEach((k) => { const r = uf.f(k); if (!by.has(r)) by.set(r, []); by.get(r).push(cu[k].pad); });
      const gs = Array.from(by.values());
      groups[ni] = gs;
      // join the groups by their closest pads (Prim over groups)
      const inT = [gs[0]];
      const rest = gs.slice(1);
      while (rest.length) {
        let best = null;
        for (const t of inT) for (let r = 0; r < rest.length; r++) for (const p of t) for (const q of rest[r]) {
          const d = Math.hypot(p.x - q.x, p.y - q.y);
          if (!best || d < best.d) best = { d, p, q, r };
        }
        open.push({ net: ni, n: net.n, a: best.p, b: best.q });
        inT.push(rest.splice(best.r, 1)[0]);
      }
    });
    return { groups, open };
  }

  // ------------------------------------------------------------------ placing the parts

  /** Places every part (edge parts on the board's sides first, then the most-connected); null when the board is too small. */
  function autoplace(D, opt) {
    opt = opt || {};
    const B = D.board, step = opt.step || 1.27, gapMm = 0.6, hs = holes(D);
    const M = model(D), byRef = new Map();
    M.pads.forEach((p) => { if (!byRef.has(p.ref)) byRef.set(p.ref, []); byRef.get(p.ref).push(p); });
    const netsOf = (ref) => new Set((byRef.get(ref) || []).filter((p) => p.net >= 0 && !isGnd(M.nets[p.net].n)).map((p) => p.net));
    const deg = new Map(D.parts.map((pt) => [pt.ref, netsOf(pt.ref).size]));
    const edgeParts = D.parts.filter((pt) => { const f = P.fpOf(pt); return f && f.edge; });
    const others = D.parts.filter((pt) => edgeParts.indexOf(pt) < 0).sort((a, b) => deg.get(b.ref) - deg.get(a.ref) || (area(b) - area(a)));
    const placed = [];
    const fits = (pt, x, y, r) => {
      const f = P.fpOf(pt), c = courtOf(pt, x, y, r), oh = (f && f.overhang) || 0, face = f && f.edge ? sideAfter(f.edge, r) : null;
      const m = 0.4;
      if (c[0] < m - (face === 'W' ? oh + m : 0) || c[1] < m - (face === 'N' ? oh + m : 0) || c[2] > B.w - m + (face === 'E' ? oh + m : 0) || c[3] > B.h - m + (face === 'S' ? oh + m : 0)) return false;
      for (const q of placed) { const d = q.c; if (c[0] < d[2] + gapMm && d[0] < c[2] + gapMm && c[1] < d[3] + gapMm && d[1] < c[3] + gapMm) return false; }
      for (const h of hs) { const dx = Math.max(c[0] - h.x, 0, h.x - c[2]), dy = Math.max(c[1] - h.y, 0, h.y - c[3]); if (Math.hypot(dx, dy) < h.keep + 0.3) return false; }
      return true;
    };
    const cost = (pt, x, y, r) => {
      let s = 0;
      const pads = partPads(pt, x, y, r);
      for (const p of pads) {
        const k = M.pinNet.get(pt.ref + '.' + p.n);
        if (k == null) continue;
        const gnd = isGnd(M.nets[k].n);
        let best = Infinity;
        for (const q of placed) for (const qp of q.pads) if (qp.net === k) { const d = Math.abs(qp.x - p.x) + Math.abs(qp.y - p.y); if (d < best) best = d; }
        if (best < Infinity) s += best * (gnd ? 0.3 : 1);
      }
      return s + 0.02 * (Math.abs(x - B.w / 2) + Math.abs(y - B.h / 2));
    };
    const put = (pt, x, y, r) => {
      pt.x = Math.round(x * 1000) / 1000; pt.y = Math.round(y * 1000) / 1000; pt.rot = r;
      const pads = partPads(pt, pt.x, pt.y, r).map((p) => Object.assign(p, { net: M.pinNet.has(pt.ref + '.' + p.n) ? M.pinNet.get(pt.ref + '.' + p.n) : -1 }));
      placed.push({ pt, c: courtOf(pt, pt.x, pt.y, r), pads });
    };
    // edge parts: the first on the left, the next on the right, then top, bottom (a USB socket goes right)
    const sides = ['W', 'E', 'N', 'S'];
    edgeParts.sort((a, b) => (P.fpOf(a).id === 'USB_A_TH') - (P.fpOf(b).id === 'USB_A_TH'));
    let si = 0;
    for (const pt of edgeParts) {
      const f = P.fpOf(pt);
      const side = f.id === 'USB_A_TH' ? 'E' : sides[Math.min(si++, 3)];
      const r = [0, 90, 180, 270].find((rr) => sideAfter(f.edge, rr) === side);
      let best = null;
      const c0 = courtOf(pt, 0, 0, r), oh = f.overhang || 0;
      const fixX = side === 'W' ? -c0[0] - oh : side === 'E' ? B.w - c0[2] + oh : null;
      const fixY = side === 'N' ? -c0[1] - oh : side === 'S' ? B.h - c0[3] + oh : null;
      for (let t = 0; t <= (fixX != null ? B.h : B.w); t += step) {
        const x = fixX != null ? fixX : t, y = fixY != null ? fixY : t;
        if (!fits(pt, x, y, r)) continue;
        const s = cost(pt, x, y, r) + 0.2 * Math.abs(t - (fixX != null ? B.h / 2 : B.w / 2));
        if (!best || s < best.s) best = { s, x, y };
      }
      if (!best) return null;
      put(pt, best.x, best.y, r);
    }
    const placeOne = (pt) => {
      let best = null;
      for (const r of [0, 90, 180, 270]) for (let y = 0; y <= B.h; y += step) for (let x = 0; x <= B.w; x += step) {
        if (!fits(pt, x, y, r)) continue;
        const s = cost(pt, x, y, r);
        if (!best || s < best.s) best = { s, x, y, r };
      }
      if (!best) return false;
      put(pt, best.x, best.y, best.r);
      return true;
    };
    for (const pt of others) if (!placeOne(pt)) return null;
    // two passes of "take one out and put it back where it is best"
    for (let pass = 0; pass < 2; pass++) for (const pt of others) {
      const k = placed.findIndex((q) => q.pt === pt);
      const old = placed.splice(k, 1)[0];
      if (!placeOne(pt)) placed.push(old);
    }
    D.tracks = []; D.vias = [];
    return true;
  }
  function area(pt) { const c = courtOf(pt, 0, 0, 0); return (c[2] - c[0]) * (c[3] - c[1]); }

  /** The board made just big enough for the parts (margin mm around them; sockets meant for the edge sit on it); parts move with it. */
  function fitBoard(D, margin) {
    const m = margin == null ? 2 : margin, extra = D.board.holes ? 4.5 : 0;
    let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    D.parts.forEach((pt) => {
      const f = P.fpOf(pt), c = courtOf(pt, pt.x, pt.y, pt.rot || 0), face = f && f.edge ? sideAfter(f.edge, pt.rot || 0) : null, oh = (f && f.overhang) || 0;
      x0 = Math.min(x0, face === 'W' ? c[0] + oh : c[0] - m - extra); y0 = Math.min(y0, face === 'N' ? c[1] + oh : c[1] - m - extra);
      x1 = Math.max(x1, face === 'E' ? c[2] - oh : c[2] + m + extra); y1 = Math.max(y1, face === 'S' ? c[3] - oh : c[3] + m + extra);
    });
    if (x0 > x1) return;
    D.parts.forEach((pt) => { pt.x = Math.round((pt.x - x0) * 100) / 100; pt.y = Math.round((pt.y - y0) * 100) / 100; });
    (D.labels || []).forEach((l) => { if (!l.ref) { l.x -= x0; l.y -= y0; } });
    D.board.w = Math.max(10, Math.ceil(x1 - x0));
    D.board.h = Math.max(10, Math.ceil(y1 - y0));
    D.tracks = []; D.vias = [];
  }

  // ------------------------------------------------------------------ the checks (DRC) with Telugu reasons

  const mm = (v) => (Math.round(v * 100) / 100).toFixed(2) + ' mm';
  /**
   * Every problem a factory (or the soldering iron) would have: [{sev: 'err'|'warn'|'info', code, x, y, te, fix}].
   * fills: the ground fills (they join pads too).
   */
  function drc(D, fabId, fills, M) {
    M = M || model(D);
    const F = FABS[fabId] || FABS.jlc, R = rules(D), out = [], B = D.board;
    const add = (sev, code, x, y, te, fix) => out.push({ sev, code, x: Math.round(x * 100) / 100, y: Math.round(y * 100) / 100, te, fix });
    const cu = copper(D, M), bb = cu.map((c) => bbox(c.o));
    const name = (c) => c.kind === 'pad' ? c.pad.ref + ' పిన్ ' + c.pad.n : c.kind === 'via' ? 'వయా (' + (M.nets[c.net] ? M.nets[c.net].n : '') + ')' : (M.nets[c.net] ? M.nets[c.net].n : '') + ' దారి';
    // 1. copper of different nets too close
    const seen = new Set();
    for (let a = 0; a < cu.length; a++) for (let b = a + 1; b < cu.length; b++) {
      const A = cu[a], Bc = cu[b];
      if (A.id === Bc.id) continue;
      if (!A.L.some((l) => Bc.L.indexOf(l) >= 0)) continue;
      const need = Math.max(F.space, R.clr - 0.02); // (below the factory's minimum: wrong; below ours: a warning)
      if (bb[a][0] > bb[b][2] + need || bb[b][0] > bb[a][2] + need || bb[a][1] > bb[b][3] + need || bb[b][1] > bb[a][3] + need) continue;
      if (A.kind === 'pad' && Bc.kind === 'pad' && A.pad.ref === Bc.pad.ref) continue; // (a part's own pins: as the part is made)
      const d = gap(A.o, Bc.o);
      if (d < need - 1e-6) {
        const key = [A.kind === 'pad' ? A.pad.ref : A.kind + A.net, Bc.kind === 'pad' ? Bc.pad.ref : Bc.kind + Bc.net].sort().join('|');
        if (seen.has(key)) continue;
        seen.add(key);
        const x = (bb[a][0] + bb[a][2] + bb[b][0] + bb[b][2]) / 4, y = (bb[a][1] + bb[a][3] + bb[b][1] + bb[b][3]) / 4;
        add(d <= 0 ? 'err' : (d < F.space ? 'err' : 'warn'), d <= 0 ? 'short' : 'clear', x, y,
          d <= 0 ? '⚡ షార్ట్: ' + name(A) + ', ' + name(Bc) + ' రాగి ఒకదానికొకటి తగులుతున్నాయి. వేర్వేరు దారులు కలిస్తే సర్క్యూట్ కాలిపోవచ్చు.'
            : d < F.space ? name(A) + ', ' + name(Bc) + ' మధ్య ' + mm(d) + ' మాత్రమే (' + F.name + ' కనీసం ' + mm(F.space) + ' కావాలి). ఫ్యాక్టరీలో రాగి అంటుకుని షార్ట్ అవ్వచ్చు.'
              : name(A) + ', ' + name(Bc) + ' మధ్య ' + mm(d) + ' (ఫ్యాక్టరీ చేయగలదు, కానీ చేతితో టంకం చేసేటప్పుడు సీసం అంటుకునే ప్రమాదం; ' + mm(R.clr) + ' మంచిది).',
          'భాగాన్ని కొంచెం దూరం జరిపి ⚡ రూట్ మళ్ళీ నొక్కండి.');
      }
    }
    // 2. track widths, drills, rings
    const idx = new Map(M.nets.map((n, i) => [n.n, i]));
    const thin = new Set();
    (D.tracks || []).forEach((t) => {
      const w = t.w || R.track, net = M.nets[idx.get(t.n)], m = t.p[Math.floor(t.p.length / 2)];
      if (w < F.track - 1e-6 && !thin.has(t.n + 'f')) { thin.add(t.n + 'f'); add('err', 'track', m[0], m[1], t.n + ' దారి ' + mm(w) + ' సన్నగా ఉంది; ' + F.name + ' కనీసం ' + mm(F.track) + ' చేయగలదు.', 'దారి వెడల్పు పెంచండి.'); }
      if (net && net.a) {
        const need = widthFor(net.a, 10, B.cu || 1);
        if (w + 1e-6 < need && !thin.has(t.n)) {
          thin.add(t.n);
          add('warn', 'amps', m[0], m[1], '🔥 ' + t.n + ' దారిలో సుమారు ' + net.a + ' A కరెంట్ వెళ్తుంది; ఈ దారి ' + mm(w) + ' మాత్రమే, వేడెక్కుతుంది. కనీసం ' + mm(need) + ' కావాలి.',
            '⚡ రూట్ మళ్ళీ నొక్కండి (కరెంట్‌కి తగ్గ వెడల్పు తానే తీసుకుంటుంది).');
        }
      }
    });
    const drills = [];
    M.pads.forEach((p) => {
      if (!p.d) return;
      drills.push({ x: p.x, y: p.y, r: p.d / 2, ref: p.ref, net: p.id });
      if (p.d < F.drill - 1e-6) add('err', 'drill', p.x, p.y, p.ref + ' రంధ్రం ' + mm(p.d) + '; ' + F.name + ' కనీసం ' + mm(F.drill) + '.', 'ఫుట్‌ప్రింట్ మార్చండి.');
      const ring = (Math.min(p.w, p.h) - p.d) / 2;
      if (ring < F.ring - 1e-6) add('err', 'ring', p.x, p.y, p.ref + ' పిన్ ' + p.n + ' చుట్టూ రాగి ఉంగరం ' + mm(ring) + ' మాత్రమే (కనీసం ' + mm(F.ring) + ').', 'ప్యాడ్ పెద్దది చేయాలి.');
    });
    (D.vias || []).forEach((v) => {
      const d = v.drill || R.viaDrill, s = v.d || R.via;
      drills.push({ x: v.x, y: v.y, r: d / 2, net: idx.has(v.n) ? idx.get(v.n) + 1 : -1 });
      if (d < F.drill - 1e-6 || (s - d) / 2 < F.ring - 1e-6) add('err', 'via', v.x, v.y, 'వయా చాలా చిన్నది.', 'వయా సైజు పెంచండి.');
    });
    holes(D).forEach((h) => drills.push({ x: h.x, y: h.y, r: h.d / 2, net: -9 }));
    // 3. holes too close to each other (the drill can break the wall between)
    for (let a = 0; a < drills.length; a++) for (let b = a + 1; b < drills.length; b++) {
      const A = drills[a], Bd = drills[b];
      if (A.ref && A.ref === Bd.ref) continue;
      const d = Math.hypot(A.x - Bd.x, A.y - Bd.y) - A.r - Bd.r;
      if (d < 0.25) add(d < 0 ? 'err' : 'warn', 'holes', (A.x + Bd.x) / 2, (A.y + Bd.y) / 2, 'రెండు రంధ్రాలు ' + (d < 0 ? 'ఒకదాని మీద ఒకటి' : mm(d) + ' దగ్గరగా') + ' ఉన్నాయి; మధ్య గోడ విరిగిపోవచ్చు.', 'భాగాన్ని జరపండి.');
    }
    // 4. copper near the board's edge
    cu.forEach((c, k) => {
      const o = c.o;
      const pts = o.k === 'r' ? [[o.cx - o.hw, o.cy - o.hh], [o.cx + o.hw, o.cy - o.hh], [o.cx + o.hw, o.cy + o.hh], [o.cx - o.hw, o.cy + o.hh]] : [[o.x1, o.y1], [o.x2, o.y2]];
      const r = o.k === 'r' ? 0 : o.r;
      const d = -Math.max(...pts.map((q) => boardSdf(B, q[0], q[1]))) - r;
      if (d < F.edge - 1e-6) {
        const f = c.kind === 'pad' ? P.fpOf(D.parts.find((pt) => pt.ref === c.pad.ref)) : null;
        if (f && f.overhang) return; // (a socket made to sit on the edge)
        add('err', 'edge', (bb[k][0] + bb[k][2]) / 2, (bb[k][1] + bb[k][3]) / 2, name(c) + ' రాగి బోర్డ్ అంచుకి ' + mm(Math.max(0, d)) + ' దగ్గరగా ఉంది (కనీసం ' + mm(F.edge) + '); కోసేటప్పుడు తెగిపోవచ్చు.', 'భాగాన్ని లోపలికి జరపండి, లేదా బోర్డ్ పెద్దది చేయండి.');
      }
    });
    // 5. parts on top of each other / outside the board / on a screw hole
    const cs = D.parts.map((pt) => ({ pt, c: courtOf(pt, pt.x, pt.y, pt.rot || 0), f: P.fpOf(pt) }));
    for (let a = 0; a < cs.length; a++) {
      const A = cs[a], c = A.c, face = A.f && A.f.edge ? sideAfter(A.f.edge, A.pt.rot || 0) : null, oh = (A.f && A.f.overhang) || 0;
      if (c[0] < -(face === 'W' ? oh + 0.5 : 0.01) || c[1] < -(face === 'N' ? oh + 0.5 : 0.01) || c[2] > B.w + (face === 'E' ? oh + 0.5 : 0.01) || c[3] > B.h + (face === 'S' ? oh + 0.5 : 0.01))
        add('err', 'outside', (c[0] + c[2]) / 2, (c[1] + c[3]) / 2, A.pt.ref + ' బోర్డ్ బయటికి వెళ్ళింది.', 'లోపలికి జరపండి, లేదా 📏 బోర్డ్ సైజు పెంచండి.');
      for (let b = a + 1; b < cs.length; b++) {
        const d = cs[b].c;
        const ox = Math.min(c[2], d[2]) - Math.max(c[0], d[0]), oy = Math.min(c[3], d[3]) - Math.max(c[1], d[1]);
        if (ox > 0.05 && oy > 0.05) add('err', 'overlap', (Math.max(c[0], d[0]) + Math.min(c[2], d[2])) / 2, (Math.max(c[1], d[1]) + Math.min(c[3], d[3])) / 2,
          A.pt.ref + ', ' + cs[b].pt.ref + ' ఒకదాని మీద ఒకటి వస్తున్నాయి; అసలు బోర్డ్ మీద రెండూ పట్టవు.', 'ఒక భాగాన్ని పక్కకి జరపండి, లేదా 🔀 అమర్చు నొక్కండి.');
      }
      holes(D).forEach((h) => { const dx = Math.max(c[0] - h.x, 0, h.x - c[2]), dy = Math.max(c[1] - h.y, 0, h.y - c[3]); if (Math.hypot(dx, dy) < h.d / 2 + 0.5) add('err', 'hole', h.x, h.y, A.pt.ref + ' స్క్రూ రంధ్రం మీదికి వచ్చింది.', 'భాగాన్ని జరపండి.'); });
    }
    // 6. what is still not joined
    const con = connectivity(D, M, fills);
    con.open.forEach((o) => add('err', 'open', (o.a.x + o.b.x) / 2, (o.a.y + o.b.y) / 2,
      '🔗 ' + o.a.ref + ' పిన్ ' + o.a.n + ', ' + o.b.ref + ' పిన్ ' + o.b.n + ' (' + o.n + ') ఇంకా కలవలేదు. ఇలాగే ఆర్డర్ చేస్తే బోర్డ్ పనిచేయదు.',
      '⚡ రూట్ నొక్కండి. కుదరకపోతే భాగాల్ని కొంచెం దూరం జరపండి లేదా బోర్డ్ పెద్దది చేయండి.'));
    // 7. footprints to check with a ruler against the part he buys
    const told = new Set();
    D.parts.forEach((pt) => {
      const f = P.fpOf(pt);
      if (!f || !f.verify || told.has(f.id)) return;
      told.add(f.id);
      add('info', 'verify', pt.x, pt.y, '📏 ' + pt.ref + ': ' + f.verify + '. అమ్మే వాళ్ళ భాగాలు కొద్దిగా వేరుగా ఉండొచ్చు.', 'భాగం కొన్నాక స్కేల్‌తో కొలిచి సరిచూడండి; తేడా ఉంటే చెప్పండి.');
    });
    D.parts.forEach((pt) => { if (!P.fpOf(pt)) add('err', 'fp', pt.x, pt.y, pt.ref + ' కి ఫుట్‌ప్రింట్ (బోర్డ్ మీద ఆకారం) లేదు.', 'వేరే భాగం ఎంచుకోండి.'); });
    const order = { err: 0, warn: 1, info: 2 };
    out.sort((a, b) => order[a.sev] - order[b.sev]);
    return { list: out, errors: out.filter((f) => f.sev === 'err').length, warns: out.filter((f) => f.sev === 'warn').length, con };
  }

  // ------------------------------------------------------------------ silkscreen: our own stroke font and the part outlines

  // glyphs on a 4 × 6 grid (y up), each a list of polylines [x0,y0,x1,y1,…]
  const GLYPH = {
    '0': [[1, 0, 0, 1, 0, 5, 1, 6, 3, 6, 4, 5, 4, 1, 3, 0, 1, 0], [0.6, 1.2, 3.4, 4.8]], '1': [[1, 5, 2, 6, 2, 0], [1, 0, 3, 0]],
    '2': [[0, 5, 1, 6, 3, 6, 4, 5, 4, 4, 0, 0, 4, 0]], '3': [[0, 5, 1, 6, 3, 6, 4, 5, 4, 4, 3, 3, 4, 2, 4, 1, 3, 0, 1, 0, 0, 1], [1.5, 3, 3, 3]],
    '4': [[3, 0, 3, 6, 0, 2, 4, 2]], '5': [[4, 6, 0, 6, 0, 3, 3, 3, 4, 2, 4, 1, 3, 0, 0, 0]], '6': [[3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1, 4, 2, 3, 3, 0, 3]],
    '7': [[0, 6, 4, 6, 1, 0]], '8': [[1, 3, 0, 4, 0, 5, 1, 6, 3, 6, 4, 5, 4, 4, 3, 3, 1, 3, 0, 2, 0, 1, 1, 0, 3, 0, 4, 1, 4, 2, 3, 3]],
    '9': [[4, 3, 1, 3, 0, 4, 0, 5, 1, 6, 3, 6, 4, 5, 4, 1, 3, 0, 1, 0]],
    A: [[0, 0, 0, 4, 2, 6, 4, 4, 4, 0], [0, 3, 4, 3]], B: [[0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3], [3, 3, 4, 2, 4, 1, 3, 0, 0, 0]],
    C: [[4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1]], D: [[0, 0, 0, 6, 3, 6, 4, 5, 4, 1, 3, 0, 0, 0]], E: [[4, 0, 0, 0, 0, 6, 4, 6], [0, 3, 3, 3]],
    F: [[0, 0, 0, 6, 4, 6], [0, 3, 3, 3]], G: [[4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1, 4, 3, 2, 3]], H: [[0, 0, 0, 6], [4, 0, 4, 6], [0, 3, 4, 3]],
    I: [[1, 0, 3, 0], [2, 0, 2, 6], [1, 6, 3, 6]], J: [[2, 6, 4, 6], [3, 6, 3, 1, 2, 0, 1, 0, 0, 1]], K: [[0, 0, 0, 6], [4, 6, 0, 2], [1.3, 3.3, 4, 0]],
    L: [[0, 6, 0, 0, 4, 0]], M: [[0, 0, 0, 6, 2, 3, 4, 6, 4, 0]], N: [[0, 0, 0, 6, 4, 0, 4, 6]], O: [[1, 0, 0, 1, 0, 5, 1, 6, 3, 6, 4, 5, 4, 1, 3, 0, 1, 0]],
    P: [[0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3]], Q: [[1, 0, 0, 1, 0, 5, 1, 6, 3, 6, 4, 5, 4, 1, 3, 0, 1, 0], [2.5, 1.5, 4, 0]],
    R: [[0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3], [2, 3, 4, 0]], S: [[4, 5, 3, 6, 1, 6, 0, 5, 0, 4, 1, 3, 3, 3, 4, 2, 4, 1, 3, 0, 1, 0, 0, 1]],
    T: [[0, 6, 4, 6], [2, 6, 2, 0]], U: [[0, 6, 0, 1, 1, 0, 3, 0, 4, 1, 4, 6]], V: [[0, 6, 2, 0, 4, 6]], W: [[0, 6, 1, 0, 2, 3, 3, 0, 4, 6]],
    X: [[0, 0, 4, 6], [0, 6, 4, 0]], Y: [[0, 6, 2, 3, 4, 6], [2, 3, 2, 0]], Z: [[0, 6, 4, 6, 0, 0, 4, 0]],
    '+': [[0, 3, 4, 3], [2, 1, 2, 5]], '-': [[0.5, 3, 3.5, 3]], '.': [[1.8, 0, 2.2, 0, 2.2, 0.4, 1.8, 0.4, 1.8, 0]], '/': [[0, 0, 4, 6]],
    '(': [[3, 6, 1.5, 4.5, 1.5, 1.5, 3, 0]], ')': [[1, 6, 2.5, 4.5, 2.5, 1.5, 1, 0]], ':': [[2, 1, 2, 1.4], [2, 4.2, 2, 4.6]], '_': [[0, 0, 4, 0]],
    '=': [[0, 2, 4, 2], [0, 4, 4, 4]], '~': [[0, 3, 1, 4, 3, 2, 4, 3]], '>': [[0, 6, 4, 3, 0, 0]], '<': [[4, 6, 0, 3, 4, 0]], '#': [[1, 0, 1.5, 6], [2.5, 0, 3, 6], [0, 2, 4, 2], [0, 4, 4, 4]]
  };
  /** Text as polylines (h = letter height mm; align 'l' | 'c' | 'r'; rot 0 / 90 / 180 / 270 around (x, y)). */
  function text(str, x, y, h, align, r) {
    const s = String(str || '').toUpperCase(), u = h / 6, adv = 6 * u, wTot = s.length ? (s.length * 6 - 2) * u : 0;
    const x0 = align === 'l' ? 0 : align === 'r' ? -wTot : -wTot / 2;
    const out = [];
    for (let i = 0; i < s.length; i++) {
      const gl = GLYPH[s[i]];
      if (!gl) continue;
      for (const pl of gl) {
        const pts = [];
        for (let k = 0; k < pl.length; k += 2) {
          const q = rot(x0 + i * adv + pl[k] * u, h / 2 - pl[k + 1] * u, r || 0);
          pts.push(x + q[0], y + q[1]);
        }
        out.push(pts);
      }
    }
    return out;
  }
  function textWidth(str, h) { const n = String(str || '').length; return n ? (n * 6 - 2) * h / 6 : 0; }

  /** Everything white on the top: part outlines, names, his labels; cut back where it would sit on a pad. */
  function silk(D, M) {
    M = M || model(D);
    const R = rules(D), lines = [];
    (D.parts || []).forEach((pt) => {
      const f = P.fpOf(pt);
      if (!f) return;
      (f.silk || []).forEach((s) => {
        const pts = [];
        for (let k = 0; k < s.p.length; k += 2) { const q = rot(s.p[k], s.p[k + 1], pt.rot || 0); pts.push(pt.x + q[0], pt.y + q[1]); }
        lines.push(pts);
      });
      if (pt.noref) return;
      const at = refAt(D, M, pt, R);
      text(pt.ref, at[0], at[1], R.textH, 'c', at[2]).forEach((l) => lines.push(l));
    });
    (D.parts || []).forEach((pt) => {
      if (!pt.pl) return;
      const names = P.pinsOf(pt), off = pt.plo || 1.9;
      partPads(pt, pt.x, pt.y, pt.rot || 0).forEach((p) => {
        const nm = names[p.n];
        if (!nm || nm === p.n) return;
        let x = p.x, y = p.y, a = 'c';
        if (pt.pl === 'E') { x += off; a = 'l'; } else if (pt.pl === 'W') { x -= off; a = 'r'; } else if (pt.pl === 'N') y -= off; else y += off;
        text(nm, x, y, 1.0, a, 0).forEach((q) => lines.push(q));
      });
    });
    (D.labels || []).forEach((l) => {
      let x = l.x, y = l.y;
      if (l.ref) { const pt = (D.parts || []).find((q) => q.ref === l.ref); if (!pt) return; x = pt.x + (l.dx || 0); y = pt.y + (l.dy || 0); }
      text(l.t, x, y, l.h || R.textH, l.a || 'c', l.r || 0).forEach((q) => lines.push(q));
    });
    // cut where a pad is (the factory would cut it anyway; this way it looks the same as the board will)
    const cuts = M.pads.map((p) => ({ x: p.x, y: p.y, r: (p.s === 'r' ? Math.hypot(p.w / 2, p.h / 2) : Math.max(p.w, p.h) / 2) + 0.15 }));
    holes(D).forEach((h) => cuts.push({ x: h.x, y: h.y, r: h.d / 2 + 0.3 }));
    const segs = [];
    const Bd = D.board;
    for (const pl of lines) for (let k = 2; k < pl.length; k += 2) {
      const q = inRect(pl[k - 2], pl[k - 1], pl[k], pl[k + 1], 0.25, 0.25, Bd.w - 0.25, Bd.h - 0.25);
      if (q) clipSeg(q[0], q[1], q[2], q[3], cuts, segs);
    }
    return { segs, w: R.silkW };
  }
  /** Where a part's name goes: above it, else below, right, left (never on a pad, never off the board); [x, y, rot]. */
  function refAt(D, M, pt, R) {
    if (pt.lx != null) return [pt.x + pt.lx, pt.y + pt.ly, pt.lr || 0];
    const c = courtOf(pt, pt.x, pt.y, pt.rot || 0), h = R.textH, tw = textWidth(pt.ref, h), B = D.board;
    const cands = [[(c[0] + c[2]) / 2, c[1] - h * 0.75, 0], [(c[0] + c[2]) / 2, c[3] + h * 0.75, 0], [c[2] + h * 0.75, (c[1] + c[3]) / 2, 90], [c[0] - h * 0.75, (c[1] + c[3]) / 2, 270],
      [(c[0] + c[2]) / 2, (c[1] + c[3]) / 2, 0]];
    for (const q of cands) {
      const hx = (q[2] ? h : tw) / 2 + 0.1, hy = (q[2] ? tw : h) / 2 + 0.1;
      if (q[0] - hx < 0.3 || q[1] - hy < 0.3 || q[0] + hx > B.w - 0.3 || q[1] + hy > B.h - 0.3) continue;
      let bad = false;
      for (const p of M.pads) {
        const r = (p.s === 'r' ? Math.hypot(p.w / 2, p.h / 2) : Math.max(p.w, p.h) / 2) + 0.2;
        const dx = Math.max(Math.abs(p.x - q[0]) - hx, 0), dy = Math.max(Math.abs(p.y - q[1]) - hy, 0);
        if (dx * dx + dy * dy < r * r) { bad = true; break; }
      }
      if (!bad) return q;
    }
    return cands[0];
  }

  /** Keeps only the part of a segment on the board (silk off the edge would be cut away anyway). */
  function inRect(x1, y1, x2, y2, X0, Y0, X1, Y1) {
    let t0 = 0, t1 = 1;
    const dx = x2 - x1, dy = y2 - y1, p = [-dx, dx, -dy, dy], q = [x1 - X0, X1 - x1, y1 - Y0, Y1 - y1];
    for (let i = 0; i < 4; i++) {
      if (Math.abs(p[i]) < 1e-12) { if (q[i] < 0) return null; continue; }
      const t = q[i] / p[i];
      if (p[i] < 0) { if (t > t1) return null; if (t > t0) t0 = t; } else { if (t < t0) return null; if (t < t1) t1 = t; }
    }
    return [x1 + dx * t0, y1 + dy * t0, x1 + dx * t1, y1 + dy * t1];
  }

  function clipSeg(x1, y1, x2, y2, cuts, out) {
    let parts = [[0, 1]];
    const dx = x2 - x1, dy = y2 - y1, A = dx * dx + dy * dy;
    for (const c of cuts) {
      if (Math.max(x1, x2) < c.x - c.r || Math.min(x1, x2) > c.x + c.r || Math.max(y1, y2) < c.y - c.r || Math.min(y1, y2) > c.y + c.r) continue;
      if (A < 1e-12) { if (Math.hypot(x1 - c.x, y1 - c.y) < c.r) parts = []; continue; }
      const fx = x1 - c.x, fy = y1 - c.y, b = 2 * (fx * dx + fy * dy), cc = fx * fx + fy * fy - c.r * c.r, disc2 = b * b - 4 * A * cc;
      if (disc2 <= 0) continue;
      const sq = Math.sqrt(disc2), t1 = (-b - sq) / (2 * A), t2 = (-b + sq) / (2 * A);
      const next = [];
      for (const p of parts) {
        if (t2 <= p[0] || t1 >= p[1]) { next.push(p); continue; }
        if (t1 > p[0]) next.push([p[0], t1]);
        if (t2 < p[1]) next.push([t2, p[1]]);
      }
      parts = next;
    }
    for (const p of parts) if ((p[1] - p[0]) * Math.sqrt(A) > 0.05 || A < 1e-12) out.push([x1 + dx * p[0], y1 + dy * p[0], x1 + dx * p[1], y1 + dy * p[1]]);
  }

  /** A copy that is safe to keep (only the fields the studio uses, numbers checked). */
  function clean(D) {
    D = D && typeof D === 'object' ? D : {};
    const num = (v, lo, hi, d) => (typeof v === 'number' && isFinite(v) ? Math.max(lo, Math.min(hi, v)) : d);
    const str = (v, n) => String(v == null ? '' : v).replace(/[\u0000-\u001f]/g, ' ').slice(0, n);
    const B = D.board || {};
    const out = {
      v: 1, id: str(D.id, 40).replace(/[^A-Za-z0-9_-]/g, '') || ('p' + Date.now().toString(36)), name: str(D.name || 'నా బోర్డ్', 60),
      title: str(D.title || 'JARVIS PCB', 30).replace(/[^ -~]/g, '').toUpperCase(), proj: str(D.proj, 30), at: num(D.at, 0, 4e12, Date.now()),
      board: { w: num(B.w, 10, 200, 50), h: num(B.h, 10, 200, 35), r: num(B.r, 0, 10, 1), holes: !!B.holes, color: ['green', 'blue', 'black', 'white', 'purple'].indexOf(B.color) >= 0 ? B.color : 'green',
        thick: [0.8, 1.0, 1.2, 1.6, 2.0].indexOf(B.thick) >= 0 ? B.thick : 1.6, cu: B.cu === 2 ? 2 : 1, rows: B.rows === 22.86 ? 22.86 : 25.4 },
      fab: FABS[D.fab] ? D.fab : 'jlc', pour: { B: !(D.pour && D.pour.B === false), F: !!(D.pour && D.pour.F) },
      parts: [], nets: [], tracks: [], vias: [], labels: [], notes: str(D.notes, 2000)
    };
    (Array.isArray(D.parts) ? D.parts : []).slice(0, 120).forEach((pt) => {
      if (!pt || !P.LIB[pt.lib]) return;
      const o = { ref: str(pt.ref, 8).replace(/[^A-Za-z0-9]/g, '').toUpperCase(), lib: pt.lib, val: str(pt.val, 24), x: num(pt.x, -20, 220, 10), y: num(pt.y, -20, 220, 10),
        rot: [0, 90, 180, 270].indexOf(pt.rot) >= 0 ? pt.rot : 0, sx: num(pt.sx, -2000, 4000, 0), sy: num(pt.sy, -2000, 4000, 0), srot: [0, 90, 180, 270].indexOf(pt.srot) >= 0 ? pt.srot : 0 };
      if (pt.fp && P.fp(pt.fp)) o.fp = pt.fp;
      if (pt.pins && typeof pt.pins === 'object') { o.pins = {}; Object.keys(pt.pins).slice(0, 40).forEach((k) => { o.pins[str(k, 4)] = str(pt.pins[k], 8); }); }
      if (pt.note) o.note = str(pt.note, 400);
      if (pt.color) o.color = str(pt.color, 10);
      if (pt.lx != null) { o.lx = num(pt.lx, -60, 60, 0); o.ly = num(pt.ly, -60, 60, 0); o.lr = [0, 90, 180, 270].indexOf(pt.lr) >= 0 ? pt.lr : 0; }
      if (pt.noref) o.noref = true;
      if (['N', 'E', 'S', 'W'].indexOf(pt.pl) >= 0) { o.pl = pt.pl; o.plo = num(pt.plo, 0.5, 12, 1.9); }
      if (o.ref && !out.parts.some((q) => q.ref === o.ref)) out.parts.push(o);
    });
    (Array.isArray(D.nets) ? D.nets : []).slice(0, 200).forEach((n) => {
      if (!n || !Array.isArray(n.p)) return;
      const o = { n: str(n.n, 16).replace(/[^A-Za-z0-9+_.-]/g, '') || 'N' + out.nets.length, p: n.p.slice(0, 80).map((p) => str(p, 16)).filter((p) => /^[A-Z0-9]+\.[A-Za-z0-9]+$/.test(p)) };
      if (n.a) o.a = num(n.a, 0, 20, 0);
      if (o.p.length && !out.nets.some((q) => q.n === o.n)) out.nets.push(o);
    });
    (Array.isArray(D.tracks) ? D.tracks : []).slice(0, 3000).forEach((t) => {
      if (!t || !Array.isArray(t.p) || t.p.length < 2) return;
      out.tracks.push({ n: str(t.n, 16), l: t.l === 'B' ? 'B' : 'F', w: num(t.w, 0.1, 5, 0.4), p: t.p.slice(0, 400).map((q) => [num(q[0], -10, 210, 0), num(q[1], -10, 210, 0)]) });
    });
    (Array.isArray(D.vias) ? D.vias : []).slice(0, 1000).forEach((v) => { if (v) out.vias.push({ n: str(v.n, 16), x: num(v.x, -10, 210, 0), y: num(v.y, -10, 210, 0) }); });
    (Array.isArray(D.labels) ? D.labels : []).slice(0, 60).forEach((l) => {
      if (!l || !l.t) return;
      const o = { t: str(l.t, 24).replace(/[^ -~]/g, ''), x: num(l.x, -10, 210, 0), y: num(l.y, -10, 210, 0), h: num(l.h, 0.8, 5, 1.2), a: l.a === 'l' || l.a === 'r' ? l.a : 'c', r: [0, 90, 180, 270].indexOf(l.r) >= 0 ? l.r : 0 };
      if (l.ref) { o.ref = str(l.ref, 8).replace(/[^A-Za-z0-9]/g, '').toUpperCase(); o.dx = num(l.dx, -60, 60, 0); o.dy = num(l.dy, -60, 60, 0); }
      out.labels.push(o);
    });
    if (Array.isArray(D.sch)) out.sch = D.sch.slice(0, 200).filter((s) => s && typeof s === 'object').map((s) => ({ t: str(s.t, 30), x: num(s.x, -2000, 4000, 0), y: num(s.y, -2000, 4000, 0) }));
    return out;
  }

  const api = { FABS, RULES, rules, widthFor, netWidth, isPower, isGnd, rot, partPads, courtOf, sideAfter, boardSdf, outline, holes, padObj, dist, gap, bbox,
    model, copper, route, pour, rects, connectivity, autoplace, fitBoard, drc, text, textWidth, silk, refAt, clean, GLYPH };
  root.PcbCore = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
