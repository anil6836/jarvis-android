// ⚡ Jarvis PCB studio — the simulator (Anil, 24 Nov, Build 3A feature 8: "PCB ఆర్డర్ ముందే ఫోన్‌లో నడిపించి చూడు").
// Our own code, ₹0, offline, never the AI's: the board's parts and nets become a small circuit (node voltages solved the
// school way, Kirchhoff + Ohm), stepped in time for the 555 / capacitors. A learning model, not a lab instrument: an LED is a
// fixed drop + a little resistance, a transistor a base diode × gain or "full on", a regulator holds its volts until its input
// is too low, a module is a small load at the volts it wants. Every number here is the same every time for the same board.
'use strict';
(function (root) {
  const P = root.PcbParts || (typeof require !== 'undefined' ? require('./pcbparts.js') : null);
  const C = root.PcbCore || (typeof require !== 'undefined' ? require('./pcbcore.js') : null);

  // ---------------------------------------------------------------- numbers the parts use

  const LED = { red: [1.9, 0.02, 0.03], yellow: [2.0, 0.02, 0.03], orange: [2.0, 0.02, 0.03], green: [2.1, 0.02, 0.03], blue: [3.0, 0.02, 0.03], white: [3.0, 0.02, 0.03],
    ir: [1.25, 0.05, 0.1] }; // [volts it drops, current it likes at most, current that burns it]
  const LED_RS = 12, GLEAK = 1e-9, RBE = 25, BETA = 200, VBE = 0.65, VCE_SAT = 0.2, RCE_SAT = 2;
  const BAT9_MAH = 500;
  // modules that plug into a header: what volts they want and how much they take (from the words on the part / the AI's note)
  const MODS = [
    // (KCX_BT003: 3.3–5.0 V, 35 mA, stereo line out — Robu's page and the module's manual, read 24 Nov 2026)
    { re: /KCX[_ -]?BT[_ -]?0{0,2}3/i, te: 'KCX_BT003 బ్లూటూత్ రిసీవర్', v: [3.3, 5.0], ma: 35, why: 'KCX_BT003 కి 3.3–5.0 V' },
    { re: /BK8000L|BK3254|KCX[_ ]?BT|bluetooth\s*audio|BT\s*audio|బ్లూటూత్\s*ఆడియో/i, te: 'బ్లూటూత్ ఆడియో మాడ్యూల్', v: [3.3, 4.2], ma: 40,
      why: 'BK8000L లాంటి బ్లూటూత్ ఆడియో మాడ్యూల్స్‌కి 3.3–4.2 V మాత్రమే' },
    { re: /HC-?0[56]/i, te: 'HC-05 / HC-06 బ్లూటూత్', v: [3.6, 6], ma: 40 },
    { re: /DHT\s*(11|22)?|ఉష్ణోగ్రత/i, te: 'DHT సెన్సార్', v: [3.3, 5.5], ma: 1.5 },
    { re: /soil|మట్టి/i, te: 'మట్టి తేమ సెన్సార్', v: [3.3, 5.5], ma: 5 },
    { re: /OLED|SSD1306/i, te: 'OLED స్క్రీన్', v: [3.3, 5.5], ma: 20 },
    { re: /BMP280|BME280/i, te: 'BMP280 సెన్సార్', v: [3.3, 5.5], ma: 1 },
    { re: /relay|రిలే/i, te: 'రిలే మాడ్యూల్', v: [4.5, 5.5], ma: 75 },
    { re: /DFPlayer|MP3/i, te: 'MP3 మాడ్యూల్', v: [3.2, 5.0], ma: 25 },
    { re: /I2C/i, te: 'I2C మాడ్యూల్', v: [3.3, 5.5], ma: 10 }
  ];

  const num = (s, re) => { const m = re.exec(String(s || '')); return m ? parseFloat(m[1]) : NaN; };
  /** "10uF 16V" → 1e-5 F; "100nF (104)", "104" → 1e-7 F. */
  function farads(v) {
    const s = String(v || '').replace(/µ/g, 'u');
    let m = /([\d.]+)\s*([pnum])F/i.exec(s);
    if (m) return parseFloat(m[1]) * { p: 1e-12, n: 1e-9, u: 1e-6, m: 1e-3 }[m[2].toLowerCase()];
    m = /\b(\d)(\d)(\d)\b/.exec(s);
    if (m) return (parseInt(m[1] + m[2], 10) * Math.pow(10, parseInt(m[3], 10))) * 1e-12;
    return NaN;
  }
  /** A rating in volts written on a part ("16V", "25 V"). */
  function ratedV(v) { return num(v, /(\d+(?:\.\d+)?)\s*V(?![a-z])/i); }
  /** "500mA" → 0.5, "PTC 1.85A" → 1.85. */
  function ratedA(v) { const s = String(v || ''); const ma = num(s, /(\d+(?:\.\d+)?)\s*mA/i); return ma >= 0 ? ma / 1000 : num(s, /(\d+(?:\.\d+)?)\s*A\b/i); }
  /** The volts a name means: "+12V" 12, "3V3" 3.3, "5V" 5, "9V IN" 9. */
  function voltsOf(s) {
    const t = String(s || '');
    let m = /(\d+)V(\d+)/i.exec(t);
    if (m) return parseFloat(m[1] + '.' + m[2]);
    m = /(\d+(?:\.\d+)?)\s*V(?![a-z])/i.exec(t);
    return m ? parseFloat(m[1]) : NaN;
  }
  function colourOf(pt) {
    if (pt.lib === 'irled') return 'ir';
    const c = String(pt.color || pt.val || '').toLowerCase();
    return Object.keys(LED).find((k) => c.indexOf(k) >= 0) || 'red';
  }
  const fmtV = (v) => (Math.abs(v) < 0.05 ? '0' : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2).replace(/0$/, '')) + ' V';
  function fmtA(a) { const x = Math.abs(a); return x >= 1 ? (Math.round(x * 100) / 100) + ' A' : x >= 0.001 ? (x >= 0.01 ? Math.round(x * 1000) : Math.round(x * 10000) / 10) + ' mA' : x >= 1e-6 ? Math.round(x * 1e6) + ' µA' : '0'; }
  const fmtW = (w) => (w >= 1 ? (Math.round(w * 100) / 100) + ' W' : Math.round(w * 1000) + ' mW');

  // ---------------------------------------------------------------- the board → a circuit

  const CONN = { term2: 1, hdr: 1, fhdr: 1 };
  /**
   * The circuit of a board. opts: { src: {key: volts}, ctl: {key: value} } (what he chose on the screen).
   * → { err } or { nodes, el, parts, sources, controls, notes, gnd }
   */
  function setup(D, opts) {
    opts = opts || {};
    const src = opts.src || {}, ctl = opts.ctl || {};
    if (!D || !Array.isArray(D.parts) || !Array.isArray(D.nets)) return { err: 'బోర్డ్ లేదు' };
    const netOf = new Map();
    D.nets.forEach((n) => (n.p || []).forEach((p) => netOf.set(p, n.n)));
    const gnd = (D.nets.find((n) => C.isGnd(n.n)) || {}).n;
    if (!gnd) return { err: 'ఈ బోర్డ్‌లో GND (−) దారి లేదు: కరెంట్ తిరిగి వెళ్ళే దారి లేకుండా నడిపించలేను.' };
    const nodes = new Map([[gnd, 0]]);
    const idx = (net) => { if (net == null) return -1; if (!nodes.has(net)) nodes.set(net, nodes.size); return nodes.get(net); };
    D.nets.forEach((n) => idx(n.n));
    let free = 0;
    const pin = (ref, k) => { const n = netOf.get(ref + '.' + k); return n == null ? idx('~' + ref + '.' + k + '#' + (free++)) : idx(n); };
    const netAt = (ref, k) => netOf.get(ref + '.' + k);
    const el = [], parts = {}, sources = [], controls = [], notes = [];
    const byRef = new Map(D.parts.map((p) => [p.ref, p]));
    const pinsOf = (pt) => P.pinsOf(pt) || {};
    const role = (pt, name) => Object.keys(pinsOf(pt)).find((k) => String(pinsOf(pt)[k]).toUpperCase() === name);
    const add = (e) => { el.push(e); return e; };

    // nets something on the board drives (a regulator's output, a module's own supply)
    const driven = new Set();
    D.parts.forEach((pt) => {
      if (pt.lib === 'r7805' || pt.lib === 'r3v3') { const o = netAt(pt.ref, role(pt, 'OUT') || '3'); if (o) driven.add(o); }
      if (pt.lib === 'buck') { const o = netAt(pt.ref, role(pt, 'OUT+') || '4'); if (o) driven.add(o); }
      if (pt.lib === 'esp32') { const o = netAt(pt.ref, '30'); if (o) driven.add(o); }
    });
    // an ESP32 board is powered by its own USB unless something on this board feeds its VIN (a regulator, a power connector)
    const usb = new Set();
    D.parts.forEach((pt) => {
      if (pt.lib !== 'esp32') return;
      const v = netAt(pt.ref, '15');
      const fed = v && (driven.has(v) || D.nets.some((n) => n.n === v && n.p.some((p) => { const q = byRef.get(p.split('.')[0]); return q && CONN[q.lib] && Object.keys(pinsOf(q)).some((k) => netAt(q.ref, k) === gnd); })));
      if (!fed) { usb.add(pt.ref); if (v) driven.add(v); }
    });

    // which connector brings the power in: one per power net nothing on the board drives
    const isConn = (pt) => !!CONN[pt.lib];
    const supplyWords = /\bIN\b|BAT|POWER|PWR|VIN|SUPPLY|ADAPT|బ్యాటరీ|అడాప్టర్|సప్లై|బైక్|USB/i;
    const moduleWords = (pt) => MODS.some((m) => m.re.test(String(pt.note || '') + ' ' + String(pt.val || '')));
    const feeds = new Map(); // net → {pt, k}
    D.nets.forEach((n) => {
      if (C.isGnd(n.n) || driven.has(n.n)) return;
      const cands = [];
      (n.p || []).forEach((p) => {
        const [ref, k] = p.split('.'), pt = byRef.get(ref);
        if (!pt || !isConn(pt)) return;
        const pins = Object.keys(pinsOf(pt));
        if (!pins.some((q) => netAt(ref, q) === gnd)) return;
        const named = String(pinsOf(pt)[k] || '');
        if (!C.isPower(n.n) && !C.isPower(named) && !(voltsOf(named) > 0)) return;
        let score = 0;
        if (supplyWords.test(String(pt.val || '') + ' ' + String(pt.note || '')) || supplyWords.test(named)) score += 4;
        if (pt.lib === 'term2') score += 2;
        if (moduleWords(pt)) score -= 3;
        if (pt.lib === 'fhdr') score -= 1;
        cands.push({ pt, k, score });
      });
      if (!cands.length) return;
      cands.sort((a, b) => b.score - a.score);
      feeds.set(n.n, cands[0]);
    });
    // a module's socket (Bluetooth, sensor, screen…) takes power, it does not bring it: when a real power connector feeds the
    // board somewhere (his J1 → fuse → diode → switch → +5V → the module), the module's power net is not a 🔋 of its own
    if ([...feeds.values()].some((f) => !moduleWords(f.pt))) [...feeds].forEach(([net, f]) => { if (moduleWords(f.pt)) feeds.delete(net); });
    const feedPins = new Set();
    feeds.forEach((f) => feedPins.add(f.pt.ref + '.' + f.k));

    // ---- the sources he can change
    feeds.forEach((f, net) => {
      const pt = f.pt, named = String(pinsOf(pt)[f.k] || '');
      let v0 = voltsOf(named);
      if (!(v0 > 0)) v0 = voltsOf(net);
      if (!(v0 > 0)) v0 = voltsOf(pt.val);
      if (!(v0 > 0)) v0 = /USB/i.test(String(pt.val) + net) ? 5 : 12;
      v0 = Math.min(24, v0);
      const kind = Math.abs(v0 - 9) < 0.6 ? '9v' : Math.abs(v0 - 12) < 1.5 ? 'bike' : Math.abs(v0 - 5) < 0.6 ? 'usb' : v0 < 4 ? 'logic' : 'dc';
      const key = pt.ref + '.' + f.k;
      const v = src[key] > 0 && src[key] <= 24 ? +src[key] : v0;
      const r = kind === '9v' ? 1.5 : kind === 'bike' ? 0.05 : kind === 'usb' ? 0.15 : kind === 'logic' ? 0.1 : 0.3;
      const gk = Object.keys(pinsOf(pt)).find((q) => netAt(pt.ref, q) === gnd);
      const e = add({ k: 'V', a: idx(net), b: pin(pt.ref, gk), v, r, ref: pt.ref, src: key });
      sources.push({ key, ref: pt.ref, pin: f.k, net, v, v0, kind, el: e, te: pt.ref + (pt.val ? ' (' + pt.val + ')' : '') + ' · ' + net });
    });

    // ---- every part
    D.parts.forEach((pt) => {
      const ref = pt.ref, info = parts[ref] = { ref, lib: pt.lib, els: [] };
      const two = (k) => { const e = add(Object.assign({ ref }, k)); info.els.push(e); return e; };
      switch (pt.lib) {
        case 'res': {
          const r = P.ohms(pt.val);
          two({ k: 'R', a: pin(ref, '1'), b: pin(ref, '2'), r: r > 0 ? r : 1000 });
          info.kind = 'res'; info.rated = 0.25;
          if (!(r > 0)) notes.push({ sev: 'warn', ref, te: ref + ': విలువ "' + (pt.val || '') + '" చదవలేకపోయాను; 1k అనుకున్నాను.' });
          break;
        }
        case 'cer': case 'elec': {
          let f = farads(pt.val);
          if (!(f > 0)) f = pt.lib === 'elec' ? 10e-6 : 100e-9;
          two({ k: 'Cap', a: pin(ref, '1'), b: pin(ref, '2'), c: f, vp: 0 });
          info.kind = 'cap'; info.polar = pt.lib === 'elec'; info.rated = ratedV(pt.val) || (pt.lib === 'cer' ? 50 : NaN);
          break;
        }
        case 'led5': case 'led3': case 'irled': {
          const col = colourOf(pt), L = LED[col];
          two({ k: 'D', a: pin(ref, '2'), b: pin(ref, '1'), vf: L[0], rs: LED_RS, on: false });
          info.kind = 'led'; info.col = col; info.good = L[1]; info.burn = L[2];
          break;
        }
        case 'd4007': case 'd5822':
          two({ k: 'D', a: pin(ref, '2'), b: pin(ref, '1'), vf: pt.lib === 'd5822' ? 0.45 : 0.7, rs: 0.05, on: false });
          info.kind = 'diode'; info.max = pt.lib === 'd5822' ? 3 : 1;
          break;
        case 'bc547':
          two({ k: 'Q', c: pin(ref, '1'), b: pin(ref, '2'), e: pin(ref, '3'), m: 'off' });
          info.kind = 'npn'; info.max = 0.1;
          break;
        case 'ne555': {
          const g = pin(ref, '1'), vcc = pin(ref, '8'), ct = pin(ref, '5');
          two({ k: 'R', a: vcc, b: ct, r: 5000, inner: true });
          two({ k: 'R', a: ct, b: g, r: 10000, inner: true });
          two({ k: 'R', a: vcc, b: g, r: 2000, inner: true });
          const t = two({ k: 'T555', g, trig: pin(ref, '2'), out: pin(ref, '3'), rst: pin(ref, '4'), ctl: ct, thr: pin(ref, '6'), dis: pin(ref, '7'), vcc, q: 1 });
          info.kind = '555'; info.t = t;
          break;
        }
        case 'r7805': case 'r3v3': {
          const lo = pt.lib === 'r3v3';
          const i = pin(ref, role(pt, 'IN') || (lo ? '3' : '1')), g = pin(ref, role(pt, 'GND') || (lo ? '1' : '2')), o = pin(ref, role(pt, 'OUT') || (lo ? '2' : '3'));
          two({ k: 'R', a: i, b: g, r: 1800, inner: true });
          const e = two({ k: 'Reg', i, g, o, vset: lo ? 3.3 : 5.0, drop: lo ? 1.1 : 2.0, m: 'reg' });
          info.kind = 'reg'; info.reg = e; info.max = lo ? 0.8 : 1.0; info.vmax = lo ? 15 : 35; info.vset = e.vset; info.drop = e.drop;
          break;
        }
        case 'buck': {
          const vs = voltsOf(pt.val);
          const e = two({ k: 'Buck', i: pin(ref, role(pt, 'IN+') || '1'), ig: pin(ref, role(pt, 'IN-') || '2'), og: pin(ref, role(pt, 'OUT-') || '3'), o: pin(ref, role(pt, 'OUT+') || '4'),
            vset: vs > 0 && vs < 30 ? (Math.abs(vs - 5) < 0.3 ? 5.1 : vs) : 5.1, m: 'reg', iin: 0 });
          info.kind = 'buck'; info.reg = e; info.max = 2; info.vmax = 40;
          break;
        }
        case 'buzzer':
          two({ k: 'R', a: pin(ref, '1'), b: pin(ref, '2'), r: 480 });
          info.kind = 'buzzer';
          break;
        case 'ptc': {
          const hold = ratedA(pt.val) > 0 ? ratedA(pt.val) : 0.5;
          two({ k: 'Fuse', a: pin(ref, '1'), b: pin(ref, '2'), hold, trip: false });
          info.kind = 'fuse'; info.hold = hold;
          break;
        }
        case 'slide': {
          const key = 'sw:' + ref, pos = ctl[key] === 'B' ? 'B' : 'A';
          const c = pin(ref, role(pt, 'C') || '2');
          two({ k: 'R', a: c, b: pin(ref, role(pt, 'A') || '1'), r: pos === 'A' ? 0.03 : 1e9 });
          two({ k: 'R', a: c, b: pin(ref, role(pt, 'B') || '3'), r: pos === 'B' ? 0.03 : 1e9 });
          controls.push({ key, ref, te: ref + ' స్లైడ్ స్విచ్', opts: [['A', 'C–A వైపు'], ['B', 'C–B వైపు']], val: pos });
          info.kind = 'switch'; info.pos = pos; info.max = 0.3;
          break;
        }
        case 'usba': {
          const key = 'phone:' + ref, on = ctl[key] !== 'off';
          if (on) two({ k: 'Load', a: pin(ref, '1'), b: pin(ref, '4'), r: 5, vmin: 4.4 });
          controls.push({ key, ref, te: ref + ' USB లో ఫోన్', opts: [['on', '📱 పెట్టాను'], ['off', 'ఖాళీ']], val: on ? 'on' : 'off' });
          info.kind = 'usb'; info.phone = on;
          break;
        }
        case 'vs1838': {
          const vc = pin(ref, role(pt, 'VCC') || '3'), g = pin(ref, role(pt, 'GND') || '2');
          two({ k: 'R', a: vc, b: g, r: 3300 });
          two({ k: 'Pull', a: pin(ref, role(pt, 'OUT') || '1'), from: vc, r: 30000 });
          info.kind = 'module'; info.want = [2.7, 5.5]; info.vpin = vc; info.gpin = g; info.te = 'IR రిసీవర్'; info.sure = true;
          break;
        }
        case 'mic':
          two({ k: 'R', a: pin(ref, '1'), b: pin(ref, '2'), r: 6000 });
          info.kind = 'mic';
          break;
        case 'jack35':
          info.kind = 'jack';
          break;
        case 'esp32': {
          const g1 = pin(ref, '14');
          if (usb.has(ref)) {
            const e1 = two({ k: 'V', a: pin(ref, '15'), b: g1, v: 5.0, r: 0.3 });
            const e2 = two({ k: 'V', a: pin(ref, '30'), b: g1, v: 3.3, r: 0.1 });
            sources.push({ key: ref + '.usb', ref, net: netAt(ref, '15') || netAt(ref, '30'), v: 5, v0: 5, kind: 'esp', fixed: true, el: e1, el2: e2, te: ref + ' ESP32 (దాని USB నుంచి)' });
          } else {
            two({ k: 'R', a: pin(ref, '15'), b: g1, r: 42 });
            const e = two({ k: 'Reg', i: pin(ref, '15'), g: g1, o: pin(ref, '30'), vset: 3.3, drop: 1.1, m: 'reg' });
            info.reg = e;
            info.want = [4.5, 9]; info.vpin = pin(ref, '15'); info.gpin = g1; info.te = 'ESP32 (VIN)'; info.sure = true; info.kind = 'module';
          }
          const names = pinsOf(pt);
          Object.keys(names).forEach((k) => {
            const n = netAt(ref, k);
            if (!n || C.isPower(n) || /^(GND|VIN|3V3|EN)$/i.test(names[k])) return;
            if (!shows(n, ref)) return;
            gpio(ref, k, names[k], n, 3.3, 'gpio');
          });
          info.kind = info.kind || 'esp32';
          break;
        }
        default:
          if (!CONN[pt.lib]) info.kind = 'other';
      }
    });

    const drivenSig = new Set();
    el.forEach((e) => { const nm = (k) => { for (const [name, i] of nodes) if (i === k) return name; return null; }; if (e.k === 'Pull') drivenSig.add(nm(e.a)); if (e.k === 'T555') drivenSig.add(nm(e.out)); });
    // ---- connectors: power in (above), modules on sockets, pins he can set
    D.parts.forEach((pt) => {
      if (!isConn(pt)) return;
      const ref = pt.ref, names = pinsOf(pt), ks = Object.keys(names), info = parts[ref];
      info.kind = 'conn';
      const gk = ks.filter((k) => netAt(ref, k) === gnd);
      const pw = ks.filter((k) => { const n = netAt(ref, k); return n && n !== gnd && !feedPins.has(ref + '.' + k) && (C.isPower(n) || C.isPower(names[k]) || voltsOf(names[k]) > 0); });
      const isFeed = ks.some((k) => feedPins.has(ref + '.' + k));
      const text = String(pt.note || '') + ' ' + String(pt.val || '');
      // a module (sensor, Bluetooth…) sitting on the socket: a small load at its supply pin
      if (gk.length && pw.length && !isFeed) {
        const mod = MODS.find((m) => m.re.test(text));
        const said = saidVolts(String(pt.note || ''));
        const vpin = pw[0];
        const want = said || (mod ? mod.v : null);
        const vnom = want ? Math.min(Math.max(voltsOf(names[vpin]) || want[1], want[0]), want[1]) : (voltsOf(names[vpin]) || 5);
        const ma = mod ? mod.ma : 20;
        add({ k: 'R', a: pin(ref, vpin), b: pin(ref, gk[0]), r: vnom / (ma / 1000), ref });
        Object.assign(info, { kind: 'module', te: mod ? mod.te : 'మాడ్యూల్', want, sure: !!said, why: mod && !said ? mod.why : null, vpin: pin(ref, vpin), gpin: pin(ref, gk[0]), ma, named: !!mod });
      }
      // a two-pin connector that is not the power: whatever he joins between its pins (a button, two nails in soil…)
      const sig = ks.filter((k) => !feedPins.has(ref + '.' + k) && !gk.includes(k) && !pw.includes(k));
      if (ks.length === 2 && !isFeed && info.kind !== 'module') {
        const na = netAt(ref, ks[0]), nb = netAt(ref, ks[1]);
        const useful = (n) => n && (C.isPower(n) || touchesPart(n, ref));
        if (useful(na) && useful(nb) && (touchesPart(na, ref) || touchesPart(nb, ref))) {
          const key = 'btw:' + ref, v = ['open', 'short', '10k'].indexOf(ctl[key]) >= 0 ? ctl[key] : 'open';
          if (v !== 'open') add({ k: 'R', a: pin(ref, ks[0]), b: pin(ref, ks[1]), r: v === 'short' ? 0.1 : 10000, ref });
          const soil = /PROBE|SOIL|మట్టి/i.test(text);
          controls.push({ key, ref, te: ref + (pt.val ? ' (' + pt.val + ')' : '') + ': రెండు పిన్నుల మధ్య',
            opts: [['open', soil ? 'పొడి (ఏమీ లేదు)' : 'తెరిచి'], ['10k', soil ? 'తడి మట్టి (≈10 kΩ)' : '≈10 kΩ'], ['short', 'వైరుతో కలిపి']], val: v });
          info.kind = 'outside';
        }
        return;
      }
      // other pins that reach a part here (not a module's own pins, not a wire something already drives): he can leave them,
      // pull them to 0 V or give them the logic volts
      if (info.kind === 'module') return;
      const hi = logicVolts();
      sig.forEach((k) => { const n = netAt(ref, k); if (n && shows(n, ref) && !C.isPower(n) && !drivenSig.has(n)) gpio(ref, k, names[k], n, hi, 'pin'); });
    });

    /** Does setting this wire change something he can see (more than a pull-up resistor to a supply)? */
    function shows(net, not) {
      const n = D.nets.find((q) => q.n === net);
      return !!n && n.p.some((p) => {
        const [r, k] = p.split('.'), q = byRef.get(r);
        if (r === not || !q || CONN[q.lib] || q.lib === 'jack35') return false;
        if (q.lib === 'res') { const other = netAt(r, k === '1' ? '2' : '1'); if (other && C.isPower(other)) return false; }
        return true;
      });
    }
    function touchesPart(net, not) {
      const n = D.nets.find((q) => q.n === net);
      return !!n && n.p.some((p) => { const r = p.split('.')[0], q = byRef.get(r); return r !== not && q && !CONN[q.lib] && q.lib !== 'jack35'; });
    }
    function logicVolts() {
      const vs = sources.map((s) => s.v).concat(D.nets.filter((n) => /3V3|3\.3V/i.test(n.n)).map(() => 3.3));
      return vs.some((v) => Math.abs(v - 3.3) < 0.2) ? 3.3 : Math.min.apply(null, vs.concat([5]));
    }
    function gpio(ref, k, name, net, hi, kind) {
      const key = kind + ':' + ref + '.' + k, v = ['open', '0', 'hi'].indexOf(ctl[key]) >= 0 ? ctl[key] : 'open';
      if (v !== 'open') add({ k: 'V', a: pin(ref, k), b: 0, v: v === 'hi' ? hi : 0, r: 30, ref, gp: true });
      controls.push({ key, ref, te: ref + ' ' + name + (name !== net ? ' (' + net + ')' : ''), opts: [['open', 'ఏమీ లేదు'], ['0', '0 V'], ['hi', fmtV(hi) + ' (ఆన్)']], val: v });
    }
    function saidVolts(t) {
      const m = /(\d+(?:\.\d+)?|\dV\d)\s*V?\s*(?:–|-|to|నుంచి)\s*(\d+(?:\.\d+)?)\s*V/i.exec(t);
      if (m) { const a = voltsOf(m[1] + 'V'), b = parseFloat(m[2]); if (a > 0 && b > a) return [a, b]; }
      const v = voltsOf(t);
      if (v > 0 && v <= 24) return v < 3.6 ? [3.0, 3.6] : [v * 0.9, v * 1.1];
      return null;
    }

    // a capacitor side that nothing else holds (it only goes out to a module / a cable): its DC level is not known here
    const touch = new Map([[0, 1]]);
    el.forEach((e) => { if (e.k === 'Cap' || (e.k === 'R' && e.r >= 1e8)) return; ['a', 'b', 'c', 'e', 'i', 'o', 'g', 'ig', 'og', 'from', 'vcc', 'out', 'dis', 'ctl'].forEach((k) => { if (typeof e[k] === 'number' && e[k] >= 0) touch.set(e[k], 1); }); });
    el.forEach((e) => { if (e.k === 'Cap') e.flt = !touch.has(e.a) || !touch.has(e.b); });
    const flt = new Set();
    nodes.forEach((i) => { if (!touch.has(i)) flt.add(i); }); // (a wire that only goes out to a module / cable: its volts are not known here)
    if (!sources.length) return { err: 'కరెంట్ ఎక్కడి నుంచి వస్తుందో కనిపించలేదు: + పిన్ ఒకటి, GND పిన్ ఒకటి ఉన్న కనెక్టర్ (బ్యాటరీ / అడాప్టర్ / USB) లేదు.' };
    return { nodes, n: nodes.size, el, parts, sources, controls, notes, gnd, D, flt, t: 0, tran: false };
  }

  // ---------------------------------------------------------------- solving

  function gauss(A, b) {
    const n = b.length;
    for (let c = 0; c < n; c++) {
      let p = c, m = Math.abs(A[c][c]);
      for (let r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > m) { m = Math.abs(A[r][c]); p = r; }
      if (m < 1e-18) { A[c][c] = 1e-12; p = c; }
      if (p !== c) { const t = A[p]; A[p] = A[c]; A[c] = t; const u = b[p]; b[p] = b[c]; b[c] = u; }
      const d = A[c][c];
      for (let r = c + 1; r < n; r++) {
        const f = A[r][c] / d;
        if (!f) continue;
        const Ar = A[r], Ac = A[c];
        for (let k = c; k < n; k++) Ar[k] -= f * Ac[k];
        b[r] -= f * b[c];
      }
    }
    const x = new Array(n).fill(0);
    for (let r = n - 1; r >= 0; r--) { let s = b[r]; for (let k = r + 1; k < n; k++) s -= A[r][k] * x[k]; x[r] = s / A[r][r]; }
    return x;
  }

  /** One linear solve with every part in its present state. dt: a time step (capacitors), or 0 for the steady state. */
  function linear(S, dt) {
    const n = S.n - 1, A = [], b = new Array(n).fill(0);
    for (let i = 0; i < n; i++) { A.push(new Array(n).fill(0)); A[i][i] = GLEAK; }
    const G = (p, q, g) => { if (p > 0) A[p - 1][p - 1] += g; if (q > 0) A[q - 1][q - 1] += g; if (p > 0 && q > 0) { A[p - 1][q - 1] -= g; A[q - 1][p - 1] -= g; } };
    const I = (p, q, i) => { if (p > 0) b[p - 1] -= i; if (q > 0) b[q - 1] += i; }; // current i flows from p to q through the source
    const VCCS = (p, q, cp, cq, gm) => { // current gm·(V(cp) − V(cq)) from p to q
      if (p > 0) { if (cp > 0) A[p - 1][cp - 1] += gm; if (cq > 0) A[p - 1][cq - 1] -= gm; }
      if (q > 0) { if (cp > 0) A[q - 1][cp - 1] -= gm; if (cq > 0) A[q - 1][cq - 1] += gm; }
    };
    const drop = (p, q, vf, rs) => { G(p, q, 1 / rs); I(p, q, -vf / rs); }; // I(p→q) = (Vp − Vq − vf) / rs
    for (const e of S.el) {
      switch (e.k) {
        case 'R': case 'Load': G(e.a, e.b, 1 / e.r); break;
        case 'V': drop(e.a, e.b, e.v, e.r); break;
        case 'Pull': drop(e.a, e.from, 0, e.r); break;
        case 'Cap': if (dt > 0) { const g = e.c / dt; G(e.a, e.b, g); I(e.a, e.b, -g * e.vp); } else G(e.a, e.b, GLEAK); break;
        case 'D': if (e.on) drop(e.a, e.b, e.vf, e.rs); else G(e.a, e.b, GLEAK); break;
        case 'Fuse': G(e.a, e.b, e.trip ? 1 / 200 : 1 / 0.1); break;
        case 'Q':
          if (e.m !== 'off') drop(e.b, e.e, VBE, RBE);
          if (e.m === 'act') { const gm = BETA / RBE; VCCS(e.c, e.e, e.b, e.e, gm); I(e.c, e.e, -gm * VBE); }
          else if (e.m === 'sat') drop(e.c, e.e, VCE_SAT, RCE_SAT);
          break;
        case 'T555':
          if (e.q) drop(e.vcc, e.out, 1.5, 8); else drop(e.out, e.g, 0.1, 8);
          if (!e.q) drop(e.dis, e.g, 0.1, 8);
          break;
        case 'Reg':
          if (e.m === 'reg') { drop(e.o, e.g, e.vset, 0.05); VCCS(e.i, e.g, e.o, e.g, -1 / 0.05); I(e.i, e.g, e.vset / 0.05); }
          else if (e.m === 'drop') drop(e.i, e.o, e.drop, 0.3);
          break;
        case 'Buck':
          if (e.m === 'reg') { drop(e.o, e.og, e.vset, 0.05); I(e.i, e.ig, e.iin); }
          else if (e.m === 'drop') drop(e.i, e.o, 1.5, 0.3);
          break;
        default:
      }
    }
    const x = gauss(A, b);
    return (k) => (k > 0 ? x[k - 1] : 0);
  }

  /** Solve, then let every switch-like part (diodes, transistors, regulators, fuses) settle into its state. */
  function settle(S, dt) {
    let V = null;
    for (let it = 0; it < 80; it++) {
      V = linear(S, dt);
      let changed = false;
      for (const e of S.el) {
        switch (e.k) {
          case 'D': {
            const vd = V(e.a) - V(e.b);
            if (!e.on && vd > e.vf + 1e-4) { e.on = true; changed = true; } else if (e.on && vd < e.vf - 1e-6) { e.on = false; changed = true; }
            break;
          }
          case 'Q': {
            const vbe = V(e.b) - V(e.e), ib = (vbe - VBE) / RBE, vce = V(e.c) - V(e.e);
            let m = e.m;
            if (m === 'off') { if (vbe > VBE + 1e-4) m = 'act'; }
            else if (ib < -1e-9) m = 'off';
            else if (m === 'act' && vce < VCE_SAT) m = 'sat';
            else if (m === 'sat') { const ic = (vce - VCE_SAT) / RCE_SAT; if (ic > BETA * ib * 1.0001 || ic < -1e-6) m = 'act'; }
            if (m !== e.m) { e.m = m; changed = true; }
            break;
          }
          case 'Reg': {
            const vin = V(e.i) - V(e.g);
            const m = vin - e.drop >= e.vset ? 'reg' : vin > e.drop + 0.05 ? 'drop' : 'off';
            if (m !== e.m) { e.m = m; changed = true; }
            break;
          }
          case 'Buck': {
            const vin = V(e.i) - V(e.ig);
            const m = vin >= Math.max(4.5, e.vset + 1.2) ? 'reg' : vin > 3 ? 'drop' : 'off';
            if (m !== e.m) { e.m = m; changed = true; }
            if (m === 'reg') {
              const iout = Math.max(0, (V(e.og) + e.vset - V(e.o)) / 0.05), want = (e.vset * iout) / (Math.max(vin, 1) * 0.85) + 0.005;
              if (Math.abs(want - e.iin) > 1e-5) { e.iin = e.iin + (want - e.iin) * 0.7; changed = true; }
            } else if (e.iin) { e.iin = 0; changed = true; }
            break;
          }
          case 'Fuse': {
            const i = Math.abs(V(e.a) - V(e.b)) / 0.1;
            if (!e.trip && i > e.hold * 2) { e.trip = true; changed = true; }
            break;
          }
          default:
        }
      }
      if (!changed) break;
    }
    return V;
  }

  /** The 555's comparators after a step (its memory: q = output high). */
  function clock555(S, V) {
    let flip = false;
    for (const e of S.el) {
      if (e.k !== 'T555') continue;
      const vc = V(e.ctl) - V(e.g), vs = V(e.vcc) - V(e.g);
      let q = e.q;
      if (V(e.rst) - V(e.g) < 0.7 || vs < 2) q = 0;
      else if (V(e.trig) - V(e.g) < vc / 2) q = 1;
      else if (V(e.thr) - V(e.g) > vc) q = 0;
      if (q !== e.q) { e.q = q; flip = true; }
    }
    return flip;
  }

  // ---------------------------------------------------------------- readings

  function read(S, V, dt) {
    const out = { t: S.t, v: {}, p: {}, src: [] };
    S.nodes.forEach((k, name) => { if (name[0] !== '~') out.v[name] = S.flt.has(k) ? null : V(k); });
    const cur = (e) => {
      switch (e.k) {
        case 'R': case 'Load': return (V(e.a) - V(e.b)) / e.r;
        case 'V': return (e.v - (V(e.a) - V(e.b))) / e.r; // out of + into the board
        case 'D': return e.on ? (V(e.a) - V(e.b) - e.vf) / e.rs : 0;
        case 'Fuse': return (V(e.a) - V(e.b)) / (e.trip ? 200 : 0.1);
        case 'Cap': return dt > 0 ? (e.c / dt) * ((V(e.a) - V(e.b)) - e.vp) : 0;
        default: return 0;
      }
    };
    S.sources.forEach((s) => { const i = cur(s.el) + (s.el2 ? cur(s.el2) : 0); out.src.push({ key: s.key, i, v: V(s.el.a) - V(s.el.b), w: i * (V(s.el.a) - V(s.el.b)) }); });
    Object.keys(S.parts).forEach((ref) => {
      const info = S.parts[ref], r = { kind: info.kind };
      const e = info.els[0];
      switch (info.kind) {
        case 'res': r.i = cur(e); r.v = V(e.a) - V(e.b); r.w = r.i * r.v; break;
        case 'cap': r.v = V(e.a) - V(e.b); break;
        case 'led': case 'diode': r.i = cur(e); r.v = V(e.a) - V(e.b); r.on = e.on && r.i > 2e-5; r.w = r.i * r.v; break;
        case 'buzzer': r.v = V(e.a) - V(e.b); r.i = r.v / e.r; r.on = r.v > 3; break;
        case 'fuse': r.i = cur(e); r.trip = e.trip; break;
        case 'npn': {
          const vbe = V(e.b) - V(e.e), ib = e.m === 'off' ? 0 : (vbe - VBE) / RBE, vce = V(e.c) - V(e.e);
          r.ib = Math.max(0, ib); r.ic = e.m === 'act' ? BETA * r.ib : e.m === 'sat' ? (vce - VCE_SAT) / RCE_SAT : 0; r.vce = vce; r.m = e.m; r.w = r.ic * vce;
          break;
        }
        case '555': { const t = info.t; r.q = t.q; r.vcc = V(t.vcc) - V(t.g); r.out = V(t.out) - V(t.g); r.iout = t.q ? (V(t.vcc) - V(t.out) - 1.5) / 8 : (V(t.out) - V(t.g) - 0.1) / 8; break; }
        case 'reg': case 'buck': {
          const g = info.reg;
          if (info.kind === 'reg') { r.vin = V(g.i) - V(g.g); r.vout = V(g.o) - V(g.g); r.iout = g.m === 'reg' ? (g.vset - r.vout) / 0.05 : g.m === 'drop' ? (V(g.i) - V(g.o) - g.drop) / 0.3 : 0; }
          else { r.vin = V(g.i) - V(g.ig); r.vout = V(g.o) - V(g.og); r.iout = g.m === 'reg' ? (g.vset - r.vout) / 0.05 : g.m === 'drop' ? (V(g.i) - V(g.o) - 1.5) / 0.3 : 0; r.iin = g.m === 'reg' ? g.iin : r.iout; }
          r.iout = Math.max(0, r.iout); r.m = g.m; r.w = info.kind === 'reg' ? Math.max(0, r.vin - r.vout) * r.iout : 0;
          break;
        }
        case 'module': r.v = V(info.vpin) - V(info.gpin); break;
        case 'esp32': break;
        case 'switch': r.pos = info.pos; r.i = Math.max(Math.abs(cur(info.els[0])), Math.abs(cur(info.els[1]))); break;
        case 'usb': r.v = e ? V(e.a) - V(e.b) : NaN; r.i = e ? cur(e) : 0; r.phone = info.phone; break;
        default:
      }
      out.p[ref] = r;
    });
    return out;
  }

  /** The board running steadily (capacitors full, a 555 just switched on). */
  function dc(S) {
    S.el.forEach((e) => { if (e.k === 'T555') e.q = 1; });
    let V = settle(S, 0);
    for (let k = 0; k < 4 && clock555(S, V); k++) V = settle(S, 0);
    S.el.forEach((e) => { if (e.k === 'Cap') e.vp = V(e.a) - V(e.b); });
    return read(S, V, 0);
  }

  const has555 = (S) => S.el.some((e) => e.k === 'T555');
  /** From switch-on: the 555's timing capacitors empty, everything else already steady. */
  function start(S) {
    dc(S);
    const tim = new Set();
    S.el.forEach((e) => { if (e.k === 'T555') { tim.add(e.thr); tim.add(e.trig); } });
    S.el.forEach((e) => { if (e.k === 'Cap' && (tim.has(e.a) || tim.has(e.b))) e.vp = 0; if (e.k === 'T555') e.q = 1; });
    S.t = 0; S.tran = true; S.dt = 1e-5;
    return step(S, 0);
  }
  /** Moves time on by `span` seconds in small steps (smaller while a timing capacitor changes fast). */
  function step(S, span) {
    let V = null, R = null, done = 0, guard = 0;
    const tim = S.el.filter((e) => e.k === 'Cap');
    do {
      const dt = span > 0 ? Math.min(S.dt, span - done) : S.dt;
      V = settle(S, dt);
      R = read(S, V, 0);
      let dvmax = 0;
      tim.forEach((e) => { const v = V(e.a) - V(e.b); dvmax = Math.max(dvmax, Math.abs(v - e.vp)); e.vp = v; });
      const flip = clock555(S, V);
      S.t += dt; done += dt;
      // ~1% of the 555's window per step
      const want = dvmax > 0 ? dt * 0.03 / dvmax : dt * 2;
      S.dt = Math.max(1e-6, Math.min(0.01, flip ? Math.min(S.dt, 2e-5) : Math.min(want, dt * 2)));
      if (span <= 0) break;
    } while (done < span - 1e-12 && ++guard < 4000);
    R.t = S.t;
    return R;
  }

  /** For a board with a 555: how many times a second, how long it stays on, and every reading averaged / at its most over a cycle. */
  function cycle(S) {
    if (!has555(S)) return null;
    start(S);
    const t5 = S.el.find((e) => e.k === 'T555');
    const edges = [];
    let last = t5.q, guard = 0, R = null;
    const acc = [];
    while (edges.length < 5 && S.t < 30 && ++guard < 60000) {
      const t0 = S.t;
      R = step(S, 0);
      if (edges.length >= 3) acc.push({ dt: S.t - t0, R });
      if (t5.q !== last) { edges.push({ t: S.t, q: t5.q }); last = t5.q; }
    }
    if (edges.length < 5) return { stuck: true, q: t5.q };
    const per = edges[4].t - edges[2].t, hi = edges[2].q === 1 ? edges[3].t - edges[2].t : edges[4].t - edges[3].t;
    // the average and the most of every current over one cycle
    const avg = { p: {}, src: {} }, most = { p: {} };
    let tot = 0;
    acc.forEach((a) => {
      tot += a.dt;
      Object.keys(a.R.p).forEach((ref) => {
        const r = a.R.p[ref], A = avg.p[ref] = avg.p[ref] || {}, M = most.p[ref] = most.p[ref] || {};
        ['i', 'w', 'ic', 'iout', 'v'].forEach((k) => { if (typeof r[k] === 'number' && isFinite(r[k])) { A[k] = (A[k] || 0) + r[k] * a.dt; M[k] = Math.max(M[k] || 0, Math.abs(r[k])); } });
      });
      a.R.src.forEach((s) => { avg.src[s.key] = (avg.src[s.key] || 0) + s.i * a.dt; });
    });
    if (tot > 0) { Object.keys(avg.p).forEach((ref) => Object.keys(avg.p[ref]).forEach((k) => { avg.p[ref][k] /= tot; })); Object.keys(avg.src).forEach((k) => { avg.src[k] /= tot; }); }
    return { f: per > 0 ? 1 / per : 0, duty: per > 0 ? hi / per : 0, avg, most };
  }

  // ---------------------------------------------------------------- what it means, in Telugu

  /**
   * ✅ / ⚠️ / ❌ lines for every part that matters. R: a reading (dc or a moment); cy: cycle() for a 555 board (averages / most).
   * → { items: [{sev, ref, te, fix}], total: {i, w}, hours }
   */
  function report(S, R, cy) {
    const items = S.notes.slice(), push = (sev, ref, te, fix) => items.push({ sev, ref, te, fix: fix || '' });
    const cyc = cy && cy.most ? cy : null; // (a 555 that stopped has no cycle to average)
    const most = (ref, k) => (cyc && cyc.most.p[ref] && typeof cyc.most.p[ref][k] === 'number' ? cyc.most.p[ref][k] : Math.abs((R.p[ref] || {})[k] || 0));
    const avg = (ref, k) => (cyc && cyc.avg.p[ref] && typeof cyc.avg.p[ref][k] === 'number' ? cyc.avg.p[ref][k] : (R.p[ref] || {})[k] || 0);
    const pt = (ref) => S.D.parts.find((q) => q.ref === ref) || {};
    Object.keys(S.parts).forEach((ref) => {
      const info = S.parts[ref], r = R.p[ref] || {};
      switch (info.kind) {
        case 'led': {
          const i = most(ref, 'i'), ia = Math.abs(avg(ref, 'i'));
          const blink = cyc && i > 0.001 && ia < i * 0.8;
          const how = info.col === 'ir' ? 'IR (కంటికి కనిపించదు; ఫోన్ కెమెరాలో కనిపిస్తుంది)' : '';
          if (i < 2e-5) push('info', ref, ref + ' LED: ఆరి ఉంది (కరెంట్ రావట్లేదు)' + (blink ? '' : ''), 'పై 🎛️ లో స్విచ్ / పిన్ మార్చి చూడండి, లేదా LED తిరగేసి ఉందేమో చూడండి.');
          else if (i > info.burn) push('err', ref, ref + ' LED: ' + fmtA(i) + ': చాలా ఎక్కువ, కాలిపోతుంది', 'దానితో ఉన్న రెసిస్టర్ పెంచండి (🧮 లెక్కలు → LED రెసిస్టర్).');
          else if (i > info.good * 1.05) push('warn', ref, ref + ' LED: ' + fmtA(i) + ': కొంచెం ఎక్కువ (' + fmtA(info.good) + ' వరకు మంచిది)', 'రెసిస్టర్ కొంచెం పెంచితే LED ఎక్కువ కాలం బతుకుతుంది.');
          else if (i < 0.0005 && info.col !== 'ir') push('warn', ref, ref + ' LED: ' + fmtA(i) + ' మాత్రమే: చాలా మసకగా వెలుగుతుంది', info.col === 'blue' || info.col === 'white' ? 'నీలం / తెలుపు LED కి 3 V పైన కావాలి; రెసిస్టర్ తగ్గించండి లేదా ఎక్కువ వోల్టేజ్ నుంచి ఇవ్వండి.' : 'రెసిస్టర్ తగ్గిస్తే బాగా వెలుగుతుంది.');
          else if (i < 0.002 && info.col !== 'ir') push('ok', ref, ref + ' LED: ' + fmtA(i) + ': మెల్లగా వెలుగుతుంది (సూచిక లైట్‌కి సరిపోతుంది)');
          else push('ok', ref, ref + ' LED: ' + fmtA(i) + (blink ? ' (వెలిగినప్పుడు), మెరుస్తోంది' : ', వెలుగుతోంది') + (how ? ' · ' + how : ''));
          break;
        }
        case 'res': {
          const w = most(ref, 'w');
          if (w > 0.25) push('err', ref, ref + ' (' + (pt(ref).val || '') + '): ' + fmtW(w) + ' వేడి: ¼ W రెసిస్టర్ కాలిపోతుంది', '½ W / 1 W రెసిస్టర్ వాడండి, లేదా విలువ మార్చండి.');
          else if (w > 0.15) push('warn', ref, ref + ' (' + (pt(ref).val || '') + '): ' + fmtW(w) + ': బాగా వేడెక్కుతుంది', '½ W రెసిస్టర్ పెడితే మంచిది.');
          break;
        }
        case 'cap': {
          const v = r.v;
          if (info.polar && info.els[0].flt) { if (Math.abs(v) > 0.3) push('info', ref, ref + ': ఒక వైపు బయటి భాగానికి వెళ్తుంది (దాని వోల్టేజ్ ఇక్కడ తెలియదు)', '+ కాలు ఎక్కువ వోల్టేజ్ ఉన్న వైపు రావాలి: కొనే మాడ్యూల్ చూసి సరిచూడండి.'); }
          else if (info.polar && v < -0.3) push('err', ref, ref + ': తిరగబడింది (+ కాలుకి ' + fmtV(v) + ')', '+ కాలు ఎక్కువ వోల్టేజ్ ఉన్న వైపు రావాలి: ✏️ మార్పులో చెప్పండి.');
          else if (info.rated > 0 && Math.abs(v) > info.rated) push('err', ref, ref + ': ' + fmtV(Math.abs(v)) + ' వస్తోంది, కానీ ' + info.rated + 'V కెపాసిటర్: పేలిపోవచ్చు', 'ఎక్కువ V ఉన్న కెపాసిటర్ వాడండి (కనీసం ' + Math.ceil(Math.abs(v) * 1.5) + 'V).');
          else if (info.rated > 0 && Math.abs(v) > info.rated * 0.8) push('warn', ref, ref + ': ' + fmtV(Math.abs(v)) + ' — ' + info.rated + 'V దగ్గర: కొంచెం ఎక్కువ V ఉన్నది మంచిది');
          break;
        }
        case 'diode': {
          const i = most(ref, 'i');
          if (i > info.max) push('err', ref, ref + ': ' + fmtA(i) + ' — ' + info.max + ' A కంటే ఎక్కువ: వేడెక్కి పాడవుతుంది');
          break;
        }
        case 'npn': {
          const ic = most(ref, 'ic');
          const m = r.m === 'sat' ? 'పూర్తిగా ఆన్' : r.m === 'act' ? 'కొంచెం ఆన్' : 'ఆఫ్';
          if (ic > info.max) push('err', ref, ref + ': ' + fmtA(ic) + ' — BC547 కి 100 mA వరకే', 'పెద్ద ట్రాన్సిస్టర్ (BD139 / TIP120) లేదా తక్కువ కరెంట్.');
          else push('info', ref, ref + ' ట్రాన్సిస్టర్: ' + m + (ic > 1e-5 ? ' (' + fmtA(ic) + ')' : ''));
          break;
        }
        case '555': {
          const vcc = r.vcc;
          if (vcc < 4.5) push('err', ref, ref + ' 555: ' + fmtV(vcc) + ' మాత్రమే: 4.5 V కంటే తక్కువైతే పనిచేయదు');
          else if (vcc > 16) push('err', ref, ref + ' 555: ' + fmtV(vcc) + ': 16 V కంటే ఎక్కువ తట్టుకోదు');
          else if (cy && !cy.stuck) push('ok', ref, ref + ' 555: సెకనుకి ' + (Math.round(cy.f * 100) / 100) + ' సార్లు, ' + Math.round(cy.duty * 100) + '% సమయం ఆన్');
          else if (cy && cy.stuck) push('warn', ref, ref + ' 555: ఆగిపోయింది (OUT ఎప్పుడూ ' + (cy.q ? 'ఆన్' : 'ఆఫ్') + ')', 'THR / TRIG కి కెపాసిటర్, DIS కి రెసిస్టర్ కలిశాయో చూడండి.');
          if (most(ref, 'iout') > 0.2) push('err', ref, ref + ' 555 OUT: ' + fmtA(most(ref, 'iout')) + ': 200 mA కంటే ఎక్కువ ఇవ్వలేదు');
          break;
        }
        case 'reg': case 'buck': {
          const nm = info.kind === 'buck' ? ref + ' బక్ మాడ్యూల్' : ref + (info.vset < 4 ? ' 3.3V రెగ్యులేటర్' : ' 7805');
          const io = most(ref, 'iout'), w = info.kind === 'reg' ? most(ref, 'w') : 0;
          if (r.vin > info.vmax) push('err', ref, nm + ': IN కి ' + fmtV(r.vin) + ': ' + info.vmax + ' V కంటే ఎక్కువ తట్టుకోదు');
          else if (r.m === 'off') push('err', ref, nm + ': IN కి ' + fmtV(r.vin) + ' మాత్రమే: OUT కి ఏమీ రావట్లేదు');
          else if (r.m === 'drop') push('warn', ref, nm + ': IN ' + fmtV(r.vin) + ' తక్కువ, అందుకే OUT ' + fmtV(r.vout) + ' మాత్రమే (' + (info.kind === 'buck' ? '' : 'కనీసం ' + (info.vset + info.drop).toFixed(1) + ' V కావాలి') + ')');
          else push('ok', ref, nm + ': ' + fmtV(r.vin) + ' → ' + fmtV(r.vout) + ', ' + fmtA(io) + (w > 0.01 ? ', వేడి ' + fmtW(w) : ''));
          if (io > info.max) push('err', ref, nm + ': ' + fmtA(io) + ': ' + info.max + ' A కంటే ఎక్కువ ఇవ్వలేదు');
          if (w > 1.5) push('err', ref, nm + ': ' + fmtW(w) + ' వేడి: హీట్‌సింక్ లేకపోతే తనే ఆగిపోతుంది', 'హీట్‌సింక్ పెట్టండి, లేదా బక్ మాడ్యూల్ వాడండి.');
          else if (w > 0.8) push('warn', ref, nm + ': ' + fmtW(w) + ' వేడి: చేత్తో పట్టుకోలేనంత వేడెక్కుతుంది', 'చిన్న హీట్‌సింక్ మంచిది.');
          break;
        }
        case 'fuse':
          if (r.trip) push('err', ref, ref + ' ఫ్యూజ్: కరెంట్ ఎక్కువై కట్ అయింది', 'ఎక్కడో షార్ట్ / ఎక్కువ లోడ్ ఉంది: పైన ❌ లు చూడండి.');
          else if (most(ref, 'i') > info.hold * 0.8) push('warn', ref, ref + ' ఫ్యూజ్: ' + fmtA(most(ref, 'i')) + ' — ' + info.hold + ' A దగ్గర: వేడెక్కితే కట్ అవ్వొచ్చు');
          else push('ok', ref, ref + ' ఫ్యూజ్: ' + fmtA(most(ref, 'i')) + ' (' + info.hold + ' A వరకు సరే)');
          break;
        case 'buzzer':
          push(r.on ? 'ok' : 'info', ref, ref + ' బజర్: ' + (r.on ? '🔊 మోగుతోంది (' + fmtV(r.v) + ')' : 'నిశ్శబ్దం'));
          break;
        case 'switch':
          push('info', ref, ref + ' స్విచ్: C–' + r.pos + ' వైపు' + (r.i > 1e-4 ? ', ' + fmtA(r.i) : ''));
          if (r.i > info.max) push('err', ref, ref + ' స్విచ్: ' + fmtA(r.i) + ': చిన్న స్లైడ్ స్విచ్‌కి 0.3 A వరకే');
          break;
        case 'usb':
          if (r.phone) {
            if (r.v < 4.4) push('err', ref, ref + ' USB: ' + fmtV(r.v) + ' మాత్రమే: ఫోన్ ఛార్జ్ అవ్వదు');
            else if (r.v > 5.5) push('err', ref, ref + ' USB: ' + fmtV(r.v) + ': ఫోన్‌కి ఎక్కువ (5.25 V వరకే)');
            else push('ok', ref, ref + ' USB: ' + fmtV(r.v) + ', ఫోన్‌కి ' + fmtA(r.i) + ' (మామూలు ఛార్జింగ్ అనుకుని)');
          }
          break;
        case 'module': {
          const v = r.v, w = info.want;
          if (v < 0.2) push('warn', ref, ref + ' ' + info.te + ': కరెంట్ రావట్లేదు');
          else if (!w) push('warn', ref, ref + ' ' + info.te + ': ' + fmtV(v) + ' వస్తోంది; ఏ మాడ్యూల్, దానికి ఎన్ని V కావాలో తెలియదు', 'కొనే మాడ్యూల్ మీద వోల్టేజ్ చూసి సరిచూడండి.');
          else if (v < w[0] * 0.95 || v > w[1] * 1.05) push(info.sure ? 'err' : 'warn', ref, ref + ' ' + info.te + ': ' + fmtV(v) + ' వస్తోంది; ' + (info.why || 'దానికి ' + w[0] + '–' + w[1] + ' V కావాలి'),
            v > w[1] ? (info.sure ? 'ఎక్కువ: కాలిపోవచ్చు. ' : 'కొనే మాడ్యూల్ మీద ' + fmtV(v).replace(' ', '') + ' అని ఉంటే సరే; లేకపోతే ') + (w[1] < 4.5 ? '3.3V రెగ్యులేటర్ (LD1117V33) నుంచి ఇవ్వండి.' : 'సరైన వోల్టేజ్ నుంచి ఇవ్వండి.') : 'తక్కువ: సరిగ్గా పనిచేయకపోవచ్చు.');
          else push('ok', ref, ref + ' ' + info.te + ': ' + fmtV(v) + ' (' + w[0] + '–' + w[1] + ' V కావాలి) ✓');
          break;
        }
        default:
      }
    });
    // what the power gives
    let ti = 0, tw = 0;
    const per = S.sources.map((s) => {
      const r = R.src.find((q) => q.key === s.key) || { i: 0, v: s.v };
      const i = cyc && typeof cyc.avg.src[s.key] === 'number' ? cyc.avg.src[s.key] : r.i;
      ti += i; tw += i * r.v;
      if (i > 3) push('err', s.ref, s.te + ': ' + fmtA(i) + ': షార్ట్ లాగా ఉంది', 'ఎక్కడో + నేరుగా GND కి కలిసిందేమో 📐 లో చూడండి.');
      else if (s.kind === '9v' && i > 0.1) push('warn', s.ref, s.te + ': ' + fmtA(i) + ': 9V బ్యాటరీ ఇంత కరెంట్ ఎక్కువ సేపు ఇవ్వలేదు', 'అడాప్టర్ / పెద్ద బ్యాటరీ మంచిది.');
      return { s, i, v: r.v };
    });
    const hours = S.sources.length === 1 && S.sources[0].kind === '9v' && ti > 1e-4 ? BAT9_MAH / (ti * 1000) : null;
    const order = { err: 0, warn: 1, ok: 2, info: 3 };
    items.sort((a, b) => order[a.sev] - order[b.sev]);
    return { items, total: { i: ti, w: tw }, per, hours };
  }

  const api = { setup, dc, start, step, cycle, report, has555, farads, ratedV, ratedA, voltsOf, fmtV, fmtA, fmtW, MODS };
  root.PcbSim = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
