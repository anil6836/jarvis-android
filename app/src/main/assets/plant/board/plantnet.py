# Jarvis plant board: the locked messenger. Only Sir's Jarvis has the key, so only it can send
# orders here or read what the board says. Messages pass through ntfy.sh, which sees only locked text.
import json, os, time, binascii, hashlib
try:
    import asyncio
except ImportError:
    import uasyncio as asyncio
try:
    from cryptolib import aes
except ImportError:
    from ucryptolib import aes
try:
    from time import ticks_ms, ticks_diff
except ImportError:
    from time import monotonic as _mono

    def ticks_ms():
        return int(_mono() * 1000)

    def ticks_diff(a, b):
        return a - b

HOST = "ntfy.sh"
PORT = 443      # orders come in only over TLS
TLS = True
PPORT = 80      # what the board says goes out on plain HTTP first: it is locked anyway, and no TLS start
FRESH = 300     # an order older than 5 minutes is not done
QUIET = 100000  # ntfy says something every 45 s; nothing for this long = reconnect


def sha(b):
    return hashlib.sha256(b).digest()


def hmac(k, m):
    if len(k) > 64:
        k = sha(k)
    k = k + b"\0" * (64 - len(k))
    ip = bytearray(64)
    op = bytearray(64)
    for i in range(64):
        ip[i] = k[i] ^ 0x36
        op[i] = k[i] ^ 0x5C
    return sha(bytes(op) + sha(bytes(ip) + m))


def same(a, b):
    if len(a) != len(b):
        return False
    r = 0
    for i in range(len(a)):
        r |= a[i] ^ b[i]
    return r == 0


def hexs(b):
    return binascii.hexlify(b).decode()


def rid():
    return hexs(os.urandom(6))


class Lock:
    """AES-128-CBC + HMAC-SHA256 (16 bytes), keys made from the 32-byte key Jarvis wrote over USB."""

    def __init__(self, key):
        self.ke = sha(key + b"enc")[:16]
        self.km = sha(key + b"mac")
        self.down = "jp" + hexs(sha(key + b"down"))[:30]
        self.up = "jp" + hexs(sha(key + b"up"))[:30]

    def seal(self, obj, d=b"u"):
        p = json.dumps(obj).encode()
        n = 16 - len(p) % 16
        p = p + bytes([n]) * n
        iv = os.urandom(16)
        c = aes(self.ke, 2, iv).encrypt(p)
        t = hmac(self.km, d + iv + c)[:16]
        return binascii.b2a_base64(iv + c + t).decode().strip()

    def open(self, s, d=b"d"):
        try:
            if isinstance(s, str):
                s = s.encode()
            b = binascii.a2b_base64(s)
        except Exception:
            return None
        if len(b) < 48 or (len(b) - 32) % 16:
            return None
        iv = b[:16]
        c = b[16:-16]
        if not same(hmac(self.km, d + iv + c)[:16], b[-16:]):
            return None
        p = aes(self.ke, 2, iv).decrypt(c)
        n = p[-1]
        if n < 1 or n > 16:
            return None
        for x in p[-n:]:
            if x != n:
                return None
        try:
            o = json.loads(p[:-n].decode())
        except Exception:
            return None
        return o if isinstance(o, dict) else None


def tls():
    import ssl
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    try:
        c.check_hostname = False
    except Exception:
        pass
    c.verify_mode = ssl.CERT_NONE  # (the lock above keeps the words safe; TLS only hides the order topic)
    return c


class Rd:
    """Reads a connection into its own buffer, so a timeout never loses a half line."""

    def __init__(self, r):
        self.r = r
        self.b = b""

    async def fill(self, t):
        d = await asyncio.wait_for(self.r.read(2048), t)
        if not d:
            raise EOFError
        self.b += d

    async def line(self, t):
        while b"\n" not in self.b:
            if len(self.b) > 8192:
                raise OSError("long line")
            await self.fill(t)
        i = self.b.index(b"\n") + 1
        l = self.b[:i]
        self.b = self.b[i:]
        return l

    async def part(self, n, t):
        if not self.b:
            await self.fill(t)
        d = self.b[:n]
        self.b = self.b[n:]
        return d


class Body:
    """The stream's lines, with or without chunked transfer."""

    def __init__(self, rd, chunked):
        self.rd = rd
        self.ch = chunked
        self.left = 0
        self.buf = b""

    async def line(self, t):
        if not self.ch:
            return await self.rd.line(t)
        while b"\n" not in self.buf:
            if self.left == 0:
                sz = (await self.rd.line(t)).strip()
                if not sz:
                    continue
                n = int(sz.split(b";")[0].decode(), 16)
                if n == 0:
                    raise EOFError
                self.left = n
            d = await self.rd.part(self.left, t)
            self.left -= len(d)
            self.buf += d
            if len(self.buf) > 8192:
                raise OSError("long line")
        i = self.buf.index(b"\n") + 1
        l = self.buf[:i]
        self.buf = self.buf[i:]
        return l


class Box:
    def __init__(self, text):
        self.text = text
        self.ev = asyncio.Event()
        self.ok = False
        self.tries = 0


class Net:
    def __init__(self, lock, on_msg, on_open=None):
        self.lock = lock
        self.on_msg = on_msg
        self.on_open = on_open
        self.out = []
        self.last_id = None
        self.lost_at = None
        self.off = None      # unix time - time.time(), from ntfy's clock
        self.ip = None
        self.ok = False      # the order stream is open
        self.opened = False
        self.seen = []
        self.busy = lambda: False    # the pump runs: no new TLS start now
        self.online = lambda: True   # the home Wi-Fi is up

    def now(self):
        return None if self.off is None else int(time.time() + self.off)

    def send(self, obj):
        obj["t"] = self.now() or 0
        obj["u"] = rid()
        b = Box(self.lock.seal(obj))
        self.out.append(b)
        return b

    async def wait(self, box, t):
        try:
            await asyncio.wait_for(box.ev.wait(), t)
        except asyncio.TimeoutError:
            return False
        return box.ok

    async def resolve(self):
        if self.ip is None:
            import socket
            self.ip = socket.getaddrinfo(HOST, PORT)[0][-1][0]
        return self.ip

    async def conn(self, port, secure):
        ip = await self.resolve()
        if secure:
            c = asyncio.open_connection(ip, port, ssl=tls(), server_hostname=HOST)
        else:
            c = asyncio.open_connection(ip, port)
        return await asyncio.wait_for(c, 20)

    async def close(self, w):
        try:
            w.close()
            await w.wait_closed()
        except Exception:
            pass

    # ---- orders in: one long-lived stream of the order topic
    async def run_stream(self):
        back = 2
        while True:
            if not self.online() or (self.busy() and self.ip is None):
                self.ok = False
                await asyncio.sleep(1)
                continue
            if self.busy():  # (no TLS start while the pump runs: the board waits)
                await asyncio.sleep(0.5)
                continue
            try:
                await self.listen()
                back = 2
            except Exception as e:
                print("stream:", e)
                if isinstance(e, OSError) and not self.ok:
                    self.ip = None
            self.ok = False
            await asyncio.sleep(back)
            back = min(back * 2, 60)

    async def listen(self):
        r, w = await self.conn(PORT, TLS)
        try:
            q = ""
            if self.last_id and self.lost_at is not None and ticks_diff(ticks_ms(), self.lost_at) < 120000:
                q = "?since=" + self.last_id
            w.write(("GET /%s/json%s HTTP/1.1\r\nHost: %s\r\nUser-Agent: jarvis-plant\r\nConnection: close\r\n\r\n"
                     % (self.lock.down, q, HOST)).encode())
            await w.drain()
            rd = Rd(r)
            st = await rd.line(20)
            if b" 200" not in st:
                raise OSError("http " + st.decode().strip())
            ch = False
            while True:
                h = (await rd.line(20)).lower()
                if h in (b"\r\n", b"\n"):
                    break
                if h.startswith(b"transfer-encoding:") and b"chunked" in h:
                    ch = True
            body = Body(rd, ch)
            self.ok = True
            quiet = ticks_ms()
            while True:
                try:
                    l = await body.line(1)
                except asyncio.TimeoutError:
                    if ticks_diff(ticks_ms(), quiet) > QUIET or not self.online():
                        raise OSError("quiet")
                    continue
                quiet = ticks_ms()
                self.event(l)
        finally:
            self.ok = False
            self.lost_at = ticks_ms()
            await self.close(w)

    def event(self, l):
        try:
            e = json.loads(l.decode())
        except Exception:
            return
        if not isinstance(e, dict):
            return
        t = e.get("time")
        if isinstance(t, int) and t > 1700000000:
            self.off = t - time.time()
        kind = e.get("event")
        if kind == "open" and not self.opened:
            self.opened = True
            if self.on_open:
                self.on_open()
        if kind != "message":
            return
        self.last_id = e.get("id")
        m = self.lock.open(e.get("message", ""), b"d")
        if m is None:
            return
        ct = m.get("t")
        now = self.now()
        if not isinstance(ct, int) or not isinstance(t, int) or now is None:
            return
        if abs(ct - t) > FRESH or now - t > FRESH:
            return
        mid = m.get("id")
        if not mid or mid in self.seen:
            return
        self.seen.append(mid)
        self.seen = self.seen[-30:]
        self.on_msg(m)

    # ---- the board's words out
    async def run_post(self):
        while True:
            if not self.out or not self.online():
                await asyncio.sleep(0.2)
                continue
            b = self.out[0]
            ok = False
            try:
                ok = await self.post(b.text, PPORT, False)
            except Exception as e:
                print("post:", e)
            if not ok and not self.busy():
                try:
                    ok = await self.post(b.text, PORT, True)
                except Exception as e:
                    print("post tls:", e)
            if not ok:
                b.tries += 1
                if b.tries < 4:
                    await asyncio.sleep(3)
                    continue
            self.out.pop(0)
            b.ok = ok
            b.ev.set()

    async def post(self, text, port, secure):
        if self.ip is None and self.busy():
            return False
        r, w = await self.conn(port, secure)
        try:
            body = text.encode()
            w.write(("POST /%s HTTP/1.1\r\nHost: %s\r\nUser-Agent: jarvis-plant\r\nContent-Type: text/plain\r\n"
                     "Content-Length: %d\r\nConnection: close\r\n\r\n" % (self.lock.up, HOST, len(body))).encode() + body)
            await w.drain()
            st = await Rd(r).line(20)
            return b" 200" in st
        finally:
            await self.close(w)
