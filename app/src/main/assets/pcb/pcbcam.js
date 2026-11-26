// 🔌 Jarvis PCB studio — 📷 the soldering guide on his REAL board (Build C, 26 Nov: "7 AR soldering on the real board").
// The back camera looks at his bare / half-soldered board; our own code finds the board by its colour (green / blue / black…
// mask), follows its four corners and draws on the live picture where the next part goes (top side) or which joints to solder
// (solder side, mirrored). Everything is our own maths here in the page: ₹0, offline, no AI. The camera picture is never kept,
// saved or sent anywhere (this page has no network), and the camera runs only while this view is open.
'use strict';
(function (root) {
  // ================================================================ the maths (no screen: tested in node too)

  /** Y, Cb, Cr of an RGB colour (0–255). */
  function ycc(r, g, b) { return [0.299 * r + 0.587 * g + 0.114 * b, 128 - 0.168736 * r - 0.331264 * g + 0.5 * b, 128 + 0.5 * r - 0.418688 * g - 0.081312 * b]; }

  /** The board's colour around a point: the average of a small patch. → {y, cb, cr} */
  function sample(px, w, h, x, y, r) {
    r = r || 3;
    let n = 0, Y = 0, B = 0, R = 0;
    for (let j = Math.max(0, Math.round(y) - r); j <= Math.min(h - 1, Math.round(y) + r); j++)
      for (let i = Math.max(0, Math.round(x) - r); i <= Math.min(w - 1, Math.round(x) + r); i++) {
        const k = (j * w + i) * 4, c = ycc(px[k], px[k + 1], px[k + 2]);
        Y += c[0]; B += c[1]; R += c[2]; n++;
      }
    return n ? { y: Y / n, cb: B / n, cr: R / n } : null;
  }

  /** Pixels that look like the board's colour (1) or not (0). A grey / black / white mask leans on brightness, a coloured one on hue. */
  function mask(px, w, h, ref, tol) {
    const m = new Uint8Array(w * h), chroma = Math.hypot(ref.cb - 128, ref.cr - 128);
    const wl = chroma < 14 ? 1.0 : 0.35, t = tol || 30;
    for (let i = 0, k = 0; i < w * h; i++, k += 4) {
      const c = ycc(px[k], px[k + 1], px[k + 2]);
      const d = Math.hypot(c[1] - ref.cb, c[2] - ref.cr) + wl * Math.abs(c[0] - ref.y);
      if (d < t) m[i] = 1;
    }
    return m;
  }

  /** Grow by one pixel then shrink by one (closes thin gaps: white print, tracks, small pads). */
  function close1(m, w, h) {
    const a = new Uint8Array(w * h), b = new Uint8Array(w * h);
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      let on = 0;
      for (let dj = -1; dj <= 1 && !on; dj++) for (let di = -1; di <= 1; di++) {
        const ii = i + di, jj = j + dj;
        if (ii >= 0 && jj >= 0 && ii < w && jj < h && m[jj * w + ii]) { on = 1; break; }
      }
      a[j * w + i] = on;
    }
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      let on = 1;
      for (let dj = -1; dj <= 1 && on; dj++) for (let di = -1; di <= 1; di++) {
        const ii = i + di, jj = j + dj;
        if (ii >= 0 && jj >= 0 && ii < w && jj < h && !a[jj * w + ii]) { on = 0; break; }
      }
      b[j * w + i] = on;
    }
    return b;
  }

  /** The joined region that holds (sx, sy) (or the board-coloured pixel nearest to it, within r). → {n, pts (its edge points), edge (pixels on the frame's border), cx, cy} */
  function region(m, w, h, sx, sy, r) {
    let start = -1;
    const x0 = Math.round(sx), y0 = Math.round(sy);
    for (let d = 0; d <= (r || 12) && start < 0; d++)
      for (let j = y0 - d; j <= y0 + d && start < 0; j++) for (let i = x0 - d; i <= x0 + d; i++) {
        if (i < 0 || j < 0 || i >= w || j >= h) continue;
        if (Math.max(Math.abs(i - x0), Math.abs(j - y0)) !== d) continue;
        if (m[j * w + i]) { start = j * w + i; break; }
      }
    if (start < 0) return null;
    const seen = new Uint8Array(w * h), q = new Int32Array(w * h), minx = new Int32Array(h).fill(w), maxx = new Int32Array(h).fill(-1);
    let qh = 0, qt = 0, n = 0, edge = 0, sxs = 0, sys = 0;
    q[qt++] = start; seen[start] = 1;
    while (qh < qt) {
      const c = q[qh++], i = c % w, j = (c - i) / w;
      n++; sxs += i; sys += j;
      if (i < minx[j]) minx[j] = i;
      if (i > maxx[j]) maxx[j] = i;
      if (i === 0 || j === 0 || i === w - 1 || j === h - 1) edge++;
      for (let dj = -1; dj <= 1; dj++) for (let di = -1; di <= 1; di++) {
        if (!di && !dj) continue;
        const ii = i + di, jj = j + dj;
        if (ii < 0 || jj < 0 || ii >= w || jj >= h) continue;
        const c2 = jj * w + ii;
        if (seen[c2] || !m[c2]) continue;
        seen[c2] = 1; q[qt++] = c2;
      }
    }
    const pts = [];
    for (let j = 0; j < h; j++) if (maxx[j] >= 0) { pts.push([minx[j], j], [maxx[j] + 1, j]); }
    return { n, pts, edge, cx: sxs / n, cy: sys / n };
  }

  /** Convex hull (Andrew's monotone chain), clockwise on screen (y down). */
  function hull(pts) {
    const p = pts.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    if (p.length < 3) return p;
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lo = [], up = [];
    for (const q of p) { while (lo.length >= 2 && cross(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); }
    for (let i = p.length - 1; i >= 0; i--) { const q = p[i]; while (up.length >= 2 && cross(up[up.length - 2], up[up.length - 1], q) <= 0) up.pop(); up.push(q); }
    lo.pop(); up.pop();
    return lo.concat(up); // (counter-clockwise in maths = clockwise on a y-down screen: top-left → top-right → bottom-right…)
  }
  function area(poly) { let a = 0; for (let i = 0; i < poly.length; i++) { const p = poly[i], q = poly[(i + 1) % poly.length]; a += p[0] * q[1] - q[0] * p[1]; } return Math.abs(a) / 2; }

  /** The hull cut down to its 4 most important corners (each time the corner whose loss costs the least area goes). */
  function quadOf(h) {
    if (!h || h.length < 4) return null;
    const p = h.slice();
    const tri = (a, b, c) => Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])) / 2;
    while (p.length > 4) {
      let best = 0, bv = Infinity;
      for (let i = 0; i < p.length; i++) {
        const v = tri(p[(i - 1 + p.length) % p.length], p[i], p[(i + 1) % p.length]);
        if (v < bv) { bv = v; best = i; }
      }
      p.splice(best, 1);
    }
    return p;
  }

  /** The 3×3 homography that takes the four src points to the four dst points (row-major, h33 = 1). */
  function homog(src, dst) {
    const A = [], b = [];
    for (let k = 0; k < 4; k++) {
      const [x, y] = src[k], [u, v] = dst[k];
      A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.push(u);
      A.push([0, 0, 0, x, y, 1, -v * x, -v * y]); b.push(v);
    }
    for (let c = 0; c < 8; c++) { // (Gauss–Jordan with the biggest pivot)
      let piv = c;
      for (let r = c + 1; r < 8; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
      if (Math.abs(A[piv][c]) < 1e-12) return null;
      [A[c], A[piv]] = [A[piv], A[c]]; [b[c], b[piv]] = [b[piv], b[c]];
      for (let r = 0; r < 8; r++) {
        if (r === c) continue;
        const f = A[r][c] / A[c][c];
        if (!f) continue;
        for (let k = c; k < 8; k++) A[r][k] -= f * A[c][k];
        b[r] -= f * b[c];
      }
    }
    const x = b.map((v, i) => v / A[i][i]);
    return [x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], 1];
  }
  function apply(H, x, y) { const w = H[6] * x + H[7] * y + H[8]; return [(H[0] * x + H[1] * y + H[2]) / w, (H[3] * x + H[4] * y + H[5]) / w]; }

  const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  /** The four corners turned so corner 0 is the board's own corner 0: following the last frame, else the long side first and the top-left one. */
  function orient(q, prev, bw, bh) {
    const shift = (k) => [0, 1, 2, 3].map((i) => q[(i + k) % 4]);
    if (prev) {
      let best = 0, bd = Infinity;
      for (let k = 0; k < 4; k++) { const s = shift(k), d = s.reduce((t, p, i) => t + dist(p, prev[i]), 0); if (d < bd) { bd = d; best = k; } }
      return shift(best);
    }
    const square = Math.abs(bw - bh) / Math.max(bw, bh) < 0.15;
    let best = 0, bs = -Infinity;
    for (let k = 0; k < 4; k++) {
      const s = shift(k), e01 = dist(s[0], s[1]) + dist(s[2], s[3]), e12 = dist(s[1], s[2]) + dist(s[3], s[0]);
      const longOk = square || (bw >= bh ? e01 >= e12 : e01 <= e12);
      const score = (longOk ? 1e6 : 0) - (s[0][0] + s[0][1]);
      if (score > bs) { bs = score; best = k; }
    }
    return shift(best);
  }

  /**
   * One look at a frame (RGBA pixels, w × h): → {ok, quad (4 corners in the frame, clockwise, corner 0 = the board's corner 0 when
   * prev is known), why ('none' | 'small' | 'edge' | 'shape'), n, ref (the colour, drifted a little toward what was seen)}.
   * st: {ref, seed [x, y], prev (last quad), bw, bh, tol}
   */
  function look(px, w, h, st) {
    const m = close1(mask(px, w, h, st.ref, st.tol), w, h);
    const rg = region(m, w, h, st.seed[0], st.seed[1], Math.round(Math.min(w, h) * 0.12));
    if (!rg) return { ok: false, why: 'none' };
    if (rg.n < w * h * 0.015) return { ok: false, why: 'small', n: rg.n };
    const hl = hull(rg.pts), q = quadOf(hl);
    if (!q) return { ok: false, why: 'shape' };
    const aq = area(q), ah = area(hl);
    if (aq < ah * 0.82) return { ok: false, why: 'shape' };
    // the four sides: a rectangle seen at a slant (opposite sides alike within ×2.2, the corners not too sharp)
    const s = [0, 1, 2, 3].map((i) => dist(q[i], q[(i + 1) % 4]));
    if (Math.max(s[0], s[2]) / Math.max(1, Math.min(s[0], s[2])) > 2.2 || Math.max(s[1], s[3]) / Math.max(1, Math.min(s[1], s[3])) > 2.2) return { ok: false, why: 'shape' };
    const asp = (s[0] + s[2]) / Math.max(1, s[1] + s[3]), want = st.bw / st.bh;
    const fits = Math.max(asp / want, want / asp) < 2.0 || Math.max(asp * want, 1 / (asp * want)) < 2.0;
    if (!fits) return { ok: false, why: 'shape' };
    const quad = orient(q, st.prev, st.bw, st.bh);
    // the colour drifts toward the board's middle (light changes as he moves)
    const mid = sample(px, w, h, rg.cx, rg.cy, 2);
    const ref = mid && Math.hypot(mid.cb - st.ref.cb, mid.cr - st.ref.cr) + Math.abs(mid.y - st.ref.y) * 0.35 < (st.tol || 30) * 0.8
      ? { y: st.ref.y * 0.9 + mid.y * 0.1, cb: st.ref.cb * 0.9 + mid.cb * 0.1, cr: st.ref.cr * 0.9 + mid.cr * 0.1 } : st.ref;
    return { ok: true, quad, edge: rg.edge > (hl.length ? 6 : 0), n: rg.n, ref, cx: rg.cx, cy: rg.cy };
  }

  // ================================================================ the view (page only)

  const CAM = { on: false, st: null, stream: null, raf: 0, last: 0, tick: 0 };

  function camApi(hooks) {
    const $ = (id) => document.getElementById(id);
    const H = hooks;
    let video = null, small = null, sctx = null, seenAt = 0, info = '';
    const view = () => $('cam'), cv = () => $('camcv');

    function bars() {
      const t = H.clear($('ctop'));
      t.appendChild(H.btn('✕', 'small', close));
      const n = H.el('div', 'rname');
      n.appendChild(H.el('div', null, '📷 బోర్డ్ మీద టంకం గైడ్'));
      n.appendChild(H.el('small', null, 'చిత్రం ఎక్కడా సేవ్ అవ్వదు, ఎవరికీ వెళ్ళదు'));
      t.appendChild(n);
      const b = H.clear($('cbot')), st = H.step();
      if (st) b.appendChild(H.el('div', 'ct', st.n + '/' + st.of + ' · ' + st.title));
      b.appendChild(H.el('div', 'muted cinfo', info));
      const r = H.el('div', 'row');
      r.appendChild(H.btn('◀️', 'small', () => { H.go(-1); bars(); }));
      r.appendChild(H.speak());
      r.appendChild(H.btn('▶️', 'main small', () => { H.go(1); bars(); }));
      r.appendChild(H.btn(CAM.st && CAM.st.bottom ? '⬆️ పై వైపు' : '⬇️ టంకం వైపు', CAM.st && CAM.st.bottom ? 'small on' : 'small', () => {
        if (!CAM.st) return;
        CAM.st.bottom = !CAM.st.bottom; CAM.st.prev = null; CAM.st.turns = 0;
        say(CAM.st.bottom ? '⬇️ బోర్డ్ తిప్పి టంకం వైపు కెమెరాకి చూపించండి. ఎరుపు గుండ్రాలు: ఈ అడుగు టంకాలు.' : '⬆️ భాగాలు పెట్టే వైపు కెమెరాకి చూపించండి.', true);
        bars();
      }));
      r.appendChild(H.btn('🔄', 'small', turn));
      r.appendChild(H.btn('🎯', 'small', () => { if (CAM.st) { CAM.st.ref = null; CAM.st.prev = null; } say(T.tap); }));
      b.appendChild(r);
    }
    const T = {
      start: '📷 కెమెరా మొదలవుతోంది…',
      tap: '👉 బోర్డ్‌ని దాని రంగు కాని బల్ల మీద పెట్టండి. ఫోన్ 20–30 cm పైన (స్టాండ్ / గ్లాస్ మీద పెడితే మంచిది). స్క్రీన్‌లో బోర్డ్ మీద ఒకసారి నొక్కండి.',
      found: '🟢 బోర్డ్ దొరికింది. పసుపు గుర్తులు: ఈ అడుగు భాగాలు (■ = దిశ). తెల్ల చుక్కలు బోర్డ్ ప్యాడ్‌ల మీద పడకపోతే 🔄 నొక్కండి.',
      foundB: '🟢 టంకం వైపు దొరికింది. ఎరుపు గుండ్రాలు: ఈ అడుగు టంకాలు. తెల్ల చుక్కలు ప్యాడ్‌ల మీద పడకపోతే 🔄 నొక్కండి.',
      none: '🔍 బోర్డ్ కనిపించట్లేదు: బోర్డ్ మొత్తం స్క్రీన్‌లో ఉండాలి. రంగు మారితే 🎯 నొక్కి బోర్డ్ మీద మళ్ళీ నొక్కండి.',
      small: '🔍 బోర్డ్ చాలా చిన్నగా ఉంది: ఫోన్ కొంచెం దగ్గరికి తీసుకురండి.',
      edge: '↔️ బోర్డ్ అంచు స్క్రీన్ బయటికి వెళ్తోంది: ఫోన్ కొంచెం పైకి ఎత్తండి.',
      shape: '🔍 బోర్డ్ ఆకారం సరిగ్గా కనిపించట్లేదు: బల్ల రంగు బోర్డ్ రంగుకి దగ్గరగా ఉందేమో. వేరే రంగు బల్ల / కాగితం మీద పెట్టి 🎯.'
    };
    function say(te, keep) { info = te; if (keep && CAM.st) CAM.st.saidAt = performance.now(); const c = document.querySelector('#cbot .cinfo'); if (c) c.textContent = te; }
    function turn() {
      if (!CAM.st) return;
      const sq = Math.abs(CAM.st.bw - CAM.st.bh) / Math.max(CAM.st.bw, CAM.st.bh) < 0.15, k = sq ? 1 : 2;
      if (CAM.st.prev) CAM.st.prev = CAM.st.prev.slice(k).concat(CAM.st.prev.slice(0, k));
      if (CAM.st.quad) CAM.st.quad = CAM.st.quad.slice(k).concat(CAM.st.quad.slice(0, k));
      CAM.st.turns = ((CAM.st.turns || 0) + k) % 4;
      say('🔄 తిప్పాను. తెల్ల చుక్కలు బోర్డ్ ప్యాడ్‌ల మీద పడితే సరి.', true);
    }

    /** Opened by his tap: the view, then Jarvis asks Android for the camera (the page starts it when that is ready). */
    function open() {
      const D = H.design();
      if (!D || CAM.on) return;
      CAM.on = true;
      CAM.st = { ref: null, seed: null, prev: null, quad: null, bw: D.board.w, bh: D.board.h, tol: 30, bottom: false, ready: false };
      view().classList.add('on');
      info = T.start;
      bars();
      H.post({ t: 'cam', on: true });
    }
    /** Jarvis says the camera may start (or why not). */
    function ready(m) {
      if (!CAM.on) return;
      if (!m.ready) { H.toast(String(m.why || '📷 కెమెరా దొరకలేదు'), 5000); close(); return; }
      start();
    }
    async function start() {
      try {
        const s = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } } });
        if (!CAM.on) { s.getTracks().forEach((t) => t.stop()); return; }
        CAM.stream = s;
        video = document.createElement('video');
        video.muted = true; video.playsInline = true; video.setAttribute('playsinline', '');
        video.srcObject = s;
        try { await video.play(); } catch (e) { info = '▶️ కెమెరా చూపించడానికి స్క్రీన్ మీద ఒకసారి నొక్కండి.'; bars(); CAM.needPlay = true; }
        CAM.st.ready = true;
        if (!CAM.needPlay) say(T.tap);
        CAM.raf = requestAnimationFrame(frame);
      } catch (e) {
        H.toast('📷 కెమెరా తెరవలేకపోయాను Sir (' + String((e && e.name) || e).slice(0, 40) + ').', 5000);
        close();
      }
    }
    /** ✕, back, or the studio went behind: the camera stops at once. */
    function close() {
      if (!CAM.on) return;
      CAM.on = false;
      if (CAM.raf) cancelAnimationFrame(CAM.raf);
      CAM.raf = 0;
      if (CAM.stream) CAM.stream.getTracks().forEach((t) => t.stop());
      CAM.stream = null;
      if (video) { video.pause(); video.srcObject = null; }
      video = null; small = null; sctx = null;
      CAM.st = null; CAM.needPlay = false;
      view().classList.remove('on');
      H.post({ t: 'cam', on: false });
    }

    /** His tap on the picture: the board's colour there (and its place) starts the following. */
    function tap(e) {
      if (!CAM.on || !CAM.st) return;
      if (CAM.needPlay && video) { CAM.needPlay = false; video.play().then(() => say(T.tap)).catch(() => {}); return; }
      if (!small || !CAM.st.ready) return;
      const r = cv().getBoundingClientRect(), fx = (e.clientX - r.left) / r.width, fy = (e.clientY - r.top) / r.height;
      const x = fx * small.width, y = fy * small.height;
      const px = sctx.getImageData(0, 0, small.width, small.height).data;
      const c = sample(px, small.width, small.height, x, y, 2);
      if (!c) return;
      CAM.st.ref = { y: c.y, cb: c.cb, cr: c.cr };
      CAM.st.seed = [x, y]; CAM.st.prev = null; CAM.st.quad = null;
      say('🔍 బోర్డ్ వెతుకుతున్నాను…');
    }

    function frame(t) {
      CAM.raf = 0;
      if (!CAM.on || !video) return;
      const c = cv(), dpr = window.devicePixelRatio || 1, W = Math.round(c.clientWidth * dpr), Hh = Math.round(c.clientHeight * dpr);
      if (c.width !== W || c.height !== Hh) { c.width = W; c.height = Hh; }
      const ctx = c.getContext('2d'), vw = video.videoWidth, vh = video.videoHeight;
      ctx.fillStyle = '#05070a'; ctx.fillRect(0, 0, W, Hh);
      if (vw && vh) {
        // the picture fills the screen (cut at the sides); the small copy we look at is the same cut, ~200 px wide
        const k = Math.max(W / vw, Hh / vh), sw = W / k, sh = Hh / k, sx = (vw - sw) / 2, sy = (vh - sh) / 2;
        ctx.drawImage(video, sx, sy, sw, sh, 0, 0, W, Hh);
        if (!small) { small = document.createElement('canvas'); small.width = 200; small.height = Math.max(40, Math.round(200 * Hh / W)); sctx = small.getContext('2d', { willReadFrequently: true }); }
        const st = CAM.st;
        if (t - CAM.last > 70) { // (about 14 looks a second)
          CAM.last = t;
          sctx.drawImage(video, sx, sy, sw, sh, 0, 0, small.width, small.height);
          if (st && st.ref && st.seed) {
            const px = sctx.getImageData(0, 0, small.width, small.height).data;
            const fresh = t - seenAt > 700;
            const r = look(px, small.width, small.height, { ref: st.ref, seed: st.seed, prev: fresh ? null : st.prev, bw: st.bw, bh: st.bh, tol: st.tol });
            if (r.ok) {
              let q = r.quad;
              if (fresh && st.turns) q = q.slice(st.turns).concat(q.slice(0, st.turns)); // (found again: his 🔄 still holds)
              // a little smoothing when it moved only a little (no jitter), a jump when he moved the board
              if (st.prev && !fresh) {
                const diag = Math.hypot(small.width, small.height), mv = q.reduce((s, p, i) => s + dist(p, st.prev[i]), 0) / 4;
                if (mv < diag * 0.05) q = q.map((p, i) => [st.prev[i][0] + (p[0] - st.prev[i][0]) * 0.55, st.prev[i][1] + (p[1] - st.prev[i][1]) * 0.55]);
              }
              st.prev = q; st.quad = q; st.ref = r.ref; st.seed = [r.cx, r.cy];
              const want = r.edge ? T.edge : st.bottom ? T.foundB : T.found;
              if (info !== want && t - (st.saidAt || 0) > 2500) say(want);
              seenAt = t;
            } else if (t - seenAt > 700) {
              st.quad = null;
              say(T[r.why] || T.none);
            }
          }
        }
        if (st && st.quad) draw(ctx, st, W / small.width, Hh / small.height);
      }
      CAM.raf = requestAnimationFrame(frame);
    }

    /** The board's parts on the live picture: the step's parts in gold (their outline, pads, + / pin 1), the rest faint. */
    function draw(ctx, st, kx, ky) {
      const D = H.design(), M = H.model(), step = H.step();
      if (!D || !M) return;
      const B = D.board, bw = B.w, bh = B.h;
      const src = st.bottom ? [[bw, 0], [0, 0], [0, bh], [bw, bh]] : [[0, 0], [bw, 0], [bw, bh], [0, bh]];
      const dst = st.quad.map((p) => [p[0] * kx, p[1] * ky]);
      const Hm = homog(src, dst);
      if (!Hm) return;
      const P = (x, y) => apply(Hm, x, y);
      const sc = (x, y) => dist(P(x, y), P(x + 1, y)); // (screen px per mm here)
      // the outline
      ctx.lineWidth = 2.5; ctx.strokeStyle = 'rgba(116,228,255,.95)';
      ctx.beginPath(); dst.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]))); ctx.closePath(); ctx.stroke();
      const cur = new Set(step && (step.k === 'part' || step.k === 'chips' || step.k === 'smd') ? step.refs : []);
      const done = new Set(H.done());
      // every pad, faint (he sees at once if the drawing sits on the real pads)
      M.pads.forEach((p) => {
        if (cur.has(p.ref)) return;
        if (st.bottom && p.smd) return; // (the solder side has no SMD pads)
        const q = P(p.x, p.y), r = Math.max(1.5, Math.max(p.w, p.h) / 2 * sc(p.x, p.y) * 0.8);
        ctx.beginPath(); ctx.arc(q[0], q[1], r, 0, Math.PI * 2);
        ctx.strokeStyle = done.has(p.ref) ? 'rgba(107,227,164,.8)' : 'rgba(255,255,255,.55)'; ctx.lineWidth = 1.2; ctx.stroke();
      });
      // this step's parts
      D.parts.filter((pt) => cur.has(pt.ref)).forEach((pt) => {
        const c = H.court(pt), poly = [[c[0], c[1]], [c[2], c[1]], [c[2], c[3]], [c[0], c[3]]].map((a) => P(a[0], a[1]));
        if (!st.bottom) {
          ctx.fillStyle = 'rgba(242,178,76,.22)'; ctx.strokeStyle = 'rgba(242,178,76,.95)'; ctx.lineWidth = 2;
          ctx.beginPath(); poly.forEach((a, i) => (i ? ctx.lineTo(a[0], a[1]) : ctx.moveTo(a[0], a[1]))); ctx.closePath(); ctx.fill(); ctx.stroke();
        }
        const pads = M.pads.filter((p) => p.ref === pt.ref && !(st.bottom && p.smd));
        pads.forEach((p) => {
          const q = P(p.x, p.y), r = Math.max(3, Math.max(p.w, p.h) / 2 * sc(p.x, p.y));
          ctx.beginPath(); ctx.arc(q[0], q[1], r * 1.25, 0, Math.PI * 2);
          ctx.strokeStyle = st.bottom ? 'rgba(255,107,107,.95)' : 'rgba(255,214,102,.95)'; ctx.lineWidth = 3; ctx.stroke();
          if (p.n === '1' && H.polar(pt)) { ctx.fillStyle = 'rgba(255,214,102,.95)'; ctx.fillRect(q[0] - r * 0.5, q[1] - r * 0.5, r, r); }
        });
        // the name (and which leg is + / 1) beside it
        const top = poly.reduce((a, b) => (b[1] < a[1] ? b : a));
        const txt = pt.ref + (st.bottom ? ' · 🔥 ' + pads.filter((p) => p.d).length : H.mark(pt) ? ' · ' + H.mark(pt) : '');
        ctx.font = '600 ' + Math.round(14 * (window.devicePixelRatio || 1)) + 'px "Noto Sans Telugu", sans-serif';
        const tw = ctx.measureText(txt).width, pad = 4 * (window.devicePixelRatio || 1);
        ctx.fillStyle = 'rgba(7,8,11,.82)'; ctx.fillRect(top[0] - tw / 2 - pad, top[1] - 26 * (window.devicePixelRatio || 1), tw + pad * 2, 20 * (window.devicePixelRatio || 1));
        ctx.fillStyle = st.bottom ? '#ff9b9b' : '#ffd666'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(txt, top[0], top[1] - 16 * (window.devicePixelRatio || 1));
      });
    }

    return { open, close, ready, tap, bars, on: () => CAM.on };
  }

  const api = { ycc, sample, mask, close1, region, hull, area, quadOf, homog, apply, orient, look, camApi, CAM };
  root.PcbCam = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
