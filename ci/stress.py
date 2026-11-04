#!/usr/bin/env python3
"""Стресс-тест: воспроизводит то, на что жалуются люди.
С --strict падает, если исправленные сбои вернулись. Без него только печатает цифры."""
import json, os, re, secrets, sys, threading, time, traceback
import e2e
from e2e import Friend, KIND_LIVE, KIND_STORED, OUT, PKG, center, count_color, draw_curve, find_text, on_layer, sh, shot, sleep_phone, tap_text, tap_xy, ui_nodes, wake_phone, top_activity
from PIL import Image

metrics = {}
STRICT = {
    "strokes_lost": lambda v: v == 0,
    "long_stroke_delivered": lambda v: v is True,
    "clear_erased_my_screen": lambda v: v is True,
    "clear_reached_friend_with_clock_skew": lambda v: v is True,
    "undo_reached_friend": lambda v: v is True,
    "friend_clear_at_old_clock_applied": lambda v: v is True,
    "delivery_after_silent_disconnect_ms": lambda v: 0 <= v < 20000,
    "friend_visible_after_silent_disconnect_ms": lambda v: 0 <= v < 25000,
    "lost_strokes_resent_after_inventory": lambda v: v > 0,
    "lost_clear_healed_by_digest": lambda v: v is True,
    "catchup_anr": lambda v: v == 0,
    "catchup_worst_hang_ms": lambda v: v < 400,
    "friend_drawing_worst_hang_ms": lambda v: v < 400,
    "friend_drawing_anr": lambda v: v == 0,
    "wallpaper_writes_on_home_screen": lambda v: v == 0,
    "wallpaper_write_during_unlock": lambda v: v == 0,
    "ten_app_missing_strokes": lambda v: v == 0,
    "ten_fingerprint_match": lambda v: v is True,
    "ten_participants_missing_app_strokes": lambda v: v == 0,
    "ten_worst_hang_ms": lambda v: v < 400,
    "ten_anr": lambda v: v == 0,
}


def metric(name, value):
    metrics[name] = value
    print(f"METRIC {name} = {value}", flush=True)


def worst_hang_ms(log):
    frames = [int(x) for x in re.findall(r"Skipped (\d+) frames", log)]
    return (max(frames) * 16.7 if frames else 0), len(frames)


def anr_count(log):
    return len(re.findall(r"ANR in " + PKG, log))


def full_log():
    return sh("logcat", "-d", "-v", "time", timeout=60)


def gfx():
    out = sh("shell", "dumpsys", "gfxinfo", PKG)
    keys = {"total": r"Total frames rendered: (\d+)", "janky": r"Janky frames: (\d+)", "p50": r"50th percentile: (\d+)ms",
            "p90": r"90th percentile: (\d+)ms", "p99": r"99th percentile: (\d+)ms", "slow_ui": r"Number Slow UI thread: (\d+)"}
    return {k: (int(m.group(1)) if (m := re.search(v, out)) else -1) for k, v in keys.items()}


def swipe(x1, y1, x2, y2, ms=350):
    sh("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def kill_existing_sockets():
    """Соединения умирают молча, как при смене вышки: пакеты пропадают, но новые подключения работают."""
    ports = set()
    for name in ("tcp", "tcp6"):
        for line in sh("shell", "cat", f"/proc/net/{name}").splitlines()[1:]:
            f = line.split()
            if len(f) > 3 and f[2].endswith(":01BB") and f[3] == "01":
                ports.add(int(f[1].split(":")[1], 16))
    for port in ports:
        for tool in ("iptables", "ip6tables"):
            sh("shell", tool, "-I", "OUTPUT", "-p", "tcp", "--sport", str(port), "-j", "DROP")
            sh("shell", tool, "-I", "INPUT", "-p", "tcp", "--dport", str(port), "-j", "DROP")
    return len(ports)


def section(fn, *args):
    try:
        fn(*args)
    except Exception:
        print(f"SECTION {fn.__name__} crashed:\n{traceback.format_exc()}", flush=True)
        metric(f"section_crashed_{fn.__name__}", True)


class Ctx:
    pass


def pick_picture(c):
    im = Image.new("RGB", (1600, 3000), (24, 70, 96))
    for y in range(0, 3000, 40):
        im.paste((40, 90, 120), (0, y, 1600, y + 20))
    pic = os.path.join(OUT, "stress_picture.jpg")
    im.save(pic, quality=95)
    sh("push", pic, "/sdcard/Pictures/holst_stress.jpg")
    sh("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/holst_stress.jpg")
    time.sleep(3)
    if e2e.open_picker():
        time.sleep(4)
        thumbs = [n for n in ui_nodes() if "photo" in (n.get("content-desc") or "").lower() and "taken" in (n.get("content-desc") or "").lower()]
        if thumbs:
            tap_xy(*center(thumbs[0]))
        else:
            tap_xy(c.W // 6, int(c.H * 0.28))
        time.sleep(5)
    shot("s0_picture_chosen")


def steady_drawing(c):
    """Обычное рисование подряд: сколько штрихов теряется и с какой задержкой."""
    W, H, friend = c.W, c.H, c.friend
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    drawn = 40
    mark = len(friend.got)
    t0 = time.time()
    for i in range(drawn):
        y = int(H * 0.25 + (i % 12) * H * 0.04)
        swipe(int(W * 0.15), y, int(W * 0.85), y + 40, 450)
        time.sleep(0.5)
    metric("draw_seconds", round(time.time() - t0))
    time.sleep(25)
    got = {g["i"]: g for g in friend.got[mark:] if g.get("k") == "s" and g["_kind"] == KIND_STORED}
    lat = sorted(g["_at"] - g["t"] / 1000 for g in got.values())
    metric("strokes_drawn", drawn)
    metric("strokes_lost", drawn - len(got))
    metric("stroke_latency_p50_ms", int(lat[len(lat) // 2] * 1000) if lat else -1)
    metric("stroke_latency_p95_ms", int(lat[int(len(lat) * .95)] * 1000) if lat else -1)
    metric("live_chunks_delivered", len([g for g in friend.got[mark:] if g.get("k") == "l"]))
    g = gfx()
    metric("own_draw_frames", g)
    shot("s1_after_drawing")


def long_stroke(c):
    """Один длинный штрих без отрыва пальца: живые куски идут непрерывно, как при настоящем рисовании."""
    W, H, friend = c.W, c.H, c.friend
    sh("logcat", "-c")
    mark = len(friend.got)
    pts = [(int(W * (0.5 + 0.38 * __import__("math").sin(i / 9))), int(H * (0.5 + 0.3 * __import__("math").sin(i / 23)))) for i in range(320)]
    t0 = time.time()
    draw_curve(pts)
    seconds = time.time() - t0
    metric("long_stroke_seconds", round(seconds))
    hit = friend.wait_for(lambda m: m.get("k") == "s" and m in friend.got[mark:], 30)
    metric("long_stroke_delivered", bool(hit))
    live = [g for g in friend.got[mark:] if g.get("k") == "l"]
    metric("long_stroke_live_chunks", len(live))
    if live:
        sid = live[0]["i"]
        seqs = sorted({g["n"] for g in live if g["i"] == sid})
        metric("long_stroke_live_chunks_missing", seqs[-1] + 1 - len(seqs))
    log = full_log()
    rej = re.findall(r"rejected by (wss://[^:]+): ([^\n]{0,40})", log)
    per = {}
    for u, m in rej:
        per.setdefault(u, {}).setdefault(m.strip(), 0)
        per[u][m.strip()] += 1
    metric("relay_rejections", sum(sum(v.values()) for v in per.values()))
    print("  rejections:", json.dumps(per, ensure_ascii=False), flush=True)
    print("  cooling:", re.findall(r"cooling (wss://\S+)", log)[:20], flush=True)


def clear_with_clock_skew(c):
    """Стирание, когда часы у друзей расходятся: у друга они спешат на 2 минуты."""
    W, H, friend, dp, bar_y = c.W, c.H, c.friend, c.dp, c.bar_y
    friend.publish(KIND_STORED, "clear", {"k": "c", "t": int((time.time() + 120) * 1000)}, created_at=int(time.time()) + 120)
    time.sleep(6)
    swipe(int(W * 0.2), int(H * 0.5), int(W * 0.8), int(H * 0.55))
    time.sleep(3)
    box = (0, int(H * .45), W, int(H * .6))
    before = count_color(shot("s2_before_clear"), (255, 255, 255), tol=20, box=box)
    marker = time.time()
    tap_xy(int(40 * dp), bar_y)
    time.sleep(1.2)
    shot("s2_panel")
    node = find_text("Стереть всё")
    if node is None:
        print("  no clear button on screen", flush=True)
        return
    x, y = center(node)
    tap_xy(x, y)
    time.sleep(0.5)
    tap_xy(x, y)
    hit = friend.wait_for(lambda m: m.get("k") == "c" and m["_at"] > marker, 40)
    metric("clear_reached_friend_with_clock_skew", bool(hit))
    metric("clear_latency_ms", int((hit[0]["_at"] - marker) * 1000) if hit else -1)
    time.sleep(2)
    after = count_color(shot("s2_after_clear"), (255, 255, 255), tol=20, box=box)
    print("  white before/after clear on my screen:", before, after, flush=True)
    metric("clear_erased_my_screen", before > 300 and after < before / 4)


def undo_with_clock_skew(c):
    W, H, friend, dp, bar_y = c.W, c.H, c.friend, c.dp, c.bar_y
    marker = time.time()
    swipe(int(W * 0.2), int(H * 0.6), int(W * 0.8), int(H * 0.62))
    time.sleep(3)
    tap_xy(W - int(40 * dp), bar_y)
    hit = friend.wait_for(lambda m: m.get("k") == "d" and m["_at"] > marker, 30)
    metric("undo_reached_friend", bool(hit))


def friend_clear_old_clock(c):
    """У друга часы отстают. Его стирание всё равно должно сработать."""
    W, H, friend = c.W, c.H, c.friend
    swipe(int(W * 0.2), int(H * 0.5), int(W * 0.8), int(H * 0.52))
    time.sleep(3)
    box = (0, int(H * .45), W, int(H * .6))
    before = count_color(shot("s2b_before"), (255, 255, 255), tol=20, box=box)
    t = int((time.time() + 200) * 1000)
    friend.publish(KIND_STORED, f"c{t}", {"k": "c", "t": t}, created_at=int(time.time()) - 60)
    time.sleep(6)
    after = count_color(shot("s2b_after"), (255, 255, 255), tol=20, box=box)
    metric("friend_clear_at_old_clock_applied", before > 300 and after < before / 4)


def silent_disconnect(c):
    """Связь пропала незаметно, а телефон об этом не знает: это и выглядит как зависание."""
    W, H, friend = c.W, c.H, c.friend
    n = kill_existing_sockets()
    print("  silently killed sockets:", n, flush=True)
    marker = time.time()
    mark = len(friend.got)
    swipe(int(W * 0.2), int(H * 0.4), int(W * 0.8), int(H * 0.42))
    hit = friend.wait_for(lambda m: m.get("k") == "s" and m in friend.got[mark:], 90)
    metric("delivery_after_silent_disconnect_ms", int((hit[0]["_at"] - marker) * 1000) if hit else -1)

    n = kill_existing_sockets()
    print("  silently killed sockets again:", n, flush=True)
    marker = time.time()
    # Настоящий телефон друга уже знает о стирании, поэтому его штрих новее. Здесь это задаём явно.
    friend.stroke(0xFF00FF5A, 3000, t=int((time.time() + 260) * 1000))
    arrived = -1
    for _ in range(70):
        time.sleep(1)
        if count_color(shot("s3_friend_after_silent"), (0, 255, 90)) > 1000:
            arrived = int((time.time() - marker) * 1000)
            break
    metric("friend_visible_after_silent_disconnect_ms", arrived)


def anti_entropy(c):
    """Друг потерял часть штрихов: приложение должно дослать их само."""
    W, H, friend = c.W, c.H, c.friend
    swipe(int(W * 0.2), int(H * 0.7), int(W * 0.8), int(H * 0.72))
    time.sleep(10)
    mark = len(friend.got)
    friend.publish(KIND_LIVE, None, {"k": "v", "f": "friend-device", "c": 0, "w": 0, "s": [], "x": []})
    time.sleep(25)
    resent = {g["i"] for g in friend.got[mark:] if g.get("k") == "s"}
    metric("lost_strokes_resent_after_inventory", len(resent))

    # Стирание потерялось в пути: друг сообщает о нём только в сверке.
    box = (0, int(H * .65), W, int(H * .78))
    before = count_color(shot("s4_before_digest_clear"), (255, 255, 255), tol=20, box=box)
    t = int((time.time() + 300) * 1000)
    friend.publish(KIND_LIVE, None, {"k": "h", "f": "friend-device", "h": "0", "n": 0, "c": t})
    time.sleep(6)
    after = count_color(shot("s4_after_digest_clear"), (255, 255, 255), tol=20, box=box)
    metric("lost_clear_healed_by_digest", before > 300 and after < before / 4)
    print("  digest clear white before/after:", before, after, flush=True)


def flood(c):
    """Телефон был без сети, теперь догоняет. Ретрансляторы отдают новые штрихи первыми."""
    W, H, friend = c.W, c.H, c.friend
    time.sleep(5)
    sh("shell", "svc", "wifi", "disable")
    sh("shell", "svc", "data", "disable")
    time.sleep(5)
    # Часы стирания из прошлого шага ушли вперёд: штрихи друга должны быть новее.
    base = int((time.time() + 400) * 1000)
    for i in range(150):
        sid = secrets.token_hex(8)
        y = -9000 + (i % 120) * 150
        pts = []
        px = py = 0
        for j in range(40):
            x = 800 + j * 210
            pts += [x - px, y + (j % 5) * 30 - py]
            px, py = x, y + (j % 5) * 30
        friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": base + i, "c": 0xFFFF00C8, "w": 120, "e": 0, "p": pts, "u": 600})
        time.sleep(0.15)
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    sh("shell", "svc", "wifi", "enable")
    sh("shell", "svc", "data", "enable")
    # Пока идёт догон, рисуем: так видно, отвечает ли экран.
    for i in range(12):
        swipe(int(W * 0.2), int(H * (0.3 + i * .04)), int(W * 0.8), int(H * (0.3 + i * .04)), 300)
        time.sleep(1)
    time.sleep(10)
    log = full_log()
    hang, n = worst_hang_ms(log)
    metric("catchup_worst_hang_ms", int(hang))
    metric("catchup_hang_events", n)
    metric("catchup_anr", anr_count(log))
    metric("catchup_frames", gfx())
    metric("catchup_px", count_color(shot("s5_after_flood"), (255, 0, 200), tol=30))


def friend_drawing(c):
    """Друг быстро рисует, а я смотрю. Приложение при этом открыто в фоне."""
    friend = c.friend
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    base = int((time.time() + 500) * 1000)
    for i in range(50):
        sid = secrets.token_hex(8)
        pts = []
        px = py = 0
        for j in range(60):
            x, y = 1000 + j * 130, -4000 + (i % 30) * 250 + (j % 8) * 60
            pts += [x - px, y - py]
            px, py = x, y
        friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": base + i, "c": 0xFFFFD60A, "w": 120, "e": 0, "p": pts, "u": 500})
        time.sleep(0.33)
    time.sleep(4)
    log = full_log()
    hang, n = worst_hang_ms(log)
    metric("friend_drawing_worst_hang_ms", int(hang))
    metric("friend_drawing_hang_events", n)
    metric("friend_drawing_anr", anr_count(log))
    metric("friend_drawing_frames", gfx())
    shot("s6_friend_drawing")


def ten_people(c):
    """Телефон и ещё 9 участников рисуют одновременно. Настоящая проверка: лист должен совпасть."""
    W, H, friend = c.W, c.H, c.friend
    # Начинаем с чистого листа, чтобы точно знать, что на нём должно быть.
    base = int((time.time() + 700) * 1000)
    friend.publish(KIND_STORED, f"c{base}", {"k": "c", "t": base - 1000})
    time.sleep(6)
    relays = e2e.RELAYS
    bots = [Friend(c.code, relays=[relays[(i + j) % len(relays)] for j in range(3)]) for i in range(9)]
    time.sleep(8)
    print("  connected:", [len(b.conns) for b in bots], flush=True)
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    mark = len(friend.got)
    sent, lock, seconds = {}, threading.Lock(), 60
    end = time.time() + seconds

    def draw(n, bot):
        seq_t = 0
        while time.time() < end:
            sid = secrets.token_hex(8)
            all_pts = []
            row = -7000 + n * 1500 + (seq_t % 4) * 300
            for j in range(24):
                all_pts.append((800 + j * 360, row + (j % 6) * 60))
            for s in range(3):
                part = all_pts[s * 8:(s + 1) * 8 + 1]
                px = py = 0
                delta = []
                for x, y in part:
                    delta += [x - px, y - py]
                    px, py = x, y
                bot.publish(KIND_LIVE, None, {"k": "l", "i": sid, "c": 0xFF000000 | (0x1F << 16) | (n * 28 << 8) | 0xE0, "w": 90, "e": 0,
                                              "n": s, "a": s * 400, "b": (s + 1) * 400, "p": delta})
                time.sleep(.42)
            px = py = 0
            delta = []
            for x, y in all_pts:
                delta += [x - px, y - py]
                px, py = x, y
            bot.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": base + int((time.time() % 100000) * 1000) + n, "c": 0xFF000000 | (0x1F << 16) | (n * 28 << 8) | 0xE0,
                                           "w": 90, "e": 0, "u": 1200, "p": delta})
            with lock:
                sent[sid] = n
            seq_t += 1
            time.sleep(.3)

    threads = [threading.Thread(target=draw, args=(i, b), daemon=True) for i, b in enumerate(bots)]
    for t in threads:
        t.start()
    time.sleep(1)
    shot("s6a_ten_midway_0")
    drawn_by_app = 0
    while time.time() < end - 2:
        y = int(H * (0.25 + (drawn_by_app % 10) * .05))
        swipe(int(W * .15), y, int(W * .85), y + 40, 700)
        drawn_by_app += 1
        time.sleep(.6)
    for t in threads:
        t.join(20)
    shot("s6a_ten_right_after")
    # Ни один из 9 участников не отвечает на сверку, поэтому лист сходится только за счёт самих ретрансляторов.
    time.sleep(35)
    shot("s6a_ten_after_wait")
    log = full_log()
    hang, n = worst_hang_ms(log)
    metric("ten_worst_hang_ms", int(hang))
    metric("ten_hang_events", n)
    metric("ten_anr", anr_count(log))
    metric("ten_frames", gfx())
    rej = re.findall(r"rejected by (wss://[^:]+): ([^\n]{0,40})", log)
    per = {}
    for u, m in rej:
        per.setdefault(u, {}).setdefault(m.strip(), 0)
        per[u][m.strip()] += 1
    print("  app rejections:", json.dumps(per, ensure_ascii=False), flush=True)
    print("  app cooling:", sorted(set(re.findall(r"cooling (wss://\S+)", log))), flush=True)
    bot_rej = {}
    for b in bots:
        for eid, m in b.acks.items():
            for url, (ok, msg) in m.items():
                if not ok:
                    bot_rej[(url, msg[:40])] = bot_rej.get((url, msg[:40]), 0) + 1
    print("  participants rejections:", bot_rej, flush=True)

    app_ids = {g["i"] for g in friend.got[mark:] if g.get("k") == "s" and g["i"] not in sent}
    metric("ten_strokes_by_participants", len(sent))
    metric("ten_strokes_by_app", drawn_by_app)
    metric("ten_app_strokes_seen", len(app_ids))
    missing = sum(len(app_ids - {g.get("i") for g in b.got if g.get("k") == "s"}) for b in bots)
    metric("ten_participants_missing_app_strokes", missing)
    cross = 0
    for i, b in enumerate(bots):
        have = {g.get("i") for g in b.got if g.get("k") == "s"}
        cross += sum(1 for sid, n in sent.items() if n != i and sid not in have)
    metric("ten_missing_between_participants", cross)

    # Приложение само сообщает отпечаток листа в сверке: сравниваем его с тем, что должно быть.
    want_ids = set(sent) | app_ids
    want = 0
    for sid in want_ids:
        want ^= int(sid, 16)
    digests = []
    deadline = time.time() + 60
    while time.time() < deadline:
        digests = [g for g in friend.got if g.get("k") == "h"]
        if digests and digests[-1]["_at"] > time.time() - 12:
            break
        time.sleep(1)
    last = digests[-1] if digests else {}
    metric("ten_app_sheet_size", last.get("n", -1))
    metric("ten_expected_sheet_size", len(want_ids))
    metric("ten_app_missing_strokes", len(want_ids) - last.get("n", 0))
    metric("ten_fingerprint_match", last.get("h") == format(want, "x"))
    since = [g for g in friend.got[mark:]]
    span = max(1, (since[-1]["_at"] - since[0]["_at"]) / 60) if since else 1
    metric("ten_app_digests_per_min", round(len([g for g in since if g.get("k") == "h"]) / span, 1))
    metric("ten_app_inventories_per_min", round(len([g for g in since if g.get("k") == "v"]) / span, 1))
    for b in bots:
        b.stop = True
        for ws in list(b.conns.values()):
            try:
                ws.close()
            except Exception:
                pass


def home_screen(c):
    """Телефон разблокирован, человек листает рабочий стол, а друг в это время рисует."""
    W, H, friend, bar_y = c.W, c.H, c.friend, c.bar_y
    launcher = "com.google.android.apps.nexuslauncher"
    # Перед разблокировкой на листе есть новый штрих: раньше обои писались прямо в момент открытия.
    swipe(int(W * 0.2), int(H * 0.6), int(W * 0.8), int(H * 0.62))
    time.sleep(2)
    sh("logcat", "-c")
    tap_xy(W // 2, bar_y)
    time.sleep(2.5)
    sh("shell", "input", "text", "1234")
    sh("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    print("  after unlock:", top_activity(), flush=True)
    metric("wallpaper_write_during_unlock", full_log().count("lock wallpaper updated"))
    sh("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    shot("s7_home")
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", launcher, "reset")
    base = int((time.time() + 2000) * 1000)
    stop_at = time.time() + 60
    i = 0
    next_stroke = 0
    while time.time() < stop_at:
        sid = secrets.token_hex(8)
        pts = []
        px = py = 0
        for j in range(30):
            x, y = 1000 + j * 250, -3000 + (i % 30) * 200
            pts += [x - px, y - py]
            px, py = x, y
        # Штрих раз в 8 секунд: друг рисует не спеша, между штрихами есть паузы.
        if time.time() >= next_stroke:
            friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": base + i, "c": 0xFF32D74B, "w": 120, "e": 0, "p": pts, "u": 400})
            i += 1
            next_stroke = time.time() + 8
        swipe(int(W * .85), H // 2, int(W * .15), H // 2, 250)
        time.sleep(.4)
        swipe(int(W * .15), H // 2, int(W * .85), H // 2, 250)
        time.sleep(.4)
    time.sleep(3)
    log = full_log()
    metric("wallpaper_writes_on_home_screen", log.count("lock wallpaper updated"))
    out = sh("shell", "dumpsys", "gfxinfo", launcher)
    keys = {"total": r"Total frames rendered: (\d+)", "janky": r"Janky frames: (\d+)", "p50": r"50th percentile: (\d+)ms",
            "p90": r"90th percentile: (\d+)ms", "p99": r"99th percentile: (\d+)ms"}
    metric("home_screen_frames", {k: (int(m.group(1)) if (m := re.search(v, out)) else -1) for k, v in keys.items()})

    # Контроль: те же движения, но приложение полностью остановлено.
    sh("shell", "am", "force-stop", PKG)
    time.sleep(5)
    sh("shell", "dumpsys", "gfxinfo", launcher, "reset")
    stop_at = time.time() + 40
    while time.time() < stop_at:
        swipe(int(W * .85), H // 2, int(W * .15), H // 2, 250)
        time.sleep(.4)
        swipe(int(W * .15), H // 2, int(W * .85), H // 2, 250)
        time.sleep(.4)
    out = sh("shell", "dumpsys", "gfxinfo", launcher)
    metric("home_screen_frames_app_stopped", {k: (int(m.group(1)) if (m := re.search(v, out)) else -1) for k, v in keys.items()})


def main():
    strict = "--strict" in sys.argv
    apk = [a for a in sys.argv[1:] if not a.startswith("--")][0]
    os.makedirs(OUT, exist_ok=True)
    code = "".join(str(secrets.randbelow(10)) for _ in range(12))
    c = Ctx()
    c.friend = Friend(code)
    c.code = code
    sh("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    sh("install", "-r", "-g", apk, timeout=180)
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    sh("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    sh("root")
    time.sleep(3)
    size = re.findall(r"(\d+)x(\d+)", sh("shell", "wm", "size"))[-1]
    c.W, c.H = int(size[0]), int(size[1])
    c.dp = c.W / 411
    c.bar_y = int(c.H - 62 * c.dp)
    e2e.stabilize()
    if not e2e.join_room(code):
        print("НЕ ПРОШЛО: не удалось настроить приложение", flush=True)
        shot("s_setup_failed")
        sys.exit(1 if strict else 0)
    time.sleep(8)
    section(pick_picture, c)
    sh("shell", "locksettings", "set-pin", "1234")
    # Приложение остаётся открытым в фоне: люди обычно просто гасят экран, не закрывая его.
    sleep_phone()
    wake_phone()
    print("on layer:", on_layer(), top_activity(), flush=True)
    if not on_layer():
        sleep_phone()
        wake_phone()
        print("on layer after retry:", on_layer(), top_activity(), flush=True)
    shot("s0_layer")

    for fn in (steady_drawing, long_stroke, clear_with_clock_skew, undo_with_clock_skew, friend_clear_old_clock,
               silent_disconnect, anti_entropy, flood, friend_drawing, ten_people, home_screen):
        print(f"--- {fn.__name__}", flush=True)
        section(fn, c)

    log = sh("logcat", "-d", "-v", "time", "Holst:D", "AndroidRuntime:E", "ActivityManager:E", "*:S", timeout=60)
    with open(os.path.join(OUT, "stress_logcat.txt"), "w") as f:
        f.write(log[-300000:])
    print("\nИТОГИ:", flush=True)
    print(json.dumps(metrics, ensure_ascii=False, indent=1), flush=True)
    with open(os.path.join(OUT, "stress.json"), "w") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=1)
    failed = [k for k, ok in STRICT.items() if k not in metrics or not ok(metrics[k])]
    failed += [k for k in metrics if k.startswith("section_crashed_")]
    print("НЕ ПРОШЛО:", failed, flush=True)
    if strict and failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
