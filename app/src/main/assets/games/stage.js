// 🎮 The sealed game stage of Jarvis's game studio (Anil, 16 Nov). The game the AI writes arrives in parts by postMessage
// ({t:'part', id, code}); each part's code runs once, in order, with G (this kit) and S (the game's own shared state).
// The parts register what the game does (start, every frame, drawing, touches); this page runs the game loop, draws the
// score bar, the ⏸ menu and the end card, and catches any error with the part it came from (sent back to Jarvis, which
// asks the AI to repair only that part). Before he plays, Jarvis has the stage play the game by itself for a few seconds
// (taps, swipes, buttons, tilt) so a broken game is caught and repaired first. No network, no storage, no way out:
// this page lives in a sandboxed frame and only talks to its parent page.
'use strict';

const post = (m) => { try { parent.postMessage(m, '*'); } catch (e) { /* (nothing to tell) */ } };
const cv = document.getElementById('c');
const ctx = cv.getContext('2d');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const FONT = '"Noto Sans Telugu", system-ui, "Noto Color Emoji", sans-serif';
const EMOJI = '"Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji", sans-serif';
const CODE_LINE0 = 3; // (a part's first line, as the browser counts it inside the function made from it)
const cleanId = (s) => (String(s || 'part').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'part');

// ------------------------------------------------------------------ the play area (game units)

let portrait = true, aspect = 2.0;
let LW = 360, LH = 720;
let dpr = 1, scale = 1, offX = 0, offY = 0, vw = 1, vh = 1;

function area() {
  const long = Math.round(Math.max(560, Math.min(820, 360 * (aspect || 2))));
  if (portrait) { LW = 360; LH = long; } else { LW = long; LH = 360; }
  G.W = LW; G.H = LH; G.portrait = portrait;
  fit();
}

function fit() {
  vw = Math.max(1, window.innerWidth || 1);
  vh = Math.max(1, window.innerHeight || 1);
  dpr = Math.min(window.devicePixelRatio || 1, 2.5);
  const w = Math.round(vw * dpr), h = Math.round(vh * dpr);
  if (cv.width !== w) cv.width = w;
  if (cv.height !== h) cv.height = h;
  scale = Math.min(vw / LW, vh / LH);
  offX = (vw - LW * scale) / 2;
  offY = (vh - LH * scale) / 2;
}

// ------------------------------------------------------------------ the game's parts and what they registered

const S = {};                 // the game's own shared state: parts keep their things here
const order = [];             // part ids, in the order they came
const KINDS = ['start', 'tick', 'draw', 'tap', 'down', 'move', 'up', 'swipe'];
const hooks = {};
KINDS.forEach((k) => { hooks[k] = []; });
let presses = [];             // {id, btn, fn, temp}
const buttons = new Map();    // button id -> {id, label, x, y, r, part, held, pid, color}
let timers = [];              // {id, at, delay, every, fn, dead, temp}
let cur = null;               // the part whose own code is running now (what it registers belongs to it)
let running = null;           // the part whose handler is running now
let loading = false;
let seq = 0;

let mode = 'idle';            // idle | preview | test | play | paused | over | broken
let best = 0, howText = '', titleText = '', howT = 0;
let gt = 0;                   // game time, seconds (stops while paused)
let ended = null;             // {won, text} once the game called G.over / G.win
let crashed = null;           // {id, msg} the first error
let testErr = null;
let bgFill = null;
let muted = false;
let newBest = false;
let baseLives = null, baseLevel = 0; // (lives / level a part set as it loaded: every new start begins with them)

function partIndex(id) { const i = order.indexOf(id); return i < 0 ? 1e6 : i; }

function sortHooks() {
  for (const k of KINDS) hooks[k].sort((a, b) => partIndex(a.id) - partIndex(b.id) || a.n - b.n);
  presses.sort((a, b) => partIndex(a.id) - partIndex(b.id) || a.n - b.n);
}

function owner() { return cur || running || 'game'; }

function reg(kind, fn) {
  if (typeof fn !== 'function') throw new Error('G.on' + kind.charAt(0).toUpperCase() + kind.slice(1) + '(...) needs a function');
  hooks[kind].push({ id: owner(), fn, n: seq++, temp: !loading });
}

function dropPart(id) {
  for (const k of KINDS) hooks[k] = hooks[k].filter((h) => h.id !== id);
  presses = presses.filter((h) => h.id !== id);
  for (const [k, b] of [...buttons]) if (b.part === id) buttons.delete(k);
  timers = timers.filter((t) => t.id !== id);
}

function errText(e) {
  let msg = (e && e.message) ? String(e.message) : String(e);
  const st = e && typeof e.stack === 'string' ? e.stack : '';
  const m = st.match(/<anonymous>:(\d+):(\d+)/);
  if (m) { const line = +m[1] - CODE_LINE0; if (line > 0) msg += ' (line ' + line + ' of the part)'; }
  return msg.slice(0, 300);
}

/** One of the game's handlers; an error stops the game and names the part it came from. */
function call(h, where, a, b, c) {
  if (crashed) return undefined;
  const was = running;
  running = h.id;
  try { return h.fn(a, b, c); }
  catch (e) { crash(h.id, where, e); return undefined; }
  finally { running = was; }
}

function crash(id, where, e) {
  if (crashed) return;
  crashed = { id, msg: 'in ' + where + ': ' + errText(e) };
  if (mode === 'test') { testErr = crashed; return; }
  if (mode === 'play' || mode === 'paused' || mode === 'over') {
    mode = 'broken';
    post({ t: 'err', id, msg: crashed.msg, state: 'play' });
    kick();
  }
}

// ------------------------------------------------------------------ sound (made here; nothing to load) and a buzz

let ac = null, soundsThisSecond = 0, soundSecond = 0;

function audio() {
  if (muted) return null;
  if (!ac) {
    try { const A = window.AudioContext || window.webkitAudioContext; if (!A) return null; ac = new A(); }
    catch (e) { return null; }
  }
  if (ac.state === 'suspended') { try { ac.resume(); } catch (e) { /* (the next touch) */ } }
  return ac;
}

function tone(freq, dur, type, vol, slide, delay) {
  const a = audio();
  if (!a || a.state !== 'running') return;
  const t0 = a.currentTime + (delay || 0);
  const o = a.createOscillator(), g = a.createGain();
  o.type = type || 'square';
  o.frequency.setValueAtTime(Math.max(30, freq), t0);
  if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(30, freq * slide), t0 + dur);
  g.gain.setValueAtTime(Math.min(0.3, vol || 0.1), t0);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  o.connect(g); g.connect(a.destination);
  o.start(t0); o.stop(t0 + dur + 0.03);
}

function noise(dur, vol) {
  const a = audio();
  if (!a || a.state !== 'running') return;
  const n = Math.max(1, Math.floor(a.sampleRate * dur));
  const buf = a.createBuffer(1, n, a.sampleRate), d = buf.getChannelData(0);
  for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n);
  const s = a.createBufferSource(), g = a.createGain();
  s.buffer = buf; g.gain.value = Math.min(0.3, vol || 0.1);
  s.connect(g); g.connect(a.destination); s.start();
}

const SOUNDS = {
  tap: () => tone(660, 0.06, 'square', 0.07),
  click: () => tone(1200, 0.03, 'square', 0.05),
  coin: () => { tone(988, 0.07, 'square', 0.09); tone(1319, 0.14, 'square', 0.09, 0, 0.07); },
  jump: () => tone(300, 0.18, 'square', 0.09, 2.4),
  pop: () => tone(520, 0.08, 'triangle', 0.14, 1.9),
  hit: () => { tone(170, 0.22, 'sawtooth', 0.12, 0.45); noise(0.12, 0.08); },
  whoosh: () => noise(0.22, 0.07),
  level: () => [523, 784, 1047].forEach((f, i) => tone(f, 0.12, 'square', 0.08, 0, i * 0.09)),
  win: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.16, 'square', 0.09, 0, i * 0.12)),
  over: () => [392, 330, 262, 196].forEach((f, i) => tone(f, 0.2, 'triangle', 0.11, 0, i * 0.16)),
};

function soundOk() {
  if (mode === 'test' || mode === 'preview' || muted) return false;
  const s = Math.floor(performance.now() / 1000);
  if (s !== soundSecond) { soundSecond = s; soundsThisSecond = 0; }
  return ++soundsThisSecond <= 24;
}

let buzzAt = 0;

// ------------------------------------------------------------------ the kit, G

const G = {
  W: LW, H: LH, portrait: true, t: 0, dt: 0,
  score: 0, lives: null, level: 0, best: 0, hud: true,
  tilt: { x: 0, y: 0 },
  pointer: { x: 0, y: 0, down: false },
  colors: { cyan: '#4fd2ff', blue: '#3a7bff', green: '#3ddc84', yellow: '#ffd23f', orange: '#ff8c42', red: '#ff4d5e',
    pink: '#ff6fb5', purple: '#9b6bff', white: '#ffffff', grey: '#8a94a6', dark: '#0b1020', night: '#05070d' },
};

G.onStart = (fn) => reg('start', fn);
G.onTick = (fn) => reg('tick', fn);
G.onDraw = (fn) => reg('draw', fn);
G.onTap = (fn) => reg('tap', fn);
G.onDown = (fn) => reg('down', fn);
G.onMove = (fn) => reg('move', fn);
G.onUp = (fn) => reg('up', fn);
G.onSwipe = (fn) => reg('swipe', fn);

G.button = (id, label, x, y, r, color) => {
  const k = String(id);
  buttons.set(k, { id: k, label: String(label == null ? '' : label).slice(0, 8), x: +x || 0, y: +y || 0, r: Math.max(18, Math.min(80, +r || 34)),
    part: owner(), held: false, pid: null, color: color || null });
};
G.held = (id) => { const b = buttons.get(String(id)); return !!(b && b.held); };
G.onPress = (id, fn) => {
  if (typeof fn !== 'function') throw new Error('G.onPress(id, fn) needs a function');
  presses.push({ id: owner(), btn: String(id), fn, n: seq++, temp: !loading });
};

G.addScore = (n, x, y, color) => {
  const v = +n || 0;
  G.score += v;
  if (typeof x === 'number' && typeof y === 'number') G.floatText((v >= 0 ? '+' : '') + v, x, y, color || '#ffd23f');
};
G.over = (text) => { if (!ended) ended = { won: false, text: text == null ? '' : String(text).slice(0, 60) }; };
G.win = (text) => { if (!ended) ended = { won: true, text: text == null ? '' : String(text).slice(0, 60) }; };

function addTimer(sec, every, fn) {
  if (typeof fn !== 'function') throw new Error('G.after / G.every need a function');
  const d = Math.max(0, +sec || 0);
  const t = { id: owner(), at: gt + d, delay: d, every: every ? Math.max(0.02, d) : 0, fn, dead: false, temp: !loading, n: seq++ };
  timers.push(t);
  return { stop() { t.dead = true; } };
}
G.after = (sec, fn) => addTimer(sec, false, fn);
G.every = (sec, fn) => addTimer(sec, true, fn);

G.sound = (name) => { if (!soundOk()) return; const f = SOUNDS[name] || SOUNDS.tap; try { f(); } catch (e) { /* (no sound is fine) */ } };
G.beep = (freq, sec, type, vol) => { if (!soundOk()) return; try { tone(+freq || 440, Math.max(0.02, Math.min(1.5, +sec || 0.1)), type, vol); } catch (e) { /* */ } };
G.buzz = (ms) => {
  if (mode !== 'play') return;
  const now = performance.now();
  if (now - buzzAt < 180) return;
  buzzAt = now;
  post({ t: 'buzz', ms: Math.max(10, Math.min(300, Math.round(+ms || 40))) });
};

// maths
G.rand = (a, b) => (b === undefined ? Math.random() * (a === undefined ? 1 : a) : a + Math.random() * (b - a));
G.randInt = (a, b) => Math.floor(a + Math.random() * (b - a + 1));
G.pick = (arr) => (arr && arr.length ? arr[Math.floor(Math.random() * arr.length)] : undefined);
G.clamp = (v, a, b) => Math.max(a, Math.min(b, v));
G.lerp = (a, b, t) => a + (b - a) * t;
G.dist = (x1, y1, x2, y2) => Math.hypot(x2 - x1, y2 - y1);
G.angle = (x1, y1, x2, y2) => Math.atan2(y2 - y1, x2 - x1);
G.deg = (d) => d * Math.PI / 180;
const isCircle = (o) => o && typeof o.r === 'number' && typeof o.w !== 'number';
G.hit = (a, b) => {
  if (!a || !b) return false;
  const ac = isCircle(a), bc = isCircle(b);
  if (ac && bc) return Math.hypot(a.x - b.x, a.y - b.y) < a.r + b.r;
  if (!ac && !bc) return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  const c = ac ? a : b, r = ac ? b : a;
  const nx = Math.max(r.x, Math.min(c.x, r.x + r.w)), ny = Math.max(r.y, Math.min(c.y, r.y + r.h));
  return (c.x - nx) * (c.x - nx) + (c.y - ny) * (c.y - ny) < c.r * c.r;
};
G.inside = (px, py, o) => (isCircle(o) ? Math.hypot(px - o.x, py - o.y) <= o.r : px >= o.x && px <= o.x + o.w && py >= o.y && py <= o.y + o.h);
G.hsl = (h, s, l, a) => 'hsla(' + (+h || 0) + ',' + (s == null ? 80 : s) + '%,' + (l == null ? 60 : l) + '%,' + (a == null ? 1 : a) + ')';

// drawing (on the game's canvas, in game units)
G.bg = (fill) => { bgFill = fill || null; };
G.rect = (x, y, w, h, color, r) => {
  ctx.fillStyle = color || '#fff';
  if (r && r > 0 && ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, Math.min(r, Math.abs(w) / 2, Math.abs(h) / 2)); ctx.fill(); }
  else ctx.fillRect(x, y, w, h);
};
G.circle = (x, y, r, color) => { ctx.fillStyle = color || '#fff'; ctx.beginPath(); ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2); ctx.fill(); };
G.ring = (x, y, r, color, width) => { ctx.strokeStyle = color || '#fff'; ctx.lineWidth = width || 3; ctx.beginPath(); ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2); ctx.stroke(); };
G.line = (x1, y1, x2, y2, color, width) => { ctx.strokeStyle = color || '#fff'; ctx.lineWidth = width || 3; ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); };
G.poly = (pts, color) => {
  if (!pts || pts.length < 3) return;
  ctx.fillStyle = color || '#fff';
  ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
  ctx.closePath(); ctx.fill();
};
G.text = (str, x, y, o) => {
  o = o || {};
  ctx.font = (o.bold === false ? '500 ' : '700 ') + Math.max(6, +o.size || 20) + 'px ' + FONT;
  ctx.textAlign = o.align || 'center';
  ctx.textBaseline = o.base || 'middle';
  ctx.fillStyle = o.color || '#fff';
  ctx.fillText(String(str), x, y);
};
G.emoji = (e, x, y, size, rot) => {
  const s = Math.max(6, +size || 32);
  ctx.save();
  ctx.translate(x, y);
  if (rot) ctx.rotate(rot);
  ctx.font = s + 'px ' + EMOJI;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#fff';
  ctx.fillText(String(e), 0, s * 0.06);
  ctx.restore();
};
// 🏍️ (Anil, 28 Nov: "G చేయి") his own 3D model from the workshop as the game's hero: a picture with see-through corners (a data:
// PNG Jarvis sends with the reset), drawn inside size × size; without one, a smiling emoji stands in.
let heroImg = null;
G.heroReady = false;
function setHero(b64) {
  heroImg = null;
  G.heroReady = false;
  if (typeof b64 !== 'string' || !b64 || b64.length > 3000000 || !/^[A-Za-z0-9+/=\s]+$/.test(b64.slice(0, 200))) return Promise.resolve();
  return new Promise((res) => {
    const im = new Image();
    im.onload = () => { heroImg = im; G.heroReady = true; res(); };
    im.onerror = () => res();
    im.src = 'data:image/png;base64,' + b64.replace(/\s+/g, '');
  });
}
G.hero = (x, y, size, rot, flip) => {
  const s = Math.max(6, +size || 64);
  if (!heroImg) { G.emoji('🙂', x, y, s, rot); return; }
  const k = s / Math.max(heroImg.width, heroImg.height), w = heroImg.width * k, h = heroImg.height * k;
  ctx.save();
  ctx.translate(x, y);
  if (rot) ctx.rotate(rot);
  if (flip) ctx.scale(-1, 1);
  ctx.drawImage(heroImg, -w / 2, -h / 2, w, h);
  ctx.restore();
};
G.grad = (x1, y1, x2, y2, stops) => {
  const g = ctx.createLinearGradient(x1, y1, x2, y2);
  (stops || [[0, '#000'], [1, '#fff']]).forEach((s) => { try { g.addColorStop(s[0], s[1]); } catch (e) { /* (a bad colour is left out) */ } });
  return g;
};
G.glow = (color, blur) => { ctx.shadowColor = color || '#4fd2ff'; ctx.shadowBlur = blur == null ? 12 : blur; };
G.noGlow = () => { ctx.shadowBlur = 0; ctx.shadowColor = 'transparent'; };

// effects
const particles = [], floats = [];
let shakeT = 0, shakeP = 0, flashT = 0, flashC = '#fff';
G.burst = (x, y, color, n) => {
  const k = Math.max(1, Math.min(60, n == null ? 14 : n | 0));
  for (let i = 0; i < k && particles.length < 600; i++) {
    const a = Math.random() * Math.PI * 2, v = 60 + Math.random() * 200;
    particles.push({ x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, life: 0.45 + Math.random() * 0.45, age: 0, r: 2 + Math.random() * 3, c: color || '#ffd23f' });
  }
};
G.shake = (power, sec) => { shakeP = Math.max(shakeP, Math.min(24, power == null ? 6 : +power)); shakeT = Math.max(shakeT, Math.min(1.5, sec == null ? 0.25 : +sec)); };
G.flash = (color, sec) => { flashC = color || '#fff'; flashT = Math.min(1, sec == null ? 0.15 : +sec); };
G.floatText = (text, x, y, color) => { if (floats.length < 40) floats.push({ text: String(text).slice(0, 24), x, y, c: color || '#fff', age: 0 }); };

Object.freeze(G.colors);

// ------------------------------------------------------------------ the game: start, every frame, drawing

function resetGame() {
  for (const k of KINDS) hooks[k] = hooks[k].filter((h) => !h.temp); // (handlers made while playing go; the parts' own stay)
  presses = presses.filter((h) => !h.temp);
  timers = timers.filter((t) => !t.temp && !t.dead);
  gt = 0;
  for (const t of timers) t.at = t.delay;
  particles.length = 0;
  floats.length = 0;
  shakeT = flashT = 0;
  G.score = 0; G.lives = baseLives; G.level = baseLevel; G.t = 0; G.dt = 0;
  G.best = best;
  G.pointer.down = false;
  for (const b of buttons.values()) { b.held = false; b.pid = null; }
  pointers.clear();
  ended = null;
  crashed = null;
  newBest = false;
  for (const h of hooks.start.slice()) { call(h, 'G.onStart'); if (crashed) break; }
}

function step(dt) {
  gt += dt;
  G.t = gt;
  G.dt = dt;
  for (const h of hooks.tick.slice()) { call(h, 'G.onTick', dt, gt); if (crashed || ended) break; }
  if (!crashed && !ended) {
    for (const t of timers.slice()) {
      if (t.dead || gt < t.at) continue;
      if (t.every) { t.at += t.every; if (t.at < gt) t.at = gt + t.every; } else t.dead = true;
      call(t, t.every ? 'G.every' : 'G.after');
      if (crashed || ended) break;
    }
    timers = timers.filter((t) => !t.dead);
  }
  effects(dt);
}

function effects(dt) {
  for (let i = particles.length - 1; i >= 0; i--) {
    const p = particles[i];
    p.age += dt;
    if (p.age >= p.life) { particles.splice(i, 1); continue; }
    p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 240 * dt; p.vx *= 0.98;
  }
  for (let i = floats.length - 1; i >= 0; i--) { const f = floats[i]; f.age += dt; f.y -= 40 * dt; if (f.age > 0.9) floats.splice(i, 1); }
  if (shakeT > 0) { shakeT -= dt; if (shakeT <= 0) shakeP = 0; }
  if (flashT > 0) flashT -= dt;
  if (howT > 0) howT -= dt;
}

function defaults() {
  ctx.globalAlpha = 1;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.font = '700 20px ' + FONT;
  ctx.shadowBlur = 0;
  ctx.shadowColor = 'transparent';
}

let uiBtns = [];

function render() {
  fit();
  uiBtns = [];
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, cv.width, cv.height);
  ctx.setTransform(dpr * scale, 0, 0, dpr * scale, dpr * offX, dpr * offY);
  ctx.save();
  ctx.beginPath();
  ctx.rect(0, 0, LW, LH);
  ctx.clip();
  if (mode === 'idle') { cover(); ctx.restore(); return; }
  ctx.save();
  if (shakeT > 0 && mode !== 'preview') ctx.translate((Math.random() * 2 - 1) * shakeP, (Math.random() * 2 - 1) * shakeP);
  defaults();
  ctx.fillStyle = bgFill || '#0b1020';
  ctx.fillRect(-30, -30, LW + 60, LH + 60);
  for (const h of hooks.draw.slice()) {
    ctx.save();
    defaults();
    call(h, 'G.onDraw', ctx);
    ctx.restore();
    if (crashed) break;
  }
  defaults();
  for (const p of particles) { ctx.globalAlpha = Math.max(0, 1 - p.age / p.life); ctx.fillStyle = p.c; ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2); ctx.fill(); }
  ctx.globalAlpha = 1;
  for (const f of floats) {
    ctx.globalAlpha = Math.max(0, 1 - f.age / 0.9);
    ctx.font = '800 20px ' + FONT;
    ctx.lineWidth = 4; ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.strokeText(f.text, f.x, f.y);
    ctx.fillStyle = f.c; ctx.fillText(f.text, f.x, f.y);
  }
  ctx.globalAlpha = 1;
  ctx.restore();
  if (flashT > 0) { ctx.globalAlpha = Math.min(0.6, flashT * 4); ctx.fillStyle = flashC; ctx.fillRect(0, 0, LW, LH); ctx.globalAlpha = 1; }
  defaults();
  drawButtons();
  if (mode !== 'preview') hud();
  if (mode === 'play' && howT > 0 && howText) banner(howText, Math.min(1, howT));
  if (mode === 'paused') pausedCard();
  else if (mode === 'over') endCard();
  else if (mode === 'broken') brokenCard();
  ctx.restore();
}

function cover() {
  const g = ctx.createLinearGradient(0, 0, 0, LH);
  g.addColorStop(0, '#0d1a2b'); g.addColorStop(1, '#05070d');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, LW, LH);
  ctx.globalAlpha = 0.9;
  ctx.font = '84px ' + EMOJI;
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText('🎮', LW / 2, LH * 0.42);
  ctx.globalAlpha = 1;
  if (titleText) { ctx.font = '700 22px ' + FONT; ctx.fillStyle = '#dcefff'; ctx.fillText(titleText, LW / 2, LH * 0.42 + 78); }
}

function pill(x, y, text, color, alignRight) {
  ctx.font = '700 17px ' + FONT;
  const w = ctx.measureText(text).width + 22, h = 32, x0 = alignRight ? x - w : x;
  ctx.fillStyle = 'rgba(5,8,14,0.55)';
  ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(x0, y, w, h, 16); else ctx.rect(x0, y, w, h); ctx.fill();
  ctx.fillStyle = color || '#fff';
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  ctx.fillText(text, x0 + 11, y + h / 2 + 1);
  return w;
}

function hud() {
  if (mode === 'test') return;
  if (G.hud !== false) {
    let x = 10;
    x += pill(x, 10, '⭐ ' + Math.round(G.score || 0), '#fff') + 6;
    const b = Math.max(best, Math.round(G.score || 0));
    if (b > 0) x += pill(x, 10, '🏆 ' + b, '#ffd23f') + 6;
    if (typeof G.lives === 'number' && G.lives > 0) {
      const n = Math.min(6, Math.floor(G.lives));
      pill(x, 10, '❤️'.repeat(n) + (G.lives > 6 ? '+' : ''), '#fff');
    }
    if (G.level > 0) pill(LW - 58, 48, 'లెవెల్ ' + G.level, '#4fd2ff', true);
  }
  // ⏸ always (top right)
  const px = LW - 30, py = 26;
  ctx.fillStyle = 'rgba(5,8,14,0.55)';
  ctx.beginPath(); ctx.arc(px, py, 18, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.fillRect(px - 6, py - 7, 4, 14);
  ctx.fillRect(px + 2, py - 7, 4, 14);
  if (mode === 'play') uiBtns.push({ x: px - 26, y: py - 26, w: 52, h: 52, act: 'pause' });
}

function drawButtons() {
  for (const b of buttons.values()) {
    ctx.globalAlpha = 1;
    ctx.fillStyle = b.held ? 'rgba(79,210,255,0.38)' : (b.color || 'rgba(255,255,255,0.13)');
    ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2); ctx.fill();
    ctx.lineWidth = 2; ctx.strokeStyle = b.held ? 'rgba(155,231,255,0.9)' : 'rgba(255,255,255,0.35)'; ctx.stroke();
    if (b.label) {
      const emoji = /\p{Extended_Pictographic}/u.test(b.label);
      ctx.font = (emoji ? '' : '700 ') + Math.round(b.r * (emoji ? 0.9 : 0.6)) + 'px ' + (emoji ? EMOJI : FONT);
      ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(b.label, b.x, b.y + 1);
    }
  }
}

function wrap(text, maxW) {
  const words = String(text).split(/\s+/), lines = [];
  let line = '';
  for (const w of words) {
    const t = line ? line + ' ' + w : w;
    if (ctx.measureText(t).width > maxW && line) { lines.push(line); line = w; } else line = t;
  }
  if (line) lines.push(line);
  return lines.slice(0, 3);
}

function banner(text, alpha) {
  ctx.globalAlpha = alpha;
  ctx.font = '600 16px ' + FONT;
  const lines = wrap(text, LW - 60), h = 18 + lines.length * 22, y = LH - h - 128; // (above a paddle or the buttons at the bottom)
  ctx.fillStyle = 'rgba(5,8,14,0.8)';
  ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(16, y, LW - 32, h, 14); else ctx.rect(16, y, LW - 32, h); ctx.fill();
  ctx.lineWidth = 1; ctx.strokeStyle = 'rgba(79,210,255,0.4)'; ctx.stroke();
  ctx.fillStyle = '#e8f4ff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  lines.forEach((l, i) => ctx.fillText(l, LW / 2, y + 20 + i * 22));
  ctx.globalAlpha = 1;
}

function card(lines, btns) {
  ctx.fillStyle = 'rgba(3,5,10,0.72)';
  ctx.fillRect(0, 0, LW, LH);
  const w = Math.min(LW - 40, 320), x = (LW - w) / 2;
  let h = 28 + lines.reduce((s, l) => s + (l.size || 20) + 14, 0) + btns.length * 62 + 8;
  const y = Math.max(20, (LH - h) / 2);
  ctx.fillStyle = 'rgba(14,22,34,0.96)';
  ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(x, y, w, h, 20); else ctx.rect(x, y, w, h); ctx.fill();
  ctx.lineWidth = 1.5; ctx.strokeStyle = 'rgba(79,210,255,0.45)'; ctx.stroke();
  let cy = y + 28;
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  for (const l of lines) {
    const s = l.size || 20;
    ctx.font = (l.bold === false ? '500 ' : '700 ') + s + 'px ' + FONT;
    ctx.fillStyle = l.color || '#fff';
    ctx.fillText(l.text, LW / 2, cy + s / 2);
    cy += s + 14;
  }
  cy += 4;
  for (const b of btns) {
    const bx = x + 20, bw = w - 40, bh = 50;
    ctx.fillStyle = b.main ? '#4fd2ff' : 'rgba(255,255,255,0.1)';
    ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(bx, cy, bw, bh, 14); else ctx.rect(bx, cy, bw, bh); ctx.fill();
    ctx.font = '700 18px ' + FONT;
    ctx.fillStyle = b.main ? '#04131a' : '#fff';
    ctx.fillText(b.text, LW / 2, cy + bh / 2 + 1);
    uiBtns.push({ x: bx, y: cy, w: bw, h: bh, act: b.act });
    cy += 62;
  }
}

function pausedCard() {
  card([{ text: '⏸️ ఆట ఆగింది', size: 24 }, { text: '⭐ ' + Math.round(G.score || 0), size: 18, color: '#ffd23f' }],
    [{ text: '▶️ కొనసాగించు', act: 'resume', main: true }, { text: '🔄 మళ్ళీ మొదలు', act: 'restart' }, { text: '🚪 స్టూడియోకి', act: 'exit' }]);
}

function endCard() {
  const e = ended || { won: false, text: '' };
  const lines = [{ text: e.won ? '🎉 గెలిచారు!' : '🏁 ఆట అయిపోయింది', size: 26 }];
  if (e.text) lines.push({ text: e.text, size: 16, bold: false, color: '#c9dcf0' });
  lines.push({ text: '⭐ స్కోర్ ' + Math.round(G.score || 0), size: 22, color: '#ffd23f' });
  if (newBest) lines.push({ text: '🏆 కొత్త రికార్డ్!', size: 18, color: '#4fd2ff' });
  else if (best > 0) lines.push({ text: '🏆 బెస్ట్ ' + best, size: 16, bold: false, color: '#c9dcf0' });
  card(lines, [{ text: '🔄 మళ్ళీ ఆడు', act: 'restart', main: true }, { text: '🚪 స్టూడియోకి', act: 'exit' }]);
}

function brokenCard() {
  card([{ text: '⚠️ ఆటలో తప్పు వచ్చింది', size: 22 }, { text: 'Jarvis సరిచేయగలడు', size: 16, bold: false, color: '#c9dcf0' }],
    [{ text: '🚪 స్టూడియోకి', act: 'exit', main: true }]);
}

function endNow() {
  const e = ended;
  mode = 'over';
  const s = Math.round(G.score || 0);
  newBest = s > best && s > 0;
  if (newBest) best = s;
  G.best = best;
  try { (e && e.won ? SOUNDS.win : SOUNDS.over)(); } catch (x) { /* */ }
  post({ t: 'over', score: s, won: !!(e && e.won), secs: Math.round(gt) });
}

// ------------------------------------------------------------------ the loop (only while a game is on the screen)

let last = 0, rafOn = false;

function loop(now) {
  rafOn = false;
  if (mode !== 'play' && mode !== 'paused' && mode !== 'over' && mode !== 'broken') return;
  const dt = last ? Math.min(0.05, Math.max(0, (now - last) / 1000)) : 1 / 60;
  last = now;
  if (mode === 'play') step(dt);
  else effects(dt);
  render();
  if (mode === 'play' && ended && !crashed) endNow();
  if (mode === 'play' || particles.length || floats.length || shakeT > 0) kick();
}

function kick() { if (!rafOn) { rafOn = true; requestAnimationFrame(loop); } }

function play() {
  audio();
  mode = 'play'; // (an error in G.onStart now stops the game and is reported, like any other)
  resetGame();
  howT = howText ? 4 : 0;
  last = 0;
  if (mode === 'play') post({ t: 'state', state: 'play' });
  kick();
}

function pauseGame() {
  if (mode !== 'play') return;
  mode = 'paused';
  for (const b of buttons.values()) { b.held = false; b.pid = null; }
  pointers.clear();
  G.pointer.down = false;
  post({ t: 'state', state: 'paused' });
  render();
}

function resumeGame() {
  if (mode !== 'paused') return;
  mode = 'play';
  last = 0;
  post({ t: 'state', state: 'play' });
  kick();
}

function uiAct(act) {
  if (act === 'pause') pauseGame();
  else if (act === 'resume') resumeGame();
  else if (act === 'restart') play();
  else if (act === 'exit') { if (mode === 'play') mode = 'paused'; post({ t: 'exit' }); render(); }
}

// ------------------------------------------------------------------ touches (game units)

const pointers = new Map();
let clockTest = 0;
const clock = () => (mode === 'test' ? clockTest : performance.now());

function toGame(e) { return { x: (e.clientX - offX) / scale, y: (e.clientY - offY) / scale }; }

function hitUi(x, y) {
  for (let i = uiBtns.length - 1; i >= 0; i--) {
    const b = uiBtns[i];
    if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) return b.act;
  }
  return null;
}

function pDown(id, x, y) {
  if (mode !== 'play' && mode !== 'test') {
    const act = hitUi(x, y);
    if (act) { try { SOUNDS.click(); } catch (e) { /* */ } uiAct(act); }
    return;
  }
  if (mode === 'play') { const act = hitUi(x, y); if (act) { uiAct(act); return; } }
  for (const b of buttons.values()) {
    if (b.pid == null && Math.hypot(x - b.x, y - b.y) <= b.r * 1.25) {
      b.held = true;
      b.pid = id;
      pointers.set(id, { x, y, sx: x, sy: y, st: clock(), btn: b.id });
      for (const h of presses.slice()) if (h.btn === b.id) { call(h, 'G.onPress'); if (crashed) return; }
      return;
    }
  }
  pointers.set(id, { x, y, sx: x, sy: y, st: clock() });
  G.pointer.x = x; G.pointer.y = y; G.pointer.down = true;
  for (const h of hooks.down.slice()) { call(h, 'G.onDown', x, y, id); if (crashed) return; }
}

function pMove(id, x, y) {
  const p = pointers.get(id);
  if (!p || p.btn) return;
  p.x = x; p.y = y;
  G.pointer.x = x; G.pointer.y = y;
  if (mode !== 'play' && mode !== 'test') return;
  for (const h of hooks.move.slice()) { call(h, 'G.onMove', x, y, id); if (crashed) return; }
}

function pUp(id, x, y) {
  const p = pointers.get(id);
  if (!p) return;
  pointers.delete(id);
  if (p.btn) { const b = buttons.get(p.btn); if (b) { b.held = false; b.pid = null; } return; }
  G.pointer.down = [...pointers.values()].some((q) => !q.btn);
  if (mode !== 'play' && mode !== 'test') return;
  for (const h of hooks.up.slice()) { call(h, 'G.onUp', x, y, id); if (crashed) return; }
  const dx = x - p.sx, dy = y - p.sy, d = Math.hypot(dx, dy), ms = clock() - p.st;
  if (d < 16 && ms < 450) { for (const h of hooks.tap.slice()) { call(h, 'G.onTap', x, y); if (crashed) return; } }
  else if (d > 34 && ms < 800) {
    const dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
    for (const h of hooks.swipe.slice()) { call(h, 'G.onSwipe', dir); if (crashed) return; }
  }
}

cv.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  audio();
  try { cv.setPointerCapture(e.pointerId); } catch (x) { /* */ }
  const g = toGame(e);
  pDown(e.pointerId, g.x, g.y);
});
cv.addEventListener('pointermove', (e) => { const g = toGame(e); pMove(e.pointerId, g.x, g.y); });
cv.addEventListener('pointerup', (e) => { const g = toGame(e); pUp(e.pointerId, g.x, g.y); });
cv.addEventListener('pointercancel', (e) => { const g = toGame(e); pUp(e.pointerId, g.x, g.y); });
cv.addEventListener('contextmenu', (e) => e.preventDefault());
window.addEventListener('resize', () => { fit(); if (mode !== 'play') render(); });

// ------------------------------------------------------------------ the self-test: the game plays itself for a few seconds

function blankScreen() {
  try {
    const t = document.createElement('canvas');
    t.width = 18; t.height = 32;
    const c2 = t.getContext('2d');
    c2.drawImage(cv, offX * dpr, offY * dpr, LW * scale * dpr, LH * scale * dpr, 0, 0, 18, 32);
    const d = c2.getImageData(0, 0, 18, 32).data, seen = new Set();
    for (let i = 0; i < d.length; i += 4) seen.add((d[i] >> 4) + ',' + (d[i + 1] >> 4) + ',' + (d[i + 2] >> 4));
    return seen.size <= 1;
  } catch (e) { return false; }
}

async function selfTest() {
  const was = mode;
  mode = 'test';
  testErr = null;
  crashed = null;
  clockTest = 0;
  const N = 420, dt = 1 / 60;
  let overs = 0, frames = 0, slow = 0;
  const btns = () => [...buttons.values()];
  if (!hooks.draw.length) testErr = { id: order[order.length - 1] || 'game', msg: 'the game draws nothing: no G.onDraw(...) in any part' };
  if (!testErr) resetGame();
  if (crashed) testErr = crashed;
  for (let i = 0; i < N && !testErr; i++) {
    clockTest = i * 1000 / 60;
    const tx = LW * (0.2 + 0.6 * ((i * 37) % 100) / 100), ty = LH * (0.25 + 0.6 * ((i * 53) % 100) / 100);
    if (i === 20 || i === 70 || i === 240) { pDown(91, tx, ty); pUp(91, tx, ty); }                     // taps
    if (i === 100) pDown(92, LW / 2, LH / 2);                                                            // a drag across
    if (i > 100 && i < 130) pMove(92, LW / 2 + (i - 100) * 4, LH / 2 + (i - 100) * 2);
    if (i === 130) pUp(92, LW / 2 + 120, LH / 2 + 60);
    if (i === 150 || i === 160 || i === 170 || i === 180) {                                              // swipes
      const d = ['left', 'up', 'right', 'down'][(i - 150) / 10], sx = LW / 2, sy = LH / 2;
      const ex = sx + (d === 'left' ? -90 : d === 'right' ? 90 : 0), ey = sy + (d === 'up' ? -90 : d === 'down' ? 90 : 0);
      pDown(93, sx, sy); pMove(93, (sx + ex) / 2, (sy + ey) / 2); clockTest += 120; pUp(93, ex, ey);
    }
    if (i === 200) btns().forEach((b, k) => pDown(100 + k, b.x, b.y));                                  // every button held
    if (i === 230) btns().forEach((b, k) => pUp(100 + k, b.x, b.y));
    G.tilt.x = i > 260 && i < 330 ? Math.sin(i / 9) * 0.7 : 0;
    G.tilt.y = i > 300 && i < 360 ? 0.5 : 0;
    const t0 = performance.now();
    step(dt);
    if (i % 3 === 0 && !crashed) { render(); frames++; }
    if (performance.now() - t0 > 120) slow++;
    if (crashed) { testErr = crashed; break; }
    if (ended) { overs++; if (overs > 4) break; resetGame(); if (crashed) { testErr = crashed; break; } }
    if (i % 30 === 29) await new Promise((r) => setTimeout(r, 0));
  }
  G.tilt.x = G.tilt.y = 0;
  if (!testErr && slow > 20) testErr = { id: order[order.length - 1] || 'game', msg: 'far too slow: one frame took more than 0.12 s again and again (draw less, loop less)' };
  if (!testErr) {
    resetGame();
    if (!crashed) for (let i = 0; i < 50 && !ended && !crashed; i++) step(dt);
    if (!crashed) render();
    if (crashed) testErr = crashed;
    else if (blankScreen()) testErr = { id: firstDrawer(), msg: 'the screen stays one plain colour: nothing visible is drawn (draw with G.rect / G.circle / G.emoji / G.text... in G.onDraw)' };
  }
  mode = was === 'test' ? 'idle' : was;
  if (testErr) {
    const e = testErr;
    crashed = null;
    post({ t: 'err', id: e.id, msg: 'test play: ' + e.msg, state: 'test' });
  } else {
    post({ t: 'tested', frames, secs: Math.round(N / 60) });
  }
  crashed = null;
  ended = null;
}

function firstDrawer() { return hooks.draw.length ? hooks.draw[0].id : (order[0] || 'game'); }

/** The game's first moments, still: what the studio shows before he plays. */
function preview() {
  mode = 'preview';
  resetGame();
  if (!crashed) for (let i = 0; i < 48 && !ended && !crashed; i++) step(1 / 60);
  particles.length = 0;
  floats.length = 0;
  shakeT = flashT = 0;
  ended = null;
  crashed = null;
  render();
}

function snap(req) {
  try {
    if (mode === 'idle') preview();
    else render();
    const long = 480, w = portrait ? Math.round(long * LW / LH) : long, h = portrait ? long : Math.round(long * LH / LW);
    const t = document.createElement('canvas');
    t.width = w; t.height = h;
    t.getContext('2d').drawImage(cv, offX * dpr, offY * dpr, LW * scale * dpr, LH * scale * dpr, 0, 0, w, h);
    const url = t.toDataURL('image/jpeg', 0.82);
    post({ t: 'file', req, kind: 'jpg', data: url.slice(url.indexOf(',') + 1) });
  } catch (e) {
    post({ t: 'warn', id: 'snap', msg: String(e && e.message || e) });
  }
}

// ------------------------------------------------------------------ the parts

let queue = Promise.resolve();

async function runPart(m) {
  const id = cleanId(m.id);
  dropPart(id); // (a part written again replaces what it had registered before)
  if (!order.includes(id)) order.push(id);
  let fn;
  try { fn = new AsyncFunction('G', 'S', '"use strict";\n' + String(m.code || '')); }
  catch (e) { post({ t: 'err', id, msg: 'syntax error: ' + ((e && e.message) || e), state: 'load' }); return; }
  cur = id;
  loading = true;
  try { await fn(G, S); }
  catch (e) { cur = null; loading = false; dropPart(id); post({ t: 'err', id, msg: errText(e), state: 'load' }); return; }
  cur = null;
  loading = false;
  if (typeof G.lives === 'number') baseLives = G.lives;
  if (typeof G.level === 'number' && G.level > 0) baseLevel = G.level;
  sortHooks();
  post({ t: 'ok', id });
}

function reset(m) {
  mode = 'idle';
  for (const k of KINDS) hooks[k] = [];
  presses = [];
  buttons.clear();
  timers = [];
  order.length = 0;
  for (const k of Object.keys(S)) delete S[k];
  bgFill = null;
  ended = null;
  crashed = null;
  particles.length = 0;
  floats.length = 0;
  pointers.clear();
  baseLives = null;
  baseLevel = 0;
  G.score = 0; G.lives = null; G.level = 0; G.hud = true;
  portrait = m.screen !== 'landscape';
  if (typeof m.aspect === 'number' && isFinite(m.aspect)) aspect = Math.max(1.3, Math.min(2.6, m.aspect));
  best = Math.max(0, Math.round(+m.best || 0));
  howText = typeof m.how === 'string' ? m.how.slice(0, 160) : '';
  titleText = typeof m.title === 'string' ? m.title.slice(0, 40) : '';
  G.best = best;
  area();
  render();
  return setHero(m.hero); // (🏍️ the parts wait for his model's picture)
}

window.addEventListener('message', (e) => {
  if (e.source !== window.parent) return;
  const m = e.data;
  if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
  switch (m.t) {
    case 'reset': queue = queue.then(() => reset(m)); break;
    case 'screen': queue = queue.then(() => { portrait = m.screen !== 'landscape'; area(); render(); }); break;
    case 'info':
      if (typeof m.how === 'string') howText = m.how.slice(0, 160);
      if (typeof m.title === 'string') { titleText = m.title.slice(0, 40); if (mode === 'idle') render(); }
      break;
    case 'part': queue = queue.then(() => runPart(m)).catch((x) => post({ t: 'warn', id: cleanId(m.id), msg: String(x) })); break;
    case 'test': queue = queue.then(selfTest).catch((x) => post({ t: 'err', id: 'game', msg: 'test play: ' + String(x), state: 'test' })); break;
    case 'preview': queue = queue.then(preview); break;
    case 'start': queue = queue.then(() => { if (typeof m.best === 'number') best = Math.max(0, Math.round(m.best)); play(); }); break;
    case 'pause': if (mode === 'play') pauseGame(); break;
    case 'resume': if (mode === 'paused') resumeGame(); break;
    case 'tilt':
      G.tilt.x = Math.max(-1, Math.min(1, +m.x || 0));
      G.tilt.y = Math.max(-1, Math.min(1, +m.y || 0));
      break;
    case 'mute': muted = !!m.on; if (muted && ac) { try { ac.suspend(); } catch (x) { /* */ } } break;
    case 'snap': queue = queue.then(() => snap(typeof m.req === 'string' ? m.req.slice(0, 40) : '')); break;
    case 'resize': fit(); if (mode !== 'play') render(); break;
    default: break;
  }
});

area();
render();
post({ t: 'ready' });
