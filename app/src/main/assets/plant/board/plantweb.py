# Jarvis plant board: the setup page. It is reachable only on the board's own locked Wi-Fi
# (JARVIS-PLANT-xxxx). Sir types the home Wi-Fi password here himself; it stays on this board.
import json, socket
try:
    import asyncio
except ImportError:
    import uasyncio as asyncio

IP = "192.168.4.1"
NET = "192.168.4."
PORT = 80
DNS_PORT = 53
BIND = "0.0.0.0"   # (DNS only; the page itself listens on the board's own address)

STYLE = ("body{font-family:sans-serif;background:#0d1f17;color:#eafff3;margin:0;padding:18px;max-width:520px}"
         "h1{font-size:22px;margin:4px 0 10px}label{display:block;margin:16px 0 6px;font-weight:bold}"
         "select,input,button{width:100%;font-size:18px;padding:12px;border-radius:10px;border:1px solid #3c7;"
         "box-sizing:border-box;background:#fff;color:#000}button{background:#2b8a57;color:#fff;margin-top:20px;border:0}"
         "p{line-height:1.55}a{color:#8fe3b4}small{color:#9cb;display:block;margin-top:22px}.ok{font-size:20px}")


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def unq(s):
    s = s.replace(b"+", b" ")
    out = bytearray()
    i = 0
    while i < len(s):
        c = s[i]
        if c == 37 and i + 2 < len(s):
            try:
                out.append(int(s[i + 1:i + 3].decode(), 16))
                i += 3
                continue
            except Exception:
                pass
        out.append(c)
        i += 1
    return bytes(out).decode()


def form(body):
    o = {}
    for part in body.split(b"&"):
        if b"=" in part:
            k, v = part.split(b"=", 1)
            try:
                o[unq(k)] = unq(v)
            except Exception:
                pass
    return o


def page(title, inner):
    return ("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>%s</title><style>%s</style></head><body>%s</body></html>" % (title, STYLE, inner)).encode()


def home(board, msg=""):
    opts = ""
    for n in board.nets:
        opts += "<option>%s</option>" % esc(n)
    if not opts:
        opts = "<option value=''>(ఏ Wi-Fi కనిపించలేదు: కింద పేరు రాయండి)</option>"
    inner = ("<h1>🌱 Jarvis మొక్కల బోర్డ్</h1>"
             "<p>ఇంటి Wi-Fi ఎంచుకుని, దాని పాస్‌వర్డ్ <b>మీరే</b> టైప్ చేయండి. అది ఈ బోర్డ్‌లోనే ఉంటుంది, Jarvis కి వెళ్ళదు.</p>"
             + ("<p style='color:#ffb4a8'>%s</p>" % msg if msg else "")
             + "<form method='post' action='/save'><label>ఇంటి Wi-Fi</label><select name='ssid'>" + opts + "</select>"
             "<label>లిస్ట్‌లో లేకపోతే, పేరు ఇక్కడ</label><input name='ssid2' autocomplete='off' autocapitalize='off'>"
             "<label>పాస్‌వర్డ్</label><input name='pw' type='password' autocomplete='off' autocapitalize='off'>"
             "<button>సేవ్ చేయి</button></form>"
             "<p><a href='/scan'>🔄 Wi-Fi లు మళ్ళీ వెతుకు</a></p>"
             "<small>ESP32 కి 2.4 GHz Wi-Fi మాత్రమే కనిపిస్తుంది. బోర్డ్ %s · ప్రోగ్రామ్ %s</small>"
             % (esc(str(board.cfg.get("id", ""))), board.fw))
    return page("Jarvis మొక్కల బోర్డ్", inner)


def done_page():
    return page("సేవ్ అయింది", "<h1>✅ సేవ్ అయింది</h1><p class='ok'>బోర్డ్ ఇప్పుడు ఇంటి Wi-Fi కి కలుస్తోంది.</p>"
                "<p>ఫోన్‌ని మీ ఇంటి Wi-Fi కి మార్చి, Jarvis లో 🌱 స్క్రీన్‌లో <b>“బోర్డ్ కలిసిందా?”</b> నొక్కండి.</p>"
                "<p>నీలం లైట్ ఆగకుండా వెలిగితే కలిసినట్టు.</p>")


def resp(w, code, body=b"", loc=None):
    h = ("HTTP/1.1 %s\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nCache-Control: no-store\r\n"
         "Connection: close\r\n" % (code, len(body)))
    if loc:
        h += "Location: %s\r\n" % loc
    w.write(h.encode() + b"\r\n" + body)


async def handle(board, r, w):
    try:
        peer = w.get_extra_info("peername")
        if not peer or not str(peer[0]).startswith(NET):
            return
        line = await asyncio.wait_for(r.readline(), 10)
        parts = line.split()
        if len(parts) < 2:
            return
        meth, path = parts[0], parts[1].split(b"?")[0]
        clen = 0
        host = b""
        for _ in range(40):
            h = await asyncio.wait_for(r.readline(), 10)
            if not h or h in (b"\r\n", b"\n"):
                break
            hl = h.lower()
            if hl.startswith(b"content-length:"):
                clen = int(h[15:].strip().decode())
            elif hl.startswith(b"host:"):
                host = h[5:].strip()
        if clen > 2048:
            resp(w, "413 Too Large")
            return
        body = await asyncio.wait_for(r.readexactly(clen), 10) if clen else b""
        if host and host.split(b":")[0] != IP.encode():
            resp(w, "302 Found", b"", "http://%s/" % IP)  # (the phone's "is there internet?" check: here is the page)
        elif meth == b"POST" and path == b"/save":
            f = form(body)
            ssid = (f.get("ssid2") or "").strip() or f.get("ssid", "")
            pw = f.get("pw", "")
            if not ssid or len(ssid.encode()) > 32:
                resp(w, "200 OK", home(board, "Wi-Fi పేరు సరిగా లేదు. మళ్ళీ ఎంచుకోండి."))
            elif pw and not (8 <= len(pw.encode()) <= 63):
                resp(w, "200 OK", home(board, "Wi-Fi పాస్‌వర్డ్ 8 నుంచి 63 అక్షరాలు ఉండాలి. మళ్ళీ టైప్ చేయండి."))
            else:
                board.save_wifi(ssid, pw)
                resp(w, "200 OK", done_page())
                await w.drain()
                await asyncio.sleep(3)
                board.hw.reset()  # (a clean start on the home Wi-Fi)
                return
        elif path == b"/scan":
            board.scan()
            resp(w, "302 Found", b"", "/")
        elif path in (b"/", b"/index.html"):
            resp(w, "200 OK", home(board))
        else:
            resp(w, "302 Found", b"", "http://%s/" % IP)
        await w.drain()
    except Exception as e:
        print("web:", e)
    finally:
        try:
            w.close()
            await w.wait_closed()
        except Exception:
            pass


def dns_reply(q, ip):
    if len(q) < 12:
        return None
    i = 12
    while i < len(q) and q[i] != 0:
        i += q[i] + 1
    i += 5
    if i > len(q):
        return None
    head = q[:2] + b"\x81\x80" + q[4:6]
    question = q[12:i]
    if q[i - 4:i - 2] == b"\x00\x01":
        a = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04" + bytes([int(x) for x in ip.split(".")])
        return head + b"\x00\x01\x00\x00\x00\x00" + question + a
    return head + b"\x00\x00\x00\x00\x00\x00" + question


async def dns(board):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    except Exception:
        pass
    s.bind((BIND, DNS_PORT))
    s.setblocking(False)
    try:
        while True:
            try:
                q, a = s.recvfrom(512)
            except OSError:
                await asyncio.sleep(0.05)
                continue
            r = dns_reply(q, IP)
            if r:
                try:
                    s.sendto(r, a)
                except OSError:
                    pass
    finally:
        s.close()


async def serve(board):
    board.scan()
    srv = await asyncio.start_server(lambda r, w: handle(board, r, w), IP, PORT)
    d = asyncio.create_task(dns(board))
    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        d.cancel()
        srv.close()
        try:
            await srv.wait_closed()
        except Exception:
            pass
