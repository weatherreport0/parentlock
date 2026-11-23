"""End-to-end test of the site: login, pairing, sync, rules, commands. Run: python tests/test_server.py"""
import json, os, subprocess, sys, tempfile, time, urllib.request, urllib.error

SRV = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "parentlock_server.py")
tmp = tempfile.mkdtemp()
db = os.path.join(tmp, "db.sqlite")
port = "18765"
subprocess.run([sys.executable, SRV, "set-password", "--db", db], input=b"testpass123\n", check=True)
p = subprocess.Popen([sys.executable, SRV, "serve", "--db", db, "--port", port])
time.sleep(1.5)
BASE = f"http://127.0.0.1:{port}"
cookie = None

def post(path, body=None, token=None, expect=200):
    global cookie
    req = urllib.request.Request(BASE + path, data=json.dumps(body or {}).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    if cookie: req.add_header("Cookie", cookie)
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        r = urllib.request.urlopen(req)
        code, data = r.status, json.loads(r.read())
        sc = r.headers.get("Set-Cookie")
        if sc and sc.startswith("sid="): cookie = sc.split(";")[0]
    except urllib.error.HTTPError as e:
        code, data = e.code, json.loads(e.read())
    assert code == expect, (path, code, data)
    return data

try:
    assert urllib.request.urlopen(BASE + "/").status == 200
    post("/api/dashboard", expect=401)
    post("/api/login", {"password": "wrong"}, expect=403)
    post("/api/login", {"password": "testpass123"})
    d = post("/api/dashboard"); assert d["paired"] is False
    post("/api/sync", {}, token="bogus", expect=401)
    code = post("/api/pair/new")["code"]
    post("/api/pair", {"code": "000000"}, expect=403)
    tok = post("/api/pair", {"code": code})["token"]
    post("/api/pair", {"code": code}, expect=403)  # one-time

    phone_cfg = {"limit": 120, "sleep_s": 1290, "sleep_e": 420, "allowed": ["org.telegram.messenger"],
                 "blocked": [], "applimits": {"com.google.android.youtube": 30}, "locked": False, "filter": False}
    r = post("/api/sync", {"base_ver": 0, "config": phone_cfg, "state": {"mode": "normal", "headline": "Осталось 1 ч",
             "usedMs": 1800000, "limitMin": 120, "bonusMin": 0, "day": "2026-11-22",
             "usage": {"com.google.android.youtube": 1200000},
             "apps": [{"p": "com.google.android.youtube", "l": "YouTube"}]},
             "events": [{"ts": 1, "text": "Телефон включён"}],
             "requests": [{"id": "r1", "type": "time", "text": "", "ts": 2}],
             "locations": [{"ts": int(time.time()*1000), "lat": 55.75, "lon": 37.62, "acc": 12}]}, token=tok)
    assert r["ver"] == 1 and r["config"]["limit"] == 120, r

    # parent changes rules + approves request + sends command
    post("/api/config", {"config": {"limit": 90, "blocked": ["com.evil"]}})
    post("/api/request", {"id": "r1", "approve": True, "min": 30})
    post("/api/command", {"type": "locate"})
    post("/api/command", {"type": "rm -rf"}, expect=400)

    # stale phone edit (base_ver 1 while server is 2) must lose
    r = post("/api/sync", {"base_ver": 1, "config": dict(phone_cfg, limit=999), "state": {"day": "2026-11-22"}}, token=tok)
    assert r["ver"] == 2 and r["config"]["limit"] == 90 and r["config"]["blocked"] == ["com.evil"], r
    types = [c["type"] for c in r["commands"]]
    assert types == ["bonus", "notify", "locate"], types
    r = post("/api/sync", {"base_ver": 2, "state": {"day": "2026-11-22"}}, token=tok)
    assert r["commands"] == [], r  # delivered once

    d = post("/api/dashboard")
    assert d["paired"] and d["lastLocation"]["lat"] == 55.75
    assert d["requests"][0]["status"] == "approved"
    assert any("включён" in e["text"] for e in d["events"])
    assert d["state"]["apps"][0]["l"] == "YouTube", "apps kept when not resent"
    assert d["days"][0]["day"] == "2026-11-22"

    # device settings, permissions, new commands
    post("/api/config", {"config": {"apk": True, "debug": "yes", "limit_we": 180, "sleep_we_s": 1380, "sleep_we_e": 540,
                                    "perm_policy": 2, "perms": {"com.x": {"camera": 2, "mic": 1, "bogus": 1, "sms": 7}}}})
    cfg = post("/api/dashboard")["config"]
    assert cfg["apk"] is True and cfg["debug"] is True and cfg["play_free"] is False, cfg
    assert cfg["limit_we"] == 180 and cfg["sleep_we_s"] == 1380 and cfg["perm_policy"] == 2, cfg
    assert cfg["perms"] == {"com.x": {"camera": 2, "mic": 1}}, cfg["perms"]
    assert cfg["limit"] == 90, "partial update keeps other keys"
    post("/api/command", {"type": "ring"})
    post("/api/command", {"type": "uninstall", "pkg": "com.x"})
    post("/api/command", {"type": "update"}, expect=400)  # no apk on server yet
    r = post("/api/sync", {"base_ver": 0, "state": {"day": "2026-11-22"}}, token=tok)
    assert [c["type"] for c in r["commands"]] == ["ring", "uninstall"] and r["commands"][1]["pkg"] == "com.x", r

    post("/api/password", {"old": "nope", "new": "newpass1234"}, expect=403)
    post("/api/password", {"old": "testpass123", "new": "newpass1234"})
    post("/api/dashboard", expect=401)  # sessions dropped
    print("ALL SERVER TESTS PASSED")
finally:
    p.terminate()

