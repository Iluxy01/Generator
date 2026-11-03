package app.holst;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Связь через бесплатные публичные ретрансляторы Nostr. Своего сервера нет: штрих уходит сразу в
 * несколько ретрансляторов, друг получает его из первого живого. Мобильная связь рвётся незаметно,
 * поэтому каждое соединение проверяется, а не считается живым только потому, что его не закрыли.
 */
final class RelayPool {
    private static final String TAG = "Holst";

    static final String[] URLS = {
        "wss://relay.primal.net",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.snort.social",
        "wss://nostr.data.haus",
        "wss://relay.sigit.io",
        "wss://nostr-01.yakihonne.com",
    };

    static final int KIND_STORED = 30078;
    static final int KIND_LIVE = 20078;

    /** Живые куски идут только через самые быстрые ретрансляторы: иначе нас ограничат за частоту. */
    private static final int LIVE_FANOUT = 3;
    private static final long CONGESTED_BYTES = 256 * 1024;

    interface Listener {
        void onConnOpen();

        void onEvent(int kind, long createdAt, String content);

        void onOk(String eventId, boolean ok, String message, String relay);

        void onStatus(int open, int total);
    }

    private final Listener listener;
    private final String pubHex;
    private final OkHttpClient http;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final List<Conn> conns = new ArrayList<>();
    private final Map<String, Boolean> seen = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(512, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 8192;
                }
            });
    private volatile long since;
    private volatile boolean stopped;
    private volatile boolean fast;

    RelayPool(Listener listener, String pubHex) {
        this.listener = listener;
        this.pubHex = pubHex;
        this.http = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    void start(long sinceSec) {
        since = sinceSec;
        for (String url : URLS) {
            Conn c = new Conn(url);
            conns.add(c);
            c.connect();
        }
        timer.schedule(this::healthTick, 2, TimeUnit.SECONDS);
    }

    void stop() {
        stopped = true;
        timer.shutdownNow();
        for (Conn c : conns) c.close();
        http.dispatcher().executorService().shutdown();
    }

    /** Холст на экране: проверяем соединения часто. В остальное время бережём батарею. */
    void setFast(boolean v) {
        fast = v;
    }

    private void healthTick() {
        if (stopped) return;
        long now = System.currentTimeMillis();
        try {
            for (Conn c : conns) c.checkHealth(now);
        } catch (RuntimeException e) {
            Log.w(TAG, "health check failed", e);
        }
        if (!stopped) timer.schedule(this::healthTick, fast ? 2 : 15, TimeUnit.SECONDS);
    }

    /** Мы могли проспать: просим пропущенное, оживляем закрытые и проверяем открытые соединения. */
    void catchUp(long sinceSec) {
        since = sinceSec;
        if (stopped) return;
        timer.execute(() -> {
            long now = System.currentTimeMillis();
            for (Conn c : conns) {
                if (c.open) {
                    c.subscribe();
                    c.probeNow(now);
                } else {
                    c.connectNow();
                }
            }
        });
    }

    void reconnectAll() {
        if (stopped) return;
        timer.execute(() -> {
            for (Conn c : conns) c.restart();
        });
    }

    void markSeen(String eventId) {
        seen.put(eventId, Boolean.TRUE);
    }

    /** Готовые штрихи и стирания: всем доступным ретрансляторам. */
    void publish(String eventJson) {
        String msg = "[\"EVENT\"," + eventJson + "]";
        long now = System.currentTimeMillis();
        for (Conn c : conns) {
            if (!c.cooling(now)) c.send(msg);
        }
    }

    /** Живой кусок: только через самые быстрые, не ограничившие нас ретрансляторы. */
    void publishLive(String eventJson) {
        String msg = "[\"EVENT\"," + eventJson + "]";
        long now = System.currentTimeMillis();
        List<Conn> best = new ArrayList<>(conns.size());
        for (Conn c : conns) {
            if (c.open && !c.cooling(now) && !c.congested()) best.add(c);
        }
        best.sort(Comparator.comparingLong((Conn c) -> c.rttMs));
        for (int i = 0; i < Math.min(LIVE_FANOUT, best.size()); i++) best.get(i).send(msg);
    }

    private void notifyStatus() {
        int open = 0;
        for (Conn c : conns) {
            if (c.open) open++;
        }
        listener.onStatus(open, conns.size());
    }

    private static boolean isRateLimit(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("rate") || m.contains("too many") || m.contains("slow down") || m.contains("banned")
                || m.contains("noting too much") || m.contains("spam") || m.contains("blocked");
    }

    final class Conn extends WebSocketListener {
        final String url;
        private volatile WebSocket ws;
        volatile boolean open;
        private int attempt;
        private boolean connecting;
        private volatile long lastRx;
        private volatile long probeSentAt;
        private volatile long connectStartedAt;
        volatile long rttMs = 5000;
        private volatile long coolUntil;
        private volatile long coolStepMs = 15_000;

        Conn(String url) {
            this.url = url;
        }

        boolean cooling(long now) {
            return now < coolUntil;
        }

        boolean congested() {
            WebSocket w = ws;
            return w != null && w.queueSize() > 32 * 1024;
        }

        synchronized void connect() {
            if (stopped || connecting || open) return;
            connecting = true;
            connectStartedAt = System.currentTimeMillis();
            ws = http.newWebSocket(new Request.Builder().url(url).header("User-Agent", "Holst").build(), this);
        }

        synchronized void connectNow() {
            attempt = 0;
            connect();
        }

        synchronized void restart() {
            WebSocket old = ws;
            ws = null;
            open = false;
            connecting = false;
            probeSentAt = 0;
            if (old != null) old.cancel();
            attempt = 0;
            connect();
            notifyStatus();
        }

        void close() {
            WebSocket w = ws;
            ws = null;
            open = false;
            if (w != null) w.cancel();
        }

        void send(String msg) {
            WebSocket w = ws;
            if (open && w != null && w.send(msg) && probeSentAt == 0) {
                // На всё, кроме закрытия подписки, ретранслятор обязан ответить: молчание — плохой знак.
                probeSentAt = System.currentTimeMillis();
            }
        }

        void subscribe() {
            String stored = "[\"REQ\",\"s\",{\"authors\":[\"" + pubHex + "\"],\"kinds\":[" + KIND_STORED
                    + "],\"since\":" + since + ",\"limit\":1000}]";
            String live = "[\"REQ\",\"l\",{\"authors\":[\"" + pubHex + "\"],\"kinds\":[" + KIND_LIVE
                    + "],\"since\":" + (System.currentTimeMillis() / 1000 - 600) + "}]";
            send(stored);
            send(live);
        }

        /** Пустой запрос: живой ретранслятор ответит сразу, мёртвое соединение промолчит. */
        void probeNow(long now) {
            probeSentAt = now;
            long future = now / 1000 + 86400;
            send("[\"REQ\",\"hb\",{\"authors\":[\"" + pubHex + "\"],\"kinds\":[" + KIND_STORED
                    + "],\"since\":" + future + ",\"limit\":1}]");
        }

        void checkHealth(long now) {
            if (!open) return;
            WebSocket w = ws;
            if (w != null && w.queueSize() > CONGESTED_BYTES) {
                Log.d(TAG, "relay congested " + url);
                restart();
                return;
            }
            long sent = probeSentAt;
            if (sent != 0) {
                if (now - sent > (fast ? 6000 : 20000)) {
                    Log.d(TAG, "relay silent, reconnecting " + url);
                    restart();
                }
                return;
            }
            if (now - lastRx > (fast ? 4000 : 60000)) probeNow(now);
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (webSocket != ws) return;
                open = true;
                connecting = false;
                attempt = 0;
                lastRx = now;
                probeSentAt = 0;
                rttMs = Math.max(1, now - connectStartedAt);
            }
            Log.d(TAG, "relay open " + url);
            subscribe();
            notifyStatus();
            listener.onConnOpen();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (webSocket != ws) return;
            long now = System.currentTimeMillis();
            lastRx = now;
            // Любой ответ означает, что соединение живо.
            long sent = probeSentAt;
            probeSentAt = 0;
            try {
                JSONArray a = new JSONArray(text);
                String type = a.getString(0);
                if ("EVENT".equals(type)) {
                    JSONObject e = a.getJSONObject(2);
                    if (!pubHex.equals(e.optString("pubkey"))) return;
                    if (seen.put(e.getString("id"), Boolean.TRUE) != null) return;
                    listener.onEvent(e.getInt("kind"), e.getLong("created_at"), e.getString("content"));
                } else if ("OK".equals(type)) {
                    boolean ok = a.getBoolean(2);
                    String message = a.optString(3);
                    if (ok) coolStepMs = 15_000;
                    else if (isRateLimit(message)) coolDown(now, message);
                    listener.onOk(a.getString(1), ok, message, url);
                } else if ("EOSE".equals(type)) {
                    if ("hb".equals(a.optString(1))) {
                        if (sent != 0) rttMs = (rttMs * 2 + (now - sent)) / 3;
                        WebSocket w = ws;
                        if (w != null) w.send("[\"CLOSE\",\"hb\"]");
                    }
                } else if ("NOTICE".equals(type) || "CLOSED".equals(type)) {
                    Log.d(TAG, url + ": " + text);
                    if (isRateLimit(text)) coolDown(now, text);
                }
            } catch (Exception e) {
                Log.d(TAG, "bad message from " + url + ": " + e);
            }
        }

        private void coolDown(long now, String why) {
            if (now < coolUntil) return;
            coolUntil = now + coolStepMs;
            Log.d(TAG, "cooling " + url + " for " + coolStepMs + "ms: " + why);
            coolStepMs = Math.min(coolStepMs * 2, 300_000);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            down(webSocket, reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            down(webSocket, String.valueOf(t));
        }

        private void down(WebSocket webSocket, String why) {
            long delay;
            synchronized (this) {
                if (webSocket != ws) return;
                open = false;
                connecting = false;
                ws = null;
                probeSentAt = 0;
                attempt = Math.min(attempt + 1, 6);
                delay = Math.min(fast ? 12 : 90, 1L << attempt);
            }
            Log.d(TAG, "relay down " + url + ": " + why);
            notifyStatus();
            if (stopped) return;
            try {
                timer.schedule(this::connect, delay, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                // Пул уже остановлен.
            }
        }
    }
}
