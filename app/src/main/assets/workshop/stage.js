// 🛠️ The sealed 3D stage of Jarvis's workshop. Parts written by the AI arrive by postMessage ({t:'part', id, code}) and run
// here, one after another, as the body of an async function (THREE, W). Everything a part adds belongs to that part:
// sending the same id again removes the old pieces first. A part that throws is reported back ({t:'err', id, msg}) and
// never takes the stage down. This page has no network (the page's CSP + Jarvis refuses every request but these files),
// and the few ways out of a page are taken away below before any part runs.
import * as THREE from './three.module.min.js';
import { OrbitControls } from './OrbitControls.js';
import { RoundedBoxGeometry } from './RoundedBoxGeometry.js';

const post = (m) => { try { parent.postMessage(m, '*'); } catch (e) { /* (nothing to tell) */ } };

// ------------------------------------------------------------------ the renderer, the studio

const canvas = document.getElementById('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'high-performance' });
let pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
renderer.setPixelRatio(pixelRatio);
renderer.setClearColor(0x000000, 0);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.NeutralToneMapping;
renderer.toneMappingExposure = 1.0;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;

const scene = new THREE.Scene();

// The reflections: a dark room with two big softboxes and one thin strip (a product-photo kit), so metal has something to show.
function studio() {
  const s = new THREE.Scene();
  s.add(new THREE.Mesh(new THREE.BoxGeometry(30, 30, 30), new THREE.MeshBasicMaterial({ color: 0x0a0c10, side: THREE.BackSide })));
  const light = (w, h, x, y, z, k, tint) => {
    const c = new THREE.Color(tint || 0xffffff).multiplyScalar(k);
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: c, side: THREE.DoubleSide }));
    m.position.set(x, y, z);
    m.lookAt(0, 0, 0);
    s.add(m);
  };
  light(7, 5, -8, 6, 6, 5.0);            // key softbox, front left
  light(6, 4, 8, 4, -2, 3.2, 0xe9f0ff);  // fill softbox, right
  light(14, 0.8, 0, 9, -8, 7.0);         // strip high behind: the crisp edge highlights
  light(16, 6, 0, 6, -11, 1.8);          // a broad soft wall behind: what flat tops reflect from the front
  light(9, 9, 0, 12, 1, 1.6);            // overhead softbox: tops of metal parts are not black
  light(10, 10, 0, -9, 0, 0.35);         // a little bounce from the floor
  return s;
}
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(studio(), 0.02).texture;

const camera = new THREE.PerspectiveCamera(32, 1, 0.05, 50000);
camera.position.set(28, 22, 52);

const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.autoRotate = true;
controls.autoRotateSpeed = 1.1;
controls.enablePan = false;
let idleTimer = 0;
let spinWanted = true;
controls.addEventListener('start', () => { controls.autoRotate = false; clearTimeout(idleTimer); userFrame = true; });
controls.addEventListener('end', () => {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => { controls.autoRotate = spinWanted; }, 6000);
});

const hemi = new THREE.HemisphereLight(0xdfe8ff, 0x0b0b0c, 0.3);
scene.add(hemi);
const key = new THREE.DirectionalLight(0xffffff, 2.4);
key.castShadow = true;
key.shadow.mapSize.set(2048, 2048);
scene.add(key, key.target);
const rim = new THREE.DirectionalLight(0xc8dcff, 1.5);
scene.add(rim, rim.target);

// The floor: dark, a little glossy, fading into the backdrop at its edge.
function fade() {
  const c = document.createElement('canvas');
  c.width = c.height = 256;
  const g = c.getContext('2d');
  const r = g.createRadialGradient(128, 128, 0, 128, 128, 128);
  r.addColorStop(0, '#ffffff');
  r.addColorStop(0.55, '#a0a0a0');
  r.addColorStop(1, '#000000');
  g.fillStyle = r;
  g.fillRect(0, 0, 256, 256);
  return new THREE.CanvasTexture(c);
}
const floor = new THREE.Mesh(new THREE.CircleGeometry(1, 96),
  new THREE.MeshStandardMaterial({ color: 0x0f1218, metalness: 0.5, roughness: 0.4, envMapIntensity: 0.45, transparent: true, alphaMap: fade(), depthWrite: false }));
floor.rotation.x = -Math.PI / 2;
floor.receiveShadow = true;
floor.renderOrder = -1;
scene.add(floor);

const model = new THREE.Group();
scene.add(model);

// ------------------------------------------------------------------ the seal: the ways out of a page, taken away

for (const k of ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'RTCPeerConnection', 'webkitRTCPeerConnection',
  'RTCDataChannel', 'RTCSessionDescription', 'RTCIceCandidate', 'Worker', 'SharedWorker', 'BroadcastChannel',
  'WebTransport', 'importScripts', 'open', 'indexedDB', 'caches']) {
  try { Object.defineProperty(window, k, { value: undefined, writable: false, configurable: false }); }
  catch (e) { try { window[k] = undefined; } catch (x) { /* (already gone) */ } }
}
try { Object.defineProperty(Navigator.prototype, 'sendBeacon', { value: undefined, writable: false, configurable: false }); } catch (e) { /* */ }
{
  const make = Document.prototype.createElement;
  const NO = new Set(['iframe', 'frame', 'frameset', 'object', 'embed', 'script', 'link', 'meta', 'base', 'form', 'a', 'img',
    'audio', 'video', 'source', 'track', 'portal', 'fencedframe']);
  Object.defineProperty(Document.prototype, 'createElement', {
    value: function (tag, o) {
      if (NO.has(String(tag).toLowerCase())) throw new Error('the workshop does not allow <' + tag + '>');
      return make.call(this, tag, o);
    }, writable: false, configurable: false,
  });
}

// ------------------------------------------------------------------ the kit (W) the parts use

const deg = (d) => (d || 0) * Math.PI / 180;
const METALS = {
  steel: ['#c9ccd1', 0.32], brushed: ['#b8bcc2', 0.44], aluminium: ['#d5d8dc', 0.3], aluminum: ['#d5d8dc', 0.3],
  chrome: ['#f3f5f8', 0.05], brass: ['#c9a24a', 0.28], copper: ['#c27a4a', 0.3], gold: ['#e3b84f', 0.18],
  titanium: ['#9ea3a8', 0.38], gunmetal: ['#4a4f56', 0.35], black: ['#25272b', 0.4], silver: ['#e2e4e7', 0.15],
};
const colour = (c, d) => new THREE.Color(c == null ? d : c);

const kit = {
  deg,
  // shapes (THREE.BufferGeometry)
  box(w, h, d, r = 0) {
    const m = Math.min(w, h, d) / 2;
    return r > 0 ? new RoundedBoxGeometry(w, h, d, 3, Math.min(r, m * 0.98)) : new THREE.BoxGeometry(w, h, d);
  },
  cyl(rTop, rBottom, h, seg = 48) { return new THREE.CylinderGeometry(rTop, rBottom, h, clampSeg(seg)); },
  cone(r, h, seg = 48) { return new THREE.ConeGeometry(r, h, clampSeg(seg)); },
  sphere(r, seg = 40) { return new THREE.SphereGeometry(r, clampSeg(seg), Math.max(6, Math.round(clampSeg(seg) * 0.75))); },
  torus(R, r, seg = 48, tube = 16) { return new THREE.TorusGeometry(R, r, Math.min(32, Math.max(4, tube)), clampSeg(seg)); },
  lathe(profile, seg = 64) { return new THREE.LatheGeometry(profile.map((p) => new THREE.Vector2(p[0], p[1])), clampSeg(seg)); },
  tube(points, radius, seg = 64, closed = false) {
    const curve = new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(p[0], p[1], p[2])), closed);
    return new THREE.TubeGeometry(curve, Math.min(256, Math.max(4, seg)), radius, 12, closed);
  },
  extrude(outline, depth, opts = {}) {
    const shape = outline instanceof THREE.Shape ? outline : new THREE.Shape(outline.map((p) => new THREE.Vector2(p[0], p[1])));
    for (const h of opts.holes || []) shape.holes.push(new THREE.Path(h.map((p) => new THREE.Vector2(p[0], p[1]))));
    const bevel = opts.bevel || 0;
    const g = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel,
      bevelSegments: 2, curveSegments: 24 });
    g.translate(0, 0, -depth / 2);
    return g;
  },
  ring(rIn, rOut, h, seg = 64) {
    const s = new THREE.Shape();
    s.absarc(0, 0, rOut, 0, Math.PI * 2, false);
    const hole = new THREE.Path();
    hole.absarc(0, 0, rIn, 0, Math.PI * 2, true);
    s.holes.push(hole);
    const g = new THREE.ExtrudeGeometry(s, { depth: h, bevelEnabled: false, curveSegments: clampSeg(seg) });
    g.rotateX(-Math.PI / 2);
    g.translate(0, -h / 2, 0);
    return g;
  },
  gear({ teeth = 20, r = 3, depth = 0.5, hole = 0, toothDepth } = {}) {
    const n = Math.max(6, Math.min(200, Math.round(teeth)));
    const rr = r - (toothDepth || Math.min(r * 0.14, (2.4 * r) / n));
    const s = new THREE.Shape();
    const step = (Math.PI * 2) / n;
    for (let i = 0; i < n; i++) {
      const a = i * step;
      const pts = [[rr, a], [r, a + step * 0.22], [r, a + step * 0.48], [rr, a + step * 0.7]];
      pts.forEach(([rad, ang], j) => {
        const x = rad * Math.cos(ang), y = rad * Math.sin(ang);
        if (i === 0 && j === 0) s.moveTo(x, y); else s.lineTo(x, y);
      });
    }
    s.closePath();
    if (hole > 0) { const h = new THREE.Path(); h.absarc(0, 0, hole, 0, Math.PI * 2, true); s.holes.push(h); }
    const g = new THREE.ExtrudeGeometry(s, { depth, bevelEnabled: false, curveSegments: 32 });
    g.rotateX(-Math.PI / 2);
    g.translate(0, -depth / 2, 0);
    return g;
  },
  // materials
  metal(kind = 'steel', opts = {}) {
    const k = METALS[String(kind).toLowerCase()];
    return new THREE.MeshPhysicalMaterial({ color: colour(k ? k[0] : kind, '#c9ccd1'), metalness: 1,
      roughness: opts.rough != null ? opts.rough : k ? k[1] : 0.3, envMapIntensity: 1.1 });
  },
  plastic(c = '#dcdcdc', opts = {}) {
    return new THREE.MeshPhysicalMaterial({ color: colour(c, '#dcdcdc'), metalness: 0, roughness: opts.rough != null ? opts.rough : 0.45,
      clearcoat: opts.gloss ? 0.8 : 0, clearcoatRoughness: 0.15 });
  },
  rubber(c = '#1b1c1e') { return new THREE.MeshStandardMaterial({ color: colour(c, '#1b1c1e'), metalness: 0, roughness: 0.9 }); },
  paint(c = '#2c4a7a', opts = {}) {
    return new THREE.MeshPhysicalMaterial({ color: colour(c, '#2c4a7a'), metalness: opts.metallic != null ? opts.metallic : 0.35,
      roughness: opts.rough != null ? opts.rough : 0.35, clearcoat: 1, clearcoatRoughness: 0.06 });
  },
  glass(opts = {}) {
    const frost = Math.max(0, Math.min(1, opts.frost || 0));
    return new THREE.MeshPhysicalMaterial({ color: colour(opts.tint, '#ffffff'), metalness: 0, roughness: 0.03 + frost * 0.5,
      transmission: 1, thickness: opts.thickness || 0.5, ior: 1.5, transparent: true });
  },
  wood(c = '#8a5a33') { return new THREE.MeshStandardMaterial({ color: colour(c, '#8a5a33'), metalness: 0, roughness: 0.68 }); },
  ceramic(c = '#f2efe8') {
    return new THREE.MeshPhysicalMaterial({ color: colour(c, '#f2efe8'), metalness: 0, roughness: 0.22, clearcoat: 0.6 });
  },
  fabric(c = '#55585e') { return new THREE.MeshStandardMaterial({ color: colour(c, '#55585e'), metalness: 0, roughness: 0.95 }); },
  glow(c = '#4fd2ff', strength = 2) {
    return new THREE.MeshStandardMaterial({ color: 0x000000, emissive: colour(c, '#4fd2ff'), emissiveIntensity: Math.min(8, strength) });
  },
  screen(c = '#0a0f14', strength = 0.4) {
    return new THREE.MeshPhysicalMaterial({ color: colour(c, '#0a0f14'), metalness: 0.1, roughness: 0.08, clearcoat: 1,
      emissive: colour(c, '#0a0f14'), emissiveIntensity: strength });
  },
  // putting pieces together
  mesh(geometry, material, o = {}) {
    const m = new THREE.Mesh(geometry, material || kit.metal());
    place(m, o);
    return m;
  },
  group(o = {}) { const g = new THREE.Group(); place(g, o); return g; },
  shape() { return new THREE.Shape(); },
};
function clampSeg(s) { return Math.max(3, Math.min(96, Math.round(s) || 3)); }
function place(o, p) {
  if (p.at) o.position.set(p.at[0] || 0, p.at[1] || 0, p.at[2] || 0);
  if (p.rot) o.rotation.set(deg(p.rot[0]), deg(p.rot[1]), deg(p.rot[2]));
  if (p.scale != null) Array.isArray(p.scale) ? o.scale.set(p.scale[0], p.scale[1], p.scale[2]) : o.scale.setScalar(p.scale);
}

// The kit as one part sees it: what it adds and animates belongs to that part.
function kitFor(rec) {
  const w = Object.create(kit);
  w.add = (obj, o = {}) => {
    if (!obj || !obj.isObject3D) throw new Error('W.add needs a mesh or group');
    obj.userData.label = typeof o.label === 'string' ? o.label.slice(0, 60) : '';
    obj.userData.explode = Array.isArray(o.explode) ? o.explode.slice(0, 3).map((v) => +v || 0) : [0, 0, 0];
    obj.traverse((x) => { if (x.isMesh) { x.castShadow = true; x.receiveShadow = true; } });
    rec.group.add(obj);
    const given = new THREE.Vector3().fromArray(obj.userData.explode);
    rec.pieces.push({ obj, label: obj.userData.label, given, explode: given.clone(), base: null, box: null });
    return obj;
  };
  w.onTick = (fn) => { if (typeof fn === 'function') rec.ticks.push(fn); };
  w.spin = (obj, axis = 'y', turns = 0.5) => {
    const a = 'xyz'.includes(axis) ? axis : 'y';
    rec.ticks.push((t, dt) => { obj.rotation[a] += Math.PI * 2 * turns * dt; });
  };
  return Object.freeze(w);
}

// ------------------------------------------------------------------ the parts

const parts = new Map();
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
let queue = Promise.resolve();
const MAX_TRIS = 900000;

const cleanId = (s) => (String(s || 'part').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'part');

function dispose(o) {
  o.traverse((x) => {
    if (x.geometry) x.geometry.dispose();
    const ms = new Set();
    for (const m of [x.material, x.userData.m0, x.userData.mg]) for (const y of (m == null ? [] : Array.isArray(m) ? m : [m])) ms.add(y);
    for (const m of ms) if (!SHARED.has(m)) m.dispose();
  });
}

function removePart(id, replacing) {
  const r = parts.get(id);
  if (!r) { if (!replacing && repick && repick.id === id) { repick = null; post({ t: 'pick', id: '' }); } return; }
  if (picked && picked.rec === r) { // (its glow goes with it; a part written again keeps his pick)
    if (replacing) repick = { id, label: picked.label, whole: picked.objs[0] === r.group };
    else post({ t: 'pick', id: '' });
    picked = null;
  } else if (!replacing && repick && repick.id === id) { repick = null; post({ t: 'pick', id: '' }); }
  if (isolated && isolated.rec === r) { // (alone: the same, written again, stays alone)
    if (replacing) reiso = { id, label: isolated.label, mode: isolated.mode, whole: isolated.objs[0] === r.group };
    else post({ t: 'view', id: '', label: '' });
    isolated = null;
  } else if (!replacing && reiso && reiso.id === id) { reiso = null; post({ t: 'view', id: '', label: '' }); }
  if (!replacing) for (let i = hiddenList.length - 1; i >= 0; i--) if (hiddenList[i].id === id) hiddenList.splice(i, 1);
  model.remove(r.group);
  dispose(r.group);
  parts.delete(id);
  autoExplode();
}

function triangles(o) {
  let n = 0;
  o.traverse((x) => {
    if (!x.isMesh || !x.geometry) return;
    const g = x.geometry;
    const c = g.index ? g.index.count / 3 : (g.attributes.position ? g.attributes.position.count / 3 : 0);
    n += c * (x.isInstancedMesh ? x.count : 1);
  });
  return Math.round(n);
}

function totalTris() { let n = 0; for (const r of parts.values()) n += r.tris || 0; return n; }

function fail(id, msg) {
  removePart(id);
  post({ t: 'err', id, msg: String(msg || 'error').slice(0, 300) });
  stats();
}

async function runPart(m) {
  const id = cleanId(m.id);
  removePart(id, true);
  const rec = { id, group: new THREE.Group(), ticks: [], pieces: [], tris: 0, box: null, born: 0, auto: false,
    label: typeof m.label === 'string' ? m.label.slice(0, 60) : '' };
  rec.group.name = id;
  rec.group.visible = false;
  model.add(rec.group);
  parts.set(id, rec);
  let fn;
  try { fn = new AsyncFunction('THREE', 'W', String(m.code || '')); }
  catch (e) { fail(id, 'syntax error: ' + (e && e.message)); return; }
  try { await fn(THREE, kitFor(rec)); }
  catch (e) { fail(id, (e && e.message) ? e.message : String(e)); return; }
  if (!parts.has(id) || parts.get(id) !== rec) return; // (replaced meanwhile)
  if (rec.group.children.length === 0) { fail(id, 'nothing was added: use W.add(...)'); return; }
  const box = new THREE.Box3().setFromObject(rec.group);
  const nums = [box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z];
  if (box.isEmpty() || nums.some((v) => !isFinite(v))) { fail(id, 'the geometry has no size or has NaN numbers'); return; }
  const span = box.getSize(new THREE.Vector3()).length();
  if (span > 20000) { fail(id, 'far too big (' + Math.round(span) + ' cm): units are centimetres'); return; }
  rec.tris = triangles(rec.group);
  if (totalTris() > MAX_TRIS) { fail(id, 'too many triangles (' + rec.tris + ' in this part): use fewer segments'); return; }
  rec.box = box;
  for (const pc of rec.pieces) { // (where each piece sits when assembled: the pull-apart moves it from here)
    pc.base = pc.obj.position.clone();
    pc.box = new THREE.Box3().setFromObject(pc.obj);
    if (pc.box.isEmpty()) pc.box = null;
  }
  // (a part with no names and no pull-apart directions, like the builds made before A2: it moves away from the middle, named as a whole)
  rec.auto = !rec.pieces.some((pc) => pc.label || pc.given.lengthSq() > 0);
  autoExplode();
  rec.born = performance.now();
  rec.group.visible = true;
  post({ t: 'ok', id, tris: rec.tris });
  if (repick && repick.id === id) { // (the part he had tapped, written again: it is still his pick)
    const rp = repick;
    repick = null;
    picked = { rec, objs: objsFor(rec, rp.label, rp.whole), meshes: null, label: rp.label };
    picked.meshes = meshesOf(picked.objs);
    post({ t: 'pick', id, label: rp.label });
  }
  if (reiso && reiso.id === id) {
    const ri = reiso;
    reiso = null;
    const objs = objsFor(rec, ri.label, ri.whole);
    isolated = { rec, objs, meshes: meshesOf(objs), label: ri.label, mode: ri.mode };
  }
  look();
  frameAll(false);
  stats();
}

function stats() { post({ t: 'stats', parts: parts.size, tris: totalTris() }); }

function reset() {
  picked = null;
  repick = null;
  isolated = null;
  reiso = null;
  xray = false;
  hiddenList.length = 0;
  if (cut.on) { cut.on = false; cut.version++; }
  for (const id of [...parts.keys()]) removePart(id);
  userFrame = false;
  explodeK = explodeGoal = 0;
  goal.zoom = 1;
  goal.dir = null;
  stats();
}

// ------------------------------------------------------------------ framing, the floor and lights follow the model

const goal = { target: new THREE.Vector3(0, 8, 0), dist: 60, floorY: 0, radius: 12, zoom: 1, dir: null };
let userFrame = false; // (he moved the camera himself: keep his angle, still follow the size)

function modelBox() {
  const b = new THREE.Box3();
  if (isolated) { // 🔍 alone: that part fills the view
    for (const o of isolated.objs) b.union(new THREE.Box3().setFromObject(o));
    if (!b.isEmpty()) return b;
  }
  const k = ease(explodeGoal);
  for (const r of parts.values()) {
    if (!r.box) continue;
    if (k === 0 || !r.pieces.some((pc) => pc.box)) { b.union(r.box); continue; }
    for (const pc of r.pieces) if (pc.box) b.union(pc.box.clone().translate(pc.explode.clone().multiplyScalar(k)));
  }
  return b;
}

// ------------------------------------------------------------------ pulled apart (an exploded view) with names

let explodeK = 0, explodeGoal = 0, labelsDirty = true, labelGroups = [];
const ease = (k) => (k <= 0 ? 0 : k >= 1 ? 1 : k * k * (3 - 2 * k));

function placePieces(rec, e) {
  for (const pc of rec.pieces) if (pc.base) pc.obj.position.copy(pc.base).addScaledVector(pc.explode, e);
}

// The directions for the "auto" parts: away from the middle of the assembled model, farther for parts farther out.
function autoExplode() {
  labelsDirty = true;
  const all = new THREE.Box3();
  for (const r of parts.values()) if (r.box) all.union(r.box);
  if (all.isEmpty()) return;
  const mid = all.getCenter(new THREE.Vector3());
  const size = all.getSize(new THREE.Vector3());
  const big = Math.max(size.x, size.y, size.z) || 1;
  for (const r of parts.values()) {
    if (!r.auto || !r.box) continue;
    const d = r.box.getCenter(new THREE.Vector3()).sub(mid);
    const v = d.length() > big * 0.04 ? d.clone().multiplyScalar(0.8).addScaledVector(d.clone().normalize(), big * 0.14) : new THREE.Vector3();
    for (const pc of r.pieces) pc.explode.copy(v);
  }
  const e = ease(explodeK);
  for (const r of parts.values()) placePieces(r, e);
}

// One name per (part, name): where its pieces sit when assembled, and which way they travel.
function buildLabels() {
  labelsDirty = false;
  labelGroups = [];
  for (const r of parts.values()) {
    if (r.auto) { // (one name for the whole part)
      if (!r.box) continue;
      const v = r.pieces.length ? r.pieces[0].explode.clone() : new THREE.Vector3();
      labelGroups.push({ text: r.label || r.id, rec: r, box: r.box.clone(), explode: v, el: null, w: 0, h: 0, center: r.box.getCenter(new THREE.Vector3()) });
      continue;
    }
    const byName = new Map();
    for (const pc of r.pieces) {
      if (!pc.label || !pc.box) continue;
      let g = byName.get(pc.label);
      if (!g) { g = { text: pc.label, rec: r, box: new THREE.Box3(), explode: pc.explode.clone(), el: null, w: 0, h: 0 }; byName.set(pc.label, g); }
      g.box.union(pc.box);
    }
    for (const g of byName.values()) { g.center = g.box.getCenter(new THREE.Vector3()); labelGroups.push(g); }
  }
  if (isolated) labelGroups = labelGroups.filter((g) => g.rec === isolated.rec && (isolated.objs[0] === g.rec.group || g.text === isolated.label || g.rec.auto));
  labelGroups = labelGroups.slice(0, 18);
  const box = document.getElementById('labels');
  const svg = document.getElementById('lines');
  for (const el of [...box.querySelectorAll('.lb')]) el.remove();
  while (svg.firstChild) svg.removeChild(svg.firstChild);
  for (const g of labelGroups) {
    const el = document.createElement('div');
    el.className = 'lb';
    el.textContent = g.text;
    box.appendChild(el);
    g.el = el;
    g.w = el.offsetWidth;
    g.h = el.offsetHeight;
    g.line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    g.dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    g.dot.setAttribute('r', '2.6');
    svg.appendChild(g.line);
    svg.appendChild(g.dot);
  }
}

const tmpV = new THREE.Vector3();
function drawLabels() {
  const box = document.getElementById('labels');
  const a = ease(explodeK), show = Math.max(a, isolated ? 1 : 0);
  box.style.opacity = String(Math.max(0, (show - 0.35) / 0.65));
  if (show < 0.35) return;
  if (labelsDirty) buildLabels();
  const w = window.innerWidth, h = window.innerHeight;
  const top = h * inset.top + 6, bottom = h * (1 - inset.bottom) - 6;
  const cols = { l: [], r: [] };
  for (const g of labelGroups) {
    tmpV.copy(g.center).addScaledVector(g.explode, a);
    tmpV.y += g.rec.group.position.y;
    tmpV.project(camera);
    g.vis = tmpV.z < 1;
    g.x = (tmpV.x + 1) / 2 * w;
    g.y = (1 - tmpV.y) / 2 * h;
    (g.x < w / 2 ? cols.l : cols.r).push(g);
  }
  for (const [side, list] of Object.entries(cols)) {
    list.sort((p, q) => p.y - q.y);
    let next = top;
    for (const g of list) { g.ly = Math.max(next, g.y - g.h / 2); next = g.ly + g.h + 6; }
    if (next - 6 > bottom) { // (ran past the bottom bar: push the column up from the bottom)
      let up = bottom;
      for (let i = list.length - 1; i >= 0; i--) { list[i].ly = Math.min(list[i].ly, up - list[i].h); up = list[i].ly - 6; }
    }
    for (const g of list) {
      const lx = side === 'l' ? 8 : w - 8 - g.w;
      g.el.style.transform = 'translate(' + Math.round(lx) + 'px,' + Math.round(g.ly) + 'px)';
      g.el.style.visibility = g.vis ? 'visible' : 'hidden';
      const ex = side === 'l' ? lx + g.w : lx, ey = g.ly + g.h / 2;
      g.line.setAttribute('x1', g.x.toFixed(1)); g.line.setAttribute('y1', g.y.toFixed(1));
      g.line.setAttribute('x2', ex.toFixed(1)); g.line.setAttribute('y2', ey.toFixed(1));
      g.dot.setAttribute('cx', g.x.toFixed(1)); g.dot.setAttribute('cy', g.y.toFixed(1));
      g.line.style.visibility = g.dot.style.visibility = g.vis ? 'visible' : 'hidden';
    }
  }
}

// ------------------------------------------------------------------ views and light, at once (no AI)

const VIEWS = { front: [0, 0.28, 1], back: [0, 0.28, -1], left: [-1, 0.28, 0], side: [1, 0.28, 0.05], right: [1, 0.28, 0],
  top: [0.001, 1, 0.03], bottom: [0.001, -0.6, 1], iso: [0.55, 0.42, 1] };
function view(name) {
  const v = VIEWS[name];
  if (!v) return;
  goal.dir = new THREE.Vector3().fromArray(v).normalize();
  userFrame = false;
}

// Look across the way the pieces travel (pulled apart along z: from the side; along y: from the front, a little above).
function sideOfTravel() {
  const sum = new THREE.Vector3();
  for (const r of parts.values()) for (const pc of r.pieces) sum.add(new THREE.Vector3(Math.abs(pc.explode.x), Math.abs(pc.explode.y), Math.abs(pc.explode.z)));
  if (sum.z >= sum.x && sum.z >= sum.y) return 'side';
  if (sum.x >= sum.y) return 'front';
  return 'iso';
}

const DAY = { env: 1, key: 2.4, rim: 1.5, hemi: 0.3, exp: 1.0, bg: '' };
function light(mode) {
  const night = mode === 'night';
  scene.environmentIntensity = night ? 0.16 : DAY.env;
  key.intensity = night ? 0.55 : DAY.key;
  rim.intensity = night ? 0.7 : DAY.rim;
  hemi.intensity = night ? 0.06 : DAY.hemi;
  renderer.toneMappingExposure = night ? 0.95 : DAY.exp;
  document.body.style.background = night ? 'radial-gradient(ellipse 70% 60% at 50% 46%, #10151e 0%, #07090d 48%, #020304 100%)' : DAY.bg;
}

// The screen's own bars cover the top and bottom of the stage: the model is framed in the part that is left.
const inset = { top: 0, bottom: 0 };
function applyInset() {
  const w = window.innerWidth || 1, h = window.innerHeight || 1;
  const shift = ((inset.bottom - inset.top) / 2) * h;
  if (Math.abs(shift) < 1) camera.clearViewOffset(); else camera.setViewOffset(w, h, 0, shift, w, h);
  camera.updateProjectionMatrix();
}

function frameAll(now) {
  const b = modelBox();
  if (b.isEmpty()) return;
  const c = b.getCenter(new THREE.Vector3());
  const r = Math.max(0.5, b.getSize(new THREE.Vector3()).length() / 2);
  const seen = Math.max(0.3, 1 - inset.top - inset.bottom);
  const vfov = 2 * Math.atan(Math.tan(deg(camera.fov) / 2) * seen);
  const hfov = 2 * Math.atan(Math.tan(deg(camera.fov) / 2) * camera.aspect);
  goal.target.copy(c);
  // fitted to what turns on the turntable: its round footprint across, its height up
  const size = b.getSize(new THREE.Vector3());
  const across = Math.max(0.3, Math.hypot(size.x, size.z) / 2), up = Math.max(0.3, size.y / 2);
  goal.dist = across * 0.6 + Math.max(across / Math.tan(hfov / 2), up / Math.tan(vfov / 2)) * 1.12;
  goal.floorY = b.min.y;
  goal.radius = r;
  key.position.set(c.x + r * 1.6, c.y + r * 2.6, c.z + r * 1.9);
  key.target.position.copy(c);
  const sc = key.shadow.camera;
  sc.left = sc.bottom = -r * 1.6;
  sc.right = sc.top = r * 1.6;
  sc.near = r * 0.3;
  sc.far = r * 8;
  sc.updateProjectionMatrix();
  key.shadow.normalBias = r * 0.004;
  key.shadow.bias = -0.0002;
  rim.position.set(c.x - r * 2.0, c.y + r * 2.2, c.z - r * 2.4);
  rim.target.position.copy(c);
  camera.near = Math.max(0.01, r * 0.02);
  camera.far = r * 60;
  camera.updateProjectionMatrix();
  controls.minDistance = r * 0.6;
  controls.maxDistance = r * 12;
  if (now) jump();
}

function jump() {
  controls.target.copy(goal.target);
  const dir = camera.position.clone().sub(controls.target).normalize();
  camera.position.copy(goal.target).addScaledVector(dir, goal.dist);
  floor.position.y = goal.floorY;
  floor.scale.setScalar(goal.radius * 9);
}

// ------------------------------------------------------------------ the loop

let running = true;
let last = performance.now();
let slow = 0;
const clock0 = performance.now();

function resize() {
  const w = window.innerWidth || 1, h = window.innerHeight || 1;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  applyInset();
  frameAll(false);
}
window.addEventListener('resize', resize);

function tick(now) {
  if (!running) return;
  requestAnimationFrame(tick);
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  const t = (now - clock0) / 1000;
  // the parts' own movement
  for (const r of parts.values()) {
    for (let i = r.ticks.length - 1; i >= 0; i--) {
      try { r.ticks[i](t, dt); }
      catch (e) { r.ticks.splice(i, 1); post({ t: 'warn', id: r.id, msg: 'tick: ' + ((e && e.message) || e) }); }
    }
    // a new part settles into place from a little above
    if (r.born) {
      const k = Math.min(1, (now - r.born) / 700);
      const e = 1 - Math.pow(1 - k, 3);
      r.group.position.y = (1 - e) * goal.radius * 0.35;
      if (k >= 1) { r.born = 0; r.group.position.y = 0; }
    }
  }
  if (picked) { // (a picked piece breathes)
    const k = 0.38 + 0.22 * Math.sin(t * 4.2);
    for (const x of picked.meshes) for (const m of mats(x.userData.mg)) if (m.emissive) m.emissiveIntensity = k;
  }
  if (sizeBox && now > sizeUntil) { scene.remove(sizeBox); sizeBox.geometry.dispose(); sizeBox = null; }
  // pulled apart / put back together
  if (explodeK !== explodeGoal) {
    explodeK = explodeGoal > explodeK ? Math.min(explodeGoal, explodeK + dt / 1.1) : Math.max(explodeGoal, explodeK - dt / 0.9);
    const e = ease(explodeK);
    for (const r of parts.values()) placePieces(r, e);
  }
  // the camera, floor and target glide to the model's size
  const a = 1 - Math.exp(-dt * 3);
  controls.target.lerp(goal.target, a);
  const off = camera.position.clone().sub(controls.target);
  const d = off.length();
  if (goal.dir) { // a view he asked for: turn the camera there
    const dir = off.clone().normalize().lerp(goal.dir, Math.min(1, a * 1.6)).normalize();
    off.copy(dir).multiplyScalar(d);
    if (dir.angleTo(goal.dir) < 0.01) goal.dir = null;
  }
  const want = goal.dist * goal.zoom;
  if (!userFrame || d > want * 1.6 || d < want * 0.35) off.setLength(d + (want - d) * a);
  camera.position.copy(controls.target).add(off);
  floor.position.y += (goal.floorY - floor.position.y) * a;
  floor.scale.setScalar(floor.scale.x + (goal.radius * 9 - floor.scale.x) * a);
  tiltStep(dt);
  controls.update();
  placeCut();
  renderer.render(scene, camera);
  drawLabels();
  // a phone that struggles: draw with fewer pixels rather than stutter
  slow = dt > 0.045 ? slow + dt : Math.max(0, slow - dt * 0.5);
  if (slow > 2 && pixelRatio > 1) { pixelRatio = Math.max(1, pixelRatio - 0.25); renderer.setPixelRatio(pixelRatio); resize(); slow = 0; }
}

function pause() { running = false; }
function resume() { if (running) return; running = true; last = performance.now(); requestAnimationFrame(tick); }

// ------------------------------------------------------------------ messages from Jarvis (through the outer page)

window.addEventListener('message', (e) => {
  if (e.source !== window.parent) return;
  const m = e.data;
  if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
  switch (m.t) {
    case 'part': queue = queue.then(() => runPart(m)).catch((x) => post({ t: 'warn', id: cleanId(m.id), msg: String(x) })); break;
    case 'remove': queue = queue.then(() => { removePart(cleanId(m.id)); frameAll(false); stats(); }); break;
    case 'reset': queue = queue.then(reset); break;
    case 'frame': goal.zoom = 1; goal.dir = null; frameAll(true); break;
    case 'inset':
      inset.top = Math.max(0, Math.min(0.4, +m.top || 0));
      inset.bottom = Math.max(0, Math.min(0.6, +m.bottom || 0));
      applyInset();
      frameAll(false);
      break;
    case 'spin': spinWanted = !!m.on; controls.autoRotate = spinWanted; break;
    case 'explode':
      explodeGoal = m.on ? 1 : 0;
      labelsDirty = true;
      controls.autoRotate = m.on ? false : spinWanted; // (still while pulled apart: the names stay put)
      if (m.on && m.turn !== false) view(sideOfTravel());
      frameAll(false);
      break;
    case 'view': view(String(m.dir)); break;
    case 'zoom':
      goal.zoom = Math.max(0.3, Math.min(3, goal.zoom * (m.in ? 0.65 : 1 / 0.65)));
      userFrame = false;
      break;
    case 'light': light(String(m.mode)); break;
    case 'glow': { // (Jarvis points at a part: an answer about it)
      const r = parts.get(cleanId(m.id || ''));
      if (!r) { unpick(); break; }
      const want = typeof m.label === 'string' ? m.label : '';
      glow(r, objsFor(r, want), want || r.label || r.id);
      break;
    }
    case 'unpick': unpick(); break;
    case 'pickpart': { // 📋 a row in his list: that whole part
      const r = parts.get(cleanId(m.id || ''));
      if (!r) break;
      if (isolated && isolated.rec !== r) isolate(false);
      glow(r, [r.group], r.label || r.id);
      post({ t: 'pick', id: r.id, label: r.label || r.id });
      break;
    }
    case 'isolate': isolate(!!m.on, String(m.mode || 'ghost')); break;
    case 'xray': setXray(!!m.on); break;
    case 'hide': { const n = hide(!!m.on, typeof m.match === 'string' ? m.match.toLowerCase() : ''); post({ t: 'hid', id: '', label: n + '/' + hiddenList.length }); break; }
    case 'showall':
      isolated = null; reiso = null; xray = false; hiddenList.length = 0; post({ t: 'view', id: '', label: '' });
      if (cut.on) { cut.on = false; cut.version++; }
      controls.autoRotate = spinWanted;
      look(); labelsDirty = true; frameAll(false);
      break;
    case 'cut': setCut(m.on === undefined ? undefined : !!m.on, m.k); break;
    case 'measure': measure(); break;
    case 'replay': replay(); break;
    case 'tilt':
      tilt.on = !!m.on;
      if (tilt.on) { tilt.x = Math.max(-0.9, Math.min(0.9, +m.x || 0)); tilt.y = Math.max(-0.6, Math.min(0.6, +m.y || 0)); controls.autoRotate = false; }
      else controls.autoRotate = spinWanted && !cut.on;
      break;
    case 'snap': snap(m.req, !!m.clean); break;
    case 'stl': stl(m.req); break;
    case 'pause': pause(); break;
    case 'resume': resume(); break;
    default: break;
  }
});

// ------------------------------------------------------------------ 👆 a tap picks a piece: it glows, and Jarvis is told its part and name
// (A4) One "look" decides every mesh's material: its own, glowing (picked), or a see-through blue ghost (🔍 one part alone,
// 🩻 X-ray); 🙈 hidden pieces; 🔪 the cut. Nothing in a part's own materials is changed except the cut's clipping.

const ray = new THREE.Raycaster();
const tapAt = new THREE.Vector2();
let downAt = null, downs = 0;
let picked = null;   // { rec, objs, meshes: Set, label }
let repick = null;   // (the picked part is being written again: { id, label })
let isolated = null; // 🔍 { rec, objs, meshes: Set, label, mode: 'ghost' | 'hide' }
let reiso = null;    // (the isolated part is being written again: { id, label, mode })
let xray = false;
const hiddenList = []; // 🙈 [{ id, label }] ('' label: the whole part)
const cut = { on: false, k: 0.5, plane: new THREE.Plane(new THREE.Vector3(1, 0, 0), 0), version: 0 };
renderer.localClippingEnabled = true;
const ghostFill = new THREE.MeshBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.07, depthWrite: false, side: THREE.DoubleSide });
const ghostLine = new THREE.LineBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.38, depthWrite: false });
const SHARED = new Set([ghostFill, ghostLine]);

canvas.addEventListener('pointerdown', (e) => { downs++; downAt = downs === 1 ? { x: e.clientX, y: e.clientY, t: performance.now() } : null; });
canvas.addEventListener('pointercancel', () => { downs = Math.max(0, downs - 1); downAt = null; });
canvas.addEventListener('pointerup', (e) => {
  downs = Math.max(0, downs - 1);
  const d = downAt;
  downAt = null;
  if (!d || performance.now() - d.t > 450 || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 10) return; // (a drag or a pinch turns / zooms)
  pickAt(e.clientX, e.clientY);
});

function shown(o) { for (let x = o; x; x = x.parent) if (!x.visible) return false; return true; }
function inside(o, root) { for (let x = o; x; x = x.parent) if (x === root) return true; return false; }
function meshesOf(objs) { const m = new Set(); for (const o of objs) o.traverse((x) => { if (x.isMesh && !x.userData.isEdge) m.add(x); }); return m; }

/** The pieces a name stands for in a part (the whole part for one named from its status line). */
function objsFor(rec, label, whole) {
  if (whole || rec.auto || !label) return [rec.group];
  const ps = rec.pieces.filter((pc) => pc.label === label);
  return ps.length ? ps.map((pc) => pc.obj) : [rec.group];
}

function pickAt(x, y) {
  const b = canvas.getBoundingClientRect();
  tapAt.set(((x - b.left) / b.width) * 2 - 1, -((y - b.top) / b.height) * 2 + 1);
  ray.setFromCamera(tapAt, camera);
  const hit = ray.intersectObject(model, true).find((h) => h.object.isMesh && !h.object.userData.isEdge && shown(h.object)
    && (!isolated || isolated.meshes.has(h.object))); // (alone: only that part answers a tap)
  if (!hit) { if (picked) { unpick(); post({ t: 'pick', id: '' }); } return; }
  for (const rec of parts.values()) {
    if (!inside(hit.object, rec.group)) continue;
    const pc = rec.auto ? null : rec.pieces.find((p) => inside(hit.object, p.obj));
    const label = rec.auto || !pc || !pc.label ? (rec.label || rec.id) : pc.label;
    const objs = pc && pc.label && !rec.auto ? objsFor(rec, pc.label) : pc && !rec.auto ? [pc.obj] : [rec.group];
    if (picked && picked.rec === rec && picked.label === label) { unpick(); post({ t: 'pick', id: '' }); return; } // (tapped again: let go)
    glow(rec, objs, label);
    post({ t: 'pick', id: rec.id, label: label });
    return;
  }
}

function glow(rec, objs, label) {
  picked = { rec, objs, meshes: meshesOf(objs), label: label || '' };
  look();
}

function unpick() {
  if (!picked) return;
  picked = null;
  look();
}

function glowOf(x) {
  if (!x.userData.mg) {
    const lit = (m) => {
      const c = m.clone();
      if (c.color) c.color = c.color.clone().lerp(new THREE.Color(0x2aa8ff), c.emissive ? 0.35 : 0.45); // (a white part shows it too)
      if (c.emissive) { c.emissive = new THREE.Color(0x2aa8ff); c.emissiveIntensity = 0.45; }
      return c;
    };
    x.userData.mg = Array.isArray(x.userData.m0) ? x.userData.m0.map(lit) : lit(x.userData.m0);
  }
  return x.userData.mg;
}

function hiddenPiece(rec, pc) {
  for (const h of hiddenList) if (h.id === rec.id && (!h.label || rec.auto || pc.label === h.label)) return true;
  return false;
}

function mats(m) { return m == null ? [] : Array.isArray(m) ? m : [m]; }

/** The cut's clipping on a material (its own side kept to put back). */
function clipped(m) {
  for (const x of mats(m)) {
    if (x.userData.cutV === cut.version) continue;
    x.userData.cutV = cut.version;
    if (x.userData.side0 === undefined) x.userData.side0 = x.side;
    x.clippingPlanes = cut.on ? [cut.plane] : null;
    x.clipShadows = true;
    x.side = cut.on ? THREE.DoubleSide : x.userData.side0; // (the inside of a cut shell shows)
    x.needsUpdate = true;
  }
}

/** Every mesh gets what it should look like now. */
function look() {
  const iso = isolated ? isolated.meshes : null, pick = picked ? picked.meshes : null;
  clipped(ghostFill);
  clipped(ghostLine);
  for (const r of parts.values()) {
    for (const pc of r.pieces) {
      const hid = hiddenPiece(r, pc);
      pc.obj.traverse((x) => {
        if (!x.isMesh || x.userData.isEdge) return;
        if (x.userData.m0 === undefined) { x.userData.m0 = x.material; x.userData.v0 = x.visible; x.userData.s0 = x.castShadow; }
        const solid = iso ? iso.has(x) : !xray || (pick && pick.has(x));
        const gone = (iso && !iso.has(x) && isolated.mode === 'hide') || (x === pc.obj && hid);
        x.material = !solid ? ghostFill : pick && pick.has(x) && !iso ? glowOf(x) : x.userData.m0; // (alone: its own colours)
        clipped(x.userData.m0);
        if (x.userData.mg) clipped(x.userData.mg);
        x.visible = !gone && x.userData.v0;
        x.castShadow = solid && x.userData.s0;
        let e = x.userData.edges;
        if (!solid && !e && !x.isInstancedMesh && x.geometry) {
          try {
            e = new THREE.LineSegments(new THREE.EdgesGeometry(x.geometry, 28), ghostLine);
            e.userData.isEdge = true;
            e.raycast = () => {};
            x.add(e);
            x.userData.edges = e;
          } catch (err) { e = null; }
        }
        if (e) e.visible = !solid;
      });
      if (!pc.obj.isMesh) { if (pc.obj.userData.gv0 === undefined) pc.obj.userData.gv0 = pc.obj.visible; pc.obj.visible = !hid && pc.obj.userData.gv0; }
    }
  }
}

// ------------------------------------------------------------------ 🔍 one part alone, 🩻 X-ray, 🙈 hide, 🔪 cut (A4, ₹0)

function isolate(on, mode) {
  if (!on) { isolated = null; reiso = null; look(); labelsDirty = true; frameAll(false); post({ t: 'view', id: '', label: '' }); return; }
  if (!picked) { post({ t: 'view', id: '', label: 'nothing-picked' }); return; }
  isolated = { rec: picked.rec, objs: picked.objs, meshes: picked.meshes, label: picked.label, mode: mode === 'hide' ? 'hide' : 'ghost' };
  controls.autoRotate = spinWanted;
  goal.zoom = 1;
  goal.dir = null;
  userFrame = false;
  labelsDirty = true;
  look();
  frameAll(false);
  post({ t: 'view', id: isolated.rec.id, label: isolated.label });
}

function setXray(on) { xray = !!on; look(); }

/** 🙈 hide / show: the picked piece, or a part by a word in its id or name ("roof" / "పైకప్పు"). */
function hide(on, match) {
  if (!on) {
    if (match) { for (let i = hiddenList.length - 1; i >= 0; i--) if (matches(hiddenList[i], match)) hiddenList.splice(i, 1); }
    else hiddenList.length = 0;
    look();
    frameAll(false);
    return 0;
  }
  let n = 0;
  if (match) {
    for (const r of parts.values()) if (r.id.includes(match) || (r.label || '').toLowerCase().includes(match)) { hiddenList.push({ id: r.id, label: '' }); n++; }
  } else if (picked) {
    hiddenList.push({ id: picked.rec.id, label: picked.rec.auto || picked.objs[0] === picked.rec.group ? '' : picked.label });
    n = 1;
    picked = null;
    post({ t: 'pick', id: '' });
  }
  look();
  frameAll(false);
  return n;
}
function matches(h, match) { const r = parts.get(h.id); return h.id.includes(match) || (r && (r.label || '').toLowerCase().includes(match)); }

function setCut(on, k) {
  if (on !== undefined) cut.on = !!on;
  if (k !== undefined && isFinite(+k)) cut.k = Math.max(0, Math.min(1, +k));
  cut.version++;
  if (cut.on) { controls.autoRotate = false; }
  else controls.autoRotate = spinWanted && !isolated;
  look();
}

/** 🔪 The cut faces the camera: the near half of the model is taken off, so its inside shows. */
const fwd = new THREE.Vector3();
function placeCut() {
  if (!cut.on) return;
  const b = new THREE.Box3();
  for (const r of parts.values()) if (r.box) b.union(r.box);
  if (b.isEmpty()) return;
  fwd.copy(controls.target).sub(camera.position);
  fwd.y = 0;
  if (fwd.lengthSq() < 1e-6) fwd.set(0, 0, -1);
  fwd.normalize();
  const mid = b.getCenter(new THREE.Vector3());
  const half = Math.max(0.5, b.getSize(new THREE.Vector3()).length() / 2);
  const at = mid.clone().addScaledVector(fwd, (cut.k - 0.5) * 2 * half);
  cut.plane.setFromNormalAndCoplanarPoint(fwd, at); // (what is nearer the camera than the plane is clipped)
}

// ------------------------------------------------------------------ 📏 size, 🎬 the build played again, 📱 tilt

let sizeBox = null, sizeUntil = 0;
function measure() {
  const b = new THREE.Box3();
  let id = '', label = '';
  if (picked) {
    id = picked.rec.id;
    label = picked.label;
    const asm = picked.objs[0] === picked.rec.group ? [picked.rec.box] : picked.rec.pieces.filter((pc) => picked.objs.includes(pc.obj)).map((pc) => pc.box);
    for (const x of asm) if (x) b.union(x);
  } else {
    for (const r of parts.values()) if (r.box) b.union(r.box);
  }
  if (b.isEmpty()) return;
  const s = b.getSize(new THREE.Vector3());
  post({ t: 'size', id, label, w: Math.round(s.x * 10) / 10, h: Math.round(s.y * 10) / 10, d: Math.round(s.z * 10) / 10 });
  if (sizeBox) { scene.remove(sizeBox); sizeBox.geometry.dispose(); }
  const now = new THREE.Box3();
  if (picked) for (const o of picked.objs) now.union(new THREE.Box3().setFromObject(o)); else now.copy(modelBox());
  sizeBox = new THREE.Box3Helper(now, 0x4fd2ff);
  scene.add(sizeBox);
  sizeUntil = performance.now() + 8000;
}

let replayAt = 0;
function replay() {
  const list = [...parts.values()];
  if (!list.length) return;
  list.forEach((r) => { r.group.visible = false; r.born = 0; });
  replayAt = 0;
  list.forEach((r, i) => setTimeout(() => {
    if (parts.get(r.id) !== r) return;
    r.group.visible = true;
    r.born = performance.now();
    post({ t: 'replay', id: r.id, label: String(i + 1) + '/' + list.length });
  }, 350 + i * 520));
}

const tilt = { on: false, x: 0, y: 0, ax: 0, ay: 0 }; // (wanted, and applied so far)
function tiltStep(dt) {
  if (!tilt.on && Math.abs(tilt.ax) < 1e-4 && Math.abs(tilt.ay) < 1e-4) return;
  const gx = tilt.on ? tilt.x : 0, gy = tilt.on ? tilt.y : 0;
  const a = 1 - Math.exp(-dt * 8);
  const nx = tilt.ax + (gx - tilt.ax) * a, ny = tilt.ay + (gy - tilt.ay) * a;
  const off = camera.position.clone().sub(controls.target);
  off.applyAxisAngle(new THREE.Vector3(0, 1, 0), -(nx - tilt.ax)); // (around)
  const side = new THREE.Vector3(0, 1, 0).cross(off).normalize();
  const turned = off.clone().applyAxisAngle(side, -(ny - tilt.ay)); // (up / down, never over the top)
  const el = Math.asin(Math.max(-1, Math.min(1, turned.y / turned.length())));
  if (el > -0.2 && el < 1.45) off.copy(turned);
  camera.position.copy(controls.target).add(off);
  tilt.ax = nx;
  tilt.ay = ny;
}

// ------------------------------------------------------------------ 📸 a picture, 🖨️ a print file: only when Jarvis asks (A4)

function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** What he sees (clean: his own colours only, no glow, ghosts or cut), on the studio backdrop, as a JPEG. */
function snap(req, clean) {
  const keep = { picked, isolated, xray, cutOn: cut.on };
  if (clean) { picked = null; isolated = null; xray = false; if (cut.on) { cut.on = false; cut.version++; } look(); }
  if (sizeBox) sizeBox.visible = false;
  renderer.render(scene, camera);
  if (sizeBox) sizeBox.visible = true;
  // (only the part of the stage he sees between the bars)
  const sy = Math.round(canvas.height * inset.top), sh = Math.max(1, Math.round(canvas.height * (1 - inset.top - inset.bottom)));
  const w = canvas.width, h = sh, k = Math.min(1, 1600 / Math.max(w, h));
  const c = document.createElement('canvas');
  c.width = Math.round(w * k);
  c.height = Math.round(h * k);
  const g = c.getContext('2d');
  const r = g.createRadialGradient(c.width / 2, c.height * 0.46, 0, c.width / 2, c.height * 0.46, Math.max(c.width, c.height) * 0.62);
  r.addColorStop(0, '#2b3342');
  r.addColorStop(0.46, '#151a23');
  r.addColorStop(1, '#07080b');
  g.fillStyle = r;
  g.fillRect(0, 0, c.width, c.height);
  g.drawImage(canvas, 0, sy, w, sh, 0, 0, c.width, c.height);
  const url = c.toDataURL('image/jpeg', 0.92);
  if (clean) { picked = keep.picked; isolated = keep.isolated; xray = keep.xray; if (keep.cutOn) { cut.on = true; cut.version++; } look(); }
  post({ t: 'file', req: String(req || ''), kind: 'jpg', data: url.slice(url.indexOf(',') + 1) });
}

/** 🖨️ The solid parts he sees, put together, as a binary STL: Z up, millimetres, the longest side 100 mm. */
function stl(req) {
  const e = ease(explodeK);
  for (const r of parts.values()) placePieces(r, 0); // (assembled)
  model.updateMatrixWorld(true);
  const tris = [];
  const v = [new THREE.Vector3(), new THREE.Vector3(), new THREE.Vector3()];
  const inst = new THREE.Matrix4(), world = new THREE.Matrix4();
  const box = new THREE.Box3();
  for (const r of parts.values()) {
    if (!r.group.visible) continue;
    r.group.traverse((x) => {
      if (!x.isMesh || x.userData.isEdge || !x.geometry || !shown(x)) return;
      if (isolated && !isolated.meshes.has(x)) return;
      const pos = x.geometry.attributes.position, idx = x.geometry.index;
      if (!pos) return;
      const n = x.isInstancedMesh ? x.count : 1;
      for (let j = 0; j < n; j++) {
        world.copy(x.matrixWorld);
        if (x.isInstancedMesh) { x.getMatrixAt(j, inst); world.multiply(inst); }
        const count = idx ? idx.count : pos.count;
        for (let i = 0; i + 2 < count; i += 3) {
          for (let q = 0; q < 3; q++) { v[q].fromBufferAttribute(pos, idx ? idx.getX(i + q) : i + q).applyMatrix4(world); box.expandByPoint(v[q]); }
          tris.push(v[0].x, v[0].y, v[0].z, v[1].x, v[1].y, v[1].z, v[2].x, v[2].y, v[2].z);
        }
      }
    });
  }
  for (const r of parts.values()) placePieces(r, e);
  const n = tris.length / 9;
  if (!n || box.isEmpty()) { post({ t: 'file', req: String(req || ''), kind: 'stl', data: '' }); return; }
  const size = box.getSize(new THREE.Vector3()), k = 100 / Math.max(size.x, size.y, size.z);
  const buf = new ArrayBuffer(84 + n * 50), dv = new DataView(buf);
  const head = 'Jarvis workshop model (original design) - mm';
  for (let i = 0; i < 80; i++) dv.setUint8(i, i < head.length ? head.charCodeAt(i) : 32);
  dv.setUint32(80, n, true);
  const a = new THREE.Vector3(), b2 = new THREE.Vector3(), c2 = new THREE.Vector3(), nn = new THREE.Vector3();
  let o = 84;
  for (let t = 0; t < n; t++) {
    const P = (q) => new THREE.Vector3((tris[t * 9 + q * 3] - box.min.x) * k, -(tris[t * 9 + q * 3 + 2] - box.max.z) * k, (tris[t * 9 + q * 3 + 1] - box.min.y) * k);
    a.copy(P(0)); b2.copy(P(1)); c2.copy(P(2)); // (three's Y up → the printer's Z up; on the bed at z 0)
    nn.subVectors(b2, a).cross(new THREE.Vector3().subVectors(c2, a)).normalize();
    for (const w of [nn, a, b2, c2]) { dv.setFloat32(o, w.x, true); dv.setFloat32(o + 4, w.y, true); dv.setFloat32(o + 8, w.z, true); o += 12; }
    dv.setUint16(o, 0, true);
    o += 2;
  }
  post({ t: 'file', req: String(req || ''), kind: 'stl', data: b64(new Uint8Array(buf)), tris: n,
    w: Math.round(size.x * k), h: Math.round(size.z * k), d: Math.round(size.y * k) });
}

canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); post({ t: 'warn', id: 'stage', msg: 'webgl context lost' }); });

resize();
jump();
requestAnimationFrame(tick);
post({ t: 'ready' });
