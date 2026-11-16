// ⚡ Jarvis's circuit lab: the simulator (Anil, 16 Nov, G1). Our own code, never the AI's: the AI only turns his words
// into a board (parts + wires as data); every number here comes from these rules, the same every time, offline.
// A learning model, not an engineering tool: home loads are treated as simple resistances at their rated watts, AC as its
// RMS value, a battery as a fixed voltage with a little inside resistance. Real house wiring is for a licensed electrician.
//
// Board: { parts: [{ id, type, x, y, rot, label, props, st }], wires: [{ a: 'id.term', b: 'id.term' }] }
// CircuitSim.solve(board) → readings for every part and wire, fuses / MCBs / burnt parts / shorts, totals and estimates.
(function (root) {
  'use strict';

  const R_WIRE = 0;           // (wires and closed switches join terminals into one node)
  const LEAK = 1e-9;          // (every node a hair to ground: a floating piece never makes the maths fail)

  // ---------------------------------------------------------------- the parts

  const P = {
    // things that give current
    battery: { te: 'బ్యాటరీ', emoji: '🔋', cat: 'src', terms: ['+', '-'], dc: true, props: { v: 12, ah: 150 }, r: 0.012,
      info: 'బ్యాటరీ కరెంట్‌ని దాచుకుని ఇస్తుంది (DC). Ah ఎంత పెద్దదైతే అంత ఎక్కువ సేపు వస్తుంది. + నుంచి కరెంట్ బయటికి వెళ్ళి, పని చేసి, − కి తిరిగి వస్తుంది.' },
    cell: { te: 'చిన్న బ్యాటరీ', emoji: '🔋', cat: 'src', terms: ['+', '-'], dc: true, props: { v: 9, mah: 500 }, r: 1.2,
      info: 'టార్చ్, బొమ్మలు, రిమోట్ లో వాడే చిన్న బ్యాటరీ (1.5V, 3V, 9V). తక్కువ కరెంట్ మాత్రమే ఇవ్వగలదు.' },
    mains: { te: 'ఇంటి కరెంట్', emoji: '🏠', cat: 'src', terms: ['l', 'n'], ac: true, props: { v: 230, on: true }, r: 0.05,
      info: 'కరెంట్ ఆఫీస్ నుంచి వచ్చే ఇంటి కరెంట్ (AC, 230V). L (లైన్ / ఫేజ్) లో కరెంట్ వస్తుంది, N (న్యూట్రల్) ద్వారా తిరిగి వెళ్తుంది. దీన్ని తాకితే షాక్ కొడుతుంది.' },
    solar: { te: 'సోలార్ ప్యానెల్', emoji: '☀️', cat: 'src', terms: ['+', '-'], dc: true, props: { w: 330, v: 18, sun: 1 },
      info: 'ఎండ నుంచి కరెంట్ (DC) తయారు చేస్తుంది. ఎండ ఎక్కువ ఉంటే ఎక్కువ కరెంట్; రాత్రి సున్నా. సాధారణంగా బ్యాటరీని నింపడానికి వాడతారు.' },
    inverter: { te: 'ఇన్వర్టర్', emoji: '🔌', cat: 'src', terms: ['b+', 'b-', 'in-l', 'in-n', 'out-l', 'out-n'], bridge: true,
      props: { va: 900, v: 12, eff: 0.85, charge: 10 },
      info: 'కరెంట్ ఉన్నప్పుడు ఇంటి కరెంట్‌ని ఇంటికి పంపి బ్యాటరీని నింపుతుంది. కరెంట్ పోతే బ్యాటరీ DC ని 230V AC గా మార్చి ఇస్తుంది. VA కంటే ఎక్కువ లోడ్ పెడితే ఆగిపోతుంది.' },
    charger: { te: 'ఛార్జర్', emoji: '🔌', cat: 'src', terms: ['in-l', 'in-n', '+', '-'], bridge: true, props: { v: 5, w: 20 },
      info: 'ఇంటి కరెంట్ (230V AC) ని తక్కువ DC గా మారుస్తుంది: ఫోన్‌కి 5V, బైక్ బ్యాటరీకి ఎక్కువ. W ఎంత ఎక్కువైతే అంత వేగంగా ఛార్జ్ అవుతుంది.' },

    // things that control the current
    switch: { te: 'స్విచ్', emoji: '🔘', cat: 'ctl', terms: ['a', 'b'], link: true, props: { on: false },
      info: 'స్విచ్ దారిని తెరుస్తుంది / మూస్తుంది. ఆన్ అయితే కరెంట్ వెళ్ళే దారి పూర్తి అవుతుంది; ఆఫ్ అయితే దారి తెగిపోతుంది. ఇంట్లో స్విచ్ ఎప్పుడూ L (ఫేజ్) వైర్ మీదే ఉండాలి.' },
    switch2: { te: 'టూ-వే స్విచ్', emoji: '🔀', cat: 'ctl', terms: ['c', '1', '2'], link: true, props: { pos: 1 },
      info: 'మధ్య (c) ని 1 కి గానీ 2 కి గానీ కలుపుతుంది. రెండు టూ-వే స్విచ్‌లతో మెట్ల లైట్‌ని కింద నుంచీ పై నుంచీ ఆన్ / ఆఫ్ చేయొచ్చు.' },
    fuse: { te: 'ఫ్యూజ్', emoji: '🧯', cat: 'ctl', terms: ['a', 'b'], link: true, props: { amp: 6 },
      info: 'కరెంట్ దాని A కంటే ఎక్కువైతే లోపలి సన్నని తీగ కరిగి దారి తెగిపోతుంది. ఇల్లు, వైర్లు కాలిపోకుండా కాపాడుతుంది. పోయాక కొత్తది పెట్టాలి.' },
    mcb: { te: 'MCB', emoji: '🎚️', cat: 'ctl', terms: ['a', 'b'], link: true, props: { amp: 16 },
      info: 'ఫ్యూజ్ పని చేసే స్విచ్: కరెంట్ దాని A కంటే ఎక్కువైతే తానే పడిపోతుంది (ట్రిప్). కారణం సరిచేసి మళ్ళీ పైకి వేస్తే చాలు.' },

    // things that use the current (rated watts at rated volts)
    bulb: { te: 'బల్బ్', emoji: '💡', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 60, v: 230 }, glow: 1,
      info: 'పాత బల్బ్: లోపలి తీగ వేడెక్కి వెలుగుతుంది. కరెంట్ ఎక్కువ తింటుంది, ఎక్కువ భాగం వేడిగా పోతుంది.' },
    ledbulb: { te: 'LED బల్బ్', emoji: '💡', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 9, v: 230 }, glow: 1,
      info: 'LED బల్బ్: పాత 60W బల్బ్ అంత వెలుగు 9W లోనే. కరెంట్ బిల్లు బాగా తగ్గుతుంది.' },
    tube: { te: 'ట్యూబ్ లైట్', emoji: '💡', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 20, v: 230 }, glow: 1,
      info: 'పొడవైన లైట్ (LED ట్యూబ్ 20W, పాత ట్యూబ్ 40W). గదికి సమానంగా వెలుగు.' },
    fan: { te: 'ఫ్యాన్', emoji: '🌀', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 75, v: 230, speed: 5 }, spin: 1,
      info: 'సీలింగ్ ఫ్యాన్: లోపలి మోటార్ రెక్కలు తిప్పుతుంది. స్పీడ్ తగ్గిస్తే కరెంట్ కొంచెం తక్కువ. (ఇక్కడ సులువైన లెక్క.)' },
    tv: { te: 'TV', emoji: '📺', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 100, v: 230 },
      info: 'TV: సుమారు 60–150W. స్టాండ్‌బైలో కూడా కొంచెం తింటుంది.' },
    fridge: { te: 'ఫ్రిజ్', emoji: '🧊', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 150, v: 230 },
      info: 'ఫ్రిజ్: మోటార్ (కంప్రెసర్) మధ్య మధ్యలో మాత్రమే నడుస్తుంది, అందుకే రోజుకి సుమారు 1–1.5 యూనిట్లు.' },
    mixie: { te: 'మిక్సీ', emoji: '🥤', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 500, v: 230 }, spin: 1,
      info: 'మిక్సీ: ఎక్కువ వాట్స్, కానీ కొన్ని నిమిషాలే నడుస్తుంది కాబట్టి బిల్లు తక్కువ.' },
    ac: { te: 'AC', emoji: '❄️', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 1500, v: 230 },
      info: 'AC: ఇంట్లో ఎక్కువ కరెంట్ తినేది. ఇన్వర్టర్ మీద సాధారణంగా నడవదు.' },
    iron: { te: 'ఐరన్ బాక్స్', emoji: '👔', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 1000, v: 230 }, heat: 1,
      info: 'ఐరన్: లోపలి కాయిల్ వేడెక్కుతుంది. లోహపు బాడీ కాబట్టి ఎర్త్ తప్పనిసరి.' },
    geyser: { te: 'గీజర్', emoji: '🚿', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 2000, v: 230 }, heat: 1,
      info: 'గీజర్: నీళ్ళు వేడి చేసే కాయిల్. చాలా ఎక్కువ కరెంట్; ఎర్త్ తప్పనిసరి, వేరే MCB మంచిది.' },
    motor: { te: 'నీళ్ళ మోటార్', emoji: '⚙️', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 750, v: 230 }, spin: 1,
      info: 'నీళ్ళ పంపు మోటార్ (1 HP = సుమారు 750W). మొదలయ్యే క్షణంలో ఇంకా ఎక్కువ కరెంట్ తీసుకుంటుంది.' },
    phone: { te: 'ఫోన్', emoji: '📱', cat: 'load', terms: ['+', '-'], load: true, props: { w: 10, v: 5, wh: 19 },
      info: 'ఫోన్ ఛార్జ్ అవుతోంది: ఛార్జర్ నుంచి 5V DC. Wh = బ్యాటరీ ఎంత పెద్దదో.' },
    dclamp: { te: '12V LED లైట్', emoji: '💡', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 10, v: 12 }, glow: 1,
      info: 'బ్యాటరీ / సోలార్ మీద నేరుగా వెలిగే 12V LED లైట్. ఇన్వర్టర్ అక్కర్లేదు, కరెంట్ తక్కువ.' },
    headlight: { te: 'హెడ్‌లైట్', emoji: '🔦', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 55, v: 12 }, glow: 1,
      info: 'కార్ / బైక్ హెడ్‌లైట్: 12V DC మీద 55W అంటే సుమారు 4.6 A.' },
    horn: { te: 'హారన్', emoji: '📯', cat: 'load', terms: ['a', 'b'], load: true, props: { w: 40, v: 12 },
      info: 'హారన్: 12V DC. నొక్కినప్పుడే కరెంట్.' },
    smallbulb: { te: 'టార్చ్ బల్బ్', emoji: '💡', cat: 'kit', terms: ['a', 'b'], load: true, props: { w: 0.75, v: 2.5 }, glow: 1,
      info: 'టార్చ్ లోని చిన్న బల్బ్ (2.5V). ఎక్కువ వోల్ట్స్ ఇస్తే తీగ తెగిపోతుంది.' },
    buzzer: { te: 'బజర్', emoji: '🔔', cat: 'kit', terms: ['a', 'b'], load: true, props: { w: 0.3, v: 9 },
      info: 'బజర్ / కాలింగ్ బెల్: కరెంట్ వెళ్తే శబ్దం చేస్తుంది.' },
    dcmotor: { te: 'చిన్న మోటార్', emoji: '⚙️', cat: 'kit', terms: ['a', 'b'], load: true, props: { w: 3, v: 6 }, spin: 1,
      info: 'బొమ్మల్లో ఉండే చిన్న DC మోటార్. + − మారిస్తే వ్యతిరేక దిశలో తిరుగుతుంది.' },
    resistor: { te: 'రెసిస్టర్', emoji: '〰️', cat: 'kit', terms: ['a', 'b'], props: { ohm: 470 },
      info: 'రెసిస్టర్ కరెంట్‌ని తగ్గిస్తుంది (Ω ఎక్కువైతే కరెంట్ తక్కువ). LED ని కాలిపోకుండా కాపాడుతుంది. V = I × R.' },
    led: { te: 'LED', emoji: '🔴', cat: 'kit', terms: ['+', '-'], props: { color: 'red' }, glow: 1,
      info: 'LED ఒకే వైపు మాత్రమే కరెంట్ పంపుతుంది (+ పొడవు కాలు). రెసిస్టర్ లేకుండా బ్యాటరీకి నేరుగా కలిపితే కాలిపోతుంది.' },
  };

  const LED_VF = { red: 1.9, yellow: 2.0, green: 2.1, blue: 3.0, white: 3.0 };
  const LED_R = 15, LED_MAX = 0.03, LED_BURN = 0.06;
  /** Hours a day each thing usually runs (for the month's units); he can change them. */
  const HOURS = { dclamp: 5, bulb: 6, ledbulb: 6, tube: 6, fan: 10, tv: 4, fridge: 9.6, mixie: 0.3, ac: 6, iron: 0.5, geyser: 1, motor: 1,
    phone: 2, headlight: 1, horn: 0.02, smallbulb: 1, buzzer: 0.05, dcmotor: 0.5 };

  function def(type) { return P[type] || null; }

  /** A part's props with the defaults filled in (and the numbers kept sane). */
  function props(p) {
    const d = def(p.type);
    const o = Object.assign({}, d ? d.props : {}, p.props || {});
    for (const k of Object.keys(o)) {
      if (typeof (d && d.props[k]) === 'number') {
        let v = +o[k];
        if (!isFinite(v)) v = d.props[k];
        o[k] = v;
      }
    }
    if (o.v != null) o.v = Math.max(0.5, Math.min(1000, o.v));
    if (o.w != null) o.w = Math.max(0, Math.min(20000, o.w));
    if (o.amp != null) o.amp = Math.max(0.1, Math.min(200, o.amp));
    if (o.ohm != null) o.ohm = Math.max(0.1, Math.min(1e7, o.ohm));
    if (o.ah != null) o.ah = Math.max(1, Math.min(2000, o.ah));
    if (o.va != null) o.va = Math.max(50, Math.min(20000, o.va));
    if (o.speed != null) o.speed = Math.max(0, Math.min(5, Math.round(o.speed)));
    if (o.sun != null) o.sun = Math.max(0, Math.min(1, o.sun));
    return o;
  }

  /** The resistance of a load at its rated watts (a fan by its speed). */
  function loadR(p, o) {
    if (p.type === 'resistor') return o.ohm;
    let w = o.w;
    if (p.type === 'fan') w = o.speed <= 0 ? 0 : o.w * (0.35 + 0.65 * o.speed / 5);
    if (w <= 0) return Infinity;
    return (o.v * o.v) / w;
  }

  // ---------------------------------------------------------------- nodes (union-find over terminals)

  function UF() { this.p = new Map(); }
  UF.prototype.find = function (a) {
    if (!this.p.has(a)) this.p.set(a, a);
    let r = a;
    while (this.p.get(r) !== r) r = this.p.get(r);
    let x = a;
    while (this.p.get(x) !== r) { const n = this.p.get(x); this.p.set(x, r); x = n; }
    return r;
  };
  UF.prototype.union = function (a, b) { const x = this.find(a), y = this.find(b); if (x !== y) this.p.set(x, y); };

  const T = (id, t) => id + '.' + t;

  /** The links that join terminals now (wires, closed switches, whole fuses, MCBs that are up): [{a, b, kind, ref}]. */
  function links(board, st) {
    const out = [];
    board.wires.forEach((w, i) => out.push({ a: w.a, b: w.b, kind: 'wire', ref: i }));
    for (const p of board.parts) {
      const d = def(p.type);
      if (!d || !d.link) continue;
      const o = props(p), s = st[p.id] || {};
      if (p.type === 'switch' && o.on) out.push({ a: T(p.id, 'a'), b: T(p.id, 'b'), kind: 'part', ref: p.id });
      if (p.type === 'switch2') out.push({ a: T(p.id, 'c'), b: T(p.id, o.pos === 2 ? '2' : '1'), kind: 'part', ref: p.id });
      if ((p.type === 'fuse' && !s.blown) || (p.type === 'mcb' && !s.tripped)) out.push({ a: T(p.id, 'a'), b: T(p.id, 'b'), kind: 'part', ref: p.id });
    }
    return out;
  }

  // ---------------------------------------------------------------- the linear solve

  function gauss(A, b) {
    const n = b.length;
    for (let c = 0; c < n; c++) {
      let piv = c, best = Math.abs(A[c][c]);
      for (let r = c + 1; r < n; r++) { const v = Math.abs(A[r][c]); if (v > best) { best = v; piv = r; } }
      if (best < 1e-300) continue;
      if (piv !== c) { const t = A[c]; A[c] = A[piv]; A[piv] = t; const u = b[c]; b[c] = b[piv]; b[piv] = u; }
      const Ac = A[c], d = Ac[c];
      for (let r = c + 1; r < n; r++) {
        const Ar = A[r], f = Ar[c] / d;
        if (f === 0) continue;
        for (let k = c; k < n; k++) Ar[k] -= f * Ac[k];
        b[r] -= f * b[c];
      }
    }
    const x = new Array(n).fill(0);
    for (let r = n - 1; r >= 0; r--) {
      let s = b[r];
      for (let k = r + 1; k < n; k++) s -= A[r][k] * x[k];
      x[r] = Math.abs(A[r][r]) < 1e-300 ? 0 : s / A[r][r];
    }
    return x;
  }

  // ---------------------------------------------------------------- one solve

  /**
   * Solves the board as it is now. st: the parts' states that last (blown fuses, tripped MCBs, burnt parts, battery charge,
   * an inverter stopped by overload); it is updated here (a fuse blows, a part burns) and returned with the readings.
   */
  function solve(board, stIn) {
    board = normalise(board);
    const st = JSON.parse(JSON.stringify(stIn || {}));
    for (const p of board.parts) {
      st[p.id] = st[p.id] || {};
      if (p.st) for (const k of Object.keys(p.st)) if (st[p.id][k] === undefined) st[p.id][k] = p.st[k];
      if ((p.type === 'battery' || p.type === 'cell') && typeof st[p.id].soc !== 'number') st[p.id].soc = 1;
    }
    const events = [];
    const mix = mixed(board);
    if (mix) return { ok: false, error: mix.te, bad: mix.ids, st, parts: {}, wires: [], totals: emptyTotals(), warnings: [], events };

    let res = null;
    const invDraw = {};
    Object.defineProperty(invDraw, '__ledOff', { value: new Set(), enumerable: false });
    Object.defineProperty(invDraw, '__knee', { value: new Set(), enumerable: false });
    for (let round = 0; round < 16; round++) {
      res = once(board, st, invDraw);
      if (res.ledBack.length) { res.ledBack.forEach((id) => (id.startsWith('knee:') ? invDraw.__knee.add(id.slice(5)) : invDraw.__ledOff.add(id))); continue; }
      // the inverter's battery side draws what its AC side gives (÷ its efficiency): solve again with that
      let moved = false;
      for (const id of Object.keys(res.invOut)) {
        const want = res.invOut[id];
        if (Math.abs((invDraw[id] || 0) - want) > 0.01) { invDraw[id] = want; moved = true; }
      }
      // what breaks: one thing per round (the biggest first), then solve again
      const brk = breaks(board, st, res);
      if (brk) { events.push(brk); continue; }
      if (!moved) break;
    }
    res.events = events;
    res.st = st;
    res.ok = true;
    res.warnings = warnings(board, res);
    return res;
  }

  function emptyTotals() { return { loadW: 0, sourceW: 0, batteries: [], inverters: [], solar: [] }; }

  function normalise(board) {
    const b = { parts: [], wires: [] };
    const ids = new Set();
    for (const p of (board && board.parts) || []) {
      if (!p || !def(p.type) || !p.id || ids.has(p.id)) continue;
      ids.add(p.id);
      b.parts.push(p);
    }
    const termOk = (t) => {
      if (typeof t !== 'string') return false;
      const i = t.lastIndexOf('.');
      if (i < 1) return false;
      const id = t.slice(0, i), term = t.slice(i + 1);
      const p = b.parts.find((x) => x.id === id);
      return !!p && def(p.type).terms.includes(term);
    };
    for (const w of (board && board.wires) || []) if (w && termOk(w.a) && termOk(w.b) && w.a !== w.b) b.wires.push({ a: w.a, b: w.b });
    return b;
  }

  /** AC and DC joined directly (not through an inverter / a charger): that is not simulated. */
  function mixed(board) {
    const uf = new UF();
    for (const w of board.wires) uf.union(w.a, w.b);
    for (const p of board.parts) {
      const d = def(p.type);
      if (p.type === 'inverter') { uf.union(T(p.id, 'b+'), T(p.id, 'b-')); uf.union(T(p.id, 'in-l'), T(p.id, 'in-n')); uf.union(T(p.id, 'out-l'), T(p.id, 'out-n')); uf.union(T(p.id, 'in-l'), T(p.id, 'out-l')); continue; }
      if (p.type === 'charger') { uf.union(T(p.id, 'in-l'), T(p.id, 'in-n')); uf.union(T(p.id, '+'), T(p.id, '-')); continue; }
      for (let i = 1; i < d.terms.length; i++) uf.union(T(p.id, d.terms[0]), T(p.id, d.terms[i]));
    }
    const kind = new Map();
    const mark = (t, k, id) => { const r = uf.find(t); const m = kind.get(r) || { ac: [], dc: [] }; m[k].push(id); kind.set(r, m); };
    for (const p of board.parts) {
      const d = def(p.type);
      if (d.ac) mark(T(p.id, 'l'), 'ac', p.id);
      if (d.dc) mark(T(p.id, '+'), 'dc', p.id);
      if (p.type === 'inverter') { mark(T(p.id, 'b+'), 'dc', p.id); mark(T(p.id, 'out-l'), 'ac', p.id); }
      if (p.type === 'charger') { mark(T(p.id, 'in-l'), 'ac', p.id); mark(T(p.id, '+'), 'dc', p.id); }
    }
    for (const m of kind.values()) {
      if (m.ac.length && m.dc.length)
        return { te: 'ఇంటి కరెంట్ (AC) ని బ్యాటరీ / సోలార్ (DC) కి నేరుగా కలిపారు: మధ్యలో ఇన్వర్టర్ లేదా ఛార్జర్ ఉండాలి. నిజంగా అయితే ఇది చాలా ప్రమాదం.', ids: m.ac.concat(m.dc) };
    }
    return null;
  }

  function once(board, st, invDraw) {
    const uf = new UF();
    const L = links(board, st);
    for (const p of board.parts) for (const t of def(p.type).terms) uf.find(T(p.id, t));
    for (const l of L) uf.union(l.a, l.b);
    const idx = new Map();
    for (const k of uf.p.keys()) { const r = uf.find(k); if (!idx.has(r)) idx.set(r, idx.size); }
    const n = idx.size;
    const node = (t) => idx.get(uf.find(t));
    const A = Array.from({ length: n }, () => new Array(n).fill(0));
    const b = new Array(n).fill(0);
    for (let i = 0; i < n; i++) A[i][i] += LEAK;
    const g = (i, j, c) => { if (i === j || !isFinite(c) || c <= 0) return; A[i][i] += c; A[j][j] += c; A[i][j] -= c; A[j][i] -= c; };
    const inj = (i, cur) => { b[i] += cur; };
    const sources = [];      // {id, plus, minus, v, r, kind}
    const elems = [];        // two-terminal conductances for the readings: {id, a, b, R, vf}
    const sinks = [];        // {id, plus, minus, I} current drawn from plus back to minus
    const invOut = {}, invMode = {}, chg = {}, ledBack = [];

    // the AC side first: which inverters see mains on their input
    const acOn = (tl, tn) => board.parts.some((q) => q.type === 'mains' && props(q).on && !(st[q.id] || {}).short
      && node(T(q.id, 'l')) === node(tl) && node(T(q.id, 'n')) === node(tn));
    for (const p of board.parts) {
      const o = props(p), s = st[p.id];
      const d = def(p.type);
      if (p.type === 'battery' || p.type === 'cell') {
        if (s.soc <= 0.02 && !s.charging) { s.dead = true; continue; }
        s.dead = false;
        const r = p.type === 'cell' ? Math.max(0.3, d.r * o.v / 9) : d.r * o.v / 12;
        sources.push({ id: p.id, plus: T(p.id, '+'), minus: T(p.id, '-'), v: o.v, r });
      } else if (p.type === 'mains') {
        if (o.on) sources.push({ id: p.id, plus: T(p.id, 'l'), minus: T(p.id, 'n'), v: o.v, r: d.r });
      } else if (p.type === 'solar') {
        if (o.sun > 0.01) {
          // a panel gives about its full current up to its working volts (flat), then less and less up to its open volts (knee)
          const imp = o.w / o.v * o.sun, isc = imp * 1.08, voc = o.v * 1.22;
          if (invDraw.__knee && invDraw.__knee.has(p.id)) sources.push({ id: p.id, plus: T(p.id, '+'), minus: T(p.id, '-'), v: voc, r: (voc - o.v) / imp, solar: o.v });
          else { const gs = isc * 0.05 / o.v; sources.push({ id: p.id, plus: T(p.id, '+'), minus: T(p.id, '-'), v: isc / gs, r: 1 / gs, solar: o.v, flat: true }); }
        }
      } else if (p.type === 'inverter') {
        const mainsIn = acOn(T(p.id, 'in-l'), T(p.id, 'in-n'));
        if (s.overload) { invMode[p.id] = 'overload'; }
        else if (mainsIn) {
          invMode[p.id] = 'mains';
          sources.push({ id: p.id, plus: T(p.id, 'out-l'), minus: T(p.id, 'out-n'), v: 230, r: 0.1, kind: 'inv' });
          // what comes through takes the same from the input side, and the battery is charged
          if (invDraw[p.id]) sinks.push({ id: p.id + ':in', plus: T(p.id, 'in-l'), minus: T(p.id, 'in-n'), I: invDraw[p.id] * o.eff / 230 });
          chg[p.id] = o.charge;
        } else {
          invMode[p.id] = 'battery';
          sources.push({ id: p.id, plus: T(p.id, 'out-l'), minus: T(p.id, 'out-n'), v: 230, r: 0.1, kind: 'inv', needsDc: true });
          if (invDraw[p.id]) sinks.push({ id: p.id + ':dc', plus: T(p.id, 'b+'), minus: T(p.id, 'b-'), I: invDraw[p.id] / Math.max(1, o.v) });
        }
      } else if (p.type === 'charger') {
        if (acOn(T(p.id, 'in-l'), T(p.id, 'in-n')) || board.parts.some((q) => q.type === 'inverter' && invMode[q.id] && invMode[q.id] !== 'overload'
          && node(T(q.id, 'out-l')) === node(T(p.id, 'in-l')) && node(T(q.id, 'out-n')) === node(T(p.id, 'in-n')))) {
          const imax = o.w / o.v;
          sources.push({ id: p.id, plus: T(p.id, '+'), minus: T(p.id, '-'), v: o.v, r: Math.max(0.05, o.v * 0.05 / imax), kind: 'chg' });
          if (invDraw[p.id]) sinks.push({ id: p.id + ':in', plus: T(p.id, 'in-l'), minus: T(p.id, 'in-n'), I: invDraw[p.id] / 0.85 / 230 });
        }
      } else if (d.load || p.type === 'resistor') {
        if (s.burnt || o.off) continue;
        const R = loadR(p, o);
        if (isFinite(R)) elems.push({ id: p.id, a: T(p.id, d.terms[0]), b: T(p.id, d.terms[1]), R });
      } else if (p.type === 'led') {
        if (s.burnt || o.off) continue;
        if (!invDraw.__ledOff || !invDraw.__ledOff.has(p.id)) elems.push({ id: p.id, a: T(p.id, '+'), b: T(p.id, '-'), R: LED_R, vf: LED_VF[o.color] || 2 });
      }
    }
    // an inverter on battery needs a live battery on its b+ / b- side
    for (const s of sources) {
      if (!s.needsDc) continue;
      const inv = board.parts.find((q) => q.id === s.id);
      const vb = sources.some((q) => (q.id !== s.id) && !q.kind && node(q.plus) === node(T(inv.id, 'b+')) && node(q.minus) === node(T(inv.id, 'b-')));
      if (!vb) { s.off = true; invMode[s.id] = 'nobattery'; }
    }
    const live = sources.filter((s) => !s.off);
    // shorts: a source whose + and − are joined by wires / switches only
    const shorted = [];
    for (const s of live) {
      if (node(s.plus) === node(s.minus)) { shorted.push(s); continue; }
      const i = node(s.plus), j = node(s.minus), c = 1 / s.r;
      g(i, j, c); inj(i, s.v * c); inj(j, -s.v * c);
    }
    for (const e of elems) {
      const i = node(e.a), j = node(e.b);
      if (e.vf) { g(i, j, 1 / e.R); inj(i, e.vf / e.R); inj(j, -e.vf / e.R); } // (an LED: I = (Va − Vb − vf) / R)
      else g(i, j, 1 / e.R);
    }
    for (const k of sinks) { inj(node(k.plus), -k.I); inj(node(k.minus), k.I); }
    const v = n ? gauss(A, b.slice()) : [];
    const V = (t) => v[node(t)] || 0;

    // the readings, and every terminal's current into its node (for the links' currents)
    const into = new Map();
    const add = (t, c) => into.set(t, (into.get(t) || 0) + c);
    const parts = {};
    for (const p of board.parts) parts[p.id] = { v: 0, i: 0, p: 0, on: false, bright: 0, state: 'ok' };
    for (const s of live) {
      let cur;
      if (shorted.includes(s)) cur = s.v / s.r;
      else cur = (s.v - (V(s.plus) - V(s.minus))) / s.r;
      add(s.plus, cur); add(s.minus, -cur);
      const r = parts[s.id];
      r.v = shorted.includes(s) ? 0 : V(s.plus) - V(s.minus);
      if (s.flat && r.v > s.solar) ledBack.push('knee:' + s.id); // (above its working volts: the knee model, solved again)
      r.i = cur;
      r.p = r.v * cur;
      r.on = true;
      if (shorted.includes(s)) r.state = 'short';
      if (s.kind === 'inv') { invOut[s.id] = Math.max(0, r.p) / Math.max(0.3, props(board.parts.find((q) => q.id === s.id)).eff); }
      if (s.kind === 'chg') invOut[s.id] = Math.max(0, r.p);
    }
    for (const k of sinks) {
      add(k.plus, -k.I); add(k.minus, k.I);
      const id = k.id.split(':')[0];
      if (k.id.endsWith(':dc')) { parts[id].dcI = k.I; parts[id].dcW = k.I * (V(k.plus) - V(k.minus)); }
      if (k.id.endsWith(':in')) { parts[id].inI = k.I; }
    }
    for (const e of elems) {
      const vab = V(e.a) - V(e.b);
      let cur = e.vf ? (vab - e.vf) / e.R : vab / e.R;
      if (e.vf && cur < -1e-9) ledBack.push(e.id); // (an LED the wrong way, or below its vf: it does not conduct; solved again)
      add(e.a, -cur); add(e.b, cur);
      const r = parts[e.id];
      r.v = vab; r.i = cur; r.p = Math.abs(vab * cur);
    }
    for (const p of board.parts) {
      const r = parts[p.id], d = def(p.type), o = props(p), s = st[p.id];
      if (d.load) {
        const rated = p.type === 'fan' ? o.w * (0.35 + 0.65 * o.speed / 5) : o.w;
        r.bright = rated > 0 ? Math.min(1.6, r.p / rated) : 0;
        r.on = r.bright > 0.08;
        r.rated = rated;
        r.dir = r.v >= 0 ? 1 : -1;
        if (s.burnt) { r.state = 'burnt'; r.on = false; r.bright = 0; }
      }
      if (p.type === 'resistor') r.on = Math.abs(r.i) > 1e-6;
      if (p.type === 'led') {
        r.bright = Math.min(1.5, Math.max(0, r.i) / 0.02);
        r.on = r.i > 0.001;
        if (s.burnt) { r.state = 'burnt'; r.on = false; }
      }
      if (p.type === 'fuse' && s.blown) r.state = 'blown';
      if (p.type === 'mcb' && s.tripped) r.state = 'tripped';
      if (p.type === 'switch') r.on = !!o.on;
      if (p.type === 'switch2') r.pos = o.pos === 2 ? 2 : 1;
      if (p.type === 'inverter') {
        r.mode = invMode[p.id] || 'off';
        r.loadW = Math.max(0, r.p);
        r.pct = o.va > 0 ? r.loadW / (o.va * 0.8) : 0;
        if (r.mode === 'overload') { r.state = 'overload'; r.on = false; }
        if (r.mode === 'nobattery') { r.state = 'dead'; r.on = false; }
        r.charging = r.mode === 'mains' && chg[p.id] > 0;
      }
      if ((p.type === 'battery' || p.type === 'cell') && s.dead) { r.state = 'dead'; r.on = false; }
      if (p.type === 'battery' || p.type === 'cell') r.soc = s.soc;
    }
    // inverter charging the battery while mains is on: a little current into the battery's +
    for (const p of board.parts) {
      if (p.type !== 'inverter' || invMode[p.id] !== 'mains') continue;
      const bat = board.parts.find((q) => (q.type === 'battery') && node(T(q.id, '+')) === node(T(p.id, 'b+')) && node(T(q.id, '-')) === node(T(p.id, 'b-')));
      if (bat && st[bat.id].soc < 0.999) { parts[bat.id].charging = Math.min(props(p).charge, 30); parts[p.id].charging = true; }
    }
    // the current in every link (wire, closed switch, fuse, MCB): a spanning tree in each node, from its terminals' currents
    const linkI = linkCurrents(L, into, uf);
    const wires = board.wires.map(() => 0);
    L.forEach((l, k) => {
      if (l.kind === 'wire') wires[l.ref] = linkI[k];
      else { const r = parts[l.ref]; r.i = Math.abs(linkI[k]); r.flow = linkI[k]; }
    });
    return { parts, wires, invOut, V: (t) => V(t), node, shorted: shorted.map((s) => s.id), links: L, linkI, ledBack };
  }

  /** Each node's links form a graph; a BFS tree carries its terminals' currents to the root. + = from l.a to l.b. */
  function linkCurrents(L, into, uf) {
    const out = new Array(L.length).fill(0);
    const byNode = new Map();
    L.forEach((l, k) => { const r = uf.find(l.a); if (!byNode.has(r)) byNode.set(r, []); byNode.get(r).push(k); });
    for (const ks of byNode.values()) {
      const adj = new Map();
      const addAdj = (t, k, other) => { if (!adj.has(t)) adj.set(t, []); adj.get(t).push([k, other]); };
      for (const k of ks) { addAdj(L[k].a, k, L[k].b); addAdj(L[k].b, k, L[k].a); }
      const rootT = L[ks[0]].a;
      const parent = new Map([[rootT, null]]), order = [rootT];
      for (let q = 0; q < order.length; q++) {
        const t = order[q];
        for (const [k, o] of adj.get(t) || []) if (!parent.has(o)) { parent.set(o, [k, t]); order.push(o); }
      }
      const sub = new Map();
      for (let q = order.length - 1; q > 0; q--) {
        const t = order[q];
        const s = (sub.get(t) || 0) + (into.get(t) || 0);
        const [k, up] = parent.get(t);
        // s flows from t up to its parent
        out[k] = L[k].a === t ? s : -s;
        sub.set(up, (sub.get(up) || 0) + s);
      }
    }
    return out;
  }

  /** The first thing that breaks now (a short, a fuse, an MCB, an overloaded inverter, a part over its volts, an LED): marks it and says it. */
  function breaks(board, st, res) {
    const cand = [];
    for (const p of board.parts) {
      const r = res.parts[p.id], o = props(p), s = st[p.id];
      if (p.type === 'fuse' && !s.blown && r.i > o.amp) cand.push({ id: p.id, over: r.i / o.amp, set: () => { s.blown = true; }, te: 'ఫ్యూజ్ పోయింది: ' + amps(r.i) + ' వెళ్ళింది, ఇది ' + o.amp + ' A దాకానే.' });
      if (p.type === 'mcb' && !s.tripped && r.i > o.amp * 1.13) cand.push({ id: p.id, over: r.i / o.amp, set: () => { s.tripped = true; }, te: 'MCB పడిపోయింది (ట్రిప్): ' + amps(r.i) + ', ఇది ' + o.amp + ' A దాకానే.' });
      if (p.type === 'inverter' && r.mode && r.mode !== 'overload' && r.mode !== 'nobattery' && r.loadW > o.va * 0.8 * 1.05)
        cand.push({ id: p.id, over: r.loadW / (o.va * 0.8), set: () => { s.overload = true; }, te: 'ఇన్వర్టర్ ఓవర్‌లోడ్: ' + Math.round(r.loadW) + ' W, ఇది ' + o.va + ' VA (సుమారు ' + Math.round(o.va * 0.8) + ' W) దాకానే. ఆగిపోయింది.' });
      const d = def(p.type);
      if (d.load && !s.burnt && r.rated > 0 && Math.abs(r.v) > o.v * 1.35 && r.p > r.rated * 1.8)
        cand.push({ id: p.id, over: Math.abs(r.v) / o.v, set: () => { s.burnt = true; }, te: d.te + ' కాలిపోయింది: ' + Math.round(Math.abs(r.v)) + ' V వచ్చింది, ఇది ' + o.v + ' V ది.' });
      if (p.type === 'led' && !s.burnt && r.i > LED_BURN)
        cand.push({ id: p.id, over: r.i / LED_MAX, set: () => { s.burnt = true; }, te: 'LED కాలిపోయింది: ' + amps(r.i) + ' వెళ్ళింది (20 mA చాలు). మధ్యలో రెసిస్టర్ పెట్టాలి.' });
    }
    if (!cand.length) return null;
    cand.sort((a, b) => b.over - a.over);
    const c = cand[0];
    c.set();
    return { id: c.id, te: c.te };
  }

  function amps(i) { const a = Math.abs(i); return a >= 1 ? (Math.round(a * 10) / 10) + ' A' : Math.round(a * 1000) + ' mA'; }

  function warnings(board, res) {
    const w = [];
    for (const id of res.shorted || []) {
      const p = board.parts.find((q) => q.id === id);
      w.push({ id, te: '⚠️ షార్ట్ సర్క్యూట్: ' + def(p.type).te + ' + − మధ్య లోడ్ లేకుండా నేరుగా కలిసింది (' + amps(res.parts[id].i) + '). నిజంగా అయితే వైర్ కాలిపోతుంది, మంటలు రావచ్చు; ఫ్యూజ్ / MCB పెట్టండి.' });
    }
    for (const p of board.parts) {
      const r = res.parts[p.id], o = props(p);
      if ((p.type === 'iron' || p.type === 'geyser') ) w.push({ id: p.id, te: '🔩 ' + def(p.type).te + ': లోహపు బాడీ, నిజంగా అయితే ఎర్త్ వైర్ తప్పనిసరి.', soft: true });
      if (def(p.type).load && !st0(res, p.id).burnt && r.rated > 0 && r.p > 0.01 && Math.abs(r.v) < o.v * 0.6 && r.on === false)
        w.push({ id: p.id, te: def(p.type).te + ' కి సరిపడా వోల్ట్స్ రావడం లేదు (' + Math.round(Math.abs(r.v) * 10) / 10 + ' V, కావాల్సింది ' + o.v + ' V).', soft: true });
    }
    return w;
  }
  function st0(res, id) { return (res.st && res.st[id]) || {}; }

  // ---------------------------------------------------------------- time, estimates

  /** Moves time on: batteries drain or fill by the current they give / take. */
  function advance(board, st, res, seconds) {
    const out = JSON.parse(JSON.stringify(st));
    for (const p of board.parts) {
      if (p.type !== 'battery' && p.type !== 'cell') continue;
      const o = props(p), s = out[p.id] || (out[p.id] = { soc: 1 });
      const r = res.parts[p.id] || {};
      const ah = p.type === 'cell' ? o.mah / 1000 : o.ah;
      let i = r.i || 0;
      if (r.charging) i -= r.charging;
      s.soc = Math.max(0, Math.min(1, (typeof s.soc === 'number' ? s.soc : 1) - i * seconds / 3600 / ah));
      s.charging = i < -0.05;
    }
    return out;
  }

  /** The numbers for the 🔋 card: what each battery gives, how long it lasts, inverter load, solar filling time, the month's units. */
  function estimates(board, res, opts) {
    board = normalise(board);
    opts = opts || {};
    const rate = typeof opts.rate === 'number' ? opts.rate : 7;
    const hours = Object.assign({}, HOURS, opts.hours || {});
    const out = { loads: [], loadW: 0, batteries: [], inverters: [], solar: [], unitsMonth: 0, billMonth: 0, rate };
    for (const p of board.parts) {
      const d = def(p.type), r = res.parts[p.id] || {}, o = props(p);
      if (d.load || p.type === 'led') {
        const w = Math.max(0, r.p || 0);
        out.loadW += w;
        const h = typeof (opts.partHours || {})[p.id] === 'number' ? opts.partHours[p.id] : (hours[p.type] != null ? hours[p.type] : 2);
        const ratedW = d.load ? (p.type === 'fan' ? o.w * (0.35 + 0.65 * o.speed / 5) : o.w) : 0.04;
        const units = ratedW * h * 30 / 1000;
        out.loads.push({ id: p.id, te: p.label || d.te, w, ratedW, hours: h, units, rupees: units * rate });
        if (o.v >= 100) { out.unitsMonth += units; out.billMonth += units * rate; }
      }
      if (p.type === 'battery') {
        const draw = Math.max(0, (r.i || 0) * (r.v || o.v));
        const wh = o.ah * o.v * (opts.dod || 0.8) * (res.st && res.st[p.id] ? res.st[p.id].soc : 1);
        out.batteries.push({ id: p.id, te: p.label || d.te, w: draw, wh: o.ah * o.v, hours: draw > 0.5 ? wh / draw : null, soc: res.st && res.st[p.id] ? res.st[p.id].soc : 1, charging: !!r.charging });
      }
      if (p.type === 'inverter') {
        out.inverters.push({ id: p.id, te: p.label || d.te, w: r.loadW || 0, va: o.va, maxW: Math.round(o.va * 0.8), pct: r.pct || 0, mode: r.mode || 'off',
          needVA: Math.ceil((r.loadW || 0) / 0.8 / 50) * 50 });
      }
      if (p.type === 'solar') {
        const w = Math.max(0, r.p || 0);
        const bat = board.parts.find((q) => q.type === 'battery');
        const need = bat ? props(bat).ah * props(bat).v * (1 - ((res.st && res.st[bat.id]) ? res.st[bat.id].soc : 1)) : 0;
        out.solar.push({ id: p.id, te: p.label || d.te, w, peak: o.w, sunHoursToFill: bat ? (need > 0 ? need / Math.max(1, o.w * 0.75) : 0) : null,
          fullBatterySunHours: bat ? props(bat).ah * props(bat).v / Math.max(1, o.w * 0.75) : null });
      }
    }
    return out;
  }

  /** 🔦 What to keep on so a battery lasts N hours: the biggest loads go off first. */
  function planBackup(board, res, hoursWanted, batteryId) {
    const est = estimates(board, res);
    const bat = est.batteries.find((b) => !batteryId || b.id === batteryId);
    if (!bat) return null;
    const p = board.parts.find((q) => q.id === bat.id);
    const o = props(p);
    const usable = o.ah * o.v * 0.8 * (bat.soc == null ? 1 : bat.soc);
    const inv = est.inverters[0];
    const eff = inv ? props(board.parts.find((q) => q.id === inv.id)).eff : 1;
    const budgetW = usable / Math.max(0.1, hoursWanted) * eff;
    const loads = est.loads.filter((l) => l.w > 0.5).sort((a, b) => a.w - b.w); // (small ones first: keep as many things as fit)
    let used = 0;
    const keep = [], off = [];
    for (const l of loads) { if (used + l.w <= budgetW) { used += l.w; keep.push(l); } else off.push(l); }
    return { hours: hoursWanted, budgetW: Math.round(budgetW), keep, off, lastsHours: used > 0 ? usable * eff / used : null };
  }

  root.CircuitSim = { PARTS: P, HOURS, LED_VF, def, props, solve, advance, estimates, planBackup, amps, normalise };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.CircuitSim;
})(typeof window !== 'undefined' ? window : globalThis);
