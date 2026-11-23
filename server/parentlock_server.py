#!/usr/bin/env python3
"""Parent control site for the "Защита" Android app. Standard library only.

Run behind nginx (HTTPS). Listens on 127.0.0.1:PORT.
  python3 parentlock_server.py serve [--port 8765] [--db /var/lib/parentlock/db.sqlite]
  python3 parentlock_server.py set-password [--slot 2]   (reads the new password from stdin;
                                                           slot 2 = second parent)
"""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
DB_PATH = os.environ.get("PARENTLOCK_DB", "/var/lib/parentlock/db.sqlite")
PORT = int(os.environ.get("PARENTLOCK_PORT", "8765"))

DEFAULT_CONFIG = {
    "limit": 0, "sleep_s": -1, "sleep_e": -1, "limit_we": -1, "sleep_we_s": -1, "sleep_we_e": -1,
    "allowed": [], "blocked": [], "applimits": {}, "locked": False, "filter": False,
    "play_free": False, "apk": False, "debug": False, "accounts": False,
    "perm_policy": 0, "perms": {},
}
FLAGS = ("locked", "filter", "play_free", "apk", "debug", "accounts")
PERM_GROUPS = ("camera", "mic", "location", "contacts", "phone", "sms", "media")
COMMAND_TYPES = {"bonus", "free", "play", "locate", "notify", "ring", "uninstall", "update"}
APK_PATH = HERE / "app.apk"
SESSION_DAYS = 30
MAX_BODY = 512 * 1024

lock = threading.Lock()
db = None
login_fails = {}  # ip -> [timestamps]


# ---------------------------------------------------------------- storage

def open_db(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE IF NOT EXISTS sessions (h TEXT PRIMARY KEY, expires INTEGER);
        CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, type TEXT, text TEXT,
            status TEXT, created INTEGER, decided INTEGER);
        CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, text TEXT);
        CREATE TABLE IF NOT EXISTS commands (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT);
        CREATE TABLE IF NOT EXISTS locations (ts INTEGER, lat REAL, lon REAL, acc REAL);
        CREATE TABLE IF NOT EXISTS daily (day TEXT PRIMARY KEY, used_ms INTEGER, usage TEXT);
    """)
    try:  # which parent the session belongs to (1 = main, 2 = second)
        conn.execute("ALTER TABLE sessions ADD COLUMN slot INTEGER DEFAULT 1")
    except sqlite3.OperationalError:
        pass
    conn.commit()
    return conn


def kv_get(k, default=None):
    row = db.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
    return json.loads(row[0]) if row else default


def kv_set(k, v):
    db.execute("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
               (k, json.dumps(v, ensure_ascii=False)))


def now_ms():
    return int(time.time() * 1000)


def sha(s):
    return hashlib.sha256(s.encode()).hexdigest()


def hash_pw(pw, salt=None):
    salt = salt or secrets.token_hex(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 200_000).hex()
    return {"salt": salt, "hash": h}


PW_KEYS = {1: "parent_pw", 2: "parent_pw2"}  # main parent, second parent


def pw_matches(pw, slot):
    rec = kv_get(PW_KEYS[slot])
    if not rec:
        return False
    return hmac.compare_digest(hash_pw(pw, rec["salt"])["hash"], rec["hash"])


def check_pw(pw):
    """Which parent this password belongs to: 1, 2, or 0."""
    for slot in (1, 2):
        if pw_matches(pw, slot):
            return slot
    return 0


def set_pw(slot, pw):
    kv_set(PW_KEYS[slot], hash_pw(pw))
    db.execute("DELETE FROM sessions WHERE slot=?", (slot,))


def clean_config(c):
    out = dict(DEFAULT_CONFIG)
    if not isinstance(c, dict):
        return out
    for k in ("limit", "sleep_s", "sleep_e", "limit_we", "sleep_we_s", "sleep_we_e", "perm_policy"):
        try:
            out[k] = int(c.get(k, out[k]))
        except (TypeError, ValueError):
            pass
    out["limit"] = max(0, min(out["limit"], 24 * 60))
    out["limit_we"] = max(-1, min(out["limit_we"], 24 * 60))
    out["perm_policy"] = 2 if out["perm_policy"] == 2 else 0
    for k in ("sleep_s", "sleep_e", "sleep_we_s", "sleep_we_e"):
        if not -1 <= out[k] < 24 * 60:
            out[k] = -1
    pm = c.get("perms", {})
    out["perms"] = {}
    if isinstance(pm, dict):
        for pkg, groups in list(pm.items())[:500]:
            if isinstance(groups, dict):
                g = {k: int(v) for k, v in groups.items() if k in PERM_GROUPS and v in (1, 2)}
                if g:
                    out["perms"][str(pkg)] = g
    for k in ("allowed", "blocked"):
        v = c.get(k, [])
        out[k] = sorted({str(x) for x in v if isinstance(x, str)})[:500] if isinstance(v, list) else []
    al = c.get("applimits", {})
    if isinstance(al, dict):
        out["applimits"] = {str(p): max(1, min(int(m), 24 * 60)) for p, m in al.items()
                            if isinstance(m, (int, float)) and m > 0}
    for k in FLAGS:
        out[k] = bool(c.get(k, False))
    return out


def add_event(text, ts=None):
    db.execute("INSERT INTO events(ts,text) VALUES(?,?)", (ts or now_ms(), str(text)[:500]))
    db.execute("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 500)")


def add_command(cmd):
    db.execute("INSERT INTO commands(body) VALUES(?)", (json.dumps(cmd, ensure_ascii=False),))


# ---------------------------------------------------------------- device API

def api_pair(body):
    code = str(body.get("code", "")).strip()
    pair = kv_get("pair")
    if not pair or pair["expires"] < now_ms() or not hmac.compare_digest(code, pair["code"]):
        return 403, {"error": "Неверный или просроченный код"}
    token = secrets.token_urlsafe(32)
    kv_set("device_token", sha(token))
    kv_set("pair", None)
    add_event("Телефон подключён к сайту")
    return 200, {"token": token, "ver": kv_get("config_ver", 0)}


def api_sync(body):
    ver = kv_get("config_ver", 0)
    cfg = clean_config(kv_get("config", DEFAULT_CONFIG))

    if body.get("config") is not None and int(body.get("base_ver", -1)) == ver:
        cfg = clean_config(body["config"])
        ver += 1
        kv_set("config", cfg)
        kv_set("config_ver", ver)

    state = body.get("state")
    if isinstance(state, dict):
        state["received"] = now_ms()
        old = kv_get("state", {}) or {}
        if "apps" not in state and "apps" in old:
            state["apps"] = old["apps"]
        kv_set("state", state)
        day = str(state.get("day", ""))[:10]
        if day:
            db.execute("INSERT INTO daily(day,used_ms,usage) VALUES(?,?,?) ON CONFLICT(day) DO UPDATE "
                       "SET used_ms=excluded.used_ms, usage=excluded.usage",
                       (day, int(state.get("usedMs", 0)), json.dumps(state.get("usage", {}))))
    else:
        kv_set("last_seen", now_ms())

    for e in (body.get("events") or [])[:50]:
        if isinstance(e, dict):
            add_event(e.get("text", ""), int(e.get("ts", now_ms())))

    for r in (body.get("requests") or [])[:20]:
        if isinstance(r, dict) and r.get("id"):
            db.execute("INSERT OR IGNORE INTO requests(id,type,text,status,created) VALUES(?,?,?,?,?)",
                       (str(r["id"])[:64], str(r.get("type", ""))[:16], str(r.get("text", ""))[:200],
                        "pending", int(r.get("ts", now_ms()))))

    for p in (body.get("locations") or [])[:50]:
        try:
            db.execute("INSERT INTO locations(ts,lat,lon,acc) VALUES(?,?,?,?)",
                       (int(p["ts"]), float(p["lat"]), float(p["lon"]), float(p.get("acc", 0))))
        except (KeyError, TypeError, ValueError):
            pass
    db.execute("DELETE FROM locations WHERE ts < ?", (now_ms() - 30 * 86400_000,))

    rows = db.execute("SELECT id, body FROM commands ORDER BY id").fetchall()
    db.execute("DELETE FROM commands WHERE id <= ?", (rows[-1][0],)) if rows else None
    return 200, {"ver": ver, "config": cfg, "commands": [json.loads(b) for _, b in rows]}


# ---------------------------------------------------------------- parent API

def dashboard():
    state = kv_get("state", {}) or {}
    reqs = db.execute("SELECT id,type,text,status,created FROM requests ORDER BY created DESC LIMIT 20").fetchall()
    events = db.execute("SELECT ts,text FROM events ORDER BY id DESC LIMIT 40").fetchall()
    since = now_ms() - 24 * 3600_000
    locs = db.execute("SELECT ts,lat,lon,acc FROM locations WHERE ts>? ORDER BY ts", (since,)).fetchall()
    last = db.execute("SELECT ts,lat,lon,acc FROM locations ORDER BY ts DESC LIMIT 1").fetchone()
    days = db.execute("SELECT day,used_ms FROM daily ORDER BY day DESC LIMIT 14").fetchall()
    pair = kv_get("pair")
    return {
        "state": state,
        "config": clean_config(kv_get("config", DEFAULT_CONFIG)),
        "configVer": kv_get("config_ver", 0),
        "paired": kv_get("device_token") is not None,
        "pairCode": pair["code"] if pair and pair["expires"] > now_ms() else None,
        "requests": [dict(zip(("id", "type", "text", "status", "created"), r)) for r in reqs],
        "events": [{"ts": t, "text": x} for t, x in events],
        "track": [dict(zip(("ts", "lat", "lon", "acc"), r)) for r in locs],
        "lastLocation": dict(zip(("ts", "lat", "lon", "acc"), last)) if last else None,
        "days": [{"day": d, "usedMs": u} for d, u in days],
        "apkVersion": (HERE / "app.version").read_text().strip() if (HERE / "app.version").exists() else None,
        "now": now_ms(),
    }


def api_parent(path, body, slot=1):
    if path == "/api/dashboard":
        d = dashboard()
        d["me"] = slot
        d["hasSecond"] = kv_get(PW_KEYS[2]) is not None
        return 200, d
    if path == "/api/config":
        cur = clean_config(kv_get("config", DEFAULT_CONFIG))
        merged = dict(cur)
        merged.update(body.get("config") or {})
        kv_set("config", clean_config(merged))
        kv_set("config_ver", kv_get("config_ver", 0) + 1)
        return 200, {"ok": True}
    if path == "/api/command":
        t = body.get("type")
        if t not in COMMAND_TYPES:
            return 400, {"error": "unknown command"}
        cmd = {"type": t}
        if "min" in body:
            cmd["min"] = max(1, min(int(body["min"]), 600))
        if "text" in body:
            cmd["text"] = str(body["text"])[:200]
        if t == "uninstall":
            cmd["pkg"] = str(body.get("pkg", ""))[:200]
        if t == "update" and not APK_PATH.exists():
            return 400, {"error": "На сервере нет файла обновления"}
        add_command(cmd)
        return 200, {"ok": True}
    if path == "/api/request":
        rid, approve = str(body.get("id", "")), bool(body.get("approve"))
        row = db.execute("SELECT type,text FROM requests WHERE id=? AND status='pending'", (rid,)).fetchone()
        if not row:
            return 404, {"error": "Запрос не найден"}
        rtype, rtext = row
        mins = max(1, min(int(body.get("min", 30)), 600))
        if approve and rtype == "time":
            add_command({"type": "bonus", "min": mins})
            add_command({"type": "notify", "text": f"Родитель добавил {mins} мин"})
        elif approve and rtype == "app":
            add_command({"type": "play", "min": mins})
            add_command({"type": "notify", "text": f"Play Маркет открыт на {mins} мин: «{rtext}»"})
        else:
            add_command({"type": "notify", "text": "Родитель отказал" + (f": «{rtext}»" if rtext else "")})
        db.execute("UPDATE requests SET status=?, decided=? WHERE id=?",
                   ("approved" if approve else "denied", now_ms(), rid))
        return 200, {"ok": True}
    if path == "/api/pair/new":
        code = f"{secrets.randbelow(900000) + 100000}"
        kv_set("pair", {"code": code, "expires": now_ms() + 15 * 60_000})
        return 200, {"code": code}
    if path == "/api/password":  # change your own password
        if not pw_matches(str(body.get("old", "")), slot):
            return 403, {"error": "Старый пароль неверный"}
        new = str(body.get("new", ""))
        if len(new) < 8:
            return 400, {"error": "Новый пароль короче 8 символов"}
        if pw_matches(new, 3 - slot):
            return 400, {"error": "Пароль должен отличаться от пароля другого родителя"}
        set_pw(slot, new)
        return 200, {"ok": True}
    if path == "/api/password2":  # main parent sets or deletes the second parent's password
        if slot != 1:
            return 403, {"error": "Это может только основной родитель"}
        if not pw_matches(str(body.get("current", "")), 1):
            return 403, {"error": "Ваш пароль неверный"}
        new = str(body.get("new", ""))
        if not new:
            kv_set(PW_KEYS[2], None)
            db.execute("DELETE FROM sessions WHERE slot=2")
            add_event("Пароль второго родителя для сайта удалён")
            return 200, {"ok": True}
        if len(new) < 8:
            return 400, {"error": "Пароль короче 8 символов"}
        if pw_matches(new, 1):
            return 400, {"error": "Пароль должен отличаться от вашего"}
        set_pw(2, new)
        add_event("Задан пароль второго родителя для сайта")
        return 200, {"ok": True}
    return 404, {"error": "not found"}


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "parentlock"

    def log_message(self, fmt, *args):
        pass

    def client_ip(self):
        return self.headers.get("X-Real-IP") or self.client_address[0]

    def send_json(self, code, obj, cookie=None):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n > MAX_BODY:
            raise ValueError("too large")
        raw = self.rfile.read(n) if n else b"{}"
        obj = json.loads(raw or b"{}")
        if not isinstance(obj, dict):
            raise ValueError("bad body")
        return obj

    def session_slot(self):
        """Logged-in parent: 1 or 2; 0 if not logged in."""
        if DEMO:
            return 1
        c = SimpleCookie(self.headers.get("Cookie", ""))
        sid = c.get("sid")
        if not sid:
            return 0
        row = db.execute("SELECT expires, slot FROM sessions WHERE h=?", (sha(sid.value),)).fetchone()
        return (row[1] or 1) if row and row[0] > now_ms() else 0

    def device_ok(self):
        auth = self.headers.get("Authorization", "")
        stored = kv_get("device_token")
        return bool(stored and auth.startswith("Bearer ")
                    and hmac.compare_digest(sha(auth[7:].strip()), stored))

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            data = (HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/app.apk" and APK_PATH.exists():
            data = APK_PATH.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        try:
            body = self.read_body()
        except Exception:
            self.send_json(400, {"error": "bad request"})
            return
        path = self.path.split("?")[0]
        with lock:
            try:
                if path == "/api/login":
                    self.login(body)
                elif path == "/api/logout":
                    self.send_json(200, {"ok": True}, "sid=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict")
                elif path == "/api/pair":
                    self.send_json(*api_pair(body))
                elif path == "/api/sync":
                    if not self.device_ok():
                        self.send_json(401, {"error": "device not paired"})
                    else:
                        self.send_json(*api_sync(body))
                elif path.startswith("/api/"):
                    slot = self.session_slot()
                    if not slot:
                        self.send_json(401, {"error": "login required"})
                    else:
                        self.send_json(*api_parent(path, body, slot))
                else:
                    self.send_json(404, {"error": "not found"})
                db.commit()
            except Exception as e:  # keep the server alive, report the error
                db.rollback()
                sys.stderr.write(f"error {path}: {e!r}\n")
                self.send_json(500, {"error": "server error"})

    def login(self, body):
        ip = self.client_ip()
        t = time.time()
        fails = [x for x in login_fails.get(ip, []) if t - x < 900]
        login_fails[ip] = fails
        if len(fails) >= 8:
            self.send_json(429, {"error": "Слишком много попыток, подождите 15 минут"})
            return
        slot = check_pw(str(body.get("password", "")))
        if not slot:
            fails.append(t)
            time.sleep(1)
            self.send_json(403, {"error": "Неверный пароль"})
            return
        login_fails.pop(ip, None)
        sid = secrets.token_urlsafe(32)
        db.execute("INSERT INTO sessions(h,expires,slot) VALUES(?,?,?)",
                   (sha(sid), now_ms() + SESSION_DAYS * 86400_000, slot))
        db.execute("DELETE FROM sessions WHERE expires < ?", (now_ms(),))
        self.send_json(200, {"ok": True},
                       f"sid={sid}; Path=/; Max-Age={SESSION_DAYS * 86400}; HttpOnly; Secure; SameSite=Strict")


DEMO = False


def seed_demo():
    """Fake family data for `demo` mode (screenshots, trying the site locally)."""
    now = now_ms()
    kv_set("device_token", sha("demo"))
    apps = [
        ("ru.dublgis.dgismobile", "2ГИС", ["location"], True),
        ("com.supercell.brawlstars", "Brawl Stars", ["mic"], True),
        ("com.android.chrome", "Chrome", ["camera", "mic", "location"], False),
        ("com.mojang.minecraftpe", "Minecraft", ["mic", "media"], True),
        ("org.telegram.messenger", "Telegram", ["camera", "mic", "contacts", "location", "media"], True),
        ("ru.yandex.music", "Яндекс Музыка", ["media"], True),
        ("com.google.android.youtube", "YouTube", ["mic", "media"], False),
        ("ru.oneme.app", "MAX", ["camera", "mic", "contacts", "media"], True),
    ]
    usage = {"com.google.android.youtube": 38, "com.supercell.brawlstars": 31, "org.telegram.messenger": 22,
             "ru.yandex.music": 9, "com.android.chrome": 6}
    api_sync({
        "base_ver": 0,
        "config": {"limit": 120, "sleep_s": 21 * 60 + 30, "sleep_e": 7 * 60, "limit_we": 180,
                   "sleep_we_s": 22 * 60 + 30, "sleep_we_e": 9 * 60,
                   "allowed": ["org.telegram.messenger", "ru.oneme.app", "ru.dublgis.dgismobile"],
                   "blocked": [], "applimits": {"com.google.android.youtube": 45, "com.supercell.brawlstars": 30},
                   "perms": {"org.telegram.messenger": {"location": 2}}},
        "state": {"mode": "normal", "headline": "Осталось сегодня: 34 мин", "usedMs": 86 * 60000,
                  "limitMin": 120, "bonusMin": 0, "battery": 71, "charging": False, "setup": False,
                  "access": True, "day": time.strftime("%Y-%m-%d"), "appVersion": "1.0.468000",
                  "apps": [{"p": p, "l": l, "g": g, "u": u} for p, l, g, u in apps],
                  "usage": {p: m * 60000 for p, m in usage.items()}},
        "events": [{"ts": now - 5 * 3600_000, "text": "Телефон включён"},
                   {"ts": now - 3 * 3600_000, "text": "Установлено приложение: Яндекс Музыка"},
                   {"ts": now - 2 * 3600_000, "text": "Сын поставил PIN на экран — PIN снят автоматически"},
                   {"ts": now - 40 * 60_000, "text": "Батарея садится: 15%"}],
        "requests": [{"id": "d1", "type": "time", "text": "", "ts": now - 4 * 60_000},
                     {"id": "d2", "type": "app", "text": "Minecraft", "ts": now - 90_000}],
        "locations": [{"ts": now - 50_000, "lat": 55.7539, "lon": 37.6208, "acc": 12}],
    })
    for i, mins in enumerate([95, 130, 118, 160, 75, 142, 86]):
        day = time.strftime("%Y-%m-%d", time.localtime(time.time() - (6 - i) * 86400))
        db.execute("INSERT OR REPLACE INTO daily(day,used_ms,usage) VALUES(?,?,?)", (day, mins * 60000, "{}"))
    db.commit()


def main():
    global db, DEMO
    args = sys.argv[1:]
    cmd = args[0] if args else "serve"
    db_path, port = DB_PATH, PORT
    if "--db" in args:
        db_path = args[args.index("--db") + 1]
    if "--port" in args:
        port = int(args[args.index("--port") + 1])
    if cmd == "demo":
        # Throwaway database, no login, fake data. Never point this at a real database.
        import tempfile
        DEMO = True
        db_path = os.path.join(tempfile.mkdtemp(prefix="parentlock-demo-"), "demo.sqlite")
        db = open_db(db_path)
        seed_demo()
        srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        print(f"DEMO (no login, fake data): http://127.0.0.1:{port}/", flush=True)
        srv.serve_forever()
        return
    db = open_db(db_path)
    if cmd == "set-password":
        pw = sys.stdin.readline().strip()
        if len(pw) < 8:
            sys.exit("password must be at least 8 characters")
        slot = 2 if "--slot" in args and args[args.index("--slot") + 1] == "2" else 1
        set_pw(slot, pw)
        db.commit()
        print("password set" + (" (second parent)" if slot == 2 else ""))
        return
    if kv_get("config") is None:
        kv_set("config", DEFAULT_CONFIG)
        kv_set("config_ver", 0)
        db.commit()
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"parentlock listening on 127.0.0.1:{port}, db {db_path}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
