#!/usr/bin/env python3
"""Проверка на настоящем Android-эмуляторе. Второй телефон («друг») изображает этот скрипт:
он независимо от приложения выводит ключи из кода, подписывает события и ходит на те же ретрансляторы.
Если что-то из протокола не сходится, друг просто не увидит штрихов приложения, а приложение — его."""
import base64
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

import websocket
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image

PKG = "app.holst"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
RELAYS = [
    "wss://relay.primal.net",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.snort.social",
    "wss://nostr.data.haus",
]
KIND_STORED, KIND_LIVE = 30078, 20078
results = []


def check(name, ok, detail="", hard=True):
    results.append((name, bool(ok), hard))
    mark = "PASS" if ok else ("FAIL" if hard else "WARN")
    print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""), flush=True)
    return ok


# ---------- BIP-340: отдельная реализация, чтобы проверять приложение, а не само себя ----------
p = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
n = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (
    0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
    0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8,
)


def tagged(tag, msg):
    h = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(h + h + msg).digest()


def padd(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and a[1] != b[1]:
        return None
    if a == b:
        lam = 3 * a[0] * a[0] * pow(2 * a[1], p - 2, p) % p
    else:
        lam = (b[1] - a[1]) * pow(b[0] - a[0], p - 2, p) % p
    x = (lam * lam - a[0] - b[0]) % p
    return x, (lam * (a[0] - x) - a[1]) % p


def pmul(pt, k):
    r = None
    for i in range(256):
        if (k >> i) & 1:
            r = padd(r, pt)
        pt = padd(pt, pt)
    return r


def schnorr_sign(msg, sk):
    d0 = int.from_bytes(sk, "big")
    P = pmul(G, d0)
    d = d0 if P[1] % 2 == 0 else n - d0
    aux = secrets.token_bytes(32)
    t = (d ^ int.from_bytes(tagged("BIP0340/aux", aux), "big")).to_bytes(32, "big")
    px = P[0].to_bytes(32, "big")
    k0 = int.from_bytes(tagged("BIP0340/nonce", t + px + msg), "big") % n
    R = pmul(G, k0)
    k = k0 if R[1] % 2 == 0 else n - k0
    rx = R[0].to_bytes(32, "big")
    e = int.from_bytes(tagged("BIP0340/challenge", rx + px + msg), "big") % n
    return rx + ((k + e * d) % n).to_bytes(32, "big")


class Friend:
    def __init__(self, code):
        master = hashlib.pbkdf2_hmac("sha256", code.encode(), b"holst-room-v1", 150000, 32)
        self.sk = hashlib.sha256(b"holst-sign" + master).digest()
        self.aes = AESGCM(hashlib.sha256(b"holst-aes" + master).digest())
        self.pub = pmul(G, int.from_bytes(self.sk, "big"))[0].to_bytes(32, "big").hex()
        self.got = []
        self.seen = set()
        self.acks = {}
        self.conns = {}
        self.lock = threading.Lock()
        self.stop = False
        for url in RELAYS:
            threading.Thread(target=self._run, args=(url,), daemon=True).start()

    def _run(self, url):
        while not self.stop:
            try:
                ws = websocket.create_connection(url, timeout=15, header=["User-Agent: Holst-e2e"])
                ws.settimeout(60)
                ws.send(json.dumps(["REQ", "s", {"authors": [self.pub], "kinds": [KIND_STORED], "since": 0, "limit": 1000}]))
                ws.send(json.dumps(["REQ", "l", {"authors": [self.pub], "kinds": [KIND_LIVE], "since": int(time.time()) - 600}]))
                self.conns[url] = ws
                print(f"  friend connected {url}", flush=True)
                while not self.stop:
                    try:
                        msg = json.loads(ws.recv())
                    except websocket.WebSocketTimeoutException:
                        ws.ping()
                        continue
                    self._on(url, msg)
            except Exception as e:
                self.conns.pop(url, None)
                print(f"  friend lost {url}: {e}", flush=True)
                time.sleep(5)

    def _on(self, url, m):
        if m[0] == "EVENT":
            e = m[2]
            with self.lock:
                if e["id"] in self.seen:
                    return
                self.seen.add(e["id"])
            raw = base64.b64decode(e["content"])
            plain = json.loads(self.aes.decrypt(raw[:12], raw[12:], None))
            plain["_kind"] = e["kind"]
            plain["_relay"] = url
            self.got.append(plain)
        elif m[0] == "OK":
            self.acks.setdefault(m[1], {})[url] = (m[2], m[3] if len(m) > 3 else "")
        elif m[0] in ("NOTICE", "CLOSED"):
            print(f"  {url}: {m}", flush=True)

    def publish(self, kind, d, plain):
        nonce = secrets.token_bytes(12)
        content = base64.b64encode(nonce + self.aes.encrypt(nonce, json.dumps(plain, separators=(",", ":")).encode(), None)).decode()
        tags = [["d", d]] if d else []
        created = int(time.time())
        ser = json.dumps([0, self.pub, created, kind, tags, content], separators=(",", ":"), ensure_ascii=False)
        eid = hashlib.sha256(ser.encode()).digest()
        ev = {"id": eid.hex(), "pubkey": self.pub, "created_at": created, "kind": kind, "tags": tags,
              "content": content, "sig": schnorr_sign(eid, self.sk).hex()}
        with self.lock:
            self.seen.add(ev["id"])
        for url, ws in list(self.conns.items()):
            try:
                ws.send(json.dumps(["EVENT", ev]))
            except Exception as e:
                print(f"  send failed {url}: {e}", flush=True)
        return ev["id"]

    def stroke(self, color, y_from_center, width=300, x0=800, x1=9200):
        sid = secrets.token_hex(8)
        pts = []
        px, py = 0, 0
        for x in range(x0, x1, 400):
            pts += [x - px, y_from_center - py]
            px, py = x, y_from_center
        self.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": int(time.time() * 1000), "c": color, "w": width, "e": 0, "p": pts})
        return sid

    def wait_for(self, predicate, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            hits = [g for g in self.got if predicate(g)]
            if hits:
                return hits
            time.sleep(0.5)
        return []


# ---------- adb ----------
def sh(*args, check_rc=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check_rc and r.returncode != 0:
        raise RuntimeError(f"adb {args}: {r.stdout} {r.stderr}")
    return r.stdout


def shot(name):
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=60)
    return path


def count_color(path, rgb, tol=40, box=None):
    im = Image.open(path).convert("RGB")
    if box:
        im = im.crop(box)
    px = im.getdata()
    return sum(1 for c in px if abs(c[0] - rgb[0]) < tol and abs(c[1] - rgb[1]) < tol and abs(c[2] - rgb[2]) < tol)


def ui_nodes():
    for _ in range(4):
        sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        data = sh("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(data[data.index("<?xml"):] if "<?xml" in data else data)
            return list(root.iter("node"))
        except Exception:
            time.sleep(1)
    return []


def center(node):
    m = re.findall(r"\d+", node.get("bounds"))
    return (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2


def find_text(text, contains=False):
    for node in ui_nodes():
        t = (node.get("text") or "").casefold()
        want = text.casefold()
        if (want in t) if contains else (t == want):
            return node
    return None


def tap_xy(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def tap_text(text, timeout=20, scroll=True):
    end = time.time() + timeout
    while time.time() < end:
        node = find_text(text)
        if node is not None:
            x, y = center(node)
            tap_xy(x, y)
            return True
        if scroll:
            sh("shell", "input", "swipe", "540", "1700", "540", "1100", "300")
        time.sleep(1)
    return False


def top_activity():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*?\{[^}]*\s(\S+/\S+)", out) or re.search(r"ResumedActivity.*?\s(\S+/\S+)", out)
    return m.group(1) if m else ""


def diag(label):
    win = sh("shell", "dumpsys", "window")
    lines = [l.strip() for l in win.splitlines() if re.search(r"mCurrentFocus|mFocusedApp|mDreamingLockscreen|mShowingDream|isKeyguardShowing|mScreenOn", l)]
    power = [l.strip() for l in sh("shell", "dumpsys", "power").splitlines() if "mWakefulness=" in l]
    print(f"  diag {label}: top={top_activity()} {lines[:5]} {power[:1]}", flush=True)


def on_layer():
    return "LockActivity" in top_activity()


def sleep_phone():
    sh("shell", "input", "keyevent", "KEYCODE_SLEEP")
    time.sleep(4)


def wake_phone():
    sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(3)


def draw_curve(points, step_ms=30):
    x, y = points[0]
    sh("shell", "input", "motionevent", "DOWN", str(x), str(y))
    for x, y in points[1:]:
        sh("shell", "input", "motionevent", "MOVE", str(x), str(y))
    sh("shell", "input", "motionevent", "UP", str(x), str(y))


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    code = "".join(str(secrets.randbelow(10)) for _ in range(12))
    print("room code", code, flush=True)
    friend = Friend(code)

    sh("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    print(sh("install", "-r", "-g", apk, timeout=180))
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    sh("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    size = re.findall(r"(\d+)x(\d+)", sh("shell", "wm", "size"))[-1]
    W, H = int(size[0]), int(size[1])
    print("screen", W, H, flush=True)
    sh("logcat", "-c")

    # ---- Первый запуск ----
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    shot("01_first_launch")
    check("приложение открылось", PKG in top_activity(), top_activity())

    check("нажат «Чистый лист»", tap_text("Чистый лист"))
    time.sleep(3)
    shot("02_after_blank")
    check("служба работает", "CanvasService" in sh("shell", "dumpsys", "activity", "services", PKG))

    # ---- Вход по коду друга ----
    check("открыт ввод кода", tap_text("Ввести код друга"))
    time.sleep(1.5)
    sh("shell", "input", "text", code)
    time.sleep(1)
    shot("03_join_dialog")
    check("код принят", tap_text("Присоединиться", scroll=False))
    time.sleep(3)

    connected = None
    for _ in range(30):
        connected = find_text("На связи", contains=True)
        if connected is not None:
            break
        time.sleep(1.5)
    check("приложение на связи с ретрансляторами", connected is not None, connected.get("text") if connected is not None else "")
    time.sleep(10)
    print("friend relays:", list(friend.conns), flush=True)
    shot("04_main_connected")

    # ---- Блокировка: холст должен оказаться на экране блокировки сам ----
    print(sh("shell", "locksettings", "set-pin", "1234"))
    time.sleep(1)
    sleep_phone()
    wake_phone()
    keyguard = sh("shell", "dumpsys", "window").count("mDreamingLockscreen=true") + sh("shell", "dumpsys", "window").count("isKeyguardShowing=true")
    print("keyguard markers:", keyguard, flush=True)
    check("при включении экрана сверху холст", on_layer(), top_activity())
    path = shot("05_lock_layer")
    diag("on layer")

    # ---- Рисуем на экране блокировки: это должно дойти до друга ----
    wave = [(int(W * (0.12 + 0.76 * i / 40)), int(H * 0.55 + (H * 0.06) * ((-1) ** (i // 5)) * ((i % 5) / 5))) for i in range(41)]
    draw_curve(wave)
    time.sleep(2)
    diag("after draw")
    path = shot("06_i_drew")
    white_after = count_color(path, (255, 255, 255), tol=20, box=(0, int(H * 0.45), W, int(H * 0.65)))
    check("свой штрих виден на холсте", white_after > 500, f"{white_after} белых точек")
    mine = friend.wait_for(lambda g: g.get("k") == "s" and g["_kind"] == KIND_STORED, 30)
    check("друг получил штрих и расшифровал его", bool(mine), f"через {mine[0]['_relay']}" if mine else "")
    if mine:
        check("точки штриха дошли целыми", len(mine[0]["p"]) > 20, f"{len(mine[0]['p']) // 2} точек")
    live_mine = [g for g in friend.got if g.get("k") == "l"]
    check("друг видит штрих ещё в процессе рисования", bool(live_mine), f"{len(live_mine)} кусков", hard=False)

    # ---- Друг рисует на моих обоях ----
    sid = secrets.token_hex(8)
    magenta = 0xFFFF00C8
    colour = (255, 0, 200)
    for seq, (a, b) in enumerate([(800, 3000), (3000, 5000), (5000, 7000)]):
        friend.publish(KIND_LIVE, None, {"k": "l", "i": sid, "c": magenta, "w": 300, "e": 0, "n": seq, "p": [a, -2000, b - a, 0]})
        time.sleep(0.4)
    time.sleep(2.5)
    path = shot("07_friend_live")
    live_px = count_color(path, colour)
    check("живой штрих друга виден сразу", live_px > 1000, f"{live_px} точек", hard=False)
    check("подпись «Друг рисует»", find_text("Друг рисует…") is not None, hard=False)
    friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": int(time.time() * 1000), "c": magenta, "w": 300, "e": 0,
                                      "p": [800, -2000, 2200, 0, 2000, 0, 2000, 0, 2200, 0]})
    time.sleep(3)
    path = shot("08_friend_done")
    done_px = count_color(path, colour)
    check("штрих друга лёг на холст", done_px > 1000, f"{done_px} точек")

    # ---- Настоящие обои: убираем слой и смотрим на системный экран блокировки ----
    time.sleep(6)
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(3)
    diag("after back")
    check("слой закрылся по «назад»", not on_layer(), top_activity())
    path = shot("09_system_keyguard")
    wall_px = count_color(path, colour)
    check("рисунок друга впечатан в настоящие обои экрана блокировки", wall_px > 1000, f"{wall_px} точек")
    print(sh("shell", "dumpsys", "wallpaper")[:1500], flush=True)

    # ---- Снова включаем экран: холст возвращается ----
    sleep_phone()
    wake_phone()
    check("после повторного включения снова холст", on_layer(), top_activity())

    # ---- Палитра, отмена, стирание ----
    tap_xy(int(W * 0.08), int(H - 66 * W / 411))
    time.sleep(1.2)
    shot("10_panel")
    before = len(friend.got)
    sh("shell", "input", "swipe", str(W // 4), str(H // 3), str(W * 3 // 4), str(H // 3 + 100), "500")
    time.sleep(2)
    last = friend.wait_for(lambda g: g.get("k") == "s" and g not in mine, 25)
    check("второй штрих дошёл", bool(last), hard=False)
    tap_xy(int(W * 0.92), int(H - 66 * W / 411))
    tomb = friend.wait_for(lambda g: g.get("k") == "d", 25)
    check("отмена дошла до друга", bool(tomb), hard=False)
    shot("11_after_undo")

    friend.publish(KIND_STORED, "clear", {"k": "c", "t": int(time.time() * 1000) + 5000})
    time.sleep(4)
    path = shot("12_after_clear")
    check("«стереть всё» от друга очистило холст", count_color(path, colour) < 200, f"{count_color(path, colour)} точек")

    # ---- Разблокировка ----
    tap_xy(W // 2, int(H - 66 * W / 411))
    time.sleep(2.5)
    shot("13_bouncer")
    sh("shell", "input", "text", "1234")
    sh("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(4)
    path = shot("14_unlocked")
    locked = "mDreamingLockscreen=true" in sh("shell", "dumpsys", "window") or "KeyguardShowing=true" in sh("shell", "dumpsys", "window")
    check("после ввода PIN телефон разблокирован и холст закрыт", not on_layer(), top_activity())
    print("locked marker:", locked, flush=True)

    # ---- Без сети: что нарисовано офлайн, должно дойти потом ----
    sh("shell", "svc", "wifi", "disable")
    sh("shell", "svc", "data", "disable")
    time.sleep(6)
    green = (0, 255, 90)
    green_id = friend.stroke(0xFF00FF5A, 2500)
    time.sleep(3)
    sleep_phone()
    wake_phone()
    before_ids = {g.get("i") for g in friend.got}
    sh("shell", "input", "swipe", str(W // 5), str(int(H * 0.3)), str(W * 4 // 5), str(int(H * 0.35)), "400")
    time.sleep(2)
    path = shot("15_offline")
    check("штрих друга не появился без сети (проверка самой проверки)", count_color(path, green) < 500, hard=False)
    sh("shell", "svc", "wifi", "enable")
    sh("shell", "svc", "data", "enable")
    arrived = 0
    for _ in range(25):
        time.sleep(3)
        path = shot("16_back_online")
        arrived = count_color(path, green)
        if arrived > 1000:
            break
    check("штрих, нарисованный другом без меня, появился после возврата сети", arrived > 1000, f"{arrived} точек")
    off = friend.wait_for(lambda g: g.get("k") == "s" and g.get("i") not in before_ids, 40)
    check("штрих, нарисованный без сети, дошёл до друга", bool(off))

    # ---- Запасной путь без разрешения «поверх окон» ----
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "deny")
    time.sleep(1)
    sleep_phone()
    wake_phone()
    time.sleep(3)
    check("без разрешения холст открывается через полноэкранное уведомление", on_layer(), top_activity(), hard=False)
    shot("17_fullscreen_fallback")
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")

    # ---- Картинка из галереи ----
    tap_xy(W // 2, int(H - 62 * W / 411))
    time.sleep(2.5)
    sh("shell", "input", "text", "1234")
    sh("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(3)
    diag("after second unlock")
    im = Image.new("RGB", (1600, 3000), (20, 140, 200))
    for y in range(1200, 1800):
        for x in range(1600):
            im.putpixel((x, y), (240, 120, 20))
    pic = os.path.join(OUT, "picture.jpg")
    im.save(pic, quality=95)
    sh("push", pic, "/sdcard/Pictures/holst_test.jpg")
    sh("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/holst_test.jpg")
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    sh("shell", "input", "swipe", "540", "500", "540", "1900", "300")
    time.sleep(1)
    if tap_text("Выбрать картинку", timeout=10):
        time.sleep(4)
        shot("18_picker")
        nodes = ui_nodes()
        thumbs = [nd for nd in nodes if "Photo" in (nd.get("content-desc") or "") or "photo" in (nd.get("content-desc") or "")]
        print("picker nodes:", [nd.get("content-desc") for nd in thumbs][:5], flush=True)
        if thumbs:
            tap_xy(*center(thumbs[0]))
        else:
            tap_xy(W // 6, int(H * 0.28))
        time.sleep(5)
        shot("19_after_pick")
        sleep_phone()
        wake_phone()
        path = shot("20_layer_with_picture")
        check("картинка из галереи лежит под рисунком", count_color(path, (240, 120, 20)) > 20000, f"{count_color(path, (240, 120, 20))} точек", hard=False)
        sh("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(4)
        path = shot("21_system_keyguard_with_picture")
        check("картинка попала в настоящие обои", count_color(path, (240, 120, 20)) > 20000, hard=False)

    print("ops accepted per relay:", flush=True)
    per = {}
    for eid, m in friend.acks.items():
        for url, (ok, msg) in m.items():
            s = per.setdefault(url, [0, 0, set()])
            s[0 if ok else 1] += 1
            if not ok:
                s[2].add(msg)
    for url in RELAYS:
        print(" ", url, per.get(url), "connected" if url in friend.conns else "DOWN", flush=True)

    friend.stop = True
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(sh("logcat", "-d", "-v", "time", "Holst:D", "AndroidRuntime:E", "ActivityTaskManager:I", "*:S", timeout=60))
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    failed = [r for r in results if not r[1] and r[2]]
    print(f"\nИТОГ: {sum(1 for r in results if r[1])} из {len(results)}, обязательных провалов: {len(failed)}", flush=True)
    for r in failed:
        print("  FAIL:", r[0], flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    try:
        main()
    finally:
        os.makedirs(OUT, exist_ok=True)
        try:
            with open(os.path.join(OUT, "logcat_full.txt"), "w") as f:
                f.write(sh("logcat", "-d", "-v", "time", "*:W", "Holst:D", timeout=60)[-400000:])
        except Exception as e:
            print("logcat failed", e)
