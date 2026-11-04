#!/usr/bin/env python3
"""Короткая проверка на других версиях Android и размерах экрана: первый запуск, холст на экране
блокировки, рисование в обе стороны, разблокировка."""
import os, re, secrets, sys, time
import e2e
from e2e import Friend, KIND_STORED, OUT, PKG, check, count_color, join_room, on_layer, results, sh, shot, sleep_phone, tap_xy, top_activity, wake_phone


def main():
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    print("android", sh("shell", "getprop", "ro.build.version.sdk").strip(), flush=True)
    code = "".join(str(secrets.randbelow(10)) for _ in range(12))
    friend = Friend(code)
    sh("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    print(sh("install", "-r", "-g", apk, timeout=240))
    sh("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    sh("shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}")
    W, H = e2e.screen_size()
    dp = e2e.dp_px()
    print("screen", W, H, "dp", dp, flush=True)
    sh("logcat", "-c")
    e2e.stabilize()

    check("первый запуск и вход по коду", join_room(code))
    time.sleep(10)
    shot("smoke_main")
    print(sh("shell", "locksettings", "set-pin", "1234"), flush=True)
    time.sleep(1)
    sleep_phone()
    wake_phone()
    if not on_layer():
        sleep_phone()
        wake_phone()
    check("холст на экране блокировки", on_layer(), top_activity())
    shot("smoke_layer")

    mark = len(friend.got)
    sh("shell", "input", "swipe", str(W // 5), str(int(H * .55)), str(W * 4 // 5), str(int(H * .6)), "500")
    hit = friend.wait_for(lambda m: m.get("k") == "s" and m in friend.got[mark:], 40)
    check("друг получил штрих", bool(hit))

    sid = secrets.token_hex(8)
    friend.publish(KIND_STORED, sid, {"k": "s", "i": sid, "t": int((time.time() + 5) * 1000), "c": 0xFFFF00C8, "w": 300, "e": 0,
                                      "p": [800, -2000, 2200, 0, 2000, 0, 2000, 0, 2200, 0], "u": 300})
    seen = 0
    for _ in range(25):
        time.sleep(2)
        seen = count_color(shot("smoke_friend"), (255, 0, 200))
        if seen > 300:
            break
    check("штрих друга виден", seen > 300, f"{seen} точек")

    bar_y = int(H - 62 * dp)
    lock_x = W - int(89 * dp)
    tap_xy(lock_x, bar_y)
    time.sleep(2)
    e2e.tap_text("Got it", timeout=2, scroll=False)
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    check("блокировка: «назад» не закрывает холст", on_layer(), top_activity())
    shot("smoke_lock_mode")
    sh("shell", "input", "swipe", str(lock_x), str(bar_y), str(lock_x), str(bar_y), "1000")
    time.sleep(1.5)

    tap_xy(W // 2, bar_y)
    time.sleep(2.5)
    shot("smoke_bouncer")
    sh("shell", "input", "text", "1234")
    sh("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(4)
    check("разблокировка закрывает холст", not on_layer(), top_activity())
    shot("smoke_unlocked")

    log = sh("logcat", "-d", "-v", "brief", timeout=60)
    crashes = re.findall(r"FATAL EXCEPTION[\s\S]{0,600}", log)
    crashes = [c for c in crashes if PKG in c]
    check("приложение ни разу не упало", not crashes, crashes[0][:500] if crashes else "")

    friend.stop = True
    failed = [r for r in results if not r[1] and r[2]]
    print(f"ИТОГ: {sum(1 for r in results if r[1])} из {len(results)}, провалов: {len(failed)}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
