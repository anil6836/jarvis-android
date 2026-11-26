# Jarvis plant-watering board: Sir's own program, put on the ESP32 by Jarvis over USB.
# The safety lives here, on the board, and works even without the internet:
#   - at most 2 minutes of pumping at a time (and a timer that stops the pump by itself),
#   - never with an empty bucket (the float switch), and it stops if the bucket runs dry,
#   - a total for the day,
#   - watering ordered from the phone stops if the home Wi-Fi drops.
# Pins (the JARVIS PLANT board): pump D26, bucket D27, button D13, blue light D25, soil D34 / D35 / D32.
import json, os, time, gc, binascii
try:
    import asyncio
except ImportError:
    import uasyncio as asyncio
try:
    from time import ticks_ms, ticks_diff
except ImportError:
    from time import monotonic as _mono

    def ticks_ms():
        return int(_mono() * 1000)

    def ticks_diff(a, b):
        return a - b
import plantnet

FW = 1
HARD_MAX = 120        # seconds: never more at one time, whatever the settings say
TZ = 19800            # India: UTC + 5:30
SOAK = 15             # seconds after watering before the soil is read again
BTN_SECS = 5          # the board's own button: a short watering
HOLD_SETUP = 8000     # hold the button 8 s: the setup Wi-Fi opens
SETUP_FOR = 900000    # a setup Wi-Fi opened by the button closes after 15 minutes
LOST_SETUP = 300000   # no home Wi-Fi for 5 minutes: the setup Wi-Fi opens too (the board keeps trying)
RETRY = 30000
TICK = 0.1
SCHED_EVERY = 20
TANK_EVERY = 10

DEF = {"n": 1, "s": 20, "max": 120, "cap": 300, "dry": 40, "tank": "closed",
       "cal": [[2600, 1200], [2600, 1200], [2600, 1200]], "sched": []}


def load(name, d=None):
    try:
        with open(name) as f:
            return json.load(f)
    except Exception:
        return d


def save(name, o):
    try:
        with open(name + ".tmp", "w") as f:
            json.dump(o, f)
        try:
            os.remove(name)
        except OSError:
            pass
        os.rename(name + ".tmp", name)
    except Exception as e:
        print("save", name, e)


def isnum(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def clean(s, old):
    """Settings from the phone, checked: anything wrong keeps the old value."""
    o = json.loads(json.dumps(old))
    if not isinstance(s, dict):
        return o

    def num(k, lo, hi):
        v = s.get(k)
        if isnum(v):
            o[k] = int(min(hi, max(lo, v)))

    num("n", 1, 3)
    num("max", 5, HARD_MAX)
    num("s", 3, HARD_MAX)
    num("cap", 10, 1800)
    num("dry", 5, 90)
    if o["s"] > o["max"]:
        o["s"] = o["max"]
    if s.get("tank") in ("closed", "open", "none"):
        o["tank"] = s["tank"]
    c = s.get("cal")
    if isinstance(c, list) and len(c) == 3:
        for i in range(3):
            x = c[i]
            if isinstance(x, list) and len(x) == 2 and isnum(x[0]) and isnum(x[1]) and 0 < x[1] + 150 <= x[0] <= 3300:
                o["cal"][i] = [int(x[0]), int(x[1])]
    sc = s.get("sched")
    if isinstance(sc, list):
        out = []
        for e in sc[:4]:
            if not isinstance(e, dict):
                continue
            h = e.get("h")
            m = e.get("m")
            if not (isinstance(h, int) and isinstance(m, int) and 0 <= h <= 23 and 0 <= m <= 59):
                continue
            v = e.get("s")
            secs = int(min(o["max"], max(3, v))) if isnum(v) else o["s"]
            out.append({"h": h, "m": m, "s": secs, "dry": bool(e.get("dry", True)), "on": bool(e.get("on", True))})
        o["sched"] = out
    return o


def avg(soil):
    v = [x[0] for x in soil if x[0] is not None]
    return sum(v) // len(v) if v else None


class HW:
    """The real board's pins."""

    def __init__(self):
        from machine import Pin, ADC, Timer
        import network
        self.Timer = Timer
        self.p = Pin(26, Pin.OUT, value=0)
        self.tk = Pin(27, Pin.IN)
        self.bt = Pin(13, Pin.IN)
        self.ld = Pin(25, Pin.OUT, value=0)
        self.adc = [ADC(Pin(n), atten=ADC.ATTN_11DB) for n in (34, 35, 32)]
        self.tm = Timer(0)
        self.w = None
        self.network = network
        try:
            network.hostname("jarvis-plant")
        except Exception:
            pass
        self.sta = network.WLAN(network.STA_IF)
        self.ap = network.WLAN(network.AP_IF)
        self.ap.active(False)

    def pump(self, on):
        self.p.value(1 if on else 0)

    def guard(self, secs):
        p = self.p
        self.tm.init(mode=self.Timer.ONE_SHOT, period=int(secs * 1000), callback=lambda t: p.value(0))

    def unguard(self):
        try:
            self.tm.deinit()
        except Exception:
            pass

    def tank(self):
        return self.tk.value()

    def button(self):
        return self.bt.value() == 0

    def led(self, on):
        self.ld.value(1 if on else 0)

    def soil(self, i):
        a = self.adc[i]
        t = 0
        for _ in range(8):
            t += a.read_uv()
        return t // 8000

    def watchdog(self):
        from machine import WDT
        self.w = WDT(timeout=30000)

    def feed(self):
        if self.w:
            self.w.feed()

    def reset(self):
        import machine
        self.pump(False)
        machine.reset()

    def wifi_ok(self):
        return self.sta.isconnected()

    def rssi(self):
        try:
            return self.sta.status("rssi") if self.sta.isconnected() else None
        except Exception:
            return None

    def sta_connect(self, ssid, pw):
        try:
            self.sta.active(True)
            try:
                self.sta.disconnect()
            except Exception:
                pass
            self.sta.connect(ssid, pw)
        except Exception as e:
            print("wifi:", e)

    def ap_on(self, ssid, pw):
        self.ap.active(True)
        sec = getattr(self.network, "AUTH_WPA2_PSK", 3)  # (WPA2: the password Jarvis made)
        try:
            self.ap.config(ssid=ssid, key=pw, security=sec)
        except Exception:
            self.ap.config(essid=ssid, password=pw, authmode=sec)

    def ap_off(self):
        self.ap.active(False)

    def scan(self):
        self.sta.active(True)
        out = []
        try:
            for x in sorted(self.sta.scan(), key=lambda x: -x[3]):
                try:
                    n = x[0].decode()
                except Exception:
                    continue
                if n and n not in out:
                    out.append(n)
        except Exception as e:
            print("scan:", e)
        return out[:15]


class Board:
    def __init__(self, hw, cfg):
        self.hw = hw
        self.cfg = cfg
        self.fw = FW
        self.set = clean(load("settings.json", {}), DEF)
        st = load("state.json", {}) or {}
        self.day = st.get("d")
        self.today = st.get("s", 0)
        self.last = st.get("last")
        self.sdone = st.get("sd", [])
        self.pumping = False
        self.stop_req = None
        self.setup = None
        self.setup_at = 0
        self.web = None
        self.nets = []
        self.tank_low = False
        self.tank_told = None
        self.boot = time.time()
        self.lock = plantnet.Lock(binascii.a2b_base64(cfg["k"]))
        self.net = plantnet.Net(self.lock, self.on_msg, self.on_open)
        self.net.busy = lambda: self.pumping
        self.net.online = hw.wifi_ok

    # ---- saved state
    def save_state(self):
        save("state.json", {"d": self.day, "s": self.today, "last": self.last, "sd": self.sdone})

    def save_wifi(self, ssid, pw):
        save("wifi.json", {"ssid": ssid, "pw": pw})

    def roll_day(self):
        now = self.net.now()
        if now is None:
            return  # (no clock yet: the day's total carries on, never reset early)
        d = (now + TZ) // 86400
        if d != self.day:
            self.day = d
            self.today = 0
            self.sdone = []
            self.save_state()

    # ---- the sensors
    def soil(self):
        out = []
        for i in range(self.set["n"]):
            mv = self.hw.soil(i)
            dry, wet = self.set["cal"][i]
            p = None
            if 300 <= mv <= 3150:
                p = int(max(0, min(100, (dry - mv) * 100 // (dry - wet))))
            out.append([p, mv])
        return out

    def tank_empty_now(self):
        n = 0
        for _ in range(5):
            n += self.hw.tank()
        raw = 1 if n >= 3 else 0
        return raw == 1 if self.set["tank"] == "closed" else raw == 0

    def tank_state(self):
        if self.set["tank"] == "none":
            return "none"
        return "empty" if self.tank_empty_now() else "ok"

    def status(self):
        self.roll_day()
        s = self.soil()
        return {"fw": FW, "id": self.cfg.get("id"), "m": [x[0] for x in s], "mv": [x[1] for x in s], "tank": self.tank_state(),
                "today": self.today, "cap": self.set["cap"], "pump": self.pumping, "last": self.last, "set": self.set,
                "clk": self.net.now() is not None, "up": int(time.time() - self.boot), "rssi": self.hw.rssi()}

    # ---- watering (every way in comes through here)
    def can_water(self, secs):
        if self.pumping:
            return False, "busy", 0, False
        if not isnum(secs) or secs < 1:
            return False, "bad", 0, False
        secs = int(min(secs, self.set["max"], HARD_MAX))
        self.roll_day()
        left = self.set["cap"] - self.today
        if left <= 0:
            return False, "cap", 0, False
        capped = secs > left
        if capped:
            secs = left
        if self.tank_state() == "empty":
            return False, "tank", 0, False
        return True, None, secs, capped

    async def water(self, secs, src):
        ok, why, secs, capped = self.can_water(secs)
        if not ok:
            return {"ok": False, "why": why}
        self.pumping = True
        self.stop_req = None
        why = "done"
        t0 = ticks_ms()
        empty = 0
        try:
            self.hw.pump(True)
            self.hw.guard(secs + 2)
            while True:
                await asyncio.sleep(TICK)
                self.hw.feed()
                if ticks_diff(ticks_ms(), t0) >= secs * 1000:
                    break
                if self.stop_req:
                    why = self.stop_req
                    break
                if self.set["tank"] != "none" and self.tank_empty_now():
                    empty += 1
                    if empty >= 5:
                        why = "tank"
                        break
                else:
                    empty = 0
                if src == "phone" and not self.hw.wifi_ok():
                    why = "wifi"
                    break
        finally:
            self.hw.pump(False)
            self.hw.unguard()
            ran = min(secs, (ticks_diff(ticks_ms(), t0) + 999) // 1000)
            self.pumping = False
            self.stop_req = None
            self.today += ran
            self.last = {"at": self.net.now(), "s": ran, "why": why, "src": src}
            self.save_state()
        if why == "tank":
            self.tank_low = True
        return {"ok": True, "ran": ran, "why": why, "asked": secs, "capped": capped}

    # ---- orders from Sir's Jarvis
    def reply(self, rid, o):
        o["re"] = rid
        o.update(self.status())
        return self.net.send(o)

    def on_open(self):
        o = {"ev": "hello"}
        o.update(self.status())
        self.net.send(o)

    def on_msg(self, m):
        c = m.get("c")
        r = m.get("id")
        if c == "status":
            self.reply(r, {"ok": True})
        elif c == "water":
            asyncio.create_task(self.phone_water(r, m.get("s")))
        elif c == "stop":
            was = self.pumping
            if was:
                self.stop_req = "stop"
            self.reply(r, {"ok": True, "stopped": was})
        elif c == "cal":
            self.calibrate(r, m.get("i"), m.get("w"))
        elif c == "set":
            self.set = clean(m.get("set"), self.set)
            save("settings.json", self.set)
            self.reply(r, {"ok": True, "saved": True})
        else:
            self.reply(r, {"ok": False, "why": "unknown"})

    def calibrate(self, r, i, w):
        if not (isinstance(i, int) and 0 <= i < 3 and w in ("dry", "wet")):
            self.reply(r, {"ok": False, "why": "bad"})
            return
        mv = self.hw.soil(i)
        if not 300 <= mv <= 3150:
            self.reply(r, {"ok": False, "why": "sensor", "mv_now": mv})
            return
        cal = [list(x) for x in self.set["cal"]]
        cal[i][0 if w == "dry" else 1] = mv
        if cal[i][0] < cal[i][1] + 150:
            self.reply(r, {"ok": False, "why": "close", "mv_now": mv})
            return
        self.set = clean({"cal": cal}, self.set)
        save("settings.json", self.set)
        self.reply(r, {"ok": True, "mv_now": mv})

    async def phone_water(self, r, secs):
        if not isnum(secs):
            secs = self.set["s"]
        ok, why, n, capped = self.can_water(secs)
        if not ok:
            self.reply(r, {"ok": False, "st": "no", "why": why})
            return
        before = avg(self.soil())
        box = self.reply(r, {"ok": True, "st": "start", "s": n, "capped": capped})
        if not await self.net.wait(box, 30):
            return  # (Jarvis can't hear the board now: no watering it can't see)
        res = await self.water(secs, "phone")
        after = None
        if res.get("ok"):
            await asyncio.sleep(SOAK)
            after = avg(self.soil())
        o = {"st": "done", "before": before, "after": after}
        o.update(res)
        self.reply(r, o)

    # ---- the board's own jobs
    async def sched_task(self):
        while True:
            await asyncio.sleep(SCHED_EVERY)
            now = self.net.now()
            if now is None:
                continue
            self.roll_day()
            mins = ((now + TZ) % 86400) // 60
            for e in self.set["sched"]:
                key = "%d:%02d" % (e["h"], e["m"])
                if not e.get("on") or key in self.sdone:
                    continue
                if 0 <= mins - (e["h"] * 60 + e["m"]) <= 10:
                    self.sdone.append(key)
                    self.save_state()
                    await self.sched_run(key, e)

    async def sched_run(self, key, e):
        before = avg(self.soil())
        why = None
        if e.get("dry"):
            if before is None:
                why = "sensor"
            elif before >= self.set["dry"]:
                why = "wet"
        res = {"ok": False, "why": why} if why else await self.water(e["s"], "sched")
        after = None
        if res.get("ok"):
            await asyncio.sleep(SOAK)
            after = avg(self.soil())
        o = {"ev": "sched", "at": key, "res": res, "before": before, "after": after}
        o.update(self.status())
        self.net.send(o)

    async def btn_task(self):
        while True:
            await asyncio.sleep(0.05)
            if not self.hw.button():
                continue
            t = ticks_ms()
            opened = False
            while self.hw.button():
                await asyncio.sleep(0.05)
                if not opened and ticks_diff(ticks_ms(), t) >= HOLD_SETUP:
                    opened = True
                    self.setup_on("button")
            held = ticks_diff(ticks_ms(), t)
            if opened or held < 50 or held >= 2000:
                continue
            if self.pumping:
                self.stop_req = "button"
            else:
                asyncio.create_task(self.water(BTN_SECS, "button"))

    async def tank_task(self):
        while True:
            await asyncio.sleep(TANK_EVERY)
            if self.set["tank"] == "none" or self.pumping:
                self.tank_low = False if self.set["tank"] == "none" else self.tank_low
                continue
            low = self.tank_empty_now()
            if low and not self.tank_low:
                now = time.time()
                if self.tank_told is None or now - self.tank_told > 6 * 3600:
                    self.tank_told = now
                    o = {"ev": "tank"}
                    o.update(self.status())
                    self.net.send(o)
            self.tank_low = low

    async def led_task(self):
        while True:
            if self.pumping:
                pat = (100, 100)
            elif self.setup:
                pat = (1000, 1000)
            elif not self.hw.wifi_ok():
                pat = (200, 200)
            elif self.tank_low:
                pat = (150, 150, 150, 1550)
            elif self.net.ok:
                pat = None
            else:
                pat = (900, 100)
            if pat is None:
                self.hw.led(True)
                await asyncio.sleep(0.5)
                continue
            on = True
            for d in pat:
                self.hw.led(on)
                on = not on
                await asyncio.sleep(d / 1000)

    async def dog_task(self):
        n = 0
        while True:
            self.hw.feed()
            await asyncio.sleep(2)
            n += 1
            if n % 15 == 0:
                gc.collect()

    def setup_on(self, why):
        if self.setup:
            return
        import plantweb
        self.setup = why
        self.setup_at = ticks_ms()
        self.hw.ap_on("JARVIS-PLANT-" + str(self.cfg.get("id", "")), self.cfg["ap"])
        if self.web is None:
            self.web = asyncio.create_task(plantweb.serve(self))

    def setup_off(self):
        if not self.setup:
            return
        self.setup = None
        self.hw.ap_off()
        if self.web:
            self.web.cancel()
            self.web = None

    def scan(self):
        self.nets = self.hw.scan()

    async def wifi_task(self):
        w = load("wifi.json")
        if not w or not w.get("ssid"):
            self.setup_on("new")
            while True:
                await asyncio.sleep(3600)  # (saving the page restarts the board)
        self.hw.sta_connect(w["ssid"], w.get("pw", ""))
        tried = ticks_ms()
        lost = ticks_ms()
        while True:
            await asyncio.sleep(1)
            if self.hw.wifi_ok():
                lost = ticks_ms()
                if self.setup == "lost":
                    self.setup_off()
                if self.setup == "button" and ticks_diff(ticks_ms(), self.setup_at) > SETUP_FOR:
                    self.setup_off()
                continue
            if self.pumping:
                continue
            if ticks_diff(ticks_ms(), tried) > RETRY:
                tried = ticks_ms()
                self.hw.sta_connect(w["ssid"], w.get("pw", ""))
            if not self.setup and ticks_diff(ticks_ms(), lost) > LOST_SETUP:
                self.setup_on("lost")

    async def keep(self, fn, name):
        while True:
            try:
                await fn()
            except Exception as e:
                print("task", name, e)
            await asyncio.sleep(2)

    async def main(self, setup_now=False):
        self.hw.pump(False)
        self.hw.watchdog()
        if setup_now:
            self.setup_on("button")
        for fn, name in ((self.wifi_task, "wifi"), (self.net.run_stream, "stream"), (self.net.run_post, "post"),
                         (self.btn_task, "button"), (self.led_task, "led"), (self.sched_task, "sched"),
                         (self.tank_task, "tank"), (self.dog_task, "dog")):
            asyncio.create_task(self.keep(fn, name))
        while True:
            await asyncio.sleep(3600)


def run():
    hw = HW()
    cfg = load("config.json")
    if not cfg or "k" not in cfg or "ap" not in cfg:
        print("no config.json: set this board up from Jarvis over USB")
        while True:
            hw.led(True)
            time.sleep(0.1)
            hw.led(False)
            time.sleep(1.9)
    b = Board(hw, cfg)
    asyncio.run(b.main(hw.button()))
