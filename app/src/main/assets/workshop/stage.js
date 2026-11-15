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
controls.addEventListener('start', () => { turning = true; });
controls.addEventListener('end', () => { turning = false; });
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
// (E) each material remembers which kind it is ("metal steel", "rubber"...): the "how it works" show and the build guide are told
for (const k of ['metal', 'plastic', 'rubber', 'paint', 'glass', 'wood', 'ceramic', 'fabric', 'glow', 'screen']) {
  const make = kit[k];
  kit[k] = function (a, b) {
    const m = make.call(kit, a, b);
    m.userData.kind = k === 'metal' ? 'metal ' + (typeof a === 'string' ? a.slice(0, 16) : 'steel') : k;
    return m;
  };
}
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
    obj.userData.label = typeof o.label === 'string' ? te(o.label).slice(0, 60) : '';
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
    for (const m of [x.material, x.userData.m0, x.userData.mg, x.userData.p0]) for (const y of (m == null ? [] : Array.isArray(m) ? m : [m])) ms.add(y);
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
  if (paintList.length) applyPaints(rec); // (🎨 his own colours)
  look();
  frameAll(false);
  stats();
}

function stats() { post({ t: 'stats', parts: parts.size, tris: totalTris() }); }

function reset() {
  endTour();
  tape.on = false;
  tapeClear();
  if (guests.size) guestOff();
  paintList.length = 0;
  grabbed = null;
  controls.enabled = true;
  binList.length = 0;
  goneList.length = 0;
  handAnims.length = 0;
  binBox();
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
  if (tour.on && tour.close && tour.units.length) { // 🎓 the show looks closer at what it talks about (never so close the rest is lost)
    const k = ease(explodeGoal);
    for (const u of tour.units) for (const pc of u.pcs) if (pc.box) b.union(pc.box.clone().translate(pc.explode.clone().multiplyScalar(pc.home ? 0 : k)));
    if (!b.isEmpty()) {
      const want = tour.size * 0.5, have = b.getSize(new THREE.Vector3()).length();
      if (have < want) b.expandByScalar((want - have) / 3.4);
      return b;
    }
  }
  if (isolated) { // 🔍 alone: that part fills the view
    for (const o of isolated.objs) b.union(new THREE.Box3().setFromObject(o));
    if (!b.isEmpty()) return b;
  }
  const k = ease(explodeGoal);
  if (guests.size) { const gb = guestBox(); if (!gb.isEmpty()) b.union(gb.translate(guestGroup.position)); } // (🔀 the second model too)
  for (const r of parts.values()) {
    if (!r.box) continue;
    if (k === 0 || !r.pieces.some((pc) => pc.box)) { b.union(r.box); continue; }
    for (const pc of r.pieces) if (pc.box) b.union(pc.box.clone().translate(pc.explode.clone().multiplyScalar(k)));
  }
  return b;
}

// ------------------------------------------------------------------ pulled apart (an exploded view) with names

let explodeK = 0, explodeGoal = 0, labelsDirty = true, labelGroups = [];
let labelsOn = true, turning = false; // (🏷️ his on/off; the model being turned by his finger)
const ease = (k) => (k <= 0 ? 0 : k >= 1 ? 1 : k * k * (3 - 2 * k));

function placePieces(rec, e) {
  for (const pc of rec.pieces) {
    if (!pc.base) continue;
    pc.obj.position.copy(pc.base);
    if (!pc.home) pc.obj.position.addScaledVector(pc.explode, e); // (put back in its place by his hand: home)
    if (pc.drag) pc.obj.position.add(pc.drag); // (moved by his hand)
    if (pc.tourOff) pc.obj.position.add(pc.tourOff); // (🎓 the show slides it)
    if (pc.spinOff) pc.obj.position.add(pc.spinOff); // (🎓 the show turns it around its middle)
  }
}

/** A name in Telugu only: "మోటార్ (Motor)" → "మోటార్", "Motor (మోటార్)" → "మోటార్"; a name with no Telugu stays as it is. */
const TE = /[\u0C00-\u0C7F]/;
function te(raw) {
  const s = String(raw || '').trim();
  if (!TE.test(s)) return s;
  const outside = s.replace(/[(\[（][^)\]）]*[)\]）]/g, ' ').replace(/\s+/g, ' ').trim();
  if (TE.test(outside)) return outside.replace(/\s*[-–/|:]\s*[A-Za-z][A-Za-z0-9 .'-]*$/, '').replace(/^[A-Za-z][A-Za-z0-9 .'-]*\s*[-–/|:]\s*/, '').trim() || s;
  const inside = (s.match(/[(\[（][^)\]）]*[)\]）]/g) || []).map((x) => x.slice(1, -1).trim()).find((x) => TE.test(x));
  return inside || s;
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
      if (!r.box || (r.pieces.length && hiddenPiece(r, r.pieces[0]))) continue;
      const v = r.pieces.length ? r.pieces[0].explode.clone() : new THREE.Vector3();
      labelGroups.push({ text: r.label || r.id, rec: r, box: r.box.clone(), explode: v, el: null, w: 0, h: 0, center: r.box.getCenter(new THREE.Vector3()) });
      continue;
    }
    const byName = new Map();
    for (const pc of r.pieces) {
      if (!pc.label || !pc.box || hiddenPiece(r, pc)) continue;
      let g = byName.get(pc.label);
      if (!g) { g = { text: pc.label, rec: r, box: new THREE.Box3(), explode: pc.explode.clone(), el: null, w: 0, h: 0 }; byName.set(pc.label, g); }
      g.box.union(pc.box);
    }
    for (const g of byName.values()) { g.center = g.box.getCenter(new THREE.Vector3()); labelGroups.push(g); }
  }
  if (isolated) labelGroups = labelGroups.filter((g) => g.rec === isolated.rec && (isolated.objs[0] === g.rec.group || g.text === isolated.label || g.rec.auto));
  if (tour.on && tour.units.length) // (🎓 only the names the show talks about)
    labelGroups = labelGroups.filter((g) => tour.units.some((u) => u.rec === g.rec && (g.rec.auto || u.pcs.some((pc) => pc.label === g.text))));
  labelGroups = labelGroups.slice(0, 12);
  const box = document.getElementById('labels');
  const svg = document.getElementById('lines');
  for (const el of [...box.querySelectorAll('.lb')]) el.remove();
  while (svg.firstChild) svg.removeChild(svg.firstChild);
  for (const g of labelGroups) {
    const el = document.createElement('div');
    el.className = 'lb';
    el.textContent = g.text;
    el.addEventListener('click', () => { // (a name tapped: that piece is picked)
      if (parts.get(g.rec.id) !== g.rec) return;
      const lab = g.rec.auto ? (g.rec.label || g.rec.id) : g.text;
      glow(g.rec, objsFor(g.rec, g.rec.auto ? '' : g.text), lab);
      post({ t: 'pick', id: g.rec.id, label: lab });
    });
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
  const a = ease(explodeK), show = labelsOn ? Math.max(a, isolated || (tour.on && tour.units.length) ? 1 : 0) : 0;
  box.style.opacity = String(Math.max(0, (show - 0.35) / 0.65) * (turning || grabbed ? 0.22 : 1)); // (faint while he turns or holds)
  box.style.display = show < 0.35 ? 'none' : 'block'; // (hidden names never catch a tap)
  if (show < 0.35) return;
  if (labelsDirty) buildLabels();
  const w = window.innerWidth, h = window.innerHeight;
  const top = h * inset.top + 6, bottom = h * (1 - inset.bottom) - 6;
  const cols = { l: [], r: [] };
  for (const g of labelGroups) {
    const p0 = g.rec.auto ? g.rec.pieces[0] : g.rec.pieces.find((pc) => pc.label === g.text);
    tmpV.copy(g.center).addScaledVector(g.explode, p0 && p0.home ? 0 : a);
    if (p0 && p0.drag) tmpV.add(p0.drag);
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
  nightOn = night;
  backdrop();
}
let nightOn = false;
/** The page behind the model: the studio (day / night), or nothing at all over the room (📷 G). */
function backdrop() {
  document.documentElement.style.background = arOn ? 'transparent' : '';
  document.body.style.background = arOn ? 'transparent'
    : nightOn ? 'radial-gradient(ellipse 70% 60% at 50% 46%, #10151e 0%, #07090d 48%, #020304 100%)' : DAY.bg;
}

// The screen's own bars cover the top and bottom of the stage: the model is framed in the part that is left.
const inset = { top: 0, bottom: 0 };
function applyInset() {
  if (xr.on) return; // (📏 on the real floor the phone's AR gives the lens)
  const w = window.innerWidth || 1, h = window.innerHeight || 1;
  const shift = ((inset.bottom - inset.top) / 2) * h;
  if (Math.abs(shift) < 1) camera.clearViewOffset(); else camera.setViewOffset(w, h, 0, shift, w, h);
  camera.updateProjectionMatrix();
}

function frameAll(now) {
  if (xr.on) return;
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
  for (const r of guests.values()) { // (🔀 the second model moves the way it was made to)
    for (let i = r.ticks.length - 1; i >= 0; i--) { try { r.ticks[i](t, dt); } catch (e) { r.ticks.splice(i, 1); } }
  }
  if (picked) { // (a picked piece breathes)
    const k = 0.38 + 0.22 * Math.sin(t * 4.2);
    for (const x of picked.meshes) for (const m of mats(x.userData.mg)) if (m.emissive) m.emissiveIntensity = k;
  }
  if (tour.on) { // 🎓 what the show talks about breathes too; its pieces turn / slide; the arrows flow
    const k = 0.42 + 0.26 * Math.sin(t * 3.6);
    for (const x of tour.focus) for (const m of mats(x.userData.mg)) if (m.emissive) m.emissiveIntensity = k;
    tourTick(now, dt);
  }
  if (sizeBox && now > sizeUntil) { scene.remove(sizeBox); sizeBox.geometry.dispose(); sizeBox = null; }
  if (handAnims.length) handStep(now);
  // pulled apart / put back together
  if (explodeK !== explodeGoal) {
    explodeK = explodeGoal > explodeK ? Math.min(explodeGoal, explodeK + dt / 1.1) : Math.max(explodeGoal, explodeK - dt / 0.9);
    const e = ease(explodeK);
    for (const r of parts.values()) placePieces(r, e);
  }
  if (xr.on) { // 📏 on the real floor: the phone's AR moves the camera, nothing glides
    xrPlaceModel();
    renderer.render(scene, camera);
    drawLabels();
    tapeDraw();
    return;
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
  if (arOn) { catcher.position.y = floor.position.y + goal.radius * 0.002; catcher.scale.setScalar(goal.radius * 3); }
  tiltStep(dt);
  controls.update();
  placeCut();
  renderer.render(scene, camera);
  recFrame(); // (🎞️ a video being made takes this frame)
  drawLabels();
  tapeDraw();
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
      binBox();
      applyInset();
      frameAll(false);
      break;
    case 'spin': spinWanted = !!m.on; controls.autoRotate = spinWanted; break;
    case 'explode':
      explodeGoal = m.on ? 1 : 0;
      labelsDirty = true;
      handsOff(); // (moved or put back by hand: everything flies to its place)
      binBox();
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
    case 'snap': snap(m.req, !!m.clean, Math.max(0, Math.min(1600, +m.most || 0))); break;
    case 'labels': labelsOn = !!m.on; break;
    case 'bin': if (picked) toBin([unitOf(picked.rec, picked.label, picked.objs[0] === picked.rec.group)]); break;
    case 'binothers': binOthers(); break;
    case 'unbin': unbin(typeof m.id === 'string' ? m.id : '', typeof m.label === 'string' ? m.label : '', !!m.all); break;
    case 'binclear': binList.length = 0; look(); labelsDirty = true; binBox(); postBin(''); break;
    case 'gone':
      goneList.length = 0;
      if (Array.isArray(m.list)) for (const g of m.list.slice(0, 200)) if (g && typeof g.id === 'string') goneList.push({ id: g.id, label: typeof g.label === 'string' ? g.label : '' });
      look(); labelsDirty = true;
      break;
    case 'stl': stl(m.req); break;
    // (E, G) 🎓 the show, 📷 the room
    case 'describe': describe(m.req); break;
    case 'tour': tourStep(m.step); break;
    case 'tourend': endTour(); break;
    case 'ar': setAr(!!m.on); break;
    // (1–9) 🎨 colours, 📏 tape, ⚖️ weight, 🔀 a second model, 📐 drawing, 🎞️ video, ✋ hand, 📏 real floor
    case 'paint': setPaints(m.list); break;
    case 'paintname': paintName(m.req, m.words); break;
    case 'tape': setTape(!!m.on); break;
    case 'mass': massOf(m.req); break;
    case 'guest': if (Array.isArray(m.parts)) setGuest(m); break;
    case 'guestpos': placeGuest(m.where === 'top' ? 'top' : 'side'); break;
    case 'guestoff': guestQueue = guestQueue.then(guestOff); break;
    case 'ortho': ortho(m.req); break;
    case 'rec': if (m.on) recStart(m); else if (m.stop) recStop(); else recCancel(); break;
    case 'nudge': nudge(m); break;
    case 'xr': setXr(!!m.on); break;
    case 'xrpose': xrPose(m); break;
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
let downAt = null, downs = 0, holdTimer = 0;
let picked = null;   // { rec, objs, meshes: Set, label }
let repick = null;   // (the picked part is being written again: { id, label })
let isolated = null; // 🔍 { rec, objs, meshes: Set, label, mode: 'ghost' | 'hide' }
let reiso = null;    // (the isolated part is being written again: { id, label, mode })
let xray = false;
const hiddenList = []; // 🙈 [{ id, label }] ('' label: the whole part)
const binList = [];    // 🗑️ in the bin: [{ id, label, whole }] (₹0; back with one tap)
const goneList = [];   // taken off the model for good (he emptied the bin): [{ id, label }], kept with the saved model
const cut = { on: false, k: 0.5, plane: new THREE.Plane(new THREE.Vector3(1, 0, 0), 0), version: 0 };
renderer.localClippingEnabled = true;
const ghostFill = new THREE.MeshBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.07, depthWrite: false, side: THREE.DoubleSide });
const ghostLine = new THREE.LineBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.38, depthWrite: false });
const SHARED = new Set([ghostFill, ghostLine]);

canvas.addEventListener('pointerdown', (e) => {
  downs++;
  downAt = downs === 1 ? { x: e.clientX, y: e.clientY, t: performance.now() } : null;
  clearTimeout(holdTimer);
  if (downs === 1 && !xr.on && !tape.on) holdTimer = setTimeout(() => { if (downAt) startGrab(downAt.x, downAt.y); }, 450); // ✋ a hold grabs
});
let xrLastX = null;
canvas.addEventListener('pointermove', (e) => {
  if (grabbed) { moveGrab(e.clientX, e.clientY); return; }
  if (xr.on && downAt && downs === 1) { if (xrLastX != null) xr.yaw += (e.clientX - xrLastX) * 0.012; xrLastX = e.clientX; } // (📏 one finger turns it)
  if (downAt && Math.hypot(e.clientX - downAt.x, e.clientY - downAt.y) > 10) clearTimeout(holdTimer); // (a drag: turning)
});
canvas.addEventListener('pointercancel', () => { downs = Math.max(0, downs - 1); downAt = null; clearTimeout(holdTimer); if (grabbed) dropGrab(-1, -1); });
canvas.addEventListener('pointerup', (e) => {
  downs = Math.max(0, downs - 1);
  clearTimeout(holdTimer);
  const d = downAt;
  downAt = null;
  if (grabbed) { dropGrab(e.clientX, e.clientY); return; }
  xrLastX = null;
  if (!d || performance.now() - d.t > 450 || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 10) return; // (a drag or a pinch turns / zooms)
  if (tape.on) { tapeAt(e.clientX, e.clientY); return; } // (📏 the tape: a point on the model)
  if (xr.on && !xrOnModel(e.clientX, e.clientY)) { // (📏 a tap on the floor: the model goes there)
    const b = canvas.getBoundingClientRect();
    post({ t: 'xrtap', x: (e.clientX - b.left) / b.width, y: (e.clientY - b.top) / b.height });
    return;
  }
  pickAt(e.clientX, e.clientY);
});

function xrOnModel(x, y) {
  if (!xr.placed) return false;
  const b = canvas.getBoundingClientRect();
  tapAt.set(((x - b.left) / b.width) * 2 - 1, -((y - b.top) / b.height) * 2 + 1);
  ray.setFromCamera(tapAt, camera);
  return ray.intersectObject(model, true).some((h) => h.object.isMesh && !h.object.userData.isEdge && shown(h.object) && h.object !== xr.shadow);
}

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
  for (const list of [hiddenList, binList, goneList])
    for (const h of list) if (h.id === rec.id && (!h.label || rec.auto || pc.label === h.label)) return true;
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
  const sf = tour.on && tour.focus.size ? tour.focus : null; // (🎓 the show: what it talks about glows, the rest fades)
  clipped(ghostFill);
  clipped(ghostLine);
  for (const r of parts.values()) {
    for (const pc of r.pieces) {
      const hid = hiddenPiece(r, pc);
      pc.obj.traverse((x) => {
        if (!x.isMesh || x.userData.isEdge) return;
        if (x.userData.m0 === undefined) { x.userData.m0 = x.material; x.userData.v0 = x.visible; x.userData.s0 = x.castShadow; }
        const solid = sf ? !tour.dim || sf.has(x) : iso ? iso.has(x) : !xray || (pick && pick.has(x));
        const gone = (iso && !iso.has(x) && isolated.mode === 'hide') || (x === pc.obj && hid);
        x.material = !solid ? ghostFill : sf ? (sf.has(x) ? glowOf(x) : x.userData.m0)
          : pick && pick.has(x) && !iso ? glowOf(x) : x.userData.m0; // (alone: its own colours)
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

// ------------------------------------------------------------------ ✋ his hand: hold a piece, carry it, drop it in the 🗑️ bin,
// anywhere on the stage, or back in its place (the 🧩 game). Nothing in the model's code changes; ₹0.

let grabbed = null; // { rec, pcs, label, whole, plane, offset, center0, start: [Vector3], home: Box3, helper }
const handAnims = [];  // [{ pcs, from: [Vector3], t0, dur, kind: 'back' | 'bin', done }]
const binEl = document.getElementById('bin');
if (binEl) binEl.addEventListener('click', () => post({ t: 'bintap' }));

function unitOf(rec, label, whole) {
  const all = whole || rec.auto || !label;
  return { rec, label: all ? '' : label, whole: all, pcs: all ? rec.pieces.slice() : rec.pieces.filter((pc) => pc.label === label) };
}

function startGrab(x, y) {
  const b = canvas.getBoundingClientRect();
  tapAt.set(((x - b.left) / b.width) * 2 - 1, -((y - b.top) / b.height) * 2 + 1);
  ray.setFromCamera(tapAt, camera);
  const hit = ray.intersectObject(model, true).find((h) => h.object.isMesh && !h.object.userData.isEdge && shown(h.object)
    && (!isolated || isolated.meshes.has(h.object)));
  if (!hit) return;
  if (hit.object.userData.guest) { // 🔀 the second model: a held finger moves all of it over the floor
    const base = new THREE.Plane(new THREE.Vector3(0, 1, 0), -hit.point.y);
    const at0 = new THREE.Vector3();
    if (!ray.ray.intersectPlane(base, at0)) return;
    grabbed = { guest: true, plane: base, at0, start: guestGroup.position.clone() };
    controls.enabled = false;
    downAt = null;
    post({ t: 'grab', id: 'guest', label: guestTitle });
    return;
  }
  let rec = null, pc = null;
  for (const r of parts.values()) if (inside(hit.object, r.group)) { rec = r; pc = r.auto ? null : r.pieces.find((p) => inside(hit.object, p.obj)); break; }
  if (!rec || !rec.pieces.length) return;
  const u = unitOf(rec, pc && pc.label ? pc.label : '', !pc || !pc.label);
  if (!u.pcs.length) return;
  const box = new THREE.Box3();
  for (const p of u.pcs) box.union(new THREE.Box3().setFromObject(p.obj));
  const center0 = box.getCenter(new THREE.Vector3());
  const n = camera.getWorldDirection(new THREE.Vector3());
  const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(n, center0);
  const at = new THREE.Vector3();
  if (!ray.ray.intersectPlane(plane, at)) return;
  const home = new THREE.Box3();
  for (const p of u.pcs) if (p.box) home.union(p.box); // (where it sits in the whole model)
  grabbed = { rec, pcs: u.pcs, label: u.whole ? (rec.label || rec.id) : u.label, whole: u.whole, plane, offset: center0.clone().sub(at), center0,
    start: u.pcs.map((p) => (p.drag ? p.drag.clone() : new THREE.Vector3())), home, helper: null, near: false };
  for (const p of u.pcs) p.home = false;
  if (explodeK > 0.5 && !home.isEmpty()) { grabbed.helper = new THREE.Box3Helper(home, 0x4fd2ff); grabbed.helper.material.transparent = true; grabbed.helper.material.opacity = 0.35; scene.add(grabbed.helper); }
  controls.enabled = false;
  downAt = null;
  glow(rec, u.whole ? [rec.group] : u.pcs.map((p) => p.obj), grabbed.label);
  post({ t: 'pick', id: rec.id, label: grabbed.label });
  post({ t: 'grab', id: rec.id, label: grabbed.label });
  binBox();
}

function moveGrab(x, y) {
  const g = grabbed;
  const b = canvas.getBoundingClientRect();
  tapAt.set(((x - b.left) / b.width) * 2 - 1, -((y - b.top) / b.height) * 2 + 1);
  ray.setFromCamera(tapAt, camera);
  const at = new THREE.Vector3();
  if (!ray.ray.intersectPlane(g.plane, at)) return;
  if (g.guest) { guestGroup.position.set(g.start.x + at.x - g.at0.x, g.start.y, g.start.z + at.z - g.at0.z); return; }
  const delta = at.add(g.offset).sub(g.center0);
  g.pcs.forEach((p, i) => { p.drag = g.start[i].clone().add(delta); });
  placePieces(g.rec, ease(explodeK));
  if (binEl) binEl.classList.toggle('hot', overBin(x, y));
  if (g.helper) { // (near its place: the outline brightens)
    const near = nearHome(g);
    if (near !== g.near) { g.near = near; g.helper.material.opacity = near ? 0.95 : 0.35; }
  }
}

function overBin(x, y) {
  if (!binEl || binEl.style.display === 'none') return false;
  const r = binEl.getBoundingClientRect();
  return x >= r.left - 18 && x <= r.right + 18 && y >= r.top - 18 && y <= r.bottom + 18;
}

/** Its centre now against where it sits in the whole model (a few % of the model's size counts as "in place"). */
function nearHome(g) {
  if (g.home.isEmpty()) return false;
  const now = new THREE.Box3();
  for (const p of g.pcs) now.union(new THREE.Box3().setFromObject(p.obj));
  const all = new THREE.Box3();
  for (const r of parts.values()) if (r.box) all.union(r.box);
  const big = Math.max(1, all.getSize(new THREE.Vector3()).length());
  return now.getCenter(new THREE.Vector3()).distanceTo(g.home.getCenter(new THREE.Vector3())) < Math.max(1.2, big * 0.07);
}

function dropGrab(x, y) {
  const g = grabbed;
  grabbed = null;
  controls.enabled = true;
  if (g.guest) { guestPlace = 'free'; post({ t: 'moved', id: 'guest', label: guestTitle }); return; }
  if (g.helper) { scene.remove(g.helper); g.helper.geometry.dispose(); }
  if (binEl) binEl.classList.remove('hot');
  if (x >= 0 && overBin(x, y)) { toBin([{ rec: g.rec, label: g.whole ? '' : g.label, whole: g.whole, pcs: g.pcs }]); binBox(); return; }
  if (explodeK > 0.5 && nearHome(g)) { // 🧩 back in its place: it clicks in
    for (const p of g.pcs) { p.drag = null; p.home = true; }
    placePieces(g.rec, ease(explodeK));
    const c = gameCount();
    post({ t: 'snap', id: g.rec.id, label: c.done + '/' + c.all });
  } else post({ t: 'moved', id: g.rec.id, label: g.label }); // (left where he put it)
  labelsDirty = true;
  binBox();
}

/** 🧩 How many pieces are in their place (a piece that never moves when pulled apart counts as placed). */
function gameCount() {
  let all = 0, done = 0;
  const seen = new Set();
  for (const r of parts.values()) {
    for (const pc of r.pieces) {
      const key = r.id + '|' + (r.auto || !pc.label ? '' : pc.label);
      if (seen.has(key) || hiddenPiece(r, pc)) continue;
      seen.add(key);
      all++;
      const still = pc.explode.lengthSq() < 1e-6 && !pc.drag;
      if (pc.home && !pc.drag || still) done++;
    }
  }
  return { all, done };
}

/** 🗑️ Into the bin: it shrinks away there; one tap on the bin brings it back. */
function toBin(units) {
  let n = 0;
  units.forEach((u, i) => {
    if (!u.pcs.length || binList.some((x) => x.id === u.rec.id && x.label === u.label)) return;
    n++;
    setTimeout(() => {
      if (parts.get(u.rec.id) !== u.rec) return;
      const s0 = u.pcs.map((p) => p.obj.scale.clone()); // (its own size, put back after)
      handAnims.push({ pcs: u.pcs, s0, t0: performance.now(), dur: 280, kind: 'bin', done: () => {
        binList.push({ id: u.rec.id, label: u.label, whole: !!u.whole, name: u.whole ? (u.rec.label || u.rec.id) : u.label });
        u.pcs.forEach((p, j) => p.obj.scale.copy(s0[j]));
        if (picked && picked.rec === u.rec && (u.whole || picked.label === u.label)) { picked = null; post({ t: 'pick', id: '' }); }
        look(); labelsDirty = true; binBox(); postBin(u.whole ? (u.rec.label || u.rec.id) : u.label);
      } });
    }, i * 70);
  });
  return n;
}

/** 🗑️ Everything but the picked piece goes in the bin (his "మిగతావి బిన్‌లో వేయి"). */
function binOthers() {
  if (!picked) { post({ t: 'bin', id: '', label: 'nothing-picked', list: [] }); return; }
  const units = [];
  for (const r of parts.values()) {
    if (r !== picked.rec) { if (!r.pieces.some((pc) => hiddenPiece(r, pc))) units.push(unitOf(r, '', true)); continue; }
    if (r.auto || picked.objs[0] === r.group) continue;
    const names = new Set(r.pieces.map((pc) => pc.label).filter((l) => l && l !== picked.label));
    for (const l of names) units.push(unitOf(r, l, false));
  }
  toBin(units);
}

/** Back from the bin: it shows where it was dropped and flies to its place. */
function unbin(id, label, all) {
  const back = [];
  for (let i = binList.length - 1; i >= 0; i--) {
    const b = binList[i];
    if (all || (b.id === id && b.label === label)) { back.push(b); binList.splice(i, 1); }
  }
  if (!back.length) return;
  look();
  for (const b of back) {
    const r = parts.get(b.id);
    if (!r) continue;
    const pcs = b.whole ? r.pieces : r.pieces.filter((pc) => pc.label === b.label);
    for (const p of pcs) p.home = false;
    handAnims.push({ pcs, from: pcs.map((p) => (p.drag ? p.drag.clone() : new THREE.Vector3())), t0: performance.now(), dur: 450, kind: 'back' });
  }
  labelsDirty = true;
  binBox();
  postBin('');
}

/** Everything his hand moved or put back goes to its place again (pulled apart / put together). */
function handsOff() {
  for (const r of parts.values()) {
    const pcs = r.pieces.filter((p) => p.drag || p.home);
    if (!pcs.length) continue;
    for (const p of pcs) p.home = false;
    handAnims.push({ pcs, from: pcs.map((p) => (p.drag ? p.drag.clone() : new THREE.Vector3())), t0: performance.now(), dur: 450, kind: 'back' });
  }
}

function handStep(now) {
  for (let i = handAnims.length - 1; i >= 0; i--) {
    const h = handAnims[i];
    const k = Math.min(1, (now - h.t0) / h.dur), e2 = 1 - Math.pow(1 - k, 3);
    if (h.kind === 'bin') h.pcs.forEach((p, j) => p.obj.scale.copy(h.s0[j]).multiplyScalar(Math.max(0.02, 1 - e2)));
    else h.pcs.forEach((p, j) => { p.drag = k >= 1 ? null : h.from[j].clone().multiplyScalar(1 - e2); });
    const recs = new Set(h.pcs.map((p) => p.obj.parent));
    for (const r of parts.values()) if (recs.has(r.group)) placePieces(r, ease(explodeK));
    if (k >= 1) { handAnims.splice(i, 1); if (h.done) h.done(); }
  }
}

function postBin(last) {
  post({ t: 'bin', id: '', label: last || '', list: binList.map((b) => ({ id: b.id, label: b.label, name: b.name || b.label, whole: !!b.whole })) });
}

/** The bin shows while pulled apart, while he holds a piece, or while something is in it. */
function binBox() {
  if (!binEl) return;
  const on = parts.size > 0 && ((explodeGoal > 0 && !tour.on) || !!grabbed || binList.length > 0); // (not in the way of a 🎓 show)
  binEl.style.display = on ? 'flex' : 'none';
  binEl.style.bottom = Math.round(window.innerHeight * inset.bottom + 14) + 'px';
  const n = document.getElementById('binN');
  if (n) n.textContent = binList.length ? String(binList.length) : '';
}

// ------------------------------------------------------------------ 📸 a picture, 🖨️ a print file: only when Jarvis asks (A4)

function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** What he sees (clean: his own colours only, no glow, ghosts or cut), on the studio backdrop, as a JPEG. */
function snap(req, clean, most) {
  const keep = { picked, isolated, xray, cutOn: cut.on };
  if (clean) { picked = null; isolated = null; xray = false; if (cut.on) { cut.on = false; cut.version++; } look(); }
  if (sizeBox) sizeBox.visible = false;
  renderer.render(scene, camera);
  if (sizeBox) sizeBox.visible = true;
  // (only the part of the stage he sees between the bars)
  const sy = Math.round(canvas.height * inset.top), sh = Math.max(1, Math.round(canvas.height * (1 - inset.top - inset.bottom)));
  const w = canvas.width, h = sh, k = Math.min(1, (most > 0 ? most : 1600) / Math.max(w, h));
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
  const inst = new THREE.Matrix4(), world = new THREE.Matrix4(), inv = model.matrixWorld.clone().invert();
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
        world.multiplyMatrices(inv, x.matrixWorld);
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

// ------------------------------------------------------------------ 🎓 (E) "how it works": a show Jarvis plays step by step
// The steps were written once by his AI and checked in Java; here they are only names, numbers and colours: the pieces it
// talks about glow and the rest fade, pieces turn or slide back and forth, little arrows flow from one part to another.
// Nothing in the model's code changes; at the end every piece is back where it was.

const tour = { on: false, focus: new Set(), units: [], dim: true, close: false, spins: [], moves: [], flows: [], t0: 0, size: 10, explode0: 0 };
const AXES = { x: new THREE.Vector3(1, 0, 0), y: new THREE.Vector3(0, 1, 0), z: new THREE.Vector3(0, 0, 1) };
const FLOW = { air: 0xbfe9ff, water: 0x3aa0ff, power: 0xffd34d, heat: 0xff8a3d, light: 0xfff3b0, fuel: 0xb48cff, signal: 0x7dff9b };
const flowGeo = new THREE.ConeGeometry(0.5, 1.4, 14);
const tourGroup = new THREE.Group();
model.add(tourGroup);
const upTo = (a, n) => (Array.isArray(a) ? a.slice(0, n) : []);
const num = (v, lo, hi, d) => (typeof v === 'number' && isFinite(v) ? Math.max(lo, Math.min(hi, v)) : d);

/** {id, piece}: those pieces of that part (a name it doesn't have: the whole part); hidden ones left out. */
function unitFor(ref) {
  if (!ref || typeof ref.id !== 'string') return null;
  const rec = parts.get(cleanId(ref.id));
  if (!rec || !rec.pieces.length) return null;
  const name = typeof ref.piece === 'string' ? te(ref.piece) : '';
  let pcs = name && !rec.auto ? rec.pieces.filter((pc) => pc.label === name) : [];
  if (!pcs.length) pcs = rec.pieces.slice();
  pcs = pcs.filter((pc) => pc.base && !hiddenPiece(rec, pc));
  return pcs.length ? { rec, pcs } : null;
}

/** Where a set of pieces is now (pulled apart, slid), its middle, in the stage's own space. */
function centreNow(u, out) {
  const b = new THREE.Box3();
  for (const pc of u.pcs) {
    if (!pc.box) continue;
    const off = pc.obj.position.clone().sub(pc.base);
    if (pc.spinOff) off.sub(pc.spinOff); // (turning around its own middle: the middle stays)
    b.union(pc.box.clone().translate(off));
  }
  if (b.isEmpty()) return null;
  b.getCenter(out);
  out.y += u.rec.group.position.y;
  return out;
}

function tourStart() {
  tour.on = true;
  tour.explode0 = explodeGoal;
  handsOff();
  if (picked) { picked = null; post({ t: 'pick', id: '' }); }
  const all = new THREE.Box3();
  for (const r of parts.values()) if (r.box) all.union(r.box);
  tour.size = all.isEmpty() ? 10 : Math.max(1, all.getSize(new THREE.Vector3()).length());
}

/** One step: what glows, pulled apart or not, from where, what turns / slides / flows. */
function tourStep(s) {
  if (!s || typeof s !== 'object') return;
  if (recd.on) recd.caption = typeof s.say === 'string' ? s.say.slice(0, 240) : '';
  if (!tour.on) tourStart();
  tourStill();
  tour.focus = new Set();
  tour.units = [];
  for (const ref of upTo(s.focus, 8)) {
    const u = unitFor(ref);
    if (!u) continue;
    tour.units.push(u);
    for (const m of meshesOf(u.pcs.map((pc) => pc.obj))) tour.focus.add(m);
  }
  tour.dim = s.dim !== false && tour.units.length > 0;
  tour.close = !!s.close && tour.units.length > 0;
  if (typeof s.explode === 'boolean' && (s.explode ? 1 : 0) !== explodeGoal) explodeGoal = s.explode ? 1 : 0;
  binBox();
  for (const sp of upTo(s.spin, 4)) {
    const u = unitFor(sp);
    if (!u) continue;
    const pivot = new THREE.Box3();
    for (const pc of u.pcs) if (pc.box) pivot.union(pc.box);
    if (pivot.isEmpty()) continue;
    tour.spins.push({ u, axis: AXES[sp.axis] ? sp.axis : 'y', speed: num(sp.speed, -4, 4, 1), pivot: pivot.getCenter(new THREE.Vector3()) });
  }
  for (const mv of upTo(s.move, 4)) {
    const u = unitFor(mv);
    if (!u || !Array.isArray(mv.by)) continue;
    const by = new THREE.Vector3(num(mv.by[0], -5000, 5000, 0), num(mv.by[1], -5000, 5000, 0), num(mv.by[2], -5000, 5000, 0));
    if (by.lengthSq() < 1e-6) continue;
    tour.moves.push({ u, by, secs: num(mv.secs, 0.4, 6, 1.5) });
  }
  for (const f of upTo(s.flow, 4)) addFlow(f);
  tour.t0 = performance.now();
  if (typeof s.view === 'string' && VIEWS[s.view]) view(s.view);
  controls.autoRotate = false;
  goal.zoom = 1;
  userFrame = false;
  labelsDirty = true;
  look();
  frameAll(false);
}

/** One end of an arrow: a part ("id", or {id, piece}) or a point [x, y, z] in cm. */
function endFor(e, piece) {
  if (Array.isArray(e) && e.length >= 3) return { at: new THREE.Vector3(num(e[0], -5000, 5000, 0), num(e[1], -5000, 5000, 0), num(e[2], -5000, 5000, 0)) };
  if (typeof e === 'string') { const u = unitFor({ id: e, piece }); return u ? { u } : null; }
  if (e && typeof e === 'object') { const u = unitFor(e); return u ? { u } : null; }
  return null;
}

function pointOf(end, out) { return end.at ? out.copy(end.at) : centreNow(end.u, out); }

function addFlow(f) {
  if (!f || typeof f !== 'object') return;
  const a = endFor(f.from, f.fromPiece), b = endFor(f.to, f.toPiece);
  if (!a || !b) return;
  const c = FLOW[f.kind] !== undefined ? FLOW[f.kind] : typeof f.kind === 'string' && /^#[0-9a-f]{6}$/i.test(f.kind) ? new THREE.Color(f.kind) : 0x4fd2ff;
  const mat = new THREE.MeshBasicMaterial({ color: c, transparent: true, opacity: 0.95, depthTest: false, depthWrite: false });
  const n = Math.round(num(f.count, 2, 12, 6));
  const items = [];
  for (let i = 0; i < n; i++) {
    const m = new THREE.Mesh(flowGeo, mat);
    m.renderOrder = 20;
    m.raycast = () => {};
    tourGroup.add(m);
    items.push(m);
  }
  const lg = new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3));
  const line = new THREE.Line(lg, new THREE.LineBasicMaterial({ color: c, transparent: true, opacity: 0.35, depthTest: false, depthWrite: false }));
  line.renderOrder = 19;
  line.raycast = () => {};
  tourGroup.add(line);
  tour.flows.push({ a, b, items, line, mat });
}

const UP = new THREE.Vector3(0, 1, 0), fa = new THREE.Vector3(), fb = new THREE.Vector3(), fd = new THREE.Vector3();
function tourTick(now, dt) {
  const t = (now - tour.t0) / 1000;
  const recs = new Set();
  for (const sp of tour.spins) { // (around the middle of those pieces, the way the AI said, a little at a time)
    const dq = new THREE.Quaternion().setFromAxisAngle(AXES[sp.axis], sp.speed * Math.PI * 2 * dt);
    for (const pc of sp.u.pcs) {
      pc.spinQ = pc.spinQ ? dq.clone().multiply(pc.spinQ) : dq.clone();
      pc.obj.quaternion.premultiply(dq);
      const d = pc.base.clone().sub(sp.pivot);
      pc.spinOff = d.clone().applyQuaternion(pc.spinQ).sub(d);
    }
    recs.add(sp.u.rec);
  }
  for (const mv of tour.moves) { // (there and back, like a piston or a button)
    const k = 0.5 - 0.5 * Math.cos((t / mv.secs) * Math.PI * 2);
    for (const pc of mv.u.pcs) pc.tourOff = mv.by.clone().multiplyScalar(k);
    recs.add(mv.u.rec);
  }
  for (const r of recs) placePieces(r, ease(explodeK));
  const size = tour.size * 0.022;
  for (const f of tour.flows) {
    if (!pointOf(f.a, fa) || !pointOf(f.b, fb)) { f.line.visible = false; for (const m of f.items) m.visible = false; continue; }
    fd.subVectors(fb, fa);
    const len = fd.length();
    const on = len > size;
    f.line.visible = on;
    const pos = f.line.geometry.attributes.position;
    pos.setXYZ(0, fa.x, fa.y, fa.z);
    pos.setXYZ(1, fb.x, fb.y, fb.z);
    pos.needsUpdate = true;
    fd.normalize();
    f.items.forEach((m, i) => {
      m.visible = on;
      if (!on) return;
      const k = (t * 0.55 + i / f.items.length) % 1;
      m.position.copy(fa).addScaledVector(fd, len * k);
      m.quaternion.setFromUnitVectors(UP, fd);
      m.scale.setScalar(size * (0.3 + 0.7 * Math.sin(Math.PI * k)));
    });
  }
}

/** The step's movement ends: every turned / slid piece back exactly as it was, the arrows gone. */
function tourStill() {
  const recs = new Set();
  for (const sp of tour.spins) for (const pc of sp.u.pcs) {
    if (pc.spinQ) { pc.obj.quaternion.premultiply(pc.spinQ.clone().invert()); pc.spinQ = null; }
    pc.spinOff = null;
    recs.add(sp.u.rec);
  }
  for (const mv of tour.moves) for (const pc of mv.u.pcs) { pc.tourOff = null; recs.add(mv.u.rec); }
  for (const r of recs) if (parts.get(r.id) === r) placePieces(r, ease(explodeK));
  for (const f of tour.flows) { f.mat.dispose(); f.line.geometry.dispose(); f.line.material.dispose(); }
  while (tourGroup.children.length) tourGroup.remove(tourGroup.children[0]);
  tour.spins = [];
  tour.moves = [];
  tour.flows = [];
}

function endTour() {
  if (!tour.on) return;
  tourStill();
  tour.on = false;
  tour.focus = new Set();
  tour.units = [];
  tour.close = false;
  if (explodeGoal !== tour.explode0) explodeGoal = tour.explode0; // (pulled apart or not, as it was before the show)
  binBox();
  controls.autoRotate = spinWanted && !cut.on && !tilt.on;
  labelsDirty = true;
  look();
  frameAll(false);
}

/**
 * 🎓 / 🛠️ What the model is made of, for the AI that writes the show or the build guide: each part's id, name, size and place
 * (cm), the names of its pieces, and their materials. Only what he sees (pieces taken off are left out); nothing else.
 */
function describe(req) {
  const r1 = (v) => Math.round(v * 10) / 10;
  const bx = (b) => { const c = b.getCenter(new THREE.Vector3()), s = b.getSize(new THREE.Vector3()); return { c: [r1(c.x), r1(c.y), r1(c.z)], s: [r1(s.x), r1(s.y), r1(s.z)] }; };
  const kinds = (objs) => {
    const k = new Set();
    for (const o of objs) o.traverse((x) => {
      if (!x.isMesh || x.userData.isEdge) return;
      for (const m of mats(x.userData.m0 !== undefined ? x.userData.m0 : x.material)) if (m && m.userData && m.userData.kind) k.add(m.userData.kind);
    });
    return [...k].slice(0, 4);
  };
  const out = [];
  for (const r of parts.values()) {
    if (!r.box) continue;
    const vis = r.pieces.filter((pc) => !hiddenPiece(r, pc));
    if (r.pieces.length && !vis.length) continue;
    const p = { id: r.id, name: r.label || r.id, box: bx(r.box), made: kinds(vis.map((pc) => pc.obj)), moves: r.ticks.length > 0, pieces: [] };
    if (!r.auto) {
      const byName = new Map();
      for (const pc of vis) {
        if (!pc.label || !pc.box) continue;
        let g = byName.get(pc.label);
        if (!g) { g = { box: new THREE.Box3(), objs: [], n: 0 }; byName.set(pc.label, g); }
        g.box.union(pc.box);
        g.objs.push(pc.obj);
        g.n++;
      }
      for (const [name, g] of byName) if (p.pieces.length < 16) p.pieces.push({ name, n: g.n, box: bx(g.box), made: kinds(g.objs) });
    }
    out.push(p);
    if (out.length >= 40) break;
  }
  post({ t: 'desc', req: String(req || ''), data: JSON.stringify(out) });
}

// ------------------------------------------------------------------ 📷 (G) the model in his room: the camera's picture behind it
// Jarvis puts the phone's camera under this page; here the backdrop and the floor go see-through and a soft shadow stays
// under the model. Not fixed to the real floor (his finger turns and moves it) and the size is not the real size.

let arOn = false;
const catcher = new THREE.Mesh(new THREE.CircleGeometry(1, 64), new THREE.ShadowMaterial({ opacity: 0.34, depthWrite: false }));
catcher.rotation.x = -Math.PI / 2;
catcher.receiveShadow = true;
catcher.visible = false;
catcher.renderOrder = -1;
catcher.raycast = () => {};
scene.add(catcher);

function setAr(on) {
  arOn = !!on;
  floor.visible = !arOn;
  catcher.visible = arOn;
  catcher.position.y = floor.position.y;
  catcher.scale.setScalar(goal.radius * 3);
  backdrop();
}

// ------------------------------------------------------------------ 🎨 (1) his own colours and materials, ₹0 (no AI)
// Jarvis keeps a list with the saved model: [{id, label, kind, color}] (label: a piece's name, or '' / the part's name for the
// whole part). The part's code is never touched: after the part is made, those pieces are dressed in a new material.

const paintList = [];
const PAINT_KINDS = { 'metal steel': ['metal', 'steel'], 'metal chrome': ['metal', 'chrome'], 'metal brass': ['metal', 'brass'],
  'metal copper': ['metal', 'copper'], 'metal gold': ['metal', 'gold'], 'metal black': ['metal', 'black'],
  'metal aluminium': ['metal', 'aluminium'], plastic: ['plastic'], rubber: ['rubber'], paint: ['paint'], glass: ['glass'],
  wood: ['wood'], ceramic: ['ceramic'], fabric: ['fabric'] };
const HEX = /^#[0-9a-f]{6}$/i;

function paintTargets(rec, label) {
  if (!label || rec.auto || label === rec.label || label === rec.id) return [rec.group];
  const ps = rec.pieces.filter((pc) => pc.label === label);
  return ps.length ? ps.map((pc) => pc.obj) : [rec.group];
}

/** A new material for one mesh: the kind he chose (in his colour, or the old colour), or the old one in his colour. */
function dressed(old, p) {
  const was = old && old.color ? '#' + old.color.getHexString() : undefined;
  const k = PAINT_KINDS[p.kind];
  let m;
  if (k) {
    if (k[0] === 'metal') { m = kit.metal(k[1]); if (HEX.test(p.color)) m.color.set(p.color); }
    else if (k[0] === 'glass') m = kit.glass(HEX.test(p.color) ? { tint: p.color } : {});
    else m = kit[k[0]](HEX.test(p.color) ? p.color : was);
  } else {
    m = old && old.clone ? old.clone() : kit.plastic();
    m.userData = Object.assign({}, old && old.userData ? old.userData : {});
    if (HEX.test(p.color)) {
      if (m.userData.kind === 'glow' && m.emissive) m.emissive.set(p.color); // (a light stays a light, in his colour)
      else if (m.color) m.color.set(p.color);
    }
  }
  delete m.userData.cutV;
  delete m.userData.side0;
  m.userData.painted = true;
  return m;
}

function unpaint(rec) {
  rec.group.traverse((x) => {
    if (!x.isMesh || x.userData.isEdge || x.userData.p0 === undefined) return;
    for (const m of mats(x.userData.m0)) if (m && m.userData && m.userData.painted) m.dispose();
    for (const m of mats(x.userData.mg)) if (m && !SHARED.has(m)) m.dispose();
    x.userData.m0 = x.userData.p0;
    x.userData.mg = undefined;
    x.userData.p0 = undefined;
    x.material = x.userData.m0;
  });
}

/** His colours on one part (or a guest's part), in the order he chose them. */
function applyPaints(rec, list) {
  unpaint(rec);
  for (const p of list || paintList) {
    if (p.id !== rec.id) continue;
    for (const o of paintTargets(rec, p.label)) o.traverse((x) => {
      if (!x.isMesh || x.userData.isEdge) return;
      if (x.userData.m0 === undefined) { x.userData.m0 = x.material; x.userData.v0 = x.visible; x.userData.s0 = x.castShadow; }
      if (x.userData.p0 === undefined) x.userData.p0 = x.userData.m0;
      const old = x.userData.m0;
      x.userData.m0 = Array.isArray(old) ? old.map((o2) => dressed(o2, p)) : dressed(old, p);
      if (old !== x.userData.p0) for (const m of mats(old)) if (m.userData && m.userData.painted) m.dispose();
      if (x.userData.mg) { for (const m of mats(x.userData.mg)) if (!SHARED.has(m)) m.dispose(); x.userData.mg = undefined; }
      x.material = x.userData.m0;
    });
  }
}

function readPaints(l) {
  const out = [];
  if (Array.isArray(l)) for (const p of l.slice(0, 300)) {
    if (!p || typeof p.id !== 'string') continue;
    out.push({ id: cleanId(p.id), label: typeof p.label === 'string' ? te(p.label) : '', kind: PAINT_KINDS[p.kind] ? p.kind : '',
      color: typeof p.color === 'string' && HEX.test(p.color) ? p.color : '' });
  }
  return out;
}

function setPaints(l) {
  paintList.length = 0;
  paintList.push(...readPaints(l));
  for (const r of parts.values()) if (r.box) applyPaints(r);
  look();
}

/** "రెక్కలు నీలం చెయ్": which part / piece his words name (the longest name that matches); nothing changes here. */
function paintName(req, words) {
  const ws = (Array.isArray(words) ? words : []).map((w) => String(w).toLowerCase().trim()).filter((w) => w.length >= 2).slice(0, 6);
  let best = null;
  const tryName = (rec, label, name) => {
    const n = String(name || '').toLowerCase();
    if (n.length < 2) return;
    for (const w of ws) {
      const ok = n.includes(w) || w.includes(n) || (w.length >= 3 && n.startsWith(w.slice(0, Math.max(3, w.length - 2))));
      if (ok && (!best || Math.min(n.length, w.length) > best.score)) best = { id: rec.id, label, score: Math.min(n.length, w.length) };
    }
  };
  for (const r of parts.values()) {
    if (!r.box) continue;
    tryName(r, '', r.label);
    tryName(r, '', r.id.replace(/-/g, ' '));
    if (!r.auto) for (const pc of r.pieces) if (pc.label) tryName(r, pc.label, pc.label);
  }
  post({ t: 'painthit', req: String(req || ''), id: best ? best.id : '', label: best ? best.label : '' });
}

// ------------------------------------------------------------------ 📏 (3) the tape: two taps on the model, the distance between

const tape = { on: false, pts: [], marks: [], line: null, el: null, d: 0, mid: new THREE.Vector3() };
const tapeMat = new THREE.MeshBasicMaterial({ color: 0xffd34d, depthTest: false, transparent: true });
const tapeLineMat = new THREE.LineBasicMaterial({ color: 0xffd34d, depthTest: false, transparent: true });
SHARED.add(tapeMat);
SHARED.add(tapeLineMat);

function tapeClear() {
  for (const m of tape.marks) { model.remove(m); m.geometry.dispose(); }
  tape.marks = [];
  tape.pts = [];
  if (tape.line) { model.remove(tape.line); tape.line.geometry.dispose(); tape.line = null; }
  if (tape.el) tape.el.style.display = 'none';
}

function setTape(on) {
  tape.on = !!on;
  tapeClear();
  if (tape.on) { if (picked) { unpick(); post({ t: 'pick', id: '' }); } controls.autoRotate = false; }
  else controls.autoRotate = spinWanted && !cut.on && !tilt.on;
}

function tapeAt(x, y) {
  const b = canvas.getBoundingClientRect();
  tapAt.set(((x - b.left) / b.width) * 2 - 1, -((y - b.top) / b.height) * 2 + 1);
  ray.setFromCamera(tapAt, camera);
  const hit = ray.intersectObject(model, true).find((h) => h.object.isMesh && !h.object.userData.isEdge && !h.object.userData.isTape && shown(h.object)
    && h.object.material !== ghostFill);
  if (!hit) return;
  if (tape.pts.length >= 2) tapeClear();
  const p = model.worldToLocal(hit.point.clone());
  tape.pts.push(p);
  const r = Math.max(0.08, goal.radius * 0.014);
  const mk = new THREE.Mesh(new THREE.SphereGeometry(r, 16, 12), tapeMat);
  mk.position.copy(p);
  mk.renderOrder = 30;
  mk.userData.isTape = true;
  mk.raycast = () => {};
  model.add(mk);
  tape.marks.push(mk);
  if (tape.pts.length === 1) { post({ t: 'tape', n: 1 }); return; }
  const g = new THREE.BufferGeometry().setFromPoints(tape.pts);
  tape.line = new THREE.Line(g, tapeLineMat);
  tape.line.renderOrder = 30;
  tape.line.userData.isTape = true;
  tape.line.raycast = () => {};
  model.add(tape.line);
  tape.d = tape.pts[0].distanceTo(tape.pts[1]);
  tape.mid.copy(tape.pts[0]).add(tape.pts[1]).multiplyScalar(0.5);
  if (!tape.el) {
    tape.el = document.createElement('div');
    tape.el.className = 'lb tape';
    document.body.appendChild(tape.el);
  }
  tape.el.textContent = '📏 ' + (tape.d >= 100 ? (tape.d / 100).toFixed(2) + ' m' : tape.d.toFixed(1) + ' cm');
  tape.el.style.display = 'block';
  post({ t: 'tape', n: 2, d: Math.round(tape.d * 10) / 10 });
}

function tapeDraw() {
  if (!tape.el || tape.pts.length < 2 || tape.el.style.display === 'none') return;
  const p = model.localToWorld(tape.mid.clone()).project(camera);
  const w = window.innerWidth, h = window.innerHeight;
  tape.el.style.transform = 'translate(' + Math.round((p.x + 1) / 2 * w - tape.el.offsetWidth / 2) + 'px,' + Math.round((1 - p.y) / 2 * h - 26) + 'px)';
}

// ------------------------------------------------------------------ ⚖️ (9) how much it holds: each part's volume and skin, by material

/** The model as he has it (pieces taken off for good left out), put together: cm³ and cm² per part per material kind. */
function massOf(req) {
  const e = ease(explodeK);
  for (const r of parts.values()) placePieces(r, 0);
  model.updateMatrixWorld(true);
  const inv = model.matrixWorld.clone().invert();
  const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3(), ab = new THREE.Vector3(), ac = new THREE.Vector3();
  const inst = new THREE.Matrix4(), M = new THREE.Matrix4();
  const all = new THREE.Box3();
  const out = [];
  const kindOf = (m) => {
    if (m && m.userData && m.userData.kind) return m.userData.kind;
    if (m && m.transmission > 0) return 'glass';
    if (m && m.metalness >= 0.8) return 'metal steel';
    return 'plastic';
  };
  for (const r of parts.values()) {
    if (!r.box) continue;
    const kinds = {};
    for (const pc of r.pieces) {
      if (goneList.some((h) => h.id === r.id && (!h.label || r.auto || pc.label === h.label))) continue;
      pc.obj.traverse((x) => {
        if (!x.isMesh || x.userData.isEdge || x.userData.isTape || !x.geometry || !x.geometry.attributes.position) return;
        const m0 = mats(x.userData.m0 !== undefined ? x.userData.m0 : x.material)[0];
        const kind = kindOf(m0);
        const pos = x.geometry.attributes.position, idx = x.geometry.index;
        const n = x.isInstancedMesh ? x.count : 1;
        let vol = 0, area = 0;
        const bb = new THREE.Box3();
        for (let j = 0; j < n; j++) {
          M.multiplyMatrices(inv, x.matrixWorld);
          if (x.isInstancedMesh) { x.getMatrixAt(j, inst); M.multiply(inst); }
          const count = idx ? idx.count : pos.count;
          let v6 = 0;
          for (let i = 0; i + 2 < count; i += 3) {
            a.fromBufferAttribute(pos, idx ? idx.getX(i) : i).applyMatrix4(M);
            b.fromBufferAttribute(pos, idx ? idx.getX(i + 1) : i + 1).applyMatrix4(M);
            c.fromBufferAttribute(pos, idx ? idx.getX(i + 2) : i + 2).applyMatrix4(M);
            v6 += a.dot(ab.copy(b).cross(c));
            area += ab.subVectors(b, a).cross(ac.subVectors(c, a)).length() / 2;
            bb.expandByPoint(a); bb.expandByPoint(b); bb.expandByPoint(c);
          }
          vol += Math.abs(v6) / 6;
        }
        if (bb.isEmpty()) return;
        const s = bb.getSize(new THREE.Vector3());
        vol = Math.min(vol, s.x * s.y * s.z); // (an open shape can't hold more than its box)
        all.union(bb);
        const k = kinds[kind] || (kinds[kind] = [0, 0]);
        k[0] += vol;
        k[1] += area;
      });
    }
    for (const k of Object.keys(kinds)) kinds[k] = [Math.round(kinds[k][0] * 100) / 100, Math.round(kinds[k][1] * 100) / 100];
    out.push({ id: r.id, name: r.label || r.id, kinds });
    if (out.length >= 60) break;
  }
  for (const r of parts.values()) placePieces(r, e);
  const sz = all.isEmpty() ? [0, 0, 0] : all.getSize(new THREE.Vector3()).toArray().map((v) => Math.round(v * 10) / 10);
  post({ t: 'mass', req: String(req || ''), data: JSON.stringify({ parts: out, size: sz }) });
}

// ------------------------------------------------------------------ 🔀 (6) a second saved model on the same stage (to look at, ₹0)
// It stands beside his model (or on top of it), moved by a held finger like a piece. It is not part of his model: no names,
// no pull-apart, not in the show, the guide, the print file or the weight. Nothing of it is saved with his model.

const guests = new Map();
const guestGroup = new THREE.Group();
guestGroup.name = 'guest';
model.add(guestGroup);
let guestQueue = Promise.resolve(), guestGone = [], guestPaints = [], guestTitle = '', guestPlace = 'side';

async function runGuestPart(m) {
  const id = cleanId(m.id);
  const rec = { id, group: new THREE.Group(), ticks: [], pieces: [], tris: 0, box: null, born: 0, auto: false, label: '' };
  guestGroup.add(rec.group);
  guests.set(id, rec);
  try { await new AsyncFunction('THREE', 'W', String(m.code || ''))(THREE, kitFor(rec)); }
  catch (e) { guestGroup.remove(rec.group); dispose(rec.group); guests.delete(id); return; }
  const tris = triangles(rec.group);
  let all = 0;
  for (const g of guests.values()) all += g.tris || 0;
  if (totalTris() + all + tris > MAX_TRIS) { guestGroup.remove(rec.group); dispose(rec.group); guests.delete(id); return; }
  rec.tris = tris;
  rec.box = new THREE.Box3().setFromObject(rec.group);
  for (const pc of rec.pieces) if (guestGone.some((h) => h.id === id && (!h.label || pc.label === h.label))) pc.obj.visible = false;
  rec.auto = !rec.pieces.some((pc) => pc.label);
  applyPaints(rec, guestPaints);
  rec.group.traverse((x) => { if (x.isMesh) x.userData.guest = true; });
}

function guestOff() {
  for (const r of guests.values()) { guestGroup.remove(r.group); dispose(r.group); }
  guests.clear();
  guestGroup.position.set(0, 0, 0);
  guestTitle = '';
  frameAll(false);
}

function guestBox() {
  const b = new THREE.Box3();
  for (const r of guests.values()) if (r.box) b.union(r.box);
  return b;
}

/** Beside his model on the floor, or standing on top of it (in the middle). */
function placeGuest(where) {
  const gb = guestBox();
  if (gb.isEmpty()) return;
  const mb = new THREE.Box3();
  for (const r of parts.values()) if (r.box) mb.union(r.box);
  const gc = gb.getCenter(new THREE.Vector3()), mc = mb.isEmpty() ? new THREE.Vector3() : mb.getCenter(new THREE.Vector3());
  guestPlace = where === 'top' ? 'top' : 'side';
  if (guestPlace === 'top' && !mb.isEmpty()) guestGroup.position.set(mc.x - gc.x, mb.max.y - gb.min.y, mc.z - gc.z);
  else {
    const gap = Math.max(4, (gb.max.x - gb.min.x) * 0.15);
    guestGroup.position.set((mb.isEmpty() ? 0 : mb.max.x) + gap - gb.min.x, (mb.isEmpty() ? 0 : mb.min.y) - gb.min.y, mc.z - gc.z);
  }
  userFrame = false;
  frameAll(false);
}

function setGuest(m) {
  guestQueue = guestQueue.then(async () => {
    guestOff();
    guestTitle = typeof m.title === 'string' ? m.title.slice(0, 60) : '';
    guestGone = Array.isArray(m.gone) ? m.gone.filter((g) => g && typeof g.id === 'string').map((g) => ({ id: cleanId(g.id), label: typeof g.label === 'string' ? g.label : '' })) : [];
    guestPaints = readPaints(m.paints);
    for (const p of (Array.isArray(m.parts) ? m.parts : []).slice(0, 40)) if (p && typeof p.code === 'string') await runGuestPart(p);
    placeGuest('side');
    post({ t: 'guest', n: guests.size });
  }).catch(() => post({ t: 'guest', n: guests.size }));
}

// ------------------------------------------------------------------ 📐 (2) a drawing sheet: the model from the front, top and side

/**
 * Flat (orthographic) views, put together, in drawing colours (light fills, dark edges) on white, all at one scale, with the
 * overall sizes drawn on (cm). Jarvis lays them on a PDF page with the parts list.
 */
function ortho(req) {
  const e = ease(explodeK);
  for (const r of parts.values()) placePieces(r, 0);
  const keepGuest = guestGroup.visible, keepTour = tourGroup.visible, keepFloor = floor.visible, keepCatch = catcher.visible;
  guestGroup.visible = false; tourGroup.visible = false; floor.visible = false; catcher.visible = false;
  for (const m of tape.marks) m.visible = false;
  if (tape.line) tape.line.visible = false;
  const keep = { picked, isolated, xray, cutOn: cut.on };
  picked = null; isolated = null; xray = false;
  if (cut.on) { cut.on = false; cut.version++; }
  look();
  model.updateMatrixWorld(true);
  const inv = model.matrixWorld.clone().invert();
  const box = new THREE.Box3();
  for (const r of parts.values()) for (const pc of r.pieces) {
    if (hiddenPiece(r, pc)) continue;
    pc.obj.traverse((x) => { if (x.isMesh && x.visible && !x.userData.isEdge) box.union(new THREE.Box3().setFromObject(x).applyMatrix4(inv)); });
  }
  const fill = new THREE.MeshBasicMaterial({ color: 0xeef2f6, polygonOffset: true, polygonOffsetFactor: 1, polygonOffsetUnits: 1 });
  const ink = new THREE.LineBasicMaterial({ color: 0x1d2633 });
  const temp = [], swapped = [];
  for (const r of parts.values()) r.group.traverse((x) => {
    if (!x.isMesh || x.userData.isEdge || !x.visible) return;
    swapped.push([x, x.material]);
    x.material = fill;
    if (!x.isInstancedMesh && x.geometry) {
      try { const l = new THREE.LineSegments(new THREE.EdgesGeometry(x.geometry, 30), ink); l.userData.isEdge = true; x.add(l); temp.push(l); } catch (err) { /* */ }
    }
  });
  const keepBg = scene.background, keepEnv = scene.environment, keepPr = renderer.getPixelRatio(), keepTone = renderer.toneMapping;
  scene.background = new THREE.Color(0xffffff);
  renderer.toneMapping = THREE.NoToneMapping;
  const s = box.getSize(new THREE.Vector3()), cen = box.getCenter(new THREE.Vector3());
  const big = Math.max(s.x, s.y, s.z, 0.1), ppc = Math.min(40, 820 / big); // (one scale for every view: pixels per cm)
  const pad = 120;
  const views = [];
  const VIEW = { // (where the camera looks from, its up, which sizes show across / up)
    front: { from: [0, 0, 1], up: [0, 1, 0], w: s.x, h: s.y, wl: 'W', hl: 'H' },
    top: { from: [0, 1, 0], up: [0, 0, -1], w: s.x, h: s.z, wl: 'W', hl: 'D' },
    side: { from: [1, 0, 0], up: [0, 1, 0], w: s.z, h: s.y, wl: 'D', hl: 'H' },
  };
  for (const name of ['front', 'top', 'side']) {
    const v = VIEW[name];
    const W = Math.max(60, Math.round(v.w * ppc)) + pad * 2, H = Math.max(60, Math.round(v.h * ppc)) + pad * 2;
    const oc = new THREE.OrthographicCamera(-W / ppc / 2, W / ppc / 2, H / ppc / 2, -H / ppc / 2, 0.01, big * 20 + 10);
    const wc = model.localToWorld(cen.clone());
    oc.position.copy(wc).add(new THREE.Vector3().fromArray(v.from).multiplyScalar(big * 5 + 5));
    oc.up.fromArray(v.up);
    oc.lookAt(wc);
    oc.updateProjectionMatrix();
    renderer.setPixelRatio(1);
    renderer.setSize(W, H, false);
    renderer.render(scene, oc);
    const c2 = document.createElement('canvas');
    c2.width = W; c2.height = H;
    const g = c2.getContext('2d');
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, W, H);
    g.drawImage(canvas, 0, 0, W, H);
    // the overall sizes: across under the view, up on its right
    const x0 = pad, x1 = W - pad, y0 = pad, y1 = H - pad;
    g.strokeStyle = '#2a6fd6'; g.fillStyle = '#2a6fd6'; g.lineWidth = 2;
    g.font = '600 26px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const arrow = (ax, ay, bx, by) => {
      g.beginPath(); g.moveTo(ax, ay); g.lineTo(bx, by); g.stroke();
      for (const [px, py, qx, qy] of [[ax, ay, bx, by], [bx, by, ax, ay]]) {
        const an = Math.atan2(qy - py, qx - px);
        g.beginPath(); g.moveTo(px, py); g.lineTo(px + 14 * Math.cos(an + 0.4), py + 14 * Math.sin(an + 0.4));
        g.lineTo(px + 14 * Math.cos(an - 0.4), py + 14 * Math.sin(an - 0.4)); g.closePath(); g.fill();
      }
    };
    const fmt = (cm) => (Math.abs(cm - Math.round(cm)) < 0.05 ? String(Math.round(cm)) : cm.toFixed(1)) + ' cm';
    g.setLineDash([6, 6]);
    g.beginPath(); g.moveTo(x0, y1 + 8); g.lineTo(x0, y1 + 70); g.moveTo(x1, y1 + 8); g.lineTo(x1, y1 + 70);
    g.moveTo(x1 + 8, y0); g.lineTo(x1 + 70, y0); g.moveTo(x1 + 8, y1); g.lineTo(x1 + 70, y1); g.stroke();
    g.setLineDash([]);
    arrow(x0, y1 + 56, x1, y1 + 56);
    g.fillStyle = '#ffffff'; const tw = g.measureText(fmt(v.w)).width + 16; g.fillRect((x0 + x1) / 2 - tw / 2, y1 + 40, tw, 32);
    g.fillStyle = '#2a6fd6'; g.fillText(fmt(v.w), (x0 + x1) / 2, y1 + 56);
    arrow(x1 + 56, y0, x1 + 56, y1);
    g.save(); g.translate(x1 + 56, (y0 + y1) / 2); g.rotate(-Math.PI / 2);
    g.fillStyle = '#ffffff'; const th = g.measureText(fmt(v.h)).width + 16; g.fillRect(-th / 2, -16, th, 32);
    g.fillStyle = '#2a6fd6'; g.fillText(fmt(v.h), 0, 0); g.restore();
    const url = c2.toDataURL('image/jpeg', 0.93);
    views.push({ name, w: W, h: H, img: url.slice(url.indexOf(',') + 1), across: Math.round(v.w * 10) / 10, up: Math.round(v.h * 10) / 10 });
  }
  for (const l of temp) { l.parent.remove(l); l.geometry.dispose(); }
  for (const [x, m] of swapped) x.material = m;
  fill.dispose(); ink.dispose();
  scene.background = keepBg; scene.environment = keepEnv; renderer.toneMapping = keepTone;
  renderer.setPixelRatio(keepPr);
  guestGroup.visible = keepGuest; tourGroup.visible = keepTour; floor.visible = keepFloor; catcher.visible = keepCatch;
  for (const m of tape.marks) m.visible = true;
  if (tape.line) tape.line.visible = true;
  picked = keep.picked; isolated = keep.isolated; xray = keep.xray;
  if (keep.cutOn) { cut.on = true; cut.version++; }
  for (const r of parts.values()) placePieces(r, e);
  look();
  resize();
  post({ t: 'ortho', req: String(req || ''), data: JSON.stringify({ size: s.toArray().map((v) => Math.round(v * 10) / 10), views }) });
}

// ------------------------------------------------------------------ 🎞️ (4) the show as a video: what he sees, with the line said

const recd = { on: false, mr: null, chunks: [], cv: null, g: null, caption: '', title: '', type: '', req: '', bg: null };

function recStart(m) {
  recCancel();
  if (typeof MediaRecorder === 'undefined' || !canvas.captureStream) { post({ t: 'file', req: String(m.req || ''), kind: 'none', data: '' }); return; }
  const sy = Math.round(canvas.height * inset.top), sh = Math.max(2, Math.round(canvas.height * (1 - inset.top - inset.bottom)));
  const k = Math.min(1, 720 / Math.min(canvas.width, sh));
  const cv = document.createElement('canvas');
  cv.width = Math.round(canvas.width * k / 2) * 2;
  cv.height = Math.round(sh * k / 2) * 2;
  const g = cv.getContext('2d');
  const type = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm']
    .find((t) => { try { return MediaRecorder.isTypeSupported(t); } catch (e) { return false; } });
  if (!type) { post({ t: 'file', req: String(m.req || ''), kind: 'none', data: '' }); return; }
  const bg = g.createRadialGradient(cv.width / 2, cv.height * 0.46, 0, cv.width / 2, cv.height * 0.46, Math.max(cv.width, cv.height) * 0.62);
  bg.addColorStop(0, nightOn ? '#10151e' : '#2b3342'); bg.addColorStop(0.46, nightOn ? '#07090d' : '#151a23'); bg.addColorStop(1, nightOn ? '#020304' : '#07080b');
  let mr;
  try { mr = new MediaRecorder(cv.captureStream(30), { mimeType: type, videoBitsPerSecond: 2500000 }); }
  catch (e) { post({ t: 'file', req: String(m.req || ''), kind: 'none', data: '' }); return; }
  Object.assign(recd, { on: true, mr, chunks: [], cv, g, caption: '', title: typeof m.title === 'string' ? m.title.slice(0, 60) : '', type, req: String(m.req || ''), bg, sy, sh });
  mr.ondataavailable = (ev) => { if (ev.data && ev.data.size) recd.chunks.push(ev.data); };
  mr.start(1000);
}

/** One frame: the backdrop, what was just drawn, the title, and the line being said (wrapped, at the bottom). */
function recFrame() {
  if (!recd.on) return;
  const { g, cv } = recd;
  g.fillStyle = recd.bg; g.fillRect(0, 0, cv.width, cv.height);
  g.drawImage(canvas, 0, recd.sy, canvas.width, recd.sh, 0, 0, cv.width, cv.height);
  const u = cv.width / 360;
  g.font = '600 ' + Math.round(13 * u) + 'px system-ui, "Noto Sans Telugu", sans-serif';
  g.textAlign = 'left'; g.textBaseline = 'top';
  g.fillStyle = 'rgba(232, 244, 255, 0.92)';
  g.fillText('🎓 ' + recd.title, 12 * u, 10 * u);
  if (!recd.caption) return;
  g.font = '500 ' + Math.round(15 * u) + 'px system-ui, "Noto Sans Telugu", sans-serif';
  const max = cv.width - 40 * u, lines = [];
  let line = '';
  for (const w of recd.caption.split(/\s+/)) {
    const t = line ? line + ' ' + w : w;
    if (g.measureText(t).width > max && line) { lines.push(line); line = w; } else line = t;
  }
  if (line) lines.push(line);
  const lh = 21 * u, boxH = lines.length * lh + 16 * u, y0 = cv.height - boxH - 14 * u;
  g.fillStyle = 'rgba(6, 12, 18, 0.74)';
  g.fillRect(12 * u, y0, cv.width - 24 * u, boxH);
  g.fillStyle = '#e8f4ff'; g.textAlign = 'center';
  lines.forEach((l, i) => g.fillText(l, cv.width / 2, y0 + 8 * u + i * lh));
}

function recStop() {
  if (!recd.on) return;
  const r = { mr: recd.mr, chunks: recd.chunks, type: recd.type, req: recd.req };
  recd.on = false;
  r.mr.onstop = async () => {
    try {
      const blob = new Blob(r.chunks, { type: r.type.split(';')[0] });
      const bytes = new Uint8Array(await blob.arrayBuffer());
      post({ t: 'file', req: r.req, kind: r.type.startsWith('video/mp4') ? 'mp4' : 'webm', data: b64(bytes) });
    } catch (e) { post({ t: 'file', req: r.req, kind: 'none', data: '' }); }
  };
  try { r.mr.stop(); } catch (e) { post({ t: 'file', req: r.req, kind: 'none', data: '' }); }
}

function recCancel() {
  if (!recd.on) return;
  recd.on = false;
  try { recd.mr.onstop = null; recd.mr.stop(); } catch (e) { /* */ }
  recd.chunks = [];
}

// ------------------------------------------------------------------ ✋ (7) his hand in the air turns and zooms (Jarvis sends nudges)

const sph = new THREE.Spherical();
function nudge(m) {
  if (xr.on) { if (isFinite(+m.az)) xr.yaw += +m.az; return; }
  const off = camera.position.clone().sub(controls.target);
  sph.setFromVector3(off);
  if (isFinite(+m.az)) sph.theta -= Math.max(-0.5, Math.min(0.5, +m.az));
  if (isFinite(+m.el)) sph.phi = Math.max(0.12, Math.min(Math.PI - 0.12, sph.phi - Math.max(-0.4, Math.min(0.4, +m.el))));
  off.setFromSpherical(sph);
  camera.position.copy(controls.target).add(off);
  if (isFinite(+m.zoom) && +m.zoom > 0) goal.zoom = Math.max(0.3, Math.min(3, goal.zoom * Math.max(0.8, Math.min(1.25, +m.zoom))));
  controls.autoRotate = false;
  userFrame = true;
  goal.dir = null;
}

// ------------------------------------------------------------------ 📏 (8) real size on the real floor: the phone's AR tells where the camera is

const xr = { on: false, placed: false, yaw: 0, base: new THREE.Vector3(), shadow: null, reticle: null, v: new THREE.Matrix4(), p: new THREE.Matrix4(), a: new THREE.Matrix4() };

function setXr(on) {
  xr.on = !!on;
  xr.placed = false;
  if (xr.on) {
    if (picked) { unpick(); post({ t: 'pick', id: '' }); }
    controls.enabled = false;
    controls.autoRotate = false;
    camera.matrixAutoUpdate = false;
    model.matrixAutoUpdate = false;
    model.visible = false;
    floor.visible = false;
    catcher.visible = false;
    key.castShadow = false;
    document.documentElement.style.background = 'transparent';
    document.body.style.background = 'transparent';
    if (!xr.reticle) {
      const rg = new THREE.RingGeometry(0.09, 0.12, 48).rotateX(-Math.PI / 2);
      xr.reticle = new THREE.Mesh(rg, new THREE.MeshBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.85, depthTest: false }));
      xr.reticle.matrixAutoUpdate = false;
      xr.reticle.renderOrder = 40;
      xr.reticle.raycast = () => {};
      scene.add(xr.reticle);
    }
    xr.reticle.visible = false;
    if (!xr.shadow) {
      xr.shadow = new THREE.Mesh(new THREE.CircleGeometry(1, 48).rotateX(-Math.PI / 2),
        new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.45, alphaMap: fade(), depthWrite: false }));
      xr.shadow.raycast = () => {};
      xr.shadow.renderOrder = -1;
      model.add(xr.shadow);
    }
    const b = new THREE.Box3();
    for (const r of parts.values()) if (r.box) b.union(r.box);
    const c = b.isEmpty() ? new THREE.Vector3() : b.getCenter(new THREE.Vector3());
    xr.base.set(c.x, b.isEmpty() ? 0 : b.min.y, c.z);
    const s = b.isEmpty() ? new THREE.Vector3(10, 10, 10) : b.getSize(new THREE.Vector3());
    xr.shadow.position.set(c.x, xr.base.y + 0.05, c.z);
    xr.shadow.scale.setScalar(Math.max(s.x, s.z) * 0.75);
    xr.shadow.visible = true;
  } else {
    controls.enabled = true;
    camera.matrixAutoUpdate = true;
    model.matrixAutoUpdate = true;
    model.position.set(0, 0, 0); model.rotation.set(0, 0, 0); model.scale.set(1, 1, 1);
    model.updateMatrix();
    model.visible = true;
    floor.visible = !arOn;
    catcher.visible = arOn;
    key.castShadow = true;
    if (xr.reticle) xr.reticle.visible = false;
    if (xr.shadow) xr.shadow.visible = false;
    backdrop();
    resize();
    jump();
  }
}

/** Each frame from Jarvis: the camera's view and lens, where the model stands (anchor) and the ring where it would go. */
function xrPose(m) {
  if (!xr.on) return;
  const ok16 = (a) => Array.isArray(a) && a.length === 16 && a.every((v) => typeof v === 'number' && isFinite(v));
  if (ok16(m.v) && ok16(m.p)) {
    xr.v.fromArray(m.v);
    camera.matrix.copy(xr.v).invert();
    camera.matrixWorldNeedsUpdate = true;
    camera.projectionMatrix.fromArray(m.p);
    camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
  }
  if (ok16(m.a)) { xr.a.fromArray(m.a); xr.placed = true; } else if (m.a === null) xr.placed = false;
  model.visible = xr.placed;
  if (xr.reticle) {
    if (ok16(m.r) && !xr.placed) { xr.reticle.matrix.fromArray(m.r); xr.reticle.visible = true; } else xr.reticle.visible = false;
  }
}

function xrPlaceModel() {
  if (!xr.on || !xr.placed) return;
  // anchor (metres) × his turn × centimetres → metres × the model's base middle at the anchor
  const T = new THREE.Matrix4().makeTranslation(-xr.base.x, -xr.base.y, -xr.base.z);
  const S = new THREE.Matrix4().makeScale(0.01, 0.01, 0.01);
  const R = new THREE.Matrix4().makeRotationY(xr.yaw);
  model.matrix.copy(xr.a).multiply(R).multiply(S).multiply(T);
  model.matrixWorldNeedsUpdate = true;
}

canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); post({ t: 'warn', id: 'stage', msg: 'webgl context lost' }); });

resize();
jump();
requestAnimationFrame(tick);
post({ t: 'ready' });
