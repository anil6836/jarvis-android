// 🔌 Jarvis PCB studio — making the board real (Build 3B, 24 Nov: "Build 3B చేయి"): 🔧 the soldering order with Telugu words for
// every step (what, where, which way round, how), 📦 the box around the board as numbers (walls, cut-outs for the sockets, posts
// at the screw holes, lid holes over LEDs / buzzer, air slots) with its README, and the binary STL writer for 3D printing.
// All our own code, ₹0, offline; nothing here calls the AI. Units: mm; board x right, y down (as the PCB tab), z up.
'use strict';
(function (root) {
  const P = root.PcbParts, C = root.PcbCore;
  const num = (v, d) => (typeof v === 'number' && isFinite(v) ? v : d);
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const r2 = (v) => Math.round(v * 100) / 100;
  const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{2460}-\u{24FF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{20E3}\u{FE0F}]/gu;
  /** Words for Jarvis's voice: no emoji, no brackets' little signs. */
  function spoken(s) { return String(s || '').replace(EMOJI, '').replace(/[×]/g, ' ').replace(/\s+/g, ' ').trim(); }

  // ================================================================ heights (as drawn in 🧊 3D, plus what plugs in on top)

  /** How tall a part stands above the board (mm). Modules on sockets and jumper-wire plugs on pins are counted too. */
  function tall(pt) {
    const f = P.fpOf(pt), b = (f && f.body) || {};
    if (pt.lib === 'buck') return 21;   // (the buck module on its pins)
    if (pt.lib === 'fhdr') return 20.5; // (a module plugged in: its own board and parts on the 8.5 mm socket)
    if (pt.lib === 'pam8403' || pt.lib === 'tp4056') return 16; // (wires on male pins; the module sits beside)
    const lm = P.LIB[pt.lib] || {};
    if (lm.mod && lm.kind === 'wire') return 16;    // (📚 a module at the end of jumper wires)
    if (b.t === 'mod') return 8.5 + 2.5 + (b.stand ? b.H : 6); // (📚 on its socket: lying flat, or standing on its 90° pins)
    switch (b.t) {
      case 'axial': return b.D + 0.8;
      case 'disc': return b.D + 2;
      case 'radial': return b.H + 0.5;
      case 'led': return b.H + 1;
      case 'to92': return 7.8;
      case 'to220': return 18.5;
      case 'dip': return 8.5;           // (the chip sitting in its socket)
      case 'hdr': return b.female ? 8.5 : 16; // (male pins: jumper-wire plugs stand on them)
      case 'term': return 10.4;
      case 'buzzer': return b.H;
      case 'usb': return 6.8;
      case 'esp32': return 15.2;
      case 'slide': return 7.6;
      case 'jack': return 6;
      case 'mic': return b.H + 0.4;
      case 'irrx': return 10;
      case 'dcjack': return 11;
      case 'btn': return b.H || 5;
      case 'pot': return 7.2;            // (the body; the shaft comes out through the lid)
      case 'usbc': return 8.4;
      case 'bat': return 21.5;
      default: return 3;
    }
  }

  // ================================================================ 🔧 the soldering guide

  /** Low parts first (they stay flat when the board is turned over), tall and strong ones last. */
  const ORDER = { res: 10, d4007: 12, d5822: 14, ne555: 20, cer: 22, ptc: 24, bc547: 30, vs1838: 32, led3: 34, led5: 36, irled: 36, mic: 38,
    slide: 40, btn: 42, hdr: 50, buck: 51, pam8403: 51, tp4056: 51, fhdr: 52, esp32: 54, elec: 60, r7805: 64, r3v3: 64, nmos: 64, pot: 66, buzzer: 70,
    jack35: 80, dcjack: 81, usba: 82, usbc: 83, term2: 84, bat18650: 90 };
  // (📚 modules: their socket with the other sockets, the male headers for wires with the other headers)
  const order = (lib) => (ORDER[lib] != null ? ORDER[lib] : P.LIB[lib] && P.LIB[lib].mod ? (P.LIB[lib].kind === 'plug' ? 52 : 50) : 45);
  const MOD_PLUG = 'సాకెట్ (ఫిమేల్ హెడర్): మాడ్యూల్‌నే సాకెట్‌లో గుచ్చి పెట్టి టంకం చేస్తే వరుస సరిగ్గా వస్తుంది (టంకం సాకెట్ కాళ్ళకే). ముందు ఒక పిన్, నిటారుగా చూసి, తర్వాత మిగతావి. మాడ్యూల్ తీసి పక్కన పెట్టండి (చివర్లో పెడతాం).';
  const MOD_WIRE = 'పిన్ హెడర్ (మేల్): పిన్నుల పొట్టి వైపు బోర్డ్‌లోకి; ముందు ఒక పిన్, నిటారుగా చూసి మిగతావి. మాడ్యూల్‌ని చివర్లో జంపర్ వైర్లతో (ఫిమేల్–ఫిమేల్) కలుపుతాం.';
  const mount = (lib) => MOUNT[lib] || (P.LIB[lib] && P.LIB[lib].mod ? (P.LIB[lib].kind === 'plug' ? MOD_PLUG : MOD_WIRE) : '');
  const MOUNT = {
    res: 'కాళ్ళని రెండు రంధ్రాల దూరానికి వంచి, బోర్డ్‌కి ఆనించి పెట్టండి. కింద కాళ్ళు కొంచెం బయటికి వంచితే పడిపోదు.',
    d4007: 'కాళ్ళని రంధ్రాల దూరానికి వంచి, బోర్డ్‌కి ఆనించి పెట్టండి. గీత బోర్డ్ మీది గీత వైపు.',
    d5822: 'కాళ్ళు లావు: రంధ్రాల దూరానికి వంచి, బోర్డ్‌కి ఆనించి. ఒక్కో కాలుకి 3–4 సెకన్ల వేడి.',
    ne555: 'IC ని నేరుగా టంకం చేయకండి: ముందు 8 పిన్ IC సాకెట్ పెట్టండి (సాకెట్ గుంత బోర్డ్ మీది గుంత గుర్తు వైపు). రెండు మూల పిన్నులు టంకం చేసి, సాకెట్ బోర్డ్‌కి ఆనిందో చూసి, తర్వాత మిగతా 6. IC ని చివర్లో పెడతాం.',
    cer: 'కాళ్ళు రంధ్రాల్లో పూర్తిగా లోపలికి. బిళ్ళ బోర్డ్‌కి కొంచెం పైన ఉన్నా పర్లేదు.',
    ptc: 'బోర్డ్ నుంచి 2–3 mm పైన ఉంచండి (ఎక్కువ కరెంట్ వస్తే ఇది వేడెక్కుతుంది).',
    bc547: 'బోర్డ్ నుంచి 3–5 mm పైకి ఉంచండి. ఒక్కో కాలుకి 2–3 సెకన్లే వేడి: ఎక్కువ వేడి తగిలితే పాడవుతుంది.',
    vs1838: 'బోర్డ్ నుంచి 3–5 mm పైకి, కన్ను బయటి వైపు. ఒక్కో కాలుకి 2–3 సెకన్లే వేడి.',
    led5: 'పొడవైన కాలు సరైన రంధ్రంలో. LED బోర్డ్ నుంచి 2–3 mm పైన ఉండొచ్చు (డబ్బా మూత రంధ్రంలోంచి కనిపించాలంటే ఇంకా పైకి).',
    led3: 'పొడవైన కాలు సరైన రంధ్రంలో. బోర్డ్ నుంచి 2–3 mm పైన ఉంచండి.',
    irled: 'పొడవైన కాలు సరైన రంధ్రంలో. TV వైపు చూపేలా కావాలంటే కాళ్ళు పొడవుగా ఉంచి తర్వాత వంచొచ్చు.',
    mic: 'త్వరగా (2 సెకన్లు) టంకం చేయండి: ఎక్కువ వేడి మైక్‌ని పాడు చేస్తుంది.',
    slide: 'స్విచ్‌ని బోర్డ్‌కి ఆనించి పట్టుకుని ఒక కాలు టంకం చేయండి; సరిగ్గా కూర్చుందో చూసి మిగతావి.',
    hdr: 'పిన్నుల పొట్టి వైపు బోర్డ్‌లోకి. ముందు ఒక్క పిన్ టంకం చేసి హెడర్ నిటారుగా ఉందో చూడండి; వంకరగా ఉంటే ఆ పిన్ మళ్ళీ వేడి చేసి సరిచేసి, తర్వాత మిగతావి.',
    buck: 'పిన్నుల పొట్టి వైపు బోర్డ్‌లోకి; ముందు ఒక పిన్, నిటారుగా ఉందో చూసి మిగతావి. బక్ మాడ్యూల్‌ని చివర్లో కలుపుతాం.',
    fhdr: 'మాడ్యూల్‌ని సాకెట్‌లో గుచ్చి పెట్టి టంకం చేస్తే వరుస సరిగ్గా వస్తుంది (టంకం సాకెట్ కాళ్ళకే, మాడ్యూల్‌కి కాదు). ముందు ఒక పిన్, నిటారుగా చూసి, తర్వాత మిగతావి.',
    esp32: 'రెండు 15-పిన్ ఫిమేల్ హెడర్లు. ESP32 ని వాటిలో గుచ్చి పెట్టి టంకం చేస్తే వరుసలు సరిగ్గా వస్తాయి. టంకం అయ్యాక ESP32 తీసి పక్కన పెట్టండి (చివర్లో పెడతాం).',
    elec: 'పొడవైన కాలు + గుర్తు ఉన్న చతురస్ర ప్యాడ్‌లో. కెపాసిటర్‌ని బోర్డ్‌కి ఆనించి పెట్టండి.',
    r7805: 'అక్షరాలు చూసి పిన్నుల వరుస సరిచూడండి. బోర్డ్ నుంచి 2–3 mm పైన ఉంచి టంకం; లావు కాళ్ళకి 3–4 సెకన్లు.',
    r3v3: 'అక్షరాలు చూసి పిన్నుల వరుస సరిచూడండి (7805 లా కాదు). బోర్డ్ నుంచి 2–3 mm పైన; లావు కాళ్ళకి 3–4 సెకన్లు.',
    buzzer: '+ గుర్తు బోర్డ్ మీది + వైపు. బజర్‌ని బోర్డ్‌కి ఆనించి పెట్టండి.',
    jack35: 'బోర్డ్ అంచుకి సరిగ్గా ఆనించి, నోరు బయటికి. లావు కాళ్ళకి ఎక్కువ సోల్డర్, 4–5 సెకన్ల వేడి (బలం కోసం).',
    usba: 'బోర్డ్ అంచుకి సరిగ్గా ఆనించి, నోరు బయటికి. పక్కన ఉన్న రెండు లావు కాళ్ళకి ఎక్కువ సోల్డర్, 4–5 సెకన్ల వేడి (బలం కోసం).',
    term2: 'వైర్ రంధ్రాలు బోర్డ్ అంచు వైపు. లావు కాళ్ళు: 3–4 సెకన్ల వేడి, సోల్డర్ రంధ్రం నిండేలా.',
    btn: 'నాలుగు కాళ్ళూ రంధ్రాల్లో పడేలా నొక్కి, బోర్డ్‌కి ఆనించి పెట్టండి. ఒక్కో కాలుకి 2–3 సెకన్లు.',
    pot: 'కాళ్ళు మూడూ రంధ్రాల్లో, నాబ్ పైకి. ముందు ఒక కాలు టంకం చేసి నిటారుగా ఉందో చూడండి; తర్వాత మిగతావి, చివర పక్క పట్టీలు (ఎక్కువ సోల్డర్, 4–5 సెకన్లు).',
    nmos: 'అక్షరాలు మీ వైపు: G D S వరుస సరిచూడండి. బోర్డ్ నుంచి 2–3 mm పైన; లావు కాళ్ళకి 3–4 సెకన్లు. ఎక్కువ కరెంట్ అయితే హీట్‌సింక్ పెట్టే చోటు వదలండి.',
    dcjack: 'బోర్డ్ అంచుకి సరిగ్గా ఆనించి, నోరు బయటికి. కాళ్ళు చదునుగా ఉంటాయి: రంధ్రం నిండేలా ఎక్కువ సోల్డర్, ఒక్కో కాలుకి 4–5 సెకన్లు (ప్లగ్ గుచ్చేటప్పుడు బలం కోసం).',
    usbc: 'Type-C బోర్డ్ VBUS, GND పిన్నులు ఈ రెండు రంధ్రాల్లోకి; నోరు బోర్డ్ అంచు బయటికి. దాని కింద రెండు వైపులా హాట్ గ్లూ పెడితే కేబుల్ గుచ్చేటప్పుడు కదలదు.',
    bat18650: 'హోల్డర్ + గుర్తు బోర్డ్ మీది + వైపు. పెగ్‌లు రంధ్రాల్లోకి, హోల్డర్ బోర్డ్‌కి ఆనాలి. లావు కాళ్ళకి 4–5 సెకన్లు. ⚠️ బ్యాటరీ ఇంకా పెట్టకండి (చివర్లో పెడతాం).',
    pam8403: 'పిన్నుల పొట్టి వైపు బోర్డ్‌లోకి; ముందు ఒక పిన్, నిటారుగా చూసి మిగతావి. PAM8403 మాడ్యూల్‌ని చివర్లో వైర్లతో కలుపుతాం.',
    tp4056: 'పిన్నుల పొట్టి వైపు బోర్డ్‌లోకి; ముందు ఒక పిన్, నిటారుగా చూసి మిగతావి. TP4056 మాడ్యూల్‌ని చివర్లో వైర్లతో కలుపుతాం.'
  };
  /** One short "which way round" each, for the last look. */
  const POL = { led5: 'పొడవు కాలు +', led3: 'పొడవు కాలు +', irled: 'పొడవు కాలు +', elec: 'తెల్ల గీత −', d4007: 'గీత = K', d5822: 'గీత = K', ne555: 'గుంత = పిన్ 1',
    bc547: 'చదునైన ముఖం: C B E', r7805: 'IN GND OUT', r3v3: 'GND OUT IN', buzzer: '+ గుర్తు', mic: 'డబ్బాకి కలిసిన కాలు −', vs1838: 'OUT GND VCC', buck: 'IN+ IN− OUT− OUT+',
    nmos: 'G D S', dcjack: 'మధ్య పిన్ +', usbc: 'VBUS GND', bat18650: '+ గుర్తు', pam8403: '+5V GND L G R', tp4056: 'B+ B− OUT+ OUT−' };
  const LED_TE = { red: 'ఎరుపు', green: 'ఆకుపచ్చ', blue: 'నీలం', yellow: 'పసుపు', white: 'తెలుపు', orange: 'నారింజ' };

  const PREP = [
    '🔥 సోల్డరింగ్ ఐరన్ (25–60 W) + దాని స్టాండ్, సోల్డర్ వైర్ (0.8 mm, లోపల ఫ్లక్స్ ఉన్నది), మొన తుడిచే తడి స్పాంజ్ / ఇత్తడి తీగ బంతి.',
    '✂️ కట్టర్ (కాళ్ళు కత్తిరించడానికి), 📏 మల్టీమీటర్, భాగాలన్నీ ఒక చిన్న డబ్బాలో.',
    '⚠️ ఐరన్ మొన 300°C కంటే ఎక్కువ వేడి: ఎప్పుడూ స్టాండ్‌లోనే పెట్టండి. దాని వైరుని మొన తాకనివ్వకండి.',
    '🌬️ గాలి వచ్చే చోట చేయండి, పొగ పీల్చకండి. 👓 కాళ్ళు కత్తిరించేటప్పుడు ముక్కలు ఎగురుతాయి: కళ్ళద్దాలు మంచిది.',
    '🧼 సీసం (lead) ఉన్న సోల్డర్ అయితే పని అయ్యాక చేతులు సబ్బుతో కడుక్కోండి; తినేవి దూరంగా. పిల్లలు దగ్గర లేకుండా.',
    '🧽 ఐరన్ వేడెక్కాక మొనకి కొంచెం సోల్డర్ పూసి స్పాంజ్‌తో తుడవండి: మొన మెరవాలి.'
  ];
  const HOW = [
    '1️⃣ భాగం కాళ్ళని బోర్డ్ పై వైపు (తెల్ల అక్షరాల వైపు) నుంచి రంధ్రాల్లో గుచ్చండి. బోర్డ్ తిప్పి కాళ్ళు కొంచెం బయటికి వంచండి (పడిపోకుండా).',
    '2️⃣ ఐరన్ మొనని ప్యాడ్‌కీ కాలుకీ ఒకేసారి తాకించి 1–2 సెకన్లు వేడి చేయండి.',
    '3️⃣ సోల్డర్ వైర్‌ని ప్యాడ్‌కి తాకించండి (మొనకి కాదు): కరిగి కాలు చుట్టూ పాకుతుంది.',
    '4️⃣ చిన్న కొండ (కోన్) ఆకారం రాగానే ముందు వైర్, తర్వాత ఐరన్ తీసేయండి. మొత్తం 3–4 సెకన్లు.',
    '5️⃣ చల్లారే వరకు 2–3 సెకన్లు కదపకండి. తర్వాత మిగిలిన కాలు కట్టర్‌తో కత్తిరించండి.',
    '✅ మంచి టంకం: మెరిసే, నునుపైన చిన్న కొండ. ❌ బంతిలా / మసకగా ఉంటే (చల్లని టంకం) మళ్ళీ వేడి చేయండి. రెండు ప్యాడ్‌లు కలిస్తే (వంతెన) మొనతో లాగేయండి.'
  ];

  function refCmp(a, b) {
    const ma = /^([A-Z]+)(\d*)$/.exec(a) || [a, a, ''], mb = /^([A-Z]+)(\d*)$/.exec(b) || [b, b, ''];
    return ma[1] === mb[1] ? (+ma[2] || 0) - (+mb[2] || 0) : ma[1] < mb[1] ? -1 : 1;
  }
  /** A value as it is written ("470" → "470Ω", "10uF 16V" → "10µF 16V"). */
  function valShow(pt) {
    let v = String(pt.val || '').replace(/uF/g, 'µF');
    if (pt.lib === 'res' && v && !/Ω|ohm/i.test(v)) v += 'Ω';
    return v;
  }
  /** A value as Jarvis says it. */
  function valSaid(pt) {
    const v = String(pt.val || '');
    if (/^led|irled/.test(pt.lib)) return LED_TE[pt.color] || (pt.lib === 'irled' ? 'IR' : '');
    if (pt.lib === 'res') {
      const m = /^\s*([\d.]+)\s*([kKM]?)/.exec(v);
      if (!m) return v;
      return m[1] + (m[2] === 'M' ? ' మెగా ఓమ్స్' : m[2] ? ' కిలో ఓమ్స్' : ' ఓమ్స్');
    }
    if (pt.lib === 'cer' || pt.lib === 'elec') {
      return v.replace(/\(.*?\)/g, '').replace(/([\d.]+)\s*(u|µ)F/gi, '$1 మైక్రో ఫారడ్').replace(/([\d.]+)\s*nF/gi, '$1 నానో ఫారడ్')
        .replace(/([\d.]+)\s*pF/gi, '$1 పికో ఫారడ్').replace(/([\d.]+)\s*V\b/g, ', $1 వోల్ట్').trim();
    }
    return '';
  }
  function drilled(pt) { const f = P.fpOf(pt); return f ? f.pads.filter((p) => p.d).length : 0; }
  function pinTe(pt, pin) {
    if (pt.lib === 'term2') return pt.ref + ' స్క్రూ ' + pin;
    const nm = P.LIB[pt.lib] ? P.pinsOf(pt)[pin] : null;
    return pt.ref + ' పిన్ ' + pin + (nm && nm !== pin ? ' (' + nm + ')' : '');
  }

  /** The steps: get ready, one good joint, each group of same parts (low to tall), look, beep test, chips / modules in, power on. */
  function solderSteps(D, opt) {
    opt = opt || {};
    const steps = [];
    steps.push({ k: 'prep', refs: [], show: 'none', title: '🧰 ముందు సిద్ధం చేసుకోండి', lines: PREP.slice(),
      say: 'ముందు సిద్ధం. ఐరన్, స్టాండ్, సోల్డర్ వైర్, తడి స్పాంజ్, కట్టర్, మల్టీమీటర్ పక్కన పెట్టుకోండి. ఐరన్ మొన చాలా వేడి: ఎప్పుడూ స్టాండ్‌లోనే పెట్టండి. గాలి వచ్చే చోట చేయండి.' });
    steps.push({ k: 'how', refs: [], show: 'none', title: '✨ ఒక మంచి టంకం ఎలా', lines: HOW.slice(),
      say: 'ఒక మంచి టంకం ఎలా. ఐరన్ మొనని ప్యాడ్‌కీ కాలుకీ ఒకేసారి తాకించి వేడి చేయండి. సోల్డర్ వైర్ ప్యాడ్‌కి తాకించండి, మొనకి కాదు. చిన్న కొండ ఆకారం రాగానే ముందు వైర్, తర్వాత ఐరన్ తీసేయండి. మొత్తం మూడు నాలుగు సెకన్లు.' });
    // the parts in groups of the same kind and value
    const groups = new Map();
    D.parts.slice().sort((a, b) => order(a.lib) - order(b.lib) || refCmp(a.ref, b.ref)).forEach((pt) => {
      const k = [pt.lib, pt.val || '', pt.fp || '', pt.color || ''].join('|');
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(pt);
    });
    const listed = [];
    groups.forEach((g) => { for (let i = 0; i < g.length; i += 6) listed.push(g.slice(i, i + 6)); });
    listed.sort((a, b) => order(a[0].lib) - order(b[0].lib) || refCmp(a[0].ref, b[0].ref));
    listed.forEach((g) => {
      const pt = g[0], L = P.LIB[pt.lib] || {}, refs = g.map((q) => q.ref);
      const v = valShow(pt), n = g.length;
      let te = L.te || pt.lib;
      if (pt.lib === 'ne555') te = 'IC సాకెట్ (8 పిన్) — ' + te + ' కోసం';
      if (pt.lib === 'esp32') te = 'ESP32 కోసం రెండు ఫిమేల్ హెడర్లు (15 పిన్)';
      let showV = v && !/^(NE555|ESP32)/i.test(v) && pt.lib !== 'term2' && pt.lib !== 'usba' && te.toLowerCase().indexOf(v.toLowerCase()) < 0 ? ' · ' + v : '';
      if (/^led|irled/.test(pt.lib) && LED_TE[pt.color]) { showV = ''; te = LED_TE[pt.color] + ' ' + te; }
      const title = refs.join(', ') + showV + ' · ' + te + (n > 1 ? ' (×' + n + ')' : '');
      const lines = ['📍 బోర్డ్ మీద ' + refs.join(', ') + ' అని రాసిన చోటు (3D లో మెరుస్తోంది).'];
      if (pt.lib === 'res') {
        const b = P.bands(pt.val);
        if (b) lines.push('🎨 రంగు గీతలు: ' + b.map((x) => x.te).join(', ') + '. (మల్టీమీటర్‌తో కొలిచి కూడా చూడొచ్చు.)');
      } else if (pt.lib === 'cer') lines.push('🔢 విలువ: ' + v + ' (బిళ్ళ మీద రాసి ఉంటుంది).');
      else if (pt.lib === 'elec') lines.push('🔋 ' + v + ': డబ్బా మీద రాసిన వోల్టేజ్ ఇంతకంటే తక్కువ ఉండకూడదు.');
      else if (/^led|irled/.test(pt.lib) && LED_TE[pt.color]) lines.push('💡 రంగు: ' + LED_TE[pt.color] + '.');
      if (L.how) lines.push((L.polar ? '' : '↔️ ') + L.how);
      if (mount(pt.lib)) lines.push('🛠️ ' + mount(pt.lib));
      const j = g.reduce((s, q) => s + drilled(q), 0);
      lines.push('🔥 కింది వైపు ' + j + ' టంకాలు. తర్వాత కాళ్ళు కత్తిరించండి.');
      if (n === 1 && pt.note) lines.push('ℹ️ ' + pt.note);
      if (pt.lib === 'fhdr' || pt.lib === 'esp32' || L.mod) lines.push('⚠️ మాడ్యూల్ పిన్ పేర్లు బోర్డ్ మీది పేర్లతో సరిపోవాలి (ముందే చూసుకోండి).');
      const said = [refs.join(', ') + '.', valSaid(pt), te.replace(/\(.*?\)/g, '') + (n > 1 ? ', ' + n + ' ' : '') + '.', L.how ? L.how.split(/[.:]/)[0] + '.' : '',
        mount(pt.lib) ? mount(pt.lib).split('.')[0] + '.' : ''].filter(Boolean).join(' ');
      steps.push({ k: 'part', refs, lib: pt.lib, title, lines, say: spoken(said), joints: j });
    });
    // 🔍 the last look
    const pol = [];
    const polOf = (q) => POL[q.lib] || (P.LIB[q.lib] && P.LIB[q.lib].mod ? Object.keys(P.pinsOf(q)).map((k) => P.pinsOf(q)[k]).join(' ') : null);
    listed.forEach((g) => { if (polOf(g[0])) pol.push(g.map((q) => q.ref).join(', ') + ' (' + polOf(g[0]) + ')'); });
    steps.push({ k: 'look', refs: [], show: 'all', title: '🔍 చెక్ 1: కళ్ళతో చూడండి', lines: [
      '🔍 ప్రతి టంకం మెరుస్తూ చిన్న కొండలా ఉందా? మసకగా / బంతిలా ఉంటే మళ్ళీ వేడి చేయండి.',
      '🔍 రెండు ప్యాడ్‌ల మధ్య సోల్డర్ వంతెన లేదు కదా? ఉంటే ఐరన్ మొనతో లాగేయండి.',
      '✂️ కాళ్ళు అన్నీ కత్తిరించారా (1–2 mm మిగిలేలా)?'
    ].concat(pol.length ? ['➡️ దిశ మళ్ళీ చూడండి: ' + pol.join(' · ')] : []),
    say: 'చెక్ ఒకటి. ప్రతి టంకం మెరుస్తూ చిన్న కొండలా ఉందా, రెండు ప్యాడ్‌ల మధ్య వంతెన లేదు కదా చూడండి.' + (pol.length ? ' దిశ ఉన్న భాగాలు మళ్ళీ చూడండి.' : '') });
    // 📏 the beep test between each supply and GND
    const gnd = D.nets.find((n) => C.isGnd(n.n));
    const pw = D.nets.filter((n) => C.isPower(n.n) && !C.isGnd(n.n)).slice(0, 3);
    const where = (net) => {
      const conn = D.parts.filter((q) => /^(term2|usba|jack35|hdr|fhdr|buck|dcjack|usbc|bat18650|tp4056)$/.test(q.lib) || (P.LIB[q.lib] && P.LIB[q.lib].mod));
      for (const q of conn) { const p = net.p.find((s) => s.split('.')[0] === q.ref); if (p) return pinTe(q, p.split('.')[1]); }
      const p = net.p[0];
      return p ? pinTe(D.parts.find((q) => q.ref === p.split('.')[0]) || { ref: p.split('.')[0], lib: '' }, p.split('.')[1]) : '';
    };
    const beep = ['🔌 ఇంకా బ్యాటరీ / కరెంట్ కలపకండి.',
      '📏 మల్టీమీటర్ నాబ్‌ని బీప్ గుర్తు (🔊) మీద పెట్టండి. రెండు ప్రోబ్‌లు ఒకదానికొకటి తాకిస్తే బీప్ వస్తే మీటర్ సరిగ్గా ఉంది.'];
    if (gnd && pw.length) pw.forEach((n) => beep.push('👉 ఒక ప్రోబ్ ' + n.n + ' (' + where(n) + '), ఇంకోటి GND (' + where(gnd) + '): ఆగకుండా బీప్ రాకూడదు. (కెపాసిటర్ నిండుతూ ఒక్క క్షణం బీప్ వచ్చి ఆగితే పర్లేదు.)'));
    else beep.push('👉 బోర్డ్ కి కరెంట్ వచ్చే రెండు చోట్ల (+ , −) మధ్య ఆగకుండా బీప్ రాకూడదు.');
    beep.push('❌ ఆగకుండా బీప్ వస్తే షార్ట్ ఉంది: కరెంట్ ఇవ్వకండి. సోల్డర్ వంతెనలు వెతకండి.');
    steps.push({ k: 'beep', refs: [], show: 'all', title: '📏 చెక్ 2: మల్టీమీటర్ బీప్', lines: beep,
      say: 'చెక్ రెండు. కరెంట్ కలపకుండా, మల్టీమీటర్ బీప్ మోడ్‌లో, ప్లస్ మరియు GND మధ్య ఆగకుండా బీప్ రాకూడదు. వస్తే షార్ట్ ఉంది, కరెంట్ ఇవ్వకండి.' });
    // 🔌 chips and modules in their sockets
    const ins = [];
    D.parts.forEach((q) => {
      if (q.lib === 'ne555') ins.push('🔲 ' + q.ref + ': NE555 ని సాకెట్‌లో పెట్టండి, చిప్ గుంత సాకెట్ గుంత వైపు. కాళ్ళు కొంచెం బయటికి ఉంటాయి: టేబుల్ మీద పక్కకి ఆనించి కొద్దిగా లోపలికి వంచి, అన్ని కాళ్ళూ రంధ్రాల మీద పెట్టి ఒకేసారి నొక్కండి.');
      if (q.lib === 'esp32') ins.push('📶 ' + q.ref + ': ESP32 ని సాకెట్లలో గుచ్చండి: దాని మీది పిన్ పేర్లు బోర్డ్ మీది పేర్లతో సరిపోవాలి, యాంటెనా బోర్డ్ బయటి వైపు.');
      if (q.lib === 'fhdr') ins.push('🧩 ' + q.ref + ': మాడ్యూల్ గుచ్చండి: మాడ్యూల్ మీది పిన్ పేర్లు, బోర్డ్ మీది పేర్లు ఒకటే వరుసలో ఉండాలి. అది కోరే వోల్టేజ్ కూడా చూడండి.');
      if (q.lib === 'buck') ins.push('🔧 ' + q.ref + ': బక్ మాడ్యూల్‌ని ముందు మల్టీమీటర్‌తో 5.1 V కి సెట్ చేసి, తర్వాతే IN+ IN− OUT− OUT+ పేర్లు సరిపోల్చి కలపండి.');
      if (q.lib === 'pam8403') ins.push('🔊 ' + q.ref + ': PAM8403 మాడ్యూల్‌ని +5V GND L G R పేర్లు సరిపోల్చి వైర్లతో కలపండి. స్పీకర్ వైర్లు దాని L+ L− R+ R− కి నేరుగా: ఏవీ GND కి కాదు.');
      if (q.lib === 'tp4056') ins.push('🔋 ' + q.ref + ': TP4056 మాడ్యూల్: B+ B− బ్యాటరీ వైపు, OUT+ OUT− బోర్డ్ వైపు (పేర్లు సరిపోల్చి). బోర్డ్ GND OUT− కే.');
      if (q.lib === 'bat18650') ins.push('🔋 ' + q.ref + ': 18650 బ్యాటరీ చివర్లో, + గుర్తు చూసి పెట్టండి (బ్యాటరీ మీద + ఉబ్బిన వైపు).');
      const lm = P.LIB[q.lib];
      if (lm && lm.mod) {
        const nm = P.pinsOf(q), order = Object.keys(nm).map((k) => nm[k]).join(' ');
        ins.push((lm.kind === 'plug' ? '🧩 ' + q.ref + ': ' + lm.te + ': సాకెట్‌లో గుచ్చండి (' + order + '): మాడ్యూల్ మీది పేర్లు బోర్డ్ మీది పేర్ల ఎదురుగా.'
          : '🔌 ' + q.ref + ': ' + lm.te + ': ' + order + ' పేర్లు సరిపోల్చి జంపర్ వైర్లతో కలపండి (🔌 వైర్ పట్టీ చూడండి).') + (lm.mod.v[1] < 4 ? ' ⚠️ ' + lm.mod.v[1] + ' V లోపే!' : ''));
      }
    });
    if (ins.length) steps.push({ k: 'chips', refs: D.parts.filter((q) => /^(ne555|esp32|fhdr|buck|pam8403|tp4056|bat18650)$/.test(q.lib) || (P.LIB[q.lib] && P.LIB[q.lib].mod)).map((q) => q.ref), show: 'all', title: '🔲 IC / మాడ్యూల్స్ పెట్టండి', lines: ins.concat(['⚠️ కరెంట్ తీసేసి ఉన్నప్పుడే పెట్టండి / తీయండి.']),
      say: 'ఇప్పుడు IC, మాడ్యూల్స్ వాటి సాకెట్లలో పెట్టండి. గుంత, పిన్ పేర్లు సరిపోవాలి. కరెంట్ తీసేసి ఉన్నప్పుడే.' });
    // ⚡ power on
    const names = pw.map((n) => n.n).join(' ');
    const has = (lib) => D.parts.some((q) => q.lib === lib);
    const supply = has('bat18650') ? '18650 బ్యాటరీ' : has('usbc') ? 'Type-C ఛార్జర్ కేబుల్' : has('dcjack') ? 'అడాప్టర్ (DC జాక్)' : /9V/i.test(names) ? '9V బ్యాటరీ' : /12V|VIN|BAT|BIKE/i.test(names) ? '12V (బైక్ / అడాప్టర్)' : /5V|USB|VBUS/i.test(names) ? 'USB 5V' : 'మీ సప్లై';
    steps.push({ k: 'power', refs: [], show: 'all', title: '⚡ మొదటిసారి కరెంట్', lines: [
      '🔋 ఇప్పుడు ' + supply + ' కలపండి: + , − సరిగ్గా చూసి.',
      '👀 మొదటి 10 సెకన్లు చూడండి: ఏదైనా భాగం బాగా వేడెక్కినా, వాసన / పొగ వచ్చినా వెంటనే కరెంట్ తీసేయండి.',
      '⚡ నడిపించు ట్యాబ్‌లో చూపినట్టే పనిచేయాలి' + (opt.simLine ? ': ' + opt.simLine : '.'),
      '🎉 పనిచేస్తే: బోర్డ్ రెడీ! పనిచేయకపోతే: చెక్ 1, 2 మళ్ళీ, తర్వాత ⚡ లో వోల్టేజ్‌లు మల్టీమీటర్‌తో పోల్చండి.'
    ], say: 'ఇప్పుడు ' + supply + ' కలపండి. మొదటి పది సెకన్లు చూడండి: ఏదైనా వేడెక్కినా, వాసన వచ్చినా వెంటనే కరెంట్ తీసేయండి.' });
    steps.forEach((s, i) => { s.n = i + 1; s.of = steps.length; });
    return steps;
  }

  // ================================================================ 📦 the box (numbers only; pcb3d.js makes the shapes and STL)

  const BOX = { wall: 2, floor: 2, lid: 2, gap: 1.5, fit: 0.3, post: 5, lip: 4, lipT: 1.2, lipL: 8, postR: 3.25, screwR: 1.3 };

  function boxPlan(D, opt) {
    opt = opt || {};
    const B = D.board, T = B.thick || 1.6, K = BOX, notes = [], warns = [];
    let tallest = 0, tref = '';
    D.parts.forEach((pt) => { const h = tall(pt); if (h > tallest) { tallest = h; tref = pt.ref; } });
    const extra = clamp(Math.round(num(opt.extra, 0)), -8, 40);
    const room = r2(Math.max(3, tallest + 2 + extra));
    // the inside: the board and a small gap, more where a part hangs past the board (not the edge sockets: they meet the wall)
    let x0 = -K.gap, y0 = -K.gap, x1 = B.w + K.gap, y1 = B.h + K.gap;
    D.parts.forEach((pt) => {
      const f = P.fpOf(pt);
      if (!f || f.edge) return;
      const c = C.courtOf(pt, pt.x, pt.y, pt.rot);
      x0 = Math.min(x0, c[0] - 0.6); y0 = Math.min(y0, c[1] - 0.6); x1 = Math.max(x1, c[2] + 0.6); y1 = Math.max(y1, c[3] + 0.6);
    });
    [x0, y0] = [r2(x0), r2(y0)]; [x1, y1] = [r2(x1), r2(y1)];
    const zB = K.floor + K.post, zTop = zB + T, zLid = r2(zTop + room);
    const X0 = r2(x0 - K.wall), Y0 = r2(y0 - K.wall), X1 = r2(x1 + K.wall), Y1 = r2(y1 + K.wall);
    const inner = { N: y0, S: y1, W: x0, E: x1 };
    const gapTo = (c, side) => (side === 'N' ? c[1] - y0 : side === 'S' ? y1 - c[3] : side === 'W' ? c[0] - x0 : x1 - c[2]);
    const at = (pt, lx, ly) => { const q = C.rot(lx, ly, pt.rot || 0); return [pt.x + q[0], pt.y + q[1]]; };
    const uOf = (side, p) => (side === 'N' || side === 'S' ? p[0] : p[1]);
    const cuts = [], lid = [];
    const cutRect = (pt, side, p, half, za, zb, why) => cuts.push({ side, u0: uOf(side, p) - half, u1: uOf(side, p) + half, z0: za, z1: zb, ref: pt.ref, why });
    const cutRound = (pt, side, p, z, r, why) => cuts.push({ side, uc: uOf(side, p), zc: z, r, ref: pt.ref, why });
    D.parts.forEach((pt) => {
      const c = C.courtOf(pt, pt.x, pt.y, pt.rot);
      switch (pt.lib) {
        case 'usba': {
          const s = C.sideAfter('N', pt.rot), d = gapTo(c, s);
          if (d > 6) warns.push('⚠️ ' + pt.ref + ' (USB) డబ్బా గోడకి ' + Math.round(d) + ' mm దూరంగా ఉంది: కేబుల్ గుచ్చడం కష్టం. 🧩 PCB లో దాన్ని బోర్డ్ అంచుకి జరపండి (నోరు బయటికి).');
          else cutRect(pt, s, at(pt, 0, -3.3), 13.1 / 2 + 0.6, zTop - 0.4, zTop + 6.8 + 0.6, 'USB');
          break;
        }
        case 'jack35': {
          const s = C.sideAfter('N', pt.rot), d = gapTo(c, s);
          if (d > 6) warns.push('⚠️ ' + pt.ref + ' (3.5mm జాక్) గోడకి ' + Math.round(d) + ' mm దూరంగా ఉంది: 🧩 PCB లో అంచుకి జరపండి.');
          else cutRound(pt, s, at(pt, 0.9, -3.2), zTop + 3, 3.6, 'AUX');
          break;
        }
        case 'term2': {
          const s = C.sideAfter('S', pt.rot), d = gapTo(c, s);
          if (d > 8) warns.push('⚠️ ' + pt.ref + ' (స్క్రూ టెర్మినల్) గోడకి ' + Math.round(d) + ' mm దూరం: వైర్లు గోడ రంధ్రం గుండా రావాలంటే 🧩 PCB లో అంచుకి జరపండి.');
          else cutRect(pt, s, at(pt, 0, 0), 10.1 / 2 + 0.6, zTop + 1.2, zTop + 6.6, 'వైర్లు');
          break;
        }
        case 'esp32': {
          const s = C.sideAfter('S', pt.rot), d = gapTo(c, s);
          if (d > 8) notes.push('ℹ️ ' + pt.ref + ' (ESP32) USB గోడకి దూరం: ప్రోగ్రామ్ చేసేటప్పుడు మూత తీసి కేబుల్ పెట్టండి.');
          else cutRect(pt, s, at(pt, 0, 23.5), 6.2, zTop + 13.9 - 4.2, zTop + 13.9 + 4.2, 'ESP32 USB');
          break;
        }
        case 'vs1838': {
          const s = C.sideAfter('S', pt.rot), d = gapTo(c, s);
          if (d <= 8) cutRound(pt, s, at(pt, 0, 2.1), zTop + 7.4, 3, 'IR కన్ను');
          else lid.push({ x: pt.x, y: pt.y, r: 3, ref: pt.ref, why: 'IR కన్ను' });
          break;
        }
        case 'led5': case 'irled': lid.push({ x: pt.x, y: pt.y, r: 2.8, ref: pt.ref, why: 'LED' }); break;
        case 'led3': lid.push({ x: pt.x, y: pt.y, r: 1.8, ref: pt.ref, why: 'LED' }); break;
        case 'buzzer': lid.push({ x: pt.x, y: pt.y, r: 2.5, ref: pt.ref, why: 'శబ్దం' }); break;
        case 'mic': lid.push({ x: pt.x, y: pt.y, r: 2.5, ref: pt.ref, why: 'మైక్' }); break;
        case 'btn':
          lid.push({ x: pt.x, y: pt.y, r: 2.2, ref: pt.ref, why: 'బటన్' });
          notes.push('👇 ' + pt.ref + ' బటన్ మూత కింద ఉంటుంది: పొడవైన బటన్ (6×6×13 mm) కొంటే మూత రంధ్రంలోంచి వేలితో నొక్కొచ్చు; లేకపోతే పెన్ను మొనతో.');
          break;
        case 'pot': {
          const p = at(pt, 0.5, 0);
          lid.push({ x: p[0], y: p[1], r: 3.6, ref: pt.ref, why: 'నాబ్' });
          notes.push('🎛️ ' + pt.ref + ' నాబ్ షాఫ్ట్ మూత రంధ్రంలోంచి బయటికి వస్తుంది (15 mm షాఫ్ట్ ఉన్నది కొనండి); నాబ్ బయటి నుంచి పెట్టండి.');
          break;
        }
        case 'dcjack': {
          const s = C.sideAfter('W', pt.rot), d = gapTo(c, s);
          if (d > 6) warns.push('⚠️ ' + pt.ref + ' (DC జాక్) గోడకి ' + Math.round(d) + ' mm దూరంగా ఉంది: అడాప్టర్ పిన్ అందదు. 🧩 PCB లో అంచుకి జరపండి (నోరు బయటికి).');
          else cutRound(pt, s, at(pt, -7.25, 0), zTop + 6.4, 4.8, 'అడాప్టర్');
          break;
        }
        case 'usbc': {
          const s = C.sideAfter('N', pt.rot), d = gapTo(c, s);
          if (d > 6) warns.push('⚠️ ' + pt.ref + ' (Type-C) గోడకి ' + Math.round(d) + ' mm దూరంగా ఉంది: కేబుల్ అందదు. 🧩 PCB లో అంచుకి జరపండి (నోరు బయటికి).');
          else cutRect(pt, s, at(pt, 0, -6.2), 5.2, zTop + 2.6, zTop + 8.6, 'Type-C');
          break;
        }
        case 'tp4056':
          notes.push('🔌 ' + pt.ref + ' TP4056 మాడ్యూల్‌ని డబ్బా లోపల గోడకి అంటించండి; దాని Type-C కోసం ఆ గోడలో చిన్న రంధ్రం (సుమారు 10 × 5 mm) ప్రింట్ అయ్యాక మీరే కత్తిరించండి.');
          break;
        case 'slide': {
          const a = at(pt, -3.0, -1.3), b = at(pt, 1.8, 0.9);
          lid.push({ x0: Math.min(a[0], b[0]), y0: Math.min(a[1], b[1]), x1: Math.max(a[0], b[0]), y1: Math.max(a[1], b[1]), ref: pt.ref, why: 'స్విచ్' });
          notes.push('🎚️ ' + pt.ref + ' స్విచ్ మూత కింద ఉంటుంది: మూత రంధ్రం గుండా పెన్ను మొనతో జరపండి.');
          break;
        }
        case 'hdr': {
          let best = null;
          ['N', 'S', 'W', 'E'].forEach((s) => { const d = gapTo(c, s); if (!best || d < best.d) best = { s, d }; });
          if (best.d <= 12) cutRound(pt, best.s, [pt.x, pt.y], Math.min(zLid - 5.2, zTop + 12), 3.5, 'వైర్లు');
          break;
        }
        default: {
          // 📚 modules: wires out through the nearest wall; a few need the outside world
          const lm = P.LIB[pt.lib];
          if (!lm || !lm.mod) break;
          if (lm.kind === 'wire') {
            let best = null;
            ['N', 'S', 'W', 'E'].forEach((s) => { const d = gapTo(c, s); if (!best || d < best.d) best = { s, d }; });
            if (best.d <= 12) cutRound(pt, best.s, [pt.x, pt.y], Math.min(zLid - 5.2, zTop + 12), 3.5, 'వైర్లు');
          }
          if (pt.lib === 'oled') notes.push('🖥️ ' + pt.ref + ' OLED స్క్రీన్ మూత కింద ఉంటుంది: తెల్ల / పారదర్శక (transparent) మూత ప్రింట్ చేయండి, లేదా మూతలో 24 × 13 mm కిటికీ కత్తిరించండి.');
          if (pt.lib === 'hcsr04') notes.push('🦇 ' + pt.ref + ' HC-SR04 "కళ్ళు" బయటికి చూడాలి: దాని ముందు గోడలో 26 mm దూరంలో రెండు 16.5 mm గుండ్రటి రంధ్రాలు కత్తిరించండి.');
          if (pt.lib === 'dht11' || pt.lib === 'bme280') notes.push('🌬️ ' + pt.ref + ' సెన్సార్‌కి బయటి గాలి తగలాలి: గాలి సందులు (🌬️) ఆన్ చేయండి; ESP32 / రెగ్యులేటర్ వేడికి దూరంగా ఉంచండి.');
          if (pt.lib === 'max98357') notes.push('🔊 ' + pt.ref + ' స్పీకర్ వైర్లకి గోడలో చిన్న రంధ్రం; స్పీకర్ ముందు మూతలో చిన్న రంధ్రాలు.');
          if (pt.lib === 'pir') notes.push('👀 ' + pt.ref + ' PIR గుమ్మటం డబ్బా బయట ఉండాలి (23 mm గుండ్రటి రంధ్రంలోంచి): ప్లాస్టిక్ / గాజు వెనక అది చూడలేదు.');
          if (pt.lib === 'relay') notes.push('⚠️ ' + pt.ref + ' రిలే: ఈ డబ్బాలో తక్కువ వోల్టేజ్ DC మాత్రమే (24 V వరకు). ఇంటి 230 V కరెంట్ ఎప్పుడూ కాదు.');
        }
      }
    });
    // fit the cuts on their walls (away from the corners, under the lid), round ones that touch others become one opening
    const span = { N: [x0 + 1, x1 - 1], S: [x0 + 1, x1 - 1], W: [y0 + 1, y1 - 1], E: [y0 + 1, y1 - 1] };
    const zLo = K.floor + 0.8, zHi = zLid - 1.2;
    const fitted = [];
    cuts.forEach((q) => {
      const s = span[q.side];
      if (q.r) {
        if (q.zc + q.r > zHi) q.zc = zHi - q.r;
        if (q.uc - q.r < s[0] || q.uc + q.r > s[1] || q.zc - q.r < zLo) { q.u0 = q.uc - q.r; q.u1 = q.uc + q.r; q.z0 = q.zc - q.r; q.z1 = q.zc + q.r; delete q.r; }
      }
      if (!q.r) {
        q.u0 = Math.max(s[0], q.u0); q.u1 = Math.min(s[1], q.u1); q.z0 = Math.max(zLo, q.z0); q.z1 = Math.min(zHi, q.z1);
        if (q.u1 - q.u0 < 1 || q.z1 - q.z0 < 1) { warns.push('⚠️ ' + q.ref + ' కి గోడలో రంధ్రం పెట్టలేకపోయాను (చోటు లేదు).'); return; }
      }
      fitted.push(q);
    });
    const box = (q) => (q.r ? [q.uc - q.r, q.zc - q.r, q.uc + q.r, q.zc + q.r] : [q.u0, q.z0, q.u1, q.z1]);
    let merged = true;
    while (merged) {
      merged = false;
      for (let i = 0; i < fitted.length && !merged; i++) {
        for (let j = i + 1; j < fitted.length && !merged; j++) {
          const a = fitted[i], b = fitted[j];
          if (a.side !== b.side) continue;
          const A = box(a), Bx = box(b);
          if (A[0] > Bx[2] + 1.2 || Bx[0] > A[2] + 1.2 || A[1] > Bx[3] + 1.2 || Bx[1] > A[3] + 1.2) continue;
          fitted[i] = { side: a.side, u0: Math.min(A[0], Bx[0]), u1: Math.max(A[2], Bx[2]), z0: Math.min(A[1], Bx[1]), z1: Math.max(A[3], Bx[3]), ref: a.ref + ', ' + b.ref, why: a.why === b.why ? a.why : a.why + ' + ' + b.why };
          fitted.splice(j, 1);
          merged = true;
        }
      }
    }
    // lid holes: inside the walls, not touching each other (those that do become one opening)
    const lidBox = (q) => (q.r ? [q.x - q.r, q.y - q.r, q.x + q.r, q.y + q.r] : [q.x0, q.y0, q.x1, q.y1]);
    const holesL = lid.filter((q) => { const b = lidBox(q); return b[0] > x0 + 2 && b[1] > y0 + 2 && b[2] < x1 - 2 && b[3] < y1 - 2; });
    merged = true;
    while (merged) {
      merged = false;
      for (let i = 0; i < holesL.length && !merged; i++) {
        for (let j = i + 1; j < holesL.length && !merged; j++) {
          const A = lidBox(holesL[i]), Bx = lidBox(holesL[j]);
          if (A[0] > Bx[2] + 1.0 || Bx[0] > A[2] + 1.0 || A[1] > Bx[3] + 1.0 || Bx[1] > A[3] + 1.0) continue;
          holesL[i] = { x0: Math.min(A[0], Bx[0]), y0: Math.min(A[1], Bx[1]), x1: Math.max(A[2], Bx[2]), y1: Math.max(A[3], Bx[3]), ref: holesL[i].ref + ', ' + holesL[j].ref, why: holesL[i].why };
          holesL.splice(j, 1);
          merged = true;
        }
      }
    }
    // posts at the board's screw holes, or small rests under its corners
    const posts = C.holes(D).map((h) => ({ x: h.x, y: h.y, r: K.postR, ri: K.screwR, z0: K.floor, z1: zB }));
    const rests = [];
    if (!posts.length) {
      const pads = [];
      D.parts.forEach((pt) => C.partPads(pt, pt.x, pt.y, pt.rot).forEach((p) => pads.push(p)));
      const s = 2.5;
      [[x0, y0, s, s], [B.w - s, y0, x1, s], [B.w - s, B.h - s, x1, y1], [x0, B.h - s, s, y1]].forEach((q) => {
        const hit = pads.some((p) => p.x + p.w / 2 > q[0] - 0.4 && p.x - p.w / 2 < q[2] + 0.4 && p.y + p.h / 2 > q[1] - 0.4 && p.y - p.h / 2 < q[3] + 0.4);
        if (hit) { warns.push('⚠️ ఒక మూలలో ప్యాడ్ ఉంది: అక్కడ బోర్డ్ కింద దిమ్మె పెట్టలేదు.'); return; }
        rests.push({ x0: q[0], y0: q[1], x1: q[2], y1: q[3], z0: K.floor, z1: zB });
      });
      notes.push('🔩 బోర్డ్‌కి స్క్రూ రంధ్రాలు లేవు: మూలల్లో చిన్న దిమ్మెల మీద కూర్చుంటుంది; కదలకుండా ఒక చుక్క హాట్‌గ్లూ. (🧩 PCB లో 🔩 రంధ్రాలు పెడితే డబ్బాలో స్క్రూ స్తంభాలు వస్తాయి.)');
    } else notes.push('🔩 బోర్డ్ ' + posts.length + ' స్తంభాల మీద: M3 × 6–8 mm స్క్రూలు (ప్లాస్టిక్‌లో తిప్పితే అవే దారి చేసుకుంటాయి).');
    // 🌬️ air slots near the top of the two long walls (for parts that get warm)
    const warm = D.parts.some((pt) => /^(r7805|r3v3|buck|esp32|d5822|tp4056|pam8403|max98357)$/.test(pt.lib));
    const ventsOn = opt.vents == null ? warm : !!opt.vents;
    const vents = [];
    if (ventsOn) {
      const sides = (x1 - x0) >= (y1 - y0) ? ['N', 'S'] : ['W', 'E'];
      sides.forEach((s) => {
        const a = span[s][0] + K.lipL + 2, b = span[s][1] - K.lipL - 2;
        for (let u = a; u + 10 <= b; u += 14) {
          [zLid - 4, zLid - 7].forEach((z) => {
            if (z - 0.8 < zTop + 1) return;
            const v = { side: s, u0: u, u1: u + 10, z0: z - 0.8, z1: z + 0.8, why: 'గాలి', vent: true };
            const near = fitted.concat(vents).some((q) => {
              if (q.side !== s) return false;
              const qb = box(q);
              return qb[0] < v.u1 + 1.5 && v.u0 < qb[2] + 1.5 && qb[1] < v.z1 + 1.5 && v.z0 < qb[3] + 1.5;
            });
            if (!near) vents.push(v);
          });
        }
      });
    }
    const why = (q) => q.why + ' (' + q.ref + ')';
    const side = { N: 'పై', S: 'కింది', W: 'ఎడమ', E: 'కుడి' };
    fitted.forEach((q) => notes.push('🕳️ ' + side[q.side] + ' గోడలో: ' + why(q)));
    if (holesL.length) notes.push('🕳️ మూతలో: ' + holesL.map(why).join(', '));
    if (vents.length) notes.push('🌬️ వేడెక్కే భాగాలు ఉన్నాయి: రెండు పొడవు గోడల పైన గాలి సందులు (' + vents.length + ').');
    return {
      K, size: { w: r2(X1 - X0), d: r2(Y1 - Y0), h: r2(zLid + K.lid) },
      box: { x0, y0, x1, y1, X0, Y0, X1, Y1, zB, zTop, zLid, T, room, tallest: r2(tallest), tref, extra, ventsOn, warm, inner },
      cuts: fitted.concat(vents), lid: holesL, posts, rests, notes, warns
    };
  }

  /** The plan as simple solids in board numbers (x right, y down, z up): plates with holes, walls with openings, tubes, blocks. */
  function solids(plan) {
    const K = plan.K, b = plan.box, out = [];
    out.push({ piece: 'base', t: 'plate', x0: b.X0, y0: b.Y0, x1: b.X1, y1: b.Y1, z0: 0, z1: K.floor, holes: [] });
    // (each opening a hair different, a few hundredths of a mm: openings in a row never share one straight line, so the shape's
    // triangles close cleanly in every 3D-print program)
    const wh = (s) => plan.cuts.filter((q) => q.side === s).map((q, k) => {
      const j = 0.011 * (k + 1);
      return q.r ? Object.assign({}, q, { uc: q.uc + j, zc: q.zc + j * 0.7 }) : Object.assign({}, q, { u0: q.u0 - j, u1: q.u1 + j * 0.7, z0: q.z0 - j * 1.3, z1: q.z1 + j * 0.9 });
    });
    const lh = plan.lid.map((q, k) => {
      const j = 0.011 * (k + 1);
      return q.r ? Object.assign({}, q, { x: q.x + j, y: q.y + j * 0.7 }) : Object.assign({}, q, { x0: q.x0 - j, x1: q.x1 + j * 0.7, y0: q.y0 - j * 1.3, y1: q.y1 + j * 0.9 });
    });
    out.push({ piece: 'base', t: 'wall', side: 'N', u0: b.X0, u1: b.X1, w0: b.Y0, w1: b.y0, z0: K.floor, z1: b.zLid, holes: wh('N') });
    out.push({ piece: 'base', t: 'wall', side: 'S', u0: b.X0, u1: b.X1, w0: b.y1, w1: b.Y1, z0: K.floor, z1: b.zLid, holes: wh('S') });
    out.push({ piece: 'base', t: 'wall', side: 'W', u0: b.y0, u1: b.y1, w0: b.X0, w1: b.x0, z0: K.floor, z1: b.zLid, holes: wh('W') });
    out.push({ piece: 'base', t: 'wall', side: 'E', u0: b.y0, u1: b.y1, w0: b.x1, w1: b.X1, z0: K.floor, z1: b.zLid, holes: wh('E') });
    plan.posts.forEach((p) => out.push({ piece: 'base', t: 'tube', x: p.x, y: p.y, r: p.r, ri: p.ri, z0: p.z0, z1: p.z1 }));
    plan.rests.forEach((q) => out.push({ piece: 'base', t: 'block', x0: q.x0, y0: q.y0, x1: q.x1, y1: q.y1, z0: q.z0, z1: q.z1 }));
    out.push({ piece: 'lid', t: 'plate', x0: b.X0, y0: b.Y0, x1: b.X1, y1: b.Y1, z0: b.zLid, z1: b.zLid + K.lid, holes: lh });
    // the lid's four corner corners hang inside the walls and hold it (pressed in)
    const f = K.fit, t = K.lipT, L = K.lipL, za = b.zLid - K.lip, zb = b.zLid;
    [[b.x0 + f, b.y0 + f, 1, 1], [b.x1 - f, b.y0 + f, -1, 1], [b.x1 - f, b.y1 - f, -1, -1], [b.x0 + f, b.y1 - f, 1, -1]].forEach((c) => {
      const [cx, cy, sx, sy] = c;
      const blk = (ax, ay, bx, by) => out.push({ piece: 'lid', t: 'block', x0: Math.min(ax, bx), y0: Math.min(ay, by), x1: Math.max(ax, bx), y1: Math.max(ay, by), z0: za, z1: zb });
      blk(cx, cy, cx + sx * t, cy + sy * L);
      blk(cx + sx * t, cy, cx + sx * L, cy + sy * t);
    });
    return out;
  }

  // ================================================================ binary STL (what every 3D printer program opens)

  /** tris: [x,y,z × 3] per triangle (counter-clockwise seen from outside). → Uint8Array. */
  function stl(tris, name) {
    const n = Math.floor(tris.length / 9), buf = new ArrayBuffer(84 + n * 50), dv = new DataView(buf), u8 = new Uint8Array(buf);
    const head = ('Jarvis PCB studio box ' + String(name || '')).replace(/[^ -~]/g, '').slice(0, 79);
    for (let i = 0; i < head.length; i++) u8[i] = head.charCodeAt(i);
    dv.setUint32(80, n, true);
    let o = 84;
    for (let i = 0; i < n; i++) {
      const k = i * 9;
      const ax = tris[k], ay = tris[k + 1], az = tris[k + 2], bx = tris[k + 3], by = tris[k + 4], bz = tris[k + 5], cx = tris[k + 6], cy = tris[k + 7], cz = tris[k + 8];
      let nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay), ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az), nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
      const l = Math.hypot(nx, ny, nz) || 1;
      nx /= l; ny /= l; nz /= l;
      [nx, ny, nz, ax, ay, az, bx, by, bz, cx, cy, cz].forEach((v, j) => dv.setFloat32(o + j * 4, v, true));
      dv.setUint16(o + 48, 0, true);
      o += 50;
    }
    return u8;
  }

  function boxReadme(D, plan, tris) {
    const s = plan.size, b = plan.box, holes = C.holes(D).length;
    const te = [
      '📦 ' + D.name + ' — 3D ప్రింట్ డబ్బా (Jarvis PCB స్టూడియో, ₹0)',
      '',
      'base.stl = కింది డబ్బా · lid.stl = మూత.',
      'బయటి కొలత: ' + s.w + ' × ' + s.d + ' × ' + s.h + ' mm (వెడల్పు × లోతు × ఎత్తు). లోపల బోర్డ్: ' + D.board.w + ' × ' + D.board.h + ' mm.',
      'లోపల భాగాలకి ఎత్తు: ' + b.room + ' mm (పొడవైనది ' + b.tref + ', ' + b.tallest + ' mm).',
      '',
      'ప్రింట్: PLA, 0.2 mm పొర, 3 గోడలు (perimeters), 20% infill, సపోర్ట్ అక్కర్లేదు.',
      '- base.stl: నేల కిందికి, ఉన్నట్టే ప్రింట్ చేయండి.',
      '- lid.stl: పై ముఖం బెడ్ మీద (ఫైల్ ఇప్పటికే అలా తిప్పి ఉంది).',
      '- మూత: నొక్కితే లోపలి మూలల అంచులతో పట్టుకుంటుంది. గట్టిగా ఉంటే ఆ అంచుల్ని ఇసుక పేపర్‌తో రుద్దండి; వదులుగా ఉంటే ఒక చుక్క హాట్‌గ్లూ.',
      ''
    ].concat(plan.notes.map((n) => '- ' + n), plan.warns.map((n) => '- ' + n), [
      '',
      'ఇంట్లో 3D ప్రింటర్ లేకపోతే: ఈ STL ఫైల్స్‌ని 3D ప్రింటింగ్ సర్వీస్ సైట్‌లో మీరే అప్‌లోడ్ చేసి, మీరే ఆర్డర్ చేయండి. Jarvis ఆర్డర్ చేయదు, డబ్బులు కట్టదు.',
      '⚠️ PCB ఆర్డర్ ముందు ఒకసారి భాగాలు కొని ఎత్తులు కొలవండి (మాడ్యూల్స్ వేరే ఎత్తు ఉండొచ్చు): 📦 డబ్బా లో ➕ ➖ తో ఎత్తు మార్చొచ్చు.',
      '',
      '— English —',
      'Box for "' + D.name + '" made by the Jarvis PCB studio (our own code).',
      'base.stl: the tray (print floor-down). lid.stl: the lid (already turned top-face-down for printing).',
      'Outside ' + s.w + ' x ' + s.d + ' x ' + s.h + ' mm; walls ' + plan.K.wall + ' mm; board ' + D.board.w + ' x ' + D.board.h + ' x ' + b.T + ' mm on ' + (holes ? holes + ' screw posts (M3 x 6-8 mm, 2.6 mm pilot holes)' : 'four corner rests (no screw holes: a dot of hot glue)') + '.',
      'Suggested: PLA, 0.2 mm layers, 3 perimeters, 20% infill, no supports. The lid is a press fit (0.3 mm play on its corner tabs).',
      'Triangles: base ' + (tris ? tris.base : '?') + ', lid ' + (tris ? tris.lid : '?') + '. Units: millimetres.'
    ]);
    return te.join('\n') + '\n';
  }

  const api = { tall, solderSteps, spoken, valSaid, boxPlan, solids, stl, boxReadme, BOX, ORDER };
  root.PcbMake = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
