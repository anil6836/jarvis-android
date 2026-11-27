// ⚡→🔌 (Anil, 28 Nov: "D చేయి"): a circuit-lab board (its parts + wires) becomes the PCB studio's circuit data (parts + how their
// pins join) — the same data the studio's AI gives — so the studio places, routes and checks it with its own code. ₹0, no AI, no
// network. Low-voltage DC only: a board with 230 V things (an inverter, house loads, a transformer) or more than 24 V is never made;
// a wall adapter (charger) becomes a DC jack (the adapter stays in the wall socket, only its DC comes to the board).
'use strict';
(function (root) {
  /** The lab's own defaults (assets/circuit/sim.js) for values a part may leave out. */
  const DEF = { battery: { v: 12 }, cell: { v: 9 }, solar: { v: 18 }, resistor: { ohm: 470 }, capacitor: { uf: 1000, v: 25 }, pot: { ohm: 10000 },
    regulator: { v: 5 }, led: { color: 'red' }, fuse: { amp: 6 }, mcb: { amp: 16 }, relay: { v: 12 }, charger: { v: 5, w: 20 },
    phone: { w: 10, v: 5 }, dclamp: { w: 10, v: 12 }, headlight: { w: 55, v: 12 }, horn: { w: 40, v: 12 }, smallbulb: { w: 0.75, v: 2.5 },
    buzzer: { w: 0.3, v: 9 }, dcmotor: { w: 3, v: 6 } };
  /** 230 V things: a board with any of these is not made. */
  const HOUSE = new Set(['inverter', 'transformer', 'bulb', 'ledbulb', 'tube', 'fan', 'tv', 'fridge', 'mixie', 'ac', 'iron', 'geyser', 'motor']);
  /** Parts that only pass the current on (a switch, a fuse): on the wall side of a charger they stay off the board with the mains. */
  const PASS = new Set(['switch', 'switch2', 'fuse', 'mcb', 'pushbtn']);
  const TE = { battery: 'బ్యాటరీ', cell: 'సెల్', solar: 'సోలార్ ప్యానెల్', charger: 'అడాప్టర్', mains: 'మెయిన్స్', inverter: 'ఇన్వర్టర్', transformer: 'ట్రాన్స్‌ఫార్మర్',
    bulb: 'బల్బ్', ledbulb: 'LED బల్బ్', tube: 'ట్యూబ్ లైట్', fan: 'ఫ్యాన్', tv: 'TV', fridge: 'ఫ్రిజ్', mixie: 'మిక్సీ', ac: 'AC', iron: 'ఐరన్', geyser: 'గీజర్',
    motor: 'నీళ్ళ మోటార్', phone: 'ఫోన్', dclamp: '12V LED లైట్', headlight: 'హెడ్‌లైట్', horn: 'హారన్', smallbulb: 'టార్చ్ బల్బ్', buzzer: 'బజర్',
    dcmotor: 'చిన్న మోటార్', resistor: 'రెసిస్టర్', led: 'LED', capacitor: 'కెపాసిటర్', diode: 'డయోడ్', npn: 'ట్రాన్సిస్టర్', pot: 'నాబ్ (పాట్)',
    ldr: 'LDR', regulator: 'రెగ్యులేటర్', pushbtn: 'పుష్ బటన్', relay: 'రిలే', switch: 'స్విచ్', switch2: 'టూ-వే స్విచ్', fuse: 'ఫ్యూజ్', mcb: 'MCB' };
  /** Loads that sit off the board on two wires: their pad names. */
  const WIRED = { dclamp: ['LMP1', 'LMP2'], headlight: ['LGT1', 'LGT2'], horn: ['HRN1', 'HRN2'], smallbulb: ['BLB1', 'BLB2'], dcmotor: ['M1', 'M2'] };
  const CAP_V = [10, 16, 25, 35, 50];

  function num(v, d) { const n = Number(v); return isFinite(n) ? n : d; }
  function props(p) { return Object.assign({}, DEF[p.type] || {}, p && p.props && typeof p.props === 'object' ? p.props : {}); }
  function trim(n) { return String(Math.round(n * 100) / 100).replace(/\.0+$/, ''); }
  function ohms(o) { o = num(o, 470); if (!(o > 0)) o = 470; return o >= 1e6 ? trim(o / 1e6) + 'M' : o >= 1000 ? trim(o / 1000) + 'k' : trim(o); }
  function volts(v) { return Math.abs(num(v, 0)); }
  function name(p) { const l = String(p.label || '').trim(); return (l || TE[p.type] || p.type) + (p.id ? ' (' + p.id + ')' : ''); }
  function railName(v) { v = volts(v); if (Math.abs(v - 3.3) < 0.05) return '3V3'; return '+' + trim(v) + 'V'; }

  /**
   * One lab part → {pre, lib, pins: {term: pad}, val?, color?, fp?, padNames?, note} or null (not on a PCB).
   * Pads follow the studio's library (pcbparts.js): an LED's 1 = K, 2 = A; a slide switch's 2 = C (common).
   */
  function map(p) {
    const o = props(p), t = p.type;
    switch (t) {
      case 'resistor': return { pre: 'R', lib: 'res', pins: { a: 1, b: 2 }, val: ohms(o.ohm), note: 'రెసిస్టర్ ' + ohms(o.ohm) + 'Ω' };
      case 'capacitor': {
        const uf = num(o.uf, 1000);
        if (uf < 1) {
          const nf = Math.round(uf * 1000);
          return { pre: 'C', lib: 'cer', pins: { '+': 1, '-': 2 }, val: nf === 100 ? '100nF (104)' : nf + 'nF', note: 'చిన్న కెపాసిటర్ ' + nf + 'nF' };
        }
        const cv = CAP_V.find((x) => x >= volts(o.v)) || 50;
        return { pre: 'C', lib: 'elec', pins: { '+': 1, '-': 2 }, val: trim(uf) + 'uF ' + cv + 'V', note: 'కెపాసిటర్ ' + trim(uf) + 'uF (+ కాలు గుర్తు చూసి)' };
      }
      case 'led': {
        const c = String(o.color || 'red').toLowerCase();
        return { pre: 'D', lib: 'led5', pins: { '+': 2, '-': 1 }, color: ['red', 'green', 'blue', 'yellow', 'white'].indexOf(c) >= 0 ? c : 'red', note: 'LED (పొడవు కాలు A = +)' };
      }
      case 'diode': return { pre: 'D', lib: 'd4007', pins: { '+': 2, '-': 1 }, note: 'డయోడ్ 1N4007 (గీత ఉన్న వైపు K)' };
      case 'npn': return { pre: 'Q', lib: 'bc547', pins: { c: 1, b: 2, e: 3 }, note: 'ట్రాన్సిస్టర్ BC547 (100mA వరకు)' };
      case 'pot': return { pre: 'RV', lib: 'pot', pins: { a: 1, w: 2, b: 3 }, val: ohms(o.ohm), note: 'నాబ్ ' + ohms(o.ohm) };
      case 'regulator': {
        const v = volts(o.v);
        if (Math.abs(v - 3.3) < 0.05) return { pre: 'U', lib: 'r3v3', pins: { gnd: 1, out: 2, in: 3 }, note: '3.3V రెగ్యులేటర్ LD1117V33', out: '3V3' };
        return { pre: 'U', lib: 'r7805', pins: { in: 1, gnd: 2, out: 3 }, note: '5V రెగ్యులేటర్ 7805' + (Math.abs(v - 5) > 0.05 ? ' (⚠️ ల్యాబ్‌లో ' + trim(v) + 'V ఉంది; 7805 ఇచ్చేది 5V మాత్రమే)' : ''), out: '+5V' };
      }
      case 'pushbtn': return { pre: 'SW', lib: 'btn', pins: { a: 1, b: 2 }, note: 'పుష్ బటన్ (50mA వరకు సిగ్నల్‌కి)' };
      case 'switch': return { pre: 'SW', lib: 'slide', pins: { a: 2, b: 1 }, note: 'స్లైడ్ స్విచ్ (0.3A వరకు)' };
      case 'switch2': return { pre: 'SW', lib: 'slide', pins: { c: 2, 1: 1, 2: 3 }, note: 'టూ-వే స్లైడ్ స్విచ్' };
      case 'fuse': case 'mcb': return { pre: 'F', lib: 'ptc', pins: { a: 1, b: 2 }, note: 'రీసెట్ ఫ్యూజ్ (PTC): ల్యాబ్‌లో ' + trim(num(o.amp, 6)) + 'A; అంత హోల్డ్ కరెంట్ ఉన్నది కొనండి' };
      case 'buzzer': return { pre: 'BZ', lib: 'buzzer', pins: { a: 1, b: 2 }, note: 'బజర్ (+ కాలు 1)' };
      case 'battery': case 'cell':
        return { pre: 'J', lib: 'term2', pins: { '+': 1, '-': 2 }, padNames: { 1: railName(o.v).replace('+', '') + '+', 2: 'GND' }, note: (t === 'cell' ? 'సెల్' : 'బ్యాటరీ') + ' ' + trim(volts(o.v)) + 'V వైర్లు ఇక్కడ (బోర్డ్ బయట)', src: true };
      case 'solar': return { pre: 'J', lib: 'term2', pins: { '+': 1, '-': 2 }, padNames: { 1: 'SOL+', 2: 'SOL-' }, note: 'సోలార్ ప్యానెల్ వైర్లు ఇక్కడ (బోర్డ్ బయట)', src: true };
      case 'charger': return { pre: 'J', lib: 'dcjack', pins: { '+': 1, '-': 2 }, note: trim(volts(o.v)) + 'V అడాప్టర్ జాక్: అడాప్టర్ గోడ సాకెట్‌లోనే, బోర్డ్‌కి DC మాత్రమే', src: true };
      case 'phone': return { pre: 'J', lib: 'usba', pins: { '+': 1, '-': 4 }, note: 'ఫోన్ USB కేబుల్ ఇక్కడ (USB-A సాకెట్, 5V)' };
      case 'ldr': return { pre: 'J', lib: 'hdr', fp: 'HDR1x2', pins: { a: 1, b: 2 }, padNames: { 1: 'LDR', 2: 'LDR' }, note: 'LDR ఈ 2 రంధ్రాల్లోకి (లేదా వైర్లతో)' };
      case 'relay': return { pre: 'J', lib: 'hdr', fp: 'HDR1x5', pins: { c1: 1, c2: 2, com: 3, no: 4, nc: 5 }, padNames: { 1: 'C1', 2: 'C2', 3: 'COM', 4: 'NO', 5: 'NC' }, note: 'రిలే (' + trim(volts(o.v)) + 'V కాయిల్) వైర్లతో, బోర్డ్ బయట; 24V లోపు DC లోడ్లకే' };
      default:
        if (WIRED[t]) return { pre: 'J', lib: 'term2', pins: { a: 1, b: 2 }, padNames: { 1: WIRED[t][0], 2: WIRED[t][1] }, note: (TE[t] || t) + ' వైర్లు ఇక్కడ (బోర్డ్ బయట)' };
        return null;
    }
  }

  /** The wires' joins: terminal "id.term" → its group (union-find). */
  function groups(parts, wires) {
    const up = new Map();
    const find = (x) => { if (!up.has(x)) up.set(x, x); let r = x; while (up.get(r) !== r) r = up.get(r); let y = x; while (up.get(y) !== r) { const n = up.get(y); up.set(y, r); y = n; } return r; };
    const ids = new Set(parts.map((p) => p.id));
    (wires || []).forEach((w) => {
      if (!w) return;
      const a = String(w.a || ''), b = String(w.b || '');
      if (!a || !b || !ids.has(a.split('.')[0]) || !ids.has(b.split('.')[0])) return;
      up.set(find(a), find(b));
    });
    return find;
  }

  /** {ans: the studio's circuit data, lines: notes for him, left: what stayed off the board} or {err}. */
  function convert(board) {
    const parts = (board && Array.isArray(board.parts) ? board.parts : []).filter((p) => p && p.id && p.type);
    const wires = board && Array.isArray(board.wires) ? board.wires : [];
    if (!parts.length) return { err: 'సర్క్యూట్ ల్యాబ్‌లో బోర్డ్ ఖాళీగా ఉంది Sir. ముందు ఒక సర్క్యూట్ గీసి, అప్పుడు 🔌 PCB గా నొక్కండి.' };

    // 1) safety: 230 V things, more than 24 V
    const house = parts.filter((p) => HOUSE.has(p.type));
    if (house.length) {
      return { err: '🚫 ఈ సర్క్యూట్‌లో 230V ఇంటి కరెంట్ భాగాలు ఉన్నాయి Sir: ' + house.slice(0, 6).map(name).join(', ') + (house.length > 6 ? '…' : '') +
        '. PCB స్టూడియో 24V లోపు DC బోర్డ్‌లు మాత్రమే చేస్తుంది (ఇంటి వైరింగ్ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ పని). బ్యాటరీ / అడాప్టర్ భాగం మాత్రమే ఉన్న సర్క్యూట్ అయితే చేస్తాను.' };
    }
    const high = parts.filter((p) => ['battery', 'cell', 'solar', 'charger', 'relay', 'regulator'].indexOf(p.type) >= 0 && volts(props(p).v) > 24);
    if (high.length) return { err: '🚫 ' + high.slice(0, 4).map((p) => name(p) + ' ' + trim(volts(props(p).v)) + 'V').join(', ') + ': 24V కంటే ఎక్కువ Sir. ఈ స్టూడియోలో 24V వరకు DC బోర్డ్‌లు మాత్రమే.' };

    // 2) the wall side: mains and the charger's in-l / in-n, and the switches / fuses between them, stay off the board
    const find = groups(parts, wires);
    const acRoots = new Set();
    parts.forEach((p) => {
      if (p.type === 'mains') { acRoots.add(find(p.id + '.l')); acRoots.add(find(p.id + '.n')); }
      if (p.type === 'charger') { acRoots.add(find(p.id + '.in-l')); acRoots.add(find(p.id + '.in-n')); }
    });
    const TERMS = { switch: ['a', 'b'], switch2: ['c', '1', '2'], fuse: ['a', 'b'], mcb: ['a', 'b'], pushbtn: ['a', 'b'] };
    const wall = new Set(parts.filter((p) => p.type === 'mains').map((p) => p.id));
    for (let grew = true; grew;) {
      grew = false;
      parts.forEach((p) => {
        if (!PASS.has(p.type) || wall.has(p.id)) return;
        const ts = TERMS[p.type];
        if (ts.some((t) => acRoots.has(find(p.id + '.' + t)))) { wall.add(p.id); ts.forEach((t) => acRoots.add(find(p.id + '.' + t))); grew = true; }
      });
    }
    // anything else touching the wall side is a 230 V join: not made
    const bad = [];
    parts.forEach((p) => {
      if (wall.has(p.id)) return;
      const m = map(p);
      if (!m) return;
      const ts = Object.keys(m.pins);
      if (ts.some((t) => acRoots.has(find(p.id + '.' + t)))) bad.push(name(p));
    });
    if (bad.length) return { err: '🚫 ' + bad.slice(0, 5).join(', ') + ': 230V వైపు కలిసి ఉన్నాయి Sir. PCB మీద 230V ఉండకూడదు; ఈ బోర్డ్ చేయలేదు. అడాప్టర్ (ఛార్జర్) DC వైపు మాత్రమే బోర్డ్ కి రావాలి.' };

    // 3) parts → the studio's parts
    const out = [], refOf = new Map(), padOf = new Map(), used = new Set(), left = [], amps = new Map(), lines = [];
    const next = (pre) => { let i = 1; while (used.has(pre + i)) i++; used.add(pre + i); return pre + i; };
    parts.forEach((p) => {
      if (wall.has(p.id)) { left.push(name(p) + ': గోడ వైపు (బోర్డ్ మీద కాదు)'); return; }
      if (p.type === 'mains') return;
      const m = map(p);
      if (!m) { left.push(name(p) + ': PCB లో ఇలాంటి భాగం లేదు'); return; }
      const ref = next(m.pre);
      refOf.set(p.id, ref);
      const q = { ref, lib: m.lib, note: ((p.label ? String(p.label).trim() + ': ' : '') + m.note).slice(0, 160) };
      if (m.val) q.val = m.val;
      if (m.color) q.color = m.color;
      if (m.fp) q.fp = m.fp;
      if (m.padNames) { q.pins = {}; Object.keys(m.padNames).forEach((k) => { q.pins[k] = m.padNames[k]; }); }
      out.push(q);
      Object.keys(m.pins).forEach((t) => padOf.set(p.id + '.' + t, { ref, pad: m.pins[t], src: m.src ? (t === '+' ? 'pos' : t === '-' ? 'neg' : '') : '', out: m.out && t === 'out' ? m.out : '', v: props(p).v, type: p.type }));
      // a load's current, for the tracks' width
      const o = props(p);
      if (o.w && o.v && (WIRED[p.type] || p.type === 'phone' || p.type === 'buzzer')) amps.set(p.id, num(o.w, 0) / Math.max(0.5, volts(o.v)));
    });

    // 4) the joins → nets
    const byRoot = new Map();
    padOf.forEach((v, term) => { const r = find(term); if (!byRoot.has(r)) byRoot.set(r, []); byRoot.get(r).push(Object.assign({ term }, v)); });
    const nets = [], names = new Set();
    let sig = 0;
    const uniq = (n) => { let k = n, i = 2; while (names.has(k)) k = n + i++; names.add(k); return k; };
    byRoot.forEach((list, r) => {
      const pins = [];
      list.forEach((x) => { const s = x.ref + '.' + x.pad; if (pins.indexOf(s) < 0) pins.push(s); });
      if (pins.length < 2) return;
      let n = '';
      if (list.some((x) => x.src === 'neg')) n = 'GND';
      else if (list.some((x) => x.out)) n = list.find((x) => x.out).out;
      else if (list.some((x) => x.src === 'pos')) { const s = list.find((x) => x.src === 'pos'); n = s.type === 'solar' ? 'SOL' : s.type === 'charger' ? 'VIN' : railName(s.v); }
      else n = 'N' + (++sig);
      const net = { n: uniq(n), p: pins };
      let a = 0;
      list.forEach((x) => { const id = x.term.split('.')[0]; if (amps.has(id)) a = Math.max(a, amps.get(id)); });
      if (a > 0.05) net.a = Math.round(a * 100) / 100;
      nets.push(net);
    });
    if (!out.length) return { err: 'ఈ సర్క్యూట్‌లో PCB మీద పెట్టగల భాగాలు లేవు Sir.' };
    if (!nets.length) return { err: 'ఈ సర్క్యూట్‌లో భాగాలు వైర్లతో కలపలేదు Sir. ల్యాబ్‌లో వైర్లు కలిపి మళ్ళీ నొక్కండి.' };

    // 5) a word for him
    const offBoard = out.filter((q) => q.lib === 'term2' || q.lib === 'dcjack' || q.lib === 'usba' || q.lib === 'hdr').length;
    if (offBoard) lines.push('🔗 బ్యాటరీ, లైట్లు, మోటార్ లాంటివి బోర్డ్ బయటే ఉంటాయి: బోర్డ్ మీద వాటి వైర్లకి ' + offBoard + ' కనెక్టర్లు పెట్టాను.');
    if (out.some((q) => q.lib === 'ptc')) lines.push('🧯 ల్యాబ్ ఫ్యూజ్ / MCB → బోర్డ్ మీద రీసెట్ ఫ్యూజ్ (PTC).');
    if (out.some((q) => q.lib === 'slide')) lines.push('🎚️ స్విచ్ → స్లైడ్ స్విచ్ (0.3A వరకు). ఎక్కువ కరెంట్ లోడ్‌కి బోర్డ్ బయట పెద్ద స్విచ్ వాడండి.');
    const big = nets.filter((n) => n.a >= 3);
    if (big.length) lines.push('⚡ ఎక్కువ కరెంట్ దారులు (' + big.map((n) => n.n + ' ' + n.a + 'A').join(', ') + '): స్టూడియో దారుల వెడల్పు దాని ప్రకారం వేస్తుంది; ✅ చెక్ చూడండి.');
    const title = String(board.title || '').trim() || 'సర్క్యూట్ ల్యాబ్ బోర్డ్';
    return {
      ans: { title: title.slice(0, 40), silk: 'JARVIS LAB', say: 'సర్క్యూట్ ల్యాబ్ నుంచి: ' + out.length + ' భాగాలు బోర్డ్ మీద.', parts: out, nets },
      lines, left,
    };
  }

  const api = { convert, map, ohms };
  root.PcbFromLab = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
