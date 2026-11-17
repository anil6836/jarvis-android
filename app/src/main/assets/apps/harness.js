// 📱 The app studio's outer page. Jarvis (Java) hands this page one message port; the sealed app preview (an iframe with
// sandbox="allow-scripts" only) talks to this page by postMessage. This page passes messages both ways and nothing else:
// from the app only a few known kinds with short, plain fields get through to Jarvis. {t:'reload'} from Jarvis starts the
// preview afresh (a clean page for a self-test or after a repair).
(function () {
  'use strict';
  var frame = document.getElementById('stage');
  var port = null;          // to Jarvis
  var toJarvis = [];        // (said by the app before Jarvis's port came)
  var stageReady = false;
  var toStage = [];         // (sent by Jarvis before the preview was ready)
  var KINDS = { ready: 1, ok: 1, err: 1, tested: 1, started: 1 };

  function num(v) { return typeof v === 'number' && isFinite(v); }

  function clean(d) {
    if (!d || typeof d !== 'object' || typeof d.t !== 'string' || !KINDS[d.t]) return null;
    var o = { t: d.t };
    if (typeof d.id === 'string') o.id = d.id.slice(0, 48);
    if (typeof d.msg === 'string') o.msg = d.msg.slice(0, 400);
    if (typeof d.state === 'string') o.state = d.state.slice(0, 20);
    if (typeof d.screen === 'string') o.screen = d.screen.slice(0, 48);
    ['screens', 'taps', 'ms'].forEach(function (k) { if (num(d[k])) o[k] = d[k]; });
    return o;
  }

  function sendJarvis(m) {
    if (port) port.postMessage(JSON.stringify(m));
    else toJarvis.push(m);
  }

  function sendStage(m) {
    if (m && m.t === 'reload') {
      stageReady = false;
      frame.src = 'stage.html?' + Date.now();
      return;
    }
    if (!stageReady || !frame.contentWindow) { toStage.push(m); return; }
    frame.contentWindow.postMessage(m, '*');
  }

  window.addEventListener('message', function (e) {
    if (e.source === frame.contentWindow) { // the app
      var m = clean(e.data);
      if (!m) return;
      if (m.t === 'ready') {
        stageReady = true;
        toStage.splice(0).forEach(sendStage);
      }
      sendJarvis(m);
      return;
    }
    // Jarvis's port (posted by the app to this page's own origin; the preview can never be the sender of this)
    if (!port && e.data === 'jarvis-port' && e.ports && e.ports[0]) {
      port = e.ports[0];
      port.onmessage = function (ev) {
        var m;
        try { m = JSON.parse(ev.data); } catch (x) { return; }
        if (!m || typeof m.t !== 'string') return;
        sendStage(m);
      };
      toJarvis.splice(0).forEach(sendJarvis);
    }
  });
})();
