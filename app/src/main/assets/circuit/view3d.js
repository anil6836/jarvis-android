// 🧊 The circuit lab's 3D view (Anil, 16 Nov, G2): the same board, standing up on a dark bench — parts as blocks, bulbs that
// glow, fans that turn, wires with the current moving along them. Our own code on the bundled three.js; it only draws what
// the simulator already worked out (no AI, no network). The page (lab.js) opens it and tells it when anything changes.
import * as THREE from './three.module.min.js';
import { OrbitControls } from './OrbitControls.js';

const FONT = '"Noto Sans Telugu", system-ui, "Noto Color Emoji", sans-serif';
const EMOJI = '"Noto Color Emoji", "Apple Color Emoji", sans-serif';
const CAT_COL = { src: 0x1b4f61, ctl: 0x34404d, load: 0x26323f, kit: 0x2d3a2b };
const H = { src: 0.9, ctl: 0.45, load: 0.7, kit: 0.38 };
const GLOW = new Set(['bulb', 'ledbulb', 'tube', 'dclamp', 'headlight', 'smallbulb', 'led']);
const SPIN = new Set(['fan', 'mixie', 'motor', 'dcmotor']);
const LED_COL = { red: 0xff5050, green: 0x5aff8c, blue: 0x5aa0ff, yellow: 0xffe65a, white: 0xf0f8ff };

let api = null, host = null, renderer = null, scene = null, camera = null, controls = null, root = null, raf = 0;
let shapeKey = '', parts = new Map(), wires = [], dots = null, lights = [], labelsAt = 0, t0 = performance.now(), lastFrame = 0;
let down = null, failed = false, fitted = false;

function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = String(text); return e; }

/** Opens the 3D view over the board. a = {board(), res(), st(), names(), K, S, reading(p), onTap(id), onClose()} */
function open(a) {
  api = a;
  if (!host) build();
  host.style.display = 'block';
  document.body.classList.add('v3d');
  if (failed) return;
  fitted = false;
  shapeKey = '';
  update();
  resize();
  if (!raf) raf = requestAnimationFrame(frame);
}

function close() {
  if (host) host.style.display = 'none';
  document.body.classList.remove('v3d');
  cancelAnimationFrame(raf);
  raf = 0;
}

function isOpen() { return !!host && host.style.display === 'block'; }

function build() {
  host = el('div');
  host.id = 'v3d';
  host.style.cssText = 'position:absolute;inset:0;z-index:7;background:#05080c;display:none;touch-action:none';
  const bar = el('div');
  bar.style.cssText = 'position:absolute;left:8px;right:8px;top:8px;display:flex;gap:6px;z-index:2;align-items:center';
  const close2d = el('span', 'tool main', '✕ 2D');
  close2d.addEventListener('click', () => { close(); if (api && api.onClose) api.onClose(); });
  const spin = el('span', 'tool', '🔄 తిప్పు');
  spin.addEventListener('click', () => { if (!controls) return; controls.autoRotate = !controls.autoRotate; spin.classList.toggle('on', controls.autoRotate); });
  const home = el('span', 'tool', '⤢ మొత్తం');
  home.addEventListener('click', () => { fitted = false; fit(); });
  bar.appendChild(close2d); bar.appendChild(spin); bar.appendChild(home);
  host.appendChild(bar);
  const tip = el('div', 'muted', 'ఒక వేలితో తిప్పండి · రెండు వేళ్ళతో జూమ్ · స్విచ్ తాకితే ఆన్ / ఆఫ్');
  tip.style.cssText = 'position:absolute;left:0;right:0;bottom:10px;text-align:center;font-size:12.5px;color:#88a5b3;z-index:2;pointer-events:none';
  host.appendChild(tip);
  document.querySelector('main').appendChild(host);
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'low-power' });
  } catch (e) {
    failed = true;
    const m = el('div', '', 'ఈ ఫోన్‌లో 3D తెరవలేకపోయాను (WebGL). 2D బోర్డ్ అలాగే పనిచేస్తుంది.');
    m.style.cssText = 'position:absolute;left:20px;right:20px;top:40%;text-align:center;color:#dceef5';
    host.appendChild(m);
    return;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.domElement.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block';
  host.appendChild(renderer.domElement);
  scene = new THREE.Scene();
  scene.background = new THREE.Color(0x05080c);
  scene.fog = new THREE.Fog(0x05080c, 30, 80);
  camera = new THREE.PerspectiveCamera(45, 1, 0.1, 200);
  scene.add(new THREE.HemisphereLight(0xbfe9ff, 0x0b0f14, 1.1));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(6, 14, 8);
  scene.add(sun);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.maxPolarAngle = Math.PI * 0.47;
  controls.minDistance = 3;
  controls.maxDistance = 70;
  controls.autoRotateSpeed = 1.2;
  root = new THREE.Group();
  scene.add(root);
  renderer.domElement.addEventListener('pointerdown', (e) => { down = { x: e.clientX, y: e.clientY, at: performance.now() }; });
  renderer.domElement.addEventListener('pointerup', (e) => {
    const d = down; down = null;
    if (!d || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 8 || performance.now() - d.at > 500) return;
    tap(e);
  });
  window.addEventListener('resize', resize);
}

function resize() {
  if (!renderer || !host) return;
  const w = host.clientWidth || 1, h = host.clientHeight || 1;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

// ---------------------------------------------------------------- the board → 3D

function bounds(board, K) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const p of board.parts) { const g = K.geom(p.type), w = (p.rot % 2) ? g.h : g.w, h = (p.rot % 2) ? g.w : g.h; x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x + w); y1 = Math.max(y1, p.y + h); }
  if (x0 > x1) { x0 = 0; y0 = 0; x1 = 8; y1 = 6; }
  return { x0, y0, x1, y1, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2, w: x1 - x0, h: y1 - y0 };
}

let B = null;
const X = (x) => x - B.cx, Z = (y) => y - B.cy;

function keyOf(board) { return JSON.stringify([board.parts.map((p) => [p.id, p.type, p.x, p.y, p.rot || 0]), board.wires]); }

function dispose(o) {
  o.traverse((c) => {
    if (c.geometry) c.geometry.dispose();
    const ms = Array.isArray(c.material) ? c.material : c.material ? [c.material] : [];
    for (const m of ms) { if (m.map) m.map.dispose(); m.dispose(); }
  });
}

/** Rebuilds the scene when the board's parts / wires changed; otherwise only the states (glow, switches, labels). */
function update() {
  if (!api || failed || !isOpen()) return;
  const board = api.board();
  const k = keyOf(board);
  if (k !== shapeKey) { shapeKey = k; rebuild(board); }
  states(true);
  if (!fitted) fit();
}

/** The whole board in view: on an upright phone a wide board is turned so its long side runs into the screen. */
function fit() {
  if (!B || !camera) return;
  resize();
  const tv = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)), th = tv * camera.aspect;
  const turn = camera.aspect < 1 && B.w > B.h * 1.05;
  root.rotation.y = turn ? Math.PI / 2 : 0;
  const across = (turn ? B.h : B.w) / 2 + 1.4, deep = (turn ? B.w : B.h) / 2 + 1.4, phi = 0.95;
  const d = Math.max(across / th, (deep * Math.sin(phi) + 1.2) / tv, 7);
  camera.position.set(0, d * Math.sin(phi), d * Math.cos(phi));
  controls.target.set(0, 0, 0);
  controls.update();
  fitted = true;
}

function rebuild(board) {
  const K = api.K, S = api.S;
  for (const c of [...root.children]) { root.remove(c); dispose(c); }
  parts = new Map(); wires = []; lights = [];
  B = bounds(board, K);
  // the bench
  const bench = new THREE.Mesh(new THREE.BoxGeometry(B.w + 4, 0.3, B.h + 4), new THREE.MeshStandardMaterial({ color: 0x0c141c, roughness: 0.9 }));
  bench.position.y = -0.15;
  root.add(bench);
  const grid = new THREE.GridHelper(Math.ceil(Math.max(B.w, B.h) + 4), Math.ceil(Math.max(B.w, B.h) + 4), 0x1e6a80, 0x16222e);
  grid.position.y = 0.005;
  grid.material.transparent = true; grid.material.opacity = 0.35;
  root.add(grid);
  for (const p of board.parts) {
    const d = S.def(p.type); if (!d) continue;
    const g = K.geom(p.type), w = ((p.rot % 2) ? g.h : g.w) - 0.35, dpt = ((p.rot % 2) ? g.w : g.h) - 0.35;
    const h = p.type === 'inverter' ? 1.3 : H[d.cat] || 0.5;
    const grp = new THREE.Group();
    grp.position.set(X(p.x + (w + 0.35) / 2), 0, Z(p.y + (dpt + 0.35) / 2));
    const body = new THREE.Mesh(new THREE.BoxGeometry(w, h, dpt), new THREE.MeshStandardMaterial({ color: p.type === 'regulator' || p.type === 'npn' ? 0x15181c : CAT_COL[d.cat] || 0x2b3a4a, roughness: 0.55, metalness: 0.15 }));
    body.position.y = h / 2;
    body.userData.part = p.id;
    grp.add(body);
    const edge = new THREE.LineSegments(new THREE.EdgesGeometry(body.geometry), new THREE.LineBasicMaterial({ color: 0x1e6a80 }));
    edge.position.copy(body.position);
    grp.add(edge);
    const rec = { p, grp, body, edge, h, w, dpt };
    // what sits on top
    if (GLOW.has(p.type)) {
      const col = p.type === 'led' ? (LED_COL[S.props(p).color] || 0xff5050) : 0xffd36b;
      const r0 = p.type === 'led' || p.type === 'smallbulb' ? 0.22 : 0.36;
      const glass = new THREE.Mesh(new THREE.SphereGeometry(r0, 20, 14), new THREE.MeshStandardMaterial({ color: 0x222222, emissive: col, emissiveIntensity: 0, roughness: 0.2, transparent: true, opacity: 0.92 }));
      glass.position.y = h + r0 * 0.9;
      glass.userData.part = p.id;
      grp.add(glass);
      rec.glass = glass; rec.col = col;
      if (lights.length < 8) { const L = new THREE.PointLight(col, 0, 6, 1.6); L.position.y = h + r0 + 0.3; grp.add(L); rec.light = L; lights.push(L); }
    }
    if (SPIN.has(p.type)) {
      const rotor = new THREE.Group();
      for (let k = 0; k < 3; k++) { const blade = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.04, 0.16), new THREE.MeshStandardMaterial({ color: 0xc9d6df })); blade.position.x = 0.32; const arm = new THREE.Group(); arm.rotation.y = k * Math.PI * 2 / 3; arm.add(blade); rotor.add(arm); }
      rotor.position.y = h + 0.1;
      grp.add(rotor);
      rec.rotor = rotor;
    }
    if (p.type === 'switch' || p.type === 'switch2' || p.type === 'pushbtn') {
      const lever = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.12, 0.26), new THREE.MeshStandardMaterial({ color: 0x88a5b3 }));
      lever.position.y = h + 0.1;
      lever.userData.part = p.id;
      grp.add(lever);
      rec.lever = lever;
    }
    if (p.type === 'battery' || p.type === 'cell' || p.type === 'capacitor') {
      const bar = new THREE.Mesh(new THREE.BoxGeometry(w * 0.8, 0.08, 0.12), new THREE.MeshStandardMaterial({ color: 0x6be3a4, emissive: 0x6be3a4, emissiveIntensity: 0.4 }));
      bar.position.set(0, h + 0.05, dpt / 2 - 0.12);
      grp.add(bar);
      rec.bar = bar;
    }
    if (['fuse', 'mcb', 'inverter', 'relay', 'npn', 'regulator', 'diode'].includes(p.type)) {
      const lamp = new THREE.Mesh(new THREE.SphereGeometry(0.09, 12, 8), new THREE.MeshStandardMaterial({ color: 0x111111, emissive: 0x6be3a4, emissiveIntensity: 0 }));
      lamp.position.set(w / 2 - 0.18, h + 0.06, dpt / 2 - 0.18);
      grp.add(lamp);
      rec.lamp = lamp;
    }
    // its name and reading, always facing him
    const cv = document.createElement('canvas');
    cv.width = 320; cv.height = 120;
    const tex = new THREE.CanvasTexture(cv);
    tex.colorSpace = THREE.SRGBColorSpace;
    const label = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
    const ls = Math.max(1, Math.min(1.6, Math.max(B.w, B.h) / 13));
    label.scale.set(2.2 * ls, 0.82 * ls, 1);
    label.position.y = h + (GLOW.has(p.type) ? 1.25 : SPIN.has(p.type) ? 0.95 : 0.75);
    label.renderOrder = 5;
    grp.add(label);
    rec.label = label; rec.cv = cv; rec.tex = tex; rec.text = '';
    root.add(grp);
    parts.set(p.id, rec);
    // the terminals: little pins
    for (const t of d.terms) {
      const c = K.termPos(p, t);
      if (!c) continue;
      const pin = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.08, 0.22, 10), new THREE.MeshStandardMaterial({ color: 0xc9a24a, metalness: 0.6, roughness: 0.3 }));
      pin.position.set(X(c[0]), 0.11, Z(c[1]));
      root.add(pin);
    }
  }
  // wires: tubes along the same elbows as on the 2D board
  board.wires.forEach((w, i) => {
    const pa = termAt(board, w.a), pb = termAt(board, w.b);
    if (!pa || !pb) return;
    let pts = [pa, pb];
    if (Math.abs(pa[1] - pb[1]) > 0.01 && Math.abs(pa[0] - pb[0]) > 0.01) { const mx = (pa[0] + pb[0]) / 2; pts = [pa, [mx, pa[1]], [mx, pb[1]], pb]; }
    const v = pts.map((q) => new THREE.Vector3(X(q[0]), 0.16, Z(q[1])));
    const path = new THREE.CurvePath();
    for (let k = 1; k < v.length; k++) if (v[k].distanceTo(v[k - 1]) > 1e-3) path.add(new THREE.LineCurve3(v[k - 1], v[k]));
    if (!path.curves.length) return;
    const mat = new THREE.MeshStandardMaterial({ color: 0x3a4756, roughness: 0.5, emissive: 0x000000 });
    const tube = new THREE.Mesh(new THREE.TubeGeometry(path, Math.max(4, path.curves.length * 6), 0.055, 6, false), mat);
    root.add(tube);
    wires.push({ i, path, len: path.getLength(), mat });
  });
  // the moving current: one instanced set of small glowing beads
  dots = new THREE.InstancedMesh(new THREE.SphereGeometry(0.075, 8, 6), new THREE.MeshBasicMaterial({ color: 0xffe08a }), 700);
  dots.count = 0;
  dots.frustumCulled = false;
  root.add(dots);
}

function termAt(board, key) {
  const i = key.lastIndexOf('.'), p = board.parts.find((q) => q.id === key.slice(0, i));
  return p ? api.K.termPos(p, key.slice(i + 1)) : null;
}

/** The states the simulator gives: glow, turning, switch levers, battery / capacitor bars, warning lamps, labels. */
function states(labels) {
  const res = api.res(), st = api.st(), names = api.names(), S = api.S;
  const shorted = new Set(res.shorted || []);
  for (const [id, rec] of parts) {
    const r = (res.parts || {})[id] || {}, o = S.props(rec.p), s = st[id] || {};
    const bad = ['burnt', 'blown', 'tripped', 'short', 'overload', 'dead'].includes(r.state) || shorted.has(id);
    rec.bad = bad;
    rec.edge.material.color.setHex(bad ? 0xff6b6b : r.on ? 0x74e4ff : 0x1e6a80);
    if (rec.glass) {
      const k = r.on ? Math.min(1.6, r.bright || 0) : 0;
      rec.glass.material.emissiveIntensity = k * 2.2;
      rec.glass.material.color.setHex(k > 0.05 ? 0xffffff : 0x222222);
      if (rec.light) rec.light.intensity = k * 6;
    }
    if (rec.lever) {
      const on = rec.p.type === 'switch' ? !!o.on : rec.p.type === 'pushbtn' ? !!r.on : o.pos === 2;
      rec.lever.rotation.z = rec.p.type === 'switch2' ? (on ? -0.45 : 0.45) : on ? 0 : 0.5;
      rec.lever.position.y = rec.h + (rec.p.type === 'pushbtn' && on ? 0.04 : 0.1);
      rec.lever.material.color.setHex(on ? 0x6be3a4 : 0x88a5b3);
    }
    if (rec.bar) {
      const f = rec.p.type === 'capacitor' ? Math.max(0, Math.min(1, (r.v || 0) / Math.max(0.1, o.v))) : (typeof s.soc === 'number' ? s.soc : 1);
      rec.bar.scale.x = Math.max(0.02, f);
      rec.bar.position.x = -rec.w * 0.4 * (1 - Math.max(0.02, f));
      const col = rec.p.type === 'capacitor' ? 0x74e4ff : f < 0.2 ? 0xff6b6b : f < 0.5 ? 0xffc861 : 0x6be3a4;
      rec.bar.material.color.setHex(col); rec.bar.material.emissive.setHex(col);
    }
    if (rec.lamp) {
      const good = rec.p.type === 'inverter' ? r.mode === 'mains' || r.mode === 'battery' : rec.p.type === 'fuse' || rec.p.type === 'mcb' ? !bad : !!r.on;
      const col = bad ? 0xff6b6b : rec.p.type === 'inverter' && r.mode === 'battery' ? 0xffc861 : rec.p.type === 'regulator' && (r.p || 0) > 3 ? 0xff8a3a : 0x6be3a4;
      rec.lamp.material.emissive.setHex(col);
      rec.lamp.material.emissiveIntensity = bad || good ? 1.6 : 0;
    }
    if (labels) {
      const text = (rec.p.label || names[id] || api.S.def(rec.p.type).te) + '\n' + api.reading(rec.p);
      if (text !== rec.text || rec.badLabel !== bad) { rec.text = text; rec.badLabel = bad; drawLabel(rec, api.S.def(rec.p.type).emoji, text, bad, r.on); }
    }
  }
  const I = res.ok ? res.wires || [] : [];
  for (const w of wires) {
    const live = Math.abs(I[w.i] || 0) > 1e-4;
    w.I = I[w.i] || 0;
    w.mat.color.setHex(live ? 0xc9a24a : 0x3a4756);
    w.mat.emissive.setHex(live ? 0x3a2a08 : 0x000000);
  }
}

function drawLabel(rec, emoji, text, bad, on) {
  const c = rec.cv.getContext('2d');
  c.clearRect(0, 0, rec.cv.width, rec.cv.height);
  c.fillStyle = 'rgba(10,15,21,0.78)';
  c.beginPath(); if (c.roundRect) c.roundRect(6, 6, 308, 108, 18); else c.rect(6, 6, 308, 108); c.fill();
  c.strokeStyle = bad ? '#ff6b6b' : on ? '#1e6a80' : '#2b3a4a'; c.lineWidth = 3; c.stroke();
  const [l1, l2] = text.split('\n');
  c.textAlign = 'center'; c.textBaseline = 'middle';
  c.font = '34px ' + EMOJI;
  c.fillText(emoji || '', 40, 44);
  c.fillStyle = '#dceef5'; c.font = '600 27px ' + FONT;
  c.fillText(l1.length > 16 ? l1.slice(0, 15) + '…' : l1, 178, 44);
  c.fillStyle = bad ? '#ff6b6b' : on ? '#74e4ff' : '#88a5b3'; c.font = '25px ' + FONT;
  c.fillText((l2 || '').slice(0, 22), 160, 86);
  rec.tex.needsUpdate = true;
}

// ---------------------------------------------------------------- every frame

const M4 = new THREE.Matrix4(), V3 = new THREE.Vector3();
function frame(now) {
  raf = 0;
  if (!isOpen()) return;
  const t = (now - t0) / 1000, dt = Math.min(0.05, (now - (lastFrame || now)) / 1000);
  lastFrame = now;
  // beads along the live wires: as many as fit, faster for more current
  let n = 0;
  if (dots) {
    for (const w of wires) {
      if (Math.abs(w.I || 0) <= 1e-4 || n >= 700) continue;
      const speed = Math.min(4, 0.45 + 0.95 * Math.log10(1 + Math.abs(w.I) * 20));
      const gap = 0.45;
      let ph = (t * speed) % gap;
      if (w.I < 0) ph = gap - ph;
      for (let d = ph; d < w.len && n < 700; d += gap) {
        w.path.getPointAt(Math.min(1, d / w.len), V3);
        V3.y = 0.24;
        M4.makeTranslation(V3.x, V3.y, V3.z);
        dots.setMatrixAt(n++, M4);
      }
    }
    dots.count = n;
    dots.instanceMatrix.needsUpdate = true;
  }
  const res = api.res();
  for (const [id, rec] of parts) {
    const r = (res.parts || {})[id] || {};
    if (rec.rotor && r.on) rec.rotor.rotation.y += dt * (6 + 10 * Math.min(1.3, r.bright || 0)) * (r.dir || 1);
    if (rec.bad) rec.edge.material.color.setHex(Math.floor(t * 3) % 2 ? 0xff6b6b : 0x992222);
  }
  if (now - labelsAt > 500) { labelsAt = now; states(true); }
  controls.update();
  renderer.render(scene, camera);
  raf = requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- a tap on a part

const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
function tap(e) {
  const r = renderer.domElement.getBoundingClientRect();
  ndc.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
  ray.setFromCamera(ndc, camera);
  const hits = ray.intersectObjects(root.children, true);
  for (const h of hits) {
    let o = h.object;
    while (o && !(o.userData && o.userData.part)) o = o.parent;
    if (o && o.userData.part) { api.onTap(o.userData.part); return; }
    // (a part's group: find which part it belongs to)
    for (const [id, rec] of parts) { let q = h.object; while (q) { if (q === rec.grp) { if (h.object.isSprite) break; api.onTap(id); return; } q = q.parent; } }
  }
}

window.__view3d = { open, close, update, isOpen };
window.dispatchEvent(new Event('view3d-ready'));
