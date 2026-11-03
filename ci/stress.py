#!/usr/bin/env python3
"""Стресс-тест: воспроизводит то, на что жалуются люди. Обязательные условия — только в конце, по METRIC."""
import json, os, re, secrets, subprocess, sys, time
import e2e
from e2e import Friend, KIND_LIVE, KIND_STORED, OUT, PKG, count_color, draw_curve, find_text, on_layer, sh, shot, sleep_phone, tap_text, tap_xy, wake_phone, top_activity

metrics = {}


def metric(name, value):
    metrics[name] = value
    print(f"METRIC {name} = {value}", flush=True)


def logcat_since_clear():
    return sh("logcat", "-d", "-v", "time", timeout=60)


def worst_hang_ms(log):
    frames = [int(x) for x in re.findall(r"Skipped (\d+) frames", log)]
    return (max(frames) * 16.7 if frames else 0), len(frames)


def anr_count(log):
    return len(re.findall(r"ANR in " + PKG, log))


def gfx():
    out = sh("shell", "dumpsys", "gfxinfo", PKG)
    keys = {"total": r"Total frames rendered: (\d+)", "janky": r"Janky frames: (\d+)", "p90": r"90th percentile: (\d+)ms",
            "p99": r"99th percentile: (\d+)ms", "slow_ui": r"Number Slow UI thread: (\d+)"}
    return {k: (int(m.group(1)) if (m := re.search(v, out)) else -1) for k, v in keys.items()}


def swipe(x1, y1, x2, y2, ms=350):
    sh("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    code = "".join(str(secrets.randbelow(10)) for _ in range(12))
    friend = Friend(code)
    sh("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    sh("install", "-r", "-g", apk, timeout=180)
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    sh("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    sh("root")
    time.sleep(3)
    size = re.findall(r"(\d+)x(\d+)", sh("shell", "wm", "size"))[-1]
    W, H = int(size[0]), int(size[1])
    dp = W / 411
    bar_y = int(H - 62 * dp)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    tap_text("Чистый лист")
    time.sleep(2)
    tap_text("Ввести код друга")
    time.sleep(1.5)
    sh("shell", "input", "text", code)
    time.sleep(1)
    tap_text("Присоединиться", scroll=False)
    time.sleep(12)
    sh("shell", "locksettings", "set-pin", "1234")
    # Приложение остаётся открытым в фоне: люди обычно просто гасят экран, не закрывая его.
    sleep_phone()
    wake_phone()
    print("on layer:", on_layer(), top_activity(), flush=True)

    # ---- 1. Обычное рисование подряд: сколько штрихов теряется и с какой задержкой ----
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    drawn = 40
    start_mark = len(friend.got)
    t0 = time.time()
    for i in range(drawn):
        y = int(H * 0.25 + (i % 12) * H * 0.04)
        swipe(int(W * 0.15), y, int(W * 0.85), y + 40, 450)
        time.sleep(0.5)
    metric("draw_seconds", round(time.time() - t0))
    time.sleep(25)
    got = [g for g in friend.got[start_mark:] if g.get("k") == "s" and g["_kind"] == KIND_STORED]
    lat = sorted(g["_at"] - g["t"] / 1000 for g in got)
    metric("strokes_drawn", drawn)
    metric("strokes_delivered", len(got))
    metric("stroke_latency_p50_ms", int(lat[len(lat) // 2] * 1000) if lat else -1)
    metric("stroke_latency_p95_ms", int(lat[int(len(lat) * .95)] * 1000) if lat else -1)
    live = [g for g in friend.got[start_mark:] if g.get("k") == "l"]
    metric("live_chunks_delivered", len(live))
    log = logcat_since_clear()
    rej = re.findall(r"rejected by (wss://[^:]+): ([^\n]{0,40})", log)
    per = {}
    for u, m in rej:
        per.setdefault(u, {}).setdefault(m.strip(), 0)
        per[u][m.strip()] += 1
    metric("relay_rejections", sum(sum(v.values()) for v in per.values()))
    print("  rejections:", json.dumps(per, ensure_ascii=False), flush=True)
    g = gfx()
    metric("own_draw_p90_frame_ms", g["p90"])
    metric("own_draw_p99_frame_ms", g["p99"])
    metric("own_draw_janky_frames", f"{g['janky']}/{g['total']}")
    shot("s1_after_drawing")

    # ---- 2. Стирание при разнице часов: у друга часы спешат на 2 минуты ----
    friend.publish(KIND_STORED, "clear", {"k": "c", "t": int((time.time() + 120) * 1000)}, created_at=int(time.time()) + 120)
    time.sleep(5)
    swipe(int(W * 0.2), int(H * 0.5), int(W * 0.8), int(H * 0.55))
    time.sleep(3)
    white_before = count_color(shot("s2_before_clear"), (255, 255, 255), tol=20, box=(0, int(H * .45), W, int(H * .6)))
    marker = time.time()
    tap_xy(int(40 * dp), bar_y)
    time.sleep(1.2)
    shot("s2_panel")
    tap_text("Стереть всё", timeout=6, scroll=False)
    time.sleep(0.5)
    node = find_text("Точно стереть", contains=True)
    if node is not None:
        tap_xy(*e2e.center(node))
    hit = friend.wait_for(lambda m: m.get("k") == "c" and m["_at"] > marker, 40)
    metric("clear_reached_friend_with_clock_skew", bool(hit))
    metric("clear_latency_ms", int((hit[0]["_at"] - marker) * 1000) if hit else -1)
    time.sleep(2)
    white_after = count_color(shot("s2_after_clear"), (255, 255, 255), tol=20, box=(0, int(H * .45), W, int(H * .6)))
    print("  white before/after clear on my screen:", white_before, white_after, flush=True)

    # ---- 3. Соединение «умерло» без обрыва, как при смене вышки или во сне ----
    sh("shell", "iptables", "-I", "OUTPUT", "-p", "tcp", "--dport", "443", "-j", "DROP")
    sh("shell", "iptables", "-I", "INPUT", "-p", "tcp", "--sport", "443", "-j", "DROP")
    time.sleep(70)
    sh("shell", "iptables", "-D", "OUTPUT", "-p", "tcp", "--dport", "443", "-j", "DROP")
    sh("shell", "iptables", "-D", "INPUT", "-p", "tcp", "--sport", "443", "-j", "DROP")
    sleep_phone()
    wake_phone()
    marker = time.time()
    mark = len(friend.got)
    swipe(int(W * 0.2), int(H * 0.4), int(W * 0.8), int(H * 0.42))
    hit = friend.wait_for(lambda m: m.get("k") == "s" and m["_at"] > marker and m in friend.got[mark:], 90)
    metric("delivery_after_dead_connection_ms", int((hit[0]["_at"] - marker) * 1000) if hit else -1)

    # Обратное направление: друг рисует сразу после обрыва.
    marker2 = time.time()
    friend.stroke(0xFF00FF5A, 3000)
    arrived = -1
    for _ in range(45):
        time.sleep(1)
        if count_color(shot("s3_friend_after_dead"), (0, 255, 90)) > 1000:
            arrived = int((time.time() - marker2) * 1000)
            break
    metric("friend_stroke_visible_after_ms", arrived)

    # ---- 4. Поток штрихов: телефон был без сети, теперь догоняет. Ретрансляторы отдают новые первыми ----
    sh("shell", "svc", "wifi", "disable")
    sh("shell", "svc", "data", "disable")
    time.sleep(5)
    flood = 150
    for i in range(flood):
        friend.stroke(0xFF3399FF, -9000 + (i % 120) * 150, width=120)
        time.sleep(0.2)
    print("  flood published, acks:", len(friend.acks), flush=True)
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    sh("shell", "svc", "wifi", "enable")
    sh("shell", "svc", "data", "enable")
    # Пока идёт догон, пытаемся рисовать: так видно, отвечает ли экран.
    for i in range(12):
        swipe(int(W * 0.2), int(H * (0.3 + i * .04)), int(W * 0.8), int(H * (0.3 + i * .04)), 300)
        time.sleep(1)
    time.sleep(10)
    log = logcat_since_clear()
    hang, n = worst_hang_ms(log)
    metric("catchup_worst_hang_ms", int(hang))
    metric("catchup_hang_events", n)
    metric("catchup_anr", anr_count(log))
    g = gfx()
    metric("catchup_p99_frame_ms", g["p99"])
    metric("catchup_slow_ui_frames", g["slow_ui"])
    path = shot("s4_after_flood")
    metric("catchup_blue_px", count_color(path, (51, 153, 255), tol=30))

    # ---- 5. Друг быстро рисует, а я смотрю ----
    sh("logcat", "-c")
    sh("shell", "dumpsys", "gfxinfo", PKG, "reset")
    for i in range(50):
        sid = secrets.token_hex(8)
        pts = []
        px = py = 0
        for j in range(60):
            x, y = 1000 + j * 130, -4000 + (i % 30) * 250 + (j % 8) * 60
            pts += [x - px, y - py]
            px, py = x, y
        friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": int(time.time() * 1000), "c": 0xFFFFD60A, "w": 120, "e": 0, "p": pts, "u": 500})
        time.sleep(0.33)
    time.sleep(4)
    log = logcat_since_clear()
    hang, n = worst_hang_ms(log)
    metric("friend_drawing_worst_hang_ms", int(hang))
    metric("friend_drawing_hang_events", n)
    g = gfx()
    metric("friend_drawing_p99_frame_ms", g["p99"])
    shot("s5_friend_drawing")

    log = sh("logcat", "-d", "-v", "time", "Holst:D", "AndroidRuntime:E", "ActivityManager:E", "*:S", timeout=60)
    with open(os.path.join(OUT, "stress_logcat.txt"), "w") as f:
        f.write(log[-300000:])
    print("\nИТОГИ:", flush=True)
    print(json.dumps(metrics, ensure_ascii=False, indent=1), flush=True)
    with open(os.path.join(OUT, "stress.json"), "w") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
