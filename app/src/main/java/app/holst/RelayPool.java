package app.holst;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * несколько ретрансляторов, друг получает его из первого живого.
 */
final class RelayPool {
    private static final String TAG = "Holst";

    static final String[] URLS = {
        "wss://relay.primal.net",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://relay.snort.social",
        "wss://nostr.data.haus",
    };

    static final int KIND_STORED = 30078;
    static final int KIND_LIVE = 20078;

    interface Listener {
        void onConnOpen(Conn c);

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
                    return size() > 4096;
                }
            });
    private volatile long since;
    private volatile boolean stopped;

    RelayPool(Listener listener, String pubHex) {
        this.listener = listener;
        this.pubHex = pubHex;
        this.http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(12, TimeUnit.SECONDS)
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
    }

    void stop() {
        stopped = true;
        timer.shutdownNow();
        for (Conn c : conns) c.close();
        http.dispatcher().executorService().shutdown();
    }

    /** Мы могли проспать: просим пропущенное заново и оживляем закрытые соединения. */
    void catchUp(long sinceSec) {
        since = sinceSec;
        if (stopped) return;
        timer.execute(() -> {
            for (Conn c : conns) {
                if (c.open) c.subscribe();
                else c.connectNow();
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

    void publish(String eventJson) {
        String msg = "[\"EVENT\"," + eventJson + "]";
        for (Conn c : conns) c.send(msg);
    }

    private void notifyStatus() {
        int open = 0;
        for (Conn c : conns) {
            if (c.open) open++;
        }
        listener.onStatus(open, conns.size());
    }

    final class Conn extends WebSocketListener {
        final String url;
        private volatile WebSocket ws;
        volatile boolean open;
        private int attempt;
        private boolean connecting;

        Conn(String url) {
            this.url = url;
        }

        synchronized void connect() {
            if (stopped || connecting || open) return;
            connecting = true;
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
            if (open && w != null) w.send(msg);
        }

        void subscribe() {
            String stored = "[\"REQ\",\"s\",{\"authors\":[\"" + pubHex + "\"],\"kinds\":[" + KIND_STORED
                    + "],\"since\":" + since + ",\"limit\":1000}]";
            String live = "[\"REQ\",\"l\",{\"authors\":[\"" + pubHex + "\"],\"kinds\":[" + KIND_LIVE
                    + "],\"since\":" + (System.currentTimeMillis() / 1000 - 600) + "}]";
            send(stored);
            send(live);
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            synchronized (this) {
                if (webSocket != ws) return;
                open = true;
                connecting = false;
                attempt = 0;
            }
            Log.d(TAG, "relay open " + url);
            subscribe();
            notifyStatus();
            listener.onConnOpen(this);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (webSocket != ws) return;
            try {
                JSONArray a = new JSONArray(text);
                String type = a.getString(0);
                if ("EVENT".equals(type)) {
                    JSONObject e = a.getJSONObject(2);
                    if (!pubHex.equals(e.optString("pubkey"))) return;
                    if (seen.put(e.getString("id"), Boolean.TRUE) != null) return;
                    listener.onEvent(e.getInt("kind"), e.getLong("created_at"), e.getString("content"));
                } else if ("OK".equals(type)) {
                    listener.onOk(a.getString(1), a.getBoolean(2), a.optString(3), url);
                } else if ("NOTICE".equals(type) || "CLOSED".equals(type)) {
                    Log.d(TAG, url + ": " + text);
                }
            } catch (Exception e) {
                Log.d(TAG, "bad message from " + url + ": " + e);
            }
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
                attempt = Math.min(attempt + 1, 6);
                delay = Math.min(90, 2L << attempt);
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
