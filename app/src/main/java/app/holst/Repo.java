package app.holst;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Содержимое общего листа. Работает только в главном потоке.
 * Каждый телефон хранит лист сам; два телефона сходятся к одному результату независимо от того,
 * в каком порядке дошли события.
 */
final class Repo {
    private static final String TAG = "Holst";
    private static final int MAX_STROKES = 4000;
    private static final long LIVE_TTL_MS = 8000;

    interface Listener {
        /** atEnd: штрих лёг поверх остальных, можно дорисовать, не перестраивая картинку. */
        void onStrokeAdded(Stroke s, boolean atEnd);

        void onReset();

        void onLiveChanged();
    }

    /** То, что ещё нужно доставить на ретрансляторы. */
    static final class Op {
        final String d;
        final String payload;
        final long sec;
        volatile Room.Event event;
        volatile long eventSec;
        int acks;

        Op(String d, String payload, long sec) {
            this.d = d;
            this.payload = payload;
            this.sec = sec;
        }
    }

    /** Штрих друга, который рисуется прямо сейчас. */
    static final class Live {
        final String id;
        final int color;
        final int width;
        final boolean erase;
        int[] pts = new int[256];
        int count;
        int seq = -1;
        long seen;

        Live(String id, int color, int width, boolean erase) {
            this.id = id;
            this.color = color;
            this.width = width;
            this.erase = erase;
        }
    }

    interface OutboxListener {
        void onOp(Op op);
    }

    private final File file;
    private final ExecutorService disk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final HashMap<String, Stroke> byId = new HashMap<>();
    private final HashSet<String> dead = new HashSet<>();
    private final ArrayList<Op> outbox = new ArrayList<>();
    private final LinkedHashMap<String, Live> live = new LinkedHashMap<>();
    private OutboxListener outboxListener;
    private long clearT;
    private long hlc;
    private long watermark;
    private int revision;

    private final Runnable saver = this::saveAsync;
    private final Runnable liveSweep = this::sweepLive;

    Repo(Context c, ExecutorService disk) {
        this.file = new File(c.getFilesDir(), "sheet.json");
        this.disk = disk;
        load();
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void setOutboxListener(OutboxListener l) {
        outboxListener = l;
    }

    List<Stroke> strokes() {
        return new ArrayList<>(strokes);
    }

    List<Op> pendingOps() {
        return new ArrayList<>(outbox);
    }

    List<Live> liveStrokes() {
        return new ArrayList<>(live.values());
    }

    int revision() {
        return revision;
    }

    /** С какого момента просить у ретранслятора пропущенное. */
    long sinceSec() {
        return watermark == 0 ? 0 : Math.max(0, watermark - 600);
    }

    private long nextT() {
        hlc = Math.max(System.currentTimeMillis(), hlc + 1);
        return hlc;
    }

    private void observeT(long t) {
        if (t > hlc) hlc = t;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    // ---------- Свои действия ----------

    Stroke addMine(String id, int color, int width, boolean erase, int[] pts) {
        Stroke s = new Stroke(id, nextT(), nowSec(), color, width, erase, pts, true);
        insert(s);
        enqueue(new Op(id, s.payload(), s.sec));
        return s;
    }

    /** Отменяет последний свой штрих. */
    boolean undoMine() {
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke s = strokes.get(i);
            if (!s.mine) continue;
            strokes.remove(i);
            byId.remove(s.id);
            dead.add(s.id);
            Iterator<Op> it = outbox.iterator();
            while (it.hasNext()) {
                if (it.next().d.equals(s.id)) it.remove();
            }
            long sec = Math.max(nowSec(), s.sec + 1);
            enqueue(new Op(s.id, "{\"k\":\"d\",\"i\":\"" + s.id + "\"}", sec));
            changed();
            for (Listener l : listeners) l.onReset();
            return true;
        }
        return false;
    }

    void clearAll() {
        long t = nextT();
        applyClear(t);
        enqueue(new Op("clear", "{\"k\":\"c\",\"t\":" + t + "}", nowSec()));
    }

    private void enqueue(Op op) {
        outbox.add(op);
        while (outbox.size() > 2000) outbox.remove(0);
        scheduleSave();
        if (outboxListener != null) outboxListener.onOp(op);
    }

    void acked(Op op) {
        op.acks++;
        if (op.acks >= 2 && outbox.remove(op)) scheduleSave();
    }

    // ---------- Что пришло от друга ----------

    void applyRemote(JSONObject o, long createdAt) throws JSONException {
        if (createdAt > watermark) {
            watermark = createdAt;
            scheduleSave();
        }
        String k = o.getString("k");
        switch (k) {
            case "s":
                Stroke s = Stroke.parse(o, createdAt, false);
                observeT(s.t);
                if (s.t <= clearT || dead.contains(s.id) || byId.containsKey(s.id)) return;
                insert(s);
                if (live.remove(s.id) != null) notifyLive();
                break;
            case "d":
                String id = o.getString("i");
                dead.add(id);
                Stroke gone = byId.remove(id);
                if (gone != null) {
                    strokes.remove(gone);
                    changed();
                    for (Listener l : listeners) l.onReset();
                } else {
                    scheduleSave();
                }
                break;
            case "c":
                long t = o.getLong("t");
                observeT(t);
                applyClear(t);
                break;
            default:
                Log.d(TAG, "unknown payload kind " + k);
        }
    }

    void applyLive(JSONObject o) throws JSONException {
        String id = o.getString("i");
        if (byId.containsKey(id) || dead.contains(id)) return;
        Live l = live.get(id);
        if (l == null) {
            l = new Live(id, o.getInt("c"), o.getInt("w"), o.optInt("e") != 0);
            live.put(id, l);
        }
        int seq = o.getInt("n");
        l.seen = System.currentTimeMillis();
        if (seq <= l.seq) return;
        l.seq = seq;
        int[] chunk = Stroke.readDelta(o.getJSONArray("p"));
        if (l.count + chunk.length > l.pts.length) {
            int[] bigger = new int[Math.max(l.pts.length * 2, l.count + chunk.length)];
            System.arraycopy(l.pts, 0, bigger, 0, l.count);
            l.pts = bigger;
        }
        System.arraycopy(chunk, 0, l.pts, l.count, chunk.length);
        l.count += chunk.length;
        notifyLive();
        main.removeCallbacks(liveSweep);
        main.postDelayed(liveSweep, LIVE_TTL_MS);
    }

    private void sweepLive() {
        long now = System.currentTimeMillis();
        boolean removed = false;
        Iterator<Map.Entry<String, Live>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().seen >= LIVE_TTL_MS - 50) {
                it.remove();
                removed = true;
            }
        }
        if (removed) notifyLive();
        if (!live.isEmpty()) main.postDelayed(liveSweep, 1000);
    }

    private void notifyLive() {
        for (Listener l : listeners) l.onLiveChanged();
    }

    private void applyClear(long t) {
        if (t <= clearT) return;
        clearT = t;
        boolean removed = false;
        Iterator<Stroke> it = strokes.iterator();
        while (it.hasNext()) {
            Stroke s = it.next();
            if (s.t <= t) {
                it.remove();
                byId.remove(s.id);
                removed = true;
            }
        }
        changed();
        if (removed) {
            for (Listener l : listeners) l.onReset();
        }
    }

    private void insert(Stroke s) {
        int lo = 0;
        int hi = strokes.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            Stroke m = strokes.get(mid);
            int cmp = m.t != s.t ? Long.compare(m.t, s.t) : m.id.compareTo(s.id);
            if (cmp < 0) lo = mid + 1;
            else hi = mid;
        }
        boolean atEnd = lo == strokes.size();
        strokes.add(lo, s);
        byId.put(s.id, s);
        boolean trimmed = false;
        while (strokes.size() > MAX_STROKES) {
            byId.remove(strokes.remove(0).id);
            trimmed = true;
        }
        changed();
        for (Listener l : listeners) {
            if (trimmed) l.onReset();
            else l.onStrokeAdded(s, atEnd);
        }
    }

    private void changed() {
        revision++;
        scheduleSave();
    }

    /** Переход на другой код: прежний лист больше не нужен. */
    void reset() {
        strokes.clear();
        byId.clear();
        dead.clear();
        outbox.clear();
        live.clear();
        clearT = 0;
        watermark = 0;
        changed();
        for (Listener l : listeners) l.onReset();
    }

    // ---------- Диск ----------

    private void scheduleSave() {
        main.removeCallbacks(saver);
        main.postDelayed(saver, 1200);
    }

    void saveAsync() {
        main.removeCallbacks(saver);
        final List<Stroke> copy = strokes();
        final List<Op> ops = pendingOps();
        final List<String> deadCopy = new ArrayList<>(dead);
        final long c = clearT;
        final long h = hlc;
        final long w = watermark;
        disk.execute(() -> write(copy, ops, deadCopy, c, h, w));
    }

    private void write(List<Stroke> copy, List<Op> ops, List<String> deadCopy, long c, long h, long w) {
        try {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("{\"clear\":").append(c).append(",\"hlc\":").append(h).append(",\"wm\":").append(w);
            sb.append(",\"strokes\":[");
            for (int i = 0; i < copy.size(); i++) {
                Stroke s = copy.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"m\":").append(s.mine ? 1 : 0).append(",\"z\":").append(s.sec)
                        .append(",\"o\":").append(s.payload()).append('}');
            }
            sb.append("],\"dead\":[");
            for (int i = 0; i < deadCopy.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(deadCopy.get(i)).append('"');
            }
            sb.append("],\"ops\":[");
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"d\":\"").append(op.d).append("\",\"z\":").append(op.sec)
                        .append(",\"o\":").append(op.payload).append('}');
            }
            sb.append("]}");
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "rename failed");
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    private void load() {
        if (!file.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            clearT = o.optLong("clear");
            hlc = o.optLong("hlc");
            watermark = o.optLong("wm");
            JSONArray a = o.getJSONArray("strokes");
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.getJSONObject(i);
                Stroke s = Stroke.parse(item.getJSONObject("o"), item.optLong("z"), item.optInt("m") != 0);
                strokes.add(s);
                byId.put(s.id, s);
            }
            JSONArray d = o.optJSONArray("dead");
            if (d != null) {
                for (int i = 0; i < d.length(); i++) dead.add(d.getString(i));
            }
            JSONArray ops = o.optJSONArray("ops");
            if (ops != null) {
                for (int i = 0; i < ops.length(); i++) {
                    JSONObject item = ops.getJSONObject(i);
                    outbox.add(new Op(item.getString("d"), item.getJSONObject("o").toString(), item.optLong("z")));
                }
            }
            revision++;
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }
}
