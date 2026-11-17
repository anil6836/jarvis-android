// 📱 The sealed preview of Jarvis's app studio (Anil, 18 Nov). The app the AI writes arrives in parts by postMessage
// ({t:'part', id, css, html, js}); the kit (kit.js) puts each part into the page and runs its code once, in order, and
// says which part an error came from. {t:'start'} shows the app; {t:'test'} has the kit tap through every screen by itself
// and answers {t:'tested'} or the first error. While he tries the preview, an error is sent as {t:'err', state:'run'}
// (🔧 can repair that part). No network, no storage, no way out: this page lives in a sandboxed frame and only talks to
// its parent page.
'use strict';

(function () {
  const kit = window.__appkit;
  const post = (m) => { try { parent.postMessage(m, '*'); } catch (e) { /* (nothing to tell) */ } };
  let phase = 'load';
  const seen = new Set();
  let queue = Promise.resolve();

  function report(id, msg) {
    if (phase !== 'run') return; // (while loading or testing, the answer to that message carries the error)
    const key = (id || '') + '|' + msg;
    if (seen.has(key) || seen.size > 20) return;
    seen.add(key);
    post({ t: 'err', state: 'run', id: id || '', msg: String(msg) });
  }

  function then(fn) { queue = queue.then(fn, fn); }

  window.addEventListener('message', (e) => {
    if (e.source !== parent) return;
    const m = e.data;
    if (!m || typeof m !== 'object' || typeof m.t !== 'string') return;
    switch (m.t) {
      case 'setup':
        then(() => { kit.setup({ name: m.name, color: m.color, theme: m.theme, net: false, error: report }); });
        break;
      case 'part':
        then(async () => {
          const id = kit.cleanId(m.id);
          let r;
          try { r = await kit.addPart({ id, css: m.css, html: m.html, js: m.js }); } catch (x) { r = { err: kit.describe(x) }; }
          post(r && r.ok ? { t: 'ok', id } : { t: 'err', state: 'load', id, msg: String((r && r.err) || 'error') });
        });
        break;
      case 'start':
        then(async () => {
          try { await kit.start(); phase = 'run'; post({ t: 'started' }); }
          catch (x) { post({ t: 'err', state: 'start', id: '', msg: kit.describe(x) }); }
        });
        break;
      case 'test':
        then(async () => {
          phase = 'test';
          const t0 = Date.now();
          let r;
          try { r = await kit.test(typeof m.ms === 'number' ? m.ms : 20000); } catch (x) { r = { err: true, id: null, msg: kit.describe(x) }; }
          phase = 'run';
          post(r && r.ok ? { t: 'tested', screens: r.screens, taps: r.taps, ms: Date.now() - t0 }
            : { t: 'err', state: 'test', id: (r && r.id) || '', msg: String((r && r.msg) || 'error'), screen: (r && r.screen) || '' });
        });
        break;
      default:
    }
  });

  post({ t: 'ready' });
})();
