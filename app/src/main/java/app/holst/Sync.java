package app.holst;

import android.os.PowerManager;
import android.util.Log;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    private static final int PUMP_BUDGET = 4;
    private static final long INVENTORY_EVERY_MS = 10_000;
    private static final long PEER_ONLINE_MS = 200_000;
    private static final long PEER_RECENT_MS = 240_000;

    /** Что последний раз сообщил другой телефон. */
    private static final class Peer {
        String hash;
        int count;
        long clear;
        long seenAt;
    }

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
    private long lastInventoryAt;
    private boolean inventoryScheduled;
    private boolean forceInventory;
    private final Map<String, Peer> peers = new HashMap<>();
    private final Random random = new Random();
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
            final Room r = Room.derive(code, app.prefs.deviceSecret());
            app.main.post(() -> {
                if (gen != generation || !started) return;
                room = r;
                RelayPool p = new RelayPool(this, r);
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
        peers.clear();
        forceInventory = false;
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

    /** Как часто отправлять куски живого штриха: при большой компании реже, чтобы не упереться в лимиты. */
    long liveIntervalMs() {
        return peerCount() > 3 ? 450 : 300;
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
            p.publishLive(e.json);
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
        // Чем больше участников, тем реже говорит каждый: общий поток сообщений остаётся небольшим.
        double scaled = interval * (1 + peerCount() / 4.0) * (0.8 + random.nextDouble() * 0.4);
        scheduleDigest((long) scaled);
    }

    private void sendDigest() {
        lastDigestSent = System.currentTimeMillis();
        sendControl("{\"k\":\"h\",\"f\":\"" + deviceId + "\",\"h\":\"" + app.repo.fingerprint()
                + "\",\"n\":" + app.repo.count() + ",\"c\":" + app.repo.clearT() + "}");
    }

    private int peerCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Peer p : peers.values()) {
            if (now - p.seenAt < PEER_RECENT_MS) n++;
        }
        return n;
    }

    /** Случайная задержка: при десяти участниках телефоны не должны отвечать хором. */
    private long jitter(long maxPerPeer) {
        return (long) (random.nextDouble() * maxPerPeer * Math.max(1, peerCount()));
    }

    private boolean matches(Peer p) {
        return p.hash.equals(app.repo.fingerprint()) && p.count == app.repo.count() && p.clear == app.repo.clearT();
    }

    /** Совпал ли лист со всеми, кого недавно было слышно. */
    private boolean inSyncWithAll() {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Peer p : peers.values()) {
            if (now - p.seenAt >= PEER_RECENT_MS) continue;
            any = true;
            if (!matches(p)) return false;
        }
        return any;
    }

    private void onPeerDigest(JSONObject o) throws JSONException {
        String from = o.optString("f");
        if (deviceId.equals(from)) return;
        peerSeen();
        Peer p = peers.get(from);
        if (p == null) {
            p = new Peer();
            peers.put(from, p);
        }
        p.hash = o.getString("h");
        p.count = o.getInt("n");
        p.clear = o.optLong("c");
        p.seenAt = System.currentTimeMillis();
        if (p.clear > app.repo.clearT()) app.repo.applyPeerClear(p.clear);
        long now = p.seenAt;
        if (inSyncWithAll()) {
            app.repo.dropOutbox();
            forceInventory = false;
        }
        if (matches(p)) {
            // Отвечаем нечасто: другой телефон только что проснулся и хочет знать, что мы здесь.
            if (now - lastDigestSent > 15_000) digestSoon(jitter(600));
            return;
        }
        if (now - lastDigestSent > 3000) digestSoon(jitter(600));
        scheduleInventory(3000 + jitter(1000));
    }

    private void scheduleInventory(long delay) {
        if (inventoryScheduled) return;
        inventoryScheduled = true;
        app.main.postDelayed(inventoryCheck, delay);
    }

    /**
     * Описи листов тяжелее всего остального, а одной на всех обычно достаточно: все видят её и
     * досылают недостающее. Поэтому после чужой описи своя откладывается.
     */
    private void inventoryCheck() {
        inventoryScheduled = false;
        if (pool == null || peers.isEmpty() || (inSyncWithAll() && !forceInventory)) return;
        long now = System.currentTimeMillis();
        long gap = INVENTORY_EVERY_MS + peerCount() * 3000L;
        // Пока кто-то рисует, расхождение нормально: штрихи ещё в пути.
        long wait = Math.max(4000 - (now - app.repo.lastChangeMs()), gap - (now - lastInventoryAt));
        if (wait > 0) {
            scheduleInventory(wait + jitter(300));
            return;
        }
        lastInventoryAt = now;
        forceInventory = false;
        Log.d(TAG, "sheets differ: mine " + app.repo.count());
        sendControl(app.repo.inventoryPayload(deviceId));
    }

    private void onPeerInventory(JSONObject o) throws JSONException {
        if (deviceId.equals(o.optString("f"))) return;
        peerSeen();
        lastInventoryAt = System.currentTimeMillis();
        Repo.Comparison r = app.repo.compareInventory(o, jitter(1200));
        Log.d(TAG, "inventory: resend " + r.resent + ", missing here " + r.iAmMissing);
        if (r.iAmMissing) {
            // Увидят мою опись и пришлют то, чего нет здесь.
            forceInventory = true;
            scheduleInventory(500 + jitter(800));
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
