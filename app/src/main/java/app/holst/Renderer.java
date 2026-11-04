package app.holst;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import java.util.List;

/**
 * Рисует штрихи на любом размере экрана. Масштаб считается только по ширине, а начало отсчёта по
 * высоте стоит в центре: на телефонах с другими пропорциями круг остаётся кругом.
 */
final class Renderer {
    static final float Q = 10000f;
    private static final PorterDuffXfermode CLEAR = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    private Renderer() {}

    static int qx(float x, float w) {
        return Math.round(x / w * Q);
    }

    static int qy(float y, float w, float h) {
        return Math.round((y - h / 2f) / w * Q);
    }

    static Paint newPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    static void setup(Paint p, int color, int width, boolean erase, float w) {
        p.setColor(color | 0xFF000000);
        p.setStrokeWidth(Math.max(1f, width / Q * w));
        p.setXfermode(erase ? CLEAR : null);
    }

    /**
     * Показывает начало штриха: все точки до lastIndex и плавный хвост к точке (tailX, tailY).
     * Так штрих друга растёт непрерывно, а не прыжками от точки к точке.
     */
    static void drawPartial(Canvas c, Path path, Playback b, float w, float h, Paint p) {
        setup(p, b.color, b.width, b.erase, w);
        float k = w / Q;
        float cy = h / 2f;
        int[] pts = b.points();
        int last = b.visibleIndex();
        if (last == 0 && b.tailX() == pts[0] && b.tailY() == pts[1]) {
            c.drawPoint(pts[0] * k, pts[1] * k + cy, p);
            return;
        }
        path.rewind();
        float px = pts[0] * k;
        float py = pts[1] * k + cy;
        path.moveTo(px, py);
        for (int i = 1; i <= last; i++) {
            float x = pts[i * 2] * k;
            float y = pts[i * 2 + 1] * k + cy;
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(b.tailX() * k, b.tailY() * k + cy);
        c.drawPath(path, p);
    }

    static void draw(Canvas c, int[] pts, int count, int color, int width, boolean erase,
                     float w, float h, Paint p) {
        if (count < 2) return;
        setup(p, color, width, erase, w);
        float k = w / Q;
        float cy = h / 2f;
        if (count < 4) {
            c.drawPoint(pts[0] * k, pts[1] * k + cy, p);
            return;
        }
        Path path = new Path();
        float px = pts[0] * k;
        float py = pts[1] * k + cy;
        path.moveTo(px, py);
        for (int i = 2; i + 1 < count; i += 2) {
            float x = pts[i] * k;
            float y = pts[i + 1] * k + cy;
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(px, py);
        c.drawPath(path, p);
    }

    /** Готовый контур штриха, чтобы не собирать его заново в каждом кадре; null для одной точки. */
    static Path buildPath(int[] pts, float w, float h) {
        if (pts.length < 4) return null;
        float k = w / Q;
        float cy = h / 2f;
        Path path = new Path();
        float px = pts[0] * k;
        float py = pts[1] * k + cy;
        path.moveTo(px, py);
        for (int i = 2; i + 1 < pts.length; i += 2) {
            float x = pts[i] * k;
            float y = pts[i + 1] * k + cy;
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(px, py);
        return path;
    }

    static void draw(Canvas c, Stroke s, float w, float h, Paint p) {
        draw(c, s.pts, s.pts.length, s.color, s.width, s.erase, w, h, p);
    }

    static void drawAll(Canvas c, List<Stroke> strokes, float w, float h) {
        Paint p = newPaint();
        for (Stroke s : strokes) draw(c, s, w, h, p);
    }
}
