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
    { k: 'added', n: '➕ మీరు పెట్టినవి (ఫోన్ / 🏠 ఇల్లు)' },
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
      if (b.src === 'file' || b.src === 'scan' || b.home) out.added.push(b);
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
    if (m === 'nolicence') return { i: '📖', s: 'వివరాలు మాత్రమే: ఉచిత గుర్తు ఉన్న కాపీ లేదు', cls: 'info' };
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

  /**
   * 📚 What to read next (₹0, from the racks): free books not read yet by an author he read, of a rack he reads, or of the same
   * time; with nothing read yet, one book from each rack to start with. [{b, score, why}], best first, at most 8.
   */
  function nextReads(books, pos) {
    const p = pos || {};
    const read = books.filter((b) => b.state === 'ready' && (p[b.id] || b.done || b.fav));
    const readIds = new Set(read.map((b) => b.id));
    const key = (a) => String(a || '').toLowerCase().replace(/[^a-z\u0C00-\u0C7F ]/g, ' ').replace(/\s+/g, ' ').trim();
    const secName = (k) => { const x = SECTIONS.find((s) => s.k === k); return x ? x.e + ' ' + x.n : ''; };
    const cands = books.filter((b) => !b.wish && !readIds.has(b.id) && (b.starter || b.free || b.state === 'ready') && b.src !== 'home');
    const out = [];
    cands.forEach((c) => {
      let score = 0, why = '';
      read.forEach((r) => {
        if (c.a && r.a && key(c.a) && key(c.a) === key(r.a)) { if (score < 3) { score = 3; why = r.a + ' రాసినదే (మీరు "' + r.t + '" చదివారు)'; } }
        else if (c.sec && c.sec === r.sec && c.sec !== 'mine') { if (score < 1) { score = 1; why = '"' + r.t + '" లాంటిదే · ' + secName(c.sec); } }
        else if (c.era && c.era === r.era) { if (score < 0.5) { score = 0.5; why = '"' + r.t + '" కాలం నాటిదే'; } }
      });
      if (score > 0) out.push({ b: c, score, why });
    });
    if (!read.length) SECTIONS.forEach((s) => { const f = cands.find((c) => c.sec === s.k); if (f) out.push({ b: f, score: 0.1, why: s.e + ' ' + s.n + ' నుంచి మొదలుపెట్టడానికి' }); });
    out.sort((x, y) => y.score - x.score || String(x.b.t).localeCompare(String(y.b.t)));
    return out.slice(0, 8);
  }

  /** 🗺️ Where each person sits on the map: the most linked in the middle, the rest on rings (no randomness: the same each time). */
  function mapLayout(people, links, w, h) {
    const deg = {};
    people.forEach((p) => { deg[p.n] = 0; });
    (links || []).forEach((l) => { if (l[0] in deg) deg[l[0]]++; if (l[1] in deg) deg[l[1]]++; });
    const order = people.slice().sort((a, b) => (deg[b.n] - deg[a.n]) || ((b.u || []).length - (a.u || []).length));
    const cx = w / 2, cy = h / 2, pos = {};
    // rings of 1, 6, 12, 18 … people; the rings spread over the whole picture (room left for the names)
    const cap = (k) => (k === 0 ? 1 : 6 * k);
    let rings = 0, room = 0;
    while (room < order.length) { room += cap(rings); rings++; }
    const rx = Math.max(10, w / 2 - 46), ry = Math.max(10, h / 2 - 26);
    let ring = 0, slot = 0;
    order.forEach((p) => {
      if (ring === 0) { pos[p.n] = { x: Math.round(cx), y: Math.round(cy), d: deg[p.n] }; ring = 1; slot = 0; return; }
      const f = rings <= 2 ? 0.82 : 0.45 + 0.55 * (ring - 1) / (rings - 2);
      const inRing = Math.min(cap(ring), order.length - (ring === 1 ? 1 : 1 + 3 * ring * (ring - 1)));
      const a = (slot / Math.max(1, inRing)) * Math.PI * 2 - Math.PI / 2 + ring * 0.5;
      pos[p.n] = { x: Math.round(cx + Math.cos(a) * rx * f), y: Math.round(cy + Math.sin(a) * ry * f), d: deg[p.n] };
      slot++;
      if (slot >= cap(ring)) { ring++; slot = 0; }
    });
    return pos;
  }

  /** 🗺️ How much a person matters in the book: chapters they are in (×2) + their links. */
  function mapRank(people, links) {
    const r = {};
    people.forEach((p) => { r[p.n] = 2 * (p.u || []).length; });
    (links || []).forEach((l) => { if (l[0] in r) r[l[0]]++; if (l[1] in r) r[l[1]]++; });
    return r;
  }

  /**
   * 🗺️ Who the map shows: {scope: -1 the whole book | a chapter (0-based), focus: a name | null, limit}.
   * No focus → the most important `limit` people (of that chapter) and the links between them.
   * A focus → that person in the middle and the `limit` most important people linked to them (in that chapter).
   * → {people, links, shown, total, more}
   */
  function mapPick(people, links, rank, opt) {
    const o = opt || {}, lim = Math.max(1, o.limit || 20), sc = o.scope === undefined ? -1 : o.scope;
    const by = (a, b) => (rank[b.n] || 0) - (rank[a.n] || 0) || String(a.n).localeCompare(String(b.n));
    const pool = people.filter((p) => sc < 0 || (p.u || []).indexOf(sc) >= 0);
    const inPool = {};
    pool.forEach((p) => { inPool[p.n] = p; });
    if (o.focus) {
      const me = people.find((p) => p.n === o.focus);
      if (me) {
        const seen = {}, nb = [];
        (links || []).forEach((l) => {
          const other = l[0] === me.n ? l[1] : l[1] === me.n ? l[0] : null;
          if (other && other !== me.n && inPool[other] && !seen[other]) { seen[other] = 1; nb.push(inPool[other]); }
        });
        nb.sort(by);
        const show = nb.slice(0, lim), keep = { [me.n]: 1 };
        show.forEach((p) => { keep[p.n] = 1; });
        const ls = (links || []).filter((l) => (l[0] === me.n && keep[l[1]]) || (l[1] === me.n && keep[l[0]]));
        return { people: [me].concat(show), links: ls, shown: show.length, total: nb.length, more: nb.length - show.length, focus: me.n };
      }
    }
    const show = pool.slice().sort(by).slice(0, lim), keep = {};
    show.forEach((p) => { keep[p.n] = 1; });
    const ls = (links || []).filter((l) => keep[l[0]] && keep[l[1]] && l[0] !== l[1]);
    return { people: show, links: ls, shown: show.length, total: pool.length, more: pool.length - show.length, focus: null };
  }

  /** 🗺️ One person in the middle, the others around (one ring up to 12, else an inner and an outer ring, taking turns). */
  function egoLayout(center, others, w, h) {
    const cx = w / 2, cy = h / 2, pos = {};
    pos[center] = { x: Math.round(cx), y: Math.round(cy) };
    const n = others.length, rx = Math.max(10, w / 2 - 50), ry = Math.max(10, h / 2 - 28);
    others.forEach((name, i) => {
      const a = (i / Math.max(1, n)) * Math.PI * 2 - Math.PI / 2;
      const f = n <= 12 ? 0.86 : (i % 2 === 0 ? 0.95 : 0.58);
      pos[name] = { x: Math.round(cx + Math.cos(a) * rx * f), y: Math.round(cy + Math.sin(a) * ry * f) };
    });
    return pos;
  }

  /** [2,3,4,5,9,11,12] → "2–5, 9, 11–12" */
  function ranges(nums) {
    const a = Array.from(new Set(nums || [])).filter((x) => Number.isFinite(x)).sort((x, y) => x - y);
    const out = [];
    for (let i = 0; i < a.length; i++) {
      let j = i;
      while (j + 1 < a.length && a[j + 1] === a[j] + 1) j++;
      out.push(j > i ? a[i] + '–' + a[j] : String(a[i]));
      i = j;
    }
    return out.join(', ');
  }

  // ================================================================ 🎙️ his words (Telugu or English, as the phone wrote them)
  const TE_C = { 'క': 'k', 'ఖ': 'kh', 'గ': 'g', 'ఘ': 'gh', 'ఙ': 'n', 'చ': 'ch', 'ఛ': 'ch', 'జ': 'j', 'ఝ': 'jh', 'ఞ': 'n', 'ట': 't', 'ఠ': 'th', 'డ': 'd', 'ఢ': 'dh',
    'ణ': 'n', 'త': 't', 'థ': 'th', 'ద': 'd', 'ధ': 'dh', 'న': 'n', 'ప': 'p', 'ఫ': 'f', 'బ': 'b', 'భ': 'bh', 'మ': 'm', 'య': 'y', 'ర': 'r', 'ఱ': 'r', 'ల': 'l', 'ళ': 'l',
    'వ': 'v', 'శ': 'sh', 'ష': 'sh', 'స': 's', 'హ': 'h' };
  const TE_V = { 'అ': 'a', 'ఆ': 'a', 'ఇ': 'i', 'ఈ': 'i', 'ఉ': 'u', 'ఊ': 'u', 'ఋ': 'ru', 'ఎ': 'e', 'ఏ': 'e', 'ఐ': 'ai', 'ఒ': 'o', 'ఓ': 'o', 'ఔ': 'au' };
  const TE_S = { 'ా': 'a', 'ి': 'i', 'ీ': 'i', 'ు': 'u', 'ూ': 'u', 'ృ': 'ru', 'ె': 'e', 'ే': 'e', 'ై': 'ai', 'ొ': 'o', 'ో': 'o', 'ౌ': 'au' };
  /** Telugu letters → rough English letters ("షెర్లాక్ హోమ్స్" → "sherlak homs"), so a name said in Telugu can meet its English spelling. */
  function te2lat(s) {
    let out = '';
    const a = Array.from(String(s || ''));
    for (let i = 0; i < a.length; i++) {
      const ch = a[i];
      if (TE_C[ch]) {
        const nx = a[i + 1];
        out += TE_C[ch];
        if (nx === '్') i++;
        else if (TE_S[nx]) { out += TE_S[nx]; i++; }
        else out += 'a';
      } else if (TE_V[ch]) out += TE_V[ch];
      else if (ch === 'ం') out += 'm';
      else if (ch === 'ః' || ch === '‌' || ch === '‍' || ch === '్') { /* (nothing said) */ }
      else out += ch;
    }
    return out;
  }
  /** Both scripts into one plain spelling for comparing ("Sherlock" ≈ "షెర్లాక్"). */
  function vnorm(s) {
    let t = te2lat(String(s || '').toLowerCase()).replace(/[^a-z0-9 ]+/g, ' ');
    t = t.replace(/ph/g, 'f').replace(/ck/g, 'k').replace(/q/g, 'k').replace(/x/g, 'ks').replace(/w/g, 'v').replace(/z/g, 'j')
      .replace(/c(?=[eiy])/g, 's').replace(/ch/g, 'c').replace(/c/g, 'k').replace(/([tdkgbs])h/g, '$1').replace(/y/g, 'i')
      .replace(/ee|ii/g, 'i').replace(/oo|uu|ou/g, 'u').replace(/aa/g, 'a').replace(/(.)\1+/g, '$1');
    return t.replace(/\s+/g, ' ').trim();
  }
  function grams(w) { const g = []; for (let i = 0; i < w.length - 1; i++) g.push(w.slice(i, i + 2)); return g; }
  function dice(a, b) {
    if (!a || !b) return 0;
    if (a === b) return 1;
    const x = grams(a), y = grams(b);
    if (!x.length || !y.length) return 0;
    const pool = y.slice();
    let hit = 0;
    x.forEach((g) => { const k = pool.indexOf(g); if (k >= 0) { hit++; pool.splice(k, 1); } });
    return 2 * hit / (x.length + y.length);
  }
  /** How alike two single words are (letters, and the consonants alone: "homs" ≈ "holmes"). */
  function subseq(x, y) { let j = 0; for (let i = 0; i < y.length && j < x.length; i++) if (y[i] === x[j]) j++; return j === x.length; }
  function wordLike(a, b) {
    const ca = a.replace(/[aeiou]/g, ''), cb = b.replace(/[aeiou]/g, '');
    // (a silent letter: "homs" said, "holmes" written → the same consonants in order, same first letter)
    const quiet = ca.length >= 3 && cb.length >= 3 && ca[0] === cb[0] && Math.abs(ca.length - cb.length) <= 1 && (subseq(ca, cb) || subseq(cb, ca)) ? 0.8 : 0;
    return Math.max(dice(a, b), ca.length > 1 && cb.length > 1 ? dice(ca, cb) * 0.95 : 0, a.length >= 4 && (b.startsWith(a) || a.startsWith(b)) ? 0.9 : 0, quiet);
  }
  const VSTOP = new Set(['the', 'of', 'a', 'an', 'and', 'in', 'to', 'tu', 'or', 'ni', 'nu', 'lo', 'pustakam', 'book']);
  /** How well what he said names this (0–1): each word he said finds its best word in the name. */
  function sayLike(said, name) {
    const w = vnorm(said).split(' ').filter((x) => x.length > 1 && !VSTOP.has(x));
    const n = vnorm(name).split(' ').filter((x) => x.length > 1 && !VSTOP.has(x));
    if (!w.length || !n.length) return 0;
    let sum = 0;
    w.forEach((x) => { let best = 0; n.forEach((y) => { best = Math.max(best, wordLike(x, y)); }); sum += best; });
    return sum / w.length;
  }
  /** The best of `items` for what he said: names(item) → its names (English, Telugu…). → {item, score} | null below `min`. */
  function bestSaid(said, items, names, min) {
    let best = null, bs = 0;
    (items || []).forEach((it) => {
      let sc = 0;
      names(it).forEach((n) => { if (n) sc = Math.max(sc, sayLike(said, n)); });
      if (sc > bs + 1e-9) { bs = sc; best = it; }
    });
    return best && bs >= (min || 0.6) ? { item: best, score: bs } : null;
  }
  const ORD = [['మొదటి', 1], ['ఒకటో', 1], ['రెండో', 2], ['రెండవ', 2], ['మూడో', 3], ['మూడవ', 3], ['నాలుగో', 4], ['నాలుగవ', 4], ['ఐదో', 5], ['ఐదవ', 5],
    ['ఆరో', 6], ['ఆరవ', 6], ['ఏడో', 7], ['ఏడవ', 7], ['ఎనిమిదో', 8], ['ఎనిమిదవ', 8], ['తొమ్మిదో', 9], ['తొమ్మిదవ', 9], ['పదో', 10], ['పదవ', 10]];
  /**
   * 🎙️ A library word he said → {c, arg?, lang?} | null (not a library word: the page then looks at what is open, then the brain).
   * c: note mark unmark me marks hall hallnext hallprev out nextq next prev chapter bigger smaller sum quiz map audio read pause search open back
   */
  function voiceCmd(raw) {
    const t = String(raw || '').trim().replace(/[.!?।,]+$/g, '').trim();
    const s = t.toLowerCase();
    if (!s) return null;
    const has = (...ws) => ws.some((w) => s.includes(w));
    let m = t.match(/^(నోట్|note)\s*(రాయి|పెట్టు|:|-)?\s*/i);
    if (m && t.length > m[0].length) return { c: 'note', arg: t.slice(m[0].length).trim() };
    if (has('నా చదువు', 'లెక్కలు', 'స్టాట్స్', 'stats', 'my reading')) return { c: 'me' };
    if (has('గుర్తులు చూపించు', 'నా గుర్తులు', 'నోట్స్ చూపించు', 'గుర్తులన్నీ', 'my marks', 'my notes')) return { c: 'marks' };
    if (has('గుర్తు తీసేయి', 'గుర్తు తీసెయ్', 'గుర్తు తీయి', 'unmark')) return { c: 'unmark' };
    if (has('గుర్తు పెట్టు', 'గుర్తుపెట్టు', 'హైలైట్', 'highlight', 'mark this', 'mark it')) return { c: 'mark' };
    if (has('కాలం హాల్', 'హాల్లో నడ', 'హాల్‌లో నడ', 'time hall', 'టైం హాల్', 'టైమ్ హాల్')) return { c: 'hall' };
    if (has('తర్వాతి కాలం', 'తరువాతి కాలం', 'next era')) return { c: 'hallnext' };
    if (has('వెనకటి కాలం', 'ముందటి కాలం', 'previous era')) return { c: 'hallprev' };
    if (has('బయటకు', 'బయటికి', 'exit')) return { c: 'out' };
    if (has('తర్వాతి ప్రశ్న', 'తరువాతి ప్రశ్న', 'next question')) return { c: 'nextq' };
    if (has('తర్వాతి అధ్యాయం', 'తరువాతి అధ్యాయం', 'నెక్స్ట్ చాప్టర్', 'next chapter')) return { c: 'next' };
    if (has('ముందటి అధ్యాయం', 'వెనకటి అధ్యాయం', 'ముందు అధ్యాయం', 'previous chapter', 'ప్రీవియస్ చాప్టర్')) return { c: 'prev' };
    m = s.match(/(?:అధ్యాయం|chapter|చాప్టర్)\s*(?:నంబర్|number)?\s*(\d+)/) || s.match(/(\d+)\s*(?:వ|వది|ో)?\s*(?:అధ్యాయం|chapter|చాప్టర్)/);
    if (m) return { c: 'chapter', arg: Number(m[1]) };
    if (has('అధ్యాయం', 'అధ్యాయానికి', 'chapter')) for (const [w, n] of ORD) if (s.includes(w)) return { c: 'chapter', arg: n };
    if (has('పెద్దవి', 'పెద్దగా', 'పెద్ద అక్షరాలు', 'bigger', 'larger')) return { c: 'bigger' };
    if (has('చిన్నవి', 'చిన్నగా', 'చిన్న అక్షరాలు', 'smaller')) return { c: 'smaller' };
    if (has('సారాంశం', 'summary')) return { c: 'sum' };
    if (has('క్విజ్', 'quiz')) return { c: 'quiz' };
    if (has('ఆడియో ఫైల్', 'audio file')) return { c: 'audio' };
    if (/^(పాత్రలు|పాత్రల మ్యాప్|మ్యాప్|characters|character map|పాత్రలు చూపించు|పాత్రల మ్యాప్ చూపించు|మ్యాప్ చూపించు)$/.test(s)) return { c: 'map' };
    if (has('కొనసాగించు', 'continue', 'resume')) return { c: 'read', lang: '', arg: '' };
    if (/^(ఆపు|ఆపేయి|ఆపెయ్|ఆగు|ఆపండి|pause|stop)$/.test(s) || has('చదవడం ఆపు', 'చదవటం ఆపు', 'stop reading')) return { c: 'pause' };
    if (/(చదువు|చదవు|చదివి వినిపించు|వినిపించు|\bread\b)/.test(s)) {
      const lang = has('ai తెలుగు', 'ఏఐ తెలుగు', 'ai telugu', 'ఏ ఐ తెలుగు') ? 'ai' : has('తెలుగులో', 'telugu') ? 'te' : has('ఇంగ్లీష్', 'ఇంగ్లిష్', 'english') ? 'en' : '';
      const arg = t.replace(/(చదివి వినిపించు|వినిపించు|చదువు|చదవు|\bread\b|ai తెలుగులో|ఏ ?ఐ తెలుగులో|తెలుగులో|ఇంగ్లీష్‌?లో|ఇంగ్లీషులో|ఇంగ్లిష్‌?లో|in english|in telugu|పుస్తకాన్ని|పుస్తకం|\bthe book\b)/gi, ' ')
        .replace(/\s(ని|ను)(?=\s|$)/g, ' ').replace(/\s+/g, ' ').trim();
      return { c: 'read', lang, arg: arg.length > 1 ? arg : '' };
    }
    m = t.match(/^(.+?)\s*(?:ని|ను)?\s*(?:వెతుకు|వెతికి పెట్టు|వెతకు|సెర్చ్ చేయి|సెర్చ్)$/) || t.match(/^(?:search|find)\s+(?:for\s+)?(.+)$/i);
    if (m) return { c: 'search', arg: m[1].trim() };
    m = t.match(/^(.+?)\s*(?:పుస్తకం|పుస్తకాన్ని)?\s*(?:ని|ను)?\s*(?:తెరువు|తెరవు|తెరిచి చూపించు|ఓపెన్ చేయి|ఓపెన్|చూపించు|చూపెట్టు|చూపు)$/) || t.match(/^(?:open|show)\s+(?:me\s+)?(.+)$/i);
    if (m) return { c: 'open', arg: m[1].replace(/\s(ని|ను)$/, '').trim() };
    if (/^(వెనక్కి|వెనక్కు|back|go back|మూసేయి|close)$/.test(s)) return { c: 'back' };
    return null;
  }
  /** All of `items` that sound like what he said, best first: [{item, score}] (at most n, each ≥ min). */
  function rankSaid(said, items, names, min, n) {
    const out = [];
    (items || []).forEach((it) => {
      let sc = 0;
      names(it).forEach((x) => { if (x) sc = Math.max(sc, sayLike(said, x)); });
      if (sc >= (min || 0.6)) out.push({ item: it, score: sc });
    });
    return out.sort((a, b) => b.score - a.score).slice(0, n || 5);
  }
  /** 🎤 What he said into the search box → the words to search ("షెర్లాక్ హోమ్స్ పుస్తకం కావాలి" → "షెర్లాక్ హోమ్స్"). */
  function searchSaid(raw) {
    let t = String(raw || '').trim().replace(/[.!?।,"“”]+/g, ' ').replace(/\s+/g, ' ').trim();
    const tail = /\s*(పుస్తకాన్ని|పుస్తకం|పుస్తకాలు|బుక్|బుక్స్|book|books|ని|ను|గురించి|కావాలి|కావాలి సర్|వెతుకు|వెతకు|వెతికి పెట్టు|చూపించు|సెర్చ్ చేయి|సెర్చ్|ఉందా|ఉన్నాయా|please|ప్లీజ్)$/i;
    for (let k = 0; k < 6 && tail.test(t); k++) t = t.replace(tail, '').trim();
    t = t.replace(/^(search|find|వెతుకు|సెర్చ్)\s+(for\s+)?/i, '').trim();
    return t;
  }
  /** ❓ Which answer he said: "B", "బి", "రెండోది", "2", or the answer's own words → 0–3 | -1. */
  function quizPick(raw, options) {
    const s = String(raw || '').trim().toLowerCase().replace(/[.!?।,]+$/g, '').replace(/^(జవాబు|answer|ఆప్షన్|option)\s*/, '').trim();
    const L = [['a', 'ఏ', 'ఎ', 'ఒకటి', '1', 'మొదటిది', 'మొదటి'], ['b', 'బి', 'బీ', 'రెండు', '2', 'రెండోది', 'రెండవది', 'రెండో'], ['c', 'సి', 'సీ', 'మూడు', '3', 'మూడోది', 'మూడవది', 'మూడో'],
      ['d', 'డి', 'డీ', 'నాలుగు', '4', 'నాలుగోది', 'నాలుగవది', 'నాలుగో']];
    for (let i = 0; i < L.length && i < (options || []).length; i++) if (L[i].includes(s)) return i;
    let best = -1, bs = 0;
    (options || []).forEach((o, i) => { const sc = Math.max(sayLike(s, o), dice(vnorm(s), vnorm(o))); if (sc > bs) { bs = sc; best = i; } });
    return bs >= 0.6 ? best : -1;
  }
  /** Sounds like a question ("ఎవరు…", "…ఎందుకు?", "who…"). */
  function isQuestion(raw) {
    const s = String(raw || '').trim().toLowerCase();
    return /[?？]$/.test(s) || /(ఎవరు|ఎవరి|ఏమి|ఏం|ఏంటి|ఏమిటి|ఎందుకు|ఎలా|ఎక్కడ|ఎప్పుడు|ఏది|ఎన్ని|ఎంత)/.test(s) || /^(who|what|why|how|where|when|which)\b/.test(s);
  }

  const K = { centre, SECTIONS, ERAS, MY, MY_SHELVES, CASE, racks, hash, colourOf, sizeOf, merge, onRack, myShelves, layout, myLayout, mark,
    yearSaid, pagesSaid, money, rackName, spineLines, wordAt, nextReads, mapLayout, mapRank, mapPick, egoLayout, ranges,
    te2lat, vnorm, sayLike, bestSaid, rankSaid, searchSaid, voiceCmd, quizPick, isQuestion };
  if (typeof module !== 'undefined' && module.exports) module.exports = K;
  root.LibKit = K;
})(typeof window !== 'undefined' ? window : globalThis);
