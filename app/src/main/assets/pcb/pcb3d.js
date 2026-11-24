// 🔌 Jarvis PCB studio — the board in 3D (our own code on the bundled three.js; no AI, no network): the real board picture
// (mask colour, copper, silver pads, white printing) on both faces of a 1.6 mm board, and simple bodies for every part —
// resistors with their colour bands, LEDs that glow, capacitors, chips, sockets — standing where they will be soldered.
// Build 3B (24 Nov): 🔧 the soldering guide (only the parts soldered so far, the next one glowing, the camera goes to it),
// 📦 the box around the board (open / closed / see-through; its 3D-print STL made from the same shapes) and 📏 the board at its
// real size on his table through the phone's AR (the camera picture is drawn by Jarvis under this see-through page).
import * as THREE from './three.module.min.js';
import { OrbitControls } from './OrbitControls.js';

const FONT = '"Noto Sans Telugu", system-ui, sans-serif';
const LED = { red: 0xff3b30, green: 0x34e36a, blue: 0x3a7bff, yellow: 0xffd93b, white: 0xf4f8ff, ir: 0x6b4a8a };
let api = null, renderer = null, scene = null, camera = null, controls = null, root = null, raf = 0, key = '', spinning = false, showLabels = false;
const bodies = new Map(), labelOf = new Map();
let fill = null; // (the light from below: brighter while 🔧 shows the joints' side)
const BG = 0x0b1016;
let labels = [], picked = null, down = null, failed = false;

export function open(a) {
  api = a;
  if (!renderer && !failed) build();
  if (failed) return;
  resize();
  update();
  if (!raf) raf = requestAnimationFrame(frame);
}
export function close() { cancelAnimationFrame(raf); raf = 0; }
/** The page made the 3D area smaller / bigger (a card under it): the picture fits it again. */
export function resized() { resize(); }
export function spin() { spinning = !spinning; if (controls) controls.autoRotate = spinning; return spinning; }
export { labelsToggle as labels };
function labelsToggle() { showLabels = !showLabels; labels.forEach((s) => { s.visible = showLabels; }); return showLabels; }

export function view(which) {
  const D = api.design();
  const r = boxPlan ? Math.hypot(boxPlan.size.w, boxPlan.size.d, boxPlan.size.h + (boxOpen ? lidLift : 0)) / 2 + 4 : Math.hypot(D.board.w, D.board.h) / 2 + 4;
  fly = null;
  // far enough that the whole board (📦 the whole box) fits the narrow phone screen
  const d = r / Math.tan((camera.fov / 2) * Math.PI / 180) / Math.min(1, camera.aspect) * (boxPlan ? 1.0 : 0.82);
  if (which === 'top') camera.position.set(0.001, d, 0.001);
  else if (which === 'bottom') camera.position.set(0.001, -d, 0.001);
  else camera.position.set(d * 0.42, d * 0.68, d * 0.6);
  controls.target.set(0, 0, 0);
  controls.update();
}

function build() {
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true }); // (alpha: 📏 AR shows the camera under it)
  } catch (e) {
    failed = true;
    const m = document.createElement('div');
    m.textContent = 'ఈ ఫోన్‌లో 3D (WebGL) తెరవలేకపోయాను.';
    m.style.cssText = 'padding:40px 16px;color:#ffc861;text-align:center';
    api.host.appendChild(m);
    return;
  }
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  api.host.appendChild(renderer.domElement);
  scene = new THREE.Scene();
  scene.background = new THREE.Color(BG);
  camera = new THREE.PerspectiveCamera(38, 1, 0.5, 2000);
  scene.add(new THREE.HemisphereLight(0xdfefff, 0x203040, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 2.2);
  sun.position.set(40, 90, 60);
  scene.add(sun);
  fill = new THREE.DirectionalLight(0x9fd8ff, 0.7);
  fill.position.set(-60, -30, -40);
  scene.add(fill);
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.12;
  controls.autoRotateSpeed = 1.6;
  renderer.domElement.style.touchAction = 'none';
  renderer.domElement.addEventListener('pointerdown', (e) => { down = { x: e.clientX, y: e.clientY, t: Date.now(), lx: e.clientX }; });
  renderer.domElement.addEventListener('pointermove', (e) => { // (📏 in AR one finger turns the board)
    if (!xr.on || !down) return;
    xr.yaw += (e.clientX - down.lx) * 0.012;
    down.lx = e.clientX;
  });
  renderer.domElement.addEventListener('pointerup', (e) => {
    const d = down;
    down = null;
    if (!d || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 8 || Date.now() - d.t > 500) return;
    tap(e);
  });
  window.addEventListener('resize', resize);
}

function resize() {
  if (!renderer) return;
  const w = api.host.clientWidth || 360, h = api.host.clientHeight || 500;
  renderer.setSize(w, h, false);
  renderer.domElement.style.width = w + 'px';
  renderer.domElement.style.height = h + 'px';
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

function frame() {
  raf = requestAnimationFrame(frame);
  if (!renderer) return;
  const now = performance.now();
  if (fly) { // (🔧 the camera glides to the next part)
    const k = Math.min(1, (now - fly.t0) / fly.ms), e = k * k * (3 - 2 * k);
    camera.position.lerpVectors(fly.p0, fly.p1, e);
    controls.target.lerpVectors(fly.q0, fly.q1, e);
    if (k >= 1) fly = null;
  }
  if (pulse.size) { // (🔧 the part to solder now breathes in blue)
    const k = 0.5 + 0.5 * Math.sin(now / 240);
    pulse.forEach((o, m) => { m.emissive.setHex(0x2bd4ff); m.emissiveIntensity = 0.2 + 0.8 * k; });
  }
  if (lidM) { // (📦 the lid lifts off / comes down)
    const want = boxOpen ? 1 : 0;
    lidT += (want - lidT) * 0.14;
    if (Math.abs(want - lidT) < 0.002) lidT = want;
    lidM.position.y = lidT * lidLift;
  }
  if (xr.on) {
    placeXr();
    renderer.render(scene, camera);
    return;
  }
  controls.update();
  renderer.render(scene, camera);
}

/** The board and parts again when the design changed (same shape: nothing to do). */
export function update() {
  if (!renderer) return;
  const D = api.design();
  const k = JSON.stringify([D.board, D.parts.map((p) => [p.ref, p.x, p.y, p.rot, p.lib, p.fp, p.color]), D.tracks.length, D.vias.length, D.pour]);
  if (k === key) return;
  const first = !key;
  key = k;
  if (root) { scene.remove(root); dispose(root); }
  root = new THREE.Group();
  scene.add(root);
  bodies.clear();
  labelOf.clear();
  labels = [];
  picked = null;
  pulse.clear();
  lidM = null;
  const B = D.board, T = B.thick || 1.6;
  // the board: its two real faces as pictures, FR-4 edges
  const top = new THREE.CanvasTexture(api.texture('top', 14)), bot = new THREE.CanvasTexture(api.texture('bottom', 10));
  [top, bot].forEach((t) => { t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4; });
  // (the bottom picture is drawn in the board's own x / y, so seen from below it sits right under its pads)
  const edge = new THREE.MeshStandardMaterial({ color: 0xbfae78, roughness: 0.8 });
  const shape = roundedRect(B.w, B.h, Math.min(B.r || 0, B.w / 2, B.h / 2));
  // (a rounded board: the outline extruded, the pictures mapped from above)
  const geo = new THREE.ExtrudeGeometry(shape, { depth: T, bevelEnabled: false, curveSegments: 8 });
  geo.rotateX(Math.PI / 2);
  geo.translate(-B.w / 2, T / 2, -B.h / 2);
  mapFaces(geo, B, T);
  const board = new THREE.Mesh(geo, [new THREE.MeshStandardMaterial({ map: top, roughness: 0.55, metalness: 0.05 }), edge, new THREE.MeshStandardMaterial({ map: bot, roughness: 0.6 })]);
  board.userData.board = true;
  root.add(board);
  // the parts
  D.parts.forEach((pt) => {
    const f = api.P.fpOf(pt);
    if (!f) return;
    const g = new THREE.Group();
    makeBody(g, pt, f);
    g.position.set(pt.x - B.w / 2, T / 2, pt.y - B.h / 2);
    g.rotation.y = -(pt.rot || 0) * Math.PI / 180;
    g.userData.ref = pt.ref;
    root.add(g);
    bodies.set(pt.ref, g);
    const s = label(pt.ref);
    const bb = new THREE.Box3().setFromObject(g);
    s.position.set(pt.x - B.w / 2, bb.max.y + 2.2, pt.y - B.h / 2);
    s.visible = showLabels;
    root.add(s);
    labels.push(s);
    labelOf.set(pt.ref, s);
  });
  if (boxPlan) buildBox();
  if (guideSt) applyGuide(false);
  if (xr.on) { root.matrixAutoUpdate = false; xrBase(); }
  if (first && !xr.on) view('side');
}

function roundedRect(w, h, r) {
  const s = new THREE.Shape();
  if (r <= 0) { s.moveTo(0, 0); s.lineTo(w, 0); s.lineTo(w, h); s.lineTo(0, h); s.lineTo(0, 0); return s; }
  s.moveTo(r, 0); s.lineTo(w - r, 0); s.quadraticCurveTo(w, 0, w, r); s.lineTo(w, h - r); s.quadraticCurveTo(w, h, w - r, h);
  s.lineTo(r, h); s.quadraticCurveTo(0, h, 0, h - r); s.lineTo(0, r); s.quadraticCurveTo(0, 0, r, 0);
  return s;
}
/** Board faces: UVs from the board's own x / y so the pictures sit exactly; groups 0 = top, 1 = sides, 2 = bottom. */
function mapFaces(geo, B, T) {
  const pos = geo.attributes.position, nrm = geo.attributes.normal, uv = geo.attributes.uv;
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i) + B.w / 2, z = pos.getZ(i) + B.h / 2;
    uv.setXY(i, x / B.w, 1 - z / B.h);
  }
  uv.needsUpdate = true;
  // regroup by normal: up = 0, down = 2, sides = 1
  const idx = geo.index ? geo.index.array : null, n = idx ? idx.length : pos.count;
  geo.clearGroups();
  let cur = -1, start = 0;
  const kind = (t) => { const a = idx ? idx[t] : t; const ny = nrm.getY(a); return ny > 0.5 ? 0 : ny < -0.5 ? 2 : 1; };
  for (let t = 0; t < n; t += 3) {
    const k = kind(t);
    if (k !== cur) { if (cur >= 0) geo.addGroup(start, t - start, cur); cur = k; start = t; }
  }
  if (cur >= 0) geo.addGroup(start, n - start, cur);
  void T;
}

function std(color, o) { return new THREE.MeshStandardMaterial(Object.assign({ color, roughness: 0.55, metalness: 0.05 }, o || {})); }
const METAL = () => std(0xc9ccd0, { metalness: 0.85, roughness: 0.3 });
const BLACK = () => std(0x1c1d20, { roughness: 0.5 });
function cyl(r1, r2, h, mat, seg) { return new THREE.Mesh(new THREE.CylinderGeometry(r1, r2, h, seg || 24), mat); }
function box(w, h, d, mat) { return new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat); }
function lead(g, x1, z1, y1, x2, z2, y2) {
  const a = new THREE.Vector3(x1, y1, z1), b = new THREE.Vector3(x2, y2, z2), len = a.distanceTo(b);
  if (len < 0.01) return;
  const m = cyl(0.28, 0.28, len, METAL(), 8);
  m.position.copy(a).add(b).multiplyScalar(0.5);
  m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), b.clone().sub(a).normalize());
  g.add(m);
}
const BAND = [0x111111, 0x7b3f00, 0xd0021b, 0xf5a623, 0xf8e71c, 0x2e9e44, 0x2f6fd6, 0x8b4fd6, 0x8c8c8c, 0xf4f4f4];

function makeBody(g, pt, f) {
  const b = f.body || {}, pads = f.pads;
  const P0 = pads[0], P1 = pads[1] || pads[0];
  switch (b.t) {
    case 'axial': {
      const y = b.D / 2 + 0.8, body = cyl(b.D / 2, b.D / 2, b.L, std(b.color === '#26262a' ? 0x26262a : 0xd8c49a, { roughness: 0.6 }));
      body.rotation.z = Math.PI / 2; body.position.y = y; g.add(body);
      if (pt.lib === 'res') {
        const bs = api.P.bands(pt.val) || [];
        bs.forEach((bd, i) => {
          const col = new THREE.Color(bd.c);
          const ring = cyl(b.D / 2 + 0.03, b.D / 2 + 0.03, 0.55, std(col));
          ring.rotation.z = Math.PI / 2; ring.position.set(i < 3 ? -b.L / 2 + 1.0 + i * 0.85 : b.L / 2 - 1.0, y, 0); g.add(ring);
        });
      } else if (b.band) {
        const ring = cyl(b.D / 2 + 0.03, b.D / 2 + 0.03, 0.7, std(0xd8d8d8));
        ring.rotation.z = Math.PI / 2; ring.position.set(P0.x < 0 ? -b.L / 2 + 0.7 : b.L / 2 - 0.7, y, 0); g.add(ring);
      }
      lead(g, P0.x, P0.y, -1.2, P0.x, P0.y, y); lead(g, P0.x, P0.y, y, -b.L / 2, 0, y);
      lead(g, P1.x, P1.y, -1.2, P1.x, P1.y, y); lead(g, P1.x, P1.y, y, b.L / 2, 0, y);
      break;
    }
    case 'disc': {
      const y = b.D / 2 + 2, d = cyl(b.D / 2, b.D / 2, b.T, std(new THREE.Color(b.color || '#d9822b'), { roughness: 0.7 }));
      d.rotation.x = Math.PI / 2; d.position.y = y; g.add(d);
      lead(g, P0.x, P0.y, -1.2, P0.x * 0.6, 0, y - b.D / 2 + 0.5); lead(g, P1.x, P1.y, -1.2, P1.x * 0.6, 0, y - b.D / 2 + 0.5);
      break;
    }
    case 'radial': {
      const can = cyl(b.D / 2, b.D / 2, b.H, std(0x1e2f55, { roughness: 0.45 }));
      can.position.y = b.H / 2 + 0.4; g.add(can);
      const stripe = box(b.D * 0.32, b.H * 0.96, 0.2, std(0xcfd7e6));
      stripe.position.set(b.D * 0.25, b.H / 2 + 0.4, b.D / 2 - 0.02); stripe.rotation.y = 0; g.add(stripe);
      const topc = cyl(b.D / 2 - 0.2, b.D / 2 - 0.2, 0.1, METAL()); topc.position.y = b.H + 0.45; g.add(topc);
      lead(g, P0.x, P0.y, -1.2, P0.x, P0.y, 0.5); lead(g, P1.x, P1.y, -1.2, P1.x, P1.y, 0.5);
      break;
    }
    case 'led': {
      const col = LED[pt.lib === 'irled' ? 'ir' : (pt.color || 'red')] || LED.red;
      const mat = std(col, { emissive: col, emissiveIntensity: pt.lib === 'irled' ? 0.15 : 0.55, transparent: true, opacity: 0.88, roughness: 0.2 });
      const rim = cyl(b.D / 2 + 0.4, b.D / 2 + 0.4, 1.0, mat); rim.position.y = 1.0 + 0.5; g.add(rim);
      const bodyH = b.H - b.D / 2 - 1.0, cylm = cyl(b.D / 2, b.D / 2, bodyH, mat); cylm.position.y = 1.0 + 1.0 + bodyH / 2; g.add(cylm);
      const dome = new THREE.Mesh(new THREE.SphereGeometry(b.D / 2, 20, 10, 0, Math.PI * 2, 0, Math.PI / 2), mat); dome.position.y = 2.0 + bodyH; g.add(dome);
      lead(g, P0.x, P0.y, -1.2, P0.x, P0.y, 1.0); lead(g, P1.x, P1.y, -1.2, P1.x, P1.y, 1.0);
      break;
    }
    case 'to92': {
      const m = new THREE.Mesh(new THREE.CylinderGeometry(2.4, 2.4, 4.8, 24, 1, false, Math.PI / 2, Math.PI), BLACK());
      m.position.y = 3 + 2.4; g.add(m);
      const flat = box(4.8, 4.8, 0.05, BLACK()); flat.position.set(0, 5.4, 0); g.add(flat);
      pads.forEach((p) => lead(g, p.x, p.y, -1.2, p.x * 0.5, 0, 3));
      break;
    }
    case 'to220': {
      const bd = box(10, 9, 4.4, BLACK()); bd.position.set(0, 3 + 4.5, -0.4); g.add(bd);
      const tab = box(10, 6.5, 1.3, METAL()); tab.position.set(0, 3 + 9 + 3.25, -1.95); g.add(tab);
      const hole = cyl(1.8, 1.8, 1.4, std(0x0b1016)); hole.rotation.x = Math.PI / 2; hole.position.set(0, 3 + 9 + 3.4, -1.95); g.add(hole);
      pads.forEach((p) => lead(g, p.x, p.y, -1.2, p.x, 0, 3.2));
      break;
    }
    case 'dip': {
      const bd = box(6.35, 3.3, 9.8, BLACK()); bd.position.y = 0.8 + 1.65; g.add(bd);
      const dot = cyl(0.45, 0.45, 0.05, std(0x55585e)); dot.position.set(-2.2, 0.8 + 3.31, -4.0); g.add(dot);
      pads.forEach((p) => { const pin = box(0.5, 2.6, 0.3, METAL()); pin.position.set(p.x * 0.92, 1.0, p.y); g.add(pin); });
      break;
    }
    case 'hdr': {
      const n = b.n, L = n * 2.54, y0 = -(n - 1) * 1.27;
      if (b.female) {
        const bd = box(2.5, 8.5, L, BLACK()); bd.position.set(0, 4.25, 0); g.add(bd);
        for (let i = 0; i < n; i++) { const hl = box(0.9, 0.05, 0.9, std(0x050505)); hl.position.set(0, 8.53, y0 + i * 2.54); g.add(hl); }
      } else {
        const bd = box(2.5, 2.5, L, BLACK()); bd.position.set(0, 1.25, 0); g.add(bd);
        for (let i = 0; i < n; i++) { const pin = box(0.64, 8.5, 0.64, std(0xe8c15a, { metalness: 0.9, roughness: 0.3 })); pin.position.set(0, 4.25 - 1.2, y0 + i * 2.54); g.add(pin); }
      }
      break;
    }
    case 'term': {
      const bd = box(10.1, 10, 8.1, std(0x2f6fd6, { roughness: 0.6 })); bd.position.set(0, 5, 0); g.add(bd);
      pads.forEach((p) => { const sc = cyl(1.4, 1.4, 0.4, METAL()); sc.position.set(p.x, 10.2, p.y - 0.8); g.add(sc); const sl = box(2.0, 0.1, 0.35, std(0x333333)); sl.position.set(p.x, 10.42, p.y - 0.8); g.add(sl);
        const hole = box(2.6, 2.8, 0.1, std(0x050505)); hole.position.set(p.x, 3.2, 4.06); g.add(hole); });
      break;
    }
    case 'buzzer': {
      const bd = cyl(b.D / 2, b.D / 2, b.H, BLACK()); bd.position.y = b.H / 2; g.add(bd);
      const hl = cyl(1, 1, 0.05, std(0x050505)); hl.position.y = b.H + 0.03; g.add(hl);
      break;
    }
    case 'usb': {
      const bd = box(13.1, 6.8, 14, METAL()); bd.position.set(0, 3.4, -3.3); g.add(bd);
      const mouth = box(11.4, 4.6, 0.1, std(0x050505)); mouth.position.set(0, 3.4, -10.36); g.add(mouth);
      const tongue = box(9, 1.2, 0.12, std(0xf2f2f2)); tongue.position.set(0, 4.1, -10.42); g.add(tongue);
      break;
    }
    case 'esp32': {
      const n = 15, y0 = -17.78, hx = b.rows / 2;
      [-hx, hx].forEach((x) => { const h = box(2.5, 8.5, n * 2.54, BLACK()); h.position.set(x, 4.25, y0 + 7 * 2.54); g.add(h); });
      const pcb = box(b.rows + 2.9, 1.6, 51.5, std(0x1a1d24)); pcb.position.set(0, 8.5 + 2.5 + 0.8, 0); g.add(pcb);
      const shield = box(16, 2.6, 17.5, METAL()); shield.position.set(0, 8.5 + 2.5 + 1.6 + 1.3, -9.5); g.add(shield);
      const ant = box(16, 0.8, 6, std(0x101216)); ant.position.set(0, 8.5 + 2.5 + 1.6 + 0.4, -21.5); g.add(ant);
      const usb = box(7.5, 2.6, 5.5, METAL()); usb.position.set(0, 8.5 + 2.5 + 1.6 + 1.3, 23.5); g.add(usb);
      const pinsTop = std(0xe8c15a, { metalness: 0.9, roughness: 0.3 });
      for (let i = 0; i < n; i++) [-hx, hx].forEach((x) => { const pn = box(0.64, 3.5, 0.64, pinsTop); pn.position.set(x, 8.5 + 1.5, y0 + i * 2.54); g.add(pn); });
      break;
    }
    case 'slide': { // SS12D00: a small black box with a metal lever on top
      const bd = box(8.6, 3.6, 3.6, METAL()); bd.position.set(0, 1.8 + 0.4, 0); g.add(bd);
      const lever = box(2.0, 3.8, 1.6, BLACK()); lever.position.set(-0.6, 3.8 + 1.9, -0.2); g.add(lever);
      pads.forEach((p) => lead(g, p.x, p.y, -1.2, p.x, 0, 0.5));
      break;
    }
    case 'jack': { // 3.5 mm jack: a black block, its round mouth at the board edge (footprint y −)
      const bd = box(8.2, 5.0, 14, BLACK()); bd.position.set(0.1, 2.5, 5.8); g.add(bd);
      const barrel = cyl(3.0, 3.0, 4.0, BLACK()); barrel.rotation.x = Math.PI / 2; barrel.position.set(0.9, 3.0, -3.2); g.add(barrel);
      const mouth = cyl(1.8, 1.8, 0.1, std(0x050505)); mouth.rotation.x = Math.PI / 2; mouth.position.set(0.9, 3.0, -5.25); g.add(mouth);
      break;
    }
    case 'mic': { // electret capsule: a metal can with a black cloth face
      const can = cyl(b.D / 2, b.D / 2, b.H, METAL()); can.position.y = b.H / 2 + 0.3; g.add(can);
      const face = cyl(b.D / 2 - 0.6, b.D / 2 - 0.6, 0.1, std(0x111111, { roughness: 0.9 })); face.position.y = b.H + 0.36; g.add(face);
      pads.forEach((p) => lead(g, p.x, p.y, -1.2, p.x, p.y, 0.4));
      break;
    }
    case 'irrx': {
      const bd = box(6, 7, 4.2, BLACK()); bd.position.set(0, 3 + 3.5, 0); g.add(bd);
      const dome = new THREE.Mesh(new THREE.SphereGeometry(1.6, 16, 10), BLACK()); dome.position.set(0, 3 + 4.4, 2.1); g.add(dome);
      pads.forEach((p) => lead(g, p.x, p.y, -1.2, p.x * 0.8, 0, 3));
      break;
    }
    default: {
      const c = f.court, bd = box(c[2] - c[0], 3, c[3] - c[1], std(0x4b5563)); bd.position.set((c[0] + c[2]) / 2, 1.5, (c[1] + c[3]) / 2); g.add(bd);
    }
  }
  // solder joints underneath (silver cones) for every hole
  const T = (api.design().board.thick || 1.6);
  pads.forEach((p) => { if (!p.d) return; const j = cyl(Math.min(p.w, p.h) / 2 * 0.9, 0.3, 1.0, METAL(), 12); j.position.set(p.x, -T - 0.5, p.y); j.rotation.x = Math.PI; g.add(j); });
}

function label(text) {
  const cv = document.createElement('canvas'), ctx = cv.getContext('2d');
  cv.width = 128; cv.height = 64;
  ctx.fillStyle = 'rgba(14,20,28,.88)'; ctx.fillRect(0, 8, 128, 48);
  ctx.strokeStyle = '#74e4ff'; ctx.lineWidth = 3; ctx.strokeRect(2, 10, 124, 44);
  ctx.fillStyle = '#dceef5'; ctx.font = '600 30px ' + FONT; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText(text, 64, 33);
  const t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: false }));
  s.scale.set(6, 3, 1);
  s.renderOrder = 10;
  return s;
}

function tap(e) {
  const r = renderer.domElement.getBoundingClientRect();
  if (xr.on && !xr.placed) { api.onXrTap && api.onXrTap((e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height); return; }
  const m = new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
  const ray = new THREE.Raycaster();
  ray.setFromCamera(m, camera);
  const hits = ray.intersectObjects(Array.from(bodies.values()).filter((b) => b.visible), true);
  let ref = null;
  if (xr.on && !hits.length) { api.onXrTap && api.onXrTap((e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height); return; } // (📏 a tap on the table: the board goes there)
  if (hits.length) { let o = hits[0].object; while (o && !o.userData.ref) o = o.parent; ref = o ? o.userData.ref : null; }
  if (picked) bodies.get(picked) && glow(bodies.get(picked), false);
  picked = ref;
  if (ref) glow(bodies.get(ref), true);
  api.onTap(ref);
}
function glow(g, on) {
  if (pulse.size && guideSt) return; // (🔧 the guide's own glow is showing)
  g.traverse((o) => {
    if (!o.isMesh || !o.material || !o.material.emissive) return;
    if (on) { o.userData.e = [o.material.emissive.getHex(), o.material.emissiveIntensity]; o.material.emissive.setHex(0x1e6a80); o.material.emissiveIntensity = 0.9; }
    else if (o.userData.e) { o.material.emissive.setHex(o.userData.e[0]); o.material.emissiveIntensity = o.userData.e[1]; }
  });
}
function dispose(o) {
  o.traverse((x) => {
    if (x.geometry) x.geometry.dispose();
    const ms = Array.isArray(x.material) ? x.material : x.material ? [x.material] : [];
    ms.forEach((m) => { if (m.map) m.map.dispose(); m.dispose(); });
  });
}

// ================================================================ 🔧 the soldering guide: what is on the board so far, what goes on now

let guideSt = null, fly = null;
const pulse = new Map(); // material → its own glow (given back when the step changes)

/** g: {done: [refs soldered], cur: [refs now], all: every part, none: the bare board} or null (the guide is off). */
export function guide(g, move) {
  guideSt = g ? { done: new Set(g.done || []), cur: (g.cur || []).slice(), all: !!g.all, none: !!g.none, bottom: !!g.bottom } : null;
  if (renderer) applyGuide(move !== false);
}
function applyGuide(move) {
  pulse.forEach((o, m) => { m.emissive.setHex(o[0]); m.emissiveIntensity = o[1]; });
  pulse.clear();
  if (!guideSt) {
    if (fill) fill.intensity = 0.7;
    bodies.forEach((b) => { b.visible = true; });
    labelOf.forEach((s) => { s.visible = showLabels; });
    return;
  }
  const g = guideSt;
  if (fill) fill.intensity = g.bottom ? 1.9 : 0.7;
  bodies.forEach((b, ref) => { b.visible = g.all || g.cur.indexOf(ref) >= 0 || (!g.none && g.done.has(ref)); });
  labelOf.forEach((s, ref) => { s.visible = g.cur.indexOf(ref) >= 0; });
  g.cur.forEach((ref) => {
    const b = bodies.get(ref);
    if (b) b.traverse((o) => { if (o.isMesh && o.material && o.material.emissive && !pulse.has(o.material)) pulse.set(o.material, [o.material.emissive.getHex(), o.material.emissiveIntensity]); });
  });
  if (move && !xr.on) focus(g.cur, g.bottom);
}
/** The camera to these parts (from above, or from below for the joints); the whole board when none. */
function focus(refs, bottom) {
  const D = api.design(), full = Math.hypot(D.board.w, D.board.h);
  const bb = new THREE.Box3();
  (refs || []).forEach((r) => { const b = bodies.get(r); if (b) bb.expandByObject(b); });
  let q1, d;
  if (bb.isEmpty()) { q1 = new THREE.Vector3(0, 0, 0); d = (full / 2 + 4) / Math.tan((camera.fov / 2) * Math.PI / 180) / Math.min(1, camera.aspect) * 0.82; }
  else {
    q1 = bb.getCenter(new THREE.Vector3());
    if (bottom) q1.y = -(D.board.thick || 1.6);
    d = Math.min(140, Math.max(55, bb.getSize(new THREE.Vector3()).length() * 2.4, full * 0.75));
  }
  const dir = bottom ? new THREE.Vector3(0.08, -1, 0.2) : new THREE.Vector3(0.3, 0.85, 0.55);
  dir.normalize();
  fly = { t0: performance.now(), ms: 650, p0: camera.position.clone(), q0: controls.target.clone(), q1, p1: q1.clone().addScaledVector(dir, d) };
}

// ================================================================ 📦 the box: the same shapes on the screen and in the STL files

let boxPlan = null, boxG = null, lidM = null, lidT = 0, lidLift = 30, boxOpen = true, boxSee = false;
const PLA = { base: 0xdfe3e8, lid: 0xc9d3dd };

/** plan from PcbMake.boxPlan (null takes the box away); look: {open, see}. */
export function boxShow(plan, look) {
  boxPlan = plan || null;
  if (look) { if ('open' in look) boxOpen = !!look.open; if ('see' in look) boxSee = !!look.see; }
  if (renderer) buildBox();
}
export function boxLook(look) {
  if ('open' in look) boxOpen = !!look.open;
  if ('see' in look) { boxSee = !!look.see; if (boxG) boxG.traverse((o) => { if (o.isMesh) seeThrough(o.material); }); }
}
function seeThrough(m) { m.transparent = boxSee; m.opacity = boxSee ? 0.33 : 1; m.depthWrite = !boxSee; m.needsUpdate = true; }

function buildBox() {
  if (boxG) { boxG.parent && boxG.parent.remove(boxG); dispose(boxG); boxG = null; }
  lidM = null;
  if (!boxPlan || !root) return;
  const D = api.design(), B = D.board, T = B.thick || 1.6, b = boxPlan.box;
  const BT = new THREE.Matrix4().set(1, 0, 0, -B.w / 2, 0, 0, 1, -(b.zB + T / 2), 0, 1, 0, -B.h / 2, 0, 0, 0, 1);
  boxG = new THREE.Group();
  const parts = window.PcbMake.solids(boxPlan);
  ['base', 'lid'].forEach((piece) => {
    const geos = parts.filter((s) => s.piece === piece).map((s) => placed(s, BT));
    const g = merge(geos);
    g.computeVertexNormals();
    const m = new THREE.MeshStandardMaterial({ color: PLA[piece], roughness: 0.72, metalness: 0.0, side: THREE.DoubleSide });
    seeThrough(m);
    const mesh = new THREE.Mesh(g, m);
    mesh.userData.box = piece;
    mesh.raycast = () => {}; // (taps go to the parts inside)
    if (piece === 'lid') { lidM = mesh; lidLift = Math.max(20, boxPlan.size.h * 0.85); mesh.position.y = lidT * lidLift; }
    boxG.add(mesh);
  });
  root.add(boxG);
  if (xr.on) xrBase();
}

/** One solid as a shape pushed out (three's ExtrudeGeometry), then put where it belongs by the matrix (inside out fixed). */
function placed(s, M) {
  let shape, depth, local;
  const Z = (z0) => new THREE.Matrix4().makeTranslation(0, 0, z0);
  if (s.t === 'plate' || s.t === 'block') {
    shape = rect(s.x0, s.y0, s.x1, s.y1);
    (s.holes || []).forEach((h) => shape.holes.push(h.r ? circle(h.x, h.y, h.r) : rect(h.x0, h.y0, h.x1, h.y1)));
    depth = s.z1 - s.z0; local = Z(s.z0);
  } else if (s.t === 'tube') {
    shape = circle(s.x, s.y, s.r);
    shape.holes.push(circle(s.x, s.y, s.ri));
    depth = s.z1 - s.z0; local = Z(s.z0);
  } else if (s.t === 'wall') {
    shape = rect(s.u0, s.z0, s.u1, s.z1);
    (s.holes || []).forEach((h) => shape.holes.push(h.r ? circle(h.uc, h.zc, h.r) : rect(h.u0, h.z0, h.u1, h.z1)));
    depth = s.w1 - s.w0;
    // (u, v, w) → board (x, y, z): N / S walls run along x, E / W along y
    local = s.side === 'N' || s.side === 'S'
      ? new THREE.Matrix4().set(1, 0, 0, 0, 0, 0, 1, s.w0, 0, 1, 0, 0, 0, 0, 0, 1)
      : new THREE.Matrix4().set(0, 0, 1, s.w0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);
  }
  let g = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: false, curveSegments: 28 });
  if (g.index) g = g.toNonIndexed();
  const all = new THREE.Matrix4().multiplyMatrices(M, local);
  g.applyMatrix4(all);
  if (all.determinant() < 0) flip(g);
  return g;
}
function rect(x0, y0, x1, y1) { const s = new THREE.Shape(); s.moveTo(x0, y0); s.lineTo(x1, y0); s.lineTo(x1, y1); s.lineTo(x0, y1); s.lineTo(x0, y0); return s; }
function circle(x, y, r) { const s = new THREE.Shape(); s.absarc(x, y, r, 0, Math.PI * 2, false); return s; }
function flip(g) {
  ['position', 'normal', 'uv'].forEach((n) => {
    const a = g.getAttribute(n);
    if (!a) return;
    const k = a.itemSize, arr = a.array;
    for (let t = 0; t < a.count; t += 3) for (let j = 0; j < k; j++) { const i1 = (t + 1) * k + j, i2 = (t + 2) * k + j, v = arr[i1]; arr[i1] = arr[i2]; arr[i2] = v; }
    a.needsUpdate = true;
  });
}
function merge(geos) {
  let n = 0;
  geos.forEach((g) => { n += g.getAttribute('position').count; });
  const pos = new Float32Array(n * 3);
  let o = 0;
  geos.forEach((g) => { const a = g.getAttribute('position').array; pos.set(a, o); o += a.length; g.dispose(); });
  const out = new THREE.BufferGeometry();
  out.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  return out;
}

/** 📦 The two STL files: base as it stands, lid turned over (its top face on the printer's bed); millimetres, z up, from 0. */
export function boxStl(plan) {
  const parts = window.PcbMake.solids(plan), b = plan.box;
  const BS = new THREE.Matrix4().set(1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1); // board (y down) → printer (y up, z up)
  const turn = new THREE.Matrix4().makeRotationX(Math.PI).multiply(BS);             // the lid upside down
  const out = {};
  ['base', 'lid'].forEach((piece) => {
    const g = merge(parts.filter((s) => s.piece === piece).map((s) => placed(s, piece === 'lid' ? turn : BS)));
    const a = g.getAttribute('position').array, mn = [Infinity, Infinity, Infinity];
    for (let i = 0; i < a.length; i += 3) for (let j = 0; j < 3; j++) if (a[i + j] < mn[j]) mn[j] = a[i + j];
    for (let i = 0; i < a.length; i += 3) for (let j = 0; j < 3; j++) a[i + j] = Math.round((a[i + j] - mn[j]) * 1000) / 1000;
    out[piece] = window.PcbMake.stl(a, piece);
    out[piece + 'Tris'] = a.length / 9;
    g.dispose();
  });
  void b;
  return out;
}

// ================================================================ 📏 the board at its real size on his table (the phone's AR)

const xr = { on: false, placed: false, yaw: 0, a: new THREE.Matrix4(), v: new THREE.Matrix4(), reticle: null, base: new THREE.Vector3(), size: null };

export function xrOn(on) {
  if (!renderer) return;
  xr.on = !!on;
  xr.placed = false;
  fly = null;
  if (xr.on) {
    controls.enabled = false;
    controls.autoRotate = false;
    camera.matrixAutoUpdate = false;
    scene.background = null;
    renderer.setClearColor(0x000000, 0);
    if (root) { root.matrixAutoUpdate = false; root.visible = false; }
    if (!xr.reticle) {
      xr.reticle = new THREE.Mesh(new THREE.RingGeometry(0.9, 1, 56).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: 0x4fd2ff, transparent: true, opacity: 0.85, depthTest: false }));
      xr.reticle.matrixAutoUpdate = false;
      xr.reticle.renderOrder = 40;
      xr.reticle.raycast = () => {};
      scene.add(xr.reticle);
    }
    xr.reticle.visible = false;
    xrBase();
  } else {
    controls.enabled = true;
    camera.matrixAutoUpdate = true;
    scene.background = new THREE.Color(BG);
    if (root) { root.matrixAutoUpdate = true; root.position.set(0, 0, 0); root.rotation.set(0, 0, 0); root.scale.set(1, 1, 1); root.updateMatrix(); root.visible = true; }
    if (xr.reticle) xr.reticle.visible = false;
    resize();
    view('side');
  }
}
export function xrTurn(deg) { xr.yaw += (deg || 90) * Math.PI / 180; }
/** Where the board's middle and lowest point are (so it stands on the table), and how big it is. */
function xrBase() {
  if (!root) return;
  const m = root.matrix.clone();
  root.matrix.identity();
  root.updateMatrixWorld(true);
  const bb = new THREE.Box3();
  root.traverse((o) => { if (o.isMesh && o.visible !== false && o.geometry) { o.geometry.computeBoundingBox(); bb.union(o.geometry.boundingBox.clone().applyMatrix4(o.matrixWorld)); } });
  root.matrix.copy(m);
  if (bb.isEmpty()) return;
  const c = bb.getCenter(new THREE.Vector3());
  xr.base.set(c.x, bb.min.y, c.z);
  xr.size = bb.getSize(new THREE.Vector3());
}
/** Each picture from Jarvis: the camera's view and lens, where the board stands (anchor) and the ring where it would go. */
export function xrPose(m) {
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
  if (xr.placed && m.fresh === true) { // (just put down: it faces him)
    const eye = new THREE.Vector3().setFromMatrixPosition(new THREE.Matrix4().copy(xr.v).invert());
    eye.applyMatrix4(new THREE.Matrix4().copy(xr.a).invert());
    if (Math.hypot(eye.x, eye.z) > 1e-3) xr.yaw = Math.atan2(eye.x, eye.z);
  }
  if (root) root.visible = xr.placed;
  if (xr.reticle) {
    if (ok16(m.r) && !xr.placed) {
      const D = api.design(), s = Math.max(D.board.w, D.board.h) / 2 * 0.001 * 1.1;
      xr.reticle.matrix.fromArray(m.r).multiply(new THREE.Matrix4().makeScale(s, s, s));
      xr.reticle.visible = true;
    } else xr.reticle.visible = false;
  }
}
function placeXr() {
  if (!root || !xr.placed) return;
  // anchor (metres) × his turn × millimetres → metres × the board's middle, lowest point at the anchor
  const T = new THREE.Matrix4().makeTranslation(-xr.base.x, -xr.base.y, -xr.base.z);
  const S = new THREE.Matrix4().makeScale(0.001, 0.001, 0.001);
  const R = new THREE.Matrix4().makeRotationY(xr.yaw);
  root.matrix.copy(xr.a).multiply(R).multiply(S).multiply(T);
  root.matrixWorldNeedsUpdate = true;
}
export function xrSize() { return xr.size ? { w: Math.round(xr.size.x * 10) / 10, d: Math.round(xr.size.z * 10) / 10, h: Math.round(xr.size.y * 10) / 10 } : null; }
