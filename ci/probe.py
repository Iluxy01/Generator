#!/usr/bin/env python3
"""Замер ретрансляторов при нагрузке как от настоящего рисования: живые куски плюс готовые штрихи.
У каждого ретранслятора свой случайный ключ. Второе соединение проверяет, что событие реально доходит."""
import base64, hashlib, json, secrets, sys, threading, time
import websocket
from e2e import G, pmul, schnorr_sign

CANDIDATES = """relay.primal.net nos.lol nostr.mom relay.snort.social nostr.data.haus nostr.oxtr.dev relay.damus.io
nostr-pub.wellorder.net offchain.pub nostr.bitcoiner.social relay.nostr.net relay.mostr.pub relay.coinos.io
relay.nostr.band strfry.iris.to nostr.einundzwanzig.space nostr-01.yakihonne.com relay.fountain.fm
nostr.fmt.wiz.biz relay.sigit.io relay.nos.social""".split()
SECONDS = 90
LIVE_PER_SEC = 4
STORED_EVERY = 1.0
rows = []


def event(sk, pub, kind, tags, content):
    created = int(time.time())
    ser = json.dumps([0, pub, created, kind, tags, content], separators=(",", ":"))
    eid = hashlib.sha256(ser.encode()).digest()
    return {"id": eid.hex(), "pubkey": pub, "created_at": created, "kind": kind, "tags": tags,
            "content": content, "sig": schnorr_sign(eid, sk).hex()}


def probe(host):
    url = "wss://" + host
    r = {"host": host, "sent_live": 0, "sent_stored": 0, "ok": 0, "rej": 0, "msgs": {}, "got_live": 0, "got_stored": 0, "rtt": [], "error": ""}
    rows.append(r)
    sk = secrets.token_bytes(32)
    pub = pmul(G, int.from_bytes(sk, "big"))[0].to_bytes(32, "big").hex()
    sent_at = {}
    try:
        t0 = time.time()
        rx = websocket.create_connection(url, timeout=12, header=["User-Agent: Holst-probe"])
        tx = websocket.create_connection(url, timeout=12, header=["User-Agent: Holst-probe"])
        r["connect_ms"] = int((time.time() - t0) * 500)
    except Exception as e:
        r["error"] = f"connect: {e}"
        return
    rx.send(json.dumps(["REQ", "x", {"authors": [pub], "kinds": [20078, 30078], "since": int(time.time()) - 60}]))
    stop = []

    def reader(ws, is_rx):
        ws.settimeout(5)
        while not stop:
            try:
                m = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            except Exception as e:
                if not stop:
                    r["error"] = f"{'rx' if is_rx else 'tx'} lost at {int(time.time() - start)}s: {e}"
                return
            if m[0] == "EVENT" and is_rx:
                e = m[2]
                r["got_live" if e["kind"] == 20078 else "got_stored"] += 1
                if e["id"] in sent_at:
                    r["rtt"].append(time.time() - sent_at[e["id"]])
            elif m[0] == "OK":
                if m[2]:
                    r["ok"] += 1
                else:
                    r["rej"] += 1
                    key = (m[3] if len(m) > 3 else "")[:70]
                    r["msgs"][key] = r["msgs"].get(key, 0) + 1
            elif m[0] == "NOTICE":
                key = "NOTICE " + str(m[1])[:60]
                r["msgs"][key] = r["msgs"].get(key, 0) + 1

    start = time.time()
    threading.Thread(target=reader, args=(rx, True), daemon=True).start()
    threading.Thread(target=reader, args=(tx, False), daemon=True).start()
    next_stored = start
    content = base64.b64encode(secrets.token_bytes(180)).decode()
    try:
        while time.time() - start < SECONDS:
            ev = event(sk, pub, 20078, [], content)
            sent_at[ev["id"]] = time.time()
            tx.send(json.dumps(["EVENT", ev]))
            r["sent_live"] += 1
            if time.time() >= next_stored:
                big = base64.b64encode(secrets.token_bytes(1200)).decode()
                ev = event(sk, pub, 30078, [["d", secrets.token_hex(8)]], big)
                sent_at[ev["id"]] = time.time()
                tx.send(json.dumps(["EVENT", ev]))
                r["sent_stored"] += 1
                next_stored += STORED_EVERY
            time.sleep(1 / LIVE_PER_SEC)
    except Exception as e:
        r["error"] = f"send at {int(time.time() - start)}s: {e}"
    time.sleep(6)
    stop.append(1)


threads = [threading.Thread(target=probe, args=(h,), daemon=True) for h in CANDIDATES]
for t in threads:
    t.start()
for t in threads:
    t.join(SECONDS + 40)

print(f"{'relay':30} {'conn':>5} {'live':>9} {'stored':>9} {'ok':>4} {'rej':>4} {'rtt50':>6} {'rtt95':>6}  notes")
for r in sorted(rows, key=lambda x: (-(x["got_live"] + x["got_stored"]), x["host"])):
    rtt = sorted(r["rtt"])
    p50 = f"{rtt[len(rtt) // 2] * 1000:.0f}" if rtt else "-"
    p95 = f"{rtt[int(len(rtt) * .95)] * 1000:.0f}" if rtt else "-"
    print(f"{r['host']:30} {r.get('connect_ms', '-'):>5} {r['got_live']:>4}/{r['sent_live']:<4} {r['got_stored']:>4}/{r['sent_stored']:<4} {r['ok']:>4} {r['rej']:>4} {p50:>6} {p95:>6}  {r['error']} {r['msgs'] or ''}", flush=True)
