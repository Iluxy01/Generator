package app.holst;

import android.os.PowerManager;
import android.util.Log;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Связывает лист на телефоне с ретрансляторами. Ни одно отдельное сообщение не обязано дойти:
 * события повторяются, пока их не подтвердят, а телефоны регулярно сверяют отпечатки листов и
 * досылают друг другу то, чего не хватает.
 */
final class Sync implements RelayPool.Listener, Repo.Listener {
    private static final String TAG = "Holst";
    private static final long PUMP_MS = 400;
    private static final int PUMP_BUDGET = 5;
    private static final long INVENTORY_EVERY_MS = 10_000;
    private static final long PEER_ONLINE_MS = 200_000;

    private final App app;
    private final String deviceId;
    private final Map<String, Repo.Op> sent = Collections.synchronizedMap(
            new LinkedHashMap<String, Repo.Op>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Repo.Op> e) {
                    return size() > 4096;
                }
            });
    private final CopyOnWriteArrayList<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private volatile Room room;
    private volatile RelayPool pool;
    private boolean started;
    private int generation;
    private volatile int open;
    private volatile int total = RelayPool.URLS.length;
    private long lastRestart;

    private long lastDigestSent;
    private long nextDigestAt;
    private long lastInventorySent;
    private boolean inventoryScheduled;
    private String peerHash;
    private int peerCount = -1;
    private long peerClear;
    private volatile long peerSeenAt;

    private final Runnable pump = this::pumpTick;
    private final Runnable digestTick = this::digestTick;
    private final Runnable inventoryCheck = this::inventoryCheck;

    Sync(App app) {
        this.app = app;
        this.deviceId = app.prefs.deviceId();
        app.repo.setOutboxListener(this::kickPump);
        app.repo.addListener(this);
    }

    int openCount() {
        return open;
    }

    int totalCount() {
        return total;
    }

    /** Когда в последний раз был слышен другой телефон; 0 — ещё ни разу. */
    long peerSeenAt() {
        return peerSeenAt;
    }

    boolean peerOnline() {
        return peerSeenAt != 0 && System.currentTimeMillis() - peerSeenAt < PEER_ONLINE_MS;
    }

    void addStatusListener(Runnable r) {
        statusListeners.add(r);
    }

    void removeStatusListener(Runnable r) {
        statusListeners.remove(r);
    }

    /** Вызывать из главного потока. Повторный вызов ничего не делает. */
    void start() {
        if (started) return;
        started = true;
        final int gen = ++generation;
        final String code = app.prefs.code();
        app.net.execute(() -> {
            final Room r = Room.derive(code);
            app.main.post(() -> {
                if (gen != generation || !started) return;
                room = r;
                RelayPool p = new RelayPool(this, r.pubHex);
                pool = p;
                updatePace();
                p.start(app.repo.sinceSec());
                kickPump();
                digestSoon(3000);
            });
        });
    }

    void stop() {
        if (!started) return;
        started = false;
        generation++;
        RelayPool p = pool;
        pool = null;
        room = null;
        sent.clear();
        open = 0;
        peerHash = null;
        peerCount = -1;
        peerSeenAt = 0;
        app.main.removeCallbacks(pump);
        app.main.removeCallbacks(digestTick);
        app.main.removeCallbacks(inventoryCheck);
        inventoryScheduled = false;
        nextDigestAt = 0;
        if (p != null) app.net.execute(p::stop);
        fireStatus();
    }

    /** Экран включился: наверстать всё, что нарисовали, пока телефон спал. */
    void catchUp() {
        RelayPool p = pool;
        if (p != null) p.catchUp(app.repo.sinceSec());
        updatePace();
        kickPump();
        digestSoon(800);
    }

    void updatePace() {
        RelayPool p = pool;
        if (p != null) p.setFast(LockActivity.isVisible());
    }

    private boolean screenOn() {
        return app.getSystemService(PowerManager.class).isInteractive();
    }

    void onNetworkChanged() {
        long now = System.currentTimeMillis();
        if (now - lastRestart < 5000) return;
        lastRestart = now;
        RelayPool p = pool;
        if (p != null) p.reconnectAll();
    }

    /** Штрих в процессе рисования: ретрансляторы только передают его, не храня. */
    void sendLive(String id, int color, int width, boolean erase, int seq, int[] pts, int startMs, int endMs) {
        final Room r = room;
        final RelayPool p = pool;
        if (r == null || p == null) return;
        app.live.execute(() -> {
            StringBuilder sb = new StringBuilder(80 + pts.length * 3);
            sb.append("{\"k\":\"l\",\"i\":\"").append(id).append("\",\"c\":").append(color)
                    .append(",\"w\":").append(width).append(",\"e\":").append(erase ? 1 : 0)
                    .append(",\"n\":").append(seq).append(",\"a\":").append(startMs)
                    .append(",\"b\":").append(endMs).append(",\"p\":");
            Stroke.appendDelta(sb, pts, pts.length);
            sb.append('}');
            Room.Event e = r.event(RelayPool.KIND_LIVE, null, r.encrypt(sb.toString()),
                    System.currentTimeMillis() / 1000);
            p.markSeen(e.id);
            p.publishLive(e.json);
        });
    }

    private void sendControl(String payload) {
        final Room r = room;
        final RelayPool p = pool;
        if (r == null || p == null) return;
        app.live.execute(() -> {
            Room.Event e = r.event(RelayPool.KIND_LIVE, null, r.encrypt(payload), System.currentTimeMillis() / 1000);
            p.markSeen(e.id);
            p.publish(e.json);
        });
    }

    // ---------- Доставка с повторами ----------

    private void kickPump() {
        app.main.removeCallbacks(pump);
        app.main.post(pump);
    }

    private void pumpTick() {
        app.main.removeCallbacks(pump);
        if (!app.repo.hasPendingOps()) return;
        final Room r = room;
        final RelayPool p = pool;
        if (r == null || p == null || open == 0) {
            app.main.postDelayed(pump, 1000);
            return;
        }
        long now = System.currentTimeMillis();
        int budget = PUMP_BUDGET;
        for (Repo.Op op : app.repo.pendingOps()) {
            if (op.nextSend > now) continue;
            op.attempts++;
            op.nextSend = now + Math.min(60_000L, 4000L << Math.min(op.attempts - 1, 4));
            app.net.execute(() -> {
                Room.Event e = eventFor(r, op);
                p.markSeen(e.id);
                p.publish(e.json);
            });
            if (--budget == 0) break;
        }
        app.main.postDelayed(pump, PUMP_MS);
    }

    /**
     * Время события — момент отправки, а не рисования. Иначе штрих, нарисованный без сети, друг не
     * получит: ретранслятор отсечёт его как слишком старый для подписки.
     */
    private Room.Event eventFor(Room r, Repo.Op op) {
        long now = System.currentTimeMillis() / 1000;
        if (op.event == null || now - op.eventSec > 120) {
            long createdAt = Math.max(now, op.sec);
            op.event = r.event(RelayPool.KIND_STORED, op.d, r.encrypt(op.payload), createdAt);
            op.eventSec = createdAt;
            sent.put(op.event.id, op);
        }
        return op.event;
    }

    // ---------- Сверка листов ----------

    private void digestSoon(long delay) {
        long at = System.currentTimeMillis() + delay;
        if (nextDigestAt != 0 && nextDigestAt <= at) return;
        scheduleDigest(delay);
    }

    private void scheduleDigest(long delay) {
        nextDigestAt = System.currentTimeMillis() + delay;
        app.main.removeCallbacks(digestTick);
        app.main.postDelayed(digestTick, delay);
    }

    private void digestTick() {
        nextDigestAt = 0;
        if (pool == null) return;
        if (open > 0) sendDigest();
        long interval = LockActivity.isVisible() ? 10_000 : screenOn() ? 60_000 : 180_000;
        scheduleDigest(interval);
    }

    private void sendDigest() {
        lastDigestSent = System.currentTimeMillis();
        sendControl("{\"k\":\"h\",\"f\":\"" + deviceId + "\",\"h\":\"" + app.repo.fingerprint()
                + "\",\"n\":" + app.repo.count() + ",\"c\":" + app.repo.clearT() + "}");
    }

    private boolean inSyncWithPeer() {
        return peerHash != null && peerHash.equals(app.repo.fingerprint())
                && peerCount == app.repo.count() && peerClear == app.repo.clearT();
    }

    private void onPeerDigest(JSONObject o) throws JSONException {
        if (deviceId.equals(o.optString("f"))) return;
        peerSeen();
        peerHash = o.getString("h");
        peerCount = o.getInt("n");
        peerClear = o.optLong("c");
        if (peerClear > app.repo.clearT()) app.repo.applyPeerClear(peerClear);
        long now = System.currentTimeMillis();
        if (inSyncWithPeer()) {
            app.repo.dropOutbox();
            // Отвечаем нечасто: другой телефон только что проснулся и хочет знать, что мы здесь.
            if (now - lastDigestSent > 15_000) sendDigest();
            return;
        }
        if (now - lastDigestSent > 3000) sendDigest();
        scheduleInventory(3000);
    }

    private void scheduleInventory(long delay) {
        if (inventoryScheduled) return;
        inventoryScheduled = true;
        app.main.postDelayed(inventoryCheck, delay);
    }

    private void inventoryCheck() {
        inventoryScheduled = false;
        if (pool == null || peerHash == null || inSyncWithPeer()) return;
        long now = System.currentTimeMillis();
        // Пока кто-то рисует, расхождение нормально: штрихи ещё в пути.
        long wait = Math.max(4000 - (now - app.repo.lastChangeMs()), INVENTORY_EVERY_MS - (now - lastInventorySent));
        if (wait > 0) {
            scheduleInventory(wait);
            return;
        }
        lastInventorySent = now;
        Log.d(TAG, "sheets differ: mine " + app.repo.count() + ", peer " + peerCount);
        sendControl(app.repo.inventoryPayload(deviceId));
    }

    private void onPeerInventory(JSONObject o) throws JSONException {
        if (deviceId.equals(o.optString("f"))) return;
        peerSeen();
        Repo.Comparison r = app.repo.compareInventory(o);
        Log.d(TAG, "inventory: resend " + r.resent + ", missing here " + r.iAmMissing);
        if (r.iAmMissing) {
            // Друг увидит мою опись и пришлёт то, чего нет здесь.
            peerHash = o.optString("f") + "?";
            scheduleInventory(500);
        }
    }

    private void peerSeen() {
        boolean was = peerOnline();
        peerSeenAt = System.currentTimeMillis();
        if (!was) fireStatus();
    }

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        digestSoon(3000);
    }

    @Override
    public void onReset() {
        digestSoon(1000);
    }

    // ---------- RelayPool.Listener: вызывается из потоков сети ----------

    @Override
    public void onConnOpen() {
        app.main.post(() -> {
            app.repo.rewindPending();
            kickPump();
            digestSoon(1500);
        });
    }

    @Override
    public void onEvent(int kind, long createdAt, String content) {
        final Room r = room;
        if (r == null) return;
        String plain = r.decrypt(content);
        if (plain == null) return;
        try {
            final JSONObject o = new JSONObject(plain);
            final String k = o.optString("k");
            app.main.post(() -> {
                if (room != r) return;
                try {
                    switch (k) {
                        case "l":
                            peerSeen();
                            app.repo.applyLive(o);
                            break;
                        case "h":
                            onPeerDigest(o);
                            break;
                        case "v":
                            onPeerInventory(o);
                            break;
                        default:
                            peerSeen();
                            app.repo.applyRemote(o, createdAt);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "bad payload: " + e);
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "bad json: " + e);
        }
    }

    @Override
    public void onOk(String eventId, boolean ok, String message, String relay) {
        boolean accepted = ok || (message != null && message.startsWith("duplicate"));
        if (!accepted) Log.d(TAG, "rejected by " + relay + ": " + message);
        final Repo.Op op = sent.get(eventId);
        if (op == null || !accepted) return;
        app.main.post(() -> app.repo.acked(op, relay));
    }

    @Override
    public void onStatus(int open, int total) {
        this.open = open;
        this.total = total;
        app.main.post(this::fireStatus);
    }

    private void fireStatus() {
        for (Runnable r : statusListeners) r.run();
    }
}
