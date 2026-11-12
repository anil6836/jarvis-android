// 🛠️ The workshop's outer page. Jarvis (Java) hands this page one message port; the sealed stage (an iframe with
// sandbox="allow-scripts" only) talks to this page by postMessage. This page passes messages both ways and nothing else:
// from the stage only a few known kinds with short, plain fields get through to Jarvis.
(function () {
  'use strict';
  var frame = document.getElementById('stage');
  var port = null;          // to Jarvis
  var toJarvis = [];        // (said by the stage before Jarvis's port came)
  var stageReady = false;
  var toStage = [];         // (sent by Jarvis before the stage was ready)
  var KINDS = { ready: 1, ok: 1, err: 1, warn: 1, stats: 1, pick: 1, view: 1, size: 1, hid: 1, replay: 1, file: 1 };

  function clean(d) {
    if (!d || typeof d !== 'object' || typeof d.t !== 'string' || !KINDS[d.t]) return null;
    var o = { t: d.t };
    if (typeof d.id === 'string') o.id = d.id.slice(0, 48);
    if (typeof d.msg === 'string') o.msg = d.msg.slice(0, 400);
    if (typeof d.tris === 'number' && isFinite(d.tris)) o.tris = Math.round(d.tris);
    if (typeof d.parts === 'number' && isFinite(d.parts)) o.parts = Math.round(d.parts);
    if (typeof d.label === 'string') o.label = d.label.slice(0, 60);
    ['w', 'h', 'd', 'tris'].forEach(function (k) { if (typeof d[k] === 'number' && isFinite(d[k])) o[k] = d[k]; });
    if (d.t === 'file') { // (a picture or a print file Jarvis asked for: base64, at most ~30 MB; Jarvis checks it)
      if (typeof d.req === 'string') o.req = d.req.slice(0, 40);
      if (d.kind === 'jpg' || d.kind === 'stl') o.kind = d.kind;
      if (typeof d.data === 'string' && d.data.length <= 40000000) o.data = d.data;
    }
    return o;
  }

  function sendJarvis(m) {
    if (port) port.postMessage(JSON.stringify(m));
    else toJarvis.push(m);
  }

  function sendStage(m) {
    if (!stageReady || !frame.contentWindow) { toStage.push(m); return; }
    frame.contentWindow.postMessage(m, '*');
  }

  window.addEventListener('message', function (e) {
    if (e.source === frame.contentWindow) { // the stage
      var m = clean(e.data);
      if (!m) return;
      if (m.t === 'ready') {
        stageReady = true;
        toStage.splice(0).forEach(sendStage);
      }
      sendJarvis(m);
      return;
    }
    // Jarvis's port (posted by the app to this page's own origin; the stage can never be the sender of this)
    if (!port && e.data === 'jarvis-port' && e.ports && e.ports[0]) {
      port = e.ports[0];
      port.onmessage = function (ev) {
        var m;
        try { m = JSON.parse(ev.data); } catch (x) { return; }
        if (m && typeof m.t === 'string') sendStage(m);
      };
      toJarvis.splice(0).forEach(sendJarvis);
    }
  });
})();
