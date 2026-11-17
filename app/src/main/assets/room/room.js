// 📐 Jarvis ఇంటి కొలతలు: the page over the camera. The phone (ARCore) finds the floor and walls and sends, every picture, where
// the camera is and where its + mark points; this page draws the lines, the room, the box, and keeps the plans. ₹0, no AI.
(function () {
  'use strict';
  const K = window.RoomKit;
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

  // ---------------------------------------------------------------- the line to Jarvis
  let port = null;
  const outbox = [];
  function post(m) { const s = JSON.stringify(m); if (port) port.postMessage(s); else outbox.push(s); }
  window.addEventListener('message', (e) => {
    if (port || e.data !== 'jarvis-port' || !e.ports || !e.ports[0]) return;
    port = e.ports[0];
    port.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (x) { return; } onJarvis(m); };
    while (outbox.length) port.postMessage(outbox.shift());
  });

  // ---------------------------------------------------------------- what is on the screen
  const TABS = [['measure', '📏 కొలత'], ['room', '🏠 గది'], ['fit', '🛏️ సరిపోతుందా'], ['plan', '📄 ప్లాన్'], ['saved', '📂 దాచినవి']];
  const CAM = { measure: 'measure', room: 'floor', fit: 'floor' };
  let tab = 'measure';
  let camOn = false, camWhy = '', st = 'look';
  let F = null;                         // the last picture's numbers: { v, p, aim: [x, y, z, kind] | null }
  const P = new Map();                  // a placed point's id → [x, y, z] (ARCore keeps them where they were put)
  let segs = [], pending = null, segsSaved = true;     // 📏
  let corners = [], closed = false;                    // 🏠 (point ids, in order round the room)
  let fit = null, rot = 0;                             // 🛏️ { id, f: [x, z] } and the turn before it is placed
  let item = K.ITEMS[1];
  const own = { w: 4, d: 2, h: 3 };
  let room = null, rooms = [];
  let waiting = null, sure = null, toastT = 0, saveT = 0, drawQ = false;

  function toast(s) { const t = $('toast'); t.textContent = s; t.style.display = 'block'; clearTimeout(toastT); toastT = setTimeout(() => { t.style.display = 'none'; }, 3800); }
  const busyNow = () => (segs.length > 0 && !segsSaved) || pending != null || (corners.length > 0 && !closed);
  let busyWas = false;
  function busy() { const b = busyNow(); if (b !== busyWas) { busyWas = b; post({ t: 'busy', on: b }); } }

  // ---------------------------------------------------------------- tabs
  function tabs() {
    const box = $('tabs');
    box.textContent = '';
    for (const [id, te] of TABS) {
      const b = el('span', 'tab' + (id === tab ? ' on' : ''), te);
      b.onclick = () => showTab(id);
      box.appendChild(b);
    }
  }
  function showTab(t) {
    if (!TABS.some((x) => x[0] === t)) t = 'measure';
    tab = t;
    const ar = !!CAM[t];
    $('ar').classList.toggle('on', ar);
    $('plan').classList.toggle('on', t === 'plan');
    $('saved').classList.toggle('on', t === 'saved');
    document.body.classList.toggle('cam', ar);
    post({ t: 'ar', on: ar, mode: CAM[t] || '' });
    if (t === 'plan') renderPlan();
    if (t === 'saved') renderSaved();
    tabs();
    controls();
    hint();
    kick();
  }

  // ---------------------------------------------------------------- the buttons under the camera
  function setBtn(id, text, on, fn) { const b = $(id); b.textContent = text; b.style.display = text ? 'flex' : 'none'; b.classList.toggle('dim', !on); b.onclick = on ? fn : null; }
  function controls() {
    $('items').style.display = tab === 'fit' ? 'flex' : 'none';
    $('own').style.display = tab === 'fit' && item.id === 'own' ? 'block' : 'none';
    if (tab === 'fit') items();
    const can = camOn && st === 'ok';
    if (tab === 'measure') {
      setBtn('undo', '↶', pending != null || segs.length > 0, undo);
      setBtn('add', '+', can, add);
      setBtn('extra', '💾', segs.length > 0 && !segsSaved, saveMeasures);
      setBtn('extra2', '🗑️', segs.length > 0 || pending != null, clearAll);
    } else if (tab === 'room') {
      setBtn('undo', '↶', !closed && corners.length > 0, undo);
      setBtn('add', '+', can && !closed, add);
      if (closed) setBtn('extra', '📄', true, () => showTab('plan'));
      else setBtn('extra', '✓', corners.length >= 3, closeRoom);
      setBtn('extra2', '🗑️', corners.length > 0, clearAll);
    } else if (tab === 'fit') {
      setBtn('undo', '↺', true, () => turn(-15));
      setBtn('add', '+', can, add);
      setBtn('extra', '↻', true, () => turn(90));
      setBtn('extra2', '🗑️', !!fit, clearAll);
    }
    summary();
  }

  function items() {
    const box = $('items');
    if (box.childElementCount === K.ITEMS.length) { [...box.children].forEach((c, i) => c.classList.toggle('on', K.ITEMS[i] === item)); return; }
    box.textContent = '';
    for (const it of K.ITEMS) {
      const c = el('span', 'chip' + (it === item ? ' on' : ''), it.te);
      c.onclick = () => { item = it; if (fit) fit.item = size(); controls(); hint(); kick(); };
      box.appendChild(c);
    }
  }
  function size() { return item.id === 'own' ? { te: item.te, w: own.w, d: own.d, h: own.h } : { te: item.te, w: item.w, d: item.d, h: item.h }; }
  ['ow', 'od', 'oh'].forEach((k) => {
    const inp = $(k), key = k[1] === 'w' ? 'w' : k[1] === 'd' ? 'd' : 'h';
    inp.value = own[key];
    inp.oninput = () => { const v = +inp.value; if (isFinite(v) && v > 0 && v <= 30) { own[key] = v; if (fit && item.id === 'own') fit.item = size(); kick(); hint(); } };
  });

  function turn(deg) {
    if (fit) fit.f = K.rotate(fit.f, deg); else rot = (rot + deg) % 360;
    kick();
  }

  // ---------------------------------------------------------------- the + : a point where the mark is (the phone fixes it there)
  function add() {
    if (!camOn) return;
    if (!F || !F.aim) { toast(tab === 'measure' ? '+ గుర్తు కింద ఏమీ దొరకలేదు. కొంచెం దగ్గరగా వెళ్ళి నెమ్మదిగా కదపండి.' : '+ గుర్తు నేల మీద లేదు. నేల వైపు చూపండి.'); return; }
    if (waiting) return;
    waiting = { tab, f: tab === 'fit' ? K.rotate(K.camForward(F.v), rot) : null, at: Date.now() };
    post({ t: 'add' });
    setTimeout(() => { if (waiting && Date.now() - waiting.at > 1500) waiting = null; }, 1600);
  }
  function added(m) {
    const w = waiting;
    waiting = null;
    if (!w || typeof m.id !== 'number') { if (typeof m.id === 'number') post({ t: 'del', ids: [m.id] }); return; }
    if (Array.isArray(m.p)) P.set(m.id, m.p);
    if (w.tab !== tab) { post({ t: 'del', ids: [m.id] }); return; }
    if (tab === 'measure') {
      if (pending == null) pending = m.id;
      else { segs.push({ a: pending, b: m.id }); pending = null; segsSaved = false; const L = K.dist3(P.get(segs[segs.length - 1].a), m.p); toast('📏 ' + K.len(L)); }
    } else if (tab === 'room') {
      const p0 = corners.length ? P.get(corners[0]) : null;
      if (corners.length >= 3 && p0 && Math.hypot(p0[0] - m.p[0], p0[2] - m.p[2]) < 0.15) { post({ t: 'del', ids: [m.id] }); closeRoom(); return; }
      corners.push(m.id);
    } else if (tab === 'fit') {
      if (fit) post({ t: 'del', ids: [fit.id] });
      fit = { id: m.id, f: w.f, item: size() };
      rot = 0;
    }
    busy();
    controls();
    hint();
    kick();
  }

  function undo() {
    const del = [];
    if (tab === 'measure') {
      if (pending != null) { del.push(pending); pending = null; }
      else if (segs.length) { const s = segs.pop(); del.push(s.a, s.b); }
    } else if (tab === 'room' && !closed && corners.length) del.push(corners.pop());
    if (del.length) post({ t: 'del', ids: del });
    busy(); controls(); hint(); kick();
  }
  function clearAll() {
    const del = [];
    if (tab === 'measure') { segs.forEach((s) => del.push(s.a, s.b)); if (pending != null) del.push(pending); segs = []; pending = null; segsSaved = true; }
    else if (tab === 'room') { del.push(...corners); corners = []; closed = false; }
    else if (tab === 'fit' && fit) { del.push(fit.id); fit = null; }
    if (del.length) post({ t: 'del', ids: del });
    busy(); controls(); hint(); kick();
  }

  // ---------------------------------------------------------------- 🏠 the room closed: its plan is kept at once
  function closeRoom() {
    if (closed || corners.length < 3) return;
    const pts = corners.map((id) => P.get(id)).filter(Boolean);
    if (pts.length < 3) return;
    closed = true;
    const n = rooms.filter((r) => r.kind === 'room').length + 1;
    room = Object.assign({ id: 'r' + Date.now().toString(36), kind: 'room', name: 'గది ' + n, at: Date.now(), corners: K.normalize(pts) }, K.roomDefaults({}));
    save(room);
    const e = K.estimate(room);
    toast('✓ గది మూసింది: ' + K.area(e.floor) + '. 📄 ప్లాన్ లో పెయింట్, టైల్స్ చూడండి.');
    busy(); controls(); hint(); kick();
  }

  function saveMeasures() {
    if (!segs.length) return;
    const list = segs.map((s) => ({ m: Math.round(K.dist3(P.get(s.a), P.get(s.b)) * 1000) / 1000 })).filter((x) => isFinite(x.m));
    const d = new Date();
    save({ id: 'm' + Date.now().toString(36), kind: 'measures', name: '📏 కొలతలు ' + d.getDate() + '/' + (d.getMonth() + 1) + ' ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0'), at: Date.now(), list });
    segsSaved = true;
    toast('💾 ' + list.length + ' కొలతలు దాచాను (📂 దాచినవి).');
    busy(); controls();
  }
  function save(x) { post({ t: 'save', item: x }); const i = rooms.findIndex((r) => r.id === x.id); if (i >= 0) rooms[i] = x; else rooms.unshift(x); }

  // ---------------------------------------------------------------- the line at the top: what to do now
  function hint() {
    const h = $('hint'), nc = $('nocam');
    nc.style.display = 'none';
    if (!CAM[tab]) return;
    if (!camOn) { h.textContent = camWhy || '📷 కెమెరా మొదలవుతోంది…'; if (camWhy) { nc.style.display = 'block'; nc.textContent = camWhy; } return; }
    const says = {
      look: '📱 ఫోన్‌ని నేల వైపు చూపి నెమ్మదిగా అటూ ఇటూ కదపండి… (కెమెరా గదిని గుర్తుపడుతోంది)',
      dark: '💡 వెలుతురు తక్కువగా ఉంది. లైట్ వేయండి.',
      fast: '🐢 ఫోన్ ఇంకా నెమ్మదిగా కదపండి.',
      plain: '🔍 గుర్తులు కనిపించడం లేదు. డిజైన్ / వస్తువులు ఉన్న వైపు చూపండి.',
    };
    if (st !== 'ok') { h.textContent = says[st] || says.look; return; }
    let s;
    if (tab === 'measure') s = pending == null ? 'కొలవాల్సిన దాని ఒక చివర మీద + పెట్టి ➕ నొక్కండి.' : 'ఇప్పుడు రెండో చివర మీద + పెట్టి ➕.';
    else if (tab === 'room') s = closed ? '✓ గది మూసింది. ➕ పక్కన 📄 తో ప్లాన్, పెయింట్, టైల్స్. కొత్త గది: 🗑️.'
      : !corners.length ? 'గది ఒక మూలలో నేల మీద (గోడలు కలిసే చోట) + పెట్టి ➕.'
        : corners.length < 3 ? 'తర్వాతి మూల మీద + పెట్టి ➕ (గది చుట్టూ వరుసగా).' : 'తర్వాతి మూల ➕. అన్ని మూలలు అయ్యాక ✓ (లేదా మొదటి మూల మీద ➕).';
    else {
      const z = size();
      s = z.te + ': ' + K.ftIn(z.w * K.FT) + ' × ' + K.ftIn(z.d * K.FT) + ' × ' + K.ftIn(z.h * K.FT) + '. '
        + (fit ? '↺ / ↻ తో తిప్పండి; ➕ మళ్ళీ నొక్కితే + ఉన్న చోటికి జరుగుతుంది.' : 'నేల మీద + పెట్టి ➕ నొక్కండి.');
    }
    if (!F || !F.aim) s += tab === 'measure' ? ' (+ కింద ఇంకా ఏమీ దొరకలేదు)' : ' (+ ఇంకా నేల మీద లేదు)';
    h.textContent = s;
  }

  function summary() {
    const box = $('sum');
    if (tab !== 'measure' || !segs.length) { box.style.display = 'none'; return; }
    box.style.display = 'block';
    box.textContent = '';
    segs.forEach((s, i) => box.appendChild(el('div', '', (i + 1) + '. ' + K.len(K.dist3(P.get(s.a) || [0, 0, 0], P.get(s.b) || [0, 0, 0])))));
  }

  // ---------------------------------------------------------------- drawing over the camera
  function kick() { if (!drawQ) { drawQ = true; requestAnimationFrame(draw); } }
  const cv = $('ov'), ctx = cv.getContext('2d');
  function label(text, x, y, color) {
    ctx.font = '600 14px "Noto Sans Telugu", system-ui, sans-serif';
    const w = ctx.measureText(text).width + 14, h = 24;
    ctx.fillStyle = 'rgba(7,8,11,0.82)';
    ctx.strokeStyle = color || '#74e4ff';
    ctx.lineWidth = 1;
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(x - w / 2, y - h / 2, w, h, 8); else ctx.rect(x - w / 2, y - h / 2, w, h);
    ctx.fill(); ctx.stroke();
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(text, x, y + 1);
  }
  function draw() {
    drawQ = false;
    const W = innerWidth, H = innerHeight, dpr = Math.min(3, devicePixelRatio || 1);
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    const live = $('live'), cross = $('cross'), fl = $('fitline');
    live.style.display = 'none';
    fl.className = '';
    const show = !!CAM[tab] && camOn && F && st === 'ok';
    cross.style.display = CAM[tab] && camOn ? 'block' : 'none';
    cross.classList.toggle('off', !(show && F.aim));
    if (!show) return;
    const C = (X) => K.clip(F.p, F.v, X), S = (X) => K.toScreen(C(X), W, H);
    const line = (A, B, color, width, dash) => {
      if (!A || !B) return null;
      const l = K.lineOnScreen(C(A), C(B), W, H);
      if (!l) return null;
      ctx.setLineDash(dash || []);
      ctx.lineCap = 'round';
      ctx.strokeStyle = 'rgba(0,0,0,0.55)'; ctx.lineWidth = width + 3;
      ctx.beginPath(); ctx.moveTo(l[0][0], l[0][1]); ctx.lineTo(l[1][0], l[1][1]); ctx.stroke();
      ctx.strokeStyle = color; ctx.lineWidth = width;
      ctx.beginPath(); ctx.moveTo(l[0][0], l[0][1]); ctx.lineTo(l[1][0], l[1][1]); ctx.stroke();
      ctx.setLineDash([]);
      return l;
    };
    const dot = (X, color) => { const s = X && S(X); if (!s) return; ctx.fillStyle = color || '#fff'; ctx.strokeStyle = '#000'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(s[0], s[1], 6, 0, 7); ctx.fill(); ctx.stroke(); };
    const mid = (l) => [(l[0][0] + l[1][0]) / 2, (l[0][1] + l[1][1]) / 2];
    const aim = F.aim ? F.aim.slice(0, 3) : null;
    const showLive = (t) => { live.textContent = t; live.style.display = 'block'; };

    if (tab === 'measure') {
      for (const s of segs) {
        const A = P.get(s.a), B = P.get(s.b), l = line(A, B, '#ffffff', 3);
        dot(A); dot(B);
        if (l) { const m = mid(l); label(K.len(K.dist3(A, B)), m[0], m[1] - 16); }
      }
      if (pending != null) {
        const A = P.get(pending);
        dot(A, '#74e4ff');
        if (A && aim) { line(A, aim, '#74e4ff', 2.5, [8, 6]); showLive(K.len(K.dist3(A, aim))); }
      }
    } else if (tab === 'room') {
      const pts = corners.map((id) => P.get(id));
      for (let i = 0; i + 1 < pts.length || (closed && i < pts.length); i++) {
        const A = pts[i], B = pts[(i + 1) % pts.length], l = line(A, B, '#74e4ff', 3.5);
        if (l && A && B) { const m = mid(l); label(K.len(Math.hypot(A[0] - B[0], A[2] - B[2])), m[0], m[1] - 16); }
      }
      pts.forEach((A) => dot(A, '#74e4ff'));
      if (!closed && pts.length && aim) {
        const last = pts[pts.length - 1];
        line(last, aim, '#74e4ff', 2.5, [8, 6]);
        const p0 = pts[0], near = pts.length >= 3 && p0 && Math.hypot(p0[0] - aim[0], p0[2] - aim[2]) < 0.15;
        if (pts.length >= 2 && !near) line(aim, p0, 'rgba(116,228,255,0.45)', 1.5, [4, 6]);
        showLive(near ? 'మొదటి మూల: ➕ తో గది మూయి' : K.len(Math.hypot(last[0] - aim[0], last[2] - aim[2])));
      }
      if (closed && pts.every(Boolean)) {
        const cx = pts.reduce((s, p) => s + p[0], 0) / pts.length, cz = pts.reduce((s, p) => s + p[2], 0) / pts.length, cy = pts[0][1];
        const s = S([cx, cy, cz]);
        if (s) label('▦ ' + K.area(Math.abs(K.polyArea(pts.map((p) => [p[0], p[2]])))), s[0], s[1], '#6be3a4');
      }
    } else if (tab === 'fit') {
      const z = fit ? fit.item : size();
      const c = fit ? P.get(fit.id) : aim, f = fit ? fit.f : K.rotate(K.camForward(F.v), rot);
      if (c) {
        const b = K.boxCorners(c, z.w * K.FT, z.d * K.FT, z.h * K.FT, f);
        let verdict = null;
        if (closed && corners.length >= 3) {
          const poly = corners.map((id) => P.get(id)).filter(Boolean).map((p) => [p[0], p[2]]);
          if (poly.length >= 3) verdict = K.fits(poly, K.foot(c[0], c[2], z.w * K.FT, z.d * K.FT, f));
        }
        const col = verdict ? (verdict.ok ? '#6be3a4' : '#ff6b6b') : (fit ? '#f2b24c' : 'rgba(242,178,76,0.7)');
        // the floor under it, see-through
        const fs = b.slice(0, 4).map(S);
        if (fs.every(Boolean)) {
          ctx.fillStyle = verdict ? (verdict.ok ? 'rgba(107,227,164,0.22)' : 'rgba(255,107,107,0.25)') : 'rgba(242,178,76,0.2)';
          ctx.beginPath(); fs.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]))); ctx.closePath(); ctx.fill();
        }
        const E = [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]];
        for (const [i, j] of E) line(b[i], b[j], col, fit ? 2.5 : 1.8, fit ? null : [6, 5]);
        const top = S([c[0], c[1] + z.h * K.FT, c[2]]);
        if (top) label(z.te.split(' ')[0] + ' ' + K.ftIn(z.w * K.FT) + ' × ' + K.ftIn(z.d * K.FT), top[0], top[1] - 18, col);
        if (verdict) {
          fl.className = verdict.ok ? 'ok' : 'bad';
          fl.textContent = verdict.ok ? '✓ గదిలో సరిపోతుంది · గోడకి దగ్గరగా ' + K.ftIn(verdict.gap) : '✗ గోడ దాటుతోంది / గది బయట ఉంది';
        } else if (fit) {
          fl.className = 'info';
          fl.textContent = closed ? '' : 'గది ముందు 🏠 లో కొలిస్తే, సరిపోతుందో కూడా చెబుతాను.';
          if (!fl.textContent) fl.className = '';
        }
      }
    }
  }

  // ---------------------------------------------------------------- 📄 the plan, paint and tiles
  function drawPlan(c, r, white, Wpx, Hpx) {
    const g = c.getContext('2d'), pts = r.corners || [];
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = white ? '#ffffff' : '#0b1118';
    g.fillRect(0, 0, Wpx, Hpx);
    if (pts.length < 3) return;
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
    const fsz = Math.max(12, Math.round(Wpx / 34));
    const padX = Math.max(Wpx * 0.12, fsz * 5.2), padY = Math.max(Hpx * 0.1, fsz * 2.8); // (room for the wall words round it)
    const s = Math.min((Wpx - padX * 2) / Math.max(0.2, x1 - x0), (Hpx - padY * 2) / Math.max(0.2, y1 - y0));
    const ox = (Wpx - (x1 - x0) * s) / 2 - x0 * s, oy = (Hpx - (y1 - y0) * s) / 2 + y1 * s;
    const X = (p) => [ox + p[0] * s, oy - p[1] * s]; // (y up on the paper)
    const ink = white ? '#0b3a4a' : '#74e4ff', text = white ? '#10202a' : '#dceef5', fill = white ? 'rgba(116,228,255,0.18)' : 'rgba(116,228,255,0.10)';
    g.beginPath(); pts.forEach((p, i) => { const q = X(p); i ? g.lineTo(q[0], q[1]) : g.moveTo(q[0], q[1]); }); g.closePath();
    g.fillStyle = fill; g.fill();
    g.lineWidth = Math.max(3, Wpx / 160); g.strokeStyle = ink; g.lineJoin = 'round'; g.stroke();
    g.textBaseline = 'middle';
    const ccw = K.polyArea(pts) > 0;
    pts.forEach((a, i) => {
      const b = pts[(i + 1) % pts.length], A = X(a), B = X(b);
      // the wall's outward side on the paper (the paper's y runs down, so a counter-clockwise room's outside is to the left of A→B)
      let ox = B[1] - A[1], oy = -(B[0] - A[0]);
      const L = Math.hypot(ox, oy) || 1; ox = -ox / L; oy = -oy / L;
      if (!ccw) { ox = -ox; oy = -oy; }
      const mx = (A[0] + B[0]) / 2 + ox * fsz * 0.7, my = (A[1] + B[1]) / 2 + oy * fsz * 0.7;
      g.textAlign = ox < -0.5 ? 'right' : ox > 0.5 ? 'left' : 'center';
      const y1 = oy < -0.5 ? my - fsz * 1.45 : oy > 0.5 ? my + fsz * 0.55 : my - fsz * 0.5, y2 = y1 + fsz * 0.98;
      g.font = '600 ' + fsz + 'px "Noto Sans Telugu", system-ui, sans-serif';
      g.fillStyle = text;
      g.fillText(K.ftIn(K.dist2(a, b)), mx, y1);
      g.font = '500 ' + Math.round(fsz * 0.78) + 'px "Noto Sans Telugu", system-ui, sans-serif';
      g.fillStyle = white ? '#4a6170' : '#88a5b3';
      g.fillText((i + 1) + ' · ' + (Math.round(K.dist2(a, b) * 100) / 100).toFixed(2) + ' మీ', mx, y2);
    });
    g.textAlign = 'center';
    pts.forEach((p) => { const q = X(p); g.fillStyle = ink; g.beginPath(); g.arc(q[0], q[1], Math.max(4, Wpx / 140), 0, 7); g.fill(); });
    const cx = pts.reduce((t, p) => t + p[0], 0) / pts.length, cy = pts.reduce((t, p) => t + p[1], 0) / pts.length, q = X([cx, cy]);
    g.fillStyle = text;
    g.font = '700 ' + Math.round(fsz * 1.15) + 'px "Noto Sans Telugu", system-ui, sans-serif';
    g.fillText(K.area(Math.abs(K.polyArea(pts))), q[0], q[1]);
  }

  function field(box, te, kind, val, set, extra) {
    const row = el('div', 'row');
    row.appendChild(el('span', 'k', te));
    let inp;
    if (kind === 'select') {
      inp = el('select');
      for (const [v, t] of extra) { const o = el('option', null, t); o.value = v; if (v === val) o.selected = true; inp.appendChild(o); }
      inp.onchange = () => set(inp.value);
    } else if (kind === 'check') {
      inp = el('input'); inp.type = 'checkbox'; inp.checked = !!val; inp.onchange = () => set(inp.checked);
    } else {
      inp = el('input'); inp.type = 'number'; inp.value = val; if (extra) inp.step = extra;
      inp.oninput = () => { const v = +inp.value; if (inp.value !== '' && isFinite(v)) set(v); };
    }
    row.appendChild(inp);
    box.appendChild(row);
  }

  function lines(r) {
    const e = K.estimate(r), o = e.o, out = [];
    e.walls.forEach((w, i) => out.push('గోడ ' + (i + 1) + ': ' + K.len(w)));
    out.push('నేల విస్తీర్ణం: ' + K.area(e.floor));
    out.push('చుట్టుకొలత: ' + K.len(e.per));
    out.push('గోడ ఎత్తు: ' + K.ftIn(e.h) + ' · తలుపులు ' + o.doors + ' (3′ × 7′) · కిటికీలు ' + o.windows + ' (4′ × 3′)');
    out.push('పెయింట్ చేసే చోటు' + (o.ceil ? ' (పైకప్పుతో)' : '') + ': ' + K.area(e.paintArea));
    out.push('🎨 పెయింట్: సుమారు ' + e.litres + ' లీటర్లు (2 కోట్లు, లీటర్‌కి ' + o.cover + ' sq ft)');
    out.push('🟫 టైల్స్ ' + K.TILES[o.tile].te + ': సుమారు ' + e.tiles + ' (' + o.waste + '% వృథా కలిపి)' + (e.boxes ? ' · ' + e.boxes + ' బాక్సులు (బాక్స్‌కి ' + o.perBox + ')' : ''));
    out.push('స్కర్టింగ్: సుమారు ' + K.len(e.skirt));
    return out;
  }

  function renderPlan() {
    const box = $('plan');
    box.textContent = '';
    if (!room) {
      box.appendChild(el('div', 'card muted', 'ఇంకా గది లేదు. 🏠 గది లో మూలలు ➕ తో పెట్టి ✓ నొక్కండి, లేదా 📂 దాచినవి నుంచి ఒక గది తెరవండి.'));
      const b = el('div', 'btns'), go = el('span', 'btn main', '🏠 గది కొలవండి');
      go.onclick = () => showTab('room');
      b.appendChild(go);
      box.appendChild(b);
      return;
    }
    const name = el('input'); name.id = 'pname'; name.value = room.name || ''; name.maxLength = 40;
    name.oninput = () => { room.name = name.value.slice(0, 40) || 'గది'; saveSoon(); };
    box.appendChild(name);
    const c = el('canvas'); c.id = 'pc';
    box.appendChild(c);
    const res = el('div', 'card'), set = el('div', 'card');
    const redo = () => {
      const r = c.getBoundingClientRect(), dpr = Math.min(3, devicePixelRatio || 1);
      c.width = Math.round((r.width || 360) * dpr); c.height = Math.round((r.height || 300) * dpr);
      drawPlan(c, room, false, c.width, c.height);
      res.textContent = '';
      const e = K.estimate(room);
      res.appendChild(el('div', 'big', '▦ ' + K.area(e.floor)));
      res.appendChild(el('div', 'muted', 'చుట్టుకొలత ' + K.len(e.per) + ' · ' + e.walls.length + ' గోడలు'));
      const t = el('table');
      const tr = (k, v) => { const r0 = el('tr'); r0.appendChild(el('td', '', k)); r0.appendChild(el('td', 'num', v)); t.appendChild(r0); };
      e.walls.forEach((w, i) => tr('గోడ ' + (i + 1), K.len(w)));
      tr('పెయింట్ చేసే చోటు', K.area(e.paintArea));
      tr('🎨 పెయింట్ (2 కోట్లు)', 'సుమారు ' + e.litres + ' లీటర్లు');
      tr('🟫 టైల్స్', 'సుమారు ' + e.tiles + (e.boxes ? ' (' + e.boxes + ' బాక్సులు)' : ''));
      tr('స్కర్టింగ్', 'సుమారు ' + K.len(e.skirt));
      res.appendChild(t);
    };
    const o = K.roomDefaults(room);
    const ch = (k) => (v) => { room[k] = v; if (k === 'tile') { room.perBox = K.TILES[v].box; } saveSoon(); redo(); if (k === 'tile') renderPlan(); };
    set.appendChild(el('div', 'muted', 'మీ ఇంటికి తగ్గట్టు మార్చండి:'));
    field(set, 'గోడ ఎత్తు (అడుగులు)', 'num', o.h_ft, ch('h_ft'), '0.5');
    field(set, 'తలుపులు', 'num', o.doors, ch('doors'), '1');
    field(set, 'కిటికీలు', 'num', o.windows, ch('windows'), '1');
    field(set, 'పైకప్పుకి కూడా పెయింట్', 'check', o.ceil, ch('ceil'));
    field(set, 'లీటర్‌కి sq ft (2 కోట్లు, డబ్బా మీద చూడండి)', 'num', o.cover, ch('cover'), '5');
    field(set, 'టైల్ సైజు', 'select', o.tile, ch('tile'), Object.keys(K.TILES).map((k) => [k, K.TILES[k].te]));
    field(set, 'వృథా % (కోతలకి)', 'num', o.waste, ch('waste'), '1');
    field(set, 'ఒక బాక్స్‌లో టైల్స్ (షాప్‌లో అడగండి)', 'num', o.perBox, ch('perBox'), '1');
    box.appendChild(res);
    box.appendChild(set);
    const b = el('div', 'btns'), pdf = el('span', 'btn main', '📄 PDF'), sv = el('span', 'btn', '📂 దాచినవి');
    pdf.onclick = () => report(room);
    sv.onclick = () => showTab('saved');
    b.appendChild(pdf); b.appendChild(sv);
    box.appendChild(b);
    box.appendChild(el('div', 'muted', '📏 ఇవి ఫోన్ కెమెరా (AR) కొలతలు: కొన్ని సెంటీమీటర్లు తేడా రావచ్చు. కొనే / కట్టే ముందు టేపుతో సరిచూసుకోండి. '
      + 'పెయింట్, టైల్స్ సుమారు లెక్కలు: డబ్బా మీది కవరేజ్, షాప్ బాక్స్ సైజు పెడితే దగ్గరగా వస్తాయి. గోడలు తిన్నగా, నిలువుగా ఉన్నాయనుకుని లెక్కించాను.'));
    requestAnimationFrame(redo);
  }
  function saveSoon() { clearTimeout(saveT); saveT = setTimeout(() => { if (room) save(room); }, 600); }

  function report(r) {
    const c = document.createElement('canvas');
    const pts = r.corners || [], xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    const asp = pts.length >= 3 ? Math.max(0.45, Math.min(1.4, (Math.max(...ys) - Math.min(...ys) + 1) / (Math.max(...xs) - Math.min(...xs) + 1))) : 0.75;
    c.width = 1100; c.height = Math.round(1100 * asp);
    drawPlan(c, r, true, c.width, c.height);
    const d = new Date(r.at || Date.now());
    post({ t: 'report', req: 'p' + Date.now().toString(36), title: r.name || 'గది', png: c.toDataURL('image/png').split(',')[1],
      lines: ['కొలిచింది: ' + d.getDate() + '/' + (d.getMonth() + 1) + '/' + d.getFullYear()].concat(lines(r)) });
    toast('📄 PDF తయారవుతోంది…');
  }

  // ---------------------------------------------------------------- 📂 what was kept
  function renderSaved() {
    const box = $('saved');
    box.textContent = '';
    if (!rooms.length) { box.appendChild(el('div', 'card muted', 'ఇంకా ఏమీ దాచలేదు. 🏠 గది మూసినప్పుడు, 📏 కొలతల్లో 💾 నొక్కినప్పుడు ఇక్కడ దాచుకుంటాను.')); return; }
    for (const r of rooms.slice().sort((a, b) => (b.at || 0) - (a.at || 0))) {
      const row = el('div', 'item'), w = el('div', 'w'), d = new Date(r.at || 0);
      const when = d.getDate() + '/' + (d.getMonth() + 1) + ' ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
      if (r.kind === 'room') {
        const e = K.estimate(r);
        w.appendChild(el('div', 'n', '🏠 ' + (r.name || 'గది')));
        w.appendChild(el('div', 'muted', when + ' · ' + K.area(e.floor) + ' · ' + e.walls.length + ' గోడలు'));
        w.onclick = () => { room = r; showTab('plan'); };
      } else {
        w.appendChild(el('div', 'n', r.name || '📏 కొలతలు'));
        w.appendChild(el('div', 'muted', when + ' · ' + (r.list || []).map((x, i) => (i + 1) + ') ' + K.len(x.m)).join(' · ')));
      }
      const del = el('span', 'del' + (sure === r.id ? ' sure' : ''), sure === r.id ? 'తీసేయాలా? 🗑️' : '🗑️');
      del.onclick = () => {
        if (sure !== r.id) { sure = r.id; renderSaved(); setTimeout(() => { if (sure === r.id) { sure = null; renderSaved(); } }, 4000); return; }
        sure = null;
        rooms = rooms.filter((x) => x.id !== r.id);
        if (room && room.id === r.id) room = null;
        post({ t: 'remove', id: r.id });
        renderSaved();
      };
      row.appendChild(w); row.appendChild(del);
      box.appendChild(row);
    }
  }

  // ---------------------------------------------------------------- from Jarvis
  function onJarvis(m) {
    switch (m.t) {
      case 'init':
        if (Array.isArray(m.rooms)) rooms = m.rooms;
        showTab(m.tab || tab);
        break;
      case 'tab': showTab(m.tab); break;
      case 'rooms': if (Array.isArray(m.list)) { rooms = m.list; if (room) room = rooms.find((r) => r.id === room.id) || room; if (tab === 'saved') renderSaved(); } break;
      case 'xr':
        camOn = !!m.on;
        camWhy = m.on ? '' : (m.why || '');
        if (!m.on) { F = null; st = 'look'; }
        controls(); hint(); kick();
        break;
      case 'st': if (m.s !== st) { st = m.s; controls(); hint(); kick(); } break;
      case 'f': {
        F = { v: m.v, p: m.p, aim: m.aim || null };
        if (m.pts) for (const k in m.pts) P.set(+k, m.pts[k]);
        if (st !== 'ok') { st = 'ok'; controls(); }
        const had = $('cross').classList.contains('off');
        if (had === !!F.aim) hint();
        kick();
        if (tab === 'measure' && segs.length) summary();
        break;
      }
      case 'added': added(m); break;
      case 'noaim': waiting = null; toast(tab === 'measure' ? '+ గుర్తు కింద ఏమీ దొరకలేదు. కొంచెం కదపండి.' : '+ గుర్తు నేల మీద లేదు. నేల వైపు చూపండి.'); break;
      case 'say': if (typeof m.s === 'string') toast(m.s); break;
      default: break;
    }
  }

  $('close').onclick = () => post({ t: 'close' });
  window.addEventListener('resize', kick);
  tabs();
  window.__room = { onJarvis, showTab, get state() { return { tab, camOn, st, segs, pending, corners, closed, fit, room, rooms, P, F }; }, add, undo, clearAll, closeRoom, turn, report, lines, drawPlan, K };
})();
