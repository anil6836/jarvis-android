// 📱 Jarvis app studio: the app kit, App (Anil, 18 Nov, stage 1). The same file runs in the studio's sealed preview on his
// phone and inside every app Jarvis builds (APK). The AI writes an app as parts (HTML + CSS + JavaScript); the kit gives
// them screens with a top bar and the back button, bottom tabs, saving, dialogs, Indian money / Telugu dates, charts and a
// little sound, and it knows which part every error came from (so only that part goes back to the AI for repair). The
// self-test taps through every screen by itself before an app is built. Nothing here talks to the network unless the app
// was planned with the internet (App.get). (20 Nov, stage 2) The phone's powers: reminders, photos, where I am, share, PDF,
// speaking and listening, a home-screen widget, torch / awake / landscape / copy, QR, and Jarvis writing into the app. In
// the APK they go through the shell's window.JarvisPhone; in the sealed preview every power answers with a sample.
(function () {
  'use strict';
  if (window.App && window.App.__kit) return;

  const HOST = { error: null, store: null, net: false, testing: false, icon: null, jarvis: [] };
  const App = { __kit: 1, my: {}, name: 'App', color: '#1e88e5' };
  window.App = App;
  const LINE0 = 2; // (a part's first line, as the browser counts it inside the function made from it)
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const cleanId = (s) => (String(s || 'part').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'part');
  const sleep = (ms) => new Promise((r) => rawTimeout(r, ms));

  // ------------------------------------------------------------------ which part an error came from

  let loadingPart = null, runningPart = null;
  const owners = new WeakMap();

  function partFrom(e) {
    const m = String((e && e.stack) || '').match(/part-([a-z0-9-]+)\.js/);
    return m ? m[1] : null;
  }

  function describe(e) {
    if (e == null) return 'error';
    if (typeof e !== 'object') return String(e).slice(0, 300);
    const msg = e.message || String(e);
    const ln = String(e.stack || '').match(/part-[a-z0-9-]+\.js:(\d+):(\d+)/);
    return ((e.name && e.name !== 'Error' ? e.name + ': ' : '') + msg + (ln ? ' (line ' + Math.max(1, ln[1] - LINE0) + ')' : '')).slice(0, 360);
  }

  function fail(e, owner) {
    const id = partFrom(e) || owner || runningPart || loadingPart || null;
    const msg = describe(e);
    try { if (HOST.error) HOST.error(id, msg); else console.error('[' + id + '] ' + msg); } catch (x) { /* (nothing more to do) */ }
  }

  function wrap(fn, owner) {
    if (typeof fn !== 'function') return fn;
    const had = owners.get(fn);
    if (had && had.__owner === owner) return had;
    const w = function () {
      const prev = runningPart;
      runningPart = owner;
      try {
        const r = fn.apply(this, arguments);
        if (r && typeof r.then === 'function') r.then(null, (e) => fail(e, owner));
        return r;
      } catch (e) {
        fail(e, owner);
      } finally {
        runningPart = prev;
      }
    };
    w.__owner = owner;
    owners.set(fn, w);
    return w;
  }

  const owner = () => runningPart || loadingPart;
  const EP = EventTarget.prototype, addEL = EP.addEventListener, remEL = EP.removeEventListener;
  EP.addEventListener = function (t, fn, o) { return addEL.call(this, t, typeof fn === 'function' ? wrap(fn, owner()) : fn, o); };
  EP.removeEventListener = function (t, fn, o) { return remEL.call(this, t, (typeof fn === 'function' && owners.get(fn)) || fn, o); };
  const rawTimeout = window.setTimeout.bind(window), rawInterval = window.setInterval.bind(window), rawFrame = window.requestAnimationFrame.bind(window);
  window.setTimeout = function (fn, ms, ...a) { return rawTimeout(typeof fn === 'function' ? wrap(fn, owner()) : function () {}, ms, ...a); };
  window.setInterval = function (fn, ms, ...a) { return rawInterval(typeof fn === 'function' ? wrap(fn, owner()) : function () {}, ms, ...a); };
  window.requestAnimationFrame = function (fn) { return rawFrame(wrap(fn, owner())); };
  addEL.call(window, 'error', (ev) => { fail(ev.error || { message: ev.message || 'error' }, null); });
  addEL.call(window, 'unhandledrejection', (ev) => { fail(ev.reason, null); });

  // ------------------------------------------------------------------ the frame: top bar, screens, tabs, toast, dialog

  let bar, back, titleEl, actionsEl, main, tabsEl, toastEl, dialogEl;
  function frame() {
    if (main) return;
    const b = document.body;
    bar = el('div', { id: 'kit-bar' });
    back = el('button', { id: 'kit-back', 'aria-label': 'వెనక్కి' }, '←');
    titleEl = el('div', { id: 'kit-title' });
    actionsEl = el('div', { id: 'kit-actions' });
    bar.append(back, titleEl, actionsEl);
    main = el('main', { id: 'kit-main' });
    tabsEl = el('nav', { id: 'kit-tabs' });
    toastEl = el('div', { id: 'kit-toast' });
    dialogEl = el('div', { id: 'kit-dialog' });
    b.prepend(bar, main, tabsEl, toastEl, dialogEl);
    addEL.call(back, 'click', () => { App.back(); });
    addEL.call(dialogEl, 'click', (ev) => { if (ev.target === dialogEl && closeDialog) closeDialog(null); }); // (a tap outside the box: cancel)
    // an app is one page: links and forms never leave it
    addEL.call(document, 'click', (ev) => {
      const a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
      if (a && !String(a.getAttribute('href') || '').startsWith('#')) ev.preventDefault();
    }, true);
    addEL.call(document, 'submit', (ev) => { ev.preventDefault(); }, true);
  }

  function el(tag, attrs, text) {
    const e = document.createElement(tag);
    for (const k in attrs || {}) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }

  const screens = () => (main ? Array.from(main.querySelectorAll('section.screen')) : []);
  const tabScreens = () => screens().filter((s) => s.dataset.tab);
  const byId = (id) => (main && id ? main.querySelector('section.screen#' + CSS.escape(id)) : null);

  let stack = [], cur = null, homeId = null, started = false;
  const dataOf = {}, showHooks = {}, actions = {}, startHooks = [];

  function tabsBuild() {
    const ts = tabScreens();
    tabsEl.textContent = '';
    document.body.classList.toggle('tabs', ts.length >= 2);
    if (ts.length < 2) return;
    for (const s of ts) {
      const raw = String(s.dataset.tab || '');
      const bar = raw.indexOf('|');
      const icon = bar >= 0 ? raw.slice(0, bar).trim() : '•', label = bar >= 0 ? raw.slice(bar + 1).trim() : raw.trim();
      const btn = el('button', { 'data-for': s.id });
      btn.append(el('span', { class: 'i' }, icon), el('span', {}, label || s.dataset.title || s.id));
      addEL.call(btn, 'click', () => { App.go(s.id); });
      tabsEl.append(btn);
    }
  }

  function show(id, data) {
    const s = byId(id);
    if (!s) throw new Error('App.go: no screen with id "' + id + '" (a <section class="screen" id="' + id + '">)');
    for (const x of screens()) x.classList.toggle('on', x === s);
    cur = id;
    dataOf[id] = data;
    titleEl.textContent = s.dataset.title || App.name;
    const isTab = !!s.dataset.tab && tabScreens().length >= 2;
    back.classList.toggle('hide', stack.length <= 1 && (isTab || !tabScreens().length || id === firstScreen()));
    tabsEl.style.display = '';
    document.body.classList.toggle('tabs', tabScreens().length >= 2 && (isTab || stack.length <= 1));
    for (const b of tabsEl.querySelectorAll('button')) b.classList.toggle('on', b.getAttribute('data-for') === id);
    actionsEl.textContent = '';
    for (const a of actions[id] || []) {
      const b = el('button', { 'aria-label': a.label || '' }, a.icon);
      addEL.call(b, 'click', a.fn);
      actionsEl.append(b);
    }
    main.scrollTop = 0;
    for (const h of showHooks[id] || []) h(data, s);
  }

  function firstScreen() {
    if (homeId && byId(homeId)) return homeId;
    const t = tabScreens();
    if (t.length) return t[0].id;
    const s = screens();
    return s.length ? s[0].id : null;
  }

  // ------------------------------------------------------------------ App: screens and moving between them

  App.go = (id, data) => {
    const s = byId(id);
    if (!s) throw new Error('App.go: no screen with id "' + id + '"');
    if (s.dataset.tab && tabScreens().length >= 2) stack = [id];
    else if (stack[stack.length - 1] !== id) stack.push(id);
    show(id, data);
  };
  App.back = () => {
    if (dialogEl && dialogEl.classList.contains('on') && closeDialog) { closeDialog(null); return true; }
    if (stack.length > 1) {
      stack.pop();
      const id = stack[stack.length - 1];
      show(id, dataOf[id]);
      return true;
    }
    const f = firstScreen();
    if (f && cur && cur !== f) { stack = [f]; show(f, dataOf[f]); return true; }
    return false;
  };
  App.current = () => cur;
  App.home = (id) => { homeId = id; };
  App.title = (t) => { if (titleEl) titleEl.textContent = String(t == null ? '' : t); };
  App.onShow = (id, fn) => { (showHooks[id] = showHooks[id] || []).push(wrap(fn, owner())); };
  App.onStart = (fn) => { startHooks.push(wrap(fn, owner())); };
  App.action = (screenId, icon, fn, label) => {
    (actions[screenId] = actions[screenId] || []).push({ icon: String(icon), fn: wrap(fn, owner()), label: label || '' });
    if (cur === screenId && started) show(cur, dataOf[cur]);
  };
  App.refresh = () => { if (cur) show(cur, dataOf[cur]); };

  // ------------------------------------------------------------------ App: the page

  App.$ = (sel, root) => (typeof sel === 'string' ? (root || document).querySelector(sel) : sel);
  App.$$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  App.esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  App.set = (target, html) => { const e = App.$(target); if (!e) throw new Error('App.set: nothing matches ' + target); e.innerHTML = html; return e; };
  App.text = (target, text) => { const e = App.$(target); if (!e) throw new Error('App.text: nothing matches ' + target); e.textContent = text == null ? '' : String(text); return e; };
  App.val = (target, ...set) => {
    const e = App.$(target);
    if (!e) throw new Error('App.val: nothing matches ' + target);
    if (set.length) { if (e.type === 'checkbox') e.checked = !!set[0]; else e.value = set[0] == null ? '' : set[0]; return set[0]; }
    return e.type === 'checkbox' ? e.checked : String(e.value == null ? '' : e.value).trim();
  };
  App.show = (target, on) => { const e = App.$(target); if (e) e.hidden = on === false; return e; };
  App.on = (target, type, fn) => {
    if (typeof fn !== 'function') throw new Error('App.on(target, "' + type + '", fn) needs a function');
    if (typeof target === 'string') {
      const sel = target;
      document.addEventListener(type, (ev) => {
        const m = ev.target && ev.target.closest ? ev.target.closest(sel) : null;
        if (m && (main.contains(m) || dialogEl.contains(m))) fn.call(m, ev, m);
      }, type === 'focus' || type === 'blur');
    } else if (target && target.addEventListener) {
      target.addEventListener(type, (ev) => fn.call(target, ev, target));
    } else throw new Error('App.on: no element ' + target);
  };
  App.click = (target, fn) => App.on(target, 'click', fn);
  App.uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  App.after = (sec, fn) => { const t = window.setTimeout(fn, Math.max(0, sec * 1000)); return { stop() { clearTimeout(t); } }; };
  App.every = (sec, fn) => { const t = window.setInterval(fn, Math.max(50, sec * 1000)); return { stop() { clearInterval(t); } }; };

  // ------------------------------------------------------------------ App: saving (on his phone only)

  const store = {
    get(k) { return HOST.store ? HOST.store.get(k) : null; },
    set(k, v) { if (HOST.store) HOST.store.set(k, v); },
    del(k) { if (HOST.store) HOST.store.del(k); },
    keys() { return HOST.store ? HOST.store.keys() : []; },
  };
  const copy = (v) => (v == null || typeof v !== 'object' ? v : JSON.parse(JSON.stringify(v)));
  App.load = (key, def) => {
    const v = store.get(String(key));
    if (v == null) return def === undefined ? null : copy(def);
    try { return JSON.parse(v); } catch (e) { return def === undefined ? null : copy(def); }
  };
  App.save = (key, value) => {
    try { store.set(String(key), JSON.stringify(value)); } catch (e) {
      App.toast('ఫోన్‌లో చోటు సరిపోలేదు, దాచలేకపోయాను');
      throw e;
    }
    return value;
  };
  App.remove = (key) => { store.del(String(key)); };
  App.keys = () => store.keys();
  App.items = (key) => { const a = App.load(key, []); return Array.isArray(a) ? a : []; };
  App.add = (key, item) => {
    const a = App.items(key);
    const it = Object.assign({ id: App.uid(), at: Date.now() }, item || {});
    a.push(it);
    App.save(key, a);
    return it;
  };
  App.find = (key, id) => App.items(key).find((x) => x && x.id === id) || null;
  App.update = (key, id, changes) => {
    const a = App.items(key);
    const it = a.find((x) => x && x.id === id);
    if (!it) return null;
    Object.assign(it, changes || {});
    App.save(key, a);
    return it;
  };
  App.del = (key, id) => {
    const a = App.items(key);
    const n = a.filter((x) => !(x && x.id === id));
    App.save(key, n);
    return n.length !== a.length;
  };

  // ------------------------------------------------------------------ App: messages and questions

  let toastT = null;
  App.toast = (text, secs) => {
    if (!toastEl) return;
    toastEl.textContent = String(text == null ? '' : text);
    toastEl.style.display = 'block';
    if (toastT) clearTimeout(toastT);
    toastT = rawTimeout(() => { toastEl.style.display = 'none'; }, Math.max(1, secs || 2.2) * 1000);
  };

  let closeDialog = null;
  function dialog(build) {
    return new Promise((resolve) => {
      if (closeDialog) closeDialog(null);
      const box = el('div', { class: 'box' });
      dialogEl.textContent = '';
      dialogEl.append(box);
      let done = false;
      const finish = (v) => {
        if (done) return;
        done = true;
        closeDialog = null;
        dialogEl.classList.remove('on');
        dialogEl.textContent = '';
        resolve(v);
      };
      closeDialog = finish;
      const auto = build(box, finish);
      dialogEl.classList.add('on');
      if (HOST.testing && auto) rawTimeout(() => finish(auto()), 25); // (the self-test answers by itself)
    });
  }

  function buttonsRow(box, list) {
    const row = el('div', { class: 'btns' });
    for (const [label, cls, fn] of list) {
      const b = el('button', { class: 'btn ' + cls }, label);
      addEL.call(b, 'click', fn);
      row.append(b);
    }
    box.append(row);
  }

  App.ask = (question, ok, cancel) => dialog((box, finish) => {
    box.append(el('div', { class: 'q' }, String(question)));
    buttonsRow(box, [[cancel || 'వద్దు', 'ghost', () => finish(false)], [ok || 'సరే', 'primary', () => finish(true)]]);
    return () => true;
  }).then((v) => v === true);

  App.pick = (title, options) => dialog((box, finish) => {
    box.append(el('div', { class: 'q' }, String(title)));
    (options || []).forEach((o, i) => {
      const b = el('button', { class: 'btn opt' }, String(o));
      addEL.call(b, 'click', () => finish(i));
      box.append(b);
    });
    buttonsRow(box, [['వద్దు', 'ghost', () => finish(-1)]]);
    return () => ((options || []).length ? 0 : -1);
  }).then((v) => (typeof v === 'number' ? v : -1));

  const SAMPLE = { text: 'పరీక్ష', number: '5', date: null, time: '09:30', textarea: 'పరీక్ష', tel: '9876543210', email: 'test@example.com' };
  function sample(f) {
    if (f.type === 'select') return (f.options || [])[0] != null ? String(optVal(f.options[0])) : '';
    if (f.type === 'check') return true;
    if (f.type === 'date') return App.today();
    return SAMPLE[f.type] != null ? SAMPLE[f.type] : 'పరీక్ష';
  }
  const optVal = (o) => (o && typeof o === 'object' ? (o.value != null ? o.value : o.label) : o);
  const optLabel = (o) => (o && typeof o === 'object' ? (o.label != null ? o.label : o.value) : o);

  App.form = (title, fields, okLabel) => dialog((box, finish) => {
    box.append(el('div', { class: 'q' }, String(title)));
    const inputs = [];
    for (const f0 of fields || []) {
      const f = Object.assign({ type: 'text' }, f0);
      const wrapEl = el('div', { class: f.type === 'check' ? 'check' : 'field' });
      let inp;
      if (f.type === 'select') {
        inp = el('select');
        for (const o of f.options || []) { const op = el('option', { value: String(optVal(o)) }, String(optLabel(o))); inp.append(op); }
      } else if (f.type === 'textarea') inp = el('textarea');
      else inp = el('input', { type: f.type === 'check' ? 'checkbox' : f.type === 'number' ? 'number' : f.type });
      if (f.type === 'number') { inp.setAttribute('inputmode', 'decimal'); inp.setAttribute('step', 'any'); }
      if (f.placeholder) inp.setAttribute('placeholder', f.placeholder);
      if (f.type === 'check') inp.checked = !!f.value; else if (f.value != null) inp.value = String(f.value);
      const lab = el('label', {}, String(f.label || f.key || ''));
      if (f.type === 'check') wrapEl.append(inp, lab); else wrapEl.append(lab, inp);
      box.append(wrapEl);
      inputs.push([f, inp]);
    }
    const read = () => {
      const out = {};
      for (const [f, inp] of inputs) {
        let v = f.type === 'check' ? inp.checked : String(inp.value == null ? '' : inp.value).trim();
        if (f.need && (v === '' || v === false)) { App.toast((f.label || f.key) + ' రాయండి'); inp.focus(); return null; }
        if (f.type === 'number') v = v === '' ? null : Number(v);
        out[f.key || f.label] = v;
      }
      return out;
    };
    buttonsRow(box, [['వద్దు', 'ghost', () => finish(null)], [okLabel || 'సరే', 'primary', () => { const v = read(); if (v) finish(v); }]]);
    if (inputs.length && !HOST.testing) rawTimeout(() => { try { inputs[0][1].focus(); } catch (e) { /* */ } }, 60);
    return () => {
      const out = {};
      for (const [f] of inputs) { const v = sample(f); out[f.key || f.label] = f.type === 'number' ? Number(v) : v; }
      return out;
    };
  });

  App.input = (label, opts) => {
    const o = Object.assign({ type: 'text' }, opts || {});
    return App.form(label, [Object.assign({}, o, { key: 'v', label: o.label || '' })]).then((r) => (r ? r.v : null));
  };

  // ------------------------------------------------------------------ App: Indian numbers, Telugu dates

  function group(intStr) {
    const neg = intStr.startsWith('-');
    let s = neg ? intStr.slice(1) : intStr;
    if (s.length > 3) s = s.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + s.slice(-3);
    return (neg ? '-' : '') + s;
  }
  App.n = (v, d) => {
    const x = Number(v);
    if (!isFinite(x)) return '0';
    const f = x.toFixed(Math.max(0, Math.min(6, d || 0)));
    const dot = f.indexOf('.');
    return group(dot >= 0 ? f.slice(0, dot) : f) + (dot >= 0 ? f.slice(dot) : '');
  };
  App.rs = (v) => {
    const x = Math.round((Number(v) || 0) * 100) / 100;
    const frac = Math.abs(x % 1) > 0.004;
    return (x < 0 ? '-₹' : '₹') + App.n(Math.abs(x), frac ? 2 : 0);
  };
  const MONTHS = ['జనవరి', 'ఫిబ్రవరి', 'మార్చి', 'ఏప్రిల్', 'మే', 'జూన్', 'జూలై', 'ఆగస్టు', 'సెప్టెంబర్', 'అక్టోబర్', 'నవంబర్', 'డిసెంబర్'];
  const DAYS = ['ఆదివారం', 'సోమవారం', 'మంగళవారం', 'బుధవారం', 'గురువారం', 'శుక్రవారం', 'శనివారం'];
  const pad = (n) => (n < 10 ? '0' : '') + n;
  function toDate(x) {
    if (x instanceof Date) return x;
    if (typeof x === 'number') return new Date(x);
    if (typeof x === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(x)) { const p = x.split('-').map(Number); return new Date(p[0], p[1] - 1, p[2]); }
    if (x == null) return new Date();
    return new Date(x);
  }
  App.ymd = (x) => { const d = toDate(x); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); };
  App.today = (plusDays) => { const d = new Date(); d.setDate(d.getDate() + (Number(plusDays) || 0)); return App.ymd(d); };
  App.date = (x, style) => {
    const d = toDate(x);
    if (isNaN(d.getTime())) return '';
    const s = d.getDate() + ' ' + MONTHS[d.getMonth()] + (style === 'short' ? '' : ' ' + d.getFullYear());
    return style === 'day' ? DAYS[d.getDay()] + ', ' + s : s;
  };
  App.weekday = (x) => DAYS[toDate(x).getDay()];
  App.month = (x) => { const d = toDate(x); return MONTHS[d.getMonth()] + ' ' + d.getFullYear(); };
  App.monthKey = (x) => { const d = toDate(x); return d.getFullYear() + '-' + pad(d.getMonth() + 1); };
  App.time = (x) => {
    const d = toDate(x);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12 || 12;
    return h + ':' + pad(d.getMinutes()) + ' ' + ap;
  };
  App.days = (a, b) => Math.round((toDate(App.ymd(b == null ? new Date() : b)) - toDate(App.ymd(a))) / 864e5);
  App.ago = (x) => {
    const s = Math.max(0, (Date.now() - toDate(x).getTime()) / 1000);
    if (s < 60) return 'ఇప్పుడే';
    if (s < 3600) return Math.floor(s / 60) + ' నిమిషాల క్రితం';
    if (s < 86400) return Math.floor(s / 3600) + ' గంటల క్రితం';
    const d = Math.floor(s / 86400);
    return d === 1 ? 'నిన్న' : d + ' రోజుల క్రితం';
  };

  // ------------------------------------------------------------------ App: charts (drawn here, no outside files)

  const PALETTE = ['#1e88e5', '#43a047', '#fb8c00', '#8e24aa', '#e53935', '#00897b', '#fdd835', '#6d4c41', '#3949ab', '#d81b60'];
  App.chart = (target, o) => {
    const host = App.$(target);
    if (!host) throw new Error('App.chart: nothing matches ' + target);
    const opt = Object.assign({ type: 'bar', labels: [], values: [] }, o || {});
    const vals = (opt.values || []).map((v) => Number(v) || 0), labels = (opt.labels || []).map((l) => String(l == null ? '' : l));
    const color = opt.color || getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || App.color;
    const fmt = opt.money ? App.rs : (v) => App.n(v, Math.abs(v) < 10 && v % 1 ? 1 : 0);
    const short = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
    let svg = '';
    const W = 320, H = 180;
    if (!vals.length) { host.innerHTML = '<div class="empty" style="padding:18px">ఇంకా ఏమీ లేదు</div>'; return; }
    if (opt.type === 'pie') {
      const total = vals.reduce((a, b) => a + Math.max(0, b), 0) || 1;
      let a0 = -Math.PI / 2, legend = '';
      const cx = 90, cy = 90, r = 80, r2 = 46;
      vals.forEach((v, i) => {
        const f = Math.max(0, v) / total, a1 = a0 + f * Math.PI * 2, c = i === 0 ? color : PALETTE[(i - 1) % PALETTE.length];
        if (f >= 0.999) svg += `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${c}"/>`;
        else if (f > 0) {
          const big = a1 - a0 > Math.PI ? 1 : 0;
          svg += `<path d="M${cx + r * Math.cos(a0)},${cy + r * Math.sin(a0)} A${r},${r} 0 ${big} 1 ${cx + r * Math.cos(a1)},${cy + r * Math.sin(a1)} L${cx},${cy} Z" fill="${c}"/>`;
        }
        legend += `<span><i style="background:${c}"></i>${App.esc(short(labels[i] || '', 14))} · ${App.esc(fmt(v))} (${Math.round(f * 100)}%)</span>`;
        a0 = a1;
      });
      svg += `<circle cx="${cx}" cy="${cy}" r="${r2}" style="fill:var(--card)"/><text x="${cx}" y="${cy + 4}" text-anchor="middle" class="v">${App.esc(fmt(total))}</text>`;
      host.innerHTML = `<svg class="kit-chart" viewBox="0 0 180 180" style="max-width:220px;margin:0 auto">${svg}</svg><div class="kit-legend">${legend}</div>`;
      return;
    }
    const max = Math.max(1e-9, ...vals.map((v) => Math.abs(v))), n = vals.length;
    const left = 8, right = 8, top = 18, bottom = 30, cw = W - left - right, ch = H - top - bottom;
    const every = Math.ceil(n / 8);
    if (opt.type === 'line') {
      const pts = vals.map((v, i) => [left + (n === 1 ? cw / 2 : (i * cw) / (n - 1)), top + ch - (Math.max(0, v) / max) * ch]);
      svg += `<polyline fill="none" stroke="${color}" stroke-width="2.5" points="${pts.map((p) => p.join(',')).join(' ')}"/>`;
      pts.forEach((p, i) => {
        svg += `<circle cx="${p[0]}" cy="${p[1]}" r="3.5" fill="${color}"/>`;
        if (i % every === 0 || i === n - 1) svg += `<text x="${p[0]}" y="${H - 10}" text-anchor="middle">${App.esc(short(labels[i] || '', 6))}</text>`;
      });
      svg += `<text x="${pts[n - 1][0]}" y="${Math.max(12, pts[n - 1][1] - 8)}" text-anchor="end" class="v">${App.esc(fmt(vals[n - 1]))}</text>`;
    } else {
      const bw = cw / n, gap = Math.min(10, bw * 0.25);
      vals.forEach((v, i) => {
        const h = (Math.max(0, v) / max) * ch, x = left + i * bw + gap / 2, y = top + ch - h;
        svg += `<rect x="${x}" y="${y}" width="${Math.max(1, bw - gap)}" height="${Math.max(0.5, h)}" rx="3" fill="${color}"/>`;
        if (n <= 12) svg += `<text x="${x + (bw - gap) / 2}" y="${Math.max(11, y - 4)}" text-anchor="middle" class="v">${App.esc(fmt(v))}</text>`;
        if (i % every === 0) svg += `<text x="${x + (bw - gap) / 2}" y="${H - 10}" text-anchor="middle">${App.esc(short(labels[i] || '', 6))}</text>`;
      });
    }
    svg += `<line x1="${left}" y1="${top + ch}" x2="${W - right}" y2="${top + ch}" style="stroke:var(--line)"/>`;
    host.innerHTML = `<svg class="kit-chart" viewBox="0 0 ${W} ${H}">${svg}</svg>`;
  };

  // ------------------------------------------------------------------ App: a little sound and a buzz

  let audio = null;
  App.beep = (kind) => {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      const o = audio.createOscillator(), g = audio.createGain(), t = audio.currentTime;
      const f = { tap: 660, ok: 880, err: 220, done: 990 }[kind] || 660;
      o.frequency.setValueAtTime(f, t);
      if (kind === 'ok' || kind === 'done') o.frequency.setValueAtTime(f * 1.5, t + 0.08);
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(0.18, t + 0.01);
      g.gain.exponentialRampToValueAtTime(0.0001, t + (kind === 'err' ? 0.3 : 0.18));
      o.connect(g).connect(audio.destination);
      o.start(t);
      o.stop(t + 0.32);
    } catch (e) { /* (no sound on this phone: fine) */ }
  };
  App.buzz = (ms) => { try { if (navigator.vibrate) navigator.vibrate(Math.max(10, Math.min(400, ms || 30))); } catch (e) { /* */ } };

  // ------------------------------------------------------------------ App: the internet (only apps planned with it)

  App.get = async (url, opts) => {
    if (!HOST.net) throw new Error(HOST.testing ? 'ప్రివ్యూలో ఇంటర్నెట్ ఉండదు' : 'ఈ యాప్‌కి ఇంటర్నెట్ లేదు');
    const r = await window.fetch(url, { method: 'GET', credentials: 'omit', cache: 'no-store' });
    if (!r.ok) throw new Error('ఇంటర్నెట్ జవాబు ' + r.status);
    return opts && opts.text ? r.text() : r.json();
  };

  // ------------------------------------------------------------------ App: the phone's powers (stage 2)

  const waiting = new Map();
  let callN = 0;
  const bridge = () => (window.JarvisPhone && typeof window.JarvisPhone.call === 'function' ? window.JarvisPhone : null);
  /** One power: through the APK's shell (it answers by App.__reply), or a sample in the preview. */
  function phone(name, args) {
    const b = bridge();
    if (!b) { try { return Promise.resolve(previewAnswer(name, args || {})); } catch (e) { return Promise.reject(e); } }
    return new Promise((res, rej) => {
      const id = 'c' + (++callN);
      waiting.set(id, { res, rej });
      try { b.call(id, name, JSON.stringify(args || {})); } catch (e) { waiting.delete(id); rej(e); }
    });
  }
  App.__reply = (id, ok, value) => {
    const w = waiting.get(id);
    if (!w) return;
    waiting.delete(id);
    if (ok) w.res(value === undefined ? null : value); else w.rej(new Error(String(value || 'error')));
  };

  const previewRem = new Map(), previewPhotos = new Map();
  let samplePics = 0, widgetNoted = false;
  const note = (text) => { if (!HOST.testing) App.toast(text, 2.6); };
  function samplePic(n) {
    const hue = (n * 67) % 360;
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 120"><rect width="160" height="120" fill="hsl(${hue},45%,82%)"/>`
      + `<circle cx="118" cy="34" r="14" fill="hsl(${hue},70%,60%)"/><path d="M0 120 L52 58 L84 92 L104 72 L160 120 Z" fill="hsl(${hue},35%,45%)"/>`
      + `<text x="8" y="18" font-size="12" font-family="sans-serif" fill="#333">నమూనా ${n}</text></svg>`;
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }
  const EVERY = { day: 'రోజూ', week: 'ప్రతి వారం', month: 'ప్రతి నెల', year: 'ప్రతి ఏడాది' };
  const whenText = (r) => App.date(r.at, 'short') + ' ' + App.time(r.at) + (r.every ? ' · ' + EVERY[r.every] : '');
  function previewAnswer(name, a) {
    switch (name) {
      case 'remind': previewRem.set(a.id, a); note('🔔 రిమైండర్: ' + a.title + ' · ' + whenText(a) + ' (ప్రివ్యూలో నోటిఫికేషన్ రాదు)'); return a.id;
      case 'unremind': previewRem.delete(a.id); return true;
      case 'reminders': return Array.from(previewRem.values());
      case 'photo': { samplePics++; const id = 'p' + Date.now().toString(36) + samplePics; previewPhotos.set(id, samplePic(samplePics)); note('📷 ప్రివ్యూలో నమూనా బొమ్మ'); return id; }
      case 'unphoto': previewPhotos.delete(a.id); return true;
      case 'where': note('📍 ప్రివ్యూలో నమూనా చోటు'); return { lat: 20.5937, lon: 78.9629, accuracy: 25, sample: true };
      case 'scan': note('▦ ప్రివ్యూలో నమూనా QR'); return 'SAMPLE-QR-123';
      case 'listen': note('🎙️ ప్రివ్యూలో నమూనా మాట'); return 'పరీక్ష మాట';
      case 'share': case 'sharePhoto': note('📤 ప్రివ్యూలో ఎక్కడికీ పంపదు: ' + String(a.text || '').slice(0, 60)); return true;
      case 'pdf': note('📄 ఫోన్ యాప్‌లో ఇక్కడ PDF స్క్రీన్ తెరుచుకుంటుంది'); return true;
      case 'say': note('🗣️ ' + String(a.text || '').slice(0, 80)); return true;
      case 'widget': if (!widgetNoted) { widgetNoted = true; note('📲 విడ్జెట్: ' + a.title + (a.lines.length ? ' · ' + a.lines[0] : '')); } return true;
      case 'torch': note(a.on ? '🔦 టార్చ్ ఆన్ (ఫోన్ యాప్‌లో)' : '🔦 టార్చ్ ఆఫ్'); return true;
      case 'awake': case 'landscape': case 'openMap': return true;
      case 'copy': return true;
      default: throw new Error('no such power: ' + name);
    }
  }

  const WHEN = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})$/;
  function whenMs(w) {
    if (typeof w === 'number' && isFinite(w)) return w;
    const m = WHEN.exec(String(w == null ? '' : w).trim());
    if (!m) throw new Error('App.remind: when must be "YYYY-MM-DD HH:MM" (or a time in ms), got ' + JSON.stringify(w));
    return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]).getTime();
  }
  /** 🔔 A reminder (a notification at that time, even when the app is closed; again every day / week / month / year). */
  App.remind = async (o) => {
    const r = Object.assign({}, o || {});
    r.id = cleanId(r.id || App.uid());
    r.at = whenMs(r.when != null ? r.when : r.at);
    r.every = Object.prototype.hasOwnProperty.call(EVERY, r.every) ? r.every : null;
    if (!r.every && r.at < Date.now() - 60000) throw new Error('App.remind: that time has already passed');
    r.title = String(r.title || App.name).slice(0, 80);
    r.text = String(r.text || '').slice(0, 240);
    r.screen = r.screen ? String(r.screen).slice(0, 40) : '';
    delete r.when;
    await phone('remind', r);
    return r.id;
  };
  App.unremind = (id) => phone('unremind', { id: cleanId(id) });
  App.reminders = async () => { const l = await phone('reminders', {}); return Array.isArray(l) ? l : []; };
  /** 📷 A photo from the camera or the gallery, kept inside this app: its id (show it with App.photoUrl), or null. */
  App.photo = async (from) => { const id = await phone('photo', { from: from === 'gallery' ? 'gallery' : 'camera' }); return id ? String(id) : null; };
  App.photoUrl = (id) => {
    const s = String(id == null ? '' : id);
    if (!/^p[a-z0-9]{2,40}$/.test(s)) return '';
    return bridge() ? 'https://app.photos/' + s + '.jpg' : (previewPhotos.get(s) || samplePic(1));
  };
  App.unphoto = (id) => phone('unphoto', { id: String(id == null ? '' : id) });
  /** 📍 Where the phone is now (asked only when he taps): {lat, lon, accuracy}. Never in the background. */
  App.where = () => phone('where', {});
  App.openMap = (lat, lon, label) => phone('openMap', { lat: Number(lat), lon: Number(lon), label: String(label || '') });
  /** 📤 To WhatsApp or anywhere he picks (Android's share screen); 📄 a page of HTML as a PDF (Android's print / PDF screen). */
  App.share = (text) => phone('share', { text: String(text == null ? '' : text).slice(0, 20000) });
  App.sharePhoto = (id, text) => phone('sharePhoto', { id: String(id == null ? '' : id), text: String(text == null ? '' : text).slice(0, 2000) });
  App.pdf = (title, html) => phone('pdf', { title: String(title || App.name).slice(0, 80), html: String(html == null ? '' : html) });
  /** 🗣️ Read aloud (Telugu); 🎙️ what he says, as text (or null). */
  App.say = (text) => phone('say', { text: String(text == null ? '' : text).slice(0, 3000) });
  App.listen = async (prompt) => { const t = await phone('listen', { prompt: String(prompt || '') }); return t ? String(t) : null; };
  /** 📲 The home-screen widget's words: a title and up to 3 short lines. */
  App.widget = (title, lines) => phone('widget', { title: String(title || App.name).slice(0, 40),
    lines: (Array.isArray(lines) ? lines : [lines]).filter((x) => x != null).slice(0, 3).map((x) => String(x).slice(0, 60)) });
  App.torch = (on) => phone('torch', { on: on !== false });
  App.awake = (on) => phone('awake', { on: on !== false });
  App.landscape = (on) => phone('landscape', { on: on !== false });
  App.copy = (text) => phone('copy', { text: String(text == null ? '' : text).slice(0, 20000) }).then(() => { App.toast('కాపీ చేశాను'); return true; });
  /** ▦ A QR / bar code read with the camera: its text, or null. */
  App.scan = async () => { const t = await phone('scan', {}); return t ? String(t) : null; };

  // 🗣️ Jarvis writes into this app ("Jarvis, ఛార్జింగ్ లాగ్‌లో 4 యూనిట్లు ₹36 రాయి"): the app opens and the handler gets the data.
  const jarvisHooks = {};
  App.onJarvis = (command, fn) => { jarvisHooks[cleanId(command)] = wrap(fn, owner()); };
  App.__jarvis = (command, data) => {
    const h = jarvisHooks[cleanId(command)];
    if (!h) { App.toast('ఈ యాప్‌కి ఆ పని తెలియదు: ' + command, 3); return false; }
    Promise.resolve(h(data && typeof data === 'object' ? data : {})).then((line) => {
      App.toast('✓ ' + (line ? String(line) : 'Jarvis రాశాడు'), 3);
      try { App.refresh(); } catch (e) { fail(e, null); }
    });
    return true;
  };
  /** (a reminder was tapped) its screen. */
  App.__open = (screen) => { try { if (screen && byId(String(screen))) App.go(String(screen)); } catch (e) { fail(e, null); } };

  // 🎨 The app's own icon (drawn by the AI as a few paths on a 108 x 108 square): the APK's launcher icon, and the splash.
  const PATH_OK = /^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]{1,1500}$/;
  const COLOR_OK = /^#[0-9a-fA-F]{6}$/;
  function iconSvg(ic, px) {
    if (!ic || !Array.isArray(ic.paths) || !ic.paths.length) return '';
    const bg = COLOR_OK.test(ic.bg || '') ? ic.bg : App.color, fg = COLOR_OK.test(ic.fg || '') ? ic.fg : '#ffffff';
    let s = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="${px}" height="${px}"><circle cx="54" cy="54" r="54" fill="${bg}"/>`;
    for (const p of ic.paths.slice(0, 8)) {
      const d = typeof p === 'string' ? p : p && p.d, f = p && COLOR_OK.test(p.fill || '') ? p.fill : fg;
      if (typeof d === 'string' && PATH_OK.test(d)) s += `<path d="${d}" fill="${f}"/>`;
    }
    return s + '</svg>';
  }
  App.icon = (px) => iconSvg(HOST.icon, px || 72);
  function splash(ms) {
    const svg = iconSvg(HOST.icon, 104);
    if (!svg || HOST.testing) return;
    const sp = el('div', { id: 'kit-splash' });
    sp.innerHTML = svg + '<div class="n">' + App.esc(App.name) + '</div>';
    document.body.append(sp);
    rawTimeout(() => { sp.classList.add('off'); rawTimeout(() => sp.remove(), 320); }, ms);
  }

  // ------------------------------------------------------------------ the host's side: setting up, parts, start, self-test

  function setup(o) {
    const opt = o || {};
    App.name = String(opt.name || App.name);
    App.color = /^#[0-9a-fA-F]{6}$/.test(opt.color || '') ? opt.color : App.color;
    document.documentElement.style.setProperty('--accent', App.color);
    document.documentElement.setAttribute('data-theme', opt.theme === 'dark' ? 'dark' : 'light');
    HOST.net = !!opt.net;
    HOST.error = opt.error || null;
    HOST.store = opt.store || memoryStore();
    HOST.icon = opt.icon && typeof opt.icon === 'object' ? opt.icon : null;
    HOST.jarvis = Array.isArray(opt.jarvis) ? opt.jarvis.slice(0, 8) : [];
    frame();
    titleEl.textContent = App.name;
  }

  function memoryStore() {
    const m = new Map();
    return { get: (k) => (m.has(k) ? m.get(k) : null), set: (k, v) => { m.set(k, String(v)); }, del: (k) => { m.delete(k); }, keys: () => Array.from(m.keys()) };
  }

  function localStore() {
    const P = 'app:';
    return {
      get: (k) => localStorage.getItem(P + k),
      set: (k, v) => { localStorage.setItem(P + k, v); },
      del: (k) => { localStorage.removeItem(P + k); },
      keys: () => { const out = []; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k && k.startsWith(P)) out.push(k.slice(P.length)); } return out; },
    };
  }

  /** One part: its CSS and HTML go into the page, then its JavaScript runs once. {ok:true} or {err}. */
  async function addPart(p) {
    frame();
    const id = cleanId(p && p.id);
    if (p.css) { const s = el('style', { 'data-part': id }); s.textContent = String(p.css); document.head.append(s); }
    if (p.html) {
      const t = document.createElement('template');
      t.innerHTML = String(p.html);
      for (const bad of t.content.querySelectorAll('script, iframe, object, embed, base, meta, link')) bad.remove();
      for (const n of t.content.querySelectorAll('*')) for (const a of Array.from(n.attributes)) if (/^on/i.test(a.name)) n.removeAttribute(a.name);
      for (const n of Array.from(t.content.children)) n.setAttribute('data-part', id); // (which part brought it: for the self-test's messages)
      main.append(t.content);
    }
    if (p.js && String(p.js).trim()) {
      let fn;
      try { fn = new AsyncFunction('App', String(p.js) + '\n//# sourceURL=part-' + id + '.js'); } catch (e) { return { err: describe(e) }; }
      const prev = loadingPart;
      loadingPart = id;
      try { await fn.call(undefined, App); } catch (e) { return { err: describe(e) }; } finally { loadingPart = prev; }
    }
    return { ok: true };
  }

  /** All parts are in: the start hooks run, the tabs are made and the first screen shows. */
  async function start() {
    if (started) return;
    started = true;
    for (const h of startHooks) await h();
    tabsBuild();
    const f = firstScreen();
    if (!f) throw new Error('the app has no screen (a <section class="screen" id="..."> in some part\'s HTML)');
    stack = [f];
    show(f, undefined);
    App.__started = true;
    splash(bridge() ? 750 : 450);
  }

  const visible = (e) => !!(e && e.isConnected && (e.offsetWidth || e.offsetHeight || e.getClientRects().length) && !e.closest('[hidden]'));
  const CLICKABLE = 'button, .btn, .item, .chip, .fab, [data-go], [data-id], [data-action], [role=button], input[type=checkbox], input[type=radio], a, .card[tabindex]';

  function fill(root) {
    for (const inp of root.querySelectorAll('input, textarea, select')) {
      if (!visible(inp) || inp.disabled || inp.readOnly) continue;
      const t = (inp.type || 'text').toLowerCase();
      if (t === 'checkbox' || t === 'radio' || t === 'file' || t === 'hidden' || t === 'button' || t === 'submit') continue;
      if (inp.tagName === 'SELECT') { if (inp.options.length > 1) inp.selectedIndex = 1; }
      else if (t === 'number' || t === 'range') inp.value = '5';
      else if (t === 'date') inp.value = App.today();
      else if (t === 'time') inp.value = '09:30';
      else if (t === 'month') inp.value = App.monthKey(new Date());
      else if (t === 'tel') inp.value = '9876543210';
      else if (t === 'email') inp.value = 'test@example.com';
      else if (!inp.value) inp.value = 'పరీక్ష';
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  /**
   * 🧪 The self-test: every screen is opened, its boxes filled and its buttons tapped (dialogs answer themselves), within
   * a few seconds. The first error ends it: {err, id, msg}; else {ok, screens, taps}.
   */
  async function test(maxMs) {
    const limit = Math.max(3000, maxMs || 20000), t0 = Date.now();
    let first = null, taps = 0;
    const was = HOST.error;
    HOST.testing = true;
    HOST.error = (id, msg) => { if (!first) first = { id, msg }; };
    try {
      try { await start(); } catch (e) { fail(e, null); }
      await sleep(60);
      if (!first && !cur) first = { id: null, msg: 'nothing shows when the app opens (no screen)' };
      if (!first) { // the same id twice (a screen and a button, say): App.$('#x') would find the wrong one
        const seenIds = new Map();
        for (const n of main.querySelectorAll('[id]')) {
          if (!seenIds.has(n.id)) { seenIds.set(n.id, n); continue; }
          const pe = n.closest('[data-part]'), pa = seenIds.get(n.id).closest('[data-part]');
          first = { id: pe ? pe.getAttribute('data-part') : null, msg: 'the id "' + n.id + '" is used twice (also in the part "'
            + (pa ? pa.getAttribute('data-part') : '?') + '"): every id, screens included, must be different' };
          break;
        }
      }
      const ids = screens().map((s) => s.id).filter(Boolean).slice(0, 14);
      const rounds = ids.concat(ids); // (twice: the second time there is data from the first, so lists and their rows get tapped too)
      for (let k = 0; k < rounds.length; k++) {
        const id = rounds[k];
        if (first || Date.now() - t0 > limit) break;
        try { App.go(id); } catch (e) { fail(e, null); break; }
        await sleep(50);
        const root = byId(id);
        if (!root) continue;
        fill(root);
        const list = App.$$(CLICKABLE, root).concat(App.$$('#kit-actions button')).filter(visible).slice(0, 24);
        for (const b of list) {
          if (first || Date.now() - t0 > limit || taps >= 140) break;
          if (!b.isConnected || !visible(b)) continue;
          if (cur !== id) { try { App.go(id); } catch (e) { fail(e, null); break; } await sleep(30); fill(root); }
          if (!b.isConnected || !visible(b)) continue;
          taps++;
          try { b.click(); } catch (e) { fail(e, null); }
          await sleep(45);
          if (dialogEl.classList.contains('on')) await sleep(60);
        }
        if (!first && k < ids.length && root && !root.textContent.trim() && !root.querySelector('svg, canvas, img, input'))
          first = { id: root.getAttribute('data-part'), msg: 'the screen "' + id + '" shows nothing at all', screen: id };
      }
      // 🗣️ every command Jarvis can send: once, with sample data of the right kinds
      for (const cmd of Object.keys(jarvisHooks)) {
        if (first || Date.now() - t0 > limit) break;
        const spec = HOST.jarvis.find((j) => j && cleanId(j.id) === cmd);
        const data = {};
        for (const [k, kind] of Object.entries((spec && spec.fields) || {})) {
          const t = String(kind || '').replace('?', '');
          data[k] = t === 'number' ? 5 : t === 'date' ? App.today() : t === 'time' ? '09:30' : t === 'bool' ? true : 'పరీక్ష';
        }
        try { App.__jarvis(cmd, data); } catch (e) { fail(e, null); }
        await sleep(60);
      }
      for (let i = 0; i < 6 && !first; i++) { try { if (!App.back()) break; } catch (e) { fail(e, null); } await sleep(20); }
      await sleep(80);
    } finally {
      HOST.testing = false;
      HOST.error = was;
    }
    return first ? { err: true, id: first.id, msg: first.msg, screen: first.screen } : { ok: true, screens: screens().length, taps };
  }

  /** Inside a built app (APK): set up, every part, then start. Errors show as a small line (the studio can repair them). */
  async function boot(opts, parts) {
    let shown = 0;
    setup(Object.assign({}, opts, {
      store: localStore(),
      error: (id, msg) => { console.error('[' + id + '] ' + msg); if (shown++ < 3) App.toast('⚠️ లోపం: ' + msg, 4); },
    }));
    for (const p of parts || []) {
      const r = await addPart(p);
      if (r.err) { console.error('[' + p.id + '] ' + r.err); if (shown++ < 3) App.toast('⚠️ లోపం: ' + r.err, 4); }
    }
    try { await start(); } catch (e) { fail(e, null); }
  }

  App.__boot = boot;
  Object.defineProperty(window, '__appkit', { value: Object.freeze({ setup, addPart, start, test, cleanId, describe }), enumerable: false });
})();
