package app.holst;

import android.util.Log;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/** Связывает лист на телефоне с ретрансляторами: шифрует, подписывает, отправляет, принимает. */
final class Sync implements RelayPool.Listener {
    private static final String TAG = "Holst";

    private final App app;
    private final Map<String, Repo.Op> sent = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private volatile Room room;
    private volatile RelayPool pool;
    private boolean started;
    private int generation;
    private volatile int open;
    private volatile int total = RelayPool.URLS.length;
    private long lastRestart;

    Sync(App app) {
        this.app = app;
        app.repo.setOutboxListener(this::publish);
    }

    int openCount() {
        return open;
    }

    int totalCount() {
        return total;
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
                p.start(app.repo.sinceSec());
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
        if (p != null) app.net.execute(p::stop);
        fireStatus();
    }

    /** Экран включился: наверстать всё, что нарисовали, пока телефон спал. */
    void catchUp() {
        RelayPool p = pool;
        if (p != null) p.catchUp(app.repo.sinceSec());
    }

    void onNetworkChanged() {
        long now = System.currentTimeMillis();
        if (now - lastRestart < 5000) return;
        lastRestart = now;
        RelayPool p = pool;
        if (p != null) p.reconnectAll();
    }

    /** Штрих в процессе рисования: ретрансляторы только передают его, не храня. */
    void sendLive(String id, int color, int width, boolean erase, int seq, int[] pts) {
        final Room r = room;
        final RelayPool p = pool;
        if (r == null || p == null) return;
        app.net.execute(() -> {
            StringBuilder sb = new StringBuilder(64 + pts.length * 3);
            sb.append("{\"k\":\"l\",\"i\":\"").append(id).append("\",\"c\":").append(color)
                    .append(",\"w\":").append(width).append(",\"e\":").append(erase ? 1 : 0)
                    .append(",\"n\":").append(seq).append(",\"p\":");
            Stroke.appendDelta(sb, pts, pts.length);
            sb.append('}');
            Room.Event e = r.event(RelayPool.KIND_LIVE, null, r.encrypt(sb.toString()),
                    System.currentTimeMillis() / 1000);
            p.markSeen(e.id);
            p.publish(e.json);
        });
    }

    private void publish(Repo.Op op) {
        final Room r = room;
        final RelayPool p = pool;
        if (r == null || p == null) return;
        app.net.execute(() -> {
            Room.Event e = eventFor(r, op);
            p.markSeen(e.id);
            p.publish(e.json);
        });
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

    // ---------- RelayPool.Listener: вызывается из потоков сети ----------

    @Override
    public void onConnOpen(RelayPool.Conn c) {
        app.main.post(() -> {
            final List<Repo.Op> ops = app.repo.pendingOps();
            final Room r = room;
            final RelayPool p = pool;
            if (ops.isEmpty() || r == null || p == null) return;
            app.net.execute(() -> {
                for (Repo.Op op : ops) {
                    Room.Event e = eventFor(r, op);
                    p.markSeen(e.id);
                    c.send("[\"EVENT\"," + e.json + "]");
                }
            });
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
            app.main.post(() -> {
                if (room != r) return;
                try {
                    if (kind == RelayPool.KIND_LIVE) app.repo.applyLive(o);
                    else app.repo.applyRemote(o, createdAt);
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
        app.main.post(() -> app.repo.acked(op));
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
