// ⚡ The circuit lab's helpers (Anil, 16 Nov): ready boards to learn from, the 🧩 challenges and their checks, the ⚠️ safety
// check, 🗣️ simple spoken edits (₹0; anything else goes to the AI), 🏠 the home's appliances and their bill, ⏱️ charging
// time, ☀️ a solar plan from his own prices, and a tidy layout for a board that came without places. Plain code, no AI.
(function (root) {
  'use strict';
  const S = root.CircuitSim || (typeof require !== 'undefined' ? require('./sim.js') : null);
  const W = (a, b) => ({ a, b });
  const clone = (x) => JSON.parse(JSON.stringify(x));

  // ---------------------------------------------------------------- the parts' places (grid cells; the page draws them)

  /** Each part's box (cells) and its terminals' places in the box (cells from the top-left), before turning. */
  const GEOM = {
    _2: { w: 2, h: 2, t: { a: [0, 1], b: [2, 1], '+': [0, 1], '-': [2, 1], l: [0, 1], n: [2, 1] } },
    switch2: { w: 2, h: 2, t: { c: [0, 1], 1: [2, 0.5], 2: [2, 1.5] } },
    inverter: { w: 3, h: 3, t: { 'in-l': [0, 0.6], 'in-n': [0, 1.2], 'b+': [0, 1.9], 'b-': [0, 2.5], 'out-l': [3, 1], 'out-n': [3, 2] } },
    charger: { w: 2, h: 2, t: { 'in-l': [0, 0.6], 'in-n': [0, 1.4], '+': [2, 0.6], '-': [2, 1.4] } },
  };
  function geom(type) { return GEOM[type] || GEOM._2; }

  /** A terminal's place on the board (cells), with the part turned by rot × 90°. */
  function termPos(p, term) {
    const g = geom(p.type), t = g.t[term];
    if (!t) return null;
    const cx = g.w / 2, cy = g.h / 2;
    let dx = t[0] - cx, dy = t[1] - cy;
    for (let k = 0; k < ((p.rot || 0) % 4 + 4) % 4; k++) { const x = dx; dx = -dy; dy = x; }
    return [(p.x || 0) + cx + dx, (p.y || 0) + cy + dy];
  }

  // ---------------------------------------------------------------- names: "బల్బ్ 2"

  const FAMILY = {
    bulb: 'bulb', ledbulb: 'bulb', tube: 'bulb', smallbulb: 'bulb', dclamp: 'bulb', headlight: 'headlight',
    fan: 'fan', tv: 'tv', fridge: 'fridge', mixie: 'mixie', ac: 'ac', iron: 'iron', geyser: 'geyser', motor: 'motor', dcmotor: 'motor',
    switch: 'switch', switch2: 'switch', fuse: 'fuse', mcb: 'mcb', battery: 'battery', cell: 'battery', inverter: 'inverter',
    solar: 'solar', led: 'led', resistor: 'resistor', horn: 'horn', buzzer: 'buzzer', charger: 'charger', phone: 'phone', mains: 'mains' };
  const FAMILY_TE = { bulb: 'బల్బ్', headlight: 'హెడ్‌లైట్', fan: 'ఫ్యాన్', tv: 'TV', fridge: 'ఫ్రిజ్', mixie: 'మిక్సీ', ac: 'AC', iron: 'ఐరన్',
    geyser: 'గీజర్', motor: 'మోటార్', switch: 'స్విచ్', fuse: 'ఫ్యూజ్', mcb: 'MCB', battery: 'బ్యాటరీ', inverter: 'ఇన్వర్టర్', solar: 'సోలార్',
    led: 'LED', resistor: 'రెసిస్టర్', horn: 'హారన్', buzzer: 'బజర్', charger: 'ఛార్జర్', phone: 'ఫోన్', mains: 'ఇంటి కరెంట్' };

  /** Every part's spoken name: its family and its number on this board ("బల్బ్ 2"), in board order. */
  function names(board) {
    const count = {}, out = {};
    for (const p of board.parts) {
      const f = FAMILY[p.type] || p.type;
      count[f] = (count[f] || 0) + 1;
      out[p.id] = (FAMILY_TE[f] || f) + ' ' + count[f];
    }
    for (const p of board.parts) {
      const f = FAMILY[p.type] || p.type;
      if (count[f] === 1) out[p.id] = FAMILY_TE[f] || f;
    }
    return out;
  }

  function newId(board, type) {
    const base = (FAMILY[type] || type).slice(0, 3);
    let n = 1;
    while (board.parts.some((p) => p.id === base + n)) n++;
    return base + n;
  }

  // ---------------------------------------------------------------- 📘 ready boards

  function part(id, type, x, y, props, extra) { return Object.assign({ id, type, x, y, rot: 0, props: props || {} }, extra || {}); }

  const TEMPLATES = [
    { id: 'inverter-home', te: '🏠 ఇన్వర్టర్ ఇల్లు', note: 'కరెంట్ ఉంటే ఇన్వర్టర్ ఇంటి కరెంట్‌ని లోపలికి పంపి బ్యాటరీ నింపుతుంది; ⚡ కరెంట్ పోయింది నొక్కితే బ్యాటరీ నుంచి నడుస్తుంది. 🔋 లెక్కలు లో బ్యాటరీ ఎంతసేపు వస్తుందో చూడండి.',
      board: () => ({ parts: [part('mai1', 'mains', 0, 1), part('mcb1', 'mcb', 3, 1, { amp: 16 }), part('inv1', 'inverter', 7, 2), part('bat1', 'battery', 2, 6, { v: 12, ah: 150 }),
        part('swi1', 'switch', 12, 0, { on: true }), part('bul1', 'ledbulb', 15, 0), part('swi2', 'switch', 12, 3, { on: true }), part('bul2', 'ledbulb', 15, 3),
        part('swi3', 'switch', 12, 6, { on: true }), part('fan1', 'fan', 15, 6), part('swi4', 'switch', 12, 9, { on: false }), part('tv1', 'tv', 15, 9)],
      wires: [W('mai1.l', 'mcb1.a'), W('mcb1.b', 'inv1.in-l'), W('mai1.n', 'inv1.in-n'), W('bat1.+', 'inv1.b+'), W('bat1.-', 'inv1.b-'),
        W('inv1.out-l', 'swi1.a'), W('swi1.b', 'bul1.a'), W('bul1.b', 'inv1.out-n'), W('inv1.out-l', 'swi2.a'), W('swi2.b', 'bul2.a'), W('bul2.b', 'inv1.out-n'),
        W('inv1.out-l', 'swi3.a'), W('swi3.b', 'fan1.a'), W('fan1.b', 'inv1.out-n'), W('inv1.out-l', 'swi4.a'), W('swi4.b', 'tv1.a'), W('tv1.b', 'inv1.out-n')] }) },
    { id: 'series-parallel', te: '💡 సిరీస్ vs ప్యారలల్', note: 'పైన రెండు బల్బులు ఒకదాని తర్వాత ఒకటి (సిరీస్): ఒక్కోదానికి సగం వోల్ట్స్, అందుకే మసకగా. కింద రెండూ పక్కపక్కనే (ప్యారలల్): ఒక్కోదానికి పూర్తి 230V, పూర్తి వెలుగు. ఇంట్లో అన్నీ ప్యారలల్ లోనే.',
      board: () => ({ parts: [part('mai1', 'mains', 0, 1), part('bul1', 'bulb', 4, 0), part('bul2', 'bulb', 8, 0),
        part('mai2', 'mains', 0, 7), part('bul3', 'bulb', 5, 5), part('bul4', 'bulb', 5, 9)],
      wires: [W('mai1.l', 'bul1.a'), W('bul1.b', 'bul2.a'), W('bul2.b', 'mai1.n'), W('mai2.l', 'bul3.a'), W('mai2.l', 'bul4.a'), W('bul3.b', 'mai2.n'), W('bul4.b', 'mai2.n')] }) },
    { id: 'led-resistor', te: '🔴 LED + రెసిస్టర్', note: 'LED కి 2V చాలు; 9V బ్యాటరీ నుంచి మిగతా 7V రెసిస్టర్ తీసుకుంటుంది. V = I × R: 7V ÷ 470Ω ≈ 15 mA. రెసిస్టర్‌ని తీసేసి నేరుగా కలిపి చూడండి (బొమ్మలోనే!).',
      board: () => ({ parts: [part('bat1', 'cell', 0, 2, { v: 9 }), part('swi1', 'switch', 4, 0, { on: true }), part('res1', 'resistor', 8, 0, { ohm: 470 }), part('led1', 'led', 8, 4, { color: 'red' })],
        wires: [W('bat1.+', 'swi1.a'), W('swi1.b', 'res1.a'), W('res1.b', 'led1.+'), W('led1.-', 'bat1.-')] }) },
    { id: 'stairs', te: '🪜 మెట్ల లైట్ (టూ-వే)', note: 'కింద ఒక స్విచ్, పైన ఒక స్విచ్: ఏది మార్చినా లైట్ మారుతుంది. రెండు స్విచ్‌ల మధ్య రెండు వైర్లు ఉంటాయి.',
      board: () => ({ parts: [part('mai1', 'mains', 0, 2), part('swi1', 'switch2', 4, 2, { pos: 1 }), part('swi2', 'switch2', 9, 2, { pos: 2 }), part('bul1', 'ledbulb', 14, 2)],
        wires: [W('mai1.l', 'swi1.c'), W('swi1.1', 'swi2.1'), W('swi1.2', 'swi2.2'), W('swi2.c', 'bul1.a'), W('bul1.b', 'mai1.n')] }) },
    { id: 'bell', te: '🔔 కాలింగ్ బెల్', note: 'బెల్ స్విచ్ నొక్కినప్పుడే దారి పూర్తై బజర్ మోగుతుంది. స్విచ్ తాకి చూడండి.',
      board: () => ({ parts: [part('bat1', 'cell', 0, 1, { v: 9 }), part('swi1', 'switch', 4, 0, { on: false }), part('buz1', 'buzzer', 8, 1)],
        wires: [W('bat1.+', 'swi1.a'), W('swi1.b', 'buz1.a'), W('buz1.b', 'bat1.-')] }) },
    { id: 'solar-battery', te: '☀️ సోలార్ + బ్యాటరీ', note: 'పగలు ప్యానెల్ బ్యాటరీని నింపుతుంది, రాత్రి బ్యాటరీ లైట్లు వెలిగిస్తుంది. ప్యానెల్ కార్డ్‌లో "ఎండ" తగ్గించి చూడండి.',
      board: () => ({ parts: [part('sol1', 'solar', 0, 0, { w: 330, v: 18 }), part('fus1', 'fuse', 4, 0, { amp: 30 }), part('bat1', 'battery', 8, 0, { v: 12, ah: 100 }, { st: { soc: 0.5 } }),
        part('fus2', 'fuse', 8, 4, { amp: 6 }), part('swi1', 'switch', 12, 4, { on: true }), part('bul1', 'dclamp', 16, 2), part('bul2', 'dclamp', 16, 6)],
        wires: [W('sol1.+', 'fus1.a'), W('fus1.b', 'bat1.+'), W('sol1.-', 'bat1.-'), W('bat1.+', 'fus2.a'), W('fus2.b', 'swi1.a'), W('swi1.b', 'bul1.a'), W('swi1.b', 'bul2.a'),
          W('bul1.b', 'bat1.-'), W('bul2.b', 'bat1.-')] }) },
    { id: 'car-12v', te: '🚗 కార్ 12V', note: 'కార్‌లో అన్నీ 12V DC: బ్యాటరీ నుంచి ఫ్యూజ్ బాక్స్, అక్కడి నుంచి స్విచ్‌లు, లైట్లు, హారన్. ప్రతి దారికి తన ఫ్యూజ్.',
      board: () => ({ parts: [part('bat1', 'battery', 0, 3, { v: 12, ah: 40 }), part('fus1', 'fuse', 4, 0, { amp: 15 }), part('swi1', 'switch', 8, 0, { on: true }),
        part('hea1', 'headlight', 12, 0), part('hea2', 'headlight', 12, 3), part('fus2', 'fuse', 4, 7, { amp: 10 }), part('swi2', 'switch', 8, 7, { on: false }), part('hor1', 'horn', 12, 7)],
        wires: [W('bat1.+', 'fus1.a'), W('fus1.b', 'swi1.a'), W('swi1.b', 'hea1.a'), W('swi1.b', 'hea2.a'), W('hea1.b', 'bat1.-'), W('hea2.b', 'bat1.-'),
          W('bat1.+', 'fus2.a'), W('fus2.b', 'swi2.a'), W('swi2.b', 'hor1.a'), W('hor1.b', 'bat1.-')] }) },
    { id: 'phone-charge', te: '📱 ఫోన్ ఛార్జింగ్', note: 'ఛార్జర్ 230V AC ని 5V DC గా మారుస్తుంది. 🔋 లెక్కలు → ⏱️ ఛార్జింగ్ టైమ్ లో ఎంతసేపో చూడండి.',
      board: () => ({ parts: [part('mai1', 'mains', 0, 1), part('cha1', 'charger', 4, 1, { v: 5, w: 20 }), part('pho1', 'phone', 8, 1)],
        wires: [W('mai1.l', 'cha1.in-l'), W('mai1.n', 'cha1.in-n'), W('cha1.+', 'pho1.+'), W('pho1.-', 'cha1.-')] }) },
    { id: 'ohm', te: '📏 V = I × R', note: 'ఒకే బ్యాటరీకి 470Ω, 1000Ω రెసిస్టర్లు. Ω ఎక్కువైతే కరెంట్ తక్కువ: 9V ÷ 470Ω ≈ 19 mA, 9V ÷ 1000Ω ≈ 9 mA. 🔌 మీటర్ తో కొలిచి చూడండి.',
      board: () => ({ parts: [part('bat1', 'cell', 0, 2, { v: 9 }), part('res1', 'resistor', 5, 0, { ohm: 470 }), part('res2', 'resistor', 5, 4, { ohm: 1000 })],
        wires: [W('bat1.+', 'res1.a'), W('bat1.+', 'res2.a'), W('res1.b', 'bat1.-'), W('res2.b', 'bat1.-')] }) },
  ];

  // ---------------------------------------------------------------- 🧩 challenges

  const lit = (r, id) => !!(r.parts[id] && r.parts[id].on);
  const broke = (r) => (r.events || []).length > 0 || (r.warnings || []).some((w) => w.te.includes('షార్ట్'));
  const setP = (b, id, k, v) => { const x = clone(b); const p = x.parts.find((q) => q.id === id); if (p) p.props = Object.assign({}, p.props, { [k]: v }); return x; };
  const has = (b, type) => b.parts.filter((p) => p.type === type);
  /** Is every way from the source through this part? (Take it out: does the thing go dark?) */
  const guards = (b, ids, loadId) => ids.some((id) => { const x = clone(b); x.parts = x.parts.filter((p) => p.id !== id); x.wires = x.wires.filter((w) => !w.a.startsWith(id + '.') && !w.b.startsWith(id + '.')); return !lit(S.solve(x), loadId); });

  const CHALLENGES = [
    { id: 'c1', te: 'బల్బ్ వెలిగించు', goal: 'బ్యాటరీ, బల్బ్ ని వైర్లతో కలిపి బల్బ్ వెలిగించండి.', wires: 2,
      board: () => ({ parts: [part('bat1', 'cell', 1, 2, { v: 3 }), part('bul1', 'smallbulb', 7, 2)], wires: [] }),
      check: (b, r) => ({ done: lit(r, 'bul1') }) },
    { id: 'c2', te: 'స్విచ్ తో బల్బ్', goal: 'స్విచ్ ఆన్ చేస్తే బల్బ్ వెలగాలి, ఆఫ్ చేస్తే ఆరిపోవాలి.', wires: 3,
      board: () => ({ parts: [part('bat1', 'cell', 1, 3, { v: 3 }), part('swi1', 'switch', 5, 0, { on: true }), part('bul1', 'smallbulb', 9, 3)], wires: [] }),
      check: (b) => ({ done: lit(S.solve(setP(b, 'swi1', 'on', true)), 'bul1') && !lit(S.solve(setP(b, 'swi1', 'on', false)), 'bul1') }) },
    { id: 'c3', te: 'రెండు బల్బులు పూర్తి వెలుగుతో', goal: 'రెండు బల్బులూ పూర్తి వెలుగుతో వెలగాలి (మసకగా కాదు).', wires: 4,
      board: () => ({ parts: [part('bat1', 'cell', 1, 3, { v: 3 }), part('bul1', 'smallbulb', 7, 0), part('bul2', 'smallbulb', 7, 6)], wires: [] }),
      check: (b, r) => ({ done: ['bul1', 'bul2'].every((id) => r.parts[id] && r.parts[id].bright > 0.85 && r.parts[id].bright < 1.4) }) },
    { id: 'c4', te: 'LED కాలిపోకుండా', goal: 'LED వెలగాలి, కాలిపోకూడదు. సరైన రెసిస్టర్ ఎంచుకోండి.', wires: 3,
      board: () => ({ parts: [part('bat1', 'cell', 1, 3, { v: 9 }), part('led1', 'led', 11, 3, { color: 'green' }),
        part('res1', 'resistor', 6, 0, { ohm: 10 }), part('res2', 'resistor', 6, 3, { ohm: 470 }), part('res3', 'resistor', 6, 6, { ohm: 100000 })], wires: [] }),
      check: (b, r) => { const d = r.parts.led1 || {}; return { done: d.on && d.state !== 'burnt', best: d.i > 0.008 && d.i < 0.025 }; } },
    { id: 'c5', te: 'మెట్ల లైట్', goal: 'రెండు టూ-వే స్విచ్‌లలో ఏది మార్చినా లైట్ మారాలి.', wires: 5,
      board: () => ({ parts: [part('mai1', 'mains', 0, 3), part('swi1', 'switch2', 5, 3), part('swi2', 'switch2', 10, 3), part('bul1', 'ledbulb', 15, 3)], wires: [] }),
      check: (b) => {
        const at = (a, c) => lit(S.solve(setP(setP(b, 'swi1', 'pos', a), 'swi2', 'pos', c)), 'bul1');
        const s = [at(1, 1), at(1, 2), at(2, 1), at(2, 2)];
        return { done: s[0] !== s[1] && s[0] !== s[2] && s[1] !== s[3] && s[2] !== s[3] };
      } },
    { id: 'c6', te: 'ఫ్యూజ్ పోకుండా 5 బల్బులు', goal: '5 బల్బులూ వెలగాలి, ఫ్యూజ్ పోకూడదు, కానీ ఫ్యూజ్ మరీ పెద్దది కాకూడదు (3 A లోపు). ఫ్యూజ్ కార్డ్‌లో A మార్చండి.', wires: 11,
      board: () => ({ parts: [part('mai1', 'mains', 0, 6), part('fus1', 'fuse', 4, 6, { amp: 1 }),
        ...[0, 3, 6, 9, 12].map((y, i) => part('bul' + (i + 1), 'bulb', 10, y))], wires: [] }),
      check: (b, r) => {
        const f = (b.parts.find((p) => p.id === 'fus1') || {}).props || {};
        const all = [1, 2, 3, 4, 5].every((i) => lit(r, 'bul' + i));
        return { done: all && r.parts.fus1 && r.parts.fus1.state !== 'blown' && (f.amp || 1) <= 3 && guards(b, ['fus1'], 'bul1'), best: (f.amp || 1) <= 2 };
      } },
    { id: 'c7', te: 'కరెంట్ పోయినా ఫ్యాన్', goal: 'కరెంట్ ఉన్నప్పుడూ, పోయినప్పుడూ ఫ్యాన్ తిరగాలి (ఇన్వర్టర్ తో).', wires: 6,
      board: () => ({ parts: [part('mai1', 'mains', 0, 0), part('inv1', 'inverter', 6, 2), part('bat1', 'battery', 0, 6, { v: 12, ah: 150 }), part('fan1', 'fan', 13, 2)], wires: [] }),
      check: (b) => ({ done: lit(S.solve(setP(b, 'mai1', 'on', true)), 'fan1') && lit(S.solve(setP(b, 'mai1', 'on', false)), 'fan1') }) },
    { id: 'c8', te: 'సోలార్ తో బ్యాటరీ నింపు', goal: 'ఎండలో సోలార్ ప్యానెల్ బ్యాటరీని నింపాలి. ⭐⭐⭐ కి మధ్యలో ఫ్యూజ్.', wires: 3,
      board: () => ({ parts: [part('sol1', 'solar', 0, 2, { w: 330, v: 18 }), part('fus1', 'fuse', 5, 0, { amp: 30 }), part('bat1', 'battery', 10, 2, { v: 12, ah: 100 }, { st: { soc: 0.3 } })], wires: [] }),
      check: (b, r) => ({ done: !!(r.parts.bat1 && r.parts.bat1.i < -1), best: !!(r.parts.fus1 && r.parts.fus1.i > 1) }) },
    { id: 'c9', te: 'కార్ హెడ్‌లైట్లు', goal: 'ఒక స్విచ్ తో రెండు హెడ్‌లైట్లూ పూర్తిగా వెలగాలి; బ్యాటరీ దగ్గర ఫ్యూజ్ ఉండాలి.', wires: 6,
      board: () => ({ parts: [part('bat1', 'battery', 0, 3, { v: 12, ah: 40 }), part('fus1', 'fuse', 4, 0, { amp: 15 }), part('swi1', 'switch', 8, 0, { on: true }),
        part('hea1', 'headlight', 13, 0), part('hea2', 'headlight', 13, 5)], wires: [] }),
      check: (b, r) => ({ done: lit(r, 'hea1') && lit(r, 'hea2') && r.parts.hea1.bright > 0.85 && r.parts.hea2.bright > 0.85
        && !lit(S.solve(setP(b, 'swi1', 'on', false)), 'hea1') && guards(b, ['fus1'], 'hea1') }) },
    { id: 'c10', te: 'ఫోన్ ఛార్జ్', goal: 'ఇంటి కరెంట్ నుంచి ఛార్జర్ ద్వారా ఫోన్ ఛార్జ్ అవ్వాలి.', wires: 4,
      board: () => ({ parts: [part('mai1', 'mains', 0, 1), part('cha1', 'charger', 5, 1, { v: 5, w: 20 }), part('pho1', 'phone', 10, 1)], wires: [] }),
      check: (b, r) => ({ done: lit(r, 'pho1') }) },
  ];

  /** Stars for a finished challenge: ⭐ done, ⭐⭐ in the fewest wires (or the "best" condition), ⭐⭐⭐ also nothing burnt / blown on the way. */
  function stars(ch, board, res, brokeOnTheWay) {
    const c = ch.check(board, res) || {};
    if (!c.done) return 0;
    let s = 1;
    const fewWires = board.wires.length <= ch.wires;
    if (c.best === undefined ? fewWires : (c.best && fewWires)) s = 2;
    if (s === 2 && !brokeOnTheWay && !broke(res)) s = 3;
    return s;
  }

  // ---------------------------------------------------------------- ⚠️ the safety check (for learning)

  function safety(boardIn) {
    const board = S.normalise(boardIn);
    const res = S.solve(board);
    const out = [];
    if (!res.ok) { out.push({ level: 'red', te: '⛔ ' + res.error, ids: res.bad || [] }); return out; }
    const N = names(board);
    const loads = board.parts.filter((p) => S.def(p.type).load);
    for (const w of res.warnings) if (/షార్ట్/.test(w.te)) out.push({ level: 'red', te: w.te, ids: [w.id] });
    for (const e of res.events) out.push({ level: 'red', te: '🔥 ' + e.te, ids: [e.id] });
    // a fuse / MCB on every source that feeds loads
    const opened = clone(board);
    const protect = board.parts.filter((p) => p.type === 'fuse' || p.type === 'mcb');
    const st = {};
    for (const p of protect) st[p.id] = p.type === 'fuse' ? { blown: true } : { tripped: true };
    const r0 = S.solve(opened, st);
    const unguarded = loads.filter((p) => r0.parts[p.id] && r0.parts[p.id].on);
    if (unguarded.length)
      out.push({ level: 'amber', te: '🧯 ' + unguarded.slice(0, 3).map((p) => N[p.id]).join(', ') + (unguarded.length > 3 ? '…' : '') + ' కి ఫ్యూజ్ / MCB రక్షణ లేదు. కరెంట్ వచ్చే చోటు దగ్గరే ఒకటి పెట్టండి.', ids: unguarded.map((p) => p.id) });
    // a fuse much bigger than what goes through it
    for (const p of protect) {
      const i = (res.parts[p.id] || {}).i || 0, amp = S.props(p).amp;
      if (i > 0.05 && amp > 2 && amp > i * 4 && amp - i > 3)
        out.push({ level: 'amber', te: '🧯 ' + N[p.id] + ' (' + amp + ' A) చాలా పెద్దది: మామూలుగా వెళ్ళేది ' + S.amps(i) + ' మాత్రమే. పెద్ద ఫ్యూజ్ సమయానికి పోదు; సుమారు ' + Math.max(1, Math.ceil(i * 1.5)) + ' A చాలు.', ids: [p.id] });
    }
    // a switch on the neutral: switched off, the thing still has 230 V on it
    for (const sw of board.parts.filter((p) => p.type === 'switch')) {
      const off = clone(board);
      off.parts.find((q) => q.id === sw.id).props = Object.assign({}, sw.props, { on: false });
      const on = clone(board);
      on.parts.find((q) => q.id === sw.id).props = Object.assign({}, sw.props, { on: true });
      const rOn = S.solve(on), rOff = S.solve(off);
      for (const m of board.parts.filter((q) => q.type === 'mains')) {
        for (const ld of loads) {
          if (!(rOn.parts[ld.id] || {}).on || (rOff.parts[ld.id] || {}).on) continue;
          const nV = rOff.V ? rOff.V(m.id + '.n') : 0;
          const d = S.def(ld.type);
          const live = d.terms.some((t) => Math.abs(rOff.V(ld.id + '.' + t) - nV) > 100);
          if (live) { out.push({ level: 'red', te: '⚡ ' + N[sw.id] + ' న్యూట్రల్ (N) మీద ఉంది: ఆఫ్ చేసినా ' + N[ld.id] + ' దగ్గర 230V ఉంటుంది, తాకితే షాక్. స్విచ్ ఎప్పుడూ L (ఫేజ్) మీదే.', ids: [sw.id, ld.id] }); break; }
        }
      }
    }
    // heavy things on an inverter; inverter load; batteries with no fuse
    for (const inv of board.parts.filter((p) => p.type === 'inverter')) {
      const r = res.parts[inv.id] || {};
      if (r.pct > 0.8 && r.state !== 'overload') out.push({ level: 'amber', te: '🔌 ' + N[inv.id] + ' దాదాపు నిండింది (' + Math.round(r.pct * 100) + '%). ఇంకా పెడితే ఆగిపోతుంది.', ids: [inv.id] });
      const heavy = loads.filter((p) => ['ac', 'geyser', 'iron', 'motor'].includes(p.type));
      for (const h of heavy) {
        const x = clone(board);
        const m = x.parts.filter((q) => q.type === 'mains');
        m.forEach((q) => { q.props = Object.assign({}, q.props, { on: false }); });
        const rx = S.solve(x);
        if ((rx.parts[h.id] || {}).on || (rx.parts[inv.id] || {}).state === 'overload')
          out.push({ level: 'amber', te: '🔋 ' + N[h.id] + ' ఇన్వర్టర్ మీద ఉంది: కరెంట్ పోతే బ్యాటరీ త్వరగా అయిపోతుంది / ఇన్వర్టర్ ఆగిపోతుంది. ఇలాంటివి ఇన్వర్టర్ లేని వేరే లైన్ మీద పెడతారు.', ids: [h.id] });
      }
    }
    for (const bat of board.parts.filter((p) => p.type === 'battery')) {
      const r = r0.parts[bat.id] || {};
      if (r.i > 0.5) out.push({ level: 'amber', te: '🔋 ' + N[bat.id] + ' దగ్గర ఫ్యూజ్ లేదు: పెద్ద బ్యాటరీ షార్ట్ అయితే వైర్లు కాలిపోతాయి. బ్యాటరీ + దగ్గరే ఫ్యూజ్ పెట్టండి.', ids: [bat.id] });
    }
    for (const p of board.parts.filter((q) => q.type === 'iron' || q.type === 'geyser'))
      out.push({ level: 'info', te: '🔩 ' + N[p.id] + ': లోహపు బాడీ; నిజంగా అయితే మూడు పిన్నుల ప్లగ్, ఎర్త్ వైర్ తప్పనిసరి.', ids: [p.id] });
    if (!out.some((o) => o.level !== 'info')) out.unshift({ level: 'ok', te: '✅ పెద్ద సమస్యలు కనిపించలేదు. (ఇది నేర్చుకోవడానికి మాత్రమే; ఇంట్లో అసలు వైరింగ్ లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ తోనే.)', ids: [] });
    return out;
  }

  // ---------------------------------------------------------------- 🗣️ spoken edits (₹0); null = not understood here (the AI may do it)

  const NUM = { 'ఒకటి': 1, 'ఒక': 1, 'మొదటి': 1, 'రెండు': 2, 'రెండో': 2, 'రెండవ': 2, 'మూడు': 3, 'మూడో': 3, 'నాలుగు': 4, 'నాలుగో': 4, 'ఐదు': 5, 'ఐదో': 5,
    'ఆరు': 6, 'ఏడు': 7, 'ఎనిమిది': 8, 'తొమ్మిది': 9, 'పది': 10, one: 1, two: 2, three: 3, four: 4, five: 5 };
  const WORDS = [
    ['bulb', ['బల్బ్', 'బల్బు', 'లైట్', 'bulb', 'light', 'లైటు']], ['fan', ['ఫ్యాన్', 'పంకా', 'fan']], ['tv', ['టీవీ', 'tv', 'టివి']],
    ['fridge', ['ఫ్రిజ్', 'fridge']], ['mixie', ['మిక్సీ', 'mixie', 'mixer']], ['ac', ['ఏసీ', 'ac', 'ఏ సి']], ['iron', ['ఐరన్', 'ఇస్త్రీ', 'iron']],
    ['geyser', ['గీజర్', 'geyser']], ['motor', ['మోటార్', 'మోటర్', 'motor', 'pump', 'పంపు']], ['switch', ['స్విచ్', 'switch']],
    ['fuse', ['ఫ్యూజ్', 'fuse']], ['mcb', ['ఎంసీబీ', 'mcb', 'ఎం సి బి']], ['battery', ['బ్యాటరీ', 'battery', 'బ్యాటరి']], ['inverter', ['ఇన్వర్టర్', 'inverter']],
    ['solar', ['సోలార్', 'solar', 'ప్యానెల్']], ['led', ['ఎల్ఈడీ', 'led']], ['resistor', ['రెసిస్టర్', 'resistor']], ['headlight', ['హెడ్‌లైట్', 'హెడ్ లైట్', 'headlight']],
    ['horn', ['హారన్', 'horn']], ['buzzer', ['బజర్', 'బెల్', 'buzzer', 'bell']], ['charger', ['ఛార్జర్', 'charger']], ['phone', ['ఫోన్', 'phone']]];
  const OFF = /(ఆపు|ఆపేయి|ఆఫ్|ఆర్పు|ఆపండి|\boff\b|turn off|switch off)/;
  const ON = /(ఆన్|వెయ్|వేయి|వెలిగించు|తిప్పు|\bon\b|turn on|switch on)/;
  const ADD = /(ఇంకో|ఇంకొక|మరో|ఇంకా ఒక|another|add one more|add another)/;
  const REMOVE = /(తీసేయి|తీసెయ్|తొలగించు|remove|delete)/;
  const RESET = /(రీసెట్|reset|మళ్ళీ వెయ్|కొత్త ఫ్యూజ్|ఫ్యూజ్ మార్చు|పైకి వెయ్)/;
  const CUT = /(కరెంట్ పోయింది|కరెంట్ పోనివ్వు|కరెంట్ తీసేయి|పవర్ కట్|power cut|current off|కరెంట్ ఆపు)/;
  const BACK = /(కరెంట్ వచ్చింది|కరెంట్ ఇవ్వు|కరెంట్ ఇచ్చేయి|power back|current on|కరెంట్ ఆన్)/;

  function family(text) {
    let best = null, at = 1e9;
    for (const [f, ws] of WORDS) for (const w of ws) { const i = text.indexOf(w); if (i >= 0 && i < at) { at = i; best = f; } }
    return best;
  }
  function number(text) {
    const m = text.match(/(\d+(?:\.\d+)?)/);
    if (m) return +m[1];
    for (const k of Object.keys(NUM)) if (new RegExp('(^|\\s)' + k + '($|\\s)').test(text)) return NUM[k];
    return null;
  }

  /** One spoken edit on the board: {board, said} (the new board and what Jarvis says), or null when it is not one of the simple ones. */
  function voiceEdit(boardIn, textIn) {
    const text = ' ' + String(textIn || '').toLowerCase().replace(/[.,!?।]/g, ' ').replace(/\s+/g, ' ').trim() + ' ';
    const board = clone(S.normalise(boardIn));
    board.parts.forEach((p, i) => { const orig = boardIn.parts.find((q) => q.id === p.id); if (orig) Object.assign(p, { x: orig.x, y: orig.y, rot: orig.rot, label: orig.label }); });
    const N = names(board);
    // (a whole new circuit, or a long sentence naming many things, is not a small edit: the AI draws it)
    if (/(కొత్త|\bnew\b)/.test(text) && /(సర్క్యూట్|బోర్డ్|బోర్డు|circuit|board)/.test(text)) return null;
    const fams = new Set();
    for (const [fw, ws] of WORDS) for (const w of ws) if (text.includes(w)) fams.add(fw);
    if (fams.size >= 3 || text.trim().split(' ').length > 9) return null;
    const mains = board.parts.filter((p) => p.type === 'mains');
    if (CUT.test(text) && mains.length) { mains.forEach((p) => { p.props = Object.assign({}, p.props, { on: false }); }); return { board, said: '⚡ కరెంట్ పోయింది (బొమ్మలో).' }; }
    if (BACK.test(text) && mains.length) { mains.forEach((p) => { p.props = Object.assign({}, p.props, { on: true }); }); return { board, said: '⚡ కరెంట్ వచ్చింది.' }; }
    const f = family(text);
    if (/(అన్నీ|అన్ని|all)/.test(text) && (OFF.test(text) || ON.test(text)) && !f) {
      const off = OFF.test(text);
      let n = 0;
      for (const p of board.parts) if (S.def(p.type).load || p.type === 'led') { p.props = Object.assign({}, p.props, { off }); n++; }
      return n ? { board, said: off ? 'అన్నీ ఆపేశాను.' : 'అన్నీ ఆన్ చేశాను.' } : null;
    }
    if (!f) return null;
    const mine = board.parts.filter((p) => (FAMILY[p.type] || p.type) === f);
    const n = number(text.replace(/\d+(\.\d+)?\s*(ah|va|w|v|a|ఆంప్స్|ఆంపియర్|వాట్స్|వాట్|వోల్ట్స్|ఓమ్స్|ohm|Ω|ఏహెచ్)/gi, ' '));
    const pick = () => (mine.length === 1 ? mine[0] : n && n >= 1 && n <= mine.length ? mine[n - 1] : null);
    // add another one, beside the first of its kind (same two connections)
    if (ADD.test(text)) {
      const like = mine[0];
      if (!like) return null;
      const id = newId(board, like.type);
      const copy = Object.assign(clone(like), { id, y: (like.y || 0) + 3, label: undefined });
      if (copy.st) delete copy.st;
      board.parts.push(copy);
      const terms = S.def(like.type).terms;
      for (const t of terms) board.wires.push({ a: like.id + '.' + t, b: id + '.' + t });
      return { board, said: (FAMILY_TE[f] || f) + ' ఇంకొకటి పెట్టాను (పక్కనే, అదే కనెక్షన్లతో).' };
    }
    const p = pick();
    if (!p) return mine.length > 1 ? { board: boardIn, said: (FAMILY_TE[f] || f) + ' లు ' + mine.length + ' ఉన్నాయి: ఏది? (ఉదా: "' + (FAMILY_TE[f] || f) + ' 2 ఆపు")', ask: true } : null;
    if (REMOVE.test(text)) {
      board.parts = board.parts.filter((q) => q.id !== p.id);
      board.wires = board.wires.filter((w) => !w.a.startsWith(p.id + '.') && !w.b.startsWith(p.id + '.'));
      return { board, said: N[p.id] + ' తీసేశాను.' };
    }
    if (RESET.test(text) && (p.type === 'fuse' || p.type === 'mcb' || p.type === 'inverter')) return { board, said: N[p.id] + ' మళ్ళీ వేశాను.', reset: p.id };
    // a number with a unit: a new rating
    const m = text.match(/(\d+(?:\.\d+)?)\s*(ah|ఏహెచ్|va|వీఏ|w\b|వాట్స్|వాట్|v\b|వోల్ట్స్|వోల్ట్|a\b|ఆంప్స్|ఆంపియర్|ohm|ఓమ్స్|Ω|mah)/i);
    const speed = text.match(/(స్పీడ్|speed)\s*(\d)/);
    if (speed && p.type === 'fan') { p.props = Object.assign({}, p.props, { speed: +speed[2] }); return { board, said: N[p.id] + ' స్పీడ్ ' + speed[2] + '.' }; }
    if (m) {
      const v = +m[1], u = m[2].toLowerCase();
      const key = /ah|ఏహెచ్/.test(u) ? 'ah' : /mah/.test(u) ? 'mah' : /va|వీఏ/.test(u) ? 'va' : /w|వాట్/.test(u) ? 'w' : /v|వోల్ట్/.test(u) ? 'v' : /a|ఆంప్|ఆంపియర్/.test(u) ? 'amp' : 'ohm';
      if (!(key in Object.assign({}, S.def(p.type).props))) return null;
      p.props = Object.assign({}, p.props, { [key]: v });
      const unit = { ah: 'Ah', mah: 'mAh', va: 'VA', w: 'W', v: 'V', amp: 'A', ohm: 'Ω' }[key];
      return { board, said: N[p.id] + ' ఇప్పుడు ' + v + ' ' + unit + '.' };
    }
    if (OFF.test(text) || ON.test(text)) {
      const off = OFF.test(text);
      if (p.type === 'switch') { p.props = Object.assign({}, p.props, { on: !off }); return { board, said: N[p.id] + (off ? ' ఆఫ్.' : ' ఆన్.') }; }
      if (p.type === 'mains') { p.props = Object.assign({}, p.props, { on: !off }); return { board, said: off ? '⚡ కరెంట్ పోయింది.' : '⚡ కరెంట్ వచ్చింది.' }; }
      if (S.def(p.type).load || p.type === 'led') { p.props = Object.assign({}, p.props, { off }); return { board, said: N[p.id] + (off ? ' ఆపేశాను.' : ' ఆన్ చేశాను.') }; }
    }
    return null;
  }

  // ---------------------------------------------------------------- 🏠 the home's appliances

  const HOME_ITEMS = [
    ['ledbulb', 'LED బల్బ్', 9, 6], ['bulb', 'పాత బల్బ్', 60, 6], ['tube', 'ట్యూబ్ లైట్', 20, 6], ['fan', 'ఫ్యాన్', 75, 10], ['bldc', 'BLDC ఫ్యాన్', 28, 10],
    ['tv', 'TV', 100, 4], ['fridge', 'ఫ్రిజ్', 150, 9.6], ['ac', 'AC', 1500, 6], ['geyser', 'గీజర్', 2000, 1], ['iron', 'ఐరన్', 1000, 0.5],
    ['mixie', 'మిక్సీ', 500, 0.3], ['motor', 'నీళ్ళ మోటార్', 750, 1], ['washer', 'వాషింగ్ మెషిన్', 500, 1], ['charger', 'ఫోన్ ఛార్జర్', 10, 3],
    ['wifi', 'Wi-Fi రౌటర్', 10, 24], ['laptop', 'ల్యాప్‌టాప్', 60, 4], ['cooler', 'కూలర్', 200, 8], ['oven', 'మైక్రోవేవ్', 1200, 0.3]];
  const HOME_TYPE = { ledbulb: 'ledbulb', bulb: 'bulb', tube: 'tube', fan: 'fan', bldc: 'fan', tv: 'tv', fridge: 'fridge', ac: 'ac', geyser: 'geyser', iron: 'iron',
    mixie: 'mixie', motor: 'motor', washer: 'motor', charger: 'ledbulb', wifi: 'ledbulb', laptop: 'tv', cooler: 'fan', oven: 'iron' };

  /** home = {rooms: [{name, items: [{kind, count, w, hours}]}], rate}: units and ₹ a month, the biggest first, and what-ifs. */
  function homeBill(home, whatIf) {
    const rate = home && typeof home.rate === 'number' ? home.rate : 7;
    const wi = whatIf || {};
    const rows = [];
    for (const room of (home && home.rooms) || []) {
      for (const it of room.items || []) {
        let w = Math.max(0, +it.w || 0), h = Math.max(0, Math.min(24, +it.hours || 0));
        const n = Math.max(0, Math.round(+it.count || 0));
        if (wi.led && it.kind === 'bulb') w = 9;
        if (wi.led && it.kind === 'tube' && w > 20) w = 20;
        if (wi.bldc && it.kind === 'fan') w = 28;
        if (wi.acHour && it.kind === 'ac') h = Math.max(0, h - 1);
        const units = n * w * h * 30 / 1000;
        rows.push({ room: room.name, kind: it.kind, te: it.te || (HOME_ITEMS.find((x) => x[0] === it.kind) || [0, it.kind])[1], count: n, w, hours: h, units, rupees: units * rate });
      }
    }
    rows.sort((a, b) => b.units - a.units);
    const units = rows.reduce((s, r) => s + r.units, 0);
    return { rows, units, rupees: units * rate, rate, dailyUnits: units / 30 };
  }

  /** The savings of each what-if, a month. */
  function homeSavings(home) {
    const base = homeBill(home);
    const out = {};
    for (const k of ['led', 'bldc', 'acHour']) { const x = homeBill(home, { [k]: true }); out[k] = { units: base.units - x.units, rupees: base.rupees - x.rupees }; }
    const all = homeBill(home, { led: true, bldc: true, acHour: true });
    out.all = { units: base.units - all.units, rupees: base.rupees - all.rupees };
    return out;
  }

  /** The home as a board: ఇంటి కరెంట్ → main MCB → a room MCB each → a switch and the room's things (several of a kind = one part with their total watts). */
  function homeBoard(home) {
    const parts = [part('mai1', 'mains', 0, 1), part('mcb1', 'mcb', 3, 1, { amp: 40 })], wires = [W('mai1.l', 'mcb1.a')];
    let y = 0, k = 0;
    (home.rooms || []).forEach((room, ri) => {
      const rid = 'rmc' + (ri + 1);
      const items = (room.items || []).filter((it) => it.count > 0 && it.w > 0);
      if (!items.length) return;
      parts.push(part(rid, 'mcb', 7, y, { amp: 16 }, { label: room.name + ' MCB' }));
      wires.push(W('mcb1.b', rid + '.a'));
      items.forEach((it, ii) => {
        k++;
        const sid = 'sw' + k, lid = 'ld' + k, type = HOME_TYPE[it.kind] || 'ledbulb';
        parts.push(part(sid, 'switch', 11, y + ii * 3, { on: true }));
        parts.push(part(lid, type, 15, y + ii * 3, { w: it.w * Math.max(1, it.count), v: 230 }, { label: (it.te || type) + (it.count > 1 ? ' ×' + it.count : '') + ' (' + room.name + ')' }));
        wires.push(W(rid + '.b', sid + '.a'), W(sid + '.b', lid + '.a'), W(lid + '.b', 'mai1.n'));
      });
      y += items.length * 3 + 1;
    });
    return { parts, wires, title: '🏠 మా ఇల్లు' };
  }

  // ---------------------------------------------------------------- ⏱️ charging time, ☀️ solar plan

  /** Hours to fill capacityWh (from empty × fill) with a charger of chargerW: the losses and the slow last part counted. */
  function chargeHours(capacityWh, chargerW, fill) {
    const need = Math.max(0, +capacityWh || 0) * Math.max(0, Math.min(1, fill == null ? 1 : fill));
    const w = Math.max(0.1, +chargerW || 0);
    return need / (w * 0.85) * 1.12;
  }

  /** ☀️ For dailyUnits a day: panels, roof, battery for the night, cost from HIS prices (left out when not given), payback. */
  function solarPlan(o) {
    const daily = Math.max(0, +o.dailyUnits || 0), sun = Math.max(1, Math.min(8, +o.sunHours || 5)), pw = Math.max(50, +o.panelW || 540);
    const share = Math.max(0, Math.min(1, o.share == null ? 1 : +o.share));
    const wh = daily * 1000 * share;
    const panels = wh > 0 ? Math.ceil(wh / (pw * sun * 0.75)) : 0;
    const kw = panels * pw / 1000;
    const areaM2 = panels * pw / 540 * 2.6;
    const nightUnits = Math.max(0, +o.nightUnits || 0);
    const bv = +o.batteryV || 24;
    const batteryAh = nightUnits > 0 ? Math.ceil(nightUnits * 1000 / (bv * 0.8) / 10) * 10 : 0;
    const genMonth = panels * pw * sun * 0.75 * 30 / 1000;
    const rate = +o.rate || 7;
    const saveMonth = Math.min(genMonth, daily * 30) * rate;
    let cost = 0, priced = false;
    if (+o.pricePerW > 0) { cost += panels * pw * +o.pricePerW; priced = true; }
    if (+o.batteryPrice > 0 && batteryAh > 0) { cost += +o.batteryPrice; priced = true; }
    if (+o.inverterPrice > 0) { cost += +o.inverterPrice; priced = true; }
    if (+o.otherPrice > 0) { cost += +o.otherPrice; priced = true; }
    return { panels, kw, areaM2, areaSqft: areaM2 * 10.764, batteryAh, batteryV: bv, genMonth, saveMonth, cost: priced ? cost : null,
      paybackYears: priced && saveMonth > 0 ? cost / (saveMonth * 12) : null };
  }

  // ---------------------------------------------------------------- places for a board that came without them

  /** Sources on the left, controls in the middle, things that use current on the right, a row each; existing places are kept. */
  function layout(boardIn) {
    const board = clone(boardIn);
    const need = board.parts.filter((p) => typeof p.x !== 'number' || typeof p.y !== 'number');
    if (!need.length) return board;
    const col = { src: 0, ctl: 5, load: 10, kit: 10 };
    const row = { src: 0, ctl: 0, load: 0, kit: 0 };
    for (const p of board.parts) {
      if (!need.includes(p)) continue;
      const c = (S.def(p.type) || {}).cat || 'load';
      p.x = c === 'src' && p.type === 'inverter' ? 5 : col[c];
      p.y = row[c];
      row[c] += geom(p.type).h + 1;
      p.rot = p.rot || 0;
    }
    return board;
  }

  /** A board from outside (the AI, a saved file) made safe to use: known parts only, ids unique, numbers sane, wires to real terminals, ≤ 80 parts. */
  function clean(boardIn) {
    const b = { title: '', parts: [], wires: [] };
    if (!boardIn || typeof boardIn !== 'object') return b;
    if (typeof boardIn.title === 'string') b.title = boardIn.title.slice(0, 60);
    const seen = new Set();
    for (const p0 of (Array.isArray(boardIn.parts) ? boardIn.parts : []).slice(0, 80)) {
      if (!p0 || typeof p0 !== 'object' || !S.def(p0.type)) continue;
      let id = String(p0.id || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 16) || newId({ parts: b.parts }, p0.type);
      while (seen.has(id)) id += 'x';
      seen.add(id);
      const props = {};
      const d = S.def(p0.type);
      for (const k of Object.keys(d.props)) if (p0.props && p0.props[k] !== undefined) props[k] = typeof d.props[k] === 'number' ? +p0.props[k] : typeof d.props[k] === 'boolean' ? !!p0.props[k] : String(p0.props[k]).slice(0, 16);
      if (p0.props && p0.props.off) props.off = true;
      const q = { id, type: p0.type, props, rot: ((+p0.rot || 0) % 4 + 4) % 4 };
      if (isFinite(+p0.x) && isFinite(+p0.y) && p0.x !== null && p0.y !== null && p0.x !== undefined && p0.y !== undefined) { q.x = Math.max(-40, Math.min(200, Math.round(+p0.x * 2) / 2)); q.y = Math.max(-40, Math.min(200, Math.round(+p0.y * 2) / 2)); }
      if (typeof p0.label === 'string') q.label = p0.label.slice(0, 40);
      if (p0.st && typeof p0.st.soc === 'number') q.st = { soc: Math.max(0, Math.min(1, p0.st.soc)) };
      // (a part renamed: wires that used its old id follow it)
      if (p0.id && p0.id !== id) q._was = String(p0.id);
      b.parts.push(q);
    }
    const map = {};
    for (const p of b.parts) map[p.id] = p.id;
    for (const p of b.parts) { if (p._was) { if (!(p._was in map)) map[p._was] = p.id; delete p._was; } } // (a doubled id: wires go to the first)
    const term = (t) => {
      if (typeof t !== 'string') return null;
      const i = t.lastIndexOf('.');
      if (i < 1) return null;
      const id = map[t.slice(0, i)], tm = t.slice(i + 1);
      const p = id && b.parts.find((x) => x.id === id);
      return p && S.def(p.type).terms.includes(tm) ? id + '.' + tm : null;
    };
    for (const w of (Array.isArray(boardIn.wires) ? boardIn.wires : []).slice(0, 300)) {
      const a = term(w && (w.a || w[0])), c = term(w && (w.b || w[1]));
      if (a && c && a !== c && !b.wires.some((x) => (x.a === a && x.b === c) || (x.a === c && x.b === a))) b.wires.push({ a, b: c });
    }
    return layout(b);
  }

  root.LabKit = { GEOM, geom, termPos, names, newId, FAMILY, FAMILY_TE, TEMPLATES, CHALLENGES, stars, safety, voiceEdit, HOME_ITEMS, homeBill, homeSavings,
    homeBoard, chargeHours, solarPlan, layout, clean };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.LabKit;
})(typeof window !== 'undefined' ? window : globalThis);
