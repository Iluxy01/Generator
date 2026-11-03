package app.holst;

import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Один штрих. Координаты не зависят от размера экрана: см. {@link Renderer}. */
final class Stroke {
    private static final SecureRandom RANDOM = new SecureRandom();

    final String id;
    final long t;
    final long sec;
    final int color;
    final int width;
    final boolean erase;
    final int[] pts;
    final boolean mine;

    Stroke(String id, long t, long sec, int color, int width, boolean erase, int[] pts, boolean mine) {
        this.id = id;
        this.t = t;
        this.sec = sec;
        this.color = color;
        this.width = width;
        this.erase = erase;
        this.pts = pts;
        this.mine = mine;
    }

    static String newId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return Crypto.hex(b);
    }

    String payload() {
        StringBuilder sb = new StringBuilder(32 + pts.length * 3);
        sb.append("{\"k\":\"s\",\"i\":\"").append(id)
                .append("\",\"t\":").append(t)
                .append(",\"c\":").append(color)
                .append(",\"w\":").append(width)
                .append(",\"e\":").append(erase ? 1 : 0)
                .append(",\"p\":");
        appendDelta(sb, pts, pts.length);
        return sb.append('}').toString();
    }

    static Stroke parse(JSONObject o, long sec, boolean mine) throws JSONException {
        return new Stroke(o.getString("i"), o.getLong("t"), sec, o.getInt("c"), o.getInt("w"),
                o.optInt("e") != 0, readDelta(o.getJSONArray("p")), mine);
    }

    /** Первая точка целиком, дальше разности: так сообщения в разы короче. */
    static void appendDelta(StringBuilder sb, int[] pts, int count) {
        sb.append('[');
        int px = 0;
        int py = 0;
        for (int i = 0; i + 1 < count; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(pts[i] - px).append(',').append(pts[i + 1] - py);
            px = pts[i];
            py = pts[i + 1];
        }
        sb.append(']');
    }

    static int[] readDelta(JSONArray a) throws JSONException {
        int n = a.length() & ~1;
        int[] out = new int[n];
        int px = 0;
        int py = 0;
        for (int i = 0; i < n; i += 2) {
            px += a.getInt(i);
            py += a.getInt(i + 1);
            out[i] = px;
            out[i + 1] = py;
        }
        return out;
    }
}
