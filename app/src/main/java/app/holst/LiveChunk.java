package app.holst;

/** Кусок штриха, который друг рисует прямо сейчас. */
final class LiveChunk {
    final String id;
    final int color;
    final int width;
    final boolean erase;
    final int seq;
    final int[] pts;
    /** Время от начала штриха, мс; -1, если отправитель его не указал. */
    final int startMs;
    final int endMs;

    LiveChunk(String id, int color, int width, boolean erase, int seq, int[] pts, int startMs, int endMs) {
        this.id = id;
        this.color = color;
        this.width = width;
        this.erase = erase;
        this.seq = seq;
        this.pts = pts;
        this.startMs = startMs;
        this.endMs = endMs;
    }
}
