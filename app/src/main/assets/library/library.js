// 📚 Jarvis గ్రంథాలయం: the wooden racks (our own drawing on the bundled three.js), the book that comes off the shelf and
// opens, the card, 🔍 search, ➕ add, the reading screen with the line being read lit. Book text and results are data:
// they only ever go into textContent / canvas text. No network here: the phone side fetches on his taps.
import * as THREE from './three.module.min.js';

const K = window.LibKit;
const C = K.CASE;
const $ = (id) => document.getElementById(id);

// ================================================================ talking to the phone
let port = null;
const outbox = [];
function post(m) { const s = JSON.stringify(m); if (port) port.postMessage(s); else outbox.push(s); }
window.addEventListener('message', (e) => {
  if (port || e.data !== 'jarvis-port' || !e.ports || !e.ports[0]) return;
  port = e.ports[0];
  port.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (x) { return; } try { onJarvis(m); } catch (x) { /* one bad message never stops the page */ } };
  while (outbox.length) port.postMessage(outbox.shift());
});

// ================================================================ state
const S = {
  starter: [], shelf: [], pos: {}, books: [], byId: {}, view: 'sec', rack: 'fic', pack: false, model: '', ai: '', aiNow: null, rate: 0, month: null,
  prefs: { font: 19, night: false, view: 'sec', mode: 'en', pair: 'te', rate: 0 }, now: null, sel: null, results: [], busy: {},
  reader: { id: '', unit: 0, n: 0, parts: [], te: null, ai: null, titles: [], mode: 'en', follow: true, scrolledAt: 0 },
  addInfo: null, ready: false,
};

function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
function btn(text, cls, fn) { const b = el('button', cls || '', text); b.onclick = (ev) => { ev.stopPropagation(); fn(); }; return b; }

let toastTimer = 0;
function toast(s, ms) {
  const t = $('toast');
  t.textContent = '';
  if (!s) return;
  t.appendChild(el('span', '', s));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.textContent = ''; }, ms || 3800);
}

// ================================================================ the room, the bookcases, the books (three.js)
const cv = $('cv');
const renderer = new THREE.WebGLRenderer({ canvas: cv, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
const scene = new THREE.Scene();
scene.background = new THREE.Color('#1a120b');
scene.fog = new THREE.Fog('#1a120b', 6, 16);
const camera = new THREE.PerspectiveCamera(50, 1, 0.03, 40);
const cam = { x: 0, y: 1.25, z: 1.75, vx: 0, vy: 0 };

scene.add(new THREE.HemisphereLight(0xffe8c8, 0x2a1a0c, 0.95));
scene.add(new THREE.AmbientLight(0xffd8a8, 0.35));
const sun = new THREE.DirectionalLight(0xfff0d6, 1.6);
sun.position.set(1.5, 4, 5);
scene.add(sun);
const lamp = new THREE.PointLight(0xffc27a, 1.4, 6, 1.6);
scene.add(lamp);

function canvasTex(w, h, draw, srgb) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  draw(c.getContext('2d'), w, h);
  const t = new THREE.CanvasTexture(c);
  if (srgb !== false) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}

function rnd(seed) { let s = seed >>> 0; return () => { s = (Math.imul(s ^ (s >>> 15), 2246822507) + 0x9e3779b9) >>> 0; return (s >>> 8) / 16777216; }; }

/** Wood grain: long wavy darker lines over a warm base. */
function woodTex(base, dark, seed, along) {
  return canvasTex(512, 512, (g, w, h) => {
    g.fillStyle = base; g.fillRect(0, 0, w, h);
    const r = rnd(seed);
    for (let i = 0; i < 90; i++) {
      const y0 = r() * h, amp = 2 + r() * 7, f = 0.004 + r() * 0.012, ph = r() * 6.3;
      g.strokeStyle = dark; g.globalAlpha = 0.08 + r() * 0.2; g.lineWidth = 0.6 + r() * 2.2;
      g.beginPath();
      for (let x = 0; x <= w; x += 8) { const y = y0 + Math.sin(x * f + ph) * amp; if (x === 0) g.moveTo(x, y); else g.lineTo(x, y); }
      g.stroke();
    }
    g.globalAlpha = 1;
    for (let i = 0; i < 2500; i++) { g.fillStyle = r() < 0.5 ? 'rgba(0,0,0,0.05)' : 'rgba(255,240,210,0.04)'; g.fillRect(r() * w, r() * h, 1 + r() * 2, 1); }
    if (along === 'y') { const d = g.getImageData(0, 0, w, h); const c2 = document.createElement('canvas'); c2.width = w; c2.height = h; c2.getContext('2d').putImageData(d, 0, 0); g.save(); g.clearRect(0, 0, w, h); g.translate(w / 2, h / 2); g.rotate(Math.PI / 2); g.drawImage(c2, -w / 2, -h / 2); g.restore(); }
  });
}

const woodMat = new THREE.MeshStandardMaterial({ map: woodTex('#6d4325', '#2e1709', 7), roughness: 0.62, metalness: 0.02 });
const woodSide = new THREE.MeshStandardMaterial({ map: woodTex('#6a4024', '#2b1508', 11, 'y'), roughness: 0.62 });
const backMat = new THREE.MeshStandardMaterial({ map: woodTex('#3f2614', '#1a0c04', 19, 'y'), roughness: 0.85 });
const pagesTex = canvasTex(256, 64, (g, w, h) => {
  g.fillStyle = '#efe4c9'; g.fillRect(0, 0, w, h);
  for (let y = 0; y < h; y += 2) { g.fillStyle = y % 4 ? 'rgba(120,95,60,0.10)' : 'rgba(120,95,60,0.18)'; g.fillRect(0, y, w, 1); }
});
const pagesMat = new THREE.MeshStandardMaterial({ map: pagesTex, roughness: 0.9 });
[woodMat, woodSide, backMat, pagesMat].forEach((m) => { m.__keep = true; });
const coverMats = {};
function coverMat(col) {
  if (!coverMats[col]) { coverMats[col] = new THREE.MeshStandardMaterial({ color: new THREE.Color(col), roughness: 0.7, metalness: 0.05 }); coverMats[col].__keep = true; }
  return coverMats[col];
}

// the floor and the wall
const floorTex = canvasTex(512, 512, (g, w, h) => {
  const r = rnd(5);
  for (let i = 0; i < 8; i++) {
    const shade = 40 + Math.floor(r() * 18);
    g.fillStyle = 'rgb(' + (shade + 52) + ',' + (shade + 22) + ',' + shade + ')';
    g.fillRect(0, i * 64, w, 63);
    g.fillStyle = 'rgba(0,0,0,0.35)'; g.fillRect(0, i * 64 + 63, w, 1);
    for (let k = 0; k < 40; k++) { g.fillStyle = 'rgba(0,0,0,0.06)'; g.fillRect(r() * w, i * 64 + r() * 60, 30 + r() * 120, 1); }
    g.fillStyle = 'rgba(0,0,0,0.3)'; g.fillRect(Math.floor(r() * w), i * 64, 1, 63);
  }
});
floorTex.wrapS = floorTex.wrapT = THREE.RepeatWrapping;
floorTex.repeat.set(10, 10);
const floor = new THREE.Mesh(new THREE.PlaneGeometry(60, 30), new THREE.MeshStandardMaterial({ map: floorTex, roughness: 0.75 }));
floor.rotation.x = -Math.PI / 2;
floor.position.set(10, 0, 6);
scene.add(floor);
const wallTex = canvasTex(256, 256, (g, w, h) => {
  g.fillStyle = '#5b3f2b'; g.fillRect(0, 0, w, h);
  const r = rnd(9);
  for (let i = 0; i < 3000; i++) { g.fillStyle = r() < 0.5 ? 'rgba(0,0,0,0.05)' : 'rgba(255,230,200,0.035)'; g.fillRect(r() * w, r() * h, 2, 2); }
});
wallTex.wrapS = wallTex.wrapT = THREE.RepeatWrapping;
wallTex.repeat.set(20, 3);
const wall = new THREE.Mesh(new THREE.PlaneGeometry(60, 4), new THREE.MeshStandardMaterial({ map: wallTex, roughness: 0.95 }));
wall.position.set(10, 2, -0.42);
scene.add(wall);

// ---------------------------------------------------------------- spines: book titles drawn into big shared pictures
const ATLAS = 2048, ROWH = 640;
let atlases = [];
function newAtlas() {
  const c = document.createElement('canvas');
  c.width = ATLAS; c.height = ATLAS;
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 4;
  const a = { c, g: c.getContext('2d'), tex, mat: new THREE.MeshStandardMaterial({ map: tex, roughness: 0.55, metalness: 0.08 }), x: 0, row: 0 };
  atlases.push(a);
  return a;
}
function slot(w) {
  let a = atlases[atlases.length - 1] || newAtlas();
  if (a.x + w > ATLAS) { a.row++; a.x = 0; }
  if ((a.row + 1) * ROWH > ATLAS) { a = newAtlas(); }
  const s = { a, x: a.x, y: a.row * ROWH, w };
  a.x += w + 4;
  return s;
}
function shade(hex, k) {
  const c = new THREE.Color(hex);
  if (k > 0) c.lerp(new THREE.Color('#ffffff'), k); else c.lerp(new THREE.Color('#000000'), -k);
  return '#' + c.getHexString();
}
function fitFont(g, text, maxW, size, min, weight, family) {
  let s = size;
  for (; s > min; s -= 2) { g.font = weight + ' ' + s + 'px ' + family; if (g.measureText(text).width <= maxW) break; }
  return s;
}
const SERIF = '"Noto Serif Telugu", "Noto Sans Telugu", Georgia, serif';
function drawSpine(sl, b) {
  const g = sl.a.g, w = sl.w, h = ROWH, col = K.colourOf(b.id);
  g.save();
  g.translate(sl.x, sl.y);
  const gr = g.createLinearGradient(0, 0, w, 0);
  gr.addColorStop(0, shade(col, -0.45)); gr.addColorStop(0.45, shade(col, 0.1)); gr.addColorStop(0.6, shade(col, 0.04)); gr.addColorStop(1, shade(col, -0.5));
  g.fillStyle = gr; g.fillRect(0, 0, w, h);
  const r = rnd(K.hash(b.id));
  for (let i = 0; i < 300; i++) { g.fillStyle = r() < 0.5 ? 'rgba(0,0,0,0.08)' : 'rgba(255,255,255,0.03)'; g.fillRect(r() * w, r() * h, 1 + r() * 2, 1 + r() * 3); }
  g.fillStyle = '#d6b05a';
  [34, 44, h - 56, h - 46].forEach((y) => g.fillRect(3, y, w - 6, 3));
  // the title, read top to bottom
  g.translate(w / 2, 0);
  g.rotate(Math.PI / 2);
  const len = h - 64 - 120, thick = w * 0.82;
  const maxChars = Math.max(8, Math.round(len / 22));
  const lines = K.spineLines(b.t, maxChars);
  const two = lines.length > 1 && thick > 60;
  const shown = two ? lines : [lines.join(' ')];
  g.fillStyle = '#f2d78f';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  let fs = Math.min(two ? thick * 0.4 : thick * 0.56, 40);
  shown.forEach((l) => { fs = Math.min(fs, fitFont(g, l, len, fs, 12, '700', SERIF)); });
  g.font = '700 ' + fs + 'px ' + SERIF;
  const cx = 64 + len / 2;
  shown.forEach((l, i) => g.fillText(l, cx, (i - (shown.length - 1) / 2) * fs * 1.08));
  // the author, small, near the bottom
  if (b.a) {
    const an = String(b.a).split(/\s+/).slice(-1)[0];
    const as = fitFont(g, an, 100, Math.min(thick * 0.34, 22), 10, '500', SERIF);
    g.font = '500 ' + as + 'px ' + SERIF;
    g.fillStyle = '#e8cf95';
    g.fillText(an, h - 84, 0);
  }
  g.restore();
  // ✨ AI Telugu made for it (some or all): a small gold sparkle at the head
  if (aiInfo(b).any) sparkle(g, sl.x + w / 2, sl.y + 17, Math.min(12, w * 0.24));
  // ✓ on the phone (a small gold dot at the foot), 🔖 a wish
  if (b.state === 'ready' || b.wish || b.home) {
    g.fillStyle = b.wish ? '#9cc8ff' : b.state === 'ready' ? '#8fd6a0' : '#ffb067'; // (🏠 his paper copy at home: orange)
    g.beginPath(); g.arc(sl.x + w / 2, sl.y + h - 22, Math.min(9, w * 0.18), 0, Math.PI * 2); g.fill();
  }
}

function sparkle(g, x, y, r) {
  g.save();
  g.fillStyle = '#ffe29a';
  g.beginPath();
  for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4, d = i % 2 ? r * 0.3 : r; g.lineTo(x + Math.cos(a - Math.PI / 2) * d, y + Math.sin(a - Math.PI / 2) * d); }
  g.closePath(); g.fill();
  g.restore();
}

// ================================================================ ✨ how far a book's AI Telugu is (from its shelf entry)
/** {any, done (chapters fully), part (chapters begun), n, pct (of the book's letters), rs (₹ spent; null = not counted), left (letters), leftRs} */
function aiInfo(b) {
  const u = (b && b.ai_u) || {}, cc = (b && b.ai_cc) || {};
  const ks = Object.keys(u);
  let done = 0, part = 0, ch = 0;
  ks.forEach((k) => { const v = Number(u[k]) || 0; if (v >= 100) done++; else if (v > 0) part++; });
  Object.keys(cc).forEach((k) => { ch += Number(cc[k]) || 0; });
  const all = Number(b && b.chars) || 0;
  const left = Math.max(0, all - ch);
  return { any: done + part > 0, done, part, n: Number(b && b.n) || 0, pct: all ? Math.min(100, Math.floor(ch * 100 / all)) : 0,
    rs: b && b.ai_rs !== undefined ? Number(b.ai_rs) : null, left, leftRs: S.rate > 0 ? left * S.rate : null };
}
/** The chapter's ✨ in the reader's list: done, or how far. */
function aiMark(b, i) {
  const v = Number(((b && b.ai_u) || {})[String(i)]) || 0;
  return v >= 100 ? ' ✨' : v > 0 ? ' ✨' + v + '%' : '';
}
/** A running job's numbers merged into the page's copy of that book (no racks rebuilt while it runs). */
function aiMerge(id, ai) {
  if (!id || !ai) return;
  const s = S.shelf.find((x) => x && x.id === id);
  if (s) Object.assign(s, ai);
  if (S.byId[id] && S.byId[id] !== s) Object.assign(S.byId[id], ai);
}
function aiLine(n) {
  if (!n) return '';
  if (n.kind === 'map') return '🗺️ ' + (n.title || (S.byId[n.id] || {}).t || '') + ' · అధ్యాయం ' + (Number(n.unit) + 1) + ' (' + (n.ci || 1) + '/' + (n.cn || 1) + ') · ' + K.money(n.rs || 0);
  return '✨ ' + (n.title || (S.byId[n.id] || {}).t || '') + ' · అధ్యాయం ' + (Number(n.unit) + 1) + (n.cn > 1 ? ' (' + n.ci + '/' + n.cn + ')' : '') + ' · '
    + (n.n ? Math.floor(n.k * 100 / n.n) : 0) + '% · ' + K.money(n.rs || 0);
}
/** The live bar: over the racks, and a row in the reader; tap → the ✨ page, ⏹ stops (the done lines stay). */
function aiBars() {
  const n = S.ai ? S.aiNow : null;
  const bar = $('aibar'), r = $('rAi');
  [bar, r].forEach((x) => { x.textContent = ''; x.hidden = !n; });
  if (!n) return;
  const pc = n.kind === 'map' ? Math.floor(Math.max(0, (n.ci || 1) - 1) * 100 / Math.max(1, n.cn || 1)) : n.n ? Math.floor(n.k * 100 / n.n) : 0;
  bar.appendChild(el('span', 't', aiLine(n)));
  bar.appendChild(btn('⏹', '', () => post({ t: 'ai_stop' })));
  const b1 = el('div', 'bar'); const f1 = el('i'); f1.style.width = pc + '%'; b1.appendChild(f1); bar.appendChild(b1);
  bar.onclick = () => { if (n.kind !== 'map') openTrs(); };
  r.appendChild(el('span', 't', aiLine(n)));
  r.appendChild(btn('⏹ ఆపు', '', () => post({ t: 'ai_stop' })));
  r.onclick = () => { if (n.kind !== 'map') openTrs(); };
}

// ================================================================ ✨ the translations page
function openTrs() {
  $('bubble').hidden = true;
  if (S.sel) closeBook(true);
  $('trs').hidden = false;
  renderTrs();
  post({ t: 'trs' });
}
function renderTrs() {
  const body = $('tBody');
  if ($('trs').hidden) return;
  body.textContent = '';
  const trRun = (id) => S.ai === id && !(S.aiNow && S.aiNow.kind === 'map');
  const books = S.shelf.filter((b) => b && b.state === 'ready' && (aiInfo(b).any || trRun(b.id)));
  const m = S.month;
  let chs = 0; books.forEach((b) => { chs += aiInfo(b).done; });
  body.appendChild(el('p', 'tsum', books.length
    ? books.length + ' పుస్తకాలు · ' + chs + ' అధ్యాయాలు పూర్తిగా తెలుగులో' + (m ? ' · ఈ నెల 📚 ' + K.money(m.books) + ' · మొత్తం ₹' + m.used + ' / హద్దు ₹' + m.limit + ' (మిగిలింది ₹' + Math.max(0, m.limit - m.used) + ')' : '')
      + (S.model ? ' · లైబ్రరీ AI: ' + S.model : '')
    : 'ఇంకా ఏ పుస్తకానికీ AI తెలుగు చేయించలేదు. పుస్తకం తెరిచి ✨ AI తెలుగు నొక్కండి: ఖర్చు ముందే చూపిస్తాను.'));
  const order = (b) => (trRun(b.id) ? 0 : aiInfo(b).pct < 100 ? 1 : 2);
  books.sort((a, b) => order(a) - order(b) || String(a.t).localeCompare(String(b.t)));
  books.forEach((b) => {
    const i = aiInfo(b), run = trRun(b.id);
    const row = el('div', 'trow' + (run ? ' run' : ''));
    row.appendChild(el('h3', '', (b.tt && b.tt !== b.t ? b.tt + ' · ' : '') + b.t));
    row.appendChild(el('div', 'n', '✨ ' + i.done + ' / ' + i.n + ' అధ్యాయాలు పూర్తి' + (i.part ? ' · ' + i.part + ' సగంలో' : '') + ' · పుస్తకంలో ' + i.pct + '%'));
    const bar = el('div', 'bar'); const f = el('i'); f.style.width = i.pct + '%'; bar.appendChild(f); row.appendChild(bar);
    const money = [];
    if (i.rs !== null && i.rs > 0) money.push('ఇప్పటిదాకా ఖర్చు ' + K.money(i.rs));
    else if (i.rs === null) money.push('ఇంతకుముందు చేసినదాని ఖర్చు లెక్కలో లేదు');
    if (i.left > 0 && i.leftRs !== null) money.push('మిగిలినవి సుమారు ' + K.money(i.leftRs) + (S.model ? ' (' + S.model + ')' : ''));
    if (i.left === 0) money.push('✅ మొత్తం పుస్తకం తెలుగులో');
    if (money.length) row.appendChild(el('div', 'n', money.join(' · ')));
    if (run && S.aiNow) row.appendChild(el('div', 'live', aiLine(S.aiNow)));
    const r = el('div', 'row');
    const firstDone = Object.keys(b.ai_u || {}).map(Number).filter((k) => b.ai_u[k] > 0).sort((x, y) => x - y)[0];
    if (firstDone !== undefined) r.appendChild(btn('📖 చదువు', 'gold', () => { $('trs').hidden = true; S.prefs.mode = 'ai'; post({ t: 'prefs', mode: 'ai' }); openReader(b.id, firstDone); }));
    if (run) r.appendChild(btn('⏹ ఆపు', '', () => post({ t: 'ai_stop' })));
    else if (i.done < i.n) {
      let next = 0; while (next < i.n && Number((b.ai_u || {})[String(next)]) >= 100) next++;
      r.appendChild(btn('✨ మిగిలినవి చేయించు', '', () => post({ t: 'quote', id: b.id, unit: Math.min(next, Math.max(0, i.n - 1)) })));
    }
    row.appendChild(r);
    body.appendChild(row);
  });
}

// ---------------------------------------------------------------- building the racks
const world = new THREE.Group();
scene.add(world);
let bookMeshes = [];
let caseX = {};
let rackList = [];

function plaqueTex(text, sub) {
  return canvasTex(1024, 160, (g, w, h) => {
    const gr = g.createLinearGradient(0, 0, 0, h);
    gr.addColorStop(0, '#3a2210'); gr.addColorStop(1, '#22130a');
    g.fillStyle = gr; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#c9a24e'; g.lineWidth = 6; g.strokeRect(8, 8, w - 16, h - 16);
    g.fillStyle = '#f0d27e'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const s = fitFont(g, text, w - 80, 64, 28, '700', SERIF);
    g.font = '700 ' + s + 'px ' + SERIF;
    g.fillText(text, w / 2, sub ? h * 0.4 : h / 2);
    if (sub) { g.font = '500 30px ' + SERIF; g.fillStyle = '#d8bb7a'; g.fillText(sub, w / 2, h * 0.76); }
  });
}
function labelTex(text) {
  return canvasTex(512, 64, (g, w, h) => {
    g.fillStyle = '#efe0bd'; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#8a6a32'; g.lineWidth = 3; g.strokeRect(2, 2, w - 4, h - 4);
    g.fillStyle = '#3a2410'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const s = fitFont(g, text, w - 24, 34, 16, '600', SERIF);
    g.font = '600 ' + s + 'px ' + SERIF;
    g.fillText(text, w / 2, h / 2 + 1);
  });
}

function box(w, h, d, mat, x, y, z, parent) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
  m.position.set(x, y, z);
  (parent || world).add(m);
  return m;
}

function bookcase(i, rack) {
  const x0 = i * (C.w + C.gap);
  const g = new THREE.Group();
  g.position.x = x0;
  world.add(g);
  box(0.05, C.h, C.d, woodSide, -C.w / 2 + 0.025, C.h / 2, 0, g);
  box(0.05, C.h, C.d, woodSide, C.w / 2 - 0.025, C.h / 2, 0, g);
  box(C.w, 0.05, C.d + 0.02, woodMat, 0, C.h - 0.025, 0.01, g);
  box(C.w, 0.09, C.d, woodMat, 0, 0.045, 0.005, g);
  box(C.w - 0.06, C.h - 0.06, 0.018, backMat, 0, C.h / 2, -C.d / 2 + 0.009, g);
  C.shelves.forEach((y) => box(C.inner, C.board, C.d - 0.02, woodMat, 0, y - C.board / 2, 0.005, g));
  const sub = rack.s || '';
  const p = new THREE.Mesh(new THREE.BoxGeometry(1.15, 0.18, 0.03), [woodMat, woodMat, woodMat, woodMat, new THREE.MeshStandardMaterial({ map: plaqueTex(rack.e + ' ' + rack.n, sub), roughness: 0.5 }), woodMat]);
  p.position.set(0, C.h + 0.13, C.d / 2 - 0.02);
  g.add(p);
  caseX[rack.k] = x0;
  return g;
}

function placeBook(p, b, x0, parent) {
  const s = slot(Math.max(48, Math.min(200, Math.round(ROWH * p.t / p.h))));
  drawSpine(s, b);
  const geo = new THREE.BoxGeometry(p.t, p.h, p.d);
  const uv = geo.attributes.uv;
  const u0 = s.x / ATLAS, u1 = (s.x + s.w) / ATLAS, v1 = 1 - s.y / ATLAS, v0 = 1 - (s.y + ROWH) / ATLAS;
  uv.setXY(16, u0, v1); uv.setXY(17, u1, v1); uv.setXY(18, u0, v0); uv.setXY(19, u1, v0);
  uv.needsUpdate = true;
  const cm = coverMat(K.colourOf(b.id));
  const m = new THREE.Mesh(geo, [cm, cm, pagesMat, pagesMat, s.a.mat, pagesMat]);
  const r = rnd(K.hash(b.id + 'z'));
  const front = C.d / 2 - 0.015 - r() * 0.012;
  m.position.set(x0 - C.inner / 2 + p.x, C.shelves[p.shelf] + p.h / 2, front - p.d / 2);
  m.userData = { id: b.id, home: m.position.clone(), p };
  (parent || world).add(m);
  bookMeshes.push(m);
}

function decor(i, rack) {
  // a small brass lamp on "నావి", a globe on the universe / cosmos racks; not books, not tappable
  const x0 = i * (C.w + C.gap);
  if (rack.k === 'cosmos' || rack.k === 'universe') {
    const globe = new THREE.Mesh(new THREE.SphereGeometry(0.09, 24, 16), new THREE.MeshStandardMaterial({ color: '#2a5a7a', roughness: 0.5, metalness: 0.1 }));
    globe.position.set(x0 + C.inner / 2 - 0.16, C.shelves[0] + 0.15, 0.02);
    world.add(globe);
    box(0.02, 0.06, 0.02, new THREE.MeshStandardMaterial({ color: '#b08a3a', metalness: 0.6, roughness: 0.4 }), x0 + C.inner / 2 - 0.16, C.shelves[0] + 0.03, 0.02);
  }
}

function emptyLabel(text, x, shelf) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(0.5, 0.0625), new THREE.MeshStandardMaterial({ map: labelTex(text), roughness: 0.8 }));
  m.position.set(x, C.shelves[shelf] + 0.06, C.d / 2 - 0.05);
  world.add(m);
}

function rebuild() {
  // throw the old racks away (their pictures too)
  const atlasMats = new Set(atlases.map((a) => a.mat));
  world.traverse((o) => {
    if (!o.isMesh) return;
    o.geometry.dispose();
    (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { if (m.__keep || atlasMats.has(m)) return; if (m.map) m.map.dispose(); m.dispose(); });
  });
  while (world.children.length) world.remove(world.children[0]);
  atlases.forEach((a) => { a.tex.dispose(); a.mat.dispose(); });
  atlases = [];
  bookMeshes = [];
  caseX = {};
  S.books = K.merge(S.starter, S.shelf);
  S.byId = {};
  S.books.forEach((b) => { S.byId[b.id] = b; });
  rackList = K.racks(S.view);
  floor.visible = wall.visible = !S.hall;
  if (S.hall && S.view === 'era') { buildHall(); atlases.forEach((a) => { a.tex.needsUpdate = true; }); chips(); dirty = true; return; }
  rackList.forEach((rack, i) => {
    bookcase(i, rack);
    decor(i, rack);
    const x0 = i * (C.w + C.gap);
    if (rack.k === 'my') {
      const sh = K.myShelves(S.books, S.pos);
      const lay = K.myLayout(sh);
      lay.forEach((p) => placeBook(p, S.byId[p.id], x0));
      K.MY_SHELVES.forEach((s, k) => {
        const shelf = 4 - k;
        const has = (sh[s.k] || []).length > 0;
        const lab = new THREE.Mesh(new THREE.PlaneGeometry(0.36, 0.045), new THREE.MeshStandardMaterial({ map: labelTex(s.n + (has ? '' : ' · ఇంకా ఏమీ లేదు')), roughness: 0.8 }));
        lab.position.set(x0, C.shelves[shelf] - C.board / 2, C.d / 2 + 0.003);
        world.add(lab);
      });
    } else {
      const list = K.onRack(S.books, S.view, rack.k);
      K.layout(list).forEach((p) => placeBook(p, S.byId[p.id], x0));
      if (!list.length) emptyLabel(rack.k === 'mine' ? '➕ తో మీ పుస్తకాలు పెట్టండి' : 'ఇంకా పుస్తకాలు లేవు · 🔍 తో వెతకండి', x0, 3);
    }
  });
  atlases.forEach((a) => { a.tex.needsUpdate = true; });
  chips();
  dirty = true;
}

// ---------------------------------------------------------------- the chips (which rack) and the camera
function chips() {
  const c = $('chips');
  c.textContent = '';
  if (S.view === 'era' && !S.hall) c.appendChild(btn('🚶 కాలం హాల్‌లో నడువు', 'chip', () => enterHall()));
  (S.hall ? hallRacks() : rackList).forEach((r) => {
    const b = el('button', 'chip' + (r.k === S.rack ? ' on' : ''), r.e + ' ' + r.n);
    b.onclick = () => (S.hall ? hallGo(r.k) : goRack(r.k));
    c.appendChild(b);
  });
  $('bView').textContent = S.view === 'era' ? '🗂️ విభాగాలు' : '🕰️ కాలం';
  $('hallbar').hidden = !S.hall;
}
function goRack(k, instant) {
  if (caseX[k] === undefined) k = rackList.length > 1 ? rackList[1].k : rackList[0].k;
  S.rack = k;
  const tx = caseX[k], ty = k === 'my' ? 1.62 : 1.25;
  if (instant) { cam.x = tx; cam.y = ty; }
  else anim(500, (e, from) => { cam.x = from.x + (tx - from.x) * e; cam.y = from.y + (ty - from.y) * e; }, { x: cam.x, y: cam.y });
  chipsOn(k, true);
  dirty = true;
}
/** The chip of this rack lit (the hall's way-in chip is not a rack). */
function chipsOn(k, scroll) {
  [...$('chips').children].forEach((b) => {
    const r = rackList.find((x) => b.textContent === x.e + ' ' + x.n);
    const on = !!r && r.k === k;
    b.classList.toggle('on', on);
    if (on && scroll) b.scrollIntoView({ inline: 'center', block: 'nearest' });
  });
}
function nearestRack() {
  let best = null, bd = 1e9;
  for (const r of rackList) { const d = Math.abs(caseX[r.k] - cam.x); if (d < bd) { bd = d; best = r.k; } }
  return best;
}

// ---------------------------------------------------------------- moving about: drag, pinch, tap
let dirty = true;
const anims = [];
function anim(ms, step, from, done) { const a = { t0: performance.now(), ms, step, from, done }; anims.push(a); dirty = true; return a; }
const ease = (k) => (k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2);

const ptrs = new Map();
let drag = null;
cv.addEventListener('pointerdown', (e) => {
  cv.setPointerCapture(e.pointerId);
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (ptrs.size === 1) drag = { x: e.clientX, y: e.clientY, t: performance.now(), moved: 0, lx: e.clientX, ly: e.clientY, lt: performance.now() };
  else drag = null;
  cam.vx = cam.vy = 0;
});
cv.addEventListener('pointermove', (e) => {
  const p = ptrs.get(e.pointerId);
  if (!p) return;
  if (ptrs.size === 2) {
    const [a, b] = [...ptrs.values()];
    const d0 = Math.hypot(a.x - b.x, a.y - b.y);
    p.x = e.clientX; p.y = e.clientY;
    const [a2, b2] = [...ptrs.values()];
    const d1 = Math.hypot(a2.x - b2.x, a2.y - b2.y);
    if (d0 > 10 && d1 > 10 && S.hall) { hallCam.back = Math.max(0, Math.min(1.25, hallCam.back + (d0 - d1) * 0.004)); dirty = true; }
    else if (d0 > 10 && d1 > 10) { cam.z = Math.max(0.75, Math.min(3.8, cam.z * d0 / d1)); dirty = true; }
    return;
  }
  const dx = e.clientX - p.x, dy = e.clientY - p.y;
  p.x = e.clientX; p.y = e.clientY;
  if (!drag || S.sel) return;
  drag.moved += Math.abs(dx) + Math.abs(dy);
  if (S.hall) { hallMove(dx, dy); return; }
  const wpp = 2 * cam.z * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) / cv.clientHeight;
  cam.x -= dx * wpp; cam.y += dy * wpp;
  const now = performance.now(), dt = Math.max(1, now - drag.lt);
  cam.vx = -dx * wpp / dt; cam.vy = dy * wpp / dt;
  drag.lt = now;
  clampCam();
  dirty = true;
});
function up(e) {
  const had = ptrs.has(e.pointerId);
  ptrs.delete(e.pointerId);
  if (!had || !drag) return;
  const tap = drag.moved < 10 && performance.now() - drag.t < 450;
  drag = null;
  if (tap) { cam.vx = cam.vy = 0; pick(e.clientX, e.clientY); }
}
cv.addEventListener('pointerup', up);
cv.addEventListener('pointercancel', (e) => { ptrs.delete(e.pointerId); drag = null; });

function clampCam() {
  const xs = Object.values(caseX);
  const lo = Math.min(...xs) - 0.6, hi = Math.max(...xs) + 0.6;
  cam.x = Math.max(lo, Math.min(hi, cam.x));
  cam.y = Math.max(0.45, Math.min(2.15, cam.y));
}

const ray = new THREE.Raycaster();
function pick(x, y) {
  if (S.sel) { closeBook(); return; }
  const r = cv.getBoundingClientRect();
  ray.setFromCamera(new THREE.Vector2(((x - r.left) / r.width) * 2 - 1, -((y - r.top) / r.height) * 2 + 1), camera);
  const hit = ray.intersectObjects(bookMeshes, false)[0];
  if (hit) openBook(hit.object);
}

// ---------------------------------------------------------------- a book off the shelf: it comes out, turns, opens
let openG = null;
function titlePageTex(b) {
  return canvasTex(512, 768, (g, w, h) => {
    g.fillStyle = '#f4ead2'; g.fillRect(0, 0, w, h);
    const r = rnd(K.hash(b.id + 'p'));
    for (let i = 0; i < 1500; i++) { g.fillStyle = 'rgba(120,90,50,0.05)'; g.fillRect(r() * w, r() * h, 2, 2); }
    g.strokeStyle = '#9a7a44'; g.lineWidth = 2; g.strokeRect(30, 30, w - 60, h - 60);
    g.fillStyle = '#3a2412'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const lines = K.spineLines(b.t, 18);
    let y = 230;
    lines.forEach((l) => { const s = fitFont(g, l, w - 110, 44, 20, '700', SERIF); g.font = '700 ' + s + 'px ' + SERIF; g.fillText(l, w / 2, y); y += s * 1.3; });
    if (b.tt && b.tt !== b.t) { g.fillStyle = '#7a4a1a'; const s = fitFont(g, b.tt, w - 110, 30, 16, '600', SERIF); g.font = '600 ' + s + 'px ' + SERIF; g.fillText(b.tt, w / 2, y + 10); y += 50; }
    g.fillStyle = '#5a3a1c'; g.font = '500 28px ' + SERIF;
    if (b.a) g.fillText(b.a, w / 2, y + 50);
    const yr = K.yearSaid(b.y);
    if (yr) { g.font = '500 24px ' + SERIF; g.fillText(yr, w / 2, y + 95); }
    g.strokeStyle = '#9a7a44'; g.beginPath(); g.moveTo(w / 2 - 60, h - 150); g.lineTo(w / 2 + 60, h - 150); g.stroke();
    g.font = '500 22px ' + SERIF; g.fillStyle = '#8a6a3a'; g.fillText('📚 Jarvis గ్రంథాలయం', w / 2, h - 110);
  });
}
function coverArtTex(b) {
  const col = K.colourOf(b.id);
  const t = canvasTex(512, 768, (g, w, h) => {
    const gr = g.createLinearGradient(0, 0, w, h);
    gr.addColorStop(0, shade(col, 0.12)); gr.addColorStop(1, shade(col, -0.35));
    g.fillStyle = gr; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#d6b05a'; g.lineWidth = 6; g.strokeRect(26, 26, w - 52, h - 52);
    g.lineWidth = 2; g.strokeRect(40, 40, w - 80, h - 80);
    g.fillStyle = '#f2d78f'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const lines = K.spineLines(b.t, 16);
    let y = 260;
    lines.forEach((l) => { const s = fitFont(g, l, w - 120, 50, 20, '700', SERIF); g.font = '700 ' + s + 'px ' + SERIF; g.fillText(l, w / 2, y); y += s * 1.3; });
    if (b.a) { g.font = '500 28px ' + SERIF; g.fillStyle = '#e8cf95'; g.fillText(b.a, w / 2, h - 140); }
  });
  if (b.cover) { // his phone keeps a cover picture for it: drawn over, in the gold frame
    const img = new Image();
    img.onload = () => {
      const c = t.image, g = c.getContext('2d');
      const w = c.width, h = c.height, s = Math.min((w - 90) / img.width, (h - 90) / img.height);
      g.drawImage(img, (w - img.width * s) / 2, (h - img.height * s) / 2, img.width * s, img.height * s);
      t.needsUpdate = true; dirty = true;
    };
    img.src = 'covers/' + b.id + '.jpg';
  }
  return t;
}

function openBook(mesh) {
  const b = S.byId[mesh.userData.id];
  if (!b) return;
  const p = mesh.userData.p;
  S.sel = { mesh, b };
  const G = new THREE.Group();
  const wq = new THREE.Quaternion();
  mesh.getWorldPosition(G.position);
  mesh.getWorldQuaternion(wq);
  G.quaternion.copy(wq);
  world.add(G);
  const mats = mesh.material.slice();
  const tp = new THREE.MeshStandardMaterial({ map: titlePageTex(b), roughness: 0.9 });
  mats[0] = tp;
  const block = new THREE.Mesh(mesh.geometry, mats);
  G.add(block);
  const cm = coverMat(K.colourOf(b.id));
  const art = new THREE.MeshStandardMaterial({ map: coverArtTex(b), roughness: 0.6 });
  const endp = new THREE.MeshStandardMaterial({ color: '#c9a56a', roughness: 0.9 });
  const pivot = new THREE.Group();
  pivot.position.set(p.t / 2 + 0.002, 0, p.d / 2 + 0.002);
  const cover = new THREE.Mesh(new THREE.BoxGeometry(0.004, p.h + 0.006, p.d + 0.004), [art, endp, cm, cm, cm, cm]);
  cover.position.set(0, 0, -(p.d + 0.004) / 2);
  pivot.add(cover);
  G.add(pivot);
  const back = new THREE.Mesh(new THREE.BoxGeometry(0.004, p.h + 0.006, p.d + 0.004), cm);
  back.position.set(-p.t / 2 - 0.002, 0, 0);
  G.add(back);
  mesh.visible = false;
  openG = { G, pivot, mesh, mats: [tp, art, endp] };
  // where it goes: in front of the eyes, upper part of the screen (the card is below)
  const vis = 2 * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
  const aspect = cv.clientWidth / Math.max(1, cv.clientHeight);
  const dist = Math.max(p.h / (0.38 * vis), (2 * p.d) / (0.8 * vis * aspect));
  const f = new THREE.Vector3();
  camera.getWorldDirection(f);
  f.y = 0; f.normalize();
  const right = new THREE.Vector3().crossVectors(f, new THREE.Vector3(0, 1, 0));
  const to = camera.position.clone().addScaledVector(f, dist).addScaledVector(right, p.d * 0.5);
  to.y = camera.position.y + 0.1 * vis * dist;
  const from = G.position.clone();
  const out = from.clone().addScaledVector(new THREE.Vector3(0, 0, 1).applyQuaternion(wq), 0.12);
  const yaw = Math.atan2(-f.x, -f.z);
  const q0 = G.quaternion.clone(), q1 = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), yaw).multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(0.12, -Math.PI / 2, 0)));
  anim(260, (e) => { G.position.lerpVectors(from, out, e); }, null, () => {
    anim(650, (e) => { G.position.lerpVectors(out, to, e); G.quaternion.slerpQuaternions(q0, q1, e); }, null, () => {
      anim(620, (e) => { pivot.rotation.y = -2.55 * e; }, null);
    });
  });
  showCard(b);
}

function closeBook(instant) {
  const o = openG;
  hideCard();
  S.sel = null;
  if (!o) return;
  openG = null;
  const done = () => { world.remove(o.G); o.mesh.visible = true; o.mats.forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); }); o.G.children.forEach((c) => { if (c.geometry && c !== o.G.children[0]) c.geometry.dispose(); }); dirty = true; };
  if (instant) { done(); return; }
  const home = new THREE.Vector3(), q1 = new THREE.Quaternion();
  o.mesh.getWorldPosition(home);
  o.mesh.getWorldQuaternion(q1);
  const from = o.G.position.clone(), q0 = o.G.quaternion.clone();
  const out = home.clone().addScaledVector(new THREE.Vector3(0, 0, 1).applyQuaternion(q1), 0.12);
  anim(260, (e) => { o.pivot.rotation.y = -2.55 * (1 - e); }, null, () => {
    anim(480, (e) => { o.G.position.lerpVectors(from, out, e); o.G.quaternion.slerpQuaternions(q0, q1, e); }, null, () => {
      anim(220, (e) => { o.G.position.lerpVectors(out, home, e); }, null, done);
    });
  });
}

// ---------------------------------------------------------------- drawing
function resize() {
  const w = cv.clientWidth, h = cv.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / Math.max(1, h);
  camera.updateProjectionMatrix();
  dirty = true;
}
window.addEventListener('resize', resize);
function frame(now) {
  requestAnimationFrame(frame);
  for (let i = anims.length - 1; i >= 0; i--) {
    const a = anims[i];
    const k = Math.min(1, (now - a.t0) / a.ms);
    a.step(ease(k), a.from);
    if (k >= 1) { anims.splice(i, 1); if (a.done) a.done(); }
    dirty = true;
  }
  if (!drag && !S.hall && (Math.abs(cam.vx) > 1e-5 || Math.abs(cam.vy) > 1e-5)) {
    cam.x += cam.vx * 16; cam.y += cam.vy * 16; cam.vx *= 0.92; cam.vy *= 0.92;
    clampCam();
    dirty = true;
    if (Math.abs(cam.vx) < 1e-5 && Math.abs(cam.vy) < 1e-5) onSettle();
  }
  if (!dirty) return;
  dirty = false;
  if (S.hall) {
    // (standing back from where he looks: two fingers apart → nearer, together → the whole bookcase)
    const sx = Math.sin(hallCam.yaw), cz = Math.cos(hallCam.yaw), bk = hallCam.back;
    camera.position.set(sx * bk, 1.32, hallCam.z + cz * bk);
    camera.lookAt(sx * bk - sx * 3, 1.18, hallCam.z + cz * bk - cz * 3);
    lamp.position.set(0, 2.5, hallCam.z - 0.6);
  } else {
    camera.position.set(cam.x, cam.y, cam.z);
    camera.lookAt(cam.x, cam.y - 0.04, 0);
    lamp.position.set(cam.x, cam.y + 0.9, 1.2);
  }
  renderer.render(scene, camera);
}
function onSettle() {
  if (S.hall) return;
  const k = nearestRack();
  if (k && k !== S.rack) { S.rack = k; chipsOn(k, false); }
}
let settleT = 0;
cv.addEventListener('pointerup', () => { clearTimeout(settleT); settleT = setTimeout(onSettle, 700); });

// ================================================================ the card under an open book
function showCard(b) {
  const c = $('card');
  c.textContent = '';
  c.hidden = false;
  c.appendChild(btn('✕', 'x', () => closeBook()));
  c.appendChild(el('h2', '', b.t));
  if (b.tt && b.tt !== b.t) c.appendChild(el('div', 'sub', b.tt));
  const who = [b.a, K.yearSaid(b.y)].filter(Boolean).join(' · ');
  if (who) c.appendChild(el('div', 'sub', who));
  if (b.about || b.summary) c.appendChild(el('p', '', b.about || b.summary));
  const marks = el('div', 'row');
  const ready = b.state === 'ready';
  if (ready) marks.appendChild(el('span', 'pill ok', '✅ ఫోన్‌లో ఉంది · ' + (b.n || 0) + ' అధ్యాయాలు · ' + K.pagesSaid(b.chars)));
  else if (b.wish) { const m = K.mark(b); marks.appendChild(el('span', 'pill ' + m.cls, m.i + ' ' + m.s)); }
  else if (b.src === 'home') { /* (only the 🏠 pill below: no free copy found) */ }
  else marks.appendChild(el('span', 'pill ok', '✅ ఉచితం · ' + (b.src === 'ws' ? 'తెలుగు వికీసోర్స్' : b.src === 'se' ? 'Standard Ebooks' : 'Project Gutenberg')));
  if (b.credit && ready) marks.appendChild(el('span', 'pill', b.credit));
  const at = S.pos[b.id];
  if (ready && at && !b.done) marks.appendChild(el('span', 'pill', '🔖 అధ్యాయం ' + (at[0] + 1) + ' దగ్గర ఆగారు'));
  if (b.done) marks.appendChild(el('span', 'pill ok', '✓ పూర్తయింది'));
  if (b.home) marks.appendChild(el('span', 'pill web', '🏠 మీ ఇంట్లో ఉంది (కాగితం పుస్తకం)'));
  const ai = aiInfo(b);
  if (ready && b.lang !== 'te' && ai.any) {
    const pl = el('span', 'pill ok', '✨ AI తెలుగు: ' + ai.done + ' / ' + ai.n + ' అధ్యాయాలు' + (ai.part ? ' (+' + ai.part + ' సగం)' : '') + ' · ' + ai.pct + '%');
    pl.onclick = (ev) => { ev.stopPropagation(); openTrs(); };
    marks.appendChild(pl);
    if (ai.left > 0 && ai.leftRs !== null) marks.appendChild(el('span', 'pill', 'మిగిలినవి సుమారు ' + K.money(ai.leftRs)));
  }
  c.appendChild(marks);
  const prog = el('div', 'prog', S.busy[b.id] || '');
  prog.id = 'cardProg';
  c.appendChild(prog);
  const row = el('div', 'row');
  const te = b.lang !== 'te';
  if (ready) {
    row.appendChild(btn('🔊 చదువు', 'gold', () => post({ t: 'read', id: b.id, lang: 'en' })));
    if (te) row.appendChild(btn('🔊 తెలుగులో (ఫోన్, ₹0)', '', () => post({ t: 'read', id: b.id, lang: 'te' })));
    if (te) row.appendChild(btn('✨ AI తెలుగు', '', () => post({ t: 'quote', id: b.id, unit: (S.pos[b.id] || [0])[0] })));
    row.appendChild(btn('📖 తెరపై చదువు', '', () => openReader(b.id, (S.pos[b.id] || [0])[0])));
  } else if ((!b.wish && b.src !== 'home') || b.free) {
    row.appendChild(btn('⬇️ రాక్‌లోకి దించు (ఉచితం)', 'gold', () => download(b, '')));
    row.appendChild(btn('⬇️ దించి చదువు', '', () => download(b, 'read')));
  }
  if (b.wish && b.ol) row.appendChild(btn('🌐 Open Library లో చూడు', '', () => post({ t: 'site', url: 'https://openlibrary.org' + b.ol })));
  c.appendChild(row);
  const row2 = el('div', 'row');
  row2.appendChild(btn(b.fav ? '⭐ ఇష్టం (తీసేయి)' : '☆ ఇష్టం', '', () => { post({ t: 'fav', id: b.id, on: !b.fav }); b.fav = !b.fav; showCard(b); }));
  if (ready) row2.appendChild(btn(b.done ? '✓ పూర్తయింది (తీసేయి)' : '✓ పూర్తయింది', '', () => { post({ t: 'done', id: b.id, on: !b.done }); b.done = !b.done; showCard(b); }));
  if (ready || b.wish) row2.appendChild(btn('🗑️ ' + (b.starter ? 'ఫోన్ నుంచి తీసేయి' : 'తీసేయి'), '', () => { post({ t: 'remove', id: b.id }); closeBook(); }));
  c.appendChild(row2);
  if (ready && te && !S.pack) c.appendChild(el('p', 'muted', 'తెలుగులో వినడానికి ఫోన్‌కి ఒకసారి తెలుగు ప్యాక్ (సుమారు 30 MB) కావాలి; "🔊 తెలుగులో" నొక్కితే అడుగుతాను.'));
}
function hideCard() { $('card').hidden = true; }
function download(b, then) {
  S.busy[b.id] = '⬇️ మొదలుపెడుతున్నాను…';
  const p = $('cardProg'); if (p) p.textContent = S.busy[b.id];
  post({ t: 'download', item: b, then });
}

// ================================================================ 🔍 search
function openSearch(q) {
  closeBook(true);
  $('search').hidden = false;
  if (q !== undefined) { $('q').value = q; runSearch(); }
  else setTimeout(() => $('q').focus(), 50);
}
function runSearch() {
  const q = $('q').value.trim();
  if (!q) return;
  const b = $('sBody');
  b.textContent = '';
  b.appendChild(el('p', 'prog', '🔍 "' + q + '" వెతుకుతున్నాను… (Project Gutenberg, Open Library' + (/[ఀ-౿]/.test(q) ? ', తెలుగు వికీసోర్స్' : '') + ')'));
  $('q').blur();
  post({ t: 'search', q });
}
function showResults(m) {
  const b = $('sBody');
  b.textContent = '';
  S.results = m.results || [];
  if (m.problems && m.problems.length) b.appendChild(el('p', 'muted', '⚠️ ' + m.problems.join(' · ') + ' (ఇంటర్నెట్ చూడండి)'));
  if (!S.results.length) { b.appendChild(el('p', '', 'ఏమీ దొరకలేదు. పేరు ఇంకోలా (English లో కూడా) రాసి చూడండి.')); return; }
  S.results.forEach((r) => {
    const card = el('div', 'res');
    const cvr = el('div', 'cv', r.lang === 'te' ? 'అ' : '📕');
    cvr.id = 'th-' + r.id;
    card.appendChild(cvr);
    const i = el('div', 'i');
    i.appendChild(el('div', 't1', r.t || ''));
    i.appendChild(el('div', 't2', [r.tt && r.tt !== r.t ? r.tt : '', r.a, K.yearSaid(r.y)].filter(Boolean).join(' · ')));
    const mk = K.mark(r);
    i.appendChild(el('span', 'pill ' + mk.cls, mk.i + ' ' + mk.s));
    const row = el('div', 'row');
    const prog = el('div', 'prog', S.busy[r.id] || '');
    prog.id = 'rp-' + r.id;
    if (r.have || (S.byId[r.id] && S.byId[r.id].state === 'ready')) row.appendChild(btn('📖 రాక్‌లో చూపించు', 'gold', () => { $('search').hidden = true; show(r.id); }));
    else if (r.free) {
      row.appendChild(btn('⬇️ రాక్‌లోకి దించు', 'gold', () => { S.busy[r.id] = '⬇️ మొదలుపెడుతున్నాను…'; prog.textContent = S.busy[r.id]; post({ t: 'download', item: r, then: '' }); }));
      row.appendChild(btn('⬇️ దించి చదువు', '', () => { S.busy[r.id] = '⬇️ మొదలుపెడుతున్నాను…'; prog.textContent = S.busy[r.id]; post({ t: 'download', item: r, then: 'read' }); }));
    } else {
      row.appendChild(btn('🔖 కోరికల రాక్‌లో పెట్టు', '', () => post({ t: 'wish', item: r })));
      if (r.ol) row.appendChild(btn('🌐 చూడు', '', () => post({ t: 'site', url: 'https://openlibrary.org' + r.ol })));
    }
    i.appendChild(row);
    i.appendChild(prog);
    card.appendChild(i);
    b.appendChild(card);
  });
}

// ================================================================ ➕ his own books
function openAdd() {
  closeBook(true);
  const a = $('add');
  a.textContent = '';
  a.hidden = false;
  a.appendChild(btn('✕', 'x', closeAdd));
  a.appendChild(el('h2', '', '➕ మీ పుస్తకం రాక్‌లో పెట్టు'));
  a.appendChild(el('p', 'muted', 'మీ ఫోన్‌లోని PDF / EPUB / TXT, లేదా మీ దగ్గర ఉన్న పుస్తకం పేజీల ఫోటోలు. అక్షరాలు మాత్రమే దాచుతాను; ఫోటోలు, PDF కాపీ దాచను.'));
  a.appendChild(btn('📄 ఫోన్ నుంచి ఫైల్ (PDF / EPUB / TXT)', 'wide', () => post({ t: 'add', kind: 'file' })));
  a.appendChild(btn('📸 పుస్తకం పేజీల ఫోటోలు (స్కానర్)', 'wide', () => post({ t: 'add', kind: 'scan' })));
  a.appendChild(btn('🏠 ఇంటి పుస్తకాల అర ఫోటో (పేర్లు AI చదువుతుంది)', 'wide', () => { closeAdd(); post({ t: 'shelf_photo' }); }));
}
function closeAdd() { $('add').hidden = true; if (S.addInfo) post({ t: 'add_cancel' }); S.addInfo = null; }
function addForm(m) {
  const info = m.info || {};
  S.addInfo = info;
  const a = $('add');
  a.textContent = '';
  a.hidden = false;
  a.appendChild(btn('✕', 'x', closeAdd));
  a.appendChild(el('h2', '', info.kind === 'photos' ? '📸 ' + info.pages + ' పేజీల ఫోటోలు' : '📄 ' + (info.kind || '').toUpperCase() + (info.pages ? ' · ' + info.pages + ' పేజీలు' : '')));
  a.appendChild(el('label', 'l', 'పుస్తకం పేరు'));
  const t = el('input', 'f'); t.value = info.t || ''; a.appendChild(t);
  a.appendChild(el('label', 'l', 'రచయిత'));
  const au = el('input', 'f'); au.value = info.a || ''; a.appendChild(au);
  a.appendChild(el('label', 'l', 'ఏ రాక్‌లో'));
  const sec = el('select', 'f');
  K.SECTIONS.forEach((s) => { const o = el('option', '', s.e + ' ' + s.n); o.value = s.k; if (s.k === 'mine') o.selected = true; sec.appendChild(o); });
  a.appendChild(sec);
  a.appendChild(el('label', 'l', 'ఏ కాలం గురించి'));
  const era = el('select', 'f');
  K.ERAS.forEach((s) => { const o = el('option', '', s.e + ' ' + s.n + ' (' + s.s + ')'); o.value = s.k; if (s.k === 'now') o.selected = true; era.appendChild(o); });
  a.appendChild(era);
  let lang = 'en';
  const pages = info.kind === 'pdf' || info.kind === 'photos';
  if (pages) {
    a.appendChild(el('label', 'l', 'పేజీల్లో భాష'));
    const n = info.pages || 0;
    const r1 = el('div', 'radio on', '🔤 English: ఫోన్ తానే చదువుతుంది · ₹0');
    const cost = m.quote1 > 0 ? K.money(m.quote1 * n) : '';
    const r2 = el('div', 'radio', '🇮🇳 తెలుగు: మీ AI చదువుతుంది · ' + (cost ? 'సుమారు ' + cost + ' (' + n + ' పేజీలు)' : 'వర్క్‌షాప్ AI ఎంచుకోవాలి'));
    r1.onclick = () => { lang = 'en'; r1.classList.add('on'); r2.classList.remove('on'); };
    r2.onclick = () => { lang = 'te'; r2.classList.add('on'); r1.classList.remove('on'); };
    a.appendChild(r1); a.appendChild(r2);
  }
  const prog = el('div', 'prog', ''); prog.id = 'addProg';
  const row = el('div', 'row');
  const go = btn('➕ రాక్‌లో పెట్టు', 'gold', () => {
    go.disabled = true;
    prog.textContent = 'మొదలుపెడుతున్నాను…';
    post({ t: 'add_go', form: { kind: info.kind, t: t.value.trim(), a: au.value.trim(), sec: sec.value, era: era.value, lang } });
    row.appendChild(btn('⏹ ఆపు', '', () => post({ t: 'ai_stop' })));
  });
  row.appendChild(go);
  a.appendChild(row);
  a.appendChild(prog);
}

// ================================================================ ✨ the cost sheet, the Telugu pack sheet
function quoteSheet(m) {
  const q = $('quote');
  q.textContent = '';
  q.hidden = false;
  q.appendChild(btn('✕', 'x', () => { q.hidden = true; }));
  q.appendChild(el('h2', '', '✨ AI తెలుగు'));
  const b = S.byId[m.id] || {};
  q.appendChild(el('div', 'sub', b.t || ''));
  // 🤖 the library's own AI (his pick; the workshop's AI stays as it is)
  const mrow = el('div', 'row');
  mrow.appendChild(btn('🤖 మోడల్ మార్చు · ప్రతి మోడల్ ధర చూడు', 'wide', () => {
    q.textContent = '';
    q.appendChild(btn('✕', 'x', () => { q.hidden = true; }));
    q.appendChild(el('h2', '', '🤖 లైబ్రరీ AI మోడల్'));
    q.appendChild(el('p', 'muted', 'మీ keys తో వాడగలిగే మోడల్స్ లిస్ట్ తెస్తున్నాను…'));
    post({ t: 'models', id: m.id, unit: m.unit });
  }));
  if (m.why) { q.appendChild(el('p', '', m.why)); q.appendChild(mrow); return; }
  q.appendChild(el('p', '', 'మీ AI ఇంగ్లీష్‌ని సహజమైన తెలుగులోకి మారుస్తుంది (ఫోన్ అనువాదం కంటే చాలా బాగుంటుంది). ఒకసారి చేసినది దాచుకుంటాను: మళ్ళీ వింటే ₹0.'));
  q.appendChild(el('p', 'muted', '🤖 మోడల్: ' + (m.model || '') + (m.lib ? ' (లైబ్రరీ కోసం మీరు ఎంచుకున్నది)' : ' (వర్క్‌షాప్ AI)')
    + ' · ఈ నెల ₹' + m.used + ' వాడింది / హద్దు ₹' + m.limit + ' · అంకెలు సుమారు' + (m.known === false ? ' · ఈ మోడల్ ధర అంచనా మాత్రమే' : '')));
  if (m.chapter && !m.chapter.done && m.chapter.ws > 0) q.appendChild(el('p', 'muted', 'వర్క్‌షాప్ AI (' + m.ws + ') తో అయితే ఇదే అధ్యాయానికి సుమారు ' + K.money(m.chapter.ws)));
  q.appendChild(mrow);
  const row = el('div', 'row');
  if (m.chapter && !m.chapter.done) row.appendChild(btn('✨ ఈ అధ్యాయం (అధ్యాయం ' + (m.unit + 1) + ') · సుమారు ' + K.money(m.chapter.rupees), 'gold wide', () => { q.hidden = true; post({ t: 'ai_go', id: m.id, scope: 'chapter', unit: m.unit }); }));
  else if (m.chapter && m.chapter.done) row.appendChild(el('p', 'muted', '✅ అధ్యాయం ' + (m.unit + 1) + ' కి AI తెలుగు ఇప్పటికే ఉంది.'));
  if (m.book && m.book.left > 0) row.appendChild(btn('✨ మిగిలిన పుస్తకం మొత్తం (' + m.book.left + ' అధ్యాయాలు) · సుమారు ' + K.money(m.book.rupees), 'wide', () => { q.hidden = true; post({ t: 'ai_go', id: m.id, scope: 'book', unit: m.unit }); }));
  row.appendChild(btn('వద్దు', 'wide', () => { q.hidden = true; }));
  q.appendChild(row);
}
/** 🤖 Every model his keys can use, with this chapter's AI Telugu cost; tapping one makes it the library's AI. */
function modelSheet(m) {
  const q = $('quote');
  q.textContent = '';
  q.hidden = false;
  q.appendChild(btn('✕', 'x', () => { q.hidden = true; S.afterModel = null; }));
  q.appendChild(el('h2', '', '🤖 లైబ్రరీ AI మోడల్'));
  const tk = TOOL_COST[m.kind];
  q.appendChild(el('div', 'sub', tk ? tk(m) + ' · ప్రతి మోడల్‌తో సుమారు ఖర్చు' : 'అధ్యాయం ' + (m.unit + 1) + ' · ' + Number(m.chars || 0).toLocaleString('en-IN') + ' అక్షరాలు · ప్రతి మోడల్‌తో AI తెలుగు సుమారు ఖర్చు'));
  q.appendChild(el('p', 'muted', 'ఇక్కడ ఎంచుకున్నది లైబ్రరీకి మాత్రమే (✨ AI తెలుగు, 📸 తెలుగు పేజీ ఫోటోలు, 📝 ❓ 💬 🗺️ ✨సూచనలు 🏠); వర్క్‌షాప్ AI మారదు. '
    + 'చౌక మోడల్ తెలుగు కొంచెం తక్కువ బాగుండొచ్చు: ముందు ఒక్క అధ్యాయం చేయించి చూడండి. "అంచనా" అంటే ఆ మోడల్ ధర నా లిస్ట్‌లో లేదు.'));
  if (m.problems) q.appendChild(el('p', 'muted', '⚠ ' + m.problems));
  const pick = (id) => {
    q.textContent = '';
    q.appendChild(el('p', 'muted', '…'));
    post({ t: 'model_set', model: id, id: m.id, unit: m.unit });
  };
  if (m.lib) q.appendChild(btn(m.ws ? '↩ వర్క్‌షాప్ AI నే వాడు (' + m.ws + ')' : '↩ నా ఎంపిక తీసేయి', 'wide', () => pick('')));
  const list = m.list || [];
  if (!list.length) { q.appendChild(btn('సరే', 'wide', () => { q.hidden = true; })); return; }
  let order = 'cheap';
  const sorts = el('div', 'row');
  const cC = btn('చౌక మొదట', 'chip on', () => { order = 'cheap'; cC.className = 'chip on'; cN.className = 'chip'; draw(); });
  const cN = btn('కొత్తవి మొదట', 'chip', () => { order = 'new'; cN.className = 'chip on'; cC.className = 'chip'; draw(); });
  sorts.appendChild(cC); sorts.appendChild(cN);
  q.appendChild(sorts);
  const box = el('input', 'f');
  box.type = 'search';
  box.placeholder = 'వెతకండి (ఉదా: haiku, flash, mini, sonnet)';
  q.appendChild(box);
  const ml = el('div', 'mlist');
  q.appendChild(ml);
  function draw() {
    const w = box.value.trim().toLowerCase();
    const shown = list.filter((x) => !w || (x.id + ' ' + x.co + ' ' + (x.name || '')).toLowerCase().includes(w));
    if (order === 'cheap') shown.sort((a, b) => (a.known === b.known ? 0 : a.known ? -1 : 1) || a.r - b.r || a.n - b.n);
    else shown.sort((a, b) => a.n - b.n);
    ml.textContent = '';
    shown.forEach((x) => {
      const on = x.id === m.cur;
      const b = el('button', 'wide' + (on ? ' on' : ''));
      b.appendChild(el('span', 'r' + (x.known ? '' : ' guess'), K.money(x.r) + (x.known ? '' : ' (అంచనా)')));
      b.appendChild(el('span', 'mid', (on ? '✓ ' : '') + x.id));
      b.appendChild(el('span', 'meta', x.co + (x.name ? ' · ' + x.name : '') + (on ? ' · ఇప్పుడు ఇదే' : '')));
      b.onclick = (ev) => { ev.stopPropagation(); if (!on) pick(x.id); };
      ml.appendChild(b);
    });
    if (!shown.length) ml.appendChild(el('p', 'muted', 'ఆ పేరుతో మోడల్ లేదు.'));
  }
  box.addEventListener('input', draw);
  draw();
}
function packSheet() {
  const q = $('quote');
  q.textContent = '';
  q.hidden = false;
  q.appendChild(btn('✕', 'x', () => { q.hidden = true; }));
  q.appendChild(el('h2', '', '⬇️ ఫోన్ తెలుగు ప్యాక్'));
  q.appendChild(el('p', '', 'ఇంగ్లీష్ పుస్తకాలు ఫోన్‌లోనే తెలుగులోకి మార్చడానికి Google ఉచిత అనువాదం ప్యాక్ ఒకసారి దించాలి (సుమారు 30 MB, ఇంటర్నెట్‌తో). తర్వాత ఇంటర్నెట్ లేకున్నా, ₹0.'));
  const row = el('div', 'row');
  row.appendChild(btn('⬇️ దించు', 'gold', () => { q.hidden = true; post({ t: 'pack' }); }));
  row.appendChild(btn('వద్దు', '', () => { q.hidden = true; }));
  q.appendChild(row);
}

// ================================================================ 📖 the reading screen
const R = S.reader;
function openReader(id, unit) {
  const b = S.byId[id];
  if (!b) return;
  closeBook(true);
  R.id = id; R.unit = Math.max(0, unit || 0); R.parts = []; R.te = null; R.ai = null; R.follow = true;
  R.mode = b.lang === 'te' ? 'en' : (S.prefs.mode || 'en');
  $('rTitle').textContent = b.t;
  $('reader').hidden = false;
  $('text').textContent = '';
  $('text').appendChild(el('p', 'muted', 'తెరుస్తున్నాను…'));
  applyReaderLook();
  post({ t: 'chapter', id, unit: R.unit });
}
function applyReaderLook() {
  $('reader').classList.toggle('night', !!S.prefs.night);
  $('text').style.fontSize = (S.prefs.font || 19) + 'px';
  $('rNight').textContent = S.prefs.night ? '☀️' : '🌙';
  const r = S.prefs.rate || 0;
  $('rRate').textContent = 'వేగం ' + (r > 0 ? r : 1) + '×';
}
function chapList() {
  const sel = $('chap'), b = S.byId[R.id] || {};
  sel.textContent = '';
  for (let i = 0; i < R.n; i++) { const o = el('option', '', (i + 1) + '. ' + (R.titles[i] || 'అధ్యాయం ' + (i + 1)) + (b.lang === 'te' ? '' : aiMark(b, i))); o.value = String(i); if (i === R.unit) o.selected = true; sel.appendChild(o); }
}
function pairOf() { return S.prefs.pair === 'ai' ? 'ai' : 'te'; }
/** The phone's Telugu is on the screen (alone, or under the English). */
function wantsPhoneTe() { return R.mode === 'te' || (R.mode === 'both' && pairOf() === 'te'); }
/** The AI's Telugu is on the screen (alone, or under the English). */
function showsAi() { return R.mode === 'ai' || (R.mode === 'both' && pairOf() === 'ai'); }
function gotChapter(m) {
  if (m.id !== R.id) return;
  R.unit = m.unit; R.n = m.n; R.parts = m.parts || []; R.te = m.te || null; R.ai = m.ai || null; R.marks = m.marks || [];
  if (m.titles && m.titles.length) R.titles = m.titles;
  const sel = $('chap');
  sel.textContent = '';
  chapList();
  modesBar();
  toolsBar();
  renderText();
  $('rBody').scrollTop = 0;
  const j = S.jumpTo;
  if (j && j.id === R.id && j.unit === R.unit) {
    S.jumpTo = null; R.follow = false; R.scrolledAt = Date.now();
    const d = $('text').querySelector('.p[data-i="' + j.part + '"]');
    if (d) setTimeout(() => d.scrollIntoView({ block: 'center' }), 60);
  }
  if (wantsPhoneTe()) needTe();
}
function modesBar() {
  const b = S.byId[R.id] || {};
  const m = $('modes');
  m.textContent = '';
  const list = b.lang === 'te' ? [['en', 'తెలుగు']] : [['en', 'English'], ['te', 'తెలుగు (ఫోన్)'], ['ai', R.ai ? '✨ AI తెలుగు' : '✨ AI తెలుగు (చేయించాలి)'], ['both', 'రెండూ']];
  list.forEach(([k, n]) => {
    const c = el('button', 'chip' + (R.mode === k ? ' on' : ''), n);
    c.onclick = () => {
      if (k === 'ai' && !R.ai) { post({ t: 'quote', id: R.id, unit: R.unit }); return; }
      R.mode = k; S.prefs.mode = k; post({ t: 'prefs', mode: k });
      modesBar(); renderText();
      if (wantsPhoneTe()) needTe();
    };
    m.appendChild(c);
  });
  // రెండూ: which Telugu goes under the English (his pick, kept)
  const pr = $('pairs');
  pr.textContent = '';
  pr.hidden = !(R.mode === 'both' && b.lang !== 'te');
  if (pr.hidden) return;
  pr.appendChild(el('span', 'pl', 'ఇంగ్లీష్ తో పాటు:'));
  [['te', '📱 ఫోన్ తెలుగు'], ['ai', R.ai ? '✨ AI తెలుగు' : '✨ AI తెలుగు (చేయించాలి)']].forEach(([k, n]) => {
    const c = el('button', 'chip' + (pairOf() === k ? ' on' : ''), n);
    c.onclick = () => {
      S.prefs.pair = k; post({ t: 'prefs', pair: k });
      modesBar(); renderText();
      if (k === 'te') needTe();
      else if (!R.ai) post({ t: 'quote', id: R.id, unit: R.unit });
    };
    pr.appendChild(c);
  });
}
function needTe() {
  if (R.te && R.te.every((x) => x)) return;
  if (!S.pack) { packSheet(); return; }
  post({ t: 'te_phone', id: R.id, unit: R.unit });
}
function teOf(i) { return showsAi() ? (R.ai && R.ai[i]) : (R.te && R.te[i]); }
function renderText() {
  const t = $('text');
  t.textContent = '';
  const noAi = R.mode === 'both' && pairOf() === 'ai' && !R.ai;
  if (noAi && R.parts.length) { // (English with the AI's Telugu, but this chapter has none yet: said, never filled with the phone's)
    const n = el('div', 'note');
    n.appendChild(document.createTextNode('✨ ఈ అధ్యాయానికి AI తెలుగు ఇంకా చేయించలేదు.'));
    n.appendChild(btn('ఖర్చు చూడు', '', () => post({ t: 'quote', id: R.id, unit: R.unit })));
    t.appendChild(n);
  }
  R.parts.forEach((p, i) => {
    const d = el('div', 'p');
    d.dataset.i = String(i);
    if (R.mode === 'en') d.textContent = p;
    else if (R.mode === 'both') { d.appendChild(document.createTextNode(p)); if (!noAi) { const s = el('span', 'te' + (teOf(i) ? '' : ' wait'), teOf(i) || '…'); d.appendChild(s); } }
    else { const tx = teOf(i); if (tx) d.textContent = tx; else { d.textContent = p; d.classList.add('wait'); } }
    const mk = markOf(i);
    if (mk) { d.classList.add('hl'); if (mk.n) d.appendChild(el('span', 'mnote', '📝 ' + mk.n)); }
    t.appendChild(d);
  });
  if (!R.parts.length) t.appendChild(el('p', 'muted', 'ఈ అధ్యాయంలో అక్షరాలు లేవు.'));
  markNow(false);
  playLabel();
}
function gotTe(m) {
  if (m.id !== R.id || m.unit !== R.unit) return;
  if (!R.te) R.te = new Array(R.parts.length).fill(null);
  (m.list || []).forEach((x, k) => { R.te[m.from + k] = x; });
  if (wantsPhoneTe()) renderText();
}
function markNow(scroll) {
  const n = S.now;
  const on = n && n.on && n.id === R.id && n.unit === R.unit;
  [...$('text').children].forEach((d) => d.classList.toggle('now', !!on && Number(d.dataset.i) === n.part));
  if (on && scroll && R.follow && Date.now() - R.scrolledAt > 6000) {
    const d = $('text').querySelector('.p[data-i="' + n.part + '"]');
    if (d) d.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}
function playLabel() {
  const n = S.now;
  const on = n && n.on && n.id === R.id;
  $('rPlay').textContent = on ? '⏸ ఆపు' : '▶ చదువు';
}
function readLang() {
  const b = S.byId[R.id] || {};
  if (b.lang === 'te' || R.mode === 'en') return 'en';
  if (R.mode === 'ai') return 'ai';
  if (R.mode === 'both' && pairOf() === 'ai') return R.ai ? 'ai' : 'en'; // (read what is on the screen)
  return 'te';
}
$('rBody').addEventListener('scroll', () => { R.scrolledAt = Date.now(); });
$('text').addEventListener('click', (e) => {
  const d = e.target.closest('.p');
  if (!d) return;
  const i = Number(d.dataset.i);
  const bub = $('bubble');
  bub.textContent = '';
  bub.appendChild(btn('▶ ఇక్కడి నుంచి చదువు', 'gold', () => { bub.hidden = true; post({ t: 'read', id: R.id, lang: readLang(), unit: R.unit, part: i }); }));
  let word = '';
  if (document.caretRangeFromPoint) {
    const r = document.caretRangeFromPoint(e.clientX, e.clientY);
    if (r && r.startContainer && r.startContainer.nodeType === 3) word = K.wordAt(r.startContainer.textContent, r.startOffset);
  }
  if (word && word.length > 1 && !/[ఀ-౿]/.test(word)) bub.appendChild(btn('🔤 "' + word + '" అర్థం', '', () => { bub.textContent = '…'; post({ t: 'word', w: word }); }));
  const mk = markOf(i);
  bub.appendChild(btn(mk ? '🖍️ గుర్తు తీసేయి' : '🖍️ గుర్తు పెట్టు', '', () => { bub.hidden = true; markSet(i, !mk, undefined); }));
  bub.appendChild(btn(mk && mk.n ? '📝 నోట్ మార్చు' : '📝 నోట్ రాయి', '', () => { bub.hidden = true; noteSheet(i); }));
  bub.hidden = false;
  bub.style.left = Math.max(8, Math.min(window.innerWidth - 240, e.clientX - 100)) + 'px';
  bub.style.top = Math.max(60, e.clientY - 70) + 'px';
});
document.addEventListener('pointerdown', (e) => { if (!e.target.closest('#bubble') && !e.target.closest('.p')) $('bubble').hidden = true; });
$('rBack').onclick = closeReader;
function closeReader() { $('reader').hidden = true; $('bubble').hidden = true; nowBar(); }
$('chap').onchange = () => { R.unit = Number($('chap').value); R.te = null; R.ai = null; post({ t: 'chapter', id: R.id, unit: R.unit }); };
$('rPrev').onclick = () => { if (R.unit > 0) { R.unit--; post({ t: 'chapter', id: R.id, unit: R.unit }); } };
$('rNext').onclick = () => { if (R.unit < R.n - 1) { R.unit++; post({ t: 'chapter', id: R.id, unit: R.unit }); } };
$('rSmall').onclick = () => { S.prefs.font = Math.max(13, (S.prefs.font || 19) - 2); post({ t: 'prefs', font: S.prefs.font }); applyReaderLook(); };
$('rBig').onclick = () => { S.prefs.font = Math.min(34, (S.prefs.font || 19) + 2); post({ t: 'prefs', font: S.prefs.font }); applyReaderLook(); };
$('rNight').onclick = () => { S.prefs.night = !S.prefs.night; post({ t: 'prefs', night: S.prefs.night }); applyReaderLook(); };
$('rPlay').onclick = () => {
  const n = S.now;
  if (n && n.on && n.id === R.id) { post({ t: 'pause' }); return; }
  const at = n && n.id === R.id && n.unit === R.unit ? n.part : (S.pos[R.id] && S.pos[R.id][0] === R.unit ? S.pos[R.id][1] : 0);
  post({ t: 'read', id: R.id, lang: readLang(), unit: R.unit, part: at || 0 });
};
const SLEEPS = [15, 30, 45, 60, 0];
let sleepI = -1;
function sleepNext() { sleepI = (sleepI + 1) % SLEEPS.length; post({ t: 'sleep', min: SLEEPS[sleepI] }); }
$('rSleep').onclick = sleepNext;
const RATES = [0.8, 1, 1.2, 1.5];
$('rRate').onclick = () => {
  const cur = S.prefs.rate > 0 ? S.prefs.rate : 1;
  const i = RATES.indexOf(cur);
  S.prefs.rate = RATES[(i + 1) % RATES.length];
  post({ t: 'rate', r: S.prefs.rate });
  applyReaderLook();
};

// ================================================================ the "now reading" bar
function nowBar() {
  const n = S.now, bar = $('nowbar');
  const b = n && S.byId[n.id];
  if (!n || !n.on || !b || !$('reader').hidden) { bar.hidden = true; return; }
  bar.hidden = false;
  $('nowT').textContent = b.t + ' · అధ్యాయం ' + (n.unit + 1) + (n.lang === 'te' ? ' · తెలుగు' : n.lang === 'ai' ? ' · ✨ తెలుగు' : '');
}
$('nowP').onclick = () => post({ t: 'pause' });
$('nowR').onclick = () => { const n = S.now; if (n) openReader(n.id, n.unit); };
$('nowS').onclick = sleepNext;

// ================================================================ the top buttons
$('bClose').onclick = () => post({ t: 'close' });
$('bSearch').onclick = () => openSearch();
$('bTr').onclick = () => openTrs();
$('tBack').onclick = () => { $('trs').hidden = true; };
$('bAdd').onclick = openAdd;
$('bView').onclick = () => { closeBook(true); S.hall = false; S.view = S.view === 'era' ? 'sec' : 'era'; post({ t: 'prefs', view: S.view }); rebuild(); goRack(S.view === 'era' ? 'universe' : 'fic', true); };
$('sBack').onclick = () => { $('search').hidden = true; };
$('sGo').onclick = runSearch;
$('q').addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });

/** Fly to a book on its rack and open it. */
function show(id) {
  if (S.hall) leaveHall();
  const all = bookMeshes.filter((x) => x.userData.id === id);
  const m = all.find((x) => Math.abs(x.position.x - (caseX.my || 0)) > C.w) || all[0]; // (its own rack before "నావి")
  if (!m) { const b = S.byId[id]; if (b) { S.sel = null; showCard(b); } return; }
  closeBook(true);
  let best = null, bd = 1e9;
  rackList.forEach((k) => { const d = Math.abs(caseX[k.k] - m.position.x); if (d < bd) { bd = d; best = k.k; } });
  if (best) S.rack = best;
  const tx = m.position.x, ty = m.position.y + 0.05;
  anim(550, (e, f) => { cam.x = f.x + (tx - f.x) * e; cam.y = f.y + (ty - f.y) * e; }, { x: cam.x, y: cam.y }, () => openBook(m));
  chipsOn(S.rack, false);
}

function back() {
  if (!$('bubble').hidden) { $('bubble').hidden = true; return; }
  if (!$('quote').hidden) { $('quote').hidden = true; return; }
  if (!$('tool').hidden) { closeTool(); return; }
  if (!$('mapp').hidden) { $('mapp').hidden = true; return; }
  if (!$('me').hidden) { $('me').hidden = true; return; }
  if (!$('trs').hidden) { $('trs').hidden = true; return; }
  if (!$('add').hidden) { closeAdd(); return; }
  if (!$('reader').hidden) { closeReader(); return; }
  if (!$('search').hidden) { $('search').hidden = true; return; }
  if (S.sel) { closeBook(); return; }
  if (S.hall) { leaveHall(); return; }
  post({ t: 'close' });
}

// ================================================================ 🕰️ the time hall: walk from the universe's start to today
const HALL = { seg: 4.4, w: 1.6, start: 5.4 };
const hallCam = { z: 5.4, yaw: 0, back: 0 };
const HALL_TIME = {
  universe: 'సుమారు 1380 కోట్ల సంవత్సరాల క్రితం విశ్వం · 460 కోట్ల సం. క్రితం సూర్యుడు',
  life: 'సుమారు 450 కోట్ల సం. క్రితం భూమి · 350 కోట్ల సం. క్రితం మొదటి జీవం',
  ancient: 'సుమారు 3 లక్షల సం. క్రితం మనిషి · 5000 సం. క్రితం రాత · క్రీ.శ. 500 దాకా',
  medieval: 'క్రీ.శ. 500 – 1500 · రాజ్యాలు, కోటలు, కావ్యాలు',
  modern: '1500 – 1900 · అచ్చు యంత్రం, సైన్స్, యంత్రాలు',
  now: '1900 – ఈ రోజు · విమానం, అంతరిక్షం, ఫోన్',
};
/** The hall's times ("నావి" stays on the racks). */
function hallRacks() { return rackList.filter((r) => r.k !== 'my'); }
function hallSide(i) { return i % 2 === 0 ? -1 : 1; }
function hallCaseZ(i) { return -i * HALL.seg - 1.6; }
function hallMat(col, glow) { return new THREE.MeshStandardMaterial({ color: col, emissive: glow || '#000000', emissiveIntensity: glow ? 0.9 : 0, roughness: 0.55, metalness: 0.15 }); }
function signTex(text, sub) {
  return canvasTex(1024, 300, (g, w, h) => {
    const gr = g.createLinearGradient(0, 0, 0, h);
    gr.addColorStop(0, '#2c1a0c'); gr.addColorStop(1, '#170d05');
    g.fillStyle = gr; g.fillRect(0, 0, w, h);
    g.strokeStyle = '#d8b25a'; g.lineWidth = 8; g.strokeRect(10, 10, w - 20, h - 20);
    g.fillStyle = '#ffe2a0'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const s = fitFont(g, text, w - 90, 84, 30, '700', SERIF);
    g.font = '700 ' + s + 'px ' + SERIF; g.fillText(text, w / 2, h * 0.38);
    if (sub) { const s2 = fitFont(g, sub, w - 70, 34, 16, '500', SERIF); g.font = '500 ' + s2 + 'px ' + SERIF; g.fillStyle = '#e8cf95'; g.fillText(sub, w / 2, h * 0.74); }
  });
}
/** Each time's own little sign of itself (simple shapes of our own): stars, the earth, a pyramid, a tower, a gear, a rocket. */
function emblem(k, parent) {
  const g = new THREE.Group();
  parent.add(g);
  const gold = hallMat('#c9a24e', '#3a2a08');
  box(0.5, 0.9, 0.5, new THREE.MeshStandardMaterial({ map: woodTex('#5a3a20', '#24130a', 23), roughness: 0.6 }), 0, 0.45, 0, g);
  const top = new THREE.Group(); top.position.y = 1.2; g.add(top);
  if (k === 'universe') {
    top.add(new THREE.Mesh(new THREE.SphereGeometry(0.1, 20, 14), hallMat('#fff3c4', '#ffd36a')));
    const r = rnd(77), dots = new THREE.Group();
    for (let i = 0; i < 60; i++) {
      const d = new THREE.Mesh(new THREE.SphereGeometry(0.012 + r() * 0.012, 6, 4), hallMat('#ffffff', r() < 0.5 ? '#9cc8ff' : '#ffd8a0'));
      const a = r() * Math.PI * 2, b = (r() - 0.5) * Math.PI, rr = 0.15 + r() * 0.2;
      d.position.set(Math.cos(a) * Math.cos(b) * rr, Math.sin(b) * rr, Math.sin(a) * Math.cos(b) * rr);
      dots.add(d);
    }
    top.add(dots);
  } else if (k === 'life') {
    top.add(new THREE.Mesh(new THREE.SphereGeometry(0.2, 28, 20), hallMat('#2f7ab0', '#0b2a44')));
    const land = hallMat('#3f8a3a', '#0d2a0c');
    [[0.08, 0.1, 0.13], [-0.1, -0.05, 0.14], [0.02, -0.14, 0.12]].forEach(([x, y, z]) => { const m = new THREE.Mesh(new THREE.SphereGeometry(0.09, 12, 8), land); m.scale.set(1, 0.6, 0.45); m.position.set(x, y, z); top.add(m); });
  } else if (k === 'ancient') {
    const m = new THREE.Mesh(new THREE.ConeGeometry(0.26, 0.32, 4), hallMat('#d8b878', '#2a1e08'));
    m.rotation.y = Math.PI / 4; top.add(m);
  } else if (k === 'medieval') {
    const st = hallMat('#9a9488', '#16140f');
    const t = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.12, 0.34, 16), st); top.add(t);
    const c = new THREE.Mesh(new THREE.ConeGeometry(0.14, 0.18, 16), hallMat('#7a2a1c', '#200a05')); c.position.y = 0.26; top.add(c);
  } else if (k === 'modern') {
    const gear = new THREE.Mesh(new THREE.TorusGeometry(0.15, 0.04, 8, 24), gold); top.add(gear);
    for (let i = 0; i < 10; i++) { const tth = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.05, 0.06), gold); const a = (i / 10) * Math.PI * 2; tth.position.set(Math.cos(a) * 0.2, Math.sin(a) * 0.2, 0); tth.rotation.z = a; top.add(tth); }
  } else {
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.07, 0.34, 16), hallMat('#e8e6e0', '#1a1a18')); top.add(body);
    const nose = new THREE.Mesh(new THREE.ConeGeometry(0.07, 0.14, 16), hallMat('#c0392b', '#2a0a06')); nose.position.y = 0.24; top.add(nose);
    const fire = new THREE.Mesh(new THREE.ConeGeometry(0.05, 0.14, 12), hallMat('#ffb347', '#ff8a2a')); fire.rotation.x = Math.PI; fire.position.y = -0.24; top.add(fire);
  }
  return g;
}
function buildHall() {
  const hr = hallRacks();
  const n = hr.length;
  const len = n * HALL.seg + 8, mid = HALL.start + 1.2 - len / 2;
  const floorM = new THREE.MeshStandardMaterial({ map: floorTex, roughness: 0.75 });
  floorM.__keep = true;
  const fl = new THREE.Mesh(new THREE.PlaneGeometry(HALL.w * 2 + 0.6, len), floorM);
  fl.rotation.x = -Math.PI / 2; fl.position.set(0, 0, mid); world.add(fl);
  const wm = new THREE.MeshStandardMaterial({ map: wallTex, roughness: 0.95 });
  wm.__keep = true;
  [-1, 1].forEach((sd) => { const w = new THREE.Mesh(new THREE.PlaneGeometry(len, 3.4), wm); w.position.set(sd * (HALL.w + 0.3), 1.7, mid); w.rotation.y = -sd * Math.PI / 2; world.add(w); });
  const ceil = new THREE.Mesh(new THREE.PlaneGeometry(HALL.w * 2 + 0.6, len), new THREE.MeshStandardMaterial({ color: '#140c06', roughness: 1 }));
  ceil.rotation.x = Math.PI / 2; ceil.position.set(0, 3.4, mid); world.add(ceil);
  // the line of time along the floor
  box(0.05, 0.006, len - 4, hallMat('#d8a84a', '#a8781e'), 0, 0.004, 0.5 - len / 2 + 2);
  hr.forEach((rack, i) => {
    const sd = hallSide(i), z = hallCaseZ(i);
    const g = bookcase(i, rack);
    g.position.set(sd * HALL.w, 0, z);
    g.rotation.y = -sd * Math.PI / 2;
    caseX[rack.k] = z;
    const list = K.onRack(S.books, 'era', rack.k);
    K.layout(list).forEach((p) => placeBook(p, S.byId[p.id], 0, g));
    // the arch over the way in, its sign; the time's emblem across from the bookcase
    const az = z + 1.75;
    const pil = hallMat('#4a2e16', '#120a04');
    [-1, 1].forEach((x) => box(0.16, 2.75, 0.16, pil, x * (HALL.w + 0.05), 1.375, az));
    box(HALL.w * 2 + 0.3, 0.14, 0.18, pil, 0, 2.8, az);
    const sign = new THREE.Mesh(new THREE.PlaneGeometry(1.5, 0.44), new THREE.MeshStandardMaterial({ map: signTex(rack.e + ' ' + rack.n, HALL_TIME[rack.k] || rack.s), emissive: '#ffffff', emissiveIntensity: 0.25, roughness: 0.6 }));
    sign.material.emissiveMap = sign.material.map;
    sign.position.set(0, 2.3, az + 0.1);
    world.add(sign);
    const em = emblem(rack.k, world);
    em.position.set(-sd * (HALL.w - 0.35), 0, az - 0.7);
    const disc = new THREE.Mesh(new THREE.CircleGeometry(0.16, 24), hallMat('#ffd36a', '#c99a2e'));
    disc.rotation.x = -Math.PI / 2; disc.position.set(0, 0.008, az); world.add(disc);
  });
  // the far end: today
  const endZ = hallCaseZ(n - 1) - 2.2;
  const end = new THREE.Mesh(new THREE.PlaneGeometry(HALL.w * 2 + 0.6, 3.4), wm);
  end.position.set(0, 1.7, endZ); world.add(end);
  const now = new THREE.Mesh(new THREE.PlaneGeometry(1.5, 0.44), new THREE.MeshStandardMaterial({ map: signTex('📍 ఈ రోజు', 'మీరు ఇక్కడ ఉన్నారు · వెనక్కి నడిస్తే కాలంలో వెనక్కి'), roughness: 0.6 }));
  now.position.set(0, 1.9, endZ + 0.02); world.add(now);
  S.hallEnd = endZ;
}
function enterHall() {
  closeBook(true);
  S.hall = true;
  hallCam.z = HALL.start; hallCam.yaw = 0; hallCam.back = 0;
  rebuild();
  S.rack = hallRacks()[0].k;
  chipsOn(S.rack, true);
  toast('🚶 వేలితో పైకి జరిపితే ముందుకు (కాలంలో ముందుకు), పక్కకి జరిపితే తల తిప్పుతారు. పుస్తకం తాకితే తెరుచుకుంటుంది.', 6500);
}
function leaveHall() {
  closeBook(true);
  S.hall = false;
  rebuild();
  goRack(S.rack && caseX[S.rack] !== undefined ? S.rack : 'universe', true);
}
function hallMove(dx, dy) {
  hallCam.yaw = Math.max(-1.5, Math.min(1.5, hallCam.yaw + dx * 0.0055));
  hallCam.z = Math.max((S.hallEnd || -30) + 1.2, Math.min(HALL.start + 0.4, hallCam.z + dy * 0.012));
  hallNear();
  dirty = true;
}
function hallNear() {
  let best = null, bd = 1e9;
  hallRacks().forEach((r) => { const d = Math.abs(caseX[r.k] - hallCam.z); if (d < bd) { bd = d; best = r.k; } });
  if (best && best !== S.rack) { S.rack = best; chipsOn(best, true); }
}
/** Walk to a time's bookcase and turn to it. */
function hallGo(k) {
  const i = hallRacks().findIndex((r) => r.k === k);
  if (i < 0) return;
  S.rack = k; chipsOn(k, true);
  const tz = hallCaseZ(i), ty = -hallSide(i) * 1.35;
  anim(900, (e, f) => { hallCam.z = f.z + (tz - f.z) * e; hallCam.yaw = f.y + (ty - f.y) * e; hallCam.back = f.b + (0.55 - f.b) * e; }, { z: hallCam.z, y: hallCam.yaw, b: hallCam.back });
}
$('hPrev').onclick = () => { const hr = hallRacks(), i = hr.findIndex((r) => r.k === S.rack); hallGo(hr[Math.max(0, i - 1)].k); };
$('hNext').onclick = () => { const hr = hallRacks(), i = hr.findIndex((r) => r.k === S.rack); hallGo(hr[Math.min(hr.length - 1, i + 1)].k); };
$('hOut').onclick = () => leaveHall();

// ================================================================ 🖍️ his marks
function markOf(i) { return (R.marks || []).find((x) => x.u === R.unit && x.k === i) || null; }
function markSet(i, on, note) {
  post({ t: 'mark', id: R.id, unit: R.unit, part: i, text: R.parts[i] || '', te: teOf(i) || '', on, note });
}
function noteSheet(i) {
  T.kind = 'note'; T.id = R.id; T.unit = R.unit;
  const q = toolSheet('📝 మీ నోట్');
  const mk = markOf(i);
  q.appendChild(el('p', 'muted', (R.parts[i] || '').slice(0, 220) + ((R.parts[i] || '').length > 220 ? '…' : '')));
  const ta = el('textarea', 'f');
  ta.placeholder = 'ఈ లైన్ గురించి మీ మాట (మీ ఫోన్‌లోనే ఉంటుంది)';
  ta.value = mk ? (mk.n || '') : '';
  q.appendChild(ta);
  const row = el('div', 'row');
  row.appendChild(btn('💾 దాచు', 'gold', () => { markSet(i, true, ta.value.trim()); closeTool(); }));
  if (mk) row.appendChild(btn('🗑️ గుర్తు, నోట్ తీసేయి', '', () => { markSet(i, false, ''); closeTool(); }));
  row.appendChild(btn('వద్దు', '', closeTool));
  q.appendChild(row);
  setTimeout(() => ta.focus(), 50);
}
function marksList(box, id, list, opener) {
  let lastU = -1;
  (list || []).forEach((x) => {
    if (x.u !== lastU) { box.appendChild(el('div', 'sub', '📖 అధ్యాయం ' + (x.u + 1))); lastU = x.u; }
    const d = el('div', 'mk');
    d.appendChild(el('div', 'mx', x.x.length > 260 ? x.x.slice(0, 260) + '…' : x.x));
    if (x.te) d.appendChild(el('div', 'te', x.te.length > 260 ? x.te.slice(0, 260) + '…' : x.te));
    if (x.n) d.appendChild(el('div', 'n', '📝 ' + x.n));
    d.onclick = () => { if (opener) opener(); $('me').hidden = true; S.jumpTo = { id, unit: x.u, part: x.k }; openReader(id, x.u); };
    box.appendChild(d);
  });
}
function openMarksSheet(id) {
  const bid = id || R.id;
  T.kind = 'marks'; T.id = bid; T.unit = R.unit;
  const q = toolSheet('🖍️ నా గుర్తులు, నోట్స్');
  const list = bid === R.id ? R.marks : ((S.me.marks || []).find((x) => x.id === bid) || {}).m;
  if (!list || !list.length) { q.appendChild(el('p', '', 'ఈ పుస్తకంలో ఇంకా గుర్తులు లేవు. చదువుతున్నప్పుడు ఒక లైన్ తాకి "🖍️ గుర్తు పెట్టు" నొక్కండి; "📝 నోట్ రాయి" తో మీ మాట రాసుకోవచ్చు.')); return; }
  const row = el('div', 'row');
  row.appendChild(btn('📄 PDF చేయి (₹0)', 'gold', () => { const p = $('pdfline'); if (p) p.textContent = '📄 తయారవుతోంది…'; post({ t: 'marks_pdf', id: bid }); }));
  q.appendChild(row);
  const pl = el('div', 'muted', ''); pl.id = 'pdfline'; q.appendChild(pl);
  const box = el('div', ''); q.appendChild(box);
  marksList(box, bid, list, closeTool);
}

// ================================================================ 🧰 the reader's tools: 📝 ❓ 💬 🗺️ 🎧 🖍️
const T = { kind: '', id: '', unit: 0, q: '', scope: 'ch' };
const TOOL_COST = {
  sum: (m) => '📝 అధ్యాయం ' + (m.unit + 1) + ' సారాంశం',
  quiz: (m) => '❓ అధ్యాయం ' + (m.unit + 1) + ' క్విజ్',
  ask_ch: (m) => '💬 అధ్యాయం ' + (m.unit + 1) + ' లో మీ ప్రశ్న',
  ask_book: () => '💬 మొత్తం పుస్తకంలో మీ ప్రశ్న',
  map: () => '🗺️ పాత్రల మ్యాప్ (మిగిలిన అధ్యాయాలు)',
};
/** After 🤖 a new model: the same tool's cost again, now with that model (no AI Telugu sheet in between). */
function afterModel() {
  const a = S.afterModel;
  S.afterModel = null;
  $('quote').hidden = true;
  T.id = a.id; T.unit = a.unit;
  if (a.kind === 'ask_ch' || a.kind === 'ask_book') {
    T.scope = a.kind === 'ask_book' ? 'book' : 'ch'; T.q = a.q || '';
    openAsk();
    const go = $('askGo'); if (go) go.textContent = '…';
    post({ t: 'tquote', kind: a.kind, id: a.id, unit: a.unit, q: T.q });
    return;
  }
  T.kind = a.kind;
  toolSheet(TOOL_NAMES[a.kind]).appendChild(el('p', 'muted', '…'));
  post({ t: 'tquote', kind: a.kind, id: a.id, unit: a.unit });
}
const TOOL_NAMES = { sum: '📝 అధ్యాయం సారాంశం (తెలుగులో)', quiz: '❓ అధ్యాయం క్విజ్', map: '🗺️ పాత్రల మ్యాప్', ask: '💬 పుస్తకాన్ని అడుగు' };
function toolsBar() {
  const t = $('tools');
  t.textContent = '';
  const n = (R.marks || []).length;
  [['📝 సారాంశం', () => toolAsk('sum')], ['❓ క్విజ్', () => toolAsk('quiz')], ['💬 పుస్తకాన్ని అడుగు', openAsk], ['🗺️ పాత్రలు', () => toolAsk('map')],
    ['🎧 ఆడియో ఫైల్', openAudio], ['🖍️ గుర్తులు' + (n ? ' (' + n + ')' : ''), () => openMarksSheet()]].forEach(([name, f]) => t.appendChild(btn(name, 'chip', f)));
}
function toolSheet(title) {
  const q = $('tool');
  q.textContent = '';
  q.hidden = false;
  q.appendChild(btn('✕', 'x', closeTool));
  q.appendChild(el('h2', '', title));
  const b = S.byId[T.id] || {};
  if (T.id) q.appendChild(el('div', 'sub', (b.t || '') + (T.kind === 'map' || (T.kind === 'ask' && T.scope === 'book') ? '' : ' · అధ్యాయం ' + (T.unit + 1))));
  return q;
}
function closeTool() { $('tool').hidden = true; T.kind = ''; }
function toolAsk(kind) {
  T.kind = kind; T.id = R.id; T.unit = R.unit; T.q = '';
  const q = toolSheet(TOOL_NAMES[kind]);
  q.appendChild(el('p', 'muted', '…'));
  post({ t: 'tquote', kind, id: R.id, unit: R.unit });
}
function modelRow(kind, q) {
  const r = el('div', 'row');
  r.appendChild(btn('🤖 మోడల్ మార్చు · ప్రతి మోడల్ ధర చూడు', 'wide', () => {
    S.afterModel = { kind, id: T.id, unit: T.unit, q: q || '' };
    closeTool();
    const s = $('quote'); s.textContent = ''; s.hidden = false;
    s.appendChild(btn('✕', 'x', () => { s.hidden = true; S.afterModel = null; }));
    s.appendChild(el('h2', '', '🤖 లైబ్రరీ AI మోడల్'));
    s.appendChild(el('p', 'muted', 'మీ keys తో వాడగలిగే మోడల్స్ లిస్ట్ తెస్తున్నాను…'));
    post({ t: 'models', id: T.id, unit: T.unit, kind, q: q || '' });
  }));
  return r;
}
function gotTquote(m) {
  if (m.kind === 'ask_ch' || m.kind === 'ask_book') { askQuote(m); return; }
  if (T.kind !== m.kind || T.id !== m.id) return;
  if (m.have) { post({ t: 'tkept', kind: m.kind, id: m.id, unit: m.unit }); return; }
  const q = toolSheet(TOOL_NAMES[m.kind]);
  if (m.why) { q.appendChild(el('p', '', m.why)); q.appendChild(modelRow(m.kind)); return; }
  const what = {
    sum: 'ఈ అధ్యాయాన్ని మీ లైబ్రరీ AI సరళమైన తెలుగులో చిన్నగా చెబుతుంది: 4–6 వాక్యాల కథ, తర్వాత ముఖ్యమైన విషయాలు.',
    quiz: 'ఈ అధ్యాయం మీద 5 ప్రశ్నలు, ఒక్కోదానికి 4 జవాబులు; మీరు నొక్కాక సరైనది ఏదో, ఎందుకో చెబుతుంది.',
    map: 'పుస్తకంలోని పాత్రలు, వాళ్ళ మధ్య సంబంధాలు, ప్రతి అధ్యాయంలో ఏమి జరిగిందో — అధ్యాయం తర్వాత అధ్యాయం చేసి ఒక మ్యాప్‌గా చూపిస్తుంది.' + (m.left ? ' ఇంకా ' + m.left + ' అధ్యాయాలు చేయాలి; పైన పట్టీలో ఎంతవరకు వచ్చిందో కనిపిస్తుంది, ⏹ తో ఆపొచ్చు.' : ''),
  }[m.kind];
  q.appendChild(el('p', '', what + ' ఒకసారి చేసినది దాచుకుంటాను: మళ్ళీ చూస్తే ₹0.'));
  q.appendChild(el('p', 'muted', '🤖 ' + m.model + ' · ఈ నెల ₹' + m.used + ' వాడింది / హద్దు ₹' + m.limit + ' · అంకెలు సుమారు' + (m.known === false ? ' · ఈ మోడల్ ధర అంచనా' : '')));
  const row = el('div', 'row');
  row.appendChild(btn('✨ చేయించు · సుమారు ' + K.money(m.rupees), 'gold wide', () => toolRun(m.kind, '')));
  const b = S.byId[m.id] || {};
  if (m.kind === 'map' && m.left < (b.n || 0)) row.appendChild(btn('📖 ఇప్పటిదాకా అయినవి చూడు (₹0)', 'wide', () => post({ t: 'tkept', kind: 'map', id: m.id, unit: 0 })));
  row.appendChild(btn('వద్దు', 'wide', closeTool));
  q.appendChild(row);
  q.appendChild(modelRow(m.kind));
}
function toolRun(kind, q) {
  const s = toolSheet(TOOL_NAMES[kind]);
  if (kind === 'map') {
    post({ t: 'tgo', kind, id: T.id, unit: T.unit, q });
    closeTool();
    toast('🗺️ పాత్రల మ్యాప్ మొదలుపెట్టాను. పైన పట్టీలో ఎంతవరకు వచ్చిందో కనిపిస్తుంది; ⏹ తో ఆపొచ్చు. అయ్యాక 🗺️ పాత్రలు నొక్కండి.', 7000);
    return;
  }
  const box = el('div', 'tbox live', kind === 'quiz' ? '❓ ప్రశ్నలు తయారవుతున్నాయి… (కొన్ని సెకన్లు)' : '✨ రాస్తోంది…');
  box.id = 'tlive';
  s.appendChild(box);
  post({ t: 'tgo', kind, id: T.id, unit: T.unit, q });
}
function showSum(res, kept) {
  const q = toolSheet(TOOL_NAMES.sum);
  q.appendChild(el('div', 'tbox', res.text || ''));
  q.appendChild(el('p', 'muted', kept ? '💾 ఇంతకుముందు చేసినది · ఇప్పుడు ₹0' : '🤖 ' + (res.model || '') + ' · ఖర్చు ' + K.money(res.rs || 0) + ' · దాచాను: మళ్ళీ చూస్తే ₹0'));
}
function showQuiz(res, kept) {
  const qs = res.q || [];
  let at = 0, right = 0;
  const draw = () => {
    const s = toolSheet(TOOL_NAMES.quiz);
    s.classList.add('quiz');
    if (at >= qs.length) {
      s.appendChild(el('div', 'tbox', '🏁 ' + qs.length + ' లో ' + right + ' సరైనవి' + (right === qs.length ? ' · అద్భుతం Sir!' : right >= qs.length / 2 ? ' · బాగుంది!' : ' · ఇంకోసారి చదివి చూడండి.')));
      const r = el('div', 'row');
      r.appendChild(btn('🔁 మళ్ళీ (₹0)', 'gold', () => { at = 0; right = 0; draw(); }));
      r.appendChild(btn('సరే', '', closeTool));
      s.appendChild(r);
      s.appendChild(el('p', 'muted', kept ? '💾 దాచిన క్విజ్ · ₹0' : '🤖 ' + (res.model || '') + ' · ఖర్చు ' + K.money(res.rs || 0)));
      return;
    }
    const x = qs[at];
    s.appendChild(el('div', 'sub', 'ప్రశ్న ' + (at + 1) + ' / ' + qs.length));
    s.appendChild(el('div', 'tbox', x.q));
    let done = false;
    const why = el('p', '', '');
    x.o.forEach((o, i) => {
      const b = btn(o, 'opt', () => {
        if (done) return;
        done = true;
        if (i === x.a) right++;
        [...s.querySelectorAll('.opt')].forEach((e, k) => { if (k === x.a) e.classList.add('right'); else if (k === i) e.classList.add('wrong'); });
        why.textContent = (i === x.a ? '✅ సరైనది! ' : '❌ సరైనది: ' + x.o[x.a] + '. ') + (x.why || '');
        nx.hidden = false;
      });
      s.appendChild(b);
    });
    s.appendChild(why);
    const nx = btn(at + 1 < qs.length ? 'తర్వాతి ప్రశ్న →' : 'ఫలితం చూడు', 'gold', () => { at++; draw(); });
    nx.hidden = true;
    s.appendChild(nx);
  };
  draw();
}

// ---- 💬 ask the book
function openAsk() {
  T.kind = 'ask'; T.id = R.id; T.unit = R.unit; T.shownQ = '';
  const q = toolSheet(TOOL_NAMES.ask);
  const sc = el('div', 'row');
  const c1 = btn('📖 ఈ అధ్యాయంలో', 'chip' + (T.scope !== 'book' ? ' on' : ''), () => { T.scope = 'ch'; c1.className = 'chip on'; c2.className = 'chip'; });
  const c2 = btn('📚 మొత్తం పుస్తకంలో', 'chip' + (T.scope === 'book' ? ' on' : ''), () => { T.scope = 'book'; c2.className = 'chip on'; c1.className = 'chip'; });
  sc.appendChild(c1); sc.appendChild(c2);
  q.appendChild(sc);
  const ta = el('textarea', 'f');
  ta.id = 'askq';
  ta.placeholder = 'ఉదా: Holmes ఈ కేసు ఎలా కనిపెట్టాడు? (తెలుగు లేదా English)';
  ta.value = T.q || '';
  q.appendChild(ta);
  q.appendChild(el('p', 'muted', 'జవాబు ఆ పుస్తకంలో ఉన్నదాని నుంచే, తెలుగులో, ఏ అధ్యాయమో చెప్పి. ఖర్చు ముందే చూపిస్తాను.'));
  const go = el('div', 'row'); go.id = 'askGo';
  go.appendChild(btn('💬 ఖర్చు చూసి అడుగు', 'gold wide', () => {
    T.q = ta.value.trim();
    if (!T.q) { ta.focus(); return; }
    go.textContent = '…';
    post({ t: 'tquote', kind: T.scope === 'book' ? 'ask_book' : 'ask_ch', id: T.id, unit: T.unit, q: T.q });
  }));
  q.appendChild(go);
  const out = el('div', 'tbox', ''); out.id = 'askOut'; q.appendChild(out);
  const hist = el('div', ''); hist.id = 'askHist'; q.appendChild(hist);
  post({ t: 'tkept', kind: 'ask', id: T.id, unit: T.unit });
}
function askQuote(m) {
  if (T.kind !== 'ask' || T.id !== m.id) return;
  const go = $('askGo');
  if (!go) return;
  go.textContent = '';
  if (m.why) { go.appendChild(el('p', '', m.why)); go.appendChild(modelRow(m.kind, T.q)); return; }
  go.appendChild(el('p', 'muted', '🤖 ' + m.model + ' · ' + (m.kind === 'ask_book' ? 'మొత్తం పుస్తకంలో వెతికి' : 'ఈ అధ్యాయం చదివి') + ' · ఈ నెల ₹' + m.used + ' / ₹' + m.limit));
  go.appendChild(btn('✅ అడుగు · సుమారు ' + K.money(m.rupees), 'gold', () => {
    go.textContent = '';
    $('askOut').textContent = '✨ వెతుకుతోంది / రాస్తోంది…';
    post({ t: 'tgo', kind: 'ask', id: T.id, unit: T.unit, q: (T.scope === 'book' ? 'book:' : 'ch:') + T.q });
  }));
  go.appendChild(btn('వద్దు', '', () => { go.textContent = ''; openAsk(); }));
  go.appendChild(modelRow(m.kind, T.q));
}
function askHistory(list) {
  const h = $('askHist');
  if (!h) return;
  h.textContent = '';
  if (!list || !list.length) return;
  h.appendChild(el('div', 'sec2', '💬 ఇంతకుముందు అడిగినవి (మళ్ళీ చూడటానికి ₹0)'));
  list.forEach((x) => {
    const d = el('div', 'qa');
    d.appendChild(el('div', 'q', '❓ ' + x.q + (x.scope === 'book' ? ' · 📚' : ' · 📖 అధ్యాయం ' + (x.unit + 1))));
    d.appendChild(el('div', 'a', x.a));
    h.appendChild(d);
  });
}

// ---- 🗺️ the people map
function openMap(res) {
  closeTool();
  const b = S.byId[T.id] || {};
  $('mapp').hidden = false;
  $('pTitle').textContent = '🗺️ ' + (b.t || 'పాత్రలు');
  const body = $('pBody');
  body.textContent = '';
  const cs = (res && res.c) || [], rs = (res && res.r) || [], es = (res && res.e) || [];
  if (!cs.length) { body.appendChild(el('p', '', 'ఇంకా పాత్రల మ్యాప్ లేదు. చదివే స్క్రీన్‌లో 🗺️ పాత్రలు నొక్కి చేయించండి.')); return; }
  body.appendChild(el('p', 'muted', cs.length + ' పాత్రలు · ' + rs.length + ' సంబంధాలు' + (res.n ? ' · ' + res.done + ' / ' + res.n + ' అధ్యాయాల నుంచి' : '') + ' · ఒక పేరు తాకితే ఆ వ్యక్తి వివరాలు'));
  const cv2 = el('canvas', ''); cv2.id = 'mapcv'; body.appendChild(cv2);
  const info = el('div', 'tbox', ''); body.appendChild(info);
  const W = cv2.clientWidth || 380, H = cv2.clientHeight || 360, dpr = Math.min(2, window.devicePixelRatio || 1);
  cv2.width = W * dpr; cv2.height = H * dpr;
  const pos = K.mapLayout(cs, rs, W, H);
  let sel = null;
  const draw = () => {
    const g = cv2.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = '#0f0a06'; g.fillRect(0, 0, W, H);
    g.font = '11px system-ui, sans-serif'; g.textAlign = 'center';
    rs.forEach((l) => {
      const a = pos[l[0]], z = pos[l[1]];
      if (!a || !z) return;
      const on = sel && (sel === l[0] || sel === l[1]);
      g.strokeStyle = on ? '#ffd36a' : 'rgba(216,178,90,0.35)'; g.lineWidth = on ? 2 : 1;
      g.beginPath(); g.moveTo(a.x, a.y); g.lineTo(z.x, z.y); g.stroke();
      if (on) { g.fillStyle = '#ffe2a0'; g.fillText(l[2], (a.x + z.x) / 2, (a.y + z.y) / 2 - 3); }
    });
    cs.forEach((c) => {
      const p = pos[c.n];
      if (!p) return;
      const r = 6 + Math.min(10, (p.d || 0) * 1.5);
      g.fillStyle = sel === c.n ? '#ffd36a' : '#c9a24e';
      g.beginPath(); g.arc(p.x, p.y, r, 0, Math.PI * 2); g.fill();
      g.fillStyle = '#f3e6d0'; g.font = (sel === c.n ? '600 13px' : '12px') + ' "Noto Sans Telugu", system-ui, sans-serif';
      g.fillText(c.te || c.n, p.x, p.y + r + 13);
    });
  };
  const tell = (c) => {
    info.textContent = '';
    if (!c) return;
    info.appendChild(el('div', '', '👤 ' + (c.te || c.n) + (c.te && c.te !== c.n ? ' (' + c.n + ')' : '')));
    if (c.w) info.appendChild(el('div', '', c.w));
    info.appendChild(el('div', 'muted', 'అధ్యాయాలు: ' + (c.u || []).map((u) => u + 1).join(', ')));
    rs.filter((l) => l[0] === c.n || l[1] === c.n).forEach((l) => info.appendChild(el('div', '', '🔗 ' + (l[0] === c.n ? l[1] : l[0]) + ' · ' + l[2])));
  };
  cv2.onclick = (e) => {
    const r = cv2.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    let best = null, bd = 30;
    cs.forEach((c) => { const p = pos[c.n]; if (!p) return; const d = Math.hypot(p.x - x, p.y - y); if (d < bd) { bd = d; best = c; } });
    sel = best ? best.n : null; draw(); tell(best);
  };
  draw();
  body.appendChild(el('div', 'sec2', '👥 పాత్రలు'));
  cs.forEach((c) => {
    const d = el('div', 'who');
    d.appendChild(el('b', '', (c.te || c.n)));
    d.appendChild(document.createTextNode((c.te && c.te !== c.n ? ' (' + c.n + ')' : '') + (c.w ? ' — ' + c.w : '')));
    d.onclick = () => { sel = c.n; draw(); tell(c); $('pBody').scrollTop = 0; };
    body.appendChild(d);
  });
  if (es.length) {
    body.appendChild(el('div', 'sec2', '📜 కథ, అధ్యాయం వారీగా'));
    es.slice().sort((a, b2) => a.u - b2.u).forEach((x) => body.appendChild(el('div', 'ev', 'అధ్యాయం ' + (x.u + 1) + ': ' + x.e)));
  }
}
$('pBack').onclick = () => { $('mapp').hidden = true; };

// ---- 🎧 a chapter as a sound file
function openAudio() {
  T.kind = 'audio'; T.id = R.id; T.unit = R.unit;
  const b = S.byId[R.id] || {};
  const q = toolSheet('🎧 అధ్యాయం ఆడియో ఫైల్ (₹0)');
  q.appendChild(el('p', '', 'ఈ అధ్యాయాన్ని మీరు వినే అదే ఫోన్ గొంతుతో, అదే వేగంతో ఒక ఆడియో ఫైల్ (.m4a) గా చేసి ఫోన్‌లోని Music/Jarvis Library లో పెడతాను. నెట్ అక్కర్లేదు, ఖర్చు లేదు. తర్వాత ఏ మ్యూజిక్ యాప్‌లో అయినా, బ్లూటూత్‌లో అయినా వినొచ్చు.'));
  let lang = b.lang === 'te' ? 'en' : readLang();
  const langs = b.lang === 'te' ? [['en', 'తెలుగు']] : [['en', 'English'], ['te', 'తెలుగు (ఫోన్)']].concat(R.ai ? [['ai', '✨ AI తెలుగు']] : []);
  const row = el('div', 'row');
  const chips2 = langs.map(([k, n]) => { const c = btn(n, 'chip' + (k === lang ? ' on' : ''), () => { lang = k; chips2.forEach((x, i) => { x.className = 'chip' + (langs[i][0] === k ? ' on' : ''); }); }); row.appendChild(c); return c; });
  q.appendChild(row);
  const pr = el('div', 'tbox', ''); pr.id = 'audLine'; q.appendChild(pr);
  const bar = el('div', 'bar'); const fill = el('i'); fill.id = 'audFill'; fill.style.width = '0%'; bar.appendChild(fill); q.appendChild(bar);
  const r2 = el('div', 'row'); r2.id = 'audBtns';
  r2.appendChild(btn('🎧 తయారు చేయి', 'gold', () => { r2.textContent = ''; r2.appendChild(btn('⏹ ఆపు', '', () => post({ t: 'audio_stop' }))); pr.textContent = '🎧 మొదలుపెడుతున్నాను…'; post({ t: 'audio', id: T.id, unit: T.unit, lang }); }));
  r2.appendChild(btn('వద్దు', '', closeTool));
  q.appendChild(r2);
  q.appendChild(el('p', 'muted', 'ఒక పెద్ద అధ్యాయానికి కొన్ని నిమిషాలు పట్టొచ్చు; ఈ షీట్ మూసినా తయారవుతూనే ఉంటుంది.'));
}

// ================================================================ 📊 నా చదువు: stats, marks, what next, 🏠
S.me = { stats: null, marks: null, ol: null, ai: null, aiq: null, aiErr: '' };
function openMe() {
  closeBook(true);
  $('me').hidden = false;
  renderMe();
  post({ t: 'stats' });
  post({ t: 'allmarks' });
}
function readAuthors() {
  const seen = {};
  S.books.filter((b) => b.state === 'ready' && (S.pos[b.id] || b.done || b.fav) && b.a).forEach((b) => { seen[b.a] = 1; });
  return Object.keys(seen).slice(0, 4);
}
function readTitles() { return S.books.filter((b) => b.state === 'ready' && (S.pos[b.id] || b.done || b.fav)).map((b) => b.t + (b.a ? ' — ' + b.a : '')).slice(0, 20); }
function renderMe() {
  if ($('me').hidden) return;
  const body = $('mBody');
  const keepY = body.scrollTop;
  body.textContent = '';
  const st = S.me.stats;
  body.appendChild(el('div', 'sec2', '📊 లెక్కలు'));
  if (!st) body.appendChild(el('p', 'muted', '…'));
  else {
    const tiles = el('div', 'tiles');
    [[st.today + ' ని.', 'ఈ రోజు విన్నది'], [st.streak + ' 🔥', 'వరుసగా రోజులు'], [st.all_h + ' గం.', 'మొత్తం'], [st.week_m + ' ని.', 'ఈ వారం'], [st.ch_week, 'ఈ వారం అధ్యాయాలు'], [st.done, 'పూర్తయిన పుస్తకాలు']]
      .forEach(([v, l]) => { const t = el('div', 'tile'); t.appendChild(el('b', '', String(v))); t.appendChild(el('span', '', l)); tiles.appendChild(t); });
    body.appendChild(tiles);
    const bars = el('div', 'bars');
    const max = Math.max(10, ...st.week.map((x) => x.m));
    st.week.forEach((x) => { const b = el('div', 'b'); b.appendChild(el('em', '', x.m ? String(x.m) : '')); const i = el('i'); i.style.height = Math.round(x.m / max * 80) + 'px'; b.appendChild(i); b.appendChild(el('span', '', x.d)); bars.appendChild(b); });
    body.appendChild(bars);
    body.appendChild(el('p', 'muted', 'నిమిషాలు = Jarvis చదివి వినిపించిన సమయం (ఈ ఫోన్‌లోనే లెక్క).'));
    (st.top || []).forEach((x) => { const d = el('div', 'sug'); d.appendChild(el('div', 'w', '📖 ' + x.t + ' · ' + x.m + ' ని.')); d.appendChild(btn('చూపించు', '', () => { $('me').hidden = true; show(x.id); })); body.appendChild(d); });
  }
  body.appendChild(el('div', 'sec2', '🖍️ నా గుర్తులు, నోట్స్'));
  const mk = S.me.marks;
  if (!mk) body.appendChild(el('p', 'muted', '…'));
  else if (!mk.length) body.appendChild(el('p', 'muted', 'ఇంకా గుర్తులు లేవు. చదివేటప్పుడు ఒక లైన్ తాకి "🖍️ గుర్తు పెట్టు" నొక్కండి.'));
  else mk.forEach((x) => {
    const d = el('div', 'sug');
    d.appendChild(el('div', 'w', '📖 ' + x.t + ' · ' + x.m.length + ' గుర్తులు'));
    d.appendChild(btn('చూడు', '', () => openMarksSheet(x.id)));
    d.appendChild(btn('📄 PDF', '', () => { openMarksSheet(x.id); const p = $('pdfline'); if (p) p.textContent = '📄 తయారవుతోంది…'; post({ t: 'marks_pdf', id: x.id }); }));
    body.appendChild(d);
  });
  body.appendChild(el('div', 'sec2', '📚 తర్వాత ఏమి చదవాలి'));
  const nx = K.nextReads(S.books, S.pos);
  if (!nx.length) body.appendChild(el('p', 'muted', 'రాక్‌లలో ఇంకా సూచించడానికి ఏమీ లేదు.'));
  nx.forEach((x) => {
    const d = el('div', 'sug');
    const w = el('div', 'w'); w.appendChild(el('div', '', '📗 ' + x.b.t + (x.b.a ? ' · ' + x.b.a : ''))); w.appendChild(el('div', 'why', x.why)); d.appendChild(w);
    d.appendChild(btn('చూపించు', '', () => { $('me').hidden = true; show(x.b.id); }));
    body.appendChild(d);
  });
  const au = readAuthors();
  if (au.length) body.appendChild(btn('🌐 మీరు చదివిన రచయితల ఇంకా ఉచిత పుస్తకాలు (Open Library, ₹0)', 'wide', () => { S.me.ol = 'busy'; renderMe(); post({ t: 'next_ol', authors: au }); }));
  if (S.me.ol === 'busy') body.appendChild(el('p', 'muted', '🌐 వెతుకుతున్నాను…'));
  else if (Array.isArray(S.me.ol)) {
    if (!S.me.ol.length) body.appendChild(el('p', 'muted', 'Open Library లో కొత్తవి దొరకలేదు.'));
    S.me.ol.forEach((o) => {
      const d = el('div', 'sug');
      const w = el('div', 'w'); w.appendChild(el('div', '', '🌐 ' + o.t + (o.a ? ' · ' + o.a : ''))); w.appendChild(el('div', 'why', o.why || '')); d.appendChild(w);
      d.appendChild(btn('⬇️ దించు', '', () => { post({ t: 'download', item: o, then: 'show' }); toast('⬇️ దించుతున్నాను: ' + o.t); }));
      body.appendChild(d);
    });
  }
  if (readTitles().length) body.appendChild(btn('✨ AI సూచనలు (మీరు చదివినవాటి పేర్లు మాత్రమే AI కి వెళ్తాయి)', 'wide', () => { S.me.ai = null; S.me.aiq = 'busy'; renderMe(); post({ t: 'next_q' }); }));
  if (S.me.aiq === 'busy') body.appendChild(el('p', 'muted', '…'));
  else if (S.me.aiq && S.me.aiq.rupees !== undefined && !S.me.ai) {
    const q = S.me.aiq;
    if (q.why) body.appendChild(el('p', '', q.why));
    else {
      const r = el('div', 'row');
      r.appendChild(btn('✅ అడుగు · సుమారు ' + K.money(q.rupees) + ' (' + q.model + ')', 'gold', () => { S.me.ai = 'busy'; renderMe(); post({ t: 'next_ai', titles: readTitles() }); }));
      r.appendChild(btn('వద్దు', '', () => { S.me.aiq = null; renderMe(); }));
      body.appendChild(r);
    }
  }
  if (S.me.ai === 'busy') body.appendChild(el('p', 'muted', '✨ ఆలోచిస్తోంది…'));
  else if (Array.isArray(S.me.ai)) S.me.ai.forEach((o) => {
    const d = el('div', 'sug');
    const w = el('div', 'w'); w.appendChild(el('div', '', '✨ ' + o.t + (o.a ? ' · ' + o.a : ''))); w.appendChild(el('div', 'why', o.why || '')); d.appendChild(w);
    d.appendChild(btn('🔍 వెతుకు', '', () => { $('me').hidden = true; openSearch(o.t + (o.a ? ' ' + o.a : '')); }));
    body.appendChild(d);
  });
  if (S.me.aiErr) body.appendChild(el('p', '', S.me.aiErr));
  body.appendChild(el('div', 'sec2', '🏠 ఇంటి పుస్తకాల అర'));
  body.appendChild(el('p', 'muted', 'మీ ఇంట్లో ఉన్న పుస్తకాల అర ఫోటో తీస్తే, AI వెన్నెముకల మీది పేర్లు చదువుతుంది; మీరు ఎంచుకున్నవి 🏠 గుర్తుతో రాక్‌లో పెడతాను. ఫోటో దాచను.'));
  body.appendChild(btn('📸 ఇంటి అర ఫోటో తీయి', 'wide', () => post({ t: 'shelf_photo' })));
  body.scrollTop = keepY;
}
$('bMe').onclick = openMe;
$('mBack').onclick = () => { $('me').hidden = true; };

// ---- 🏠 the shelf photo's sheets
function shelfReady(m) {
  T.kind = 'shelf'; T.id = '';
  const q = toolSheet('🏠 ఇంటి అర ఫోటో రెడీ');
  if (m.why) { q.appendChild(el('p', '', m.why)); q.appendChild(btn('సరే', 'wide', () => { post({ t: 'shelf_cancel' }); closeTool(); })); return; }
  q.appendChild(el('p', '', 'మీ లైబ్రరీ AI ఫోటోలోని పుస్తకాల పేర్లు చదువుతుంది. తర్వాత మీరు ఎంచుకున్నవే రాక్‌లో పెడతాను. ఫోటో ఈ పనికే, తర్వాత పోతుంది.'));
  q.appendChild(el('p', 'muted', '🤖 ' + m.model + (m.known === false ? ' · ధర అంచనా' : '')));
  const r = el('div', 'row');
  r.appendChild(btn('✨ పేర్లు చదువు · సుమారు ' + K.money(m.rupees), 'gold wide', () => { r.textContent = ''; r.appendChild(el('p', 'muted', '✨ చదువుతోంది…')); post({ t: 'shelf_go' }); }));
  r.appendChild(btn('వద్దు (ఫోటో వదిలేయి)', 'wide', () => { post({ t: 'shelf_cancel' }); closeTool(); }));
  q.appendChild(r);
}
function shelfBooks(m) {
  T.kind = 'shelf'; T.id = '';
  const q = toolSheet('🏠 ఫోటోలో దొరికిన పుస్తకాలు');
  if (m.err) { q.appendChild(el('p', '', m.err)); return; }
  const list = m.list || [];
  q.appendChild(el('p', 'muted', list.length + ' పుస్తకాలు చదివింది. రాక్‌లో పెట్టాల్సినవి ఎంచుకోండి (తప్పుగా చదివినవి తీసేయండి).'));
  const boxes = [];
  list.forEach((x) => {
    const d = el('label', 'chk');
    const c = el('input'); c.type = 'checkbox'; c.checked = true;
    d.appendChild(c); d.appendChild(el('span', '', x.t + (x.a ? ' · ' + x.a : '')));
    boxes.push([c, x]);
    q.appendChild(d);
  });
  const r = el('div', 'row');
  r.appendChild(btn('🏠 రాక్‌లో పెట్టు', 'gold wide', () => { const pick = boxes.filter(([c]) => c.checked).map(([, x]) => x); if (!pick.length) return; post({ t: 'home_add', list: pick }); closeTool(); toast('🏠 ' + pick.length + ' పుస్తకాలు పెడుతున్నాను… (వాటి వివరాలు Open Library నుంచి)', 5000); }));
  r.appendChild(btn('వద్దు', 'wide', closeTool));
  q.appendChild(r);
}

// ================================================================ messages from the phone
function onJarvis(m) {
  switch (m.t) {
    case 'init':
      S.starter = m.starter || []; S.shelf = m.shelf || []; S.pos = m.pos || {}; S.pack = !!m.pack; S.model = m.model || '';
      S.prefs = Object.assign(S.prefs, m.prefs || {}); S.view = S.prefs.view === 'era' ? 'era' : 'sec'; S.now = m.now || null; S.ai = m.ai || '';
      S.rate = Number(m.rate) || 0; S.aiNow = m.ainow || null; if (S.aiNow && !S.ai) S.ai = S.aiNow.id;
      rebuild();
      { const reading = K.myShelves(S.books, S.pos).reading.length > 0; goRack(reading ? 'my' : (S.view === 'era' ? 'universe' : 'fic'), true); }
      S.ready = true;
      nowBar();
      aiBars();
      break;
    case 'shelf': {
      S.shelf = m.shelf || []; S.pos = m.pos || S.pos;
      const sel = S.sel && S.sel.b.id;
      const keep = { x: cam.x, y: cam.y, z: cam.z };
      closeBook(true);
      rebuild();
      Object.assign(cam, keep);
      if (sel) { const mesh = bookMeshes.find((x) => x.userData.id === sel); if (mesh) openBook(mesh); }
      break;
    }
    case 'prog': {
      S.busy[m.id] = m.s;
      const a = $('cardProg'); if (a && S.sel && S.sel.b.id === m.id) a.textContent = m.s;
      const r = $('rp-' + m.id); if (r) r.textContent = m.s;
      if (m.id === 'add') { const p = $('addProg'); if (p) p.textContent = m.s; }
      break;
    }
    case 'got': {
      const b = m.book || {};
      delete S.busy[b.id];
      const r = $('rp-' + b.id); if (r) r.textContent = '✅ రాక్‌లో పెట్టాను';
      if (b.id && b.id.startsWith('f-')) { $('add').hidden = true; S.addInfo = null; }
      toast('✅ "' + (b.t || '') + '" రాక్‌లో పెట్టాను' + (m.cost > 0 ? ' · ఖర్చు ' + K.money(m.cost) : ''));
      if (m.then === 'read') post({ t: 'read', id: b.id, lang: 'en' });
      if (m.then === 'read' || m.then === 'show') setTimeout(() => { $('search').hidden = true; show(b.id); }, 400);
      break;
    }
    case 'fail': {
      delete S.busy[m.id];
      toast(m.s, 6000);
      const a = $('cardProg'); if (a) a.textContent = '';
      const r = $('rp-' + m.id); if (r) r.textContent = m.s;
      if (m.id === 'add') { const p = $('addProg'); if (p) p.textContent = m.s; }
      break;
    }
    case 'results': showResults(m); break;
    case 'thumb': {
      const c = $('th-' + m.id);
      if (c && typeof m.d === 'string' && m.d.startsWith('data:image/')) { c.textContent = ''; const img = el('img'); img.src = m.d; img.alt = ''; c.appendChild(img); }
      break;
    }
    case 'chapter': gotChapter(m); break;
    case 'te': gotTe(m); break;
    case 'need_pack': packSheet(); break;
    case 'pack': S.pack = !!m.ok; toast(m.s, 5000); if (m.ok && !$('reader').hidden && wantsPhoneTe()) needTe(); break;
    case 'quote': if (S.afterModel) afterModel(); else quoteSheet(m); break;
    case 'models': modelSheet(m); break;
    case 'model': S.model = m.model || ''; S.rate = Number(m.rate) || 0; break;
    case 'ai_on': S.ai = m.id; S.aiNow = { id: m.id, kind: m.kind || 'tr', title: (S.byId[m.id] || {}).t || '', unit: 0, k: 0, n: 0, ci: 1, cn: 1, rs: 0 }; aiBars();
      if (m.kind !== 'map') toast('✨ AI తెలుగు మొదలుపెట్టాను. అయిన భాగాలు వెంటనే దాచుకుంటాను; ఆపాలంటే ⏹.'); break;
    case 'trs': S.month = m; S.rate = Number(m.rate) || S.rate; if (m.model) S.model = m.model;
      if (m.shelf) { (m.shelf || []).forEach((x) => { if (x && x.ai_u) aiMerge(x.id, { ai_u: x.ai_u, ai_cc: x.ai_cc, ai_rs: x.ai_rs }); }); }
      if (m.ainow) { S.aiNow = m.ainow; S.ai = m.ainow.id; } renderTrs(); aiBars(); break;
    case 'aiprog': {
      S.ai = m.id; S.aiNow = m; aiMerge(m.id, m.ai);
      aiBars(); renderTrs();
      if (!$('reader').hidden && R.id === m.id) chapList();
      S.busy[m.id] = m.s;
      const a = $('cardProg');
      if (a && S.sel && S.sel.b.id === m.id) { a.textContent = m.s + ' '; a.appendChild(btn('⏹ ఆపు', '', () => post({ t: 'ai_stop' }))); }
      break;
    }
    case 'aidone':
      S.ai = ''; S.aiNow = null; delete S.busy[m.id]; aiBars(); renderTrs();
      toast(m.s + (m.kind === 'map' ? ' చదివే స్క్రీన్‌లో 🗺️ పాత్రలు నొక్కండి.' : ''), 7000);
      if (!$('reader').hidden && R.id === m.id && m.kind !== 'map') post({ t: 'chapter', id: R.id, unit: R.unit });
      { const a = $('cardProg'); if (a) a.textContent = ''; }
      break;
    case 'now':
      S.now = m.on ? m : (S.now ? Object.assign({}, S.now, { on: false }) : null);
      if (m.on) S.pos[m.id] = [m.unit, m.part, Date.now()];
      if (!$('reader').hidden) {
        if (m.on && m.id === R.id && m.unit !== R.unit && R.follow && Date.now() - R.scrolledAt > 6000) { R.unit = m.unit; post({ t: 'chapter', id: R.id, unit: R.unit }); }
        else markNow(true);
        playLabel();
      }
      nowBar();
      break;
    case 'word': { const bub = $('bubble'); bub.textContent = ''; bub.appendChild(el('b', '', m.w + ': ')); bub.appendChild(document.createTextNode(m.te)); bub.hidden = false; break; }
    case 'show': if (S.ready) show(m.id); else setTimeout(() => onJarvis(m), 300); break;
    case 'search_open': if (!S.ready) { setTimeout(() => onJarvis(m), 300); break; } openSearch(m.q || undefined); break;
    case 'view':
      if (!S.ready) { setTimeout(() => onJarvis(m), 300); break; }
      if (m.v === 'add') { openAdd(); break; }
      if (m.v === 'tr') { closeBook(true); openTrs(); break; }
      if (m.v === 'era' || m.v === 'sec') { closeBook(true); S.view = m.v; post({ t: 'prefs', view: S.view }); rebuild(); goRack(m.v === 'era' ? 'universe' : 'fic', true); }
      break;
    case 'addinfo': addForm(m); break;
    // ---- 📊 🖍️ 🎧 💬 📝 ❓ 🗺️ 📚 🏠
    case 'stats': S.me.stats = m; renderMe(); break;
    case 'allmarks': S.me.marks = m.list || []; renderMe(); break;
    case 'marks':
      if (m.id === R.id) { R.marks = m.list || []; if (!$('reader').hidden) { const y = $('rBody').scrollTop; renderText(); $('rBody').scrollTop = y; toolsBar(); } }
      if (S.me.marks) post({ t: 'allmarks' });
      break;
    case 'pdfready': {
      const p = $('pdfline');
      const say = '📄 PDF సేవ్ అయింది: ' + m.where;
      if (p) { p.textContent = say + ' '; if (m.share) p.appendChild(btn('📤 షేర్ చేయి', 'gold', () => post({ t: 'pdf_share' }))); }
      else toast(say, 6000);
      break;
    }
    case 'audprog': {
      const l = $('audLine'), f = $('audFill');
      if (l) l.textContent = m.s; if (f) f.style.width = Math.max(0, Math.min(100, m.pct || 0)) + '%';
      break;
    }
    case 'auddone': {
      const l = $('audLine'), f = $('audFill'), b = $('audBtns');
      if (m.err) { if (l) l.textContent = '⚠ ' + m.err; else toast('🎧 ' + m.err, 6000); if (b) { b.textContent = ''; b.appendChild(btn('సరే', '', closeTool)); } break; }
      if (f) f.style.width = '100%';
      if (l) l.textContent = '✅ ఆడియో ఫైల్ రెడీ: ' + m.where; else toast('🎧 ఆడియో ఫైల్ రెడీ: ' + m.where, 6000);
      if (b) { b.textContent = ''; if (m.play) b.appendChild(btn('▶ వినండి', 'gold', () => post({ t: 'audio_play' }))); b.appendChild(btn('సరే', '', closeTool)); }
      break;
    }
    case 'tquote': gotTquote(m); break;
    case 'tlive': {
      if (m.kind === 'ask') { const o = $('askOut'); if (o && T.kind === 'ask' && T.id === m.id) o.textContent = m.s; break; }
      const o = $('tlive'); if (o && T.kind === m.kind && T.id === m.id) o.textContent = m.s;
      break;
    }
    case 'tres': {
      if (m.kind === 'map') { if (m.err) toast('🗺️ ' + m.err, 6000); else { T.id = m.id; openMap(m.res); } break; }
      if (T.id !== m.id || (T.kind !== m.kind)) { if (m.err) toast(m.err, 6000); else toast('✅ ' + (TOOL_NAMES[m.kind] || '') + ' రెడీ: మళ్ళీ నొక్కితే ₹0', 5000); break; }
      if (m.err) {
        if (m.kind === 'ask') { const o = $('askOut'); if (o) o.textContent = '⚠ ' + m.err; break; }
        toolSheet(TOOL_NAMES[m.kind]).appendChild(el('p', '', '⚠ ' + m.err));
        break;
      }
      if (m.kind === 'sum') showSum(m.res, !!m.res.kept);
      else if (m.kind === 'quiz') showQuiz(m.res, !!m.res.kept);
      else if (m.kind === 'ask') {
        const o = $('askOut');
        T.shownQ = m.res.q;
        if (o) o.textContent = m.res.a + '\n\n' + (m.res.kept ? '💾 ఇంతకుముందు అడిగిందే · ₹0' : '🤖 ' + (m.res.model || '') + ' · ఖర్చు ' + K.money(m.res.rs || 0));
        post({ t: 'tkept', kind: 'ask', id: T.id, unit: T.unit });
      }
      break;
    }
    case 'tkept': {
      if (m.kind === 'ask') { if (T.kind === 'ask' && T.id === m.id) askHistory(((m.res || {}).list || []).filter((x) => !(T.shownQ && x.q === T.shownQ))); break; }
      if (m.kind === 'map') { T.id = m.id; openMap(m.res); break; }
      if (T.kind !== m.kind || T.id !== m.id) break;
      if (!m.res) { toolAsk(m.kind); break; }
      if (m.kind === 'sum') showSum(m.res, true); else if (m.kind === 'quiz') showQuiz(m.res, true);
      break;
    }
    case 'nextol': S.me.ol = m.list || []; renderMe(); break;
    case 'nextq': S.me.aiq = m; renderMe(); break;
    case 'nextai': if (m.err) { S.me.ai = null; S.me.aiq = null; S.me.aiErr = '⚠ ' + m.err; } else { S.me.ai = m.list || []; S.me.aiErr = ''; } renderMe(); break;
    case 'shelfready': shelfReady(m); break;
    case 'shelfbooks': shelfBooks(m); break;
    case 'say': toast(m.s, 5000); break;
    case 'back': back(); break;
    default: break;
  }
}

resize();
requestAnimationFrame(frame);
window.__lib = { S, onJarvis, get cam() { return cam; }, show, openReader, back, rebuild,
  where(id) { const m = bookMeshes.find((x) => x.userData.id === id); if (!m) return null; frame(performance.now()); const v = m.getWorldPosition(new THREE.Vector3()).project(camera); return { x: (v.x + 1) / 2 * cv.clientWidth, y: (1 - v.y) / 2 * cv.clientHeight }; } };
