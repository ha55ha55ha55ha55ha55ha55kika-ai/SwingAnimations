#!/usr/bin/env python3
"""HTTP-реле для ClientLink (когда на сервере нельзя поставить плагин). Версия 2: передаёт ещё и косметику,
чтобы все игроки с модом видели косметику друг друга.

Запуск:   python3 swing_relay.py
При старте сам печатает ссылку для мода и токен (готовые строки для вставки в ClientLinkModule.java).
Переменные (все необязательные): PORT (если нет, берётся SERVER_PORT панели, иначе 8765), KITKAT_TOKEN или SWING_TOKEN (общий секрет;
если не задан, берётся из swing_token.txt / kitkat_token.txt рядом со скриптом, а если их нет, создаётся случайный и запоминается
в swing_token.txt), PUBLIC_URL (если ссылку не удалось определить сама), TRUST_PROXY=1 (если хостинг стоит за прокси).
Токен принимается в заголовке X-KitKat-Token (новый клиент) или X-Swing-Token (старый): обновлять токен не нужно.
Хранит только в памяти: кто сейчас с клиентом на каком сервере, что слушает и какую косметику носит. Записи живут 90 секунд.
"""
import hmac
import json
import math
import os
import re
import secrets
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# --- Настройки (можно поменять тут или задать переменными окружения хостинга) ---
# Порт: PORT, иначе SERVER_PORT (его выставляют панели Pterodactyl/Wispbyte: это тот порт, который открыт снаружи), иначе 8765.
PORT = int(os.environ.get("PORT") or os.environ.get("SERVER_PORT") or "8765")
TOKEN = os.environ.get("KITKAT_TOKEN") or os.environ.get("SWING_TOKEN", "")   # общий пароль; если не задан, создаётся сам (см. load_token)
TRUST_PROXY = os.environ.get("TRUST_PROXY", "") == "1"  # 1, если хостинг стоит за прокси (берём IP из X-Forwarded-For)
TTL = 90                                          # запись живёт 90 с; клиент шлёт /hello каждые 30-45 с
MAX_SERVERS = 200
MAX_PER_SERVER = 500
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
NAME_RE = re.compile(r"^[A-Za-z0-9_.]{1,32}$")
VERSION = 2
# Косметика: "id=1,размер,x,y,z;id2=1,..." (только надетые предметы). Лимиты те же, что в клиенте (CosmeticState).
COS_ID_RE = re.compile(r"^[a-z0-9_]{1,40}$")
COS_MAX_ITEMS = 16
COS_MAX_LEN = 1000
COS_SCALE = (0.3, 3.0)
COS_POS = (-20.0, 20.0)
MAX_BODY = 4096
# Дневник ("/l <текст>" в игре): построчный JSON-лог, независимо от DATA (не имеет TTL, не теряется).
DIARY_LOG = os.environ.get("DIARY_LOG") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "diary.log")
DIARY_MAX_LEN = 400
DIARY_LOCK = threading.Lock()

DATA = {}   # server -> {uuid: entry}
HITS = {}   # ip -> [timestamps]
LOCK = threading.Lock()


def clean(s):
    s = "".join(ch for ch in str(s) if ord(ch) >= 32 and ch != "\u00a7" and ord(ch) != 127)
    return s.strip()[:80]


def sanitize_cosmetics(raw):
    """Пересобирает строку косметики в безопасный канонический вид; всё лишнее и битое выбрасывает.
    Возвращает "" если нет ни одного корректного надетого предмета."""
    if not isinstance(raw, str) or not raw or len(raw) > COS_MAX_LEN:
        return ""
    out = []
    seen = set()
    for part in raw.split(";")[:COS_MAX_ITEMS]:
        item, eq, rest = part.partition("=")
        if not eq or not COS_ID_RE.match(item) or item in seen:
            continue
        f = rest.split(",")
        if len(f) != 5 or f[0] != "1":
            continue                      # не надет или не тот формат
        try:
            nums = [float(x) for x in f[1:]]
        except ValueError:
            continue
        if not all(math.isfinite(x) for x in nums):
            continue
        scale = min(max(nums[0], COS_SCALE[0]), COS_SCALE[1])
        x, y, z = (min(max(v, COS_POS[0]), COS_POS[1]) for v in nums[1:])
        out.append("%s=1,%.3f,%.2f,%.2f,%.2f" % (item, scale, x, y, z))
        seen.add(item)
    return ";".join(out)


def clean_diary(s):
    """Как clean(), но с лимитом длины дневниковой записи (он больше, чем у короткого текста трека)."""
    if not isinstance(s, str):
        return ""
    s = "".join(ch for ch in s if ord(ch) >= 32 and ch != "\u00a7" and ord(ch) != 127)
    return s.strip()[:DIARY_MAX_LEN]


def purge(now):
    for srv in list(DATA):
        d = DATA[srv]
        for u in [u for u, e in d.items() if now - e["ts"] > TTL]:
            del d[u]
        if not d:
            del DATA[srv]
    # Старые записи по IP не копим бесконечно.
    for ip in list(HITS):
        if not any(now - t < 10 for t in HITS[ip]):
            del HITS[ip]


def limited(ip, now):
    h = [t for t in HITS.get(ip, []) if now - t < 10]
    h.append(now)
    HITS[ip] = h
    return len(h) > 30


def load_token():
    """Токен из SWING_TOKEN; иначе из swing_token.txt; иначе создаём случайный и сохраняем (чтобы не менялся при перезапуске)."""
    if TOKEN:
        return TOKEN
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "swing_token.txt")
    for name in ("swing_token.txt", "kitkat_token.txt"):   # токен из прошлых версий, чтобы пароль не менялся
        try:
            with open(os.path.join(here, name), encoding="utf-8") as f:
                t = f.read().strip()
            if t:
                return t
        except OSError:
            continue
    t = secrets.token_urlsafe(18)
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(t + "\n")
    except OSError:
        print("  (не удалось сохранить токен в %s, при перезапуске он изменится; задайте KITKAT_TOKEN)" % path)
    return t


def detect_public_url():
    """Адрес реле для мода: PUBLIC_URL, переменные популярных хостингов, иначе внешний IP хоста + порт."""
    for name in ("PUBLIC_URL", "RELAY_URL"):
        v = os.environ.get(name, "").strip().rstrip("/")
        if v:
            return v if v.startswith("http") else "https://" + v
    for name, tmpl in (("RAILWAY_PUBLIC_DOMAIN", "https://%s"), ("RENDER_EXTERNAL_URL", "%s"),
                       ("KOYEB_PUBLIC_DOMAIN", "https://%s"), ("FLY_APP_NAME", "https://%s.fly.dev")):
        v = os.environ.get(name, "").strip().rstrip("/")
        if v:
            return tmpl % v
    for svc in ("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"):
        try:
            req = urllib.request.Request(svc, headers={"User-Agent": "swing-relay"})
            with urllib.request.urlopen(req, timeout=4) as r:
                ip = r.read().decode("utf-8").strip()
            if re.match(r"^[0-9a-fA-F:.]{3,45}$", ip):
                return "http://%s:%d" % (ip if ":" not in ip else "[%s]" % ip, PORT)
        except Exception:
            continue
    return ""


def print_banner(url, token):
    line = "=" * 62
    print(line)
    print(" KitKatRelay v%d запущен (порт %d), косметика включена" % (VERSION, PORT))
    print(line)
    if url:
        print(" Ссылка для мода:  " + url)
    else:
        print(" Ссылку определить не удалось (нет доступа в интернет?).")
        print(" Узнай адрес в панели хостинга и впиши его вместо ССЫЛКА ниже,")
        print(" либо задай переменную PUBLIC_URL и перезапусти.")
        url = "ССЫЛКА"
    print(" Токен:            " + token)
    print("-" * 62)
    print(" Вставь в ClientLinkModule.java перед сборкой мода:")
    print('   private static final String DEFAULT_RELAY_URL = "%s";' % url)
    print('   private static final String DEFAULT_RELAY_TOKEN = "%s";' % token)
    print(" или (без пересборки) в config/kitkatvisuals_modules.json, модуль client_link:")
    print('   "relay_url": "%s", "relay_token": "%s"' % (url, token))
    print("-" * 62)
    if url.startswith("https://") and not TRUST_PROXY:
        print(" Похоже, хостинг стоит за прокси: задай TRUST_PROXY=1, иначе все игроки")
        print(" будут для реле одним IP и начнут получать 429.")
    print(" Проверка: открой ссылку в браузере, должно быть {\"ok\": true, ...}")
    print(line, flush=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "KitKatRelay/2.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj=None):
        body = json.dumps(obj if obj is not None else {}).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ip(self):
        if TRUST_PROXY:
            fwd = self.headers.get("X-Forwarded-For", "")
            if fwd:
                return fwd.split(",")[0].strip()[:64]
        return self.client_address[0]

    def _auth(self):
        if not TOKEN:
            return True
        # Новый клиент шлёт X-KitKat-Token, старый X-Swing-Token: принимаем оба.
        want = TOKEN.encode("utf-8")
        for h in ("X-KitKat-Token", "X-Swing-Token"):
            got = self.headers.get(h, "")
            if got and hmac.compare_digest(got.encode("utf-8"), want):
                return True
        return False

    def do_POST(self):
        now = time.time()
        if not self._auth():
            return self._send(403)
        path = urlparse(self.path).path
        if path == "/hello":
            return self._do_hello(now)
        if path == "/diary":
            return self._do_diary(now)
        return self._send(404)

    def _do_hello(self, now):
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > MAX_BODY:
                return self._send(413)
            o = json.loads(self.rfile.read(n).decode("utf-8"))
            server = clean(o["server"]).lower()[:100]
            uid = str(o["uuid"]).lower()
            name = str(o["name"])
            if not server or not UUID_RE.match(uid) or not NAME_RE.match(name):
                return self._send(400)
            state = int(o.get("state", 0))
            entry = {"uuid": uid, "name": name, "state": state if state in (0, 1, 2) else 0,
                     "title": clean(o.get("title", "")), "artist": clean(o.get("artist", "")),
                     "cosmetics": sanitize_cosmetics(o.get("cosmetics", "")), "ts": now}
        except Exception:
            return self._send(400)
        with LOCK:
            if limited(self._ip(), now):
                return self._send(429)
            purge(now)
            if server not in DATA and len(DATA) >= MAX_SERVERS:
                return self._send(503)
            d = DATA.setdefault(server, {})
            if uid not in d and len(d) >= MAX_PER_SERVER:
                return self._send(503)
            d[uid] = entry
        self._send(200)

    def _do_diary(self, now):
        """/l <текст> в игре: клиент шлёт сюда копию для постоянного лога (не влияет на DATA/TTL)."""
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > MAX_BODY:
                return self._send(413)
            o = json.loads(self.rfile.read(n).decode("utf-8"))
            server = clean(o["server"]).lower()[:100]
            uid = str(o["uuid"]).lower()
            name = str(o["name"])
            text = clean_diary(o.get("text", ""))
            if not server or not UUID_RE.match(uid) or not NAME_RE.match(name) or not text:
                return self._send(400)
        except Exception:
            return self._send(400)
        with LOCK:
            if limited(self._ip(), now):
                return self._send(429)
        line = json.dumps({"ts": now, "server": server, "uuid": uid, "name": name, "text": text}, ensure_ascii=False)
        try:
            with DIARY_LOCK:
                with open(DIARY_LOG, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
        except OSError:
            return self._send(500)
        self._send(200)

    def do_GET(self):
        now = time.time()
        u = urlparse(self.path)
        if u.path == "/":
            return self._send(200, {"ok": True, "service": "swing-relay", "version": VERSION, "features": ["cosmetics", "diary"]})  # проверка, что сервис жив
        if not self._auth():
            return self._send(403)
        if u.path != "/peers":
            return self._send(404)
        server = clean(parse_qs(u.query).get("server", [""])[0]).lower()[:100]
        with LOCK:
            if limited(self._ip(), now):
                return self._send(429)
            purge(now)
            out = [{k: v for k, v in e.items() if k != "ts"} for e in DATA.get(server, {}).values()]
        self._send(200, out)


if __name__ == "__main__":
    TOKEN = load_token()
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)   # порт занят или закрыт: ошибка видна сразу
    print_banner(detect_public_url(), TOKEN)
    httpd.serve_forever()
