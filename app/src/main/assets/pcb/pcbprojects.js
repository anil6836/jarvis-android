// 🔌 Jarvis PCB studio — ready boards (₹0, our own designs): each with its parts, how they join (nets), where they sit on the
// board, where they sit on the drawing (schematic), what each part does here, and what it costs to buy roughly. All low-voltage
// DC (a 9 V battery, a bike's 12 V, USB 5 V): no 230 V board is ever made here.
'use strict';
(function (root) {
  const PROJECTS = [
    {
      id: 'flasher', emoji: '🚨', te: 'LED ఫ్లాషర్ (555 టైమర్)', title: 'LED FLASHER',
      what: 'రెండు LED లు మార్చి మార్చి మెరుస్తాయి (సెకనుకి సుమారు ఒకటిన్నర సార్లు). 9V బ్యాటరీ. మొదటి PCB కి సరైనది: 10 భాగాలు, అన్నీ చేతితో టంకం.',
      cost: 'భాగాలు సుమారు ₹60–100', level: 'మొదటి బోర్డ్',
      board: { w: 48, h: 36, r: 1.5, holes: false },
      parts: [
        { ref: 'J1', lib: 'term2', val: '9V IN', pins: { 1: '+9V', 2: 'GND' }, pl: 'E', plo: 5.0, x: 4.4, y: 18, rot: 90, sx: 100, sy: 450, note: '9V బ్యాటరీ క్లిప్ వైర్లు ఇక్కడ: ఎరుపు + (పైన), నలుపు − (కింద).' },
        { ref: 'U1', lib: 'ne555', val: 'NE555', x: 25, y: 18, rot: 0, sx: 200, sy: 260, note: 'టిక్ టిక్ చేసే మెదడు: పిన్ 3 (OUT) మార్చి మార్చి + / − అవుతుంది.' },
        { ref: 'R1', lib: 'res', val: '1k', x: 35, y: 10, rot: 90, sx: 80, sy: 150, srot: 90, note: 'R1, R2, C1 కలిసి వేగం నిర్ణయిస్తాయి.' },
        { ref: 'R2', lib: 'res', val: '47k', x: 39.5, y: 19.5, rot: 90, sx: 80, sy: 240, srot: 90, note: 'ఇది పెంచితే (100k) LED లు నెమ్మదిగా మెరుస్తాయి; తగ్గిస్తే (22k) వేగంగా.' },
        { ref: 'C1', lib: 'elec', val: '10uF 16V', x: 44.2, y: 30.2, rot: 90, sx: 80, sy: 340, srot: 90, note: 'టైమింగ్ కెపాసిటర్: ఛార్జ్ అయి ఖాళీ అవుతూ ఉంటుంది. + కాలు పైన.' },
        { ref: 'C2', lib: 'cer', val: '10nF (103)', x: 34, y: 30, rot: 0, sx: 210, sy: 380, srot: 90, note: 'పిన్ 5 ని నిశ్శబ్దంగా ఉంచుతుంది (చిన్న కుదుపులు రాకుండా).' },
        { ref: 'R3', lib: 'res', val: '470', x: 15, y: 31, rot: 0, sx: 320, sy: 290, srot: 90, note: 'D1 LED కి కరెంట్ తగ్గిస్తుంది (లేకపోతే LED కాలిపోతుంది).' },
        { ref: 'D1', lib: 'led5', val: 'RED', x: 15.5, y: 24.5, rot: 0, color: 'red', sx: 320, sy: 360, srot: 90, note: 'OUT + అయినప్పుడు వెలుగుతుంది.' },
        { ref: 'R4', lib: 'res', val: '470', x: 15, y: 5, rot: 0, sx: 320, sy: 120, srot: 90, note: 'D2 LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D2', lib: 'led5', val: 'GREEN', x: 13.5, y: 11.5, rot: 180, color: 'green', sx: 320, sy: 190, srot: 90, note: 'OUT − అయినప్పుడు వెలుగుతుంది (D1 ఆరినప్పుడు).' }
      ],
      nets: [
        { n: '+9V', p: ['J1.1', 'U1.8', 'U1.4', 'R1.1', 'R4.1'], a: 0.05 },
        { n: 'GND', p: ['J1.2', 'U1.1', 'C1.2', 'C2.2', 'D1.1'], a: 0.05 },
        { n: 'DIS', p: ['U1.7', 'R1.2', 'R2.1'] },
        { n: 'THR', p: ['U1.6', 'U1.2', 'R2.2', 'C1.1'] },
        { n: 'CTRL', p: ['U1.5', 'C2.1'] },
        { n: 'OUT', p: ['U1.3', 'R3.1', 'D2.1'] },
        { n: 'LED1', p: ['R3.2', 'D1.2'] },
        { n: 'LED2', p: ['R4.2', 'D2.2'] }
      ],
      labels: [{ t: 'LED FLASHER', x: 30, y: 34.2, h: 1.3 }],
      extra: [{ te: '8 పిన్ IC సాకెట్', val: 'DIP-8', why: 'U1 కింద (చిప్‌కి వేడి తగలదు)', n: 1, k: 'socket8', q: '8 pin IC socket DIP' }, { te: '9V బ్యాటరీ క్లిప్', why: 'J1 కి', n: 1, k: 'clip9v', q: '9V battery snap connector' }],
      buy: ['NE555 IC + 8 పిన్ IC సాకెట్', 'రెసిస్టర్లు: 1k ×1, 47k ×1, 470 ×2 (¼ W)', '10µF 16V కెపాసిటర్, 10nF (103) సిరామిక్', 'ఎరుపు, ఆకుపచ్చ 5mm LED', '2 పిన్ స్క్రూ టెర్మినల్ 5.08mm', '9V బ్యాటరీ + క్లిప్']
    },
    {
      id: 'usb', emoji: '🔌', te: '12V → 5V USB ఛార్జర్ (బైక్ / కారు)', title: '12V TO 5V USB',
      what: 'బైక్ / కారు 12V బ్యాటరీ నుంచి ఫోన్ ఛార్జింగ్ (5V, 2A వరకు). తిరగేసి కలిపినా కాలిపోకుండా డయోడ్, ఎక్కువ కరెంట్‌కి PTC ఫ్యూజ్. 12V→5V మార్చే పని రెడీమేడ్ బక్ మాడ్యూల్ చేస్తుంది.',
      cost: 'భాగాలు సుమారు ₹150–250 (బక్ మాడ్యూల్‌తో)', level: 'రెండో బోర్డ్',
      board: { w: 60, h: 40, r: 1.5, holes: true },
      parts: [
        { ref: 'J1', lib: 'term2', val: '12V IN', pins: { 1: '+12V', 2: 'GND' }, pl: 'E', plo: 5.0, x: 4.4, y: 20, rot: 90, sx: 60, sy: 120, note: 'బైక్ 12V: ఎరుపు వైరు + (పైన), నలుపు − (కింద). బైక్ స్విచ్ తర్వాత నుంచి తీసుకుంటే బండి ఆపినప్పుడు బ్యాటరీ ఖాళీ కాదు.' },
        { ref: 'F1', lib: 'ptc', val: 'PTC 1.85A', x: 15, y: 12.5, rot: 0, sx: 150, sy: 110, note: 'ఎక్కువ కరెంట్ వస్తే ఆపుతుంది; చల్లారాక మళ్ళీ పనిచేస్తుంది (RXEF185 లాంటిది).' },
        { ref: 'D1', lib: 'd5822', val: '1N5822', x: 27.5, y: 12.5, rot: 180, sx: 240, sy: 110, note: 'వైర్లు తిరగేసి కలిపితే కరెంట్ ఆపుతుంది: బోర్డ్ కాపాడుతుంది.' },
        { ref: 'C1', lib: 'elec', val: '100uF 25V', fp: 'CP_D6_P2.5', x: 30, y: 25, rot: 90, sx: 60, sy: 230, srot: 90, note: 'బండి కరెంట్‌లో కుదుపులు నింపుతుంది. 25V కంటే తక్కువ వోల్టేజ్ ఉన్నది పెట్టకండి.' },
        { ref: 'J2', lib: 'buck', val: 'BUCK 5V', pl: 'E', plo: 1.9, x: 40, y: 20, rot: 0, sx: 190, sy: 230, note: 'LM2596 / XL4015 బక్ మాడ్యూల్ ఇక్కడ వైర్లతో (లేదా పిన్నులతో) కలుస్తుంది. ముందే 5.1V కి సెట్ చేయండి.' },
        { ref: 'C2', lib: 'elec', val: '220uF 16V', fp: 'CP_D6_P2.5', x: 45, y: 31, rot: 90, sx: 300, sy: 300, srot: 90, note: 'ఫోన్‌కి వెళ్ళే 5V ని స్థిరంగా ఉంచుతుంది.' },
        { ref: 'J3', lib: 'usba', val: 'USB-A', x: 50.4, y: 18, rot: 90, sx: 190, sy: 380, note: 'ఫోన్ కేబుల్ ఇక్కడ. D+ D− కలిపి ఉంచాం: ఫోన్ "ఇది ఛార్జర్" అని గుర్తించి ఎక్కువ కరెంట్ తీసుకుంటుంది.' },
        { ref: 'R1', lib: 'res', val: '1k', x: 18, y: 33, rot: 0, sx: 300, sy: 150, srot: 90, note: 'పవర్ LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D2', lib: 'led3', val: 'GREEN', x: 9.5, y: 33, rot: 180, color: 'green', sx: 300, sy: 220, srot: 90, note: '5V వస్తోంది అని చూపిస్తుంది.' }
      ],
      nets: [
        { n: 'VIN', p: ['J1.1', 'F1.1'], a: 1.5 },
        { n: 'FUSED', p: ['F1.2', 'D1.2'], a: 1.5 },
        { n: '+12V', p: ['D1.1', 'C1.1', 'J2.1'], a: 1.5 },
        { n: 'GND', p: ['J1.2', 'C1.2', 'J2.2', 'J2.3', 'C2.2', 'J3.4', 'J3.S1', 'J3.S2', 'D2.1'], a: 2.1 },
        { n: '+5V', p: ['J2.4', 'C2.1', 'J3.1', 'R1.1'], a: 2.1 },
        { n: 'DPDM', p: ['J3.2', 'J3.3'] },
        { n: 'PWR', p: ['R1.2', 'D2.2'] }
      ],
      labels: [{ t: '12V TO 5V USB', x: 33, y: 37.6, h: 1.3 }],
      extra: [{ te: 'పిన్ హెడర్ స్ట్రిప్', val: '2.54 mm', why: 'J2: బక్ మాడ్యూల్ కలపడానికి (4 పిన్నులు కట్ చేయండి)', n: 1, k: 'hdr', q: 'male berg strip 2.54mm' }],
      buy: ['LM2596 / XL4015 బక్ మాడ్యూల్ (5V కి సెట్ చేయాలి)', 'USB-A ఫిమేల్ సాకెట్ (PCB, 90°)', '1N5822 డయోడ్', 'PTC ఫ్యూజ్ 1.85A (RXEF185)', '100µF 25V, 220µF 16V కెపాసిటర్లు', '1k రెసిస్టర్, ఆకుపచ్చ 3mm LED', '2 పిన్ స్క్రూ టెర్మినల్ 5.08mm', '4 పిన్ హెడర్ లేదా వైర్లు']
    },
    {
      id: 'soil', emoji: '🌱', te: 'మట్టి ఎండితే అలారం (మొక్కలకి)', title: 'SOIL ALARM',
      what: 'కుండీలో మట్టి ఎండిపోతే బజర్ మోగుతుంది, ఎరుపు LED వెలుగుతుంది. నీళ్ళు పోస్తే ఆగిపోతుంది. రెండు స్క్రూలు / మేకులే సెన్సార్. 9V బ్యాటరీ.',
      cost: 'భాగాలు సుమారు ₹70–120', level: 'మొదటి బోర్డ్',
      board: { w: 52, h: 34, r: 1.5, holes: false },
      parts: [
        { ref: 'J1', lib: 'term2', val: '9V IN', pins: { 1: '+9V', 2: 'GND' }, pl: 'E', plo: 5.0, x: 4.4, y: 11, rot: 90, sx: 60, sy: 330, note: '9V బ్యాటరీ: + పైన, − కింద.' },
        { ref: 'J2', lib: 'term2', val: 'PROBE', x: 47.6, y: 11, rot: 270, sx: 60, sy: 90, note: 'మట్టిలో గుచ్చే రెండు మేకులు / స్క్రూల వైర్లు ఇక్కడ (2–3 cm దూరంగా గుచ్చండి).' },
        { ref: 'R1', lib: 'res', val: '1k', x: 33, y: 7, rot: 0, sx: 150, sy: 100, note: 'తడి మట్టి నుంచి వచ్చే కరెంట్‌ని Q1 కి పంపుతుంది.' },
        { ref: 'R2', lib: 'res', val: '100k', x: 33, y: 13, rot: 0, sx: 160, sy: 210, srot: 90, note: 'మట్టి ఎండినప్పుడు Q1 ని ఖచ్చితంగా ఆపేస్తుంది.' },
        { ref: 'Q1', lib: 'bc547', val: 'BC547', x: 22, y: 18, rot: 0, sx: 220, sy: 170, note: 'తడి ఉంటే ఆన్: Q2 ని ఆపేస్తుంది.' },
        { ref: 'R3', lib: 'res', val: '10k', x: 18, y: 5, rot: 0, sx: 260, sy: 70, srot: 90, note: 'Q1 ఆఫ్ అయినప్పుడు Q2 కి కరెంట్ ఇస్తుంది.' },
        { ref: 'R4', lib: 'res', val: '4.7k', x: 28, y: 30, rot: 0, sx: 270, sy: 190, srot: 90, note: 'Q2 కి వెళ్ళే కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'Q2', lib: 'bc547', val: 'BC547', x: 31, y: 22, rot: 0, sx: 300, sy: 250, note: 'ఎండిపోతే ఆన్: బజర్, LED మోగిస్తుంది.' },
        { ref: 'BZ1', lib: 'buzzer', val: 'BUZZER', x: 42, y: 25, rot: 0, sx: 330, sy: 140, srot: 90, note: 'ఆక్టివ్ బజర్ (5–12V). + కాలు + గుర్తు వైపు.' },
        { ref: 'R5', lib: 'res', val: '1k', x: 11, y: 30, rot: 0, sx: 370, sy: 70, srot: 90, note: 'ఎరుపు LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D1', lib: 'led5', val: 'RED', x: 13.5, y: 22.5, rot: 270, color: 'red', sx: 370, sy: 140, srot: 90, note: 'ఎండిపోతే వెలుగుతుంది.' }
      ],
      nets: [
        { n: '+9V', p: ['J1.1', 'J2.1', 'R3.1', 'BZ1.1', 'R5.1'], a: 0.05 },
        { n: 'GND', p: ['J1.2', 'Q1.3', 'Q2.3', 'R2.2'], a: 0.05 },
        { n: 'PROBE', p: ['J2.2', 'R1.1'] },
        { n: 'Q1B', p: ['R1.2', 'R2.1', 'Q1.2'] },
        { n: 'X', p: ['Q1.1', 'R3.2', 'R4.1'] },
        { n: 'Q2B', p: ['R4.2', 'Q2.2'] },
        { n: 'ALARM', p: ['Q2.1', 'BZ1.2', 'D1.1'] },
        { n: 'LEDA', p: ['R5.2', 'D1.2'] }
      ],
      labels: [{ t: 'PROBE', ref: 'J2', dx: -6.6, dy: -5.0, h: 1.2 }, { t: 'SOIL ALARM', x: 40, y: 2.4, h: 1.3 }],
      extra: [{ te: '9V బ్యాటరీ క్లిప్', why: 'J1 కి', n: 1, k: 'clip9v', q: '9V battery snap connector' }],
      buy: ['BC547 ట్రాన్సిస్టర్ ×2', 'రెసిస్టర్లు: 1k ×2, 100k, 10k, 4.7k', 'ఆక్టివ్ బజర్ 5–12V (12mm)', 'ఎరుపు 5mm LED', '2 పిన్ స్క్రూ టెర్మినల్ ×2', 'రెండు స్టీల్ స్క్రూలు / మేకులు, వైరు', '9V బ్యాటరీ + క్లిప్']
    },
    {
      id: 'esp32', emoji: '📡', te: 'ESP32 సెన్సార్ బోర్డ్ (Wi-Fi)', title: 'JARVIS SENSOR',
      what: 'ESP32 గుచ్చే బోర్డ్: ఉష్ణోగ్రత / తేమ (DHT11/DHT22), మట్టి తేమ సెన్సార్, I2C (OLED స్క్రీన్ / BMP280) కి సాకెట్లు, ఒక LED. Wi-Fi తో ఫోన్ Jarvis కి పంపొచ్చు (కోడ్ తర్వాత).',
      cost: 'సుమారు ₹450–650 (ESP32 బోర్డ్‌తో)', level: 'మధ్యస్థం',
      board: { w: 66, h: 60, r: 2, holes: true, rows: 25.4 },
      parts: [
        { ref: 'U1', lib: 'esp32', val: 'ESP32 DEVKIT', x: 31, y: 30, rot: 0, sx: 190, sy: 250, noref: false, lx: 0, ly: -22.6, note: 'బోర్డ్ మెదడు. రెండు 15-పిన్ ఫిమేల్ హెడర్లలో గుచ్చండి; USB కింది వైపు, యాంటెనా పై వైపు.' },
        { ref: 'J1', lib: 'fhdr', val: 'DHT', fp: 'FHDR1x3', pins: { 1: 'V', 2: 'D', 3: 'G' }, pl: 'E', x: 55, y: 17, rot: 0, sx: 330, sy: 320, srot: 180, note: 'DHT11 / DHT22 మాడ్యూల్: V = 3.3V, D = డేటా (GPIO4), G = −. మీ మాడ్యూల్ మీది పేర్లు చూసి గుచ్చండి.' },
        { ref: 'R1', lib: 'res', val: '10k', x: 55, y: 30, rot: 90, sx: 290, sy: 280, srot: 90, note: 'DHT డేటా వైరుని 3.3V కి లాగి ఉంచుతుంది (pull-up).' },
        { ref: 'J2', lib: 'fhdr', val: 'SOIL', fp: 'FHDR1x3', pins: { 1: 'V', 2: 'G', 3: 'A' }, pl: 'E', x: 55, y: 42, rot: 0, sx: 50, sy: 170, note: 'మట్టి తేమ సెన్సార్ మాడ్యూల్: V, G, A (అనలాగ్ → GPIO34).' },
        { ref: 'J3', lib: 'fhdr', val: 'I2C', fp: 'FHDR1x4', pins: { 1: 'V', 2: 'G', 3: 'SDA', 4: 'SCL' }, pl: 'W', x: 8, y: 22, rot: 0, sx: 330, sy: 160, srot: 180, note: 'OLED స్క్రీన్ / BMP280 సెన్సార్: SDA = GPIO21, SCL = GPIO22.' },
        { ref: 'R2', lib: 'res', val: '330', x: 9.5, y: 40, rot: 90, sx: 290, sy: 60, note: 'LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D1', lib: 'led5', val: 'BLUE', x: 9.5, y: 50.5, rot: 90, color: 'blue', sx: 340, sy: 60, note: 'GPIO23 తో ఆన్/ఆఫ్: "Jarvis పనిచేస్తోంది" అని చూపొచ్చు.' }
      ],
      nets: [
        { n: '3V3', p: ['U1.30', 'J1.1', 'J2.1', 'J3.1', 'R1.1'], a: 0.3 },
        { n: 'GND', p: ['U1.14', 'U1.29', 'J1.3', 'J2.2', 'J3.2', 'D1.1'], a: 0.3 },
        { n: 'DHT', p: ['U1.26', 'J1.2', 'R1.2'] },
        { n: 'SOIL', p: ['U1.4', 'J2.3'] },
        { n: 'SDA', p: ['U1.20', 'J3.3'] },
        { n: 'SCL', p: ['U1.17', 'J3.4'] },
        { n: 'LED', p: ['U1.16', 'R2.1'] },
        { n: 'LEDA', p: ['R2.2', 'D1.2'] }
      ],
      labels: [{ t: 'USB', ref: 'U1', dx: 0, dy: 27.4, h: 1.2 }, { t: 'JARVIS SENSOR', x: 31, y: 2.6, h: 1.3 }],
      extra: [{ te: 'ఫిమేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)', why: 'U1 (ESP32) కి 15 + 15 పిన్నులు కట్ చేయండి; J1, J2, J3 కి కూడా దీని నుంచే', n: 2, k: 'fhdr', q: 'female berg strip 2.54mm 40 pin' }, { te: 'DHT11 మాడ్యూల్', val: '3 పిన్', why: 'J1 లో గుచ్చేది', n: 1, k: 'dht11', q: 'DHT11 temperature humidity sensor module' }, { te: 'మట్టి తేమ సెన్సార్', val: 'capacitive', why: 'J2 లో గుచ్చేది', n: 1, k: 'soilcap', q: 'capacitive soil moisture sensor' }, { te: 'OLED స్క్రీన్ (ఐచ్ఛికం)', val: '0.96 inch I2C', why: 'J3 లో గుచ్చేది', n: 1, k: 'oled', q: '0.96 inch OLED I2C SSD1306' }],
      buy: ['ESP32 DevKit V1 (30 పిన్)', '15 పిన్ ఫిమేల్ హెడర్ ×2, 3 పిన్ ×2, 4 పిన్ ×1', 'DHT11 / DHT22 మాడ్యూల్', 'మట్టి తేమ సెన్సార్ మాడ్యూల్ (capacitive మంచిది)', '10k, 330 రెసిస్టర్లు, నీలం 5mm LED', 'ఐచ్ఛికం: 0.96" OLED (I2C)']
    },
    {
      id: 'irboard', emoji: '📺', te: 'Jarvis స్పీకర్ IR బోర్డ్ (TV / AC రిమోట్)', title: 'JARVIS IR',
      what: 'మన Jarvis స్పీకర్ ప్లాన్‌లోని IR భాగం: రెండు IR LED లతో TV / AC కి ఆదేశాలు, IR రిసీవర్‌తో మీ రిమోట్ బటన్‌లు నేర్చుకోవడం. ESP32 / స్పీకర్ బోర్డ్‌కి 5 వైర్లతో కలుస్తుంది.',
      cost: 'భాగాలు సుమారు ₹80–150', level: 'మధ్యస్థం',
      board: { w: 44, h: 30, r: 1.5, holes: false },
      parts: [
        { ref: 'J1', lib: 'hdr', val: 'ESP32', fp: 'HDR1x5', pins: { 1: '5V', 2: '3V3', 3: 'GND', 4: 'TX', 5: 'RX' }, pl: 'E', x: 3.5, y: 15, rot: 0, sx: 50, sy: 250, note: 'ESP32 / స్పీకర్ బోర్డ్ నుంచి 5 వైర్లు: 5V, 3V3, GND, TX (IR పంపే GPIO), RX (IR వినే GPIO).' },
        { ref: 'R2', lib: 'res', val: '1k', x: 11, y: 26, rot: 0, sx: 130, sy: 270, note: 'ESP32 పిన్ నుంచి ట్రాన్సిస్టర్‌కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'Q1', lib: 'bc547', val: 'BC547', x: 23, y: 24, rot: 0, sx: 200, sy: 270, note: 'ESP32 చిన్న కరెంట్‌తో IR LED లకి పెద్ద కరెంట్ ఇస్తుంది.' },
        { ref: 'R1', lib: 'res', val: '47', x: 18, y: 5, rot: 0, sx: 210, sy: 60, srot: 90, note: 'IR LED లకి కరెంట్ (సుమారు 45 mA) పరిమితం.' },
        { ref: 'D1', lib: 'irled', val: 'IR 940nm', x: 33, y: 6.5, rot: 0, sx: 210, sy: 130, srot: 90, note: 'IR LED 1: TV / AC వైపు తిప్పండి.' },
        { ref: 'D2', lib: 'irled', val: 'IR 940nm', x: 39.5, y: 14, rot: 270, sx: 210, sy: 190, srot: 90, note: 'IR LED 2: ఇంకో దిశకి (గది మొత్తానికి).' },
        { ref: 'U1', lib: 'vs1838', val: 'VS1838B', x: 15, y: 15, rot: 0, sx: 330, sy: 280, note: 'మీ రిమోట్ బటన్‌లు ఇది విని ESP32 కి చెబుతుంది.' },
        { ref: 'R3', lib: 'res', val: '100', x: 25.5, y: 13, rot: 0, sx: 280, sy: 190, srot: 90, note: 'రిసీవర్‌కి శుభ్రమైన కరెంట్ (datasheet చెప్పినట్టు 100 Ω + 10 µF).' },
        { ref: 'C1', lib: 'elec', val: '10uF 16V', x: 33, y: 19, rot: 0, sx: 330, sy: 220, note: 'రిసీవర్ కరెంట్ కుదుపులు నింపుతుంది. + కాలు ఎడమ.' },
        { ref: 'R4', lib: 'res', val: '1k', x: 34, y: 26.5, rot: 0, sx: 130, sy: 150, srot: 90, note: 'పవర్ LED కి.' },
        { ref: 'D3', lib: 'led3', val: 'GREEN', x: 40, y: 22, rot: 90, color: 'green', sx: 130, sy: 210, srot: 90, note: 'బోర్డ్‌కి కరెంట్ వస్తోంది.' }
      ],
      nets: [
        { n: '+5V', p: ['J1.1', 'R1.1'], a: 0.1 },
        { n: '3V3', p: ['J1.2', 'R3.1', 'R4.1'], a: 0.05 },
        { n: 'GND', p: ['J1.3', 'Q1.3', 'U1.2', 'C1.2', 'D3.1'], a: 0.1 },
        { n: 'IRTX', p: ['J1.4', 'R2.1'] },
        { n: 'IRRX', p: ['J1.5', 'U1.1'] },
        { n: 'QB', p: ['R2.2', 'Q1.2'] },
        { n: 'IRA', p: ['R1.2', 'D1.2'] },
        { n: 'IRM', p: ['D1.1', 'D2.2'] },
        { n: 'IRK', p: ['D2.1', 'Q1.1'] },
        { n: 'RXV', p: ['R3.2', 'U1.3', 'C1.1'] },
        { n: 'PWR', p: ['R4.2', 'D3.2'] }
      ],
      labels: [{ t: 'JARVIS IR', x: 15, y: 28.8, h: 1.3 }],
      extra: [{ te: 'జంపర్ వైర్లు', val: 'ఫిమేల్-ఫిమేల్', why: 'J1 నుంచి ESP32 / స్పీకర్ బోర్డ్‌కి', n: 5, k: null, q: 'female to female jumper wires' }],
      buy: ['IR LED 940nm ×2', 'VS1838B IR రిసీవర్', 'BC547', 'రెసిస్టర్లు: 47, 100, 1k ×2', '10µF 16V కెపాసిటర్, ఆకుపచ్చ 3mm LED', '5 పిన్ హెడర్ + జంపర్ వైర్లు']
    },
    {
      // 🌱 27 Nov: his plant-watering board, part 1 of 4 ("మనం చేయాల్సిన పనులు (4 భాగాలు)"): 1–3 pots, water from a bucket / small tank.
      // The board's program (part 2), Jarvis's locked messenger (part 3) and the phone / watch words (part 4) come in the next builds.
      id: 'plant', emoji: '🌱', te: 'మొక్కలకి నీళ్ళ బోర్డ్ (ESP32)', title: 'JARVIS PLANT',
      what: 'మీరు డ్యూటీలో ఉన్నా ఫోన్ Jarvis తో 1–3 కుండీలకి బకెట్ నుంచి నీళ్ళు పోసే బోర్డ్: ESP32 (Wi-Fi మెదడు), చిన్న 5V పంప్ స్విచ్ (MOSFET), 3 మట్టి తేమ సెన్సార్లు, బకెట్ ఖాళీ సెన్సార్ (ఫ్లోట్ స్విచ్), టెస్ట్ బటన్, 2 LED లు. ఫోన్ ఛార్జర్ (Type-C 5V) తో నడుస్తుంది. బోర్డ్ ప్రోగ్రామ్, ఫోన్ మాటలు తర్వాతి బిల్డ్‌లలో.',
      cost: 'సుమారు ₹800–1,200 (ESP32, పంప్, సెన్సార్లతో; PCB వేరు)', level: 'మధ్యస్థం',
      board: { w: 84, h: 66, r: 2, holes: true, rows: 25.4 },
      parts: [
        { ref: 'U1', lib: 'esp32', val: 'ESP32 DEVKIT', x: 62, y: 33, rot: 0, sx: 330, sy: 300, note: 'బోర్డ్ మెదడు: Wi-Fi తో మీ ఫోన్ Jarvis మాట విని పంప్ నడుపుతుంది, మట్టి తేమ చదువుతుంది. రెండు 15-పిన్ ఫిమేల్ హెడర్లలో గుచ్చండి; దాని USB బోర్డ్ కింది అంచు వైపు (ఫోన్ నుంచి ప్రోగ్రామ్ ఎక్కించడానికి).' },
        { ref: 'J1', lib: 'usbc', val: 'Type-C 5V', x: 37, y: 59.5, rot: 180, sx: 500, sy: 70, note: 'Type-C పవర్ మాడ్యూల్: ఫోన్ ఛార్జర్ (5V, 1A లేదా పైన) ఇక్కడ పెట్టండి. బోర్డ్ అంచున, సాకెట్ బయటికి.' },
        { ref: 'F1', lib: 'ptc', val: 'PTC 1.1A', x: 37, y: 49, rot: 0, sx: 610, sy: 70, note: 'ఏదైనా షార్ట్ అయితే కరెంట్ ఆపుతుంది; చల్లారాక మళ్ళీ పనిచేస్తుంది.' },
        { ref: 'C1', lib: 'elec', val: '470uF 10V', x: 28, y: 62.5, rot: 0, sx: 690, sy: 130, srot: 90, note: 'పంప్ మొదలయ్యే క్షణం వచ్చే కరెంట్ కుదుపుని నింపుతుంది (ESP32 మళ్ళీ మొదలవ్వకుండా). + కాలు గుర్తు చూసి పెట్టండి.' },
        { ref: 'C2', lib: 'cer', val: '100nF (104)', x: 15, y: 20, rot: 0, sx: 80, sy: 70, srot: 90, note: 'సెన్సార్లకి వెళ్ళే 3.3V ని నిశ్శబ్దంగా ఉంచుతుంది (తేమ రీడింగ్ స్థిరంగా).' },
        { ref: 'Q1', lib: 'nmos', val: 'IRLZ44N', x: 23, y: 54, rot: 0, sx: 600, sy: 360, note: 'పంప్ స్విచ్: ESP32 D26 పిన్ 3.3V ఇస్తే పంప్ నడుస్తుంది, 0V అయితే ఆగుతుంది.' },
        { ref: 'R1', lib: 'res', val: '100', x: 37, y: 37, rot: 0, sx: 480, sy: 380, note: 'ESP32 పిన్ నుంచి MOSFET గేట్‌కి (100 Ω): పిన్‌ని కాపాడుతుంది.' },
        { ref: 'R2', lib: 'res', val: '10k', x: 23, y: 46, rot: 0, sx: 540, sy: 450, srot: 90, note: 'బోర్డ్ మొదలయ్యే క్షణం పంప్ తనంతట తానే నడవకుండా గేట్‌ని 0V లో ఉంచుతుంది.' },
        { ref: 'D1', lib: 'd5822', val: '1N5822', x: 13.5, y: 54, rot: 90, sx: 690, sy: 250, srot: 90, note: 'పంప్ ఆగిన క్షణం వచ్చే వోల్టేజ్ కుదుపుని మింగేస్తుంది (MOSFET కాపాడుతుంది). గీత ఉన్న వైపు +5V.' },
        { ref: 'J2', lib: 'term2', val: 'PUMP 0.2A', pins: { 1: '+5V', 2: 'PUMP-' }, x: 4.6, y: 51.5, rot: 90, sx: 600, sy: 250, note: 'పంప్ రెండు వైర్లు: ఎరుపు +5V (పైన), నలుపు PUMP− (కింద).' },
        { ref: 'J3', lib: 'term2', val: 'TANK', pins: { 1: 'TANK', 2: 'GND' }, x: 4.6, y: 40, rot: 90, sx: 70, sy: 480, note: 'బకెట్ ఫ్లోట్ స్విచ్ రెండు వైర్లు (దిశ లేదు). బకెట్‌లో నీళ్ళు అయిపోతే బోర్డ్ నీళ్ళు పోయడం ఆపేస్తుంది.' },
        { ref: 'R3', lib: 'res', val: '10k', x: 17, y: 35, rot: 0, sx: 160, sy: 460, srot: 90, note: 'ఫ్లోట్ స్విచ్ వైరుని 3.3V కి లాగి ఉంచుతుంది (pull-up).' },
        { ref: 'C3', lib: 'cer', val: '100nF (104)', x: 15, y: 41, rot: 0, sx: 215, sy: 520, srot: 90, note: 'ఫ్లోట్ కదిలినప్పుడు వచ్చే చిన్న కుదుపులు తీసేస్తుంది.' },
        { ref: 'J4', lib: 'soilcap', val: 'SOIL 1', x: 3.4, y: 10.5, rot: 0, sx: 150, sy: 120, srot: 180, note: 'కుండీ 1 మట్టి తేమ సెన్సార్ (capacitive v1.2) కేబుల్: GND · VCC · AOUT. ఒక్క కుండీ అయితే ఇది ఒక్కటే చాలు.' },
        { ref: 'J5', lib: 'soilcap', val: 'SOIL 2', x: 3.4, y: 19, rot: 0, sx: 150, sy: 240, srot: 180, note: 'కుండీ 2 సెన్సార్ (ఐచ్ఛికం).' },
        { ref: 'J6', lib: 'soilcap', val: 'SOIL 3', x: 3.4, y: 27.5, rot: 0, sx: 150, sy: 360, srot: 180, note: 'కుండీ 3 సెన్సార్ (ఐచ్ఛికం).' },
        { ref: 'SW1', lib: 'btn', val: 'WATER', x: 15, y: 6, rot: 0, sx: 100, sy: 600, note: 'ఇంట్లో నొక్కితే కొన్ని సెకన్లు నీళ్ళు (టెస్ట్ కోసం, ఫోన్ అక్కర్లేదు).' },
        { ref: 'R4', lib: 'res', val: '10k', x: 17, y: 13, rot: 0, sx: 180, sy: 580, srot: 90, note: 'బటన్ వైరుని 3.3V కి లాగి ఉంచుతుంది.' },
        { ref: 'R5', lib: 'res', val: '1k', x: 31, y: 6, rot: 0, sx: 760, sy: 130, srot: 90, note: 'పవర్ LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D2', lib: 'led3', val: 'GREEN', color: 'green', x: 41, y: 6, rot: 0, sx: 760, sy: 210, srot: 90, note: 'పవర్ LED: బోర్డ్‌కి కరెంట్ వస్తోంది.' },
        { ref: 'R6', lib: 'res', val: '330', x: 31, y: 12, rot: 0, sx: 240, sy: 660, note: 'పని LED కి కరెంట్ తగ్గిస్తుంది.' },
        { ref: 'D3', lib: 'led3', val: 'BLUE', color: 'blue', x: 41, y: 12, rot: 0, sx: 320, sy: 660, note: 'పని LED (D25): Wi-Fi కలిస్తే వెలుగుతుంది, నీళ్ళు పోస్తున్నప్పుడు మెరుస్తుంది.' }
      ],
      nets: [
        { n: 'VBUS', p: ['J1.1', 'F1.1'], a: 0.6 },
        { n: '+5V', p: ['F1.2', 'C1.1', 'U1.15', 'J2.1', 'D1.1', 'R5.1'], a: 0.6 },
        { n: 'GND', p: ['J1.2', 'C1.2', 'C2.2', 'U1.14', 'U1.29', 'Q1.3', 'R2.2', 'J3.2', 'C3.2', 'J4.1', 'J5.1', 'J6.1', 'SW1.2', 'D2.1', 'D3.1'], a: 0.6 },
        { n: 'PUMP-', p: ['J2.2', 'D1.2', 'Q1.2'], a: 0.4 },
        { n: 'GATE', p: ['Q1.1', 'R1.2', 'R2.1'] },
        { n: 'PUMPON', p: ['R1.1', 'U1.9'] },
        { n: '3V3', p: ['U1.30', 'C2.1', 'J4.2', 'J5.2', 'J6.2', 'R3.1', 'R4.1'], a: 0.05 },
        { n: 'SOIL1', p: ['U1.4', 'J4.3'] }, { n: 'SOIL2', p: ['U1.5', 'J5.3'] }, { n: 'SOIL3', p: ['U1.6', 'J6.3'] },
        { n: 'TANK', p: ['U1.10', 'J3.1', 'R3.2', 'C3.1'] },
        { n: 'BTN', p: ['U1.13', 'SW1.1', 'R4.2'] },
        { n: 'PWRLED', p: ['R5.2', 'D2.2'] },
        { n: 'LED', p: ['U1.8', 'R6.1'] }, { n: 'LEDA', p: ['R6.2', 'D3.2'] }
      ],
      labels: [{ t: 'USB', ref: 'U1', dx: 0, dy: 27.4, h: 1.2 }, { t: 'JARVIS PLANT', x: 31, y: 27, h: 1.3 }],
      extra: [
        { te: 'ఫిమేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)', why: 'U1 (ESP32) కి 15 + 15 పిన్నులు కట్ చేయండి', n: 1, k: 'fhdr', q: 'female berg strip 2.54mm 40 pin' },
        { te: 'మేల్ బెర్గ్ స్ట్రిప్ (40 పిన్)', why: 'J4, J5, J6 సెన్సార్ సాకెట్లకి 3 + 3 + 3 పిన్నులు', n: 1, k: 'hdr', q: 'male berg strip 2.54mm 40 pin' },
        { te: 'మట్టి తేమ సెన్సార్', val: 'capacitive v1.2', why: 'కుండీకి ఒకటి (1–3), కేబుల్‌తో J4 / J5 / J6 కి', n: 1, k: 'soilcap', q: 'capacitive soil moisture sensor v1.2' },
        { te: 'మినీ నీళ్ళ పంప్', val: '3–6V DC, సబ్‌మెర్సిబుల్', why: 'బకెట్‌లో మునిగి ఉంటుంది; వైర్లు J2 కి', n: 1, k: null, q: 'mini submersible water pump 3-6V DC' },
        { te: 'సిలికాన్ పైప్', val: '6 mm లోపలి వ్యాసం, 1–2 మీటర్లు', why: 'పంప్ నుంచి కుండీలకి (3 కుండీలకి T జాయింట్లు)', n: 1, k: null, q: 'silicone tube 6mm water pump' },
        { te: 'ఫ్లోట్ స్విచ్', val: 'చిన్నది, నిలువు (FS37A లాంటిది)', why: 'బకెట్ ఖాళీ అయితే చెబుతుంది; వైర్లు J3 కి', n: 1, k: null, q: 'mini vertical float switch water level sensor' },
        { te: 'ఫోన్ ఛార్జర్ + Type-C కేబుల్', val: '5V, 1A లేదా పైన', why: 'J1 కి (మీ దగ్గర ఉన్నది చాలు)', n: 1, k: null, q: '5V 2A USB charger type C cable' }
      ],
      buy: ['ESP32 DevKit V1 (30 పిన్, CP2102)', 'IRLZ44N MOSFET, 1N5822 డయోడ్, PTC 1.1A', 'రెసిస్టర్లు: 100, 330, 1k, 10k ×3', '470µF 10V, 100nF ×2', 'Type-C పవర్ మాడ్యూల్, 6×6 బటన్, ఆకుపచ్చ + నీలం 3mm LED', '2-పిన్ స్క్రూ టెర్మినల్ ×2, బెర్గ్ స్ట్రిప్‌లు (ఫిమేల్, మేల్)', 'మట్టి తేమ సెన్సార్ (capacitive) ×1–3, 3–6V మినీ పంప్ + సిలికాన్ పైప్, ఫ్లోట్ స్విచ్']
    }
  ];

  /** A new design (his own copy) from a ready board. */
  function make(id) {
    const p = PROJECTS.find((q) => q.id === id);
    if (!p) return null;
    const D = JSON.parse(JSON.stringify({ board: p.board, parts: p.parts, nets: p.nets, labels: p.labels || [] }));
    D.id = id + '-' + Date.now().toString(36);
    D.name = p.te;
    D.title = p.title;
    D.proj = id;
    D.board.color = 'green';
    D.board.thick = 1.6;
    D.board.cu = 1;
    D.fab = 'jlc';
    D.pour = { B: true, F: false };
    D.tracks = [];
    D.vias = [];
    return D;
  }

  const api = { PROJECTS, make };
  root.PcbProjects = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
