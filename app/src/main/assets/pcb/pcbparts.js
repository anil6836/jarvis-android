// 🔌 Jarvis PCB studio — the parts library (Anil, 23 Nov: "1 నుంచి 18 వరకు అన్ని చేయి").
// Our own drawn footprints for common through-hole parts (sizes from the usual published standards: 2.54 mm pin pitch, axial
// 10.16 mm, DIP rows 7.62 mm, 5 mm LEDs…), the schematic symbol of each part, and what each part is in simple Telugu.
// Footprints whose real size changes from seller to seller are marked verify: the studio tells him to check them with a ruler
// against the part he buys before ordering. Units: millimetres; footprint y grows downwards (screen), pad 1 is square.
'use strict';
(function (root) {
  // ------------------------------------------------------------------ footprints

  const C = (n, x, y, d, drill) => ({ n: String(n), x, y, s: 'c', w: d, h: d, d: drill });
  const R = (n, x, y, w, h, drill) => ({ n: String(n), x, y, s: 'r', w, h, d: drill });
  const O = (n, x, y, w, h, drill) => ({ n: String(n), x, y, s: 'o', w, h, d: drill });

  /** A circle as a silk polyline (from a0 to a1 degrees; 0 = right, 90 = down). */
  function arc(cx, cy, r, a0, a1, steps) {
    const pts = [], n = steps || Math.max(8, Math.round(Math.abs(a1 - a0) / 12));
    for (let i = 0; i <= n; i++) { const a = (a0 + (a1 - a0) * i / n) * Math.PI / 180; pts.push(cx + r * Math.cos(a), cy + r * Math.sin(a)); }
    return { p: pts };
  }
  const L = (...pts) => ({ p: pts });
  const RECT = (x1, y1, x2, y2) => ({ p: [x1, y1, x2, y1, x2, y2, x1, y2, x1, y1] });
  const PLUS = (x, y, s) => [L(x - s, y, x + s, y), L(x, y - s, x, y + s)];

  const FP = {};
  function def(id, o) { o.id = id; FP[id] = o; return o; }

  def('R_AX10', { name: 'R_Axial_DIN0207_P10.16mm', pads: [C(1, -5.08, 0, 1.6, 0.8), C(2, 5.08, 0, 1.6, 0.8)],
    silk: [RECT(-3.15, -1.25, 3.15, 1.25), L(-4.1, 0, -3.15, 0), L(3.15, 0, 4.1, 0)], court: [-6.1, -1.5, 6.1, 1.5],
    body: { t: 'axial', L: 6.3, D: 2.5, color: '#d8c49a' } });
  def('C_DISC5', { name: 'C_Disc_D5.0mm_P5.00mm', pads: [C(1, -2.5, 0, 1.6, 0.8), C(2, 2.5, 0, 1.6, 0.8)],
    silk: [L(-2.6, -1.5, 2.6, -1.5), L(-2.6, 1.5, 2.6, 1.5)], court: [-3.4, -1.8, 3.4, 1.8],
    body: { t: 'disc', D: 5, T: 2.5, color: '#d9822b' } });
  function cp(id, D, P, pad, drill, H) {
    const r = D / 2 + 0.25;
    return def(id, { name: 'CP_Radial_D' + D + 'mm_P' + P + 'mm', pads: [R(1, -P / 2, 0, pad, pad, drill), C(2, P / 2, 0, pad, drill)],
      silk: [arc(0, 0, r, 0, 360, 36)].concat(PLUS(-r - 0.6, -r + 0.6, 0.5)), court: [-r - 1.3, -r - 0.3, r + 0.3, r + 0.3],
      body: { t: 'radial', D, H }, polar: true });
  }
  cp('CP_D5_P2', 5, 2.0, 1.6, 0.8, 11);
  cp('CP_D6_P2.5', 6.3, 2.5, 1.6, 0.8, 11);
  cp('CP_D8_P3.5', 8, 3.5, 1.8, 0.9, 12);
  cp('CP_D10_P5', 10, 5.0, 2.0, 1.0, 16);
  function led(id, D, H) {
    const r = D / 2 + 0.45, flat = -r * 0.86;
    const ang = Math.acos(flat / r) * 180 / Math.PI; // (the flat edge on the cathode side, as on the real LED)
    return def(id, { name: 'LED_D' + D + '.0mm', pads: [R(1, -1.27, 0, 1.8, 1.8, 0.9), C(2, 1.27, 0, 1.8, 0.9)],
      silk: [arc(0, 0, r, -(180 - (180 - ang)), 180 - (180 - ang), 30), L(flat, -r * 0.51, flat, r * 0.51)], court: [-r - 0.3, -r - 0.3, r + 0.3, r + 0.3],
      body: { t: 'led', D, H }, polar: true });
  }
  led('LED_5MM', 5, 8.6);
  led('LED_3MM', 3, 5.3);
  def('D_DO41', { name: 'D_DO-41_P10.16mm', pads: [R(1, -5.08, 0, 2.2, 2.2, 1.1), C(2, 5.08, 0, 2.2, 1.1)],
    silk: [RECT(-2.6, -1.35, 2.6, 1.35), L(-1.9, -1.35, -1.9, 1.35), L(-1.7, -1.35, -1.7, 1.35), L(-1.5, -1.35, -1.5, 1.35),
      L(-3.8, 0, -2.6, 0), L(2.6, 0, 3.8, 0)], court: [-6.4, -1.7, 6.4, 1.7],
    body: { t: 'axial', L: 5.2, D: 2.7, color: '#26262a', band: true }, polar: true });
  def('D_DO201', { name: 'D_DO-201AD_P12.70mm', pads: [R(1, -6.35, 0, 3.0, 3.0, 1.5), C(2, 6.35, 0, 3.0, 1.5)],
    silk: [RECT(-4.75, -2.65, 4.75, 2.65), L(-3.9, -2.65, -3.9, 2.65), L(-3.6, -2.65, -3.6, 2.65), L(-3.3, -2.65, -3.3, 2.65)],
    court: [-8.1, -2.95, 8.1, 2.95], body: { t: 'axial', L: 9.5, D: 5.3, color: '#26262a', band: true }, polar: true });
  def('TO92', { name: 'TO-92_Inline_Wide', pads: [R(1, -2.54, 0, 1.5, 1.5, 0.8), C(2, 0, 0, 1.5, 0.8), C(3, 2.54, 0, 1.5, 0.8)],
    silk: [L(-2.2, 1.75, 2.2, 1.75), arc(0, 0.15, 2.6, 202, 338, 16)], court: [-3.4, -2.8, 3.4, 2.1], body: { t: 'to92' } });
  def('TO220_V', { name: 'TO-220-3_Vertical', pads: [R(1, -2.54, 0, 1.905, 2.0, 1.1), O(2, 0, 0, 1.905, 2.0, 1.1), O(3, 2.54, 0, 1.905, 2.0, 1.1)],
    silk: [RECT(-5.0, -3.1, 5.0, 1.5), L(-5.0, -1.9, 5.0, -1.9)], court: [-5.3, -3.4, 5.3, 1.8], body: { t: 'to220' } });
  (function dip8() {
    const pads = [];
    for (let i = 0; i < 4; i++) pads.push(i === 0 ? R(1, -3.81, -3.81, 1.6, 1.6, 0.8) : C(i + 1, -3.81, -3.81 + i * 2.54, 1.6, 0.8));
    for (let i = 0; i < 4; i++) pads.push(C(5 + i, 3.81, 3.81 - i * 2.54, 1.6, 0.8));
    def('DIP8', { name: 'DIP-8_W7.62mm', pads, silk: [L(-0.8, -5.1, -2.6, -5.1, -2.6, 5.1, 2.6, 5.1, 2.6, -5.1, 0.8, -5.1), arc(0, -5.1, 0.8, 0, 180, 8)],
      court: [-4.9, -5.5, 4.9, 5.5], body: { t: 'dip', n: 8 } });
  })();
  /** 1 × N pin header (male, for wires / modules) or female header (a module plugs in), 2.54 mm. */
  function hdr(n, female) {
    const id = (female ? 'FHDR1x' : 'HDR1x') + n;
    if (FP[id]) return FP[id];
    const pads = [], y0 = -(n - 1) * 1.27;
    for (let i = 0; i < n; i++) pads.push(i === 0 ? R(1, 0, y0, 1.7, 1.7, 1.0) : C(i + 1, 0, y0 + i * 2.54, 1.7, 1.0));
    const hy = n * 1.27;
    return def(id, { name: (female ? 'PinSocket_1x' : 'PinHeader_1x') + String(n).padStart(2, '0') + '_P2.54mm', pads,
      silk: [RECT(-1.33, -hy - 0.06, 1.33, hy + 0.06)], court: [-1.6, -hy - 0.35, 1.6, hy + 0.35], body: { t: 'hdr', n, female: !!female } });
  }
  for (let n = 2; n <= 8; n++) { hdr(n, false); hdr(n, true); }
  def('TERM2_508', { name: 'TerminalBlock_2P_P5.08mm', pads: [R(1, -2.54, 0, 2.6, 2.6, 1.3), C(2, 2.54, 0, 2.6, 1.3)],
    silk: [RECT(-5.1, -4.1, 5.1, 4.1), L(-5.1, 2.9, 5.1, 2.9)], court: [-5.4, -4.4, 5.4, 4.4], body: { t: 'term', n: 2 }, edge: 'S' });
  def('BUZZER_12', { name: 'Buzzer_12x9.5_RM7.6', pads: [R(1, -3.8, 0, 2.0, 2.0, 1.0), C(2, 3.8, 0, 2.0, 1.0)],
    silk: [arc(0, 0, 6.1, 0, 360, 40)].concat(PLUS(-3.8, -2.6, 0.6)), court: [-6.4, -6.4, 6.4, 6.4], body: { t: 'buzzer', D: 12, H: 9.5 },
    verify: 'కాళ్ళ మధ్య దూరం 7.6 mm (కొన్ని బజర్లకు 6.5 mm)', polar: true });
  // USB-A socket, right angle, through hole: contacts 2.50 / 2.00 / 2.50 mm apart and the shell legs 13.14 mm apart (the usual
  // published layout); the front (y −) goes to the board edge. Sellers' sockets differ a little: verify.
  def('USB_A_TH', { name: 'USB_A_Receptacle_Horizontal_TH', pads: [C(1, 3.5, 0, 1.5, 0.92), C(2, 1.0, 0, 1.5, 0.92), C(3, -1.0, 0, 1.5, 0.92), C(4, -3.5, 0, 1.5, 0.92),
    C('S1', -6.57, -2.71, 3.0, 2.3), C('S2', 6.57, -2.71, 3.0, 2.3)],
    silk: [L(-6.6, -10.3, 6.6, -10.3), L(-6.6, -10.3, -6.6, 3.7, 6.6, 3.7, 6.6, -10.3)], court: [-8.2, -10.6, 8.2, 4.0], body: { t: 'usb' },
    edge: 'N', overhang: 1.0, verify: '4 పిన్నుల దూరం 2.5 / 2.0 / 2.5 mm, పక్క కాళ్ళు 13.1 mm దూరం' });
  def('PTC_RAD5', { name: 'Fuse_Polyfuse_P5.08mm', pads: [C(1, -2.54, 0, 1.6, 0.8), C(2, 2.54, 0, 1.6, 0.8)],
    silk: [RECT(-3.9, -1.6, 3.9, 1.6)], court: [-4.2, -1.9, 4.2, 1.9], body: { t: 'disc', D: 7.4, T: 3, color: '#c9a400' },
    verify: 'కాళ్ళ మధ్య దూరం 5.1 mm' });
  def('IR3', { name: 'IR_Receiver_3pin_P2.54mm', pads: [R(1, -2.54, 0, 1.5, 1.5, 0.8), C(2, 0, 0, 1.5, 0.8), C(3, 2.54, 0, 1.5, 0.8)],
    silk: [RECT(-3.1, -1.2, 3.1, 2.4), arc(0, 2.4, 1.6, 0, 180, 10)], court: [-3.4, -1.5, 3.4, 4.3], body: { t: 'irrx' },
    verify: 'పిన్నుల వరుస (చాలా VS1838B లకి: OUT, GND, VCC) కొన్న భాగం మీద చూడండి' });
  // Build 2.1 (24 Nov, "1,2,3 చేయి"): a slide switch, a 3.5 mm audio jack and an electret mic (the 3.3 V regulator uses TO220_V).
  // SS12D00 slide switch: 3 pins 2.54 mm apart (the usual Indian listings say 2.54 mm; some switches are 2.0 mm: verify).
  def('SW_SLIDE_SPDT', { name: 'SW_Slide_SPDT_SS12D00_P2.54mm', pads: [R(1, -2.54, 0, 1.7, 1.7, 1.0), C(2, 0, 0, 1.7, 1.0), C(3, 2.54, 0, 1.7, 1.0)],
    silk: [RECT(-4.35, -1.85, 4.35, 1.85), RECT(-1.6, -3.4, 0.4, -1.85)], court: [-4.65, -3.7, 4.65, 2.15], body: { t: 'slide' },
    verify: 'స్లైడ్ స్విచ్ కాళ్ళ మధ్య దూరం 2.54 mm (SS12D00; కొన్ని స్విచ్‌లకి 2.0 mm)' });
  // 3.5 mm stereo jack, horizontal, through hole: the CUI SJ1-3533NG layout (KiCad's footprint of it, round holes); the plug goes in
  // from the board edge (y −). PJ-307-type jacks have other legs: verify.
  def('JACK35_SJ1', { name: 'Jack_3.5mm_CUI_SJ1-3533NG_Horizontal_CircularHoles', pads: [R('S', 0, 0, 2.8, 2.8, 2.0), C('T', 2, 2.4, 2.8, 2.0), C('R', 2, 7.9, 2.8, 2.0)],
    silk: [L(-2.22, -5.32, 4.02, -5.32, 4.02, -1.32, 4.32, -1.32, 4.32, 12.92, -4.12, 12.92, -4.12, -1.32, -2.22, -1.32, -2.22, -5.32)],
    court: [-4.5, -5.7, 4.7, 13.3], body: { t: 'jack' }, edge: 'N', overhang: 0.5,
    verify: '3.5mm జాక్ CUI SJ1-3533NG కాళ్ళ వరుసతో గీశాం (S, T 2.0/2.4 mm, R 7.9 mm). మామూలుగా దొరికే PJ-307 లాంటి జాక్‌ల కాళ్ళు వేరు: అలాంటిది వాడితే ఆర్డర్ ముందు చెప్పండి' });
  // Electret mic capsule 9.7 mm, 2 pins (wire-lead mics: solder the wires to these pads).
  def('MIC_D9.7', { name: 'Electret_Mic_D9.7mm_P2.54mm', pads: [R(1, -1.27, 0, 1.7, 1.7, 1.0), C(2, 1.27, 0, 1.7, 1.0)],
    silk: [arc(0, 0, 5.05, 0, 360, 40)].concat(PLUS(-3.4, -2.6, 0.6)), court: [-5.35, -5.35, 5.35, 5.35], body: { t: 'mic', D: 9.7, H: 6.7 },
    verify: 'మైక్ కాళ్ళ దూరం 2.54 mm అనుకున్నాం; వైర్లు ఉన్న మైక్ అయితే ఆ వైర్లు ఈ రెండు ప్యాడ్‌లకి టంకం చేయండి', polar: true });

  /** ESP32 DevKit V1 (30 pins) on two female headers: rows 25.4 mm apart (some boards 22.86 mm: measure yours). */
  function esp32(rows) {
    const id = 'ESP32_DEVKIT30_' + Math.round(rows * 100);
    if (FP[id]) return FP[id];
    const pads = [], hx = rows / 2, y0 = -17.78;
    for (let i = 0; i < 15; i++) pads.push(i === 0 ? R(1, -hx, y0 + i * 2.54, 1.7, 1.7, 1.0) : C(i + 1, -hx, y0 + i * 2.54, 1.7, 1.0));
    for (let i = 0; i < 15; i++) pads.push(C(16 + i, hx, y0 + i * 2.54, 1.7, 1.0));
    const bw = rows + 2.9;
    return def(id, { name: 'ESP32_DevKitV1_30pin_Socket_Rows' + rows + 'mm', pads,
      silk: [RECT(-bw / 2, -25.75, bw / 2, 25.75), L(-bw / 2, -20.2, bw / 2, -20.2)], court: [-bw / 2 - 0.3, -26.05, bw / 2 + 0.3, 26.05],
      body: { t: 'esp32', rows }, verify: 'రెండు పిన్నుల వరుసల మధ్య దూరం ' + rows + ' mm (మీ ESP32 బోర్డ్‌ని స్కేల్‌తో కొలవండి)' });
  }
  esp32(25.4); esp32(22.86);

  // ---- 25 Nov ("1. ఇంకా భాగాలు (₹0)"): power sockets, a push button, a knob, a MOSFET switch, an 18650 holder (+ two modules on headers).
  // Pad places from KiCad's own library (BarrelJack_Horizontal, SW_PUSH_6mm, Potentiometer_Alps_RK09K_Single_Vertical,
  // BatteryHolder_MPD_BH-18650-PC2) and the parts' drawings; centred on the body. Sellers' parts differ: each says what to check.
  // DC-005 power jack 5.5 × 2.1 mm, horizontal: + centre pin at the back, − sleeve 6 mm ahead of it, the switch pin to the side. Its flat
  // blade legs go into big round holes (no slots, so every factory drills it the same way); the plug goes in from the board edge (x −).
  def('DCJACK_DC005', { name: 'BarrelJack_DC-005_Horizontal_CircularHoles', pads: [R(1, 6.45, 0, 4.6, 4.6, 3.2), C(2, 0.45, 0, 4.6, 3.2), C(3, 3.45, 4.75, 5.0, 3.8)],
    silk: [L(7.25, -2.7, 7.25, -4.6, -7.25, -4.6, -7.25, 4.6, 0.7, 4.6), L(6.25, 4.6, 7.25, 4.6, 7.25, 2.7), L(-3.75, -4.6, -3.75, 4.6)],
    court: [-7.6, -4.9, 9.0, 7.6], body: { t: 'dcjack' }, edge: 'W', overhang: 0.3, polar: true,
    verify: 'DC జాక్ (DC-005 లాంటిది) కాళ్ళు: మధ్య పిన్ వెనక, − కాలు 6 mm ముందు, మూడో కాలు పక్కకి 4.7 mm. కొన్న జాక్‌ని 📏 1:1 మీద పెట్టి చూడండి' });
  // 6 × 6 mm tactile push button, 4 legs: the two legs on one side are joined inside the button (1 and 1b, 2 and 2b); 6.5 × 4.5 mm.
  def('BTN_6MM', { name: 'SW_PUSH_6mm', pads: [R(1, -3.25, -2.25, 2.0, 2.0, 1.1), C('1b', 3.25, -2.25, 2.0, 1.1), C(2, -3.25, 2.25, 2.0, 1.1), C('2b', 3.25, 2.25, 2.0, 1.1)],
    silk: [L(-2.0, -3.3, 2.0, -3.3), L(-2.0, 3.3, 2.0, 3.3), L(-4.4, -1.0, -4.4, 1.0), L(4.4, -1.0, 4.4, 1.0), arc(0, 0, 1.7, 0, 360, 24)],
    court: [-4.7, -3.6, 4.7, 3.6], body: { t: 'btn', H: 5 } });
  // RV09 / Alps RK09K knob, vertical (the shaft points up): legs 1 W 3 in a row 2.5 mm apart in front, two bracket tabs at the sides
  // (plated holes, soldered for strength; not joined to anything).
  def('POT_RV09', { name: 'Potentiometer_RV09_Alps_RK09K_Single_Vertical', pads: [R(1, -7, 2.5, 1.8, 1.8, 1.0), C(2, -7, 0, 1.8, 1.0), C(3, -7, -2.5, 1.8, 1.0),
    C('M1', 0, -4.4, 3.6, 2.0), C('M2', 0, 4.4, 3.6, 2.0)],
    silk: [L(-2.2, -4.9, -6, -4.9, -6, 4.9, -2.2, 4.9), L(2.2, -4.9, 6, -4.9, 6, 4.9, 2.2, 4.9), arc(0.5, 0, 3, 0, 360, 30)],
    court: [-8.2, -6.5, 6.3, 6.5], body: { t: 'pot' },
    verify: 'నాబ్ (RV09 / RK09K) కాళ్ళు 2.5 mm దూరం, పక్క పట్టీలు 8.8 mm దూరం. వేరే నాబ్‌లు (16 mm WH148) వేరు: కొన్నదాన్ని 📏 1:1 మీద చూడండి' });
  // A USB Type-C power board (a small ready breakout with its CC resistors) standing on 2 pins (VBUS, GND) at its back; its mouth at
  // the board edge (y −).
  def('USBC_MOD', { name: 'USB_C_Power_Breakout_2pin_P2.54mm', pads: [R(1, -1.27, 4.2, 1.7, 1.7, 1.0), C(2, 1.27, 4.2, 1.7, 1.0)],
    silk: [RECT(-4.6, -6.2, 4.6, 6.2), RECT(-3.2, -6.2, 3.2, -2.6)], court: [-4.9, -6.5, 4.9, 6.5], body: { t: 'usbc' }, edge: 'N', overhang: 1.2, polar: true,
    verify: 'Type-C బోర్డ్ సైజు, VBUS / GND పిన్నుల వరుస సెల్లర్‌ని బట్టి మారతాయి. 5.1kΩ CC రెసిస్టర్లు ఉన్నదే కొనండి' });
  // One 18650 cell holder, through hole: legs 72 mm apart and two plastic pegs (holes for them, plated, joined to nothing).
  def('BAT18650', { name: 'BatteryHolder_MPD_BH-18650-PC2', pads: [R(1, -36, 0, 3.6, 3.6, 2.0), C(2, 36, 0, 3.6, 2.0), C('M1', -27.355, 0, 4.4, 3.2), C('M2', 28.255, 0, 4.4, 3.2)],
    silk: [RECT(-39.0, -10.25, 39.0, 10.25)].concat(PLUS(-36, -5.6, 1.0), [L(35, -5.6, 37, -5.6)]), court: [-39.3, -10.55, 39.3, 10.55], body: { t: 'bat' }, polar: true,
    verify: '18650 హోల్డర్ కాళ్ళ దూరం 72 mm, ప్లాస్టిక్ పెగ్‌లకి రెండు రంధ్రాలు (MPD BH-18650-PC2 లాంటిది). వేరే హోల్డర్లు వేరు: కొన్నదాన్ని కొలవండి, + ఏ వైపో చూడండి' });

  // ---- 26 Nov ("2. 📚 మాడ్యూల్ లైబ్రరీ"): ready modules that plug into a socket on the board. The socket row is the exact 2.54 mm
  // pitch (pin 1 left, pins in a row along x); around it the module's own board as he sees it from above: lying flat over the
  // board (body: which side of the row it covers) or standing up on its 90° pins (only its thin edge over the board). The outline
  // is the usual size of that module (sellers differ by a mm or two: verify). lab: the side the pin names are printed on.
  function modFp(id, o) {
    const n = o.n, x0 = -(n - 1) * 1.27, hx = n * 1.27, pads = [];
    for (let i = 0; i < n; i++) pads.push(i === 0 ? R(1, x0, 0, 1.7, 1.7, 1.0) : C(i + 1, x0 + i * 2.54, 0, 1.7, 1.0));
    const out = o.out, row = [-hx - 0.06, -1.33, hx + 0.06, 1.33];
    const court = [Math.min(row[0] - 0.3, out[0] - 0.3), Math.min(row[1] - 0.3, out[1] - 0.3), Math.max(row[2] + 0.3, out[2] + 0.3), Math.max(row[3] + 0.3, out[3] + 0.3)];
    return def(id, { name: o.name, pads, silk: [RECT(row[0], row[1], row[2], row[3]), RECT(out[0], out[1], out[2], out[3])].concat(o.silk || []), court,
      body: Object.assign({ t: 'mod', n, w: out[2] - out[0], d: out[3] - out[1], cx: (out[0] + out[2]) / 2, cy: (out[1] + out[3]) / 2 }, o.body), lab: o.lab, verify: o.verify,
      edge: o.edge, overhang: o.overhang });
  }
  // HC-05 (ZS-040 board, 15.5 × 37 mm): its 6 pins are bent 90°, so it stands up on the socket; the parts face y −.
  modFp('MOD_HC05', { n: 6, name: 'Module_HC-05_ZS-040_Standing_Socket_1x06_P2.54mm', out: [-7.9, -4.4, 7.9, 1.6], lab: 'S',
    body: { k: 'hc05', stand: true, H: 37, col: 0x1f57b8 }, verify: 'HC-05 బోర్డ్ 15.5 × 37 mm, 90° వంగిన 6 పిన్నులతో నిలబడుతుంది' });
  // 0.96" OLED (SSD1306, 4-pin I2C, 27.3 × 27.8 mm): lies flat over the board, its pins along its top edge, the screen toward y +.
  modFp('MOD_OLED096', { n: 4, name: 'Module_OLED_0.96in_I2C_Flat_Socket_1x04_P2.54mm', out: [-13.65, -2.2, 13.65, 25.6], lab: 'N',
    silk: [RECT(-11.5, 3.6, 11.5, 18.6)], body: { k: 'oled', col: 0x1f57b8 }, verify: 'OLED బోర్డ్ 27.3 × 27.8 mm, పిన్నులు పై అంచున మధ్యలో (కొన్నవి 26 × 26)' });
  // DHT11 / DHT22 module (3 pins, the sensor on a small board): stands up, the sensor faces y −.
  modFp('MOD_DHT', { n: 3, name: 'Module_DHT11_DHT22_Standing_Socket_1x03_P2.54mm', out: [-7.0, -7.6, 7.0, 1.6], lab: 'S',
    body: { k: 'dht', stand: true, H: 26, col: 0x1f57b8 }, verify: 'DHT మాడ్యూల్ బోర్డ్ సుమారు 14 × 26 mm, నిలబడుతుంది (సెన్సార్ ముందుకి)' });
  // DS3231 real-time clock (ZS-042, 38 × 22 mm): its 6 pins are bent 90°, it stands; the coin cell holder is at its back (y +).
  modFp('MOD_DS3231', { n: 6, name: 'Module_DS3231_ZS-042_Standing_Socket_1x06_P2.54mm', out: [-11.0, -3.4, 11.0, 8.6], lab: 'N',
    body: { k: 'ds3231', stand: true, H: 38, col: 0x1f57b8 }, verify: 'DS3231 (ZS-042) బోర్డ్ 38 × 22 mm, నిలబడుతుంది, వెనక బ్యాటరీ' });
  // GY-BME280 / GY-BMP280, 4-pin I2C (about 11 × 14 mm): lies flat, the sensor toward y −.
  modFp('MOD_BME280', { n: 4, name: 'Module_GY-BME280_4pin_Flat_Socket_1x04_P2.54mm', out: [-5.6, -12.4, 5.6, 1.6], lab: 'S',
    body: { k: 'bme', col: 0x6b3fa0 }, verify: 'BME280 4-పిన్ బోర్డ్ సుమారు 11 × 14 mm (6 పిన్నుల 3.3V బోర్డ్ వేరు)' });
  // GY-521 MPU6050 (21.2 × 16.4 mm, 8 pins along its long edge): lies flat, the chip toward y −.
  modFp('MOD_MPU6050', { n: 8, name: 'Module_GY-521_MPU6050_Flat_Socket_1x08_P2.54mm', out: [-10.6, -14.8, 10.6, 1.6], lab: 'S',
    body: { k: 'mpu', col: 0x1f57b8 }, verify: 'GY-521 బోర్డ్ 21.2 × 16.4 mm, 8 పిన్నులు పొడవైన అంచున' });
  // HC-SR04 ultrasonic (45 × 20 mm, 4 pins at its bottom edge): stands up, the two "eyes" look out toward y −.
  modFp('MOD_HCSR04', { n: 4, name: 'Module_HC-SR04_Standing_Socket_1x04_P2.54mm', out: [-22.5, -13.6, 22.5, 2.4], lab: 'S', edge: 'N', overhang: 11.5,
    body: { k: 'sr04', stand: true, H: 20, col: 0x1f57b8 }, verify: 'HC-SR04 బోర్డ్ 45 × 20 mm, కళ్ళు ముందుకి 12 mm బయటికి' });
  // MAX98357A I2S amplifier (about 17.8 × 19.3 mm, 7 pins at one edge, the speaker screw block at the other): lies flat, toward y −.
  modFp('MOD_MAX98357', { n: 7, name: 'Module_MAX98357A_I2S_Amp_Flat_Socket_1x07_P2.54mm', out: [-8.9, -17.8, 8.9, 1.6], lab: 'S',
    body: { k: 'max', col: 0x6b3fa0 }, verify: 'MAX98357A బోర్డ్ సుమారు 17.8 × 19.3 mm, పిన్నులు ఒక అంచున, స్పీకర్ స్క్రూలు ఇంకో అంచున' });

  // ---- 26 Nov (Build C, "5 SMD + ఫ్యాక్టరీ టంకం"): the small parts as surface-mount ones the factory solders (PCBA). Pad places
  // and sizes from KiCad's own library (R_0805_2012Metric, C_0805_2012Metric, LED_0805_2012Metric, D_SMA, SOT-23, SOIC-8_3.9x4.9mm_P1.27mm,
  // SOT-223-3_TabPin2), with the same zero turn as KiCad's (the factory's turn is worked out from it). The pads keep the LIBRARY's pin
  // numbers (a SOT-23 transistor's physical pin 1 is the base, so its pad is named "2" = B here): every net stays as it was.
  const S = (n, x, y, w, h) => ({ n: String(n), x, y, s: 'r', w, h });
  function chip(id, name, px, pw, ph, bw, bd, col, polar) {
    const cx = px + pw / 2 + 0.25, cy = Math.max(ph / 2, bd / 2) + 0.25;
    const silk = polar ? [L(-cx - 0.35, -cy + 0.2, -cx - 0.35, cy - 0.2), L(-bw / 2 + 0.3, -cy, bw / 2 - 0.3, -cy), L(-bw / 2 + 0.3, cy, bw / 2 - 0.3, cy)]
      : [L(-0.25, -cy, 0.25, -cy), L(-0.25, cy, 0.25, cy)];
    return def(id, { name, pads: [S(1, -px, 0, pw, ph), S(2, px, 0, pw, ph)], silk, court: [-cx - 0.6, -cy - 0.3, cx + 0.4, cy + 0.3],
      body: { t: 'chip', w: bw, d: bd, H: polar === 'led' ? 0.8 : col === 'c' ? 1.25 : 0.55, col }, polar: !!polar, smt: true });
  }
  chip('R_0805', 'R_0805_2012Metric', 0.9125, 1.025, 1.4, 2.0, 1.25, 'r');
  chip('C_0805', 'C_0805_2012Metric', 0.95, 1.0, 1.45, 2.0, 1.25, 'c');
  chip('LED_0805', 'LED_0805_2012Metric', 0.9375, 0.975, 1.4, 2.0, 1.25, 'led', 'led');
  def('D_SMA', { name: 'D_SMA', pads: [S(1, -2.0, 0, 2.5, 1.8), S(2, 2.0, 0, 2.5, 1.8)],
    silk: [L(2.1, -1.55, -3.5, -1.55, -3.5, 1.55, 2.1, 1.55)], court: [-3.75, -1.8, 3.6, 1.8], body: { t: 'sma' }, polar: true, smt: true });
  // SOT-23 NPN (MMBT3904): physical 1 = B, 2 = E, 3 = C → our pads "2", "3", "1" (the library's C B E)
  def('SOT23_NPN', { name: 'SOT-23', pads: [S(2, -1.1375, -0.95, 1.325, 0.6), S(3, -1.1375, 0.95, 1.325, 0.6), S(1, 1.1375, 0, 1.325, 0.6)],
    silk: [L(0.2, -1.55, 0.75, -1.55, 0.75, -0.6), L(0.2, 1.55, 0.75, 1.55, 0.75, 0.6), L(-0.75, -1.55, -0.35, -1.55)], court: [-2.05, -1.75, 2.05, 1.75],
    body: { t: 'sot23' }, polar: true, smt: true });
  (function soic8() {
    const pads = [];
    for (let i = 0; i < 4; i++) pads.push(S(i + 1, -2.475, -1.905 + i * 1.27, 1.95, 0.6));
    for (let i = 0; i < 4; i++) pads.push(S(5 + i, 2.475, 1.905 - i * 1.27, 1.95, 0.6));
    def('SOIC8', { name: 'SOIC-8_3.9x4.9mm_P1.27mm', pads, silk: [L(-1.95, -2.56, 1.95, -2.56), L(-1.95, 2.56, 1.95, 2.56), L(-3.45, -2.6, -3.45, -2.3)],
      court: [-3.7, -2.7, 3.7, 2.7], body: { t: 'soic', n: 8 }, polar: true, smt: true });
  })();
  // SOT-223 regulator (AMS1117-3.3): 1 GND, 2 OUT, 3 IN in a row; the wide tab is OUT too (KiCad's TabPin2: the same pad name "2")
  def('SOT223_REG', { name: 'SOT-223-3_TabPin2', pads: [S(1, -2.3, 3.15, 1.5, 2.0), S(2, 0, 3.15, 1.5, 2.0), S(3, 2.3, 3.15, 1.5, 2.0), S(2, 0, -3.15, 3.8, 2.0)],
    silk: [L(-3.45, -1.9, -3.45, 1.9), L(3.45, -1.9, 3.45, 1.9)], court: [-4.0, -4.4, 4.0, 4.4], body: { t: 'sot223' }, polar: true, smt: true });

  /** SMD parts the factory solders: the footprint, the part it is, its LCSC / JLCPCB number (looked up on jlcpcb.com, 26 Nov 2026), the
   *  footprint name the factory's form wants and the turn the factory adds to KiCad's (its own library's zero differs for some). */
  const RES_LCSC = { '100': 'C17408', '220': 'C17557', '330': 'C17630', '470': 'C17710', '1k': 'C17513', '2.2k': 'C17520', '4.7k': 'C17673', '10k': 'C17414',
    '22k': 'C17560', '47k': 'C17713', '100k': 'C149504', '1M': 'C17514' };
  const CAP_LCSC = { '10nF': 'C1710', '100nF': 'C49678', '1uF': 'C28323', '10uF': 'C15850' };
  const LED_LCSC = { red: 'C84256', green: 'C2297', white: 'C34499', yellow: 'C2296', blue: 'C84259' };
  const SMD = {
    res: { fp: 'R_0805', jfp: '0805', te: '0805 SMD రెసిస్టర్ (⅛ W)', rot: 0, how: 'దిశ లేదు. 2 × 1.25 mm నల్లటి ముక్క; మీద 103 (= 10k) లాంటి అంకెలు ఉండొచ్చు, ఉండకపోవచ్చు.' },
    cer: { fp: 'C_0805', jfp: '0805', te: '0805 SMD కెపాసిటర్', rot: 0, how: 'దిశ లేదు. మీద ఏమీ రాసి ఉండదు: విలువ ఫ్యాక్టరీ జాబితాలోనే.' },
    led5: { fp: 'LED_0805', jfp: 'LED0805', te: '0805 SMD LED', rot: 0, how: '⚠️ దిశ ఉంది: కింద / మీద ఆకుపచ్చ గుర్తు (T లేదా బాణం) ఉన్న చివర − (K, ప్యాడ్ 1, బోర్డ్ మీది గీత వైపు).' },
    led3: { fp: 'LED_0805', jfp: 'LED0805', te: '0805 SMD LED', rot: 0, how: '⚠️ దిశ ఉంది: కింద / మీద ఆకుపచ్చ గుర్తు (T లేదా బాణం) ఉన్న చివర − (K, ప్యాడ్ 1, బోర్డ్ మీది గీత వైపు).' },
    d4007: { fp: 'D_SMA', jfp: 'SMA', te: 'M7 (SMD 1N4007, 1 A)', mpn: 'M7', lcsc: 'C95872', rot: 0, how: '⚠️ తెల్ల గీత ఉన్న చివర K (−, ప్యాడ్ 1), బోర్డ్ మీది గీత వైపు.' },
    d5822: { fp: 'D_SMA', jfp: 'SMA', te: 'SS34 (SMD షాట్కీ, 3 A 40 V)', mpn: 'SS34', lcsc: 'C8678', rot: 0, how: '⚠️ తెల్ల గీత ఉన్న చివర K (−, ప్యాడ్ 1), బోర్డ్ మీది గీత వైపు.' },
    bc547: { fp: 'SOT23_NPN', jfp: 'SOT-23', te: 'MMBT3904 (SMD NPN)', mpn: 'MMBT3904', lcsc: 'C20526', rot: 180, how: 'SOT-23: ఒక వైపు రెండు కాళ్ళు (B, E), ఇంకో వైపు ఒకటి (C). 200 mA వరకు; ఈ స్టూడియో 100 mA వరకే అనుకుంటుంది.' },
    ne555: { fp: 'SOIC8', jfp: 'SOIC-8', te: 'NE555DR (SMD 555)', mpn: 'NE555DR', lcsc: 'C7593', rot: 270, how: '⚠️ చిప్ మీది చిన్న చుక్క / గీత ఉన్న మూల పిన్ 1. సాకెట్ ఉండదు: ఫ్యాక్టరీ నేరుగా టంకం చేస్తుంది.' },
    r3v3: { fp: 'SOT223_REG', jfp: 'SOT-223', te: 'AMS1117-3.3 (SMD 3.3 V)', mpn: 'AMS1117-3.3', lcsc: 'C6186', rot: 180, how: 'SOT-223: మూడు కాళ్ళు GND · OUT · IN, వెనక వెడల్పాటి పట్టీ కూడా OUT. IN 15 V కంటే మించకూడదు; OUT దగ్గర 10 µF తప్పకుండా.' }
  };
  function resKey(v) {
    const o = ohms(v);
    if (!(o > 0)) return '';
    const s = o >= 1e6 ? o / 1e6 + 'M' : o >= 1e3 ? o / 1e3 + 'k' : String(o);
    return s.replace(/\.0+(?=[kM]?$)/, '');
  }
  function capKey(v) {
    const m = /^\s*([\d.]+)\s*([pnuµm]?)F?/i.exec(String(v || ''));
    if (!m) return '';
    const f = parseFloat(m[1]) * ({ p: 1e-12, n: 1e-9, u: 1e-6, 'µ': 1e-6, m: 1e-3 }[m[2].toLowerCase()] || 1);
    return [['10nF', 1e-8], ['100nF', 1e-7], ['1uF', 1e-6], ['10uF', 1e-5]].reduce((a, k) => (Math.abs(f - k[1]) / k[1] < 0.02 ? k[0] : a), '');
  }
  /** What this part becomes as an SMD part (null: it stays a through-hole part). */
  function smdOf(part) {
    const s = SMD[part && part.lib];
    if (!s) return null;
    const o = Object.assign({ lib: part.lib }, s);
    if (part.lib === 'res') { const k = resKey(part.val); o.mpn = (k || ascii(part.val)) + ' 0805'; o.lcsc = RES_LCSC[k] || ''; }
    else if (part.lib === 'cer') { const k = capKey(part.val); o.mpn = (k || ascii(part.val)) + ' 0805'; o.lcsc = CAP_LCSC[k] || ''; if (k === '10uF') o.volts = 25; }
    else if (part.lib === 'led5' || part.lib === 'led3') {
      const c = (String(part.color || part.val || '').toLowerCase().match(/red|green|blue|yellow|white/) || ['red'])[0];
      o.mpn = c.toUpperCase() + ' LED 0805'; o.lcsc = LED_LCSC[c] || ''; o.col = c;
    }
    return o;
  }
  function ascii(v) { return String(v || '').replace(/µ/g, 'u').replace(/Ω/g, '').replace(/[^ -~]/g, '').trim(); }
  /** Is this part already the SMD one (its footprint has no holes)? */
  function isSmd(part) { const f = fpOf(part); return !!(f && f.smt); }

  function fp(id) {
    if (FP[id]) return FP[id];
    let m = /^(F?)HDR1x(\d+)$/.exec(id || '');
    if (m && +m[2] >= 1 && +m[2] <= 40) return hdr(+m[2], !!m[1]);
    m = /^ESP32_DEVKIT30_(\d+)$/.exec(id || '');
    if (m) return esp32(+m[1] / 100);
    return null;
  }

  // ------------------------------------------------------------------ the parts (what he picks), their symbols and Telugu words

  // sym: res cap cpol led diode npn box conn buzzer fuse spdt mic btn pot nmos cell
  // pins: pad number → the name on the symbol
  const LIB = {
    res: { sym: 'res', fp: 'R_AX10', pre: 'R', pins: { 1: '1', 2: '2' }, te: 'రెసిస్టర్',
      what: 'కరెంట్‌ని తగ్గించే చిన్న భాగం. నీళ్ళ పైపులో సన్నని చోటు లాంటిది: ఎంత ఎక్కువ ఓమ్స్ (Ω) అయితే అంత తక్కువ కరెంట్ వెళ్తుంది.',
      how: 'దిశ లేదు: ఏ వైపు పెట్టినా సరే. విలువ రంగు గీతల మీద ఉంటుంది.' },
    cer: { sym: 'cap', fp: 'C_DISC5', pre: 'C', pins: { 1: '1', 2: '2' }, te: 'సిరామిక్ కెపాసిటర్',
      what: 'కొద్దిగా కరెంట్ దాచుకుని వెంటనే ఇచ్చే చిన్న బిళ్ళ. చిన్న శబ్దాలు, కుదుపులు (noise) తీసేస్తుంది.',
      how: 'దిశ లేదు. మీద 103 అంటే 10 nF, 104 అంటే 100 nF.' },
    elec: { sym: 'cpol', fp: 'CP_D5_P2', pre: 'C', pins: { 1: '+', 2: '-' }, te: 'ఎలక్ట్రోలైటిక్ కెపాసిటర్',
      what: 'కరెంట్ దాచుకునే చిన్న ట్యాంక్. సప్లై కుదుపుల్ని సరిచేస్తుంది, టైమర్‌లో సమయం లెక్కిస్తుంది.',
      how: '⚠️ దిశ ఉంది: పొడవైన కాలు + (చతురస్ర ప్యాడ్), తెల్ల గీత ఉన్న వైపు −. తిరగేస్తే ఉబ్బి పేలవచ్చు. వోల్టేజ్ సప్లై కంటే ఎక్కువ ఉండాలి.', polar: true },
    led5: { sym: 'led', fp: 'LED_5MM', pre: 'D', pins: { 1: 'K', 2: 'A' }, te: 'LED (5 mm)',
      what: 'కరెంట్ వెళ్తే వెలిగే చిన్న బల్బ్. ఒక్కటే దిశలో పనిచేస్తుంది. ఎప్పుడూ రెసిస్టర్‌తో కలిపి పెట్టాలి.',
      how: '⚠️ పొడవైన కాలు + (A, గుండ్రటి ప్యాడ్). చదునుగా ఉన్న అంచు, చిన్న కాలు − (K, చతురస్ర ప్యాడ్).', polar: true },
    led3: { sym: 'led', fp: 'LED_3MM', pre: 'D', pins: { 1: 'K', 2: 'A' }, te: 'LED (3 mm)',
      what: 'చిన్న సూచిక బల్బ్: "పవర్ ఉంది" అని చూపిస్తుంది.', how: '⚠️ పొడవైన కాలు + (A). చిన్న కాలు − (K, చతురస్ర ప్యాడ్).', polar: true },
    irled: { sym: 'led', fp: 'LED_5MM', pre: 'D', pins: { 1: 'K', 2: 'A' }, te: 'IR LED (940 nm)',
      what: 'TV రిమోట్‌లో ఉండే కనిపించని వెలుగు బల్బ్. దీనితో TV / AC కి ఆదేశాలు పంపుతాం. (ఫోన్ కెమెరాలో ఊదా రంగులో కనిపిస్తుంది.)',
      how: '⚠️ పొడవైన కాలు + (A). చిన్న కాలు − (K, చతురస్ర ప్యాడ్).', polar: true },
    d4007: { sym: 'diode', fp: 'D_DO41', pre: 'D', pins: { 1: 'K', 2: 'A' }, te: 'డయోడ్ 1N4007',
      what: 'కరెంట్‌ని ఒక్క దిశలోనే వెళ్ళనిచ్చే ఒన్-వే గేట్. బ్యాటరీ తిరగేసి పెట్టినా సర్క్యూట్ కాలిపోకుండా కాపాడుతుంది.',
      how: '⚠️ తెల్ల/వెండి గీత ఉన్న వైపు K (−), చతురస్ర ప్యాడ్ వైపు.', polar: true },
    d5822: { sym: 'diode', fp: 'D_DO201', pre: 'D', pins: { 1: 'K', 2: 'A' }, te: 'షాట్కీ డయోడ్ 1N5822 (3 A)',
      what: 'పెద్ద కరెంట్ (3 A వరకు) కోసం ఒన్-వే గేట్. మామూలు డయోడ్ కంటే తక్కువ వోల్టేజ్ పోతుంది (సుమారు 0.4 V), తక్కువ వేడి.',
      how: '⚠️ గీత ఉన్న వైపు K (−), చతురస్ర ప్యాడ్ వైపు. కాళ్ళు లావుగా ఉంటాయి.', polar: true },
    bc547: { sym: 'npn', fp: 'TO92', pre: 'Q', pins: { 1: 'C', 2: 'B', 3: 'E' }, te: 'ట్రాన్సిస్టర్ BC547 (NPN)',
      what: 'చిన్న కరెంట్‌తో పెద్ద కరెంట్‌ని ఆన్/ఆఫ్ చేసే ఎలక్ట్రానిక్ స్విచ్. B కాలికి కొద్దిగా కరెంట్ ఇస్తే C నుంచి E కి కరెంట్ వెళ్తుంది.',
      how: '⚠️ చదునైన ముఖం మీ వైపు, కాళ్ళు కిందికి: ఎడమ నుంచి C, B, E (BC547 కి). వేరే ట్రాన్సిస్టర్ (2N2222, S8050) పెడితే వరుస మారుతుంది.', polar: true },
    ne555: { sym: 'box', fp: 'DIP8', pre: 'U', pins: { 1: 'GND', 2: 'TRIG', 3: 'OUT', 4: 'RESET', 5: 'CTRL', 6: 'THR', 7: 'DIS', 8: 'VCC' },
      box: { L: ['7', '6', '2'], R: ['3'], T: ['4', '8'], B: ['1', '5'] }, te: 'టైమర్ IC NE555',
      what: 'ప్రపంచంలో ఎక్కువగా వాడే టైమర్ చిప్. రెసిస్టర్లు, కెపాసిటర్ బట్టి ఆన్-ఆఫ్-ఆన్-ఆఫ్ అని టిక్ టిక్ చేస్తుంది (LED మెరవడం, బజర్ శబ్దం).',
      how: '⚠️ చిప్ మీద చిన్న గుంత / గీత ఉన్న వైపు పిన్ 1. IC సాకెట్ (8 పిన్) పెట్టి దానిలో IC పెడితే సులభం, వేడి తగలదు.', polar: true },
    r7805: { sym: 'box', fp: 'TO220_V', pre: 'U', pins: { 1: 'IN', 2: 'GND', 3: 'OUT' }, box: { L: ['1'], R: ['3'], B: ['2'] }, te: '5V రెగ్యులేటర్ 7805',
      what: 'ఎక్కువ వోల్టేజ్ (7–20 V) ని స్థిరమైన 5 V గా మార్చే చిప్. మిగిలిన వోల్టేజ్ వేడిగా పోతుంది.',
      how: '⚠️ అక్షరాలు మీ వైపు: ఎడమ IN, మధ్య GND, కుడి OUT. 0.3 A కంటే ఎక్కువ అయితే హీట్‌సింక్ కావాలి.', polar: true },
    term2: { sym: 'conn', fp: 'TERM2_508', pre: 'J', pins: { 1: '1', 2: '2' }, te: 'స్క్రూ టెర్మినల్ (2 పిన్)',
      what: 'వైర్లు స్క్రూతో బిగించే చోటు. బ్యాటరీ / అడాప్టర్ / సెన్సార్ వైర్లు ఇక్కడ పెడతాం, టంకం (solder) అక్కర్లేదు.',
      how: 'వైరు వెళ్ళే రంధ్రాలు బోర్డ్ అంచు వైపు ఉండాలి. + − గుర్తులు బోర్డ్ మీద చూసి వైరు పెట్టండి.' },
    hdr: { sym: 'conn', fp: 'HDR1x4', pre: 'J', pins: null, te: 'పిన్ హెడర్',
      what: '2.54 mm దూరంలో వరుసగా ఉండే పిన్నులు. జంపర్ వైర్లతో మాడ్యూల్స్‌ని కలపడానికి.',
      how: 'పిన్ 1 చతురస్ర ప్యాడ్. కింది పట్టికలోని పిన్ పేర్లే బోర్డ్ మీద రాసి ఉంటాయి: వాటిని చూసి వైర్లు కలపండి.' },
    fhdr: { sym: 'conn', fp: 'FHDR1x3', pre: 'J', pins: null, te: 'ఫిమేల్ హెడర్ (సాకెట్)',
      what: 'మాడ్యూల్ / సెన్సార్ పిన్నులు గుచ్చే సాకెట్. తీసి మళ్ళీ పెట్టొచ్చు.',
      how: '⚠️ కింది పట్టికలోని పిన్ పేర్లే బోర్డ్ మీద రాసి ఉంటాయి. మాడ్యూల్ ముందు కొని, దాని మీది పిన్ పేర్లు, వాటి వరుస, అది కోరే వోల్టేజ్ ఈ పట్టికతో సరిచూడండి. తేడా ఉంటే PCB ఆర్డర్ ముందే ✏️ మార్పు లో చెప్పండి.' },
    buck: { sym: 'box', fp: 'HDR1x4', pre: 'J', pins: { 1: 'IN+', 2: 'IN-', 3: 'OUT-', 4: 'OUT+' }, box: { L: ['1', '2'], R: ['4', '3'] }, te: 'బక్ మాడ్యూల్ కనెక్షన్ (12V → 5V)',
      what: '12 V ని 5 V గా మార్చే చిన్న రెడీమేడ్ బోర్డ్ (LM2596 / XL4015 / MP1584 మాడ్యూల్) ఇక్కడ కలుస్తుంది. వేడి తక్కువ, 2–3 A వరకు ఇస్తుంది.',
      how: '⚠️ ముందు మాడ్యూల్ స్క్రూ తిప్పి మల్టీమీటర్‌తో 5.1 V కి సెట్ చేయండి, తర్వాతే కలపండి. IN+ IN− OUT− OUT+ పేర్లు సరిపోల్చండి.', polar: true },
    buzzer: { sym: 'buzzer', fp: 'BUZZER_12', pre: 'BZ', pins: { 1: '+', 2: '-' }, te: 'బజర్ (ఆక్టివ్, 5–12 V)',
      what: 'కరెంట్ వస్తే "బీప్" అని శబ్దం చేసే చిన్న డబ్బా. లోపలే ఆసిలేటర్ ఉంటుంది.', how: '⚠️ + గుర్తు / పొడవైన కాలు + వైపు.', polar: true },
    ptc: { sym: 'fuse', fp: 'PTC_RAD5', pre: 'F', pins: { 1: '1', 2: '2' }, te: 'PTC ఫ్యూజ్ (తనంతట తానే తిరిగి వచ్చే ఫ్యూజ్)',
      what: 'ఎక్కువ కరెంట్ వస్తే వేడెక్కి కరెంట్ ఆపుతుంది; చల్లారాక మళ్ళీ పనిచేస్తుంది. మార్చక్కర్లేని ఫ్యూజ్.', how: 'దిశ లేదు.' },
    usba: { sym: 'box', fp: 'USB_A_TH', pre: 'J', pins: { 1: 'VBUS', 2: 'D-', 3: 'D+', 4: 'GND', S1: 'SH', S2: 'SH' }, box: { L: ['1', '2', '3', '4'], B: ['S1', 'S2'] }, te: 'USB-A సాకెట్',
      what: 'ఫోన్ ఛార్జింగ్ కేబుల్ గుచ్చే సాకెట్. VBUS = 5 V, GND = −.', how: 'బోర్డ్ అంచు దగ్గర, నోరు బయటికి. పక్కన ఉన్న రెండు లావు కాళ్ళు కూడా టంకం చేయండి (బలం కోసం).' },
    esp32: { sym: 'box', fp: 'ESP32_DEVKIT30_2540', pre: 'U',
      pins: { 1: 'EN', 2: 'VP', 3: 'VN', 4: 'D34', 5: 'D35', 6: 'D32', 7: 'D33', 8: 'D25', 9: 'D26', 10: 'D27', 11: 'D14', 12: 'D12', 13: 'D13', 14: 'GND', 15: 'VIN',
        16: 'D23', 17: 'D22', 18: 'TX0', 19: 'RX0', 20: 'D21', 21: 'D19', 22: 'D18', 23: 'D5', 24: 'TX2', 25: 'RX2', 26: 'D4', 27: 'D2', 28: 'D15', 29: 'GND', 30: '3V3' },
      box: { L: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15'], R: ['16', '17', '18', '19', '20', '21', '22', '23', '24', '25', '26', '27', '28', '29', '30'] },
      te: 'ESP32 DevKit (30 పిన్)', what: 'Wi-Fi, బ్లూటూత్ ఉన్న చిన్న కంప్యూటర్ బోర్డ్. సెన్సార్లు చదివి ఫోన్‌కి / ఇంటర్నెట్‌కి పంపుతుంది. Jarvis ఇంటి పనులకి మెదడు.',
      how: 'నేరుగా టంకం చేయకండి: రెండు 15-పిన్ ఫిమేల్ హెడర్లు టంకం చేసి, ESP32 ని వాటిలో గుచ్చండి. యాంటెనా బోర్డ్ బయటి వైపు.' },
    r3v3: { sym: 'box', fp: 'TO220_V', pre: 'U', pins: { 1: 'GND', 2: 'OUT', 3: 'IN' }, box: { L: ['3'], R: ['2'], B: ['1'] }, te: '3.3V రెగ్యులేటర్ LD1117V33',
      what: '5–12 V ని స్థిరమైన 3.3 V గా మార్చే చిప్ (0.8 A వరకు). 3.3 V మీద నడిచే మాడ్యూల్స్‌కి (బ్లూటూత్, సెన్సార్లు, స్క్రీన్లు): వాటికి 5 V ఇస్తే కాలిపోతాయి.',
      how: '⚠️ పిన్నులు 7805 లా కాదు! అక్షరాలు మీ వైపు: ఎడమ GND, మధ్య OUT, కుడి IN. IN 15 V కంటే మించకూడదు. OUT దగ్గర కనీసం 10 µF కెపాసిటర్ తప్పకుండా ఉండాలి.', polar: true },
    slide: { sym: 'spdt', fp: 'SW_SLIDE_SPDT', pre: 'SW', pins: { 1: 'A', 2: 'C', 3: 'B' }, te: 'స్లైడ్ స్విచ్ (SS12D00)',
      what: 'జరిపితే కరెంట్ దారి మార్చే చిన్న స్విచ్. మధ్య కాలు (C) ఒకసారి ఒక పక్క కాలుకి (A), ఇంకోసారి ఇంకో పక్క కాలుకి (B) కలుస్తుంది. ఆన్ / ఆఫ్ కి, లేదా రెండు మోడ్‌లకి.',
      how: 'దిశ లేదు. మధ్య కాలు C. ఆన్ / ఆఫ్ కి C, A మాత్రమే వాడతాం. చిన్న స్విచ్: 0.3 A వరకే (మోటార్ / పెద్ద లైట్ కి కాదు).' },
    jack35: { sym: 'box', fp: 'JACK35_SJ1', pre: 'J', pins: { T: 'L', R: 'R', S: 'GND' }, box: { L: ['T', 'R', 'S'] }, te: '3.5mm ఆడియో జాక్ (స్టీరియో)',
      what: 'హెడ్‌ఫోన్ / AUX కేబుల్ గుచ్చే రంధ్రం. ప్లగ్ చివరి కొన (T) ఎడమ శబ్దం, మధ్య ఉంగరం (R) కుడి శబ్దం, వెనక పెద్ద భాగం (S) GND.',
      how: 'బోర్డ్ అంచు దగ్గర, నోరు బయటికి. మూడు కాళ్ళూ టంకం చేయండి (బలం కోసం కూడా).' },
    mic: { sym: 'mic', fp: 'MIC_D9.7', pre: 'MK', pins: { 1: '+', 2: '-' }, te: 'మైక్ (ఎలక్ట్రెట్, 9.7 mm)',
      what: 'మాటని చిన్న కరెంట్ మార్పుగా మార్చే చిన్న మైక్. పనిచేయడానికి 2.2–10 kΩ రెసిస్టర్ ద్వారా కొద్దిగా కరెంట్ (bias) ఇవ్వాలి.',
      how: '⚠️ దిశ ఉంది: కింద చూస్తే ఒక కాలు లోహపు డబ్బాకి కలిసి ఉంటుంది: అది − (GND). రెండోది +.', polar: true },
    // 25 Nov: power sockets, a button, a knob, a MOSFET switch, an 18650 holder, and two modules joined by wires (like the buck)
    dcjack: { sym: 'box', fp: 'DCJACK_DC005', pre: 'J', pins: { 1: '+', 2: '-', 3: 'SW' }, box: { L: ['1', '2', '3'] }, te: 'DC పవర్ జాక్ (5.5 × 2.1 mm)',
      what: 'అడాప్టర్ పిన్ గుచ్చే గుండ్రటి సాకెట్ (రూటర్ / స్పీకర్ అడాప్టర్ లాంటిది). మధ్య పిన్ (1) సాధారణంగా +, బయటి గొట్టం (2) −. మూడో కాలు (SW) ప్లగ్ పెట్టినప్పుడు విడిపోయే చిన్న స్విచ్: మామూలుగా వాడం.',
      how: 'బోర్డ్ అంచున, నోరు బయటికి. ⚠️ అడాప్టర్ మీది ⊖–◉–⊕ గుర్తు చూడండి: మధ్య + ఉన్నదే వాడండి. 3 కాళ్ళూ టంకం (బలం కోసం).', polar: true },
    usbc: { sym: 'box', fp: 'USBC_MOD', pre: 'J', pins: { 1: 'VBUS', 2: 'GND' }, box: { L: ['1', '2'] }, te: 'USB Type-C పవర్ బోర్డ్ (మాడ్యూల్)',
      what: 'ఫోన్ ఛార్జర్ కేబుల్ (Type-C) గుచ్చే చిన్న రెడీమేడ్ బోర్డ్. దాని నుంచి 5 V (VBUS), GND ఈ బోర్డ్‌కి వస్తాయి.',
      how: '⚠️ "5.1kΩ" (CC రెసిస్టర్లు) ఉన్న Type-C బోర్డ్ కొనండి: అవి లేకపోతే C-to-C కేబుల్ / కొత్త ఛార్జర్లు కరెంట్ ఇవ్వవు. దాని మీది VBUS, GND పేర్లు చూసి, రెండు పిన్నులతో ఈ రంధ్రాల మీద నిలబెట్టి టంకం చేయండి. నోరు బోర్డ్ అంచు బయటికి.', polar: true },
    btn: { sym: 'btn', fp: 'BTN_6MM', pre: 'SW', pins: { 1: '1', 2: '2' }, te: 'పుష్ బటన్ (6 × 6 mm)',
      what: 'నొక్కినంత సేపు 1, 2 కలుస్తాయి; వదిలితే విడిపోతాయి. ESP32 / 555 కి "నొక్కారు" అని చెప్పడానికి. ఒకే వైపు ఉన్న రెండు కాళ్ళు బటన్ లోపల కలిసే ఉంటాయి.',
      how: 'నాలుగు కాళ్ళూ రంధ్రాల్లో పడేలా నొక్కి పెట్టండి. చిన్న కరెంట్‌కే (50 mA వరకు): లైట్ / మోటార్‌ని నేరుగా ఆన్ చేయడానికి కాదు.' },
    pot: { sym: 'pot', fp: 'POT_RV09', pre: 'RV', pins: { 1: '1', 2: 'W', 3: '3' }, te: 'నాబ్ (పొటెన్షియోమీటర్ RV09)',
      what: 'తిప్పితే విలువ మారే రెసిస్టర్. రెండు చివర్ల (1, 3) మధ్య మొత్తం విలువ (ఉదా: 10k); మధ్య కాలు W తిప్పినట్టు 1 నుంచి 3 వైపు జరుగుతుంది. వాల్యూమ్, వేగం, వెలుగు మార్చడానికి.',
      how: 'నాబ్ పైకి. పక్కన ఉన్న రెండు లోహపు పట్టీలు కూడా టంకం చేయండి (తిప్పేటప్పుడు ఊడకుండా). వాల్యూమ్‌కి: 1 → GND, 3 → పాట లోపలికి, W → బయటికి.' },
    nmos: { sym: 'nmos', fp: 'TO220_V', pre: 'Q', pins: { 1: 'G', 2: 'D', 3: 'S' }, te: 'MOSFET IRLZ44N (కరెంట్ స్విచ్)',
      what: 'చిన్న సిగ్నల్‌తో (ESP32 / 555 పిన్ 3.3–5 V) పెద్ద కరెంట్ (పంప్, మోటార్, LED స్ట్రిప్) ఆన్ / ఆఫ్ చేసే స్విచ్. G కి వోల్టేజ్ వస్తే D–S కలుస్తాయి.',
      how: '⚠️ అక్షరాలు మీ వైపు: ఎడమ G, మధ్య D, కుడి S. G కి 100Ω, G నుంచి GND కి 10k తప్పకుండా. పంప్ / మోటార్ అయితే దాని రెండు వైర్ల మధ్య డయోడ్ (గీత + వైపు).', polar: true },
    bat18650: { sym: 'cell', fp: 'BAT18650', pre: 'BT', pins: { 1: '+', 2: '-' }, te: '18650 బ్యాటరీ హోల్డర్',
      what: 'ఒక 18650 లిథియం బ్యాటరీ (3.7 V; పూర్తిగా నిండితే 4.2 V, ఖాళీ 3.0 V) పెట్టే హోల్డర్.',
      how: '⚠️ హోల్డర్ మీది +, − గుర్తులు బోర్డ్ మీది గుర్తులతో సరిపోవాలి. బ్యాటరీ తిరగేస్తే షార్ట్: ప్రొటెక్షన్ ఉన్న TP4056 తో మాత్రమే వాడండి. ఉబ్బిన / దెబ్బతిన్న బ్యాటరీ వాడకండి.', polar: true },
    pam8403: { sym: 'box', fp: 'HDR1x5', pre: 'J', pins: { 1: '+5V', 2: 'GND', 3: 'L', 4: 'G', 5: 'R' }, box: { L: ['3', '4', '5'], R: ['1', '2'] }, te: 'PAM8403 ఆంప్ మాడ్యూల్ కనెక్షన్ (2 × 3 W)',
      what: 'చిన్న స్పీకర్లు (4–8 Ω, 3 W వరకు) నేరుగా మోగించే రెడీమేడ్ ఆంప్ బోర్డ్. 5 V మీద నడుస్తుంది; పాట (L, G, R) లోపలికి, స్పీకర్ వైర్లు ఆంప్ మీది L+ L− R+ R− కి నేరుగా.',
      how: '⚠️ స్పీకర్ వైర్లు (L+ L− R+ R−) ఏవీ GND కి కలపకండి: ఆంప్ కాలిపోతుంది. పెద్ద శబ్దంలో 1 A వరకు తీసుకుంటుంది: 2 A సప్లై మంచిది. మాడ్యూల్ మీది పిన్ పేర్లు చూసి వైర్లు కలపండి.', polar: true },
    tp4056: { sym: 'box', fp: 'HDR1x6', pre: 'J', pins: { 1: 'IN+', 2: 'IN-', 3: 'B+', 4: 'B-', 5: 'OUT+', 6: 'OUT-' }, box: { L: ['1', '2', '3', '4'], R: ['5', '6'] }, te: 'TP4056 ఛార్జర్ మాడ్యూల్ కనెక్షన్ (ప్రొటెక్షన్‌తో)',
      what: 'ఒక లిథియం బ్యాటరీ (18650) ని దాని Type-C నుంచి 1 A తో ఛార్జ్ చేసే రెడీమేడ్ బోర్డ్. B+ B− బ్యాటరీకి, OUT+ OUT− బోర్డ్‌కి: బ్యాటరీ 2.4 V కంటే తగ్గినా, షార్ట్ అయినా OUT కట్ చేస్తుంది.',
      how: '⚠️ OUT+ OUT− ఉన్న (6 ప్యాడ్‌లు, DW01 ప్రొటెక్షన్) మాడ్యూల్ కొనండి. బోర్డ్ GND ఎప్పుడూ OUT− కే, B− కి కాదు. B+ బ్యాటరీ +, B− బ్యాటరీ −: తిరగేస్తే కాలిపోతుంది. ఛార్జింగ్ దాని సొంత Type-C నుంచే (IN+ IN− మామూలుగా వాడం). ఛార్జ్ అవుతున్నప్పుడు వేడెక్కుతుంది.', polar: true },
    vs1838: { sym: 'box', fp: 'IR3', pre: 'U', pins: { 1: 'OUT', 2: 'GND', 3: 'VCC' }, box: { L: ['3', '1', '2'] }, te: 'IR రిసీవర్ VS1838B',
      what: 'TV రిమోట్ వెలుగుని పట్టుకునే కన్ను. రిమోట్ బటన్‌లు నేర్చుకోవడానికి.', how: '⚠️ గుండ్రటి కన్ను మీ వైపు: చాలా వాటికి ఎడమ నుంచి OUT, GND, VCC. కొన్నిటికి వేరేగా ఉంటుంది: కొన్న భాగం చూడండి.', polar: true },

    // ---- 26 Nov: 📚 the module library. plug: the module sits in a socket on this board; wire: it stays beside the board and jumper
    // wires (or its own cable) join its pins to a male header here. mod: the volts it wants, the mA it takes (for ⚡), out5: pins that
    // give 5 V out when it runs on 5 V (an ESP32 pin must not get them directly). vars: the pin orders sellers' boards come in.
    hc05: { sym: 'box', fp: 'MOD_HC05', pre: 'U', kind: 'plug', pins: { 1: 'EN', 2: 'VCC', 3: 'GND', 4: 'TXD', 5: 'RXD', 6: 'STATE' }, box: { L: ['4', '5', '6', '1'], T: ['2'], B: ['3'] },
      te: 'HC-05 బ్లూటూత్ మాడ్యూల్ (సీరియల్)', mod: { v: [3.6, 6], ma: 40, te: 'HC-05 బ్లూటూత్' },
      what: 'ఫోన్ నుంచి బ్లూటూత్‌తో అక్షరాలు (ఆదేశాలు) పంపడానికి / తీసుకోవడానికి. ESP32 కి సొంత బ్లూటూత్ ఉంది: ఇది సాధారణంగా Arduino లాంటి బోర్డ్‌లకి. జత కలిపే PIN సాధారణంగా 1234.',
      how: '⚠️ VCC కి 5 V (3.6–6 V), కానీ దాని TXD / RXD 3.3 V వి. TXD → మైక్రోకంట్రోలర్ RX, RXD ← మైక్రోకంట్రోలర్ TX (అడ్డంగా). 5 V బోర్డ్ TX నుంచి RXD కి 1k + 2k డివైడర్ పెట్టాలి (ESP32 అయితే అక్కర్లేదు). STATE జత కలిస్తే HIGH.', polar: true },
    oled: { sym: 'box', fp: 'MOD_OLED096', pre: 'U', kind: 'plug', pins: { 1: 'GND', 2: 'VCC', 3: 'SCL', 4: 'SDA' }, box: { L: ['3', '4'], T: ['2'], B: ['1'] },
      vars: [{ te: 'GND · VCC · SCL · SDA (ఎక్కువగా దొరికేది)', pins: { 1: 'GND', 2: 'VCC', 3: 'SCL', 4: 'SDA' } },
        { te: 'VCC · GND · SCL · SDA (కొన్ని బోర్డ్‌లు, SH1106)', pins: { 1: 'VCC', 2: 'GND', 3: 'SCL', 4: 'SDA' }, box: { L: ['3', '4'], T: ['1'], B: ['2'] } }],
      te: 'OLED స్క్రీన్ 0.96" (I2C, 4 పిన్)', mod: { v: [3.3, 5.5], ma: 20, te: 'OLED స్క్రీన్' },
      what: 'అక్షరాలు, అంకెలు, చిన్న బొమ్మలు చూపించే చిన్న స్క్రీన్ (128 × 64). రెండు వైర్ల I2C (SCL, SDA) తో మాట్లాడుతుంది; చిరునామా సాధారణంగా 0x3C.',
      how: '⚠️ రెండు రకాల బోర్డ్‌లు ఉన్నాయి: కొన్నిటికి GND మొదట, కొన్నిటికి VCC మొదట. తిరగేస్తే స్క్రీన్ కాలిపోతుంది. కొన్న స్క్రీన్ మీది పేర్లు చూసి, అవసరమైతే కింద 🔁 పిన్ వరుస మార్చండి. ESP32 కి SDA = GPIO21, SCL = GPIO22.', polar: true },
    dht11: { sym: 'box', fp: 'MOD_DHT', pre: 'U', kind: 'plug', pins: { 1: 'VCC', 2: 'DATA', 3: 'GND' }, box: { L: ['2'], T: ['1'], B: ['3'] },
      vars: [{ te: '+ · OUT · − (VCC, DATA, GND)', pins: { 1: 'VCC', 2: 'DATA', 3: 'GND' } }, { te: 'S · + · − (DATA, VCC, GND)', pins: { 1: 'DATA', 2: 'VCC', 3: 'GND' }, box: { L: ['1'], T: ['2'], B: ['3'] } }],
      te: 'DHT11 / DHT22 మాడ్యూల్ (ఉష్ణోగ్రత, తేమ)', mod: { v: [3.3, 5.5], ma: 1.5, te: 'DHT సెన్సార్' },
      what: 'గదిలో ఉష్ణోగ్రత (°C), గాలి తేమ (%) చెప్పే సెన్సార్. DHT11 నీలం (చౌక), DHT22 తెలుపు (ఎక్కువ ఖచ్చితం). ఒక్క DATA వైరుతో మాట్లాడుతుంది.',
      how: '⚠️ మాడ్యూల్‌లను బట్టి పిన్ వరుస మారుతుంది ("+ OUT −" లేదా "S + −"). కొన్నదాని మీది గుర్తులు చూసి, అవసరమైతే 🔁 పిన్ వరుస మార్చండి. మాడ్యూల్ మీదే 10k రెసిస్టర్ ఉంటుంది: బయట అక్కర్లేదు. రెండు సెకన్లకి ఒకసారే చదవాలి.', polar: true },
    ds3231: { sym: 'box', fp: 'MOD_DS3231', pre: 'U', kind: 'plug', pins: { 1: '32K', 2: 'SQW', 3: 'SCL', 4: 'SDA', 5: 'VCC', 6: 'GND' }, box: { L: ['3', '4', '2', '1'], T: ['5'], B: ['6'] },
      te: 'DS3231 గడియారం మాడ్యూల్ (RTC)', mod: { v: [3.3, 5.5], ma: 1, te: 'DS3231 గడియారం' },
      what: 'కరెంట్ పోయినా సమయం, తేదీ గుర్తుంచుకునే గడియారం (వెనక చిన్న బటన్ బ్యాటరీ). నీళ్ళు పోసే టైమ్, అలారం లాంటి వాటికి. I2C (SCL, SDA), చిరునామా 0x68.',
      how: '⚠️ ZS-042 బోర్డ్ బ్యాటరీని ఛార్జ్ చేయడానికి చూస్తుంది: మామూలు CR2032 (ఛార్జ్ కానిది) వేస్తే 3.3 V మీద నడపండి (లేదా LIR2032 వాడండి). 32K, SQW మామూలుగా వాడం.', polar: true },
    bme280: { sym: 'box', fp: 'MOD_BME280', pre: 'U', kind: 'plug', pins: { 1: 'VIN', 2: 'GND', 3: 'SCL', 4: 'SDA' }, box: { L: ['3', '4'], T: ['1'], B: ['2'] },
      te: 'BME280 / BMP280 సెన్సార్ (4 పిన్, I2C)', mod: { v: [3.3, 3.6], ma: 1, te: 'BME280 సెన్సార్', why: 'BME280 కి 3.3 V ఇవ్వడం సురక్షితం (5 V కి కొన్ని బోర్డ్‌లే తట్టుకుంటాయి)' },
      what: 'ఉష్ణోగ్రత, గాలి ఒత్తిడి (వర్షం వచ్చే సూచన), ఎత్తు చెప్పే చిన్న సెన్సార్. BME280 తేమ కూడా చెప్తుంది, BMP280 చెప్పదు. I2C, చిరునామా 0x76 లేదా 0x77.',
      how: '⚠️ VIN కి 3.3 V ఇవ్వండి (ESP32 3V3 పిన్ నుంచి). 6 పిన్నుల (CSB, SDO ఉన్న) బోర్డ్ ఇది కాదు: 4 పిన్నుల బోర్డ్ కొనండి.', polar: true },
    mpu6050: { sym: 'box', fp: 'MOD_MPU6050', pre: 'U', kind: 'plug', pins: { 1: 'VCC', 2: 'GND', 3: 'SCL', 4: 'SDA', 5: 'XDA', 6: 'XCL', 7: 'AD0', 8: 'INT' }, box: { L: ['3', '4', '8', '7', '5', '6'], T: ['1'], B: ['2'] },
      te: 'MPU6050 మాడ్యూల్ GY-521 (వంపు, కదలిక)', mod: { v: [3.3, 5.0], ma: 4, te: 'MPU6050' },
      what: 'బోర్డ్ ఎంత వంగిందో, ఎంత వేగంగా తిరిగిందో, కుదుపులు చెప్పే సెన్సార్ (బండి పడిపోవడం, బ్యాలెన్స్ రోబో). I2C, చిరునామా 0x68 (AD0 HIGH అయితే 0x69).',
      how: 'VCC కి 3.3–5 V (బోర్డ్ మీద సొంత 3.3 V రెగ్యులేటర్ ఉంది). XDA, XCL మామూలుగా వాడం. చిప్ పైకి కనిపించేలా సాకెట్‌లో పెట్టండి; బోర్డ్‌ని కదలకుండా బిగించండి.', polar: true },
    hcsr04: { sym: 'box', fp: 'MOD_HCSR04', pre: 'U', kind: 'plug', pins: { 1: 'VCC', 2: 'TRIG', 3: 'ECHO', 4: 'GND' }, box: { L: ['2', '3'], T: ['1'], B: ['4'] },
      te: 'HC-SR04 దూరం కొలిచే సెన్సార్ (అల్ట్రాసోనిక్)', mod: { v: [4.5, 5.5], ma: 15, te: 'HC-SR04', out5: ['ECHO'] },
      what: 'గబ్బిలంలా శబ్ద తరంగం పంపి, తిరిగి వచ్చే సమయంతో దూరం (2 cm – 4 m) కొలుస్తుంది. ట్యాంక్‌లో నీళ్ళ ఎత్తు, తలుపు దగ్గర ఎవరైనా వచ్చారా.',
      how: '⚠️ VCC కి 5 V కావాలి, ECHO నుంచి 5 V వస్తుంది: ESP32 కి నేరుగా ఇస్తే పిన్ పాడవుతుంది. ECHO → 1k → ESP32 పిన్, ఆ పిన్ → 2k → GND (డివైడర్). రెండు "కళ్ళు" బోర్డ్ అంచు బయటికి చూసేలా.', polar: true },
    max98357: { sym: 'box', fp: 'MOD_MAX98357', pre: 'U', kind: 'plug', pins: { 1: 'LRC', 2: 'BCLK', 3: 'DIN', 4: 'GAIN', 5: 'SD', 6: 'GND', 7: 'VIN' }, box: { L: ['1', '2', '3', '5', '4'], T: ['7'], B: ['6'] },
      te: 'MAX98357A I2S ఆంప్ మాడ్యూల్ (3 W)', mod: { v: [2.5, 5.5], ma: 300, te: 'MAX98357A ఆంప్' },
      what: 'ESP32 నుంచి డిజిటల్ పాటని (I2S: LRC, BCLK, DIN) నేరుగా స్పీకర్ శబ్దంగా మార్చే ఆంప్. Jarvis స్పీకర్ లాంటి వాటికి. 5 V మీద 4 Ω స్పీకర్‌కి 3 W.',
      how: '⚠️ స్పీకర్ వైర్లు దాని సొంత + − స్క్రూలకే, ఏదీ GND కి కాదు. పెద్ద శబ్దంలో 0.8 A వరకు: 5 V 2 A సప్లై. GAIN ఖాళీగా వదిలితే 9 dB. SD ఖాళీ = ఆన్.', polar: true },
    kcxbt003: { sym: 'box', fp: 'HDR1x4', pre: 'J', kind: 'wire', pins: { 1: 'VBAT', 2: 'GND', 3: 'DAC_L', 4: 'DAC_R' }, box: { L: ['3', '4'], T: ['1'], B: ['2'] },
      te: 'KCX_BT003 బ్లూటూత్ ఆడియో రిసీవర్ కనెక్షన్', mod: { v: [3.3, 5.0], ma: 35, te: 'KCX_BT003 బ్లూటూత్ రిసీవర్', why: 'KCX_BT003 కి 3.3–5.0 V' },
      what: 'ఫోన్ పాటని బ్లూటూత్‌తో తీసుకుని ఎడమ (DAC_L), కుడి (DAC_R) శబ్దంగా ఇచ్చే చిన్న మాడ్యూల్. పాత AUX స్పీకర్‌కి బ్లూటూత్ ఇవ్వడానికి.',
      how: '⚠️ ఈ మాడ్యూల్‌కి పిన్నులు ఉండవు, అంచున బంగారు ప్యాడ్‌లు: VBAT, GND, DAC_L, DAC_R ప్యాడ్‌లకి 4 సన్నని వైర్లు టంకం చేసి, వాటిని ఈ హెడర్‌కి కలపండి. ప్యాడ్ పేర్లు మాడ్యూల్ మీద / దాని ఫోటోలో చూసి సరిచూడండి.', polar: true },
    dfplayer: { sym: 'box', fp: 'HDR1x5', pre: 'J', kind: 'wire', pins: { 1: 'VCC', 2: 'GND', 3: 'RX', 4: 'TX', 5: 'BUSY' }, box: { L: ['3', '4', '5'], T: ['1'], B: ['2'] },
      te: 'DFPlayer Mini MP3 మాడ్యూల్ కనెక్షన్', mod: { v: [3.2, 5.0], ma: 80, te: 'DFPlayer MP3' },
      what: 'మెమరీ కార్డ్ (micro SD) లోని MP3 పాటలు, మాటలు ప్లే చేసే మాడ్యూల్. చిన్న స్పీకర్ (3 W లోపు) నేరుగా దాని SPK1, SPK2 కి. ESP32 RX / TX తో ఆదేశాలు.',
      how: '⚠️ RX ముందు 1k రెసిస్టర్ పెట్టండి (లేకపోతే "కీ" శబ్దం). TX → ESP32 RX, RX ← ESP32 TX. స్పీకర్ వైర్లు మాడ్యూల్ SPK1, SPK2 కే, GND కి కాదు. కార్డ్‌లో పాటలు 0001.mp3, 0002.mp3 లా పేరు పెట్టండి. (మాడ్యూల్‌ని బోర్డ్ మీదే గుచ్చే సాకెట్ ఇంకా పెట్టలేదు: వరుసల దూరం సరిగ్గా తెలిసే వరకు వైర్లతో.)', polar: true },
    lcd1602: { sym: 'box', fp: 'HDR1x4', pre: 'J', kind: 'wire', pins: { 1: 'GND', 2: 'VCC', 3: 'SDA', 4: 'SCL' }, box: { L: ['3', '4'], T: ['2'], B: ['1'] },
      te: '16×2 LCD స్క్రీన్ (I2C బోర్డ్‌తో) కనెక్షన్', mod: { v: [4.5, 5.5], ma: 25, te: 'LCD 16×2' },
      what: 'రెండు లైన్లు, ఒక్కో లైన్‌కి 16 అక్షరాలు చూపించే నీలం / ఆకుపచ్చ స్క్రీన్. వెనక చిన్న I2C బోర్డ్ (PCF8574) ఉంటే 4 వైర్లే. చిరునామా 0x27 లేదా 0x3F.',
      how: '⚠️ VCC కి 5 V. అక్షరాలు కనిపించకపోతే వెనక నీలం డబ్బాలో స్క్రూ (కాంట్రాస్ట్) తిప్పండి. ESP32 కి SDA = GPIO21, SCL = GPIO22.', polar: true },
    relay: { sym: 'box', fp: 'HDR1x3', pre: 'J', kind: 'wire', pins: { 1: 'VCC', 2: 'GND', 3: 'IN' }, box: { L: ['3'], T: ['1'], B: ['2'] },
      te: 'రిలే మాడ్యూల్ (1 ఛానెల్, 5 V) కనెక్షన్', mod: { v: [4.5, 5.5], ma: 75, te: 'రిలే మాడ్యూల్' },
      what: 'చిన్న సిగ్నల్‌తో లోపల ఒక స్విచ్ "టక్" అని మూసే మాడ్యూల్. ఈ స్టూడియోలో దాని కాంటాక్ట్‌లు (COM, NO, NC) తక్కువ వోల్టేజ్ DC (24 V వరకు) పంప్ / వాల్వ్ / లైట్ కి మాత్రమే.',
      how: '⚠️ ఇంటి 230 V కరెంట్ ఈ బోర్డ్‌కి / రిలేకి కలపకండి: అది లైసెన్స్ ఉన్న ఎలక్ట్రీషియన్ పని. చాలా మాడ్యూల్స్ IN = LOW అయితే ఆన్ అవుతాయి (కొన్ని HIGH కి): కొన్నదాని మీద చూడండి. మాడ్యూల్ మీది పేర్లు (VCC / DC+, GND / DC−, IN / S) చూసి వైర్లు కలపండి.', polar: true },
    soilcap: { sym: 'box', fp: 'HDR1x3', pre: 'J', kind: 'wire', pins: { 1: 'GND', 2: 'VCC', 3: 'AOUT' }, box: { L: ['3'], T: ['2'], B: ['1'] },
      te: 'మట్టి తేమ సెన్సార్ (capacitive v1.2) కనెక్షన్', mod: { v: [3.3, 5.5], ma: 5, te: 'మట్టి తేమ సెన్సార్' },
      what: 'కుండీ మట్టిలో గుచ్చే నల్లటి పట్టీ: మట్టి ఎంత తడిగా ఉందో AOUT వోల్టేజ్‌గా చెప్తుంది (పొడి = ఎక్కువ, తడి = తక్కువ). తుప్పు పట్టదు.',
      how: 'దానితో వచ్చే 3 వైర్ల కేబుల్ (GND, VCC, AOUT) ఈ హెడర్‌కి. AOUT → ESP32 ADC పిన్ (GPIO32–39). తెల్ల గీత పైన ఉన్న భాగం మట్టిలోకి వెళ్ళకూడదు (పైన ఎలక్ట్రానిక్స్ ఉంటాయి).', polar: true },
    pir: { sym: 'box', fp: 'HDR1x3', pre: 'J', kind: 'wire', pins: { 1: 'VCC', 2: 'OUT', 3: 'GND' }, box: { L: ['2'], T: ['1'], B: ['3'] },
      te: 'PIR కదలిక సెన్సార్ HC-SR501 కనెక్షన్', mod: { v: [4.5, 12], ma: 1, te: 'PIR సెన్సార్' },
      what: 'మనిషి / జంతువు వేడి కదిలితే OUT HIGH (3.3 V) చేసే తెల్ల గుమ్మటం సెన్సార్ (7 m వరకు). లైట్ ఆటోమేటిక్‌గా వెలగడానికి, ఎవరైనా వచ్చారని చెప్పడానికి.',
      how: 'VCC కి 5 V. OUT 3.3 V: ESP32 కి నేరుగా సరే. వెనక రెండు ఆరెంజ్ స్క్రూలు: ఒకటి దూరం, ఒకటి ఎంత సేపు ఆన్. ఆన్ చేశాక 1 నిమిషం సెటిల్ అవుతుంది. పిన్ పేర్లు గుమ్మటం తీసి చూడండి.', polar: true },
    rc522: { sym: 'box', fp: 'HDR1x8', pre: 'J', kind: 'wire', pins: { 1: 'SDA', 2: 'SCK', 3: 'MOSI', 4: 'MISO', 5: 'IRQ', 6: 'GND', 7: 'RST', 8: '3V3' }, box: { L: ['1', '2', '3', '4', '7', '5'], T: ['8'], B: ['6'] },
      te: 'RC522 RFID కార్డ్ రీడర్ కనెక్షన్', mod: { v: [3.0, 3.6], ma: 26, te: 'RC522 RFID', why: 'RC522 3.3 V మీదే: 5 V ఇస్తే కాలిపోతుంది' },
      what: 'కార్డ్ / కీచైన్ ట్యాగ్ దగ్గర పెడితే దాని నంబర్ చదివే రీడర్ (తాళం లేని తలుపు, హాజరు). SPI (SCK, MOSI, MISO, SDA) తో మాట్లాడుతుంది.',
      how: '⚠️ 3V3 పిన్‌కి 3.3 V మాత్రమే, 5 V ఎప్పుడూ కాదు. ESP32 కి: SCK GPIO18, MISO GPIO19, MOSI GPIO23, SDA GPIO5, RST GPIO22 (కోడ్‌లో మార్చొచ్చు). IRQ మామూలుగా వాడం.', polar: true },
    servo: { sym: 'box', fp: 'HDR1x3', pre: 'J', kind: 'wire', pins: { 1: 'GND', 2: '+5V', 3: 'SIG' }, box: { L: ['3'], T: ['2'], B: ['1'] },
      te: 'సర్వో మోటార్ SG90 కనెక్షన్', mod: { v: [4.8, 6.0], ma: 250, te: 'SG90 సర్వో' },
      what: 'చెప్పిన కోణానికి (0°–180°) తిరిగి ఆగే చిన్న మోటార్: తలుపు గొళ్ళెం, కెమెరా తిప్పడం, నీళ్ళ వాల్వ్. SIG పిన్‌కి PWM పల్స్.',
      how: '⚠️ సర్వో ప్లగ్ ఇలాగే గుచ్చండి: గోధుమ (GND) పిన్ 1 వైపు, ఎరుపు (+5V) మధ్య, నారింజ (SIG) చివర. కదిలేటప్పుడు 0.7 A వరకు తీసుకుంటుంది: ESP32 3V3 / USB నుంచి కాదు, సొంత 5 V 1 A సప్లై.', polar: true },
    ws2812: { sym: 'box', fp: 'HDR1x3', pre: 'J', kind: 'wire', pins: { 1: '5V', 2: 'DIN', 3: 'GND' }, box: { L: ['2'], T: ['1'], B: ['3'] },
      te: 'WS2812B RGB LED పట్టీ / రింగ్ కనెక్షన్', mod: { v: [4.5, 5.5], ma: 300, te: 'WS2812B LED లు' },
      what: 'ప్రతి LED కి సొంత చిప్: ఒక్క DIN వైరుతో ఏ LED ఏ రంగో చెప్పొచ్చు (Jarvis రింగ్ లాంటి వెలుగు). ఒక్కో LED పూర్తి తెలుపులో 60 mA.',
      how: '⚠️ పట్టీ మీది బాణం దిశలో DIN వైపు నుంచే కలపండి. DIN ముందు 330Ω, 5V–GND మధ్య 1000 µF కెపాసిటర్ మంచిది. 8 LED లు దాటితే సొంత 5 V 2 A సప్లై; ESP32 3.3 V డేటా చాలా పట్టీలకి సరిపోతుంది.', polar: true }
  };
  // (every module's first order is its own pins; a part saved with var = i uses vars[i])
  Object.keys(LIB).forEach((k) => { const L = LIB[k]; if (L.vars && !L.pins) L.pins = L.vars[0].pins; });

  /** Resistor colour bands of a value ("4.7k" → yellow violet red gold). */
  const BAND = ['#111', '#7b3f00', '#d0021b', '#f5a623', '#f8e71c', '#2e9e44', '#2f6fd6', '#8b4fd6', '#8c8c8c', '#f4f4f4'];
  const BAND_TE = ['నలుపు', 'గోధుమ', 'ఎరుపు', 'నారింజ', 'పసుపు', 'ఆకుపచ్చ', 'నీలం', 'ఊదా', 'బూడిద', 'తెలుపు'];
  function ohms(v) {
    const m = /^\s*([\d.]+)\s*([kKmM]?)\s*(?:Ω|ohm|R)?\s*$/.exec(String(v || ''));
    if (!m) return NaN;
    return parseFloat(m[1]) * (m[2] === 'k' || m[2] === 'K' ? 1e3 : m[2] === 'M' ? 1e6 : m[2] === 'm' ? 1e6 : 1);
  }
  function bands(v) {
    let o = ohms(v);
    if (!(o >= 1) || o >= 1e10) return null;
    let e = 0;
    while (o >= 100) { o /= 10; e++; }
    let d = Math.round(o * (o < 10 ? 10 : 1));
    if (o < 10) e--;
    if (d >= 100) { d = Math.round(d / 10); e++; }
    const d1 = Math.floor(d / 10), d2 = d % 10;
    const mult = e >= 0 ? { c: BAND[e], te: BAND_TE[e] } : e === -1 ? { c: '#c9a227', te: 'బంగారం' } : { c: '#c0c0c0', te: 'వెండి' };
    return [{ c: BAND[d1], te: BAND_TE[d1] }, { c: BAND[d2], te: BAND_TE[d2] }, mult, { c: '#c9a227', te: 'బంగారం (±5%)' }];
  }

  /** The pins of a part as {pad number: name} (headers name their pins 1…N unless the part names them). */
  function pinsOf(part) {
    const lib = LIB[part.lib];
    if (part.pins) return part.pins;
    if (lib && lib.vars && part.var > 0 && lib.vars[part.var]) return lib.vars[part.var].pins;
    if (lib && lib.pins) return lib.pins;
    const f = fp(part.fp || (lib && lib.fp)), out = {};
    if (f) f.pads.forEach((p) => { out[p.n] = p.n; });
    return out;
  }
  function fpOf(part) { const lib = LIB[part.lib]; return fp(part.fp || (lib && lib.fp)); }
  /** The 📚 library's modules (plug ones first), for lists. */
  const MODULES = Object.keys(LIB).filter((k) => LIB[k].mod).sort((a, b) => (LIB[a].kind === 'plug' ? 0 : 1) - (LIB[b].kind === 'plug' ? 0 : 1));

  /**
   * Another pin order for a module (his module's board is the other kind): the joins move with the pin NAMES (VCC stays on VCC),
   * so the pads under them change. Returns the nets touched, or null when nothing changes. The caller re-routes.
   */
  function setVariant(D, part, i) {
    const lib = LIB[part.lib];
    if (!lib || !lib.vars || !lib.vars[i] || (part.var | 0) === i || part.pins) return null;
    const from = pinsOf(part), to = lib.vars[i].pins, padOfName = {};
    Object.keys(to).forEach((k) => { padOfName[to[k]] = k; });
    const touched = new Set();
    D.nets.forEach((n) => {
      n.p = n.p.map((p) => {
        const [ref, k] = p.split('.');
        if (ref !== part.ref) return p;
        const nk = padOfName[from[k]];
        if (!nk || nk === k) return p;
        touched.add(n.n);
        return ref + '.' + nk;
      });
    });
    part.var = i;
    if (!i) delete part.var;
    return touched;
  }

  const api = { FP, LIB, MODULES, fp, fpOf, pinsOf, setVariant, bands, ohms, BAND_TE, SMD, smdOf, isSmd, resKey, capKey };
  root.PcbParts = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
