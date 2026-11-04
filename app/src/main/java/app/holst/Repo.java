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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Содержимое общего листа. Работает только в главном потоке.
 * Лист — это штрихи, список отменённых и момент последнего стирания. Такие данные можно объединять
 * в любом порядке, поэтому два телефона рано или поздно приходят к одному рисунку.
 */
final class Repo {
    private static final String TAG = "Holst";
    private static final int MAX_STROKES = 4000;
    private static final int MAX_DEAD = 5000;
    /**
     * Стирание накрывает штрихи чуть вперёд по времени. Если часы друга спешат или его штрих ещё в
     * пути, он сотрётся у обоих, а не останется только у него.
     */
    private static final long CLEAR_MARGIN_MS = 60_000;

    interface Listener {
        /** atEnd: штрих лёг поверх остальных, можно дорисовать, не перестраивая картинку. */
        void onStrokeAdded(Stroke s, boolean atEnd);

        void onReset();

        default void onLiveChunk(LiveChunk c) {}
    }

    /** То, что ещё нужно доставить на ретрансляторы. */
    static final class Op {
        final String d;
        final String payload;
        final long sec;
        volatile Room.Event event;
        volatile long eventSec;
        final HashSet<String> ackRelays = new HashSet<>();
        long nextSend;
        int attempts;

        Op(String d, String payload, long sec) {
            this.d = d;
            this.payload = payload;
            this.sec = sec;
        }
    }

    interface OutboxListener {
        void onOutboxChanged();
    }

    /** Что выяснилось при сверке с другим телефоном. */
    static final class Comparison {
        int resent;
        boolean iAmMissing;
    }

    private final File file;
    private final ExecutorService disk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final HashMap<String, Stroke> byId = new HashMap<>();
    private final LinkedHashSet<String> dead = new LinkedHashSet<>();
    private final ArrayList<Op> outbox = new ArrayList<>();
    private OutboxListener outboxListener;
    private long clearT;
    private long hlc;
    private long watermark;
    private int revision;
    private int clearEpoch;
    private long idXor;
    private long lastChangeMs;

    private final Runnable saver = this::saveAsync;

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

    boolean hasPendingOps() {
        return !outbox.isEmpty();
    }

    boolean contains(String id) {
        return byId.containsKey(id);
    }

    boolean isLast(Stroke s) {
        return !strokes.isEmpty() && strokes.get(strokes.size() - 1) == s;
    }

    int revision() {
        return revision;
    }

    int clearEpoch() {
        return clearEpoch;
    }

    long clearT() {
        return clearT;
    }

    int count() {
        return strokes.size();
    }

    /** Отпечаток набора штрихов: не зависит от порядка, считается за постоянное время. */
    String fingerprint() {
        return Long.toHexString(idXor);
    }

    long lastChangeMs() {
        return lastChangeMs;
    }

    /** С какого момента просить у ретранслятора пропущенное. */
    long sinceSec() {
        return watermark == 0 ? 0 : Math.max(0, watermark - 600);
    }

    private static long idBits(String id) {
        try {
            return Long.parseUnsignedLong(id, 16);
        } catch (NumberFormatException e) {
            return id.hashCode();
        }
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

    Stroke addMine(String id, int color, int width, boolean erase, int[] pts, int duration) {
        Stroke s = new Stroke(id, nextT(), nowSec(), color, width, erase, pts, true, duration);
        insert(s);
        enqueue(new Op(id, s.payload(), s.sec));
        return s;
    }

    /** Отменяет последний свой штрих. */
    boolean undoMine() {
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke s = strokes.get(i);
            if (!s.mine) continue;
            remove(i, s);
            markDead(s.id);
            Iterator<Op> it = outbox.iterator();
            while (it.hasNext()) {
                if (it.next().d.equals(s.id)) it.remove();
            }
            // Отмена — отдельное событие: иначе ретранслятор может отбросить её как «устаревшую».
            enqueue(new Op("x" + s.id, "{\"k\":\"d\",\"i\":\"" + s.id + "\"}", nowSec()));
            changed();
            for (Listener l : listeners) l.onReset();
            return true;
        }
        return false;
    }

    void clearAll() {
        long t = Math.max(System.currentTimeMillis() + CLEAR_MARGIN_MS, hlc + 1);
        hlc = t;
        applyClear(t);
        // У каждого стирания своё имя. С одним именем ретранслятор хранит только самое новое по
        // часам телефона и молча отбрасывает остальные: у одного стёрлось, у другого нет.
        enqueue(new Op("c" + t, "{\"k\":\"c\",\"t\":" + t + "}", nowSec()));
    }

    private void enqueue(Op op) {
        outbox.add(op);
        while (outbox.size() > 2000) outbox.remove(0);
        scheduleSave();
        if (outboxListener != null) outboxListener.onOutboxChanged();
    }

    /** Подтвердил ретранслятор. Двух достаточно, чтобы событие пережило отказ одного из них. */
    void acked(Op op, String relay) {
        op.ackRelays.add(relay);
        if (op.ackRelays.size() >= 2 && outbox.remove(op)) scheduleSave();
    }

    /** Ретранслятор снова на связи: неподтверждённое стоит отправить ещё раз. */
    void rewindPending() {
        for (Op op : outbox) op.nextSend = 0;
    }

    /** Другой телефон подтвердил, что листы совпали: повторно отправлять больше нечего. */
    void dropOutbox() {
        if (outbox.isEmpty()) return;
        outbox.clear();
        scheduleSave();
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
                if (s.t <= clearT || dead.contains(s.id)) return;
                if (byId.containsKey(s.id)) {
                    // Кто-то уже дослал этот штрих: повторять незачем.
                    cancelUnsentResend(s.id);
                    return;
                }
                insert(s);
                break;
            case "d":
                applyDead(o.getString("i"));
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

    void applyPeerClear(long t) {
        observeT(t);
        applyClear(t);
    }

    private void applyDead(String id) {
        markDead(id);
        Stroke gone = byId.get(id);
        if (gone != null) {
            remove(strokes.indexOf(gone), gone);
            changed();
            for (Listener l : listeners) l.onReset();
        } else {
            scheduleSave();
        }
    }

    private void markDead(String id) {
        dead.add(id);
        if (dead.size() > MAX_DEAD) {
            Iterator<String> it = dead.iterator();
            it.next();
            it.remove();
        }
    }

    void applyLive(JSONObject o) throws JSONException {
        String id = o.getString("i");
        if (byId.containsKey(id) || dead.contains(id)) return;
        LiveChunk c = new LiveChunk(id, o.getInt("c"), o.getInt("w"), o.optInt("e") != 0, o.getInt("n"),
                Stroke.readDelta(o.getJSONArray("p")), o.optInt("a", -1), o.optInt("b", -1));
        for (Listener l : listeners) l.onLiveChunk(c);
    }

    private void applyClear(long t) {
        if (t <= clearT) return;
        clearT = t;
        clearEpoch++;
        Iterator<Stroke> it = strokes.iterator();
        while (it.hasNext()) {
            Stroke s = it.next();
            if (s.t <= t) {
                it.remove();
                byId.remove(s.id);
                idXor ^= idBits(s.id);
            }
        }
        changed();
        for (Listener l : listeners) l.onReset();
    }

    private void remove(int index, Stroke s) {
        strokes.remove(index);
        byId.remove(s.id);
        idXor ^= idBits(s.id);
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
        idXor ^= idBits(s.id);
        boolean trimmed = false;
        while (strokes.size() > MAX_STROKES) {
            Stroke old = strokes.get(0);
            remove(0, old);
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
        lastChangeMs = System.currentTimeMillis();
        scheduleSave();
    }

    /** Переход на другой код: прежний лист больше не нужен. */
    void reset() {
        strokes.clear();
        byId.clear();
        dead.clear();
        outbox.clear();
        idXor = 0;
        clearT = 0;
        watermark = 0;
        clearEpoch++;
        changed();
        for (Listener l : listeners) l.onReset();
    }

    // ---------- Сверка с другим телефоном ----------

    /** Опись недавних штрихов и отмен для другого телефона. */
    String inventoryPayload(String deviceId) {
        int maxIds = 800;
        int from = Math.max(0, strokes.size() - maxIds);
        long windowT = from > 0 ? strokes.get(from).t : 0;
        StringBuilder sb = new StringBuilder(16 + (strokes.size() - from) * 13);
        sb.append("{\"k\":\"v\",\"f\":\"").append(deviceId).append("\",\"c\":").append(clearT)
                .append(",\"w\":").append(windowT).append(",\"s\":[");
        for (int i = from; i < strokes.size(); i++) {
            String id = strokes.get(i).id;
            if (i > from) sb.append(',');
            sb.append('"').append(id, 0, Math.min(10, id.length())).append('"');
        }
        sb.append("],\"x\":[");
        int skip = Math.max(0, dead.size() - 200);
        int i = 0;
        boolean first = true;
        for (String id : dead) {
            if (i++ < skip) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(id).append('"');
        }
        return sb.append("]}").toString();
    }

    /**
     * Сравнивает свой лист с описью друга: применяет его стирания и отмены, а штрихи, которых у него
     * нет, снова ставит на отправку.
     */
    Comparison compareInventory(JSONObject o, long resendDelayMs) throws JSONException {
        Comparison r = new Comparison();
        long c = o.optLong("c");
        if (c > clearT) applyPeerClear(c);
        JSONArray x = o.optJSONArray("x");
        if (x != null) {
            for (int i = 0; i < x.length(); i++) {
                String id = x.getString(i);
                if (!dead.contains(id)) applyDead(id);
            }
        }
        JSONArray s = o.getJSONArray("s");
        HashSet<String> theirs = new HashSet<>(s.length() * 2);
        for (int i = 0; i < s.length(); i++) theirs.add(s.getString(i));
        HashSet<String> mine = new HashSet<>(strokes.size() * 2);
        long windowT = o.optLong("w");
        long now = System.currentTimeMillis();
        for (Stroke st : strokes) {
            String prefix = st.id.substring(0, Math.min(10, st.id.length()));
            mine.add(prefix);
            // Совсем свежий штрих может быть ещё в пути.
            if (st.t >= windowT && !theirs.contains(prefix) && now - st.seenMs > 6000 && r.resent < 150) {
                resend(st, now + resendDelayMs);
                r.resent++;
            }
        }
        for (String id : theirs) {
            if (!mine.contains(id)) {
                r.iAmMissing = true;
                break;
            }
        }
        return r;
    }

    /** Досылает штрих не сразу: у нескольких участников он есть, и первый избавит остальных от работы. */
    private void resend(Stroke st, long notBefore) {
        for (Op op : outbox) {
            if (op.d.equals(st.id)) return;
        }
        Op op = new Op(st.id, st.payload(), nowSec());
        op.nextSend = notBefore;
        enqueue(op);
    }

    private void cancelUnsentResend(String id) {
        Iterator<Op> it = outbox.iterator();
        while (it.hasNext()) {
            Op op = it.next();
            if (op.attempts == 0 && op.d.equals(id)) {
                it.remove();
                return;
            }
        }
    }

    // ---------- Диск ----------

    private void scheduleSave() {
        main.removeCallbacks(saver);
        main.postDelayed(saver, 3000);
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
                idXor ^= idBits(s.id);
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
