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
      what: 'TV రిమోట్ వెలుగుని పట్టుకునే కన్ను. రిమోట్ బటన్‌లు నేర్చుకోవడానికి.', how: '⚠️ గుండ్రటి కన్ను మీ వైపు: చాలా వాటికి ఎడమ నుంచి OUT, GND, VCC. కొన్నిటికి వేరేగా ఉంటుంది: కొన్న భాగం చూడండి.', polar: true }
  };

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
    if (lib && lib.pins) return lib.pins;
    const f = fp(part.fp || (lib && lib.fp)), out = {};
    if (f) f.pads.forEach((p) => { out[p.n] = p.n; });
    return out;
  }
  function fpOf(part) { const lib = LIB[part.lib]; return fp(part.fp || (lib && lib.fp)); }

  const api = { FP, LIB, fp, fpOf, pinsOf, bands, ohms, BAND_TE };
  root.PcbParts = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
