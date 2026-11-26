/* 💰 Jarvis money advisor page. Our own code only; no network. Everything Jarvis sends is data and goes in with textContent. */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = String(text);
    return e;
  }
  function add(parent, ...kids) { kids.forEach((k) => { if (k) parent.appendChild(k); }); return parent; }
  function btn(text, cls, fn) {
    const b = el('button', 'btn' + (cls ? ' ' + cls : ''), text);
    b.type = 'button';
    b.addEventListener('click', (e) => { e.stopPropagation(); fn(e); });
    return b;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); return n; }
  let toastT = 0;
  function toast(s, ms) { const t = $('toast'); t.textContent = s; t.style.display = 'block'; clearTimeout(toastT); toastT = setTimeout(() => { t.style.display = 'none'; }, ms || 3200); }

  /** ₹1,23,456 (Indian grouping) */
  function rs(v) {
    const n = Math.round(Number(v) || 0), neg = n < 0;
    let s = String(Math.abs(n));
    if (s.length > 3) {
      const last = s.slice(-3);
      let rest = s.slice(0, -3);
      const parts = [];
      while (rest.length > 2) { parts.unshift(rest.slice(-2)); rest = rest.slice(0, -2); }
      if (rest) parts.unshift(rest);
      s = parts.join(',') + ',' + last;
    }
    return (neg ? '−₹' : '₹') + s;
  }
  const MON = ['జన', 'ఫిబ్ర', 'మార్చి', 'ఏప్రి', 'మే', 'జూన్', 'జూలై', 'ఆగ', 'సెప్టె', 'అక్టో', 'నవం', 'డిసెం'];
  function when(t) {
    const d = new Date(Number(t) || 0);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12 || 12;
    return d.getDate() + ' ' + MON[d.getMonth()] + ', ' + h + ':' + String(d.getMinutes()).padStart(2, '0') + ' ' + ap;
  }
  const MONF = ['జనవరి', 'ఫిబ్రవరి', 'మార్చి', 'ఏప్రిల్', 'మే', 'జూన్', 'జూలై', 'ఆగస్టు', 'సెప్టెంబర్', 'అక్టోబర్', 'నవంబర్', 'డిసెంబర్'];
  function ym(s) { const m = /^(\d{4})-(\d{2})$/.exec(s || ''); return m ? MONF[parseInt(m[2], 10) - 1] + ' ' + m[1] : (s || ''); }
  function day(t) { const d = new Date(Number(t) || 0); return d.getDate() + ' ' + MON[d.getMonth()] + ' ' + d.getFullYear(); }

  // ---------------------------------------------------------------- talking to Jarvis

  let port = null;
  const outbox = [];
  function post(m) { const s = JSON.stringify(m); if (port) port.postMessage(s); else outbox.push(s); }
  window.addEventListener('message', (e) => {
    if (port || e.data !== 'jarvis-port' || !e.ports || !e.ports[0]) return;
    port = e.ports[0];
    port.onmessage = (ev) => { let m; try { m = JSON.parse(ev.data); } catch (x) { return; } onJarvis(m); };
    outbox.splice(0).forEach((s) => port.postMessage(s));
    post({ t: 'ready' });
  });

  // ---------------------------------------------------------------- state

  let D = null;          // everything from Jarvis (the bundle)
  let tab = 'month';
  let monthIdx = 0;
  let aiBusy = false;
  let meDirty = false;
  let fundPick = null;   // the open holding form's fund search callback

  const TABS = [['month', '📊 ఈ నెల'], ['waste', '🕳️ వృథా'], ['safe', '🛡️ రక్షణ'], ['goals', '🎯 లక్ష్యాలు'], ['invest', '📈 పెట్టుబడులు'],
    ['loans', '💳 అప్పులు'], ['calc', '🧮 లెక్కలు'], ['tax', '🧾 పన్ను'], ['buy', '🛒 కొనొచ్చా?'], ['scheme', '🚨 స్కీమ్ చెక్'], ['ai', '🤖 AI సమీక్ష'], ['me', '⚙️ నా వివరాలు']];

  const GOAL_KINDS = [['house', '🏠 ఇల్లు'], ['car', '🚗 కారు'], ['bike', '🏍️ బండి'], ['education', '🎓 చదువు'], ['wedding', '💍 పెళ్ళి'], ['retire', '🧓 రిటైర్మెంట్'],
    ['travel', '✈️ ప్రయాణం'], ['emergency', '🧯 అత్యవసర నిధి'], ['gadget', '📱 వస్తువు'], ['other', '🎯 ఇతరం']];
  const H_KINDS = [['fd', '🏦 బ్యాంక్ FD'], ['rd', '🏦 RD'], ['savings', '💵 సేవింగ్స్ / క్యాష్'], ['mf', '📈 మ్యూచువల్ ఫండ్'], ['stock', '📊 షేర్లు'], ['ppf', '🏛️ PPF'],
    ['epf', '🏛️ EPF / PF'], ['nps', '🏛️ NPS'], ['post', '📮 పోస్టాఫీస్ స్కీమ్'], ['ssy', '👧 సుకన్య సమృద్ధి'], ['gold', '🪙 బంగారం'], ['lic', '🛡️ LIC / పాలసీ'],
    ['chit', '🤝 చిట్టీ'], ['land', '🏡 స్థలం / ఇల్లు'], ['other', '📦 ఇతరం']];
  const kindName = (list, k) => { const f = list.find((x) => x[0] === k); return f ? f[1] : k; };

  function note(parent) {
    add(parent, el('p', 'note', 'ఇది మీ నిర్ణయానికి సమాచారం మాత్రమే. Jarvis SEBI రిజిస్టర్డ్ సలహాదారు కాదు; ఏ ఫండ్ / షేర్ / కంపెనీ కొనమని చెప్పదు, ఏదీ కట్టదు, కొనదు, అమ్మదు. '
      + 'పెద్ద నిర్ణయాలకు ఫీ తీసుకునే SEBI రిజిస్టర్డ్ సలహాదారు (RIA) ని అడగొచ్చు.'));
  }

  // ---------------------------------------------------------------- tabs

  function buildTabs() {
    const n = $('tabs');
    TABS.forEach(([id, name]) => {
      const b = el('button', 'tab', name);
      b.type = 'button';
      b.dataset.id = id;
      b.addEventListener('click', () => show(id));
      n.appendChild(b);
    });
  }

  function show(id) {
    if (!TABS.some((t) => t[0] === id)) return;
    tab = id;
    document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('on', b.dataset.id === id));
    document.querySelectorAll('main section').forEach((s) => s.classList.toggle('on', s.id === 's-' + id));
    const b = document.querySelector('.tab[data-id="' + id + '"]');
    if (b) b.scrollIntoView({ inline: 'center', block: 'nearest' });
    $('main').scrollTop = 0;
    if (id === 'me' && !meDirty) renderMe();
  }

  function renderAll() {
    if (!D) return;
    renderMonth(); renderWaste(); renderSafe(); renderGoals(); renderInvest(); renderLoans(); renderAi();
    if (!meDirty) renderMe();
  }

  // ---------------------------------------------------------------- 📊 this month

  function renderMonth() {
    const s = clear($('s-month'));
    if (!D.sms) {
      const c = add(el('div', 'card warn'), el('div', '', '📩 బ్యాంక్ / UPI / కార్డ్ SMS చదవడానికి అనుమతి ఇస్తే, ఖర్చులు Jarvis తానే లెక్కిస్తుంది.'),
        el('div', 'small muted', 'SMS ఈ ఫోన్‌లోనే చదివి లెక్కిస్తాను; SMS మాటలు, ఖాతా నంబర్లు ఎక్కడికీ పంపను. ఇప్పుడు మీరు రాసిన ఖర్చులు మాత్రమే లెక్కలో ఉన్నాయి.'));
      add(c, btn('అనుమతి ఇవ్వు', 'main', () => post({ t: 'perm_sms' })));
      s.appendChild(c);
    }
    // the hero: today's allowance
    const a = D.allowance || {};
    const h = el('div', 'hero');
    if (a.left >= 0 && a.plan > 0) {
      add(h, el('div', 'lab', 'ఈరోజు ఇంత వరకు ఖర్చు పెట్టొచ్చు'));
      const big = el('div', 'big' + (a.per_day < (a.plan / 30) * 0.5 ? ' low' : ''), rs(a.per_day));
      h.appendChild(big);
      const m = el('div', 'meter');
      const used = Math.min(100, Math.max(0, (a.spent / a.plan) * 100));
      const fill = el('i', used >= 100 ? 'over' : '');
      fill.style.width = used + '%';
      m.appendChild(fill);
      const mk = el('b');
      const today = new Date(), dim = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
      mk.style.left = Math.min(100, (today.getDate() / dim) * 100) + '%';
      m.appendChild(mk);
      add(h, m, el('div', 'small muted', 'ఈ నెల ప్లాన్ ' + rs(a.plan) + ' లో ' + rs(a.spent) + ' అయింది; ' + rs(Math.max(0, a.left)) + ' మిగిలింది, ' + a.days_left + ' రోజులు. (గీత = ఈరోజు)'));
    } else {
      add(h, el('div', 'lab', 'రోజూ ఎంత ఖర్చు పెట్టొచ్చు?'), el('div', 'small', a.te || ''), btn('⚙️ నా వివరాలు పెట్టు', 'main', () => show('me')));
    }
    s.appendChild(h);
    const months = D.months || [];
    if (!months.length) return;
    if (monthIdx >= months.length) monthIdx = 0;
    const chips = el('div', 'chips');
    months.forEach((m, i) => {
      const b = el('button', 'tab' + (i === monthIdx ? ' on' : ''), m.label + (m.so_far ? ' (ఇప్పటి వరకు)' : ''));
      b.type = 'button';
      b.addEventListener('click', () => { monthIdx = i; renderMonth(); });
      chips.appendChild(b);
    });
    s.appendChild(chips);
    const m = months[monthIdx];
    if (!m.count) {
      add(s, el('div', 'card empty', 'ఈ నెలకి లెక్కలు లేవు' + (D.sms ? ' (బ్యాంక్ SMS లు కనిపించలేదు).' : '.')));
    } else {
      const g = el('div', 'grid2');
      const tile = (k, v, cls) => add(el('div', 'tile'), el('div', 'k', k), el('div', 'v ' + (cls || ''), v));
      add(g, tile('వచ్చింది', rs(m.came_in), 'in'), tile('ఖర్చు', rs(m.spent)), tile('పెట్టుబడి / పొదుపు', rs(m.invested)),
        tile('మిగిలింది', rs(m.left), m.left < 0 ? 'badc' : 'in'));
      s.appendChild(g);
      const line = [];
      if (m.save_rate !== undefined) line.push('జీతంలో ' + m.save_rate + '% మిగిలింది (ఖర్చు తర్వాత).');
      line.push('స్థిర ఖర్చులు (EMI, అద్దె, బిల్లులు) ' + rs(m.fixed) + '; మార్చగలిగేవి ' + rs(m.flexible) + '.');
      if (m.refunds > 0) line.push('రిఫండ్‌లు ' + rs(m.refunds) + '.');
      add(s, el('div', 'small muted', line.join(' ')));
      add(s, el('h2', '', 'ఏ రకానికి ఎంత'));
      const c = el('div', 'card');
      const top = Math.max(1, ...(m.cats || []).map((x) => x.amount));
      (m.cats || []).forEach((x) => {
        const b = el('div', 'bar');
        add(b, add(el('div', 'row'), el('span', 'grow', x.te), el('span', 'tiny', x.n + ' సార్లు'), el('span', 'amt', rs(x.amount))));
        const tr = el('div', 'track'), f = el('i', x.fixed ? 'fixed' : '');
        f.style.width = Math.max(2, (x.amount / top) * 100) + '%';
        add(b, add(tr, f));
        c.appendChild(b);
      });
      add(c, el('div', 'tiny', 'బూడిద రంగు = స్థిర ఖర్చులు; నీలం = మీరు మార్చగలిగేవి.'));
      s.appendChild(c);
      if ((m.payees || []).length) {
        add(s, el('h2', '', 'ఎక్కువ డబ్బు వెళ్ళిన చోట్లు'));
        const p = el('div', 'card');
        m.payees.forEach((x) => add(p, add(el('div', 'row'), el('span', 'grow', x.name), el('span', 'tiny', x.n + ' సార్లు'), el('span', 'amt', rs(x.amount)))));
        s.appendChild(p);
      }
    }
    const act = el('div');
    add(act, btn('➕ ఖర్చు రాయి', 'main', expenseForm), btn('📄 ' + m.label + ' PDF', '', () => { post({ t: 'pdf', month: m.month }); toast('📄 చేస్తున్నాను…'); }));
    s.appendChild(act);
    add(s, el('h2', '', 'ఇటీవలి లావాదేవీలు'));
    const r = el('div', 'card');
    const list = D.recent || [];
    if (!list.length) add(r, el('div', 'empty', 'ఇంకా ఏమీ లేవు.'));
    list.forEach((x) => {
      const row = el('div', 'tx');
      const left = el('div', 'grow');
      left.style.flex = '1';
      left.style.minWidth = '0';
      add(left, el('div', 'who', x.payee || (x.credit ? 'జమ' : 'ఖర్చు')),
        el('div', 'meta', when(x.t) + ' · ' + x.te + (x.bank ? ' · ' + x.bank : '') + (x.src === 'manual' ? ' · మీరు రాసింది' : '')));
      add(row, left, el('div', 'amt ' + (x.credit ? 'in' : 'out'), (x.credit ? '+' : '−') + rs(x.amt)));
      const fixable = !x.credit && x.src === 'sms' && x.payee && x.payee !== 'UPI వ్యక్తి' && x.payee !== 'ATM';
      if (fixable) { const f = el('button', '', 'రకం'); f.type = 'button'; f.addEventListener('click', () => catForm(x)); row.appendChild(f); }
      r.appendChild(row);
    });
    s.appendChild(r);
    note(s);
  }

  function catSelect(cur) {
    const sel = el('select');
    (D.cats || []).filter((c) => !['salary', 'refund', 'self', 'card_bill'].includes(c.id)).forEach((c) => {
      const o = el('option', '', c.te);
      o.value = c.id;
      if (c.id === cur) o.selected = true;
      sel.appendChild(o);
    });
    return sel;
  }

  function catForm(x) {
    const f = openSheet('ఈ చోటి ఖర్చుల రకం');
    add(f, el('div', 'muted small', x.payee + ': ఇప్పుడు "' + x.te + '". మార్చితే ఈ పేరుకి వెళ్ళే అన్ని ఖర్చులూ (పాతవి, కొత్తవి) ఈ రకంలో లెక్కిస్తాను.'));
    const sel = catSelect(x.cat);
    add(f, el('label', 'f', 'రకం'), sel);
    add(f, btn('దాచు', 'main', () => { post({ t: 'payee_cat', payee: x.payee, cat: sel.value }); closeSheet(); toast('✅ మార్చాను'); }),
      btn('Jarvis అంచనాకి వదిలేయి', 'quiet', () => { post({ t: 'payee_cat', payee: x.payee, cat: '' }); closeSheet(); }));
  }

  function expenseForm() {
    const f = openSheet('➕ ఖర్చు రాయి');
    add(f, el('div', 'muted small', 'క్యాష్‌తో కట్టినవి, SMS రాని ఖర్చులు ఇక్కడ రాయండి (UPI / కార్డ్ SMS వచ్చేవి తానే లెక్కలోకి వస్తాయి).'));
    const amt = numIn('', 'ఉదా: 250'), what = textIn('', 'ఏమిటి (ఉదా: కూరగాయలు)'), shop = textIn('', 'ఎక్కడ (ఐచ్ఛికం)'), sel = catSelect('groceries');
    add(f, el('label', 'f', 'ఎంత ₹'), amt, el('label', 'f', 'ఏమిటి'), what, el('label', 'f', 'ఎక్కడ'), shop, el('label', 'f', 'రకం'), sel);
    add(f, btn('దాచు', 'main', () => {
      const v = parseFloat(amt.value);
      if (!(v >= 1)) { toast('ఎంతో రాయండి'); return; }
      post({ t: 'expense', amount: v, what: what.value, shop: shop.value, cat: sel.value });
      closeSheet();
      toast('✅ రాశాను');
    }));
  }

  // ---------------------------------------------------------------- 🕳️ waste and subscriptions

  function renderWaste() {
    const s = clear($('s-waste'));
    const leaks = D.leaks || [];
    add(s, el('h2', '', 'వృథా అవుతున్న డబ్బు'));
    if (!leaks.length) add(s, el('div', 'card empty', 'ఈ నెల పెద్ద వృథా కనిపించలేదు 👍 (నెల మధ్యలో మళ్ళీ చూడండి.)'));
    else {
      const yearly = leaks.reduce((t, x) => t + (x.yearly || 0), 0);
      if (yearly > 0) add(s, el('div', 'small muted', 'ఇవి సగం తగ్గించినా ఏడాదికి సుమారు ' + rs(yearly / 2) + ' మిగులుతుంది.'));
      leaks.forEach((x) => {
        const c = el('div', 'card warn');
        add(c, el('b', '', x.icon + ' ' + x.title));
        add(c, el('div', 'small', x.tip));
        if (x.yearly > 0 && (x.tip || '').indexOf(rs(x.yearly)) < 0) add(c, el('div', 'tiny', 'ఏడాదికి సుమారు ' + rs(x.yearly)));
        s.appendChild(c);
      });
    }
    add(s, el('h2', '', '🔁 ప్రతి నెల కట్ అయ్యేవి'));
    const subs = D.subs || [];
    if (!subs.length) add(s, el('div', 'card empty', 'నెల నెలా కట్ అయ్యే సబ్‌స్క్రిప్షన్‌లు కనిపించలేదు.'));
    subs.forEach((x) => {
      const c = el('div', 'card' + (x.mark === 'cancel' ? ' bad' : x.mark === 'keep' ? ' ok' : ''));
      add(c, add(el('div', 'row'), el('b', 'grow', x.name), el('span', 'amt', rs(x.amount))));
      add(c, el('div', 'small muted', 'ప్రతి నెల సుమారు ' + x.day + ' తేదీన; ఏడాదికి ' + rs(x.yearly) + (x.months > 1 ? '; ' + x.months + ' నెలలు కనిపించింది' : '')));
      add(c, btn('✅ వాడుతున్నా, ఉంచు', x.mark === 'keep' ? 'on' : '', () => post({ t: 'sub_mark', name: x.name, mark: x.mark === 'keep' ? '' : 'keep' })),
        btn('✋ ఆపాలి', x.mark === 'cancel' ? 'on' : '', () => post({ t: 'sub_mark', name: x.name, mark: x.mark === 'cancel' ? '' : 'cancel' })));
      if (x.mark === 'cancel') add(c, el('div', 'tiny', 'ఆ యాప్ / వెబ్‌సైట్‌లో మీరే ఆపాలి (Jarvis ఏదీ ఆపదు). మళ్ళీ కట్ అయ్యే 2 రోజుల ముందు గుర్తు చేస్తాను.'));
      s.appendChild(c);
    });
    note(s);
  }

  // ---------------------------------------------------------------- 🛡️ safety net and next steps

  function renderSafe() {
    const s = clear($('s-safe'));
    add(s, el('h2', '', 'మీ రక్షణ'));
    (D.protection || []).forEach((x) => {
      const c = el('div', 'card ' + (x.st || 'unknown'));
      add(c, el('b', '', x.icon + ' ' + x.title), el('div', 'small', x.now), el('div', 'tiny', x.why));
      s.appendChild(c);
    });
    add(s, el('h2', '', '👣 ఈ వరుసలో చేయండి'));
    const ol = el('ol', 'steps');
    (D.priorities || []).forEach((x) => ol.appendChild(el('li', '', x.te)));
    add(s, add(el('div', 'card'), ol));
    const bal = D.balances || [];
    add(s, el('h2', '', 'బ్యాంక్ బ్యాలెన్స్ (చివరి SMS లో)'));
    if (!bal.length) add(s, el('div', 'card empty', D.sms ? 'బ్యాలెన్స్ ఉన్న SMS కనిపించలేదు.' : 'SMS అనుమతి ఇస్తే ఇక్కడ కనిపిస్తుంది.'));
    else {
      const c = el('div', 'card');
      bal.forEach((b) => add(c, add(el('div', 'row'), el('span', 'grow', b.bank), el('span', 'amt', rs(b.amount)))));
      add(c, el('div', 'tiny', 'ఖాతా నంబర్లు చూపించను. అత్యవసర నిధిగా లెక్కించిన మొత్తం: ' + rs(D.liquid) + '; నెల సగటు ఖర్చు ' + rs(D.monthly_spend) + '.'));
      s.appendChild(c);
    }
    note(s);
  }

  // ---------------------------------------------------------------- 🎯 goals

  function renderGoals() {
    const s = clear($('s-goals'));
    add(s, btn('➕ కొత్త లక్ష్యం', 'main', () => goalForm(null)));
    const raw = D.goals_raw || [];
    const gs = D.goals || [];
    if (!gs.length) add(s, el('div', 'card empty', 'ఇల్లు, బండి, పిల్లల చదువు, పెళ్ళి, రిటైర్మెంట్… ఒక్కో లక్ష్యానికి ఎంత కావాలి, ఎప్పటికి అని రాయండి: నెలకి ఎంత పెట్టాలో, ఏ రకాలు సరిపోతాయో Jarvis చెబుతుంది.'));
    gs.forEach((g) => {
      const c = el('div', 'card');
      add(c, add(el('div', 'row'), el('b', 'grow', kindName(GOAL_KINDS, g.kind).split(' ')[0] + ' ' + g.name), el('span', 'pill', ym(g.by))));
      const p = el('div', 'prog'), i = el('i');
      i.style.width = Math.max(1, g.pct) + '%';
      add(c, add(p, i));
      add(c, el('div', 'small muted', rs(g.have) + ' ఉంది / సుమారు ' + rs(g.future) + ' కావాలి (' + g.pct + '%)'));
      add(c, el('div', '', 'నెలకి సుమారు ' + rs(g.monthly) + ' (' + g.years + ' ఏళ్ళు; ఏడాదికి ' + g.assumed + '% అంచనా, హామీ కాదు)'));
      if (g.fit) add(c, el('div', 'small', 'ఇంత కాలానికి సాధారణంగా సరిపోయే రకాలు: ' + g.fit));
      const r = raw.find((x) => x.id === g.id);
      add(c, btn('మార్చు', 'quiet', () => goalForm(r || g)), btn('తీసేయి', 'quiet', () => confirmSheet('"' + g.name + '" లక్ష్యం తీసేయనా?', () => post({ t: 'goal_rm', id: g.id }))));
      s.appendChild(c);
    });
    note(s);
  }

  function goalForm(g) {
    const f = openSheet(g ? '🎯 లక్ష్యం మార్చు' : '🎯 కొత్త లక్ష్యం');
    const name = textIn(g ? g.name : '', 'ఉదా: కొత్త బండి');
    const kind = selectIn(GOAL_KINDS, g ? g.kind : 'other');
    const target = numIn(g ? g.target : '', 'ఈరోజు ధరలో ₹');
    const by = el('input');
    by.type = 'month';
    by.value = g ? g.by || '' : '';
    const saved = numIn(g ? g.saved || '' : '', 'ఇప్పటికే పక్కన పెట్టింది ₹ (ఐచ్ఛికం)');
    add(f, el('label', 'f', 'పేరు'), name, el('label', 'f', 'రకం'), kind, el('label', 'f', 'ఈరోజు ధరలో ఎంత కావాలి ₹'), target,
      el('label', 'f', 'ఎప్పటికి (నెల)'), by, el('label', 'f', 'ఇప్పటికే ఉన్నది ₹'), saved,
      el('div', 'tiny', 'ఈ లక్ష్యానికి పెట్టిన FD / ఫండ్ లు 📈 పెట్టుబడులు లో "ఏ లక్ష్యానికి" అని ఎంచుకుంటే అవి కూడా లెక్కలో కలుస్తాయి.'));
    add(f, btn('దాచు', 'main', () => {
      if (!name.value.trim() || !(parseFloat(target.value) > 0) || !by.value) { toast('పేరు, ఎంత, ఎప్పటికి: మూడూ రాయండి'); return; }
      post({ t: 'goal', v: { id: g ? g.id : '', name: name.value, kind: kind.value, target: parseFloat(target.value), by: by.value, saved: parseFloat(saved.value) || 0 } });
      closeSheet();
      toast('✅ దాచాను');
    }));
  }

  // ---------------------------------------------------------------- 📈 investments, net worth, the guide

  function renderInvest() {
    const s = clear($('s-invest'));
    const n = D.net || {};
    const hero = el('div', 'hero');
    add(hero, el('div', 'lab', 'మీ నికర ఆస్తి (అంచనా)'), el('div', 'big' + (n.total < 0 ? ' low' : ''), rs(n.total)),
      el('div', 'small muted', 'బ్యాంక్ ' + rs(n.bank) + ' + పెట్టుబడులు ' + rs(n.invested) + ' + ఇతరులు ఇవ్వాల్సింది ' + rs(n.lent) + ' − అప్పులు ' + rs(n.owe)));
    s.appendChild(hero);
    if ((n.by_kind || []).length) {
      const c = el('div', 'card');
      n.by_kind.forEach((x) => {
        const b = el('div', 'bar');
        add(b, add(el('div', 'row'), el('span', 'grow', x.te), el('span', 'tiny', x.pct + '%'), el('span', 'amt', rs(x.amount))));
        const tr = el('div', 'track'), f = el('i');
        f.style.width = Math.max(2, x.pct) + '%';
        add(b, add(tr, f));
        c.appendChild(b);
      });
      s.appendChild(c);
    }
    add(s, btn('➕ పెట్టుబడి రాయి', 'main', () => holdingForm(null)), btn('🔄 ఫండ్ / షేర్ ధరలు తెచ్చు', '', () => { post({ t: 'prices' }); toast('🔄 తెస్తున్నాను…'); }));
    if (D.prices_at > 0) add(s, el('div', 'tiny', 'ధరలు చివరిగా: ' + when(D.prices_at) + ' (ఫండ్ NAV: AMFI; షేర్: పబ్లిక్ ధర, ఆలస్యం ఉండొచ్చు). మీ ఫండ్ / షేర్ కోడ్ మాత్రమే వెళ్తుంది.'));
    add(s, el('h2', '', 'మీ పెట్టుబడులు'));
    const raw = D.holdings_raw || [];
    const hs = D.holdings || [];
    if (!hs.length) add(s, el('div', 'card empty', 'FD, RD, PPF, PF, ఫండ్, షేర్, బంగారం, చిట్టీ… మీవి రాయండి. ఫండ్ అయితే పేరుతో వెతికి ఎంచుకోండి: రోజూ ఇప్పటి విలువ చూపిస్తాను.'));
    hs.forEach((h) => {
      const c = el('div', 'card');
      add(c, add(el('div', 'row'), el('b', 'grow', h.kind_te.split(' ')[0] + ' ' + (h.name || h.kind_te)), el('span', 'amt', rs(h.now))));
      const bits = ['పెట్టింది ' + rs(h.invested)];
      if (h.invested > 0 && h.now !== h.invested) bits.push((h.gain >= 0 ? 'లాభం ' : 'నష్టం ') + rs(Math.abs(h.gain)) + (h.gain_pct !== undefined ? ' (' + h.gain_pct + '%)' : ''));
      if (h.sip > 0) bits.push('SIP ' + rs(h.sip) + '/నెల' + (h.sip_day ? ' (' + h.sip_day + ' న)' : ''));
      if (h.rate > 0) bits.push(h.rate + '%');
      if (h.days_to_maturity !== undefined && h.days_to_maturity >= 0) bits.push('మెచ్యూరిటీ ' + h.days_to_maturity + ' రోజుల్లో');
      if (h.emergency) bits.push('🧯 అత్యవసర నిధి');
      add(c, el('div', 'small muted', bits.join(' · ')));
      if (h.price > 0 && h.price_at > 0) add(c, el('div', 'tiny', 'ధర ' + h.price + ' (' + when(h.price_at) + ')'));
      const r = raw.find((x) => x.id === h.id);
      add(c, btn('మార్చు', 'quiet', () => holdingForm(r || h)), btn('తీసేయి', 'quiet', () => confirmSheet('"' + (h.name || h.kind_te) + '" తీసేయనా?', () => post({ t: 'holding_rm', id: h.id }))));
      s.appendChild(c);
    });
    add(s, el('h2', '', '📚 ఏ రకం ఎలాంటిది'));
    add(s, el('div', 'small muted', 'రకాలు మాత్రమే (ఏ ఫండ్ / కంపెనీ అనేది మీ నిర్ణయం). రిస్క్: ●○○○○ తక్కువ, ●●●●● ఎక్కువ.'));
    (D.guide || []).forEach((g) => {
      const d = el('details');
      const sm = el('summary');
      add(sm, el('span', '', g.name + '  '), el('span', 'dots', '●'.repeat(g.risk) + '○'.repeat(5 - g.risk)));
      d.appendChild(sm);
      const dl = el('dl');
      const item = (k, v) => { if (v) add(dl, el('dt', '', k), el('dd', '', v)); };
      item('ఏమిటి', g.what);
      if (g.rate) item('ఇప్పటి రేటు', g.rate + '% (' + (D.rates || {}).date + ')');
      item('ఎప్పుడు తీయొచ్చు', g.out);
      item('పన్ను', g.tax);
      item('దేనికి బాగుంటుంది', g.for);
      item('జాగ్రత్త', g.careful);
      add(d, add(el('div'), dl));
      s.appendChild(d);
    });
    const rt = D.rates || {};
    add(s, el('h2', '', 'ప్రభుత్వ పథకాల రేట్లు'));
    const c = el('div', 'card');
    const t = el('table', 'rates');
    (rt.list || []).forEach((r) => add(t, add(el('tr'), el('td', '', r.name), el('td', '', r.rate + '%'))));
    add(c, t, el('div', 'tiny', rt.date + ' న చూసినవి. మూలం: ' + rt.source + '. ప్రభుత్వం ప్రతి 3 నెలలకి మార్చొచ్చు; బ్యాంక్ FD రేట్లు బ్యాంక్ బట్టి మారతాయి.'));
    s.appendChild(c);
    note(s);
  }

  const SHOW = {
    code: ['mf', 'stock'], units: ['mf', 'stock'], price: ['mf', 'stock'], value: ['savings', 'epf', 'nps', 'gold', 'lic', 'chit', 'land', 'other', 'ppf', 'ssy', 'rd'],
    rate: ['fd', 'rd', 'post', 'ppf', 'ssy', 'epf'], start: ['fd', 'rd', 'post', 'ppf', 'ssy', 'lic', 'chit'], maturity: ['fd', 'rd', 'post', 'ppf', 'ssy', 'lic', 'chit'],
    sip: ['mf', 'rd', 'nps', 'ppf', 'ssy', 'chit', 'lic', 'stock', 'gold', 'other'], emergency: ['savings', 'fd', 'rd', 'mf', 'other'],
  };

  function holdingForm(h) {
    const f = openSheet(h ? '📈 పెట్టుబడి మార్చు' : '📈 పెట్టుబడి రాయి');
    const kind = selectIn(H_KINDS, h ? h.kind : 'fd');
    const name = textIn(h ? h.name : '', 'ఉదా: SBI FD / నా PPF');
    const code = textIn(h ? h.code || '' : '', '');
    const codeLab = el('label', 'f', '');
    const search = el('div');
    const q = textIn('', 'ఫండ్ పేరులో మాటలు (ఉదా: nifty 50 index direct)');
    const found = el('div');
    add(search, el('label', 'f', '🔎 ఫండ్ వెతుకు (AMFI లిస్ట్)'), q, btn('వెతుకు', '', () => { post({ t: 'fund_search', q: q.value }); clear(found).appendChild(el('div', 'tiny', 'వెతుకుతున్నాను…')); }), found);
    const units = numIn(h ? h.units || '' : '', '');
    const unitsLab = el('label', 'f', '');
    const price = numIn(h ? h.price || '' : '', 'తెలియకపోతే ఖాళీ');
    const invested = numIn(h ? h.invested || '' : '', '₹');
    const value = numIn(h ? h.value || '' : '', 'తెలిస్తే (ఐచ్ఛికం)');
    const rate = numIn(h ? h.rate || '' : '', 'ఉదా: 7.1');
    const start = el('input'); start.type = 'date'; start.value = h ? h.start || '' : '';
    const maturity = el('input'); maturity.type = 'date'; maturity.value = h ? h.maturity || '' : '';
    const sip = numIn(h ? h.sip || '' : '', '₹ (లేకపోతే ఖాళీ)');
    const sipDay = numIn(h ? h.sip_day || '' : '', '1–31');
    const goals = [['', '— ఏదీ కాదు —']].concat((D.goals_raw || []).map((g) => [g.id, g.name]));
    const goal = selectIn(goals, h ? h.goal || '' : '');
    const emerg = el('input'); emerg.type = 'checkbox'; emerg.checked = !!(h && h.emergency);
    const parts = {
      code: [codeLab, code, search], units: [unitsLab, units], price: [el('label', 'f', 'ఒక్కోదాని ధర (ఫండ్ NAV / షేర్ ధర)'), price],
      value: [el('label', 'f', 'ఇప్పటి విలువ ₹'), value], rate: [el('label', 'f', 'వడ్డీ % (ఏడాదికి)'), rate], start: [el('label', 'f', 'మొదలు పెట్టిన తేదీ'), start],
      maturity: [el('label', 'f', 'మెచ్యూరిటీ తేదీ'), maturity], sip: [el('label', 'f', 'నెలకి కట్టేది (SIP / RD / వాయిదా) ₹'), sip, el('label', 'f', 'ఏ తేదీన'), sipDay],
      emergency: [add(el('label', 'ck'), emerg, el('span', '', '🧯 ఇది అత్యవసర నిధి (ఎప్పుడైనా తీయగలిగేది)'))],
    };
    add(f, el('label', 'f', 'రకం'), kind, el('label', 'f', 'పేరు'), name);
    Object.keys(parts).forEach((k) => parts[k].forEach((n) => f.appendChild(n)));
    add(f, el('label', 'f', 'మొత్తం పెట్టింది ₹'), invested, el('label', 'f', 'ఏ లక్ష్యానికి'), goal);
    function relabel() {
      const k = kind.value;
      Object.keys(parts).forEach((p) => parts[p].forEach((n) => { n.style.display = SHOW[p].includes(k) ? '' : 'none'; }));
      search.style.display = k === 'mf' ? '' : 'none';
      codeLab.textContent = k === 'mf' ? 'ఫండ్ స్కీమ్ కోడ్ (కింద వెతికి ఎంచుకోండి)' : 'NSE కోడ్ (ఉదా: TCS; BSE కి TCS.BO)';
      unitsLab.textContent = k === 'mf' ? 'యూనిట్లు (స్టేట్‌మెంట్‌లో)' : 'షేర్లు ఎన్ని';
    }
    kind.addEventListener('change', relabel);
    relabel();
    fundPick = (list) => {
      clear(found);
      if (!list.length) { found.appendChild(el('div', 'tiny', 'దొరకలేదు: వేరే మాటలతో వెతకండి.')); return; }
      list.forEach((x) => {
        const b = btn(x.name + '  (NAV ' + x.nav + ', ' + x.date + ')', 'quiet', () => {
          code.value = x.code; name.value = x.name; price.value = x.nav; clear(found).appendChild(el('div', 'tiny', '✅ ఎంచుకున్నారు: ' + x.code));
        });
        b.style.textAlign = 'left';
        b.style.display = 'block';
        found.appendChild(b);
      });
    };
    add(f, btn('దాచు', 'main', () => {
      const k = kind.value;
      const v = {
        id: h ? h.id : '', kind: k, name: name.value, invested: parseFloat(invested.value) || 0, goal: goal.value,
        code: SHOW.code.includes(k) ? code.value.trim() : '', units: SHOW.units.includes(k) ? parseFloat(units.value) || 0 : 0,
        value: SHOW.value.includes(k) ? parseFloat(value.value) || 0 : 0, rate: SHOW.rate.includes(k) ? parseFloat(rate.value) || 0 : 0,
        start: SHOW.start.includes(k) ? start.value : '', maturity: SHOW.maturity.includes(k) ? maturity.value : '',
        sip: SHOW.sip.includes(k) ? parseFloat(sip.value) || 0 : 0, sip_day: SHOW.sip.includes(k) ? parseInt(sipDay.value, 10) || 0 : 0,
        emergency: SHOW.emergency.includes(k) && emerg.checked,
      };
      if (SHOW.price.includes(k) && parseFloat(price.value) > 0) v.price = parseFloat(price.value);
      if (!v.name.trim() && !v.code) { toast('పేరు రాయండి'); return; }
      if (!(v.invested > 0) && !(v.units > 0) && !(v.value > 0) && !(v.sip > 0)) { toast('ఎంత పెట్టారో రాయండి'); return; }
      post({ t: 'holding', v });
      closeSheet();
      toast('✅ దాచాను');
    }));
  }

  // ---------------------------------------------------------------- 💳 loans and the card

  function renderLoans() {
    const s = clear($('s-loans'));
    const p = D.profile || {};
    add(s, el('h2', '', '💳 క్రెడిట్ కార్డ్'));
    const c = el('div', 'card' + (p.card_due > 0 ? ' bad' : ''));
    const due = numIn(p.card_due || '', '₹ (లేకపోతే 0)'), rate = numIn(p.card_rate || 3.5, '');
    add(c, el('label', 'f', 'ఈ నెల కట్టకుండా మిగిలిన కార్డ్ బాకీ ₹'), due, el('label', 'f', 'కార్డ్ వడ్డీ నెలకి %'), rate,
      btn('దాచు', 'main', () => { post({ t: 'profile', v: { card_due: parseFloat(due.value) || 0, card_rate: parseFloat(rate.value) || 3.5 } }); toast('✅ దాచాను'); }),
      el('div', 'tiny', 'కార్డ్ బిల్లు ప్రతి నెల మొత్తం కట్టేస్తే వడ్డీ ఉండదు; మినిమం మాత్రమే కడితే ఏడాదికి 40% పైన వడ్డీ.'));
    s.appendChild(c);
    add(s, el('h2', '', '🏦 లోన్‌లు'));
    add(s, btn('➕ లోన్ రాయి', 'main', loanForm));
    const loans = D.loans || [];
    if (!loans.length) add(s, el('div', 'card empty', 'లోన్‌లు లేవు. (🏦 అప్పులు లో Jarvis కి చెప్పిన EMI లు, తీసుకున్న అప్పులు ఇక్కడ కనిపిస్తాయి.)'));
    loans.forEach((l) => {
      const k = el('div', 'card' + (l.rate >= 14 ? ' bad' : ''));
      add(k, add(el('div', 'row'), el('b', 'grow', (l.kind === 'emi' ? '🏦 ' : '🤝 ') + l.name), el('span', 'amt', rs(l.outstanding))));
      const bits = [];
      if (l.kind === 'emi') bits.push('EMI ' + rs(l.emi) + ', ఇంకా ' + l.months_left + ' నెలలు');
      bits.push('మిగిలింది సుమారు ' + rs(l.outstanding) + (l.rate > 0 ? '' : ' (వడ్డీ % రాస్తే సరిగ్గా లెక్కిస్తాను)'));
      add(k, el('div', 'small muted', bits.join('; ')));
      const r = numIn(l.rate || '', 'వడ్డీ % (ఏడాదికి)');
      add(k, el('label', 'f', 'వడ్డీ % (ఏడాదికి)'), r, btn('దాచు', '', () => { post({ t: 'loan_rate', id: l.id, rate: parseFloat(r.value) || 0 }); toast('✅ దాచాను'); }));
      if (l.kind === 'emi' && l.rate > 0 && l.months_left > 0) {
        const x1 = numIn('', 'ఒకేసారి ₹'), x2 = numIn('', 'నెలకి ₹');
        const res = el('div', 'result');
        const id = 'pp' + l.id;
        add(k, el('label', 'f', '⏩ ముందే కడితే: ఇప్పుడు ఒకేసారి'), x1, el('label', 'f', 'లేదా ప్రతి నెల అదనంగా'), x2,
          btn('లెక్కించు', '', () => { results[id] = res; post({ t: 'calc', id, a: { kind: 'prepay', outstanding: l.outstanding, rate: l.rate, emi: l.emi, extra_now: parseFloat(x1.value) || 0, extra_monthly: parseFloat(x2.value) || 0 } }); }), res);
      }
      s.appendChild(k);
    });
    add(s, el('div', 'tiny', 'ఏ లోన్ ముందు? ఎక్కువ వడ్డీది ముందు (కార్డ్ → పర్సనల్ లోన్ → బండి → ఇల్లు). హోమ్ లోన్‌కి పన్ను మినహాయింపు ఉంటే ఆ లెక్క కూడా చూడండి.'));
    note(s);
  }

  function loanForm() {
    const f = openSheet('🏦 లోన్ రాయి');
    const name = textIn('', 'ఉదా: బండి లోన్'), emi = numIn('', '₹'), months = numIn('', 'ఉదా: 36'), done = numIn('', 'ఉదా: 10'), dayN = numIn('', '1–31'), rate = numIn('', 'ఉదా: 10.5');
    add(f, el('label', 'f', 'పేరు'), name, el('label', 'f', 'EMI ₹'), emi, add(el('div', 'two'), add(el('div'), el('label', 'f', 'మొత్తం నెలలు'), months), add(el('div'), el('label', 'f', 'ఇప్పటికే కట్టినవి'), done)),
      add(el('div', 'two'), add(el('div'), el('label', 'f', 'EMI తేదీ'), dayN), add(el('div'), el('label', 'f', 'వడ్డీ %'), rate)),
      el('div', 'tiny', 'ఇది 🏦 అప్పులు లిస్ట్‌లో కూడా ఉంటుంది (Jarvis EMI రోజు గుర్తు చేస్తుంది). Jarvis ఏదీ కట్టదు.'));
    add(f, btn('దాచు', 'main', () => {
      post({ t: 'loan_add', v: { name: name.value, emi: parseFloat(emi.value) || 0, months: parseInt(months.value, 10) || 0, done: parseInt(done.value, 10) || 0, day: parseInt(dayN.value, 10) || 0, rate: parseFloat(rate.value) || 0 } });
      closeSheet();
    }));
  }

  // ---------------------------------------------------------------- 🧮 the sums

  const results = {};
  const CALCS = [
    ['sip', '📈 SIP (నెల నెలా పెట్టుబడి)', [['monthly', 'నెలకి ₹', 5000], ['rate', 'ఏడాదికి రాబడి % (అంచనా)', 10], ['years', 'ఎన్ని ఏళ్ళు', 10]]],
    ['lumpsum', '💵 ఒకేసారి పెట్టుబడి', [['amount', '₹', 100000], ['rate', 'ఏడాదికి %', 7], ['years', 'ఏళ్ళు', 5]]],
    ['fd', '🏦 FD', [['amount', '₹', 100000], ['rate', 'వడ్డీ %', 7], ['years', 'ఏళ్ళు', 1]]],
    ['rd', '🏦 RD', [['monthly', 'నెలకి ₹', 2000], ['rate', 'వడ్డీ %', 6.7], ['months', 'నెలలు', 60]]],
    ['ppf', '🏛️ PPF', [['yearly', 'ఏడాదికి ₹ (1.5 లక్షల వరకు)', 150000], ['years', 'ఏళ్ళు (కనీసం 15)', 15]]],
    ['emi', '🧾 లోన్ EMI', [['principal', 'లోన్ ₹', 500000], ['rate', 'వడ్డీ %', 10], ['months', 'నెలలు', 60]]],
    ['prepay', '⏩ లోన్ ముందే కడితే', [['outstanding', 'మిగిలిన లోన్ ₹', 300000], ['rate', 'వడ్డీ %', 9], ['emi', 'EMI ₹', 10000], ['extra_now', 'ఇప్పుడు ఒకేసారి ₹', 50000], ['extra_monthly', 'నెలకి అదనంగా ₹', 0]]],
    ['inflation', '📉 ధరలు పెరిగితే', [['amount', 'ఈరోజు ₹', 100000], ['years', 'ఏళ్ళు', 10], ['rate', 'ధరలు ఏడాదికి %', 6]]],
    ['retire', '🧓 రిటైర్మెంట్‌కి ఎంత', [['age', 'ఇప్పటి వయసు', 35], ['retire_age', 'రిటైర్ అయ్యే వయసు', 60], ['monthly_expense', 'ఇప్పటి నెల ఖర్చు ₹', 30000], ['have', 'ఇప్పటికే ఉన్నది ₹', 0], ['rate', 'ఏడాదికి రాబడి % (అంచనా)', 10]]],
    ['goal', '🎯 ఒక లక్ష్యానికి నెలకి ఎంత', [['target', 'ఈరోజు ధర ₹', 500000], ['years', 'ఎన్ని ఏళ్ళలో', 5], ['have', 'ఇప్పటికే ఉన్నది ₹', 0]]],
    ['card', '💳 కార్డ్ బాకీ వడ్డీ', [['balance', 'బాకీ ₹', 20000], ['monthly_rate', 'నెలకి వడ్డీ %', 3.5], ['months', 'నెలలు', 12]]],
    ['double', '✖️2 డబ్బు రెట్టింపు ఎప్పుడు', [['rate', 'ఏడాదికి %', 7]]],
  ];

  function buildCalc() {
    const s = clear($('s-calc'));
    add(s, el('div', 'small muted', 'లెక్కలు ఫోన్‌లోనే (AI కాదు, ఖర్చు లేదు). రాబడి % లు అంచనాలు: షేర్ ఫండ్‌లలో ఏటా మారుతుంది, హామీ కాదు.'));
    const pick = selectIn(CALCS.map((c) => [c[0], c[1]]), 'sip');
    const box = el('div');
    add(s, el('label', 'f', 'ఏ లెక్క'), pick, box);
    function draw() {
      clear(box);
      const c = CALCS.find((x) => x[0] === pick.value);
      const ins = {};
      const p = (D && D.profile) || {};
      const card = el('div', 'card');
      c[2].forEach(([k, lab, def]) => {
        let v = def;
        if (c[0] === 'retire' && k === 'age' && p.age > 0) v = p.age;
        if (c[0] === 'retire' && k === 'monthly_expense' && D && D.monthly_spend > 0) v = D.monthly_spend;
        if (c[0] === 'card' && k === 'monthly_rate' && p.card_rate > 0) v = p.card_rate;
        if (c[0] === 'card' && k === 'balance' && p.card_due > 0) v = p.card_due;
        ins[k] = numIn(v, '');
        add(card, el('label', 'f', lab), ins[k]);
      });
      const res = el('div', 'result');
      add(card, btn('లెక్కించు', 'main', () => {
        const a = { kind: c[0] };
        Object.keys(ins).forEach((k) => { const v = parseFloat(ins[k].value); if (!isNaN(v)) a[k] = v; });
        results['c'] = res;
        post({ t: 'calc', id: 'c', a });
      }), res);
      box.appendChild(card);
    }
    pick.addEventListener('change', draw);
    draw();
    note(s);
  }

  function buildTax() {
    const s = clear($('s-tax'));
    const p = (D && D.profile) || {};
    add(s, el('div', 'small muted', 'టాక్స్ ఇయర్ 2026-27: కొత్త విధానం vs పాత విధానం (మీరు ఇచ్చే మినహాయింపులతో). కొత్త చట్టంలో సెక్షన్ నంబర్లు మారాయి; పేర్లు పాత పేర్లతోనే చూపిస్తున్నాను.'));
    const card = el('div', 'card');
    const inc = numIn(p.income > 0 ? p.income * 12 : '', 'జీతం + ఇతర ఆదాయం (పన్ను ముందు)');
    const sal = el('input'); sal.type = 'checkbox'; sal.checked = true;
    const f = {};
    [['d80c', '80C (PF, PPF, ELSS, LIC, ట్యూషన్ ఫీ, హోమ్ లోన్ అసలు) ₹'], ['d80d', 'హెల్త్ ఇన్సూరెన్స్ ప్రీమియం (80D) ₹'], ['nps', 'మీ NPS అదనం (80CCD(1B)) ₹'],
      ['home_interest', 'హోమ్ లోన్ వడ్డీ (ఉంటున్న ఇల్లు) ₹'], ['hra', 'HRA మినహాయింపు ₹'], ['other', 'ఇతర మినహాయింపులు ₹']].forEach(([k, lab]) => { f[k] = numIn('', '0'); add(card, el('label', 'f', lab), f[k]); });
    const age = numIn(p.age || '', 'వయసు');
    card.insertBefore(add(el('label', 'ck'), sal, el('span', '', 'జీతగాడిని (స్టాండర్డ్ డిడక్షన్)')), card.firstChild);
    card.insertBefore(inc, card.firstChild);
    card.insertBefore(el('label', 'f', 'ఏడాది ఆదాయం ₹'), card.firstChild);
    add(card, el('label', 'f', 'వయసు'), age);
    const res = el('div', 'result');
    add(card, btn('పోల్చు', 'main', () => {
      const a = { kind: 'tax', salaried: sal.checked };
      const v = parseFloat(inc.value);
      if (!(v > 0)) { toast('ఏడాది ఆదాయం రాయండి'); return; }
      a.income = v;
      Object.keys(f).forEach((k) => { a[k] = parseFloat(f[k].value) || 0; });
      a.age = parseInt(age.value, 10) || 0;
      results['tax'] = res;
      post({ t: 'calc', id: 'tax', a });
    }), res);
    s.appendChild(card);
    add(s, el('div', 'tiny', 'ITR గడువు (జీతగాళ్ళు): జూలై 31. ఇది అంచనా: ఫారం 16 / CA తో సరిచూసుకోండి.'));
    note(s);
  }

  function buildBuy() {
    const s = clear($('s-buy'));
    add(s, el('div', 'small muted', 'ఏదైనా కొనాలనుకుంటే: ఈ నెల ప్లాన్, అత్యవసర నిధి, కార్డ్ బాకీ చూసి Jarvis చెబుతుంది. ఫోన్‌లోనే లెక్క.'));
    const card = el('div', 'card');
    const what = textIn('', 'ఉదా: కొత్త ఫోన్'), price = numIn('', '₹');
    const res = el('div', 'result');
    add(card, el('label', 'f', 'ఏమిటి'), what, el('label', 'f', 'ధర ₹'), price, btn('కొనొచ్చా?', 'main', () => {
      const v = parseFloat(price.value);
      if (!(v > 0)) { toast('ధర రాయండి'); return; }
      results['buy'] = res;
      post({ t: 'calc', id: 'buy', a: { kind: 'canbuy', price: v, what: what.value } });
    }), res);
    s.appendChild(card);
    note(s);
  }

  function buildScheme() {
    const s = clear($('s-scheme'));
    add(s, el('div', 'small muted', 'ఎవరైనా "డబ్బు రెట్టింపు", "నెలకి 10%", "గ్రూప్‌లో టిప్స్", "ఈ యాప్ డౌన్‌లోడ్ చేయండి" అని పంపితే: ఆ మెసేజ్ ఇక్కడ పేస్ట్ చేయండి. ఫోన్‌లోనే చూస్తాను, ఎవరికీ పంపను.'));
    const card = el('div', 'card');
    const txt = el('textarea');
    txt.placeholder = 'మెసేజ్ / ఆఫర్ ఇక్కడ పేస్ట్ చేయండి';
    const res = el('div', 'result');
    add(card, txt, btn('చెక్ చేయి', 'main', () => {
      if (txt.value.trim().length < 10) { toast('మెసేజ్ పేస్ట్ చేయండి'); return; }
      results['scheme'] = res;
      post({ t: 'calc', id: 'scheme', a: { kind: 'scheme', text: txt.value.slice(0, 5000) } });
    }), res);
    s.appendChild(card);
    add(s, el('h2', '', 'సరిచూసుకోవడానికి (మీ ట్యాప్‌తో మాత్రమే తెరుస్తాను)'));
    add(s, btn('🌐 SEBI: రిజిస్టర్ అయిన సలహాదారులు', '', () => post({ t: 'site', id: 'sebi' })), btn('🌐 RBI సచేత్', '', () => post({ t: 'site', id: 'sachet' })),
      btn('🌐 cybercrime.gov.in', '', () => post({ t: 'site', id: 'cyber' })), btn('📞 1930 (మోసం జరిగితే)', '', () => post({ t: 'dial1930' })));
    add(s, el('div', 'tiny', '1930 డయలర్‌లో మాత్రమే తెరుస్తాను; కాల్ మీరే నొక్కాలి. మోసం జరిగిన వెంటనే (గంటల్లో) చెబితే డబ్బు ఆపే అవకాశం ఎక్కువ.'));
    note(s);
  }

  function showResult(id, r) {
    const res = results[id];
    if (!res) return;
    clear(res);
    res.style.display = 'block';
    if (r.verdict && r.head) {
      res.className = 'result';
      add(res, el('div', 'verdict ' + (r.verdict === 'ok' || r.verdict === 'none' ? 'in' : r.verdict === 'careful' || r.verdict === 'warn' ? 'warnc' : 'badc'), r.head));
      const ul = el('ul', 'plain');
      (r.why || r.flags || []).forEach((x) => ul.appendChild(el('li', '', x)));
      if (ul.childNodes.length) res.appendChild(ul);
      if (r.how) { add(res, el('div', 'small muted', 'ఎలా సరిచూసుకోవాలి:')); const h = el('ul', 'plain small'); r.how.forEach((x) => h.appendChild(el('li', '', x))); res.appendChild(h); }
      return;
    }
    res.textContent = r.text || '';
  }

  // ---------------------------------------------------------------- 🤖 the AI's review

  function renderAi() {
    const s = clear($('s-ai'));
    const p = D.profile || {};
    const c = el('div', 'card');
    add(c, el('div', '', 'మీ AI ఈ నెల లెక్కలు చూసి: ఎలా గడిచింది, ఏ 3 వృథాలు ఎంత తగ్గించొచ్చు, రక్షణలో ముందు ఏది, లక్ష్యాలకి ఏ రకాలు సరిపోతాయి, ఈ వారం ఒక చిన్న పని.'),
      el('div', 'small muted', 'వెళ్ళేవి మొత్తాలు మాత్రమే (రకాల వారీగా ఖర్చు, లక్ష్యాలు, రక్షణ, నికర ఆస్తి). SMS మాటలు, ఖాతా నంబర్లు, మీరు డబ్బు పంపిన వ్యక్తుల పేర్లు వెళ్ళవు. మీ AI కి ఒకసారి అడుగుతుంది: కొంచెం ఖర్చు అవుతుంది.'));
    const b = btn(aiBusy ? '🤖 అడుగుతున్నాను…' : '🤖 ఈ నెల సమీక్ష అడుగు', 'main', () => { if (aiBusy) return; aiBusy = true; post({ t: 'ai' }); renderAi(); });
    b.disabled = aiBusy;
    add(c, b);
    s.appendChild(c);
    const sw = el('div', 'card');
    const ck = el('input'); ck.type = 'checkbox'; ck.checked = !!p.ai_monthly;
    ck.addEventListener('change', () => { post({ t: 'profile', v: { ai_monthly: ck.checked } }); toast(ck.checked ? 'ప్రతి నెల 1న అడుగుతాను' : 'ఆపేశాను'); });
    add(sw, add(el('label', 'ck'), ck, el('span', '', 'ప్రతి నెల 1న నేనే అడగాలి')), el('div', 'tiny', 'మీరు ఆన్ చేస్తేనే (ప్రతి నెల ఒకసారి ఖర్చు). జవాబు నోటిఫికేషన్‌లో, ఇక్కడ కూడా.'));
    s.appendChild(sw);
    const rv = D.reviews || [];
    add(s, el('h2', '', 'ఇంతకుముందు సమీక్షలు'));
    if (!rv.length) add(s, el('div', 'card empty', 'ఇంకా లేవు.'));
    rv.forEach((r) => {
      const k = el('div', 'card');
      add(k, el('div', 'tiny', day(r.t)), el('div', '', r.text));
      k.lastChild.style.whiteSpace = 'pre-wrap';
      s.appendChild(k);
    });
    note(s);
  }

  // ---------------------------------------------------------------- ⚙️ his details

  function renderMe() {
    const s = clear($('s-me'));
    if (!D) return;
    const p = D.profile || {};
    const v = {};
    const card = el('div', 'card');
    v.income = numIn(p.income || '', ''); v.save_goal = numIn(p.save_goal || '', ''); v.budget = numIn(p.budget || '', '');
    v.salary_day = numIn(p.salary_day || '', '1–31'); v.age = numIn(p.age || '', ''); v.dependents = numIn(p.dependents || '', '0');
    v.ef_months = numIn(p.ef_months || 6, '');
    const two = (a, b) => add(el('div', 'two'), a, b);
    const cell = (lab, inp) => add(el('div'), el('label', 'f', lab), inp);
    add(card, el('label', 'f', 'నెల జీతం (చేతికి వచ్చేది) ₹'), v.income, el('label', 'f', 'నెలకి పొదుపు లక్ష్యం ₹'), v.save_goal,
      el('label', 'f', 'నెల ఖర్చు బడ్జెట్ ₹ (ఐచ్ఛికం; లేకపోతే జీతం − పొదుపు)'), v.budget,
      two(cell('జీతం తేదీ', v.salary_day), cell('వయసు', v.age)), two(cell('మీ మీద ఆధారపడేవాళ్ళు', v.dependents), cell('అత్యవసర నిధి (నెలలు)', v.ef_months)));
    add(s, el('h2', '', 'జీతం, పొదుపు'), card);
    const ins = el('div', 'card');
    v.term = numIn(p.term || '', '₹ (లేకపోతే 0)'); v.health = numIn(p.health || '', '₹ (లేకపోతే 0)'); v.low_bal = numIn(p.low_bal || '', '₹ (ఐచ్ఛికం)');
    const fam = el('input'); fam.type = 'checkbox'; fam.checked = !!p.health_family;
    add(ins, el('label', 'f', 'టర్మ్ ఇన్సూరెన్స్ కవర్ ₹'), v.term, el('label', 'f', 'హెల్త్ ఇన్సూరెన్స్ కవర్ ₹ (ఆఫీసుది కాకుండా మీది)'), v.health,
      add(el('label', 'ck'), fam, el('span', '', 'హెల్త్ కవర్ కుటుంబం అందరికీ')), el('label', 'f', 'బ్యాంక్ బ్యాలెన్స్ ఇంత కంటే తగ్గితే చెప్పు ₹'), v.low_bal);
    add(s, el('h2', '', 'రక్షణ'), ins);
    add(s, el('h2', '', 'విలువ పడిపోతే మీకు ఎలా ఉంటుంది?'));
    let risk = p.risk || 'mid';
    const rk = el('div', 'risk');
    [['low', '📉 తక్కువ: పెట్టిన డబ్బు తగ్గితే నిద్ర పట్టదు'], ['mid', '⚖️ మధ్యస్థం: కొంచెం పైకి కిందకి పర్వాలేదు'], ['high', '📈 ఎక్కువ: 7+ ఏళ్ళు ఆగగలను, 30% పడినా అమ్మను']].forEach(([k, lab]) => {
      const b = el('button', k === risk ? 'on' : '', lab);
      b.type = 'button';
      b.addEventListener('click', () => { risk = k; meDirty = true; rk.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); });
      rk.appendChild(b);
    });
    add(s, add(el('div', 'card'), rk, el('div', 'tiny', 'దీన్ని బట్టి లక్ష్యాలకి సరిపోయే రకాలు చూపిస్తాను (రకాలు మాత్రమే, ఏ ఫండ్ / షేర్ కాదు).')));
    const al = el('div', 'card');
    const sw = {};
    [['alert_unusual', '⚠️ పెద్ద / రాత్రి డెబిట్ వస్తే "ఇది మీరేనా?" అని అడుగు'], ['alert_salary', '💰 జీతం రాగానే "ముందు పొదుపు" గుర్తు'], ['alert_subs', '🔁 సబ్‌స్క్రిప్షన్ మళ్ళీ కట్ అయ్యే 2 రోజుల ముందు'],
      ['alert_maturity', '📅 FD మెచ్యూరిటీ, SIP ముందు రోజు'], ['month_report', '📄 ప్రతి నెల 1న నెల రిపోర్ట్ PDF'], ['ai_monthly', '🤖 ప్రతి నెల 1న AI సమీక్ష (ఖర్చు అవుతుంది)']].forEach(([k, lab]) => {
      sw[k] = el('input'); sw[k].type = 'checkbox'; sw[k].checked = !!p[k];
      sw[k].addEventListener('change', () => { meDirty = true; });
      add(al, add(el('label', 'ck'), sw[k], el('span', '', lab)));
    });
    add(al, el('div', 'tiny', 'సాయంత్రం 9 తర్వాత ఒకసారి చూసి చెబుతాను. Jarvis ఏదీ కట్టదు, ఆపదు: చెబుతుంది మాత్రమే.'));
    add(s, el('h2', '', 'గుర్తులు'), al);
    Object.keys(v).forEach((k) => v[k].addEventListener('input', () => { meDirty = true; }));
    add(s, btn('దాచు', 'main', () => {
      const o = { risk, health_family: fam.checked };
      Object.keys(v).forEach((k) => { const n = parseFloat(v[k].value); o[k] = isNaN(n) ? 0 : n; });
      if (!(o.ef_months >= 3)) o.ef_months = 6;
      Object.keys(sw).forEach((k) => { o[k] = sw[k].checked; });
      post({ t: 'profile', v: o });
      meDirty = false;
      toast('✅ దాచాను');
    }));
    add(s, el('div', 'tiny', 'ఇవి మీ ఫోన్‌లోనే (మీ Google Drive బ్యాకప్‌లో కూడా, మీ మిగతా Jarvis సెట్టింగ్స్ లాగే). చాట్‌లో ఎవరికీ చెప్పాల్సిన పని లేదు.'));
    note(s);
  }

  // ---------------------------------------------------------------- small form parts

  function numIn(v, ph) {
    const i = el('input');
    i.type = 'number';
    i.inputMode = 'decimal';
    i.step = 'any';
    if (v !== '' && v !== undefined && v !== null) i.value = v;
    if (ph) i.placeholder = ph;
    return i;
  }
  function textIn(v, ph) { const i = el('input'); i.type = 'text'; i.value = v || ''; if (ph) i.placeholder = ph; i.maxLength = 80; return i; }
  function selectIn(list, cur) {
    const s = el('select');
    list.forEach(([k, lab]) => { const o = el('option', '', lab); o.value = k; if (k === cur) o.selected = true; s.appendChild(o); });
    return s;
  }

  let sheetOpen = false;
  function openSheet(title) {
    const sh = clear($('sheet'));
    add(sh, add(el('div', 'row'), el('h3', 'grow', title), btn('✕', 'quiet', closeSheet)));
    $('veil').style.display = 'block';
    sh.style.display = 'block';
    sh.classList.remove('show');
    void sh.offsetWidth;
    sh.classList.add('show');
    sh.scrollTop = 0;
    sheetOpen = true;
    const body = el('div');
    sh.appendChild(body);
    return body;
  }
  function closeSheet() { $('sheet').style.display = 'none'; $('veil').style.display = 'none'; sheetOpen = false; fundPick = null; }
  function confirmSheet(q, yes) {
    const f = openSheet(q);
    add(f, btn('అవును', 'main', () => { yes(); closeSheet(); }), btn('వద్దు', 'quiet', closeSheet));
  }

  // ---------------------------------------------------------------- from Jarvis

  function onJarvis(m) {
    switch (m.t) {
      case 'data': {
        D = m.d || {};
        if (D.reviews === undefined) D.reviews = [];
        renderAll();
        if (!calcBuilt) { buildCalc(); buildTax(); buildBuy(); buildScheme(); calcBuilt = true; }
        break;
      }
      case 'tab': show(m.tab); break;
      case 'say': toast(m.s, 4200); break;
      case 'calc': showResult(m.id, m.r || {}); break;
      case 'funds': if (fundPick) fundPick(m.list || []); break;
      case 'ai_done': aiBusy = false; if (D) renderAi(); if (m.ok) toast('🤖 సమీక్ష వచ్చింది'); break;
      case 'back': {
        if (sheetOpen) { closeSheet(); post({ t: 'back', used: true }); }
        else if (tab !== 'month') { show('month'); post({ t: 'back', used: true }); }
        else post({ t: 'back', used: false });
        break;
      }
      default: break;
    }
  }
  let calcBuilt = false;

  // ---------------------------------------------------------------- start

  buildTabs();
  show('month');
  $('back').addEventListener('click', () => { if (sheetOpen) closeSheet(); else post({ t: 'close' }); });
  $('reload').addEventListener('click', () => { post({ t: 'refresh' }); toast('🔄 మళ్ళీ లెక్కిస్తున్నాను…'); });
  $('veil').addEventListener('click', closeSheet);
  add($('s-month'), el('div', 'empty', 'లెక్కిస్తున్నాను…'));
})();
