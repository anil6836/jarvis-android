// 🔌 Jarvis PCB studio page (Anil, 23 Nov: "1 నుంచి 18 వరకు అన్ని చేయి"): ready boards, the schematic (tap a part: what it is
// in Telugu), the PCB (move / turn parts, auto-route, ground fill), ✅ the factory checks with Telugu reasons, 🧊 the board in
// 3D, 🏭 the factory files (.zip saved / shared / the factory's site only on his tap) and 🧮 the numbers. All our own code, ₹0,
// no network: designs are data, every word goes in with textContent. Jarvis (the app) talks to this page through one port.
// Build 2 (24 Nov): 💡 idea / voice / a sketch photo → a board by the studio's AI (Jarvis asks it; the cost first, his tap or ✋ 3 s),
// 🗣️ his words change the board (small ones here, ₹0), 🤖 help for the checks. The AI's answer is data only (pcbai.js).
// Build 3A (24 Nov): ⚡ నడిపించు (the board running on the phone before ordering: pcbsim.js, ₹0), 🕑 versions kept inside the
// design, 💻 KiCad files for the laptop (pcbkicad.js). All our own code, offline.
// Build 3B (24 Nov): 🔧 the soldering guide (3D: the next part glows; 🔊 Telugu; "Jarvis తర్వాత"), 📦 a 3D-print box (STL), 📏 the
// board at its real size (1:1 on the screen; on his table through the phone's AR) and 🎓 the 5-day Telugu class (pcbmake.js,
// pcbclass.js). All ₹0 and offline; nothing calls the AI.
'use strict';
(function () {
  const P = window.PcbParts, C = window.PcbCore, F = window.PcbFab, J = window.PcbProjects, A = window.PcbAI, SIM = window.PcbSim, KI = window.PcbKiCad, MK = window.PcbMake, CL = window.PcbClass;
  const $ = (id) => document.getElementById(id);
  const FONT = '"Noto Sans Telugu", system-ui, "Noto Color Emoji", sans-serif';

  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = String(text); return e; }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); return n; }
  function btn(text, cls, fn) { const b = el('button', 'btn' + (cls ? ' ' + cls : ''), text); b.addEventListener('click', (e) => { e.stopPropagation(); fn(e); }); return b; }
  function tool(text, cls, fn) { const b = el('button', 'tool' + (cls ? ' ' + cls : ''), text); b.addEventListener('click', (e) => { e.stopPropagation(); fn(e); }); return b; }
  const r2 = (v) => Math.round(v * 100) / 100;
  let toastT = 0;
  function toast(s, ms) { const t = $('toast'); t.textContent = s; t.style.display = 'block'; clearTimeout(toastT); toastT = setTimeout(() => { t.style.display = 'none'; }, ms || 2600); }

  // ---------------------------------------------------------------- talking to Jarvis

  let port = null;
  const outbox = [];
  function post(m) { const s = JSON.stringify(m); if (port) port.postMessage(s); else outbox.push(s); }
  window.addEventListener('message', (e) => {
    if (port || e.data !== 'jarvis-port' || !e.ports || !e.ports[0]) return;
    port = e.ports[0];
    port.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (x) { return; } onJarvis(m); };
    outbox.splice(0).forEach((s) => port.postMessage(s));
    post({ t: 'ready' });
  });

  // ---------------------------------------------------------------- state

  let D = null;                 // the open design
  let M = null;                 // its pads and nets
  let fills = { B: null, F: null };
  let check = null;             // the checks' result
  let drawer = { items: {} };   // 🧰 (28 Nov, E) his parts drawer: key → {te, n, q} (kept by Jarvis on the phone)
  let silkCache = null;
  let designs = [];             // his saved designs (summaries from Jarvis)
  let tab = 'home';
  let undo = [];
  let saveT = 0, calcT = 0;
  let saved = null;             // the last .zip saved: {where, kind: 'gerber' | 'kicad'}
  let zipKind = 'gerber';
  let autoRoute = true;
  const pv = { s: 8, ox: 20, oy: 20, fit: true, layer: 'both', real: false, sel: null };
  const sv = { s: 1, ox: 20, oy: 20, fit: true, sel: null, vals: true };
  // Build 3B
  const sg = { on: false, i: 0, steps: null, auto: false, bottom: false };   // 🔧 the soldering guide
  const bx = { on: false, plan: null, open: true, see: false };              // 📦 the box
  const rs = { on: false, ox: 0, oy: 0, fit: true, cal: false, drag: null };  // 📏 1:1 on the screen
  const xrS = { on: false, wait: false, st: '', box: false };                // 📷 AR on his table
  let speaking = false, scr = { xdpi: 0, k: 1 }, learnSt = { d: {} }, afterOpen = null;
  const lv = { day: 0, ans: {} };                                            // 🎓 the day open, his quiz answers
  let ft = null;                                                             // 🧪 fault finder: {id, tests, gnd, i, res: [{got, w}]}

  function gndName() { const n = D && D.nets.find((q) => C.isGnd(q.n)); return n ? n.n : null; }

  /** After any change: the fill, the checks, the screen; kept on the phone a little later. */
  function changed(how) {
    if (!D) return;
    M = C.model(D);
    silkCache = null;
    sim.rep = null; // (⚡ runs the changed board again when asked)
    if (how !== 'load' && ft) ft = null; // (🧪 a changed board: its points are worked out again)
    const g = gndName();
    fills.B = g && D.pour.B ? C.pour(D, 1, g, M) : null;
    fills.F = g && D.pour.F ? C.pour(D, 0, g, M) : null;
    check = C.drc(D, D.fab, [fills.B, fills.F], M);
    state();
    if (how !== 'load') {
      D.at = Date.now();
      clearTimeout(saveT);
      saveT = setTimeout(() => post({ t: 'save', d: C.clean(D) }), 1200);
    }
    redraw();
    makeFollow();
  }
  function state() {
    const s = $('state');
    $('name').firstChild.textContent = D ? D.name : '🔌 PCB స్టూడియో';
    $('sub').textContent = D ? D.board.w + ' × ' + D.board.h + ' mm · ' + D.parts.length + ' భాగాలు' : 'నిజమైన PCB బోర్డ్‌లు';
    if (!D || !check) { s.className = ''; s.textContent = '—'; }
    else if (check.errors) { s.className = 'bad'; s.textContent = '❌ ' + check.errors + ' తప్పులు'; }
    else { s.className = 'ok'; s.textContent = check.warns ? '✅ సిద్ధం · ⚠️ ' + check.warns : '✅ ఆర్డర్‌కి సిద్ధం'; }
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('off', !D && t.dataset.tab !== 'home' && t.dataset.tab !== 'calc' && t.dataset.tab !== 'learn'));
  }
  function snap() { if (D) { undo.push(JSON.stringify({ board: D.board, parts: D.parts, tracks: D.tracks, vias: D.vias, labels: D.labels, pour: D.pour })); if (undo.length > 25) undo.shift(); } }
  function back1() {
    const s = undo.pop();
    if (!s || !D) { toast('వెనక్కి వెళ్ళడానికి ఇంకేమీ లేదు'); return; }
    Object.assign(D, JSON.parse(s));
    changed();
    toast('↩️ ముందున్నట్టు చేశాను');
  }
  function open(d, how) {
    makeReset();
    D = C.clean(d);
    undo = [];
    pv.fit = true; sv.fit = true; pv.sel = null; sv.sel = null;
    if (D.parts.some((p) => p.lib === 'esp32')) D.parts.forEach((p) => { if (p.lib === 'esp32') p.fp = 'ESP32_DEVKIT30_' + Math.round(D.board.rows * 100); });
    if (!D.tracks.length && how !== 'load') reroute(true);
    if (!(D.vers && D.vers.length)) verSnap(how === 'load' ? '📂 తెరిచినప్పుడు ఉన్నది' : '📦 మొదటి రూపం');
    changed(how);
  }
  function reroute(quiet) {
    const r = C.route(D);
    const miss = r.failed.reduce((s, f) => s + f.pins.length, 0);
    if (!quiet) toast(miss ? '⚡ ' + miss + ' కలపలేకపోయాను: భాగాల్ని కొంచెం దూరం జరపండి లేదా బోర్డ్ పెద్దది చేయండి.' : '⚡ అన్నీ కలిపాను (' + D.tracks.length + ' దారులు, ' + D.vias.length + ' వయాలు).');
    return r;
  }

  // ---------------------------------------------------------------- messages from Jarvis

  function onJarvis(m) {
    switch (m.t) {
      case 'init':
        designs = Array.isArray(m.list) ? m.list : [];
        if (m.scr && typeof m.scr === 'object') scr = { xdpi: +m.scr.xdpi || 0, k: +m.scr.k > 0.5 && +m.scr.k < 1.5 ? +m.scr.k : 1 };
        if (m.learn && typeof m.learn === 'object' && m.learn.d && typeof m.learn.d === 'object') learnSt = { d: m.learn.d };
        if (m.drawer && m.drawer.items && typeof m.drawer.items === 'object') drawer = { items: m.drawer.items };
        if (m.cur && m.cur.parts) open(m.cur, 'load');
        if (m.tab === 'idea') { showTab('home'); toIdea(); }
        else if (m.tab === 'drawer') { showTab('home'); drawerSheet(); }
        else showTab(typeof m.tab === 'string' && TABS.indexOf(m.tab) >= 0 ? m.tab : 'home');
        break;
      case 'designs': designs = Array.isArray(m.list) ? m.list : []; if (tab === 'home') myDesigns(); break;
      case 'design': if (m.d && m.d.parts) { open(m.d, 'load'); const f = afterOpen; afterOpen = null; if (f) f(); else showTab('pcb'); } break;
      case 'saved': saved = { where: String(m.where || ''), kind: zipKind }; if (tab === 'fab') fab(); if (bx.on && tab === 'd3') boxCard(); toast('💾 సేవ్ అయింది: ' + saved.where, 4000); break;
      case 'say': toast(String(m.s || ''), 4000); break;
      case 'cam': if (camv) camv.ready(m); break; // (📷 Android's camera permission: yes / no)
      case 'camoff': if (camv && camv.on()) { camv.close(); toast('📷 స్టూడియో వెనక్కి వెళ్ళింది: కెమెరా ఆపాను. మళ్ళీ కావాలంటే 📷 నొక్కండి.', 4000); } break;
      case 'tab': if (m.tab === 'idea') { showTab('home'); toIdea(); } else if (m.tab === 'drawer') { showTab('home'); drawerSheet(); } else if (TABS.indexOf(m.tab) >= 0) showTab(m.tab); break;
      // ---- 🤖 the studio's AI (asked by Jarvis; this page never sees a key or the network)
      case 'aiquote': { const cb = quoteCb[m.kind]; if (cb) cb(m); break; }
      case 'aiwait': ai.wait = { kind: m.kind, words: String(m.words || ''), model: String(m.model || ''), money: String(m.money || ''), until: Date.now() + (m.secs || 3) * 1000 }; bar(); break;
      case 'aicancelled': ai.wait = null; ai.pending = null; bar(); toast('✋ సరే, చేయలేదు · ఖర్చు ₹0', 3000); break;
      case 'aistart': ai.wait = null; ai.job = { kind: m.kind, model: String(m.model || ''), money: String(m.money || ''), at: Date.now(), n: 0 }; bar(); break;
      case 'aiprog': if (ai.job) { ai.job.n = m.n | 0; bar(); } break;
      case 'aidone': aiDone(m); break;
      case 'photoready':
        if (m.for === 'scan') { if (m.side === 'bottom') scanSt.bottom = String(m.thumb || ''); else scanSt.top = String(m.thumb || ''); scanSheet(); break; }
        if ((m.for === 'pins' || m.for === 'solder') && ph && ph.for === m.for) { ph.thumb = String(m.thumb || ''); ph.ans = null; if (m.for === 'pins') pinSheet(); else solderSheet(); break; }
        idea.photo = String(m.thumb || ''); if (tab !== 'home') showTab('home'); else home(); toIdea(); break;
      case 'dictst': dict.on = !!m.on; dictLook(); break;
      case 'dictated': dict.on = false; dictLook(); dictInto(String(m.s || '')); break;
      case 'vheard': heard(String(m.s || ''), false); break;
      case 'vline': if (m.s) toast(String(m.s), 2600); break;
      case 'vmic': $('mic').classList.toggle('on', m.st === 1); if (speaking !== (m.st === 3)) { speaking = m.st === 3; speakLook(); } break;
      // ---- 📷 AR (Build 3B): the camera under this page, its picture drawn by Jarvis; the board placed by his tap
      case 'xr': if (m.on) xrEnter(); else { if (m.why) toast(String(m.why), 6000); xrLeave(); if (tab === 'd3') tools3d(); } break;
      case 'xrst': xrSaid(String(m.s || '')); break;
      case 'xrpose': if (xrS.on && v3d) v3d.xrPose(m); break;
      case 'idea': idea.text = String(m.s || ''); showTab('home'); toIdea(); if (m.go) makeIdea(true); break;
      case 'change': heard(String(m.s || ''), true); break;
      case 'fromlab': fromLab(m); break; // ⚡→🔌 (28 Nov, D) a circuit-lab board made into a PCB, ₹0
      case 'project': {
        const pr = J.PROJECTS.find((q) => q.id === m.id);
        if (!pr) break;
        open(J.make(pr.id));
        showTab('pcb');
        toast('✅ ' + pr.te + ': మీ కాపీ తెరిచాను.', 3000);
        break;
      }
      case 'back': {
        let used = true;
        if (ai.wait) post({ t: 'aicancel' });
        else if (camv && camv.on()) camv.close();
        else if (xrS.on || xrS.wait) stopAr();
        else if (rs.on) closeReal();
        else if ($('sheet').classList.contains('on')) closeSheet();
        else if (tab === 'learn' && lv.day) { lv.day = 0; hush(); learnTab(); }
        else if (pv.sel) { pv.sel = null; selBar(); redraw(); }
        else if (tab !== 'home') showTab('home');
        else used = false;
        post({ t: 'back', used });
        break;
      }
      default:
    }
  }

  // ---------------------------------------------------------------- tabs

  const TABS = ['home', 'sch', 'pcb', 'drc', 'sim', 'd3', 'fab', 'calc', 'learn'];
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('close').addEventListener('click', () => post({ t: 'close' }));
  $('name').addEventListener('click', () => { if (D) renameSheet(); });
  function showTab(name) {
    if (!D && name !== 'home' && name !== 'calc' && name !== 'learn') { toast('ముందు ఒక బోర్డ్ తెరవండి (📚 బోర్డ్‌లు)'); name = 'home'; }
    tab = name;
    if (name !== 'd3' && (xrS.on || xrS.wait)) stopAr();
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === name));
    TABS.forEach((n) => $(n).classList.toggle('on', n === name));
    closeSheet();
    if (name !== 'd3' && v3d) v3d.close();
    if (name !== 'd3') { $('guide').classList.remove('on'); $('boxcard').classList.remove('on'); $('v3d').style.bottom = '0px'; }
    if (name === 'home') home();
    else if (name === 'sch') { sizeCv($('schcv')); schTools(); redraw(); }
    else if (name === 'pcb') { sizeCv($('pcbcv')); pcbTools(); selBar(); redraw(); }
    else if (name === 'drc') drcList();
    else if (name === 'sim') { sizeCv($('simcv')); simBuild(); simTools(); drawSim(); simGo(); }
    else if (name === 'd3') open3d();
    else if (name === 'fab') fab();
    else if (name === 'calc') calc();
    else if (name === 'learn') learnTab();
    const on = document.querySelector('.tab.on');
    if (on && on.scrollIntoView) on.scrollIntoView({ inline: 'nearest', block: 'nearest' });
  }
  function redraw() { if (tab === 'pcb') drawPcb(); else if (tab === 'sch') drawSch(); else if (tab === 'drc') drcList(); else if (tab === 'sim') { simBuild(); simTools(); drawSim(); simGo(); } else if (tab === 'd3' && v3d) v3d.update(); }
  window.addEventListener('resize', () => { if (tab === 'pcb') { sizeCv($('pcbcv')); pv.fit = true; drawPcb(); } else if (tab === 'sch') { sizeCv($('schcv')); sv.fit = true; drawSch(); } else if (tab === 'sim') { sizeCv($('simcv')); simv.fit = true; drawSim(); } });

  function sizeCv(cv) {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth || 360, h = cv.clientHeight || 500;
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
    const ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx, w, h };
  }

  // ---------------------------------------------------------------- sheet

  function sheet(build) {
    const s = clear($('sheet'));
    s.appendChild(el('div', 'grip'));
    build(s);
    s.scrollTop = 0; // (a new sheet starts at its top, not where the last one was left)
    s.classList.add('on');
  }
  function closeSheet() { $('sheet').classList.remove('on'); }
  $('sheet').addEventListener('click', (e) => { if (e.target.classList.contains('grip')) closeSheet(); });

  // ================================================================ 📚 home: ready boards, his boards

  function home() {
    const h = clear($('home'));
    const intro = el('div', 'card');
    intro.appendChild(el('div', null, '🔌 ఇక్కడ తయారయ్యేవి నిజమైన PCB బోర్డ్‌లు.'));
    intro.appendChild(el('div', 'muted', 'స్కీమాటిక్ → PCB → చెక్ → ఫ్యాక్టరీ ఫైల్స్ (Gerber .zip). ఆ zip మీరే ఫ్యాక్టరీ సైట్‌లో అప్‌లోడ్ చేసి, మీరే డబ్బులు కడితే అసలు బోర్డ్‌లు ఇంటికి వస్తాయి. బ్యాటరీ / USB / అడాప్టర్ (తక్కువ వోల్టేజ్ DC) బోర్డ్‌లు మాత్రమే; 230V కరెంట్ బోర్డ్‌లు ఇక్కడ చేయను.'));
    h.appendChild(intro);
    ideaCard(h);
    scanCard(h);
    if (D) {
      const cur = el('div', 'card tap');
      cur.appendChild(el('div', null, '▶️ ఇప్పుడు తెరిచి ఉంది: ' + D.name));
      cur.appendChild(el('div', 'muted', D.board.w + ' × ' + D.board.h + ' mm · ' + (check && check.errors ? '❌ ' + check.errors + ' తప్పులు' : '✅ ఆర్డర్‌కి సిద్ధం')));
      cur.addEventListener('click', () => showTab('pcb'));
      const cr = el('div', 'row');
      cr.appendChild(btn('⚡ నడిపించు', 'small', () => showTab('sim')));
      cr.appendChild(btn('🕑 వెర్షన్లు (' + ((D.vers || []).length) + ')', 'small', verSheet));
      cr.appendChild(btn('🔧 టంకం', 'small', () => startGuide()));
      cr.appendChild(btn('📦 డబ్బా', 'small', startBox));
      cr.appendChild(btn('📏 అసలు సైజు', 'small', openReal));
      cr.appendChild(btn('🔌 వైర్లు', 'small', () => wireSheet(false)));
      cr.appendChild(btn('✅ కొనే లిస్ట్', 'small', buyGo));
      cr.appendChild(btn('🧪 పనిచేయట్లేదా?', 'small', () => faultStart(true)));
      cr.appendChild(btn('📷 టంకం చెక్ (AI)', 'small', solderStart));
      cr.appendChild(btn('👨‍🔧 రివ్యూ (AI)', 'small', () => { showTab('drc'); setTimeout(() => { const c = $('reviewcard'); if (c) $('drc').scrollTop = Math.max(0, c.offsetTop - 8); }, 60); }));
      cur.appendChild(cr);
      h.appendChild(cur);
    }
    learnCard(h);
    drawerCard(h);
    modLibCard(h);
    h.appendChild(el('h3', null, '📚 రెడీ బోర్డ్‌లు (₹0, మీ కాపీగా తెరుచుకుంటాయి)'));
    J.PROJECTS.forEach((pr) => {
      const c = el('div', 'card tap proj');
      const cv = el('canvas');
      c.appendChild(cv);
      const t = el('div');
      t.appendChild(el('div', 't', pr.emoji + ' ' + pr.te));
      t.appendChild(el('div', 'd', pr.what));
      const tags = el('div');
      tags.appendChild(el('span', 'tag', pr.level));
      tags.appendChild(el('span', 'tag', pr.board.w + '×' + pr.board.h + ' mm'));
      tags.appendChild(el('span', 'tag', pr.cost));
      t.appendChild(tags);
      c.appendChild(t);
      c.addEventListener('click', () => {
        const d = J.make(pr.id);
        open(d);
        showTab('pcb');
        toast('✅ ' + pr.te + ': మీ కాపీ తెరిచాను. ' + (check.errors ? '❌ ' + check.errors + ' తప్పులు' : 'చెక్ అయింది, ఆర్డర్‌కి సిద్ధం.'), 3500);
      });
      h.appendChild(c);
      requestAnimationFrame(() => thumb(cv, pr));
    });
    h.appendChild(el('h3', null, '📂 నా బోర్డ్‌లు'));
    const mine = el('div');
    mine.id = 'mine';
    h.appendChild(mine);
    myDesigns();
  }
  /** His saved boards (only this part is drawn again when the list changes: what he is typing above stays). */
  function myDesigns() {
    const h = $('mine');
    if (!h) return;
    clear(h);
    if (!designs.length) h.appendChild(el('div', 'muted', 'ఇంకా ఏమీ లేవు. పైన ఒక రెడీ బోర్డ్ తెరిచి మార్చినా, 💡 AI తో కొత్తది చేసినా ఇక్కడ దాచుకుంటుంది (తనంతట తానే).'));
    designs.slice(0, 100).forEach((d) => {
      const c = el('div', 'card');
      const row = el('div', 'row');
      const k = el('div', 'k');
      k.appendChild(el('div', null, String(d.name || 'బోర్డ్')));
      k.appendChild(el('div', 'muted', (d.w || '?') + ' × ' + (d.h || '?') + ' mm · ' + (d.n || 0) + ' భాగాలు · ' + when(d.at)));
      row.appendChild(k);
      row.appendChild(btn('తెరువు', 'main small', () => post({ t: 'open', id: d.id })));
      row.appendChild(btn('🗑️', 'small', () => {
        clear(c);
        c.appendChild(el('div', null, '🗑️ "' + d.name + '" నిజంగా తీసేయాలా? (తిరిగి రాదు)'));
        const r2b = el('div', 'row');
        r2b.appendChild(btn('అవును, తీసేయి', 'bad', () => { post({ t: 'remove', id: d.id }); if (D && D.id === d.id) { D = null; check = null; state(); } }));
        r2b.appendChild(btn('వద్దు', '', () => myDesigns()));
        c.appendChild(r2b);
      }));
      c.appendChild(row);
      h.appendChild(c);
    });
  }
  function when(at) {
    if (!at) return '';
    const d = new Date(at);
    return d.getDate() + '/' + (d.getMonth() + 1) + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  /** A small picture of a ready board (its parts and the board, no routing: fast). */
  function thumb(cv, pr) {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth || 92, h = cv.clientHeight || 66;
    cv.width = w * dpr; cv.height = h * dpr;
    const ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const d = C.clean(J.make(pr.id)), s = Math.min((w - 8) / d.board.w, (h - 8) / d.board.h);
    const view = { s, ox: (w - d.board.w * s) / 2, oy: (h - d.board.h * s) / 2 };
    drawBoard(ctx, d, C.model(d), null, view, { real: true, thumb: true });
  }

  // ================================================================ drawing a board (the PCB tab, thumbnails, the 3D picture)

  const MASK = { green: '#1d6b34', blue: '#1b4f9c', black: '#15171a', white: '#e8ebee', purple: '#4b2a7a' };
  function lighter(hex, k) {
    const n = parseInt(hex.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    const f = (c) => Math.round(c + (255 - c) * k);
    return 'rgb(' + f(r) + ',' + f(g) + ',' + f(b) + ')';
  }
  function padPath(ctx, p, s, ox, oy, grow) {
    const e = grow || 0, x = ox + p.x * s, y = oy + p.y * s, w = (p.w + 2 * e) * s, h = (p.h + 2 * e) * s;
    ctx.beginPath();
    if (p.s === 'r') ctx.rect(x - w / 2, y - h / 2, w, h);
    else if (p.s === 'o' && Math.abs(w - h) > 0.01) {
      const r = Math.min(w, h) / 2;
      if (w > h) { ctx.moveTo(x - w / 2 + r, y - r); ctx.lineTo(x + w / 2 - r, y - r); ctx.arc(x + w / 2 - r, y, r, -Math.PI / 2, Math.PI / 2); ctx.lineTo(x - w / 2 + r, y + r); ctx.arc(x - w / 2 + r, y, r, Math.PI / 2, Math.PI * 1.5); }
      else { ctx.moveTo(x + r, y - h / 2 + r); ctx.lineTo(x + r, y + h / 2 - r); ctx.arc(x, y + h / 2 - r, r, 0, Math.PI); ctx.lineTo(x - r, y - h / 2 + r); ctx.arc(x, y - h / 2 + r, r, Math.PI, 0); }
      ctx.closePath();
    } else ctx.arc(x, y, w / 2, 0, Math.PI * 2);
  }
  /**
   * The board at view {s, ox, oy}. o: {real (the colours it will have), layer: both | top | bottom, bottomSide (seen from under),
   * thumb, sel (ref), drag {ref,x,y,rot}, markers (checks), rats (unjoined lines)}.
   */
  function drawBoard(ctx, d, m, fl, view, o) {
    const s = view.s, ox = view.ox, oy = view.oy, B = d.board, X = (x) => ox + x * s, Y = (y) => oy + y * s;
    const real = !!o.real, mask = MASK[B.color] || MASK.green, under = o.bottomSide;
    const showF = real ? !under : o.layer !== 'bottom', showB = real ? !!under : o.layer !== 'top';
    // the board
    ctx.beginPath();
    C.outline(B, 8).forEach((q, i) => (i ? ctx.lineTo(X(q[0]), Y(q[1])) : ctx.moveTo(X(q[0]), Y(q[1]))));
    ctx.closePath();
    ctx.fillStyle = real ? mask : '#0c2215';
    ctx.fill();
    if (!real) { ctx.strokeStyle = '#e8d36a'; ctx.lineWidth = 1; ctx.stroke(); }
    const cuCol = (L) => real ? lighter(mask, B.color === 'white' ? -0.0 : 0.16) : L ? '#3d8fe0' : '#e05a3d';
    const fillCol = (L) => real ? lighter(mask, B.color === 'white' ? 0 : 0.1) : L ? 'rgba(61,143,224,.17)' : 'rgba(224,90,61,.16)';
    const layer = (L) => {
      if (fl && fl[L ? 'B' : 'F']) { ctx.fillStyle = fillCol(L); fl[L ? 'B' : 'F'].rects.forEach((r) => ctx.fillRect(X(r[0]), Y(r[1]), (r[2] - r[0]) * s + 0.4, (r[3] - r[1]) * s + 0.4)); }
      ctx.strokeStyle = cuCol(L); ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      (d.tracks || []).forEach((t) => {
        if ((t.l === 'B' ? 1 : 0) !== L) return;
        if (o.drag && o.dragNets && o.dragNets.has(t.n)) return;
        ctx.lineWidth = Math.max(1, (t.w || 0.4) * s);
        ctx.beginPath();
        t.p.forEach((q, i) => (i ? ctx.lineTo(X(q[0]), Y(q[1])) : ctx.moveTo(X(q[0]), Y(q[1]))));
        ctx.stroke();
      });
    };
    if (!o.thumb) {
      if (real) { if (showB) layer(1); if (showF) layer(0); }
      else { if (showB) layer(1); if (showF) layer(0); }
    }
    // pads, vias, holes
    const pads = o.drag ? C.model(Object.assign({}, d, { parts: d.parts.map((pt) => (pt.ref === o.drag.ref ? Object.assign({}, pt, { x: o.drag.x, y: o.drag.y, rot: o.drag.rot }) : pt)) })).pads : m.pads;
    const padCol = real ? '#c9ced3' : '#d6b14a';
    pads.forEach((p) => {
      if (p.smd && under) return;
      ctx.fillStyle = padCol; padPath(ctx, p, s, ox, oy); ctx.fill();
      if (p.d) { ctx.fillStyle = '#060606'; ctx.beginPath(); ctx.arc(X(p.x), Y(p.y), Math.max(0.6, p.d / 2 * s), 0, Math.PI * 2); ctx.fill(); }
    });
    if (!o.thumb) (d.vias || []).forEach((v) => {
      if (o.drag && o.dragNets && o.dragNets.has(v.n)) return;
      ctx.fillStyle = real ? cuCol(0) : '#d6b14a'; ctx.beginPath(); ctx.arc(X(v.x), Y(v.y), 0.4 * s, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#060606'; ctx.beginPath(); ctx.arc(X(v.x), Y(v.y), Math.max(0.5, 0.2 * s), 0, Math.PI * 2); ctx.fill();
    });
    C.holes(d).forEach((hh) => { ctx.fillStyle = '#060606'; ctx.beginPath(); ctx.arc(X(hh.x), Y(hh.y), hh.d / 2 * s, 0, Math.PI * 2); ctx.fill(); });
    // silkscreen (top only)
    if (!under && (showF || real)) {
      const sk = o.thumb || o.drag ? C.silk(o.drag ? Object.assign({}, d, { parts: d.parts.map((pt) => (pt.ref === o.drag.ref ? Object.assign({}, pt, { x: o.drag.x, y: o.drag.y, rot: o.drag.rot }) : pt)) }) : d) : (silkCache || (silkCache = C.silk(d, m)));
      ctx.strokeStyle = real && B.color === 'white' ? '#111' : '#f4f4f4';
      ctx.lineWidth = Math.max(0.6, sk.w * s);
      ctx.lineCap = 'round';
      ctx.beginPath();
      sk.segs.forEach((q) => { ctx.moveTo(X(q[0]), Y(q[1])); ctx.lineTo(X(q[2]), Y(q[3])); });
      ctx.stroke();
    }
    if (o.thumb) return;
    // the selected part
    if (o.sel) {
      const pt = d.parts.find((q) => q.ref === o.sel);
      if (pt) {
        const c = o.drag ? C.courtOf(pt, o.drag.x, o.drag.y, o.drag.rot) : C.courtOf(pt, pt.x, pt.y, pt.rot || 0);
        ctx.strokeStyle = '#74e4ff'; ctx.lineWidth = 2; ctx.setLineDash([6, 4]);
        ctx.strokeRect(X(c[0]) - 2, Y(c[1]) - 2, (c[2] - c[0]) * s + 4, (c[3] - c[1]) * s + 4);
        ctx.setLineDash([]);
      }
    }
    // what is not joined yet, and the checks
    if (o.rats) {
      ctx.strokeStyle = '#ffe066'; ctx.lineWidth = 1.5; ctx.setLineDash([5, 4]);
      o.rats.forEach((q) => { ctx.beginPath(); ctx.moveTo(X(q.a.x), Y(q.a.y)); ctx.lineTo(X(q.b.x), Y(q.b.y)); ctx.stroke(); });
      ctx.setLineDash([]);
    }
    if (o.markers) o.markers.forEach((f) => {
      if (f.sev === 'info') return;
      ctx.strokeStyle = f.sev === 'err' ? '#ff4d4d' : '#ffc861'; ctx.lineWidth = 2.5;
      ctx.beginPath(); ctx.arc(X(f.x), Y(f.y), Math.max(10, 1.3 * s), 0, Math.PI * 2); ctx.stroke();
    });
  }

  // ================================================================ 🧩 the PCB tab

  function pcbTools() {
    const t = clear($('pcbtools'));
    if (fr.on) {
      // ✍️ his own tracks: the tools of this mode only
      t.appendChild(tool('✅ అయింది', 'main', () => { fr.on = false; fr.del = false; fr.cur = null; pcbTools(); drawPcb(); }));
      t.appendChild(tool(fr.layer === 'F' ? '🔴 పై పొర (F)' : '🔵 కింది పొర (B)', 'on', () => { fr.layer = fr.layer === 'F' ? 'B' : 'F'; pcbTools(); toast(fr.layer === 'F' ? '🔴 ఇక దారులు పై పొర మీద' : '🔵 ఇక దారులు కింది పొర మీద'); }));
      t.appendChild(tool(fr.del ? '🗑️ తీసేయి ✓' : '🗑️ దారి తీసేయి', fr.del ? 'on' : '', () => { fr.del = !fr.del; pcbTools(); toast(fr.del ? '🗑️ తీసేయాల్సిన దారి మీద నొక్కండి' : '✍️ ప్యాడ్ నుంచి వేలు జరపండి'); }));
      t.appendChild(tool('↩️', '', back1));
      t.appendChild(tool('🔍', '', () => { pv.fit = true; drawPcb(); }));
      return;
    }
    t.appendChild(tool('⚡ రూట్', 'main', () => { snap(); reroute(); changed(); }));
    t.appendChild(tool('✍️ చేతితో దారి', '', () => {
      fr.on = true; fr.del = false; fr.cur = null;
      if (autoRoute) { autoRoute = false; }
      pcbTools(); drawPcb();
      toast('✍️ ఒక ప్యాడ్ మీద వేలు పెట్టి, అదే దారి (net) ఉన్న ప్యాడ్ వరకు జరపండి. అడ్డం ఉన్నవాటి చుట్టూ తానే తిరుగుతుంది. (ఆటో రూట్ ఆపాను: ఇక ⚡ రూట్ నొక్కితేనే అన్నీ మళ్ళీ వేస్తుంది.)', 6000);
    }));
    t.appendChild(tool(autoRoute ? '🔁 ఆటో రూట్ ఆన్' : '🔁 ఆటో రూట్ ఆఫ్', autoRoute ? 'on' : '', () => { autoRoute = !autoRoute; pcbTools(); toast(autoRoute ? 'భాగం కదిపిన ప్రతిసారీ దారులు తానే మళ్ళీ వేస్తుంది' : 'కదిపాక ⚡ రూట్ మీరే నొక్కాలి'); }));
    t.appendChild(tool('👁️ ' + ({ both: 'రెండు పొరలు', top: 'పై పొర', bottom: 'కింది పొర' }[pv.layer]), '', () => { pv.layer = { both: 'top', top: 'bottom', bottom: 'both' }[pv.layer]; pcbTools(); drawPcb(); }));
    t.appendChild(tool(pv.real ? '🎨 అసలు రంగు' : '🎨 పొరల రంగు', pv.real ? 'on' : '', () => { pv.real = !pv.real; pcbTools(); drawPcb(); }));
    t.appendChild(tool('⏚ GND నింపు' + (D && D.pour.B ? ' ✓' : ''), D && D.pour.B ? 'on' : '', () => { snap(); D.pour.B = !D.pour.B; changed(); pcbTools(); }));
    t.appendChild(tool('📏 బోర్డ్', '', boardSheet));
    t.appendChild(tool('📏 అసలు సైజు', '', openReal));
    t.appendChild(tool('🔀 అమర్చు', '', () => {
      sheet((s) => {
        s.appendChild(el('h2', null, '🔀 భాగాలు మళ్ళీ అమర్చాలా?'));
        s.appendChild(el('div', 'muted', 'అన్ని భాగాల్ని Jarvis తనంతట తానే మళ్ళీ పెడుతుంది (కనెక్టర్లు అంచుల దగ్గర, కలిసే భాగాలు దగ్గర దగ్గర), తర్వాత దారులు వేస్తుంది. ఇప్పటి అమరిక పోతుంది (↩️ తో వెనక్కి రావచ్చు).'));
        const r = el('div', 'row');
        r.appendChild(btn('అవును, అమర్చు', 'main', () => {
          snap();
          const ok = C.autoplace(D, { labels: true });
          if (!ok) { back1(); toast('బోర్డ్ చిన్నది: అన్ని భాగాలు పట్టలేదు. 📏 బోర్డ్ పెంచి మళ్ళీ చూడండి.'); closeSheet(); return; }
          reroute(); changed(); closeSheet();
        }));
        r.appendChild(btn('వద్దు', '', closeSheet));
        s.appendChild(r);
      });
    }));
    t.appendChild(tool('✏️ మార్పు', '', () => editSheet('')));
    t.appendChild(tool('🔌 వైర్లు', '', () => wireSheet(false)));
    t.appendChild(tool('↩️', '', back1));
    t.appendChild(tool('🕑', '', verSheet));
    t.appendChild(tool('🔍', '', () => { pv.fit = true; drawPcb(); }));
  }
  function drawPcb() {
    if (!D) return;
    const cv = $('pcbcv'), { ctx, w, h } = sizeCv(cv);
    if (pv.fit) {
      const s = Math.min((w - 20) / D.board.w, (h - 130) / D.board.h);
      pv.s = s; pv.ox = (w - D.board.w * s) / 2; pv.oy = 44 + (h - 130 - D.board.h * s) / 2; pv.fit = false;
    }
    ctx.fillStyle = '#07080b'; ctx.fillRect(0, 0, w, h);
    // a faint 2.54 mm grid under the board
    if (pv.s > 6) {
      ctx.fillStyle = 'rgba(116,228,255,.07)';
      for (let x = 0; x <= D.board.w; x += 2.54) for (let y = 0; y <= D.board.h; y += 2.54) ctx.fillRect(pv.ox + x * pv.s - 0.5, pv.oy + y * pv.s - 0.5, 1, 1);
    }
    drawBoard(ctx, D, M, fills, pv, { real: pv.real, layer: pv.layer, sel: pv.sel, drag: drag, dragNets: drag && drag.nets,
      rats: check ? check.con.open : null, markers: check ? check.list : null });
    if (fr.on) frDraw(ctx);
    const c = clear($('pcbchips'));
    if (check) {
      const open = check.con.open.length;
      c.appendChild(el('span', 'chip ' + (open ? 'bad' : 'ok'), open ? '🔗 ' + open + ' కలవాలి' : '🔗 అన్నీ కలిశాయి'));
      c.appendChild(el('span', 'chip ' + (check.errors ? 'bad' : 'ok'), check.errors ? '❌ ' + check.errors : '✅ చెక్'));
      c.appendChild(el('span', 'chip', '🛤️ ' + D.tracks.length + ' · ⭕ ' + D.vias.length));
      if (pv.layer !== 'both') c.appendChild(el('span', 'chip warn', pv.layer === 'top' ? 'పై పొర మాత్రమే' : 'కింది పొర మాత్రమే'));
    }
  }
  function toWorld(v, x, y) { return [(x - v.ox) / v.s, (y - v.oy) / v.s]; }
  function partAt(wx, wy) {
    let best = null;
    D.parts.forEach((pt) => {
      const c = C.courtOf(pt, pt.x, pt.y, pt.rot || 0);
      if (wx >= c[0] - 0.4 && wx <= c[2] + 0.4 && wy >= c[1] - 0.4 && wy <= c[3] + 0.4) {
        const a = (c[2] - c[0]) * (c[3] - c[1]);
        if (!best || a < best.a) best = { pt, a };
      }
    });
    return best ? best.pt : null;
  }
  let drag = null;

  // ---------------------------------------------------------------- ✍️ tracks by his finger (27 Nov, "6."): ₹0, our own router
  const fr = { on: false, del: false, layer: 'F', cur: null };
  /** Copper of a net under the finger: a pad, a track or a via (its net name, and the point to start from). */
  function frHit(w) {
    const cu = C.copper(D, M);
    let best = null;
    cu.forEach((c) => {
      if (c.net < 0) return;
      const d = C.dist(c.o, w[0], w[1]);
      if (d > 0.6) return;
      const pri = c.kind === 'pad' ? 0 : c.kind === 'via' ? 1 : 2;
      if (!best || pri < best.pri || (pri === best.pri && d < best.d)) best = { c, d, pri };
    });
    if (!best) return null;
    const c = best.c, n = M.nets[c.net].n;
    let at = [w[0], w[1]];
    if (c.kind === 'pad') at = [c.pad.x, c.pad.y];
    else if (c.kind === 'via') at = [c.o.x1, c.o.y1];
    return { net: n, ni: c.net, at, kind: c.kind, pad: c.pad || null };
  }
  function frStart(w) {
    const h = frHit(w);
    if (!h) { toast('✍️ ప్యాడ్ / దారి మీద నుంచే మొదలుపెట్టండి'); return false; }
    const r = C.fingerRouter(D, M, h.net, fr.layer);
    if (!r) return false;
    fr.cur = { net: h.net, ni: h.ni, from: h.at, startPad: h.pad, r, pts: [h.at], reached: false, end: null, raf: 0, want: null };
    drawPcb();
    return true;
  }
  function frMove(w) {
    const c = fr.cur;
    c.want = w;
    if (c.raf) return;
    c.raf = requestAnimationFrame(() => {
      c.raf = 0;
      if (fr.cur !== c || !c.want) return;
      // a finger over the net's own pad / track: aim at its centre so the line ends inside it
      const h = frHit(c.want), aim = h && h.net === c.net && !(c.startPad && h.pad === c.startPad) ? h.at : c.want;
      const out = c.r.route(c.from[0], c.from[1], aim[0], aim[1]);
      c.pts = out.pts; c.reached = out.reached; c.end = h && h.net === c.net && out.reached && !(c.startPad && h.pad === c.startPad) ? h : null;
      drawPcb();
    });
  }
  function frEnd(cancel) {
    const c = fr.cur;
    fr.cur = null;
    if (c && c.raf) cancelAnimationFrame(c.raf);
    if (cancel || !c || c.pts.length < 2) { drawPcb(); return; }
    const len = c.pts.reduce((sum, p, i) => (i ? sum + Math.hypot(p[0] - c.pts[i - 1][0], p[1] - c.pts[i - 1][1]) : 0), 0);
    if (len < 0.5) { drawPcb(); return; }
    snap();
    const pts = c.pts.slice();
    if (c.end) pts[pts.length - 1] = c.end.at.slice();
    D.tracks.push({ n: c.net, l: c.r.layer, w: c.r.w, p: pts.map((q) => [r2(q[0]), r2(q[1])]) });
    changed();
    pcbTools();
    toast(c.end ? '✅ ' + c.net + ' కలిసింది (' + (c.r.layer === 'F' ? 'పై' : 'కింది') + ' పొర)' : c.reached ? '✍️ ముక్క వేశాను: దాని చివర నుంచి కొనసాగించండి' : '✍️ అడ్డం ఉన్న చోట ఆగాను: అక్కడి నుంచి కొనసాగించండి, లేదా 🔴/🔵 పొర మార్చండి', 3500);
  }
  function frDelete(w) {
    let best = null;
    D.tracks.forEach((t, ti) => { for (let i = 1; i < t.p.length; i++) { const d = C.dist({ k: 'c', x1: t.p[i - 1][0], y1: t.p[i - 1][1], x2: t.p[i][0], y2: t.p[i][1], r: (t.w || 0.4) / 2 }, w[0], w[1]); if (d < 0.5 && (!best || d < best.d)) best = { ti, d }; } });
    if (!best) { toast('🗑️ దారి మీదే నొక్కండి'); return; }
    snap();
    const t = D.tracks.splice(best.ti, 1)[0];
    changed();
    toast('🗑️ ' + t.n + ' దారి ముక్క తీసేశాను (↩️ తో వెనక్కి)');
  }
  function frDraw(ctx) {
    const s = pv.s, X = (x) => pv.ox + x * s, Y = (y) => pv.oy + y * s, c = fr.cur;
    if (!c) return;
    // the net's other copper glows: that is where the line may end
    M.pads.forEach((p) => { if (p.net !== c.ni || p === c.startPad) return; ctx.strokeStyle = 'rgba(116,228,255,.9)'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(X(p.x), Y(p.y), Math.max(5, Math.max(p.w, p.h) / 2 * s + 3), 0, Math.PI * 2); ctx.stroke(); });
    ctx.strokeStyle = c.r.layer === 'F' ? 'rgba(229,83,59,.85)' : 'rgba(79,163,255,.85)';
    ctx.lineWidth = Math.max(2, c.r.w * s); ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.beginPath(); c.pts.forEach((q, i) => (i ? ctx.lineTo(X(q[0]), Y(q[1])) : ctx.moveTo(X(q[0]), Y(q[1])))); ctx.stroke();
    if (c.want) { ctx.fillStyle = c.end ? '#43a047' : c.reached ? '#fff' : '#ffb74d'; ctx.beginPath(); ctx.arc(X(c.want[0]), Y(c.want[1]), 5, 0, Math.PI * 2); ctx.fill(); }
  }
  function netsOfPart(ref) { const s = new Set(); D.nets.forEach((n) => { if (n.p.some((p) => p.split('.')[0] === ref)) s.add(n.n); }); return s; }
  gestures($('pcbcv'), pv, {
    redraw: () => drawPcb(),
    tap(x, y) {
      if (!D) return;
      if (fr.on && fr.del) { frDelete(toWorld(pv, x, y)); return; }
      const w = toWorld(pv, x, y), pt = partAt(w[0], w[1]);
      pv.sel = pt ? pt.ref : null;
      selBar();
      drawPcb();
    },
    dragStart(x, y) {
      if (D && fr.on) return !fr.del && frStart(toWorld(pv, x, y));
      if (!D || !pv.sel) return false;
      const w = toWorld(pv, x, y), pt = partAt(w[0], w[1]);
      if (!pt || pt.ref !== pv.sel) return false;
      drag = { ref: pt.ref, x: pt.x, y: pt.y, rot: pt.rot || 0, dx: w[0] - pt.x, dy: w[1] - pt.y, nets: netsOfPart(pt.ref) };
      return true;
    },
    dragMove(x, y) {
      if (fr.cur) { frMove(toWorld(pv, x, y)); return; }
      const w = toWorld(pv, x, y), g = 0.635;
      drag.x = Math.round((w[0] - drag.dx) / g) * g; drag.y = Math.round((w[1] - drag.dy) / g) * g;
      drawPcb();
    },
    dragEnd(cancel) {
      if (fr.cur) { frEnd(cancel); return; }
      const d = drag;
      drag = null;
      if (cancel || !d) { drawPcb(); return; }
      const pt = D.parts.find((q) => q.ref === d.ref);
      if (!pt || (Math.abs(pt.x - d.x) < 0.01 && Math.abs(pt.y - d.y) < 0.01)) { drawPcb(); return; }
      snap();
      pt.x = r2(d.x); pt.y = r2(d.y);
      afterMove(d.nets);
    }
  });
  function afterMove(nets) {
    if (autoRoute) {
      chipBusy();
      setTimeout(() => { reroute(true); changed(); }, 30);
    } else {
      D.tracks = D.tracks.filter((t) => !nets.has(t.n));
      D.vias = D.vias.filter((v) => !nets.has(v.n));
      changed();
    }
  }
  function chipBusy() { const c = $('pcbchips'); c.appendChild(el('span', 'chip warn', '⚡ దారులు వేస్తున్నాను…')); }
  function selBar() {
    const b = $('sel');
    if (!D || !pv.sel || tab !== 'pcb') { b.style.display = 'none'; return; }
    const pt = D.parts.find((q) => q.ref === pv.sel);
    if (!pt) { b.style.display = 'none'; return; }
    const lib = P.LIB[pt.lib] || {};
    clear(b);
    b.appendChild(el('div', 't', pt.ref + ' · ' + (lib.te || pt.lib) + (pt.val ? ' · ' + pt.val : '')));
    b.appendChild(el('div', 'm', 'వేలితో లాగితే కదులుతుంది (0.635 mm గీతల మీద). ' + (autoRoute ? 'వదిలాక దారులు తానే మళ్ళీ వేస్తుంది.' : 'వదిలాక ⚡ రూట్ నొక్కండి.')));
    const r = el('div', 'row');
    r.appendChild(btn('🔄 తిప్పు', 'small', () => { snap(); pt.rot = ((pt.rot || 0) + 90) % 360; afterMove(netsOfPart(pt.ref)); }));
    r.appendChild(btn('ℹ️ ఇది ఏమిటి', 'small', () => partSheet(pt)));
    r.appendChild(btn('✕', 'small', () => { pv.sel = null; selBar(); drawPcb(); }));
    b.appendChild(r);
    b.style.display = 'block';
  }

  function boardSheet() {
    sheet((s) => {
      s.appendChild(el('h2', null, '📏 బోర్డ్'));
      const B = D.board;
      const stepper = (label, get, set, step, lo, hi, unit) => {
        const r = el('div', 'row');
        r.appendChild(el('div', 'k', label));
        const v = el('span', 'val', get() + (unit || ''));
        r.appendChild(btn('−', 'small', () => { const n = Math.max(lo, r2(get() - step)); if (n !== get()) { snap(); set(n); changed(); v.textContent = get() + (unit || ''); } }));
        r.appendChild(v);
        r.appendChild(btn('+', 'small', () => { const n = Math.min(hi, r2(get() + step)); if (n !== get()) { snap(); set(n); changed(); v.textContent = get() + (unit || ''); } }));
        s.appendChild(r);
      };
      stepper('వెడల్పు', () => B.w, (n) => { B.w = n; pv.fit = true; }, 1, 10, 200, ' mm');
      stepper('ఎత్తు', () => B.h, (n) => { B.h = n; pv.fit = true; }, 1, 10, 200, ' mm');
      stepper('మూలల వంపు', () => B.r, (n) => { B.r = n; }, 0.5, 0, 6, ' mm');
      const r1 = el('div', 'row');
      r1.appendChild(btn('📐 భాగాలకి సరిపడా చేయి', '', () => { snap(); C.fitBoard(D); pv.fit = true; reroute(true); changed(); closeSheet(); toast('📐 బోర్డ్ ' + D.board.w + ' × ' + D.board.h + ' mm అయింది'); }));
      r1.appendChild(btn((B.holes ? '✓ ' : '') + '🔩 స్క్రూ రంధ్రాలు (M3)', B.holes ? 'on' : '', () => { snap(); B.holes = !B.holes; reroute(true); changed(); boardSheet(); }));
      s.appendChild(r1);
      s.appendChild(el('h3', null, 'రంగు (సోల్డర్ మాస్క్)'));
      const sw = el('div', 'seg');
      Object.keys(MASK).forEach((k) => {
        const b = el('span', 'swatch' + (B.color === k ? ' on' : ''));
        b.style.background = MASK[k];
        b.addEventListener('click', () => { B.color = k; changed(); boardSheet(); });
        sw.appendChild(b);
      });
      s.appendChild(sw);
      s.appendChild(el('div', 'muted', 'ఆకుపచ్చ అన్నిటికంటే చవక, త్వరగా వస్తుంది. వేరే రంగులకి కొన్ని ఫ్యాక్టరీలు ఎక్కువ రోజులు / డబ్బు తీసుకుంటాయి.'));
      s.appendChild(el('h3', null, 'మందం, రాగి'));
      const r3 = el('div', 'seg');
      [1.0, 1.2, 1.6, 2.0].forEach((t) => r3.appendChild(btn(t + ' mm', B.thick === t ? 'on small' : 'small', () => { B.thick = t; changed(); boardSheet(); })));
      [1, 2].forEach((c) => r3.appendChild(btn(c + ' oz', B.cu === c ? 'on small' : 'small', () => { B.cu = c; changed(); boardSheet(); })));
      s.appendChild(r3);
      s.appendChild(el('div', 'muted', '1.6 mm, 1 oz మామూలు (చవక). ఎక్కువ కరెంట్ (3 A పైన) కి 2 oz.'));
      if (D.parts.some((p) => p.lib === 'esp32')) {
        s.appendChild(el('h3', null, 'ESP32 బోర్డ్ పిన్నుల వరుసల మధ్య దూరం'));
        const r4 = el('div', 'seg');
        [25.4, 22.86].forEach((v) => r4.appendChild(btn(v + ' mm', B.rows === v ? 'on small' : 'small', () => {
          snap(); B.rows = v; D.parts.forEach((p) => { if (p.lib === 'esp32') p.fp = 'ESP32_DEVKIT30_' + Math.round(v * 100); }); reroute(true); changed(); boardSheet();
        })));
        s.appendChild(r4);
        s.appendChild(el('div', 'muted', 'మీ ESP32 బోర్డ్‌ని స్కేల్‌తో కొలవండి: ఒక వరుస పిన్నుల మధ్య నుంచి ఇంకో వరుస మధ్య వరకు.'));
      }
    });
  }
  function renameSheet() {
    sheet((s) => {
      s.appendChild(el('h2', null, '✏️ పేరు'));
      const a = el('input'); a.value = D.name; a.maxLength = 60; a.style.width = '100%';
      s.appendChild(el('div', 'muted', 'మీకు కనిపించే పేరు (తెలుగు సరే):'));
      s.appendChild(a);
      const b = el('input'); b.value = D.title; b.maxLength = 24; b.style.width = '100%';
      s.appendChild(el('div', 'muted', 'బోర్డ్ మీద ముద్రించే పేరు (ఇంగ్లీష్ అక్షరాలు, అంకెలు మాత్రమే):'));
      s.appendChild(b);
      const r = el('div', 'row');
      r.appendChild(btn('సరే', 'main', () => {
        D.name = a.value.trim().slice(0, 60) || D.name;
        const t = b.value.toUpperCase().replace(/[^ -~]/g, '').trim().slice(0, 24);
        if (t && t !== D.title) { (D.labels || []).forEach((l) => { if (!l.ref && l.t === D.title) l.t = t; }); D.title = t; }
        changed(); closeSheet();
      }));
      s.appendChild(r);
    });
  }

  // ================================================================ 📐 the schematic

  /** A part's symbol in its own units: pins {pad: [x, y, dx, dy]} and shapes. */
  function symbol(pt) {
    const lib = P.LIB[pt.lib] || {}, names = P.pinsOf(pt), kind = lib.sym || 'box', S = [], pins = {}, T = [];
    const L = (...q) => S.push({ l: q });
    const byRole = (role) => Object.keys(names).find((k) => names[k] === role);
    const two = (a, b, len) => { pins[a] = [-len, 0, -1, 0]; pins[b] = [len, 0, 1, 0]; };
    switch (kind) {
      case 'res':
        two('1', '2', 30);
        L(-30, 0, -20, 0, -17, -7, -11, 7, -5, -7, 1, 7, 7, -7, 13, 7, 17, -7, 20, 0, 30, 0);
        break;
      case 'cap':
        two('1', '2', 20);
        L(-20, 0, -4, 0); L(4, 0, 20, 0); L(-4, -12, -4, 12); L(4, -12, 4, 12);
        break;
      case 'cpol':
        pins[byRole('+') || '1'] = [-20, 0, -1, 0]; pins[byRole('-') || '2'] = [20, 0, 1, 0];
        L(-20, 0, -4, 0); L(7, 0, 20, 0); L(-4, -12, -4, 12); S.push({ a: [17, 0, 13, 2.4, 3.9] }); L(-14, -10, -8, -10); L(-11, -13, -11, -7);
        break;
      case 'led': case 'diode':
        pins[byRole('A') || '2'] = [-20, 0, -1, 0]; pins[byRole('K') || '1'] = [20, 0, 1, 0];
        L(-20, 0, -8, 0); L(8, 0, 20, 0); S.push({ poly: [-8, -9, -8, 9, 8, 0] }); L(8, -9, 8, 9);
        if (kind === 'led') { L(0, -12, 7, -20); L(7, -20, 3, -19); L(7, -20, 6, -16); L(6, -11, 13, -19); L(13, -19, 9, -18); L(13, -19, 12, -15); }
        break;
      case 'npn': {
        pins[byRole('B') || '2'] = [-30, 0, -1, 0]; pins[byRole('C') || '1'] = [10, -30, 0, -1]; pins[byRole('E') || '3'] = [10, 30, 0, 1];
        S.push({ c: [2, 0, 16] }); L(-30, 0, -6, 0); L(-6, -10, -6, 10); L(-6, -4, 10, -14, 10, -30); L(-6, 4, 10, 14, 10, 30);
        S.push({ poly: [10, 14, 3, 13, 6, 8] });
        break;
      }
      case 'conn': {
        const ks = Object.keys(names).sort((a, b) => (parseInt(a, 10) || 0) - (parseInt(b, 10) || 0));
        const n = ks.length, h = n * 20;
        S.push({ r: [-14, -h / 2, 0, h / 2] });
        ks.forEach((k, i) => { const y = -h / 2 + 10 + i * 20; pins[k] = [20, y, 1, 0]; L(0, y, 20, y); S.push({ c: [-7, y, 3] }); if (names[k] !== k) T.push({ t: names[k], x: -18, y, a: 'right', small: true }); });
        break;
      }
      case 'buzzer':
        pins[byRole('+') || '1'] = [-20, 0, -1, 0]; pins[byRole('-') || '2'] = [20, 0, 1, 0];
        L(-20, 0, -8, 0); L(8, 0, 20, 0); S.push({ a: [0, -2, 10, Math.PI, 2 * Math.PI] }); L(-10, -2, 10, -2); L(-14, -10, -9, -10); L(-11.5, -12.5, -11.5, -7.5);
        break;
      case 'fuse':
        two('1', '2', 30);
        L(-30, 0, 30, 0); S.push({ r: [-14, -6, 14, 6] });
        break;
      case 'spdt': // slide switch: the common (C) on the left, A up and B down on the right; the lever rests on A
        pins[byRole('C') || '2'] = [-30, 0, -1, 0]; pins[byRole('A') || '1'] = [30, -10, 1, 0]; pins[byRole('B') || '3'] = [30, 10, 1, 0];
        L(-30, 0, -11, 0); S.push({ c: [-9, 0, 2] }); L(-7, -1, 9, -9); S.push({ c: [11, -10, 2] }); S.push({ c: [11, 10, 2] }); L(13, -10, 30, -10); L(13, 10, 30, 10);
        T.push({ t: 'A', x: 22, y: -16, a: 'center', small: true }); T.push({ t: 'B', x: 22, y: 16, a: 'center', small: true });
        break;
      case 'mic': // electret mic: a circle with a bar on its sound side
        pins[byRole('+') || '1'] = [-25, 0, -1, 0]; pins[byRole('-') || '2'] = [25, 0, 1, 0];
        S.push({ c: [0, 0, 10] }); L(-25, 0, -10, 0); L(10, 0, 25, 0); L(-10, -10, -10, 10); L(-20, -9, -14, -9); L(-17, -12, -17, -6);
        break;
      case 'btn': // push button: two contacts and the bar above them that comes down when pressed
        two('1', '2', 30);
        L(-30, 0, -12, 0); S.push({ c: [-10, 0, 2] }); S.push({ c: [10, 0, 2] }); L(12, 0, 30, 0); L(-13, -8, 13, -8); L(0, -8, 0, -15); L(-5, -15, 5, -15);
        break;
      case 'pot': // knob: a resistor with an arrow (the wiper W) pressing on it from above
        pins[byRole('1') || '1'] = [-30, 0, -1, 0]; pins[byRole('3') || '3'] = [30, 0, 1, 0]; pins[byRole('W') || '2'] = [0, -30, 0, -1];
        L(-30, 0, -20, 0, -17, -7, -11, 7, -5, -7, 1, 7, 7, -7, 13, 7, 17, -7, 20, 0, 30, 0);
        L(0, -30, 0, -14); S.push({ poly: [0, -8, -4, -15, 4, -15] });
        break;
      case 'nmos': { // N-channel MOSFET: the gate plate on the left, D up, S down, the body arrow pointing in
        pins[byRole('G') || '1'] = [-30, 8, -1, 0]; pins[byRole('D') || '2'] = [10, -30, 0, -1]; pins[byRole('S') || '3'] = [10, 30, 0, 1];
        L(-30, 8, -10, 8); L(-10, -12, -10, 12);
        L(-5, -14, -5, -7); L(-5, -3, -5, 3); L(-5, 7, -5, 14);
        L(-5, -10, 10, -10, 10, -30); L(-5, 10, 10, 10, 10, 30); L(-5, 0, 10, 0, 10, 10);
        S.push({ poly: [-4, 0, 3, -3.5, 3, 3.5] });
        break;
      }
      case 'cell': // a battery cell: the long plate +, the short plate −
        pins[byRole('+') || '1'] = [-25, 0, -1, 0]; pins[byRole('-') || '2'] = [25, 0, 1, 0];
        L(-25, 0, -3, 0); L(-3, -13, -3, 13); L(4, -7, 4, 7); L(4, 0, 25, 0); L(-14, -11, -8, -11); L(-11, -14, -11, -8);
        break;
      default: {
        const bx = (lib.vars && pt.var > 0 && lib.vars[pt.var] && lib.vars[pt.var].box) || lib.box || { L: Object.keys(names) };
        const Ls = bx.L || [], Rs = bx.R || [], Ts = bx.T || [], Bs = bx.B || [];
        const longest = (arr) => arr.reduce((m, k) => Math.max(m, String(names[k] || k).length), 0);
        const mt = Ts.length ? 22 : 10, mb = Bs.length ? 22 : 10;
        const w = Math.max(60, 40 * Math.max(Ts.length, Bs.length) + 10, (longest(Ls) + longest(Rs)) * 8 + 24), h = Math.max(40, 20 * Math.max(Ls.length, Rs.length) + mt + mb - 20 + 20);
        S.push({ r: [-w / 2, -h / 2, w / 2, h / 2] });
        Ls.forEach((k, i) => { const y = -h / 2 + mt + 10 + i * 20; pins[k] = [-w / 2 - 20, y, -1, 0]; L(-w / 2 - 20, y, -w / 2, y); T.push({ t: names[k] || k, x: -w / 2 + 4, y, a: 'left', small: true }); });
        Rs.forEach((k, i) => { const y = -h / 2 + mt + 10 + i * 20; pins[k] = [w / 2 + 20, y, 1, 0]; L(w / 2, y, w / 2 + 20, y); T.push({ t: names[k] || k, x: w / 2 - 4, y, a: 'right', small: true }); });
        const sp = (arr) => (arr.length > 1 && longest(arr) > 2 ? 40 : 20);
        Ts.forEach((k, i) => { const x = -((Ts.length - 1) * sp(Ts)) / 2 + i * sp(Ts); pins[k] = [x, -h / 2 - 20, 0, -1]; L(x, -h / 2 - 20, x, -h / 2); T.push({ t: names[k] || k, x, y: -h / 2 + 9, a: 'center', small: true }); });
        Bs.forEach((k, i) => { const x = -((Bs.length - 1) * sp(Bs)) / 2 + i * sp(Bs); pins[k] = [x, h / 2 + 20, 0, 1]; L(x, h / 2, x, h / 2 + 20); T.push({ t: names[k] || k, x, y: h / 2 - 9, a: 'center', small: true }); });
      }
    }
    return { S, pins, T };
  }
  /** The symbol turned and placed: world pins, a box around it. */
  function placed(pt) {
    const sy = symbol(pt), r = pt.srot || 0, x0 = pt.sx || 0, y0 = pt.sy || 0;
    const tr = (x, y) => { const q = C.rot(x, y, r); return [x0 + q[0], y0 + q[1]]; };
    const pins = {};
    Object.keys(sy.pins).forEach((k) => { const p = sy.pins[k], a = tr(p[0], p[1]), d = C.rot(p[2], p[3], r); pins[k] = [a[0], a[1], d[0], d[1]]; });
    let bx = [1e9, 1e9, -1e9, -1e9];
    const grow = (x, y) => { bx = [Math.min(bx[0], x), Math.min(bx[1], y), Math.max(bx[2], x), Math.max(bx[3], y)]; };
    sy.T.forEach((t) => {
      const q = tr(t.x, t.y), w = String(t.t).length * 6;
      if (r === 90 || r === 270) grow(q[0], q[1]);
      else { const a = r === 180 ? (t.a === 'left' ? 'right' : t.a === 'right' ? 'left' : t.a) : t.a; grow(a === 'right' ? q[0] - w : a === 'left' ? q[0] + w : q[0] - w / 2, q[1]); if (a === 'center') grow(q[0] + w / 2, q[1]); }
    });
    sy.S.forEach((s) => {
      if (s.l) for (let i = 0; i < s.l.length; i += 2) { const q = tr(s.l[i], s.l[i + 1]); grow(q[0], q[1]); }
      if (s.poly) for (let i = 0; i < s.poly.length; i += 2) { const q = tr(s.poly[i], s.poly[i + 1]); grow(q[0], q[1]); }
      if (s.r) { const a = tr(s.r[0], s.r[1]), b = tr(s.r[2], s.r[3]); grow(a[0], a[1]); grow(b[0], b[1]); }
      if (s.c) { const q = tr(s.c[0], s.c[1]); grow(q[0] - s.c[2], q[1] - s.c[2]); grow(q[0] + s.c[2], q[1] + s.c[2]); }
      if (s.a) { const q = tr(s.a[0], s.a[1]); grow(q[0] - s.a[2], q[1] - s.a[2]); grow(q[0] + s.a[2], q[1] + s.a[2]); }
    });
    // the drawn body alone (no pin leads, no names): wires keep a little room from it
    const box = bx;
    bx = [1e9, 1e9, -1e9, -1e9];
    const pinAt = (x, y) => Object.keys(sy.pins).some((k) => Math.abs(sy.pins[k][0] - x) < 0.5 && Math.abs(sy.pins[k][1] - y) < 0.5);
    sy.S.forEach((s) => {
      if (s.l) for (let i = 0; i < s.l.length; i += 2) { if (pinAt(s.l[i], s.l[i + 1])) continue; const q = tr(s.l[i], s.l[i + 1]); grow(q[0], q[1]); }
      if (s.poly) for (let i = 0; i < s.poly.length; i += 2) { const q = tr(s.poly[i], s.poly[i + 1]); grow(q[0], q[1]); }
      if (s.r) { const a = tr(s.r[0], s.r[1]), b = tr(s.r[2], s.r[3]); grow(a[0], a[1]); grow(b[0], b[1]); }
      if (s.c) { const q = tr(s.c[0], s.c[1]); grow(q[0] - s.c[2], q[1] - s.c[2]); grow(q[0] + s.c[2], q[1] + s.c[2]); }
      if (s.a) { const q = tr(s.a[0], s.a[1]); grow(q[0] - s.a[2], q[1] - s.a[2]); grow(q[0] + s.a[2], q[1] + s.a[2]); }
    });
    return { sy, tr, pins, box, body: bx[0] < 1e9 ? bx : box, r };
  }
  function autoSch() {
    // parts without a place on the drawing get one (a grid in the order they join)
    const missing = D.parts.filter((p) => !p.sx && !p.sy);
    if (!missing.length) return;
    const order = A.schOrder(D);
    missing.sort((a, b) => order.indexOf(a.ref) - order.indexOf(b.ref));
    let x = 60, y = 60, rowH = 0;
    missing.forEach((pt) => {
      const pl = placed(Object.assign({}, pt, { sx: 0, sy: 0 })), w = pl.box[2] - pl.box[0], h = pl.box[3] - pl.box[1];
      if (x + w > 470 && x > 60) { x = 60; y += rowH + 85; rowH = 0; }
      pt.sx = Math.round(x - pl.box[0]); pt.sy = Math.round(y - pl.box[1]);
      x += w + 95; rowH = Math.max(rowH, h);
    });
  }
  let layCache = null;
  /** The drawing's layout: symbols placed, wires (never on top of another net's wire, never through a symbol), flags, names. */
  function schLayout() {
    autoSch();
    const key = D.id + '|' + (sv.vals ? 1 : 0) + '|' + D.parts.map((p) => [p.ref, p.lib, p.val, p.sx, p.sy, p.srot || 0, p.fp || '', p.pins ? JSON.stringify(p.pins) : ''].join(',')).join(';') + '|' + JSON.stringify(D.nets);
    if (layCache && layCache.key === key) return layCache.lay;
    const P2 = new Map();
    D.parts.forEach((pt) => P2.set(pt.ref, placed(pt)));
    const wires = [], flags = [], dots = [], labels = [], segAll = [];
    // every pin end on the drawing with its net: a wire never runs over another net's pin (it would look joined)
    const netOf = new Map();
    D.nets.forEach((n) => n.p.forEach((q) => netOf.set(q, n.n)));
    const pinEnds = [];
    P2.forEach((pl, ref) => Object.keys(pl.pins).forEach((k) => { const e = pl.pins[k]; pinEnds.push([e[0], e[1], netOf.get(ref + '.' + k) || '']); }));
    // room kept for the supply / ground symbols that may sit on supply pins (wires go around them)
    const flagRoom = [];
    P2.forEach((pl, ref) => Object.keys(pl.pins).forEach((k) => {
      const nn = netOf.get(ref + '.' + k);
      if (!nn || !C.isPower(nn)) return;
      const e = pl.pins[k], ex = e[0] + e[2] * 14, ey = e[1] + e[3] * 14;
      flagRoom.push([Math.min(e[0], ex) - 2, Math.min(e[1], ey) - 2, Math.max(e[0], ex) + 2, Math.max(e[1], ey) + 2, nn]);
      flagRoom.push(C.isGnd(nn) ? [ex - 10, ey, ex + 10, ey + 14, nn] : [ex - 9, ey - 8, ex + 9, ey, nn]);
    }));
    // shorter nets first: their wires take the straight paths, longer ones go around them
    const order = D.nets.slice().sort((x, y) => (C.isPower(x.n) ? 1 : 0) - (C.isPower(y.n) ? 1 : 0) || x.p.length - y.p.length);
    order.forEach((n) => {
      const ends = n.p.map((p) => { const [ref, pad] = p.split('.'); const pl = P2.get(ref); return pl && pl.pins[pad] ? pl.pins[pad] : null; }).filter(Boolean);
      if (!ends.length) return;
      const power = C.isPower(n.n), gnd = C.isGnd(n.n);
      if (power) {
        // pins of a supply net close together are joined by a wire and get one symbol; far ones get their own
        const grp = ends.map((_, i) => i), f = (i) => (grp[i] === i ? i : (grp[i] = f(grp[i])));
        ends.forEach((a, i) => ends.forEach((b, j) => { if (j > i && Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) <= 50) grp[f(i)] = f(j); }));
        const by = new Map();
        ends.forEach((e, i) => { const r = f(i); if (!by.has(r)) by.set(r, []); by.get(r).push(e); });
        by.forEach((es) => {
          if (es.length > 1) join(es, n.n, false);
          const pick = es.reduce((m, e) => (gnd ? (e[1] > m[1] ? e : m) : (e[1] < m[1] ? e : m)), es[0]);
          flags.push({ e: pick, n: n.n, gnd });
        });
        return;
      }
      join(ends, n.n, true);
    });
    let bounds = [1e9, 1e9, -1e9, -1e9];
    P2.forEach((pl) => { bounds = [Math.min(bounds[0], pl.box[0]), Math.min(bounds[1], pl.box[1]), Math.max(bounds[2], pl.box[2]), Math.max(bounds[3], pl.box[3])]; });
    const taken = [];
    flags.forEach((f) => {
      const e = f.e, ex = e[0] + e[2] * 14, ey = e[1] + e[3] * 14;
      if (f.gnd) taken.push([ex - 10, ey, ex + 10, ey + 14]);
      else { const w = String(f.n).length * 7; taken.push([ex - w / 2, ey - 21, ex + w / 2, ey - 5]); }
    });
    // a net's name beside its wire, never on it (none when there is no free spot next to its wires)
    const free = [];
    labels.forEach((l) => { const b = nameSpot(l, taken); if (b) { free.push({ t: l.t, box: b.slice(0, 4), side: b[4] }); taken.push(b.slice(0, 4)); } });
    const lay = { P2, wires, flags, dots, labels: free, bounds, names: new Map() };
    // each part's name and value: beside it, off wires, flags, other symbols and the names already placed
    D.parts.forEach((pt) => { const at = nameAt(pt, P2.get(pt.ref), lay, taken); lay.names.set(pt.ref, at); taken.push(at.box); });
    layCache = { key, lay };
    return lay;

    function inside(x1, y1, x2, y2, b) { // does a straight wire run through a symbol (not just touch its pin end)?
      const bx0 = b[0] + 3, by0 = b[1] + 3, bx1 = b[2] - 3, by1 = b[3] - 3;
      return Math.max(x1, x2) > bx0 && Math.min(x1, x2) < bx1 && Math.max(y1, y2) > by0 && Math.min(y1, y2) < by1;
    }
    function near(x1, y1, x2, y2, b) { // does a straight wire touch a symbol's drawn body (with 4 of room around it)?
      return Math.max(x1, x2) > b[0] - 4 && Math.min(x1, x2) < b[2] + 4 && Math.max(y1, y2) > b[1] - 4 && Math.min(y1, y2) < b[3] + 4;
    }
    function cost(pts, name, A, Bp) {
      let c = 0;
      for (let i = 0; i + 3 < pts.length; i += 2) {
        const x1 = pts[i], y1 = pts[i + 1], x2 = pts[i + 2], y2 = pts[i + 3];
        if (Math.abs(x1 - x2) < 0.01 && Math.abs(y1 - y2) < 0.01) continue;
        const hz = Math.abs(y1 - y2) < 0.5;
        c += Math.abs(x2 - x1) + Math.abs(y2 - y1);
        for (const q of segAll) {
          if (q[4] === name) continue;
          const qh = Math.abs(q[1] - q[3]) < 0.5;
          if (hz && qh) {
            if (Math.abs(q[1] - y1) < 5 && Math.min(Math.max(x1, x2), Math.max(q[0], q[2])) - Math.max(Math.min(x1, x2), Math.min(q[0], q[2])) > 1) c += 500;
          } else if (!hz && !qh) {
            if (Math.abs(q[0] - x1) < 5 && Math.min(Math.max(y1, y2), Math.max(q[1], q[3])) - Math.max(Math.min(y1, y2), Math.min(q[1], q[3])) > 1) c += 500;
          } else {
            const h = hz ? [x1, y1, x2] : [q[0], q[1], q[2]], v = hz ? [q[0], q[1], q[3]] : [x1, y1, y2];
            if (v[0] > Math.min(h[0], h[2]) + 1 && v[0] < Math.max(h[0], h[2]) - 1 && h[1] > Math.min(v[1], v[2]) + 1 && h[1] < Math.max(v[1], v[2]) - 1) c += 30;
          }
        }
        P2.forEach((pl) => { if (inside(x1, y1, x2, y2, pl.box) || near(x1, y1, x2, y2, pl.body)) c += 300; });
        for (const r of flagRoom) if (r[4] !== name && Math.max(x1, x2) > r[0] && Math.min(x1, x2) < r[2] && Math.max(y1, y2) > r[1] && Math.min(y1, y2) < r[3]) c += 200;
        for (const e of pinEnds) {
          if (e[2] === name) continue;
          const on = hz ? Math.abs(e[1] - y1) < 1.5 && e[0] > Math.min(x1, x2) - 1.5 && e[0] < Math.max(x1, x2) + 1.5
            : Math.abs(e[0] - x1) < 1.5 && e[1] > Math.min(y1, y2) - 1.5 && e[1] < Math.max(y1, y2) + 1.5;
          if (on) c += 500;
        }
      }
      // leave a pin the way it points, arrive the way the other pin points
      if (pts.length >= 4 && ((pts[2] - A[0]) * A[2] + (pts[3] - A[1]) * A[3]) < -0.5) c += 60;
      const n = pts.length;
      if (n >= 4 && ((pts[n - 4] - Bp[0]) * Bp[2] + (pts[n - 3] - Bp[1]) * Bp[3]) < -0.5) c += 60;
      return c + (n / 2 - 2) * 8;
    }
    function tidy(pts) { // drop repeated and in-line points
      const o = [];
      for (let i = 0; i < pts.length; i += 2) {
        const x = pts[i], y = pts[i + 1], k = o.length;
        if (k >= 2 && Math.abs(o[k - 2] - x) < 0.01 && Math.abs(o[k - 1] - y) < 0.01) continue;
        if (k >= 4 && ((Math.abs(o[k - 4] - o[k - 2]) < 0.01 && Math.abs(o[k - 2] - x) < 0.01) || (Math.abs(o[k - 3] - o[k - 1]) < 0.01 && Math.abs(o[k - 1] - y) < 0.01))) { o[k - 2] = x; o[k - 1] = y; continue; }
        o.push(x, y);
      }
      return o;
    }
    function route(A, Bp, name) {
      const g = 10, xs = new Set(), ys = new Set(), ax = [], bx = [], ay = [], by = [];
      for (let k = -5; k <= 5; k++) { xs.add(Math.round((A[0] + Bp[0]) / 2 / g) * g + k * g); ys.add(Math.round((A[1] + Bp[1]) / 2 / g) * g + k * g); }
      [10, 20, 30].forEach((d) => { ax.push(A[0] + A[2] * d); bx.push(Bp[0] + Bp[2] * d); ay.push(A[1] + A[3] * d); by.push(Bp[1] + Bp[3] * d); });
      ax.concat(bx).forEach((x) => xs.add(x)); ay.concat(by).forEach((y) => ys.add(y));
      let best = null;
      const len = (t) => { let d = 0; for (let i = 0; i + 3 < t.length; i += 2) d += Math.abs(t[i + 2] - t[i]) + Math.abs(t[i + 3] - t[i + 1]); return d; };
      const tryAll = (cands, extra) => cands.forEach((c) => { const t = tidy(c), v = cost(t, name, A, Bp) + extra; if (!best || v < best.v) best = { v, t }; });
      // an L, then a Z through a free column / row
      tryAll([[A[0], A[1], Bp[0], A[1], Bp[0], Bp[1]], [A[0], A[1], A[0], Bp[1], Bp[0], Bp[1]]], 0);
      const zs = [];
      xs.forEach((xm) => zs.push([A[0], A[1], xm, A[1], xm, Bp[1], Bp[0], Bp[1]]));
      ys.forEach((ym) => zs.push([A[0], A[1], A[0], ym, Bp[0], ym, Bp[0], Bp[1]]));
      tryAll(zs, 3);
      if (best.v - len(best.t) < 60) return best.t;
      // still crossing something: step out of each pin first and go around (up to five straight pieces)
      const more = [];
      ys.forEach((ym) => {
        ax.forEach((x1) => { more.push([A[0], A[1], x1, A[1], x1, ym, Bp[0], ym, Bp[0], Bp[1]]); bx.forEach((x2) => more.push([A[0], A[1], x1, A[1], x1, ym, x2, ym, x2, Bp[1], Bp[0], Bp[1]])); });
        bx.forEach((x2) => more.push([A[0], A[1], A[0], ym, x2, ym, x2, Bp[1], Bp[0], Bp[1]]));
      });
      xs.forEach((xm) => {
        ay.forEach((y1) => { more.push([A[0], A[1], A[0], y1, xm, y1, xm, Bp[1], Bp[0], Bp[1]]); by.forEach((y2) => more.push([A[0], A[1], A[0], y1, xm, y1, xm, y2, Bp[0], y2, Bp[0], Bp[1]])); });
        by.forEach((y2) => more.push([A[0], A[1], xm, A[1], xm, y2, Bp[0], y2, Bp[0], Bp[1]]));
      });
      tryAll(more, 6);
      return best.t;
    }
    function join(ends, name, named) {
      // shortest joining (Prim), each link routed around the wires already drawn
      const inT = [0], left = ends.map((_, i) => i).slice(1), deg = ends.map(() => 0), mine = [];
      while (left.length) {
        let best = null;
        inT.forEach((a) => left.forEach((b, bi) => { const d = Math.abs(ends[a][0] - ends[b][0]) + Math.abs(ends[a][1] - ends[b][1]); if (!best || d < best.d) best = { d, a, b, bi }; }));
        const pts = route(ends[best.a], ends[best.b], name);
        pts.net = name;
        wires.push(pts);
        for (let i = 0; i + 3 < pts.length; i += 2) {
          segAll.push([pts[i], pts[i + 1], pts[i + 2], pts[i + 3], name]);
          mine.push([pts[i], pts[i + 1], pts[i + 2], pts[i + 3]]);
        }
        deg[best.a]++; deg[best.b]++;
        inT.push(best.b); left.splice(best.bi, 1);
      }
      ends.forEach((e, i) => { if (deg[i] > 1) dots.push(e); });
      if (named) labels.push({ t: name, segs: mine });
    }
    function nameSpot(l, taken) { // a net's name beside one of its own wires: above a level one, right of an upright one; off wires, symbols, flags and names
      const w = String(l.t).length * 6.2, hh = 12, hit = (b, q) => Math.max(q[0], q[2]) >= b[0] && Math.min(q[0], q[2]) <= b[2] && Math.max(q[1], q[3]) >= b[1] && Math.min(q[1], q[3]) <= b[3];
      const over = (a, b, m) => a[0] - m < b[2] && b[0] < a[2] + m && a[1] - m < b[3] && b[1] < a[3] + m;
      let best = null;
      l.segs.forEach((q) => {
        const hz = Math.abs(q[1] - q[3]) < 0.5, len = Math.abs(q[2] - q[0]) + Math.abs(q[3] - q[1]);
        if (len <= 30) return;
        const mx = (q[0] + q[2]) / 2, my = (q[1] + q[3]) / 2;
        const cs = hz ? [[mx - w / 2, my - 3 - hh, mx + w / 2, my - 3, 0], [mx - w / 2, my + 3, mx + w / 2, my + 3 + hh, 1]]
          : [[mx + 5, my - hh / 2, mx + 5 + w, my + hh / 2, 2], [mx - 5 - w, my - hh / 2, mx - 5, my + hh / 2, 3]];
        cs.forEach((b) => {
          let n = b[4] * 3 - Math.min(len, 150) / 30;
          for (const s of segAll) if (hit(b, s)) n += 100;
          P2.forEach((pl) => { if (over(pl.box, b, 3)) n += 1000; else if (over(pl.box, b, 12)) n += 40; }); // close to a symbol it reads as the symbol's
          for (const t of taken) if (over(t, b, 1)) n += 100;
          if (!best || n < best.n) best = { n, b: b.slice(0, 5) };
        });
      });
      return best && best.n < 1000 ? best.b : null;
    }
  }
  /** Where a symbol's name goes: right / left of a tall one, above / below a wide one, the first side with nothing on it. */
  function nameAt(pt, pl, lay, taken) {
    const b = pl.box, wide = b[2] - b[0] > (b[3] - b[1]) * 1.2, tw = Math.max(String(pt.ref).length * 7.5, sv.vals ? String(pt.val || '').length * 6 : 0), th = sv.vals && pt.val ? 24 : 13;
    const cy = (b[1] + b[3]) / 2, cx = (b[0] + b[2]) / 2;
    const cands = wide ? [{ x: cx, y: b[1] - th / 2 - 4, a: 'center' }, { x: cx, y: b[3] + th / 2 + 4, a: 'center' }, { x: b[2] + 6, y: cy, a: 'left' }, { x: b[0] - 6, y: cy, a: 'right' }]
      : [{ x: b[2] + 6, y: cy, a: 'left' }, { x: b[0] - 6, y: cy, a: 'right' }, { x: cx, y: b[1] - th / 2 - 4, a: 'center' }, { x: cx, y: b[3] + th / 2 + 4, a: 'center' }];
    // a little further out when the first places are taken
    cands.slice().forEach((c) => cands.push(c.a === 'center' ? { x: c.x, y: c.y + (c.y < cy ? -14 : 14), a: c.a } : { x: c.x + (c.a === 'left' ? 14 : -14), y: c.y, a: c.a }));
    const segs = [];
    lay.wires.forEach((q) => { for (let i = 0; i + 3 < q.length; i += 2) segs.push([q[i], q[i + 1], q[i + 2], q[i + 3]]); });
    lay.flags.forEach((f) => { const e = f.e; segs.push([e[0], e[1], e[0] + e[2] * 14, e[1] + e[3] * 14]); });
    const boxOf = (c) => { const x0 = c.a === 'left' ? c.x : c.a === 'right' ? c.x - tw : c.x - tw / 2; return [x0, c.y - th / 2, x0 + tw, c.y + th / 2]; };
    const over = (p, q) => p[0] <= q[2] && p[2] >= q[0] && p[1] <= q[3] && p[3] >= q[1];
    const hits = (c) => {
      const B = boxOf(c);
      let n = 0;
      segs.forEach((q) => { if (Math.max(q[0], q[2]) >= B[0] && Math.min(q[0], q[2]) <= B[2] && Math.max(q[1], q[3]) >= B[1] && Math.min(q[1], q[3]) <= B[3]) n++; });
      lay.P2.forEach((o, ref) => { if (ref !== pt.ref && over(o.box, B)) n += 2; });
      (taken || []).forEach((t) => { if (over(t, B)) n += 2; });
      return n;
    };
    const all = lay.bounds;
    const out = (c) => { const B = boxOf(c); return all && (B[0] < all[0] - 4 || B[2] > all[2] + 4) ? 1 : 0; };
    let best = null;
    cands.forEach((c, i) => { const n = (hits(c) * 2 + out(c)) * 10 + i; if (!best || n < best.n) best = { n, c }; });
    return Object.assign({ box: boxOf(best.c) }, best.c);
  }

  function schTools() {
    const t = clear($('schtools'));
    t.appendChild(tool(sv.vals ? '🏷️ విలువలు ✓' : '🏷️ విలువలు', sv.vals ? 'on' : '', () => { sv.vals = !sv.vals; schTools(); drawSch(); }));
    t.appendChild(tool('✏️ మార్పు', '', () => editSheet('')));
    t.appendChild(tool('⚡ నడిపించు', '', () => showTab('sim')));
    t.appendChild(tool('🔌 వైర్లు', '', () => wireSheet(false)));
    t.appendChild(tool('🔍', '', () => { sv.fit = true; drawSch(); }));
    t.appendChild(tool('🧩 PCB చూడు', '', () => showTab('pcb')));
    t.appendChild(tool('🕑', '', verSheet));
  }
  function drawSch() {
    drawSchOn($('schcv'), sv, null);
    const c = clear($('schchips'));
    c.appendChild(el('span', 'chip', '👆 భాగం మీద నొక్కితే అది ఏమిటో తెలుగులో'));
  }
  /** The drawing on a canvas. ov: a ⚡ run's readings ({v: net → volts, p: ref → reading, sev: ref → 'err' | 'warn', glow: ref → 0..1}) or null. */
  function drawSchOn(cv, sv, ov) {
    if (!D) return;
    const { ctx, w, h } = sizeCv(cv), lay = schLayout();
    let bx = [1e9, 1e9, -1e9, -1e9];
    lay.P2.forEach((pl) => { bx = [Math.min(bx[0], pl.box[0] - 30), Math.min(bx[1], pl.box[1] - 40), Math.max(bx[2], pl.box[2] + 30), Math.max(bx[3], pl.box[3] + 40)]; });
    if (sv.fit) { // (sv.top: where the drawing may start, below the chips over it — ⚡ can have two rows of them)
      const top = sv.top > 40 ? sv.top : 40, room = h - top - 90;
      const s = Math.min((w - 16) / (bx[2] - bx[0]), room / (bx[3] - bx[1]), 2.2);
      sv.s = s; sv.ox = (w - (bx[2] - bx[0]) * s) / 2 - bx[0] * s; sv.oy = top + (room - (bx[3] - bx[1]) * s) / 2 - bx[1] * s; sv.fit = false;
      sv.fitted = [sv.s, sv.ox, sv.oy];
    }
    const s = sv.s, X = (x) => sv.ox + x * s, Y = (y) => sv.oy + y * s;
    ctx.fillStyle = '#0a1016'; ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = 'rgba(116,228,255,.06)';
    const gs = 20 * s;
    if (gs > 6) for (let x = ((sv.ox % gs) + gs) % gs; x < w; x += gs) for (let y = ((sv.oy % gs) + gs) % gs; y < h; y += gs) ctx.fillRect(x - 0.5, y - 0.5, 1, 1);
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    // wires (⚡: coloured by their volts)
    ctx.strokeStyle = '#6be3a4'; ctx.lineWidth = Math.max(1, (ov ? 2.2 : 1.6) * s);
    lay.wires.forEach((q) => {
      const unknown = ov && typeof ov.v[q.net] !== 'number'; // (⚡: a wire that only goes out to a module / cable)
      if (ov) ctx.strokeStyle = unknown ? '#7d8f99' : voltColour(ov.v[q.net], ov.vmax);
      if (unknown) ctx.setLineDash([4, 4]);
      ctx.beginPath(); ctx.moveTo(X(q[0]), Y(q[1])); for (let i = 2; i + 1 < q.length; i += 2) ctx.lineTo(X(q[i]), Y(q[i + 1])); ctx.stroke();
      if (unknown) ctx.setLineDash([]);
    });
    ctx.fillStyle = '#6be3a4';
    lay.dots.forEach((e) => { ctx.beginPath(); ctx.arc(X(e[0]), Y(e[1]), Math.max(2, 3.2 * s), 0, Math.PI * 2); ctx.fill(); });
    // power and ground flags
    ctx.font = Math.max(9, 11 * s) + 'px ' + FONT;
    lay.flags.forEach((f) => {
      const e = f.e, ex = e[0] + e[2] * 14, ey = e[1] + e[3] * 14;
      ctx.strokeStyle = f.gnd ? '#9fb7c3' : '#ffc861'; ctx.lineWidth = Math.max(1, 1.6 * s);
      ctx.beginPath(); ctx.moveTo(X(e[0]), Y(e[1])); ctx.lineTo(X(ex), Y(ey)); ctx.stroke();
      ctx.beginPath();
      if (f.gnd) { ctx.moveTo(X(ex), Y(ey)); ctx.lineTo(X(ex), Y(ey + 6)); ctx.moveTo(X(ex - 9), Y(ey + 6)); ctx.lineTo(X(ex + 9), Y(ey + 6)); ctx.moveTo(X(ex - 6), Y(ey + 9.5)); ctx.lineTo(X(ex + 6), Y(ey + 9.5)); ctx.moveTo(X(ex - 3), Y(ey + 13)); ctx.lineTo(X(ex + 3), Y(ey + 13)); }
      else { ctx.moveTo(X(ex), Y(ey)); ctx.lineTo(X(ex), Y(ey - 6)); ctx.moveTo(X(ex - 8), Y(ey - 6)); ctx.lineTo(X(ex + 8), Y(ey - 6)); }
      ctx.stroke();
      if (!f.gnd) { ctx.fillStyle = '#ffc861'; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom'; ctx.fillText(f.n, X(ex), Y(ey - 8)); }
      if (ov && !f.gnd && typeof ov.v[f.n] === 'number') { ctx.fillStyle = voltColour(ov.v[f.n], ov.vmax); ctx.font = '600 ' + Math.max(8, 9.5 * s) + 'px ' + FONT; ctx.fillText(SIM.fmtV(ov.v[f.n]), X(ex), Y(ey - 20)); ctx.font = Math.max(9, 11 * s) + 'px ' + FONT; }
    });
    // net names
    ctx.fillStyle = 'rgba(107,227,164,.75)'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.font = Math.max(8, 9.5 * s) + 'px ' + FONT;
    lay.labels.forEach((l) => {
      // beside an upright wire the words start at the side away from it (a longer ⚡ volts text then never runs onto the wire)
      const x = l.side === 2 ? l.box[0] : l.side === 3 ? l.box[2] : (l.box[0] + l.box[2]) / 2, y = (l.box[1] + l.box[3]) / 2;
      ctx.textAlign = l.side === 2 ? 'left' : l.side === 3 ? 'right' : 'center';
      if (ov && typeof ov.v[l.t] === 'number') { ctx.fillStyle = voltColour(ov.v[l.t], ov.vmax); ctx.fillText(SIM.fmtV(ov.v[l.t]), X(x), Y(y)); ctx.fillStyle = 'rgba(107,227,164,.75)'; }
      else ctx.fillText(l.t, X(x), Y(y));
    });
    ctx.textAlign = 'center';
    // ⚡ lit LEDs glow, a sounding buzzer shows 🔊, a part with a problem gets a ring
    if (ov) D.parts.forEach((pt) => {
      const pl = lay.P2.get(pt.ref), g = ov.glow[pt.ref], sev = ov.sev[pt.ref];
      if (!pl) return;
      if (g > 0.01) {
        const c = pl.tr(0, 0), rr = Math.max(10, 26 * s), col = GLOW[ov.col[pt.ref]] || GLOW.red;
        const gr = ctx.createRadialGradient(X(c[0]), Y(c[1]), 0, X(c[0]), Y(c[1]), rr);
        gr.addColorStop(0, 'rgba(' + col + ',' + (0.25 + 0.6 * g).toFixed(2) + ')'); gr.addColorStop(1, 'rgba(' + col + ',0)');
        ctx.fillStyle = gr; ctx.beginPath(); ctx.arc(X(c[0]), Y(c[1]), rr, 0, Math.PI * 2); ctx.fill();
      }
      if (ov.p[pt.ref] && ov.p[pt.ref].kind === 'buzzer' && ov.p[pt.ref].on) { ctx.font = Math.max(12, 16 * s) + 'px ' + FONT; ctx.textAlign = 'left'; ctx.textBaseline = 'bottom'; ctx.fillText('🔊', X(pl.box[2]) + 2, Y(pl.box[1]) + 4); }
      if (sev === 'err' || sev === 'warn') {
        ctx.strokeStyle = sev === 'err' ? '#ff6b6b' : '#ffc861'; ctx.lineWidth = 2; ctx.setLineDash([5, 4]);
        // (around the symbol and its name, so the ring never runs through the name)
        const nb = (lay.names.get(pt.ref) || {}).box, b = nb ? [Math.min(pl.box[0], nb[0]), Math.min(pl.box[1], nb[1]), Math.max(pl.box[2], nb[2]), Math.max(pl.box[3], nb[3])] : pl.box;
        ctx.strokeRect(X(b[0] - 6), Y(b[1] - 6), (b[2] - b[0] + 12) * s, (b[3] - b[1] + 12) * s); ctx.setLineDash([]);
      }
    });
    // symbols
    D.parts.forEach((pt) => {
      const pl = lay.P2.get(pt.ref), on = sv.sel === pt.ref;
      ctx.strokeStyle = on ? '#74e4ff' : '#e6f3f8'; ctx.fillStyle = on ? '#74e4ff' : '#e6f3f8'; ctx.lineWidth = Math.max(1, (on ? 2.2 : 1.6) * s);
      pl.sy.S.forEach((sh) => {
        ctx.beginPath();
        if (sh.l) for (let i = 0; i < sh.l.length; i += 2) { const q = pl.tr(sh.l[i], sh.l[i + 1]); if (i) ctx.lineTo(X(q[0]), Y(q[1])); else ctx.moveTo(X(q[0]), Y(q[1])); }
        else if (sh.poly) { for (let i = 0; i < sh.poly.length; i += 2) { const q = pl.tr(sh.poly[i], sh.poly[i + 1]); if (i) ctx.lineTo(X(q[0]), Y(q[1])); else ctx.moveTo(X(q[0]), Y(q[1])); } ctx.closePath(); ctx.fill(); }
        else if (sh.r) { const a = pl.tr(sh.r[0], sh.r[1]), b = pl.tr(sh.r[2], sh.r[3]); ctx.rect(X(Math.min(a[0], b[0])), Y(Math.min(a[1], b[1])), Math.abs(b[0] - a[0]) * s, Math.abs(b[1] - a[1]) * s); }
        else if (sh.c) { const q = pl.tr(sh.c[0], sh.c[1]); ctx.arc(X(q[0]), Y(q[1]), sh.c[2] * s, 0, Math.PI * 2); }
        else if (sh.a) { const q = pl.tr(sh.a[0], sh.a[1]), ro = pl.r * Math.PI / 180; ctx.arc(X(q[0]), Y(q[1]), sh.a[2] * s, sh.a[3] + ro, sh.a[4] + ro); }
        ctx.stroke();
      });
      ctx.font = Math.max(7, 8.5 * s) + 'px ' + FONT; ctx.textBaseline = 'middle';
      pl.sy.T.forEach((t) => {
        const q = pl.tr(t.x, t.y);
        let a = t.a;
        if (pl.r === 180 && a !== 'center') a = a === 'left' ? 'right' : 'left';
        ctx.textAlign = pl.r === 90 || pl.r === 270 ? 'center' : a;
        ctx.fillStyle = on ? '#74e4ff' : '#b9d3de';
        ctx.fillText(t.t, X(q[0]), Y(q[1]));
      });
      // name and value: beside the symbol where no wire runs
      const at = lay.names.get(pt.ref);
      ctx.fillStyle = on ? '#74e4ff' : '#e6f3f8'; ctx.font = '600 ' + Math.max(9, 11 * s) + 'px ' + FONT;
      ctx.textAlign = at.a; ctx.textBaseline = 'middle';
      ctx.fillText(pt.ref, X(at.x), Y(at.y - (sv.vals && pt.val ? 6 : 0)));
      if (sv.vals && pt.val) { ctx.font = Math.max(8, 9.5 * s) + 'px ' + FONT; ctx.fillStyle = on ? '#74e4ff' : '#88a5b3'; ctx.fillText(pt.val, X(at.x), Y(at.y + 6)); }
    });
  }
  const GLOW = { red: '255,70,70', green: '80,255,140', blue: '90,160,255', yellow: '255,225,70', orange: '255,160,60', white: '255,255,255', ir: '190,110,255' };
  /** 0 V grey-blue, then green → amber → red as the volts go up (to the highest supply on the board). */
  function voltColour(v, vmax) {
    if (typeof v !== 'number' || !isFinite(v)) return '#6be3a4';
    if (Math.abs(v) < 0.05) return '#58748a';
    const f = Math.max(0, Math.min(1, v / (vmax || 12)));
    const mix = (a, b, t) => a.map((x, i) => Math.round(x + (b[i] - x) * t));
    const c = f < 0.5 ? mix([107, 227, 164], [255, 200, 97], f / 0.5) : mix([255, 200, 97], [255, 107, 107], (f - 0.5) / 0.5);
    return 'rgb(' + c.join(',') + ')';
  }
  gestures($('schcv'), sv, {
    redraw: () => drawSch(),
    tap(x, y) {
      if (!D) return;
      const lay = schLayout(), w = toWorld(sv, x, y);
      let hit = null;
      lay.P2.forEach((pl, ref) => { const b = pl.box; if (w[0] >= b[0] - 8 && w[0] <= b[2] + 8 && w[1] >= b[1] - 8 && w[1] <= b[3] + 8) { const a = (b[2] - b[0]) * (b[3] - b[1]); if (!hit || a < hit.a) hit = { ref, a }; } });
      sv.sel = hit ? hit.ref : null;
      drawSch();
      if (hit) partSheet(D.parts.find((p) => p.ref === hit.ref));
    }
  });

  /** Modules that plug into a header (what the AI named): buy them first and check their pins against the board. */
  function moduleLines() {
    const mods = D ? D.parts.filter((p) => ((p.lib === 'fhdr' || p.lib === 'hdr') && p.note) || (P.LIB[p.lib] && P.LIB[p.lib].mod)) : [];
    if (!mods.length) return [];
    return ['👉 🔌 మాడ్యూల్స్ ముందు కొని, వాటి మీది పిన్ పేర్లు, వాటి వరుస, అవి కోరే వోల్టేజ్ బోర్డ్ మీది పేర్లతో సరిచూడండి (తేడా ఉంటే PCB ఆర్డర్ ముందే ✏️ మార్పు):']
      .concat(mods.map((p) => '🔌 ' + p.ref + ' (' + Object.keys(P.pinsOf(p)).map((k) => P.pinsOf(p)[k]).join(' · ') + '): ' + (p.note || P.LIB[p.lib].te) +
        (P.LIB[p.lib] && P.LIB[p.lib].vars && !p.pins ? ' (వేరే వరుస అయితే ఆ భాగం షీట్‌లో 🔁)' : '')));
  }
  /** What a part is, in simple Telugu: what it does, what it does on this board, how to put it in, its pins. */
  function partSheet(pt) {
    if (!pt) return;
    const lib = P.LIB[pt.lib] || {}, f = P.fpOf(pt), names = P.pinsOf(pt);
    sheet((s) => {
      s.appendChild(el('h2', null, pt.ref + ' · ' + (lib.te || pt.lib) + (pt.val ? ' · ' + pt.val : '')));
      if (pt.lib === 'res') {
        const bs = P.bands(pt.val);
        if (bs) {
          const row = el('div', 'bands'), body = el('div', 'body');
          bs.forEach((b) => { const i = el('i'); i.style.background = b.c; body.appendChild(i); });
          row.appendChild(body);
          s.appendChild(row);
          s.appendChild(el('div', 'muted', 'రంగు గీతలు: ' + bs.map((b) => b.te).join(', ') + '. బంగారం గీత కుడి వైపు ఉండేలా పట్టుకుని ఎడమ నుంచి చదవండి.'));
        }
      }
      if (lib.what) s.appendChild(el('div', 'step', '🔎 ' + lib.what));
      if (pt.note) s.appendChild(el('div', 'step', '📌 ఈ బోర్డ్‌లో: ' + pt.note));
      const sm = P.isSmd(pt) ? P.smdOf(pt) : null;
      if (sm) s.appendChild(el('div', 'step okc', '🏭 SMD భాగం: ' + sm.te + (sm.mpn ? ' · ' + sm.mpn : '') + (sm.lcsc ? ' · LCSC ' + sm.lcsc : '') + '. ఫ్యాక్టరీ టంకం చేస్తుంది. ' + (sm.how || '')));
      else if (lib.how) s.appendChild(el('div', 'step', '🔧 ' + lib.how));
      if (lib.mod) s.appendChild(el('div', 'step', modLine(lib)));
      if (f && f.verify) s.appendChild(el('div', 'step warnc', '📏 కొనే ముందు / ఆర్డర్ ముందు: ' + f.verify + '.'));
      s.appendChild(el('h3', null, 'పిన్నులు'));
      const tb = el('table');
      const hr = el('tr'); ['ప్యాడ్', 'పేరు', 'కలిసేది'].forEach((t) => hr.appendChild(el('th', null, t))); tb.appendChild(hr);
      Object.keys(names).forEach((k) => {
        const net = D.nets.find((n) => n.p.indexOf(pt.ref + '.' + k) >= 0);
        const tr = el('tr');
        tr.appendChild(el('td', null, k)); tr.appendChild(el('td', null, names[k])); tr.appendChild(el('td', null, net ? net.n : '—'));
        tb.appendChild(tr);
      });
      s.appendChild(tb);
      if (lib.vars && lib.vars.length > 1 && !pt.pins) varPicker(s, pt);
      if (f) s.appendChild(el('div', 'muted', 'బోర్డ్ మీద ఆకారం (ఫుట్‌ప్రింట్): ' + f.name));
      const r = el('div', 'row');
      r.appendChild(btn('🧩 PCB లో చూపించు', '', () => { pv.sel = pt.ref; showTab('pcb'); }));
      if (lib.mod && window.PcbBuy) r.appendChild(btn('🛒 ' + shopName(), '', () => post({ t: 'shopsearch', shop, q: window.PcbBuy.query(pt) })));
      if (lib.mod || /^(fhdr|hdr|buck|pam8403|tp4056)$/.test(pt.lib)) r.appendChild(btn('📷 పిన్ చెక్ (AI)', '', () => pinStart(pt)));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }

  // ================================================================ 👨‍🔧 a senior engineer's review (27 Nov, list 4): his studio AI, cost first, on tap

  /** What the reviewer gets: the board, the parts with their pin names, the joins, the phone's checks and ⚡ results. */
  function forReview() {
    let simItems = [];
    try { const S = SIM.setup(D, { src: sim.src, ctl: sim.ctl }); if (!S.err) { const R = SIM.dc(S); simItems = SIM.report(S, R, null).items.filter((i) => i.sev !== 'ok').slice(0, 20).map((i) => i.sev + ': ' + i.te); } } catch (e) { /* (the review goes without ⚡) */ }
    return JSON.stringify({
      board: { w: D.board.w, h: D.board.h, copper_oz: D.board.cu || 1, layers: 2 },
      parts: D.parts.map((p) => { const L = P.LIB[p.lib] || {}, nm = P.pinsOf(p) || {}; return { ref: p.ref, kind: L.te ? p.lib + ' (' + L.te + ')' : p.lib, val: p.val || '', note: p.note || '', pins: nm }; }),
      nets: D.nets.map((n) => ({ n: n.n, p: n.p, a: n.a || undefined })),
      phone_checks: (check ? check.list : []).filter((f) => f.sev !== 'info').slice(0, 20).map((f) => f.sev + ': ' + f.te),
      phone_sim: simItems
    });
  }
  function reviewCard(h) {
    if (!D) return;
    const c = el('div', 'card idea');
    c.id = 'reviewcard';
    c.appendChild(el('div', 't', '👨‍🔧 AI సీనియర్ ఇంజనీర్ రివ్యూ'));
    const rv = ai.review && ai.review.id === D.id ? ai.review : null;
    if (rv) {
      c.appendChild(el('div', 'step', '⭐ ' + rv.score + ' / 10 · ' + rv.items.length + ' సూచనలు · ' + rv.cost));
      c.appendChild(btn('📋 చివరి రివ్యూ చూడు', 'small', () => reviewShow(rv)));
    }
    c.appendChild(el('div', 'muted', 'ఆర్డర్ ముందు అనుభవం ఉన్న ఇంజనీర్ లాగా మీ AI మొత్తం బోర్డ్ చూస్తుంది: తప్పు పిన్లు, 5V → 3.3V, కెపాసిటర్లు, పుల్-అప్‌లు, వేడి, రక్షణ… భాగాలు, కలుపులు, ఫోన్ చెక్‌లు, ⚡ ఫలితాలు వెళ్తాయి (ఫోటోలు, మీ పేరు కాదు).'));
    const q = el('div');
    c.appendChild(q);
    const r = el('div', 'row');
    const design = forReview();
    const go = btn('👨‍🔧 రివ్యూ చేయి', 'main', () => {
      if (ai.job || ai.wait) { toast('ఒక AI పని ఇంకా నడుస్తోంది'); return; }
      ai.pending = { kind: 'review', id: D.id };
      post({ t: 'aiask', kind: 'review', words: '', design: forReview(), voice: false });
    });
    r.appendChild(go);
    c.appendChild(r);
    h.appendChild(c);
    quote('review', '', design.length, (m) => {
      quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'review', words: '', chars: design.length }));
      go.disabled = !m.ok || !!ai.job || !!ai.wait;
      go.textContent = m.ok && m.money ? '👨‍🔧 రివ్యూ చేయి · ' + m.money : '👨‍🔧 రివ్యూ చేయి';
    });
  }
  function reviewDone(m, a) {
    const items = (Array.isArray(a.items) ? a.items : []).filter((x) => x && typeof x === 'object').slice(0, 12).map((x) => ({
      sev: ['err', 'warn', 'tip'].indexOf(x.sev) >= 0 ? x.sev : 'tip', ref: String(x.ref || '').toUpperCase().replace(/[^A-Z0-9,]/g, '').slice(0, 16),
      te: String(x.te || '').slice(0, 240), fix: String(x.fix || '').slice(0, 240), ask: String(x.ask || '').replace(/[\u0000-\u001f]/g, ' ').slice(0, 200) }));
    const score = Math.max(0, Math.min(10, Math.round(+a.score || 0)));
    const rv = { id: D ? D.id : null, score, say: String(a.say || '').slice(0, 500), items, cost: '🤖 ' + (m.model || 'AI') + ' · 💰 ' + m.cost };
    ai.review = rv;
    reviewShow(rv);
    if (tab === 'drc') drcList();
  }
  function reviewShow(rv) {
    sheet((s) => {
      s.appendChild(el('h2', null, '👨‍🔧 రివ్యూ: ⭐ ' + rv.score + ' / 10'));
      const bar = el('div'); bar.style.cssText = 'height:8px;border-radius:4px;background:var(--line);margin:2px 0 8px;overflow:hidden';
      const f = el('div'); f.style.cssText = 'height:100%;width:' + rv.score * 10 + '%;background:' + (rv.score >= 8 ? '#43a047' : rv.score >= 5 ? '#fb8c00' : '#e53935');
      bar.appendChild(f); s.appendChild(bar);
      if (rv.say) s.appendChild(el('div', 'step', '🤖 ' + rv.say));
      if (!rv.items.length) s.appendChild(el('div', 'step okc', '✅ పెద్ద తప్పులు ఏవీ కనిపించలేదు.'));
      const icon = { err: '❌', warn: '⚠️', tip: '💡' };
      rv.items.forEach((x) => {
        const d = el('div', 'step' + (x.sev === 'err' ? ' warnc' : ''));
        d.appendChild(el('div', null, icon[x.sev] + ' ' + (x.ref ? x.ref + ': ' : '') + x.te));
        if (x.fix) d.appendChild(el('div', 'muted', '🔧 ' + x.fix));
        const rr = el('div', 'row');
        if (x.ref && D && D.parts.some((q) => q.ref === x.ref.split(',')[0])) rr.appendChild(btn('🧩 చూపించు', 'small', () => { pv.sel = x.ref.split(',')[0]; closeSheet(); showTab('pcb'); }));
        if (x.ask || x.fix) rr.appendChild(btn('✏️ మార్పులో చెప్పు', 'small', () => editSheet(x.ask || x.fix)));
        d.appendChild(rr);
        s.appendChild(d);
      });
      s.appendChild(el('div', 'muted', '💡 AI కూడా తప్పు చేయొచ్చు: మార్పు చేసే ముందు ఒక్కోటి 📐 స్కీమాటిక్‌లో చూడండి. ' + rv.cost));
      const r = el('div', 'row');
      r.appendChild(btn('📋 పంపు', '', () => post({ t: 'sharetext', text: ['👨‍🔧 ' + (D ? D.name : '') + ' రివ్యూ: ' + rv.score + '/10', rv.say].concat(rv.items.map((x) => icon[x.sev] + ' ' + (x.ref ? x.ref + ': ' : '') + x.te + (x.fix ? ' → ' + x.fix : ''))).concat(['(Jarvis PCB స్టూడియో)']).join('\n') })));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }

  // ================================================================ 📷 an old board → its circuit (27 Nov, list 8): his studio AI reads the photos

  const scanSt = { top: null, bottom: null, words: '' };
  function scanCard(h) {
    const c = el('div', 'card tap');
    c.appendChild(el('div', null, '📷 పాత బోర్డ్ ఫోటో → స్కీమాటిక్ (AI)'));
    c.appendChild(el('div', 'muted', 'ఇంట్లో ఉన్న పాత / పాడైన చిన్న బోర్డ్ (బ్యాటరీ / అడాప్టర్ వాటిది) ఫోటో తీస్తే, దాని భాగాలు, కలుపులు చదివి స్టూడియోలో కొత్త బోర్డ్‌గా గీస్తుంది: రిపేర్‌కి, నేర్చుకోవడానికి, మళ్ళీ చేయించడానికి.'));
    c.addEventListener('click', scanSheet);
    h.appendChild(c);
  }
  function scanSheet() {
    sheet((s) => {
      s.appendChild(el('h2', null, '📷 పాత బోర్డ్ → స్కీమాటిక్'));
      s.appendChild(el('div', 'muted', 'దగ్గరగా, మంచి వెలుతురులో, బోర్డ్ మొత్తం కనిపించేలా. భాగాల మీది అక్షరాలు (R1, 10k, 7805…) చదివేలా. రెండు వైపులూ ఉంటే బాగా అర్థమవుతుంది. ఫోటోలు మీరు నొక్కాక ఒక్క బొమ్మగా కలిపి మీ AI కి ఒక్కసారే వెళ్తాయి; ఎక్కడా దాచను.'));
      s.appendChild(el('div', 'step warnc', '⚠️ 230 V ఇంటి కరెంట్ బోర్డ్‌లు (ట్రాన్స్‌ఫార్మర్, పెద్ద 400V కెపాసిటర్లు ఉన్నవి) చేయను. బోర్డ్ కరెంట్‌కి కలపకుండానే ఫోటో తీయండి.'));
      [['top', '1️⃣ పై వైపు (భాగాలు ఉన్న వైపు)'], ['bottom', '2️⃣ కింది వైపు (దారులు, టంకాలు): ఐచ్ఛికం']].forEach(([side, te]) => {
        s.appendChild(el('h3', null, te));
        if (scanSt[side]) {
          const im = el('img'); im.src = scanSt[side]; im.alt = ''; im.style.cssText = 'width:100%;max-height:200px;object-fit:contain;border-radius:10px;background:#000';
          s.appendChild(im);
        }
        const r = el('div', 'row');
        r.appendChild(btn(scanSt[side] ? '📷 మళ్ళీ తీయి' : '📷 కెమెరా', scanSt[side] ? 'small' : 'small main', () => { closeSheet(); post({ t: 'photo', from: 'cam', for: 'scan', side }); }));
        r.appendChild(btn('🖼️ గ్యాలరీ', 'small', () => { closeSheet(); post({ t: 'photo', from: 'gal', for: 'scan', side }); }));
        if (scanSt[side]) r.appendChild(btn('✕', 'small', () => { scanSt[side] = null; post({ t: 'photodrop', side }); scanSheet(); }));
        s.appendChild(r);
      });
      const ta = el('textarea');
      ta.rows = 2; ta.maxLength = 300; ta.value = scanSt.words; ta.placeholder = '(ఐచ్ఛికం) ఇది ఏ బోర్డ్? ఉదా: పాత 12V LED లైట్ డ్రైవర్';
      ta.addEventListener('input', () => { scanSt.words = ta.value; });
      s.appendChild(ta);
      const q = el('div');
      s.appendChild(q);
      const r2 = el('div', 'row');
      const go = btn('🔎 చదివి బోర్డ్ చేయి', 'main', () => {
        if (!scanSt.top && !scanSt.bottom) { toast('ముందు ఫోటో తీయండి'); return; }
        const no = scanSt.words.trim() ? A.refuseWords(scanSt.words) : null;
        if (no) { resultSheet('🚫 చేయలేదు', [no]); return; }
        if (ai.job || ai.wait) { toast('ఒక AI పని ఇంకా నడుస్తోంది'); return; }
        ai.pending = { kind: 'scan', words: scanSt.words.trim(), voice: false };
        closeSheet();
        post({ t: 'aiask', kind: 'scan', words: scanSt.words.trim(), voice: false });
      });
      go.disabled = !scanSt.top && !scanSt.bottom;
      r2.appendChild(go);
      r2.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r2);
      quote('scan', scanSt.words, 0, (m) => {
        quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'scan', words: scanSt.words, chars: 0 }));
        go.disabled = !m.ok || !!ai.job || !!ai.wait || (!scanSt.top && !scanSt.bottom);
        go.textContent = m.ok && m.money ? '🔎 చదివి బోర్డ్ చేయి · ' + m.money : '🔎 చదివి బోర్డ్ చేయి';
      });
    });
  }

  // ================================================================ 🔬 advanced ⚡ (27 Nov, "🧠 బోర్డ్‌కి మెదడు" list 3): sound, part heat, copper heat; ₹0

  const adv = { id: null, tab: 'heat', inp: null, outs: null };
  function advRun() {
    const S = SIM.setup(D, { src: sim.src, ctl: sim.ctl });
    if (S.err) return { err: S.err };
    const R = SIM.dc(S), cy = SIM.has555(S) ? SIM.cycle(SIM.setup(D, { src: sim.src, ctl: sim.ctl })) : null;
    return { S, R, cy };
  }
  function advSheet(tabName) {
    if (!D) return;
    if (adv.id !== D.id) { adv.id = D.id; adv.inp = null; adv.outs = null; }
    if (tabName) adv.tab = tabName;
    let run = null;
    try { run = advRun(); } catch (e) { run = { err: String((e && e.message) || e).slice(0, 80) }; }
    sheet((s) => {
      s.appendChild(el('h2', null, '🔬 లోతుగా చూడు'));
      const sg = el('div', 'seg');
      [['heat', '🌡️ భాగాల వేడి'], ['copper', '🔥 దారుల వేడి'], ['sound', '🎵 శబ్దం (ఫ్రీక్వెన్సీ)']].forEach(([k, t]) => sg.appendChild(btn(t, adv.tab === k ? 'on small' : 'small', () => advSheet(k))));
      s.appendChild(sg);
      if (run.err) { s.appendChild(el('div', 'step warnc', '⚡ ' + run.err)); s.appendChild(btn('మూసేయి', '', closeSheet)); return; }
      if (adv.tab === 'heat') advHeat(s, run);
      else if (adv.tab === 'copper') advCopper(s, run);
      else advSound(s, run);
      s.appendChild(el('div', 'muted', 'ఇది మా లెక్క (ఒక మోడల్): గది 35 °C అనుకుని, డేటాషీట్ల సుమారు అంకెలతో. నిజమైన బోర్డ్ కొంచెం తేడా ఉండొచ్చు. ₹0, AI లేదు.'));
      const r = el('div', 'row');
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  function heatBar(t, max) {
    const b = el('div'); b.style.cssText = 'height:8px;border-radius:4px;background:var(--line);margin:4px 0 2px;overflow:hidden';
    const f = el('div'), k = Math.max(0, Math.min(1, (t - 25) / (Math.max(max, 100) - 25)));
    f.style.cssText = 'height:100%;width:' + Math.round(k * 100) + '%;background:' + (t > max ? '#e53935' : t > 60 ? '#fb8c00' : '#43a047');
    b.appendChild(f);
    return b;
  }
  function advHeat(s, run) {
    const h = SIM.heat(run.S, run.R, run.cy);
    s.appendChild(el('div', 'muted', 'ప్రతి భాగం ఎన్ని వాట్లు వేడిగా మారుస్తుందో, దాని వల్ల ఎంత వేడెక్కుతుందో (సుమారు). 60 °C పైన చేత్తో పట్టుకోలేం.'));
    if (!h.parts.length) { s.appendChild(el('div', 'step okc', '✅ ఏ భాగం గట్టిగా వేడెక్కదు (అన్నీ 2 mW లోపే).')); return; }
    h.parts.forEach((p) => {
      const d = el('div', 'step' + (p.sev === 'err' ? ' warnc' : ''));
      d.appendChild(el('div', null, (p.sev === 'err' ? '❌ ' : p.sev === 'warn' ? '⚠️ ' : '✅ ') + p.ref + ' ' + p.te + ': ' + SIM.fmtW(p.w) + ' → సుమారు ' + Math.round(p.t) + ' °C'));
      d.appendChild(heatBar(p.t, p.max));
      let tip = '';
      const lib = (D.parts.find((q) => q.ref === p.ref) || {}).lib;
      if (p.sev !== 'ok' && /^(r7805|r3v3|nmos)$/.test(lib)) tip = '🧊 చిన్న హీట్‌సింక్ (TO-220, స్క్రూతో) పెడితే సుమారు ' + Math.round(h.air + p.w * 25) + ' °C' + (lib === 'r7805' && p.w > 1 ? '. ఇంకా తగ్గాలంటే 7805 బదులు బక్ మాడ్యూల్.' : '.');
      else if (p.sev !== 'ok' && lib === 'res') tip = '½ W లేదా 1 W రెసిస్టర్ పెట్టండి (అదే విలువ), లేదా విలువ మార్చండి.';
      else if (p.sev !== 'ok' && /^d/.test(lib)) tip = 'డయోడ్ చుట్టూ రాగి ఎక్కువ ఉంటే చల్లగా ఉంటుంది; పెద్ద కరెంట్ అయితే 1N5822.';
      if (p.sev === 'err') tip = '❌ ' + p.max + ' °C దాటుతుంది: పాడవుతుంది. ' + tip;
      if (tip) d.appendChild(el('div', 'muted', tip));
      s.appendChild(d);
    });
  }
  function advCopper(s, run) {
    const h = SIM.heat(run.S, run.R, run.cy);
    s.appendChild(el('div', 'muted', 'ప్రతి దారిలో వెళ్ళే కరెంట్, దాని వెడల్పు (' + h.oz + ' oz రాగి), దాని వల్ల రాగి ఎంత వేడెక్కుతుందో (IPC-2221 లెక్క). 10 °C లోపు ✅.'));
    if (!h.nets.length) { s.appendChild(el('div', 'step okc', '✅ ఏ దారిలోనూ 20 mA కంటే ఎక్కువ కరెంట్ వెళ్ళదు.')); return; }
    h.nets.forEach((n) => {
      const d = el('div', 'step' + (n.sev === 'err' ? ' warnc' : ''));
      const mark = n.sev === 'err' ? '❌ ' : n.sev === 'warn' ? '⚠️ ' : n.sev === 'info' ? 'ℹ️ ' : '✅ ';
      d.appendChild(el('div', null, mark + n.n + ': ' + SIM.fmtA(n.a) + (n.w ? ' · దారి ' + n.w + ' mm · +' + (Math.round(n.dt * 10) / 10) + ' °C' : ' · దారులు ఇంకా వేయలేదు (🧩 ⚡ రూట్)') + (n.pour ? ' · GND నింపు ఉంది (ఇంకా చల్లగా)' : '')));
      if (n.sev === 'err' || n.sev === 'warn') d.appendChild(el('div', 'muted', '👉 ఈ దారి వెడల్పు పెంచమని ✏️ మార్పులో చెప్పండి ("' + n.n + ' దారి 1.5 mm చేయి"), లేదా 🧮 లెక్కలు → ట్రాక్ వెడల్పు.'));
      s.appendChild(d);
    });
  }
  function advSound(s, run) {
    const ins = SIM.acInputs(D, run.S);
    if (!ins.length) { s.appendChild(el('div', 'step', '🎵 ఈ బోర్డ్‌కి శబ్దం వచ్చే వైరు (AUX సాకెట్, మైక్, L / R / IN అని పేరున్న పిన్) లేదు. ఇది ఆడియో బోర్డ్‌లకి: శబ్దం ఏ ఫ్రీక్వెన్సీలలో తగ్గుతుందో చూపిస్తుంది.')); return; }
    if (!adv.inp || !ins.some((i) => i.net === adv.inp)) adv.inp = ins[0].net;
    const cand = D.nets.filter((n) => n.n !== adv.inp && !C.isGnd(n.n) && !C.isPower(n.n) && run.R.v[n.n] != null).map((n) => n.n);
    if (!adv.outs) {
      // first: where the sound goes on (an amp's input, an AUX out, a module's L / R), else the wires right next to the input
      const want = [];
      D.parts.forEach((pt) => { const nm = P.pinsOf(pt) || {}; Object.keys(nm).forEach((k) => { if (/^(L|R|T|IN|DAC_L|DAC_R|LIN|RIN|DIN)$/i.test(nm[k])) { const n = D.nets.find((q) => q.p.indexOf(pt.ref + '.' + k) >= 0); if (n && cand.indexOf(n.n) >= 0 && want.indexOf(n.n) < 0) want.push(n.n); } }); });
      adv.outs = (want.length ? want : cand).slice(0, 2);
    }
    s.appendChild(el('div', 'muted', 'శబ్దం ఎక్కడి నుంచి వస్తుంది:'));
    const si = el('div', 'seg');
    ins.forEach((i) => si.appendChild(btn(i.te, i.net === adv.inp ? 'on small' : 'small', () => { adv.inp = i.net; adv.outs = null; advSheet(); })));
    s.appendChild(si);
    s.appendChild(el('div', 'muted', 'ఎక్కడ చూడాలి (3 వరకు):'));
    const so = el('div', 'seg');
    cand.forEach((n) => { const on = adv.outs.indexOf(n) >= 0; so.appendChild(btn((on ? '✓ ' : '') + n, on ? 'on small' : 'small', () => { adv.outs = on ? adv.outs.filter((q) => q !== n) : adv.outs.concat([n]).slice(-3); advSheet(); })); });
    s.appendChild(so);
    if (!adv.outs.length) return;
    const r = SIM.ac(run.S, run.R, adv.inp, adv.outs, 61);
    if (r.err) { s.appendChild(el('div', 'step warnc', r.err)); return; }
    const cv = el('canvas');
    cv.style.cssText = 'width:100%;height:220px;display:block;margin:8px 0;border-radius:10px;background:#0b1018;touch-action:none';
    s.appendChild(cv);
    const read = el('div', 'step');
    s.appendChild(read);
    adv.outs.forEach((n, j) => {
      const c = r.cut[j], d = el('div', 'step');
      const sw = el('span'); sw.style.cssText = 'display:inline-block;width:18px;height:3px;margin-right:6px;vertical-align:middle;background:' + GCOL[j % GCOL.length]; d.appendChild(sw);
      if (!c || c.top < -100) { d.appendChild(document.createTextNode(n + ': ఇక్కడికి శబ్దం రాదు.')); s.appendChild(d); return; }
      const lvl = c.top > -1 ? 'పూర్తిగా' : c.top > -6.5 ? 'కొంచెం తగ్గి (' + Math.round(c.top) + ' dB)' : 'బాగా తగ్గి (' + Math.round(c.top) + ' dB' + (c.top < -6 ? ', సగం కంటే తక్కువ' : '') + ')';
      const lo = c.lo && c.lo > 20 ? Math.round(c.lo) + ' Hz కంటే కింది శబ్దాలు (బేస్, దడ దడ) తగ్గుతాయి' : '';
      const hi = c.hi && c.hi < 20000 ? (c.hi >= 1000 ? (Math.round(c.hi / 100) / 10) + ' kHz' : Math.round(c.hi) + ' Hz') + ' పైన శబ్దాలు (సన్నని, హై) తగ్గుతాయి' : '';
      d.appendChild(document.createTextNode(n + ': శబ్దం ' + lvl + ' వస్తుంది. ' + (lo || hi ? [lo, hi].filter(Boolean).join('; ') + '.' : '✅ వినిపించే 20 Hz – 20 kHz అంతా సమానంగా.')));
      s.appendChild(d);
    });
    requestAnimationFrame(() => drawFreq(cv, read, r, adv.outs));
  }
  function drawFreq(cv, read, r, outs) {
    const dpr = window.devicePixelRatio || 1, W = cv.clientWidth || 320, H = cv.clientHeight || 220;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    const g = cv.getContext('2d'), L = 34, Rm = 10, T = 10, Bm = 24, pw = W - L - Rm, ph = H - T - Bm;
    const lx = (f) => L + (Math.log10(f) - 1) / 4 * pw, Y = (db) => T + (6 - Math.max(-40, Math.min(6, db))) / 46 * ph;
    let at = -1;
    function paint() {
      g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H);
      g.fillStyle = 'rgba(143,163,184,0.10)'; g.fillRect(L, T, lx(20) - L, ph); g.fillRect(lx(20000), T, L + pw - lx(20000), ph);
      g.strokeStyle = '#263241'; g.lineWidth = 1; g.font = '10px system-ui'; g.fillStyle = '#8fa3b8';
      [6, 0, -10, -20, -30, -40].forEach((db) => { const y = Y(db); g.beginPath(); g.moveTo(L, y); g.lineTo(L + pw, y); g.stroke(); g.textAlign = 'right'; g.fillText(db + '', L - 4, y + 3); });
      [10, 100, 1000, 10000, 100000].forEach((f) => { const x = lx(f); g.beginPath(); g.moveTo(x, T); g.lineTo(x, T + ph); g.stroke(); g.textAlign = f === 100000 ? 'right' : 'center'; g.fillText(f >= 1000 ? f / 1000 + 'k Hz'.slice(0, f === 100000 ? 4 : 1) : f + '', f === 100000 ? x + 4 : x, H - 8); });
      g.textAlign = 'left'; g.fillText('dB', 4, T + 8);
      outs.forEach((n, j) => { g.strokeStyle = GCOL[j % GCOL.length]; g.lineWidth = 2; g.beginPath(); let pen = false;
        r.ys[j].forEach((y, i) => { if (y == null) { pen = false; return; } const px = lx(r.fs[i]), py = Y(y); if (pen) g.lineTo(px, py); else { g.moveTo(px, py); pen = true; } }); g.stroke(); });
      if (at >= 0) { g.strokeStyle = '#fff'; g.beginPath(); g.moveTo(lx(r.fs[at]), T); g.lineTo(lx(r.fs[at]), T + ph); g.stroke(); }
    }
    const show = (i) => { at = i; const f = r.fs[i]; read.textContent = '👉 ' + (f >= 1000 ? (Math.round(f / 100) / 10) + ' kHz' : Math.round(f) + ' Hz') + ': ' + outs.map((n, j) => n + ' ' + (r.ys[j][i] == null ? '—' : (Math.round(r.ys[j][i] * 10) / 10) + ' dB')).join(' · ') + (f < 20 || f > 20000 ? ' (మనకి వినపడదు)' : ''); paint(); };
    const near = (e) => { const rc = cv.getBoundingClientRect(), lf = 1 + (e.clientX - rc.left - L) / pw * 4; let b = 0; r.fs.forEach((f, i) => { if (Math.abs(Math.log10(f) - lf) < Math.abs(Math.log10(r.fs[b]) - lf)) b = i; }); return b; };
    cv.addEventListener('pointerdown', (e) => { try { cv.setPointerCapture(e.pointerId); } catch (x) { /* */ } show(near(e)); });
    cv.addEventListener('pointermove', (e) => { if (e.buttons || e.pressure > 0) show(near(e)); });
    show(30);
  }

  // ================================================================ 📷 photo checks (26 Nov, "4." and "5."): his AI reads the photo, our code compares
  // The photo goes to his studio AI only when he taps (the cost shown first), once; nothing keeps it (only its small picture stays on
  // this screen until he leaves). The pin names it reads are compared with the board by our own code (₹0).

  let ph = null; // {for: 'pins' | 'solder', ref, thumb, ans, cost}
  const PIN_SAME = [['GND', 'G', '-', '0V', 'VSS', 'GROUND', 'DC-', 'OUT-'], ['VCC', 'V', '+', 'VIN', 'VDD', 'V+', 'DC+', '5V', '+5V', 'PWR', 'VBAT', 'VBUS', 'OUT+'],
    ['3V3', '3.3V', '+3.3V', '3.3'], ['DATA', 'DAT', 'OUT', 'S', 'SIG', 'DOUT', 'D'], ['TX', 'TXD', 'TX0'], ['RX', 'RXD', 'RX0'], ['DIN', 'DI'], ['SCL', 'SCK'], ['SDA']];
  function pinNorm(n) {
    const u = String(n || '').toUpperCase().replace(/[−–]/g, '-').replace(/\s+/g, '');
    const g = PIN_SAME.find((a) => a.indexOf(u) >= 0);
    return g ? g[0] : u;
  }
  const seqSame = (a, b) => a.length === b.length && a.every((x, i) => x === '?' || b[i] === '?' || pinNorm(x) === pinNorm(b[i]));
  function pinStart(pt) {
    if (!D || !pt) return;
    ph = { for: 'pins', ref: pt.ref, thumb: null, ans: null };
    pinSheet();
  }
  function pinDesign(pt) {
    const L = P.LIB[pt.lib] || {}, nm = P.pinsOf(pt);
    return JSON.stringify({ ref: pt.ref, module: (L.mod && L.mod.te) || L.te || pt.lib, val: pt.val || '', note: pt.note || '', board_pins: Object.keys(nm).map((k) => nm[k]),
      other_orders: (L.vars || []).map((v) => Object.keys(v.pins).map((k) => v.pins[k])) });
  }
  function photoAsk(kind, design) {
    if (ai.job || ai.wait) { toast('ఒక AI పని ఇంకా నడుస్తోంది'); return; }
    ai.pending = { kind, id: D.id };
    post({ t: 'aiask', kind, words: '', design, voice: false });
  }
  function photoButtons(s, kind) {
    const r = el('div', 'row');
    r.appendChild(btn('📷 కెమెరాతో తీయి', ph.thumb ? '' : 'main', () => { closeSheet(); post({ t: 'photo', from: 'cam', for: kind }); }));
    r.appendChild(btn('🖼️ గ్యాలరీ నుంచి', '', () => { closeSheet(); post({ t: 'photo', from: 'gal', for: kind }); }));
    s.appendChild(r);
  }
  function thumbImg(s) {
    const im = el('img');
    im.src = ph.thumb; im.alt = '';
    im.style.cssText = 'width:100%;max-height:300px;object-fit:contain;border-radius:10px;background:#000;margin:6px 0';
    s.appendChild(im);
    return im;
  }
  function pinSheet() {
    const pt = D && ph && D.parts.find((q) => q.ref === ph.ref);
    if (!pt) return;
    const L = P.LIB[pt.lib] || {}, nm = P.pinsOf(pt), names = Object.keys(nm).map((k) => nm[k]);
    sheet((s) => {
      s.appendChild(el('h2', null, '📷 ' + pt.ref + ': మాడ్యూల్ పిన్ చెక్'));
      s.appendChild(el('div', 'step', '🧩 బోర్డ్ మీద వరుస: ' + names.join(' · ')));
      if (!ph.thumb) {
        s.appendChild(el('div', 'muted', 'కొన్న మాడ్యూల్ పిన్ పేర్లు (VCC, GND…) చదివేలా దగ్గరగా, వెలుతురులో, నీడ లేకుండా ఫోటో తీయండి. పేర్లు వెనక వైపు ఉంటే వెనక వైపు. ఫోటో మీరు నొక్కాక మీ AI కి ఒక్కసారే వెళ్తుంది; ఎక్కడా దాచను. పేర్లు పోల్చడం మా సొంత లెక్క (₹0).'));
        photoButtons(s, 'pins');
        s.appendChild(btn('మూసేయి', '', closeSheet));
        return;
      }
      thumbImg(s);
      const q = el('div');
      s.appendChild(q);
      const r = el('div', 'row');
      const go = btn('🔎 పిన్లు చదువు', 'main', () => photoAsk('pins', pinDesign(pt)));
      r.appendChild(go);
      r.appendChild(btn('📷 వేరే ఫోటో', '', () => { ph.thumb = null; post({ t: 'photodrop' }); pinSheet(); }));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
      const design = pinDesign(pt);
      quote('pins', '', design.length, (m) => {
        quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'pins', words: '', chars: design.length }));
        go.disabled = !m.ok || !!ai.job || !!ai.wait;
        go.textContent = m.ok && m.money ? '🔎 పిన్లు చదువు · ' + m.money : '🔎 పిన్లు చదువు';
      });
    });
  }
  /** Our own comparison of what the AI read with the board's pins (₹0), and the one-tap fixes. */
  function pinsDone(m, a) {
    const pt = D && ph && ph.for === 'pins' && D.parts.find((q) => q.ref === ph.ref);
    const cost = '🤖 ' + (m.model || 'AI') + ' · 💰 ఖర్చు ' + m.cost;
    if (!pt) { resultSheet('📷 పిన్ చెక్', [String(a.say || ''), cost]); return; }
    const L = P.LIB[pt.lib] || {}, nm = P.pinsOf(pt), pads = Object.keys(nm), board = pads.map((k) => nm[k]);
    const got = (Array.isArray(a.pins) ? a.pins : []).map((x) => String(x == null ? '?' : x).replace(/[^\w+\-.?/]/g, '').slice(0, 8) || '?').slice(0, 40);
    sheet((s) => {
      s.appendChild(el('h2', null, '📷 ' + pt.ref + ': పిన్ చెక్ ఫలితం'));
      if (ph.thumb) thumbImg(s);
      if (!got.length) { s.appendChild(el('div', 'step warnc', '❓ ఫోటోలో పిన్ పేర్లు చదవలేకపోయింది.')); if (a.say) s.appendChild(el('div', 'step', '🤖 ' + a.say)); }
      else {
        s.appendChild(el('div', 'step', '📷 మాడ్యూల్ మీద (' + (a.side === 'back' ? 'వెనక వైపు' : 'ముందు వైపు') + '): ' + got.join(' · ')));
        s.appendChild(el('div', 'step', '🧩 బోర్డ్ మీద: ' + board.join(' · ')));
        const plug = L.kind === 'plug' || pt.lib === 'fhdr', stand = P.fpOf(pt) && P.fpOf(pt).body && P.fpOf(pt).body.stand;
        const rev = got.slice().reverse();
        let fix = null;
        if (seqSame(board, got)) s.appendChild(el('div', 'step okc', '✅ వరుస సరిపోయింది: ' + (plug ? 'మాడ్యూల్ పేర్లు బోర్డ్ పేర్ల ఎదురుగా వచ్చేలా గుచ్చండి.' : 'పేర్లు చూసి ఒకే పేరు ఉన్న పిన్నులు వైర్లతో కలపండి.')));
        else if (seqSame(board, rev)) s.appendChild(el('div', 'step okc', plug && !stand ? '↔️ పేర్లు అవే, కానీ ఫోటోలో తిరగబడి ఉన్నాయి (వెనక వైపు చదివితే ఇలాగే వస్తుంది). గుచ్చేటప్పుడు VCC → VCC, GND → GND ఎదురుగా ఉన్నాయో చూడండి.'
          : '✅ పేర్లు అవే (తిరగబడి చదివాం: మాడ్యూల్ నిలబడుతుంది / వైర్లతో కలుపుతాం). పేర్లు చూసి ఎదురుగా కలపండి.'));
        else {
          // one of the library's other orders?
          const vi = (L.vars || []).findIndex((v) => { const vn = Object.keys(v.pins).map((k) => v.pins[k]); return seqSame(vn, got) || seqSame(vn, rev); });
          if (vi >= 0 && !pt.pins) {
            s.appendChild(el('div', 'step warnc', '⚠️ మీ మాడ్యూల్ వేరే రకం: ' + L.vars[vi].te + '. బోర్డ్ వరుస మార్చాలి (లేకపోతే VCC, GND తారుమారై కాలిపోవచ్చు).'));
            fix = btn('🔁 బోర్డ్‌ని ఈ వరుసకి మార్చు (₹0)', 'main', () => { snap(); const t = P.setVariant(D, pt, vi); if (t) { afterMove(t); toast('🔁 ' + pt.ref + ': ' + L.vars[vi].te + '; దారులు మళ్ళీ వేస్తున్నాను', 4000); } closeSheet(); });
          } else {
            const bs = board.map(pinNorm).sort().join(','), gs = got.map(pinNorm).sort().join(',');
            if (bs === gs && (pt.lib === 'fhdr' || pt.lib === 'hdr') && pt.pins) {
              s.appendChild(el('div', 'step warnc', '⚠️ పేర్లు అవే, కానీ వరుస వేరు. బోర్డ్ పిన్ల వరుస మార్చాలి.'));
              fix = btn('🔁 బోర్డ్ పిన్లు మాడ్యూల్ వరుసకి మార్చు (₹0)', 'main', () => { snap(); const t = reorderPins(pt, got); if (t) { afterMove(t); toast('🔁 ' + pt.ref + ': ' + got.join(' · '), 4000); } closeSheet(); });
            } else {
              const miss = board.filter((b) => !got.some((g) => pinNorm(g) === pinNorm(b))), extra = got.filter((g) => g !== '?' && !board.some((b) => pinNorm(g) === pinNorm(b)));
              s.appendChild(el('div', 'step warnc', '❌ వరుస / పేర్లు బోర్డ్‌తో సరిపోలలేదు.' + (miss.length ? ' బోర్డ్ కోరేవి మాడ్యూల్ మీద లేవు: ' + miss.join(', ') + '.' : '') + (extra.length ? ' మాడ్యూల్ మీద అదనంగా: ' + extra.join(', ') + '.' : '')));
              s.appendChild(el('div', 'step', '👉 PCB ఆర్డర్ ముందే ✏️ మార్పులో చెప్పండి (కింద బటన్ మాట రాసి పెడుతుంది; AI, ఖర్చు ముందే చూపిస్తుంది).'));
              fix = btn('✏️ మార్పులో చెప్పు', 'main', () => editSheet(pt.ref + ' మాడ్యూల్ పిన్ వరుస ఇలా ఉంది: ' + got.join(', ') + '. బోర్డ్ ఈ వరుసకి మార్చు.'));
            }
          }
        }
        if (got.indexOf('?') >= 0 || a.sure === false) s.appendChild(el('div', 'muted', '❓ కొన్ని పేర్లు స్పష్టంగా లేవు: ఇంకా దగ్గరగా / వెలుతురులో ఇంకో ఫోటో తీస్తే మంచిది.'));
        if (a.volts && L.mod) s.appendChild(el('div', 'step', '⚡ మాడ్యూల్ మీద వోల్టేజ్: ' + String(a.volts).slice(0, 20) + ' · స్టూడియో అనుకున్నది ' + L.mod.v[0] + '–' + L.mod.v[1] + ' V (⚡ నడిపించు లో బోర్డ్ ఇచ్చేది చూడండి)'));
        if (a.say) s.appendChild(el('div', 'step', '🤖 ' + a.say));
        if (fix) s.appendChild(fix);
      }
      s.appendChild(el('div', 'muted', cost));
      const r = el('div', 'row');
      r.appendChild(btn('📷 ఇంకో ఫోటో', '', () => { ph.thumb = null; pinSheet(); }));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** A header's pins renamed to his module's order (the joins move with the names, like 🔁). */
  function reorderPins(pt, got) {
    const from = P.pinsOf(pt), pads = Object.keys(from), to = {};
    pads.forEach((k, i) => { to[k] = String(got[i] || from[k]).slice(0, 8); });
    const padOf = {};
    pads.forEach((k) => { padOf[pinNorm(to[k])] = k; });
    const touched = new Set();
    D.nets.forEach((n) => { n.p = n.p.map((q) => { const [ref, k] = q.split('.'); if (ref !== pt.ref) return q; const nk = padOf[pinNorm(from[k])]; if (!nk || nk === k) return q; touched.add(n.n); return ref + '.' + nk; }); });
    pt.pins = to;
    return touched;
  }

  function solderStart() {
    if (!D) return;
    ph = { for: 'solder', ref: null, thumb: null, ans: null };
    solderSheet();
  }
  function solderDesign() {
    return JSON.stringify({ board_mm: [D.board.w, D.board.h], parts: D.parts.map((q) => ({ ref: q.ref, what: (P.LIB[q.lib] || {}).te || q.lib, x: q.x, y: q.y, rot: q.rot || 0, pins: Object.keys(P.pinsOf(q)).length })) });
  }
  const JOINT = {
    bridge: ['🌉 వంతెన', 'రెండు ప్యాడ్‌ల మధ్య సోల్డర్ అంటుకుంది: ఐరన్ మొన శుభ్రం చేసి, వంతెన మీద నుంచి బయటికి లాగండి (సోల్డర్ విక్ ఉంటే దానితో). షార్ట్ అవుతుంది, ముందు ఇదే.'],
    cold: ['🧊 చల్లని టంకం', 'మసకగా / బంతిలా ఉంది: ఐరన్‌ని ప్యాడ్, కాలు రెండిటికీ 2–3 సెకన్లు తాకించి, కొద్దిగా కొత్త సోల్డర్ పెట్టండి. మెరిసే కొండలా రావాలి.'],
    little: ['🕳️ తక్కువ సోల్డర్', 'ప్యాడ్ / రంధ్రం పూర్తిగా నిండలేదు: మళ్ళీ వేడి చేసి కొంచెం సోల్డర్ పెట్టండి.'],
    much: ['🟠 ఎక్కువ సోల్డర్', 'పెద్ద ముద్ద: పక్క ప్యాడ్‌కి తాకట్లేదని చూడండి; సోల్డర్ సకర్ / విక్‌తో కొంచెం తీయండి.'],
    missing: ['⭕ టంకం లేదు', 'ఈ ప్యాడ్‌కి టంకం మర్చిపోయారు: టంకం వేయండి.'],
    lifted: ['⚠️ ప్యాడ్ ఊడింది', 'ఎక్కువ వేడికి ప్యాడ్ బోర్డ్ నుంచి లేచింది: ఆ కాలుని సన్నని వైరుతో దాని దారికి జోడించాలి (ఇక వేడి ఎక్కువ పెట్టకండి).'],
    burnt: ['🔥 కాలిన గుర్తు', 'బోర్డ్ కాలింది: దగ్గరి భాగం పాడైందేమో చూడండి; ఐరన్ ఎక్కువ సేపు పెట్టకండి.'],
    flux: ['🟤 ఫ్లక్స్ మిగిలింది', 'గోధుమ జిగట: IPA (ఐసోప్రొపైల్ ఆల్కహాల్) + పాత టూత్‌బ్రష్‌తో తుడవండి. హానికరం కాదు.']
  };
  function solderSheet() {
    if (!D || !ph) return;
    sheet((s) => {
      s.appendChild(el('h2', null, '📷 టంకం ఫోటో చెక్'));
      if (!ph.thumb) {
        s.appendChild(el('div', 'muted', 'బోర్డ్ కింది వైపు (టంకాలు ఉన్న వైపు) పూర్తిగా కనిపించేలా, దగ్గరగా, మంచి వెలుతురులో, నీడ లేకుండా ఫోటో తీయండి. మీ AI వంతెనలు, మసక టంకాలు, మర్చిపోయిన ప్యాడ్‌లు చూపిస్తుంది. ఫోటో మీరు నొక్కాక ఒక్కసారే వెళ్తుంది; ఎక్కడా దాచను.'));
        s.appendChild(el('div', 'step', '💡 AI చెప్పేది సలహా మాత్రమే: 🔧 గైడ్‌లోని 📏 బీప్ టెస్ట్ కూడా తప్పకుండా చేయండి.'));
        photoButtons(s, 'solder');
        s.appendChild(btn('మూసేయి', '', closeSheet));
        return;
      }
      thumbImg(s);
      const q = el('div');
      s.appendChild(q);
      const r = el('div', 'row');
      const design = solderDesign();
      const go = btn('🔎 టంకాలు చూడు', 'main', () => photoAsk('solder', design));
      r.appendChild(go);
      r.appendChild(btn('📷 వేరే ఫోటో', '', () => { ph.thumb = null; post({ t: 'photodrop' }); solderSheet(); }));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
      quote('solder', '', design.length, (m) => {
        quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'solder', words: '', chars: design.length }));
        go.disabled = !m.ok || !!ai.job || !!ai.wait;
        go.textContent = m.ok && m.money ? '🔎 టంకాలు చూడు · ' + m.money : '🔎 టంకాలు చూడు';
      });
    });
  }
  function solderDone(m, a) {
    const cost = '🤖 ' + (m.model || 'AI') + ' · 💰 ఖర్చు ' + m.cost;
    const js = (Array.isArray(a.joints) ? a.joints : []).filter((j) => j && typeof j === 'object').slice(0, 12).map((j) => ({
      x: Math.max(0, Math.min(1, +j.x || 0)), y: Math.max(0, Math.min(1, +j.y || 0)), kind: JOINT[j.kind] ? j.kind : 'cold',
      near: String(j.near || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6), te: String(j.te || '').slice(0, 160) }));
    sheet((s) => {
      s.appendChild(el('h2', null, js.length ? '📷 ' + js.length + ' టంకాలు చూడాలి' : (a.ok === false ? '📷 సరిగ్గా చూడలేకపోయింది' : '📷 టంకాలు బాగున్నాయి')));
      if (ph && ph.thumb) {
        const wrap = el('div'); wrap.style.cssText = 'position:relative;margin:6px 0';
        const im = el('img'); im.src = ph.thumb; im.alt = ''; im.style.cssText = 'width:100%;display:block;border-radius:10px';
        wrap.appendChild(im);
        js.forEach((j, i) => {
          const d = el('div', null, String(i + 1));
          d.style.cssText = 'position:absolute;left:calc(' + (j.x * 100) + '% - 13px);top:calc(' + (j.y * 100) + '% - 13px);width:22px;height:22px;border:2px solid ' + (j.kind === 'bridge' || j.kind === 'lifted' ? '#ff5252' : '#ffd54f') +
            ';border-radius:50%;color:#fff;font:bold 12px system-ui;text-align:center;line-height:22px;background:rgba(0,0,0,.45)';
          wrap.appendChild(d);
        });
        s.appendChild(wrap);
      }
      if (a.say) s.appendChild(el('div', 'step', '🤖 ' + a.say));
      js.forEach((j, i) => {
        const J = JOINT[j.kind], d = el('div', 'step' + (j.kind === 'bridge' || j.kind === 'lifted' ? ' warnc' : ''));
        d.appendChild(el('div', null, (i + 1) + '. ' + J[0] + (j.near ? ' · ' + j.near + ' దగ్గర' : '') + (j.te ? ': ' + j.te : '')));
        d.appendChild(el('div', 'muted', '🔧 ' + J[1]));
        s.appendChild(d);
      });
      if (!js.length && a.ok !== false) s.appendChild(el('div', 'step okc', '✅ ఫోటోలో తప్పు టంకాలు కనిపించలేదు. ఇప్పుడు 📏 బీప్ టెస్ట్ చేసి కరెంట్ ఇవ్వండి.'));
      s.appendChild(el('div', 'muted', '💡 ఫోటో నుంచి AI చెప్పేది సలహా మాత్రమే (అది తప్పు చేయొచ్చు). సరిచేశాక 📏 బీప్ టెస్ట్, తర్వాత 🧪 తప్పు వెతుకు.'));
      s.appendChild(el('div', 'muted', cost));
      const r = el('div', 'row');
      r.appendChild(btn('📷 సరిచేశాక ఇంకో ఫోటో', '', () => { ph.thumb = null; solderSheet(); }));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }

  // ================================================================ 📈 the ⚡ graph (26 Nov, "7."): the board run again for many values, ₹0

  const GCOL = ['#4fc3f7', '#ffb74d', '#81c784', '#f06292', '#ba68c8'];
  const gr = { id: null, ax: 0, on: null, res: null };
  function graphSheet() {
    if (!D) return;
    const base = { src: sim.src, ctl: sim.ctl };
    const axes = SIM.axes(D, base), lines = SIM.lines(D, base);
    if (!axes.length || !lines.length) { toast('📈 ఈ బోర్డ్‌కి గ్రాఫ్ గీయలేను: ⚡ నడిపించు లో 🔋 సప్లై చూడండి.', 4000); return; }
    if (gr.id !== D.id) { gr.id = D.id; gr.ax = 0; gr.on = null; }
    if (gr.ax >= axes.length) gr.ax = 0;
    const ax = axes[gr.ax];
    if (!gr.on) gr.on = defaultLines(ax, lines);
    const want = gr.on.map((id) => lines.find((l) => l.k + ':' + l.id === id)).filter(Boolean);
    let res = null;
    try { res = SIM.sweep(D, base, ax, want, 41); } catch (e) { res = { err: String((e && e.message) || e).slice(0, 80) }; }
    sheet((s) => {
      s.appendChild(el('h2', null, '📈 గ్రాఫ్: ఇది మారితే బోర్డ్ ఎలా ఉంటుంది'));
      s.appendChild(el('div', 'muted', 'ఏది మారుస్తాం (కింద అడ్డ గీత):'));
      const sa = el('div', 'seg');
      axes.forEach((a, i) => sa.appendChild(btn(a.te, i === gr.ax ? 'on small' : 'small', () => { gr.ax = i; gr.on = null; graphSheet(); })));
      s.appendChild(sa);
      s.appendChild(el('div', 'muted', 'ఏవి చూపించాలి (4 వరకు):'));
      const sl = el('div', 'seg');
      lines.forEach((l) => {
        const id = l.k + ':' + l.id, on = gr.on.indexOf(id) >= 0;
        sl.appendChild(btn((on ? '✓ ' : '') + l.te + ' (' + l.unit + ')', on ? 'on small' : 'small', () => {
          if (on) gr.on = gr.on.filter((q) => q !== id); else { gr.on = gr.on.concat([id]); if (gr.on.length > 4) gr.on.shift(); }
          graphSheet();
        }));
      });
      s.appendChild(sl);
      if (!res || res.err || !res.xs || !res.xs.length) { s.appendChild(el('div', 'step warnc', '📈 గీయలేకపోయాను: ' + ((res && res.err) || 'ఫలితం లేదు'))); }
      else {
        const cv = el('canvas');
        cv.style.cssText = 'width:100%;height:240px;display:block;margin:8px 0;border-radius:10px;background:#0b1018;touch-action:none';
        s.appendChild(cv);
        const read = el('div', 'step');
        s.appendChild(read);
        const lg = el('div');
        want.forEach((l, j) => { const d = el('div', 'muted'); const sw = el('span'); sw.style.cssText = 'display:inline-block;width:18px;height:3px;margin-right:6px;vertical-align:middle;background:' + GCOL[j % GCOL.length] + (l.unit === 'mA' ? ';border-top:1px dashed #0b1018' : ''); d.appendChild(sw); d.appendChild(document.createTextNode(l.te + ' (' + l.unit + ')' + (l.unit === 'mA' ? ' · కుడి వైపు అంకెలు, చుక్కల గీత' : ''))); lg.appendChild(d); });
        s.appendChild(lg);
        // where it goes wrong: the stretches of ❌ / ⚠️ along the line
        const segs = [];
        res.bad.forEach((b, i) => { const last = segs[segs.length - 1]; if (b && last && last.b === b && last.why === res.why[i] && last.i1 === i - 1) last.i1 = i; else if (b) segs.push({ b, i0: i, i1: i, why: res.why[i] }); });
        const u = ax.unit === 's' ? ' s' : ax.unit === '%' ? '%' : ' V', fx = (x) => (ax.unit === 's' ? (Math.round(x * 1000) / 1000) : Math.round(x * 10) / 10) + u;
        segs.slice(0, 4).forEach((g) => s.appendChild(el('div', 'step ' + (g.b === 'err' ? 'warnc' : ''), (g.b === 'err' ? '❌ ' : '⚠️ ') + fx(res.xs[g.i0]) + (g.i1 > g.i0 ? ' – ' + fx(res.xs[g.i1]) : '') + ': ' + g.why)));
        if (!segs.length && ax.k !== 'time') s.appendChild(el('div', 'step', '✅ ఈ మొత్తం పరిధిలో (' + fx(ax.lo) + ' – ' + fx(ax.hi) + ') ⚡ ఏ తప్పూ చూపలేదు.'));
        if (ax.k === 'src' && (ax.kind === 'li' || ax.kind === '9v')) s.appendChild(el('div', 'muted', '🔋 బ్యాటరీ వాడుతూ ఉంటే వోల్టేజ్ తగ్గుతుంది: గ్రాఫ్‌లో కుడి నుంచి ఎడమకి జరుగుతుంది.'));
        if (ax.k === 'time') s.appendChild(el('div', 'muted', '⏱️ 555 సెకనుకి ' + (Math.round(res.f * 100) / 100) + ' సార్లు టిక్ టిక్: ఇక్కడ మూడు సార్లు చూపిస్తున్నాను.'));
        s.appendChild(el('div', 'muted', '👆 గ్రాఫ్ మీద వేలు పెట్టి జరిపితే ఆ చోటు అంకెలు. ఇది మా లెక్క (ఒక మోడల్), నిజమైన బోర్డ్ కొంచెం తేడా ఉండొచ్చు.'));
        requestAnimationFrame(() => drawGraph(cv, read, ax, want, res, fx));
      }
      const r = el('div', 'row');
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** At first: the power nets and an LED / the supply current for a supply; the knob's own wire for a knob; the 555's legs over time. */
  function defaultLines(ax, lines) {
    const pick = [];
    const add = (l) => { if (l && pick.indexOf(l.k + ':' + l.id) < 0 && pick.length < 3) pick.push(l.k + ':' + l.id); };
    if (ax.k === 'time') { ['OUT', 'THR', 'TRIG'].forEach((n) => add(lines.find((l) => l.k === 'v' && l.id === n))); add(lines.find((l) => l.k === 'i' && / LED/.test(l.te))); }
    else if (ax.k === 'pot') {
      const ref = ax.key.split(':')[1], net = D.nets.find((n) => n.p.indexOf(ref + '.2') >= 0);
      if (net) add(lines.find((l) => l.k === 'v' && l.id === net.n));
      add(lines.find((l) => l.k === 'i' && / LED/.test(l.te))); add(lines.find((l) => l.k === 'src'));
    } else {
      // (the supply itself and what the board makes from it: the lowest power wire, e.g. +5V after a 12 V input)
      const pw = lines.filter((l) => l.k === 'v' && l.power), src = pw.find((l) => l.id === ax.net) || pw[0];
      const R0 = SIM.dc(SIM.setup(D, { src: sim.src, ctl: sim.ctl }));
      const low = pw.filter((l) => l !== src && R0.v[l.id] > 0.5).sort((a, b) => R0.v[a.id] - R0.v[b.id])[0];
      add(src); add(low);
      add(lines.find((l) => l.k === 'i' && / LED/.test(l.te)) || lines.find((l) => l.k === 'src'));
    }
    if (!pick.length) add(lines[0]);
    return pick;
  }
  function drawGraph(cv, read, ax, want, res, fx) {
    const dpr = window.devicePixelRatio || 1, W = cv.clientWidth || 320, H = cv.clientHeight || 240;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    const g = cv.getContext('2d');
    const L = 38, Rm = want.some((l) => l.unit === 'mA') ? 40 : 10, T = 12, Bm = 26, pw = W - L - Rm, ph = H - T - Bm;
    const xs = res.xs, x0 = xs[0], x1 = xs[xs.length - 1];
    const range = (unit) => { let lo = 0, hi = 0; want.forEach((l, j) => { if (l.unit !== unit) return; res.ys[j].forEach((y) => { if (y != null && isFinite(y)) { lo = Math.min(lo, y); hi = Math.max(hi, y); } }); }); hi = hi > lo ? hi * 1.1 : lo + 1; return [lo, nice(hi)]; };
    const nice = (v) => { const e = Math.pow(10, Math.floor(Math.log10(v))), m = v / e; return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * e; };
    const rV = range('V'), rI = range('mA');
    const X = (x) => L + (x - x0) / ((x1 - x0) || 1) * pw, Y = (y, r) => T + ph - (y - r[0]) / ((r[1] - r[0]) || 1) * ph;
    let at = -1;
    function paint() {
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, W, H);
      // ❌ / ⚠️ stretches behind
      res.bad.forEach((b, i) => {
        if (!b) return;
        const a = i > 0 ? (X(xs[i - 1]) + X(xs[i])) / 2 : X(xs[i]), c = i < xs.length - 1 ? (X(xs[i]) + X(xs[i + 1])) / 2 : X(xs[i]);
        g.fillStyle = b === 'err' ? 'rgba(229,57,53,0.22)' : 'rgba(255,183,77,0.16)';
        g.fillRect(a, T, Math.max(1, c - a), ph);
      });
      g.strokeStyle = '#263241'; g.lineWidth = 1; g.font = '10px system-ui'; g.fillStyle = '#8fa3b8';
      for (let k = 0; k <= 4; k++) {
        const yy = T + ph * k / 4; g.beginPath(); g.moveTo(L, yy); g.lineTo(L + pw, yy); g.stroke();
        if (want.some((l) => l.unit === 'V')) { g.textAlign = 'right'; g.fillText((Math.round((rV[1] - (rV[1] - rV[0]) * k / 4) * 10) / 10) + '', L - 4, yy + 3); }
        if (Rm > 10) { g.textAlign = 'left'; g.fillText(Math.round(rI[1] - (rI[1] - rI[0]) * k / 4) + '', L + pw + 4, yy + 3); }
      }
      g.textAlign = 'center';
      for (let k = 0; k <= 4; k++) { const xv = x0 + (x1 - x0) * k / 4; g.fillText(fx(xv), X(xv), H - 8); }
      g.textAlign = 'left'; if (want.some((l) => l.unit === 'V')) g.fillText('V', 4, T + 8); if (Rm > 10) { g.textAlign = 'right'; g.fillText('mA', W - 2, T + 8); }
      // where he has it now
      if (ax.now != null && ax.k !== 'time') { g.strokeStyle = '#ffffff55'; g.setLineDash([3, 3]); g.beginPath(); g.moveTo(X(ax.now), T); g.lineTo(X(ax.now), T + ph); g.stroke(); g.setLineDash([]); }
      want.forEach((l, j) => {
        const r = l.unit === 'mA' ? rI : rV;
        g.strokeStyle = GCOL[j % GCOL.length]; g.lineWidth = 2; g.setLineDash(l.unit === 'mA' ? [5, 3] : []);
        g.beginPath(); let pen = false;
        res.ys[j].forEach((y, i) => { if (y == null || !isFinite(y)) { pen = false; return; } const px = X(xs[i]), py = Y(y, r); if (pen) g.lineTo(px, py); else { g.moveTo(px, py); pen = true; } });
        g.stroke(); g.setLineDash([]);
      });
      if (at >= 0) { g.strokeStyle = '#fff'; g.lineWidth = 1; g.beginPath(); g.moveTo(X(xs[at]), T); g.lineTo(X(xs[at]), T + ph); g.stroke(); }
    }
    const show = (i) => {
      at = i;
      read.textContent = '👉 ' + fx(xs[i]) + ': ' + want.map((l, j) => { const y = res.ys[j][i]; return l.te + ' ' + (y == null || !isFinite(y) ? '—' : (l.unit === 'mA' ? Math.round(y * 10) / 10 : Math.round(y * 100) / 100) + ' ' + l.unit); }).join(' · ') + (res.why[i] ? '  ·  ' + (res.bad[i] === 'err' ? '❌ ' : '⚠️ ') + res.why[i] : '');
      paint();
    };
    const near = (e) => { const r = cv.getBoundingClientRect(), x = x0 + (e.clientX - r.left - L) / pw * (x1 - x0); let b = 0; xs.forEach((v, i) => { if (Math.abs(v - x) < Math.abs(xs[b] - x)) b = i; }); return b; };
    cv.addEventListener('pointerdown', (e) => { try { cv.setPointerCapture(e.pointerId); } catch (x) { /* */ } show(near(e)); });
    cv.addEventListener('pointermove', (e) => { if (e.buttons || e.pressure > 0) show(near(e)); });
    let i0 = 0;
    if (ax.now != null && ax.k !== 'time') xs.forEach((v, i) => { if (Math.abs(v - ax.now) < Math.abs(xs[i0] - ax.now)) i0 = i; });
    show(ax.k === 'time' ? 0 : i0);
  }

  // ================================================================ 🧪 the multimeter fault finder (26 Nov, "3."): ₹0, our own code

  function faultStart(keep) {
    if (!D) return;
    if (!keep || !ft || ft.id !== D.id) {
      let S = null, R = null;
      try { S = SIM.setup(D, { src: sim.src, ctl: sim.ctl }); if (!S.err) R = SIM.dc(S); } catch (e) { S = null; }
      if (!S || S.err || !R) { toast('🧪 ఈ బోర్డ్‌ని ⚡ నడిపించలేకపోయాను: ' + ((S && S.err) || 'తెలియని కారణం'), 4000); return; }
      const f = MK.faultTests(D, S, R);
      if (!f.tests.length || !f.gnd) { toast('🧪 ఈ బోర్డ్‌లో కొలవాల్సిన చోట్లు దొరకలేదు (GND / సప్లై లేదు).', 4000); return; }
      ft = { id: D.id, tests: f.tests, gnd: f.gnd, i: -1, res: [] };
    }
    faultSheet();
  }
  const fv = (x) => (Math.round(x * 100) / 100) + ' V';
  function faultSheet() {
    sheet((s) => {
      const n = ft.tests.length;
      if (ft.i < 0) {
        s.appendChild(el('h2', null, '🧪 బోర్డ్ పనిచేయట్లేదా? మీటర్‌తో వెతుకుదాం'));
        s.appendChild(el('div', 'muted', '⚡ నడిపించు లెక్క ప్రకారం ప్రతి చోటు ఎంత వోల్టేజ్ చూపాలో నాకు తెలుసు. మీరు కొలిచి అంకె చెప్తే, ఎక్కడ తేడా వచ్చిందో, ఏ భాగం వల్లో చెప్తాను. ' + n + ' చోట్లు, ₹0, AI లేదు.'));
        [['ముందు 🔧 టంకం గైడ్‌లోని 🔍 చెక్ 1 (కళ్ళతో), 📏 చెక్ 2 (బీప్: + , GND మధ్య షార్ట్ లేదు) చేశారా?', 'షార్ట్ ఉంటే కరెంట్ ఇవ్వకండి'],
          ['మల్టీమీటర్ నాబ్ DC V (V⎓) మీద, 20 V రేంజ్ (ఆటో మీటర్ అయితే V⎓ చాలు)', 'నలుపు వైరు COM లో, ఎరుపు VΩ లో'],
          ['నలుపు ప్రోబ్ GND మీద పెట్టి అలాగే ఉంచండి: ' + ft.gnd.te, 'మొసలి క్లిప్ ఉంటే దానితో పట్టించండి'],
          ['ఇప్పుడు బోర్డ్‌కి కరెంట్ ఇవ్వండి', 'ఏదైనా బాగా వేడెక్కినా, వాసన వచ్చినా వెంటనే తీసేయండి']].forEach((q, i) => {
          const d = el('div', 'step');
          d.appendChild(el('div', null, '👉 అడుగు ' + (i + 1) + ': ' + q[0]));
          d.appendChild(el('div', 'muted', q[1]));
          s.appendChild(d);
        });
        s.appendChild(el('div', 'step warnc', '⚠️ ఎరుపు ప్రోబ్ మొన రెండు పిన్నులకి ఒకేసారి తాకకూడదు (షార్ట్). ఒక్క పిన్ మీదే నిలబెట్టండి.'));
        const r = el('div', 'row');
        r.appendChild(btn('▶️ మొదలుపెడదాం', 'main', () => { ft.i = 0; faultSheet(); }));
        r.appendChild(btn('📷 టంకం ఫోటో చెక్ (AI)', '', solderStart));
        r.appendChild(btn('మూసేయి', '', closeSheet));
        s.appendChild(r);
        return;
      }
      if (ft.i >= n) {
        s.appendChild(el('h2', null, '🧪 ఫలితం'));
        const bad = ft.res.filter((x) => x && !x.w.ok).length;
        s.appendChild(el('div', 'step', bad ? '❌ ' + bad + ' చోట్ల తేడా వచ్చింది. మొదటి ❌ నుంచి సరిచేయండి: తర్వాతివి దాని మీదే ఆధారపడతాయి.' : '✅ కొలిచిన చోట్లన్నీ సరిగ్గా ఉన్నాయి. అయినా పనిచేయకపోతే: మాడ్యూల్స్ పిన్ వరుస, ESP32 కోడ్ (పిన్ నంబర్లు), LED / డయోడ్ దిశ చూడండి.'));
        ft.tests.forEach((t, i) => {
          const x = ft.res[i];
          s.appendChild(el('div', 'step', (x ? (x.w.ok ? '✅ ' : '❌ ') : '⏭️ ') + t.net + ' @ ' + t.where.te + ': ' + fv(t.v) + ' రావాలి' + (x ? ', ' + fv(x.got) + ' వచ్చింది' : ' (కొలవలేదు)')));
        });
        const r = el('div', 'row');
        r.appendChild(btn('🔄 మళ్ళీ మొదటి నుంచి', '', () => { ft.i = -1; ft.res = []; faultSheet(); }));
        r.appendChild(btn('మూసేయి', '', closeSheet));
        s.appendChild(r);
        return;
      }
      const t = ft.tests[ft.i], x = ft.res[ft.i];
      s.appendChild(el('h2', null, '🧪 చోటు ' + (ft.i + 1) + ' / ' + n + ': ' + t.net));
      s.appendChild(el('div', 'step', '⚫ నలుపు ప్రోబ్: ' + ft.gnd.te + ' (GND, అలాగే ఉంచండి)'));
      s.appendChild(el('div', 'step', '🔴 ఎరుపు ప్రోబ్: ' + t.where.te));
      s.appendChild(el('div', 'step', '🎯 రావాల్సింది: సుమారు ' + fv(t.v) + (t.moving ? ' (ఈ చోటు టిక్ టిక్ మారుతుంది: 0 నుంచి ' + fv(t.max || t.v) + ' మధ్య కదిలే అంకెలు సరే)' : '') +
        (t.kind === 'supply' ? ' · ఇది బోర్డ్‌కి వస్తున్న సప్లై' : t.kind === 'led' ? ' · LED + కాలు' : '')));
      if (!x) {
        const inp = el('input');
        inp.type = 'number'; inp.inputMode = 'decimal'; inp.step = '0.01'; inp.min = '0'; inp.max = '60'; inp.placeholder = 'మీటర్ చూపిన అంకె (ఉదా: 4.97)';
        inp.style.cssText = 'width:100%;font-size:18px;padding:10px;margin:8px 0;border-radius:10px;border:1px solid var(--line);background:transparent;color:inherit';
        s.appendChild(inp);
        const answer = (got) => { if (!(got >= 0 && got < 100)) { toast('మీటర్ చూపిన అంకె రాయండి (ఉదా: 4.97)'); return; } ft.res[ft.i] = { got, w: MK.faultWhy(D, t, got) }; faultSheet(); };
        const r = el('div', 'row');
        r.appendChild(btn('✔️ చూడు', 'main', () => answer(parseFloat(String(inp.value).replace(',', '.')))));
        r.appendChild(btn('≈ ' + fv(t.v) + ' వచ్చింది', '', () => answer(t.v)));
        r.appendChild(btn('0 V', '', () => answer(0)));
        s.appendChild(r);
      } else {
        s.appendChild(el('div', x.w.ok ? 'step' : 'step warnc', x.w.head));
        x.w.lines.forEach((l) => s.appendChild(el('div', 'step', l)));
        if (!x.w.ok && t.kind !== 'supply') {
          const bp = MK.beepPairs(D, t.net);
          if (bp.length) {
            s.appendChild(el('h3', null, '🔊 బీప్ చెక్ (కరెంట్ తీసేసి, మీటర్ బీప్ 🔊 మీద)'));
            bp.forEach((b) => s.appendChild(el('div', 'step', '🔊 ' + b + ': బీప్ రావాలి')));
            s.appendChild(el('div', 'step', '🔇 ' + t.where.te + ' ↔ GND: ఆగకుండా బీప్ రాకూడదు (వస్తే షార్ట్)'));
          }
        }
        if (!x.w.ok && ft.i < n - 1) s.appendChild(el('div', 'muted', 'ముందు ఇది సరిచేయడం మంచిది: తర్వాతి చోట్లు దీని మీదే ఆధారపడతాయి.'));
      }
      const r2 = el('div', 'row');
      if (x) r2.appendChild(btn(ft.i < n - 1 ? '➡️ తర్వాతి చోటు' : '🏁 ఫలితం', 'main', () => { ft.i++; faultSheet(); }));
      if (x) r2.appendChild(btn('🔁 మళ్ళీ కొలిచాను', '', () => { ft.res[ft.i] = null; faultSheet(); }));
      if (!x) r2.appendChild(btn('⏭️ వదిలేయి', '', () => { ft.i++; faultSheet(); }));
      r2.appendChild(btn('🧩 బోర్డ్‌లో చూపించు', '', () => { pv.sel = t.where.ref; closeSheet(); showTab('pcb'); toast('🧩 ' + t.where.te + ' (మళ్ళీ 🧪 నొక్కితే ఇదే చోటు నుంచి)', 4000); }));
      if (ft.i > 0) r2.appendChild(btn('⬅️', '', () => { ft.i--; faultSheet(); }));
      r2.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r2);
    });
  }

  // ================================================================ 🔌 the wire table (26 Nov, "6. వైర్ టేబుల్"): ₹0, our own code

  function swatch(c) {
    const MKc = window.PcbMake.COLS[c] || ['', '#888'], sw = el('span');
    sw.style.cssText = 'display:inline-block;width:22px;height:10px;border-radius:5px;margin-right:8px;vertical-align:middle;border:1px solid #8a93a6;background:' + MKc[1];
    return sw;
  }
  function wireSheet(all) {
    if (!D) return;
    const MK = window.PcbMake, w = MK.wireTable(D);
    sheet((s) => {
      s.appendChild(el('h2', null, '🔌 వైర్ పట్టీ: ఏ పిన్ నుంచి ఏ పిన్‌కి'));
      s.appendChild(el('div', 'muted', 'బోర్డ్ నుంచి బయటికి వెళ్ళే ప్రతి వైరు, దాని రంగు. ఎరుపు = +, నలుపు = GND ఎప్పుడూ ఇలాగే ఉంచండి; మిగతా రంగులు సలహా మాత్రమే (ఒక్కో దారికి ఒక రంగు ఉంటే తప్పులు రావు).'));
      if (!w.off.length) s.appendChild(el('div', 'step', 'ఈ బోర్డ్‌కి బయటి వైర్లు లేవు: భాగాలన్నీ బోర్డ్ మీదే. కింద 🧪 కలుపుల పట్టీ బ్రెడ్‌బోర్డ్ మీద ముందే చూడటానికి.'));
      w.off.forEach((g) => {
        const c = el('div', 'card');
        c.appendChild(el('div', null, '▶️ ' + g.ref + ' · ' + g.te));
        c.appendChild(el('div', 'muted', '🔧 ' + g.how));
        g.rows.forEach((r) => {
          const row = el('div', 'step');
          row.appendChild(swatch(r.col));
          row.appendChild(document.createTextNode(g.ref + ' ' + r.name + ' → ' + r.to + ' · ' + MK.COLS[r.col][0] + (r.thick ? ' · 💪 మందమైన వైరు (1 A పైన)' : '')));
          c.appendChild(row);
        });
        s.appendChild(c);
      });
      s.appendChild(el('h3', null, '🧪 అన్ని కలుపులు (PCB ఆర్డర్ ముందే బ్రెడ్‌బోర్డ్ మీద చూడాలంటే)'));
      if (!all) {
        s.appendChild(btn('🧪 ' + w.nets.length + ' కలుపులు చూపించు', '', () => wireSheet(true)));
      } else {
        s.appendChild(el('div', 'muted', 'ప్రతి లైన్ ఒక దారి: ↔ తో ఉన్నవన్నీ ఒకే వైరుతో (లేదా బ్రెడ్‌బోర్డ్ ఒకే వరుసలో) కలవాలి. కరెంట్ తీసేసి కలపండి.'));
        w.nets.forEach((n) => {
          const row = el('div', 'step');
          row.appendChild(swatch(n.col));
          row.appendChild(document.createTextNode(n.n + ': ' + n.ends.map((e) => e.ref + ' ' + e.name).join(' ↔ ')));
          s.appendChild(row);
        });
      }
      const r = el('div', 'row');
      r.appendChild(btn('📋 పట్టీ పంపు', '', () => post({ t: 'sharetext', text: MK.wireText(D) })));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }

  // ================================================================ 📚 the module library (26 Nov, "2. మాడ్యూల్ లైబ్రరీ"): ₹0, our own data

  const shopName = () => ((window.PcbBuy && window.PcbBuy.SHOPS.find((q) => q[0] === shop)) || ['', 'షాప్'])[1];
  /** "⚡ 3.6–6 V · about 40 mA · 🧩 plugs into a socket on the board". */
  function modLine(lib) {
    const m = lib.mod;
    return '⚡ కావాల్సిన వోల్టేజ్: ' + m.v[0] + '–' + m.v[1] + ' V · సుమారు ' + m.ma + ' mA · ' +
      (lib.kind === 'plug' ? '🧩 బోర్డ్ మీది సాకెట్‌లో గుచ్చేది' : '🔌 బోర్డ్ పక్కన, జంపర్ వైర్లతో కలిపేది') + (m.v[1] < 4 ? ' · ⚠️ 5 V ఇవ్వకండి' : '');
  }
  /** His module's board is the other kind: pick its pin order; the joins follow the names (VCC stays VCC), the tracks are laid again. */
  function varPicker(s, pt) {
    const lib = P.LIB[pt.lib], cur = pt.var | 0;
    s.appendChild(el('h3', null, '🔁 పిన్ వరుస (మీ మాడ్యూల్ మీది పేర్లు చూసి ఎంచుకోండి)'));
    s.appendChild(el('div', 'muted', 'ఎడమ నుంచి కుడికి, మాడ్యూల్ మీది పేర్లు చదివినట్టు. మార్చితే కలుపులు పేర్లతో పాటు జరుగుతాయి, దారులు మళ్ళీ వేస్తాను (₹0). ↩️ తో వెనక్కి.'));
    lib.vars.forEach((v, i) => {
      const b = btn((i === cur ? '✓ ' : '') + v.te, i === cur ? 'on' : '', () => {
        if (i === cur) return;
        snap();
        const t = P.setVariant(D, pt, i);
        if (!t) return;
        D.parts.forEach((q) => { if (q.ref === pt.ref) delete q.pl; });
        const lb = C.labelBox(pt, pt.x, pt.y, pt.rot || 0, D.board);
        if (lb) { pt.pl = lb.side; pt.plo = lb.off; }
        afterMove(t);
        toast('🔁 ' + pt.ref + ': ' + v.te + '. బోర్డ్ మీది పేర్లు, కలుపులు మారాయి' + (autoRoute ? '; దారులు మళ్ళీ వేస్తున్నాను.' : '; ⚡ రూట్ నొక్కండి.'), 4000);
        partSheet(pt);
      });
      b.style.display = 'block'; b.style.width = '100%'; b.style.margin = '4px 0';
      s.appendChild(b);
    });
  }
  /** The card on 📚 బోర్డ్‌లు. */
  function modLibCard(h) {
    const c = el('div', 'card tap');
    c.appendChild(el('div', null, '📚 మాడ్యూల్ లైబ్రరీ: ' + P.MODULES.length + ' రెడీ మాడ్యూల్స్, అసలు పిన్ వరుసతో'));
    c.appendChild(el('div', 'muted', 'బ్లూటూత్, స్క్రీన్లు, సెన్సార్లు, రిలే, సర్వో… ఒక్కోదానికి పిన్ పేర్లు, వోల్టేజ్, జాగ్రత్తలు. బోర్డ్‌లో కావాలంటే ✏️ మార్పులో పేరుతో చెప్పండి.'));
    c.addEventListener('click', modList);
    h.appendChild(c);
  }
  function modList() {
    sheet((s) => {
      s.appendChild(el('h2', null, '📚 మాడ్యూల్ లైబ్రరీ'));
      s.appendChild(el('div', 'muted', 'ఒకటి నొక్కితే దాని వివరాలు. ఇవి మేము గీసిన పిన్ వరుసలు: కొన్న మాడ్యూల్ మీది పేర్లతో ఎప్పుడూ ఒకసారి సరిచూడండి.'));
      [['plug', '🧩 బోర్డ్ మీది సాకెట్‌లో గుచ్చేవి'], ['wire', '🔌 వైర్లతో కలిపేవి']].forEach(([kind, title]) => {
        s.appendChild(el('h3', null, title));
        P.MODULES.filter((k) => P.LIB[k].kind === kind).forEach((k) => {
          const L = P.LIB[k], it = el('div', 'item tap');
          const x = el('div', 'x');
          x.appendChild(el('div', null, L.te));
          x.appendChild(el('div', 'f', Object.keys(L.pins).map((q) => L.pins[q]).join(' · ') + '  ·  ' + L.mod.v[0] + '–' + L.mod.v[1] + ' V' + (L.vars ? '  ·  🔁 ' + L.vars.length + ' వరుసలు' : '')));
          it.appendChild(x);
          it.addEventListener('click', () => modInfo(k));
          s.appendChild(it);
        });
      });
      const r = el('div', 'row');
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** One module from the library (no board needed). */
  function modInfo(k) {
    const L = P.LIB[k], f = P.fp(L.fp);
    sheet((s) => {
      s.appendChild(el('h2', null, '📚 ' + L.te));
      s.appendChild(el('div', 'step', '🔎 ' + L.what));
      s.appendChild(el('div', 'step', '🔧 ' + L.how));
      s.appendChild(el('div', 'step', modLine(L)));
      if (f && f.verify) s.appendChild(el('div', 'step warnc', '📏 ' + f.verify + '.'));
      (L.vars || [{ te: '', pins: L.pins }]).forEach((v) => {
        s.appendChild(el('h3', null, 'పిన్నులు' + (v.te ? ': ' + v.te : '')));
        const tb = el('table'), hr = el('tr');
        ['పిన్', 'పేరు'].forEach((t) => hr.appendChild(el('th', null, t))); tb.appendChild(hr);
        Object.keys(v.pins).forEach((q) => { const tr = el('tr'); tr.appendChild(el('td', null, q)); tr.appendChild(el('td', null, v.pins[q])); tb.appendChild(tr); });
        s.appendChild(tb);
      });
      const r = el('div', 'row');
      if (window.PcbBuy) r.appendChild(btn('🛒 ' + shopName(), '', () => post({ t: 'shopsearch', shop, q: window.PcbBuy.query({ lib: k, val: '' }) })));
      if (D) r.appendChild(btn('✏️ నా బోర్డ్‌లో పెట్టు', 'main', () => editSheet(L.te + ' (' + k + ') కలుపు: ')));
      r.appendChild(btn('⬅️ లిస్ట్', '', modList));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }

  // ================================================================ pan, pinch, tap, drag (both drawings)

  function gestures(cv, view, on) {
    const pts = new Map();
    let mode = null, st = null, pinch = null, moved = false;
    const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
    cv.addEventListener('pointerdown', (e) => {
      try { cv.setPointerCapture(e.pointerId); } catch (x) { /* (old WebView) */ }
      pts.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pts.size === 1) {
        st = { x: e.offsetX, y: e.offsetY, ox: view.ox, oy: view.oy, t: Date.now() };
        moved = false;
        mode = on.dragStart && on.dragStart(e.offsetX, e.offsetY) ? 'drag' : 'pan';
      } else if (pts.size === 2) {
        if (mode === 'drag' && on.dragEnd) on.dragEnd(true);
        mode = 'pinch';
        const [a, b] = Array.from(pts.values());
        pinch = { d: dist(a, b) || 1, s: view.s, cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, ox: view.ox, oy: view.oy };
      }
    });
    cv.addEventListener('pointermove', (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (mode === 'pinch' && pts.size >= 2) {
        const [a, b] = Array.from(pts.values());
        const s = Math.max(0.2, Math.min(80, pinch.s * dist(a, b) / pinch.d));
        const wx = (pinch.cx - pinch.ox) / pinch.s, wy = (pinch.cy - pinch.oy) / pinch.s, cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
        view.s = s; view.ox = cx - wx * s; view.oy = cy - wy * s; view.fit = false;
        moved = true;
        on.redraw();
      } else if (st && pts.size === 1) {
        const dx = e.offsetX - st.x, dy = e.offsetY - st.y;
        if (Math.hypot(dx, dy) > 6) moved = true;
        if (!moved) return;
        if (mode === 'drag') on.dragMove(e.offsetX, e.offsetY);
        else if (mode === 'pan') { view.ox = st.ox + dx; view.oy = st.oy + dy; view.fit = false; on.redraw(); }
      }
    });
    const up = (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.delete(e.pointerId);
      if (pts.size === 0) {
        if (mode === 'drag') on.dragEnd(!moved);
        if (!moved && mode !== 'pinch' && st && Date.now() - st.t < 600 && on.tap) on.tap(e.offsetX, e.offsetY);
        mode = null; st = null; pinch = null;
      } else if (pts.size === 1 && mode === 'pinch') {
        const [a] = Array.from(pts.values());
        st = { x: a.x, y: a.y, ox: view.ox, oy: view.oy, t: 0 }; mode = 'pan'; moved = true;
      }
    };
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', (e) => { if (mode === 'drag' && on.dragEnd) on.dragEnd(true); mode = null; pts.delete(e.pointerId); });
    cv.addEventListener('wheel', (e) => {
      e.preventDefault();
      const k = e.deltaY < 0 ? 1.15 : 1 / 1.15, s = Math.max(0.2, Math.min(80, view.s * k));
      const wx = (e.offsetX - view.ox) / view.s, wy = (e.offsetY - view.oy) / view.s;
      view.s = s; view.ox = e.offsetX - wx * s; view.oy = e.offsetY - wy * s; view.fit = false;
      on.redraw();
    }, { passive: false });
  }

  // ================================================================ ✅ the checks

  function drcList() {
    const h = clear($('drc'));
    if (!D || !check) return;
    const top = el('div', 'card');
    if (!check.errors) {
      top.appendChild(el('div', 'big okc', '✅ ఆర్డర్‌కి సిద్ధం'));
      top.appendChild(el('div', 'muted', (C.FABS[D.fab] || C.FABS.jlc).name + ' నియమాలతో చూశాను: షార్ట్‌లు లేవు, అన్ని పిన్నులు కలిశాయి, దారులు / రంధ్రాలు / అంచు సరిగ్గా ఉన్నాయి.' + (check.warns ? ' ⚠️ ' + check.warns + ' సూచనలు కింద (ఆర్డర్ ఆపవు).' : '')));
    } else {
      top.appendChild(el('div', 'big err', '❌ ' + check.errors + ' తప్పులు'));
      top.appendChild(el('div', 'muted', 'ఇవి సరిచేసే వరకు బోర్డ్ పనిచేయకపోవచ్చు లేదా ఫ్యాక్టరీ తిరస్కరించవచ్చు. ఒక్కోదాని మీద నొక్కితే PCB లో ఆ చోటు చూపిస్తుంది.'));
    }
    h.appendChild(top);
    helpCard(h);
    reviewCard(h);
    const fabRow = el('div', 'row');
    fabRow.appendChild(el('div', 'k muted', 'ఏ ఫ్యాక్టరీ నియమాలతో:'));
    Object.keys(C.FABS).forEach((k) => fabRow.appendChild(btn(C.FABS[k].name, D.fab === k ? 'on small' : 'small', () => { D.fab = k; changed(); drcList(); })));
    h.appendChild(fabRow);
    const icon = { err: '❌', warn: '⚠️', info: '📏' };
    check.list.forEach((f) => {
      const it = el('div', 'item');
      it.appendChild(el('div', 'i', icon[f.sev]));
      const x = el('div', 'x'), te = String(f.te || '');
      x.appendChild(el('div', f.sev === 'err' ? 'err' : f.sev === 'warn' ? 'warnc' : '', te.startsWith(icon[f.sev]) ? te.slice(icon[f.sev].length).trim() : te));
      if (f.fix) x.appendChild(el('div', 'f', '👉 ' + f.fix));
      it.appendChild(x);
      it.addEventListener('click', () => {
        showTab('pcb');
        const { w, h: hh } = sizeCv($('pcbcv'));
        pv.s = Math.max(pv.s, 14); pv.ox = w / 2 - f.x * pv.s; pv.oy = (hh - 60) / 2 - f.y * pv.s; pv.fit = false;
        drawPcb();
      });
      h.appendChild(it);
    });
    if (!check.list.length) h.appendChild(el('div', 'muted', 'ఏ సూచనలూ లేవు.'));
    h.appendChild(el('div', 'muted', '\nఈ చెక్ మన సొంత కోడ్ (₹0, నెట్ లేకుండా). ఫ్యాక్టరీ సైట్ కూడా అప్‌లోడ్ చేశాక తన చెక్ చేస్తుంది.'));
  }

  // ================================================================ 🧊 3D

  let v3d = null, v3dLoading = false;
  function texture(side, pxPerMm) {
    const k = pxPerMm || 12, w = Math.round(D.board.w * k), h = Math.round(D.board.h * k);
    const cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    const ctx = cv.getContext('2d');
    ctx.fillStyle = '#c8b98a'; ctx.fillRect(0, 0, w, h);
    drawBoard(ctx, D, M, fills, { s: k, ox: 0, oy: 0 }, { real: true, bottomSide: side === 'bottom' });
    return cv;
  }
  function open3d() {
    const info = clear($('v3dinfo'));
    if (xrS.wait) info.appendChild(el('span', 'chip', '📷 AR మొదలవుతోంది…'));
    else info.appendChild(el('span', 'chip', '👆 ఒక వేలితో తిప్పండి, రెండు వేళ్ళతో దగ్గర/దూరం · భాగం మీద నొక్కితే పేరు'));
    clear($('d3tools'));
    with3d(() => {
      if (tab !== 'd3') return;
      v3d.open(api3d());
      if (xrS.on) return;
      v3d.guide(null);
      v3d.boxShow(bx.on ? bx.plan : null, bx.on ? { open: bx.open, see: bx.see } : null);
      if (sg.on) guideShow(true);
      if (bx.on) { boxCard(); v3d.view('side'); }
      if (!sg.on && !bx.on) fit3d();
      tools3d();
    });
  }
  function tools3d() {
    const t = clear($('d3tools'));
    if (!v3d) return;
    if (xrS.on || xrS.wait) {
      t.appendChild(tool('✕ AR ఆపు', 'main', stopAr));
      if (xrS.on) {
        t.appendChild(tool('🔄 90°', '', () => v3d.xrTurn(90)));
        t.appendChild(tool(xrS.box ? '📦 డబ్బాతో ✓' : '📦 డబ్బాతో', xrS.box ? 'on' : '', () => {
          xrS.box = !xrS.box;
          if (xrS.box) { bx.plan = bx.plan || MK.boxPlan(D, { extra: makeOf().extra, vents: makeOf().vents }); v3d.boxShow(bx.plan, { open: false, see: false }); } else v3d.boxShow(null);
          tools3d();
        }));
        t.appendChild(tool('🏷️ పేర్లు', '', (e) => { const on = v3d.labels(); e.target.classList.toggle('on', on); }));
      }
      return;
    }
    if (sg.on) {
      t.appendChild(tool('✕ గైడ్ ఆపు', '', stopGuide));
      t.appendChild(tool('📷 నిజమైన బోర్డ్ మీద', 'main', camOpen));
      t.appendChild(tool(sg.bottom ? '⬆️ పై వైపు' : '⬇️ టంకం వైపు', sg.bottom ? 'on' : '', () => { sg.bottom = !sg.bottom; guideShow(true); }));
      t.appendChild(tool('🔍 మొత్తం', '', () => v3d.view('side')));
      t.appendChild(tool('📋 అన్ని అడుగులు', '', guideList));
      return;
    }
    if (bx.on) {
      t.appendChild(tool('✕ డబ్బా', '', stopBox));
      t.appendChild(tool('↗️ పక్కనుంచి', '', () => v3d.view('side')));
      t.appendChild(tool('⬆️ పైనుంచి', '', () => v3d.view('top')));
      t.appendChild(tool('🔄 తిరుగు', '', (e) => { const on = v3d.spin(); e.target.classList.toggle('on', on); }));
      t.appendChild(tool('📷 AR', '', () => { xrS.box = true; startAr(); }));
      return;
    }
    t.appendChild(tool('🔧 టంకం గైడ్', 'main', () => startGuide()));
    t.appendChild(tool('📦 డబ్బా', '', startBox));
    t.appendChild(tool('📷 AR', '', startAr));
    t.appendChild(tool('⬆️ పైనుంచి', '', () => v3d.view('top')));
    t.appendChild(tool('↗️ పక్కనుంచి', '', () => v3d.view('side')));
    t.appendChild(tool('⬇️ కిందనుంచి', '', () => v3d.view('bottom')));
    t.appendChild(tool('🔄 తిరుగు', '', (e) => { const on = v3d.spin(); e.target.classList.toggle('on', on); }));
    t.appendChild(tool('🏷️ పేర్లు', '', (e) => { const on = v3d.labels(); e.target.classList.toggle('on', on); }));
  }
  function api3d() {
    return {
      host: $('v3d'), design: () => D, model: () => M, texture, P, C,
      onXrTap: (x, y) => { if (xrS.on) post({ t: 'xrtap', x, y }); },
      onTap: (ref) => {
        const info = clear($('v3dinfo'));
        if (!ref) { info.appendChild(el('span', 'chip', '👆 భాగం మీద నొక్కితే పేరు')); return; }
        const pt = D.parts.find((q) => q.ref === ref), lib = pt ? P.LIB[pt.lib] || {} : {};
        const chip = el('span', 'chip ok', ref + ' · ' + (lib.te || '') + (pt && pt.val ? ' · ' + pt.val : '') + ' (ℹ️ నొక్కండి)');
        chip.addEventListener('click', () => partSheet(pt));
        info.appendChild(chip);
      }
    };
  }

  // ================================================================ 🏭 the factory

  // ================================================================ 🏭 Build C (26 Nov): small parts as SMD, soldered by the factory (PCBA)

  /** Why a part stays a through-hole one (his iron). */
  function stayWhy(pt) {
    switch (pt.lib) {
      case 'r7805': return 'వేడెక్కే 7805: కాళ్ళదే (హీట్‌సింక్ పెట్టొచ్చు)';
      case 'nmos': return 'పెద్ద కరెంట్ MOSFET: కాళ్ళదే';
      case 'elec': return 'ఎలక్ట్రోలైటిక్ కెపాసిటర్: కాళ్ళదే';
      case 'irled': return 'IR LED: TV వైపు చూడాలి, కాళ్ళదే';
      case 'ptc': return 'PTC ఫ్యూజ్: కాళ్ళదే';
      default: return 'కనెక్టర్ / సాకెట్ / స్విచ్ / మాడ్యూల్: బలం కోసం కాళ్ళదే';
    }
  }
  /** Each resistor's watts on ⚡ (an ⅛ W SMD one must stay cool). */
  function resWatts() {
    const out = new Map();
    try {
      const S = SIM.setup(D, { src: sim.src, ctl: sim.ctl });
      if (S.err) return out;
      const cy = SIM.has555(S) ? SIM.cycle(SIM.setup(D, { src: sim.src, ctl: sim.ctl })) : null, R = SIM.dc(S);
      D.parts.filter((q) => q.lib === 'res').forEach((q) => {
        const w = cy && cy.most && cy.most.p[q.ref] && typeof cy.most.p[q.ref].w === 'number' ? cy.most.p[q.ref].w : Math.abs((R.p[q.ref] || {}).w || 0);
        out.set(q.ref, w);
      });
    } catch (e) { /* (no ⚡: every resistor may go) */ }
    return out;
  }
  /** What can become SMD now, what stays, and what already is. */
  function smdPlan() {
    const go = [], stay = [], done = [], W = resWatts();
    D.parts.forEach((pt) => {
      if (P.isSmd(pt)) { done.push(pt); return; }
      const sm = P.smdOf(pt);
      if (!sm) { stay.push({ pt, why: stayWhy(pt) }); return; }
      const w = W.get(pt.ref) || 0;
      if (pt.lib === 'res' && w > 0.075) { stay.push({ pt, why: 'ఇది ' + SIM.fmtW(w) + ' వేడి తీసుకుంటుంది: ⅛ W SMD కి ఎక్కువ, కాళ్ళదే' }); return; }
      go.push({ pt, sm });
    });
    return { go, stay, done };
  }
  function pcbaCard(h) {
    const pl = smdPlan(), c = el('div', 'card');
    h.appendChild(el('h3', null, '🏭 ఫ్యాక్టరీలోనే టంకం (SMD, ఐచ్ఛికం)'));
    if (!pl.done.length && !pl.go.length) {
      c.appendChild(el('div', 'muted', 'ఈ బోర్డ్‌లో SMD గా మార్చగలిగే చిన్న భాగాలు లేవు (కనెక్టర్లు, మాడ్యూల్స్, పెద్ద భాగాలు మాత్రమే). అన్నీ మీరే టంకం చేయాలి.'));
      h.appendChild(c);
      return;
    }
    if (!pl.done.length) {
      c.appendChild(el('div', null, '🏭 చిన్న భాగాలని ఫ్యాక్టరీనే పెట్టి టంకం చేయాలా?'));
      c.appendChild(el('div', 'muted', 'రెసిస్టర్లు, కెపాసిటర్లు, LED లు, డయోడ్లు, ట్రాన్సిస్టర్, 555, 3.3V రెగ్యులేటర్: కాళ్ళు లేని చిన్న SMD భాగాలుగా మారతాయి. ఫ్యాక్టరీ వాటిని పెట్టి టంకం చేసి పంపుతుంది (PCBA). కనెక్టర్లు, సాకెట్లు, మాడ్యూల్స్, పెద్ద భాగాలు మీరే టంకం చేస్తారు. బోర్డ్ చిన్నది అవుతుంది, టంకం పని తగ్గుతుంది; ఖర్చు కొంచెం ఎక్కువ.'));
      c.appendChild(el('div', 'step', '🔁 ' + pl.go.length + ' భాగాలు SMD కావచ్చు · ' + pl.stay.length + ' కాళ్ళవే ఉంటాయి'));
      const r = el('div', 'row');
      r.appendChild(btn('🏭 SMD గా మార్చి చూడు (₹0)', 'main', smdSheet));
      c.appendChild(r);
      h.appendChild(c);
      return;
    }
    const sp = F.pcbaSplit(D);
    c.appendChild(el('div', 'okc', '🏭 ఫ్యాక్టరీ పెట్టేవి: ' + sp.smd.length + ' SMD భాగాలు · మీరు టంకం చేసేవి: ' + sp.th.length));
    c.appendChild(el('div', 'muted', '"📦 అసెంబ్లీ ఫైల్స్" మూడు ఫైల్స్ సేవ్ చేస్తుంది: బోర్డ్ (Gerber zip), భాగాల జాబితా (BOM), భాగాల స్థానాలు (CPL). JLCPCB సైట్‌లో "PCB Assembly" ఆన్ చేసి అవి మీరే అప్‌లోడ్ చేస్తారు; ధర, పేమెంట్ అక్కడే, మీరే.'));
    if (sp.noNum.length) c.appendChild(el('div', 'step warnc', '🔢 ' + sp.noNum.join(', ') + ': LCSC నంబర్ నా దగ్గర లేదు. JLCPCB BOM పేజీలో విలువ చూసి మీరే భాగం ఎంచుకోవాలి.'));
    if (pl.go.length) c.appendChild(el('div', 'step', '🔁 ఇంకా ' + pl.go.length + ' భాగాలు SMD కావచ్చు (కొత్తగా వచ్చినవి).'));
    const r = el('div', 'row');
    r.appendChild(btn('📦 అసెంబ్లీ ఫైల్స్ సేవ్ చేయి', 'main', () => pcbaSave()));
    r.appendChild(btn('📋 భాగాలు', 'small', pcbaList));
    r.appendChild(btn('👣 JLCPCB లో ఎలా', 'small', pcbaHow));
    if (pl.go.length) r.appendChild(btn('🔁 మిగిలినవీ SMD', 'small', smdSheet));
    r.appendChild(btn('↩️ కాళ్ళ భాగాలకి తిరిగి', 'small', smdBackSheet));
    c.appendChild(r);
    if (saved && saved.kind === 'pcba') {
      c.appendChild(el('div', 'okc', '💾 సేవ్ అయింది: ' + String(saved.where).replace(/[^/]*$/, '') + ' (zip + 2 csv)'));
      const r2 = el('div', 'row');
      r2.appendChild(btn('🌐 JLCPCB సైట్ తెరువు', 'main', () => post({ t: 'site', fab: 'jlc' })));
      r2.appendChild(btn('📤 zip పంపు', '', () => post({ t: 'share' })));
      c.appendChild(r2);
      c.appendChild(el('div', 'muted', 'మీరు నొక్కితేనే. లాగిన్, అప్‌లోడ్, పేమెంట్ మీరే.'));
    }
    h.appendChild(c);
  }
  function smdSheet() {
    const pl = smdPlan();
    sheet((s) => {
      s.appendChild(el('h2', null, '🏭 SMD గా మార్చాలా? (' + pl.go.length + ' భాగాలు)'));
      s.appendChild(el('div', 'muted', 'కలుపులు (ఏ పిన్ ఎక్కడికి) అలాగే ఉంటాయి; భాగాలు చిన్నవి అవుతాయి, దారులు మళ్ళీ వేస్తాను. ముందున్న బోర్డ్ 🕑 వెర్షన్‌గా దాచి ఉంచుతాను: ↩️ / 🕑 తో వెనక్కి రావచ్చు. ₹0, ఫోన్‌లోనే.'));
      s.appendChild(el('h3', null, '🏭 ఫ్యాక్టరీ పెట్టేవి'));
      pl.go.forEach((g) => s.appendChild(el('div', 'step', g.pt.ref + (g.pt.val && g.pt.lib !== 'ne555' && g.pt.lib !== 'r3v3' ? ' ' + g.pt.val : '') + ' → ' + g.sm.te + (g.sm.lcsc ? ' · LCSC ' + g.sm.lcsc : ' · LCSC నంబర్ లేదు (JLCPCB లో ఎంచుకోవాలి)') + (g.sm.volts ? ' · ' + g.sm.volts + ' V' : ''))));
      if (pl.stay.length) {
        s.appendChild(el('h3', null, '🔧 మీరే టంకం చేసేవి (కాళ్ళవే)'));
        pl.stay.forEach((g) => s.appendChild(el('div', 'step muted', g.pt.ref + ': ' + g.why)));
      }
      s.appendChild(el('div', 'step warnc', '💰 JLCPCB లో "Basic" భాగాలకి అదనపు ఫీజు లేదు; "Extended" భాగాలకి (ఉదా: NE555DR, నీలం LED) ఒక్కో రకానికి కొంచెం ఎక్కువ. అసెంబ్లీకి సెటప్ ఛార్జీ కూడా ఉంటుంది. ధర వాళ్ళ సైట్‌లోనే చూపిస్తుంది; ఆర్డర్, పేమెంట్ మీరే.'));
      const r = el('div', 'row');
      r.appendChild(btn('✅ మార్చు (స్థానాలు అలాగే)', 'main', () => smdApply(pl.go, false)));
      r.appendChild(btn('📐 మార్చి బోర్డ్ చిన్నది చేయి', '', () => smdApply(pl.go, true)));
      r.appendChild(btn('వద్దు', '', closeSheet));
      s.appendChild(r);
      s.appendChild(el('div', 'muted', '📐: భాగాలన్నీ Jarvis మళ్ళీ దగ్గరగా అమర్చి బోర్డ్ చిన్నది చేస్తుంది (ఇప్పటి అమరిక పోతుంది, 🕑 లో ఉంటుంది).'));
    });
  }
  /** The swap itself: a 🕑 version first, then the SMD footprints, then the tracks (or a smaller board). */
  function smdApply(list, shrink) {
    if (!list.length) { closeSheet(); return; }
    const before = { w: D.board.w, h: D.board.h };
    verSnap('🏭 SMD కి ముందు');
    snap();
    list.forEach((g) => { const q = D.parts.find((x) => x.ref === g.pt.ref); if (q) { q.fp = g.sm.fp; delete q.pl; } });
    let miss = 0, moved = false;
    const relay = () => {
      const title = (D.labels.find((l) => !l.ref) || {}).t || '';
      return A.layout(JSON.parse(JSON.stringify(D)), title);
    };
    miss = reroute(true).failed.reduce((t, f) => t + f.pins.length, 0);
    if (shrink || miss) { // (📐, or the old places leave pins unjoined: Jarvis places them again closer; a bigger board is never chosen for 📐)
      const best = relay(), area = D.board.w * D.board.h;
      if (best && (best.miss < miss || (shrink && best.miss === miss && best.d.board.w * best.d.board.h < area - 1))) {
        ['board', 'parts', 'labels', 'tracks', 'vias'].forEach((k) => { D[k] = best.d[k]; });
        moved = true; miss = best.miss;
      }
    }
    changed();
    closeSheet();
    if (tab === 'fab') fab(); // (the card behind shows the new state)
    resultSheet('🏭 ' + list.length + ' భాగాలు SMD అయ్యాయి', [
      'బోర్డ్: ' + before.w + ' × ' + before.h + ' mm' + (moved ? ' → ' + D.board.w + ' × ' + D.board.h + ' mm' : ''),
      moved && !shrink ? '🔀 పాత స్థానాల్లో కొన్ని పిన్నులు కలవలేదు: అందుకే భాగాలు మళ్ళీ దగ్గరగా అమర్చాను (పాతది 🕑 లో ఉంది).' : '',
      shrink && !moved ? '📐 భాగాలు మళ్ళీ అమర్చినా ఇంతకంటే చిన్నది కాలేదు (కనెక్టర్లు / మాడ్యూల్స్ పెద్దవి): స్థానాలు అలాగే ఉంచాను.' : '',
      miss ? '⚠️ ' + miss + ' పిన్నులు ఇంకా కలవలేదు: 🧩 PCB లో భాగాలు కొంచెం జరిపి ⚡ రూట్, లేదా ✍️ చేతితో దారి.' : '✅ అన్ని కలుపులూ దారులతో కలిశాయి.',
      check.errors ? '❌ ✅ చెక్ లో ' + check.errors + ' తప్పులు: చూసి సరిచేయండి.' : '✅ చెక్: తప్పులు లేవు.',
      '⚡ నడిపించు లో ఒకసారి చూడండి (SMD రెసిస్టర్ ⅛ W, 10 µF SMD 25 V).',
      '🏭 ఫ్యాక్టరీ ట్యాబ్‌లో "📦 అసెంబ్లీ ఫైల్స్" తో ఆర్డర్ ఫైల్స్.'
    ].filter(Boolean), [btn('🧩 PCB చూపించు', 'main', () => { closeSheet(); showTab('pcb'); })]);
  }
  function smdBackSheet() {
    sheet((s) => {
      const n = D.parts.filter((q) => P.isSmd(q)).length;
      s.appendChild(el('h2', null, '↩️ ' + n + ' SMD భాగాలు మళ్ళీ కాళ్ళ భాగాలుగా?'));
      s.appendChild(el('div', 'muted', 'అన్నీ మీరే టంకం చేసేలా. కాళ్ళ భాగాలు పెద్దవి: చోటు సరిపోకపోతే భాగాలు మళ్ళీ అమర్చి బోర్డ్ సైజు మారుస్తాను. ముందున్నది 🕑 లో ఉంటుంది.'));
      const r = el('div', 'row');
      r.appendChild(btn('అవును, తిరిగి మార్చు', 'main', () => {
        verSnap('🏭 SMD నుంచి తిరిగి ముందు');
        snap();
        D.parts.forEach((q) => { if (P.isSmd(q)) { delete q.fp; delete q.pl; } });
        M = C.model(D);
        const bad = C.drc(D, D.fab, [null, null], M).list.some((f) => f.code === 'overlap' || f.code === 'outside');
        if (bad) {
          const title = (D.labels.find((l) => !l.ref) || {}).t || '';
          const best = A.layout(JSON.parse(JSON.stringify(D)), title);
          if (best) ['board', 'parts', 'labels', 'tracks', 'vias'].forEach((k) => { D[k] = best.d[k]; });
          else reroute(true);
        } else reroute(true);
        changed();
        closeSheet();
        toast('↩️ అన్నీ మళ్ళీ కాళ్ళ భాగాలు' + (bad ? '; చోటు కోసం భాగాలు మళ్ళీ అమర్చాను.' : '.'), 4000);
        if (tab === 'fab') fab();
      }));
      r.appendChild(btn('వద్దు', '', closeSheet));
      s.appendChild(r);
    });
  }
  function pcbaList() {
    const sp = F.pcbaSplit(D);
    sheet((s) => {
      s.appendChild(el('h2', null, '📋 ఫ్యాక్టరీ పెట్టే భాగాలు (' + sp.smd.length + ')'));
      const tb = el('table'), hr = el('tr');
      ['భాగం', 'ఏమిటి', 'LCSC'].forEach((t) => hr.appendChild(el('th', null, t))); tb.appendChild(hr);
      sp.smd.forEach((q) => { const sm = P.smdOf(q) || {}; const tr = el('tr'); [q.ref, sm.mpn || q.val || '', sm.lcsc || '—'].forEach((t) => tr.appendChild(el('td', null, t))); tb.appendChild(tr); });
      s.appendChild(tb);
      s.appendChild(el('h3', null, '🔧 మీరు టంకం చేసేవి (' + sp.th.length + ')'));
      s.appendChild(el('div', 'muted', sp.th.map((q) => q.ref + (q.val ? ' ' + q.val : '')).join(' · ') || '—'));
      s.appendChild(el('div', 'muted', 'LCSC నంబర్లు 26 Nov 2026 న jlcpcb.com లో చూసినవి. స్టాక్, ధర మారతాయి: ఆర్డర్ ముందు వాళ్ళ BOM పేజీలో ప్రతి భాగం ఫోటో, విలువ చూసుకోండి.'));
    });
  }
  function pcbaHow() {
    sheet((s) => {
      s.appendChild(el('h2', null, '👣 JLCPCB లో అసెంబ్లీ ఆర్డర్ (మీరే)'));
      ['1. 🏭 ఇక్కడ "📦 అసెంబ్లీ ఫైల్స్ సేవ్ చేయి": Downloads/Jarvis/pcb లో 3 ఫైల్స్ (…-gerber-….zip, …-BOM-JLCPCB.csv, …-CPL-JLCPCB.csv).',
        '2. 🌐 JLCPCB సైట్ (మీ బ్రౌజర్): Gerber zip అప్‌లోడ్ చేయండి. బోర్డ్ ఎంపికలు మామూలుగానే.',
        '3. కిందికి జరిపి "PCB Assembly" ఆన్ చేయండి. Side: Top. (Economic చాలు.)',
        '4. "Next" తర్వాత BOM ఫైల్, CPL ఫైల్ అప్‌లోడ్ చేయండి.',
        '5. BOM పేజీలో ప్రతి భాగం ఫోటో, విలువ చూడండి. LCSC నంబర్ లేనివి మీరే వెతికి ఎంచుకోండి. మీరు టంకం చేసే కాళ్ళ భాగాలు అక్కడ ఉండవు: అది సరే.',
        '6. Placement (బొమ్మ) పేజీలో ప్రతి భాగం పిన్ 1 చుక్క / గీత బోర్డ్ మీది గుర్తుతో సరిపోతుందా చూడండి (LED, డయోడ్, 555, ట్రాన్సిస్టర్, రెగ్యులేటర్). తిరగబడి ఉంటే అక్కడే Rotate.',
        '7. ధర చూసి మీరే ఆర్డర్ / పేమెంట్. Jarvis లాగిన్ అవ్వదు, ఆర్డర్ చేయదు, డబ్బులు కట్టదు.',
        '🇮🇳 Lion Circuits / PCB Power: అదే 3 ఫైల్స్ పంపి అసెంబ్లీ ధర అడగొచ్చు.'].forEach((t) => s.appendChild(el('div', 'step', t)));
    });
  }
  function pcbaSave() {
    const sp = F.pcbaSplit(D);
    if (!sp.smd.length) return;
    const go = () => {
      const base = F.slug(D), day = new Date().toISOString().slice(0, 10);
      const files = F.files(D, fills).concat([{ name: 'README-ASSEMBLY.txt', text: F.pcbaReadme(D, base) }]);
      zipKind = 'pcba';
      post({ t: 'csv', name: base + '-BOM-JLCPCB.csv', text: F.jlcBom(D) });
      post({ t: 'csv', name: base + '-CPL-JLCPCB.csv', text: F.jlcCpl(D) });
      post({ t: 'zip', name: base + '-gerber-' + day + '.zip', data: F.b64(F.zip(files)), title: D.name, kind: 'gerber' });
      toast('📦 అసెంబ్లీ ఫైల్స్ (3) సేవ్ చేస్తున్నాను…');
    };
    if (!check.errors) { go(); return; }
    sheet((s) => {
      s.appendChild(el('h2', null, '❌ ఇంకా ' + check.errors + ' తప్పులు ఉన్నాయి'));
      s.appendChild(el('div', 'muted', 'ఇలాగే ఆర్డర్ చేస్తే బోర్డ్ పనిచేయకపోవచ్చు. ముందు ✅ చెక్ లో సరిచేయడం మంచిది.'));
      const q = el('div', 'row');
      q.appendChild(btn('✅ చెక్ చూపించు', 'main', () => { closeSheet(); showTab('drc'); }));
      q.appendChild(btn('అయినా సేవ్ చేయి', 'warn', () => { closeSheet(); go(); }));
      s.appendChild(q);
    });
  }

  function fab() {
    const h = clear($('fab'));
    if (!D) return;
    const Fb = C.FABS[D.fab] || C.FABS.jlc;
    const top = el('div', 'card');
    top.appendChild(el('div', null, '🏭 ఈ బోర్డ్ నిజంగా తయారవుతుందా?'));
    top.appendChild(el('div', 'muted', check.errors ? '❌ ఇంకా ' + check.errors + ' తప్పులు ఉన్నాయి (✅ చెక్ చూడండి). సరిచేశాక ఫైల్స్ తయారు చేయండి.'
      : '✅ అవును. కింది బటన్‌తో ఫ్యాక్టరీ ఫైల్స్ (.zip) తయారవుతాయి. ఆ zip ని ' + Fb.name + ' సైట్‌లో మీరే అప్‌లోడ్ చేసి, మీరే డబ్బులు కడితే బోర్డ్‌లు ఇంటికి వస్తాయి. Jarvis లాగిన్ అవ్వదు, డబ్బులు కట్టదు.'));
    h.appendChild(top);
    h.appendChild(el('h3', null, '1️⃣ ఫ్యాక్టరీ'));
    const fr = el('div', 'seg');
    Object.keys(C.FABS).forEach((k) => fr.appendChild(btn(C.FABS[k].name, D.fab === k ? 'on small' : 'small', () => { D.fab = k; changed(); fab(); })));
    h.appendChild(fr);
    h.appendChild(el('div', 'muted', Fb.te));
    // our board against their minimums
    const tb = el('table');
    const hr = el('tr'); ['', 'మన బోర్డ్', Fb.name + ' కనీసం', ''].forEach((t) => hr.appendChild(el('th', null, t))); tb.appendChild(hr);
    const minTrack = Math.min(...D.tracks.map((t) => t.w || 0.4).concat([0.4]));
    const drills = M.pads.filter((p) => p.d).map((p) => p.d).concat(D.vias.map(() => C.RULES.viaDrill));
    const rings = M.pads.filter((p) => p.d).map((p) => (Math.min(p.w, p.h) - p.d) / 2).concat(D.vias.map(() => (C.RULES.via - C.RULES.viaDrill) / 2));
    const codes = new Set(check.list.filter((f) => f.sev === 'err').map((f) => f.code));
    [['దారి వెడల్పు', minTrack, Fb.track, !codes.has('track')], ['దారుల మధ్య దూరం', C.RULES.clr, Fb.space, !codes.has('clear') && !codes.has('short')],
      ['చిన్న రంధ్రం', Math.min(...drills), Fb.drill, !codes.has('drill') && !codes.has('via')], ['రంధ్రం చుట్టూ రాగి', Math.min(...rings), Fb.ring, !codes.has('ring')],
      ['అంచు నుంచి రాగి', C.RULES.edge, Fb.edge, !codes.has('edge')]].forEach((r) => {
      const tr = el('tr');
      tr.appendChild(el('td', null, r[0])); tr.appendChild(el('td', 'num', r2(r[1]) + ' mm')); tr.appendChild(el('td', 'num', r2(r[2]) + ' mm')); tr.appendChild(el('td', null, r[3] ? '✅' : '❌'));
      tb.appendChild(tr);
    });
    h.appendChild(tb);
    h.appendChild(el('div', 'muted', 'ఫ్యాక్టరీ కనీసాలు వాళ్ళ సైట్‌లో 23 Nov 2026 న చూసినవి; మారవచ్చు. మన బోర్డ్ వాటికంటే చాలా వెడల్పుగా గీశాం (చేతితో టంకం సులభం).'));
    h.appendChild(el('h3', null, '2️⃣ ఆర్డర్ ముందు చూసుకోవాల్సినవి'));
    const ver = check.list.filter((f) => f.code === 'verify');
    const ck = [[!check.errors, '✅ చెక్ లో తప్పులు లేవు'], [true, '📄 భాగాల జాబితా (కింద) చూసి భాగాలు కొనండి'],
      [!ver.length, ver.length ? '📏 ఈ భాగాల కొలతలు మీరు కొనేదానితో స్కేల్‌తో సరిచూడండి:' : '📏 కొలిచి చూడాల్సిన భాగాలు లేవు']];
    const sl = simLine();
    ck.forEach((c, i) => {
      h.appendChild(el('div', 'step', (c[0] ? '' : '👉 ') + c[1]));
      if (i === 1 && sl) { const d = el('div', 'step' + (sl.ok ? '' : ' warnc'), (sl.ok ? '' : '👉 ') + sl.te); d.addEventListener('click', () => showTab('sim')); h.appendChild(d); }
    });
    ver.forEach((f) => h.appendChild(el('div', 'step warnc', f.te)));
    moduleLines().forEach((t, i) => h.appendChild(el('div', i ? 'step warnc' : 'step', t)));
    h.appendChild(el('h3', null, '3️⃣ ఫ్యాక్టరీ ఫైల్స్ (.zip)'));
    const opts = el('div', 'muted', '2 పొరలు · ' + D.board.w + ' × ' + D.board.h + ' mm · ' + D.board.thick + ' mm · ' + D.board.cu + ' oz రాగి · రంగు: ' + ({ green: 'ఆకుపచ్చ', blue: 'నీలం', black: 'నలుపు', white: 'తెలుపు', purple: 'ఊదా' }[D.board.color] || D.board.color) + '. లోపల: Gerber (' + (M.pads.some((q) => q.smd) ? 7 : 6) + '), డ్రిల్, BOM, భాగాల స్థానాలు (CPL), README.');
    h.appendChild(opts);
    const r = el('div', 'row');
    const make = () => {
      const files = F.files(D, fills), z = F.zip(files), name = F.slug(D) + '-gerber-' + new Date().toISOString().slice(0, 10) + '.zip';
      zipKind = 'gerber';
      post({ t: 'zip', name, data: F.b64(z), title: D.name, kind: 'gerber' });
      toast('📦 ఫైల్స్ తయారు చేశాను (' + Math.round(z.length / 1024) + ' KB); సేవ్ చేస్తున్నాను…');
    };
    r.appendChild(btn('📦 ఫైల్స్ తయారు చేసి సేవ్ చేయి', 'main', () => {
      if (!check.errors) { make(); return; }
      sheet((s) => {
        s.appendChild(el('h2', null, '❌ ఇంకా ' + check.errors + ' తప్పులు ఉన్నాయి'));
        s.appendChild(el('div', 'muted', 'ఇలాగే ఆర్డర్ చేస్తే బోర్డ్ పనిచేయకపోవచ్చు. ముందు ✅ చెక్ లో సరిచేయడం మంచిది.'));
        const q = el('div', 'row');
        q.appendChild(btn('✅ చెక్ చూపించు', 'main', () => showTab('drc')));
        q.appendChild(btn('అయినా తయారు చేయి', 'warn', () => { closeSheet(); make(); }));
        s.appendChild(q);
      });
    }));
    h.appendChild(r);
    if (saved && saved.kind === 'gerber') {
      const c = el('div', 'card');
      c.appendChild(el('div', 'okc', '💾 సేవ్ అయింది: ' + saved.where));
      const r3 = el('div', 'row');
      r3.appendChild(btn('📤 పంపు / షేర్', '', () => post({ t: 'share' })));
      r3.appendChild(btn('🌐 ' + Fb.name + ' సైట్ తెరువు', 'main', () => post({ t: 'site', fab: D.fab })));
      c.appendChild(r3);
      c.appendChild(el('div', 'muted', 'రెండూ మీరు నొక్కితేనే. సైట్ మీ బ్రౌజర్‌లో తెరుచుకుంటుంది; లాగిన్, అప్‌లోడ్, పేమెంట్ మీరే.'));
      h.appendChild(c);
    }
    pcbaCard(h);
    kicadCard(h);
    const mk = el('div', 'card');
    mk.appendChild(el('div', null, '🛠️ బోర్డ్‌లు ఇంటికి వచ్చాక'));
    mk.appendChild(el('div', 'muted', '🔧 టంకం గైడ్ (అడుగు అడుగుగా, 🔊 తెలుగులో) · 📦 3D ప్రింట్ డబ్బా (STL) · 📏 భాగాలు కొన్నాక అసలు సైజులో పోల్చి చూడండి.'));
    const mr = el('div', 'row');
    mr.appendChild(btn('🔧 టంకం గైడ్', 'small', () => startGuide()));
    mr.appendChild(btn('📷 బోర్డ్ మీద గైడ్', 'small', camOpen));
    mr.appendChild(btn('📦 డబ్బా', 'small', startBox));
    mr.appendChild(btn('📏 అసలు సైజు', 'small', openReal));
    mk.appendChild(mr);
    h.appendChild(mk);
    h.appendChild(el('h3', null, '4️⃣ ' + Fb.name + ' లో ఆర్డర్ (మీరే)'));
    const steps = [
      ['🌐 ' + Fb.name + ' సైట్ తెరువు నొక్కండి (పైన, సేవ్ అయ్యాక వస్తుంది).', 'ఫ్యాక్టరీ హోమ్ పేజీ; "Quote" / "Order" లాంటి బటన్.', 'సైట్ తెరవకపోతే బ్రౌజర్‌లో పేరు వెతకండి'],
      ['Gerber / zip అప్‌లోడ్ బటన్ నొక్కి, Downloads → Jarvis → pcb లోని ఈ .zip ఎంచుకోండి.', 'మీ బోర్డ్ బొమ్మ, సైజు ' + D.board.w + ' × ' + D.board.h + ' mm, 2 Layers.', 'సైజు వేరుగా వస్తే ఆగి నాకు స్క్రీన్‌షాట్ పంపండి'],
      ['Layers 2, Thickness ' + D.board.thick + ' mm, Qty (సాధారణంగా 5), రంగు మీ ఇష్టం. మిగతావి మార్చకండి.', 'ధర, ఎన్ని రోజుల్లో వస్తుందో.', 'ఏది ఎంచుకోవాలో తెలియకపోతే స్క్రీన్‌షాట్ పంపండి'],
      ['అడ్రస్, డెలివరీ, పేమెంట్: మీరే చూసి మీరే చేయండి.', 'ఆర్డర్ నంబర్.', 'Jarvis డబ్బులు కట్టదు']
    ];
    steps.forEach((s2, i) => {
      const d = el('div', 'step');
      d.appendChild(el('div', null, '👉 అడుగు ' + (i + 1) + ': ' + s2[0]));
      d.appendChild(el('div', 'muted', 'మీకు కనిపించేది: ' + s2[1] + ' (' + s2[2] + ')'));
      h.appendChild(d);
    });
    buyList(h);
  }

  // ================================================================ 🛒 where to buy (A: a search in the shop he picks; B: pages looked up on 24 Nov)

  let shop = 'robu';
  function buyList(h) {
    const B = window.PcbBuy;
    const pr = D.proj && J.PROJECTS.find((q) => q.id === D.proj);
    h.appendChild(el('h3', null, '📄 కొనాల్సిన భాగాలు · 🛒 ఎక్కడ దొరుకుతాయి'));
    h.appendChild(el('div', 'muted', '🛒 = ఎంచుకున్న షాప్‌లో ఆ భాగం కోసం వెతికిన పేజీ. 🔗 = ' + B.CHECKED + ' న మేము చూసిన ఆ భాగం పేజీ (ధర, స్టాక్ మారవచ్చు). రెండూ మీరు నొక్కితేనే మీ బ్రౌజర్‌లో తెరుచుకుంటాయి.'));
    const sr = el('div', 'seg');
    B.SHOPS.forEach((q) => {
      const b = btn(q[1], shop === q[0] ? 'on small' : 'small', () => { shop = q[0]; sr.querySelectorAll('.btn').forEach((x) => x.classList.toggle('on', x === b)); });
      sr.appendChild(b);
    });
    h.appendChild(sr);
    const rows = buyRows();
    drawerBar(h, rows);
    // ✅ (26 Nov, "8. కొనే చెక్‌లిస్ట్"): he ticks what he has; kept with this board
    const got = () => rows.filter((r) => (D.buy && D.buy[r.key]) || covered(r)).length;
    const prog = el('div', 'card');
    const pt0 = el('div');
    const bar = el('div'); bar.style.cssText = 'height:8px;border-radius:4px;background:var(--line);margin-top:6px;overflow:hidden';
    const fill = el('div'); fill.style.cssText = 'height:100%;background:#43a047;width:0';
    bar.appendChild(fill);
    const showProg = () => { const n = got(); pt0.textContent = (n === rows.length ? '🎉 అన్నీ దొరికాయి: ' : '✅ కొనే చెక్‌లిస్ట్: ') + n + ' / ' + rows.length + (n === rows.length ? '. ఇక 🔧 టంకం!' : ' దొరికాయి (కొన్నది / ఇంట్లో ఉన్నది ⬜ నొక్కి ✅ చేయండి)'); fill.style.width = Math.round(100 * n / Math.max(1, rows.length)) + '%'; };
    prog.appendChild(pt0); prog.appendChild(bar);
    h.appendChild(prog);
    showProg();
    rows.forEach((r) => {
      const it = el('div', 'item');
      const tick = btn(D.buy && D.buy[r.key] ? '✅' : '⬜', 'small', () => {
        D.buy = Object.assign({}, D.buy || {});
        if (D.buy[r.key]) delete D.buy[r.key]; else D.buy[r.key] = 1;
        tick.textContent = D.buy[r.key] ? '✅' : '⬜';
        it.style.opacity = D.buy[r.key] ? '0.55' : '';
        showProg(); saveSoon();
      });
      tick.style.flex = 'none';
      it.appendChild(tick);
      if ((D.buy && D.buy[r.key]) || covered(r)) it.style.opacity = '0.55';
      const x = el('div', 'x');
      x.appendChild(el('div', null, r.te + '  × ' + r.n));
      if (r.refs) x.appendChild(el('div', 'f', r.refs));
      const hv = haveOf(r);
      if (hv > 0) { const hl = el('div', 'f', hv >= r.n ? '🧰 ఇంట్లో ' + hv + ' ఉన్నాయి: కొనక్కర్లేదు' : '🧰 ఇంట్లో ' + hv + ' ఉన్నాయి · ఇంకా ' + (r.n - hv) + ' కొనాలి'); hl.style.color = hv >= r.n ? '#66bb6a' : '#ffb74d'; x.appendChild(hl); }
      if (r.tip) x.appendChild(el('div', 'f', r.tip));
      if (r.pages.length) {
        const lk = el('div', 'seg');
        lk.style.marginTop = '4px';
        r.pages.forEach((pg) => lk.appendChild(btn('🔗 ' + pg[0] + (pg[2] ? ' · ' + pg[2] : ''), 'small', () => post({ t: 'shop', url: pg[1] }))));
        x.appendChild(lk);
      }
      it.appendChild(x);
      if (!r.noShop) it.appendChild(btn('🛒', 'small', () => post({ t: 'shopsearch', shop, q: r.q })));
      if (r.dk) it.appendChild(btn('🧰', 'small', () => countSheet(r.dk, r.te, r.q, () => fab())));
      h.appendChild(it);
    });
    const kc = el('div', 'card');
    kc.appendChild(el('div', null, '🧰 కిట్‌లు: ఒకసారి కొంటే తర్వాతి బోర్డ్‌లకి కూడా సరిపోతాయి'));
    B.KITS.forEach((k) => {
      const r = el('div', 'row');
      r.appendChild(el('div', 'k', k.te));
      k.pages.forEach((pg) => r.appendChild(btn('🔗 ' + pg[0], 'small', () => post({ t: 'shop', url: pg[1] }))));
      r.appendChild(btn('🛒', 'small', () => post({ t: 'shopsearch', shop, q: k.q })));
      kc.appendChild(r);
    });
    h.appendChild(kc);
    const r4 = el('div', 'row');
    r4.appendChild(btn('📋 జాబితా పంపు (దుకాణానికి / మీకు)', '', () => {
      const lines = ['🔌 ' + D.name + ': కొనాల్సిన భాగాలు'];
      rows.filter((r) => !r.noShop).forEach((r) => lines.push('• ' + r.q + ' × ' + r.n + (r.refs ? ' (' + r.refs + ')' : '')));
      lines.push('(Jarvis PCB స్టూడియో)');
      post({ t: 'sharetext', text: lines.join('\n') });
    }));
    r4.appendChild(btn('📋 ఇంకా కొనాల్సినవి మాత్రమే', '', () => {
      const left = rows.filter((r) => !(D.buy && D.buy[r.key]) && !covered(r));
      if (!left.length) { toast('🎉 అన్నీ దొరికాయి: కొనాల్సింది ఏమీ లేదు'); return; }
      const lines = ['🔌 ' + D.name + ': ఇంకా కొనాల్సినవి (' + left.length + ')'];
      left.forEach((r) => lines.push('• ' + (r.q || r.te) + ' × ' + Math.max(1, r.n - haveOf(r)) + (r.refs ? ' (' + r.refs + ')' : '')));
      lines.push('(Jarvis PCB స్టూడియో)');
      post({ t: 'sharetext', text: lines.join('\n') });
    }));
    h.appendChild(r4);
    h.appendChild(el('div', 'muted', '💡 రెసిస్టర్లు, LED లు సాధారణంగా 10 / 50 / 100 ప్యాక్‌లలో వస్తాయి. కొనే ముందు పేజీలో విలువ, సైజు ఒకసారి చూడండి. ' + (pr ? '(' + pr.cost + ') ' : '') +
      'కొనడం, పేమెంట్ మీరే; Jarvis కార్ట్‌లో పెట్టదు, కొనదు.'));
  }
  // ================================================================ 🧰 his parts drawer (Anil, 28 Nov: "E చేయి"): what he has at home, ₹0

  /** A part's place in the drawer: its kind and value (an LED: its colour), the same on every board. */
  function dKey(pt) {
    const lib = String(pt.lib || '');
    if (/^led[35]$/.test(lib)) return 'p:' + lib + '|' + String(pt.color || pt.val || '').toUpperCase().replace(/[^A-Z]/g, '');
    return 'p:' + lib + '|' + String(pt.val || '').replace(/\(.*?\)/g, '').replace(/\s+/g, '').toUpperCase();
  }
  function rowKey(r) { return r.dk || (/^x:/.test(r.key) && r.key !== 'x:pcb' ? r.key : ''); }
  function haveOf(r) { const k = rowKey(r), x = k && drawer.items[k]; return x ? Math.max(0, x.n | 0) : 0; }
  function covered(r) { return r.n > 0 && haveOf(r) >= r.n; }
  function saveDrawer() { post({ t: 'drawer', v: drawer }); }
  function drawerTotals() { const ks = Object.keys(drawer.items).filter((k) => (drawer.items[k].n | 0) > 0); return { kinds: ks.length, pieces: ks.reduce((s, k) => s + (drawer.items[k].n | 0), 0) }; }
  function setHave(k, te, q, n) {
    n = Math.max(0, Math.min(99999, Math.round(n) || 0));
    if (!k) return;
    if (n === 0) delete drawer.items[k];
    else drawer.items[k] = { te: String(te || (drawer.items[k] || {}).te || k).slice(0, 80), n, q: String(q || (drawer.items[k] || {}).q || '').slice(0, 100) };
    saveDrawer();
  }
  function drawerCard(h) {
    const t = drawerTotals();
    const c = el('div', 'card tap');
    c.appendChild(el('div', 't', '🧰 నా పార్ట్స్ డబ్బా (₹0)'));
    c.appendChild(el('div', 'muted', t.kinds ? 'ఇంట్లో ' + t.kinds + ' రకాలు, ' + t.pieces + ' ముక్కలు · ఏ బోర్డ్‌కైనా ఇంకా ఏం కొనాలో 🏭 కొనే లిస్ట్‌లో చూపిస్తుంది'
      : 'ఇంట్లో ఉన్న LED, రెసిస్టర్, మాడ్యూల్స్ ఒకసారి ఇక్కడ రాసుకోండి: ప్రతి బోర్డ్‌కి ఇంకా ఏం కొనాలో అదే చెబుతుంది.'));
    c.addEventListener('click', () => drawerSheet());
    h.appendChild(c);
  }
  /** On the buy list: the drawer's line, and "these are all in / I used these". */
  function drawerBar(h, rows) {
    const t = drawerTotals(), parts = rows.filter((r) => rowKey(r));
    const c = el('div', 'card');
    const cov = parts.filter(covered).length;
    c.appendChild(el('div', null, '🧰 నా పార్ట్స్ డబ్బా: ' + (t.kinds ? t.kinds + ' రకాలు · ఈ బోర్డ్‌కి ' + cov + ' / ' + parts.length + ' ఇంట్లోనే ఉన్నాయి' : 'ఇంకా ఖాళీ')));
    const r = el('div', 'row');
    r.appendChild(btn('🧰 డబ్బా తెరువు', 'small', () => drawerSheet()));
    r.appendChild(btn('➕ ఇవన్నీ కొన్నాను', 'small', () => confirmSheet('➕ ఈ బోర్డ్ భాగాలన్నీ డబ్బాలో పెట్టనా?', 'కొన్న తర్వాత నొక్కండి: ప్రతి భాగం ఎన్ని కావాలో అన్ని డబ్బాలో కలుస్తాయి.', () => {
      parts.forEach((x) => setHave(rowKey(x), x.te, x.q, haveOf(x) + x.n)); toast('🧰 డబ్బాలో పెట్టాను: ' + parts.length + ' రకాలు'); fab();
    })));
    r.appendChild(btn('➖ ఈ బోర్డ్‌కి వాడాను', 'small', () => confirmSheet('➖ ఈ బోర్డ్‌కి వాడినవి డబ్బా నుంచి తీసేయనా?', 'టంకం చేశాక నొక్కండి: ప్రతి భాగం ఎన్ని వాడారో అన్ని తగ్గుతాయి.', () => {
      parts.forEach((x) => { const hv = haveOf(x); if (hv) setHave(rowKey(x), x.te, x.q, hv - x.n); }); toast('🧰 డబ్బా నుంచి తీసేశాను'); fab();
    })));
    c.appendChild(r);
    h.appendChild(c);
  }
  function confirmSheet(title, line, yes) {
    sheet((s) => {
      s.appendChild(el('h2', null, title));
      s.appendChild(el('div', 'step', line));
      const r = el('div', 'row');
      r.appendChild(btn('✅ అవును', 'main', () => { closeSheet(); yes(); }));
      r.appendChild(btn('వద్దు', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** How many of one thing he has (− / + / type it). */
  function countSheet(k, te, q, after) {
    let n = (drawer.items[k] || {}).n | 0;
    sheet((s) => {
      s.appendChild(el('h2', null, '🧰 ' + te));
      s.appendChild(el('div', 'muted', 'ఇంట్లో ఎన్ని ఉన్నాయి? (0 = డబ్బా నుంచి తీసేయి)'));
      const r = el('div', 'row');
      const box = el('input'); box.type = 'number'; box.min = '0'; box.max = '99999'; box.value = String(n); box.style.cssText = 'width:90px;font-size:20px;text-align:center;background:#12151c;color:#e8eef8;border:1px solid #3a4352;border-radius:8px;padding:6px 8px;box-sizing:border-box';
      box.addEventListener('input', () => { n = Math.max(0, parseInt(box.value, 10) || 0); });
      r.appendChild(btn('−', '', () => { n = Math.max(0, n - 1); box.value = String(n); }));
      r.appendChild(box);
      r.appendChild(btn('+', '', () => { n += 1; box.value = String(n); }));
      [5, 10].forEach((d) => r.appendChild(btn('+' + d, 'small', () => { n += d; box.value = String(n); })));
      s.appendChild(r);
      const r2 = el('div', 'row');
      r2.appendChild(btn('💾 దాచు', 'main', () => { setHave(k, te, q, n); closeSheet(); toast(n ? '🧰 ' + te + ': ' + n : '🧰 ' + te + ' డబ్బా నుంచి తీసేశాను'); if (after) after(); }));
      r2.appendChild(btn('వద్దు', '', closeSheet));
      s.appendChild(r2);
    });
  }
  /** Kinds he can add by hand (the studio's parts and modules), with a value box where a value matters. */
  function addKinds() {
    const out = [];
    Object.keys(P.LIB).forEach((k) => { const L = P.LIB[k]; if (L && L.te && !/^(term2|hdr|fhdr)$/.test(k)) out.push([k, L.te]); });
    out.push(['x:hdr', 'మేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)'], ['x:fhdr', 'ఫిమేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)'], ['x:ff', 'జంపర్ వైర్లు ఫిమేల్–ఫిమేల్ (40 ప్యాక్)'], ['term2', '2 పిన్ స్క్రూ టెర్మినల్']);
    return out;
  }
  function drawerSheet() {
    sheet((s) => {
      const t = drawerTotals();
      s.appendChild(el('h2', null, '🧰 నా పార్ట్స్ డబ్బా'));
      s.appendChild(el('div', 'muted', 'ఇంట్లో ఉన్నవి (పేర్లు, ఎన్ని: మీ ఫోన్‌లోనే). ' + (t.kinds ? t.kinds + ' రకాలు, ' + t.pieces + ' ముక్కలు.' : 'ఇంకా ఖాళీ: కింద ➕ తో రాసుకోండి, లేదా 🏭 కొనే లిస్ట్‌లో "➕ ఇవన్నీ కొన్నాను".')));
      const keys = Object.keys(drawer.items).filter((k) => (drawer.items[k].n | 0) > 0).sort((a, b) => String(drawer.items[a].te).localeCompare(String(drawer.items[b].te)));
      keys.forEach((k) => {
        const x = drawer.items[k];
        const it = el('div', 'item');
        const xx = el('div', 'x');
        xx.appendChild(el('div', null, x.te));
        if (x.q) xx.appendChild(el('div', 'f', x.q));
        it.appendChild(xx);
        it.appendChild(btn('−', 'small', () => { setHave(k, x.te, x.q, (x.n | 0) - 1); drawerSheet(); }));
        it.appendChild(el('div', 'k', String(x.n)));
        it.appendChild(btn('+', 'small', () => { setHave(k, x.te, x.q, (x.n | 0) + 1); drawerSheet(); }));
        it.appendChild(btn('✏️', 'small', () => countSheet(k, x.te, x.q, drawerSheet)));
        s.appendChild(it);
      });
      // ➕ by hand: a kind, its value (LED: colour), how many
      const add = el('div', 'card');
      add.appendChild(el('div', null, '➕ కొత్తది రాసుకో'));
      const sel = el('select'); sel.style.cssText = 'width:100%;font-size:16px;margin:6px 0;background:#12151c;color:#e8eef8;border:1px solid #3a4352;border-radius:8px;padding:6px 8px;box-sizing:border-box';
      addKinds().forEach((q) => { const o = el('option', null, q[1]); o.value = q[0]; sel.appendChild(o); });
      const val = el('input'); val.placeholder = 'విలువ: 1k / 100uF 25V / RED (LED రంగు)'; val.style.cssText = 'width:100%;font-size:16px;margin:4px 0;background:#12151c;color:#e8eef8;border:1px solid #3a4352;border-radius:8px;padding:6px 8px;box-sizing:border-box';
      const cnt = el('input'); cnt.type = 'number'; cnt.min = '1'; cnt.value = '10'; cnt.style.cssText = 'width:90px;font-size:16px;margin:4px 0;background:#12151c;color:#e8eef8;border:1px solid #3a4352;border-radius:8px;padding:6px 8px;box-sizing:border-box';
      add.appendChild(sel); add.appendChild(val); add.appendChild(cnt);
      add.appendChild(btn('💾 డబ్బాలో పెట్టు', 'main', () => {
        const lib = sel.value, v = val.value.trim(), n = Math.max(1, parseInt(cnt.value, 10) || 1);
        let k, te, q;
        if (/^x:/.test(lib)) { k = lib; te = sel.options[sel.selectedIndex].textContent; q = ''; }
        else {
          const pt = /^led[35]$/.test(lib) ? { lib, color: v || 'RED', val: (v || 'RED').toUpperCase() } : { lib, val: v };
          k = dKey(pt); te = (P.LIB[lib] || {}).te + (v ? ' · ' + v : ''); q = window.PcbBuy ? window.PcbBuy.query(pt) : '';
        }
        setHave(k, te, q, ((drawer.items[k] || {}).n | 0) + n);
        toast('🧰 ' + te + ': ' + ((drawer.items[k] || {}).n | 0));
        drawerSheet();
      }));
      s.appendChild(add);
      s.appendChild(el('div', 'muted', '💡 ఒకే విలువ వేరేగా రాస్తే (1k / 1K / 1000) వేరుగా లెక్కపెడుతుంది: బోర్డ్‌లో ఉన్నట్టే రాయండి, లేదా 🏭 కొనే లిస్ట్‌లో ప్రతి భాగం పక్క 🧰 నొక్కండి.'));
      const r = el('div', 'row');
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** Open 🏭 at the checklist. */
  function buyGo() {
    showTab('fab');
    setTimeout(() => { const f = $('fab'), h3 = Array.from(f.querySelectorAll('h3')).find((x) => /కొనాల్సిన భాగాలు/.test(x.textContent)); if (h3) f.scrollTop = h3.offsetTop - 8; }, 60);
  }
  /**
   * Every line to buy for this board: its parts (same kind and value together), the ready board's extras, and for any other board
   * the extras it needs (header strips, jumper wires, IC socket) and the PCB itself. key: stable, for the ✅ ticks.
   */
  function buyRows() {
    const B = window.PcbBuy, pr = D.proj && J.PROJECTS.find((q) => q.id === D.proj), rows = [];
    const g = new Map();
    D.parts.forEach((pt) => { const k = pt.lib + '|' + pt.val + '|' + (pt.color || ''); if (!g.has(k)) g.set(k, { pt, refs: [], k }); g.get(k).refs.push(pt.ref); });
    g.forEach((v) => rows.push({ key: 'p:' + v.k, dk: dKey(v.pt), te: ((P.LIB[v.pt.lib] || {}).te || v.pt.lib) + (v.pt.val ? ' · ' + v.pt.val : ''), refs: v.refs.join(', '), n: v.refs.length, q: B.query(v.pt), pages: B.pages(B.key(v.pt)), tip: sizeTip(v.pt) }));
    if (pr) ((pr.extra || [])).forEach((x) => rows.push({ key: 'x:' + x.te, te: x.te + (x.val ? ' · ' + x.val : ''), refs: x.why || '', n: x.n || 1, q: x.q, pages: B.pages(x.k) }));
    else {
      // what the parts need that is not a part on the board
      let fem = 0, male = 0, ff = 0;
      const fRefs = [], mRefs = [];
      D.parts.forEach((pt) => {
        const L = P.LIB[pt.lib] || {}, n = Object.keys(P.pinsOf(pt)).length;
        if (pt.lib === 'esp32') { fem += 30; fRefs.push(pt.ref); }
        else if (pt.lib === 'fhdr' || (L.mod && L.kind === 'plug')) { fem += n; fRefs.push(pt.ref); }
        else if (/^(hdr|buck|pam8403|tp4056)$/.test(pt.lib) || (L.mod && L.kind === 'wire')) { male += n; mRefs.push(pt.ref); ff += n; }
      });
      if (fem) rows.push({ key: 'x:fhdr', te: 'ఫిమేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)', refs: fRefs.join(', ') + ': ' + fem + ' పిన్నులు కట్ చేయండి', n: Math.ceil(fem / 40), q: 'female berg strip 2.54mm 40 pin', pages: B.pages('fhdr') });
      if (male) rows.push({ key: 'x:hdr', te: 'మేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)', refs: mRefs.join(', ') + ': ' + male + ' పిన్నులు కట్ చేయండి', n: Math.ceil(male / 40), q: 'male berg strip 2.54mm', pages: B.pages('hdr') });
      if (ff) rows.push({ key: 'x:ff', te: 'జంపర్ వైర్లు ఫిమేల్–ఫిమేల్ (40 ప్యాక్)', refs: ff + ' వైర్లు కావాలి (🔌 వైర్ పట్టీ)', n: Math.ceil(ff / 40), q: 'female to female jumper wires 40 pcs', pages: [] });
      if (D.parts.some((pt) => pt.lib === 'ne555')) rows.push({ key: 'x:socket8', te: 'IC సాకెట్ 8 పిన్', refs: 'NE555 కింద', n: D.parts.filter((pt) => pt.lib === 'ne555').length, q: '8 pin IC socket', pages: B.pages('socket8') });
    }
    rows.push({ key: 'x:pcb', te: '🏭 PCB బోర్డ్ (ఫ్యాక్టరీ నుంచి, సాధారణంగా 5 ముక్కలు)', refs: D.board.w + ' × ' + D.board.h + ' mm · 🏭 ఫైల్స్ పైన', n: 1, q: '', pages: [], noShop: true });
    return rows;
  }
  /** The size the board expects (electrolytic capacitors come in many sizes). */
  function sizeTip(pt) {
    const f = P.fpOf(pt);
    if (!f || !f.body) return '';
    if (f.body.t === 'radial') {
      const pd = f.pads.length > 1 ? Math.abs(f.pads[1].x - f.pads[0].x) : 0;
      return '📏 బోర్డ్ మీద చోటు: వ్యాసం ' + f.body.D + ' mm వరకు, కాళ్ళ దూరం ' + r2(pd) + ' mm. వోల్టేజ్ ' + (String(pt.val).match(/\d+\s*V/) || ['?'])[0] + ' లేదా ఎక్కువ.';
    }
    if (f.verify) return '📏 ' + f.verify + '.';
    return '';
  }

  // ================================================================ 🧮 the numbers

  const E12 = [1.0, 1.2, 1.5, 1.8, 2.2, 2.7, 3.3, 3.9, 4.7, 5.6, 6.8, 8.2];
  function e12up(v) {
    if (!(v > 0)) return NaN;
    const e = Math.floor(Math.log10(v)), m = v / Math.pow(10, e);
    for (const x of E12) if (x >= m - 1e-9) return x * Math.pow(10, e);
    return 10 * Math.pow(10, e);
  }
  function e12near(v) {
    if (!(v > 0)) return NaN;
    const e = Math.floor(Math.log10(v));
    let best = null;
    for (const k of [e - 1, e, e + 1]) for (const x of E12) { const c = x * Math.pow(10, k); if (!best || Math.abs(Math.log(c / v)) < Math.abs(Math.log(best / v))) best = c; }
    return best;
  }
  function ohm(v) { return v >= 1e6 ? r2(v / 1e6) + 'M' : v >= 1000 ? r2(v / 1000) + 'k' : r2(v) + ''; }
  function calc() {
    const h = clear($('calc'));
    h.appendChild(el('div', 'muted', 'అన్నీ ఫోన్‌లోనే లెక్కిస్తుంది (₹0). విలువలు మార్చగానే జవాబు మారుతుంది.'));
    const box = (title, inputs, out) => {
      const c = el('div', 'card');
      c.appendChild(el('div', null, title));
      const ins = inputs.map((i) => {
        const r = el('div', 'row');
        r.appendChild(el('div', 'k muted', i[0]));
        let inp;
        if (i[2] && Array.isArray(i[2])) { inp = el('select'); i[2].forEach((o) => { const op = el('option', null, o[0]); op.value = o[1]; inp.appendChild(op); }); inp.value = i[1]; }
        else if (i[2] === 'text') { inp = el('input'); inp.type = 'text'; inp.value = i[1]; inp.maxLength = 12; }
        else { inp = el('input'); inp.type = 'number'; inp.step = 'any'; inp.value = i[1]; inp.inputMode = 'decimal'; }
        r.appendChild(inp);
        c.appendChild(r);
        return inp;
      });
      const res = el('div', 'step');
      c.appendChild(res);
      const run = () => { clear(res); out(ins.map((x) => parseFloat(x.value)), res, ins); };
      ins.forEach((x) => x.addEventListener('input', () => { clearTimeout(calcT); calcT = setTimeout(run, 150); }));
      run();
      h.appendChild(c);
    };
    box('💡 LED కి రెసిస్టర్ ఎంత?', [['సప్లై వోల్టేజ్ (V)', 9], ['LED రంగు', '2.0', [['ఎరుపు (2.0 V)', '2.0'], ['ఆకుపచ్చ / పసుపు (2.1 V)', '2.1'], ['నీలం / తెలుపు (3.0 V)', '3.0'], ['IR (1.3 V)', '1.3']]], ['కరెంట్ (mA, మామూలు 10–20)', 15]], (v, o) => {
      const [vs, vf, ma] = v;
      if (!(vs > vf) || !(ma > 0)) { o.textContent = 'సప్లై LED వోల్టేజ్ కంటే ఎక్కువ ఉండాలి.'; return; }
      const r = (vs - vf) / (ma / 1000), std = e12up(r), p = Math.pow(ma / 1000, 2) * std, bs = P.bands(ohm(std));
      o.appendChild(el('div', 'big', ohm(std) + ' Ω'));
      o.appendChild(el('div', 'muted', 'లెక్క: (' + vs + ' − ' + vf + ') ÷ ' + ma + ' mA = ' + Math.round(r) + ' Ω → దగ్గరి దుకాణం విలువ (పైది) ' + ohm(std) + ' Ω. వేడి ' + Math.round(p * 1000) + ' mW → ' + (p < 0.125 ? '¼ W సరిపోతుంది.' : p < 0.25 ? '½ W తీసుకోండి.' : '1 W లేదా ఎక్కువ.')));
      if (bs) { const row = el('div', 'bands'), body = el('div', 'body'); bs.forEach((b) => { const i = el('i'); i.style.background = b.c; body.appendChild(i); }); row.appendChild(body); o.appendChild(row); }
    });
    box('🛤️ కరెంట్‌కి దారి వెడల్పు (IPC-2221, బయటి పొర)', [['కరెంట్ (A)', 2], ['వేడి పెరుగుదల (°C)', 10], ['రాగి', '1', [['1 oz (మామూలు)', '1'], ['2 oz', '2']]]], (v, o) => {
      const w = C.widthFor(v[0], v[1], v[2]);
      if (!(w > 0)) { o.textContent = 'కరెంట్ ఇవ్వండి.'; return; }
      o.appendChild(el('div', 'big', r2(Math.max(w, 0.15)) + ' mm'));
      o.appendChild(el('div', 'muted', v[0] + ' A కి కనీసం ' + r2(w) + ' mm (' + Math.round(w / 0.0254) + ' mil). మన బోర్డ్‌లో ఈ వెడల్పు కంటే వెడల్పుగా ' + (v[0] > 3 ? '2 oz లేదా ఇంకా వెడల్పు దారి వాడండి.' : 'గీస్తే చల్లగా ఉంటుంది.')));
    });
    box('🔋 బ్యాటరీ ఎంతసేపు వస్తుంది?', [['బ్యాటరీ (mAh)', 500], ['సర్క్యూట్ కరెంట్ (mA)', 20]], (v, o) => {
      if (!(v[0] > 0) || !(v[1] > 0)) { o.textContent = 'రెండు విలువలు ఇవ్వండి.'; return; }
      const hrs = v[0] / v[1] * 0.8;
      o.appendChild(el('div', 'big', hrs >= 48 ? r2(hrs / 24) + ' రోజులు' : r2(hrs) + ' గంటలు'));
      o.appendChild(el('div', 'muted', v[0] + ' ÷ ' + v[1] + ' × 0.8 (బ్యాటరీ పూర్తిగా ఇవ్వదు) = ' + r2(hrs) + ' గంటలు. 9V బ్యాటరీ సుమారు 400–550 mAh.'));
    });
    box('➗ వోల్టేజ్ డివైడర్', [['Vin (V)', 12], ['R1 (పైది, Ω)', 10000], ['R2 (కిందది, Ω)', 4700], ['కావాల్సిన Vout (V, ఐచ్ఛికం)', 3.3]], (v, o) => {
      const [vin, a, b, want] = v;
      if (!(a > 0) || !(b > 0)) { o.textContent = 'R1, R2 ఇవ్వండి.'; return; }
      const vout = vin * b / (a + b);
      o.appendChild(el('div', 'big', r2(vout) + ' V'));
      o.appendChild(el('div', 'muted', 'Vout = Vin × R2 ÷ (R1 + R2) = ' + vin + ' × ' + ohm(b) + ' ÷ ' + ohm(a + b) + '. కరెంట్ ' + r2(vin / (a + b) * 1000) + ' mA.'));
      if (want > 0 && want < vin) { const r = e12near(a * want / (vin - want)); o.appendChild(el('div', 'muted', '🎯 ' + want + ' V కావాలంటే R1 = ' + ohm(a) + ' తో R2 ≈ ' + ohm(r) + ' Ω (→ ' + r2(vin * r / (a + r)) + ' V).')); }
      o.appendChild(el('div', 'muted', '⚠️ ఇది సిగ్నల్ / కొలతలకి మాత్రమే; దీనితో పవర్ (మోటార్, LED లు) ఇవ్వకూడదు.'));
    });
    box('🎨 రెసిస్టర్ రంగు గీతలు', [['విలువ (ఉదా: 4.7k, 330, 100k)', '4.7k', 'text']], (v, o, ins) => {
      const s = String(ins[0].value || '').trim();
      if (!s) { o.textContent = 'విలువ రాయండి; గీతల రంగులు చూపిస్తాను.'; return; }
      const bs = P.bands(s);
      if (!bs) { o.textContent = 'అర్థం కాలేదు: 470, 4.7k, 1M లాగా రాయండి.'; return; }
      const row = el('div', 'bands'), body = el('div', 'body'); bs.forEach((b) => { const i = el('i'); i.style.background = b.c; body.appendChild(i); }); row.appendChild(body); o.appendChild(row);
      o.appendChild(el('div', 'muted', bs.map((b) => b.te).join(' → ')));
    });
    box('⏱️ 555 టైమర్ (LED ఫ్లాషర్) వేగం', [['R1 (Ω)', 1000], ['R2 (Ω)', 47000], ['C (µF)', 10]], (v, o) => {
      const [a, b, c] = v;
      if (!(a > 0) || !(b > 0) || !(c > 0)) { o.textContent = 'మూడు విలువలు ఇవ్వండి.'; return; }
      const C1 = c / 1e6, hi = 0.693 * (a + b) * C1, lo = 0.693 * b * C1, f = 1 / (hi + lo);
      o.appendChild(el('div', 'big', r2(f) + ' సార్లు / సెకను'));
      o.appendChild(el('div', 'muted', 'ఆన్ ' + r2(hi) + ' సె, ఆఫ్ ' + r2(lo) + ' సె. f = 1.44 ÷ ((R1 + 2·R2) × C). నెమ్మదిగా కావాలంటే R2 లేదా C పెంచండి.'));
    });
  }

  // ================================================================ 💡 🗣️ 🤖 the studio's AI (build 2): Jarvis asks it, this page only shows and builds

  const ai = { wait: null, job: null, pending: null, help: null, review: null };
  const idea = { text: '', photo: null };
  const dict = { on: false, to: 'idea' };
  const quoteCb = {};
  let quoteT = 0, barT = 0;
  const KIND_TE = { new: 'కొత్త బోర్డ్', photo: 'ఫోటో నుంచి బోర్డ్', edit: 'బోర్డ్‌లో మార్పు', help: 'చెక్ కి సహాయం',
    pins: 'మాడ్యూల్ పిన్ చెక్', solder: 'టంకం చెక్', review: 'ఇంజనీర్ రివ్యూ', scan: 'పాత బోర్డ్ ఫోటో' };
  const DOING = { new: 'బోర్డ్ గీస్తోంది…', photo: 'ఫోటో చూసి గీస్తోంది…', edit: 'మార్పు చేస్తోంది…', help: 'తప్పులు చూస్తోంది…',
    pins: 'పిన్ పేర్లు చదువుతోంది…', solder: 'టంకాలు చూస్తోంది…', review: 'బోర్డ్ రివ్యూ చేస్తోంది…', scan: 'పాత బోర్డ్ చదువుతోంది…' };

  $('mic').addEventListener('click', () => post({ t: 'mic' }));

  /** The cost of one job on his AI (asked from Jarvis, who knows the price list; the page never sees keys). */
  function quote(kind, words, chars, cb) { quoteCb[kind] = cb; post({ t: 'aiquote', kind, words: String(words || ''), chars: chars | 0 }); }
  function quoteLine(box, m, onPick) {
    clear(box);
    const r = el('div', 'quote');
    if (m.model) { r.appendChild(document.createTextNode('🤖 ' + m.model + ' · సుమారు ')); r.appendChild(el('span', 'm', m.money || '?')); }
    else r.appendChild(document.createTextNode('🤖 AI ఎంచుకోలేదు'));
    r.appendChild(document.createTextNode('  '));
    const ch = btn('🔁 మార్చు', 'small', onPick);
    r.appendChild(ch);
    box.appendChild(r);
    if (!m.ok && m.why) box.appendChild(el('div', 'muted warnc', m.why));
    if (m.month) box.appendChild(el('div', 'muted', m.month + ' (వర్క్‌షాప్, స్టూడియోలు అన్నిటికీ కలిపి)'));
  }

  // ---------------------------------------------------------------- the 💡 card on 📚 బోర్డ్‌లు

  const IDEAS = [['🔋 బైక్ బ్యాటరీ అలారం', 'బైక్ 12V బ్యాటరీ 11.5V కంటే తగ్గితే ఎరుపు LED వెలగాలి, బజర్ మోగాలి'], ['🌡️ ESP32 + DHT11 + OLED', 'ESP32 కి DHT11, చిన్న OLED స్క్రీన్ (I2C) కలిపే బోర్డ్, USB 5V తో'],
    ['🚦 ESP32 ట్రాఫిక్ లైట్', 'ESP32 తో మూడు LED ల ట్రాఫిక్ లైట్ (ఎరుపు, పసుపు, ఆకుపచ్చ)'], ['💡 USB LED లైట్', 'USB 5V తో నాలుగు తెల్ల LED ల చిన్న లైట్, ప్రతి LED కి రెసిస్టర్'],
    ['🔔 555 బీప్ బజర్', '9V బ్యాటరీతో 555 టైమర్ బజర్: సెకనుకి ఒకసారి బీప్']];
  function ideaCard(h) {
    const c = el('div', 'card idea');
    c.id = 'ideacard';
    c.appendChild(el('div', 't', '💡 ఐడియాతో కొత్త బోర్డ్ (AI)'));
    c.appendChild(el('div', 'muted', 'బోర్డ్ ఏం చేయాలో రాయండి / 🎤 చెప్పండి, లేదా కాగితం మీద గీసిన సర్క్యూట్ 📷 ఫోటో పెట్టండి. AI భాగాలు, కలుపులు ఇస్తుంది; అమర్చడం, దారులు, చెక్ ఫోన్‌లోనే.'));
    const ta = el('textarea');
    ta.id = 'ideatext'; ta.rows = 3; ta.maxLength = 1500; ta.value = idea.text;
    ta.placeholder = 'ఉదా: బైక్ 12V బ్యాటరీ తగ్గితే ఎరుపు LED వెలగాలి';
    ta.addEventListener('input', () => { idea.text = ta.value; ideaQuoteSoon(); });
    c.appendChild(ta);
    const r = el('div', 'row');
    const mic = btn(dict.on && dict.to === 'idea' ? '🔴 వింటున్నాను… (ఆపు)' : '🎤 మాటతో', dict.on && dict.to === 'idea' ? 'on' : '', () => { dict.to = 'idea'; post({ t: 'dictate' }); });
    mic.id = 'ideamic';
    r.appendChild(mic);
    r.appendChild(btn(idea.photo ? '📷 ఫోటో మార్చు' : '📷 చేతి బొమ్మ ఫోటో', '', photoSheet));
    c.appendChild(r);
    const q = el('div');
    q.id = 'ideaquote';
    c.appendChild(q);
    const go = el('div', 'row');
    const b = btn('⚡ తయారు చేయి', 'main', () => makeIdea(false));
    b.id = 'ideago';
    go.appendChild(b);
    c.appendChild(go);
    if (idea.photo) {
      const ph = el('div', 'photo');
      const img = el('img');
      img.src = idea.photo; img.alt = 'మీ సర్క్యూట్ ఫోటో';
      ph.appendChild(img);
      const x = el('div', 'muted', 'ఈ ఫోటో ⚡ నొక్కాక AI కి ఒక్కసారే వెళ్తుంది; ఫోన్‌లో దాచను. పైన మాటల్లో ఏమైనా చెప్పాలంటే రాయండి (ఐచ్ఛికం).');
      x.appendChild(el('br'));
      x.appendChild(btn('✕ ఫోటో తీసేయి', 'small', () => { idea.photo = null; post({ t: 'photodrop' }); home(); }));
      ph.appendChild(x);
      c.appendChild(ph);
    } else {
      const ex = el('div');
      ex.appendChild(el('span', 'muted', 'ఉదాహరణలు (నొక్కితే పైన రాస్తుంది): '));
      IDEAS.forEach((w) => { const e = el('span', 'ex', w[0]); e.addEventListener('click', () => { idea.text = w[1]; ta.value = w[1]; ideaQuoteSoon(); }); ex.appendChild(e); });
      c.appendChild(ex);
    }
    c.appendChild(el('div', 'muted', 'మీరు నొక్కితేనే AI కి వెళ్తుంది. తక్కువ వోల్టేజ్ DC (24V వరకు) బోర్డ్‌లు మాత్రమే. AI కూడా తప్పు చేయొచ్చు: ఆర్డర్ ముందు 📐 స్కీమాటిక్ ఒకసారి చూడండి.'));
    h.appendChild(c);
    ideaQuote();
  }
  function ideaQuoteSoon() { clearTimeout(quoteT); quoteT = setTimeout(ideaQuote, 450); }
  function ideaQuote() {
    const kind = idea.photo ? 'photo' : 'new';
    const no = idea.text.trim() ? A.refuseWords(idea.text) : null;
    quote(kind, idea.text, 0, (m) => {
      const box = $('ideaquote'), b = $('ideago');
      if (!box || !b) return;
      quoteLine(box, m, () => post({ t: 'pickmodel', kind, words: idea.text, chars: 0 }));
      if (no) box.appendChild(el('div', 'muted err', no));
      b.disabled = !m.ok || !!no || !!ai.job || !!ai.wait;
      b.textContent = m.ok && m.money ? '⚡ తయారు చేయి · ' + m.money : '⚡ తయారు చేయి';
    });
  }
  function toIdea() { const c = $('ideacard'); if (c) $('home').scrollTop = Math.max(0, c.offsetTop - 8); }
  function photoSheet() {
    sheet((s) => {
      s.appendChild(el('h2', null, '📷 కాగితం మీద గీసిన సర్క్యూట్'));
      s.appendChild(el('div', 'muted', 'పెన్నుతో స్పష్టంగా గీసి, పైనుంచి వెలుతురులో ఫోటో తీయండి. భాగాల విలువలు (1k, 10uF…) రాస్తే బాగా అర్థమవుతుంది. ఫోటో ⚡ నొక్కాక మీ AI కి ఒక్కసారే వెళ్తుంది; ఎక్కడా దాచను.'));
      const r = el('div', 'row');
      r.appendChild(btn('📷 కెమెరాతో తీయి', 'main', () => { closeSheet(); post({ t: 'photo', from: 'cam' }); }));
      r.appendChild(btn('🖼️ గ్యాలరీ నుంచి', '', () => { closeSheet(); post({ t: 'photo', from: 'gal' }); }));
      s.appendChild(r);
    });
  }
  function makeIdea(voice) {
    const w = idea.text.trim();
    if (!w && !idea.photo) { toast('ముందు మీ ఐడియా రాయండి / 🎤 చెప్పండి, లేదా 📷 ఫోటో పెట్టండి'); return; }
    const no = A.refuseWords(w);
    if (no) { resultSheet('🚫 చేయలేదు', [no]); if (voice) post({ t: 'vdone', say: no.replace(/\(.*\)$/, ''), voice: true }); return; }
    if (ai.job || ai.wait) { toast('ఒక AI పని ఇంకా నడుస్తోంది; అది అయ్యాక అడగండి.'); return; }
    const kind = idea.photo ? 'photo' : 'new';
    ai.pending = { kind, words: w, voice: !!voice };
    post({ t: 'aiask', kind, words: w, voice: !!voice });
  }

  // ---------------------------------------------------------------- 🎤 dictation into a box

  function dictLook() {
    const b = $('ideamic');
    if (b) { const on = dict.on && dict.to === 'idea'; b.textContent = on ? '🔴 వింటున్నాను… (ఆపు)' : '🎤 మాటతో'; b.classList.toggle('on', on); }
    const e = $('editmic');
    if (e) { const on = dict.on && dict.to === 'edit'; e.textContent = on ? '🔴 వింటున్నాను…' : '🎤'; e.classList.toggle('on', on); }
  }
  function dictInto(w) {
    if (!w) return;
    if (dict.to === 'edit' && $('edittext')) { const t = $('edittext'); t.value = (t.value.trim() ? t.value.trim() + ' ' : '') + w; editHint(); return; }
    idea.text = (idea.text.trim() ? idea.text.trim() + ' ' : '') + w;
    const t = $('ideatext');
    if (t) t.value = idea.text;
    ideaQuoteSoon();
  }

  // ---------------------------------------------------------------- ✏️ a change to the open board (small ones here, ₹0)

  /** Examples with this board's own part names. */
  function editExamples() {
    const first = (f) => D.parts.find(f);
    const res = first((p) => p.lib === 'res'), led = first((p) => p.lib === 'led5' || p.lib === 'led3'), any = first((p) => !/^(term2|usba)$/.test(p.lib));
    const out = [];
    if (res) out.push(res.ref + ' ని ' + (res.val === '10k' ? '22k' : '10k') + ' చేయి');
    if (any) out.push(any.ref + ' ని ఎడమకి 3 mm జరుపు', any.ref + ' తిప్పు');
    if (led) out.push(led.ref + ' ' + (led.color === 'blue' ? 'ఆకుపచ్చ' : 'నీలం') + ' చేయి');
    out.push('బోర్డ్ నీలం రంగు', 'స్క్రూ రంధ్రాలు పెట్టు', 'ఇంకో LED, దాని రెసిస్టర్ జోడించు');
    return out;
  }
  function editSheet(prefill) {
    if (!D) { toast('ముందు ఒక బోర్డ్ తెరవండి'); return; }
    sheet((s) => {
      s.appendChild(el('h2', null, '✏️ ఈ బోర్డ్‌లో మార్పు'));
      s.appendChild(el('div', 'muted', 'రాయండి / 🎤 చెప్పండి. చిన్న మార్పులు (విలువ, తిప్పు, జరుపు, LED రంగు, బోర్డ్ సైజు / రంగు, రంధ్రాలు) ఫోన్‌లోనే ₹0. భాగాలు జోడించడం / తీయడం AI చేస్తుంది: ఖర్చు ముందే చూపిస్తుంది.'));
      const ta = el('textarea');
      ta.id = 'edittext'; ta.rows = 2; ta.maxLength = 600; ta.value = prefill || '';
      ta.addEventListener('input', () => { clearTimeout(quoteT); quoteT = setTimeout(editHint, 350); });
      s.appendChild(ta);
      const ex = el('div');
      editExamples().forEach((w) => { const e = el('span', 'ex', w); e.addEventListener('click', () => { ta.value = w; editHint(); }); ex.appendChild(e); });
      s.appendChild(ex);
      const hint = el('div');
      hint.id = 'edithint';
      s.appendChild(hint);
      const r = el('div', 'row');
      const mic = btn('🎤', '', () => { dict.to = 'edit'; post({ t: 'dictate' }); });
      mic.id = 'editmic';
      r.appendChild(mic);
      const go = btn('✏️ చేయి', 'main', () => editGo(ta.value, false, false));
      go.id = 'editgo';
      r.appendChild(go);
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
      editHint();
    });
  }
  function editHint() {
    const t = $('edittext'), h = $('edithint'), go = $('editgo');
    if (!t || !h || !go) return;
    const w = t.value.trim();
    clear(h);
    go.disabled = !w;
    go.textContent = '✏️ చేయి';
    if (!w) return;
    const cmd = A.local(w, D);
    if (cmd && cmd.k !== 'noref') { h.appendChild(el('div', 'quote okc', '₹0 · ఫోన్‌లోనే చేస్తాను (AI అక్కర్లేదు)')); go.textContent = '✏️ చేయి (₹0)'; return; }
    if (cmd && cmd.k === 'noref') { h.appendChild(el('div', 'quote warnc', cmd.ref + ' అనే భాగం ఈ బోర్డ్‌లో లేదు. (📐 స్కీమాటిక్‌లో పేర్లు చూడండి)')); go.disabled = true; return; }
    const no = A.refuseWords(w);
    if (no) { h.appendChild(el('div', 'quote err', no)); go.disabled = true; return; }
    const design = A.forEdit(D);
    quote('edit', w, design.length, (m) => {
      if (!$('edithint')) return;
      clear(h);
      h.appendChild(el('div', 'muted', '🤖 ఇది ఫోన్‌లో చేయలేను; AI చేస్తుంది. అలాగే ఉన్న భాగాలు అదే చోట ఉంటాయి.'));
      const q = el('div');
      quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'edit', words: w, chars: design.length }));
      h.appendChild(q);
      go.disabled = !m.ok || !!ai.job || !!ai.wait;
      go.textContent = m.ok && m.money ? '🤖 AI తో చేయి · ' + m.money : '🤖 AI తో చేయి';
    });
  }
  /** His words about the board: a small change here (₹0), else the AI (his tap here; by voice ✋ 3 seconds). */
  function editGo(words, voice, fromBrain) {
    const w = String(words || '').trim();
    if (!w) return false;
    const cmd = A.local(w, D);
    if (cmd) {
      const said = doLocal(cmd);
      if (cmd.k !== 'noref') closeSheet();
      toast(said, 3500);
      if (voice) post({ t: 'vdone', say: said, voice: true });
      return true;
    }
    const no = A.refuseWords(w);
    if (no) { resultSheet('🚫 చేయలేదు', [no]); if (voice) post({ t: 'vdone', say: no.replace(/\(.*\)$/, ''), voice: true }); return true; }
    if (!D) { const t = 'ముందు ఒక బోర్డ్ తెరవండి Sir (📚 బోర్డ్‌లు).'; toast(t); if (voice) post({ t: 'vdone', say: t, voice: true }); return true; }
    if (ai.job || ai.wait) { const t = 'ఒక AI పని ఇంకా నడుస్తోంది Sir.'; toast(t); if (voice) post({ t: 'vdone', say: t, voice: true }); return true; }
    closeSheet();
    ai.pending = { kind: 'edit', words: w, voice: !!voice, id: D.id };
    post({ t: 'aiask', kind: 'edit', words: w, design: A.forEdit(D), voice: !!voice || !!fromBrain });
    return true;
  }
  /** A small change done here; what was done, in a short Telugu line. */
  function doLocal(cmd) {
    const LED_TE = { red: 'ఎరుపు', green: 'ఆకుపచ్చ', blue: 'నీలం', yellow: 'పసుపు', white: 'తెలుపు', orange: 'నారింజ' };
    const MASK_TE = { green: 'ఆకుపచ్చ', blue: 'నీలం', black: 'నలుపు', white: 'తెలుపు', purple: 'ఊదా' };
    if (cmd.k === 'tab') { showTab(cmd.tab); return 'సరే Sir.'; }
    if (cmd.k === 'versions') { if (!D) return 'ముందు ఒక బోర్డ్ తెరవండి Sir.'; verSheet(); return '🕑 వెర్షన్లు చూపిస్తున్నాను.'; }
    if (cmd.k === 'make') {
      if (!D) return 'ముందు ఒక బోర్డ్ తెరవండి Sir (📚 బోర్డ్‌లు).';
      if (cmd.what === 'guide') return startGuide() || 'సరే Sir.';
      if (cmd.what === 'box') return startBox() || 'సరే Sir.';
      if (cmd.what === 'ar') return startAr() || 'సరే Sir.';
      return openReal() || 'సరే Sir.';
    }
    if (!D) return 'ముందు ఒక బోర్డ్ తెరవండి Sir.';
    const pt = cmd.ref ? D.parts.find((p) => p.ref === cmd.ref) : null;
    switch (cmd.k) {
      case 'undo': if (!undo.length) return 'వెనక్కి వెళ్ళడానికి ఏమీ లేదు Sir.'; back1(); return '↩️ ముందున్నట్టు చేశాను.';
      case 'noref': return cmd.ref + ' అనే భాగం ఈ బోర్డ్‌లో లేదు Sir.';
      case 'route': { snap(); const r = reroute(true); changed(); const miss = r.failed.reduce((a, f) => a + f.pins.length, 0); return miss ? '⚡ ' + miss + ' కలపలేకపోయాను; భాగాల్ని కొంచెం దూరం జరపండి.' : '⚡ దారులు మళ్ళీ వేశాను, అన్నీ కలిశాయి.'; }
      case 'place': { snap(); if (!C.autoplace(D, { labels: true })) { back1(); return 'బోర్డ్ చిన్నది: అన్ని భాగాలు పట్టలేదు.'; } reroute(true); changed(); return '🔀 భాగాలు మళ్ళీ అమర్చి దారులు వేశాను.'; }
      case 'fill': snap(); D.pour.B = !!cmd.on; changed(); if (tab === 'pcb') pcbTools(); return cmd.on ? '⏚ GND నింపాను.' : '⏚ GND నింపు తీసేశాను.';
      case 'holes': snap(); D.board.holes = !!cmd.on; reroute(true); changed(); return cmd.on ? '🔩 4 మూలల్లో M3 స్క్రూ రంధ్రాలు పెట్టాను.' : '🔩 స్క్రూ రంధ్రాలు తీసేశాను.';
      case 'fit': snap(); C.fitBoard(D); pv.fit = true; reroute(true); changed(); return '📐 బోర్డ్ ' + D.board.w + ' × ' + D.board.h + ' mm చేశాను.';
      case 'size': {
        snap();
        if (cmd.w) D.board.w = Math.max(10, Math.min(200, cmd.w));
        if (cmd.h) D.board.h = Math.max(10, Math.min(200, cmd.h));
        pv.fit = true; reroute(true); changed();
        return '📏 బోర్డ్ ' + D.board.w + ' × ' + D.board.h + ' mm.' + (check && check.errors ? ' ❌ ' + check.errors + ' తప్పులు: భాగాలు బయటికి వెళ్ళాయేమో, ✅ చెక్ చూడండి.' : '');
      }
      case 'color': D.board.color = cmd.c; changed(); return '🎨 బోర్డ్ రంగు ' + MASK_TE[cmd.c] + '.';
      case 'rot': snap(); pt.rot = ((pt.rot || 0) + cmd.deg) % 360; afterMove(netsOfPart(pt.ref)); return '🔄 ' + pt.ref + ' ని ' + cmd.deg + '° తిప్పాను.';
      case 'move': {
        snap();
        pt.x = r2(pt.x + cmd.dx); pt.y = r2(pt.y + cmd.dy);
        afterMove(netsOfPart(pt.ref));
        const dir = cmd.dx < 0 ? 'ఎడమకి' : cmd.dx > 0 ? 'కుడికి' : cmd.dy < 0 ? 'పైకి' : 'కిందికి';
        return '↔️ ' + pt.ref + ' ని ' + dir + ' ' + r2(Math.abs(cmd.dx || cmd.dy)) + ' mm జరిపాను.';
      }
      case 'led': snap(); pt.color = cmd.color; pt.val = cmd.color.toUpperCase(); changed(); return '💡 ' + pt.ref + ' ఇప్పుడు ' + LED_TE[cmd.color] + ' LED. (రెసిస్టర్ విలువ 🧮 లెక్కలు లో చూడండి: నీలం / తెలుపు కి 3V.)';
      case 'val': {
        snap();
        const old = pt.val;
        pt.val = cmd.val;
        let more = '';
        if (pt.lib === 'elec') { const f = A.elecFp(cmd.val); if (f !== (P.fpOf(pt) || {}).id) { pt.fp = f; reroute(true); more = ' పెద్ద / చిన్న కెపాసిటర్ కాబట్టి బోర్డ్ మీద చోటు మార్చాను.'; } }
        changed();
        return '✏️ ' + pt.ref + ': ' + old + ' → ' + cmd.val + '.' + more;
      }
      default: return 'అర్థం కాలేదు Sir.';
    }
  }

  // ---------------------------------------------------------------- 🎙️ his words heard here

  function heard(words, fromBrain) {
    const w = String(words || '').trim();
    if (!w) return;
    if (!fromBrain && guideWords(w)) return; // (🔧 "తర్వాత" / "వెనక్కి" / "మళ్ళీ" while soldering: the step is read out)
    const cmd = A.local(w, D);
    if (cmd) { const said = doLocal(cmd); toast(said, 3500); post({ t: 'vdone', say: said, voice: true }); return; }
    if (A.newBoardWords(w)) { idea.text = w; showTab('home'); toIdea(); makeIdea(true); return; }
    if (D && (fromBrain || A.boardWords(w))) { editGo(w, true, fromBrain); return; }
    if (fromBrain) { const t = 'ముందు ఒక బోర్డ్ తెరవండి Sir (📚 బోర్డ్‌లు), తర్వాత మార్పు చెప్పండి.'; toast(t, 3500); post({ t: 'vdone', say: t, voice: true }); return; }
    post({ t: 'vdone', brain: true, s: w });
  }

  // ---------------------------------------------------------------- the bar: ✋ 3 seconds, the AI at work, ⏹

  function bar() {
    const b = $('aibar');
    clearTimeout(barT);
    clear(b);
    ['ideago', 'editgo'].forEach((id) => { const g = $(id); if (g && (ai.wait || ai.job)) g.disabled = true; });
    if (ai.wait) {
      const left = Math.max(0, Math.ceil((ai.wait.until - Date.now()) / 1000));
      const x = el('div', 'x');
      x.appendChild(el('div', null, '⏳ ' + KIND_TE[ai.wait.kind] + (ai.wait.words ? ': “' + ai.wait.words.slice(0, 60) + (ai.wait.words.length > 60 ? '…' : '') + '”' : '')));
      x.appendChild(el('div', 'muted', '🤖 ' + ai.wait.model + ' · సుమారు ' + ai.wait.money + ' · ' + (left ? left + ' సెకన్లలో మొదలు' : 'మొదలవుతోంది…')));
      b.appendChild(x);
      b.appendChild(btn('✋ వద్దు', 'warn', () => post({ t: 'aicancel' })));
      b.classList.add('on');
      barT = setTimeout(bar, 300);
      return;
    }
    if (ai.job) {
      const secs = Math.round((Date.now() - ai.job.at) / 1000);
      const x = el('div', 'x');
      x.appendChild(el('div', null, '🧠 ' + ai.job.model + ' ' + DOING[ai.job.kind] + ' ' + secs + ' సె'));
      x.appendChild(el('div', 'muted', 'సుమారు ' + ai.job.money + (ai.job.n ? ' · ' + ai.job.n + ' అక్షరాలు వచ్చాయి' : '')));
      b.appendChild(x);
      b.appendChild(btn('⏹ ఆపు', 'bad', () => sheet((s) => {
        s.appendChild(el('h2', null, 'AI పని ఆపాలా?'));
        s.appendChild(el('div', 'muted', 'ఇప్పటిదాకా అయిన ఖర్చు మాత్రం పడుతుంది.'));
        const r = el('div', 'row');
        r.appendChild(btn('⏹ ఆపు', 'bad', () => { closeSheet(); post({ t: 'aistop' }); }));
        r.appendChild(btn('కొనసాగించు', '', closeSheet));
        s.appendChild(r);
      })));
      b.classList.add('on');
      barT = setTimeout(bar, 1000);
      return;
    }
    b.classList.remove('on');
    if (tab === 'home') ideaQuote();
  }

  // ---------------------------------------------------------------- the answer

  function resultSheet(title, lines, buttons) {
    sheet((s) => {
      s.appendChild(el('h2', null, title));
      lines.filter(Boolean).forEach((l) => s.appendChild(el('div', 'step', l)));
      const r = el('div', 'row');
      (buttons || []).forEach((b) => r.appendChild(b));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  function aiDone(m) {
    const pend = ai.pending || {};
    ai.job = null; ai.wait = null; ai.pending = null;
    bar();
    const voice = !!m.voice, kind = m.kind;
    const said = (t) => { if (voice) post({ t: 'vdone', say: t, voice: true }); };
    if (!m.ok) {
      const t = m.refuse ? '🚫 ' + m.refuse : m.fail;
      resultSheet(m.stopped ? '⏹ ఆపేశాను' : m.refuse ? '🚫 AI చేయలేదు' : '✗ రాలేదు', [t, m.refuse || (!m.stopped && m.cost) ? '💰 ఖర్చు ' + m.cost : '']);
      said(t.replace(/^🚫 /, ''));
      return;
    }
    const a = m.ans || {};
    if (kind === 'help') return helpDone(m, a, said);
    if (kind === 'pins') return pinsDone(m, a);
    if (kind === 'review') return reviewDone(m, a);
    if (kind === 'solder') return solderDone(m, a);
    const cost = '🤖 ' + (m.model || 'AI') + ' · 💰 ఖర్చు ' + m.cost;
    if (!Array.isArray(a.parts)) {
      const t = String(a.say || 'AI ఏమీ మార్చలేదు.');
      resultSheet('💬 AI జవాబు', [t, cost]);
      said(t + ' ఖర్చు ' + m.cost + '.');
      return;
    }
    if (kind === 'edit') return editDone(m, a, pend, cost, said);
    toast('⚡ భాగాలు అమర్చి దారులు వేస్తున్నాను…', 9000);
    setTimeout(() => {
      const r = A.build(a, { model: m.model, cost: m.cost, words: pend.words });
      if (r.err) { resultSheet(r.refuse ? '🚫 బోర్డ్ చేయలేదు' : '✗ బోర్డ్ కాలేదు', [r.err, cost]); said(r.err + ' ఖర్చు ' + m.cost + '.'); return; }
      $('toast').style.display = 'none';
      open(r.D);
      if (kind === 'scan') { scanSt.top = null; scanSt.bottom = null; scanSt.words = ''; }
      else { idea.text = ''; idea.photo = null; }
      showTab('pcb');
      const info = r.info, warn = info.dropped.map((d) => '⚠️ వదిలేశాను: ' + d).concat(info.fixes.slice(0, 6).map((f) => '⚠️ ' + f));
      if (m.cut) warn.unshift('⚠️ AI జవాబు హద్దు దగ్గర మధ్యలో ఆగిపోయింది: వచ్చినంత వరకు బోర్డ్ చేశాను, కొన్ని భాగాలు లేకపోవచ్చు.');
      const head = D.parts.length + ' భాగాలు · ' + D.board.w + ' × ' + D.board.h + ' mm · ' + (r.miss ? '⚡ ' + r.miss + ' పిన్నులు కలపలేకపోయాను' : '🔗 అన్నీ కలిశాయి') + ' · ' + (check.errors ? '❌ ' + check.errors + ' తప్పులు (✅ చెక్ → 🤖 AI సహాయం)' : '✅ చెక్ అయింది');
      resultSheet((kind === 'scan' ? '📷 పాత బోర్డ్ నుంచి: ' : '✅ బోర్డ్ రెడీ: ') + D.name, [head, kind === 'scan' ? '📷 ఫోటో నుంచి చదివిన సర్క్యూట్ (సుమారు): ప్రతి విలువ, ప్రతి కలుపు నిజమైన బోర్డ్‌తో ఒకసారి సరిచూడండి (మల్టీమీటర్ బీప్‌తో).' : '', info.say ? '🤖 ' + info.say : ''].concat(warn, moduleLines(), [cost,
        '👉 ఆర్డర్ ముందు: 📐 స్కీమాటిక్‌లో కలుపులు ఒకసారి చూడండి (AI కూడా తప్పు చేయొచ్చు), 🏭 ఫ్యాక్టరీ లో "కొలిచి చూడాల్సినవి" చూడండి.']),
      [btn('📐 స్కీమాటిక్', '', () => showTab('sch')), btn('🧊 3D', '', () => showTab('d3'))]);
      said('బోర్డ్ రెడీ Sir: ' + D.parts.length + ' భాగాలు, ' + (r.miss ? r.miss + ' పిన్నులు కలపలేకపోయాను' : 'అన్నీ కలిశాయి') + '. ఖర్చు ' + m.cost + '.');
    }, 40);
  }
  /** ⚡→🔌 (Anil, 28 Nov: "D చేయి") the circuit lab's board → this studio's parts and joins (pcblab.js) → placed, routed, checked. ₹0. */
  function fromLab(m) {
    const L = window.PcbFromLab;
    const r = L ? L.convert(m.board || {}) : { err: 'సర్క్యూట్ ల్యాబ్ బోర్డ్ చదవలేకపోయాను.' };
    if (r.err) { showTab('home'); resultSheet('🚫 PCB గా చేయలేదు', [r.err, '💰 ఖర్చు ₹0 (AI వాడలేదు)']); return; }
    toast('⚡ సర్క్యూట్ ల్యాబ్ బోర్డ్: భాగాలు అమర్చి దారులు వేస్తున్నాను…', 9000);
    setTimeout(() => {
      const b = A.build(r.ans, { model: '⚡ సర్క్యూట్ ల్యాబ్', cost: '₹0', words: r.ans.title });
      if (b.err) { resultSheet('✗ బోర్డ్ కాలేదు', [b.err, '💰 ఖర్చు ₹0']); return; }
      $('toast').style.display = 'none';
      open(b.D);
      showTab('pcb');
      const info = b.info, warn = info.dropped.map((d) => '⚠️ వదిలేశాను: ' + d).concat(info.fixes.slice(0, 6).map((f) => '⚠️ ' + f));
      const head = D.parts.length + ' భాగాలు · ' + D.board.w + ' × ' + D.board.h + ' mm · ' + (b.miss ? '⚡ ' + b.miss + ' పిన్నులు కలపలేకపోయాను' : '🔗 అన్నీ కలిశాయి') + ' · ' + (check && check.errors ? '❌ ' + check.errors + ' తప్పులు (✅ చెక్ చూడండి)' : '✅ చెక్ అయింది');
      resultSheet('🔌 సర్క్యూట్ ల్యాబ్ నుంచి PCB: ' + D.name, [head].concat(r.lines, r.left.slice(0, 8).map((x) => '↪️ ' + x), warn, moduleLines(), ['💰 ఖర్చు ₹0 (AI వాడలేదు)',
        '👉 ఆర్డర్ ముందు: 📐 స్కీమాటిక్‌లో కలుపులు ఒకసారి చూడండి, 🏭 ఫ్యాక్టరీ లో "కొలిచి చూడాల్సినవి" చూడండి.']),
      [btn('📐 స్కీమాటిక్', '', () => showTab('sch')), btn('🧊 3D', '', () => showTab('d3'))]);
    }, 40);
  }
  function editDone(m, a, pend, cost, said) {
    if (!D || (pend.id && D.id !== pend.id)) { const t = 'మీరు వేరే బోర్డ్ తెరిచారు; ఈ మార్పు వేయలేదు.'; resultSheet('✗ మార్పు వేయలేదు', [t, cost]); said(t); return; }
    if (m.cut) { const t = 'మార్పు జవాబు మధ్యలోనే ఆగిపోయింది Sir, అందుకే బోర్డ్ మార్చలేదు.'; resultSheet('✗ మార్పు వేయలేదు', [t, cost]); said(t + ' ఖర్చు ' + m.cost + '.'); return; }
    const r = A.merge(D, a);
    if (r.err) { resultSheet(r.refuse ? '🚫 మార్పు చేయలేదు' : '✗ మార్పు వేయలేదు', [r.err, cost]); said(r.err); return; }
    snap();
    verSnap('🤖 AI మార్పు ముందు' + (pend.words ? ': "' + String(pend.words).slice(0, 40) + '"' : ''));
    const sizeWas = D.board.w + '×' + D.board.h;
    ['board', 'parts', 'nets', 'tracks', 'vias', 'labels'].forEach((k) => { D[k] = r.D[k]; });
    if (sizeWas !== D.board.w + '×' + D.board.h) pv.fit = true;
    changed();
    const lines = [];
    if (r.added.length) lines.push('➕ కొత్తవి: ' + r.added.join(', '));
    if (r.removed.length) lines.push('➖ తీసేసినవి: ' + r.removed.join(', '));
    if (r.changed.length) lines.push('✏️ ' + r.changed.join(' · '));
    if (!lines.length) lines.push('భాగాలు అలాగే ఉన్నాయి; కలుపులు మారాయి.');
    lines.push((r.miss ? '⚡ ' + r.miss + ' పిన్నులు కలపలేకపోయాను' : '🔗 అన్నీ కలిశాయి') + ' · ' + (check.errors ? '❌ ' + check.errors + ' తప్పులు' : '✅ చెక్ అయింది') + (sizeWas !== D.board.w + '×' + D.board.h ? ' · 📏 బోర్డ్ ' + D.board.w + ' × ' + D.board.h + ' mm' : ''));
    if (r.info.say) lines.push('🤖 ' + r.info.say);
    r.info.dropped.forEach((d) => lines.push('⚠️ వదిలేశాను: ' + d));
    lines.push(cost);
    resultSheet('✅ మార్పు అయింది', lines, [btn('↩️ వెనక్కి', '', () => { back1(); closeSheet(); })]);
    said('మార్పు అయింది Sir. ఖర్చు ' + m.cost + '.');
  }

  // ---------------------------------------------------------------- 🤖 help for the checks (moves he applies himself)

  function helpCard(h) {
    if (!D || !check) return;
    const c = el('div', 'card idea');
    const hp = ai.help && ai.help.id === D.id ? ai.help : null;
    if (hp) {
      c.appendChild(el('div', 't', '🤖 AI సహాయం'));
      if (hp.say) c.appendChild(el('div', 'step', hp.say));
      if (hp.moves.length && !hp.done) {
        c.appendChild(el('div', 'muted', 'AI చెప్పిన జరుపులు:'));
        hp.moves.forEach((mv) => {
          const bits = [];
          if (mv.dx) bits.push((mv.dx < 0 ? 'ఎడమకి ' : 'కుడికి ') + Math.abs(mv.dx) + ' mm');
          if (mv.dy) bits.push((mv.dy < 0 ? 'పైకి ' : 'కిందికి ') + Math.abs(mv.dy) + ' mm');
          if (mv.rot != null) bits.push(mv.rot + '° కి తిప్పు');
          c.appendChild(el('div', 'item', '• ' + mv.ref + ': ' + bits.join(', ')));
        });
        const r = el('div', 'row');
        r.appendChild(btn('✅ ఈ మార్పులు చేయి', 'main', () => {
          const before = check.errors;
          snap();
          verSnap('🤖 చెక్ సలహా జరిపే ముందు');
          A.applyMoves(D, hp.moves);
          reroute(true);
          changed();
          hp.done = true;
          drcList();
          toast('❌ ' + before + ' → ' + (check.errors ? '❌ ' + check.errors : '✅ 0 తప్పులు') + ' (↩️ తో వెనక్కి రావచ్చు)', 4000);
        }));
        r.appendChild(btn('వద్దు', '', () => { ai.help = null; drcList(); }));
        c.appendChild(r);
      } else if (hp.done) c.appendChild(el('div', 'muted okc', '✓ ఆ జరుపులు చేశాను. ఇంకా తప్పులు ఉంటే మళ్ళీ అడగొచ్చు.'));
      c.appendChild(el('div', 'muted', '💰 ' + hp.cost));
      h.appendChild(c);
      if (!hp.done || !(check.errors || check.warns)) return;
    }
    if (!check.errors && !check.warns) return;
    const c2 = hp ? el('div', 'card idea') : c;
    c2.appendChild(el('div', 't', '🤖 AI సహాయం: ఈ తప్పులు ఎందుకు, ఎలా సరిచేయాలి'));
    c2.appendChild(el('div', 'muted', 'తప్పులు, భాగాల చోట్లు మీ AI కి వెళ్తాయి; తెలుగులో కారణం + భాగాల్ని ఎక్కడికి జరపాలో చెబుతుంది. మీరు ✅ నొక్కితేనే జరుపుతాను.'));
    const q = el('div');
    c2.appendChild(q);
    const r = el('div', 'row');
    const go = btn('🤖 అడుగు', 'main', () => {
      if (ai.job || ai.wait) { toast('ఒక AI పని ఇంకా నడుస్తోంది'); return; }
      ai.pending = { kind: 'help', id: D.id };
      post({ t: 'aiask', kind: 'help', words: '', design: A.forHelp(D, check), voice: false });
    });
    r.appendChild(go);
    c2.appendChild(r);
    h.appendChild(c2);
    const design = A.forHelp(D, check);
    quote('help', '', design.length, (m) => {
      quoteLine(q, m, () => post({ t: 'pickmodel', kind: 'help', words: '', chars: design.length }));
      go.disabled = !m.ok || !!ai.job || !!ai.wait;
      go.textContent = m.ok && m.money ? '🤖 అడుగు · ' + m.money : '🤖 అడుగు';
    });
  }
  function helpDone(m, a, said) {
    if (!D) return;
    const mv = A.moves(D, a.moves);
    ai.help = { id: D.id, say: String(a.say || '').slice(0, 800), moves: mv, cost: (m.model || 'AI') + ' · ఖర్చు ' + m.cost, done: false };
    showTab('drc');
    $('drc').scrollTop = 0;
    said((ai.help.say || 'చూశాను Sir.') + (mv.length ? ' జరుపులు తెర మీద ఉన్నాయి; ✅ నొక్కితే చేస్తాను.' : ''));
  }

  // ---------------------------------------------------------------- start

  state();
  showTab('home');
  // ================================================================ ⚡ నడిపించు: the board running on the phone before ordering (pcbsim.js, ₹0, offline)

  const sim = { key: '', src: {}, ctl: {}, S: null, R: null, cy: null, rep: null, live: false, run: true, raf: 0, last: 0, err: null };
  const simv = { s: 1, ox: 20, oy: 20, fit: true, sel: null, vals: true };
  /** Builds the circuit of the open board with what he chose (supply volts, switches, pins) and runs it. */
  function simBuild() {
    if (!D) return;
    if (sim.key !== D.id) { sim.key = D.id; sim.src = {}; sim.ctl = {}; simv.fit = true; sim.run = true; }
    const opts = { src: sim.src, ctl: sim.ctl };
    sim.S = null; sim.rep = null; sim.cy = null; sim.live = false; sim.err = null;
    try {
      const S = SIM.setup(D, opts);
      if (S.err) { sim.err = S.err; return; }
      if (SIM.has555(S)) {
        sim.cy = SIM.cycle(SIM.setup(D, opts));       // (a copy measures one cycle: how fast, the average and the most)
        sim.R = SIM.start(S);
        sim.live = !!(sim.cy && !sim.cy.stuck && sim.cy.f < 8);
      } else sim.R = SIM.dc(S);
      sim.S = S;
      sim.rep = SIM.report(S, sim.R, sim.cy);
    } catch (e) { sim.err = '⚡ ఈ బోర్డ్‌ని నడిపించలేకపోయాను (' + String((e && e.message) || e).slice(0, 80) + ')'; sim.S = null; }
  }
  function simLoop(t) {
    sim.raf = 0;
    if (tab !== 'sim' || !sim.S || !sim.live || !sim.run) return;
    const dt = sim.last ? Math.min(0.05, Math.max(0.001, (t - sim.last) / 1000)) : 0.016;
    sim.last = t;
    try { sim.R = SIM.step(sim.S, dt); } catch (e) { sim.run = false; simTools(); return; }
    drawSim();
    sim.raf = requestAnimationFrame(simLoop);
  }
  function simGo() { if (!sim.raf && sim.live && sim.run && tab === 'sim') { sim.last = 0; sim.raf = requestAnimationFrame(simLoop); } }
  /** What the drawing shows: volts on every wire, lit LEDs, problem rings. */
  function simOverlay() {
    const R = sim.R || { v: {}, p: {} }, ov = { v: R.v || {}, p: R.p || {}, sev: {}, glow: {}, col: {}, vmax: 1 };
    sim.S.sources.forEach((s) => { ov.vmax = Math.max(ov.vmax, s.v); });
    (sim.rep ? sim.rep.items : []).forEach((it) => { if (it.sev === 'err' || (it.sev === 'warn' && ov.sev[it.ref] !== 'err')) ov.sev[it.ref] = it.sev; });
    Object.keys(sim.S.parts).forEach((ref) => {
      const info = sim.S.parts[ref];
      if (info.kind !== 'led') return;
      ov.col[ref] = info.col;
      const live = (R.p[ref] || {}).i || 0, avg = sim.cy && sim.cy.avg && sim.cy.avg.p[ref] ? sim.cy.avg.p[ref].i || 0 : live;
      const i = sim.live || !sim.cy ? live : avg;
      ov.glow[ref] = i > 2e-5 ? Math.min(1, 0.15 + i / info.good) : 0;
    });
    return ov;
  }
  function drawSim() {
    const c = clear($('simchips'));
    if (!D) return;
    if (!sim.S) {
      c.appendChild(el('span', 'chip bad', sim.err || '⚡ ఇంకా నడిపించలేదు'));
      simTop();
      drawSchOn($('simcv'), simv, null);
      return;
    }
    simChips(c);
    simTop();
    drawSchOn($('simcv'), simv, simOverlay());
  }
  /**
   * The chips over the drawing can take one row or two (a second 🔋, the ⏱️ speed…): the drawing starts below them, so they never
   * cover J1. Measured on every draw; when a row more comes later and he has not moved / zoomed the drawing, it is placed again.
   */
  function simTop() {
    const cr = $('simchips').getBoundingClientRect(), vr = $('simcv').getBoundingClientRect();
    const top = cr.height ? Math.round(cr.bottom - vr.top) + 10 : 40;
    const untouched = simv.fitted && simv.fitted[0] === simv.s && simv.fitted[1] === simv.ox && simv.fitted[2] === simv.oy;
    if (simv.fit || (untouched && top > (simv.top || 40))) { simv.top = top; simv.fit = true; }
  }
  function simChips(c) {
    const rep = sim.rep, errs = rep.items.filter((i) => i.sev === 'err').length, warns = rep.items.filter((i) => i.sev === 'warn').length;
    const sum = el('span', 'chip tap ' + (errs ? 'bad' : warns ? 'warn' : 'ok'), errs ? '❌ ' + errs + (warns ? ' · ⚠️ ' + warns : '') + ' · 📋 చూడు' : warns ? '⚠️ ' + warns + ' గమనించండి · 📋' : '✅ బాగా నడుస్తోంది · 📋');
    sum.addEventListener('click', simReport);
    c.appendChild(sum);
    sim.S.sources.forEach((s) => {
      const r = sim.R.src.find((q) => q.key === s.key) || { i: 0 };
      const i = sim.cy && sim.cy.avg && typeof sim.cy.avg.src[s.key] === 'number' ? sim.cy.avg.src[s.key] : r.i;
      const ch = el('span', 'chip tap', (s.kind === 'esp' ? '🔌 ' + s.ref + ' USB' : '🔋 ' + s.ref + ' ' + SIM.fmtV(s.v)) + ' · ' + SIM.fmtA(i));
      ch.addEventListener('click', simSupply);
      c.appendChild(ch);
    });
    if (sim.cy && !sim.cy.stuck) c.appendChild(el('span', 'chip', '⏱️ సెకనుకి ' + (Math.round(sim.cy.f * 100) / 100) + ' సార్లు' + (sim.live ? '' : ' (కంటికి ఆగకుండా)')));
    if (sim.live && sim.run) c.appendChild(el('span', 'chip', '▶️ ' + sim.R.t.toFixed(1) + ' s'));
  }
  function simTools() {
    const t = clear($('simtools'));
    if (sim.live) t.appendChild(tool(sim.run ? '⏸️ ఆపు' : '▶️ నడుపు', sim.run ? '' : 'main', () => { sim.run = !sim.run; simTools(); drawSim(); simGo(); }));
    t.appendChild(tool('🔋 కరెంట్', '', simSupply));
    if (sim.S && sim.S.controls.length) t.appendChild(tool('🎛️ స్విచ్‌లు (' + sim.S.controls.length + ')', 'main', simControls));
    t.appendChild(tool('📋 ఫలితం', '', simReport));
    t.appendChild(tool('📈 గ్రాఫ్', '', graphSheet));
    t.appendChild(tool('🔬 లోతుగా', '', () => advSheet()));
    t.appendChild(tool('🧪 తప్పు వెతుకు', '', () => faultStart(true)));
    t.appendChild(tool('🔄 మళ్ళీ', '', () => { sim.run = true; simBuild(); simTools(); drawSim(); simGo(); toast('⚡ మొదటి నుంచి (స్విచ్ ఆన్ చేసినట్టు)'); }));
    t.appendChild(tool('🔍', '', () => { simv.fit = true; drawSim(); }));
  }
  function simAgain() { simBuild(); simTools(); drawSim(); simGo(); }
  const SUPPLY = {
    '9v': [[6.5, 'అయిపోతున్న 9V'], [9, 'కొత్త 9V బ్యాటరీ']],
    bike: [[11.8, 'ఆగిన బండి'], [12.6, 'పూర్తి బ్యాటరీ'], [14.4, 'నడుస్తున్న బండి']],
    usb: [[4.75, 'బలహీన USB'], [5, 'USB / అడాప్టర్ 5V'], [5.25, 'కొంచెం ఎక్కువ']],
    logic: [[3.0, 'తక్కువ'], [3.3, '3.3V']],
    li: [[3.0, 'ఖాళీ 18650'], [3.7, 'సగం'], [4.2, 'పూర్తిగా నిండింది']]
  };
  function simSupply() {
    if (!sim.S) { toast(sim.err || 'ముందు బోర్డ్ తెరవండి'); return; }
    sheet((s) => {
      s.appendChild(el('h2', null, '🔋 కరెంట్ ఎక్కడి నుంచి వస్తుంది'));
      s.appendChild(el('div', 'muted', 'బోర్డ్‌కి వచ్చే వోల్టేజ్ మార్చి చూడండి: బ్యాటరీ అయిపోతుంటే, బండి నడుస్తుంటే ఏమవుతుందో వెంటనే కనిపిస్తుంది.'));
      sim.S.sources.forEach((src) => {
        const c = el('div', 'card');
        c.appendChild(el('div', null, src.te));
        if (src.fixed) { c.appendChild(el('div', 'muted', 'ESP32 బోర్డ్ తన USB నుంచి 5V, దాని లోపలి రెగ్యులేటర్ నుంచి 3.3V ఇస్తుంది.')); s.appendChild(c); return; }
        const sg = el('div', 'seg');
        const opts = (SUPPLY[src.kind] || [[r2(src.v0 * 0.85), 'తక్కువ'], [src.v0, 'మామూలు'], [r2(Math.min(24, src.v0 * 1.15)), 'ఎక్కువ']]);
        opts.forEach((o) => sg.appendChild(btn(SIM.fmtV(o[0]) + ' · ' + o[1], Math.abs(src.v - o[0]) < 0.01 ? 'on small' : 'small', () => { sim.src[src.key] = o[0]; simAgain(); simSupply(); })));
        c.appendChild(sg);
        const row = el('div', 'row');
        const inp = el('input'); inp.type = 'number'; inp.min = '0.5'; inp.max = '24'; inp.step = '0.1'; inp.value = String(src.v);
        row.appendChild(inp);
        row.appendChild(btn('V సెట్ చేయి', 'small', () => { const v = parseFloat(inp.value); if (!(v >= 0.5 && v <= 24)) { toast('0.5 నుంచి 24 V వరకే (ఇక్కడ తక్కువ వోల్టేజ్ బోర్డ్‌లు మాత్రమే)'); return; } sim.src[src.key] = v; simAgain(); simSupply(); }));
        c.appendChild(row);
        s.appendChild(c);
      });
      const r = el('div', 'row'); r.appendChild(btn('మూసేయి', '', closeSheet)); s.appendChild(r);
    });
  }
  function simControls() {
    if (!sim.S) return;
    sheet((s) => {
      s.appendChild(el('h2', null, '🎛️ స్విచ్‌లు, బయటి కనెక్షన్లు'));
      s.appendChild(el('div', 'muted', 'బటన్ నొక్కినట్టు, స్విచ్ జరిపినట్టు, మాడ్యూల్ పిన్ ఆన్ చేసినట్టు: మార్చగానే బోర్డ్ ఏం చేస్తుందో డ్రాయింగ్‌లో కనిపిస్తుంది.'));
      sim.S.controls.forEach((ct) => {
        const c = el('div', 'card');
        c.appendChild(el('div', null, ct.te));
        const sg = el('div', 'seg');
        ct.opts.forEach((o) => sg.appendChild(btn(o[1], ct.val === o[0] ? 'on small' : 'small', () => { sim.ctl[ct.key] = o[0]; simAgain(); simControls(); })));
        c.appendChild(sg);
        s.appendChild(c);
      });
      const r = el('div', 'row'); r.appendChild(btn('మూసేయి', '', closeSheet)); s.appendChild(r);
    });
  }
  const SEV_ICON = { err: '❌', warn: '⚠️', ok: '✅', info: 'ℹ️' };
  function simReport() {
    if (!sim.S) { toast(sim.err || 'ముందు బోర్డ్ తెరవండి'); return; }
    const rep = sim.rep;
    sheet((s) => {
      s.appendChild(el('h2', null, '⚡ నడిపిస్తే ఏమవుతుంది'));
      const top = el('div', 'card');
      rep.per.forEach((p) => top.appendChild(el('div', null, (p.s.kind === 'esp' ? '🔌 ' : '🔋 ') + p.s.te + ': ' + SIM.fmtV(p.v) + ', ' + SIM.fmtA(p.i) + (sim.cy && sim.cy.avg ? ' (సగటు)' : ''))));
      if (rep.hours) top.appendChild(el('div', 'muted', '🔋 ' + (rep.bat === 'li' ? '18650 బ్యాటరీ (సుమారు 2000 mAh)' : '9V బ్యాటరీ (సుమారు 500 mAh)') + ' సుమారు ' + (rep.hours >= 48 ? Math.round(rep.hours / 24) + ' రోజులు' : Math.round(rep.hours) + ' గంటలు') + ' వస్తుంది.'));
      if (sim.cy && !sim.cy.stuck) top.appendChild(el('div', 'muted', '⏱️ 555: సెకనుకి ' + (Math.round(sim.cy.f * 100) / 100) + ' సార్లు, ' + Math.round(sim.cy.duty * 100) + '% సమయం OUT ఆన్.'));
      s.appendChild(top);
      rep.items.forEach((it) => {
        const d = el('div', 'item');
        d.appendChild(el('div', 'i', SEV_ICON[it.sev] || '•'));
        const x = el('div', 'x');
        x.appendChild(el('div', it.sev === 'err' ? 'err' : it.sev === 'warn' ? 'warnc' : it.sev === 'ok' ? '' : 'muted', it.te));
        if (it.fix) x.appendChild(el('div', 'f', '👉 ' + it.fix));
        d.appendChild(x);
        if (it.ref) d.addEventListener('click', () => { simv.sel = it.ref; closeSheet(); drawSim(); });
        s.appendChild(d);
      });
      s.appendChild(el('div', 'muted', 'ఇది మన సొంత లెక్క (₹0, నెట్ లేకుండా, AI కాదు): LED, ట్రాన్సిస్టర్, రెగ్యులేటర్, మాడ్యూల్స్ సుమారు విలువలతో. అసలు బోర్డ్‌లో కొంచెం తేడా ఉంటుంది; మాడ్యూల్ కొన్నాక దాని మీది వోల్టేజ్ తప్పకుండా చూడండి.'));
      const r = el('div', 'row');
      if (sim.S.controls.length) r.appendChild(btn('🎛️ స్విచ్‌లు', '', simControls));
      r.appendChild(btn('🔋 కరెంట్', '', simSupply));
      r.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(r);
    });
  }
  /** A part tapped while running: its numbers, its pins' volts, what the run says about it. */
  function simPart(ref) {
    const pt = D.parts.find((p) => p.ref === ref), r = (sim.R.p || {})[ref] || {}, lib = P.LIB[pt.lib] || {};
    sheet((s) => {
      s.appendChild(el('h2', null, ref + ' · ' + (lib.te || pt.lib) + (pt.val ? ' · ' + pt.val : '')));
      const g = el('div', 'reading'), row = (k, v) => { g.appendChild(el('div', 'k', k)); g.appendChild(el('div', null, v)); };
      if (typeof r.i === 'number') row('కరెంట్', SIM.fmtA(r.i));
      if (typeof r.v === 'number') row('దాని మీద వోల్టేజ్', SIM.fmtV(r.v));
      if (typeof r.w === 'number' && r.w > 1e-4) row('వేడి', SIM.fmtW(r.w));
      if (r.kind === 'npn') row('స్థితి', r.m === 'sat' ? 'పూర్తిగా ఆన్' : r.m === 'act' ? 'కొంచెం ఆన్' : 'ఆఫ్');
      if (r.kind === '555') row('OUT', r.q ? 'ఆన్ (' + SIM.fmtV(r.out) + ')' : 'ఆఫ్');
      if (typeof r.vin === 'number') { row('IN', SIM.fmtV(r.vin)); row('OUT', SIM.fmtV(r.vout) + ' · ' + SIM.fmtA(r.iout || 0)); }
      if (r.kind === 'buzzer') row('శబ్దం', r.on ? '🔊 మోగుతోంది' : 'నిశ్శబ్దం');
      s.appendChild(g);
      const names = P.pinsOf(pt), tb = el('table'), hr = el('tr');
      ['పిన్', 'పేరు', 'దారి', 'వోల్టేజ్'].forEach((t) => hr.appendChild(el('th', null, t))); tb.appendChild(hr);
      Object.keys(names).forEach((k) => {
        const net = D.nets.find((n) => n.p.indexOf(ref + '.' + k) >= 0), v = net ? sim.R.v[net.n] : undefined, tr = el('tr');
        [k, names[k], net ? net.n : '—', typeof v === 'number' ? SIM.fmtV(v) : net ? '— (బయటికి)' : '—'].forEach((t) => tr.appendChild(el('td', null, t)));
        tb.appendChild(tr);
      });
      s.appendChild(tb);
      sim.rep.items.filter((it) => it.ref === ref).forEach((it) => {
        const d = el('div', 'step ' + (it.sev === 'err' ? 'err' : it.sev === 'warn' ? 'warnc' : ''), (SEV_ICON[it.sev] || '') + ' ' + it.te);
        s.appendChild(d);
        if (it.fix) s.appendChild(el('div', 'muted', '👉 ' + it.fix));
      });
      const b = el('div', 'row');
      b.appendChild(btn('📐 ఇది ఏమిటి?', '', () => partSheet(pt)));
      b.appendChild(btn('మూసేయి', '', closeSheet));
      s.appendChild(b);
    });
  }
  gestures($('simcv'), simv, {
    redraw: () => drawSim(),
    tap(x, y) {
      if (!D || !sim.S) return;
      const lay = schLayout(), w = toWorld(simv, x, y);
      let hit = null;
      lay.P2.forEach((pl, ref) => { const b = pl.box; if (w[0] >= b[0] - 8 && w[0] <= b[2] + 8 && w[1] >= b[1] - 8 && w[1] <= b[3] + 8) { const a = (b[2] - b[0]) * (b[3] - b[1]); if (!hit || a < hit.a) hit = { ref, a }; } });
      simv.sel = hit ? hit.ref : null;
      drawSim();
      if (hit) simPart(hit.ref);
    }
  });
  /** One line for the factory checklist: what a run says (built here when the tab was not opened). */
  function simLine() {
    if (!D) return null;
    if (sim.key !== D.id || !sim.rep) simBuild();
    if (!sim.rep) return { ok: false, te: '⚡ ' + (sim.err || 'నడిపించలేకపోయాను') };
    const e = sim.rep.items.filter((i) => i.sev === 'err').length, w = sim.rep.items.filter((i) => i.sev === 'warn').length;
    return e ? { ok: false, te: '⚡ నడిపిస్తే ❌ ' + e + (w ? ' · ⚠️ ' + w : '') + ': ⚡ నడిపించు లో చూసి సరిచేయండి' }
      : w ? { ok: false, te: '⚡ నడిపిస్తే ⚠️ ' + w + ' గమనించాల్సినవి (⚡ నడిపించు లో చూడండి)' } : { ok: true, te: '⚡ నడిపించి చూశాను: తప్పులు లేవు' };
  }

  // ================================================================ 🕑 versions: the board as it was, kept inside the design (₹0, on the phone)

  const VMAX = 20, VBYTES = 900000;
  /** Keeps how the board is now (why: what is about to happen; named: he asked, kept longest). */
  function verSnap(why, named) {
    if (!D) return null;
    const d = JSON.parse(JSON.stringify({ name: D.name, title: D.title, board: D.board, parts: D.parts, nets: D.nets, tracks: D.tracks, vias: D.vias, labels: D.labels, pour: D.pour, fab: D.fab }));
    const v = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), at: Date.now(), why: String(why || '').slice(0, 60), named: !!named, d };
    D.vers = (Array.isArray(D.vers) ? D.vers : []).concat([v]);
    while (D.vers.length > VMAX || (D.vers.length > 1 && JSON.stringify(D.vers).length > VBYTES)) {
      const i = D.vers.findIndex((x) => !x.named);
      D.vers.splice(i >= 0 ? i : 0, 1);
    }
    return v;
  }
  /** What changed from a to b, in short Telugu lines. */
  function verDiff(a, b) {
    const out = [], pa = new Map(a.parts.map((p) => [p.ref, p])), pb = new Map(b.parts.map((p) => [p.ref, p]));
    const nm = (p) => p.ref + (p.val ? ' (' + p.val + ')' : '');
    const added = b.parts.filter((p) => !pa.has(p.ref)), gone = a.parts.filter((p) => !pb.has(p.ref));
    if (added.length) out.push('➕ కొత్తవి: ' + added.map(nm).join(', '));
    if (gone.length) out.push('➖ తీసేసినవి: ' + gone.map(nm).join(', '));
    const vals = [];
    b.parts.forEach((p) => { const o = pa.get(p.ref); if (o && ((o.val || '') !== (p.val || '') || o.lib !== p.lib || (o.color || '') !== (p.color || ''))) vals.push(p.ref + ': ' + (o.val || o.lib) + ' → ' + (p.val || p.lib)); });
    if (vals.length) out.push('✏️ ' + vals.join(' · '));
    const moved = b.parts.filter((p) => { const o = pa.get(p.ref); return o && (Math.abs(o.x - p.x) > 0.05 || Math.abs(o.y - p.y) > 0.05 || (o.rot || 0) !== (p.rot || 0)); }).length;
    if (moved) out.push('↔️ ' + moved + ' భాగాలు జరిగాయి / తిరిగాయి');
    const pins = (d) => { const m = new Map(); d.nets.forEach((n) => n.p.forEach((q) => m.set(q, n.n))); return m; };
    const ma = pins(a), mb = pins(b);
    let nets = 0;
    new Set(Array.from(ma.keys()).concat(Array.from(mb.keys()))).forEach((k) => { if (ma.get(k) !== mb.get(k)) nets++; });
    if (nets) out.push('🔗 ' + nets + ' పిన్నుల కలుపులు మారాయి');
    if (a.board.w !== b.board.w || a.board.h !== b.board.h) out.push('📏 బోర్డ్ ' + a.board.w + '×' + a.board.h + ' → ' + b.board.w + '×' + b.board.h + ' mm');
    if ((a.tracks || []).length !== (b.tracks || []).length) out.push('⚡ దారులు ' + (a.tracks || []).length + ' → ' + (b.tracks || []).length);
    return out.length ? out : ['తేడా ఏమీ లేదు (ఇప్పుడున్నట్టే)'];
  }
  function verSheet() {
    if (!D) { toast('ముందు ఒక బోర్డ్ తెరవండి (📚 బోర్డ్‌లు)'); return; }
    const vs = (D.vers || []).slice().reverse();
    sheet((s) => {
      s.appendChild(el('h2', null, '🕑 వెర్షన్లు (' + vs.length + ')'));
      s.appendChild(el('div', 'muted', 'ప్రతి AI మార్పు ముందు, మీరు 📌 నొక్కినప్పుడు ఇప్పటి బోర్డ్ ఇక్కడ దాచుకుంటుంది (ఫోన్‌లోనే, ₹0). పాతది తెరిస్తే ఇప్పుడున్నది కూడా ముందే ఇక్కడ దాచుకుంటుంది: ఏదీ పోదు.'));
      const add = el('div', 'row');
      const inp = el('input'); inp.type = 'text'; inp.placeholder = 'పేరు (ఉదా: మొదటి ఆర్డర్ ముందు)'; inp.maxLength = 40; inp.style.flex = '1';
      add.appendChild(inp);
      add.appendChild(btn('📌 ఇప్పటిది దాచు', 'main', () => { verSnap('📌 ' + (inp.value.trim() || 'నా వెర్షన్ ' + ((D.vers || []).length + 1)), true); changed(); verSheet(); toast('📌 దాచాను'); }));
      s.appendChild(add);
      if (!vs.length) s.appendChild(el('div', 'muted', 'ఇంకా ఏమీ లేవు.'));
      vs.forEach((v) => {
        const c = el('div', 'card ver' + (v.named ? ' named' : ''));
        c.appendChild(el('div', 't', v.why || 'వెర్షన్'));
        c.appendChild(el('div', 'd', when(v.at) + ' · ' + v.d.parts.length + ' భాగాలు · ' + v.d.board.w + ' × ' + v.d.board.h + ' mm'));
        c.appendChild(el('div', 'd', 'ఇప్పటికీ దీనికీ తేడా: ' + verDiff(v.d, D).slice(0, 3).join(' · ')));
        const r = el('div', 'row');
        r.appendChild(btn('↩️ ఇది తెరువు', 'small', () => {
          verSnap('↩️ "' + (v.why || 'వెర్షన్') + '" తెరిచే ముందు ఉన్నది');
          snap();
          const d = JSON.parse(JSON.stringify(v.d));
          ['board', 'parts', 'nets', 'tracks', 'vias', 'labels', 'pour', 'fab', 'name', 'title'].forEach((k) => { if (d[k] !== undefined) D[k] = d[k]; });
          pv.fit = true; sv.fit = true; simv.fit = true; sim.key = '';
          changed();
          closeSheet();
          toast('↩️ "' + (v.why || 'వెర్షన్') + '" తెరిచాను. ఇంతకు ముందున్నది కూడా 🕑 లో ఉంది.', 4000);
        }));
        r.appendChild(btn('🔍 తేడాలు', 'small', () => resultSheet('🔍 ఇప్పటి బోర్డ్‌కీ "' + (v.why || 'వెర్షన్') + '" కీ తేడా', ['(ఆ వెర్షన్ నుంచి ఇప్పటికి ఏం మారిందో)'].concat(verDiff(v.d, D)), [btn('🕑 వెనక్కి', '', verSheet)])));
        r.appendChild(btn('🗑️', 'small', () => {
          clear(c);
          c.appendChild(el('div', null, '🗑️ "' + (v.why || 'వెర్షన్') + '" నిజంగా తీసేయాలా? (తిరిగి రాదు)'));
          const r2b = el('div', 'row');
          r2b.appendChild(btn('అవును, తీసేయి', 'bad', () => { D.vers = (D.vers || []).filter((x) => x.id !== v.id); changed(); verSheet(); }));
          r2b.appendChild(btn('వద్దు', '', verSheet));
          c.appendChild(r2b);
        }));
        c.appendChild(r);
        s.appendChild(c);
      });
      const r = el('div', 'row'); r.appendChild(btn('మూసేయి', '', closeSheet)); s.appendChild(r);
    });
  }

  // ================================================================ 💻 KiCad files for the laptop (pcbkicad.js, ₹0)

  function kicadZip() {
    if (!D) return;
    try {
      const files = KI.files(D, schLayout()), z = F.zip(files), name = F.slug(D) + '-kicad-' + new Date().toISOString().slice(0, 10) + '.zip';
      zipKind = 'kicad';
      post({ t: 'zip', name, data: F.b64(z), title: D.name, kind: 'kicad' });
      toast('💻 KiCad ఫైల్స్ తయారు చేశాను (' + Math.round(z.length / 1024) + ' KB); సేవ్ చేస్తున్నాను…');
    } catch (e) { toast('KiCad ఫైల్స్ తయారు కాలేదు: ' + String((e && e.message) || e).slice(0, 80), 4000); }
  }
  function kicadCard(h) {
    h.appendChild(el('h3', null, '💻 KiCad ఫైల్స్ (ల్యాప్‌టాప్ కోసం)'));
    h.appendChild(el('div', 'muted', 'ఇదే బోర్డ్ (PCB + స్కీమాటిక్) KiCad అనే ఉచిత ప్రోగ్రామ్‌లో (kicad.org, 8 లేదా కొత్తది) ల్యాప్‌టాప్‌లో తెరవడానికి. భాగాల ఆకారాలు (ఫుట్‌ప్రింట్‌లు) ఫైల్ లోపలే ఉంటాయి. ఫ్యాక్టరీకి పంపాల్సింది పైన 📦 Gerber zip మాత్రమే.'));
    const r = el('div', 'row');
    r.appendChild(btn('💻 KiCad ఫైల్స్ తయారు చేసి సేవ్ చేయి', '', kicadZip));
    h.appendChild(r);
    if (saved && saved.kind === 'kicad') {
      const c = el('div', 'card');
      c.appendChild(el('div', 'okc', '💾 సేవ్ అయింది: ' + saved.where));
      const r3 = el('div', 'row');
      r3.appendChild(btn('📤 పంపు / షేర్', '', () => post({ t: 'share' })));
      c.appendChild(r3);
      c.appendChild(el('div', 'muted', 'ల్యాప్‌టాప్‌లో: zip విప్పి .kicad_pro తెరవండి. GND రాగి నింపడానికి PCB Editor లో B నొక్కండి.'));
      h.appendChild(c);
    }
  }

  // ================================================================ Build 3B: 🔧 soldering guide, 📦 box, 📏 real size / AR, 🎓 class


  function makeOf() { return (D && D.make) || {}; }
  function setMake(k, v) { if (!D) return; D.make = Object.assign({}, D.make || {}, { [k]: v }); saveSoon(); }
  function saveSoon() { if (!D) return; D.at = Date.now(); clearTimeout(saveT); saveT = setTimeout(() => post({ t: 'save', d: C.clean(D) }), 1200); }
  /** 🔊 Jarvis reads it in his chosen voice (his tap, or he asked by voice); ⏹ stops it. */
  function readAloud(text) { const s = String(text || '').trim(); if (s) post({ t: 'read', s: s.slice(0, 4000) }); }
  function hush() { post({ t: 'hush' }); }
  function speakBtn(text, cls) { return btn(speaking ? '⏹ ఆపు' : '🔊 వినిపించు', (cls || 'small') + ' spk', () => { if (speaking) hush(); else readAloud(text); }); }
  function speakLook() { document.querySelectorAll('.btn.spk').forEach((b) => { b.textContent = speaking ? '⏹ ఆపు' : '🔊 వినిపించు'; }); }
  /** The 3D module, loaded once (then fn). */
  const v3dWait = [];
  function with3d(fn) {
    if (v3d) { fn(); return; }
    v3dWait.push(fn);
    if (v3dLoading) return;
    v3dLoading = true;
    import('./pcb3d.js').then((m) => {
      v3d = m; v3dLoading = false;
      if (tab === 'd3') { v3d.open(api3d()); tools3d(); }
      v3dWait.splice(0).forEach((f) => { try { f(); } catch (e) { toast('3D: ' + String((e && e.message) || e).slice(0, 80)); } });
    }).catch(() => { v3dLoading = false; v3dWait.length = 0; toast('3D తెరవలేకపోయాను'); });
  }
  /** What the board should do on its first power-on (from ⚡). */
  function simExpect() {
    try { if (sim.key !== D.id || !sim.rep) simBuild(); } catch (e) { return ''; }
    if (!sim.rep) return '';
    const bits = [];
    if (sim.cy && sim.cy.f && !sim.cy.stuck) bits.push('555 సెకనుకి సుమారు ' + (Math.round(sim.cy.f * 10) / 10) + ' సార్లు టిక్ టిక్ (LED లు అలా మెరుస్తాయి)');
    if (sim.rep.total && sim.rep.total.i > 1e-4) bits.push('బోర్డ్ మొత్తం సుమారు ' + SIM.fmtA(sim.rep.total.i) + ' తీసుకుంటుంది');
    return bits.join('; ');
  }
  /** Leaving a board / opening another: the guide and the box go (each board starts fresh; his step is kept in the board). */
  function makeReset() {
    sg.on = false; sg.steps = null; bx.on = false; bx.plan = null;
    ['guide', 'boxcard'].forEach((id) => { const e = $(id); if (e) e.classList.remove('on'); });
    if (v3d) { v3d.guide(null); v3d.boxShow(null); }
    if (rs.on) closeReal();
  }
  /** (after any change) the guide's steps and the box follow the board. */
  function makeFollow() {
    if (!D) return;
    if (sg.on) { sg.steps = MK.solderSteps(D, { simLine: '' }); sg.i = Math.min(sg.i, sg.steps.length - 1); if (tab === 'd3') guideShow(false); }
    if (bx.on || (xrS.on && xrS.box)) { bx.plan = MK.boxPlan(D, { extra: makeOf().extra, vents: makeOf().vents }); if (v3d) v3d.boxShow(bx.plan); if (bx.on && tab === 'd3') boxCard(); }
    if (rs.on) drawReal();
  }

  // ---------------------------------------------------------------- 🔧 the soldering guide (3D: the part to solder glows; 🔊 Telugu)

  // ================================================================ 📷 Build C: the soldering guide drawn on his real board (pcbcam.js)

  let camv = null;
  function camView() {
    if (camv || !window.PcbCam) return camv;
    camv = window.PcbCam.camApi({
      clear, el, btn, toast, post,
      design: () => D, model: () => M,
      step: () => (sg.on && sg.steps ? sg.steps[sg.i] : null),
      go: (d) => guideGo(d),
      speak: () => { const st = sg.on && sg.steps ? sg.steps[sg.i] : null; return speakBtn(st ? st.say : ''); },
      done: () => {
        const out = D.parts.filter((q) => P.isSmd(q)).map((q) => q.ref);
        if (sg.on && sg.steps) sg.steps.slice(0, sg.i).forEach((s) => { if (s.k === 'part') s.refs.forEach((r) => out.push(r)); });
        return out;
      },
      court: (pt) => C.courtOf(pt, pt.x, pt.y, pt.rot || 0),
      polar: (pt) => !!(P.LIB[pt.lib] || {}).polar,
      mark: (pt) => {
        const L = P.LIB[pt.lib] || {};
        if (!L.polar) return '';
        const n1 = String(P.pinsOf(pt)['1'] || '1');
        return '■ = ' + (n1 === 'K' ? 'K (−)' : n1 === '+' ? '+' : /^\d+$/.test(n1) ? 'పిన్ 1' : n1);
      }
    });
    $('camcv').addEventListener('pointerup', (e) => camv.tap(e));
    return camv;
  }
  /** 📷 His tap: the guide on (where he stopped), then the camera view over everything. */
  function camOpen() {
    if (!D) return '';
    const v = camView();
    if (!v) return '';
    if (!sg.on) startGuide();
    if (xrS.on || xrS.wait) stopAr();
    if (rs.on) closeReal();
    v.open();
    return '📷 బోర్డ్ మీద టంకం గైడ్: బోర్డ్ మీద ఒకసారి నొక్కండి.';
  }
  function startGuide(at) {
    if (!D) return '';
    if (xrS.on) stopAr();
    bx.on = false; $('boxcard').classList.remove('on');
    if (v3d) v3d.boxShow(null);
    sg.steps = MK.solderSteps(D, { simLine: simExpect() });
    const kept = makeOf().solder | 0;
    sg.i = Math.max(0, Math.min(sg.steps.length - 1, at != null ? at : kept >= sg.steps.length - 1 ? 0 : kept));
    sg.on = true;
    if (tab !== 'd3') showTab('d3'); else open3d();
    return '🔧 టంకం గైడ్: ' + sg.steps.length + ' అడుగులు. ' + (sg.i ? 'మీరు ఆగిన అడుగు ' + (sg.i + 1) + ' నుంచి.' : '');
  }
  function stopGuide() {
    sg.on = false;
    $('guide').classList.remove('on');
    fit3d();
    if (v3d) { v3d.guide(null); v3d.view('side'); }
    hush();
    tools3d();
  }
  function guideShow(move) {
    if (!sg.on || !sg.steps) return;
    const st = sg.steps[sg.i];
    const done = D.parts.filter((q) => P.isSmd(q)).map((q) => q.ref); // (🏭 the factory's SMD parts are on the board from the start)
    sg.steps.slice(0, sg.i).forEach((s) => { if (s.k === 'part') s.refs.forEach((r) => done.push(r)); });
    const g = clear($('guide'));
    g.classList.add('on');
    const hd = el('div', 'gh');
    hd.appendChild(el('span', 'gn', st.n + '/' + st.of));
    hd.appendChild(el('span', 'gt', st.title));
    g.appendChild(hd);
    const bar = el('div', 'gbar'), fill = el('i');
    fill.style.width = Math.round(st.n / st.of * 100) + '%';
    bar.appendChild(fill);
    g.appendChild(bar);
    st.lines.forEach((l) => g.appendChild(el('div', 'gl', l)));
    const r = el('div', 'row');
    r.appendChild(btn('◀️', 'small', () => guideGo(-1)));
    r.appendChild(speakBtn(st.say));
    r.appendChild(btn(st.n === st.of ? '🎉 పూర్తి' : '▶️ తర్వాత', 'main', () => (st.n === st.of ? guideEnd() : guideGo(1))));
    r.appendChild(btn(sg.auto ? '🔁 ✓' : '🔁', sg.auto ? 'small on' : 'small', () => {
      sg.auto = !sg.auto;
      toast(sg.auto ? '🔁 ఇక ప్రతి అడుగూ Jarvis చదువుతుంది' : '🔁 ఆపాను: 🔊 నొక్కితేనే చదువుతుంది', 2500);
      guideShow(null);
      if (sg.auto) readAloud(st.say);
    }));
    g.appendChild(r);
    if (sg.i < 3) g.appendChild(el('div', 'muted', '🎙️ చేతులు ఖాళీ లేకపోతే: "Jarvis తర్వాత" / "Jarvis వెనక్కి" / "Jarvis మళ్ళీ చెప్పు". 🔁 = ప్రతి అడుగూ తానే చదువుతుంది.'));
    g.scrollTop = 0;
    tools3d();
    fit3d();
    if (v3d && move !== null) v3d.guide({ done, cur: st.k === 'part' || st.k === 'chips' || st.k === 'smd' ? st.refs : [], all: st.show === 'all', none: st.show === 'none', bottom: sg.bottom }, !!move);
  }
  /** The 3D picture uses the space above the card (so the part to solder is never under it). */
  function fit3d() {
    const host = $('v3d'), c = ['guide', 'boxcard'].map($).find((e) => e.classList.contains('on'));
    const b = c && !xrS.on ? c.offsetHeight + 64 : 0;
    if (host.style.bottom !== b + 'px') { host.style.bottom = b + 'px'; if (v3d) v3d.resized(); }
  }
  function guideGo(d, voice) {
    if (!sg.on || !sg.steps) return;
    const i = Math.max(0, Math.min(sg.steps.length - 1, sg.i + d));
    if (i === sg.i && d) { if (voice) readAloud(d > 0 ? 'ఇదే చివరి అడుగు.' : 'ఇదే మొదటి అడుగు.'); return; }
    sg.i = i;
    setMake('solder', i);
    guideShow(true);
    if (voice || sg.auto) readAloud('అడుగు ' + sg.steps[i].n + '. ' + sg.steps[i].say);
  }
  function guideEnd() {
    setMake('solder', 0);
    resultSheet('🎉 టంకం అయిపోయింది!', ['బోర్డ్ పనిచేస్తే: 📦 డబ్బా తయారు చేసుకోవచ్చు (3D ప్రింట్).', 'పనిచేయకపోతే: 🔍 చెక్ 1, 📏 చెక్ 2 మళ్ళీ చూడండి; ⚡ నడిపించు లో చూపిన వోల్టేజ్‌లు మల్టీమీటర్‌తో పోల్చండి.'],
      [btn('📦 డబ్బా', 'main', () => { closeSheet(); stopGuide(); startBox(); })]);
    stopGuide();
  }
  function guideList() {
    if (!sg.steps) return;
    sheet((s) => {
      s.appendChild(el('h2', null, '📋 టంకం అడుగులు (' + sg.steps.length + ')'));
      s.appendChild(el('div', 'muted', 'ఏ అడుగు మీద నొక్కితే అక్కడికి వెళ్తుంది.'));
      sg.steps.forEach((st, i) => {
        const d = el('div', 'step' + (i === sg.i ? ' cy' : ''), (i < sg.i ? '✅ ' : i === sg.i ? '▶️ ' : '▫️ ') + st.n + '. ' + st.title);
        d.addEventListener('click', () => { closeSheet(); sg.i = i; setMake('solder', i); guideShow(true); });
        s.appendChild(d);
      });
      const r = el('div', 'row'); r.appendChild(btn('మూసేయి', '', closeSheet)); s.appendChild(r);
    });
  }
  /** His words while the guide is on: next / back / again (₹0). */
  function guideWords(w) {
    if (!sg.on) return false;
    const s = w.toLowerCase();
    if (/(తర్వాత|తరువాత|next|ముందుకు|అయింది|అయిపోయింది|done)/.test(s)) { guideGo(1, true); return true; }
    if (/(వెనక్కి|వెనుకకు|ముందుది|previous|back)/.test(s)) { guideGo(-1, true); return true; }
    if (/(మళ్ళీ|మళ్లీ|repeat|again|ఏమిటి|ఏంటి)/.test(s)) { readAloud('అడుగు ' + sg.steps[sg.i].n + '. ' + sg.steps[sg.i].say); return true; }
    if (/(గైడ్\s*ఆపు|stop\s*guide|టంకం\s*ఆపు)/.test(s)) { stopGuide(); return true; }
    return false;
  }

  // ---------------------------------------------------------------- 📦 the box (3D; STL files for a 3D printer)

  function startBox() {
    if (!D) return '';
    if (sg.on) { sg.on = false; $('guide').classList.remove('on'); if (v3d) v3d.guide(null); }
    bx.on = true;
    bx.plan = MK.boxPlan(D, { extra: makeOf().extra, vents: makeOf().vents });
    if (tab !== 'd3') showTab('d3'); else open3d();
    const s = bx.plan.size;
    return '📦 డబ్బా: ' + s.w + ' × ' + s.d + ' × ' + s.h + ' mm.';
  }
  function stopBox() {
    bx.on = false;
    $('boxcard').classList.remove('on');
    fit3d();
    if (v3d) v3d.boxShow(null);
    tools3d();
  }
  function boxCard() {
    const c = clear($('boxcard')), p = bx.plan;
    if (!p) return;
    c.classList.add('on');
    const s = p.size;
    c.appendChild(el('div', 'gt', '📦 డబ్బా: ' + s.w + ' × ' + s.d + ' × ' + s.h + ' mm (బయట)'));
    c.appendChild(el('div', 'muted', 'లోపల భాగాలకి ఎత్తు ' + p.box.room + ' mm (పొడవైనది ' + p.box.tref + ', ' + p.box.tallest + ' mm' + (p.box.extra ? '; మీరు ' + (p.box.extra > 0 ? '+' : '') + p.box.extra + ' mm' : '') + ')'));
    p.warns.forEach((w) => c.appendChild(el('div', 'gl warnc', w)));
    const r = el('div', 'row');
    r.appendChild(btn(bx.open ? '🔽 మూత పెట్టు' : '🔼 మూత తీయి', 'small', () => { bx.open = !bx.open; v3d.boxLook({ open: bx.open }); boxCard(); v3d.view('side'); }));
    r.appendChild(btn('👁️ లోపల', bx.see ? 'small on' : 'small', () => { bx.see = !bx.see; v3d.boxLook({ see: bx.see }); boxCard(); }));
    r.appendChild(btn('ℹ️', 'small', boxSheet));
    c.appendChild(r);
    const r2 = el('div', 'row');
    r2.appendChild(el('span', 'muted', 'ఎత్తు'));
    r2.appendChild(btn('➖', 'small', () => boxTaller(-2)));
    r2.appendChild(btn('➕', 'small', () => boxTaller(2)));
    r2.appendChild(btn('💾 STL ఫైల్స్ సేవ్', 'main', boxZip));
    c.appendChild(r2);
    if (saved && saved.kind === 'box') {
      const r3 = el('div', 'row');
      r3.appendChild(el('span', 'okc', '💾 ' + saved.where));
      r3.appendChild(btn('📤 పంపు / షేర్', 'small', () => post({ t: 'share' })));
      c.appendChild(r3);
    }
    fit3d();
  }
  function boxTaller(d) {
    const e = Math.max(-8, Math.min(40, (makeOf().extra | 0) + d));
    setMake('extra', e);
    bx.plan = MK.boxPlan(D, { extra: e, vents: makeOf().vents });
    if (v3d) v3d.boxShow(bx.plan);
    boxCard();
  }
  function boxSheet() {
    const p = bx.plan;
    if (!p) return;
    sheet((s) => {
      s.appendChild(el('h2', null, '📦 డబ్బా వివరాలు'));
      ['బయట ' + p.size.w + ' × ' + p.size.d + ' × ' + p.size.h + ' mm · గోడలు ' + p.K.wall + ' mm · నేల ' + p.K.floor + ' mm · మూత ' + p.K.lid + ' mm.',
        'బోర్డ్ నేల నుంచి ' + p.K.post + ' mm పైన (కింద కాళ్ళకి చోటు). మూత లోపలి 4 మూలల అంచులతో నొక్కితే పట్టుకుంటుంది.']
        .concat(p.notes, p.warns).forEach((l) => s.appendChild(el('div', 'step', l)));
      const r = el('div', 'row');
      const v = p.box.ventsOn;
      r.appendChild(btn(v ? '🌬️ గాలి సందులు ✓' : '🌬️ గాలి సందులు', v ? 'on' : '', () => { setMake('vents', !v); bx.plan = MK.boxPlan(D, { extra: makeOf().extra, vents: !v }); if (v3d) v3d.boxShow(bx.plan); boxCard(); boxSheet(); }));
      s.appendChild(r);
      s.appendChild(el('div', 'muted', '🖨️ ప్రింట్: PLA, 0.2 mm పొర, 3 గోడలు, 20% infill, సపోర్ట్ అక్కర్లేదు. base.stl ఉన్నట్టే; lid.stl ఇప్పటికే తిప్పి ఉంది (పై ముఖం బెడ్ మీద). ఇంట్లో ప్రింటర్ లేకపోతే 3D ప్రింటింగ్ సర్వీస్‌కి మీరే అప్‌లోడ్ చేసి మీరే ఆర్డర్ చేయండి.'));
      s.appendChild(el('div', 'muted', '⚠️ భాగాలు కొన్నాక ఎత్తులు కొలిచి చూడండి (మాడ్యూల్స్ వేరే ఎత్తు ఉండొచ్చు): ➕ ➖ తో మార్చొచ్చు.'));
      const r2 = el('div', 'row'); r2.appendChild(btn('మూసేయి', '', closeSheet)); s.appendChild(r2);
    });
  }
  function boxZip() {
    if (!D || !bx.plan) return;
    with3d(() => {
      try {
        const out = v3d.boxStl(bx.plan), dir = F.slug(D) + '-box/';
        const files = [{ name: dir + 'base.stl', bytes: out.base }, { name: dir + 'lid.stl', bytes: out.lid },
          { name: dir + 'README.txt', text: MK.boxReadme(D, bx.plan, { base: out.baseTris, lid: out.lidTris }) }];
        const z = F.zip(files), name = F.slug(D) + '-box-' + new Date().toISOString().slice(0, 10) + '.zip';
        zipKind = 'box';
        post({ t: 'zip', name, data: F.b64(z), title: D.name, kind: 'box' });
        toast('📦 STL ఫైల్స్ తయారు చేశాను (' + Math.round(z.length / 1024) + ' KB); సేవ్ చేస్తున్నాను…');
      } catch (e) { toast('STL తయారు కాలేదు: ' + String((e && e.message) || e).slice(0, 80), 4000); }
    });
  }

  // ---------------------------------------------------------------- 📏 the board at its real size: on the screen (1:1) and on his table (AR)

  function pxmm() { const dpr = window.devicePixelRatio || 1, x = scr.xdpi > 60 ? scr.xdpi : 160 * dpr; return x / 25.4 / dpr * (scr.k || 1); }
  function openReal() {
    if (!D) return '';
    rs.on = true; rs.fit = true; rs.cal = false;
    $('real').classList.add('on');
    realBars();
    requestAnimationFrame(drawReal);
    return '📏 బోర్డ్ ఇప్పుడు స్క్రీన్ మీద అసలు సైజులో (' + D.board.w + ' × ' + D.board.h + ' mm).';
  }
  function closeReal() { rs.on = false; $('real').classList.remove('on'); }
  function realBars() {
    const t = clear($('rtop'));
    t.appendChild(btn('✕', 'small', closeReal));
    const n = el('div', 'rname');
    n.appendChild(el('div', null, '📏 అసలు సైజు (1:1)'));
    n.appendChild(el('small', null, D.board.w + ' × ' + D.board.h + ' mm · వేలితో జరపండి'));
    t.appendChild(n);
    const b = clear($('rbot'));
    if (rs.cal) {
      b.appendChild(el('div', 'muted', '🎯 స్క్రీన్ మీద ఒక స్కేల్ (రూలర్) పెట్టండి: పై గీతల్లో 0 నుంచి 5 వరకు సరిగ్గా 5 cm ఉండాలి. తేడా ఉంటే ➖ ➕ నొక్కండి.'));
      const r = el('div', 'row');
      r.appendChild(btn('➖', 'small', () => realK(-0.005)));
      r.appendChild(el('span', 'val', (Math.round((scr.k || 1) * 1000) / 10) + '%'));
      r.appendChild(btn('➕', 'small', () => realK(0.005)));
      r.appendChild(btn('✓ సరిపోయింది', 'main small', () => { rs.cal = false; realBars(); }));
      b.appendChild(r);
    } else {
      b.appendChild(el('div', 'muted', '👉 కొన్న భాగాన్ని (రెసిస్టర్, LED, మాడ్యూల్, USB సాకెట్) స్క్రీన్ మీద మెల్లగా పెట్టండి: దాని కాళ్ళు గుండ్రటి ప్యాడ్‌ల మీద పడాలి. పడకపోతే ఆర్డర్ ముందే చెప్పండి.'));
      const r = el('div', 'row');
      r.appendChild(btn('🎯 కొలత సరిచూడు', 'small', () => { rs.cal = true; realBars(); }));
      r.appendChild(btn('📷 AR: టేబుల్ మీద', 'small', () => { closeReal(); startAr(); }));
      b.appendChild(r);
    }
  }
  function realK(d) { scr.k = Math.max(0.8, Math.min(1.25, Math.round(((scr.k || 1) + d) * 1000) / 1000)); post({ t: 'scale', k: scr.k }); realBars(); drawReal(); }
  function drawReal() {
    if (!rs.on || !D) return;
    const cv = $('realcv'), { ctx, w, h } = sizeCv(cv), s = pxmm(), B = D.board;
    const top = 46 + 26, bot = ($('rbot').offsetHeight || 90) + 16;
    if (rs.fit) {
      rs.ox = B.w * s <= w - 40 ? (w - B.w * s) / 2 : 30;
      rs.oy = B.h * s <= h - top - bot - 20 ? top + (h - top - bot - B.h * s) / 2 : top + 16;
      rs.fit = false;
    }
    ctx.fillStyle = '#05070a'; ctx.fillRect(0, 0, w, h);
    drawBoard(ctx, D, M, fills, { s, ox: rs.ox, oy: rs.oy }, { real: true });
    // the rulers: millimetres from the board's top-left corner, numbers every centimetre
    ctx.fillStyle = 'rgba(14,20,28,.9)'; ctx.fillRect(0, 46, w, 24); ctx.fillRect(0, 70, 24, h - 70);
    ctx.strokeStyle = '#dceef5'; ctx.fillStyle = '#dceef5'; ctx.lineWidth = 1; ctx.font = '10px ' + FONT; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    const mm0x = Math.ceil(-rs.ox / s), mm1x = Math.floor((w - rs.ox) / s);
    ctx.beginPath();
    for (let k = mm0x; k <= mm1x; k++) { const x = rs.ox + k * s, L = k % 10 === 0 ? 10 : k % 5 === 0 ? 7 : 4; ctx.moveTo(x, 70); ctx.lineTo(x, 70 - L); }
    ctx.stroke();
    for (let k = Math.max(0, Math.ceil(mm0x / 10) * 10); k <= mm1x; k += 10) ctx.fillText(String(k / 10), rs.ox + k * s, 47);
    const mm0y = Math.ceil((70 - rs.oy) / s), mm1y = Math.floor((h - rs.oy) / s);
    ctx.beginPath();
    for (let k = mm0y; k <= mm1y; k++) { const y = rs.oy + k * s, L = k % 10 === 0 ? 10 : k % 5 === 0 ? 7 : 4; ctx.moveTo(24, y); ctx.lineTo(24 - L, y); }
    ctx.stroke();
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    for (let k = Math.max(10, Math.ceil(mm0y / 10) * 10); k <= mm1y; k += 10) ctx.fillText(String(k / 10), 2, rs.oy + k * s);
    ctx.textAlign = 'left'; ctx.textBaseline = 'top'; ctx.fillStyle = '#88a5b3';
    ctx.fillText('cm', w - 22, 58);
  }
  (function realTouch() {
    const cv = $('realcv');
    cv.addEventListener('pointerdown', (e) => { rs.drag = { x: e.clientX, y: e.clientY, ox: rs.ox, oy: rs.oy }; cv.setPointerCapture && cv.setPointerCapture(e.pointerId); });
    cv.addEventListener('pointermove', (e) => { if (!rs.drag) return; rs.ox = rs.drag.ox + e.clientX - rs.drag.x; rs.oy = rs.drag.oy + e.clientY - rs.drag.y; drawReal(); });
    const up = () => { rs.drag = null; };
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
    window.addEventListener('resize', () => { if (rs.on) { rs.fit = true; drawReal(); } });
  })();

  const XR_TE = {
    look: '📷 ఫోన్‌ని టేబుల్ వైపు చూపి నెమ్మదిగా అటూ ఇటూ కదపండి…',
    floor: '✅ టేబుల్ దొరికింది: నీలం రింగ్ ఉన్న చోట తాకండి, బోర్డ్ అక్కడ అసలు సైజులో కూర్చుంటుంది.',
    dark: '💡 వెలుతురు తక్కువగా ఉంది: లైట్ వేయండి.', fast: '🐢 ఫోన్ ఇంకా నెమ్మదిగా కదపండి.',
    plain: '🔲 టేబుల్ మీద గుర్తులు కనిపించడం లేదు: పుస్తకం / గుడ్డ ఉన్న చోటు చూపండి.', nofloor: '✋ అక్కడ టేబుల్ దొరకలేదు: నీలం రింగ్ కనిపించే చోట తాకండి.'
  };
  function startAr() {
    if (!D) return '';
    if (rs.on) closeReal();
    if (tab !== 'd3') showTab('d3'); else open3d();
    xrS.wait = true; xrS.st = '';
    xrInfo('📷 AR మొదలవుతోంది…');
    tools3d();
    with3d(() => post({ t: 'xr', on: true }));
    return '📷 కెమెరాతో టేబుల్ మీద చూపిస్తున్నాను.';
  }
  function stopAr() { post({ t: 'xr', on: false }); xrLeave(); }
  function xrEnter() {
    xrS.on = true; xrS.wait = false;
    document.body.classList.add('xr'); document.documentElement.classList.add('xr');
    if (tab !== 'd3') showTab('d3');
    $('v3d').style.bottom = '0px';
    with3d(() => {
      v3d.open(api3d());
      if (xrS.box) { bx.plan = bx.plan || MK.boxPlan(D, { extra: makeOf().extra, vents: makeOf().vents }); v3d.boxShow(bx.plan, { open: false, see: false }); } else if (!bx.on) v3d.boxShow(null);
      v3d.xrOn(true);
    });
    xrInfo(XR_TE.look);
    tools3d();
  }
  function xrLeave() {
    const was = xrS.on || xrS.wait;
    xrS.on = false; xrS.wait = false; xrS.st = '';
    document.body.classList.remove('xr'); document.documentElement.classList.remove('xr');
    if (!was) return;
    if (v3d) { v3d.xrOn(false); v3d.boxShow(bx.on ? bx.plan : null, bx.on ? { open: bx.open, see: bx.see } : null); }
    if (tab === 'd3') open3d();
  }
  function xrInfo(te) {
    const i = clear($('v3dinfo'));
    i.appendChild(el('span', 'chip', te));
    if (xrS.st === 'placed') i.appendChild(el('span', 'chip ok', '📏 ' + D.board.w + ' × ' + D.board.h + ' mm · అసలు సైజు · ఒక వేలితో తిప్పండి'));
  }
  function xrSaid(s) {
    if (!xrS.on) return;
    if (s === 'nofloor') { xrInfo(XR_TE.nofloor); return; }
    xrS.st = s;
    xrInfo(s === 'placed' ? '✅ ఇదే మీ బోర్డ్ అసలు సైజు. ఇంకో చోట తాకితే అక్కడికి జరుగుతుంది.' : XR_TE[s] || XR_TE.look);
  }

  // ---------------------------------------------------------------- 🎓 the 5-day Telugu class

  function learnDone() { return CL.DAYS.filter((d) => learnSt.d[d.n] && learnSt.d[d.n].done).length; }
  function learnNext() { const d = CL.DAYS.find((x) => !(learnSt.d[x.n] && learnSt.d[x.n].done)); return d ? d.n : 0; }
  function learnCard(h) {
    const c = el('div', 'card tap idea');
    const n = learnDone(), nx = learnNext();
    c.appendChild(el('div', 't', '🎓 5 రోజుల తెలుగు PCB క్లాస్ (₹0)'));
    c.appendChild(el('div', 'muted', n === 5 ? '🏆 5 రోజులూ పూర్తి! పాఠాలు మళ్ళీ చూడొచ్చు.' : n ? '✅ ' + n + '/5 రోజులు అయ్యాయి · తర్వాత: రోజు ' + nx + ' (' + CL.DAYS[nx - 1].title + ')'
      : 'కరెంట్ నుంచి టంకం, డబ్బా వరకు: రోజుకి 20–30 నిమిషాలు, పాఠాలు 🔊 వినొచ్చు, స్టూడియోలో చేసి చూడటం, చిన్న క్విజ్.'));
    c.addEventListener('click', () => { lv.day = 0; showTab('learn'); });
    h.appendChild(c);
  }
  function learnTab() {
    const h = clear($('learn'));
    if (lv.day) { learnDay(h, CL.DAYS[lv.day - 1]); return; }
    const top = el('div', 'card');
    top.appendChild(el('div', 't', '🎓 5 రోజుల్లో PCB: ఐడియా నుంచి చేతిలో పనిచేసే బోర్డ్ వరకు'));
    top.appendChild(el('div', 'muted', 'ప్రతి రోజు: చిన్న పాఠాలు (🔊 Jarvis చదువుతుంది), స్టూడియోలో చేసి చూడటం, 4 ప్రశ్నల క్విజ్. అంతా ఫోన్‌లోనే, ₹0. రోజుకి 20–30 నిమిషాలు.'));
    const dots = el('div', 'dots');
    CL.DAYS.forEach((d) => dots.appendChild(el('span', learnSt.d[d.n] && learnSt.d[d.n].done ? 'dot on' : 'dot', String(d.n))));
    top.appendChild(dots);
    h.appendChild(top);
    const nx = learnNext();
    CL.DAYS.forEach((d) => {
      const st = learnSt.d[d.n] || {};
      const c = el('div', 'card tap lday' + (st.done ? ' done' : d.n === nx ? ' next' : ''));
      c.appendChild(el('div', 't', d.emoji + ' రోజు ' + d.n + ': ' + d.title));
      c.appendChild(el('div', 'muted', d.goal));
      c.appendChild(el('div', st.done ? 'okc' : 'cy', st.done ? '✅ పూర్తి · క్విజ్ ' + (st.score | 0) + '/' + d.quiz.length : d.n === nx ? '▶️ ఇప్పుడు ఇది' : ''));
      c.addEventListener('click', () => { lv.day = d.n; learnTab(); $('learn').scrollTop = 0; });
      h.appendChild(c);
    });
    if (learnDone() === 5) {
      const c = el('div', 'card');
      c.appendChild(el('div', 'okc', '🏆 5 రోజులూ పూర్తి!'));
      c.appendChild(el('div', 'muted', 'ఇప్పుడు 📚 బోర్డ్‌లు లోని 💡 ఐడియా కార్డ్‌లో మీ సొంత బోర్డ్ చెప్పి చూడండి.'));
      h.appendChild(c);
    }
  }
  function learnDay(h, day) {
    const r0 = el('div', 'row');
    r0.appendChild(btn('◀️ రోజులు', 'small', () => { lv.day = 0; hush(); learnTab(); }));
    h.appendChild(r0);
    h.appendChild(el('h2', null, day.emoji + ' రోజు ' + day.n + ': ' + day.title));
    h.appendChild(el('div', 'muted', day.goal));
    day.parts.forEach((p) => {
      const c = el('div', 'card lp');
      const hr = el('div', 'row');
      hr.appendChild(el('div', 'k t', p.h));
      hr.appendChild(speakBtn(CL.said(p)));
      c.appendChild(hr);
      p.t.forEach((x) => c.appendChild(el('p', null, x)));
      h.appendChild(c);
    });
    h.appendChild(el('h3', null, '🛠️ చేసి చూడండి'));
    day.tasks.forEach((tk) => {
      const c = el('div', 'card');
      c.appendChild(el('div', null, tk.t));
      const r = el('div', 'row');
      r.appendChild(btn(tk.b, 'small', () => learnGo(tk.go)));
      c.appendChild(r);
      h.appendChild(c);
    });
    h.appendChild(el('h3', null, '❓ క్విజ్ (' + day.quiz.length + ' ప్రశ్నలు)'));
    const ans = lv.ans[day.n] || (lv.ans[day.n] = {});
    day.quiz.forEach((q, qi) => {
      const c = el('div', 'card');
      c.appendChild(el('div', 't', (qi + 1) + '. ' + q.q));
      const rot = (day.n * 2 + qi) % q.a.length, order = q.a.map((_, i) => (i + rot) % q.a.length);
      const picked = ans[qi];
      order.forEach((oi) => {
        const b = btn(q.a[oi], 'qopt' + (picked != null ? (oi === 0 ? ' ok' : oi === picked ? ' no' : '') : ''), () => {
          if (ans[qi] != null) return;
          ans[qi] = oi;
          const y = $('learn').scrollTop;
          learnTab();
          $('learn').scrollTop = y;
        });
        c.appendChild(b);
      });
      if (picked != null) c.appendChild(el('div', picked === 0 ? 'okc gl' : 'err gl', (picked === 0 ? '✅ సరైనది. ' : '❌ సరైనది: ' + q.a[0] + '. ') + q.why));
      h.appendChild(c);
    });
    const got = day.quiz.filter((q, qi) => ans[qi] === 0).length, all = day.quiz.every((q, qi) => ans[qi] != null);
    const st = learnSt.d[day.n] || {};
    const c = el('div', 'card');
    const pass = Math.ceil(day.quiz.length * 0.75);
    if (all && got < pass) {
      c.appendChild(el('div', 'warnc', '📝 క్విజ్: ' + got + '/' + day.quiz.length + ' సరైనవి. ' + pass + ' సరైతే రోజు పూర్తి: పైన పాఠాలు ఇంకోసారి చదివి (🔊 వినొచ్చు) క్విజ్ మళ్ళీ చేయండి.'));
      const r = el('div', 'row');
      r.appendChild(btn('🔁 క్విజ్ మళ్ళీ', 'main', () => { lv.ans[day.n] = {}; learnTab(); }));
      c.appendChild(r);
    } else if (all) {
      c.appendChild(el('div', got === day.quiz.length ? 'okc' : 'cy', '📝 క్విజ్: ' + got + '/' + day.quiz.length + ' సరైనవి.'));
      const r = el('div', 'row');
      r.appendChild(btn('✅ రోజు ' + day.n + ' పూర్తి', 'main', () => {
        learnSt.d[day.n] = { done: true, score: Math.max(got, st.score | 0), at: Date.now() };
        post({ t: 'learn', v: learnSt });
        hush();
        toast(day.n < 5 ? '✅ రోజు ' + day.n + ' పూర్తి! రేపు రోజు ' + (day.n + 1) + '.' : '🏆 5 రోజులూ పూర్తి!', 3500);
        lv.day = 0; learnTab(); $('learn').scrollTop = 0;
      }));
      r.appendChild(btn('🔁 క్విజ్ మళ్ళీ', 'small', () => { lv.ans[day.n] = {}; learnTab(); }));
      c.appendChild(r);
    } else c.appendChild(el('div', 'muted', 'అన్ని ప్రశ్నలకీ జవాబు ఇచ్చాక "రోజు పూర్తి" బటన్ వస్తుంది.' + (st.done ? ' (ఈ రోజు ఇంతకు ముందే పూర్తి ✅)' : '')));
    h.appendChild(c);
  }
  /** A task's button: the ready board (his open copy, his last copy, or a new copy), then the tab / guide / box / real size. */
  function learnGo(go) {
    hush();
    if (go.k === 'tab') { showTab(go.tab); return; }
    const then = () => {
      if (go.k === 'proj') showTab(go.tab);
      else if (go.k === 'guide') startGuide();
      else if (go.k === 'box') startBox();
      else if (go.k === 'real') openReal();
    };
    if (D && D.proj === go.id) { then(); return; }
    const mine = designs.filter((q) => q.proj === go.id).sort((a, b) => (b.at || 0) - (a.at || 0))[0];
    if (mine) { afterOpen = then; post({ t: 'open', id: mine.id }); return; }
    const pr = J.PROJECTS.find((q) => q.id === go.id);
    open(J.make(go.id));
    toast('✅ ' + (pr ? pr.te : '') + ': మీ కాపీ తెరిచాను (📂 నా బోర్డ్‌లు లో ఉంటుంది).', 3000);
    then();
  }

  window.__pcb = { open, get D() { return D; }, get check() { return check; }, showTab, reroute, changed, onJarvis, texture, partSheet: (ref) => partSheet(D && D.parts.find((q) => q.ref === ref)), modList, modInfo, wireSheet, buyGo, faultStart, get ft() { return ft; }, graphSheet, gr, advSheet, adv, fr, frStart, frMove, frEnd, frDelete, reviewDone, scanSheet, scanSt, forReview, pinStart, solderStart, get ph() { return ph; }, pinsDone, solderDone, pv, sv,
    ai, idea, editSheet, heard, lay: () => schLayout(), sim, simv, simBuild, simReport, simControls, simSupply, simPart, verSheet, verSnap, verDiff, kicadFiles: () => KI.files(D, schLayout()),
    sg, bx, rs, xrS, lv, startGuide, stopGuide, guideGo, guideWords, startBox, stopBox, boxTaller, boxZip, openReal, closeReal, drawReal, startAr, stopAr, xrEnter, xrLeave, xrSaid,
    learnTab, learnGo, get learnSt() { return learnSt; }, set scr(v) { scr = v; }, get v3d() { return v3d; },
    smdPlan, smdSheet, smdApply, smdBackSheet, pcbaList, pcbaHow, pcbaSave, camOpen, get camv() { return camv; } };
})();
