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
    if (x.material) (Array.isArray(x.material) ? x.material : [x.material]).forEach((m) => m.dispose());
  });
}

function removePart(id) {
  const r = parts.get(id);
  if (!r) return;
  model.remove(r.group);
  dispose(r.group);
  parts.delete(id);
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
  removePart(id);
  const rec = { id, group: new THREE.Group(), ticks: [], tris: 0, box: null, born: 0 };
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
  rec.born = performance.now();
  rec.group.visible = true;
  post({ t: 'ok', id, tris: rec.tris });
  frameAll(false);
  stats();
}

function stats() { post({ t: 'stats', parts: parts.size, tris: totalTris() }); }

function reset() {
  for (const id of [...parts.keys()]) removePart(id);
  userFrame = false;
  stats();
}

// ------------------------------------------------------------------ framing, the floor and lights follow the model

const goal = { target: new THREE.Vector3(0, 8, 0), dist: 60, floorY: 0, radius: 12 };
let userFrame = false; // (he moved the camera himself: keep his angle, still follow the size)

function modelBox() {
  const b = new THREE.Box3();
  for (const r of parts.values()) if (r.box) b.union(r.box);
  return b;
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
  // the camera, floor and target glide to the model's size
  const a = 1 - Math.exp(-dt * 3);
  controls.target.lerp(goal.target, a);
  const off = camera.position.clone().sub(controls.target);
  const d = off.length();
  if (!userFrame || d > goal.dist * 1.6 || d < goal.dist * 0.35) off.setLength(d + (goal.dist - d) * a);
  camera.position.copy(controls.target).add(off);
  floor.position.y += (goal.floorY - floor.position.y) * a;
  floor.scale.setScalar(floor.scale.x + (goal.radius * 9 - floor.scale.x) * a);
  controls.update();
  renderer.render(scene, camera);
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
    case 'frame': frameAll(true); break;
    case 'inset':
      inset.top = Math.max(0, Math.min(0.4, +m.top || 0));
      inset.bottom = Math.max(0, Math.min(0.6, +m.bottom || 0));
      applyInset();
      frameAll(false);
      break;
    case 'spin': spinWanted = !!m.on; controls.autoRotate = spinWanted; break;
    case 'pause': pause(); break;
    case 'resume': resume(); break;
    default: break;
  }
});

canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); post({ t: 'warn', id: 'stage', msg: 'webgl context lost' }); });

resize();
jump();
requestAnimationFrame(tick);
post({ t: 'ready' });
