#!/usr/bin/env python3
"""HTTP-реле для ClientLink (когда на сервере нельзя поставить плагин).

Запуск:   python3 swing_relay.py
Переменные: PORT (по умолчанию 8765), SWING_TOKEN (необязательный общий секрет, клиент шлёт его в X-Swing-Token).
Хранит только в памяти: кто сейчас с клиентом на каком сервере и что слушает. Записи живут 90 секунд.
"""
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("PORT", "8765"))
TOKEN = os.environ.get("SWING_TOKEN", "")
TTL = 90
MAX_SERVERS = 200
MAX_PER_SERVER = 500
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
NAME_RE = re.compile(r"^[A-Za-z0-9_.]{1,32}$")

DATA = {}   # server -> {uuid: entry}
HITS = {}   # ip -> [timestamps]
LOCK = threading.Lock()


def clean(s):
    s = "".join(ch for ch in str(s) if ord(ch) >= 32 and ch != "\u00a7" and ord(ch) != 127)
    return s.strip()[:80]


def purge(now):
    for srv in list(DATA):
        d = DATA[srv]
        for u in [u for u, e in d.items() if now - e["ts"] > TTL]:
            del d[u]
        if not d:
            del DATA[srv]


def limited(ip, now):
    h = [t for t in HITS.get(ip, []) if now - t < 10]
    h.append(now)
    HITS[ip] = h
    return len(h) > 30


class Handler(BaseHTTPRequestHandler):
    server_version = "SwingRelay/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj=None):
        body = json.dumps(obj if obj is not None else {}).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth(self):
        return not TOKEN or self.headers.get("X-Swing-Token", "") == TOKEN

    def do_POST(self):
        now = time.time()
        if not self._auth():
            return self._send(403)
        if urlparse(self.path).path != "/hello":
            return self._send(404)
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > 2048:
                return self._send(413)
            o = json.loads(self.rfile.read(n).decode("utf-8"))
            server = clean(o["server"]).lower()[:100]
            uid = str(o["uuid"]).lower()
            name = str(o["name"])
            if not server or not UUID_RE.match(uid) or not NAME_RE.match(name):
                return self._send(400)
            state = int(o.get("state", 0))
            entry = {"uuid": uid, "name": name, "state": state if state in (0, 1, 2) else 0,
                     "title": clean(o.get("title", "")), "artist": clean(o.get("artist", "")), "ts": now}
        except Exception:
            return self._send(400)
        with LOCK:
            if limited(self.client_address[0], now):
                return self._send(429)
            purge(now)
            if server not in DATA and len(DATA) >= MAX_SERVERS:
                return self._send(503)
            d = DATA.setdefault(server, {})
            if uid not in d and len(d) >= MAX_PER_SERVER:
                return self._send(503)
            d[uid] = entry
        self._send(200)

    def do_GET(self):
        now = time.time()
        if not self._auth():
            return self._send(403)
        u = urlparse(self.path)
        if u.path != "/peers":
            return self._send(404)
        server = clean(parse_qs(u.query).get("server", [""])[0]).lower()[:100]
        with LOCK:
            if limited(self.client_address[0], now):
                return self._send(429)
            purge(now)
            out = [{k: v for k, v in e.items() if k != "ts"} for e in DATA.get(server, {}).values()]
        self._send(200, out)


if __name__ == "__main__":
    print("SwingRelay on :%d%s" % (PORT, " (token required)" if TOKEN else ""))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
