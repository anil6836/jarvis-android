// 🔌 Jarvis PCB studio — 🛒 where to buy the parts (Anil, 24 Nov: "A తో పాటు B కూడా చేయాలి").
// A: for every part, the words to search for in an Indian shop of his choice (works for any board, the search page is always fresh).
// B: for the parts of the ready boards, shop pages we looked up on 24 Nov 2026 (price / stock change; he checks the page).
// Nothing here buys anything: a link opens in his browser only when he taps it, and he buys and pays there himself.
'use strict';
(function (root) {
  const SHOPS = [['robu', 'Robu'], ['robocraze', 'Robocraze'], ['amazon', 'Amazon'], ['flipkart', 'Flipkart'], ['probots', 'Probots']];
  const CHECKED = '24 Nov 2026';
  const RC = 'https://robocraze.com/products/', RB = 'https://robu.in/product/';
  const rc = (slug, note) => ['Robocraze', RC + slug, note || ''];
  const rb = (slug, note) => ['Robu', RB + slug, note || ''];

  /** Shop pages per part kind (and value): [shop, url, what you get]. */
  const PAGES = {
    'ne555': [rc('555-timer-ic'), ['Robu', 'https://robu.in/?p=161997', '5 ప్యాక్']],
    'socket8': [rc('8-pin-ic-base')],
    'res:1k': [rc('1k-resistor-pack-of-10', '10 ప్యాక్'), rb('1k-ohm-0-25w-metal-film-resistor-pack-of-100/', '100 ప్యాక్')],
    'res:47k': [rc('47k-resistor-pack-of-10', '10 ప్యాక్'), rb('47k-ohm-0-25w-metal-film-resistor-pack-of-100/', '100 ప్యాక్')],
    'res:470': [rc('470-ohm-resistor-pack-of-10', '10 ప్యాక్')],
    'res:4.7k': [rc('4-7k-resistor-pack-of-10', '10 ప్యాక్')],
    'res:330': [rc('330-ohm-resistor-pack-of-10', '10 ప్యాక్')],
    'res:100': [rc('100-ohm-resistor-pack-of-10', '10 ప్యాక్')],
    'res:10k': [rc('10k-resistor-pack-of-10', '10 ప్యాక్')],
    'res:100k': [rc('100k-ohm-resistor', '10 ప్యాక్')],
    'elec:10uF': [rb('10-uf-25v-through-hole-electrolytic-capacitor-pack-of-40/', '25V, 40 ప్యాక్ (16V కంటే ఎక్కువ: సరే)')],
    'elec:100uF': [rc('100uf-25v-electrolytic-capacitor-pack-of-5', '25V, 5 ప్యాక్')],
    'cer:103': [rb('0-01uf-103-ceramic-capacitor-dip-pack-of-50/', '50 ప్యాక్')],
    'led5:RED': [rc('5mm-red-led-pack-of-11', '10 ప్యాక్')],
    'led5:GREEN': [rc('5mm-green-led-pack-of-10', '10 ప్యాక్'), rb('5mm-green-dip-led-pack-of-50/', '50 ప్యాక్')],
    'led5:BLUE': [rb('5mm-blue-dip-led-pack-of-50/', '50 ప్యాక్'), rc('5mm-white-blue-led-pack-of-10', 'తెల్ల లెన్స్, నీలం వెలుగు, 10 ప్యాక్')],
    'led3:GREEN': [rc('3mm-green-led-pack-of-10', '10 ప్యాక్'), rb('3mm-led-green-50pcs/', '50 ప్యాక్')],
    'irled': [rc('5mm-ir-transmitter-receiver-pair', 'ఒక జతలో పంపే IR LED ఒకటి + రిసీవర్ ఒకటి; 2 జతలు కొనండి, పంపేదే వాడండి')],
    'd5822': [rb('1n5822-stmicroelectronics-1n5822-schottky-rectifier-40-v-3-a-single-do-201ad-2-pins-525-mv/', 'ST, 3A'), rb('1n5822-1w-schottky-diode-pack-of-10/', '10 ప్యాక్')],
    'bc547': [rc('bc547-transistor-ic-pack-of-5', '5 ప్యాక్')],
    'term2': [rb('degson-2-pin-5-08mm-pcb-terminal-block/', '5.08 mm, PCB మీద టంకం చేసేది')],
    'clip9v': [rc('9v-battery-snap-connector-pack-of-5', '5 ప్యాక్')],
    'buck': [rc('lm2596-dc-dc-buck-module'), rb('lm2596s-dc-dc-buck-converter-power-supply/')],
    'ptc': [rb('rxef185-2-littelfuse-rxef185-2-pptc-resettable-fuse-1-85a-72v-th/', 'Littelfuse RXEF185')],
    'buzzer': [rb('tmb12a12-cldz-electromagnetic-active-integrated-buzzer-12v12x9-5mm/', '12V, 12 mm (9V మీద కూడా మోగుతుంది)')],
    'esp32': [rc('esp32-development-board-with-cp2102-wifi-bluetooth-dual-core-30-pin', '30 పిన్, CP2102')],
    'fhdr': [rc('40x1-female-berg-strip', '40 పిన్: కావాల్సినన్ని కట్ చేయండి')],
    'hdr': [rc('40x1-pin-2-54mm-pitch-male-berg-strip', '40 పిన్: కావాల్సినన్ని కట్ చేయండి'), rb('2-54mm-1x40-pin-male-single-row-straight-short-header-strip-pack-of-3/', '3 ప్యాక్')],
    'vs1838': [rc('ir-receiving-head-vs1838b-remote-control-receiver'), rb('ir-receiving-head-vs1838b-remote-control-receiver/', '5 ప్యాక్')],
    'dht11': [rc('dht11-humidity-temperature-sensor-module'), rb('dht11-digital-relative-humidity-temperature-sensor-module/')],
    'soilcap': [rc('analog-capacitive-soil-moisture-sensor', 'తుప్పు పట్టనిది (capacitive)')],
    'oled': [rc('0-96in-oled-display-module-4pin', '4 పిన్ I2C, SSD1306')]
  };

  /** Kits worth having for the next boards too. */
  const KITS = [
    { te: '🧰 రెసిస్టర్ కిట్', q: 'resistor kit 1/4 watt assorted', pages: [rc('resistor-box', '150 రెసిస్టర్లు, 30 విలువలు'), rb('820pcs-14w-1pct-41-kinds-each-value-20pcs-metal-film-resistors-kit-plastic-box/', '820, 41 విలువలు')] },
    { te: '💡 LED కిట్', q: '5mm LED kit assorted colours', pages: [rc('500pcs-5-mm-led-kit-5-colors-with-durable-storage-box', '500 LED లు, 5 రంగులు')] },
    { te: '🥫 కెపాసిటర్ కిట్', q: 'electrolytic capacitor kit assorted', pages: [rc('15-value-electrolytic-capacitor-kit-with-box-200pc', '200, 15 విలువలు')] }
  ];

  const clean = (s) => String(s || '').replace(/\(.*?\)/g, '').replace(/\s+/g, ' ').trim();
  function colour(pt) { return String(pt.color || pt.val || '').toUpperCase().replace(/[^A-Z]/g, ''); }

  /** The key of a part in PAGES (null = no page looked up: the search still works). */
  function key(pt) {
    const v = clean(pt.val);
    switch (pt.lib) {
      case 'res': return 'res:' + v.replace(/\s*(ohm|Ω|R)$/i, '');
      case 'elec': { const m = /^([\d.]+)\s*u/i.exec(v); return m ? 'elec:' + m[1] + 'uF' : null; }
      case 'cer': return /103/.test(pt.val) ? 'cer:103' : null;
      case 'led5': return 'led5:' + colour(pt);
      case 'led3': return 'led3:' + colour(pt);
      default: return pt.lib;
    }
  }
  /** What to type in a shop's search box for this part (English; any board, any value). */
  function query(pt) {
    const v = clean(pt.val);
    switch (pt.lib) {
      case 'res': return v.replace(/\s*(ohm|Ω)$/i, '') + ' ohm resistor 1/4 watt';
      case 'elec': return v + ' electrolytic capacitor';
      case 'cer': return (/103/.test(pt.val) ? '103' : v) + ' ceramic capacitor';
      case 'led5': return '5mm ' + colour(pt).toLowerCase() + ' LED';
      case 'led3': return '3mm ' + colour(pt).toLowerCase() + ' LED';
      case 'irled': return 'IR LED 5mm 940nm';
      case 'd4007': return '1N4007 diode';
      case 'd5822': return '1N5822 schottky diode';
      case 'bc547': return 'BC547 transistor';
      case 'ne555': return 'NE555 timer IC DIP 8';
      case 'r7805': return '7805 voltage regulator';
      case 'term2': return '2 pin screw terminal 5.08mm PCB';
      case 'hdr': return 'male berg strip 2.54mm';
      case 'fhdr': return 'female berg strip 2.54mm';
      case 'buck': return 'LM2596 buck converter module';
      case 'buzzer': return '12V active buzzer 12mm';
      case 'ptc': return 'PTC resettable fuse ' + (/\d/.test(v) ? v : '1.85A');
      case 'r3v3': return 'LD1117V33 3.3V regulator TO-220';
      case 'slide': return 'SS12D00 slide switch';
      case 'jack35': return 'CUI SJ1-3533NG 3.5mm stereo jack';
      case 'mic': return 'electret condenser microphone 9.7mm';
      case 'dcjack': return 'DC-005 DC power jack 5.5mm 2.1mm PCB';
      case 'usbc': return 'Type C female breakout board 5.1k';
      case 'btn': return '6x6 tactile push button 4 pin';
      case 'pot': return 'RV09 vertical potentiometer ' + (/\d/.test(v) ? v : '10K');
      case 'nmos': return 'IRLZ44N MOSFET';
      case 'bat18650': return '18650 battery holder PCB mount';
      case 'pam8403': return 'PAM8403 mini amplifier module 5V';
      case 'tp4056': return 'TP4056 Type C charging module with protection';
      case 'usba': return 'USB A female PCB 90 degree';
      case 'esp32': return 'ESP32 30 pin development board';
      case 'vs1838': return 'VS1838B IR receiver';
      // 📚 modules (26 Nov): search words only (no shop pages looked up for them yet)
      case 'hc05': return 'HC-05 Bluetooth module 6 pin';
      case 'oled': return '0.96 inch OLED I2C 4 pin SSD1306';
      case 'dht11': return /22/.test(v) ? 'DHT22 AM2302 module' : 'DHT11 temperature humidity sensor module';
      case 'ds3231': return 'DS3231 RTC module ZS-042';
      case 'bme280': return 'BME280 sensor module 4 pin I2C';
      case 'mpu6050': return 'MPU6050 GY-521 module';
      case 'hcsr04': return 'HC-SR04 ultrasonic sensor';
      case 'max98357': return 'MAX98357A I2S amplifier module';
      case 'kcxbt003': return 'KCX_BT003 Bluetooth audio receiver module';
      case 'dfplayer': return 'DFPlayer Mini MP3 module';
      case 'lcd1602': return '16x2 LCD with I2C module';
      case 'relay': return '5V 1 channel relay module';
      case 'soilcap': return 'capacitive soil moisture sensor v1.2';
      case 'pir': return 'HC-SR501 PIR motion sensor';
      case 'rc522': return 'RC522 RFID module';
      case 'servo': return 'SG90 servo motor';
      case 'ws2812': return 'WS2812B LED ring ' + (/\d+/.test(v) ? (/(\d+)/.exec(v)[1]) + ' LED' : '8 LED');
      default: return v;
    }
  }
  function pages(k) { return (k && PAGES[k]) || []; }

  const api = { SHOPS, CHECKED, PAGES, KITS, key, query, pages };
  root.PcbBuy = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
