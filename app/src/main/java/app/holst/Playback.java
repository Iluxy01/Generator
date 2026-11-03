package app.holst;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * Проигрывает штрих друга с той скоростью, с какой он рисовался. Куски приходят рывками и не по
 * порядку, поэтому между сетью и экраном стоит небольшой запас времени: на экране штрих растёт
 * ровно, а если сеть отстала — плавно догоняет.
 */
final class Playback {
    private static final float LEAD_MS = 180f;
    private static final long GAP_WAIT_MS = 450;

    final String id;
    final int color;
    final int width;
    final boolean erase;

    private int[] pts = new int[256];
    private int[] ms = new int[128];
    private int pairs;
    private final TreeMap<Integer, LiveChunk> waiting = new TreeMap<>();
    private int nextSeq;
    private long gapSince;
    private boolean hasFinal;
    private boolean hadLive;
    private float playMs = -LEAD_MS;
    private int visible;
    private float tailX;
    private float tailY;
    private boolean finished;
    private long lastDataAt;

    Playback(String id, int color, int width, boolean erase) {
        this.id = id;
        this.color = color;
        this.width = width;
        this.erase = erase;
    }

    int[] points() {
        return pts;
    }

    int visibleIndex() {
        return visible;
    }

    float tailX() {
        return tailX;
    }

    float tailY() {
        return tailY;
    }

    boolean isFinished() {
        return finished;
    }

    boolean hasFinal() {
        return hasFinal;
    }

    boolean hasPoints() {
        return pairs > 0;
    }

    long lastDataAt() {
        return lastDataAt;
    }

    void addChunk(LiveChunk c, long now) {
        if (hasFinal) return;
        lastDataAt = now;
        hadLive = true;
        if (c.seq < nextSeq) return;
        waiting.put(c.seq, c);
        drainWaiting(now);
    }

    private void drainWaiting(long now) {
        while (!waiting.isEmpty()) {
            int first = waiting.firstKey();
            if (first != nextSeq) {
                if (gapSince == 0) gapSince = now;
                if (now - gapSince < GAP_WAIT_MS) return;
                // Пропавший кусок не ждём: его заполнит готовый штрих.
                nextSeq = first;
            }
            gapSince = 0;
            append(waiting.pollFirstEntry().getValue());
            nextSeq++;
        }
    }

    private void append(LiveChunk c) {
        int[] p = c.pts;
        int n = p.length / 2;
        int from = 0;
        if (pairs > 0 && n > 0 && p[0] == pts[(pairs - 1) * 2] && p[1] == pts[(pairs - 1) * 2 + 1]) from = 1;
        int added = n - from;
        if (added <= 0) return;
        ensure(pairs + added);
        int lastMs = pairs > 0 ? ms[pairs - 1] : 0;
        int start = c.startMs >= 0 ? Math.max(c.startMs, lastMs) : lastMs;
        int end = c.endMs >= 0 ? Math.max(c.endMs, start) : start + 250;
        for (int i = 0; i < added; i++) {
            pts[pairs * 2] = p[(from + i) * 2];
            pts[pairs * 2 + 1] = p[(from + i) * 2 + 1];
            ms[pairs] = start + (end - start) * (i + 1) / added;
            pairs++;
        }
        if (pairs == added) {
            tailX = pts[0];
            tailY = pts[1];
        }
    }

    private void ensure(int wantPairs) {
        if (wantPairs * 2 > pts.length) {
            int size = Math.max(wantPairs, pts.length);
            pts = Arrays.copyOf(pts, size * 2);
            ms = Arrays.copyOf(ms, size);
        }
    }

    /** Пришёл готовый штрих: теперь известны все точки и общее время. */
    void setFinal(int[] finalPts, int durationMs, long now) {
        lastDataAt = now;
        waiting.clear();
        int n = finalPts.length / 2;
        int total = durationMs > 0 ? durationMs : n * 8;
        boolean prefix = pairs <= n;
        for (int i = 0; prefix && i < pairs * 2; i++) prefix = pts[i] == finalPts[i];
        ensure(n);
        int known = prefix ? pairs : 0;
        int lastMs = known > 0 ? ms[known - 1] : 0;
        int end = Math.max(total, lastMs + 1);
        for (int i = known; i < n; i++) {
            ms[i] = lastMs + (int) ((long) (end - lastMs) * (i - known + 1) / Math.max(1, n - known));
        }
        System.arraycopy(finalPts, 0, pts, 0, finalPts.length);
        if (pairs == 0 || !prefix) {
            tailX = pts[0];
            tailY = pts[1];
        }
        pairs = n;
        visible = Math.min(visible, Math.max(0, n - 1));
        hasFinal = true;
        if (!hadLive) playMs = 0;
    }

    /** Сдвинуть проигрывание на dt миллисекунд. */
    void advance(float dt, long now) {
        if (!waiting.isEmpty()) drainWaiting(now);
        if (pairs == 0 || finished) return;
        int available = ms[pairs - 1];
        float behind = available - playMs;
        float rate = 1f;
        if (behind > 1500) rate = 3f;
        else if (behind > 700) rate = 1.6f;
        // Штрих пришёл целиком, без живых кусков: показываем быстро, но всё равно рисованием.
        if (hasFinal && !hadLive) rate = Math.max(rate, behind / 1200f);
        playMs += dt * rate;
        if (!hasFinal && playMs > available) playMs = available;

        while (visible + 1 < pairs && ms[visible + 1] <= playMs) visible++;
        if (visible + 1 < pairs) {
            int a = ms[visible];
            int b = ms[visible + 1];
            float f = b > a ? Math.max(0f, Math.min(1f, (playMs - a) / (b - a))) : 1f;
            tailX = pts[visible * 2] + (pts[visible * 2 + 2] - pts[visible * 2]) * f;
            tailY = pts[visible * 2 + 1] + (pts[visible * 2 + 3] - pts[visible * 2 + 1]) * f;
        } else {
            tailX = pts[visible * 2];
            tailY = pts[visible * 2 + 1];
            if (hasFinal && playMs >= available) finished = true;
        }
    }
}
