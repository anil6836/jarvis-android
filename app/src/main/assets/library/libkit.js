// 📚 Jarvis గ్రంథాలయం: the page's plain logic (no drawing) — racks, which books go on which rack and shelf, where each
// book stands, spine colours, the marks a search result gets, sizes said in words. Tested on the desk with node.
(function (root) {
  'use strict';

  const SECTIONS = [
    { k: 'fic', e: '📖', n: 'కథలు, నవలలు' },
    { k: 'kids', e: '🧒', n: 'పిల్లల కథలు' },
    { k: 'hist', e: '🏛️', n: 'చరిత్ర, జీవిత కథలు' },
    { k: 'sci', e: '🔬', n: 'సైన్స్, ప్రకృతి' },
    { k: 'cosmos', e: '🌌', n: 'విశ్వం, భూమి' },
    { k: 'spirit', e: '🪷', n: 'ఆధ్యాత్మికం, తత్వం' },
    { k: 'poetry', e: '✍️', n: 'కవిత్వం, ఇతిహాసాలు, నాటకాలు' },
    { k: 'telugu', e: '🇮🇳', n: 'తెలుగు' },
    { k: 'mine', e: '📂', n: 'నేను పెట్టినవి' },
  ];
  const ERAS = [
    { k: 'universe', e: '🌌', n: 'విశ్వం పుట్టుక', s: 'కోట్ల సంవత్సరాల క్రితం' },
    { k: 'life', e: '🦕', n: 'భూమి, జీవం', s: 'మనిషి పుట్టక ముందు' },
    { k: 'ancient', e: '🏺', n: 'పురాతన కాలం', s: 'క్రీ.శ. 500 దాకా' },
    { k: 'medieval', e: '🏰', n: 'మధ్య యుగం', s: '500 – 1500' },
    { k: 'modern', e: '⚙️', n: 'ఆధునిక కాలం', s: '1500 – 1900' },
    { k: 'now', e: '🚀', n: 'నేటి కాలం', s: '1900 నుంచి' },
  ];
  const MY = { k: 'my', e: '🔖', n: 'నావి' };
  const MY_SHELVES = [
    { k: 'reading', n: '📖 చదువుతున్నవి' },
    { k: 'fav', n: '⭐ ఇష్టమైనవి' },
    { k: 'done', n: '✓ పూర్తయినవి' },
    { k: 'wish', n: '🔖 కోరికలు (వివరాలు మాత్రమే)' },
    { k: 'added', n: '➕ ఫోన్ నుంచి పెట్టినవి' },
  ];

  /** The racks for a view: 'sec' (by subject) or 'era' (by time), "నావి" first. */
  function racks(view) {
    return [MY].concat(view === 'era' ? ERAS : SECTIONS);
  }

  function hash(s) {
    let h = 2166136261 >>> 0;
    const t = String(s || '');
    for (let i = 0; i < t.length; i++) { h ^= t.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
    return h >>> 0;
  }

  // leather / cloth colours of old books (spine, gold letters stay readable on each)
  const COLOURS = ['#6b1d1d', '#1f2f55', '#1d4a33', '#5a3a1e', '#2b2420', '#4a1f3d', '#1b4a4f', '#7a5a14', '#3d4a1a', '#55301f', '#23334a', '#6a2a12'];

  function colourOf(id) { return COLOURS[hash(id) % COLOURS.length]; }

  /** Thickness and height (metres) of a book's spine: thicker for big books. */
  function sizeOf(b) {
    const h = hash(b.id);
    const chars = Number(b.chars) || 0;
    const t = chars > 0 ? Math.min(0.068, 0.03 + Math.sqrt(chars) / 30000) : 0.034 + (h % 23) / 1000;
    return { t: Math.round(t * 1000) / 1000, h: 0.23 + ((h >>> 5) % 8) / 100, d: 0.16 + ((h >>> 9) % 5) / 100 };
  }

  /** The starter racks and his shelf as one list; his entry (downloaded / flags) wins. Wishes stay only on "నావి". */
  function merge(starter, shelf) {
    const mine = {};
    (shelf || []).forEach((b) => { if (b && b.id) mine[b.id] = b; });
    const out = [];
    const seen = {};
    (starter || []).forEach((s) => {
      const m = mine[s.id];
      const b = Object.assign({}, s, m || {}, { starter: true });
      if (!m) b.state = 'none';
      out.push(b);
      seen[s.id] = 1;
    });
    (shelf || []).forEach((b) => { if (b && b.id && !seen[b.id]) out.push(Object.assign({ state: 'none' }, b)); });
    return out;
  }

  /** The books on one rack (not wishes), oldest written first on time racks, else in list order. */
  function onRack(books, view, k) {
    const key = view === 'era' ? 'era' : 'sec';
    const list = books.filter((b) => !b.wish && (b[key] || (view === 'era' ? 'now' : 'mine')) === k);
    if (view === 'era') list.sort((a, b) => (num(a.y) - num(b.y)));
    return list;
  }

  function num(y) { return y === undefined || y === null || y === '' ? 99999 : Number(y); }

  /** The shelves of "నావి": reading (latest first), favourites, finished, wishes, added from the phone. */
  function myShelves(books, pos) {
    const p = pos || {};
    const out = { reading: [], fav: [], done: [], wish: [], added: [] };
    books.forEach((b) => {
      if (b.wish) { out.wish.push(b); return; }
      if (b.state === 'ready' && p[b.id] && !b.done) out.reading.push(b);
      if (b.fav) out.fav.push(b);
      if (b.done) out.done.push(b);
      if (b.src === 'file' || b.src === 'scan') out.added.push(b);
    });
    out.reading.sort((a, b) => ((p[b.id] || [])[2] || 0) - ((p[a.id] || [])[2] || 0));
    return out;
  }

  // a bookcase: inner width 1.6 m; shelf tops (bottom → top); books fill the eye-level shelves first
  const CASE = { w: 1.7, inner: 1.6, h: 2.25, d: 0.36, gap: 0.32, shelves: [0.1, 0.52, 0.94, 1.36, 1.78], board: 0.025 };
  const FILL_ORDER = [3, 2, 4, 1, 0];

  /** A shelf's row moved to the middle of the shelf (a few books stand in the middle, as on a real shelf). */
  function centre(row) {
    if (!row.length) return row;
    const used = row[row.length - 1].x + row[row.length - 1].t / 2 - (row[0].x - row[0].t / 2);
    const shift = (CASE.inner - used) / 2 - (row[0].x - row[0].t / 2);
    row.forEach((p) => { p.x = Math.round((p.x + shift) * 10000) / 10000; });
    return row;
  }

  /** Where each book stands in a bookcase: [{id, shelf, x (from the case's left inner edge, centre of the book), t, h, d}]. */
  function layout(list, order) {
    const ord = order || FILL_ORDER;
    const out = [];
    let si = 0, x = 0.04, row = [];
    for (const b of list) {
      const s = sizeOf(b);
      if (x + s.t > CASE.inner - 0.04) { out.push(...centre(row)); row = []; si++; x = 0.04; }
      if (si >= ord.length) break; // (a full bookcase: the rest wait for "వెతుకు")
      row.push({ id: b.id, shelf: ord[si], x: x + s.t / 2, t: s.t, h: s.h, d: s.d });
      x += s.t + 0.002;
    }
    if (si < ord.length) out.push(...centre(row));
    return out;
  }

  /** "నావి": one shelf per kind, top to bottom; each shelf holds as many as fit (latest first). */
  function myLayout(shelves) {
    const out = [];
    MY_SHELVES.forEach((sh, i) => {
      const shelf = 4 - i;
      let x = 0.04;
      const row = [];
      for (const b of shelves[sh.k] || []) {
        const s = sizeOf(b);
        if (x + s.t > CASE.inner - 0.04) break;
        row.push({ id: b.id, shelf, x: x + s.t / 2, t: s.t, h: s.h, d: s.d, my: sh.k });
        x += s.t + 0.002;
      }
      out.push(...centre(row));
    });
    return out;
  }

  /** A search result's mark: what he can do with it. */
  function mark(r) {
    if (r && r.have) return { i: '✅', s: 'మీ రాక్‌లో ఉంది', cls: 'ok' };
    if (r && r.free) return { i: '✅', s: 'ఉచితం: రాక్‌లోకి దించుకోవచ్చు', cls: 'ok' };
    const m = r && r.mark;
    if (m === 'web') return { i: '🌐', s: 'Open Library సైట్‌లో ఉచితంగా చదవొచ్చు', cls: 'web' };
    if (m === 'borrow') return { i: '🏛️', s: 'Open Library లో అరువు (మీరే సైన్ ఇన్ అయి)', cls: 'web' };
    if (m === 'buy') return { i: '🔒', s: 'కాపీరైట్ ఉంది: కొనాలి / గ్రంథాలయంలో', cls: 'lock' };
    return { i: '📖', s: 'వివరాలు మాత్రమే', cls: 'info' };
  }

  function yearSaid(y) {
    if (y === undefined || y === null || y === '') return '';
    const n = Number(y);
    return n < 0 ? 'క్రీ.పూ. ' + (-n) : String(n);
  }

  /** "సుమారు 320 పేజీలు" from letters (about 1,800 a page). */
  function pagesSaid(chars) {
    const n = Math.max(1, Math.round((Number(chars) || 0) / 1800));
    return 'సుమారు ' + n + ' పేజీలు';
  }

  function money(r) {
    const v = Number(r) || 0;
    return v < 10 ? '₹' + v.toFixed(2) : '₹' + Math.round(v);
  }

  /** The rack's name with its emoji. */
  function rackName(view, k) {
    const r = racks(view).find((x) => x.k === k);
    return r ? r.e + ' ' + r.n : k;
  }

  /** Lines of a title that fit a spine: up to 2 lines of about maxChars each. */
  function spineLines(title, maxChars) {
    const words = String(title || '').trim().split(/\s+/);
    const lines = [''];
    for (const w of words) {
      const cur = lines[lines.length - 1];
      if (!cur) lines[lines.length - 1] = w;
      else if ((cur + ' ' + w).length <= maxChars) lines[lines.length - 1] = cur + ' ' + w;
      else if (lines.length < 2) lines.push(w);
      else { lines[1] = lines[1] + ' ' + w; }
    }
    return lines.map((l) => (l.length > maxChars * 1.6 ? l.slice(0, Math.floor(maxChars * 1.6) - 1) + '…' : l));
  }

  /** The words of a part, for a tap on one word ("meaning"). */
  function wordAt(text, index) {
    const s = String(text || '');
    if (index < 0 || index >= s.length) return '';
    let a = index, b = index;
    while (a > 0 && /[A-Za-z'\-]/.test(s[a - 1])) a--;
    while (b < s.length && /[A-Za-z'\-]/.test(s[b])) b++;
    return s.slice(a, b).replace(/^['\-]+|['\-]+$/g, '');
  }

  const K = { centre, SECTIONS, ERAS, MY, MY_SHELVES, CASE, racks, hash, colourOf, sizeOf, merge, onRack, myShelves, layout, myLayout, mark,
    yearSaid, pagesSaid, money, rackName, spineLines, wordAt };
  if (typeof module !== 'undefined' && module.exports) module.exports = K;
  root.LibKit = K;
})(typeof window !== 'undefined' ? window : globalThis);
