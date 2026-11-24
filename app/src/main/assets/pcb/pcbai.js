// 🔌 PCB studio build 2 (Anil, 24 Nov: "Build 2 చేయి"): the studio AI's answer (parts + how their pins join, data only) → a board
// our own code places, routes and checks; an AI change merged into his board (what stayed keeps its place); the board in words
// for the AI; his words → a ₹0 change done on the phone when it is a small one. Pure functions: no network, no keys, no AI here.
// Low-voltage DC only: an answer that shows mains / high voltage is never made into a board.
'use strict';
(function (root) {
  const P = root.PcbParts || (typeof require !== 'undefined' ? require('./pcbparts.js') : null);
  const C = root.PcbCore || (typeof require !== 'undefined' ? require('./pcbcore.js') : null);

  const LED_COLORS = { red: 'RED', green: 'GREEN', blue: 'BLUE', yellow: 'YELLOW', white: 'WHITE', orange: 'ORANGE' };
  const MAX_PARTS = 40;

  // ================================================================ safety (his words before any AI; the AI's answer before any board)

  const MAINS = [/\b(230|220|240|110|120)\s*v(ac)?\b/i, /\bv\s*ac\b/i, /\bmains\b/i, /మెయిన్స్/, /ఇంటి\s*(కరెంట్|వైరింగ్|వైర్లు)/, /house\s*wiring/i,
    /\bac\s*(mains|supply|power|current|in|input|live|line)\b/i, /ఏసీ\s*కరెంట్/, /\b(live|neutral|phase)\b/i, /ట్రాన్స్‌?ఫార్మర్/, /\btransformer\b/i,
    /capacitive\s*dropper/i, /\btriac\b/i, /\bssr\b/i];
  const HARM = [/\btaser\b/i, /టేజర్/, /stun\s*gun/i, /shock(er|\s*device|\s*machine)/i, /షాక్\s*(ఇచ్చే|కొట్టే|మెషిన్)/, /electric\s*fence/i, /కరెంట్\s*(కంచె|ఫెన్స్)/, /fence\s*energi[sz]er/i,
    /\bjammer\b/i, /జామర్/, /meter\s*(bypass|tamper|slow|stop|magnet)/i, /మీటర్\s*(బైపాస్|తిప్పు|ఆపు|నెమ్మది|స్లో)/, /\bweapon\b/i, /ఆయుధం/, /\b(gun|bomb|detonat\w*|igniter)\b/i, /బాంబు/,
    /(spy|hidden|secret)\s*(camera|mic|tracker|recorder)/i, /రహస్యంగా\s*(రికార్డ్|వినే|ట్రాక్)/];

  const REFUSE_MAINS = '🚫 ఇది 230V ఇంటి కరెంట్ పని Sir. ఈ స్టూడియోలో బ్యాటరీ / USB / అడాప్టర్ / బైక్ 12V (24V వరకు) బోర్డ్‌లు మాత్రమే చేస్తాను; ఇంటి వైరింగ్ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ పని. (ఖర్చు ₹0, AI కి పంపలేదు)';
  const REFUSE_HARM = '🚫 ఇలాంటిది చేయను Sir: మనుషులకి / జంతువులకి హాని, రహస్యంగా వినడం / చూడడం, మీటర్ మార్చడం, జామర్లు ఇక్కడ చేయను. (ఖర్చు ₹0, AI కి పంపలేదు)';

  /** His words: a refusal said on the phone (₹0, nothing sent) or null. */
  function refuseWords(words) {
    const s = String(words || '');
    if (HARM.some((r) => r.test(s))) return REFUSE_HARM;
    if (MAINS.some((r) => r.test(s))) return REFUSE_MAINS;
    return null;
  }
  function volts(s) {
    let mx = 0;
    // (a part number like LD1117V33 is not 1117 volts: the number must stand on its own)
    String(s || '').replace(/(?<![A-Za-z0-9.])(\d+(?:\.\d+)?)\s*V(?![A-Za-z0-9])/gi, (_, n) => { mx = Math.max(mx, parseFloat(n)); return _; });
    String(s || '').replace(/(?<![A-Za-z0-9.])(\d+)V(\d+)(?![A-Za-z0-9])/gi, (_, a) => { mx = Math.max(mx, parseFloat(a)); return _; });
    return mx;
  }
  /** The AI's answer: why it must not become a board (mains, more than 24 V on a rail, a part rated for mains) or null. */
  function unsafe(ans) {
    const parts = Array.isArray(ans.parts) ? ans.parts : [], nets = Array.isArray(ans.nets) ? ans.nets : [];
    const names = nets.map((n) => String(n && n.n || ''));
    parts.forEach((q) => { if (q && q.pins && typeof q.pins === 'object') Object.keys(q.pins).forEach((k) => names.push(String(q.pins[k]))); });
    const vals = parts.map((q) => String(q && q.val || ''));
    const words = [String(ans.title || ''), String(ans.silk || '')].concat(names, vals).join(' \n ').replace(/[_.\-/]+/g, ' ');
    if (HARM.some((r) => r.test(words))) return REFUSE_HARM.replace(' (ఖర్చు ₹0, AI కి పంపలేదు)', '');
    if (MAINS.some((r) => r.test(words))) return REFUSE_MAINS.replace(' (ఖర్చు ₹0, AI కి పంపలేదు)', '');
    if (names.some((n) => volts(n) > 24)) return '🚫 AI జవాబులో 24V కంటే ఎక్కువ వోల్టేజ్ ఉంది Sir; ఇక్కడ 24V వరకు DC బోర్డ్‌లు మాత్రమే. అందుకే బోర్డ్ చేయలేదు.';
    if (vals.some((v) => volts(v) >= 160)) return REFUSE_MAINS.replace(' (ఖర్చు ₹0, AI కి పంపలేదు)', '');
    return null;
  }

  // ================================================================ the AI's answer → parts and nets the studio knows

  function elecFp(val) {
    const m = /(\d+(?:\.\d+)?)\s*(u|µ|μ)f/i.exec(String(val || '')), uf = m ? parseFloat(m[1]) : 10;
    return uf <= 47 ? 'CP_D5_P2' : uf <= 220 ? 'CP_D6_P2.5' : uf <= 470 ? 'CP_D8_P3.5' : 'CP_D10_P5';
  }
  function netName(n, i) {
    let s = String(n == null ? '' : n).toUpperCase().replace(/\s+/g, '_').replace(/[^A-Z0-9+_.-]/g, '').slice(0, 16);
    if (/^(GND|GROUND|0V|VSS|-|GNDA|GNDD|COM)$/.test(s)) s = 'GND';
    if (/^\+?3\.?3V?$/.test(s)) s = '3V3';
    return s || 'N' + i;
  }
  function pinName(v) { return String(v == null ? '' : v).toUpperCase().replace(/[^ -~]/g, '').replace(/\s+/g, '').slice(0, 8); }
  function nextRef(pre, used) { let i = 1; while (used.has(pre + i)) i++; return pre + i; }

  /** Pad of a part by its number or its pin name ("U1.VCC" → 8; a name two pins share, like an ESP32's GND: the first one not joined yet); null when none. */
  function padOf(pt, s, taken) {
    const f = P.fpOf(pt);
    if (!f) return null;
    const k = String(s).trim();
    if (f.pads.some((p) => p.n === k)) return k;
    if (f.pads.some((p) => p.n.toUpperCase() === k.toUpperCase())) return f.pads.find((p) => p.n.toUpperCase() === k.toUpperCase()).n;
    const names = P.pinsOf(pt), want = k.toUpperCase().replace(/^\+/, '');
    const hit = Object.keys(names).filter((n) => String(names[n]).toUpperCase().replace(/^\+/, '') === want && f.pads.some((p) => p.n === n));
    if (!hit.length) return null;
    return hit.find((n) => !(taken && taken.has(pt.ref + '.' + n))) || hit[0];
  }

  /**
   * {parts, nets, title, silk, say, dropped: [why], fixes: [what we put right]} or {err, refuse}. Parts are checked against the studio's
   * library (unknown ones are left out and said), pins against each footprint, every pin in one net, nets of one pin left out.
   */
  function parse(ans) {
    if (!ans || typeof ans !== 'object') return { err: 'AI జవాబులో బోర్డ్ లేదు.' };
    const bad = unsafe(ans);
    if (bad) return { err: bad, refuse: true };
    const dropped = [], fixes = [], parts = [], byRef = new Map(), used = new Set();
    const rawParts = Array.isArray(ans.parts) ? ans.parts : [];
    if (rawParts.length > MAX_PARTS) dropped.push((rawParts.length - MAX_PARTS) + ' భాగాలు (' + MAX_PARTS + ' కంటే ఎక్కువ)');
    // headers: as many pins as the joins use (the AI may name a 1x4 and join pin 5)
    const hdrMax = new Map();
    (Array.isArray(ans.nets) ? ans.nets : []).forEach((n) => (n && Array.isArray(n.p) ? n.p : []).forEach((s) => {
      const m = /^\s*([A-Za-z]+\d+)\s*[.:]\s*(\d+)\s*$/.exec(String(s));
      if (m) hdrMax.set(m[1].toUpperCase(), Math.max(hdrMax.get(m[1].toUpperCase()) || 0, parseInt(m[2], 10)));
    }));
    rawParts.slice(0, MAX_PARTS).forEach((q) => {
      if (!q || typeof q !== 'object') return;
      const lib = String(q.lib || '').toLowerCase().trim();
      const L = Object.prototype.hasOwnProperty.call(P.LIB, lib) ? P.LIB[lib] : null;
      if (!L) { dropped.push(String(q.ref || '?').slice(0, 8) + ' (' + String(q.lib || '?').slice(0, 20) + ': స్టూడియోలో లేని భాగం)'); return; }
      let ref = String(q.ref || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
      if (!/^[A-Z]+\d+$/.test(ref)) ref = '';
      if (ref && used.has(ref)) { dropped.push(ref + ' (రెండోసారి వచ్చింది)'); return; }
      if (!ref) ref = nextRef(L.pre, used);
      used.add(ref);
      const pt = { ref, lib, val: String(q.val == null ? '' : q.val).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 24) };
      if (lib === 'hdr' || lib === 'fhdr') {
        const named = q.pins && typeof q.pins === 'object' ? Object.keys(q.pins).map((k) => parseInt(k, 10)).filter((k) => k > 0) : [];
        let n = parseInt((/^F?HDR1x(\d+)$/i.exec(String(q.fp || '')) || [])[1], 10) || 0;
        n = Math.max(n, named.length ? Math.max.apply(null, named) : 0, hdrMax.get(ref) || 0) || 4;
        if (n > 8) { fixes.push(ref + ': ' + n + ' పిన్నులు → 8 వరకే'); n = 8; }
        pt.fp = (lib === 'fhdr' ? 'FHDR1x' : 'HDR1x') + Math.max(2, n);
      } else if (lib === 'elec') {
        const want = String(q.fp || '');
        pt.fp = /^CP_D(5_P2|6_P2\.5|8_P3\.5|10_P5)$/.test(want) ? want : elecFp(pt.val);
      }
      if ((lib === 'term2' || lib === 'hdr' || lib === 'fhdr') && q.pins && typeof q.pins === 'object') {
        const f = P.fpOf(pt), o = {};
        Object.keys(q.pins).forEach((k) => { const nm = pinName(q.pins[k]); if (nm && f.pads.some((p) => p.n === String(k))) o[String(k)] = nm; });
        if (Object.keys(o).length) { f.pads.forEach((p) => { if (!o[p.n]) o[p.n] = p.n; }); pt.pins = o; }
      }
      if (lib === 'led5' || lib === 'led3') {
        const c = String(q.color || q.val || '').toLowerCase().match(/red|green|blue|yellow|white|orange/);
        pt.color = c ? c[0] : 'red';
        if (!pt.val || /^led$/i.test(pt.val) || LED_COLORS[pt.val.toLowerCase()]) pt.val = LED_COLORS[pt.color];
      }
      if (q.note) pt.note = String(q.note).replace(/[\u0000-\u001f]/g, ' ').slice(0, 300);
      parts.push(pt);
      byRef.set(ref, pt);
    });
    if (!parts.length) return { err: 'AI జవాబులో స్టూడియో భాగాలు ఏవీ లేవు' + (dropped.length ? ' (' + dropped.slice(0, 4).join(', ') + ')' : '') + '.' };
    const pinNet = new Map(), nets = [];
    (Array.isArray(ans.nets) ? ans.nets : []).slice(0, 150).forEach((n, i) => {
      if (!n || !Array.isArray(n.p)) return;
      const name = netName(n.n, i + 1);
      let net = nets.find((q) => q.n === name);
      const fresh = !net;
      if (!net) net = { n: name, p: [] };
      n.p.slice(0, 80).forEach((s) => {
        const m = /^\s*([A-Za-z]+\d+)\s*[.:\-]\s*([A-Za-z0-9+\-_]+)\s*$/.exec(String(s));
        if (!m) { fixes.push(String(s).slice(0, 12) + ': అర్థం కాలేదు, వదిలేశాను'); return; }
        const pt = byRef.get(m[1].toUpperCase());
        if (!pt) { fixes.push(m[1].toUpperCase() + '.' + m[2] + ': ఆ భాగం లేదు'); return; }
        const pad = padOf(pt, m[2], pinNet);
        if (!pad) { fixes.push(pt.ref + '.' + m[2] + ': ఆ పిన్ లేదు'); return; }
        const key = pt.ref + '.' + pad;
        if (pinNet.has(key)) { if (pinNet.get(key) !== name) fixes.push(key + ': ' + pinNet.get(key) + ', ' + name + ' రెండిట్లో ఉంది; మొదటిదే ఉంచాను'); return; }
        pinNet.set(key, name);
        net.p.push(key);
      });
      const a = parseFloat(n.a);
      if (a > 0) net.a = Math.min(10, Math.max(net.a || 0, a));
      if (fresh) nets.push(net);
    });
    const kept = nets.filter((n) => {
      if (n.p.length >= 2) return true;
      if (n.p.length === 1) fixes.push(n.n + ': ఒకే పిన్ (' + n.p[0] + '), వదిలేశాను');
      return false;
    });
    if (!kept.length) return { err: 'AI జవాబులో భాగాల మధ్య కలుపులు (కనెక్షన్లు) లేవు. ఇంకోసారి కొంచెం వివరంగా చెప్పండి.' };
    // the shield of a USB socket joins its ground
    parts.filter((pt) => pt.lib === 'usba').forEach((pt) => {
      const g = kept.find((n) => n.n === 'GND');
      if (g) ['S1', 'S2'].forEach((s) => { const k = pt.ref + '.' + s; if (!pinNet.has(k)) { g.p.push(k); pinNet.set(k, 'GND'); } });
    });
    const silk = String(ans.silk || '').toUpperCase().replace(/[^A-Z0-9 +\-./]/g, '').replace(/\s+/g, ' ').trim().slice(0, 18);
    return { parts, nets: kept, title: String(ans.title || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 50), silk, say: String(ans.say || '').trim().slice(0, 600), dropped, fixes };
  }

  // ================================================================ laying it out: board size, places, names, routes (our own code)

  /** Pin names on the board beside a connector's pads (where autoplace left them room; else away from the edge). */
  function pinLabels(D) {
    D.parts.forEach((pt) => {
      if (pt.pl) return;
      const lb = C.labelBox(pt, pt.x, pt.y, pt.rot || 0, D.board);
      if (lb) { pt.pl = lb.side; pt.plo = lb.off; }
    });
  }

  /** A board just big enough to start from (the parts' areas, room for the biggest one). */
  function startSize(D, k) {
    let a = 0, big = [0, 0];
    D.parts.forEach((pt) => {
      const c = C.courtOf(pt, 0, 0, 0), w = c[2] - c[0] + 1.6, h = c[3] - c[1] + 1.6;
      a += w * h;
      if (Math.max(w, h) > Math.max(big[0], big[1])) big = [Math.max(w, h), Math.min(w, h)];
    });
    const A = a * k;
    let w = Math.sqrt(A * 1.35), h = A / w;
    w = Math.max(w, big[0] + 4, 20); h = Math.max(h, big[1] + 4, 16);
    return [Math.min(160, Math.ceil(w)), Math.min(120, Math.ceil(h))];
  }

  function addTitle(D, silk) {
    const t = String(silk || '').trim();
    D.labels = (D.labels || []).filter((l) => l.ref);
    if (!t) return;
    let h = 1.3;
    while (h > 0.9 && C.textWidth(t, h) > D.board.w - 3) h -= 0.1;
    if (C.textWidth(t, h) > D.board.w - 3) return;
    D.board.h = Math.round((D.board.h + h + 2.2) * 100) / 100;
    D.labels.push({ t, x: Math.round(D.board.w / 2 * 100) / 100, y: Math.round((D.board.h - 1.2 - h / 2) * 100) / 100, h: Math.round(h * 10) / 10 });
  }

  function missing(r) { return r.failed.reduce((s, f) => s + f.pins.length, 0); }

  /**
   * Places and routes a new board: a few tries with more room between parts; the first one where every pin is joined wins, else
   * the one with the fewest left (said to him). Stops trying after `ms` (the phone stays quick).
   */
  function layout(D, silk, ms) {
    const t0 = Date.now(), limit = ms || 7000;
    let best = null;
    const tries = [[0.8, 2.4], [1.4, 3.0], [2.2, 3.8], [3.0, 4.8]];
    for (const tr of tries) {
      if (best && Date.now() - t0 > limit) break;
      const d = JSON.parse(JSON.stringify(D));
      const sz = startSize(d, tr[1]);
      d.board.w = sz[0]; d.board.h = sz[1];
      let ok = false;
      for (let g = 0; g < 4 && !ok; g++) {
        ok = !!C.autoplace(d, { gap: tr[0], labels: true });
        if (!ok) { d.board.w = Math.ceil(d.board.w * 1.18); d.board.h = Math.ceil(d.board.h * 1.18); }
      }
      if (!ok) continue;
      C.fitBoard(d, 1.6 + tr[0] / 2);
      addTitle(d, silk);
      pinLabels(d);
      const r = C.route(d), miss = missing(r);
      if (!best || miss < best.miss || (miss === best.miss && d.board.w * d.board.h < best.d.board.w * best.d.board.h)) best = { d, miss };
      if (!miss) break;
    }
    return best;
  }

  /** A NEW board from the AI's answer: {D, miss, info} or {err, refuse}. */
  function build(ans, opt) {
    opt = opt || {};
    const r = parse(ans);
    if (r.err) return r;
    const D = C.clean({ id: 'ai' + Date.now().toString(36), name: r.title || String(opt.words || 'AI బోర్డ్').slice(0, 40), title: r.silk || 'JARVIS PCB',
      board: { w: 60, h: 40, r: 1, holes: !!opt.holes }, parts: r.parts, nets: r.nets, labels: [],
      notes: ('🤖 ' + (opt.model || 'AI') + (opt.cost ? ' · ' + opt.cost : '') + (r.say ? ': ' + r.say : '')).slice(0, 2000) });
    // (clean() kept the names, pins, notes and colours; places come next)
    const best = layout(D, r.silk, opt.ms);
    if (!best) return { err: 'భాగాలు బోర్డ్ మీద అమర్చలేకపోయాను (చాలా పెద్దవి / ఎక్కువ). మాటల్లో కొన్ని భాగాలు తగ్గించి మళ్ళీ అడగండి.' };
    return { D: best.d, miss: best.miss, info: r };
  }

  /**
   * His board changed by the AI's whole answer: parts that stay (same ref, kind and footprint) keep their place; new ones are placed
   * around them (the board grows a little if they don't fit); the routes are made again. {D, miss, added, removed, changed, info} or {err}.
   */
  function merge(base, ans, opt) {
    opt = opt || {};
    const r = parse(ans);
    if (r.err) return r;
    const D = JSON.parse(JSON.stringify(base));
    const old = new Map(base.parts.map((p) => [p.ref, p]));
    const fixed = new Set(), changed = [];
    D.parts = r.parts.map((q) => {
      const o = old.get(q.ref);
      if (!o || o.lib !== q.lib || String(P.fpOf(o) && P.fpOf(o).id) !== String(P.fpOf(q) && P.fpOf(q).id)) return q;
      fixed.add(q.ref);
      if (String(o.val) !== String(q.val) || String(o.color || '') !== String(q.color || '')) changed.push(q.ref + ': ' + (o.val || '?') + ' → ' + (q.val || '?'));
      const keep = { x: o.x, y: o.y, rot: o.rot || 0, sx: o.sx, sy: o.sy, srot: o.srot };
      ['lx', 'ly', 'lr', 'noref', 'pl', 'plo'].forEach((k) => { if (o[k] != null) keep[k] = o[k]; });
      return Object.assign({}, q, keep, { note: q.note || o.note });
    });
    const added = D.parts.filter((p) => !fixed.has(p.ref)).map((p) => p.ref);
    const removed = base.parts.filter((p) => !D.parts.some((q) => q.ref === p.ref)).map((p) => p.ref);
    D.nets = r.nets;
    D.labels = (D.labels || []).filter((l) => !l.ref || D.parts.some((p) => p.ref === l.ref));
    D.tracks = []; D.vias = [];
    if (added.length) {
      let ok = false;
      for (let g = 0; g < 5 && !ok; g++) {
        ok = !!C.autoplace(D, { fixed, gap: 1.0, labels: true });
        if (!ok) { D.board.w = Math.min(200, Math.ceil(D.board.w * 1.15 + 3)); D.board.h = Math.min(200, Math.ceil(D.board.h * 1.12 + 2)); }
      }
      if (!ok) return { err: 'కొత్త భాగాలకి బోర్డ్ మీద చోటు దొరకలేదు. 📏 బోర్డ్ పెంచి మళ్ళీ అడగండి.' };
      pinLabels(D);
    }
    const out = C.clean(D);
    const rt = C.route(out);
    return { D: out, miss: missing(rt), added, removed, changed, info: r };
  }

  // ================================================================ the board in words for the AI

  /** For a change: the parts and joins (no places; the studio keeps those). */
  function forEdit(D) {
    return JSON.stringify({
      board: { w: D.board.w, h: D.board.h },
      parts: D.parts.map((p) => {
        const o = { ref: p.ref, lib: p.lib, val: p.val };
        if (p.color) o.color = p.color;
        if (p.lib === 'hdr' || p.lib === 'fhdr' || p.lib === 'elec') o.fp = (P.fpOf(p) || {}).id;
        if (p.pins) o.pins = p.pins;
        return o;
      }),
      nets: D.nets.map((n) => (n.a ? { n: n.n, p: n.p, a: n.a } : { n: n.n, p: n.p }))
    });
  }
  /** For the checks' help: each part's place and size, and the problems (Telugu, with where). */
  function forHelp(D, check) {
    const r = (v) => Math.round(v * 10) / 10;
    return JSON.stringify({
      board: { w: D.board.w, h: D.board.h, holes: !!D.board.holes },
      parts: D.parts.map((p) => { const c = C.courtOf(p, p.x, p.y, p.rot || 0); return { ref: p.ref, lib: p.lib, val: p.val, x: r(p.x), y: r(p.y), rot: p.rot || 0, box: [r(c[0]), r(c[1]), r(c[2]), r(c[3])] }; }),
      problems: (check ? check.list : []).filter((f) => f.sev !== 'info').slice(0, 30).map((f) => ({ sev: f.sev, code: f.code, x: r(f.x), y: r(f.y), te: f.te }))
    });
  }
  /** The help's moves, checked: parts that exist, at most 15 mm, turns of 90°, kept on the board. */
  function moves(D, list) {
    const out = [];
    (Array.isArray(list) ? list : []).slice(0, 20).forEach((m) => {
      if (!m) return;
      const pt = D.parts.find((p) => p.ref === String(m.ref || '').toUpperCase());
      if (!pt) return;
      const dx = Math.max(-15, Math.min(15, parseFloat(m.dx) || 0)), dy = Math.max(-15, Math.min(15, parseFloat(m.dy) || 0));
      const rot = [0, 90, 180, 270].indexOf(parseInt(m.rot, 10)) >= 0 ? parseInt(m.rot, 10) : null;
      if (!dx && !dy && (rot == null || rot === (pt.rot || 0))) return;
      out.push({ ref: pt.ref, dx: Math.round(dx * 100) / 100, dy: Math.round(dy * 100) / 100, rot });
    });
    return out;
  }
  function applyMoves(D, list) {
    list.forEach((m) => {
      const pt = D.parts.find((p) => p.ref === m.ref);
      if (!pt) return;
      if (m.rot != null) pt.rot = m.rot;
      pt.x = Math.round((pt.x + m.dx) * 100) / 100; pt.y = Math.round((pt.y + m.dy) * 100) / 100;
      const c = C.courtOf(pt, pt.x, pt.y, pt.rot || 0), f = P.fpOf(pt), oh = f && f.edge ? (f.overhang || 0) + 0.4 : 0;
      if (c[0] < 0.4 - oh) pt.x += 0.4 - oh - c[0]; if (c[2] > D.board.w - 0.4 + oh) pt.x -= c[2] - (D.board.w - 0.4 + oh);
      if (c[1] < 0.4 - oh) pt.y += 0.4 - oh - c[1]; if (c[3] > D.board.h - 0.4 + oh) pt.y -= c[3] - (D.board.h - 0.4 + oh);
      pt.x = Math.round(pt.x * 100) / 100; pt.y = Math.round(pt.y * 100) / 100;
    });
  }

  // ================================================================ 🗣️ his words → a small change on the phone (₹0), or null

  function norm(words) {
    let s = ' ' + String(words || '').toLowerCase().replace(/[“”"'`]/g, ' ') + ' ';
    s = s.replace(/బి\s*జెడ్\s*(\d+)/g, ' bz$1 ').replace(/ఆర్\s*(\d+)/g, ' r$1 ').replace(/సి\s*(\d+)/g, ' c$1 ').replace(/డి\s*(\d+)/g, ' d$1 ')
      .replace(/యు\s*(\d+)/g, ' u$1 ').replace(/జె\s*(\d+)/g, ' j$1 ').replace(/క్యూ\s*(\d+)/g, ' q$1 ').replace(/ఎఫ్\s*(\d+)/g, ' f$1 ')
      .replace(/\b(bz|sw|mk|[rcdqujf])\s+(\d{1,2})\b/g, ' $1$2 ');
    s = s.replace(/(\d)\s*(కె|కే|కిలో)/g, '$1k').replace(/(\d)\s*(ఎం\b|మెగా)/g, '$1m').replace(/(\d)\s*(మైక్రో\s*ఫ(ా|ార)డ్|మైక్రో)/g, '$1uf')
      .replace(/(\d)\s*(నానో\s*ఫ(ా|ార)డ్|నానో)/g, '$1nf').replace(/(\d)\s*(వోల్ట్(స్|లు)?)/g, '$1v').replace(/(\d)\s*(ఎంఎం|మిల్లీమీటర్(లు)?|మి\.మీ)/g, '$1mm')
      .replace(/(\d)\s*(డిగ్రీ(లు|స్)?)/g, '$1deg').replace(/µf|μf/g, 'uf');
    return s;
  }
  const VERB_SHOW = /(చూపించు|చూపు|తెరువు|తెరిచి|వెళ్ళు|వెళ్లు|show|open|go to|చూడాలి)/;
  const COLOR_WORDS = [['blue', /(నీలం|నీలి|బ్లూ|blue)/], ['black', /(నలుపు|నల్ల|బ్లాక్|black)/], ['white', /(తెలుపు|తెల్ల|వైట్|white)/], ['purple', /(ఊదా|పర్పుల్|purple)/],
    ['green', /(ఆకుపచ్చ|పచ్చ|గ్రీన్|green)/]];
  const LED_WORDS = [['red', /(ఎరుపు|ఎర్ర|రెడ్|red)/], ['green', /(ఆకుపచ్చ|పచ్చ|గ్రీన్|green)/], ['blue', /(నీలం|నీలి|బ్లూ|blue)/], ['yellow', /(పసుపు|ఎల్లో|yellow)/],
    ['white', /(తెలుపు|తెల్ల|వైట్|white)/], ['orange', /(నారింజ|ఆరెంజ్|orange)/]];
  const OFF = /(తీసేయి|తీసెయ్|తీసేయ్|వద్దు|ఆపు|ఆఫ్|\boff\b|remove|no\b|లేకుండా)/;
  const BOARD = /(బోర్డ్|బోర్డు|board|pcb|పీసీబీ|సైజు|size)/;

  /** A small change he said / typed that the phone does itself: {k, ...} or null (then the AI, with the cost first). */
  function local(words, D) {
    const s = norm(words);
    if (/^\s*(undo|వెనక్కి|ముందులా|ముందున్నట్టు|ముందు\s*ఉన్నట్టు)( చేయి| చెయ్)?\s*$/.test(s)) return { k: 'undo' };
    const tabs = [['sch', /(స్కీమాటిక్|schematic|సర్క్యూట్\s*బొమ్మ)/], ['d3', /(3d|త్రీడీ|త్రీ\s*డీ)/], ['drc', /(చెక్|\bdrc\b|తప్పులు)/], ['fab', /(ఫ్యాక్టరీ|gerber|జెర్బర్|factory|జిప్)/],
      ['calc', /(లెక్కలు|calculator|కాలిక్యులేటర్)/], ['pcb', /(\bpcb\b|పీసీబీ)/], ['home', /(రెడీ\s*బోర్డ్|బోర్డ్‌లు|బోర్డ్ లిస్ట్|my boards|నా బోర్డ్)/]];
    // Build 3B: 🎓 the class, 🔧 the soldering guide, 📦 the 3D-print box, 📷 AR / 📏 real size (₹0)
    if (/(క్లాస్|\bclass\b|పాఠం|పాఠాలు|lesson|నేర్చుకో)/.test(s)) return { k: 'tab', tab: 'learn' };
    if (/(టంకం|సోల్డర్|సోల్డరింగ్|solder)/.test(s)) return { k: 'make', what: 'guide' };
    if (/(డబ్బా|బాక్స్|enclosure|ఎన్‌క్లోజర్|ఎన్క్లోజర్|\bstl\b|3d\s*ప్రింట్|త్రీడీ\s*ప్రింట్|3d\s*print)/.test(s)) return { k: 'make', what: 'box' };
    if (/(\bar\b|ఏఆర్|టేబుల్\s*మీద)/.test(s)) return { k: 'make', what: 'ar' };
    if (/(అసలు\s*సైజు|నిజమైన\s*సైజు|real\s*size|ఒరిజినల్\s*సైజు|\b1\s*:\s*1\b)/.test(s)) return { k: 'make', what: 'real' };
    if (VERB_SHOW.test(s)) for (const t of tabs) if (t[1].test(s)) return { k: 'tab', tab: t[0] };
    // Build 3A: ⚡ the board running on the phone, 🕑 the kept versions (₹0)
    if (/(సిమ్యులేషన్|సిములేషన్|simulation|simulate|నడిపించు|నడిపించి\s*చూ|నడిపి\s*చూ)/.test(s)) return { k: 'tab', tab: 'sim' };
    if (/(వెర్షన్|version)/.test(s)) return { k: 'versions' };
    if (!D) return null;
    const refM = /\b(bz\d{1,2}|sw\d{1,2}|mk\d{1,2}|[rcdqujf]\d{1,2})\b/.exec(s);
    const pt = refM ? D.parts.find((p) => p.ref === refM[1].toUpperCase()) : null;
    if (!refM) {
      if (/(రూట్|\broute\b|దారులు\s*(వేయి|వెయ్|గీయి|గియ్|మళ్ళీ))/.test(s)) return { k: 'route' };
      if (/(అమర్చు|arrange|auto\s*place|ఆటో\s*ప్లేస్)/.test(s)) return { k: 'place' };
      if (/(gnd|జీఎన్డీ|గ్రౌండ్|ground)\s*(fill|నింపు|ఫిల్|పోర్|pour)|(fill|నింపు)\s*(gnd|ground|గ్రౌండ్)/.test(s)) return { k: 'fill', on: !OFF.test(s) };
      if (/(స్క్రూ|screw|mounting|మౌంటింగ్)\s*(రంధ్ర|hole|హోల్)/.test(s)) return { k: 'holes', on: !OFF.test(s) };
      if (BOARD.test(s) && /(సరిపడా|\bfit\b|చిన్నది\s*చేయి|shrink)/.test(s)) return { k: 'fit' };
      const wh = /(\d{2,3})\s*(x|×|\*|బై|by|into|ఇంటు)\s*(\d{2,3})/.exec(s);
      if (wh && BOARD.test(s)) return { k: 'size', w: +wh[1], h: +wh[3] };
      const w1 = /(వెడల్పు|width)\D{0,12}(\d{2,3})/.exec(s), h1 = /(ఎత్తు|height)\D{0,12}(\d{2,3})/.exec(s);
      if (w1 || h1) return { k: 'size', w: w1 ? +w1[2] : null, h: h1 ? +h1[2] : null };
      if (/(రంగు|color|colour|కలర్|మాస్క్)/.test(s) && !/(led|ఎల్ఈడీ|ఎల్ ఈ డి)/.test(s)) for (const c of COLOR_WORDS) if (c[1].test(s)) return { k: 'color', c: c[0] };
      return null;
    }
    if (!pt) return { k: 'noref', ref: refM[1].toUpperCase() };
    const after = s.slice(refM.index + refM[0].length);
    if (/(తిప్పు|తిప్పి|rotate|turn|రొటేట్)/.test(s)) {
      const dm = /\b(90|180|270)\s*(deg)?\b/.exec(after);
      return { k: 'rot', ref: pt.ref, deg: dm ? +dm[1] : 90 };
    }
    const dirs = [[-1, 0, /(ఎడమ|left|లెఫ్ట్)/], [1, 0, /(కుడి|right|రైట్)/], [0, -1, /(పైకి|\bup\b|పైన|అప్)/], [0, 1, /(కిందికి|కిందకి|\bdown\b|కింద|డౌన్)/]];
    const dir = dirs.find((d) => d[2].test(after) || d[2].test(s));
    if (dir && /(జరుపు|జరిపి|జరపు|కదుపు|కదిపి|move|shift|తీసుకెళ్ళు|పెట్టు|కి\s|వైపు)/.test(s)) {
      const am = /(\d+(?:\.\d+)?)\s*(mm)?/.exec(after.replace(/\b(90|180|270)deg\b/, ''));
      const mm = Math.max(0.5, Math.min(30, am ? parseFloat(am[1]) : 2.54));
      return { k: 'move', ref: pt.ref, dx: dir[0] * mm, dy: dir[1] * mm };
    }
    if (pt.lib === 'led5' || pt.lib === 'led3') { for (const c of LED_WORDS) if (c[1].test(after)) return { k: 'led', ref: pt.ref, color: c[0] }; }
    if (pt.lib === 'res') {
      const vm = /\b(\d+(?:\.\d+)?)\s*([kmr]|ohms?|ఓమ్(స్|లు)?|Ω)?(?![a-z0-9])/.exec(after);
      if (vm) {
        const unit = (vm[2] || '').toLowerCase(), v = vm[1] + (unit === 'k' ? 'k' : unit === 'm' ? 'M' : '');
        if (P.ohms(v) >= 1 && P.ohms(v) <= 10e6) return { k: 'val', ref: pt.ref, val: v };
      }
    }
    if (pt.lib === 'elec' || pt.lib === 'cer') {
      const vm = /(\d+(?:\.\d+)?)\s*(uf|nf|pf)(?:\s*(\d{1,3})\s*v)?/.exec(after);
      if (vm) {
        const unit = vm[2] === 'uf' ? 'uF' : vm[2] === 'nf' ? 'nF' : 'pF';
        let v = vm[1] + unit;
        if (pt.lib === 'elec') { const oldV = /(\d+)\s*V/i.exec(pt.val || ''); v += ' ' + (vm[3] || (oldV ? oldV[1] : '16')) + 'V'; }
        return { k: 'val', ref: pt.ref, val: v };
      }
    }
    return null;
  }

  /** Words that ask for a NEW board ("కొత్త బోర్డ్ …", "make a board …"). */
  function newBoardWords(words) {
    return /(కొత్త|new)\s*(బోర్డ్|బోర్డు|pcb|పీసీబీ|సర్క్యూట్|circuit)/i.test(String(words || '')) || /make\s+(me\s+)?a\s+(pcb|board|circuit)/i.test(String(words || ''));
  }
  /** Words about changing the board (for the AI when they are not a small change). */
  function boardWords(words) {
    const s = norm(words);
    const thing = /(బోర్డ్|బోర్డు|board|pcb|పీసీబీ|భాగం|భాగాలు|పార్ట్|\bpart|రెసిస్టర్|resistor|\bled|ఎల్ఈడీ|ఎల్ ఈ డి|కెపాసిటర్|capacitor|ట్రాన్సిస్టర్|transistor|బజర్|buzzer|డయోడ్|diode|సెన్సార్|sensor|హెడర్|header|టెర్మినల్|terminal|ఫ్యూజ్|fuse|esp32|555|7805|usb|రెగ్యులేటర్|regulator|మాడ్యూల్|module|స్విచ్|switch|జాక్|jack|మైక్|\bmic|\b(bz|sw|mk|[rcdqujf])\d{1,2}\b)/;
    const verb = /(చేయి|చెయ్|పెట్టు|జోడించు|కలుపు|\badd|remove|తీసేయి|తీసెయ్|మార్చు|change|replace|పెంచు|తగ్గించు|increase|reduce|make|ఇంకో|ఇంకొక|మరో|another|బదులు|instead)/;
    return thing.test(s) && verb.test(s);
  }

  /** Schematic order: from the power input along the joins (neighbours sit together on the drawing). */
  function schOrder(D) {
    const nb = new Map(D.parts.map((p) => [p.ref, new Set()]));
    D.nets.forEach((n) => {
      if (C.isGnd(n.n)) return;
      const refs = Array.from(new Set(n.p.map((q) => q.split('.')[0])));
      if (refs.length > 6 && C.isPower(n.n)) return;
      refs.forEach((a) => refs.forEach((b) => { if (a !== b && nb.has(a)) nb.get(a).add(b); }));
    });
    const start = D.parts.find((p) => p.lib === 'term2' || p.lib === 'usba') || D.parts[0];
    const seen = new Set(), out = [];
    const q = start ? [start.ref] : [];
    const visit = () => { while (q.length) { const r = q.shift(); if (seen.has(r)) continue; seen.add(r); out.push(r); Array.from(nb.get(r) || []).sort().forEach((x) => { if (!seen.has(x)) q.push(x); }); } };
    visit();
    D.parts.forEach((p) => { if (!seen.has(p.ref)) { q.push(p.ref); visit(); } });
    return out;
  }

  const api = { refuseWords, unsafe, elecFp, parse, layout, build, merge, forEdit, forHelp, moves, applyMoves, local, norm, newBoardWords, boardWords, schOrder, pinLabels, startSize };
  root.PcbAI = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
