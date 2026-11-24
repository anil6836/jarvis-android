// 💻 Jarvis PCB studio — the board as KiCad files (Anil, 24 Nov, Build 3A feature 15: "KiCad file for the laptop").
// Our own code, ₹0: the same board, written in KiCad 8's text format so the free KiCad program (kicad.org; 8 or newer opens it)
// shows it on a laptop: the PCB (outline, every part's footprint with its pads, the tracks, the vias, the GND fill area, the words)
// and the schematic (each part's drawing, the wires, the net names). The footprints travel inside the files ("Jarvis" library).
// Nothing here goes on the network: the .zip is saved on the phone, and shared only when he taps 📤.
'use strict';
(function (root) {
  const P = root.PcbParts || (typeof require !== 'undefined' ? require('./pcbparts.js') : null);
  const C = root.PcbCore || (typeof require !== 'undefined' ? require('./pcbcore.js') : null);
  const F = root.PcbFab || (typeof require !== 'undefined' ? require('./pcbfab.js') : null);

  // ---------------------------------------------------------------- writing S-expressions

  const q = (s) => '"' + String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/[\u0000-\u001f]/g, ' ') + '"';
  function n(x) {
    if (!isFinite(x)) x = 0;
    let s = (Math.round(x * 10000) / 10000).toFixed(4).replace(/\.?0+$/, '');
    if (s === '-0') s = '0';
    return s;
  }
  /** (name a b (child …) …): children are strings (atoms on the same line) or nodes (on their own lines). */
  function N(name) { return { name, kids: Array.prototype.slice.call(arguments, 1).filter((k) => k != null && k !== '') }; }
  function render(node, depth) { // (children keep their order: atoms stay on the first line until the first child node)
    const pad = '\t'.repeat(depth);
    let s = pad + '(' + node.name, nested = false;
    for (const k of node.kids) {
      if (typeof k === 'string') s += nested ? '\n' + pad + '\t' + k : ' ' + k;
      else { s += '\n' + render(k, depth + 1); nested = true; }
    }
    return s + (nested ? '\n' + pad + ')' : ')');
  }
  /** Fixed ids for one board (the same board gives the same ids). */
  function ids(seed) {
    let h = 2166136261 >>> 0;
    for (const ch of String(seed)) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619) >>> 0; }
    let c = 0;
    const r = () => { h ^= h << 13; h >>>= 0; h ^= h >>> 17; h ^= h << 5; h >>>= 0; c++; return ((h + c * 2654435761) >>> 0).toString(16).padStart(8, '0'); };
    return () => { const a = r(), b = r(), c2 = r(), d = r(); return a + '-' + b.slice(0, 4) + '-4' + b.slice(5, 8) + '-' + ((parseInt(c2[0], 16) & 3) | 8).toString(16) + c2.slice(1, 4) + '-' + c2.slice(4, 8) + d; };
  }
  const font = (size, thick) => N('effects', N('font', N('size', n(size), n(size)), thick ? N('thickness', n(thick)) : null));
  const hidden = (size) => N('effects', N('font', N('size', n(size), n(size))), N('hide', 'yes'));
  const stroke = (w) => N('stroke', N('width', n(w)), N('type', 'solid'));
  /** Our turns are clockwise (y down); KiCad's are counter-clockwise. */
  const kang = (r) => ((360 - (r || 0)) % 360);
  function local(pt, x, y) { // a board point in the footprint's own (unturned) frame
    const dx = x - pt.x, dy = y - pt.y, r = (pt.rot || 0) * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
    return [dx * c + dy * s, -dx * s + dy * c];
  }

  // ---------------------------------------------------------------- the PCB

  const LAYERS = [['0', 'F.Cu', 'signal'], ['31', 'B.Cu', 'signal'], ['32', 'B.Adhes', 'user', 'B.Adhesive'], ['33', 'F.Adhes', 'user', 'F.Adhesive'],
    ['34', 'B.Paste', 'user'], ['35', 'F.Paste', 'user'], ['36', 'B.SilkS', 'user', 'B.Silkscreen'], ['37', 'F.SilkS', 'user', 'F.Silkscreen'],
    ['38', 'B.Mask', 'user'], ['39', 'F.Mask', 'user'], ['40', 'Dwgs.User', 'user', 'User.Drawings'], ['41', 'Cmts.User', 'user', 'User.Comments'],
    ['42', 'Eco1.User', 'user', 'User.Eco1'], ['43', 'Eco2.User', 'user', 'User.Eco2'], ['44', 'Edge.Cuts', 'user'], ['45', 'Margin', 'user'],
    ['46', 'B.CrtYd', 'user', 'B.Courtyard'], ['47', 'F.CrtYd', 'user', 'F.Courtyard'], ['48', 'B.Fab', 'user'], ['49', 'F.Fab', 'user']];
  const OX = 50, OY = 50; // the board sits on the page here (mm)

  function setup(D) {
    const t = D.board.thick || 1.6, cu = 0.035 * (D.board.cu || 1), col = { green: 'Green', blue: 'Blue', black: 'Black', white: 'White', purple: 'Purple' }[D.board.color] || 'Green';
    const L = (name, kids) => N.apply(null, ['layer', q(name)].concat(kids));
    return N('setup',
      N('stackup',
        L('F.SilkS', [N('type', q('Top Silk Screen')), N('color', q(col === 'White' ? 'Black' : 'White'))]),
        L('F.Paste', [N('type', q('Top Solder Paste'))]),
        L('F.Mask', [N('type', q('Top Solder Mask')), N('color', q(col)), N('thickness', '0.01')]),
        L('F.Cu', [N('type', q('copper')), N('thickness', n(cu))]),
        L('dielectric 1', [N('type', q('core')), N('thickness', n(Math.max(0.2, t - 2 * cu - 0.02))), N('material', q('FR4')), N('epsilon_r', '4.5'), N('loss_tangent', '0.02')]),
        L('B.Cu', [N('type', q('copper')), N('thickness', n(cu))]),
        L('B.Mask', [N('type', q('Bottom Solder Mask')), N('color', q(col)), N('thickness', '0.01')]),
        L('B.Paste', [N('type', q('Bottom Solder Paste'))]),
        L('B.SilkS', [N('type', q('Bottom Silk Screen')), N('color', q(col === 'White' ? 'Black' : 'White'))]),
        N('copper_finish', q('None')), N('dielectric_constraints', 'no')),
      N('pad_to_mask_clearance', '0'),
      N('allow_soldermask_bridges_in_footprints', 'no'),
      N('pcbplotparams', N('layerselection', '0x00000f0_80000001'), N('plot_on_all_layers_selection', '0x0000000_00000000'), N('disableapertmacros', 'no'),
        N('usegerberextensions', 'yes'), N('usegerberattributes', 'no'), N('usegerberadvancedattributes', 'no'), N('creategerberjobfile', 'no'),
        N('dashed_line_dash_ratio', '12.000000'), N('dashed_line_gap_ratio', '3.000000'), N('svgprecision', '6'), N('plotframeref', 'no'), N('viasonmask', 'no'),
        N('mode', '1'), N('useauxorigin', 'no'), N('hpglpennumber', '1'), N('hpglpenspeed', '20'), N('hpglpendiameter', '15.000000'),
        N('pdf_front_fp_property_popups', 'yes'), N('pdf_back_fp_property_popups', 'yes'), N('dxfpolygonmode', 'yes'), N('dxfimperialunits', 'yes'),
        N('dxfusepcbnewfont', 'yes'), N('psnegative', 'no'), N('psa4output', 'no'), N('plotreference', 'yes'), N('plotvalue', 'yes'), N('plotfptext', 'yes'),
        N('plotinvisibletext', 'no'), N('sketchpadsonfab', 'no'), N('subtractmaskfromsilk', 'no'), N('outputformat', '1'), N('mirror', 'no'),
        N('drillshape', '0'), N('scaleselection', '1'), N('outputdirectory', q(''))));
  }

  /** The .kicad_pcb text. sym: {ref: the schematic symbol's id} (so KiCad knows which drawing each footprint belongs to). */
  function pcb(D, sym, sheet) {
    const uid = ids(D.id + ':pcb'), R = C.rules(D), M = C.model(D);
    const nets = [''].concat(D.nets.map((x) => x.n)), netNo = new Map(nets.map((x, i) => [x, i]));
    const pinNet = new Map();
    D.nets.forEach((x) => x.p.forEach((p) => pinNet.set(p, x.n)));
    const X = (x) => n(OX + x), Y = (y) => n(OY + y);
    const top = N('kicad_pcb', N('version', '20240108'), N('generator', q('jarvis_pcb_studio')), N('generator_version', q('1.0')),
      N('general', N('thickness', n(D.board.thick || 1.6)), N('legacy_teardrops', 'no')), N('paper', q('A4')),
      N.apply(null, ['title_block', N('title', q(D.title || D.name || 'JARVIS PCB')), N('company', q('Jarvis PCB studio'))]),
      N.apply(null, ['layers'].concat(LAYERS.map((l) => N.apply(null, [l[0], q(l[1]), l[2]].concat(l[3] ? [q(l[3])] : []))))),
      setup(D));
    nets.forEach((x, i) => top.kids.push(N('net', String(i), q(x))));
    // footprints
    const words = C.texts(D, M);
    D.parts.forEach((pt) => {
      const f = P.fpOf(pt);
      if (!f) return;
      const a = kang(pt.rot), lib = P.LIB[pt.lib] || {};
      const fp = N('footprint', q('Jarvis:' + f.name), N('layer', q('F.Cu')), N('uuid', q(uid())), N('at', X(pt.x), Y(pt.y), a ? n(a) : null));
      if (lib.te) fp.kids.push(N('descr', q(String(lib.te))));
      const ref = words.find((w) => w.kind === 'ref' && w.ref === pt.ref);
      const rl = ref ? local(pt, ref.x, ref.y) : [0, -2];
      fp.kids.push(N('property', q('Reference'), q(pt.ref), N('at', n(rl[0]), n(rl[1]), n(ref ? kang(ref.r) : a)), N('layer', q('F.SilkS')), pt.noref ? N('hide', 'yes') : null,
        N('uuid', q(uid())), font(R.textH, 0.15)));
      fp.kids.push(N('property', q('Value'), q(pt.val || pt.lib), N('at', '0', '0', n(a)), N('layer', q('F.Fab')), N('uuid', q(uid())), font(1, 0.15)));
      fp.kids.push(N('property', q('Footprint'), q('Jarvis:' + f.name), N('at', '0', '0', n(a)), N('layer', q('F.Fab')), N('hide', 'yes'), N('uuid', q(uid())), font(1.27)));
      fp.kids.push(N('property', q('Datasheet'), q(''), N('at', '0', '0', n(a)), N('layer', q('F.Fab')), N('hide', 'yes'), N('uuid', q(uid())), font(1.27)));
      fp.kids.push(N('property', q('Description'), q(pt.note || ''), N('at', '0', '0', n(a)), N('layer', q('F.Fab')), N('hide', 'yes'), N('uuid', q(uid())), font(1.27)));
      if (sym && sym[pt.ref]) { fp.kids.push(N('path', q('/' + sym[pt.ref]))); fp.kids.push(N('sheetname', q('Root'))); fp.kids.push(N('sheetfile', q(sheet))); }
      fp.kids.push(N('attr', 'through_hole'));
      (f.silk || []).forEach((s) => { for (let k = 2; k < s.p.length; k += 2) fp.kids.push(N('fp_line', N('start', n(s.p[k - 2]), n(s.p[k - 1])), N('end', n(s.p[k]), n(s.p[k + 1])), stroke(0.12), N('layer', q('F.SilkS')), N('uuid', q(uid())))); });
      if (f.court) fp.kids.push(N('fp_rect', N('start', n(f.court[0]), n(f.court[1])), N('end', n(f.court[2]), n(f.court[3])), stroke(0.05), N('fill', 'none'), N('layer', q('F.CrtYd')), N('uuid', q(uid()))));
      words.filter((w) => w.kind === 'pin' && w.ref === pt.ref).forEach((w) => {
        const l = local(pt, w.x, w.y);
        fp.kids.push(N('fp_text', 'user', q(w.t), N('at', n(l[0]), n(l[1]), n(kang(w.r))), N('layer', q('F.SilkS')), N('uuid', q(uid())),
          N('effects', N('font', N('size', n(w.h), n(w.h)), N('thickness', '0.15')), w.a === 'l' ? N('justify', 'left') : w.a === 'r' ? N('justify', 'right') : null)));
      });
      f.pads.forEach((p) => {
        const shape = p.s === 'r' ? 'rect' : p.s === 'o' ? 'oval' : 'circle', net = pinNet.get(pt.ref + '.' + p.n);
        fp.kids.push(N('pad', q(p.n), 'thru_hole', shape, N('at', n(p.x), n(p.y), a ? n(a) : null), N('size', n(p.w), n(p.h)), N('drill', n(p.d)),
          N('layers', q('*.Cu'), q('*.Mask')), N('remove_unused_layers', 'no'), net ? N('net', String(netNo.get(net)), q(net)) : null, N('uuid', q(uid()))));
      });
      top.kids.push(fp);
    });
    // mounting holes (no copper)
    C.holes(D).forEach((h, i) => {
      top.kids.push(N('footprint', q('Jarvis:MountingHole_' + n(h.d) + 'mm'), N('layer', q('F.Cu')), N('uuid', q(uid())), N('at', X(h.x), Y(h.y)),
        N('property', q('Reference'), q('H' + (i + 1)), N('at', '0', n(-h.d)), N('layer', q('F.SilkS')), N('hide', 'yes'), N('uuid', q(uid())), font(1, 0.15)),
        N('property', q('Value'), q('M3'), N('at', '0', n(h.d)), N('layer', q('F.Fab')), N('uuid', q(uid())), font(1, 0.15)),
        N('attr', 'exclude_from_pos_files', 'exclude_from_bom'),
        N('fp_circle', N('center', '0', '0'), N('end', n(h.keep / 2), '0'), stroke(0.05), N('fill', 'none'), N('layer', q('F.CrtYd')), N('uuid', q(uid()))),
        N('pad', q(''), 'np_thru_hole', 'circle', N('at', '0', '0'), N('size', n(h.d), n(h.d)), N('drill', n(h.d)), N('layers', q('*.Cu'), q('*.Mask')), N('uuid', q(uid())))));
    });
    // the outline: straight edges and round corners
    const B = D.board, r = Math.min(B.r || 0, B.w / 2, B.h / 2), line = (x1, y1, x2, y2) => top.kids.push(N('gr_line', N('start', X(x1), Y(y1)), N('end', X(x2), Y(y2)), stroke(0.1), N('layer', q('Edge.Cuts')), N('uuid', q(uid()))));
    const arc = (cx, cy, a0) => { const p = (deg) => [cx + r * Math.cos(deg * Math.PI / 180), cy + r * Math.sin(deg * Math.PI / 180)], s = p(a0), m = p(a0 + 45), e = p(a0 + 90);
      top.kids.push(N('gr_arc', N('start', X(s[0]), Y(s[1])), N('mid', X(m[0]), Y(m[1])), N('end', X(e[0]), Y(e[1])), stroke(0.1), N('layer', q('Edge.Cuts')), N('uuid', q(uid())))); };
    line(r, 0, B.w - r, 0); line(B.w, r, B.w, B.h - r); line(B.w - r, B.h, r, B.h); line(0, B.h - r, 0, r);
    if (r > 0) { arc(B.w - r, r, 270); arc(B.w - r, B.h - r, 0); arc(r, B.h - r, 90); arc(r, r, 180); }
    // the board's own words (title…) that do not belong to a part
    words.filter((w) => w.kind === 'label').forEach((w) => {
      top.kids.push(N('gr_text', q(w.t), N('at', X(w.x), Y(w.y), n(kang(w.r))), N('layer', q('F.SilkS')), N('uuid', q(uid())),
        N('effects', N('font', N('size', n(w.h), n(w.h)), N('thickness', n(Math.max(0.15, w.h * 0.15)))), w.a === 'l' ? N('justify', 'left') : w.a === 'r' ? N('justify', 'right') : null)));
    });
    // tracks and vias
    (D.tracks || []).forEach((t) => {
      const no = netNo.has(t.n) ? netNo.get(t.n) : 0;
      for (let k = 1; k < t.p.length; k++) {
        const p0 = t.p[k - 1], p1 = t.p[k];
        if (Math.abs(p0[0] - p1[0]) < 1e-6 && Math.abs(p0[1] - p1[1]) < 1e-6) continue;
        top.kids.push(N('segment', N('start', X(p0[0]), Y(p0[1])), N('end', X(p1[0]), Y(p1[1])), N('width', n(t.w || R.track)), N('layer', q(t.l === 'B' ? 'B.Cu' : 'F.Cu')), N('net', String(no)), N('uuid', q(uid()))));
      }
    });
    (D.vias || []).forEach((v) => top.kids.push(N('via', N('at', X(v.x), Y(v.y)), N('size', n(R.via)), N('drill', n(R.viaDrill)), N('layers', q('F.Cu'), q('B.Cu')),
      N('net', String(netNo.has(v.n) ? netNo.get(v.n) : 0)), N('uuid', q(uid())))));
    // the GND fill: the area only (KiCad fills it again: press B)
    const g = D.nets.find((x) => C.isGnd(x.n));
    if (g) ['B', 'F'].forEach((side) => {
      if (!D.pour || !D.pour[side]) return;
      const pts = C.outline(B, 4).slice(0, -1);
      top.kids.push(N('zone', N('net', String(netNo.get(g.n))), N('net_name', q(g.n)), N('layer', q(side + '.Cu')), N('uuid', q(uid())), N('hatch', 'edge', '0.5'),
        N('connect_pads', N('clearance', n(R.pclr || 0.4))), N('min_thickness', '0.25'), N('filled_areas_thickness', 'no'),
        N('fill', 'yes', N('thermal_gap', n(R.gap || 0.4)), N('thermal_bridge_width', n(R.spoke || 0.5))),
        N('polygon', N.apply(null, ['pts'].concat(pts.map((p) => 'xy ' + X(p[0]) + ' ' + Y(p[1])).map((s) => '(' + s + ')'))))));
    });
    return render(top, 0) + '\n';
  }

  // ---------------------------------------------------------------- the schematic

  const K = 0.127; // our drawing's units → mm (20 units = 2.54 mm, KiCad's pin spacing)
  /** The .kicad_sch text from the drawing the studio shows (lay: its layout). → {text, sym: {ref: uuid}, sheet uuid} */
  function sch(D, lay, base) {
    const uid = ids(D.id + ':sch'), sheet = uid(), sym = {};
    let x0 = 1e9, y0 = 1e9;
    lay.P2.forEach((pl) => { x0 = Math.min(x0, pl.box[0]); y0 = Math.min(y0, pl.box[1]); });
    const SX = (x) => n(25.4 + (x - x0) * K), SY = (y) => n(25.4 + (y - y0) * K);
    const top = N('kicad_sch', N('version', '20231120'), N('generator', q('jarvis_pcb_studio')), N('generator_version', q('1.0')), N('uuid', q(sheet)), N('paper', q('A4')),
      N('title_block', N('title', q(D.title || D.name || 'JARVIS PCB')), N('company', q('Jarvis PCB studio'))));
    const libs = N('lib_symbols');
    top.kids.push(libs);
    const pinFont = N('effects', N('font', N('size', '1', '1')));
    D.parts.forEach((pt) => {
      const pl = lay.P2.get(pt.ref);
      if (!pl) return;
      const name = 'Jarvis:' + pt.ref + '_' + pt.lib, short = pt.ref + '_' + pt.lib;
      const ox = pl.tr(0, 0)[0], oy = pl.tr(0, 0)[1];
      const LX = (x) => n((x - ox) * K), LY = (y) => n(-(y - oy) * K); // the symbol's own frame: y up
      const gfx = N('symbol', q(short + '_0_1'));
      const fill0 = N('fill', N('type', 'none')), st = N('stroke', N('width', '0'), N('type', 'default'));
      pl.sy.S.forEach((s) => {
        if (s.l || s.poly) {
          const arr = s.l || s.poly, pts = [];
          for (let i = 0; i < arr.length; i += 2) { const w = pl.tr(arr[i], arr[i + 1]); pts.push('(xy ' + LX(w[0]) + ' ' + LY(w[1]) + ')'); }
          if (s.poly) pts.push(pts[0]);
          gfx.kids.push(N('polyline', N.apply(null, ['pts'].concat(pts)), st, s.poly ? N('fill', N('type', 'outline')) : fill0));
        } else if (s.r) {
          const a = pl.tr(s.r[0], s.r[1]), b = pl.tr(s.r[2], s.r[3]);
          gfx.kids.push(N('rectangle', N('start', LX(a[0]), LY(a[1])), N('end', LX(b[0]), LY(b[1])), st, fill0));
        } else if (s.c) {
          const c = pl.tr(s.c[0], s.c[1]);
          gfx.kids.push(N('circle', N('center', LX(c[0]), LY(c[1])), N('radius', n(s.c[2] * K)), st, fill0));
        } else if (s.a) {
          const c = pl.tr(s.a[0], s.a[1]), ro = pl.r * Math.PI / 180, at = (t) => [c[0] + s.a[2] * Math.cos(t + ro), c[1] + s.a[2] * Math.sin(t + ro)];
          const p0 = at(s.a[3]), pm = at((s.a[3] + s.a[4]) / 2), p1 = at(s.a[4]);
          gfx.kids.push(N('arc', N('start', LX(p0[0]), LY(p0[1])), N('mid', LX(pm[0]), LY(pm[1])), N('end', LX(p1[0]), LY(p1[1])), st, fill0));
        }
      });
      pl.sy.T.forEach((t) => {
        const w = pl.tr(t.x, t.y), side = pl.r === 90 || pl.r === 270;
        let a = t.a;
        if (pl.r === 180 && a !== 'center') a = a === 'left' ? 'right' : 'left';
        gfx.kids.push(N('text', q(t.t), N('at', LX(w[0]), LY(w[1]), side ? '90' : '0'), N('effects', N('font', N('size', '1', '1')), !side && a !== 'center' ? N('justify', a) : null)));
      });
      const pins = N('symbol', q(short + '_1_1'));
      Object.keys(pl.pins).forEach((k) => {
        const e = pl.pins[k], ang = Math.round(Math.atan2(e[3], -e[2]) * 180 / Math.PI + 360) % 360; // pointing into the body (y up)
        pins.kids.push(N('pin', 'passive', 'line', N('at', LX(e[0]), LY(e[1]), String(ang)), N('length', '0'),
          N('name', q(String((P.pinsOf(pt) || {})[k] || k)), pinFont), N('number', q(k), pinFont)));
      });
      libs.kids.push(N('symbol', q(name), N('pin_numbers', 'hide'), N('pin_names', N('offset', '0'), 'hide'), N('exclude_from_sim', 'no'), N('in_bom', 'yes'), N('on_board', 'yes'),
        N('property', q('Reference'), q(String(pt.ref).replace(/\d+$/, '')), N('at', '0', '0', '0'), font(1.27)),
        N('property', q('Value'), q(pt.val || pt.lib), N('at', '0', '0', '0'), font(1.27)),
        N('property', q('Footprint'), q(''), N('at', '0', '0', '0'), hidden(1.27)),
        N('property', q('Datasheet'), q(''), N('at', '0', '0', '0'), hidden(1.27)),
        N('property', q('Description'), q(''), N('at', '0', '0', '0'), hidden(1.27)),
        gfx, pins));
      // the part on the sheet
      const id = uid(), at = lay.names.get(pt.ref) || { x: ox, y: oy, a: 'center' }, f = P.fpOf(pt);
      sym[pt.ref] = id;
      const just = at.a === 'left' ? N('justify', 'left') : at.a === 'right' ? N('justify', 'right') : null;
      const place = N('symbol', N('lib_id', q(name)), N('at', SX(ox), SY(oy), '0'), N('unit', '1'), N('exclude_from_sim', 'no'), N('in_bom', 'yes'), N('on_board', 'yes'), N('dnp', 'no'),
        N('uuid', q(id)),
        N('property', q('Reference'), q(pt.ref), N('at', SX(at.x), SY(at.y - (pt.val ? 6 : 0)), '0'), N('effects', N('font', N('size', '1.27', '1.27')), just)),
        N('property', q('Value'), q(pt.val || pt.lib), N('at', SX(at.x), SY(at.y + 6), '0'), N('effects', N('font', N('size', '1.27', '1.27')), just)),
        N('property', q('Footprint'), q(f ? 'Jarvis:' + f.name : ''), N('at', SX(ox), SY(oy), '0'), hidden(1.27)),
        N('property', q('Datasheet'), q(''), N('at', SX(ox), SY(oy), '0'), hidden(1.27)),
        N('property', q('Description'), q(pt.note || ''), N('at', SX(ox), SY(oy), '0'), hidden(1.27)));
      Object.keys(pl.pins).forEach((k) => place.kids.push(N('pin', q(k), N('uuid', q(uid())))));
      place.kids.push(N('instances', N('project', q(base), N('path', q('/' + sheet), N('reference', q(pt.ref)), N('unit', '1')))));
      top.kids.push(place);
    });
    // wires, the dots where three meet, and the names (a name sits ON a wire in KiCad; the same name joins wires)
    const wire = (a, b) => top.kids.push(N('wire', N('pts', '(xy ' + SX(a[0]) + ' ' + SY(a[1]) + ')', '(xy ' + SX(b[0]) + ' ' + SY(b[1]) + ')'), N('stroke', N('width', '0'), N('type', 'default')), N('uuid', q(uid()))));
    const label = (t, x, y, ang) => top.kids.push(N('label', q(t), N('at', SX(x), SY(y), String(ang || 0)), N('fields_autoplaced', 'yes'),
      N('effects', N('font', N('size', '1.27', '1.27')), N('justify', ang === 180 ? 'right' : 'left', 'bottom')), N('uuid', q(uid()))));
    const longest = new Map();
    lay.wires.forEach((w) => {
      for (let i = 2; i + 1 < w.length; i += 2) {
        const a = [w[i - 2], w[i - 1]], b = [w[i], w[i + 1]];
        if (Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6) continue;
        wire(a, b);
        const len = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
        if (w.net && (!longest.has(w.net) || len > longest.get(w.net).len)) longest.set(w.net, { len, x: (a[0] + b[0]) / 2, y: (a[1] + b[1]) / 2 });
      }
    });
    lay.dots.forEach((e) => top.kids.push(N('junction', N('at', SX(e[0]), SY(e[1])), N('diameter', '0'), N('color', '0', '0', '0', '0'), N('uuid', q(uid())))));
    lay.flags.forEach((f) => {
      const e = f.e, end = [e[0] + e[2] * 14, e[1] + e[3] * 14];
      wire([e[0], e[1]], end);
      label(f.n, end[0], end[1], e[2] < -0.5 ? 180 : 0);
    });
    longest.forEach((v, net) => { if (!C.isPower(net)) label(net, v.x, v.y, 0); });
    top.kids.push(N('sheet_instances', N('path', q('/'), N('page', q('1')))));
    return { text: render(top, 0) + '\n', sym, sheet };
  }

  // ---------------------------------------------------------------- the project and the zip

  function pro(D, base, sheet) {
    const R = C.rules(D);
    return JSON.stringify({
      board: { design_settings: { defaults: { board_outline_line_width: 0.1, copper_line_width: 0.2, silk_line_width: 0.15, silk_text_size_h: 1.2, silk_text_size_v: 1.2 },
        rules: { min_clearance: R.clr, min_track_width: Math.min(R.track, 0.25), min_via_diameter: 0.6, min_through_hole_diameter: 0.3, min_copper_edge_clearance: R.edge } } },
      meta: { filename: base + '.kicad_pro', version: 1 },
      net_settings: { classes: [{ name: 'Default', clearance: R.clr, track_width: R.track, via_diameter: R.via, via_drill: R.viaDrill, wire_width: 6, bus_width: 12,
        diff_pair_gap: 0.25, diff_pair_via_gap: 0.25, diff_pair_width: 0.2, line_style: 0, microvia_diameter: 0.3, microvia_drill: 0.1, pcb_color: 'rgba(0, 0, 0, 0.000)', schematic_color: 'rgba(0, 0, 0, 0.000)' }],
        meta: { version: 3 } },
      sheets: [[sheet, 'Root']]
    }, null, 2) + '\n';
  }
  function readme(D, base) {
    return [
      'Jarvis PCB studio - KiCad files for "' + (D.title || D.name) + '"',
      '',
      'Open with KiCad 8 or newer (free: https://www.kicad.org). KiCad 9 opens these and updates them.',
      '1. Unzip this folder on the laptop.',
      '2. Double-click ' + base + '.kicad_pro (or open it from KiCad: File > Open Project).',
      '3. PCB Editor: the board, parts, tracks and vias as in the phone. Press B once to refill the GND copper area.',
      '4. Schematic Editor: the same drawing as the phone\'s schematic tab.',
      '5. Run Inspect > Design Rules Checker before ordering from KiCad.',
      'The footprints are inside the files (library name "Jarvis"); nothing else is needed.',
      '',
      'తెలుగులో: ల్యాప్‌టాప్‌లో KiCad 8 (లేదా కొత్తది) ఇన్‌స్టాల్ చేసి, ఈ zip విప్పి, ' + base + '.kicad_pro తెరవండి.',
      'PCB Editor లో బోర్డ్, Schematic Editor లో స్కీమాటిక్. GND రాగి నింపడానికి B నొక్కండి.',
      ''
    ].join('\r\n');
  }
  /** Every file: [{name, text}] (the drawing's layout comes from the page). */
  function files(D, lay) {
    const base = F.slug(D), dir = base + '-kicad/';
    const s = sch(D, lay, base);
    return [
      { name: dir + base + '.kicad_pro', text: pro(D, base, s.sheet) },
      { name: dir + base + '.kicad_pcb', text: pcb(D, s.sym, base + '.kicad_sch') },
      { name: dir + base + '.kicad_sch', text: s.text },
      { name: dir + 'README.txt', text: readme(D, base) }
    ];
  }

  const api = { files, pcb, sch, pro, render, N, q, n };
  root.PcbKiCad = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
