// 🔌 Jarvis PCB studio page (Anil, 23 Nov: "1 నుంచి 18 వరకు అన్ని చేయి"): ready boards, the schematic (tap a part: what it is
// in Telugu), the PCB (move / turn parts, auto-route, ground fill), ✅ the factory checks with Telugu reasons, 🧊 the board in
// 3D, 🏭 the factory files (.zip saved / shared / the factory's site only on his tap) and 🧮 the numbers. All our own code, ₹0,
// no network: designs are data, every word goes in with textContent. Jarvis (the app) talks to this page through one port.
'use strict';
(function () {
  const P = window.PcbParts, C = window.PcbCore, F = window.PcbFab, J = window.PcbProjects;
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
  let silkCache = null;
  let designs = [];             // his saved designs (summaries from Jarvis)
  let tab = 'home';
  let undo = [];
  let saveT = 0, calcT = 0;
  let saved = null;             // the last .zip saved: {where}
  let autoRoute = true;
  const pv = { s: 8, ox: 20, oy: 20, fit: true, layer: 'both', real: false, sel: null };
  const sv = { s: 1, ox: 20, oy: 20, fit: true, sel: null, vals: true };

  function gndName() { const n = D && D.nets.find((q) => C.isGnd(q.n)); return n ? n.n : null; }

  /** After any change: the fill, the checks, the screen; kept on the phone a little later. */
  function changed(how) {
    if (!D) return;
    M = C.model(D);
    silkCache = null;
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
  }
  function state() {
    const s = $('state');
    $('name').firstChild.textContent = D ? D.name : '🔌 PCB స్టూడియో';
    $('sub').textContent = D ? D.board.w + ' × ' + D.board.h + ' mm · ' + D.parts.length + ' భాగాలు' : 'నిజమైన PCB బోర్డ్‌లు';
    if (!D || !check) { s.className = ''; s.textContent = '—'; }
    else if (check.errors) { s.className = 'bad'; s.textContent = '❌ ' + check.errors + ' తప్పులు'; }
    else { s.className = 'ok'; s.textContent = check.warns ? '✅ సిద్ధం · ⚠️ ' + check.warns : '✅ ఆర్డర్‌కి సిద్ధం'; }
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('off', !D && t.dataset.tab !== 'home' && t.dataset.tab !== 'calc'));
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
    D = C.clean(d);
    undo = [];
    pv.fit = true; sv.fit = true; pv.sel = null; sv.sel = null;
    if (D.parts.some((p) => p.lib === 'esp32')) D.parts.forEach((p) => { if (p.lib === 'esp32') p.fp = 'ESP32_DEVKIT30_' + Math.round(D.board.rows * 100); });
    if (!D.tracks.length && how !== 'load') reroute(true);
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
        if (m.cur && m.cur.parts) open(m.cur, 'load');
        showTab(typeof m.tab === 'string' && TABS.indexOf(m.tab) >= 0 ? m.tab : 'home');
        break;
      case 'designs': designs = Array.isArray(m.list) ? m.list : []; if (tab === 'home') home(); break;
      case 'design': if (m.d && m.d.parts) { open(m.d, 'load'); showTab('pcb'); } break;
      case 'saved': saved = { where: String(m.where || '') }; if (tab === 'fab') fab(); toast('💾 సేవ్ అయింది: ' + saved.where, 4000); break;
      case 'say': toast(String(m.s || ''), 4000); break;
      case 'tab': if (TABS.indexOf(m.tab) >= 0) showTab(m.tab); break;
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
        if ($('sheet').classList.contains('on')) closeSheet();
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

  const TABS = ['home', 'sch', 'pcb', 'drc', 'd3', 'fab', 'calc'];
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('close').addEventListener('click', () => post({ t: 'close' }));
  $('name').addEventListener('click', () => { if (D) renameSheet(); });
  function showTab(name) {
    if (!D && name !== 'home' && name !== 'calc') { toast('ముందు ఒక బోర్డ్ తెరవండి (📚 బోర్డ్‌లు)'); name = 'home'; }
    tab = name;
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === name));
    TABS.forEach((n) => $(n).classList.toggle('on', n === name));
    closeSheet();
    if (name !== 'd3' && v3d) v3d.close();
    if (name === 'home') home();
    else if (name === 'sch') { sizeCv($('schcv')); schTools(); redraw(); }
    else if (name === 'pcb') { sizeCv($('pcbcv')); pcbTools(); selBar(); redraw(); }
    else if (name === 'drc') drcList();
    else if (name === 'd3') open3d();
    else if (name === 'fab') fab();
    else if (name === 'calc') calc();
    const on = document.querySelector('.tab.on');
    if (on && on.scrollIntoView) on.scrollIntoView({ inline: 'nearest', block: 'nearest' });
  }
  function redraw() { if (tab === 'pcb') drawPcb(); else if (tab === 'sch') drawSch(); else if (tab === 'drc') drcList(); else if (tab === 'd3' && v3d) v3d.update(); }
  window.addEventListener('resize', () => { if (tab === 'pcb') { sizeCv($('pcbcv')); pv.fit = true; drawPcb(); } else if (tab === 'sch') { sizeCv($('schcv')); sv.fit = true; drawSch(); } });

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
    if (D) {
      const cur = el('div', 'card tap');
      cur.appendChild(el('div', null, '▶️ ఇప్పుడు తెరిచి ఉంది: ' + D.name));
      cur.appendChild(el('div', 'muted', D.board.w + ' × ' + D.board.h + ' mm · ' + (check && check.errors ? '❌ ' + check.errors + ' తప్పులు' : '✅ ఆర్డర్‌కి సిద్ధం')));
      cur.addEventListener('click', () => showTab('pcb'));
      h.appendChild(cur);
    }
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
    if (!designs.length) h.appendChild(el('div', 'muted', 'ఇంకా ఏమీ లేవు. పైన ఒక రెడీ బోర్డ్ తెరిచి మార్చితే ఇక్కడ దాచుకుంటుంది (తనంతట తానే).'));
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
        r2b.appendChild(btn('వద్దు', '', () => home()));
        c.appendChild(r2b);
      }));
      c.appendChild(row);
      h.appendChild(c);
    });
    const soon = el('div', 'card soon');
    soon.appendChild(el('div', null, '🔜 త్వరలో (తర్వాతి బిల్డ్‌లలో)'));
    soon.appendChild(el('div', 'muted', '💡 మీ ఐడియా / మాట / చేతి బొమ్మ ఫోటో నుంచి సర్క్యూట్ (AI, ఖర్చు ముందే చెబుతుంది) · 🗣️ మాటతో మార్పులు · ⚡ PCB కి ముందు సిమ్యులేషన్ · 📏 AR లో అసలు సైజు · 🔧 టంకం గైడ్ · 🛒 కొనే లింకులు · 💻 KiCad ఫైల్ · 🎓 5 రోజుల తెలుగు క్లాస్ · 📦 బోర్డ్ డబ్బా 3D · 🕑 పాత వెర్షన్లు'));
    h.appendChild(soon);
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
    t.appendChild(tool('⚡ రూట్', 'main', () => { snap(); reroute(); changed(); }));
    t.appendChild(tool(autoRoute ? '🔁 ఆటో రూట్ ఆన్' : '🔁 ఆటో రూట్ ఆఫ్', autoRoute ? 'on' : '', () => { autoRoute = !autoRoute; pcbTools(); toast(autoRoute ? 'భాగం కదిపిన ప్రతిసారీ దారులు తానే మళ్ళీ వేస్తుంది' : 'కదిపాక ⚡ రూట్ మీరే నొక్కాలి'); }));
    t.appendChild(tool('👁️ ' + ({ both: 'రెండు పొరలు', top: 'పై పొర', bottom: 'కింది పొర' }[pv.layer]), '', () => { pv.layer = { both: 'top', top: 'bottom', bottom: 'both' }[pv.layer]; pcbTools(); drawPcb(); }));
    t.appendChild(tool(pv.real ? '🎨 అసలు రంగు' : '🎨 పొరల రంగు', pv.real ? 'on' : '', () => { pv.real = !pv.real; pcbTools(); drawPcb(); }));
    t.appendChild(tool('⏚ GND నింపు' + (D && D.pour.B ? ' ✓' : ''), D && D.pour.B ? 'on' : '', () => { snap(); D.pour.B = !D.pour.B; changed(); pcbTools(); }));
    t.appendChild(tool('📏 బోర్డ్', '', boardSheet));
    t.appendChild(tool('🔀 అమర్చు', '', () => {
      sheet((s) => {
        s.appendChild(el('h2', null, '🔀 భాగాలు మళ్ళీ అమర్చాలా?'));
        s.appendChild(el('div', 'muted', 'అన్ని భాగాల్ని Jarvis తనంతట తానే మళ్ళీ పెడుతుంది (కనెక్టర్లు అంచుల దగ్గర, కలిసే భాగాలు దగ్గర దగ్గర), తర్వాత దారులు వేస్తుంది. ఇప్పటి అమరిక పోతుంది (↩️ తో వెనక్కి రావచ్చు).'));
        const r = el('div', 'row');
        r.appendChild(btn('అవును, అమర్చు', 'main', () => {
          snap();
          const ok = C.autoplace(D);
          if (!ok) { back1(); toast('బోర్డ్ చిన్నది: అన్ని భాగాలు పట్టలేదు. 📏 బోర్డ్ పెంచి మళ్ళీ చూడండి.'); closeSheet(); return; }
          reroute(); changed(); closeSheet();
        }));
        r.appendChild(btn('వద్దు', '', closeSheet));
        s.appendChild(r);
      });
    }));
    t.appendChild(tool('↩️', '', back1));
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
  function netsOfPart(ref) { const s = new Set(); D.nets.forEach((n) => { if (n.p.some((p) => p.split('.')[0] === ref)) s.add(n.n); }); return s; }
  gestures($('pcbcv'), pv, {
    redraw: () => drawPcb(),
    tap(x, y) {
      if (!D) return;
      const w = toWorld(pv, x, y), pt = partAt(w[0], w[1]);
      pv.sel = pt ? pt.ref : null;
      selBar();
      drawPcb();
    },
    dragStart(x, y) {
      if (!D || !pv.sel) return false;
      const w = toWorld(pv, x, y), pt = partAt(w[0], w[1]);
      if (!pt || pt.ref !== pv.sel) return false;
      drag = { ref: pt.ref, x: pt.x, y: pt.y, rot: pt.rot || 0, dx: w[0] - pt.x, dy: w[1] - pt.y, nets: netsOfPart(pt.ref) };
      return true;
    },
    dragMove(x, y) {
      const w = toWorld(pv, x, y), g = 0.635;
      drag.x = Math.round((w[0] - drag.dx) / g) * g; drag.y = Math.round((w[1] - drag.dy) / g) * g;
      drawPcb();
    },
    dragEnd(cancel) {
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
      default: {
        const bx = lib.box || { L: Object.keys(names) };
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
    return { sy, tr, pins, box: bx, r };
  }
  function autoSch() {
    // parts without a place on the drawing get one (a grid in the order they join)
    const missing = D.parts.filter((p) => !p.sx && !p.sy);
    if (!missing.length) return;
    let x = 60, y = 60, rowH = 0;
    missing.forEach((pt) => {
      const pl = placed(Object.assign({}, pt, { sx: 0, sy: 0 })), w = pl.box[2] - pl.box[0], h = pl.box[3] - pl.box[1];
      if (x + w > 700) { x = 60; y += rowH + 70; rowH = 0; }
      pt.sx = Math.round(x - pl.box[0]); pt.sy = Math.round(y - pl.box[1]);
      x += w + 70; rowH = Math.max(rowH, h);
    });
  }
  function schLayout() {
    autoSch();
    const P2 = new Map();
    D.parts.forEach((pt) => P2.set(pt.ref, placed(pt)));
    const wires = [], flags = [], dots = [], labels = [];
    D.nets.forEach((n) => {
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
    // a net's name only where it does not sit on a symbol
    const free = labels.filter((l) => { for (const pl of P2.values()) { const b = pl.box; if (l.x > b[0] - 12 && l.x < b[2] + 12 && l.y > b[1] - 6 && l.y < b[3] + 12) return false; } return true; });
    return { P2, wires, flags, dots, labels: free, bounds };
    function join(ends, name, named) {
      // shortest joining (Prim, by L-shaped length)
      const inT = [0], left = ends.map((_, i) => i).slice(1), deg = ends.map(() => 0);
      let longest = null;
      while (left.length) {
        let best = null;
        inT.forEach((a) => left.forEach((b, bi) => { const d = Math.abs(ends[a][0] - ends[b][0]) + Math.abs(ends[a][1] - ends[b][1]); if (!best || d < best.d) best = { d, a, b, bi }; }));
        const A = ends[best.a], Bp = ends[best.b];
        const hFirst = Math.abs(A[2]) > 0.5;
        const mid = hFirst ? [Bp[0], A[1]] : [A[0], Bp[1]];
        wires.push([A[0], A[1], mid[0], mid[1], Bp[0], Bp[1]]);
        if (!longest || best.d > longest.d) longest = { d: best.d, x: (A[0] + mid[0]) / 2, y: (A[1] + mid[1]) / 2, x2: (mid[0] + Bp[0]) / 2, y2: (mid[1] + Bp[1]) / 2, h: Math.abs(A[0] - mid[0]) >= Math.abs(mid[1] - Bp[1]) };
        deg[best.a]++; deg[best.b]++;
        inT.push(best.b); left.splice(best.bi, 1);
      }
      ends.forEach((e, i) => { if (deg[i] > 1) dots.push(e); });
      if (named && longest && longest.d > 30) labels.push({ t: name, x: longest.h ? longest.x : longest.x2, y: longest.h ? longest.y : longest.y2 });
    }
  }
  /** Where a symbol's name goes: right / left of a tall one, above / below a wide one, the first side no wire crosses. */
  function nameAt(pt, pl, lay) {
    const b = pl.box, wide = b[2] - b[0] > (b[3] - b[1]) * 1.2, tw = Math.max(String(pt.ref).length * 7.5, sv.vals ? String(pt.val || '').length * 6 : 0), th = sv.vals && pt.val ? 24 : 13;
    const cy = (b[1] + b[3]) / 2, cx = (b[0] + b[2]) / 2;
    const cands = wide ? [{ x: cx, y: b[1] - th / 2 - 4, a: 'center' }, { x: cx, y: b[3] + th / 2 + 4, a: 'center' }, { x: b[2] + 6, y: cy, a: 'left' }, { x: b[0] - 6, y: cy, a: 'right' }]
      : [{ x: b[2] + 6, y: cy, a: 'left' }, { x: b[0] - 6, y: cy, a: 'right' }, { x: cx, y: b[1] - th / 2 - 4, a: 'center' }, { x: cx, y: b[3] + th / 2 + 4, a: 'center' }];
    const segs = [];
    lay.wires.forEach((q) => { segs.push([q[0], q[1], q[2], q[3]], [q[2], q[3], q[4], q[5]]); });
    lay.flags.forEach((f) => { const e = f.e, ex = e[0] + e[2] * 14, ey = e[1] + e[3] * 14; segs.push([e[0], e[1], ex, ey], f.gnd ? [ex - 9, ey + 6, ex + 9, ey + 13] : [ex - 8, ey - 20, ex + 8, ey - 6]); });
    const hits = (c) => {
      const x0 = c.a === 'left' ? c.x : c.a === 'right' ? c.x - tw : c.x - tw / 2, x1 = x0 + tw, y0 = c.y - th / 2, y1 = c.y + th / 2;
      let n = 0;
      segs.forEach((q) => { if (Math.max(q[0], q[2]) >= x0 && Math.min(q[0], q[2]) <= x1 && Math.max(q[1], q[3]) >= y0 && Math.min(q[1], q[3]) <= y1) n++; });
      lay.P2.forEach((o, ref) => { if (ref === pt.ref) return; const B2 = o.box; if (B2[0] <= x1 && B2[2] >= x0 && B2[1] <= y1 && B2[3] >= y0) n += 2; });
      return n;
    };
    const all = lay.bounds;
    const out = (c) => { const x0 = c.a === 'left' ? c.x : c.a === 'right' ? c.x - tw : c.x - tw / 2; return all && (x0 < all[0] - 4 || x0 + tw > all[2] + 4) ? 5 : 0; };
    let best = null;
    cands.forEach((c, i) => { const n = (hits(c) + out(c)) * 10 + i; if (!best || n < best.n) best = { n, c }; });
    return best.c;
  }

  function schTools() {
    const t = clear($('schtools'));
    t.appendChild(tool(sv.vals ? '🏷️ విలువలు ✓' : '🏷️ విలువలు', sv.vals ? 'on' : '', () => { sv.vals = !sv.vals; schTools(); drawSch(); }));
    t.appendChild(tool('🔍', '', () => { sv.fit = true; drawSch(); }));
    t.appendChild(tool('🧩 PCB చూడు', '', () => showTab('pcb')));
  }
  function drawSch() {
    if (!D) return;
    const cv = $('schcv'), { ctx, w, h } = sizeCv(cv), lay = schLayout();
    let bx = [1e9, 1e9, -1e9, -1e9];
    lay.P2.forEach((pl) => { bx = [Math.min(bx[0], pl.box[0] - 30), Math.min(bx[1], pl.box[1] - 40), Math.max(bx[2], pl.box[2] + 30), Math.max(bx[3], pl.box[3] + 40)]; });
    if (sv.fit) {
      const s = Math.min((w - 16) / (bx[2] - bx[0]), (h - 130) / (bx[3] - bx[1]), 2.2);
      sv.s = s; sv.ox = (w - (bx[2] - bx[0]) * s) / 2 - bx[0] * s; sv.oy = 40 + (h - 130 - (bx[3] - bx[1]) * s) / 2 - bx[1] * s; sv.fit = false;
    }
    const s = sv.s, X = (x) => sv.ox + x * s, Y = (y) => sv.oy + y * s;
    ctx.fillStyle = '#0a1016'; ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = 'rgba(116,228,255,.06)';
    const gs = 20 * s;
    if (gs > 6) for (let x = ((sv.ox % gs) + gs) % gs; x < w; x += gs) for (let y = ((sv.oy % gs) + gs) % gs; y < h; y += gs) ctx.fillRect(x - 0.5, y - 0.5, 1, 1);
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    // wires
    ctx.strokeStyle = '#6be3a4'; ctx.lineWidth = Math.max(1, 1.6 * s);
    lay.wires.forEach((q) => { ctx.beginPath(); ctx.moveTo(X(q[0]), Y(q[1])); ctx.lineTo(X(q[2]), Y(q[3])); ctx.lineTo(X(q[4]), Y(q[5])); ctx.stroke(); });
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
    });
    // net names
    ctx.fillStyle = 'rgba(107,227,164,.75)'; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom';
    ctx.font = Math.max(8, 9.5 * s) + 'px ' + FONT;
    lay.labels.forEach((l) => ctx.fillText(l.t, X(l.x), Y(l.y) - 2));
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
      const at = nameAt(pt, pl, lay);
      ctx.fillStyle = on ? '#74e4ff' : '#e6f3f8'; ctx.font = '600 ' + Math.max(9, 11 * s) + 'px ' + FONT;
      ctx.textAlign = at.a; ctx.textBaseline = 'middle';
      ctx.fillText(pt.ref, X(at.x), Y(at.y - (sv.vals && pt.val ? 6 : 0)));
      if (sv.vals && pt.val) { ctx.font = Math.max(8, 9.5 * s) + 'px ' + FONT; ctx.fillStyle = on ? '#74e4ff' : '#88a5b3'; ctx.fillText(pt.val, X(at.x), Y(at.y + 6)); }
    });
    const c = clear($('schchips'));
    c.appendChild(el('span', 'chip', '👆 భాగం మీద నొక్కితే అది ఏమిటో తెలుగులో'));
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
      if (lib.how) s.appendChild(el('div', 'step', '🔧 ' + lib.how));
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
      if (f) s.appendChild(el('div', 'muted', 'బోర్డ్ మీద ఆకారం (ఫుట్‌ప్రింట్): ' + f.name));
      const r = el('div', 'row');
      r.appendChild(btn('🧩 PCB లో చూపించు', '', () => { pv.sel = pt.ref; showTab('pcb'); }));
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
    const fabRow = el('div', 'row');
    fabRow.appendChild(el('div', 'k muted', 'ఏ ఫ్యాక్టరీ నియమాలతో:'));
    Object.keys(C.FABS).forEach((k) => fabRow.appendChild(btn(C.FABS[k].name, D.fab === k ? 'on small' : 'small', () => { D.fab = k; changed(); drcList(); })));
    h.appendChild(fabRow);
    const icon = { err: '❌', warn: '⚠️', info: '📏' };
    check.list.forEach((f) => {
      const it = el('div', 'item');
      it.appendChild(el('div', 'i', icon[f.sev]));
      const x = el('div', 'x');
      x.appendChild(el('div', f.sev === 'err' ? 'err' : f.sev === 'warn' ? 'warnc' : '', f.te));
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
    info.appendChild(el('span', 'chip', '👆 ఒక వేలితో తిప్పండి, రెండు వేళ్ళతో దగ్గర/దూరం · భాగం మీద నొక్కితే పేరు'));
    const t = clear($('d3tools'));
    if (v3d) { v3d.open(api3d()); tools3d(); return; }
    if (v3dLoading) return;
    v3dLoading = true;
    import('./pcb3d.js').then((m) => { v3d = m; v3dLoading = false; if (tab === 'd3') { v3d.open(api3d()); tools3d(); } })
      .catch(() => { v3dLoading = false; t.appendChild(el('span', 'chip bad', '3D తెరవలేకపోయాను')); });
  }
  function tools3d() {
    const t = clear($('d3tools'));
    t.appendChild(tool('⬆️ పైనుంచి', '', () => v3d.view('top')));
    t.appendChild(tool('↗️ పక్కనుంచి', '', () => v3d.view('side')));
    t.appendChild(tool('⬇️ కిందనుంచి', '', () => v3d.view('bottom')));
    t.appendChild(tool('🔄 తిరుగు', '', (e) => { const on = v3d.spin(); e.target.classList.toggle('on', on); }));
    t.appendChild(tool('🏷️ పేర్లు', '', (e) => { const on = v3d.labels(); e.target.classList.toggle('on', on); }));
  }
  function api3d() {
    return {
      host: $('v3d'), design: () => D, model: () => M, texture, P, C,
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
    ck.forEach((c) => h.appendChild(el('div', 'step', (c[0] ? '' : '👉 ') + c[1])));
    ver.forEach((f) => h.appendChild(el('div', 'step warnc', f.te)));
    h.appendChild(el('h3', null, '3️⃣ ఫ్యాక్టరీ ఫైల్స్ (.zip)'));
    const opts = el('div', 'muted', '2 పొరలు · ' + D.board.w + ' × ' + D.board.h + ' mm · ' + D.board.thick + ' mm · ' + D.board.cu + ' oz రాగి · రంగు: ' + ({ green: 'ఆకుపచ్చ', blue: 'నీలం', black: 'నలుపు', white: 'తెలుపు', purple: 'ఊదా' }[D.board.color] || D.board.color) + '. లోపల: Gerber (' + (M.pads.some((q) => q.smd) ? 7 : 6) + '), డ్రిల్, BOM, భాగాల స్థానాలు (CPL), README.');
    h.appendChild(opts);
    const r = el('div', 'row');
    const make = () => {
      const files = F.files(D, fills), z = F.zip(files), name = F.slug(D) + '-gerber-' + new Date().toISOString().slice(0, 10) + '.zip';
      post({ t: 'zip', name, data: F.b64(z), title: D.name });
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
    if (saved) {
      const c = el('div', 'card');
      c.appendChild(el('div', 'okc', '💾 సేవ్ అయింది: ' + saved.where));
      const r3 = el('div', 'row');
      r3.appendChild(btn('📤 పంపు / షేర్', '', () => post({ t: 'share' })));
      r3.appendChild(btn('🌐 ' + Fb.name + ' సైట్ తెరువు', 'main', () => post({ t: 'site', fab: D.fab })));
      c.appendChild(r3);
      c.appendChild(el('div', 'muted', 'రెండూ మీరు నొక్కితేనే. సైట్ మీ బ్రౌజర్‌లో తెరుచుకుంటుంది; లాగిన్, అప్‌లోడ్, పేమెంట్ మీరే.'));
      h.appendChild(c);
    }
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
    const rows = [];
    const g = new Map();
    D.parts.forEach((pt) => { const k = pt.lib + '|' + pt.val + '|' + (pt.color || ''); if (!g.has(k)) g.set(k, { pt, refs: [] }); g.get(k).refs.push(pt.ref); });
    g.forEach((v) => rows.push({ te: ((P.LIB[v.pt.lib] || {}).te || v.pt.lib) + (v.pt.val ? ' · ' + v.pt.val : ''), refs: v.refs.join(', '), n: v.refs.length, q: B.query(v.pt), pages: B.pages(B.key(v.pt)), tip: sizeTip(v.pt) }));
    ((pr && pr.extra) || []).forEach((x) => rows.push({ te: x.te + (x.val ? ' · ' + x.val : ''), refs: x.why || '', n: x.n || 1, q: x.q, pages: B.pages(x.k) }));
    rows.forEach((r) => {
      const it = el('div', 'item');
      const x = el('div', 'x');
      x.appendChild(el('div', null, r.te + '  × ' + r.n));
      if (r.refs) x.appendChild(el('div', 'f', r.refs));
      if (r.tip) x.appendChild(el('div', 'f', r.tip));
      if (r.pages.length) {
        const lk = el('div', 'seg');
        lk.style.marginTop = '4px';
        r.pages.forEach((pg) => lk.appendChild(btn('🔗 ' + pg[0] + (pg[2] ? ' · ' + pg[2] : ''), 'small', () => post({ t: 'shop', url: pg[1] }))));
        x.appendChild(lk);
      }
      it.appendChild(x);
      it.appendChild(btn('🛒', 'small', () => post({ t: 'shopsearch', shop, q: r.q })));
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
      rows.forEach((r) => lines.push('• ' + r.q + ' × ' + r.n + (r.refs ? ' (' + r.refs + ')' : '')));
      lines.push('(Jarvis PCB స్టూడియో)');
      post({ t: 'sharetext', text: lines.join('\n') });
    }));
    h.appendChild(r4);
    h.appendChild(el('div', 'muted', '💡 రెసిస్టర్లు, LED లు సాధారణంగా 10 / 50 / 100 ప్యాక్‌లలో వస్తాయి. కొనే ముందు పేజీలో విలువ, సైజు ఒకసారి చూడండి. ' + (pr ? '(' + pr.cost + ') ' : '') +
      'కొనడం, పేమెంట్ మీరే; Jarvis కార్ట్‌లో పెట్టదు, కొనదు.'));
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

  // ---------------------------------------------------------------- start

  state();
  showTab('home');
  window.__pcb = { open, get D() { return D; }, get check() { return check; }, showTab, reroute, changed, onJarvis, texture, partSheet: (ref) => partSheet(D && D.parts.find((q) => q.ref === ref)), pv, sv };
})();
